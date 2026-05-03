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

object TdChatRepository : TdAuthClient.UpdateListener {

    interface Listener {
        fun onChatsChanged(chats: List<ChatListItem>)
        fun onChatError(message: String)
    }

    private const val TAG = "TdChatRepository"
    private const val MAIN_LIST_INITIAL_LIMIT = 200
    private const val MAIN_LIST_RECOVERY_LIMIT = 100
    private const val PUBLISH_THROTTLE_MS = 250L
    private const val MAX_BACKFILL_GET_CHAT = 24
    private const val AUTO_DOWNLOAD_AVATAR_LIMIT = 40

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val started = AtomicBoolean(false)
    private val refreshInFlight = AtomicBoolean(false)
    private val pendingChats = ConcurrentHashMap.newKeySet<Long>()
    private val publishScheduled = AtomicBoolean(false)
    private val publishInFlight = AtomicBoolean(false)
    private val publishDirty = AtomicBoolean(false)
    private val publishExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var roomStore: RoomChatListStore? = null
    @Volatile
    private var isAuthorized = false
    @Volatile
    private var lastPublishedItems: List<ChatListItem> = emptyList()
    @Volatile
    private var initialCacheLoaded = false
    @Volatile
    private var publishedToListeners = false

    fun start(context: Context, authorized: Boolean = false) {
        if (authorized) {
            isAuthorized = true
        }
        TdAuthClient.init(context)
        TdEntityCache.start()
        if (roomStore == null) {
            roomStore = RoomChatListStore(context.applicationContext)
            roomStore?.loadAsync { cachedItems ->
                initialCacheLoaded = true
                if (cachedItems.isNotEmpty() && lastPublishedItems.isEmpty()) {
                    lastPublishedItems = cachedItems
                    publishSnapshot()
                } else if (cachedItems.isNotEmpty()) {
                    val mergedItems = mergeItems(cachedItems, lastPublishedItems)
                    if (mergedItems != lastPublishedItems) {
                        lastPublishedItems = mergedItems
                        publishSnapshot()
                    }
                }
            }
        }
        if (started.compareAndSet(false, true)) {
            TdAuthClient.addUpdateListener(this)
        }
        refreshChats()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        if (initialCacheLoaded || lastPublishedItems.isNotEmpty()) {
            publishSnapshot()
        }
        if (TdEntityCache.chatList().isNotEmpty()) {
            schedulePublish()
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun refreshChats() {
        if (!isAuthorized) return
        if (!refreshInFlight.compareAndSet(false, true)) return

        TdAuthClient.send(TdApi.GetChats(TdApi.ChatListMain(), MAIN_LIST_INITIAL_LIMIT)) { result ->
            when (result) {
                is TdApi.Chats -> handleChatSnapshot(result.chatIds)
                is TdApi.Error -> {
                    refreshInFlight.set(false)
                    emitError(result.message)
                }
            }
        }

        TdAuthClient.send(TdApi.LoadChats(TdApi.ChatListMain(), MAIN_LIST_RECOVERY_LIMIT), emitErrors = false) { result ->
            refreshInFlight.set(false)
            if (result is TdApi.Error && result.code != 404) {
                emitError(result.message)
            }
        }
    }

    fun requestAvatar(chatId: Long) {
        val chat = TdEntityCache.chat(chatId)
        if (chat == null) {
            fetchChat(chatId)
            return
        }

        TdEntityCache.requestAvatarForChat(chat) {
            schedulePublish()
        }
    }

    override fun onTdUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewChat -> {
                TdEntityCache.putChat(update.chat)
                schedulePublish()
            }

            is TdApi.UpdateChatPosition -> {
                updateChat(update.chatId, fallback = { applyCachedPosition(update.chatId, update.position) }) { chat ->
                    chat.positions = updatePositions(chat.positions, update.position)
                }
            }

            is TdApi.UpdateChatLastMessage -> {
                updateChat(update.chatId, fallback = {
                    applyCachedLastMessage(update.chatId, update.lastMessage, update.positions)
                }) { chat ->
                    chat.lastMessage = update.lastMessage
                    if (update.positions.isNotEmpty()) {
                        chat.positions = update.positions
                    }
                }
            }

            is TdApi.UpdateChatReadInbox -> {
                updateChat(update.chatId, fallback = {
                    updateCachedItem(update.chatId) { item ->
                        item.copy(unreadCount = update.unreadCount)
                    }
                }) { chat ->
                    chat.lastReadInboxMessageId = update.lastReadInboxMessageId
                    chat.unreadCount = update.unreadCount
                }
            }

            is TdApi.UpdateChatTitle -> {
                updateChat(update.chatId, fallback = {
                    updateCachedItem(update.chatId) { item ->
                        item.copy(title = update.title.ifBlank { item.title })
                    }
                }) { chat ->
                    chat.title = update.title
                }
            }

            is TdApi.UpdateChatPhoto -> {
                updateChat(update.chatId, fallback = {
                    updateCachedItem(update.chatId) { item ->
                        val avatar = TdEntityCache.chatPhotoAvatar(update.photo)
                        item.copy(avatarFileId = avatar.fileId, avatarPath = avatar.path)
                    }
                }) { chat ->
                    chat.photo = update.photo
                }
            }

            is TdApi.UpdateFile -> {
                TdEntityCache.putFile(update.file)
                val affectedChatIds = TdEntityCache.chatIdsUsingAvatarFile(update.file.id)
                if (affectedChatIds.any { chatId -> TdEntityCache.chat(chatId) != null }) {
                    schedulePublish()
                }
            }

            is TdApi.UpdateAuthorizationState -> {
                isAuthorized = update.authorizationState is TdApi.AuthorizationStateReady
                if (isAuthorized) {
                    refreshChats()
                }
            }

            else -> Unit
        }
    }

    private fun updateChat(
        chatId: Long,
        fallback: () -> Boolean = { false },
        block: (TdApi.Chat) -> Unit
    ) {
        val updated = TdEntityCache.updateChat(chatId, block)
        if (!updated) {
            val handledByCachedItem = fallback()
            if (!handledByCachedItem || !TdEntityCache.hasChat(chatId)) {
                fetchChat(chatId)
            }
            return
        }
        schedulePublish()
    }

    private fun fetchChat(chatId: Long) {
        if (TdEntityCache.hasChat(chatId)) return
        if (!pendingChats.add(chatId)) return

        TdAuthClient.send(TdApi.GetChat(chatId), emitErrors = false) { result ->
            pendingChats.remove(chatId)
            when (result) {
                is TdApi.Chat -> {
                    TdEntityCache.putChat(result)
                    schedulePublish()
                }

                is TdApi.Error -> Log.d(TAG, "GetChat($chatId) failed: ${result.code} ${result.message}")
            }
        }
    }

    private fun publishSnapshot() {
        val items = lastPublishedItems
        mainHandler.post {
            if (listeners.isNotEmpty()) {
                publishedToListeners = true
            }
            listeners.forEach { it.onChatsChanged(items) }
        }
    }

    private fun schedulePublish() {
        if (!publishScheduled.compareAndSet(false, true)) return

        mainHandler.postDelayed({
            publishScheduled.set(false)
            publish()
        }, PUBLISH_THROTTLE_MS)
    }

    private fun publish() {
        if (!publishInFlight.compareAndSet(false, true)) {
            publishDirty.set(true)
            return
        }

        publishExecutor.execute {
            val items = buildChatListSnapshot()
            if (items == lastPublishedItems) {
                if (items.isEmpty() && initialCacheLoaded && !publishedToListeners) {
                    publishSnapshot()
                }
                finishPublish()
                return@execute
            }

            lastPublishedItems = items
            roomStore?.saveAsync(items)
            publishSnapshot()
            finishPublish()
        }
    }

    private fun finishPublish() {
        publishInFlight.set(false)
        if (publishDirty.getAndSet(false)) {
            publish()
        }
    }

    private fun buildChatListSnapshot(): List<ChatListItem> {
        val tdItems = TdEntityCache.chatList()
            .mapNotNull { chat -> chat.toListItem() }
            .sortedForChatList()
            .mapIndexed { index, item ->
                if (index < AUTO_DOWNLOAD_AVATAR_LIMIT && item.avatarPath.isNullOrBlank()) {
                    TdEntityCache.chat(item.id)?.let { chat ->
                        TdEntityCache.requestAvatarForChat(chat) {
                            schedulePublish()
                        }
                    }
                }
                item
            }
        return mergeWithCachedItems(tdItems)
    }

    private fun TdApi.Chat.toListItem(): ChatListItem? {
        val mainPosition = positions.firstOrNull { it.list is TdApi.ChatListMain && it.order != 0L }
            ?: return null
        val avatar = TdEntityCache.chatAvatar(this)

        return ChatListItem(
            id = id,
            title = title.ifBlank { "Chat $id" },
            avatarFileId = avatar.fileId,
            avatarPath = avatar.path,
            lastMessage = lastMessage.toPreview(),
            time = lastMessage?.date?.toMessageTime().orEmpty(),
            unreadCount = unreadCount,
            isPinned = mainPosition.isPinned,
            order = mainPosition.order
        )
    }

    private fun handleChatSnapshot(chatIds: LongArray) {
        val missingIds = chatIds
            .asSequence()
            .filterNot { TdEntityCache.hasChat(it) }
            .take(MAX_BACKFILL_GET_CHAT)
            .toList()

        missingIds.forEach(::fetchChat)

        if (missingIds.isEmpty()) {
            schedulePublish()
        }
    }

    private fun applyCachedPosition(chatId: Long, position: TdApi.ChatPosition): Boolean {
        if (position.list !is TdApi.ChatListMain) return hasPublishedItem(chatId)

        return if (position.order == 0L) {
            removeCachedItem(chatId)
        } else {
            updateCachedItem(chatId) { item ->
                item.copy(order = position.order, isPinned = position.isPinned)
            }
        }
    }

    private fun applyCachedLastMessage(
        chatId: Long,
        lastMessage: TdApi.Message?,
        positions: Array<TdApi.ChatPosition>
    ): Boolean {
        val mainPosition = positions.firstOrNull { it.list is TdApi.ChatListMain }
        return updateCachedItem(chatId) { item ->
            item.copy(
                lastMessage = lastMessage.toPreview(),
                time = lastMessage?.date?.toMessageTime().orEmpty(),
                order = mainPosition?.order ?: item.order,
                isPinned = mainPosition?.isPinned ?: item.isPinned
            )
        }
    }

    private fun updateCachedItem(chatId: Long, block: (ChatListItem) -> ChatListItem): Boolean {
        val index = lastPublishedItems.indexOfFirst { it.id == chatId }
        if (index == -1) return false

        val items = lastPublishedItems.toMutableList()
        items[index] = block(items[index])
        updatePublishedItems(items.sortedForChatList(), persist = true)
        return true
    }

    private fun removeCachedItem(chatId: Long): Boolean {
        val items = lastPublishedItems.filterNot { it.id == chatId }
        if (items.size == lastPublishedItems.size) return false

        updatePublishedItems(items, persist = true)
        roomStore?.deleteAsync(chatId)
        return true
    }

    private fun updatePublishedItems(items: List<ChatListItem>, persist: Boolean) {
        if (items == lastPublishedItems) return

        lastPublishedItems = items
        if (persist) {
            roomStore?.saveAsync(items)
        }

        publishSnapshot()
    }

    private fun hasPublishedItem(chatId: Long): Boolean {
        return lastPublishedItems.any { it.id == chatId }
    }

    private fun List<ChatListItem>.sortedForChatList(): List<ChatListItem> {
        return sortedWith(
            compareByDescending<ChatListItem> { it.isPinned }
                .thenByDescending { it.order }
                .thenByDescending { it.id }
        )
    }

    private fun mergeWithCachedItems(tdItems: List<ChatListItem>): List<ChatListItem> {
        if (lastPublishedItems.isEmpty()) return tdItems

        val knownChatIds = TdEntityCache.chatList().mapTo(HashSet()) { it.id }
        if (knownChatIds.isEmpty()) return if (tdItems.isEmpty()) lastPublishedItems else tdItems

        val stillUnknownCachedItems = lastPublishedItems.filterNot { it.id in knownChatIds }
        return mergeItems(stillUnknownCachedItems, tdItems)
    }

    private fun mergeItems(
        baseItems: List<ChatListItem>,
        overrideItems: List<ChatListItem>
    ): List<ChatListItem> {
        val merged = LinkedHashMap<Long, ChatListItem>(baseItems.size + overrideItems.size)
        baseItems.forEach { item -> merged[item.id] = item }
        overrideItems.forEach { item -> merged[item.id] = item }
        return merged.values.toList().sortedForChatList()
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
