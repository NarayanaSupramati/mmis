package network.mmis.runtime

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow

/** App metadata has its own DB; the proven message DB/schema is not migrated or replaced. */
class ContactStore(context:Context,name:String="mmis-contacts.sqlite"):SQLiteOpenHelper(context,name,null,1) {
    val changes=MutableStateFlow(0L)
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE contacts(id TEXT PRIMARY KEY, peer TEXT NOT NULL, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE registry_cache(id TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE contact_notices(id TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE local_events(id INTEGER PRIMARY KEY AUTOINCREMENT, contact_id TEXT NOT NULL, fingerprint TEXT NOT NULL, text TEXT NOT NULL, at_ms INTEGER NOT NULL, UNIQUE(contact_id,fingerprint))")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) { error("Explicit non-destructive migration required") }
    @Synchronized fun contacts():List<Contact> = readableDatabase.rawQuery("SELECT value FROM contacts ORDER BY id",null).use { c -> buildList { while(c.moveToNext()) add(Contact.from(JSONObject(c.getString(0)))) } }
    @Synchronized fun save(contact:Contact) {
        writableDatabase.insertWithOnConflict("contacts",null,ContentValues().apply { put("id",contact.id);put("peer",contact.peer.key);put("value",contact.json().toString()) },SQLiteDatabase.CONFLICT_REPLACE)
        changes.value++
    }
    @Synchronized fun notice(id:String):JSONObject? = readableDatabase.rawQuery("SELECT value FROM contact_notices WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
    @Synchronized fun clearConversationNotices(peer:String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.delete("local_events","contact_id IN (SELECT id FROM contacts WHERE peer=?)",arrayOf(peer))
            db.delete("contact_notices","id IN (SELECT id FROM contacts WHERE peer=?)",arrayOf(peer))
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        changes.value++
    }
    @Synchronized fun observed(contact:Contact,o:RegistryObservation,namespace:String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val json=o.json().toString()
            if(o.result is RegistryResult.Registered || o.result==RegistryResult.NotRegistered) db.insertWithOnConflict("registry_cache",null,ContentValues().apply { put("id","$namespace/${o.wallet}");put("value",json) },SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("contact_notices",null,ContentValues().apply {put("id",contact.id);put("value",json)},SQLiteDatabase.CONFLICT_REPLACE)
            if(ProductPresentation.endpointChanged(contact,o.result)) db.insertWithOnConflict("local_events",null,ContentValues().apply {
                put("contact_id",contact.id);put("fingerprint",(o.result as RegistryResult.Registered).endpoint.json().toString());put("text","Wallet messaging identity changed.");put("at_ms",o.resolvedAt)
            },SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changes.value++
    }
    @Synchronized fun cached(namespace:String,wallet:String):JSONObject? = readableDatabase.rawQuery("SELECT value FROM registry_cache WHERE id=?",arrayOf("$namespace/$wallet")).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
    @Synchronized fun localEventCount(id:String):Int=readableDatabase.rawQuery("SELECT COUNT(*) FROM local_events WHERE contact_id=?",arrayOf(id)).use { it.moveToFirst();it.getInt(0) }
    @Synchronized fun localEvents():Map<String,List<String>> = readableDatabase.rawQuery("SELECT contact_id,text FROM local_events ORDER BY at_ms,id",null).use { c ->
        val rows=mutableMapOf<String,MutableList<String>>()
        while(c.moveToNext()) rows.getOrPut(c.getString(0)) { mutableListOf() }.add(c.getString(1))
        rows
    }
}
