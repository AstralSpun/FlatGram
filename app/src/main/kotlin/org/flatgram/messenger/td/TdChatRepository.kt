package org.flatgram.messenger.td

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object TdChatRepository : TdAuthClient.UpdateListener {

    interface Listener {
        fun onChatsChanged(chats: List<ChatListItem>)
        fun onChatError(message: String)
    }

    private const val TAG = "TdChatRepository"
    private const val MAIN_LIST_LIMIT = 100

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        TdAuthClient.init(context)
        if (started.compareAndSet(false, true)) {
            TdAuthClient.addUpdateListener(this)
        }
        refreshChats()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        publish()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun refreshChats() {
        TdAuthClient.send(TdApi.GetChats(TdApi.ChatListMain(), MAIN_LIST_LIMIT)) { result ->
            when (result) {
                is TdApi.Chats -> result.chatIds.forEach { chatId -> fetchChat(chatId) }
                is TdApi.Error -> emitError(result.message)
            }
        }

        TdAuthClient.send(TdApi.LoadChats(TdApi.ChatListMain(), MAIN_LIST_LIMIT), emitErrors = false) { result ->
            if (result is TdApi.Error && result.code != 404) {
                emitError(result.message)
            }
        }
    }

    override fun onTdUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewChat -> {
                chats[update.chat.id] = update.chat
                publish()
            }

            is TdApi.UpdateChatPosition -> {
                updateChat(update.chatId) { chat ->
                    chat.positions = updatePositions(chat.positions, update.position)
                }
            }

            is TdApi.UpdateChatLastMessage -> {
                updateChat(update.chatId) { chat ->
                    chat.lastMessage = update.lastMessage
                    if (update.positions.isNotEmpty()) {
                        chat.positions = update.positions
                    }
                }
            }

            is TdApi.UpdateChatReadInbox -> {
                updateChat(update.chatId) { chat ->
                    chat.lastReadInboxMessageId = update.lastReadInboxMessageId
                    chat.unreadCount = update.unreadCount
                }
            }

            is TdApi.UpdateChatTitle -> {
                updateChat(update.chatId) { chat ->
                    chat.title = update.title
                }
            }

            is TdApi.UpdateChatPhoto -> {
                updateChat(update.chatId) { chat ->
                    chat.photo = update.photo
                }
            }

            is TdApi.UpdateAuthorizationState -> {
                if (update.authorizationState is TdApi.AuthorizationStateReady) {
                    refreshChats()
                }
            }

            else -> Unit
        }
    }

    private fun updateChat(chatId: Long, block: (TdApi.Chat) -> Unit) {
        val chat = chats[chatId]
        if (chat == null) {
            fetchChat(chatId)
            return
        }
        block(chat)
        publish()
    }

    private fun fetchChat(chatId: Long) {
        TdAuthClient.send(TdApi.GetChat(chatId), emitErrors = false) { result ->
            when (result) {
                is TdApi.Chat -> {
                    chats[result.id] = result
                    publish()
                }

                is TdApi.Error -> Log.d(TAG, "GetChat($chatId) failed: ${result.code} ${result.message}")
            }
        }
    }

    private fun publish() {
        val items = chats.values
            .mapNotNull { chat -> chat.toListItem() }
            .sortedWith(
                compareByDescending<ChatListItem> { it.isPinned }
                    .thenByDescending { it.order }
                    .thenByDescending { it.id }
            )

        mainHandler.post {
            listeners.forEach { it.onChatsChanged(items) }
        }
    }

    private fun TdApi.Chat.toListItem(): ChatListItem? {
        val mainPosition = positions.firstOrNull { it.list is TdApi.ChatListMain && it.order != 0L }
            ?: return null

        return ChatListItem(
            id = id,
            title = title.ifBlank { "Chat $id" },
            lastMessage = lastMessage.toPreview(),
            time = lastMessage?.date?.toMessageTime().orEmpty(),
            unreadCount = unreadCount,
            isPinned = mainPosition.isPinned,
            order = mainPosition.order
        )
    }

    private fun updatePositions(
        current: Array<TdApi.ChatPosition>,
        incoming: TdApi.ChatPosition
    ): Array<TdApi.ChatPosition> {
        val positions = current.toMutableList()
        val index = positions.indexOfFirst { it.list.isSameChatList(incoming.list) }

        if (incoming.order == 0L) {
            if (index != -1) positions.removeAt(index)
            return positions.toTypedArray()
        }

        if (index == -1) {
            positions.add(incoming)
        } else {
            positions[index] = incoming
        }

        return positions.toTypedArray()
    }

    private fun TdApi.ChatList.isSameChatList(other: TdApi.ChatList): Boolean {
        return when {
            this is TdApi.ChatListMain && other is TdApi.ChatListMain -> true
            this is TdApi.ChatListArchive && other is TdApi.ChatListArchive -> true
            this is TdApi.ChatListFolder && other is TdApi.ChatListFolder -> chatFolderId == other.chatFolderId
            else -> false
        }
    }

    private fun TdApi.Message?.toPreview(): String {
        val content = this?.content ?: return "No messages yet"
        return when (content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessagePhoto -> content.caption.text.ifBlank { "Photo" }
            is TdApi.MessageVideo -> content.caption.text.ifBlank { "Video" }
            is TdApi.MessageAnimation -> content.caption.text.ifBlank { "GIF" }
            is TdApi.MessageAudio -> content.caption.text.ifBlank { "Audio" }
            is TdApi.MessageVoiceNote -> content.caption.text.ifBlank { "Voice message" }
            is TdApi.MessageVideoNote -> "Video message"
            is TdApi.MessageSticker -> content.sticker.emoji.ifBlank { "Sticker" }
            is TdApi.MessageDocument -> content.caption.text.ifBlank { "Document" }
            is TdApi.MessageContact -> "Contact"
            is TdApi.MessageLocation -> "Location"
            is TdApi.MessagePoll -> content.poll.question.text.ifBlank { "Poll" }
            is TdApi.MessageCall -> "Call"
            is TdApi.MessagePinMessage -> "Pinned message"
            is TdApi.MessageChatAddMembers -> "New members"
            is TdApi.MessageChatJoinByLink -> "Joined by link"
            is TdApi.MessageChatDeleteMember -> "Member removed"
            is TdApi.MessageChatChangeTitle -> "Chat title changed"
            is TdApi.MessageChatChangePhoto -> "Chat photo changed"
            is TdApi.MessageChatDeletePhoto -> "Chat photo removed"
            else -> content.javaClass.simpleName.removePrefix("Message")
        }
    }

    private fun Int.toMessageTime(): String {
        if (this <= 0) return ""
        val messageDate = Date(this * 1000L)
        val now = System.currentTimeMillis()
        val pattern = if (now - messageDate.time < 24L * 60L * 60L * 1000L) "HH:mm" else "MM/dd"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(messageDate)
    }

    private fun emitError(message: String) {
        mainHandler.post {
            listeners.forEach { it.onChatError(message) }
        }
    }
}
