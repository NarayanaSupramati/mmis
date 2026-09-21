package network.mmis.runtime
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Shared across receiver and DmCallbacks to observe overlap, without serializing their work. */
class CallbackMetrics(private val event: (String,JSONObject)->Unit) {
    private val active=AtomicInteger()
    fun <T> measure(type: String, work: ()->T): T {
        val start=SystemClock.elapsedRealtimeNanos(); val overlap=active.incrementAndGet()
        try { return work() }
        finally {
            val end=SystemClock.elapsedRealtimeNanos(); active.decrementAndGet()
            event("callback",JSONObject().put("type",type).put("thread",Thread.currentThread().name)
                .put("overlap",overlap).put("durationUs",(end-start)/1000)
                .put("startElapsedNs",start.toString()).put("endElapsedNs",end.toString()))
        }
    }
}
