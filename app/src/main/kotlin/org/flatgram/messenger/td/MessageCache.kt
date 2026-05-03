package org.flatgram.messenger.td

import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

internal class MessageCache(
    private val maxMessagesPerChat: Int
) {
    private val messagesByChat = ConcurrentHashMap<Long, ConcurrentHashMap<Long, TdApi.Message>>()

    fun chatIds(): Set<Long> = messagesByChat.keys

    fun messages(chatId: Long): Collection<TdApi.Message> {
        return messagesByChat[chatId]?.values.orEmpty()
    }

    fun put(message: TdApi.Message): Boolean {
        val cache = messagesByChat.getOrPut(message.chatId) { ConcurrentHashMap() }
        return cache.put(message.id, message) !== message
    }

    fun update(chatId: Long, messageId: Long, block: (TdApi.Message) -> Unit): Boolean {
        val message = messagesByChat[chatId]?.get(messageId) ?: return false
        block(message)
        return true
    }

    fun replace(chatId: Long, oldMessageId: Long, message: TdApi.Message) {
        val cache = messagesByChat.getOrPut(chatId) { ConcurrentHashMap() }
        cache.remove(oldMessageId)
        cache[message.id] = message
    }

    fun removeAll(chatId: Long, messageIds: LongArray) {
        val cache = messagesByChat[chatId] ?: return
        messageIds.forEach(cache::remove)
    }

    fun oldestMessageId(chatId: Long): Long {
        return messagesByChat[chatId]
            ?.keys
            ?.asSequence()
            ?.filter { it > 0L }
            ?.minOrNull()
            ?: 0L
    }

    fun trim(chatId: Long) {
        val cache = messagesByChat[chatId] ?: return
        if (cache.size <= maxMessagesPerChat) return

        val removableIds = cache.values
            .asSequence()
            .filter { it.id > 0L }
            .sortedWith(compareBy<TdApi.Message> { it.sortTimestamp() }.thenBy { it.id })
            .take(cache.size - maxMessagesPerChat)
            .map { it.id }
            .toList()

        removableIds.forEach(cache::remove)
    }

    private fun TdApi.Message.sortTimestamp(): Int {
        return if (date == 0 && sendingState is TdApi.MessageSendingStatePending) {
            Int.MAX_VALUE
        } else {
            date
        }
    }
}
