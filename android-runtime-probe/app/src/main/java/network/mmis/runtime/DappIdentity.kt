package network.mmis.runtime

import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity

/** Stable web identity. DAL verifies package + actual APK certificate, not this display name. */
object DappIdentity {
    const val NAME="Money Moves in Silence"
    const val URI="https://narayanasupramati.github.io"
    const val ICON="mmis-icon.png"
    fun connection()=ConnectionIdentity(Uri.parse(URI),Uri.parse(ICON),NAME)
}
