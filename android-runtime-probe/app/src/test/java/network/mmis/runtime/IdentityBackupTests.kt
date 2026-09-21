package network.mmis.runtime

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

class IdentityBackupTests {
    private val password="test-only-long-password".toCharArray()
    private fun identity():ByteArray {
        val seed=ByteArray(32) {it.toByte()};val pub=Ed25519PrivateKeyParameters(seed,0).generatePublicKey().encoded
        return byteArrayOf(0)+seed+pub+pub
    }
    private fun payload():JSONObject {
        val peer=IdentityBackup.derivedEndpoint(identity())
        // The native export itself is exercised through real JNI instrumentation.
        val native=ByteArray(163);ByteBuffer.wrap(native).order(ByteOrder.LITTLE_ENDIAN).putInt(16,1).putInt(20,65536);native[24]=4
        return JSONObject().put("format",IdentityBackup.FORMAT).put("version",1).put("xxdkVersion","4.7.9")
            .put("createdAt","2026-09-21T00:00:00Z").put("signingPublicKey",peer.key).put("dmToken",peer.token)
            .put("privateAddress",PrivateAddress.encode(peer)).put("xxIdentity","<xxChannelIdentity(0)"+Base64.getEncoder().encodeToString(native)+"xxChannelIdentity>")
    }
    @Test fun authenticatedRoundtripAndRandomSalt() {
        val p=payload();val a=IdentityBackup.encrypt(p,password);val b=IdentityBackup.encrypt(p,password)
        assertFalse(a.contentEquals(b));val out=IdentityBackup.decrypt(a,password)
        assertEquals(p.toString(),out.toString());IdentityBackup.validateIdentity(identity(),IdentityBackup.validatePayload(out))
    }
    @Test fun wrongPasswordAndCiphertextRejected() {
        val bytes=IdentityBackup.encrypt(payload(),password)
        assertTrue(runCatching {IdentityBackup.decrypt(bytes,"another-long-password".toCharArray())}.isFailure)
        val j=JSONObject(String(bytes));val c=Base64.getDecoder().decode(j.getString("ciphertext"));c[0]=(c[0].toInt() xor 1).toByte()
        j.put("ciphertext",Base64.getEncoder().encodeToString(c))
        assertTrue(runCatching {IdentityBackup.decrypt(j.toString().toByteArray(),password)}.isFailure)
    }
    @Test fun unknownVersionAndUnboundedKdfRejected() {
        val bytes=IdentityBackup.encrypt(payload(),password)
        for((key,value) in listOf("version" to 2,"version" to 1.5,"version" to "1","iterations" to Int.MAX_VALUE,"iterations" to 600000.5)) {
            val j=JSONObject(String(bytes)).put(key,value)
            assertTrue(runCatching {IdentityBackup.decrypt(j.toString().toByteArray(),password)}.isFailure)
        }
        assertTrue(runCatching {IdentityBackup.encrypt(payload().put("version",2),password)}.isFailure)
    }
    @Test fun tokenUnsignedAndAddressStrict() {
        val p=payload();val peer=PeerIdentity(p.getString("signingPublicKey"),4294967295L)
        p.put("dmToken",peer.token).put("privateAddress",PrivateAddress.encode(peer))
        assertEquals(4294967295L,IdentityBackup.validatePayload(IdentityBackup.decrypt(IdentityBackup.encrypt(p,password),password)).token)
        for(bad in listOf(-1L,4294967296L,2.5,"2426510008")) {
            assertTrue(runCatching {IdentityBackup.validatePayload(JSONObject(p.toString()).put("dmToken",bad))}.isFailure)
        }
        assertTrue(runCatching {IdentityBackup.validatePayload(p.put("privateAddress","sp1_invalid"))}.isFailure)
    }
    @Test fun inconsistentPrivatePublicAndMetadataRejected() {
        val bytes=identity();val expected=IdentityBackup.derivedEndpoint(bytes)
        bytes[65]=(bytes[65].toInt() xor 1).toByte()
        assertTrue(runCatching {IdentityBackup.derivedEndpoint(bytes)}.isFailure)
        assertTrue(runCatching {IdentityBackup.validateIdentity(identity(),expected.copy(token=expected.token xor 1))}.isFailure)
    }
}
