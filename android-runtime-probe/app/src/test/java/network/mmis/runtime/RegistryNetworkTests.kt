package network.mmis.runtime

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RegistryNetworkTests {
    private val lab=RegistryNetwork(RegistryProfile.Lab,RegistryDeployment("solana:devnet",RegistryProfile.Lab.genesis,"Bb3evw39nAonSVCykSnjjm1vrdKhuy6MAR7R2iDQGCyJ",1))
    private val release=RegistryNetwork(RegistryProfile.Release,RegistryDeployment("solana:mainnet",RegistryProfile.Release.genesis,"MmisXGzDeJbPeK9uMDpqYxQ3ED7xsjYahP9rS5AvuYz",1))
    private fun invalid(block:()->Unit) {try {block();fail("Must reject mismatched configuration")} catch(_:IllegalArgumentException) {}}
    @Test fun profilesPinChainAndGenesis() {
        invalid {RegistryNetwork(RegistryProfile.Release,lab.deployment)}
        invalid {RegistryNetwork(RegistryProfile.Lab,release.deployment)}
        invalid {RegistryNetwork(RegistryProfile.Release,release.deployment.copy(genesisHash=lab.deployment.genesisHash))}
        assertEquals("solana:mainnet",lab.diagnostics().getString("seekerChain"))
        assertEquals("solana:mainnet",release.diagnostics().getString("seekerChain"))
    }
    @Test fun rpcConfigCannotSelectAnotherChainOrLeakIntoFallback() {
        val configured=JSONObject().put("chain","solana:mainnet").put("rpcUrl","https://example.test/rpc")
        assertEquals("https://example.test/rpc",release.rpcUrl(configured))
        invalid {lab.rpcUrl(configured)}
        invalid {release.rpcUrl(configured.put("rpcUrl","http://example.test/rpc"))}
        assertEquals("https://api.devnet.solana.com",lab.rpcUrl(null))
        assertEquals("https://api.mainnet-beta.solana.com",release.rpcUrl(null))
        assertNotEquals(lab.profile.rpcConfigName,release.profile.rpcConfigName)
    }
    @Test fun walletAuthorizationRemainsSeparateAndLabKeepsExistingFile() {
        assertEquals("wallet-authorization.properties",lab.profile.authFileName)
        assertNotEquals(lab.profile.authFileName,release.profile.authFileName)
    }
    @Test fun legacyPendingCannotBeRestoredUnderMainnetOrAnotherDeployment() {
        val pending=JSONObject().put("namespace",lab.deployment.namespace).put("state","Submitted").put("signature","old")
        assertTrue(RegistryStorageNames.matches(pending,lab.deployment));assertFalse(RegistryStorageNames.matches(pending,release.deployment))
        assertFalse(RegistryStorageNames.matches(JSONObject(),release.deployment))
        assertEquals("registry-pending.json",RegistryStorageNames.pending(lab.deployment))
        assertNotEquals(RegistryStorageNames.pending(lab.deployment),RegistryStorageNames.pending(release.deployment))
        val changed=release.deployment.copy(programId=lab.deployment.programId)
        assertNotEquals(RegistryStorageNames.pending(changed),RegistryStorageNames.pending(release.deployment))
    }
    @Test fun genesisMismatchFailsBeforeDependentOperation()=runBlocking {
        var downstream=false
        try {release.verifyGenesis {method,_->assertEquals("getGenesisHash",method);lab.deployment.genesisHash};downstream=true;fail()} catch(_:IllegalStateException) {}
        assertFalse(downstream)
        release.verifyGenesis {_,_->release.deployment.genesisHash}
    }
    @Test fun releaseDerivesDifferentPdaAndDoesNotReadLabCache()=runBlocking {
        val wallet="MMiS5XBcZRPUK69w7ayuE7mBzby5BxzgifUsLKkHNeo"
        assertNotEquals(RegistryProtocol.pda(lab.deployment,wallet),RegistryProtocol.pda(release.deployment,wallet))
        val old=RegistryResolver(lab.deployment,{method,_->if(method=="getGenesisHash") lab.deployment.genesisHash else JSONObject().put("context",JSONObject().put("slot",1)).put("value",JSONObject.NULL)})
        old.resolve(wallet);assertNotNull(old.cached(wallet))
        val calls=mutableListOf<String>()
        val current=RegistryResolver(release.deployment,{method,_->calls.add(method);lab.deployment.genesisHash})
        assertNull(current.cached(wallet));assertEquals(RegistryResult.RpcFailure,current.resolve(wallet,false).result)
        assertEquals(listOf("getGenesisHash"),calls);assertNull(current.cached(wallet));assertNotNull(old.cached(wallet))
    }
    @Test fun instructionDataIsIdenticalAcrossProgramProfiles()=runBlocking {
        for(operation in listOf("register","update","close")) {
            val wallet="MMiS5XBcZRPUK69w7ayuE7mBzby5BxzgifUsLKkHNeo"
            val a=RegistryProtocol.instruction(lab.deployment,wallet,operation,ByteArray(32){1},0xffffffffL)
            val b=RegistryProtocol.instruction(release.deployment,wallet,operation,ByteArray(32){1},0xffffffffL)
            assertArrayEquals(a.data,b.data);assertNotEquals(a.programId,b.programId)
        }
    }
}
