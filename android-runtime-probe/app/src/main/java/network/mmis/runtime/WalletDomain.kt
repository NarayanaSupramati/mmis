package network.mmis.runtime

import com.funkatronics.encoders.Base58
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64
import java.util.Properties
import java.io.StringReader
import java.io.StringWriter

class SolanaAccount(bytes: ByteArray, val label: String? = null, val chains: List<String> = emptyList(), val features: List<String> = emptyList()) {
    private val key=bytes.copyOf()
    val publicKeyBytes get()=key.copyOf()
    val address: String=Base58.encodeToString(key)
    init { require(key.size==32); require(Base58.decode(address).contentEquals(key)) }
    companion object { fun from(address: String)=SolanaAccount(Base58.decode(address)) }
}

/** Token is private transport metadata: deliberately NOT a data class/toString payload. */
class WalletAuthorization(val accounts: List<SolanaAccount>, val selected: Int, internal val token: String, val walletUri: String?) {
    init { require(accounts.isNotEmpty()); require(selected in accounts.indices); require(token.isNotEmpty()) }
    val active get()=accounts[selected]
    override fun toString()="WalletAuthorization(accounts=${accounts.size}, selected=$selected, tokenPresent=true)"
    internal fun encode(): String {
        val p=Properties()
        p.setProperty("token",token); p.setProperty("selected",selected.toString()); p.setProperty("count",accounts.size.toString())
        walletUri?.let { p.setProperty("uri",it) }
        accounts.forEachIndexed { i,a ->
            p.setProperty("account.$i",a.address); a.label?.let { p.setProperty("label.$i",it) }
            p.setProperty("chains.$i",a.chains.joinToString(",")); p.setProperty("features.$i",a.features.joinToString(","))
        }
        return StringWriter().also { p.store(it,null) }.toString()
    }
    companion object {
        internal fun decode(text: String): WalletAuthorization {
            val p=Properties().apply { load(StringReader(text)) }
            val count=p.getProperty("count").toInt(); require(count in 1..100)
            fun list(key:String)=p.getProperty(key,"").split(',').filter { it.isNotEmpty() }
            return WalletAuthorization((0 until count).map { i -> SolanaAccount(Base58.decode(p.getProperty("account.$i")),p.getProperty("label.$i"),list("chains.$i"),list("features.$i")) },p.getProperty("selected").toInt(),p.getProperty("token"),p.getProperty("uri"))
        }
    }
}

object WalletProof {
    fun canonical(address: String, nonce: String): ByteArray {
        SolanaAccount.from(address)
        require(nonce.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return "MMIS Wallet Proof\nversion:1\nwallet:$address\nnonce:$nonce\nchain:solana:devnet\n".toByteArray(Charsets.UTF_8)
    }
    fun verify(message: ByteArray, signature: ByteArray, key: ByteArray): Boolean = try {
        if(key.size!=32 || signature.size!=64) false else Ed25519Signer().run {
            init(false,Ed25519PublicKeyParameters(key,0)); update(message,0,message.size); verifySignature(signature)
        }
    } catch(_: Exception) { false }
    fun b64(bytes: ByteArray): String=Base64.getEncoder().encodeToString(bytes)
}
