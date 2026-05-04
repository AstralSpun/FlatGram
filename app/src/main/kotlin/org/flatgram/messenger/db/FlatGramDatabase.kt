package org.flatgram.messenger.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.flatgram.messenger.db.dao.ChatDao
import org.flatgram.messenger.db.dao.MessageDao
import org.flatgram.messenger.db.entity.ChatEntity
import org.flatgram.messenger.db.entity.MessageEntity

@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 3,
    exportSchema = false
)
abstract class FlatGramDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `chatId` INTEGER NOT NULL,
                        `id` INTEGER NOT NULL,
                        `senderKey` TEXT NOT NULL,
                        `senderName` TEXT NOT NULL,
                        `avatarFileId` INTEGER,
                        `avatarPath` TEXT,
                        `text` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `time` TEXT NOT NULL,
                        `isOutgoing` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`chatId`, `id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_chatId_date` ON `messages` (`chatId`, `date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_chatId_id` ON `messages` (`chatId`, `id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_updatedAt` ON `messages` (`updatedAt`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `contentSnapshot` TEXT")
            }
        }
    }
}
