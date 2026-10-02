package network.mmis.runtime

import android.content.Intent
import androidx.activity.ComponentActivity
import org.json.JSONObject

/** Production variants do not parse or dispatch automation intent extras. */
internal object TestIntentBridge {
    fun main(activity:ComponentActivity,intent:Intent,app:ProbeApplication) = Unit
    fun wallet(intent:Intent,run:(String)->Unit) = Unit
    fun registry(intent:Intent,run:(String,JSONObject)->Unit) = Unit
}
