package network.mmis.runtime

import com.funkatronics.encoders.Base58
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/** Read-only discovery. This configuration never comes from MMIS's devnet deployment. */
object SeekerProtocol {
    const val GENESIS="5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
    const val PROGRAM="ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK"
    const val ROOT="3mX9b4AZaQehNoQGfckVcmgmA6bkBoFcbLj9RMmMyNcU"
    const val PARENT="F3A8kuikEiu6k2399oSJ1PWfcJYDHqpwoQ2e8psSDNuF"
    const val TLD_PROGRAM="TLDHkysf5pCnKsVA4gXpNvmy7psXLPEu4LAdDJthT9S"
    const val NAME_PROGRAM="NH3uX6FtVE2fNREAioP7hm5RaozotZxeL6khU1EHx51"
    val discriminator=byteArrayOf(68,72,88,44,15,167.toByte(),103,243.toByte())
    fun rpcUrl(config:String?):String?=runCatching {
        val url=if(config==null) "https://api.mainnet-beta.solana.com" else {
            val j=JSONObject(config);require(j.getString("chain")=="solana:mainnet");j.getString("rpcUrl")
        }
        val uri=java.net.URI(url);require(uri.scheme=="https" && uri.host!=null);url
    }.getOrNull()
    // Bounded ASCII product subset. SDK hashes exact UTF-8: do not case-fold or apply IDNA.
    // 63 is a product limit, not an asserted on-chain maximum. Full Unicode is deferred.
    fun name(raw:String):String {
        val n=raw.trim();require(n.endsWith(".skr"))
        val label=n.removeSuffix(".skr")
        require(label.length in 1..63 && label.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?")) && !label.contains("--"))
        return n
    }
    suspend fun derive(seeds:List<ByteArray>,program:String):String = Base58.encodeToString(ProgramDerivedAddress.find(seeds,SolanaPublicKey.from(program)).getOrThrow().bytes)
    fun key(s:String)=SolanaPublicKey.from(s).bytes
    suspend fun pda(name:String,parent:String=PARENT,nclass:String?=null):String = derive(listOf(
        MessageDigest.getInstance("SHA-256").digest(("ALT Name Service"+name).toByteArray(Charsets.UTF_8)),
        nclass?.let(::key) ?: ByteArray(32), key(parent)),PROGRAM)
    suspend fun domain(name:String)=pda(name(name).removeSuffix(".skr"))
    suspend fun tldHouse()=derive(listOf("tld_house".toByteArray(),".skr".toByteArray()),TLD_PROGRAM)
    suspend fun nftRecord(domain:String):String {
        val house=derive(listOf("name_house".toByteArray(),key(tldHouse())),NAME_PROGRAM)
        return derive(listOf("nft_record".toByteArray(),key(house),key(domain)),NAME_PROGRAM)
    }
    data class Header(val parent:String,val owner:String,val nclass:String,val expires:BigInteger,val nonTransferable:Boolean)
    fun header(value:JSONObject):Header {
        require(value.getString("owner")==PROGRAM && !value.getBoolean("executable"))
        val data=value.getJSONArray("data");require(data.getString(1)=="base64")
        val b=Base64.getDecoder().decode(data.getString(0));require(b.size>=200 && b.copyOfRange(0,8).contentEquals(discriminator))
        require(b[120].toInt() in 0..1)
        return Header(Base58.encodeToString(b.copyOfRange(8,40)),Base58.encodeToString(b.copyOfRange(40,72)),Base58.encodeToString(b.copyOfRange(72,104)),BigInteger(1,b.copyOfRange(104,112).reversedArray()),b[120].toInt()==1)
    }
}

sealed class SeekerNameResult(val kind:String) {
    data class Resolved(val wallet:String):SeekerNameResult("Resolved")
    data object NotFound:SeekerNameResult("NotFound")
    data object InvalidName:SeekerNameResult("InvalidName")
    data object RpcFailure:SeekerNameResult("RpcFailure")
    data object MalformedRecord:SeekerNameResult("MalformedRecord")
    data object UnsupportedRecord:SeekerNameResult("UnsupportedRecord")
}
data class SeekerObservation(val name:String,val result:SeekerNameResult,val pda:String="",val contextSlot:String="0",val resolvedAt:Long=0) {
    fun json()=JSONObject().put("name",name).put("network","solana:mainnet").put("genesis",SeekerProtocol.GENESIS).put("result",result.kind)
        .put("wallet",(result as? SeekerNameResult.Resolved)?.wallet ?: JSONObject.NULL).put("pda",pda).put("contextSlot",contextSlot).put("resolvedAt",resolvedAt)
    companion object { fun from(j:JSONObject):SeekerObservation {
        require(j.getString("genesis")==SeekerProtocol.GENESIS)
        val result=when(j.getString("result")) {"Resolved"->SeekerNameResult.Resolved(SolanaAccount.from(j.getString("wallet")).address);"NotFound"->SeekerNameResult.NotFound;else->error("Uncacheable observation")}
        return SeekerObservation(SeekerProtocol.name(j.getString("name")),result,j.getString("pda"),j.getString("contextSlot"),j.getLong("resolvedAt"))
    } }
}
data class SeekerReverseObservation(val wallet:String,val names:List<String>,val status:String,val contextSlot:String="0",val resolvedAt:Long=0) {
    fun json()=JSONObject().put("wallet",wallet).put("names",JSONArray(names)).put("status",status).put("contextSlot",contextSlot).put("resolvedAt",resolvedAt)
        .put("network","solana:mainnet").put("scope","unwrapped .skr; at most 16 records; forward-verified")
}
interface SeekerIdResolver {
    suspend fun resolveName(name:String,fresh:Boolean=false):SeekerObservation
    suspend fun resolveWallet(wallet:String,fresh:Boolean=false):SeekerReverseObservation
}
interface SeekerCache {
    fun get(name:String):SeekerObservation?
    fun put(observation:SeekerObservation)
}
class MemorySeekerCache:SeekerCache {
    private val values=mutableMapOf<String,SeekerObservation>()
    @Synchronized override fun get(name:String)=values[name]
    @Synchronized override fun put(observation:SeekerObservation) {
        values.remove(observation.name);values[observation.name]=observation
        while(values.size>128) values.remove(values.keys.first())
    }
}
class MainnetSeekerResolver(private val call:suspend (String,JSONArray)->Any,private val cache:SeekerCache=MemorySeekerCache(),private val now:()->Long=System::currentTimeMillis):SeekerIdResolver {
    companion object { const val POSITIVE_TTL=3600000L;const val NEGATIVE_TTL=60000L }
    @Volatile var last:SeekerObservation?=null;private set
    @Volatile var lastReverse:SeekerReverseObservation?=null;private set
    private val reverseCache=mutableMapOf<String,SeekerReverseObservation>()
    override suspend fun resolveWallet(wallet:String,fresh:Boolean):SeekerReverseObservation {
        SolanaAccount.from(wallet)
        val old=synchronized(reverseCache) { reverseCache[wallet] }
        if(!fresh && old!=null && now()-old.resolvedAt in 0 until 86400000L) return old.also { lastReverse=it }
        try {
            check(call("getGenesisHash",JSONArray())==SeekerProtocol.GENESIS)
            val filters=JSONArray().put(JSONObject().put("memcmp",JSONObject().put("offset",40).put("bytes",wallet)))
                .put(JSONObject().put("memcmp",JSONObject().put("offset",8).put("bytes",SeekerProtocol.PARENT)))
            val rpc=call("getProgramAccounts",JSONArray().put(SeekerProtocol.PROGRAM).put(JSONObject().put("filters",filters).put("encoding","base64")
                .put("commitment","finalized").put("withContext",true).put("dataSlice",JSONObject().put("offset",0).put("length",200)))) as JSONObject
            val slot=rpc.getJSONObject("context").getLong("slot").toString()
            val rows=rpc.getJSONArray("value").objects().sortedBy { it.getString("pubkey") }
            val names=mutableListOf<String>();val house=SeekerProtocol.tldHouse()
            for(row in rows.take(16)) {
                val header=SeekerProtocol.header(row.getJSONObject("account"));require(header.owner==wallet && header.parent==SeekerProtocol.PARENT)
                val domainPda=row.getString("pubkey")
                val reversePda=SeekerProtocol.pda(domainPda,RegistryProtocol.SYSTEM,house)
                val reverse=call("getMultipleAccounts",JSONArray().put(JSONArray().put(reversePda)).put(JSONObject().put("encoding","base64").put("commitment","finalized"))) as JSONObject
                val value=reverse.getJSONArray("value").optJSONObject(0) ?: continue
                val reverseHeader=SeekerProtocol.header(value);require(reverseHeader.nclass==house && reverseHeader.parent==RegistryProtocol.SYSTEM)
                val bytes=Base64.getDecoder().decode(value.getJSONArray("data").getString(0))
                val label=bytes.copyOfRange(200,bytes.size).toString(Charsets.UTF_8).trimEnd('\u0000')
                val name=runCatching { SeekerProtocol.name("$label.skr") }.getOrNull() ?: continue
                if(SeekerProtocol.domain(name)!=domainPda) continue
                val forward=resolveName(name,true)
                check(forward.result!=SeekerNameResult.RpcFailure) { "Forward verification unavailable" }
                if((forward.result as? SeekerNameResult.Resolved)?.wallet==wallet) names.add(name)
            }
            val result=SeekerReverseObservation(wallet,names.distinct().sorted(),if(rows.size>16) "Partial" else "Resolved",slot,now())
            synchronized(reverseCache) {
                reverseCache.remove(wallet);reverseCache[wallet]=result
                while(reverseCache.size>64) reverseCache.remove(reverseCache.keys.first())
            }
            return result.also { lastReverse=it }
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { return SeekerReverseObservation(wallet,emptyList(),"RpcUnavailable",resolvedAt=now()).also { lastReverse=it } }
    }
    override suspend fun resolveName(name:String,fresh:Boolean):SeekerObservation {
        val canonical=try { SeekerProtocol.name(name) } catch(_:Exception) { return SeekerObservation(name.take(100),SeekerNameResult.InvalidName) }
        val old=cache.get(canonical)
        if(!fresh && old!=null && now()-old.resolvedAt in 0 until if(old.result is SeekerNameResult.Resolved) POSITIVE_TTL else NEGATIVE_TTL) return old.also { last=it }
        val pda=SeekerProtocol.domain(canonical)
        var slot="0"
        fun observation(r:SeekerNameResult)=SeekerObservation(canonical,r,pda,slot,now()).also {
            last=it;if(r is SeekerNameResult.Resolved || r==SeekerNameResult.NotFound) cache.put(it)
        }
        try {
            check(call("getGenesisHash",JSONArray())==SeekerProtocol.GENESIS)
            val rpc=call("getMultipleAccounts",JSONArray().put(JSONArray().put(SeekerProtocol.PARENT).put(pda)).put(JSONObject().put("encoding","base64").put("commitment","finalized"))) as JSONObject
            slot=rpc.getJSONObject("context").getLong("slot").also { require(it>0) }.toString()
            val values=rpc.getJSONArray("value");require(values.length()==2)
            try {
                val parent=SeekerProtocol.header(values.getJSONObject(0))
                require(parent.parent==SeekerProtocol.ROOT && parent.owner==SeekerProtocol.tldHouse() && parent.nclass==RegistryProtocol.SYSTEM)
                if(values.isNull(1)) return observation(SeekerNameResult.NotFound)
                val record=SeekerProtocol.header(values.getJSONObject(1))
                require(record.parent==SeekerProtocol.PARENT && record.nclass==RegistryProtocol.SYSTEM && record.owner!=RegistryProtocol.SYSTEM)
                // Match SDK expiry + parent's grace seconds (default 50 days), without JS date precision loss.
                val grace=if(parent.expires==BigInteger.ZERO) BigInteger.valueOf(50L*86400) else parent.expires
                if(record.expires!=BigInteger.ZERO && record.expires+grace<=BigInteger.valueOf(now()/1000)) return observation(SeekerNameResult.NotFound)
                // Never mislabel NFT custody as a wallet. Tokenized records need a separate ownership reader.
                if(record.owner==SeekerProtocol.nftRecord(pda)) return observation(SeekerNameResult.UnsupportedRecord)
                return observation(SeekerNameResult.Resolved(record.owner))
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { return observation(SeekerNameResult.MalformedRecord) }
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { return observation(SeekerNameResult.RpcFailure) }
    }
}
fun seekerMessage(result:SeekerNameResult)=when(result) {
    is SeekerNameResult.Resolved->"Seeker ID found"
    SeekerNameResult.NotFound->"Seeker ID not found."
    SeekerNameResult.InvalidName->"Enter a valid Seeker ID ending in .skr."
    SeekerNameResult.RpcFailure->"Could not resolve Seeker ID. Try again."
    SeekerNameResult.UnsupportedRecord->"This Seeker ID record is not supported in this preview."
    SeekerNameResult.MalformedRecord->"The Seeker ID record is invalid."
}

fun seekerMatches(contact:Contact,observation:SeekerObservation)=
    (observation.result as? SeekerNameResult.Resolved)?.wallet==contact.provenance?.wallet
fun seekerOwnerChanged(contact:Contact,observation:SeekerObservation)=
    observation.result is SeekerNameResult.Resolved && !seekerMatches(contact,observation)
