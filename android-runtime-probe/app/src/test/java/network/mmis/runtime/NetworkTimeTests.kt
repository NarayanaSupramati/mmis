package network.mmis.runtime

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NetworkTimeTests {
    private val epoch=1789800000000L
    private fun invalid(block:()->Unit) {try {block();fail("Expected rejection")} catch(_:IllegalArgumentException) {}}
    private fun packet():Pair<ByteArray,ByteArray> {
        val request=SntpPacket.request(epoch,SecureRandom())
        val response=ByteArray(48);response[0]=0x24;response[1]=2
        request.copyInto(response,24,40,48)
        SntpPacket.writeTimestamp(response,32,epoch+130)
        SntpPacket.writeTimestamp(response,40,epoch+150)
        return request to response
    }
    private fun parse(request:ByteArray,response:ByteArray,elapsed:Long=100)=SntpPacket.response(response,request,epoch,1000,1000+elapsed,"test")
    @Test fun selectsProviderAtApi33() {
        for(api in 29..32) assertEquals(TimeProviderType.SNTP,timeProviderType(api))
        for(api in 33..36) assertEquals(TimeProviderType.AndroidNetworkTime,timeProviderType(api))
        invalid {timeProviderType(28)}
    }
    @Test fun fourTimestampsAndMonotonicAnchor() {
        val (q,r)=packet();val s=parse(q,r)
        assertEquals(epoch+190,s.epochMs);assertEquals(90L,s.offsetMs)
        assertEquals(80L,s.roundTripMs);assertEquals(1100L,s.elapsedMs)
    }
    @Test fun rejectsInvalidPacketMetadata() {
        val (q,r)=packet()
        for(size in listOf(0,47,513)) invalid {parse(q,r.copyOf(size))}
        for(flags in listOf(0x23,0x14,0xe4)) invalid {parse(q,r.copyOf().also {it[0]=flags.toByte()})}
        for(stratum in listOf(0,16,255)) invalid {parse(q,r.copyOf().also {it[1]=stratum.toByte()})}
        invalid {parse(q,r.copyOf().also {it[24]=(it[24].toInt() xor 1).toByte()})}
        for(pos in listOf(32,40)) invalid {parse(q,r.copyOf().also {it.fill(0,pos,pos+8)})}
    }
    @Test fun rejectsBadTimingAndUncertainty() {
        val (q,r)=packet()
        invalid {parse(q,r,-1)};invalid {parse(q,r,1401)};invalid {parse(q,r,1300)}
        invalid {parse(q,r.copyOf().also {SntpPacket.writeTimestamp(it,40,epoch+129)})}
        invalid {parse(q,r.copyOf().also {SntpPacket.writeTimestamp(it,40,epoch+250)})}
        for(pos in listOf(4,8)) invalid {parse(q,r.copyOf().also {it[pos+1]=3})}
        invalid {parse(q,r.copyOf().also {SntpPacket.writeTimestamp(it,32,946684800000)})}
    }
    @Test fun acceptsV3And2036Rollover() {
        val (q,r)=packet();parse(q,r.copyOf().also {it[0]=0x1c})
        val b=ByteArray(48);val future=2208988800123L
        SntpPacket.writeTimestamp(b,40,future)
        assertEquals(future.toDouble(),SntpPacket.timestamp(b,40),0.001)
    }
    private class Clock(var value:Long=1000)
    private class Fake(private val clock:Clock=Clock()):CachedTimeProvider(TimeProviderType.SNTP,{clock.value},100,200,30) {
        var mono:Long get()=clock.value;set(value) {clock.value=value}
        var next=NetworkTimeSample(100000,1000,"fake",0)
        var failure=false;var calls=0
        override fun obtain():NetworkTimeSample {calls++;if(failure) error("offline");return next}
    }
    @Test fun cachedReadUsesOnlyMonotonicElapsed() {
        val p=Fake();p.refresh();p.mono+=40
        assertEquals(100040,p.nowMs());assertEquals(1,p.calls);assertFalse(p.needsRefresh())
        p.mono+=60;assertTrue(p.needsRefresh());assertTrue(p.isReady())
        p.mono+=101;assertFalse(p.isReady());assertEquals(100201,p.nowMs())
    }
    @Test fun retainsGoodSampleAndRetriesAfterCooldownOrNetworkRecovery() {
        val p=Fake();p.refresh();p.failure=true;p.mono=1100;p.refreshIfStale()
        assertTrue(p.isReady());p.refreshIfStale();assertEquals(2,p.calls)
        p.mono=1130;p.refreshIfStale();assertEquals(3,p.calls)
        p.mono=1201
        try {p.refreshIfStale();fail()} catch(_:IllegalStateException) {}
        assertFalse(p.isReady());p.failure=false;p.next=NetworkTimeSample(100201,1201,"new",0)
        p.networkRestored();p.refreshIfStale();assertTrue(p.isReady());assertEquals("new",p.diagnostics().getString("source"))
    }
    @Test fun coldFailureNeverInventsTime() {
        val p=Fake();p.failure=true
        try {p.refreshIfStale();fail()} catch(_:IllegalStateException) {}
        assertFalse(p.isReady());assertTrue(p.diagnostics().isNull("nowMs"))
        try {p.nowMs();fail()} catch(_:IllegalStateException) {}
    }
    @Test fun fallbackStopsAtFirstValidServer() {
        val tried=mutableListOf<String>()
        val p=SntpNetworkTimeProvider({1000},SntpExchange {s->tried.add(s);if(s=="bad") throw SocketTimeoutException();NetworkTimeSample(epoch,1000,s,0)},listOf("bad","good","unused"))
        p.refresh();assertEquals(listOf("bad","good"),tried);assertEquals(epoch,p.nowMs())
    }
    @Test fun udpTimeoutIsBounded() {
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use {silent->
            val p=UdpSntpExchange({System.nanoTime()/1000000},{epoch},{_,_->InetAddress.getLoopbackAddress()},silent.localPort,50)
            val start=System.nanoTime()
            try {p.sample("local");fail()} catch(_:SocketTimeoutException) {}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000)
        }
    }
    @Test fun wallClockJumpDuringUdpExchangeDoesNotChangeResult() {
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use {server->
            val mono=java.util.concurrent.atomic.AtomicLong(1000)
            val wall=java.util.concurrent.atomic.AtomicLong(epoch)
            val reads=java.util.concurrent.atomic.AtomicInteger()
            val thread=Thread {
                val incoming=DatagramPacket(ByteArray(48),48);server.soTimeout=2000;server.receive(incoming)
                val reply=ByteArray(48);reply[0]=0x24;reply[1]=2;incoming.data.copyInto(reply,24,40,48)
                SntpPacket.writeTimestamp(reply,32,epoch+130);SntpPacket.writeTimestamp(reply,40,epoch+150)
                wall.set(epoch+86400000);mono.set(1100)
                server.send(DatagramPacket(reply,48,incoming.address,incoming.port))
            };thread.start()
            try {
                val exchange=UdpSntpExchange(mono::get,{reads.incrementAndGet();wall.get()},{_,_->InetAddress.getLoopbackAddress()},server.localPort)
                val sample=exchange.sample("loopback")
                assertEquals(epoch+190,sample.epochMs);assertEquals(1,reads.get());assertEquals(1100,sample.elapsedMs)
            } finally {thread.join(2500)}
        }
    }
    @Test fun nativeReadersDoNotWaitForRefreshAndSeeWholeSample() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var first=true
        val p=object:CachedTimeProvider(TimeProviderType.SNTP,{1000},100,200) {
            override fun obtain():NetworkTimeSample {
                if(first) {first=false;return NetworkTimeSample(epoch,1000,"first",0)}
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));return NetworkTimeSample(epoch+500,999,"second",0)
            }
        }
        p.refresh();val pool=Executors.newFixedThreadPool(2)
        try {
            val refresh=pool.submit {p.refresh()};assertTrue(entered.await(2,TimeUnit.SECONDS))
            val reader=pool.submit<Long> {repeat(10000) {assertEquals(epoch,p.nowMs())};p.nowMs()}
            assertEquals(epoch,reader.get(2,TimeUnit.SECONDS).toLong());release.countDown();refresh.get(2,TimeUnit.SECONDS)
            assertEquals(epoch+501,p.nowMs())
        } finally {release.countDown();pool.shutdownNow()}
    }
    @Test fun checkedIntegerBoundaries() {
        assertEquals(Long.MIN_VALUE,checkedLong(BigInteger.valueOf(Long.MIN_VALUE)))
        assertEquals(Long.MAX_VALUE,checkedLong(BigInteger.valueOf(Long.MAX_VALUE)))
        invalid {checkedLong(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE))}
        invalid {checkedLong(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))}
        assertNotEquals("Unknown time",messageTime("1789800000000000000"))
        assertEquals("Unknown time",messageTime("invalid"))
    }
}
