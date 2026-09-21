package network.mmis.runtime

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import kotlin.math.ceil
import kotlin.math.roundToLong

object SntpConfig {
    val servers=listOf("time.google.com","time1.google.com","time2.google.com")
    // Cold emulator/system resolver misses exceeded 600ms in API30 acceptance.
    const val DNS_TIMEOUT_MS=1500
    const val UDP_TIMEOUT_MS=1400
    const val MAX_RTT_MS=1000L
    const val REFRESH_MS=15*60*1000L
    const val HARD_STALE_MS=30*60*1000L
}
/** NTPv3/v4 server-response validation and four-timestamp arithmetic. */
object SntpPacket {
    private const val NTP_EPOCH=2208988800L
    private fun u32(b:ByteArray,p:Int):Long=(0..3).fold(0L) {a,n->(a shl 8) or (b[p+n].toLong() and 255)}
    fun timestamp(b:ByteArray,p:Int):Double {
        val seconds=u32(b,p);val fraction=u32(b,p+4)
        require(seconds!=0L || fraction!=0L) {"Zero NTP timestamp"}
        // Resolve 2036 rollover inside the explicitly accepted 2020–2100 window.
        val unfolded=seconds+if(seconds<NTP_EPOCH) 0x100000000L else 0L
        return (unfolded-NTP_EPOCH)*1000.0+fraction*1000.0/4294967296.0
    }
    fun writeTimestamp(b:ByteArray,p:Int,epochMs:Long) {
        val seconds=Math.floorDiv(epochMs,1000)+NTP_EPOCH
        val fraction=Math.floorMod(epochMs,1000)*4294967296L/1000
        for(n in 0..3) {b[p+n]=(seconds ushr (24-8*n)).toByte();b[p+4+n]=(fraction ushr (24-8*n)).toByte()}
    }
    fun request(wallMs:Long,random:SecureRandom):ByteArray=ByteArray(48).also {
        it[0]=0x23;writeTimestamp(it,40,wallMs)
        // Correlation nonce in sub-millisecond fraction bits, not authentication.
        it[46]=random.nextInt(256).toByte();it[47]=random.nextInt(256).toByte()
    }
    fun response(reply:ByteArray,request:ByteArray,t1:Long,sentElapsed:Long,receivedElapsed:Long,source:String):NetworkTimeSample {
        require(reply.size>=48 && reply.size<=512 && request.size==48) {"Invalid NTP packet length"}
        val flags=reply[0].toInt() and 255;val version=(flags ushr 3) and 7
        require(flags and 7==4 && version in 3..4) {"Invalid NTP mode/version"}
        require(flags ushr 6!=3) {"NTP server unsynchronized"}
        require((reply[1].toInt() and 255) in 1..15) {"Invalid stratum or Kiss-o'-Death"}
        require((0..7).all {reply[24+it]==request[40+it]}) {"Originate timestamp mismatch"}
        val t2=timestamp(reply,32);val t3=timestamp(reply,40)
        require(t2 in 1577836800000.0..4102444800000.0 && t3 in 1577836800000.0..4102444800000.0) {"Implausible server epoch"}
        val elapsed=receivedElapsed-sentElapsed
        require(elapsed in 0..SntpConfig.UDP_TIMEOUT_MS.toLong()) {"Invalid elapsed RTT"}
        require(t3>=t2 && t3-t2<=elapsed+2.0) {"Invalid server processing interval"}
        val delay=elapsed-(t3-t2)
        require(delay>=-2.0 && delay<=SntpConfig.MAX_RTT_MS) {"Excessive or negative NTP RTT"}
        val rootDelay=u32(reply,4).toInt()/65536.0*1000
        val dispersion=u32(reply,8)/65536.0*1000
        require(rootDelay in -1000.0..2000.0 && dispersion<=2000) {"Excessive root delay/dispersion"}
        val t4=t1.toDouble()+elapsed // Immune to a wall-clock edit during the exchange.
        val offset=((t2-t1)+(t3-t4))/2.0
        return NetworkTimeSample((t4+offset).roundToLong(),receivedElapsed,source,offset.roundToLong(),
            delay.coerceAtLeast(0.0).roundToLong(),ceil(delay.coerceAtLeast(0.0)/2+dispersion+rootDelay.coerceAtLeast(0.0)/2+1).toLong())
    }
}
fun interface SntpExchange { fun sample(server:String):NetworkTimeSample }
class UdpSntpExchange(private val elapsed:()->Long,private val wall:()->Long,
    private val resolve:(String,Int)->InetAddress,private val port:Int=123,
    private val timeoutMs:Int=SntpConfig.UDP_TIMEOUT_MS):SntpExchange {
    private val random=SecureRandom()
    override fun sample(server:String):NetworkTimeSample {
        val address=resolve(server,SntpConfig.DNS_TIMEOUT_MS)
        return DatagramSocket().use {socket->
            socket.connect(address,port);socket.soTimeout=timeoutMs
            val t1=wall();val start=elapsed();val request=SntpPacket.request(t1,random)
            socket.send(DatagramPacket(request,request.size))
            val received=DatagramPacket(ByteArray(513),513);socket.receive(received)
            SntpPacket.response(received.data.copyOf(received.length),request,t1,start,elapsed(),server)
        }
    }
}
class SntpNetworkTimeProvider(elapsed:()->Long,private val exchange:SntpExchange,
    private val servers:List<String> = SntpConfig.servers
):CachedTimeProvider(TimeProviderType.SNTP,elapsed,SntpConfig.REFRESH_MS,SntpConfig.HARD_STALE_MS) {
    override fun obtain():NetworkTimeSample {
        val failures=mutableListOf<String>()
        for(server in servers) {
            try {return exchange.sample(server)} catch(e:Exception) {failures.add("$server: ${e.javaClass.simpleName}: ${e.message}")}
        }
        throw java.io.IOException("SNTP unavailable: ${failures.joinToString("; ")}")
    }
}
