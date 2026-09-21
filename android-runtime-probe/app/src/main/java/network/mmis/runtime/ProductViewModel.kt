package network.mmis.runtime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

data class ProductUi(val address:String="",val candidate:Contact?=null,val lookupText:String="",val lookupBusy:Boolean=false,val scanBusy:Boolean=false,val scanMessage:String="",
    val seekerNotices:Map<String,SeekerObservation> = emptyMap(),val drafts:Map<String,String> = emptyMap(),val sending:Set<String> = emptySet(),val notices:Map<String,RegistryObservation> = emptyMap(),val error:String?=null)

class ProductViewModel(val repository:ProductRepository,private val saved:SavedStateHandle):ViewModel() {
    val appState=repository.state
    private val mutable=MutableStateFlow(ProductUi(address=saved["address"] ?: "",drafts=saved.get<HashMap<String,String>>("drafts") ?: emptyMap()))
    val ui=mutable.asStateFlow()
    private var lookupJob:Job?=null
    private val refreshJobs=mutableMapOf<String,Job>()
    init { repository.start() }
    fun beginScan():Boolean {
        if(ui.value.scanBusy) return false
        mutable.update { it.copy(scanBusy=true,scanMessage="") };return true
    }
    fun scanned(result:QrScanResult) {
        val input=qrInput(result)
        mutable.update { it.copy(scanBusy=false,scanMessage=input.message) }
        input.address?.let { address(it);lookUp() }
    }
    fun address(value:String) { lookupJob?.cancel();saved["address"]=value;mutable.update { it.copy(address=value,candidate=null,lookupText="",lookupBusy=false,error=null) } }
    fun lookUp() {
        lookupJob?.cancel();val input=try { PrivateAddress.classify(ui.value.address) } catch(_:Exception) {
            mutable.update { it.copy(candidate=null,lookupText="Enter a valid Seeker ID, Solana wallet or private address.",lookupBusy=false) };return
        }
        mutable.update { it.copy(candidate=null,lookupBusy=true,lookupText=when(input) { is AddressInput.Seeker->"Looking up Seeker ID…";is AddressInput.Solana->"Looking up private messaging…";else->"Checking private address…" }) }
        lookupJob=viewModelScope.launch {
            try { val (contact,text)=repository.lookup(input) { text->mutable.update { it.copy(lookupText=text) } };mutable.update { it.copy(candidate=contact,lookupText=text,lookupBusy=false) } }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { mutable.update { it.copy(candidate=null,lookupBusy=false,lookupText="Could not check messaging availability. Try again.") } }
        }
    }
    fun open(contact:Contact,navigate:(Contact)->Unit) { viewModelScope.launch {
        val existing=appState.value.contacts.find { it.id==contact.id }
        val chosen=if(existing!=null && contact.alias.isBlank()) contact.copy(alias=existing.alias) else contact
        repository.openConversation(chosen);navigate(chosen)
    } }
    fun draft(id:String,text:String) { val drafts=ui.value.drafts+ (id to text);saved["drafts"]=HashMap(drafts);mutable.update { it.copy(drafts=drafts) } }
    fun clearError() { mutable.update { it.copy(error=null) } }
    fun reconnect() {clearError();repository.reconnect()}
    fun alias(contact:Contact,value:String) { viewModelScope.launch { repository.save(contact.copy(alias=value.trim().take(80))) } }
    private suspend fun checkSeeker(contact:Contact,fresh:Boolean):Boolean {
        val p=contact.provenance ?: return true
        val name=p.seekerId ?: return true
        val o=repository.resolveSeeker(name,fresh)
        mutable.update { it.copy(seekerNotices=it.seekerNotices+(contact.id to o)) }
        return seekerMatches(contact,o)
    }
    fun reviewSeeker(contact:Contact) { address(contact.provenance!!.seekerId!!);lookUp() }
    fun refresh(contact:Contact) { refreshJobs.remove(contact.id)?.cancel();refreshJobs[contact.id]=viewModelScope.launch {
        if(!checkSeeker(contact,true)) return@launch
        repository.notice(contact)?.let { notice->mutable.update { it.copy(notices=it.notices+(contact.id to notice)) } }
        repository.refreshContact(contact)?.let { notice->mutable.update { it.copy(notices=it.notices+(contact.id to notice)) } }
    } }
    fun deleteConversation(contact:Contact,navigateToChats:()->Unit) {
        val id=contact.peer.conversationId
        if(id in ui.value.sending) return
        mutable.update {it.copy(sending=it.sending+id,error=null)}
        val ids=appState.value.contacts.filter {it.peer.key==contact.peer.key}.map {it.id}.toSet()+contact.id
        ids.forEach {refreshJobs.remove(it)?.cancel()}
        viewModelScope.launch {
            try {
                repository.deleteConversation(contact.peer)
                val drafts=ui.value.drafts-id;saved["drafts"]=HashMap(drafts)
                mutable.update {it.copy(drafts=drafts,notices=it.notices-ids,seekerNotices=it.seekerNotices-ids)}
                navigateToChats()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {mutable.update {it.copy(error="Could not finish deleting this conversation. Try again.")}}
            finally {mutable.update {it.copy(sending=it.sending-id)}}
        }
    }
    fun newIdentity(contact:Contact,navigate:(Contact)->Unit) {
        val o=ui.value.notices[contact.id] ?: return
        repository.contactFrom(o)?.let { candidate ->
            val old=contact.provenance
            val provenance=candidate.provenance!!.copy(seekerId=old?.seekerId,seekerSlot=old?.seekerSlot,seekerResolvedAt=old?.seekerResolvedAt)
            open(candidate.copy(alias=contact.alias,provenance=provenance),navigate)
        }
    }
    fun oldDirect(contact:Contact,navigate:(Contact)->Unit) { open(Contact(contact.peer,AddressSource.Direct,alias=contact.alias),navigate) }
    fun send(contact:Contact) {
        val id=contact.peer.conversationId;val text=ui.value.drafts[id].orEmpty()
        if(text.isBlank() || id in ui.value.sending || appState.value.network!=PrivateNetwork.Ready) return
        mutable.update { it.copy(sending=it.sending+id,error=null) }
        viewModelScope.launch {
            try {
                if(!checkSeeker(contact,true)) {
                    mutable.update { it.copy(error="Seeker ID needs review. Your draft is kept.") };return@launch
                }
                if(contact.source==AddressSource.Solana) {
                    val o=repository.refreshContact(contact)!!
                    mutable.update { it.copy(notices=it.notices+(contact.id to o)) }
                    if(o.result !is RegistryResult.Registered || ProductPresentation.endpointChanged(contact,o.result)) {
                        mutable.update { it.copy(error=if(ProductPresentation.endpointChanged(contact,o.result)) "This wallet changed its messaging identity. Choose how to continue." else ProductPresentation.lookup(o.result)) };return@launch
                    }
                }
                if(repository.send(contact.peer,text)) { if(ui.value.drafts[id]==text) draft(id,"") }
                else mutable.update { it.copy(error="Sending did not complete. Your draft is kept. Check history before retrying; a delayed message may still arrive.") }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { mutable.update { it.copy(error="Sending did not complete. Your draft is kept. Check history before retrying.") } }
            finally { mutable.update { it.copy(sending=it.sending-id) } }
        }
    }
    suspend fun wallet(command:String,sender:ActivityResultSender) { try { repository.walletAction(command,sender) } catch(e:CancellationException) { throw e } catch(_:Exception) { mutable.update { it.copy(error="Wallet unavailable. Private-address chats still work.") } } }
    suspend fun mutation(operation:String,wallet:String?,sender:ActivityResultSender,foreground:suspend ()->Unit) {
        try { repository.mutate(operation,wallet,sender,foreground) }
        catch(e:CancellationException) { throw e }
        catch(_:SolanaWalletAdapter.AccountMismatch) { mutable.update { it.copy(error="Selected wallet changed. Review the account before continuing.") } }
        catch(_:Exception) { mutable.update { it.copy(error="Could not change messaging settings. Refresh to check the current state.") } }
    }
    fun refreshIdentity() { viewModelScope.launch { repository.refreshOwn() } }
}
