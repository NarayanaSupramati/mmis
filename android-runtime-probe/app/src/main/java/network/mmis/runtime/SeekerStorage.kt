package network.mmis.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Separate public discovery cache, never a source of contact authorization. */
class DiskSeekerCache(context:Context):SeekerCache {
    private val root=File(context.filesDir,"seeker-mainnet-cache").apply { mkdirs() }
    private fun file(name:String)=AtomicFile(File(root,MessageDigest.getInstance("SHA-256").digest((SeekerProtocol.GENESIS+"/"+name).toByteArray()).joinToString("") { "%02x".format(it) }+".json"))
    @Synchronized override fun get(name:String):SeekerObservation?=try { SeekerObservation.from(JSONObject(file(name).readFully().toString(Charsets.UTF_8))) } catch(_:Exception) { null }
    @Synchronized override fun put(observation:SeekerObservation) {
        val f=file(observation.name);val out=f.startWrite()
        try { out.write(observation.json().toString().toByteArray());f.finishWrite(out) } catch(e:Exception) { f.failWrite(out);throw e }
        root.listFiles { file -> file.extension=="json" }?.sortedByDescending { it.lastModified() }?.drop(128)?.forEach { it.delete() }
    }
}
class SeekerRpcClient(context:Context) {
    private val url=runCatching { SeekerProtocol.rpcUrl(File(context.filesDir,"seeker-mainnet.json").takeIf {it.exists()}?.readText()) }.getOrNull()
    val endpoint=when(url) { null->"Invalid mainnet RPC configuration";"https://api.mainnet-beta.solana.com"->url;else->"configured mainnet RPC" }
    suspend fun call(method:String,params:JSONArray):Any=withContext(Dispatchers.IO) {
        require(method in setOf("getGenesisHash","getMultipleAccounts","getProgramAccounts")) // Read-only by construction.
        val c=URL(url ?: error("Invalid mainnet RPC configuration")).openConnection() as HttpURLConnection
        try {
            c.requestMethod="POST";c.connectTimeout=12000;c.readTimeout=12000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use { it.write(JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method).put("params",params).toString().toByteArray()) }
            check(c.responseCode==200)
            val j=JSONObject(c.inputStream.bufferedReader().use { it.readText() });check(!j.has("error"));j.get("result")
        } finally { c.disconnect() }
    }
}
