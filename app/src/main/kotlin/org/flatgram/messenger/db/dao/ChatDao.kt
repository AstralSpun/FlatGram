package org.flatgram.messenger.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.flatgram.messenger.db.entity.ChatEntity

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY isPinned DESC, `order` DESC, id DESC LIMIT :limit")
    fun getTopChats(limit: Int): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertChats(chats: List<ChatEntity>)

    @Query("DELETE FROM chats WHERE id = :chatId")
    fun deleteChat(chatId: Long)
}
