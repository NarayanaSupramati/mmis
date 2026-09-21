package network.mmis.runtime

import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi

fun createNetworkTimeProvider():CachedTimeProvider = if(Build.VERSION.SDK_INT>=33) {
    AndroidNetworkTimeProvider()
} else {
    check(timeProviderType(Build.VERSION.SDK_INT)==TimeProviderType.SNTP)
    SntpNetworkTimeProvider(SystemClock::elapsedRealtime,
        UdpSntpExchange(SystemClock::elapsedRealtime,System::currentTimeMillis,AndroidTimeDns::resolve))
}

/** Class isolation keeps the API33 symbol off all older execution paths. */
@RequiresApi(33)
class AndroidNetworkTimeProvider:CachedTimeProvider(TimeProviderType.AndroidNetworkTime,SystemClock::elapsedRealtime,300000,300000) {
    override fun obtain():NetworkTimeSample {
        val before=SystemClock.elapsedRealtime()
        val epoch=SystemClock.currentNetworkTimeClock().millis()
        val after=SystemClock.elapsedRealtime()
        // This is a cache read, not an NTP exchange; Android does not expose upstream RTT.
        return NetworkTimeSample(epoch,(before+after)/2,"Android cached network time",epoch-System.currentTimeMillis())
    }
}
