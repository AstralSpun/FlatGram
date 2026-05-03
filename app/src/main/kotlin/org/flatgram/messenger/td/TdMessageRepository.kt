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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

object TdMessageRepository : TdAuthClient.UpdateListener {

    interface Listener {
        fun onMessagesChanged(chatId: Long, messages: List<MessageListItem>)
        fun onMessageError(chatId: Long, message: String)
    }

    interface ChatListener {
        fun onMessagesChanged(messages: List<MessageListItem>)
        fun onMessageError(message: String)
    }

    private const val TAG = "TdMessageRepository"
    private const val PAGE_SIZE = 50
    private const val MIN_BUFFERED_MESSAGES_PER_CHAT = 300
    private const val MAX_CACHED_MESSAGES_PER_CHAT = 500
    private const val COMMON_MERGE_TIME_SECONDS = 900

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val chatListeners = ConcurrentHashMap<Long, CopyOnWriteArraySet<ChatListener>>()
    private val messageCache = MessageCache(MAX_CACHED_MESSAGES_PER_CHAT)
    private val storedItemsByChat = ConcurrentHashMap<Long, ConcurrentHashMap<Long, MessageListItem>>()
    private val loadingInitial = ConcurrentHashMap<Long, AtomicBoolean>()
    private val loadingOlder = ConcurrentHashMap<Long, AtomicBoolean>()
    private val endReached = ConcurrentHashMap.newKeySet<Long>()
    private val viewedMessages = ConcurrentHashMap<Long, MutableSet<Long>>()
    private val storedOldestMessageIds = ConcurrentHashMap<Long, Long>()
    private val pendingPublishes = ConcurrentHashMap.newKeySet<Long>()
    private val dirtyPublishes = ConcurrentHashMap.newKeySet<Long>()
    private val listExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val started = AtomicBoolean(false)

    private var roomStore: RoomMessageStore? = null
    private val lastPublishedItemsByChat = ConcurrentHashMap<Long, List<MessageListItem>>()

    fun start(context: Context) {
        TdAuthClient.init(context)
        TdEntityCache.start()
        if (roomStore == null) {
            roomStore = RoomMessageStore(context.applicationContext)
        }
        if (started.compareAndSet(false, true)) {
            TdAuthClient.addUpdateListener(this)
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        messageCache.chatIds().forEach { chatId ->
            val items = listItems(chatId)
            mainHandler.post {
                if (listeners.contains(listener)) {
                    listener.onMessagesChanged(chatId, items)
                }
            }
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun addChatListener(chatId: Long, listener: ChatListener) {
        chatListeners.getOrPut(chatId) { CopyOnWriteArraySet() }.add(listener)
        lastPublishedItemsByChat[chatId]?.let { items ->
            mainHandler.post {
                if (chatListeners[chatId]?.contains(listener) == true) {
                    listener.onMessagesChanged(items)
                }
            }
            return
        }
        if (hasChatContent(chatId)) {
            listExecutor.execute {
                val items = listItems(chatId)
                lastPublishedItemsByChat[chatId] = items
                mainHandler.post {
                    if (chatListeners[chatId]?.contains(listener) == true) {
                        listener.onMessagesChanged(items)
                    }
                }
            }
        }
    }

    fun removeChatListener(chatId: Long, listener: ChatListener) {
        chatListeners[chatId]?.remove(listener)
    }

    fun currentMessages(chatId: Long): List<MessageListItem> {
        return lastPublishedItemsByChat[chatId].orEmpty()
    }

    fun openChat(chatId: Long) {
        loadStoredLatest(chatId)
    }

    private fun openTdChat(chatId: Long) {
        TdAuthClient.send(TdApi.OpenChat(chatId), emitErrors = false) { result ->
            if (result is TdApi.Error) {
                emitError(chatId, result.message)
            }
        }
    }

    fun closeChat(chatId: Long) {
        TdAuthClient.send(TdApi.CloseChat(chatId), emitErrors = false)
        messageCache.trim(chatId)
    }

    fun markVisibleMessagesRead(chatId: Long) {
        markRecentMessagesRead(chatId, messageCache.messages(chatId))
    }

    fun markVisibleMessagesRead(chatId: Long, visibleMessages: List<MessageListItem>) {
        val ids = visibleMessages
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

    fun loadInitial(chatId: Long) {
        endReached.remove(chatId)
        val loading = loadingInitial.getOrPut(chatId) { AtomicBoolean(false) }
        if (!loading.compareAndSet(false, true)) return
        loadHistory(chatId = chatId, fromMessageId = 0L, isOlderPage = false)
    }

    fun loadOlder(chatId: Long) {
        if (endReached.contains(chatId)) return

        val loading = loadingOlder.getOrPut(chatId) { AtomicBoolean(false) }
        if (!loading.compareAndSet(false, true)) return

        val oldestMessageId = oldestMessageId(chatId)

        if (oldestMessageId == 0L) {
            loading.set(false)
            loadInitial(chatId)
            return
        }

        loadStoredOlderThenRemote(chatId, oldestMessageId)
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
                    persistMessages(chatId)
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
                persistMessages(update.message.chatId)
                publish(update.message.chatId)
            }

            is TdApi.UpdateNewChat -> {
                TdEntityCache.putChat(update.chat)
                publishAffectedConversationsBySender(TdApi.MessageSenderChat(update.chat.id))
            }

            is TdApi.UpdateUser -> {
                TdEntityCache.putUser(update.user)
                publishAffectedConversationsBySender(TdApi.MessageSenderUser(update.user.id))
            }

            is TdApi.UpdateChatTitle -> {
                val updated = TdEntityCache.updateChat(update.chatId) { chat ->
                    chat.title = update.title
                }
                if (updated) {
                    publishAffectedConversationsBySender(TdApi.MessageSenderChat(update.chatId))
                }
            }

            is TdApi.UpdateChatPhoto -> {
                val updated = TdEntityCache.updateChat(update.chatId) { chat ->
                    chat.photo = update.photo
                }
                if (updated) {
                    publishAffectedConversationsBySender(TdApi.MessageSenderChat(update.chatId))
                }
            }

            is TdApi.UpdateFile -> {
                TdEntityCache.putFile(update.file)
                val affectedSenderKeys = TdEntityCache.senderKeysUsingAvatarFile(update.file.id)
                messageCache.chatIds().forEach { chatId ->
                    if (messageCache.messages(chatId).any { TdEntityCache.senderKey(it.senderId) in affectedSenderKeys }) {
                        persistMessages(chatId)
                        publish(chatId)
                    }
                }
            }

            is TdApi.UpdateMessageContent -> {
                val updatedTdMessage = updateMessage(update.chatId, update.messageId) { message ->
                    message.content = update.newContent
                }
                val updatedStoredMessage = updateStoredItem(update.chatId, update.messageId) { item ->
                    item.copy(text = MessageContentFormatter.format(update.newContent))
                }
                if (updatedStoredMessage && !updatedTdMessage) {
                    persistMessages(update.chatId)
                    publish(update.chatId)
                }
            }

            is TdApi.UpdateMessageSendSucceeded -> {
                messageCache.replace(update.message.chatId, update.oldMessageId, update.message)
                putMessage(update.message)
                removeStoredItem(update.message.chatId, update.oldMessageId)
                roomStore?.deleteAsync(update.message.chatId, update.oldMessageId)
                persistMessages(update.message.chatId)
                publish(update.message.chatId)
            }

            is TdApi.UpdateMessageSendFailed -> {
                messageCache.replace(update.message.chatId, update.oldMessageId, update.message)
                putMessage(update.message)
                removeStoredItem(update.message.chatId, update.oldMessageId)
                roomStore?.deleteAsync(update.message.chatId, update.oldMessageId)
                persistMessages(update.message.chatId)
                publish(update.message.chatId)
            }

            is TdApi.UpdateDeleteMessages -> {
                if (!update.isPermanent) return
                messageCache.removeAll(update.chatId, update.messageIds)
                removeStoredItems(update.chatId, update.messageIds)
                roomStore?.deleteAsync(update.chatId, update.messageIds)
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
            if (!isOlderPage) {
                loadingInitial[chatId]?.set(false)
            }
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
                    if (isOlderPage && !result.messages.hasOlderProgress(fromMessageId)) {
                        endReached.add(chatId)
                    }
                    putMessages(result.messages, shouldRequestSenders = !isOlderPage)
                    persistTdMessages(result.messages)
                    publish(chatId)
                    ensureInitialOlderBuffer(chatId)
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

    private fun loadStoredLatest(chatId: Long) {
        roomStore?.loadLatestAsync(chatId, MAX_CACHED_MESSAGES_PER_CHAT) { storedItems ->
            if (storedItems.isNotEmpty()) {
                putStoredItems(chatId, storedItems)
                publish(chatId)
            } else {
                publish(chatId)
            }
            openTdChat(chatId)
            loadInitial(chatId)
        } ?: run {
            publish(chatId)
            openTdChat(chatId)
            loadInitial(chatId)
        }
    }

    private fun loadStoredOlderThenRemote(chatId: Long, oldestMessageId: Long) {
        roomStore?.loadOlderAsync(chatId, oldestMessageId, PAGE_SIZE) { storedItems ->
            if (storedItems.isNotEmpty()) {
                putStoredItems(chatId, storedItems)
                loadingOlder[chatId]?.set(false)
                publish(chatId)
                ensureInitialOlderBuffer(chatId)
                return@loadOlderAsync
            }
            loadHistory(chatId = chatId, fromMessageId = oldestMessageId, isOlderPage = true)
        } ?: loadHistory(chatId = chatId, fromMessageId = oldestMessageId, isOlderPage = true)
    }

    private fun ensureInitialOlderBuffer(chatId: Long) {
        if (endReached.contains(chatId)) return
        if (messageCount(chatId) >= MIN_BUFFERED_MESSAGES_PER_CHAT) return
        loadOlder(chatId)
    }

    private fun persistMessages(chatId: Long) {
        val items = listItems(chatId)
        if (items.isNotEmpty()) {
            rememberStoredOldest(chatId, items)
            roomStore?.saveAsync(items)
        }
    }

    private fun persistTdMessages(messages: Array<TdApi.Message>) {
        if (messages.isEmpty()) return
        val items = messages.map { it.toListItem() }
        items.groupBy { it.chatId }.forEach { (chatId, chatItems) ->
            rememberStoredOldest(chatId, chatItems)
        }
        roomStore?.saveAsync(items)
    }

    private fun putStoredItems(chatId: Long, items: List<MessageListItem>) {
        if (items.isEmpty()) return
        val storedItems = storedItemsByChat.getOrPut(chatId) { ConcurrentHashMap() }
        items.forEach { item -> storedItems[item.id] = item }
        rememberStoredOldest(chatId, items)
    }

    private fun updateStoredItem(
        chatId: Long,
        messageId: Long,
        block: (MessageListItem) -> MessageListItem
    ): Boolean {
        val storedItems = storedItemsByChat[chatId] ?: return false
        val item = storedItems[messageId] ?: return false
        storedItems[messageId] = block(item)
        return true
    }

    private fun removeStoredItem(chatId: Long, messageId: Long) {
        storedItemsByChat[chatId]?.remove(messageId)
    }

    private fun removeStoredItems(chatId: Long, messageIds: LongArray) {
        val storedItems = storedItemsByChat[chatId] ?: return
        messageIds.forEach(storedItems::remove)
    }

    private fun messageCount(chatId: Long): Int {
        val ids = HashSet<Long>()
        messageCache.messages(chatId).forEach { ids.add(it.id) }
        storedItemsByChat[chatId]?.keys?.let(ids::addAll)
        return ids.size
    }

    private fun hasChatContent(chatId: Long): Boolean {
        return messageCache.messages(chatId).any() || storedItemsByChat[chatId]?.isNotEmpty() == true
    }

    private fun oldestMessageId(chatId: Long): Long {
        return minOfPositive(
            messageCache.oldestMessageId(chatId),
            storedOldestMessageIds[chatId] ?: 0L
        )
    }

    private fun minOfPositive(first: Long, second: Long): Long {
        return when {
            first <= 0L -> second
            second <= 0L -> first
            else -> minOf(first, second)
        }
    }

    private fun rememberStoredOldest(chatId: Long, items: List<MessageListItem>) {
        val oldestMessageId = items
            .asSequence()
            .map { it.id }
            .filter { it > 0L }
            .minOrNull()
            ?: return
        storedOldestMessageIds.merge(chatId, oldestMessageId, ::minOf)
    }

    private fun putMessage(message: TdApi.Message) {
        val changed = messageCache.put(message)
        if (changed) {
            requestSender(message)
        }
    }

    private fun putMessages(messages: Array<TdApi.Message>, shouldRequestSenders: Boolean) {
        messages.forEach { message ->
            val changed = messageCache.put(message)
            if (changed && shouldRequestSenders) {
                requestSender(message)
            }
        }
    }

    private fun Array<TdApi.Message>.hasOlderProgress(fromMessageId: Long): Boolean {
        if (fromMessageId <= 0L) return true
        return any { it.id in 1 until fromMessageId }
    }

    private fun updateMessage(
        chatId: Long,
        messageId: Long,
        block: (TdApi.Message) -> Unit
    ): Boolean {
        if (!messageCache.update(chatId, messageId, block)) return false
        persistMessages(chatId)
        publish(chatId)
        return true
    }

    private fun publish(chatId: Long) {
        if (!pendingPublishes.add(chatId)) {
            dirtyPublishes.add(chatId)
            return
        }

        listExecutor.execute {
            val items = listItems(chatId)
            lastPublishedItemsByChat[chatId] = items
            mainHandler.post {
                listeners.forEach { it.onMessagesChanged(chatId, items) }
                chatListeners[chatId]?.forEach { it.onMessagesChanged(items) }
                pendingPublishes.remove(chatId)
                if (dirtyPublishes.remove(chatId)) {
                    publish(chatId)
                }
            }
        }
    }

    private fun listItems(chatId: Long): List<MessageListItem> {
        val tdItems = messageCache.messages(chatId)
            .sortedWith(compareBy<TdApi.Message> { it.sortTimestamp() }.thenBy { it.id })
            .map { it.toListItem() }

        val storedItems = storedItemsByChat[chatId]?.values.orEmpty()
        if (storedItems.isEmpty()) return tdItems.withBubbleGroups().asNewestFirst()

        val mergedItems = LinkedHashMap<Long, MessageListItem>(storedItems.size + tdItems.size)
        storedItems.forEach { item -> mergedItems[item.id] = item }
        tdItems.forEach { item -> mergedItems[item.id] = item }
        return mergedItems.values
            .sortedWith(compareBy<MessageListItem> { it.sortTimestamp() }.thenBy { it.id })
            .withBubbleGroups()
            .asNewestFirst()
    }

    private fun MessageListItem.sortTimestamp(): Int {
        return if (date == 0 && status == MessageSendStatus.SENDING) {
            Int.MAX_VALUE
        } else {
            date
        }
    }

    private fun TdApi.Message.toListItem(): MessageListItem {
        val avatar = TdEntityCache.senderAvatar(senderId)
        return MessageListItem(
            id = id,
            chatId = chatId,
            senderId = TdEntityCache.senderKey(senderId),
            senderName = TdEntityCache.senderDisplayName(senderId),
            avatarFileId = avatar.fileId,
            avatarPath = avatar.path,
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

    private fun List<MessageListItem>.asNewestFirst(): List<MessageListItem> {
        return asReversed()
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

    private fun publishAffectedConversationsBySender(sender: TdApi.MessageSender) {
        val senderKey = TdEntityCache.senderKey(sender)
        messageCache.chatIds().forEach { chatId ->
            if (messageCache.messages(chatId).any { TdEntityCache.senderKey(it.senderId) == senderKey }) {
                persistMessages(chatId)
                publish(chatId)
            }
        }
    }

    private fun requestSender(message: TdApi.Message) {
        TdEntityCache.requestSender(message.senderId) {
            publishAffectedConversationsBySender(message.senderId)
            TdEntityCache.requestAvatarForSender(message.senderId) {
                publishAffectedConversationsBySender(message.senderId)
            }
        }
        TdEntityCache.requestAvatarForSender(message.senderId) {
            publishAffectedConversationsBySender(message.senderId)
        }
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
            chatListeners[chatId]?.forEach { it.onMessageError(message) }
        }
    }
}
