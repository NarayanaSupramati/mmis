package network.mmis.runtime

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import java.util.Base64

class ProductTests {
    private val x=PeerIdentity(Base64.getEncoder().encodeToString(ByteArray(32) {1}),3823185488)
    private val y=PeerIdentity(Base64.getEncoder().encodeToString(ByteArray(32) {2}),4294967295)
    private val provenance=Provenance("wallet-A","deployment-A","1","123",456)
    @Test fun browserCodecVectors() {
        val rows=JSONArray(javaClass.classLoader!!.getResource("private-address-vectors.json")!!.readText())
        for(i in 0 until rows.length()) { val row=rows.getJSONObject(i);val peer=PeerIdentity.from(row.getJSONObject("peer"));val address=row.getString("address")
            assertEquals(address,PrivateAddress.encode(peer));assertEquals(peer,PrivateAddress.decode(address));assertEquals(AddressInput.Direct(peer),PrivateAddress.classify(" $address "))
        }
    }
    @Test fun strictParserRejectsCorruptionAndAmbiguity() {
        val valid=PrivateAddress.encode(x);val bytes=Base64.getUrlDecoder().decode(valid.removePrefix("sp1_"));bytes[3]=(bytes[3].toInt() xor 1).toByte()
        val badCrc="sp1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        for(bad in listOf(badCrc,valid+"=",valid.dropLast(1),valid.replace('_','+'),"sp2_"+valid.substring(4),"0OIl","1111","1".repeat(45),"11111111111111111111111111111111111","{}","https://example.com","11111111111111111111111111111111 "+valid)) {
            assertTrue("Accepted: $bad",runCatching { PrivateAddress.classify(bad) }.isFailure)
        }
        assertEquals(AddressInput.Solana("11111111111111111111111111111111"),PrivateAddress.classify("11111111111111111111111111111111"))
        // Last base64url character has unused bits; noncanonical variants must fail.
        val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";val index=alphabet.indexOf(valid.last())
        assertTrue(runCatching { PrivateAddress.decode(valid.dropLast(1)+alphabet[index xor 1]) }.isFailure)
    }
    @Test fun contactsNeverMergeDifferentSigningIdentities() {
        val direct=Contact(x,AddressSource.Direct)
        val a=Contact(x,AddressSource.Solana,provenance)
        val rebound=Contact(y,AddressSource.Solana,provenance.copy(revision="2"))
        val otherWallet=Contact(x,AddressSource.Solana,provenance.copy(wallet="wallet-B"))
        assertNotEquals(a.id,rebound.id);assertNotEquals(a.peer.conversationId,rebound.peer.conversationId)
        assertNotEquals(a.id,otherWallet.id);assertEquals(a.peer.conversationId,otherWallet.peer.conversationId)
        assertEquals(direct.peer.conversationId,a.peer.conversationId)
        assertEquals(a,Contact.from(a.json()))
    }
    @Test fun presentationSeparatesFailureMismatchAndPending() {
        val registered=RegistryResult.Registered(RegistryEndpoint(x.key,x.token,1uL))
        assertEquals(BindingState.Registered,ProductPresentation.binding(x,registered))
        assertEquals(BindingState.DifferentIdentity,ProductPresentation.binding(y,registered))
        assertEquals(BindingState.NotRegistered,ProductPresentation.binding(x,RegistryResult.NotRegistered))
        assertEquals(BindingState.RpcUnavailable,ProductPresentation.binding(x,RegistryResult.RpcFailure))
        assertEquals(BindingState.OutcomeUnknown,ProductPresentation.binding(x,registered,"OutcomeUnknown"))
        assertEquals(BindingState.Conflict,ProductPresentation.binding(x,registered,"ConflictStateChanged"))
        assertEquals(BindingState.PendingConfirmation,ProductPresentation.binding(x,registered,"WalletSigned"))
        assertTrue(ProductPresentation.endpointChanged(Contact(y,AddressSource.Solana,provenance),registered))
        assertNotEquals(ProductPresentation.lookup(RegistryResult.NotRegistered),ProductPresentation.lookup(RegistryResult.RpcFailure))
    }
    @Test fun statusesAndTimestampPrecision() {
        assertEquals(2,settledMessageStatus(2,1));assertEquals(2,settledMessageStatus(2,3));assertEquals(3,settledMessageStatus(3,0));assertEquals(2,settledMessageStatus(3,2))
        assertEquals("Sending",messageStatus(0));assertEquals("Sending",messageStatus(1));assertEquals("Sent to network",messageStatus(2));assertEquals("Failed",messageStatus(3))
        val raw="1789571431357348854";assertNotEquals("Unknown time",messageTime(raw));assertEquals("1789571431357348854",raw)
        assertEquals("Unknown time",messageTime("bad"))
        assertEquals("Unknown time",messageTime("-6795364578871345152"))
        assertEquals("Unknown time",messageTime("0"))
    }
}
