package org.flatgram.messenger.td

import android.content.Context
import android.util.Log
import org.flatgram.messenger.db.FlatGramDatabase
import org.flatgram.messenger.db.entity.ChatEntity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal class RoomChatListStore(context: Context) {

    private val chatDao = FlatGramDatabase.get(context).chatDao()
    private val legacySnapshotStore = ChatListSnapshotStore(context.applicationContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val saveGeneration = AtomicLong(0L)

    fun loadAsync(onLoaded: (List<ChatListItem>) -> Unit) {
        executor.execute {
            val items = runCatching {
                val roomItems = chatDao.getTopChats(MAX_CACHED_CHATS).map { it.toChatListItem() }
                if (roomItems.isNotEmpty()) {
                    roomItems
                } else {
                    legacySnapshotStore.load().also { legacyItems ->
                        if (legacyItems.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            chatDao.upsertChats(legacyItems.map { it.toEntity(now) })
                            legacySnapshotStore.delete()
                        }
                    }
                }
            }.getOrElse { error ->
                Log.w(TAG, "Load Room chat cache failed", error)
                emptyList()
            }
            onLoaded(items)
        }
    }

    fun saveAsync(items: List<ChatListItem>) {
        val generation = saveGeneration.incrementAndGet()
        val snapshot = items.take(MAX_CACHED_CHATS)
        executor.execute {
            if (generation != saveGeneration.get()) return@execute

            runCatching {
                val now = System.currentTimeMillis()
                chatDao.upsertChats(snapshot.map { it.toEntity(now) })
            }.onFailure { error ->
                Log.w(TAG, "Save Room chat cache failed", error)
            }
        }
    }

    fun deleteAsync(chatId: Long) {
        executor.execute {
            runCatching {
                chatDao.deleteChat(chatId)
            }.onFailure { error ->
                Log.w(TAG, "Delete Room chat cache failed: $chatId", error)
            }
        }
    }

    private fun ChatListItem.toEntity(updatedAt: Long): ChatEntity {
        return ChatEntity(
            id = id,
            title = title,
            avatarFileId = avatarFileId,
            avatarPath = avatarPath,
            lastMessage = lastMessage,
            time = time,
            unreadCount = unreadCount,
            isPinned = isPinned,
            order = order,
            updatedAt = updatedAt
        )
    }

    private fun ChatEntity.toChatListItem(): ChatListItem {
        return ChatListItem(
            id = id,
            title = title,
            avatarFileId = avatarFileId,
            avatarPath = avatarPath,
            lastMessage = lastMessage,
            time = time,
            unreadCount = unreadCount,
            isPinned = isPinned,
            order = order
        )
    }

    private companion object {
        const val TAG = "RoomChatListStore"
        const val MAX_CACHED_CHATS = 1000
    }
}
