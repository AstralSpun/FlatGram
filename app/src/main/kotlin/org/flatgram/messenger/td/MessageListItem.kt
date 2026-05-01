package org.flatgram.messenger.td

data class MessageListItem(
    val id: Long,
    val chatId: Long,
    val text: String,
    val time: String,
    val isOutgoing: Boolean,
    val status: MessageSendStatus
)

enum class MessageSendStatus {
    NONE,
    SENDING,
    FAILED
}
