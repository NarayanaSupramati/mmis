package network.mmis.runtime

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Base64

class RegistryActivity:ComponentActivity() {
    private val registry get()=(application as ProbeApplication).registry
    private lateinit var sender:ActivityResultSender
    private lateinit var text:TextView
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable { override fun run() { if(text.text.toString()!=registry.snapshot) text.text=registry.snapshot; handler.postDelayed(this,500) } }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); sender=ActivityResultSender(this)
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,70,20,30) }
        column.addView(TextView(this).apply { text="MMIS Messaging Registry — ${registry.network.label}"; textSize=20f })
        val address=EditText(this).apply { hint="Recipient Solana address" }; column.addView(address)
        val message=EditText(this).apply { hint="Private message" }; column.addView(message)
        val endpoint=EditText(this).apply { hint="Explicit replacement endpoint JSON (update)" }; column.addView(endpoint)
        val signOnly=CheckBox(this).apply { text="Lab fakewallet: sign only, submit via app RPC" }; column.addView(signOnly)
        fun button(label:String,operation:String) { column.addView(Button(this).apply { text=label; setOnClickListener {
            try { val p=JSONObject().put("wallet",address.text.toString()).put("text",message.text.toString()).put("signOnly",signOnly.isChecked)
                if(operation=="update") p.put("endpoint",DmDescriptor.parse(endpoint.text.toString()).json())
                run(operation,p)
            } catch(_:Exception) { Toast.makeText(this@RegistryActivity,"Check address or endpoint",Toast.LENGTH_SHORT).show() }
        } }) }
        button("Register current xx endpoint","register"); button("Resolve address","resolve"); button("Send to Solana address","send-solana")
        button("Update to explicit endpoint","update"); button("Close my registration","close"); button("Reconcile pending transaction","reconcile")
        text=TextView(this).apply { textSize=11f; setTextIsSelectable(true) }; column.addView(text)
        setContentView(ScrollView(this).apply { addView(column) }); handle(intent)
    }
    private fun run(operation:String,payload:JSONObject) { lifecycleScope.launch { registry.operate(operation,payload,sender) { lifecycle.withResumed {} } } }
    private fun handle(intent:Intent) {
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE==0) return
        val operation=intent.getStringExtra("command") ?: return; intent.removeExtra("command")
        val payload=intent.getStringExtra("payload")?.let { JSONObject(String(Base64.getDecoder().decode(it),Charsets.UTF_8)) } ?: JSONObject()
        run(operation,payload)
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
