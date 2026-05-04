package org.flatgram.messenger.td

data class MessageListItem(
    val id: Long,
    val chatId: Long,
    val senderId: String,
    val senderName: String,
    val avatarFileId: Int?,
    val avatarPath: String?,
    val text: String,
    val content: MessageContentUi,
    val date: Int,
    val time: String,
    val isOutgoing: Boolean,
    val status: MessageSendStatus,
    val groupPosition: MessageBubbleGroupPosition,
    val showAvatar: Boolean
)

enum class MessageSendStatus {
    NONE,
    SENDING,
    FAILED
}

enum class MessageBubbleGroupPosition {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM
}
