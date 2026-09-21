package network.mmis.runtime

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.solana.mobilewalletadapter.clientlib.*
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client.JsonRpc20RemoteException
import com.solana.mobilewalletadapter.common.ProtocolContract
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.*
import com.funkatronics.encoders.Base58
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Application owner. ActivityResultSender is borrowed only for the duration of a suspend call. */
class SolanaWalletAdapter(context: Context) {
    val network=(context.applicationContext as ProbeApplication).registryNetwork
    private val blockchain get()=when(network.profile) {RegistryProfile.Lab->Solana.Devnet;RegistryProfile.Release->Solana.Mainnet}
    private val authStore=WalletAuthorizationStore(context)
    private val evidence=AtomicFile(File(context.filesDir,"wallet-public-snapshot.json"))
    private var authorization: WalletAuthorization?=authStore.load()
    private val busy=AtomicBoolean(false)
    private val rpc=SolanaRpcClient(context)
    private val events=JSONArray()
    private var state=if(authorization==null) "DISCONNECTED" else "RESTORED"
    @Volatile var snapshot="{}"; private set
    private val published=kotlinx.coroutines.flow.MutableStateFlow("{}")
    val updates:kotlinx.coroutines.flow.StateFlow<String> get()=published
    init { publish("Restored",JSONObject().put("authPresent",authorization!=null)) }

    @Synchronized private fun publish(event:String, detail:JSONObject=JSONObject()) {
        events.put(JSONObject().put("event",event).put("time",System.currentTimeMillis()).put("detail",detail))
        val a=authorization
        snapshot=JSONObject().put("state",state).put("busy",busy.get()).put("chain",network.deployment.chain).put("profile",network.profile.name)
            .put("authPresent",a!=null).put("selected",a?.selected ?: -1)
            .put("accounts",JSONArray(a?.accounts?.map { JSONObject().put("address",it.address).put("publicKeyBase64",WalletProof.b64(it.publicKeyBytes)).put("label",it.label ?: JSONObject.NULL).put("chains",JSONArray(it.chains)).put("features",JSONArray(it.features)) } ?: emptyList<JSONObject>()))
            .put("walletUri",a?.walletUri ?: JSONObject.NULL).put("events",events).toString(2)
        atomicWrite(evidence,snapshot)
        published.value=snapshot
    }
    private fun atomicWrite(file:AtomicFile,value:String) {
        val out=file.startWrite()
        try { out.write(value.toByteArray()); file.finishWrite(out) } catch(e:Exception) { file.failWrite(out); throw e }
    }
    private fun accept(result:AuthorizationResult) {
        val old=authorization
        val accounts=result.accounts.map { SolanaAccount(it.publicKey,it.accountLabel,it.chains?.toList() ?: emptyList(),it.features?.toList() ?: emptyList()) }
        val index=accounts.indexOfFirst { it.address==old?.active?.address }.takeIf { it>=0 } ?: 0
        val fresh=WalletAuthorization(accounts,index,result.authToken,result.walletUriBase?.toString())
        authStore.save(fresh); authorization=fresh; state="AUTHORIZED"
        publish("Authorization",JSONObject().put("tokenPresent",true).put("tokenChanged",old!=null && old.token!=fresh.token).put("accountChanged",old!=null && old.active.address!=fresh.active.address))
    }
    private fun clear() { authStore.clear(); authorization=null; state="DISCONNECTED" }
    fun selectNext() {
        if(!busy.compareAndSet(false,true)) return
        try { authorization?.let { val next=WalletAuthorization(it.accounts,(it.selected+1)%it.accounts.size,it.token,it.walletUri); authStore.save(next); authorization=next } }
        finally { busy.set(false); publish("AccountSelected") }
    }
    fun selectedAddress():String?=authorization?.active?.address
    class AccountMismatch:Exception("WALLET_ACCOUNT_MISMATCH")
    data class RegistrySignature(val signature:String,val signedBytes:ByteArray?,val strategy:String)
    /** Exact message was built from the captured intent by RegistryController. */
    suspend fun signRegistry(sender:ActivityResultSender,intendedWallet:String,tx:Transaction,signOnly:Boolean):RegistrySignature {
        check(busy.compareAndSet(false,true)) { "BUSY" }
        try {
            if(selectedAddress()!=intendedWallet) throw AccountMismatch()
            rpc.verifyGenesis()
            val expectedMessage=tx.message.serialize().copyOf()
            val client=MobileWalletAdapter(DappIdentity.connection()).apply {
                blockchain=this@SolanaWalletAdapter.blockchain; authToken=authorization?.token
            }
            var signed:RegistrySignature?=null
            var accountMismatch=false
            val result=client.transact(sender) { auth ->
                accept(auth)
                if(selectedAddress()!=intendedWallet || auth.accounts.none { SolanaAccount(it.publicKey,null,emptyList(),emptyList()).address==intendedWallet }) {
                    accountMismatch=true; throw AccountMismatch()
                }
                check(tx.message.serialize().contentEquals(expectedMessage))
                val caps=getCapabilities()
                val optional=caps.supportedOptionalFeatures.toList()
                publish("RegistryCapabilities",JSONObject().put("optionalFeatures",JSONArray(optional)).put("signOnlyRequested",signOnly))
                if(signOnly) {
                    check("solana:signTransactions" in optional) { "SIGN_TRANSACTIONS_NOT_ADVERTISED" }
                    val bytes=signTransactions(arrayOf(tx.serialize())).signedPayloads.single()
                    val parsed=Transaction.from(bytes)
                    check(parsed.message.serialize().contentEquals(expectedMessage))
                    check(parsed.signatures.size==1 && WalletProof.verify(expectedMessage,parsed.signatures.single(),authorization!!.active.publicKeyBytes))
                    signed=RegistrySignature(Base58.encodeToString(parsed.signatures.single()),bytes,"advertised signTransactions + app RPC")
                } else {
                    // signAndSendTransactions is a mandatory MWA 2.0 method; legacy boolean is not its feature gate.
                    val signature=signAndSendTransactions(arrayOf(tx.serialize())).signatures.single()
                    signed=RegistrySignature(Base58.encodeToString(signature),null,"MWA signAndSendTransactions")
                }
            }
            if(accountMismatch) throw AccountMismatch()
            if(result is TransactionResult.Failure) throw result.e
            return signed ?: error("WALLET_DID_NOT_SIGN")
        } finally { busy.set(false); publish("RegistryWalletIdle") }
    }
    suspend fun operate(command:String,sender:ActivityResultSender) {
        if(!busy.compareAndSet(false,true)) { publish("BUSY"); return }
        publish("Started",JSONObject().put("operation",command))
        try {
            if(command !in setOf("authorize","reauthorize","proof","transaction","deauthorize","stale")) throw IllegalArgumentException()
            check(network.profile==RegistryProfile.Lab || command !in setOf("proof","transaction","stale")) {"LAB_ONLY_OPERATION"}
            if(command !in setOf("authorize") && authorization==null) { state="AUTHORIZATION_REQUIRED"; publish(state); return }
            val client=MobileWalletAdapter(DappIdentity.connection()).apply {
                blockchain=this@SolanaWalletAdapter.blockchain
                authToken=if(command=="stale") "invalid-test-token" else authorization?.token
            }
            if(command=="deauthorize") {
                val result=client.disconnect(sender)
                // Local logout is unconditional, even when the wallet cannot be reached for revocation.
                clear(); publish("Deauthorized",JSONObject().put("remoteRevoked",result is TransactionResult.Success))
                return
            }
            val result=client.transact(sender) { auth ->
                accept(auth)
                val caps=getCapabilities()
                publish("Capabilities",JSONObject().put("optionalFeatures",JSONArray(caps.supportedOptionalFeatures.toList())).put("transactionVersions",JSONArray(caps.supportedTransactionVersions.toList())).put("signAndSend",caps.supportsSignAndSendTransactions).put("maxMessages",caps.maxMessagesPerSigningRequest).put("maxTransactions",caps.maxTransactionsPerSigningRequest))
                val account=authorization!!.active
                when(command) {
                    "proof" -> {
                        val bytes=WalletProof.canonical(account.address,"task02-proof-001")
                        val signed=signMessagesDetached(arrayOf(bytes),arrayOf(account.publicKeyBytes)).messages.single()
                        val signature=signed.signatures.single()
                        check(signed.addresses.single().contentEquals(account.publicKeyBytes))
                        check(signed.message.contentEquals(bytes))
                        val valid=WalletProof.verify(bytes,signature,account.publicKeyBytes)
                        val bad=bytes.copyOf().apply { this[0]=(this[0].toInt() xor 1).toByte() }
                        val badSig=signature.copyOf().apply { this[0]=(this[0].toInt() xor 1).toByte() }
                        check(valid && !WalletProof.verify(bad,signature,account.publicKeyBytes) && !WalletProof.verify(bytes,badSig,account.publicKeyBytes))
                        publish("Proof",JSONObject().put("message",bytes.toString(Charsets.UTF_8)).put("messageBase64",WalletProof.b64(bytes)).put("address",account.address).put("signatureBase64",WalletProof.b64(signature)).put("verified",valid).put("modifiedMessageVerified",false).put("modifiedSignatureVerified",false))
                    }
                    "transaction" -> {
                        val balance=rpc.balance(account.address); publish("Balance",JSONObject().put("lamports",balance))
                        if(balance<10000) {
                            try { publish("Airdrop",JSONObject().put("signature",rpc.airdrop(account.address))); delay(3000) }
                            catch(e:Exception) { publish("AirdropUnavailable",errorDetail(e)) }
                        }
                        val hash=rpc.blockhash()
                        val key=SolanaPublicKey(account.publicKeyBytes)
                        val instruction=TransactionInstruction(SolanaPublicKey.from("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"),listOf(AccountMeta(key,true,true)),"MMIS Task 02 devnet probe".toByteArray())
                        val tx=Transaction(Message.Builder().addInstruction(instruction).setRecentBlockhash(hash.getString("blockhash")).build())
                        val bytes=signTransactions(arrayOf(tx.serialize())).signedPayloads.single()
                        val signed=Transaction.from(bytes)
                        check(signed.message.serialize().contentEquals(tx.message.serialize()))
                        check(WalletProof.verify(signed.message.serialize(),signed.signatures.single(),account.publicKeyBytes))
                        val sig=Base58.encodeToString(signed.signatures.single())
                        publish("TransactionSigned",JSONObject().put("signature",sig).put("blockhash",hash).put("verified",true).put("bytesBase64",WalletProof.b64(bytes)).put("rpc",rpc.endpoint))
                        try {
                            val submitted=rpc.submit(bytes); check(submitted==sig)
                            publish("Submitted",JSONObject().put("signature",submitted))
                            var confirmed=false
                            for(i in 0 until 20) {
                                delay(1500)
                                val status=rpc.status(sig) as JSONObject
                                val value=status.getJSONArray("value").optJSONObject(0)
                                if(value!=null) {
                                    publish("Confirmation",value)
                                    if(!value.isNull("err")) break
                                    if(value.optString("confirmationStatus") in setOf("confirmed","finalized")) { confirmed=true; break }
                                }
                            }
                            publish("SubmissionFinished",JSONObject().put("confirmed",confirmed).put("balanceAfter",rpc.balance(account.address)))
                        } catch(e:Exception) { if(e is CancellationException) throw e; publish("SubmissionBlocked",errorDetail(e)) }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            when(result) {
                is TransactionResult.Success -> { state="AUTHORIZED"; publish("Succeeded",JSONObject().put("operation",command)) }
                is TransactionResult.NoWalletFound -> { state="WALLET_NOT_FOUND"; publish(state) }
                is TransactionResult.Failure -> {
                    val cause=rootCause(result.e)
                    state=when {
                        cause is InterruptedException || cause is CancellationException -> "AUTHORIZATION_CANCELLED"
                        cause is JsonRpc20RemoteException && cause.code==ProtocolContract.ERROR_AUTHORIZATION_FAILED -> if(command in setOf("reauthorize","stale")) "REAUTHORIZATION_FAILED" else "AUTHORIZATION_FAILED"
                        cause is JsonRpc20RemoteException && cause.code==ProtocolContract.ERROR_NOT_SIGNED -> "SIGNING_REJECTED"
                        else -> "WALLET_OPERATION_FAILED"
                    }
                    if(state=="REAUTHORIZATION_FAILED") { clear(); state="REAUTHORIZATION_FAILED" }
                    publish(state,errorDetail(result.e))
                }
            }
        } catch(e:CancellationException) { state="AUTHORIZATION_CANCELLED"; publish(state); throw e }
        catch(e:Exception) { state="WALLET_OPERATION_FAILED"; publish(state,errorDetail(e)) }
        finally { busy.set(false); publish("Idle") }
    }
    private fun rootCause(e:Throwable):Throwable { var t=e; repeat(8) { t=t.cause ?: return t }; return t }
    private fun errorDetail(e:Exception)=JSONObject().put("type",rootCause(e).javaClass.simpleName).apply {
        if(e is SolanaRpcClient.RpcFailure) put("rpcCode",e.code)
        (rootCause(e) as? JsonRpc20RemoteException)?.let { put("protocolCode",it.code) }
    }
}


