package org.flatgram.messenger.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.flatgram.messenger.db.entity.MessageEntity

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id DESC LIMIT :limit")
    fun getLatestMessages(chatId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id < :fromMessageId ORDER BY id DESC LIMIT :limit")
    fun getOlderMessages(chatId: Long, fromMessageId: Long, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id = :messageId")
    fun deleteMessage(chatId: Long, messageId: Long)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id IN (:messageIds)")
    fun deleteMessages(chatId: Long, messageIds: List<Long>)

    @Query(
        """
        DELETE FROM messages
        WHERE chatId = :chatId
        AND id NOT IN (
            SELECT id FROM messages WHERE chatId = :chatId ORDER BY id DESC LIMIT :keepCount
        )
        """
    )
    fun trimChat(chatId: Long, keepCount: Int)
}
