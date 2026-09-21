@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
package network.mmis.runtime

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

private val palette=mmisPalette

/** One flat top-level stack. Drafts/lookup live in the Activity ViewModel, not saved tab stacks. */
private fun NavHostController.navigateTopLevel(route:String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState=false }
        launchSingleTop=true
        restoreState=false
    }
}

@Composable fun ProductApp(vm:ProductViewModel,walletAction:(String)->Unit,mutation:(String,String?)->Unit,recreateActivity:()->Unit,scanQr:()->Unit) {
    val state by vm.appState.collectAsStateWithLifecycle();val ui by vm.ui.collectAsStateWithLifecycle()
    val notifications by vm.repository.notifications.state.collectAsStateWithLifecycle()
    val nav=rememberNavController();val entry by nav.currentBackStackEntryAsState();val route=entry?.destination?.route ?: "chats"
    fun chat(contact:Contact) { nav.navigate("chat/${contact.peer.conversationId}?source=${Uri.encode(contact.id)}") { launchSingleTop=true } }
    val conversation=route.startsWith("chat/")
    val visibleKey=if(conversation) entry?.arguments?.getString("peer")?.let {
        runCatching {java.util.Base64.getEncoder().encodeToString(java.util.Base64.getUrlDecoder().decode(it))}.getOrNull()
    } else null
    SideEffect { vm.repository.notifications.visibleConversation(visibleKey) }
    MmisTheme { MmisBackground {
        Scaffold(containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,modifier=Modifier.semantics { testTagsAsResourceId=true },
            topBar={ TopAppBar(title={ Column {
                Text(when {conversation->"Conversation";route=="chats"->"Money Moves in Silence";route=="new"->"New message";route=="identity"->"Identity & wallet";route=="diagnostics"->"Diagnostics";else->"Settings"},fontSize=if(route=="chats") 19.sp else 22.sp,fontWeight=FontWeight.SemiBold)
                if(route=="chats") Text("Your conversations, privately.",style=MaterialTheme.typography.labelMedium,color=palette.onSurfaceVariant)
            } },colors=TopAppBarDefaults.topAppBarColors(containerColor=Color.Transparent),navigationIcon={ if(conversation || route=="diagnostics") IconButton(onClick={nav.popBackStack()},modifier=Modifier.testTag("go-back")) { Icon(Icons.Default.ArrowBack,"Back") } }) },
            bottomBar={ if(!conversation && route!="diagnostics") NavigationBar(containerColor=palette.surface) {
                listOf(Triple("chats","Chats",Icons.Default.Home),Triple("new","New",Icons.Default.Add),Triple("identity","Identity",Icons.Default.Person),Triple("settings","Settings",Icons.Default.Settings)).forEach { (target,label,icon)->
                    NavigationBarItem(selected=route==target,onClick={nav.navigateTopLevel(target)},icon={Icon(icon,label)},label={Text(label)},modifier=Modifier.testTag("nav-$target"))
                }
            } },
            floatingActionButton={ if(route=="chats") ExtendedFloatingActionButton(onClick={nav.navigateTopLevel("new")},icon={Icon(Icons.Default.Add,null)},text={Text("New message")},modifier=Modifier.testTag("new-chat")) }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                state.localError?.let { Text(it,Modifier.padding(16.dp),color=palette.error) }
                if(state.loaded && vm.repository.identityRuntime.needsIdentity) IdentitySetup(vm.repository.identityRuntime)
                notifications.current?.takeIf {notifications.foreground}?.let { banner ->
                    IncomingBanner(banner,notificationTitle(banner.event.conversationKey,state.contacts),
                        dismiss={vm.repository.notifications.dismiss(banner.event.localMessageId)},
                        open={
                            vm.repository.notifications.dismiss(banner.event.localMessageId)
                            if(vm.repository.hasMessage(banner.event.conversationKey,banner.event.localMessageId)) {
                            val row=state.chats.find {it.peer.key==banner.event.conversationKey}
                            val id=row?.peer?.conversationId ?: PeerIdentity(banner.event.conversationKey,0).conversationId
                            nav.navigate("chat/$id?source=${Uri.encode(row?.sourceId.orEmpty())}") {launchSingleTop=true}
                            }
                        })
                }
                if(ui.error!=null) Surface(color=palette.error.copy(alpha=.12f)) { Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(ui.error!!,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);TextButton(onClick=vm::clearError) { Text("Dismiss") }
                } }
                NavHost(navController=nav,startDestination="chats",modifier=Modifier.weight(1f)) {
                    composable("chats") { ChatsScreen(state,{nav.navigateTopLevel("new")}) { row->nav.navigate("chat/${row.peer.conversationId}?source=${Uri.encode(row.sourceId.orEmpty())}") } }
                    composable("new") { NewMessageScreen(state,ui,vm,scanQr) { chat(it) } }
                    composable("chat/{peer}?source={source}") { back ->
                        val conversationState by vm.appState.collectAsStateWithLifecycle()
                        val peer=back.arguments?.getString("peer").orEmpty();val contact=conversationState.contact(peer,back.arguments?.getString("source"))
                        if(contact==null) Text(if(conversationState.loaded) "Conversation unavailable" else "Loading history…",Modifier.padding(24.dp))
                        else ConversationScreen(contact,conversationState,ui,vm,{vm.reviewSeeker(contact);nav.navigateTopLevel("new")},{nav.navigateTopLevel("chats")}) { chat(it) }
                    }
                    composable("identity") { IdentityScreen(state,vm,walletAction,mutation) }
                    composable("settings") { SettingsScreen(vm,{nav.navigate("diagnostics")}) }
                    composable("diagnostics") { DiagnosticsScreen(state,vm,recreateActivity) }
                }
            }
        }
    } }
}

@Composable private fun Panel(content:@Composable ColumnScope.()->Unit) {
    Surface(shape=RoundedCornerShape(20.dp),color=palette.surface,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content) }
}
@Composable private fun IncomingBanner(banner:ForegroundBanner,title:String,dismiss:()->Unit,open:()->Unit) {
    Surface(color=palette.primaryContainer,shape=RoundedCornerShape(16.dp),tonalElevation=4.dp,
        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp).testTag("incoming-banner")) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick=open).padding(14.dp).testTag("incoming-open")) {
                Text(title,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("incoming-title"))
                if(banner.count>1) Text("${banner.count} new messages",style=MaterialTheme.typography.labelSmall)
                Text(banner.event.text,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.testTag("incoming-preview"))
            }
            IconButton(onClick=dismiss,modifier=Modifier.testTag("incoming-dismiss")) {Icon(Icons.Default.Close,"Dismiss notification")}
        }
    }
}
@Composable private fun NetworkLine(network:PrivateNetwork) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).background(if(network==PrivateNetwork.Ready) palette.primary else palette.onSurfaceVariant,RoundedCornerShape(10.dp)))
        Text("Private network · ${when(network) {PrivateNetwork.Error->"Unavailable";else->network.name}}",style=MaterialTheme.typography.labelMedium,color=palette.onSurfaceVariant)
    }
}
@Composable private fun ChatsScreen(state:AppState,newMessage:()->Unit,open:(ChatRow)->Unit) {
    Column {
        NetworkLine(state.network)
        if(state.chats.isEmpty()) Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center) {
            Text("No conversations yet",style=MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp));Text("Message a Seeker ID, Solana wallet or private address.",color=palette.onSurfaceVariant)
            Spacer(Modifier.height(24.dp));Button(onClick=newMessage) {Text("New message")}
            Text("A wallet is optional.",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall,color=palette.onSurfaceVariant)
        } else LazyColumn(contentPadding=PaddingValues(bottom=90.dp)) {
            items(state.chats,key={it.peer.conversationId}) { chat ->
                Row(Modifier.fillMaxWidth().clickable { open(chat) }.padding(horizontal=20.dp,vertical=17.dp).testTag("chat-${chat.peer.conversationId}"),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Surface(shape=RoundedCornerShape(16.dp),color=palette.surfaceVariant,modifier=Modifier.size(46.dp)) { Box(contentAlignment=Alignment.Center) {Text(if(chat.title=="Unknown private contact") "?" else chat.title.take(1).uppercase(),fontSize=21.sp,color=palette.primary)} }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(chat.title,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(chat.messages.lastOrNull()?.text ?: "Start a conversation",maxLines=1,overflow=TextOverflow.Ellipsis,color=palette.onSurfaceVariant)
                        Text(chat.sourceLabel,style=MaterialTheme.typography.labelSmall,color=palette.onSurfaceVariant)
                    }
                    Text(chat.messages.lastOrNull()?.let { messageTime(it.timestamp).substringAfter("· ") } ?: "",style=MaterialTheme.typography.labelSmall,color=palette.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(start=80.dp),color=palette.surfaceVariant)
            }
        }
    }
}
@Composable private fun NewMessageScreen(state:AppState,ui:ProductUi,vm:ProductViewModel,scanQr:()->Unit,open:(Contact)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { Text("Start with an address",style=MaterialTheme.typography.headlineSmall);Text("Paste a Seeker ID (.skr), Solana wallet or private address.",Modifier.padding(top=8.dp),color=palette.onSurfaceVariant) }
        item { OutlinedTextField(ui.address,vm::address,label={Text("Seeker ID, wallet or private address")},modifier=Modifier.fillMaxWidth().testTag("address-input"),minLines=2,maxLines=4) }
        item { OutlinedButton(onClick=scanQr,enabled=!ui.scanBusy,modifier=Modifier.testTag("scan-qr")) {Text(if(ui.scanBusy) "Scanning…" else "Scan QR")}
            if(ui.scanMessage.isNotBlank()) Text(ui.scanMessage,Modifier.padding(top=8.dp).testTag("scan-message")) }
        item { Button(onClick=vm::lookUp,enabled=ui.address.isNotBlank()&&!ui.lookupBusy,modifier=Modifier.fillMaxWidth().testTag("lookup-address")) { Text(if(ui.lookupBusy) "Looking up…" else "Continue") } }
        if(ui.lookupText.isNotBlank()) item { Panel { Text(ui.lookupText);ui.candidate?.let { c->
            Text(if(c.source==AddressSource.Solana) "Messaging address found via Solana wallet. Messages use private messaging identity." else "Direct private messaging. No wallet needed.",style=MaterialTheme.typography.bodySmall,color=palette.onSurfaceVariant)
            Button(onClick={vm.open(c,open)},modifier=Modifier.testTag("open-chat")) {Text("Open chat")}
        } } }
        if(state.contacts.isNotEmpty()) { item { Text("Saved contacts",style=MaterialTheme.typography.titleMedium) }
            items(state.contacts,key={it.id}) { c->Panel { Text(c.title,fontWeight=FontWeight.SemiBold);Text(c.provenance?.wallet?.let(::shortAddress) ?: shortAddress(PrivateAddress.encode(c.peer)),color=palette.onSurfaceVariant);TextButton(onClick={vm.open(c,open)}) {Text("Open conversation")} } }
        }
    }
}
@Composable private fun ConversationScreen(contact:Contact,state:AppState,ui:ProductUi,vm:ProductViewModel,reviewSeeker:()->Unit,deleted:()->Unit,navigate:(Contact)->Unit) {
    val id=contact.peer.conversationId
    val messages=state.chats.find { it.peer.key==contact.peer.key }?.messages ?: emptyList()
    val notice=ui.notices[contact.id]
    val seekerNotice=ui.seekerNotices[contact.id]
    val seekerBlocked=seekerNotice!=null && !seekerMatches(contact,seekerNotice)
    val changed=notice?.let { ProductPresentation.endpointChanged(contact,it.result) }==true
    val unavailable=notice!=null && notice.result !is RegistryResult.Registered
    var details by rememberSaveable(contact.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(contact.id) {mutableStateOf(false)}
    var retry by remember(contact.id) {mutableStateOf<ChatMessage?>(null)}
    var alias by rememberSaveable(contact.id) { mutableStateOf(contact.alias) }
    val clipboard=LocalClipboardManager.current
    LaunchedEffect(contact.id) { vm.refresh(contact) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(contact.title,style=MaterialTheme.typography.titleLarge);Text(if(contact.source==AddressSource.Solana) contact.provenance!!.let { p->if(p.seekerId!=null) "${p.seekerId} · ${shortAddress(p.wallet)}" else "Found via ${shortAddress(p.wallet)}" } else "Private address",style=MaterialTheme.typography.labelMedium,color=palette.onSurfaceVariant) }
            TextButton(onClick={alias=contact.alias;details=true},modifier=Modifier.testTag("contact-details")) {Text("Details")}
        }
        NetworkLine(state.network)
        if(seekerBlocked) Surface(color=palette.surfaceVariant,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
            Text(if(seekerOwnerChanged(contact,seekerNotice!!)) "This Seeker ID now resolves to a different wallet." else seekerMessage(seekerNotice.result))
            if(seekerOwnerChanged(contact,seekerNotice)) TextButton(onClick=reviewSeeker,modifier=Modifier.testTag("review-seeker-wallet")) {Text("Review new wallet")}
            TextButton(onClick={vm.refresh(contact)}) {Text("Retry Seeker ID check")}
            TextButton(onClick={vm.oldDirect(contact,navigate)}) {Text("Continue old direct chat")}
        } }
        if(!seekerBlocked && (changed || unavailable)) Surface(color=palette.surfaceVariant,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
            Text(if(changed) "This wallet changed its messaging identity." else ProductPresentation.lookup(notice!!.result))
            if(changed) TextButton(onClick={vm.newIdentity(contact,navigate)},modifier=Modifier.testTag("open-new-identity")) {Text("Open new identity")}
            TextButton(onClick={vm.oldDirect(contact,navigate)},modifier=Modifier.testTag("continue-old-direct")) {Text("Continue old direct chat")}
            TextButton(onClick={vm.refresh(contact)}) {Text("Refresh")}
        } }
        val list=rememberLazyListState()
        LaunchedEffect(messages.lastOrNull()?.id) { if(messages.isNotEmpty()) list.animateScrollToItem(messages.size) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("message-list"),state=list,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="local-events") { state.localEvents[contact.id]?.forEach { event ->
                Text("Local notice · $event",Modifier.fillMaxWidth().padding(10.dp),style=MaterialTheme.typography.labelMedium,color=palette.onSurfaceVariant)
            } }
            if(messages.isEmpty()) item {Text("Your conversation starts here.",color=palette.onSurfaceVariant,modifier=Modifier.padding(16.dp))}
            items(messages,key={it.id}) { message ->
                Column(Modifier.fillMaxWidth(),horizontalAlignment=if(message.outgoing) Alignment.End else Alignment.Start) {
                    Surface(color=if(message.outgoing) MmisColors.outgoing else palette.surfaceVariant,shape=RoundedCornerShape(18.dp),modifier=Modifier.widthIn(max=310.dp)) {
                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            SelectionContainer { Text(message.text,style=MaterialTheme.typography.bodyLarge) }
                            Text(messageTime(message.timestamp)+(if(message.outgoing) " · ${messageStatus(message.status)}" else ""),style=MaterialTheme.typography.labelSmall,color=palette.onSurfaceVariant)
                            if(message.outgoing && message.status==3) TextButton(onClick={retry=message},enabled=id !in ui.sending,modifier=Modifier.testTag("retry-${message.id}")) {Text("Retry")}
                        }
                    }
                }
            }
        }
        if(state.network!=PrivateNetwork.Ready) Row(Modifier.padding(horizontal=18.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("${if(state.network==PrivateNetwork.Connecting) "Connecting" else "Private network unavailable"} · history remains available",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=palette.onSurfaceVariant)
            if(state.network!=PrivateNetwork.Connecting) TextButton(onClick=vm::reconnect,modifier=Modifier.testTag("reconnect-private-network")) {Text("Reconnect")}
        }
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ui.drafts[id].orEmpty(),{vm.draft(id,it)},placeholder={Text("Message")},modifier=Modifier.weight(1f).testTag("message-input"),maxLines=5,shape=RoundedCornerShape(22.dp))
            FilledIconButton(onClick={vm.send(contact)},enabled=state.network==PrivateNetwork.Ready && !changed && !unavailable && !seekerBlocked && ui.drafts[id].orEmpty().isNotBlank() && id !in ui.sending,modifier=Modifier.size(52.dp).testTag("send-message")) {Icon(Icons.Default.Send,"Send")}
        }
    }
    if(details) AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest={details=false},title={Text("Contact details")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(alias,{alias=it},label={Text("Local alias")},modifier=Modifier.testTag("contact-alias"))
        Text("Only you see this name. It does not change the sender's nickname.",style=MaterialTheme.typography.bodySmall)
        contact.provenance?.seekerId?.let { name->Text("Seeker ID · $name");TextButton(onClick={clipboard.setText(AnnotatedString(name))}) {Text("Copy Seeker ID")} }
        contact.provenance?.let { p->TextButton(onClick={clipboard.setText(AnnotatedString(p.wallet))}) {Text("Copy wallet address")} }
        Text(contact.provenance?.let {"Messaging address found via Solana wallet ${it.wallet}. This is address provenance, not a wallet signature on each message."} ?: "Direct private contact. No wallet identity is asserted.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={clipboard.setText(AnnotatedString(PrivateAddress.encode(contact.peer)))}) {Text("Copy private address")}
        TextButton(onClick={details=false;confirmDelete=true},enabled=id !in ui.sending,modifier=Modifier.testTag("delete-conversation")) {Text("Delete conversation",color=palette.error)}
    }},confirmButton={TextButton(onClick={vm.alias(contact,alias);details=false},modifier=Modifier.testTag("save-alias")) {Text("Save")}},dismissButton={TextButton(onClick={details=false}) {Text("Cancel")}})
    if(confirmDelete) AlertDialog(modifier=Modifier.semantics {testTagsAsResourceId=true},onDismissRequest={confirmDelete=false},
        title={Text("Delete conversation?")},text={Text("Remove this conversation, messages and draft from this device? Your saved contact and alias stay. Your identity and the other person's history stay unchanged.")},
        confirmButton={TextButton(onClick={confirmDelete=false;vm.deleteConversation(contact,deleted)},enabled=id !in ui.sending,modifier=Modifier.testTag("confirm-delete-conversation")) {Text("Delete",color=palette.error)}},
        dismissButton={TextButton(onClick={confirmDelete=false},modifier=Modifier.testTag("cancel-delete-conversation")) {Text("Cancel")}})
    retry?.let {message->AlertDialog(onDismissRequest={retry=null},title={Text("Retry message?")},
        text={Text("Copy this message to the composer, then tap Send for a new attempt. The failed attempt stays in history. A delayed original may still arrive."+(if(ui.drafts[id].isNullOrBlank()) "" else " This replaces your current draft."))},
        confirmButton={TextButton(onClick={vm.draft(id,message.text);retry=null}) {Text("Copy to composer")}},dismissButton={TextButton(onClick={retry=null}) {Text("Cancel")}})}
}

@Composable private fun IdentityScreen(state:AppState,vm:ProductViewModel,walletAction:(String)->Unit,mutation:(String,String?)->Unit) {
    var confirm by rememberSaveable { mutableStateOf("") };var intendedWallet by rememberSaveable { mutableStateOf<String?>(null) };var showBinding by rememberSaveable {mutableStateOf(false)}
    val clipboard=LocalClipboardManager.current
    fun ask(operation:String) { intendedWallet=state.wallet;confirm=operation }
    LaunchedEffect(state.wallet) { vm.refreshIdentity() }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { Text("MMIS network: ${vm.repository.registryNetwork.profile.networkName}",modifier=Modifier.testTag("registry-network"),style=MaterialTheme.typography.bodyMedium) }
        item { Panel {
            Text("Private messaging address",style=MaterialTheme.typography.titleLarge)
            Text("This device's messaging identity is independent of your wallet.",color=palette.onSurfaceVariant)
            state.local?.let { p->Text(PrivateAddress.encode(p),style=MaterialTheme.typography.bodySmall,maxLines=3);OutlinedButton(onClick={clipboard.setText(AnnotatedString(PrivateAddress.encode(p)))},modifier=Modifier.testTag("copy-private-address")) {Text("Copy private address")} } ?: Text("Preparing your private address…")
            state.local?.let { QrShareButton(PrivateAddress.encode(it),"Share private address","qr-private",true) }
            BackupIdentityButton()
        } }
        item { Panel {
            Text("Wallet",style=MaterialTheme.typography.titleLarge);Text(state.walletLabel)
            state.walletSeekerNames.firstOrNull()?.let { name ->
                Text("Seeker ID: $name")
                TextButton(onClick={clipboard.setText(AnnotatedString(name))}) {Text("Copy Seeker ID")}
                QrShareButton(name,"Share Seeker ID","qr-seeker")
            }
            if(state.wallet==null) {Text("Optional. Let people find your private address through your wallet.",color=palette.onSurfaceVariant);Button(onClick={walletAction("authorize")},enabled=!state.walletBusy,modifier=Modifier.testTag("connect-wallet")) {Text("Connect wallet")} }
            else { TextButton(onClick={clipboard.setText(AnnotatedString(state.wallet))}) {Text("Copy wallet address")};Row {TextButton(onClick={walletAction("deauthorize")},enabled=!state.walletBusy&&!state.registryBusy) {Text("Disconnect wallet")};TextButton(onClick={vm.repository.nextAccount()},enabled=!state.walletBusy&&!state.registryBusy,modifier=Modifier.testTag("next-wallet")) {Text("Switch account")}} }
            state.wallet?.let { QrShareButton(it,"Share wallet address","qr-wallet") }
            if(state.wallet!=null) TextButton(onClick={walletAction("reauthorize")},enabled=!state.walletBusy&&!state.registryBusy,modifier=Modifier.testTag("reauthorize-wallet")) {Text("Reconnect authorized wallet")}
        } }
        if(state.wallet!=null) item { Panel {
            Text("Private messaging for wallet",style=MaterialTheme.typography.titleLarge)
            Text(state.pendingLabel ?: when(state.binding) {
                BindingState.Registered->"Private messaging enabled"
                BindingState.NotRegistered->"Not enabled"
                BindingState.DifferentIdentity->"This wallet is linked to a different messaging identity."
                BindingState.RpcUnavailable->"Could not check messaging availability. Try again."
                BindingState.Conflict->"Messaging setting changed elsewhere. Refresh to continue."
                BindingState.Error->"Could not change messaging settings. Refresh to check the current state."
                BindingState.OutcomeUnknown->"Transaction status uncertain"
                else->"Checking messaging settings…"
            },modifier=Modifier.testTag("binding-status"))
            val free=!state.walletBusy&&!state.registryBusy&&state.pendingLabel==null
            if(state.binding==BindingState.NotRegistered) Button(onClick={ask("register")},enabled=free&&state.local!=null,modifier=Modifier.testTag("enable-messaging")) {Text("Enable private messaging")}
            if(state.binding==BindingState.DifferentIdentity) {
                Text("This device keeps its own private address. Connecting a wallet does not restore another device's conversations.",color=palette.onSurfaceVariant)
                OutlinedButton(onClick={showBinding=true}) {Text("Use current wallet binding")}
                Button(onClick={ask("update")},enabled=free&&state.local!=null,modifier=Modifier.testTag("replace-binding")) {Text("Replace with this device's identity")}
            }
            if(state.binding in setOf(BindingState.Registered,BindingState.DifferentIdentity)) OutlinedButton(onClick={ask("close")},enabled=free,modifier=Modifier.testTag("disable-messaging")) {Text("Disable for this wallet")}
            if(state.pendingLabel!=null) TextButton(onClick={mutation("reconcile",state.wallet)},enabled=!state.registryBusy,modifier=Modifier.testTag("retry-registry-check")) {Text("Retry check")}
            else TextButton(onClick=vm::refreshIdentity,enabled=!state.registryBusy) {Text("Refresh settings")}
        } }
        item { Panel {Text("Create a new messaging identity",style=MaterialTheme.typography.titleMedium);Text("Not available in this preview. Rotation must preserve history and old secrets; it does not revoke old private addresses.",color=palette.onSurfaceVariant);OutlinedButton(onClick={},enabled=false) {Text("Create new identity")}} }
    }
    if(confirm.isNotEmpty()) AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest={confirm=""},title={Text(when(confirm) {"close"->"Disable private messaging?";"update"->"Replace wallet binding?";else->"Enable private messaging?"})},
        text={Text(if(confirm=="close") "This removes the wallet's public messaging listing. People who already know your private address may still be able to message it. Your identity and history stay on this device."
            else "This will publicly link your Solana wallet to this device's private messaging address. Messages themselves remain off-chain."+(if(confirm=="update") " Future wallet lookups will use this device. Existing conversations with the old identity remain separate." else ""))},
        confirmButton={TextButton(onClick={val operation=confirm;confirm="";mutation(operation,intendedWallet)},modifier=Modifier.testTag("confirm-registry")) {Text("Continue to wallet")}},dismissButton={TextButton(onClick={confirm=""}) {Text("Cancel")}})
    if(showBinding) AlertDialog(modifier=Modifier.semantics { testTagsAsResourceId=true },onDismissRequest={showBinding=false},title={Text("Current public binding")},text={Text("The wallet points to another messaging identity. This device cannot receive its messages without that identity's private backup. Your local identity stays unchanged.\n\n"+(state.listing?.result as? RegistryResult.Registered)?.endpoint?.let { PrivateAddress.encode(PeerIdentity(it.keyBase64,it.token)) }.orEmpty())},confirmButton={TextButton(onClick={showBinding=false}) {Text("Keep current binding")}})
}
@Composable private fun SettingsScreen(vm:ProductViewModel,diagnostics:()->Unit) {
    var auto by remember {mutableStateOf(vm.repository.autoConnect)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Panel {Text("Private network",style=MaterialTheme.typography.titleLarge);Row(verticalAlignment=Alignment.CenterVertically) {Text("Connect automatically",Modifier.weight(1f));Switch(auto,{auto=it;vm.repository.setAutoConnect(it)},modifier=Modifier.testTag("auto-connect"))};Text("History stays available while offline. This preview does not guarantee background delivery.",color=palette.onSurfaceVariant)}
        Panel {Text("MMIS preview",style=MaterialTheme.typography.titleLarge);Text("Wallet settings use Solana ${vm.repository.registryNetwork.profile.networkName.lowercase()}. Messages travel through the private network.",color=palette.onSurfaceVariant);TextButton(onClick=diagnostics,modifier=Modifier.testTag("open-diagnostics")) {Text("Diagnostics")}}
        Text("Money Moves in Silence",color=palette.onSurfaceVariant)
    }
}
@Composable private fun DiagnosticsScreen(state:AppState,vm:ProductViewModel,recreate:()->Unit) {
    val notifications by vm.repository.notifications.state.collectAsStateWithLifecycle()
    var signOnly by remember {mutableStateOf(vm.repository.fakeWalletSignOnly)}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text("${vm.repository.registryNetwork.label} · ${BuildConfig.VERSION_NAME}",modifier=Modifier.testTag("registry-profile"),style=MaterialTheme.typography.titleLarge)
            if(vm.repository.registryNetwork.profile==RegistryProfile.Release) Text("Mainnet beta deployment is upgradeable.") }
        item { Text("Foreground notifications: shown ${notifications.shown}, current chat suppressed ${notifications.suppressedCurrentChat}, background suppressed ${notifications.suppressedBackground}, queue drops ${notifications.queueDrops}",style=MaterialTheme.typography.bodySmall) }
        item { Text("Laboratory options",style=MaterialTheme.typography.titleLarge)
            if(vm.repository.registryNetwork.profile==RegistryProfile.Lab) Row(verticalAlignment=Alignment.CenterVertically) {Text("Official fakewallet: sign only, submit via app RPC",Modifier.weight(1f));Switch(signOnly,{signOnly=it;vm.repository.setFakeWalletSignOnly(it)},modifier=Modifier.testTag("fakewallet-mode"))}
            Text("Wallet identity verification is not configured for production.",color=palette.onSurfaceVariant);TextButton(onClick=recreate,modifier=Modifier.testTag("recreate-activity")) {Text("Recreate Activity")} }
        item { SelectionContainer {Text(state.diagnostics.take(18000),fontSize=11.sp)} }
    }
}

