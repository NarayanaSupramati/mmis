package network.mmis.runtime

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import org.json.JSONObject
import java.util.Base64

/** Laboratory automation only; excluded from Release and Hackathon source sets. */
internal object TestIntentBridge {
    fun main(activity:ComponentActivity,intent:Intent,app:ProbeApplication) {
        if(activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE==0) return
        val command=intent.getStringExtra("command") ?: return
        intent.removeExtra("command")
        try {
            when {
                command=="recreate" -> activity.recreate()
                command.startsWith("wallet-") -> activity.startActivity(Intent(activity,WalletActivity::class.java).putExtra("command",command.removePrefix("wallet-")))
                command.startsWith("registry-") -> activity.startActivity(Intent(activity,RegistryActivity::class.java).putExtra("command",command.removePrefix("registry-")).putExtra("payload",intent.getStringExtra("payload")))
                command in setOf("startup","initialize","connect","network-restored","disconnect","send","snapshot","time-check") -> app.runtime.command(command,payload(intent))
            }
        } catch(_:Exception) { app.runtime.event("InvalidDebugIntent") }
    }
    fun wallet(intent:Intent,run:(String)->Unit) {
        val command=intent.getStringExtra("command") ?: return
        intent.removeExtra("command")
        if(command=="double-authorize") {run("authorize");run("authorize")}
        else if(command in setOf("authorize","reauthorize","deauthorize","proof","transaction","select","recreate")) run(command)
    }
    fun registry(intent:Intent,run:(String,JSONObject)->Unit) {
        val command=intent.getStringExtra("command") ?: return
        intent.removeExtra("command")
        if(command !in setOf("register","resolve","send-solana","update","close","reconcile")) return
        try {run(command,payload(intent))} catch(_:Exception) { /* Invalid laboratory payload is ignored. */ }
    }
    private fun payload(intent:Intent):JSONObject {
        val raw=intent.getStringExtra("payload") ?: return JSONObject()
        require(raw.length<=65536)
        return JSONObject(String(Base64.getDecoder().decode(raw),Charsets.UTF_8))
    }
}
