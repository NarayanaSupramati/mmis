package network.mmis.runtime

import android.net.DnsResolver
import android.os.CancellationSignal
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Public API29 DNS, bounded independently of UDP; no unbounded getAllByName worker. */
object AndroidTimeDns {
    fun resolve(host:String,timeoutMs:Int):InetAddress {
        val latch=CountDownLatch(1);val addresses=AtomicReference<List<InetAddress>>()
        val failure=AtomicReference<Exception>();val cancel=CancellationSignal()
        DnsResolver.getInstance().query(null,host,DnsResolver.FLAG_EMPTY,Executor {it.run()},cancel,
            object:DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer:List<InetAddress>,rcode:Int) {
                    if(rcode==0) addresses.set(answer) else failure.set(UnknownHostException("DNS rcode $rcode"))
                    latch.countDown()
                }
                override fun onError(error:DnsResolver.DnsException) {failure.set(error);latch.countDown()}
            })
        try {
            if(!latch.await(timeoutMs.toLong(),TimeUnit.MILLISECONDS)) throw SocketTimeoutException("DNS timeout")
            failure.get()?.let {throw it}
            val list=addresses.get().orEmpty()
            return list.firstOrNull {it is Inet4Address} ?: list.firstOrNull() ?: throw UnknownHostException("No NTP address")
        } finally {cancel.cancel()}
    }
}
