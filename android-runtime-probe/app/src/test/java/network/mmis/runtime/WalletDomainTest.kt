package network.mmis.runtime

import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class WalletDomainTest {
    private val secret=Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() },0)
    private val key=secret.generatePublicKey().encoded
    @Test fun publicKeys() {
        val a=SolanaAccount(key)
        assertArrayEquals(key,SolanaAccount.from(a.address).publicKeyBytes)
        for(invalid in listOf("0OIl", "1", "", "111111111111111111111111111111111")) {
            assertThrows(Exception::class.java) { SolanaAccount.from(invalid) }
        }
        val copy=a.publicKeyBytes; copy[0]=0; assertArrayEquals(key,a.publicKeyBytes)
    }
    @Test fun canonicalAndSignature() {
        val address=SolanaAccount(key).address
        val bytes=WalletProof.canonical(address,"test-1")
        assertEquals("MMIS Wallet Proof\nversion:1\nwallet:$address\nnonce:test-1\nchain:solana:devnet\n",bytes.toString(Charsets.UTF_8))
        assertArrayEquals(bytes,WalletProof.canonical(address,"test-1"))
        assertFalse(bytes.contentEquals(WalletProof.canonical(address,"test-2")))
        assertThrows(IllegalArgumentException::class.java) { WalletProof.canonical(address,"injected\nfield") }
        val signature=Ed25519Signer().run { init(true,secret); update(bytes,0,bytes.size); generateSignature() }
        assertTrue(WalletProof.verify(bytes,signature,key))
        assertFalse(WalletProof.verify(bytes+byteArrayOf(1),signature,key))
        signature[0]=(signature[0].toInt() xor 1).toByte()
        assertFalse(WalletProof.verify(bytes,signature,key))
    }
    @Test fun persistenceAndRedaction() {
        val token="TEST_ONLY_TOKEN_DO_NOT_LOG"
        val a=WalletAuthorization(listOf(SolanaAccount(key,"label\nUnicode Ж",listOf("solana:devnet"),listOf("solana:signTransactions"))),0,token,"https://wallet.example")
        val restored=WalletAuthorization.decode(a.encode())
        assertEquals(a.active.address,restored.active.address); assertEquals(a.active.label,restored.active.label)
        assertEquals(a.active.chains,restored.active.chains); assertEquals(a.active.features,restored.active.features)
        assertEquals(token,restored.token); assertEquals(a.walletUri,restored.walletUri)
        assertFalse(a.toString().contains(token)); assertFalse(restored.toString().contains(token))
    }
}
