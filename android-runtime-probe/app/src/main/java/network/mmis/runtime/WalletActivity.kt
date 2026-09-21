package network.mmis.runtime

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.launch

class WalletActivity: ComponentActivity() {
    private val wallet get()=(application as ProbeApplication).wallet
    private lateinit var sender:ActivityResultSender
    private lateinit var text:TextView
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable { override fun run() { if(text.text.toString()!=wallet.snapshot) text.text=wallet.snapshot; handler.postDelayed(this,500) } }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); sender=ActivityResultSender(this)
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,70,20,30) }
        column.addView(TextView(this).apply { text="Money Moves in Silence — ${wallet.network.label}"; textSize=20f })
        fun button(label:String,command:String) { column.addView(Button(this).apply { text=label; setOnClickListener { run(command) } }) }
        button("Connect wallet","authorize"); button("Reauthorize","reauthorize")
        if(wallet.network.profile==RegistryProfile.Lab) {button("Sign proof","proof");button("Sign transaction + submit devnet","transaction")}
        button("Disconnect wallet","deauthorize")
        button("Next authorized account","select"); button("Recreate Activity","recreate")
        text=TextView(this).apply { textSize=11f; setTextIsSelectable(true) }; column.addView(text)
        setContentView(ScrollView(this).apply { addView(column) }); handle(intent)
    }
    private fun run(command:String) {
        when(command) { "recreate" -> recreate(); "select" -> wallet.selectNext(); else -> lifecycleScope.launch { wallet.operate(command,sender) } }
    }
    private fun handle(intent:Intent) {
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE==0) return
        intent.getStringExtra("command")?.let {
            intent.removeExtra("command")
            if(it=="double-authorize") { run("authorize"); run("authorize") }
            else if(it!="wallet") run(it)
        }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
