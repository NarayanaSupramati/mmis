package network.mmis.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlinx.coroutines.CancellationException

data class RegistryObservation(val wallet:String,val pda:String,val result:RegistryResult,val contextSlot:Long,val resolvedAt:Long,val tupleChanged:Boolean=false) {
    fun json()=JSONObject().put("wallet",wallet).put("pda",pda).put("result",result.kind).put("contextSlot",contextSlot.toString()).put("resolvedAt",resolvedAt)
        .put("tupleChanged",tupleChanged).apply {
            if(result is RegistryResult.Registered) put("endpoint",result.endpoint.json())
            if(result==RegistryResult.NotRegistered) put("message","This Solana address has not enabled private messaging.")
        }
}
/** Cache is an optimization for a running session. Every new conversation bypasses it. */
class RegistryResolver(val deployment:RegistryDeployment,private val call:suspend (String,JSONArray)->Any,private val now:()->Long=System::currentTimeMillis) {
    companion object { const val POSITIVE_TTL=300000L; const val NEGATIVE_TTL=30000L }
    private val cache=mutableMapOf<String,RegistryObservation>()
    @Synchronized fun cached(wallet:String)=cache["${deployment.namespace}/$wallet"]
    @Synchronized private fun remember(o:RegistryObservation) { cache["${deployment.namespace}/${o.wallet}"]=o }
    suspend fun resolve(wallet:String,fresh:Boolean=true,minSlot:Long=0):RegistryObservation {
        val pda=RegistryProtocol.pda(deployment,wallet)
        val old=cached(wallet)
        if(!fresh && old!=null && now()-old.resolvedAt < if(old.result==RegistryResult.NotRegistered) NEGATIVE_TTL else POSITIVE_TTL) return old
        try {
            check(call("getGenesisHash",JSONArray())==deployment.genesisHash)
            val options=JSONObject().put("encoding","base64").put("commitment","finalized")
            if(minSlot>0) options.put("minContextSlot",minSlot)
            val rpc=call("getAccountInfo",JSONArray().put(pda).put(options)) as JSONObject
            val value=rpc.optJSONObject("value")
            val bytes=value?.getJSONArray("data")?.let { require(it.getString(1)=="base64"); Base64.getDecoder().decode(it.getString(0)) }
            val decoded=RegistryProtocol.decode(pda,pda,deployment.programId,value?.getString("owner"),value?.getBoolean("executable") ?: false,bytes)
            val observation=RegistryObservation(wallet,pda,decoded,rpc.getJSONObject("context").getLong("slot"),now(),old!=null && old.result!=decoded)
            // An outage or corrupt reply must not destroy the last usable descriptor.
            if(decoded is RegistryResult.Registered || decoded==RegistryResult.NotRegistered) remember(observation)
            return observation
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { return RegistryObservation(wallet,pda,RegistryResult.RpcFailure,0,now()) }
    }
}
