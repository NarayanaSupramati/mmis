package network.mmis.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Profile-owned RPC; bad local config disables RPC, never the direct xx/history path. */
class SolanaRpcClient(context: android.content.Context) {
    val network=(context.applicationContext as ProbeApplication).registryNetwork
    private val configured=runCatching {
        val file=java.io.File(context.filesDir,network.profile.rpcConfigName)
        network.rpcUrl(if(file.exists()) JSONObject(file.readText()) else null)
    }
    val endpoint=if(configured.getOrNull()==network.profile.publicRpc) network.profile.publicRpc else "${network.profile.networkName} RPC (local config; URL redacted)"
    class RpcFailure(val code: Int,val instructionError:Any?=null): Exception("Solana RPC failure ($code)")
    suspend fun verifyGenesis()=network.verifyGenesis(::call)
    var requestCount=0L; private set
    suspend fun call(method: String, params: JSONArray=JSONArray()): Any = withContext(Dispatchers.IO) {
        if(method in setOf("sendTransaction","requestAirdrop")) {
            if(method=="requestAirdrop") check(network.profile==RegistryProfile.Lab) {"LAB_ONLY_OPERATION"}
            verifyGenesis()
        }
        requestCount++
        val url=configured.getOrElse {throw IllegalStateException("INVALID_RPC_CONFIG")}
        val c=URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod="POST"; c.connectTimeout=20000; c.readTimeout=20000; c.doOutput=true
            c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use { it.write(JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method).put("params",params).toString().toByteArray()) }
            if(c.responseCode!=200) throw RpcFailure(c.responseCode)
            val j=JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if(j.has("error")) { val e=j.getJSONObject("error"); throw RpcFailure(e.getInt("code"),e.optJSONObject("data")?.opt("err")) }
            j.get("result")
        } finally { c.disconnect() }
    }
    suspend fun blockhash(): JSONObject {verifyGenesis();return (call("getLatestBlockhash",JSONArray().put(JSONObject().put("commitment","confirmed"))) as JSONObject).getJSONObject("value")}
    suspend fun balance(address: String): Long=(call("getBalance",JSONArray().put(address)) as JSONObject).getLong("value")
    suspend fun airdrop(address: String): String=call("requestAirdrop",JSONArray().put(address).put(10000000)) as String
    suspend fun submit(bytes: ByteArray): String=call("sendTransaction",JSONArray().put(WalletProof.b64(bytes)).put(JSONObject().put("encoding","base64").put("preflightCommitment","confirmed"))) as String
    suspend fun status(signature: String): Any=call("getSignatureStatuses",JSONArray().put(JSONArray().put(signature)).put(JSONObject().put("searchTransactionHistory",true)))
}
