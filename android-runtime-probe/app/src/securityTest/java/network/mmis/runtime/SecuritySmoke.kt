package network.mmis.runtime

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.solana.mobilewalletadapter.clientlib.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit signed-test APK only. Never packaged into the application. No on-chain transactions. */
class SecuritySmoke:Instrumentation() {
    private var wallet=false
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);wallet=arguments?.getString("wallet")=="true";start()}
    override fun onStart() {
        val out=Bundle()
        try {
            val app=targetContext.applicationContext as ProbeApplication
            check(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE==0)
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            var calls=0
            runOnMainSync {
                val invalid=Intent().putExtra("command","disconnect").putExtra("payload","not-base64")
                TestIntentBridge.main(activity,invalid,app)
                TestIntentBridge.wallet(invalid) { calls++ }
                TestIntentBridge.registry(invalid) { _,_->calls++ }
            }
            check(calls==0)
            out.putBoolean("productionBridgeInert",true)
            // RFC 8032 test vector 1: empty message, independently specified public key/signature.
            fun hex(s:String)=s.chunked(2).map {it.toInt(16).toByte()}.toByteArray()
            val key=hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
            val signature=hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
            check(WalletProof.verify(byteArrayOf(),signature,key))
            check(!WalletProof.verify(byteArrayOf(1),signature,key))
            out.putBoolean("deviceEd25519KnownVector",true)
            if(wallet) {
                val done=CountDownLatch(1);var failure:Throwable?=null
                runOnMainSync {
                    val f=MainActivity::class.java.getDeclaredField("walletSender").apply {isAccessible=true}
                    val sender=f.get(activity) as ActivityResultSender
                    activity.lifecycleScope.launch {
                        try {
                            app.wallet.operate("reauthorize",sender)
                            check(JSONObject(app.wallet.snapshot).getString("state")=="AUTHORIZED")
                            out.putBoolean("mwaReauthorize",true)
                            val client=MobileWalletAdapter(DappIdentity.connection()).apply {blockchain=Solana.Mainnet}
                            val result=client.transact(sender) { auth ->
                                val message="MMIS Task11 off-chain signature verification. No transaction or permission grant.".toByteArray()
                                val publicKey=auth.accounts.first().publicKey
                                val signed=signMessagesDetached(arrayOf(message),arrayOf(publicKey)).messages.single()
                                check(signed.message.contentEquals(message))
                                check(signed.addresses.single().contentEquals(publicKey))
                                check(WalletProof.verify(message,signed.signatures.single(),publicKey))
                                check(!WalletProof.verify(message+byteArrayOf(0),signed.signatures.single(),publicKey))
                                out.putBoolean("mwaDetachedSignatureVerified",true)
                            }
                            check(result is TransactionResult.Success)
                        }catch(e:Throwable){failure=e}finally{done.countDown()}
                    }
                }
                check(done.await(180,TimeUnit.SECONDS)) {"Wallet confirmation timed out"}
                failure?.let {throw it}
            }
            out.putString("result","PASS");finish(Activity.RESULT_OK,out)
        }catch(e:Throwable){out.putString("result","FAIL: ${e.javaClass.simpleName}: ${e.message}");finish(Activity.RESULT_CANCELED,out)}
    }
}
