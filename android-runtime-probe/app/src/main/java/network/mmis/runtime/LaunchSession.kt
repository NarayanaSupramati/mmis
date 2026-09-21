package network.mmis.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LaunchState(val processAt:Long,val visibleAt:Long?=null,val readyAt:Long?=null,
    val minimumAt:Long?=null,val dismissedAt:Long?=null,val localFailure:Boolean=false,
    val asset:String="loading",val reducedMotion:Boolean=false,val presentedAt:Long?=null) {
    val active get()=dismissedAt==null
}

/** Process-owned, monotonic clock. Never persisted; no Activity or network dependencies. */
class LaunchSession(private val now:()->Long,val minimumDurationMs:Long=2200,processAt:Long=now()) {
    private val mutable=MutableStateFlow(LaunchState(processAt))
    val state=mutable.asStateFlow()
    @Synchronized fun visible(reduced:Boolean) {
        if(mutable.value.visibleAt==null) mutable.value=mutable.value.copy(visibleAt=now(),reducedMotion=reduced)
        tick()
    }
    @Synchronized fun localReady(failed:Boolean=false) {
        if(mutable.value.readyAt==null) mutable.value=mutable.value.copy(readyAt=now(),localFailure=failed)
        tick()
    }
    @Synchronized fun asset(value:String) { mutable.value=mutable.value.copy(asset=value) }
    @Synchronized fun presented() {
        if(!mutable.value.active && mutable.value.presentedAt==null) mutable.value=mutable.value.copy(presentedAt=now())
    }
    @Synchronized fun tick() {
        var s=mutable.value
        if(!s.active) return
        val start=s.visibleAt ?: return
        if(now()-start>=minimumDurationMs && s.minimumAt==null) s=s.copy(minimumAt=now())
        if(s.minimumAt!=null && s.readyAt!=null) s=s.copy(dismissedAt=now())
        mutable.value=s
    }
}

fun reducedLaunchMotion(animatorScale:Float)=animatorScale<1f
