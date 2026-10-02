package network.mmis.runtime

import org.junit.Assert.*
import org.junit.Test

class IncomingLaunchTest {
    private val view="android.intent.action.VIEW"
    @Test fun launcherAndExplicitLaunchRemainSupported() {
        assertTrue(IncomingLaunch.accepts("android.intent.action.MAIN",null))
        assertTrue(IncomingLaunch.accepts(null,null))
    }
    @Test fun canonicalLinkIsAccepted() {
        assertTrue(IncomingLaunch.accepts(view,"https://narayanasupramati.github.io/mmis/open"))
        assertTrue(IncomingLaunch.accepts(view,"https://narayanasupramati.github.io:443/mmis/open"))
    }
    @Test fun untrustedOrAmbiguousViewUrisAreRejected() {
        for(uri in listOf(null,"not a uri","http://narayanasupramati.github.io/mmis/open",
            "https://evil.test/mmis/open","https://narayanasupramati.github.io.evil.test/mmis/open",
            "https://evil@narayanasupramati.github.io/mmis/open","https://narayanasupramati.github.io:444/mmis/open",
            "https://narayanasupramati.github.io/mmis/open/","https://narayanasupramati.github.io/mmis/%6fpen",
            "https://narayanasupramati.github.io/mmis/open?command=send","https://narayanasupramati.github.io/mmis/open#sign")) {
            assertFalse(uri,IncomingLaunch.accepts(view,uri))
        }
    }
}
