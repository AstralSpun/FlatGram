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
import kotlin.math.abs

object TdMessageRepository : TdAuthClient.UpdateListener {

    interface Listener {
        fun onMessagesChanged(chatId: Long, messages: List<MessageListItem>)
        fun onMessageError(chatId: Long, message: String)
    }

    private const val TAG = "TdMessageRepository"
    private const val PAGE_SIZE = 50
    private const val COMMON_MERGE_TIME_SECONDS = 900

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val messagesByChat = ConcurrentHashMap<Long, ConcurrentHashMap<Long, TdApi.Message>>()
    private val usersById = ConcurrentHashMap<Long, TdApi.User>()
    private val chatsById = ConcurrentHashMap<Long, TdApi.Chat>()
    private val loadingOlder = ConcurrentHashMap<Long, AtomicBoolean>()
    private val endReached = ConcurrentHashMap.newKeySet<Long>()
    private val viewedMessages = ConcurrentHashMap<Long, MutableSet<Long>>()
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        TdAuthClient.init(context)
        if (started.compareAndSet(false, true)) {
            TdAuthClient.addUpdateListener(this)
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun openChat(chatId: Long) {
        TdAuthClient.send(TdApi.OpenChat(chatId), emitErrors = false) { result ->
            if (result is TdApi.Error) {
                emitError(chatId, result.message)
            }
        }
        loadInitial(chatId)
    }

    fun closeChat(chatId: Long) {
        TdAuthClient.send(TdApi.CloseChat(chatId), emitErrors = false)
    }

    fun markVisibleMessagesRead(chatId: Long) {
        markRecentMessagesRead(chatId, messagesByChat[chatId]?.values.orEmpty())
    }

    fun loadInitial(chatId: Long) {
        endReached.remove(chatId)
        loadHistory(chatId = chatId, fromMessageId = 0L, isOlderPage = false)
    }

    fun loadOlder(chatId: Long) {
        if (endReached.contains(chatId)) return

        val loading = loadingOlder.getOrPut(chatId) { AtomicBoolean(false) }
        if (!loading.compareAndSet(false, true)) return

        val oldestMessageId = messagesByChat[chatId]
            ?.keys
            ?.filter { it > 0L }
            ?.minOrNull()
            ?: 0L

        if (oldestMessageId == 0L) {
            loading.set(false)
            loadInitial(chatId)
            return
        }

        loadHistory(chatId = chatId, fromMessageId = oldestMessageId, isOlderPage = true)
    }

    fun sendText(chatId: Long, text: String) {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return

        val content = TdApi.InputMessageText(
            TdApi.FormattedText(trimmedText, emptyArray()),
            null,
            true
        )

        TdAuthClient.send(
            TdApi.SendMessage(chatId, null, null, null, null, content),
            emitErrors = false
        ) { result ->
            when (result) {
                is TdApi.Message -> {
                    putMessage(result)
                    publish(chatId)
                }

                is TdApi.Error -> emitError(chatId, result.message)
            }
        }
    }

    override fun onTdUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewMessage -> {
                putMessage(update.message)
                publish(update.message.chatId)
            }

            is TdApi.UpdateNewChat -> {
                chatsById[update.chat.id] = update.chat
                publishAffectedConversationsBySender(TdApi.MessageSenderChat(update.chat.id))
            }

            is TdApi.UpdateUser -> {
                usersById[update.user.id] = update.user
                publishAffectedConversationsBySender(TdApi.MessageSenderUser(update.user.id))
            }

            is TdApi.UpdateChatTitle -> {
                val chat = chatsById[update.chatId]
                if (chat != null) {
                    chat.title = update.title
                }
                publishAffectedConversationsBySender(TdApi.MessageSenderChat(update.chatId))
            }

            is TdApi.UpdateMessageContent -> {
                updateMessage(update.chatId, update.messageId) { message ->
                    message.content = update.newContent
                }
            }

            is TdApi.UpdateMessageSendSucceeded -> {
                val cache = messagesByChat.getOrPut(update.message.chatId) { ConcurrentHashMap() }
                cache.remove(update.oldMessageId)
                cache[update.message.id] = update.message
                publish(update.message.chatId)
            }

            is TdApi.UpdateMessageSendFailed -> {
                val cache = messagesByChat.getOrPut(update.message.chatId) { ConcurrentHashMap() }
                cache.remove(update.oldMessageId)
                cache[update.message.id] = update.message
                publish(update.message.chatId)
            }

            is TdApi.UpdateDeleteMessages -> {
                if (!update.isPermanent) return
                val cache = messagesByChat[update.chatId] ?: return
                update.messageIds.forEach { cache.remove(it) }
                publish(update.chatId)
            }

            else -> Unit
        }
    }

    private fun loadHistory(chatId: Long, fromMessageId: Long, isOlderPage: Boolean) {
        TdAuthClient.send(
            TdApi.GetChatHistory(chatId, fromMessageId, 0, PAGE_SIZE, false),
            emitErrors = false
        ) { result ->
            if (isOlderPage) {
                loadingOlder[chatId]?.set(false)
            }

            when (result) {
                is TdApi.Messages -> {
                    if (result.messages.isEmpty()) {
                        if (isOlderPage) endReached.add(chatId)
                        publish(chatId)
                        return@send
                    }
                    result.messages.forEach(::putMessage)
                    publish(chatId)
                }

                is TdApi.Error -> {
                    if (isOlderPage && result.code == 404) {
                        endReached.add(chatId)
                    } else {
                        emitError(chatId, result.message)
                    }
                }
            }
        }
    }

    private fun putMessage(message: TdApi.Message) {
        val cache = messagesByChat.getOrPut(message.chatId) { ConcurrentHashMap() }
        cache[message.id] = message
        requestSenderName(message.senderId)
    }

    private fun updateMessage(
        chatId: Long,
        messageId: Long,
        block: (TdApi.Message) -> Unit
    ) {
        val message = messagesByChat[chatId]?.get(messageId) ?: return
        block(message)
        publish(chatId)
    }

    private fun publish(chatId: Long) {
        val items = messagesByChat[chatId]
            ?.values
            ?.sortedWith(compareBy<TdApi.Message> { it.sortTimestamp() }.thenBy { it.id })
            ?.map { it.toListItem() }
            ?.withBubbleGroups()
            .orEmpty()

        mainHandler.post {
            listeners.forEach { it.onMessagesChanged(chatId, items) }
        }

    }

    private fun TdApi.Message.toListItem(): MessageListItem {
        return MessageListItem(
            id = id,
            chatId = chatId,
            senderId = senderId.senderKey(),
            senderName = senderId.senderDisplayName(),
            text = MessageContentFormatter.format(content),
            date = date,
            time = date.toMessageTime(),
            isOutgoing = isOutgoing,
            status = sendingState.toSendStatus(),
            groupPosition = MessageBubbleGroupPosition.SINGLE,
            showAvatar = !isOutgoing
        )
    }

    private fun List<MessageListItem>.withBubbleGroups(): List<MessageListItem> {
        return mapIndexed { index, item ->
            val previous = getOrNull(index - 1)
            val next = getOrNull(index + 1)
            val joinsPrevious = canMerge(previous, item)
            val joinsNext = canMerge(item, next)
            val groupPosition = when {
                joinsPrevious && joinsNext -> MessageBubbleGroupPosition.MIDDLE
                joinsPrevious -> MessageBubbleGroupPosition.BOTTOM
                joinsNext -> MessageBubbleGroupPosition.TOP
                else -> MessageBubbleGroupPosition.SINGLE
            }

            item.copy(
                groupPosition = groupPosition,
                showAvatar = !item.isOutgoing && !joinsNext
            )
        }
    }

    private fun canMerge(first: MessageListItem?, second: MessageListItem?): Boolean {
        if (first == null || second == null) return false
        if (first.isOutgoing != second.isOutgoing) return false
        if (first.senderId != second.senderId) return false
        return abs(second.date - first.date) <= COMMON_MERGE_TIME_SECONDS
    }

    private fun TdApi.Message.sortTimestamp(): Int {
        return if (date == 0 && sendingState is TdApi.MessageSendingStatePending) {
            Int.MAX_VALUE
        } else {
            date
        }
    }

    private fun TdApi.MessageSender?.senderKey(): String {
        return when (this) {
            is TdApi.MessageSenderUser -> "user:$userId"
            is TdApi.MessageSenderChat -> "chat:$chatId"
            else -> "unknown"
        }
    }

    private fun TdApi.MessageSender?.senderDisplayName(): String {
        return when (this) {
            is TdApi.MessageSenderUser -> usersById[userId]?.displayName().orEmpty()
            is TdApi.MessageSenderChat -> chatsById[chatId]?.title.orEmpty()
            else -> ""
        }
    }

    private fun requestSenderName(sender: TdApi.MessageSender?) {
        when (sender) {
            is TdApi.MessageSenderUser -> {
                if (usersById.containsKey(sender.userId)) return
                TdAuthClient.send(TdApi.GetUser(sender.userId), emitErrors = false) { result ->
                    if (result is TdApi.User) {
                        usersById[result.id] = result
                        publishAffectedConversationsBySender(TdApi.MessageSenderUser(result.id))
                    }
                }
            }

            is TdApi.MessageSenderChat -> {
                if (chatsById.containsKey(sender.chatId)) return
                TdAuthClient.send(TdApi.GetChat(sender.chatId), emitErrors = false) { result ->
                    if (result is TdApi.Chat) {
                        chatsById[result.id] = result
                        publishAffectedConversationsBySender(TdApi.MessageSenderChat(result.id))
                    }
                }
            }
        }
    }

    private fun publishAffectedConversationsBySender(sender: TdApi.MessageSender) {
        val senderKey = sender.senderKey()
        messagesByChat.forEach { (chatId, messages) ->
            if (messages.values.any { it.senderId.senderKey() == senderKey }) {
                publish(chatId)
            }
        }
    }

    private fun TdApi.User.displayName(): String {
        return listOf(firstName, lastName)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
    }

    private fun TdApi.MessageSendingState?.toSendStatus(): MessageSendStatus {
        return when (this) {
            is TdApi.MessageSendingStatePending -> MessageSendStatus.SENDING
            is TdApi.MessageSendingStateFailed -> MessageSendStatus.FAILED
            else -> MessageSendStatus.NONE
        }
    }

    private fun Int.toMessageTime(): String {
        if (this <= 0) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(this * 1000L))
    }

    private fun markRecentMessagesRead(chatId: Long, messages: Collection<TdApi.Message>) {
        val ids = messages
            .asSequence()
            .filter { !it.isOutgoing && it.id > 0L }
            .sortedByDescending { it.id }
            .take(20)
            .map { it.id }
            .filter { id ->
                val viewed = viewedMessages.getOrPut(chatId) { ConcurrentHashMap.newKeySet() }
                viewed.add(id)
            }
            .toList()

        if (ids.isEmpty()) return

        TdAuthClient.send(
            TdApi.ViewMessages(chatId, ids.toLongArray(), null, true),
            emitErrors = false
        ) { result ->
            if (result is TdApi.Error) {
                Log.d(TAG, "ViewMessages($chatId) failed: ${result.code} ${result.message}")
            }
        }
    }

    private fun emitError(chatId: Long, message: String) {
        mainHandler.post {
            listeners.forEach { it.onMessageError(chatId, message) }
        }
    }
}
