package network.mmis.runtime

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Callers hold this monitor through each transaction; never calls back into xxDK. */
class MessageStore(context: Context, name: String = "probe-messages.sqlite",private val incoming:IncomingMessageEvents?=null) : SQLiteOpenHelper(context,name,null,2) {
    companion object { const val MAX_DELETED_IDS=10000;const val DELETED_RETENTION_MS=30L*24*60*60*1000 }
    private fun createDeleted(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE deleted_messages(message_id TEXT PRIMARY KEY, local_id INTEGER NOT NULL, deleted_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX deleted_local_id ON deleted_messages(local_id)")
    }
    // Dirty signal for immediate repository refresh; never interpreted as a banner event.
    val incomingChanges=MutableStateFlow(0L)
    override fun onCreate(db: SQLiteDatabase) {
        createDeleted(db)
        db.execSQL("CREATE TABLE conversations (pub_key TEXT PRIMARY KEY, nickname TEXT NOT NULL, token INTEGER NOT NULL, codeset INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, message_id TEXT UNIQUE NOT NULL, peer TEXT NOT NULL, sender TEXT NOT NULL, nickname TEXT NOT NULL, text TEXT NOT NULL, timestamp_ns TEXT NOT NULL, round_id TEXT NOT NULL, type INTEGER NOT NULL, status INTEGER NOT NULL, direction TEXT NOT NULL, parent_id TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { if(old==1 && new==2) createDeleted(db) else error("Explicit migration required") }
    private fun pruneDeleted(db:SQLiteDatabase) {
        db.delete("deleted_messages","deleted_at<?",arrayOf((System.currentTimeMillis()-DELETED_RETENTION_MS).toString()))
        db.execSQL("DELETE FROM deleted_messages WHERE message_id IN (SELECT message_id FROM deleted_messages ORDER BY deleted_at DESC,local_id DESC LIMIT -1 OFFSET $MAX_DELETED_IDS)")
    }
    private fun rememberDeleted(db:SQLiteDatabase,mid:String,id:Long,at:Long) {
        // Native queued sends may share the all-zero placeholder; it is not a network ID.
        val key=if(mid=="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") "local:$id" else mid
        db.insertWithOnConflict("deleted_messages",null,ContentValues().apply {
            put("message_id",key);put("local_id",id);put("deleted_at",at)
        },SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun deleteConversation(peer:String,clearPresentation:()->Unit={}) {
        val db=writableDatabase;db.beginTransaction()
        try {
            val at=System.currentTimeMillis()
            db.rawQuery("SELECT message_id,id FROM messages WHERE peer=?",arrayOf(peer)).use {c->while(c.moveToNext()) rememberDeleted(db,c.getString(0),c.getLong(1),at)}
            db.delete("messages","peer=?",arrayOf(peer));db.delete("conversations","pub_key=?",arrayOf(peer))
            pruneDeleted(db);db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        // Same monitor as inbound commit+emit: an old banner cannot race back after removal.
        clearPresentation();incomingChanges.update {it+1}
    }
    @Synchronized fun receive(mid: String, peer: String, sender: String, nickname: String, text: String,
        token: Long, codeset: Long, timestamp: Long, round: Long, type: Long, status: Long, direction: String, parent: String?): Long {
        val db=writableDatabase; db.beginTransaction()
        var inserted:IncomingMessageEvent?=null
        val result:Long
        try {
            val deleted=db.rawQuery("SELECT local_id FROM deleted_messages WHERE message_id=? AND deleted_at>=?",arrayOf(mid,(System.currentTimeMillis()-DELETED_RETENTION_MS).toString())).use {if(it.moveToFirst()) it.getLong(0) else null}
            if(deleted!=null) {db.setTransactionSuccessful();return deleted}
            // Only the peer's metadata belongs to the peer conversation; outgoing nickname is ours.
            if(direction=="inbound") db.insertWithOnConflict("conversations",null,ContentValues().apply {
                put("pub_key",peer); put("nickname",nickname); put("token",token); put("codeset",codeset)
            },SQLiteDatabase.CONFLICT_REPLACE)
            // The all-zero ID means a new local send attempt, not a dedupe identity.
            // Give each attempt a distinct row until xxDK supplies its real network ID.
            val storedMid=if(direction=="outbound" && mid=="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") "queued:${java.util.UUID.randomUUID()}" else mid
            val values=ContentValues().apply {
                put("message_id",storedMid); put("peer",peer); put("sender",sender); put("nickname",nickname); put("text",text)
                put("timestamp_ns",timestamp.toString()); put("round_id",round.toString()); put("type",type)
                put("status",status); put("direction",direction); put("parent_id",parent)
            }
            val existing=db.rawQuery("SELECT id FROM messages WHERE message_id=?",arrayOf(storedMid)).use { if(it.moveToFirst()) it.getLong(0) else null }
            val id=existing ?: db.insertOrThrow("messages",null,values)
            // A duplicate does not roll a settled record back to an earlier status.
            db.setTransactionSuccessful();result=id
            if(existing==null && direction=="inbound") inserted=IncomingMessageEvent(peer,id,mid,sender,text,timestamp.toString())
        } finally { db.endTransaction() }
        // Transaction has committed; presentation never changes the stored row or callback UUID.
        inserted?.let { incoming?.emit(it);incomingChanges.update { revision->revision+1 } }
        return result
    }
    @Synchronized fun ensurePeer(peer: DmDescriptor) {
        writableDatabase.insertWithOnConflict("conversations",null,ContentValues().apply {
            put("pub_key",peer.publicKeyBase64); put("nickname",""); put("token",peer.token); put("codeset",0)
        },SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun update(id: Long, mid: String, timestamp: Long, round: Long, status: Long) {
        val db=writableDatabase; db.beginTransaction()
        try {
            // Self-reception can race a send-status callback. Keep the UUID returned on send.
            val previous=db.rawQuery("SELECT status FROM messages WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst()) it.getInt(0) else null }
            if(previous==null) {
                val deletedAt=db.rawQuery("SELECT deleted_at FROM deleted_messages WHERE local_id=? LIMIT 1",arrayOf(id.toString())).use {if(it.moveToFirst()) it.getLong(0) else null}
                if(deletedAt!=null) {
                    rememberDeleted(db,mid,id,deletedAt)
                    // A self-reception with the final ID can precede this late status callback.
                    if(mid!="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") db.delete("messages","message_id=?",arrayOf(mid))
                    pruneDeleted(db);db.setTransactionSuccessful();incomingChanges.update {it+1};return
                }
            }
            check(previous!=null) { "Unknown sent UUID $id" }
            val storedMid=if(mid=="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") "queued:$id" else mid
            val duplicate=db.rawQuery("SELECT status FROM messages WHERE message_id=? AND id<>?",arrayOf(storedMid,id.toString())).use { if(it.moveToFirst()) it.getInt(0) else 0 }
            val settled=settledMessageStatus(previous,duplicate,status.toInt())
            db.delete("messages","message_id=? AND id<>?",arrayOf(storedMid,id.toString()))
            db.update("messages",ContentValues().apply { put("message_id",storedMid); put("timestamp_ns",timestamp.toString()); put("round_id",round.toString()); put("status",settled) },"id=?",arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun delete(mid: String, sender: String): Boolean = writableDatabase.delete("messages","message_id=? AND sender=?",arrayOf(mid,sender))>0
    @Synchronized fun conversations(): JSONArray = JSONArray().apply {
        readableDatabase.rawQuery("SELECT * FROM conversations ORDER BY pub_key",null).use { c -> while(c.moveToNext()) put(JSONObject()
            .put("pub_key",c.getString(0)).put("nickname",c.getString(1)).put("token",c.getLong(2)).put("codeset_version",c.getLong(3)).put("blocked_timestamp",JSONObject.NULL)) }
    }
    @Synchronized fun conversation(key: String): JSONObject? {
        val all=conversations(); for(i in 0 until all.length()) if(all.getJSONObject(i).getString("pub_key")==key) return all.getJSONObject(i)
        return null
    }
    @Synchronized fun messages(): JSONArray = JSONArray().apply {
        readableDatabase.rawQuery("SELECT * FROM messages ORDER BY id",null).use { c -> while(c.moveToNext()) {
            val row=JSONObject(); for(i in 0 until c.columnCount) row.put(c.getColumnName(i),if(c.isNull(i)) JSONObject.NULL else if(c.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER) c.getLong(i) else c.getString(i))
            row.put("statusName",statusName(row.getLong("status"))); put(row)
        } }
    }
}
