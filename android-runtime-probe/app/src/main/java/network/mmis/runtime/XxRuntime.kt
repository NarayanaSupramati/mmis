package network.mmis.runtime

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import bindings.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

fun interface NetworkTimeProvider { fun nowMs(): Long }
class XxTimeAdapter(private val provider: NetworkTimeProvider) : TimeSource { override fun nowMs()=provider.nowMs() }
class ProbeApplication : Application() {
    val registryNetwork:RegistryNetwork by lazy {
        val profile=RegistryProfile.valueOf(BuildConfig.REGISTRY_PROFILE)
        RegistryNetwork(profile,RegistryDeployment.from(JSONObject(assets.open(profile.assetName).bufferedReader().use {it.readText()})))
    }
    val launchSession=LaunchSession(SystemClock::elapsedRealtime,LaunchVisuals.current.minimumDurationMs,android.os.Process.getStartElapsedRealtime())
    private val notificationScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Default)
    val notifications=ForegroundNotificationCoordinator(SystemClock::elapsedRealtime,notificationScope)
    val incomingMessages=IncomingMessageEvents(notifications::accept)
    val messages:MessageStore by lazy { MessageStore(this,incoming=incomingMessages) }
    val contacts:ContactStore by lazy { ContactStore(this) }
    val product:ProductRepository by lazy { ProductRepository(this) }
    lateinit var runtime: XxRuntime; private set
    lateinit var wallet: SolanaWalletAdapter; private set
    val registry:RegistryController by lazy { RegistryController(this,wallet,runtime) }
    override fun onCreate() {
        super.onCreate();runtime=XxRuntime(this);wallet=SolanaWalletAdapter(this)
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) notificationScope.launch {
            launchSession.state.collect { s ->
                val j=JSONObject().put("pid",android.os.Process.myPid()).put("processAt",s.processAt)
                    .put("visibleAt",s.visibleAt ?: JSONObject.NULL).put("readyAt",s.readyAt ?: JSONObject.NULL)
                    .put("minimumAt",s.minimumAt ?: JSONObject.NULL).put("dismissedAt",s.dismissedAt ?: JSONObject.NULL)
                    .put("presentedAt",s.presentedAt ?: JSONObject.NULL)
                    .put("localFailure",s.localFailure).put("asset",s.asset).put("reducedMotion",s.reducedMotion)
                val file=AtomicFile(File(filesDir,"launch-public-snapshot.json"));val out=file.startWrite()
                try {out.write(j.toString(2).toByteArray());file.finishWrite(out)} catch(e:Exception) {file.failWrite(out)}
            }
        }
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) notificationScope.launch {
            notifications.state.collect { s ->
                val j=JSONObject().put("foreground",s.foreground).put("visibleConversation",s.visibleConversation ?: JSONObject.NULL)
                    .put("accepted",s.accepted).put("shown",s.shown).put("suppressedCurrentChat",s.suppressedCurrentChat)
                    .put("suppressedBackground",s.suppressedBackground).put("queueDrops",s.queueDrops).put("pending",s.pending.size)
                    .put("currentMessageId",s.current?.event?.networkMessageId ?: JSONObject.NULL)
                    .put("currentCount",s.current?.count ?: 0)
                val file=AtomicFile(File(filesDir,"notification-public-snapshot.json"));val out=file.startWrite()
                try {out.write(j.toString(2).toByteArray());file.finishWrite(out)} catch(e:Exception) {file.failWrite(out)}
            }
        }
    }
}

/** Foreground-only experimental owner. All explicit JNI operations use one worker. */
class XxRuntime(private val context: Context) {
    val ownerId=UUID.randomUUID().toString()
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"mmis-runtime") }
    private val root=File(context.filesDir,"mmis").apply { mkdirs() }
    private val secrets=SecretMaterialStore(context)
    private val store=(context.applicationContext as ProbeApplication).messages
    private val events=ArrayList<JSONObject>()
    private val callbackMetrics=CallbackMetrics(::event)
    private val time=createNetworkTimeProvider()
    private val adapter=XxTimeAdapter(time)
    private var cmix: Cmix?=null; private var dm: DMClient?=null
    private var receiver: Receiver?=null; private var callbacks: DmCallbacks?=null
    private var descriptor: DmDescriptor?=null
    private var stage="New"; private var error: String?=null; private var version: String?=null
    private var installedTime=false; private var follower=false; private var healthy=false; private var ready=false
    private var creates=0; private var dmCreates=0
    private var createRequested=false
    private val restoreMarker=File(root,"restore-in-progress")
    val needsIdentity get()=!secrets.hasIdentity()
    private suspend fun <T> serialized(block:()->T):T=kotlinx.coroutines.suspendCancellableCoroutine { c ->
        worker.execute {if(c.isActive) c.resumeWith(runCatching(block))}
    }
    suspend fun createIdentity()=serialized {
        check(needsIdentity && !restoreMarker.exists());createRequested=true
        try {initialize();connect()} finally {publish()}
    }
    suspend fun exportBackup(password:CharArray):ByteArray=serialized {
        check(!restoreMarker.exists());val client=checkNotNull(dm) {"Initialize the messaging identity first."}
        val peer=PeerIdentity(descriptor!!.publicKeyBase64,descriptor!!.token)
        val native=client.exportPrivateIdentity(String(password))
        val imported=Bindings.importPrivateIdentity(String(password),native.copyOf())
        try {
            IdentityBackup.validateIdentity(imported,peer)
            val payload=JSONObject().put("format",IdentityBackup.FORMAT).put("version",1).put("xxdkVersion","4.7.9")
                .put("createdAt",java.time.Instant.now().toString()).put("signingPublicKey",peer.key).put("dmToken",peer.token)
                .put("privateAddress",PrivateAddress.encode(peer)).put("xxIdentity",native.toString(Charsets.UTF_8))
                .put("history",MigrationHistory.export(store,(context.applicationContext as ProbeApplication).contacts))
            IdentityBackup.encrypt(payload,password)
        } finally {imported.fill(0);native.fill(0)}
    }
    suspend fun inspectBackup(bytes:ByteArray,password:CharArray):PeerIdentity=serialized {
        val payload=IdentityBackup.decrypt(bytes,password);val peer=IdentityBackup.validatePayload(payload)
        val identity=Bindings.importPrivateIdentity(String(password),payload.getString("xxIdentity").toByteArray(Charsets.UTF_8))
        try {IdentityBackup.validateIdentity(identity,peer);MigrationHistory.validate(payload.getJSONObject("history"));peer} finally {identity.fill(0)}
    }
    suspend fun restoreBackup(bytes:ByteArray,password:CharArray)=serialized {
        check(needsIdentity && dm==null && cmix==null && !createRequested && !restoreMarker.exists()) {"Restore requires a fresh identity state."}
        check(!File(root,"cmix").exists()) {"Existing native state is retained; restore was refused."}
        val payload=IdentityBackup.decrypt(bytes,password);val peer=IdentityBackup.validatePayload(payload)
        val identity=Bindings.importPrivateIdentity(String(password),payload.getString("xxIdentity").toByteArray(Charsets.UTF_8))
        try {
            IdentityBackup.validateIdentity(identity,peer)
            val contacts=(context.applicationContext as ProbeApplication).contacts
            MigrationHistory.validate(payload.getJSONObject("history"));check(MigrationHistory.isEmpty(store,contacts))
            atomicWrite(restoreMarker,byteArrayOf(1))
            MigrationHistory.restore(payload.getJSONObject("history"),store,contacts)
            secrets.installIdentity(identity)
            // Public invariant persists across process restarts and is rechecked against actual JNI.
            atomicWrite(File(root,"restored-endpoint.json"),peer.json().toString().toByteArray())
            check(restoreMarker.delete())
            descriptor=DmDescriptor(peer.key,peer.token);stage="Restored";publish()
            peer
        } finally {identity.fill(0)}
    }
    @Volatile var snapshot="{}"; private set
    private val published=kotlinx.coroutines.flow.MutableStateFlow("{}")
    val state:kotlinx.coroutines.flow.StateFlow<String> get()=published
    init { worker.execute { publish() } }
    fun event(name: String, data: JSONObject=JSONObject()) {
        val row=JSONObject().put("event",name).put("atMs",System.currentTimeMillis()).put("elapsedMs",SystemClock.elapsedRealtime()).put("thread",Thread.currentThread().name).put("data",data)
        synchronized(events) { events.add(row); if(events.size>500) events.removeAt(0) }
        android.util.Log.i("MMIS",row.toString())
        worker.execute { publish() }
    }
    private fun publish() {
        val value=JSONObject().put("ownerId",ownerId).put("pid",android.os.Process.myPid()).put("stage",stage).put("error",error ?: JSONObject.NULL)
            .put("version",version ?: JSONObject.NULL).put("descriptor",descriptor?.json() ?: JSONObject.NULL)
            .put("follower",follower).put("healthy",healthy).put("ready",ready).put("cmixCreates",creates).put("dmCreates",dmCreates)
            .put("time",time.diagnostics())
            .put("events",synchronized(events) { JSONArray(events.toList()) }).put("messages",store.messages()).put("conversations",store.conversations())
        snapshot=value.toString()
        published.value=snapshot
        atomicWrite(File(context.filesDir,"public-snapshot.json"),snapshot.toByteArray())
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val atom=AtomicFile(file); val stream=atom.startWrite()
        try { stream.write(bytes); atom.finishWrite(stream) } catch(e: Exception) { atom.failWrite(stream); throw e }
    }
    fun command(name: String, data: JSONObject=JSONObject()) { worker.execute {
        try {
            if(name!="snapshot" && name!="time-check") error=null
            when(name) {
                "startup" -> { initialize(); connect() }
                "initialize" -> initialize()
                "connect" -> { initialize();connect() }
                "network-restored" -> { time.networkRestored();initialize();connect() }
                "disconnect" -> disconnect()
                "send" -> send(data)
                "snapshot" -> Unit
                "time-check" -> if(installedTime) { time.refreshIfStale(); event("TIME_RESUME_CHECK",time.diagnostics()) }
                else -> error("Unknown command")
            }
        } catch(e: Throwable) {
            error="$stage: ${e.javaClass.simpleName}: ${e.message}"
            event("error",JSONObject().put("code",stage).put("detail",error))
        } finally { publish() }
    } }
    private fun initialize() {
        check(!restoreMarker.exists()) {"An interrupted restore needs recovery; native startup is blocked."}
        if(needsIdentity && !createRequested) {stage="AwaitingIdentity";return}
        if(dm!=null) { event("alreadyInitialized"); return }
        check(cmix==null) { "Partially initialized runtime: restart process; persisted state is retained" }
        stage="JNI_LOAD_FAILED"; version=Bindings.getVersion(); check(version=="4.7.9"); Bindings.logLevel(4)
        event("JNI_LOADED",JSONObject().put("version",version).put("abi",android.os.Build.SUPPORTED_ABIS[0]).put("library","libgojni.so"))
        stage="TIME_NOT_READY"
        time.refreshIfStale()
        if(!installedTime) { Bindings.setTimeSource(adapter); installedTime=true; event("TIME_INSTALLED",time.diagnostics()) }
        val state=File(root,"cmix"); val passwordFile=File(root,"storage-secret.bin")
        stage="STORAGE_FAILED"
        check(!state.exists() || passwordFile.exists()) { "Existing cMix state without storage secret; refusing regeneration" }
        val password=secrets.readOrCreate("storage-secret.bin",32) { ByteArray(32).also { SecureRandom().nextBytes(it) } }
        if(!state.exists()) {
            stage="NDF_FAILED"; event("NDF_DOWNLOAD_STARTED")
            val cert=context.resources.openRawResource(R.raw.mainnet).bufferedReader().use { it.readText() }
            val ndf=Bindings.downloadAndVerifySignedNdfWithUrl("https://elixxir-bins.s3.us-west-1.amazonaws.com/ndf/mainnet.json",cert)
            event("NDF_SIGNATURE_VERIFIED",JSONObject().put("bytes",ndf.size))
            // Both native bindings consume/zero their byte[] argument (copyAndClear).
            stage="CMIX_CREATE_FAILED"; Bindings.newCmix(ndf.toString(Charsets.UTF_8),state.absolutePath,password.copyOf(),""); creates++; event("CMIX_CREATED")
        }
        stage="CMIX_LOAD_FAILED"; val net=try { Bindings.loadCmix(state.absolutePath,password.copyOf(),Bindings.getDefaultCMixParams()) } finally { password.fill(0) }; cmix=net; event("CMIX_LOADED")
        stage="STORAGE_FAILED"
        val identityFile=File(root,"identity.bin"); val existed=identityFile.exists()
        val identity=secrets.readOrCreate("identity.bin",97) { Bindings.generateChannelIdentity(net.id) }
        // getPublicKey is the ECDH transport key. Descriptor/callbacks/sendText use Ed25519.
        val signingKey=b64(identity.copyOfRange(65,97))
        receiver=Receiver(store,signingKey,callbackMetrics,::event)
        callbacks=object: DmCallbacks { override fun eventUpdate(eventType: Long, jsonData: ByteArray?) {
            callbackMetrics.measure("eventUpdate:$eventType") { event("dmEvent",JSONObject().put("type",eventType).put("bytes",jsonData?.size ?: 0)) }
        } }
        stage="DM_CREATE_FAILED"
        val notifications=Bindings.loadNotifications(net.id)
        dm=Bindings.newDMClient(net.id,notifications.id,identity,object: DMReceiverBuilder {
            override fun build(path: String?): DMReceiver=receiver!!
        },callbacks!!)
        dmCreates++
        val publicIdentity=dm!!.identity
        check(publicIdentity.size==33 && b64(publicIdentity.copyOfRange(1,33))==signingKey)
        descriptor=DmDescriptor(signingKey,dm!!.token)
        File(root,"restored-endpoint.json").takeIf {it.exists()}?.let {
            check(PeerIdentity.from(JSONObject(it.readText()))==PeerIdentity(signingKey,dm!!.token)) {"Restored identity mismatch; connection is blocked."}
        }
        dm!!.setNickname("MMIS Android")
        event("DM_CREATED",JSONObject().put("identityReused",existed).put("descriptor",descriptor!!.json()).put("transportKey",b64(dm!!.publicKey)))
        stage="Initialized"
    }
    private fun connect() {
        if(needsIdentity && !createRequested) {stage="AwaitingIdentity";return}
        verifyRestoredEndpoint()
        check(dm!=null) { "Initialize first" }
        stage="TIME_NOT_READY"; time.refreshIfStale(); event("TIME_FRESHNESS_CHECK",time.diagnostics())
        if(ready && cmix!!.readyToSend()) {stage="ReadyToSend";return}
        ready=false
        val net=cmix!!; val start=SystemClock.elapsedRealtime()
        stage="NETWORK_START_FAILED"
        if(!follower) { net.startNetworkFollower(5000); follower=true; event("FollowerStarted"); publish() }
        stage="NETWORK_NOT_READY"; healthy=net.waitForNetwork(30000)
        event("NetworkHealth",JSONObject().put("healthy",healthy)); publish()
        val until=SystemClock.elapsedRealtime()+90000
        while(!ready && SystemClock.elapsedRealtime()<until) { ready=net.readyToSend(); if(!ready) Thread.sleep(1000) }
        check(ready) { "ReadyToSend timed out" }
        healthy=net.isHealthy; stage="ReadyToSend"; event("ReadyToSend",JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-start))
    }
    private fun disconnect() {
        if(follower) { stage="NETWORK_STOP_FAILED"; cmix!!.stopNetworkFollower(); follower=false; healthy=false; ready=false; event("FollowerStopped") }
        stage=if(dm==null) "New" else "Disconnected"
    }
    suspend fun sendProduct(peer:PeerIdentity,text:String):Boolean = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        worker.execute {
            if(!continuation.isActive) return@execute
            val success=try { error=null;send(JSONObject().put("peer",peer.json()).put("text",text));true }
            catch(e:Exception) { error="SEND_FAILED: ${e.javaClass.simpleName}";event("error",JSONObject().put("code","SEND_FAILED"));false }
            finally { publish() }
            if(continuation.isActive) continuation.resumeWith(Result.success(success))
        }
    }
    private fun send(data: JSONObject) {
        verifyRestoredEndpoint()
        stage="TIME_NOT_READY"; time.refreshIfStale()
        stage="SEND_FAILED"; check(ready && follower && cmix!!.readyToSend()) { "Network not ready" }
        val peer=DmDescriptor.parse(data.getJSONObject("peer").toString()); val text=data.getString("text")
        require(text.isNotBlank()); store.ensurePeer(peer)
        val result=dm!!.sendText(Base64.decode(peer.publicKeyBase64,Base64.DEFAULT),tokenBits(peer.token),text,0,Bindings.getDefaultCMixParams())
        // Keep original JSON text so JS evidence cannot round int64/uint64 values.
        val raw=result.toString(Charsets.UTF_8); JSONObject(raw)
        event("SEND_REPORT",JSONObject().put("text",text).put("peer",peer.json()).put("jniToken",tokenBits(peer.token)).put("raw",raw))
        stage="ReadyToSend"
    }
    private fun verifyRestoredEndpoint() {
        check(!restoreMarker.exists())
        File(root,"restored-endpoint.json").takeIf {it.exists()}?.let {
            val expected=PeerIdentity.from(JSONObject(it.readText()))
            check(dm!=null && descriptor!=null && expected==PeerIdentity(descriptor!!.publicKeyBase64,dm!!.token)) {"Restored endpoint mismatch; network operations blocked."}
        }
    }
}
