package org.flatgram.messenger.td

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

internal class ChatListSnapshotStore(context: Context) {

    private val snapshotFile = File(context.filesDir, SNAPSHOT_FILE_NAME)
    private val executor = Executors.newSingleThreadExecutor()

    fun load(): List<ChatListItem> {
        if (!snapshotFile.exists()) return emptyList()

        return runCatching {
            val array = JSONArray(snapshotFile.readText())
            List(array.length()) { index ->
                array.getJSONObject(index).toChatListItem()
            }
        }.getOrElse {
            snapshotFile.delete()
            emptyList()
        }
    }

    fun saveAsync(items: List<ChatListItem>) {
        val snapshot = items.take(MAX_SNAPSHOT_ITEMS)
        executor.execute {
            runCatching {
                val array = JSONArray()
                snapshot.forEach { item -> array.put(item.toJson()) }
                snapshotFile.writeText(array.toString())
            }
        }
    }

    fun delete() {
        if (snapshotFile.exists()) {
            snapshotFile.delete()
        }
    }

    private fun ChatListItem.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("lastMessage", lastMessage)
            .put("time", time)
            .put("unreadCount", unreadCount)
            .put("isPinned", isPinned)
            .put("order", order)
            .apply {
                avatarFileId?.let { put("avatarFileId", it) }
                avatarPath?.let { put("avatarPath", it) }
            }
    }

    private fun JSONObject.toChatListItem(): ChatListItem {
        return ChatListItem(
            id = getLong("id"),
            title = optString("title"),
            avatarFileId = if (has("avatarFileId")) optInt("avatarFileId") else null,
            avatarPath = if (has("avatarPath")) optString("avatarPath").ifBlank { null } else null,
            lastMessage = optString("lastMessage"),
            time = optString("time"),
            unreadCount = optInt("unreadCount"),
            isPinned = optBoolean("isPinned"),
            order = optLong("order")
        )
    }

    private companion object {
        const val SNAPSHOT_FILE_NAME = "chat-list-snapshot.json"
        const val MAX_SNAPSHOT_ITEMS = 300
    }
}
