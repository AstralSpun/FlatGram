package org.flatgram.messenger.td

data class ChatListItem(
    val id: Long,
    val title: String,
    val lastMessage: String,
    val time: String,
    val unreadCount: Int,
    val isPinned: Boolean,
    val order: Long
)
