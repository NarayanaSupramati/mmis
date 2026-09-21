package network.mmis.runtime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.Lifecycle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.json.JSONObject

class MainActivity:ComponentActivity() {
    private val app get()=application as ProbeApplication
    private val model:ProductViewModel by viewModels { viewModelFactory { initializer { ProductViewModel(app.product,createSavedStateHandle()) } } }
    private lateinit var walletSender:ActivityResultSender
    override fun onCreate(savedInstanceState:Bundle?) {
        val splash=installSplashScreen()
        super.onCreate(savedInstanceState);walletSender=ActivityResultSender(this)
        splash.setOnExitAnimationListener { it.remove() }
        // Access the model now: startup runs concurrently with the visual presentation.
        val productModel=model
        lifecycleScope.launch { app.launchSession.state.collectLatest { s ->
            if(s.presentedAt!=null) {
                if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) app.notifications.foreground(true)
            }
        } }
        setContent { LaunchHost(app.launchSession) { ProductApp(productModel,
            walletAction={ command->lifecycleScope.launch { model.wallet(command,walletSender) } },
            mutation={ operation,wallet->lifecycleScope.launch { model.mutation(operation,wallet,walletSender) { lifecycle.withResumed {} } } },
            recreateActivity={ recreate() },scanQr={
                val target=model
                if(target.beginScan()) QrScanner(applicationContext).scan(target::scanned)
            }) } }
        app.runtime.event("ActivityCreated",JSONObject().put("owner",app.runtime.ownerId));handle(intent)
    }
    override fun onResume() {
        super.onResume();app.notifications.foreground(app.launchSession.state.value.presentedAt!=null);app.runtime.event("ActivityResumed");app.product.foreground()
        if(app.product.pendingNeedsCheck()) lifecycleScope.launch { model.mutation("reconcile",app.wallet.selectedAddress(),walletSender) { lifecycle.withResumed {} } }
    }
    override fun onPause() { app.product.background();app.notifications.foreground(false);app.runtime.event("ActivityPaused");super.onPause() }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);handle(intent) }
    private fun handle(intent:Intent) {
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE==0) return
        val command=intent.getStringExtra("command") ?: return;intent.removeExtra("command")
        if(command=="recreate") { recreate();return }
        if(command.startsWith("wallet-")) { startActivity(Intent(this,WalletActivity::class.java).putExtra("command",command.removePrefix("wallet-")));return }
        if(command.startsWith("registry-")) { startActivity(Intent(this,RegistryActivity::class.java).putExtra("command",command.removePrefix("registry-")).putExtra("payload",intent.getStringExtra("payload")));return }
        val data=intent.getStringExtra("payload")?.let { JSONObject(String(java.util.Base64.getDecoder().decode(it),Charsets.UTF_8)) } ?: JSONObject()
        app.runtime.command(command,data)
    }
}
