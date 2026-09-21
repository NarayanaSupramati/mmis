package network.mmis.runtime

import android.util.Base64
import org.json.JSONObject

fun tokenBits(logical: Long): Int {
    require(logical in 0L..0xffff_ffffL) { "Token must be uint32" }
    return logical.toInt()
}
fun logicalToken(bits: Int): Long = bits.toLong() and 0xffff_ffffL
fun statusName(status: Long): String = when(status) {
    0L -> "queued"; 1L -> "submitted"; 2L -> "networkAccepted"; 3L -> "failed"; else -> "unknown($status)"
}
fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
data class DmDescriptor(val publicKeyBase64: String, val token: Long) {
    init {
        tokenBits(token)
        val decoded = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        require(decoded.size == 32 && b64(decoded) == publicKeyBase64) { "Expected canonical 32-byte signing key" }
    }
    fun json() = JSONObject().put("version",1).put("signingPublicKey",publicKeyBase64).put("dmToken",token)
    companion object {
        fun parse(text: String): DmDescriptor {
            val obj=JSONObject(text)
            require(obj.optInt("version",1)==1)
            val raw=obj.get("dmToken")
            require(raw is Int || raw is Long) { "Token must be an integer" }
            return DmDescriptor(obj.getString("signingPublicKey"),(raw as Number).toLong())
        }
    }
}
