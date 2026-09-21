package network.mmis.runtime

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SeekerTests {
    private fun fixture()=JSONObject(javaClass.classLoader!!.getResource("mainnet-observations.json")!!.readText())
    private fun account(data:String)=JSONObject().put("owner",SeekerProtocol.PROGRAM).put("executable",false).put("data",JSONArray().put(data).put("base64"))
    private fun response():JSONObject {
        val f=fixture();return JSONObject().put("context",JSONObject().put("slot",447835110))
            .put("value",JSONArray().put(account(f.getJSONObject("parent").getString("data")))
                .put(account(f.getJSONArray("observations").getJSONObject(0).getJSONObject("account").getString("data"))))
    }
    private val wallet="6fHkwweD2gMMGNXKPvH6tYAVZsL1BW8LadVgPgvVWLcE"
    @Test fun officialSdkDerivationVectors()=runBlocking {
        val rows=JSONArray(javaClass.classLoader!!.getResource("derivation-vectors.json")!!.readText())
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);assertEquals(row.getString("pda"),SeekerProtocol.domain(row.getString("name")))
            assertEquals(row.getString("parent"),SeekerProtocol.pda(".skr",SeekerProtocol.ROOT))
        }
        assertNotEquals(SeekerProtocol.domain("x0d.skr"),SeekerProtocol.domain("X0d.skr")) // exact SDK hashing; no invented folding
    }
    @Test fun parserPrecedenceAndBoundedNames() {
        assertEquals(AddressInput.Seeker("x0d.skr"),PrivateAddress.classify(" x0d.skr "))
        assertEquals(AddressInput.Solana(wallet),PrivateAddress.classify(wallet))
        val direct=PrivateAddress.encode(PeerIdentity(Base64.getEncoder().encodeToString(ByteArray(32) {1}),1))
        assertTrue(PrivateAddress.classify(direct) is AddressInput.Direct)
        for(name in listOf("x0d","x0d.sol",".skr","x..skr","x.skr.","x.skr.evil","x.skr/path","x y.skr","-x.skr","x_.skr","x--y.skr","a".repeat(64)+".skr","аlice.skr",wallet)) {
            assertTrue(name,runCatching { SeekerProtocol.name(name) }.isFailure)
        }
        assertEquals("Alice.skr",SeekerProtocol.name("Alice.skr"))
        assertTrue(runCatching {SeekerProtocol.name("alice.SKR")}.isFailure)
    }
    @Test fun mainnetRecordMatchesIndependentOfficialObservation()=runBlocking {
        val resolver=MainnetSeekerResolver({method,params->
            if(method=="getGenesisHash") SeekerProtocol.GENESIS else {
                assertEquals("getMultipleAccounts",method);assertEquals(SeekerProtocol.PARENT,params.getJSONArray(0).getString(0))
                assertEquals("98zGFoe39uruFPihNEvCnxrWeDQKjcJHfzrExzeWvDa8",params.getJSONArray(0).getString(1));response()
            }
        })
        val result=resolver.resolveName("x0d.skr",true)
        assertEquals(SeekerNameResult.Resolved(wallet),result.result);assertEquals("447835110",result.contextSlot)
    }
    @Test fun notFoundRpcMalformedAndWrongNetworkStayDistinct()=runBlocking {
        val notFound=MainnetSeekerResolver({m,_->if(m=="getGenesisHash") SeekerProtocol.GENESIS else response().apply {getJSONArray("value").put(1,JSONObject.NULL)}})
        assertEquals(SeekerNameResult.NotFound,notFound.resolveName("unallocated.skr",true).result)
        val broken=MainnetSeekerResolver({_,_->throw java.io.IOException("injected failure")})
        assertEquals(SeekerNameResult.RpcFailure,broken.resolveName("x0d.skr",true).result)
        val wrongChain=MainnetSeekerResolver({_,_->"EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"})
        assertEquals(SeekerNameResult.RpcFailure,wrongChain.resolveName("x0d.skr",true).result)
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("owner",RegistryProtocol.SYSTEM)}, {it.put("executable",true)},
            {it.put("data",JSONArray().put("AA==").put("base64"))},
            {val bytes=Base64.getDecoder().decode(it.getJSONArray("data").getString(0));bytes[8]=0;it.getJSONArray("data").put(0,Base64.getEncoder().encodeToString(bytes))}
        )) {
            val r=response();change(r.getJSONArray("value").getJSONObject(1))
            val resolver=MainnetSeekerResolver({m,_->if(m=="getGenesisHash") SeekerProtocol.GENESIS else r})
            assertEquals(SeekerNameResult.MalformedRecord,resolver.resolveName("x0d.skr",true).result)
        }
    }
    @Test fun invalidNamesNeverTouchTransport()=runBlocking {
        var calls=0;val resolver=MainnetSeekerResolver({_,_->calls++;error("must not call")})
        assertEquals(SeekerNameResult.InvalidName,resolver.resolveName("x0d",true).result);assertEquals(0,calls)
    }
    @Test fun invalidMainnetConfigurationFailsOnlyTheOptionalResolver() {
        assertEquals("https://api.mainnet-beta.solana.com",SeekerProtocol.rpcUrl(null))
        assertNull(SeekerProtocol.rpcUrl("not json"))
        assertNull(SeekerProtocol.rpcUrl("{\"chain\":\"solana:devnet\",\"rpcUrl\":\"https://api.devnet.solana.com\"}"))
        assertNull(SeekerProtocol.rpcUrl("{\"chain\":\"solana:mainnet\",\"rpcUrl\":\"http://example.com\"}"))
    }
    @Test fun cacheExpiryFreshOwnerChangeAndFailurePreservation()=runBlocking {
        var clock=100000L;var calls=0;var fail=false;var rpc=response()
        val cache=MemorySeekerCache()
        val resolver=MainnetSeekerResolver({m,_->calls++;if(fail) error("offline");if(m=="getGenesisHash") SeekerProtocol.GENESIS else rpc},cache,{clock})
        val first=resolver.resolveName("x0d.skr",false);assertEquals(2,calls)
        clock++;assertEquals(first,resolver.resolveName("x0d.skr",false));assertEquals(2,calls)
        clock+=MainnetSeekerResolver.POSITIVE_TTL;resolver.resolveName("x0d.skr",false);assertEquals(4,calls)
        fail=true;assertEquals(SeekerNameResult.RpcFailure,resolver.resolveName("x0d.skr",true).result)
        assertEquals(SeekerNameResult.Resolved(wallet),cache.get("x0d.skr")!!.result)
        fail=false;val newOwner=SolanaAccount.from("4dSaHiTHajbszz6QHf32m5vfTC5aD52SJAUZkHs5ctYR").publicKeyBytes
        val value=rpc.getJSONArray("value").getJSONObject(1);val bytes=Base64.getDecoder().decode(value.getJSONArray("data").getString(0))
        newOwner.copyInto(bytes,40);value.getJSONArray("data").put(0,Base64.getEncoder().encodeToString(bytes))
        val changed=resolver.resolveName("x0d.skr",true);assertNotEquals(first.result,changed.result)
        assertEquals(changed,cache.get("x0d.skr"))
    }
    @Test fun namesAreProvenanceNotConversationKeysAndChangesAreSeparate() {
        val peer=PeerIdentity(Base64.getEncoder().encodeToString(ByteArray(32) {1}),1)
        val p=Provenance(wallet,"devnet/test","1","123",456,seekerId="x0d.skr",seekerSlot="447835110",seekerResolvedAt=789)
        val seeker=Contact(peer,AddressSource.Solana,p)
        val raw=seeker.copy(provenance=p.copy(seekerId=null,seekerSlot=null,seekerResolvedAt=null))
        assertEquals(seeker.peer.conversationId,raw.peer.conversationId);assertNotEquals(seeker.id,raw.id)
        assertEquals(seeker,Contact.from(seeker.json()));assertEquals("x0d.skr",seeker.title)
        assertEquals("Local alias",seeker.copy(alias="Local alias").title);assertEquals(shortAddress(wallet),raw.title)
        val changed=SeekerObservation("x0d.skr",SeekerNameResult.Resolved("4dSaHiTHajbszz6QHf32m5vfTC5aD52SJAUZkHs5ctYR"))
        assertTrue(seekerOwnerChanged(seeker,changed));assertFalse(seekerMatches(seeker,changed))
        assertFalse(seekerOwnerChanged(seeker,changed.copy(result=SeekerNameResult.RpcFailure)))
        assertFalse(ProductPresentation.endpointChanged(seeker,RegistryResult.Registered(RegistryEndpoint(peer.key,peer.token,2uL))))
        assertTrue(ProductPresentation.endpointChanged(seeker,RegistryResult.Registered(RegistryEndpoint(peer.key,2,2uL))))
    }
    @Test fun negativeCacheExpiresAndOutageDoesNotBecomeAbsence()=runBlocking {
        var clock=100000L;var calls=0;var fail=false
        val resolver=MainnetSeekerResolver({m,_->calls++;if(fail) error("offline");if(m=="getGenesisHash") SeekerProtocol.GENESIS else response().apply {getJSONArray("value").put(1,JSONObject.NULL)}},now={clock})
        assertEquals(SeekerNameResult.NotFound,resolver.resolveName("missing.skr",false).result)
        clock++;resolver.resolveName("missing.skr",false);assertEquals(2,calls)
        clock+=MainnetSeekerResolver.NEGATIVE_TTL;fail=true
        assertEquals(SeekerNameResult.RpcFailure,resolver.resolveName("missing.skr",false).result)
        assertNotEquals(seekerMessage(SeekerNameResult.RpcFailure),seekerMessage(SeekerNameResult.NotFound))
    }
    @Test fun expiredAndWrappedRecordsDoNotResolveToCustodyWallet()=runBlocking {
        val rpc=response();val value=rpc.getJSONArray("value").getJSONObject(1)
        val bytes=Base64.getDecoder().decode(value.getJSONArray("data").getString(0))
        bytes[104]=1 // expiration at epoch + 1 second; parent uses default grace
        value.getJSONArray("data").put(0,Base64.getEncoder().encodeToString(bytes))
        val resolver=MainnetSeekerResolver({m,_->if(m=="getGenesisHash") SeekerProtocol.GENESIS else rpc})
        assertEquals(SeekerNameResult.NotFound,resolver.resolveName("x0d.skr",true).result)
        bytes[104]=0;SeekerProtocol.key(SeekerProtocol.nftRecord(SeekerProtocol.domain("x0d.skr"))).copyInto(bytes,40)
        value.getJSONArray("data").put(0,Base64.getEncoder().encodeToString(bytes))
        assertEquals(SeekerNameResult.UnsupportedRecord,resolver.resolveName("x0d.skr",true).result)
    }
    @Test fun reverseUsesMainnetAndVerifiesForwardMapping()=runBlocking {
        val reverse=JSONObject(javaClass.classLoader!!.getResource("reverse-observation.json")!!.readText())
        var calls=0;var failForward=false
        val resolver=MainnetSeekerResolver({method,params->
            calls++
            when(method) {
                "getGenesisHash"->SeekerProtocol.GENESIS
                "getProgramAccounts"->{
                    assertEquals(wallet,params.getJSONObject(1).getJSONArray("filters").getJSONObject(0).getJSONObject("memcmp").getString("bytes"))
                    JSONObject().put("context",JSONObject().put("slot",447838483)).put("value",JSONArray().put(JSONObject().put("pubkey","98zGFoe39uruFPihNEvCnxrWeDQKjcJHfzrExzeWvDa8").put("account",response().getJSONArray("value").getJSONObject(1))))
                }
                else->if(params.getJSONArray(0).length()==1) {
                    assertEquals(reverse.getString("reversePda"),params.getJSONArray(0).getString(0))
                    JSONObject().put("context",JSONObject().put("slot",447838483)).put("value",JSONArray().put(account(reverse.getJSONObject("account").getString("data"))))
                } else { check(!failForward) { "Forward RPC unavailable" };response() }
            }
        })
        val result=resolver.resolveWallet(wallet,true)
        assertEquals("Resolved",result.status);assertEquals(listOf("x0d.skr"),result.names)
        val after=calls;assertEquals(result,resolver.resolveWallet(wallet,false));assertEquals(after,calls)
        failForward=true
        assertEquals("RpcUnavailable",resolver.resolveWallet(wallet,true).status)
        assertEquals(result,resolver.resolveWallet(wallet,false))
        val unavailable=MainnetSeekerResolver({_,_->error("provider does not support GPA")})
        assertTrue(unavailable.resolveWallet(wallet,true).names.isEmpty())
        assertEquals("RpcUnavailable",unavailable.lastReverse!!.status)
    }
}

