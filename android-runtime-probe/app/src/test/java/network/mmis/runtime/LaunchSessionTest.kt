package network.mmis.runtime

import org.junit.Assert.*
import org.junit.Test

class LaunchSessionTest {
    @Test fun notificationPresentationWaitsForActualTransitionCompletion() {
        var time=0L;val s=LaunchSession({time});s.visible(false);s.presented();assertNull(s.state.value.presentedAt)
        s.localReady();time=2200;s.tick();assertNull(s.state.value.presentedAt)
        time=2700;s.presented();assertEquals(2700L,s.state.value.presentedAt)
        time=9000;s.presented();assertEquals(2700L,s.state.value.presentedAt)
    }
    @Test fun fastReadinessStillWaitsForFullBrandWindow() {
        var time=10L;val s=LaunchSession({time});s.localReady();s.visible(false)
        time=2209;s.tick();assertTrue(s.state.value.active)
        time=2210;s.tick();assertFalse(s.state.value.active)
        assertEquals(2210L,s.state.value.dismissedAt)
    }
    @Test fun slowLocalStateWaitsButFailureExitsAfterMinimum() {
        var time=0L;val s=LaunchSession({time});s.visible(false);time=3000;s.tick()
        assertTrue(s.state.value.active);s.localReady(failed=true)
        assertFalse(s.state.value.active);assertTrue(s.state.value.localFailure)
    }
    @Test fun failureBeforeMinimumStillShowsBrandAndNoNetworkGateExists() {
        var time=0L;val s=LaunchSession({time});s.visible(false);s.localReady(true)
        assertTrue(s.state.value.active);time=2200;s.tick();assertFalse(s.state.value.active)
    }
    @Test fun recreationContinuesExistingTimelineAndCompletedProcessNeverReplays() {
        var time=0L;val s=LaunchSession({time});s.visible(false);time=1000;s.visible(false)
        assertEquals(0L,s.state.value.visibleAt);s.localReady();time=2200;s.tick()
        time=9000;s.visible(false);assertFalse(s.state.value.active);assertEquals(2200L,s.state.value.dismissedAt)
        assertTrue(LaunchSession({time}).state.value.active)
    }
    @Test fun brokenOrMissingAssetCannotGateLocalReadiness() {
        var time=0L;val s=LaunchSession({time});s.visible(false);s.asset("fallback");s.localReady();time=2200;s.tick()
        assertFalse(s.state.value.active);assertEquals("fallback",s.state.value.asset)
    }
    @Test fun reducedMotionRetainsMinimumWithoutRequiringAnimation() {
        assertTrue(reducedLaunchMotion(0f));assertTrue(reducedLaunchMotion(.5f));assertFalse(reducedLaunchMotion(1f))
        var time=0L;val s=LaunchSession({time});s.visible(true);s.asset("reduced-static");s.localReady();time=2200;s.tick()
        assertFalse(s.state.value.active);assertTrue(s.state.value.reducedMotion)
    }
}
