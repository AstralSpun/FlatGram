package org.flatgram.messenger.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.flatgram.messenger.db.dao.ChatDao
import org.flatgram.messenger.db.entity.ChatEntity

@Database(
    entities = [ChatEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FlatGramDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var instance: FlatGramDatabase? = null

        fun get(context: Context): FlatGramDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FlatGramDatabase::class.java,
                    "flatgram.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
