package network.mmis.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ForegroundNotificationTests {
    @Test fun deletionClearsOnlyTargetAndAllowsNewArrival() {
        val c=ForegroundNotificationCoordinator({0});c.foreground(true)
        c.accept(event(1));c.accept(event(2,2));c.clearConversation(key(1))
        assertEquals(2L,c.state.value.current!!.event.localMessageId)
        c.accept(event(3));c.clearConversation(key(1));assertTrue(c.state.value.pending.isEmpty())
        c.dismiss(2);c.accept(event(4));assertEquals(4L,c.state.value.current!!.event.localMessageId)
    }
    private fun key(n:Int)=Base64.getEncoder().encodeToString(ByteArray(32) {n.toByte()})
    private fun event(id:Long,peer:Int=1,text:String="same text")=IncomingMessageEvent(key(peer),id,"network-$id",key(peer),text,"1234567890123456789")
    @Test fun currentConversationSuppressionUsesExactXxKey() {
        val c=ForegroundNotificationCoordinator({0});c.foreground(true);c.visibleConversation(key(1));c.accept(event(1))
        assertNull(c.state.value.current);assertEquals(1L,c.state.value.suppressedCurrentChat)
        c.accept(event(2,2));assertEquals(2L,c.state.value.current!!.event.localMessageId)
        c.visibleConversation(key(2));assertNull(c.state.value.current)
        c.visibleConversation(null);c.accept(event(3));assertEquals(3L,c.state.value.current!!.event.localMessageId)
    }
    @Test fun backgroundClearsQueueAndNeverReplaysOnResume() {
        val c=ForegroundNotificationCoordinator({0});c.accept(event(1));c.foreground(true);assertNull(c.state.value.current)
        c.accept(event(2));c.accept(event(3,2));c.foreground(false)
        assertNull(c.state.value.current);assertTrue(c.state.value.pending.isEmpty())
        c.accept(event(4));c.foreground(true);assertNull(c.state.value.current);assertEquals(2L,c.state.value.suppressedBackground)
    }
    @Test fun identicalTextCoalescesByConversationButCountsDistinctInsertedEvents() {
        var time=0L;val c=ForegroundNotificationCoordinator({time});c.foreground(true)
        c.accept(event(1));val expiry=c.state.value.current!!.expiresAt
        time=1000;c.accept(event(2));assertEquals(2,c.state.value.current!!.count);assertEquals(expiry,c.state.value.current!!.expiresAt)
        time=expiry;c.expire();assertNull(c.state.value.current)
        c.accept(event(3));time+=ForegroundNotificationCoordinator.DURATION_MS+1
        c.accept(event(4));assertEquals(1,c.state.value.current!!.count);assertEquals(4L,c.state.value.current!!.event.localMessageId)
    }
    @Test fun fifoOverflowDropsOldestPendingAndCoalescesPendingWithoutReordering() {
        val c=ForegroundNotificationCoordinator({0});c.foreground(true);c.accept(event(1,1))
        for(i in 2..7)c.accept(event(i.toLong(),i))
        assertEquals(5,c.state.value.pending.size);assertEquals(1L,c.state.value.queueDrops)
        assertEquals(listOf(3L,4L,5L,6L,7L),c.state.value.pending.map {it.event.localMessageId})
        c.accept(event(8,4));assertEquals(listOf(3L,8L,5L,6L,7L),c.state.value.pending.map {it.event.localMessageId})
        c.dismiss(1);assertEquals(3L,c.state.value.current!!.event.localMessageId)
        c.dismiss(1);assertEquals(3L,c.state.value.current!!.event.localMessageId)
    }
    @Test fun enteringQueuedConversationSuppressesItsPendingBanner() {
        val c=ForegroundNotificationCoordinator({0});c.foreground(true);c.accept(event(1));c.accept(event(2,2));c.visibleConversation(key(2))
        c.dismiss(1);assertNull(c.state.value.current);assertEquals(1L,c.state.value.suppressedCurrentChat)
    }
    @Test fun previewBoundAndNewCoordinatorStartsEmpty() {
        val c=ForegroundNotificationCoordinator({0});c.foreground(true);c.accept(event(1,text="a\nb\tc"+"x".repeat(10000)))
        assertEquals(160,c.state.value.current!!.event.text.length);assertFalse(c.state.value.current!!.event.text.contains('\n'))
        c.accept(event(2,text="x".repeat(159)+"🚀"));assertEquals(159,c.state.value.current!!.event.text.length)
        assertNull(ForegroundNotificationCoordinator({0}).state.value.current)
    }
    @Test fun locallyKnownDisplayPriorityWithoutRpcOrRemoteNickname() {
        val peer=PeerIdentity(key(1),1)
        val wallet="6fHkwweD2gMMGNXKPvH6tYAVZsL1BW8LadVgPgvVWLcE"
        val p=Provenance(wallet,"test", "1","1",0)
        val direct=Contact(peer,AddressSource.Direct)
        val solana=Contact(peer,AddressSource.Solana,p)
        val seeker=solana.copy(provenance=p.copy(seekerId="x0d.skr"))
        assertEquals("Unknown private contact",notificationTitle(key(1),listOf(direct)))
        assertEquals(shortAddress(wallet),notificationTitle(key(1),listOf(direct,solana)))
        assertEquals("x0d.skr",notificationTitle(key(1),listOf(solana,seeker)))
        assertEquals("Alice",notificationTitle(key(1),listOf(seeker,direct.copy(alias="Alice"))))
        assertEquals("Unknown private contact",notificationTitle(key(2),listOf(seeker)))
    }
    @Test fun oneShotStreamDoesNotReplayOrRetainHistory() {
        val received=mutableListOf<IncomingMessageEvent>();val stream=IncomingMessageEvents(received::add)
        stream.emit(event(1));assertEquals(listOf(event(1)),received)
        assertNull(ForegroundNotificationCoordinator({0}).state.value.current)
    }
}
