package network.mmis.runtime

import java.net.URI

/** App Links only open the UI; they never authorize wallet or registry operations. */
internal object IncomingLaunch {
    fun accepts(action:String?,data:String?):Boolean {
        if(action!="android.intent.action.VIEW") return true
        return try {
            val uri=URI(data ?: return false)
            uri.scheme=="https" && uri.host=="narayanasupramati.github.io" &&
                uri.rawPath=="/mmis/open" && uri.rawUserInfo==null &&
                uri.port in listOf(-1,443) && uri.rawQuery==null && uri.rawFragment==null
        } catch(_:Exception) { false }
    }
}
