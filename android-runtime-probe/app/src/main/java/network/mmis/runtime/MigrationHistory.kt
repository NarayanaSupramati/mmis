package network.mmis.runtime

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Logical rows only. No wallet credentials, RPC configuration, native state or SQLite file copying. */
object MigrationHistory {
    private val schemas=linkedMapOf(
        "conversations" to "pub_key nickname token# codeset#",
        "messages" to "id# message_id peer sender nickname text timestamp_ns round_id type# status# direction parent_id?",
        "contacts" to "id peer value",
        "registry_cache" to "id value",
        "contact_notices" to "id value",
        "local_events" to "id# contact_id fingerprint text at_ms#")
    private fun messageTable(t:String)=t in setOf("messages","conversations")
    fun export(messages:MessageStore,contacts:ContactStore):JSONObject = synchronized(messages) {synchronized(contacts) {
        JSONObject().put("format","mmis-local-history").put("version",1).put("tables",JSONObject().apply {
            for((table,_) in schemas) {
                val db=if(messageTable(table)) messages.readableDatabase else contacts.readableDatabase
                put(table,JSONArray().apply {db.rawQuery("SELECT * FROM $table ORDER BY 1",null).use {c->
                    while(c.moveToNext()) put(JSONObject().apply {for(i in 0 until c.columnCount) put(c.getColumnName(i),when(c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL->JSONObject.NULL;Cursor.FIELD_TYPE_INTEGER->c.getLong(i);else->c.getString(i)
                    })})
                }})
            }
        })
    }}
    fun validate(j:JSONObject) {
        require(j.getString("format")=="mmis-local-history" && j.getInt("version")==1)
        val tables=j.getJSONObject("tables");require(tables.keys().asSequence().toSet()==schemas.keys)
        for((table,schema) in schemas) {
            val specs=schema.split(" ");val rows=tables.getJSONArray(table);require(rows.length()<=100000)
            val ids=HashSet<String>()
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i);require(row.keys().asSequence().toSet()==specs.map {it.trimEnd('#','?')}.toSet())
                for(spec in specs) {
                    val value=row.get(spec.trimEnd('#','?'))
                    if(spec.endsWith('#')) require(value is Number && value.toString().matches(Regex("-?[0-9]+")))
                    else require(value is String || (spec.endsWith('?') && value==JSONObject.NULL))
                }
                require(ids.add(row.get(specs.first().trimEnd('#','?')).toString()))
                when(table) {
                    "conversations"->PeerIdentity(row.getString("pub_key"),row.getLong("token"))
                    "messages"->{require(row.getLong("id")>0);row.getString("timestamp_ns").toLong();row.getString("round_id").toLong();require(row.getString("direction") in setOf("inbound","outbound"))}
                    "contacts"->{val c=Contact.from(JSONObject(row.getString("value")));require(c.id==row.getString("id") && c.peer.key==row.getString("peer"))}
                    "registry_cache","contact_notices"->JSONObject(row.getString("value"))
                }
            }
        }
    }
    fun isEmpty(messages:MessageStore,contacts:ContactStore)=schemas.keys.all {table->
        val db=if(messageTable(table)) messages.readableDatabase else contacts.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $table",null).use {it.moveToFirst();it.getLong(0)==0L}
    }
    fun restore(j:JSONObject,messages:MessageStore,contacts:ContactStore) = synchronized(messages) {synchronized(contacts) {
        validate(j);check(isEmpty(messages,contacts)) {"Restore requires empty local history."}
        val db=messages.writableDatabase
        // One SQL transaction across both existing version-1 schemas. A runtime marker also
        // blocks startup after process interruption (SQLite WAL is not cross-file crash atomic).
        db.execSQL("ATTACH DATABASE ? AS migration_contacts",arrayOf(contacts.writableDatabase.path))
        try {
            db.beginTransaction()
            try {
                for((table,schema) in schemas) for(row in j.getJSONObject("tables").getJSONArray(table).objects()) {
                    val values=ContentValues()
                    for(spec in schema.split(" ")) {
                        val key=spec.trimEnd('#','?')
                        when {row.isNull(key)->values.putNull(key);spec.endsWith('#')->values.put(key,row.getLong(key));else->values.put(key,row.getString(key))}
                    }
                    db.insertOrThrow(if(messageTable(table)) table else "migration_contacts.$table",null,values)
                }
                db.setTransactionSuccessful()
            } finally {db.endTransaction()}
        } finally {db.execSQL("DETACH DATABASE migration_contacts")}
        contacts.changes.value++
    }}
}
