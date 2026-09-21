package network.mmis.runtime

import java.util.Base64
import java.util.zip.CRC32
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** Public endpoint only. Conversation identity is the signing key, never the wallet. */
data class PeerIdentity(val key:String,val token:Long) {
    init { val bytes=Base64.getDecoder().decode(key); require(bytes.size==32 && Base64.getEncoder().encodeToString(bytes)==key); require(token in 0..0xffffffffL) }
    val conversationId get()=Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(key))
    val usable get()=token!=0L && Base64.getDecoder().decode(key).any { it!=0.toByte() }
    fun json()=JSONObject().put("version",1).put("signingPublicKey",key).put("dmToken",token)
    companion object {
        fun from(j:JSONObject)=PeerIdentity(j.getString("signingPublicKey"),j.getLong("dmToken"))
    }
}
sealed interface AddressInput {
    data class Direct(val peer:PeerIdentity):AddressInput
    data class Solana(val wallet:String):AddressInput
    data class Seeker(val name:String):AddressInput
}
object PrivateAddress {
    fun encode(peer:PeerIdentity):String {
        val b=ByteArray(41);b[0]=1;Base64.getDecoder().decode(peer.key).copyInto(b,1)
        val buffer=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);buffer.putInt(33,peer.token.toInt())
        buffer.putInt(37,CRC32().apply { update(b,0,37) }.value.toInt())
        return "sp1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
    fun decode(value:String):PeerIdentity {
        require(value.matches(Regex("sp1_[A-Za-z0-9_-]{55}")))
        val b=Base64.getUrlDecoder().decode(value.removePrefix("sp1_"));require(b.size==41 && b[0]==1.toByte())
        val buffer=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        require(buffer.getInt(37).toLong() and 0xffffffffL==CRC32().apply { update(b,0,37) }.value)
        val peer=PeerIdentity(Base64.getEncoder().encodeToString(b.copyOfRange(1,33)),buffer.getInt(33).toLong() and 0xffffffffL)
        require(encode(peer)==value);return peer
    }
    fun classify(raw:String):AddressInput {
        val value=raw.trim()
        if(value.startsWith("sp1_")) return AddressInput.Direct(decode(value))
        if(value.contains(".")) return AddressInput.Seeker(SeekerProtocol.name(value))
        require(value.matches(Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")))
        val account=SolanaAccount.from(value);require(account.address==value)
        return AddressInput.Solana(value)
    }
}
enum class AddressSource { Direct, Solana }
data class Provenance(val wallet:String,val namespace:String,val revision:String,val contextSlot:String,val resolvedAt:Long,val seekerId:String?=null,val seekerSlot:String?=null,val seekerResolvedAt:Long?=null) {
    fun json()=JSONObject().put("wallet",wallet).put("namespace",namespace).put("revision",revision).put("contextSlot",contextSlot).put("resolvedAt",resolvedAt).put("seekerId",seekerId ?: JSONObject.NULL).put("seekerSlot",seekerSlot ?: JSONObject.NULL).put("seekerResolvedAt",seekerResolvedAt ?: JSONObject.NULL)
    companion object { fun from(j:JSONObject)=Provenance(j.getString("wallet"),j.getString("namespace"),j.getString("revision"),j.getString("contextSlot"),j.getLong("resolvedAt"),j.optString("seekerId").takeIf { !j.isNull("seekerId") && it.isNotBlank() },j.optString("seekerSlot").takeIf { !j.isNull("seekerSlot") },if(j.isNull("seekerResolvedAt")) null else j.getLong("seekerResolvedAt")) }
}
data class Contact(val peer:PeerIdentity,val source:AddressSource,val provenance:Provenance?=null,val alias:String="") {
    init { require((source==AddressSource.Solana)==(provenance!=null)) }
    val id get()=if(provenance==null) "direct/${peer.conversationId}/${peer.token}" else "${provenance.namespace}/${provenance.wallet}/${peer.conversationId}/${peer.token}"+(provenance.seekerId?.let { "/seeker/$it" } ?: "")
    val title get()=alias.ifBlank { provenance?.seekerId ?: provenance?.wallet?.let(::shortAddress) ?: "Private contact" }
    fun json()=JSONObject().put("peer",peer.json()).put("source",source.name).put("alias",alias).put("provenance",provenance?.json() ?: JSONObject.NULL)
    companion object { fun from(j:JSONObject)=Contact(PeerIdentity.from(j.getJSONObject("peer")),AddressSource.valueOf(j.getString("source")),j.optJSONObject("provenance")?.let(Provenance::from),j.optString("alias")) }
}
fun shortAddress(s:String)=if(s.length>16) s.take(6)+"…"+s.takeLast(4) else s
fun checkedLong(value:BigInteger):Long {
    require(value>=BigInteger.valueOf(Long.MIN_VALUE) && value<=BigInteger.valueOf(Long.MAX_VALUE)) {"Integer outside Long range"}
    return value.toLong()
}
fun messageTime(ns:String):String=try { val n=BigInteger(ns);require(n.signum()>0);DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(checkedLong(n.divide(BigInteger.valueOf(1000000))))) } catch(_:Exception) { "Unknown time" }
fun messageStatus(status:Int)=when(status) {0,1->"Sending";2->"Sent to network";3->"Failed";else->"Status unknown"}
fun settledMessageStatus(vararg states:Int):Int = when {2 in states->2;3 in states->3;1 in states->1;else->0}
enum class PrivateNetwork { Offline, Connecting, Ready, Error }
enum class BindingState { NotRegistered, Registered, DifferentIdentity, Changing, Disabling, PendingConfirmation, OutcomeUnknown, Conflict, RpcUnavailable, Error, Checking }
object ProductPresentation {
    fun binding(local:PeerIdentity?,result:RegistryResult?,pending:String?=null):BindingState = when(pending) {
        "OutcomeUnknown"->BindingState.OutcomeUnknown
        "ConflictStateChanged"->BindingState.Conflict
        "Built","WalletSigned","Submitted","Confirmed","Finalized"->BindingState.PendingConfirmation
        "Failed"->BindingState.Error
        else->when(result) {
            RegistryResult.NotRegistered->BindingState.NotRegistered
            RegistryResult.RpcFailure->BindingState.RpcUnavailable
            is RegistryResult.Registered->if(local!=null && local.key==result.endpoint.keyBase64 && local.token==result.endpoint.token) BindingState.Registered else BindingState.DifferentIdentity
            null->BindingState.Checking
            else->BindingState.Error
        }
    }
    fun lookup(result:RegistryResult)=when(result) {
        RegistryResult.NotRegistered->"This Solana address has not enabled private messaging."
        RegistryResult.RpcFailure->"Could not check messaging availability. Try again."
        is RegistryResult.Registered->"Private messaging available"
        else->"This messaging listing is not supported or is invalid."
    }
    fun endpointChanged(contact:Contact,result:RegistryResult)=result is RegistryResult.Registered && (contact.peer.key!=result.endpoint.keyBase64 || contact.peer.token!=result.endpoint.token)
}
