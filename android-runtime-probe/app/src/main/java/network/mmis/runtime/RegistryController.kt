package network.mmis.runtime

import android.content.Context
import android.util.AtomicFile
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Application-owned data survives Activity replacement; no Activity or wallet association is retained. */
class RegistryController(context:Context,private val wallet:SolanaWalletAdapter,private val runtime:XxRuntime) {
    val network=(context.applicationContext as ProbeApplication).registryNetwork
    val deployment=network.deployment
    private val rpc=SolanaRpcClient(context)
    val resolver=RegistryResolver(deployment,rpc::call)
    private val pendingFile=AtomicFile(File(context.filesDir,RegistryStorageNames.pending(deployment)))
    private val publicFile=AtomicFile(File(context.filesDir,"registry-public-snapshot.json"))
    private val busy=AtomicBoolean(false)
    private val restoredResult=runCatching { JSONObject(pendingFile.readFully().toString(Charsets.UTF_8)) }
    private val pendingUnreadable=pendingFile.baseFile.exists() && restoredResult.isFailure
    private val restored:JSONObject?=restoredResult.getOrNull()
    private val pendingNamespaceMismatch=restored!=null && !RegistryStorageNames.matches(restored,deployment)
    private var pending:JSONObject?=restored?.takeUnless {pendingNamespaceMismatch}
    private val events=JSONArray()
    @Volatile var snapshot="{}"; private set
    private val published=kotlinx.coroutines.flow.MutableStateFlow("{}")
    val updates:kotlinx.coroutines.flow.StateFlow<String> get()=published
    init { publish("Restored") }
    private fun write(file:AtomicFile,s:String) { val out=file.startWrite(); try { out.write(s.toByteArray()); file.finishWrite(out) } catch(e:Exception) { file.failWrite(out); throw e } }
    @Synchronized private fun publish(name:String,detail:JSONObject=JSONObject()) {
        events.put(JSONObject().put("event",name).put("time",System.currentTimeMillis()).put("detail",detail))
        snapshot=network.diagnostics().put("busy",busy.get()).put("pendingNamespaceMismatch",pendingNamespaceMismatch).put("pendingUnreadable",pendingUnreadable)
            .put("pending",pending ?: JSONObject.NULL).put("rpcRequests",rpc.requestCount).put("events",events).toString(2)
        write(publicFile,snapshot)
        published.value=snapshot
    }
    private fun state(s:String,detail:JSONObject=JSONObject()) { pending?.put("state",s); pending?.let { write(pendingFile,it.toString()) }; publish(s,detail) }
    private fun clearPending() { pending=null; pendingFile.delete() }
    private fun terminal(s:String,detail:JSONObject) { state(s,detail); clearPending() }
    private fun error(e:Exception)=JSONObject().put("type",e.javaClass.simpleName).apply {
        if(e is SolanaRpcClient.RpcFailure) { put("rpcCode",e.code); put("instructionError",e.instructionError ?: JSONObject.NULL); if(e.instructionError!=null) put("code",RegistryErrors.normalize(e.instructionError)) }
        if(e is SolanaWalletAdapter.AccountMismatch) put("code","WALLET_ACCOUNT_MISMATCH")
    }
    suspend fun operate(operation:String,payload:JSONObject,sender:ActivityResultSender,awaitForeground:suspend ()->Unit) {
        if(!busy.compareAndSet(false,true)) { publish("BUSY"); return }
        try {
            if(operation=="resolve" || operation=="send-solana") {
                val w=payload.getString("wallet")
                val observation=resolver.resolve(w,true)
                publish("Resolved",observation.json())
                if(operation=="send-solana") {
                    val r=observation.result
                    check(r is RegistryResult.Registered) { "ENDPOINT_UNAVAILABLE" }
                    runtime.event("SolanaDiscovery",observation.json())
                    runtime.command("send",JSONObject().put("peer",r.endpoint.descriptor()).put("text",payload.getString("text")))
                }
                return
            }
            check(!pendingNamespaceMismatch) {"PENDING_DEPLOYMENT_MISMATCH"}
            check(!pendingUnreadable) {"PENDING_STORAGE_UNREADABLE"}
            if(operation=="reconcile") { reconcile(); return }
            require(operation in setOf("register","update","close"))
            if(pending!=null) {
                reconcile()
                publish(if(pending!=null) "PENDING_RECONCILIATION_REQUIRED" else "RECONCILED_REVIEW_BEFORE_NEW_MUTATION")
                return // Reconciliation never falls through into a second wallet signature.
            }
            val w=wallet.selectedAddress() ?: error("AUTHORIZATION_REQUIRED")
            val before=resolver.resolve(w,true)
            publish("BeforeMutation",before.json())
            check(before.result is RegistryResult.Registered || before.result==RegistryResult.NotRegistered)
            val descriptor=when(operation) { "close"->null; "update"->payload.getJSONObject("endpoint"); else->JSONObject(runtime.snapshot).getJSONObject("descriptor") }
            val key=descriptor?.getString("signingPublicKey")?.let { Base64.getDecoder().decode(it) } ?: byteArrayOf()
            val token=descriptor?.getLong("dmToken") ?: 0L
            val ix=RegistryProtocol.instruction(deployment,w,operation,key,token)
            val blockhash=rpc.blockhash()
            // beta4 has no feePayer builder API. W is the sole signer and pays fees,
            // so its transaction-wide privilege is writable even for update.
            val feeIx=com.solana.transaction.TransactionInstruction(ix.programId,ix.accounts.mapIndexed { i,a ->
                if(i==0) com.solana.transaction.AccountMeta(a.publicKey,true,true) else a
            },ix.data)
            val tx=Transaction(Message.Builder().addInstruction(feeIx).setRecentBlockhash(blockhash.getString("blockhash")).build())
            val old=(before.result as? RegistryResult.Registered)?.endpoint
            pending=JSONObject().put("namespace",deployment.namespace).put("operation",operation).put("wallet",w).put("pda",before.pda)
                .put("blockhash",blockhash.getString("blockhash")).put("lastValidBlockHeight",blockhash.getLong("lastValidBlockHeight"))
                .put("key",descriptor?.getString("signingPublicKey") ?: "").put("token",token.toString())
                .put("expectedRevision",if(operation=="register") "1" else if(old!=null && old.revision<ULong.MAX_VALUE) (old.revision+1uL).toString() else "overflow")
            state("Built")
            // Laboratory sign-only is explicit, never inferred from deprecated capabilities.
            val signed=wallet.signRegistry(sender,w,tx,payload.optBoolean("signOnly",false))
            pending!!.put("signature",signed.signature).put("strategy",signed.strategy)
            state(if(signed.signedBytes!=null) "WalletSigned" else "Submitted")
            if(signed.signedBytes!=null) {
                // Wallet association can finish before Android resumes the caller.
                // Wait for RESUMED before using its network access; do not re-sign.
                awaitForeground()
                // Write Submitted BEFORE broadcast: transport failure may occur after acceptance.
                state("Submitted")
                try { check(rpc.submit(signed.signedBytes)==signed.signature) }
                catch(e:SolanaRpcClient.RpcFailure) {
                    if(e.instructionError!=null) { terminal("Failed",error(e)); return }; throw e
                }
            }
            reconcile()
        } catch(e:CancellationException) { if(pending!=null) state("OutcomeUnknown"); throw e }
        catch(e:Exception) {
            if(pending!=null && e is SolanaWalletAdapter.AccountMismatch) terminal("Failed",error(e))
            else if(pending!=null) state("OutcomeUnknown",error(e)) else publish("Failed",error(e))
        } finally { busy.set(false); publish("Idle") }
    }
    private suspend fun reconcile() {
        val p=pending ?: return
        check(RegistryStorageNames.matches(p,deployment)) {"PENDING_DEPLOYMENT_MISMATCH"}
        rpc.verifyGenesis()
        val w=p.getString("wallet")
        repeat(50) {
            val sig=p.optString("signature")
            val status=if(sig.isEmpty()) null else (rpc.status(sig) as JSONObject).getJSONArray("value").optJSONObject(0)
            if(status!=null && !status.isNull("err")) { terminal("Failed",JSONObject().put("transactionError",status.get("err")).put("code",RegistryErrors.normalize(status.get("err")))); return }
            val confirmation=status?.optString("confirmationStatus")
            if(confirmation=="confirmed" && p.optString("state")!="Confirmed") state("Confirmed")
            if(confirmation=="finalized") {
                state("Finalized")
                val observed=resolver.resolve(w,true,status.getLong("slot"))
                if(observed.result==RegistryResult.RpcFailure) { state("OutcomeUnknown",observed.json()); return }
                val r=observed.result
                val matches=if(p.getString("operation")=="close") r==RegistryResult.NotRegistered else r is RegistryResult.Registered &&
                    r.endpoint.keyBase64==p.getString("key") && r.endpoint.token.toString()==p.getString("token") && r.endpoint.revision.toString()==p.getString("expectedRevision")
                terminal(if(matches) "AppliedObserved" else "ConflictStateChanged",observed.json().put("signature",sig)); return
            }
            val height=(rpc.call("getBlockHeight",JSONArray().put(JSONObject().put("commitment","finalized"))) as Number).toLong()
            if(height>p.getLong("lastValidBlockHeight") && status==null) {
                val observed=resolver.resolve(w,true)
                if(observed.result==RegistryResult.RpcFailure) { state("OutcomeUnknown",observed.json()); return }
                terminal("ExpiredReconciled",observed.json().put("signature",sig)); return
            }
            delay(1500)
        }
        state("OutcomeUnknown") // elapsed time is not blockhash expiry
    }
}
