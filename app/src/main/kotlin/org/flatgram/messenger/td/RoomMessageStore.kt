package org.flatgram.messenger.td

import android.content.Context
import android.util.Log
import org.flatgram.messenger.db.FlatGramDatabase
import org.flatgram.messenger.db.entity.MessageEntity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class RoomMessageStore(context: Context) {

    private val messageDao = FlatGramDatabase.get(context).messageDao()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun loadLatestAsync(chatId: Long, limit: Int, onLoaded: (List<MessageListItem>) -> Unit) {
        executor.execute {
            val items = runCatching {
                messageDao.getLatestMessages(chatId, limit)
                    .map { it.toListItem() }
                    .sortedBy { it.id }
            }.getOrElse { error ->
                Log.w(TAG, "Load latest messages failed: $chatId", error)
                emptyList()
            }
            onLoaded(items)
        }
    }

    fun loadOlderAsync(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        onLoaded: (List<MessageListItem>) -> Unit
    ) {
        executor.execute {
            val items = runCatching {
                messageDao.getOlderMessages(chatId, fromMessageId, limit)
                    .map { it.toListItem() }
                    .sortedBy { it.id }
            }.getOrElse { error ->
                Log.w(TAG, "Load older messages failed: $chatId", error)
                emptyList()
            }
            onLoaded(items)
        }
    }

    fun saveAsync(items: List<MessageListItem>) {
        if (items.isEmpty()) return
        executor.execute {
            runCatching {
                messageDao.upsertMessages(items.map { it.toEntity(System.currentTimeMillis()) })
                val chatIds = items.mapTo(HashSet()) { it.chatId }
                chatIds.forEach { chatId -> messageDao.trimChat(chatId, MAX_STORED_MESSAGES_PER_CHAT) }
            }.onFailure { error ->
                Log.w(TAG, "Save messages failed", error)
            }
        }
    }

    fun deleteAsync(chatId: Long, messageId: Long) {
        executor.execute {
            runCatching {
                messageDao.deleteMessage(chatId, messageId)
            }.onFailure { error ->
                Log.w(TAG, "Delete message failed: $chatId/$messageId", error)
            }
        }
    }

    fun deleteAsync(chatId: Long, messageIds: LongArray) {
        val ids = messageIds.filter { it > 0L }
        if (ids.isEmpty()) return
        executor.execute {
            runCatching {
                messageDao.deleteMessages(chatId, ids)
            }.onFailure { error ->
                Log.w(TAG, "Delete messages failed: $chatId", error)
            }
        }
    }

    private fun MessageListItem.toEntity(updatedAt: Long): MessageEntity {
        return MessageEntity(
            chatId = chatId,
            id = id,
            senderKey = senderId,
            senderName = senderName,
            avatarFileId = avatarFileId,
            avatarPath = avatarPath,
            text = text,
            date = date,
            time = time,
            isOutgoing = isOutgoing,
            status = status.name,
            updatedAt = updatedAt
        )
    }

    private fun MessageEntity.toListItem(): MessageListItem {
        return MessageListItem(
            id = id,
            chatId = chatId,
            senderId = senderKey,
            senderName = senderName,
            avatarFileId = avatarFileId,
            avatarPath = avatarPath,
            text = text,
            date = date,
            time = time,
            isOutgoing = isOutgoing,
            status = runCatching { MessageSendStatus.valueOf(status) }.getOrDefault(MessageSendStatus.NONE),
            groupPosition = MessageBubbleGroupPosition.SINGLE,
            showAvatar = !isOutgoing
        )
    }

    private companion object {
        const val TAG = "RoomMessageStore"
        const val MAX_STORED_MESSAGES_PER_CHAT = 500
    }
}
