package network.mmis.runtime

import org.json.JSONObject
import java.security.MessageDigest

enum class RegistryProfile(val networkName:String,val chain:String,val genesis:String,val publicRpc:String) {
    Lab("Devnet","solana:devnet","EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG","https://api.devnet.solana.com"),
    Release("Mainnet","solana:mainnet","5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d","https://api.mainnet-beta.solana.com");
    val assetName get()="registry-${networkName.lowercase()}.json"
    val rpcConfigName get()="solana-${networkName.lowercase()}.json"
    val authFileName get()=if(this==Lab) "wallet-authorization.properties" else "wallet-authorization-mainnet.properties"
}

/** One immutable deployment for the process; no runtime network toggle. */
data class RegistryNetwork(val profile:RegistryProfile,val deployment:RegistryDeployment) {
    init {require(deployment.chain==profile.chain && deployment.genesisHash==profile.genesis) {"Registry profile/deployment mismatch"}}
    val label get()="${profile.name}/${profile.networkName}"
    fun rpcUrl(config:JSONObject?):String {
        if(config==null) return profile.publicRpc
        require(config.getString("chain")==deployment.chain) {"RPC config chain mismatch"}
        val url=config.getString("rpcUrl");val parsed=java.net.URI(url)
        require(parsed.scheme=="https" && !parsed.host.isNullOrBlank() && parsed.userInfo==null && parsed.fragment==null) {"Invalid RPC URL"}
        return url
    }
    suspend fun verifyGenesis(call:suspend (String,org.json.JSONArray)->Any) {
        check(call("getGenesisHash",org.json.JSONArray())==deployment.genesisHash) {"RPC_GENESIS_MISMATCH"}
    }
    fun diagnostics()=JSONObject().put("profile",profile.name).put("network",profile.networkName).put("chain",deployment.chain)
        .put("genesisHash",deployment.genesisHash).put("programId",deployment.programId).put("protocolVersion",1)
        .put("seekerChain","solana:mainnet").put("upgradeableBeta",profile==RegistryProfile.Release)
}

object RegistryStorageNames {
    // Preserve the original Lab pending file without migrating or reinterpreting it.
    private const val LEGACY_DEVNET="solana:devnet/EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG/Bb3evw39nAonSVCykSnjjm1vrdKhuy6MAR7R2iDQGCyJ"
    fun pending(deployment:RegistryDeployment):String = if(deployment.namespace==LEGACY_DEVNET) "registry-pending.json" else
        "registry-pending-"+MessageDigest.getInstance("SHA-256").digest(deployment.namespace.toByteArray()).joinToString("") {"%02x".format(it)}+".json"
    fun matches(pending:JSONObject,deployment:RegistryDeployment)=pending.optString("namespace")==deployment.namespace
}
