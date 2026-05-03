package org.flatgram.messenger.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "messages",
    primaryKeys = ["chatId", "id"],
    indices = [
        Index(value = ["chatId", "date"]),
        Index(value = ["chatId", "id"]),
        Index(value = ["updatedAt"])
    ]
)
data class MessageEntity(
    val chatId: Long,
    val id: Long,
    val senderKey: String,
    val senderName: String,
    val avatarFileId: Int?,
    val avatarPath: String?,
    val text: String,
    val date: Int,
    val time: String,
    val isOutgoing: Boolean,
    val status: String,
    val updatedAt: Long
)
