package org.flatgram.messenger.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val avatarFileId: Int?,
    val avatarPath: String?,
    val lastMessage: String,
    val time: String,
    val unreadCount: Int,
    val isPinned: Boolean,
    val order: Long,
    val updatedAt: Long
)
