package org.flatgram.messenger.td

import org.drinkless.tdlib.TdApi

object MessageContentFormatter {

    fun format(content: TdApi.MessageContent?): String {
        return when (content) {
            null -> ""
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
}
