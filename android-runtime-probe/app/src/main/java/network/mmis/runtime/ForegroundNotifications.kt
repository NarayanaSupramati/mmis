package network.mmis.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class IncomingMessageEvent(val conversationKey:String,val localMessageId:Long,val networkMessageId:String,
    val senderKey:String,val text:String,val timestampNs:String)

/** Single-subscriber, synchronous one-shot stream. No replay, buffering or Activity reference. */
class IncomingMessageEvents(private val consume:(IncomingMessageEvent)->Unit) {
    fun emit(event:IncomingMessageEvent)=consume(event)
}
data class ForegroundBanner(val event:IncomingMessageEvent,val count:Int=1,val expiresAt:Long=0)
data class NotificationState(val foreground:Boolean=false,val visibleConversation:String?=null,
    val current:ForegroundBanner?=null,val pending:List<ForegroundBanner> = emptyList(),
    val accepted:Long=0,val shown:Long=0,val suppressedCurrentChat:Long=0,val suppressedBackground:Long=0,val queueDrops:Long=0)

/** Process-owned presentation queue. Input is ONLY committed new inbound rows, never history. */
class ForegroundNotificationCoordinator(private val now:()->Long,scope:CoroutineScope?=null) {
    companion object { const val DURATION_MS=6000L;const val MAX_PENDING=5 }
    private val mutable=MutableStateFlow(NotificationState())
    val state=mutable.asStateFlow()
    init { scope?.launch {
        state.map { it.current?.expiresAt }.distinctUntilChanged().collectLatest { deadline ->
            if(deadline!=null) { delay((deadline-now()).coerceAtLeast(0));expire() }
        }
    } }
    @Synchronized fun foreground(visible:Boolean) {
        mutable.value=mutable.value.copy(foreground=visible,
            current=if(visible) mutable.value.current else null,pending=if(visible) mutable.value.pending else emptyList())
    }
    @Synchronized fun visibleConversation(key:String?) {
        var s=mutable.value.copy(visibleConversation=key)
        val suppressed=s.pending.filter { it.event.conversationKey==key }.sumOf { it.count }.toLong()+
            (s.current?.takeIf { it.event.conversationKey==key }?.count ?: 0)
        s=s.copy(pending=s.pending.filterNot { it.event.conversationKey==key },
            current=s.current?.takeUnless { it.event.conversationKey==key },suppressedCurrentChat=s.suppressedCurrentChat+suppressed)
        mutable.value=advance(s)
    }
    @Synchronized fun accept(event:IncomingMessageEvent) {
        expire()
        var s=mutable.value.copy(accepted=mutable.value.accepted+1)
        if(!s.foreground) { mutable.value=s.copy(suppressedBackground=s.suppressedBackground+1);return }
        if(s.visibleConversation==event.conversationKey) { mutable.value=s.copy(suppressedCurrentChat=s.suppressedCurrentChat+1);return }
        // Only retain a small display preview, not arbitrarily large message text.
        val shortened=event.text.replace(Regex("[\\r\\n\\t]+")," ").take(160)
        val preview=event.copy(text=if(shortened.lastOrNull()?.isHighSurrogate()==true) shortened.dropLast(1) else shortened)
        val current=s.current
        if(current?.event?.conversationKey==event.conversationKey) {
            // Keep original expiry: a flood cannot hold one banner on screen forever.
            mutable.value=s.copy(current=current.copy(event=preview,count=(current.count+1).coerceAtMost(999)));return
        }
        val pending=s.pending.toMutableList();val index=pending.indexOfFirst { it.event.conversationKey==event.conversationKey }
        if(index>=0) pending[index]=pending[index].copy(event=preview,count=(pending[index].count+1).coerceAtMost(999))
        else {
            if(pending.size==MAX_PENDING) {pending.removeAt(0);s=s.copy(queueDrops=s.queueDrops+1)}
            pending.add(ForegroundBanner(preview))
        }
        mutable.value=advance(s.copy(pending=pending))
    }
    private fun advance(s:NotificationState):NotificationState {
        if(!s.foreground || s.current!=null || s.pending.isEmpty()) return s
        return s.copy(current=s.pending.first().copy(expiresAt=now()+DURATION_MS),pending=s.pending.drop(1),shown=s.shown+1)
    }
    @Synchronized fun dismiss(localId:Long) {
        if(mutable.value.current?.event?.localMessageId==localId) mutable.value=advance(mutable.value.copy(current=null))
    }
    @Synchronized fun clearConversation(key:String) {
        val s=mutable.value
        mutable.value=advance(s.copy(current=s.current?.takeUnless {it.event.conversationKey==key},
            pending=s.pending.filterNot {it.event.conversationKey==key}))
    }
    @Synchronized fun expire() {
        if(mutable.value.current?.expiresAt?.let {it<=now()}==true) mutable.value=advance(mutable.value.copy(current=null))
    }
}

fun notificationTitle(key:String,contacts:List<Contact>):String {
    val known=contacts.filter {it.peer.key==key}
    return known.firstOrNull {it.alias.isNotBlank()}?.alias
        ?: known.firstOrNull {it.provenance?.seekerId!=null}?.provenance?.seekerId
        ?: known.firstOrNull {it.provenance!=null}?.provenance?.wallet?.let(::shortAddress)
        ?: "Unknown private contact"
}
