package network.mmis.runtime

import bindings.DMReceiver
import org.json.JSONObject

class Receiver(private val store: MessageStore, private val ownKey: String, private val metrics: CallbackMetrics,
    private val event: (String, JSONObject)->Unit) : DMReceiver {
    private fun <T> measured(type: String, work: ()->T): T = metrics.measure(type) {
        try { work() }
        catch(e: Exception) { event("STORAGE_FAILED",JSONObject().put("callback",type).put("error",e.javaClass.simpleName)); throw e }
    }
    private fun receiveRow(type: Long, mid: ByteArray?, nick: String?, text: String?, partner: ByteArray?, sender: ByteArray?, token: Int, codeset: Long, timestamp: Long, round: Long, status: Long, parent: ByteArray?=null): Long = measured("receive:$type") {
        val senderKey=b64(requireNotNull(sender)); val peer=b64(requireNotNull(partner))
        val id=store.receive(b64(requireNotNull(mid)),peer,senderKey,nick.orEmpty(),text.orEmpty(),logicalToken(token),codeset,timestamp,round,type,status,
            if(senderKey==ownKey) "outbound" else "inbound",parent?.let(::b64))
        event("message",JSONObject().put("uuid",id).put("sender",senderKey).put("partner",peer).put("token",logicalToken(token)).put("jniToken",token).put("status",status).put("statusName",statusName(status)))
        id
    }
    override fun receive(messageID: ByteArray?, nickname: String?, text: ByteArray?, partnerKey: ByteArray?, senderKey: ByteArray?, dmToken: Int, codeset: Long, timestamp: Long, roundId: Long, mType: Long, status: Long)=receiveRow(mType,messageID,nickname,text?.toString(Charsets.UTF_8),partnerKey,senderKey,dmToken,codeset,timestamp,roundId,status)
    override fun receiveText(messageID: ByteArray?, nickname: String?, text: String?, partnerKey: ByteArray?, senderKey: ByteArray?, dmToken: Int, codeset: Long, timestamp: Long, roundId: Long, status: Long)=receiveRow(1,messageID,nickname,text,partnerKey,senderKey,dmToken,codeset,timestamp,roundId,status)
    override fun receiveReply(messageID: ByteArray?, reactionTo: ByteArray?, nickname: String?, text: String?, partnerKey: ByteArray?, senderKey: ByteArray?, dmToken: Int, codeset: Long, timestamp: Long, roundId: Long, status: Long)=receiveRow(2,messageID,nickname,text,partnerKey,senderKey,dmToken,codeset,timestamp,roundId,status,reactionTo)
    override fun receiveReaction(messageID: ByteArray?, reactionTo: ByteArray?, nickname: String?, reaction: String?, partnerKey: ByteArray?, senderKey: ByteArray?, dmToken: Int, codeset: Long, timestamp: Long, roundId: Long, status: Long)=receiveRow(3,messageID,nickname,reaction,partnerKey,senderKey,dmToken,codeset,timestamp,roundId,status,reactionTo)
    override fun updateSentStatus(uuid: Long, messageID: ByteArray?, timestamp: Long, roundID: Long, status: Long)=measured("updateSentStatus") {
        store.update(uuid,b64(requireNotNull(messageID)),timestamp,roundID,status)
        event("status",JSONObject().put("uuid",uuid).put("messageID",b64(messageID)).put("status",status).put("statusName",statusName(status)))
    }
    override fun deleteMessage(messageID: ByteArray?, senderPubKey: ByteArray?)=measured("deleteMessage") { store.delete(b64(requireNotNull(messageID)),b64(requireNotNull(senderPubKey))) }
    override fun getConversation(senderPubKey: ByteArray?): ByteArray=measured("getConversation") { (store.conversation(b64(requireNotNull(senderPubKey)))?.toString() ?: "null").toByteArray() }
    override fun getConversations(): ByteArray=measured("getConversations") { store.conversations().toString().toByteArray() }
}
