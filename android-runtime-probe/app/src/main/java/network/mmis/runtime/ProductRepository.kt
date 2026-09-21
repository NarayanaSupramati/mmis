package network.mmis.runtime

import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

fun JSONArray.objects():List<JSONObject> = (0 until length()).map { getJSONObject(it) }
data class ChatMessage(val id:Long,val messageId:String,val peer:String,val text:String,val timestamp:String,val status:Int,val outgoing:Boolean)
data class ChatRow(val peer:PeerIdentity,val title:String,val sourceId:String?,val sourceLabel:String,val messages:List<ChatMessage>)
data class AppState(
    val chats:List<ChatRow> = emptyList(), val contacts:List<Contact> = emptyList(),
    val local:PeerIdentity?=null,val network:PrivateNetwork=PrivateNetwork.Connecting,
    val wallet:String?=null,val walletLabel:String="Not connected",val walletBusy:Boolean=false,
    val binding:BindingState=BindingState.Checking,val listing:RegistryObservation?=null,
    val pendingLabel:String?=null,val registryBusy:Boolean=false,val diagnostics:String="",val loaded:Boolean=false,
    val localEvents:Map<String,List<String>> = emptyMap(),val walletSeekerNames:List<String> = emptyList(),val localError:String?=null
)

/** Resolve from the observed repository snapshot, including a contact saved while an unknown chat is open. */
fun AppState.contact(peerId:String,sourceId:String?):Contact? =
    contacts.find { it.id==sourceId && it.peer.conversationId==peerId }
        ?: if(sourceId.isNullOrBlank()) chats.find { it.peer.conversationId==peerId }?.let { row ->
            contacts.find { it.id==row.sourceId } ?: Contact(row.peer,AddressSource.Direct)
        } else null

/** Application repository: transport owners never depend on Compose or navigation. */
class ProductRepository(private val app:ProbeApplication) {
    val identityRuntime get()=app.runtime
    val registryNetwork get()=app.registryNetwork
    val notifications get()=app.notifications
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val started=AtomicBoolean(false)
    @Volatile private var inForeground=false
    private val seekerRpc=SeekerRpcClient(app)
    private val seeker=MainnetSeekerResolver(seekerRpc::call,DiskSeekerCache(app))
    private val walletNames=MutableStateFlow<SeekerReverseObservation?>(null)
    suspend fun resolveSeeker(name:String,fresh:Boolean=false)=withContext(Dispatchers.IO) {
        seeker.resolveName(name,fresh).also { revision.value++ }
    }
    private val own=MutableStateFlow<RegistryObservation?>(null)
    private val revision=MutableStateFlow(0L)
    private val mutable=MutableStateFlow(AppState())
    val state:StateFlow<AppState> = mutable.asStateFlow()
    private var registryProblem:String?=null
    private var lastRegistryEvent=0L
    private val preferences=app.getSharedPreferences("product-settings",0)
    val fakeWalletSignOnly get()=registryNetwork.profile==RegistryProfile.Lab && preferences.getBoolean("fakewallet-sign-only",false)
    val autoConnect get()=preferences.getBoolean("auto-connect",true)
    fun setFakeWalletSignOnly(value:Boolean) { preferences.edit().putBoolean("fakewallet-sign-only",value).apply();revision.value++ }
    fun setAutoConnect(value:Boolean) { preferences.edit().putBoolean("auto-connect",value).apply();app.runtime.command(if(value) "startup" else "disconnect");revision.value++ }
    fun start() {
        if(!started.compareAndSet(false,true)) return
        // Foreground-only recovery on a validated default network; no timer/polling service.
        val connectivity=app.getSystemService(android.net.ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object:android.net.ConnectivityManager.NetworkCallback() {
            private var validated=false
            override fun onAvailable(network:android.net.Network) {validated=false}
            override fun onLost(network:android.net.Network) {validated=false}
            override fun onCapabilitiesChanged(network:android.net.Network,capabilities:android.net.NetworkCapabilities) {
                val now=capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if(now && !validated && inForeground && autoConnect) app.runtime.command("network-restored")
                validated=now
            }
        })
        scope.launch {
            combine(app.runtime.state,app.wallet.updates,app.registry.updates,app.contacts.changes,own,revision,app.messages.incomingChanges) { Unit }
                .conflate().collect {
                    try { rebuild();app.launchSession.localReady() }
                    catch(e:CancellationException) { throw e }
                    catch(e:Exception) {
                        mutable.value=mutable.value.copy(loaded=true,localError="Local history could not be loaded. Restart the app to try again.")
                        android.util.Log.e("MmisLocalState","Local product initialization failed: ${e.javaClass.simpleName}")
                        app.launchSession.localReady(failed=true)
                    }
                }
        }
        scope.launch {
            app.wallet.updates.map { app.wallet.selectedAddress() }.distinctUntilChanged().collectLatest { w ->
                own.value=null
                if(w!=null) refreshOwn(w)
            }
        }
        scope.launch {
            app.wallet.updates.map { app.wallet.selectedAddress() }.distinctUntilChanged().collectLatest { w ->
                walletNames.value=null;revision.value++
                if(w!=null) {
                    val reverse=withContext(Dispatchers.IO) { seeker.resolveWallet(w) }
                    if(app.wallet.selectedAddress()==w) { walletNames.value=reverse;revision.value++ }
                }
            }
        }
        // Read history is scheduled independently of long initialization/connect work.
        app.runtime.command(if(autoConnect) "startup" else "initialize")
    }
    fun foreground() { inForeground=true;if(started.get() && autoConnect) app.runtime.command("connect") }
    fun background() {inForeground=false}
    fun reconnect() {app.runtime.command("connect")}
    private suspend fun rebuild()=withContext(Dispatchers.IO) {
        val runtime=JSONObject(app.runtime.snapshot);val wallet=JSONObject(app.wallet.snapshot);val registry=JSONObject(app.registry.snapshot)
        val contacts=app.contacts.contacts()
        val messages=app.messages.messages().objects().map { ChatMessage(it.getLong("id"),it.getString("message_id"),it.getString("peer"),it.getString("text"),it.getString("timestamp_ns"),it.getInt("status"),it.getString("direction")=="outbound") }
        val peers=app.messages.conversations().objects().associate { it.getString("pub_key") to PeerIdentity(it.getString("pub_key"),it.getLong("token")) }.toMutableMap()
        val chats=peers.values.map { peer ->
            val contact=contacts.filter { it.peer.key==peer.key }.sortedWith(compareByDescending<Contact> { it.alias.isNotBlank() }.thenByDescending { it.provenance?.seekerId!=null }.thenByDescending {it.source==AddressSource.Solana}.thenBy {it.id}).firstOrNull()
            ChatRow(peer,contact?.title ?: "Unknown private contact",contact?.id,if(contact?.source==AddressSource.Solana) "Found via Solana" else "Private address",
                messages.filter { it.peer==peer.key }.sortedWith(compareBy<ChatMessage> { it.timestamp.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO }.thenBy { it.id }))
        }.sortedByDescending { it.messages.lastOrNull()?.timestamp?.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO }
        val pending=registry.optJSONObject("pending")
        registry.optJSONArray("events")?.objects()?.filter { it.optLong("time")>lastRegistryEvent }?.forEach { event ->
            when(event.getString("event")) {
                "ConflictStateChanged"->registryProblem="ConflictStateChanged"
                "Failed"->registryProblem="Failed"
                "Built","AppliedObserved","ExpiredReconciled"->registryProblem=null
            }
            if(event.getString("event") in setOf("AppliedObserved","ConflictStateChanged","ExpiredReconciled")) {
                val detail=event.getJSONObject("detail")
                if(detail.optString("wallet")==app.wallet.selectedAddress() && detail.has("result")) own.value=observationFromJson(detail)
            }
            lastRegistryEvent=maxOf(lastRegistryEvent,event.optLong("time"))
        }
        val local=runtime.optJSONObject("descriptor")?.let(PeerIdentity::from)
        val stage=runtime.optString("stage")
        val net=when {
            !runtime.isNull("error")->PrivateNetwork.Error
            runtime.optBoolean("ready") && runtime.optBoolean("follower")->PrivateNetwork.Ready
            stage=="Disconnected" || (!autoConnect && local!=null)->PrivateNetwork.Offline
            else->PrivateNetwork.Connecting
        }
        val selected=app.wallet.selectedAddress()
        val walletLabel=when {
            wallet.optBoolean("busy")->"Operation pending"
            wallet.optString("state")=="REAUTHORIZATION_FAILED"->"Authorization expired"
            wallet.optString("state").endsWith("FAILED")->"Wallet unavailable. Try again."
            selected!=null->"Connected: ${shortAddress(selected)}"
            else->"Not connected"
        }
        val pendingState=pending?.optString("state")
        val label=when(pendingState) { "Built"->"Waiting for wallet approval";"WalletSigned"->"Submitting";"Submitted"->"Confirming";"Confirmed"->"Finalizing";"Finalized"->"Updating…";"OutcomeUnknown"->"Transaction status uncertain";else->null }
        val diagnostics=JSONObject().put("xx",runtime).put("wallet",wallet).put("registry",registry).put("seekerDomains",JSONObject().put("network","solana:mainnet").put("genesis",SeekerProtocol.GENESIS).put("rpc",seekerRpc.endpoint).put("last",seeker.last?.json() ?: JSONObject.NULL).put("reverse",seeker.lastReverse?.json() ?: JSONObject.NULL)).put("privateAddress",local?.let(PrivateAddress::encode)).toString(2)
        val next=AppState(chats,contacts,local,net,selected,walletLabel,wallet.optBoolean("busy"),ProductPresentation.binding(local,own.value?.result,pendingState ?: registryProblem),own.value,label,registry.optBoolean("busy"),diagnostics,true)
        mutable.value=next.copy(localEvents=app.contacts.localEvents(),walletSeekerNames=walletNames.value?.takeIf { it.wallet==selected }?.names ?: emptyList())
        // Debug-only sanitized evidence; no secret store or authenticated RPC URL is consulted.
        if(app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) {
            val j=JSONObject().put("network",net.name).put("wallet",selected ?: JSONObject.NULL).put("walletLabel",walletLabel).put("binding",next.binding.name)
                .put("local",local?.json() ?: JSONObject.NULL).put("contacts",JSONArray(contacts.map { it.json() }))
                .put("chats",JSONArray(chats.map { JSONObject().put("key",it.peer.key).put("title",it.title).put("source",it.sourceLabel).put("messageIds",JSONArray(it.messages.map { m->m.messageId })) }))
                .put("seekerLast",seeker.last?.json() ?: JSONObject.NULL).put("seekerReverse",seeker.lastReverse?.json() ?: JSONObject.NULL).put("runtimeOwner",runtime.optString("ownerId")).put("loaded",true)
            val file=android.util.AtomicFile(File(app.filesDir,"product-public-snapshot.json"));val out=file.startWrite()
            try { out.write(j.toString(2).toByteArray());file.finishWrite(out) } catch(e:Exception) { file.failWrite(out);throw e }
        }
    }
    suspend fun refreshOwn(wallet:String?=app.wallet.selectedAddress()) {
        registryProblem=null;revision.value++
        if(wallet!=null) { val o=app.registry.resolver.resolve(wallet,true);if(app.wallet.selectedAddress()==wallet) own.value=o }
    }
    suspend fun lookup(input:AddressInput,progress:(String)->Unit={}):Pair<Contact?,String> = when(input) {
        is AddressInput.Direct-> if(input.peer.usable) Contact(input.peer,AddressSource.Direct) to "Private address available" else null to "This private address cannot receive messages."
        is AddressInput.Solana->{ val o=app.registry.resolver.resolve(input.wallet,true); contactFrom(o) to ProductPresentation.lookup(o.result) }
        is AddressInput.Seeker->{
            val name=resolveSeeker(input.name)
            val found=name.result as? SeekerNameResult.Resolved
            if(found==null) null to seekerMessage(name.result)
            else {
                val heading="${name.name}\n${shortAddress(found.wallet)}\nSeeker ID found"
                progress("$heading\nChecking private messaging availability…")
                val o=app.registry.resolver.resolve(found.wallet,true)
                val c=contactFrom(o)?.let { it.copy(provenance=it.provenance!!.copy(seekerId=name.name,seekerSlot=name.contextSlot,seekerResolvedAt=name.resolvedAt)) }
                c to "$heading\n${if(o.result==RegistryResult.NotRegistered) "Private messaging is not enabled for this wallet." else ProductPresentation.lookup(o.result)}"
            }.also { revision.value++ }
        }
    }
    fun contactFrom(o:RegistryObservation):Contact? {
        val r=o.result as? RegistryResult.Registered ?: return null
        return Contact(PeerIdentity(r.endpoint.keyBase64,r.endpoint.token),AddressSource.Solana,Provenance(o.wallet,app.registry.deployment.namespace,r.endpoint.revision.toString(),o.contextSlot.toString(),o.resolvedAt))
    }
    suspend fun save(contact:Contact)=withContext(Dispatchers.IO) { app.contacts.save(contact) }
    suspend fun openConversation(contact:Contact)=withContext(Dispatchers.IO) {
        app.contacts.save(contact)
        app.messages.ensurePeer(DmDescriptor(contact.peer.key,contact.peer.token))
        revision.update {it+1}
    }
    suspend fun deleteConversation(peer:PeerIdentity)=withContext(Dispatchers.IO) {
        app.messages.deleteConversation(peer.key) { app.notifications.clearConversation(peer.key) }
        app.contacts.clearConversationNotices(peer.key)
        rebuild()
    }
    fun hasMessage(key:String,id:Long)=app.messages.messages().objects().any {it.getString("peer")==key && it.getLong("id")==id}
    suspend fun refreshContact(contact:Contact):RegistryObservation? {
        val p=contact.provenance ?: return null
        if(p.namespace!=app.registry.deployment.namespace) return RegistryObservation(p.wallet,"",RegistryResult.RpcFailure,0,System.currentTimeMillis())
        val o=app.registry.resolver.resolve(p.wallet,true)
        withContext(Dispatchers.IO) { app.contacts.observed(contact,o,p.namespace) }
        return o
    }
    suspend fun notice(contact:Contact):RegistryObservation?=withContext(Dispatchers.IO) { app.contacts.notice(contact.id)?.let(::observationFromJson) }
    suspend fun send(peer:PeerIdentity,text:String)=app.runtime.sendProduct(peer,text)
    suspend fun walletAction(command:String,sender:ActivityResultSender) { app.wallet.operate(command,sender);refreshOwn() }
    fun nextAccount() { app.wallet.selectNext() }
    suspend fun mutate(operation:String,intendedWallet:String?,sender:ActivityResultSender,foreground:suspend ()->Unit) {
        if(operation!="reconcile" && app.wallet.selectedAddress()!=intendedWallet) throw SolanaWalletAdapter.AccountMismatch()
        val p=JSONObject().put("signOnly",fakeWalletSignOnly)
        if(operation=="update") p.put("endpoint",state.value.local?.json() ?: error("Identity unavailable"))
        app.registry.operate(operation,p,sender,foreground)
        // Readback from the controller drives successful state. Refresh does not hide conflicts/errors.
    }
    fun pendingNeedsCheck():Boolean { val j=JSONObject(app.registry.snapshot);return j.optJSONObject("pending")!=null && !j.optBoolean("busy") }
    companion object {
        fun observationFromJson(j:JSONObject):RegistryObservation {
            val r=when(j.getString("result")) {
                "Registered"->{ val e=j.getJSONObject("endpoint"); RegistryResult.Registered(RegistryEndpoint(e.getString("signingPublicKey"),e.getLong("dmToken"),e.getString("revision").toULong())) }
                "NotRegistered"->RegistryResult.NotRegistered;"UnsupportedVersion"->RegistryResult.UnsupportedVersion;"WrongOwner"->RegistryResult.WrongOwner;"Malformed"->RegistryResult.Malformed;else->RegistryResult.RpcFailure
            }
            return RegistryObservation(j.getString("wallet"),j.getString("pda"),r,j.getLong("contextSlot"),j.getLong("resolvedAt"),j.optBoolean("tupleChanged"))
        }
    }
}

