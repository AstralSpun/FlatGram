package org.flatgram.messenger.td

import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class AvatarInfo(
    val fileId: Int?,
    val path: String?
)

object TdEntityCache : TdAuthClient.UpdateListener {

    private val usersById = ConcurrentHashMap<Long, TdApi.User>()
    private val chatsById = ConcurrentHashMap<Long, TdApi.Chat>()
    private val filesById = ConcurrentHashMap<Int, TdApi.File>()

    private val requestedUsers = ConcurrentHashMap.newKeySet<Long>()
    private val requestedChats = ConcurrentHashMap.newKeySet<Long>()
    private val requestedFiles = ConcurrentHashMap.newKeySet<Int>()
    private val started = AtomicBoolean(false)

    fun start() {
        if (started.compareAndSet(false, true)) {
            TdAuthClient.addUpdateListener(this)
        }
    }

    override fun onTdUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewChat -> putChat(update.chat)
            is TdApi.UpdateUser -> putUser(update.user)
            is TdApi.UpdateChatTitle -> updateChat(update.chatId) { chat ->
                chat.title = update.title
            }
            is TdApi.UpdateChatPhoto -> updateChat(update.chatId) { chat ->
                chat.photo = update.photo
            }
            is TdApi.UpdateFile -> putFile(update.file)
            else -> Unit
        }
    }

    fun putUser(user: TdApi.User) {
        usersById[user.id] = user
        putFile(user.profilePhoto?.small)
        putFile(user.profilePhoto?.big)
    }

    fun putChat(chat: TdApi.Chat) {
        chatsById[chat.id] = chat
        putFile(chat.photo?.small)
        putFile(chat.photo?.big)
    }

    fun putFile(file: TdApi.File?) {
        if (file == null || file.id == 0) return
        filesById[file.id] = file
    }

    fun updateChat(chatId: Long, block: (TdApi.Chat) -> Unit): Boolean {
        val chat = chatsById[chatId] ?: return false
        block(chat)
        putChat(chat)
        return true
    }

    fun chat(chatId: Long): TdApi.Chat? = chatsById[chatId]

    fun chatList(): Collection<TdApi.Chat> = chatsById.values

    fun user(userId: Long): TdApi.User? = usersById[userId]

    fun hasChat(chatId: Long): Boolean = chatsById.containsKey(chatId)

    fun senderKey(sender: TdApi.MessageSender?): String {
        return when (sender) {
            is TdApi.MessageSenderUser -> "user:${sender.userId}"
            is TdApi.MessageSenderChat -> "chat:${sender.chatId}"
            else -> "unknown"
        }
    }

    fun senderDisplayName(sender: TdApi.MessageSender?): String {
        return when (sender) {
            is TdApi.MessageSenderUser -> usersById[sender.userId]?.displayName().orEmpty()
            is TdApi.MessageSenderChat -> chatsById[sender.chatId]?.title.orEmpty()
            else -> ""
        }
    }

    fun chatAvatar(chat: TdApi.Chat?): AvatarInfo {
        return avatarForFile(chat?.photo?.small)
    }

    fun chatPhotoAvatar(photo: TdApi.ChatPhotoInfo?): AvatarInfo {
        return avatarForFile(photo?.small)
    }

    fun senderAvatar(sender: TdApi.MessageSender?): AvatarInfo {
        val file = when (sender) {
            is TdApi.MessageSenderUser -> usersById[sender.userId]?.profilePhoto?.small
            is TdApi.MessageSenderChat -> chatsById[sender.chatId]?.photo?.small
            else -> null
        }
        return avatarForFile(file)
    }

    fun requestSender(sender: TdApi.MessageSender?, onLoaded: () -> Unit) {
        when (sender) {
            is TdApi.MessageSenderUser -> requestUser(sender.userId, onLoaded)
            is TdApi.MessageSenderChat -> requestChat(sender.chatId, onLoaded)
        }
    }

    fun requestAvatarForChat(chat: TdApi.Chat?, onLoaded: () -> Unit) {
        requestFile(chat?.photo?.small, onLoaded)
    }

    fun requestAvatarForSender(sender: TdApi.MessageSender?, onLoaded: () -> Unit) {
        val file = when (sender) {
            is TdApi.MessageSenderUser -> usersById[sender.userId]?.profilePhoto?.small
            is TdApi.MessageSenderChat -> chatsById[sender.chatId]?.photo?.small
            else -> null
        }
        requestFile(file, onLoaded)
    }

    fun requestAvatarForSenderKey(senderKey: String, avatarFileId: Int?, onLoaded: () -> Unit) {
        val file = avatarFileForSenderKey(senderKey)
            ?: avatarFileId?.let { filesById[it] ?: TdApi.File().apply { id = it } }

        if (file != null) {
            requestFile(file, onLoaded)
            return
        }

        when {
            senderKey.startsWith("user:") -> {
                val userId = senderKey.removePrefix("user:").toLongOrNull() ?: return
                requestUser(userId) {
                    requestAvatarForSenderKey(senderKey, avatarFileId, onLoaded)
                }
            }

            senderKey.startsWith("chat:") -> {
                val chatId = senderKey.removePrefix("chat:").toLongOrNull() ?: return
                requestChat(chatId) {
                    requestAvatarForSenderKey(senderKey, avatarFileId, onLoaded)
                }
            }
        }
    }

    private fun avatarFileForSenderKey(senderKey: String): TdApi.File? {
        return when {
            senderKey.startsWith("user:") -> {
                val userId = senderKey.removePrefix("user:").toLongOrNull()
                userId?.let { usersById[it]?.profilePhoto?.small }
            }

            senderKey.startsWith("chat:") -> {
                val chatId = senderKey.removePrefix("chat:").toLongOrNull()
                chatId?.let { chatsById[it]?.photo?.small }
            }

            else -> null
        }
    }

    fun chatIdsUsingAvatarFile(fileId: Int): Set<Long> {
        return chatsById.values
            .asSequence()
            .filter { it.photo?.small?.id == fileId }
            .map { it.id }
            .toSet()
    }

    fun senderKeysUsingAvatarFile(fileId: Int): Set<String> {
        val userKeys = usersById.values
            .asSequence()
            .filter { it.profilePhoto?.small?.id == fileId }
            .map { "user:${it.id}" }

        val chatKeys = chatsById.values
            .asSequence()
            .filter { it.photo?.small?.id == fileId }
            .map { "chat:${it.id}" }

        return (userKeys + chatKeys).toSet()
    }

    private fun requestUser(userId: Long, onLoaded: () -> Unit) {
        if (usersById.containsKey(userId)) return
        if (!requestedUsers.add(userId)) return

        TdAuthClient.send(TdApi.GetUser(userId), emitErrors = false) { result ->
            when (result) {
                is TdApi.User -> {
                    putUser(result)
                    onLoaded()
                }

                is TdApi.Error -> requestedUsers.remove(userId)
            }
        }
    }

    private fun requestChat(chatId: Long, onLoaded: () -> Unit) {
        if (chatsById.containsKey(chatId)) return
        if (!requestedChats.add(chatId)) return

        TdAuthClient.send(TdApi.GetChat(chatId), emitErrors = false) { result ->
            when (result) {
                is TdApi.Chat -> {
                    putChat(result)
                    onLoaded()
                }

                is TdApi.Error -> requestedChats.remove(chatId)
            }
        }
    }

    private fun requestFile(file: TdApi.File?, onLoaded: () -> Unit) {
        if (file == null || file.id == 0) return
        val currentFile = filesById[file.id] ?: file.also(::putFile)
        val local = currentFile.local
        if (local?.isDownloadingCompleted == true || local?.isDownloadingActive == true) return
        if (!requestedFiles.add(currentFile.id)) return

        TdAuthClient.send(
            TdApi.DownloadFile(currentFile.id, 32, 0, 0, false),
            emitErrors = false
        ) { result ->
            when (result) {
                is TdApi.File -> {
                    putFile(result)
                    onLoaded()
                }

                is TdApi.Error -> requestedFiles.remove(currentFile.id)
            }
        }
    }

    private fun avatarForFile(file: TdApi.File?): AvatarInfo {
        if (file == null || file.id == 0) return AvatarInfo(fileId = null, path = null)

        val currentFile = filesById[file.id] ?: file.also(::putFile)
        val local = currentFile.local
        val path = if (local?.isDownloadingCompleted == true && !local.path.isNullOrBlank()) {
            local.path
        } else {
            null
        }

        return AvatarInfo(fileId = currentFile.id, path = path)
    }

    private fun TdApi.User.displayName(): String {
        return listOf(firstName, lastName)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
    }
}
