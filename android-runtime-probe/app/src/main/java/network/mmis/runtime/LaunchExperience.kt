package network.mmis.runtime

import android.provider.Settings
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.*

data class LaunchVisualConfig(val animationRes:Int?=R.raw.mmis_intro,val minimumDurationMs:Long=2200)
object LaunchVisuals {
    val current=LaunchVisualConfig()
    fun forContext(context:android.content.Context)=current.copy(animationRes=context.resources.getIdentifier(
        context.getString(R.string.launch_animation_resource),"raw",context.packageName).takeIf {it!=0})
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable fun LaunchHost(session:LaunchSession,content:@Composable ()->Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val reduced=remember { reducedLaunchMotion(Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)) }
    // Once-per-process timestamps make recreation continue at the same progress, never restart.
    val config=remember {LaunchVisuals.forContext(context)}
    val visibility=remember(session) {MutableTransitionState(state.active)}
    visibility.targetState=state.active
    LaunchedEffect(visibility.isIdle,visibility.currentState) {
        if(visibility.isIdle && !visibility.currentState) session.presented()
    }
    var elapsed by remember { mutableLongStateOf(session.state.value.visibleAt?.let {SystemClock.elapsedRealtime()-it} ?: 0L) }
    LaunchedEffect(session) {
        withFrameNanos { session.visible(reduced) }
        while(session.state.value.active) {
            withFrameNanos { elapsed=SystemClock.elapsedRealtime()-(session.state.value.visibleAt ?: SystemClock.elapsedRealtime());session.tick() }
        }
    }
    BackHandler(enabled=state.presentedAt==null) { }
    MmisTheme { MmisBackground {
        // Precompose safe local navigation behind the intro, without exposing its controls.
        if(state.readyAt!=null) Box(if(state.presentedAt==null) Modifier.clearAndSetSemantics {} else Modifier) { content() }
        AnimatedVisibility(visibleState=visibility,exit=fadeOut(tween(if(reduced) 0 else MmisMotion.FADE_MS))) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope {
                while(true) awaitPointerEvent().changes.forEach {it.consume()}
            } }.semantics { testTagsAsResourceId=true }.testTag("launch-experience")) {
                LaunchExperience(config,elapsed,reduced,session::asset)
            }
        }
    } }
}

@Composable fun LaunchExperience(config:LaunchVisualConfig,elapsed:Long,reduced:Boolean,onAsset:(String)->Unit) {
    MmisBackground {
        Column(Modifier.align(Alignment.Center).padding(36.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            // Static identity is always present, including parse/draw failure or disabled motion.
            Text("MMIS",color=MmisColors.accent,fontSize=18.sp,letterSpacing=8.sp,fontWeight=FontWeight.Medium)
            if(config.animationRes!=null && !reduced) {
                val result=rememberLottieComposition(LottieCompositionSpec.RawRes(config.animationRes))
                LaunchedEffect(result.isSuccess,result.isFailure) { onAsset(if(result.isFailure) "fallback" else if(result.isSuccess) "loaded" else "loading") }
                LottieAnimation(result.value,progress={ (elapsed/(result.value?.duration?.coerceAtLeast(1f) ?: 2200f)).coerceIn(0f,1f) },
                    safeMode=true,modifier=Modifier.size(168.dp).testTag("launch-animation"))
            } else {
                LaunchedEffect(Unit) {onAsset(if(reduced) "reduced-static" else "fallback")}
                Spacer(Modifier.height(64.dp))
            }
            Text("Money Moves\nin Silence",fontSize=36.sp,lineHeight=43.sp,fontWeight=FontWeight.Light,
                color=MmisColors.primaryText,modifier=Modifier.testTag("launch-brand"))
            Spacer(Modifier.height(24.dp))
            Text("A quiet place to connect.",color=MmisColors.secondaryText,fontSize=13.sp)
        }
    }
}
