package network.mmis.runtime

import org.json.JSONObject

enum class TimeProviderType { AndroidNetworkTime, SNTP }
fun timeProviderType(api:Int):TimeProviderType { require(api>=29);return if(api>=33) TimeProviderType.AndroidNetworkTime else TimeProviderType.SNTP }
data class NetworkTimeSample(val epochMs:Long,val elapsedMs:Long,val source:String,
    val offsetMs:Long,val roundTripMs:Long?=null,val uncertaintyMs:Long?=null)
private data class TimeCacheState(val sample:NetworkTimeSample?=null,val lastError:String?=null,
    val attempts:Int=0,val successes:Int=0,val lastAttemptElapsed:Long?=null)

/** One immutable volatile sample for Go callbacks; no I/O or refresh mutex on reads. */
abstract class CachedTimeProvider(val providerType:TimeProviderType,private val elapsed:()->Long,
    val refreshAfterMs:Long,val hardStaleMs:Long,private val retryAfterMs:Long=30000):NetworkTimeProvider {
    @Volatile private var state=TimeCacheState()
    protected abstract fun obtain():NetworkTimeSample
    private fun age(s:NetworkTimeSample)=elapsed()-s.elapsedMs
    fun isReady():Boolean=state.sample?.let {age(it) in 0..hardStaleMs} ?: false
    fun needsRefresh():Boolean=state.sample?.let {age(it) !in 0 until refreshAfterMs} ?: true
    @Synchronized fun networkRestored() {state=state.copy(lastAttemptElapsed=null)}
    override fun nowMs():Long {
        val s=state.sample ?: throw IllegalStateException("Network time has no sample")
        // Live native workers can extrapolate stale time; new starts/sends are readiness-gated.
        // Never substitute wall time or throw from a live callback merely due to sample expiry.
        return s.epochMs+(elapsed()-s.elapsedMs)
    }
    @Synchronized fun refresh() {
        val old=state;state=old.copy(attempts=old.attempts+1,lastAttemptElapsed=elapsed())
        try {
            val sample=obtain();check(elapsed()-sample.elapsedMs in 0..hardStaleMs) {"Invalid monotonic anchor"}
            state=state.copy(sample=sample,lastError=null,successes=old.successes+1)
        } catch(e:Exception) {state=state.copy(lastError="${e.javaClass.simpleName}: ${e.message}");throw e}
    }
    fun refreshIfStale() {
        if(!needsRefresh()) return
        val s=state
        if(s.lastError!=null && s.lastAttemptElapsed?.let {elapsed()-it in 0 until retryAfterMs}==true) {
            check(isReady()) {"Network time retry cooldown: ${s.lastError}"};return
        }
        try {refresh()} catch(e:Exception) {if(!isReady()) throw e}
        check(isReady()) {"Network time is stale"}
    }
    fun diagnostics():JSONObject {
        val s=state;val a=s.sample;val age=a?.let(::age)
        return JSONObject().put("providerType",providerType.name).put("source",a?.source ?: providerType.name)
            .put("ready",a!=null && age!! in 0..hardStaleMs).put("sampleAgeMs",age ?: JSONObject.NULL)
            .put("lastSuccessfulSync",a?.epochMs ?: JSONObject.NULL).put("sampleEpochMs",a?.epochMs ?: JSONObject.NULL)
            .put("sampleElapsedMs",a?.elapsedMs ?: JSONObject.NULL).put("roundTripDelayMs",a?.roundTripMs ?: JSONObject.NULL)
            .put("sampleUncertaintyMs",a?.uncertaintyMs ?: JSONObject.NULL).put("estimatedOffsetMs",a?.offsetMs ?: JSONObject.NULL)
            .put("offsetMs",a?.offsetMs ?: JSONObject.NULL).put("lastError",s.lastError ?: JSONObject.NULL)
            .put("refreshCount",s.attempts).put("successfulRefreshCount",s.successes)
            .put("refreshAfterMs",refreshAfterMs).put("hardStaleMs",hardStaleMs).put("retryAfterMs",retryAfterMs)
            .put("nowMs",a?.let {it.epochMs+(elapsed()-it.elapsedMs)} ?: JSONObject.NULL)
            .put("freshness",if(providerType==TimeProviderType.AndroidNetworkTime) "App sample age; Android upstream NTP age is not exposed" else "In-memory SNTP sample; not persisted or authenticated")
    }
}
