package com.novelcharacter.app.data.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.novelcharacter.app.data.dao.CharacterDao
import com.novelcharacter.app.data.dao.CharacterFieldValueDao
import com.novelcharacter.app.data.dao.CharacterStateChangeDao
import com.novelcharacter.app.data.dao.FieldDefinitionDao
import com.novelcharacter.app.data.dao.NovelDao
import com.novelcharacter.app.data.dao.TimelineDao
import com.novelcharacter.app.data.dao.UniverseDao
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe

@Database(
    entities = [
        Novel::class,
        Character::class,
        TimelineEvent::class,
        TimelineCharacterCrossRef::class,
        Universe::class,
        FieldDefinition::class,
        CharacterFieldValue::class,
        CharacterStateChange::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun characterDao(): CharacterDao
    abstract fun timelineDao(): TimelineDao
    abstract fun universeDao(): UniverseDao
    abstract fun fieldDefinitionDao(): FieldDefinitionDao
    abstract fun characterFieldValueDao(): CharacterFieldValueDao
    abstract fun characterStateChangeDao(): CharacterStateChangeDao

    companion object {
        private const val TAG = "AppDatabase"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from version 1 to 2:
         * Added universes, field_definitions, character_field_values,
         * character_state_changes tables and timeline_character_cross_ref.
         * Added universeId column to novels and timeline_events.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 1 to 2")

                // Create universes table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `universes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // Add universeId column to novels
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `universeId` INTEGER DEFAULT NULL REFERENCES `universes`(`id`) ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_novels_universeId` ON `novels` (`universeId`)")

                // Add universeId column to timeline_events
                db.execSQL("ALTER TABLE `timeline_events` ADD COLUMN `universeId` INTEGER DEFAULT NULL REFERENCES `universes`(`id`) ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_events_universeId` ON `timeline_events` (`universeId`)")

                // Create field_definitions table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `field_definitions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `universeId` INTEGER NOT NULL,
                        `key` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `config` TEXT NOT NULL DEFAULT '{}',
                        `groupName` TEXT NOT NULL DEFAULT '기본 정보',
                        `displayOrder` INTEGER NOT NULL DEFAULT 0,
                        `isRequired` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`universeId`) REFERENCES `universes`(`id`) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_field_definitions_universeId` ON `field_definitions` (`universeId`)")

                // Create character_field_values table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `character_field_values` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `characterId` INTEGER NOT NULL,
                        `fieldDefinitionId` INTEGER NOT NULL,
                        `value` TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(`characterId`) REFERENCES `characters`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`fieldDefinitionId`) REFERENCES `field_definitions`(`id`) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_character_field_values_characterId_fieldDefinitionId` ON `character_field_values` (`characterId`, `fieldDefinitionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_field_values_characterId` ON `character_field_values` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_field_values_fieldDefinitionId` ON `character_field_values` (`fieldDefinitionId`)")

                // Create character_state_changes table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `character_state_changes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `characterId` INTEGER NOT NULL,
                        `year` INTEGER NOT NULL,
                        `month` INTEGER,
                        `day` INTEGER,
                        `fieldKey` TEXT NOT NULL,
                        `newValue` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`characterId`) REFERENCES `characters`(`id`) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_state_changes_characterId` ON `character_state_changes` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_state_changes_year` ON `character_state_changes` (`year`)")

                // Create timeline_character_cross_ref table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `timeline_character_cross_ref` (
                        `eventId` INTEGER NOT NULL,
                        `characterId` INTEGER NOT NULL,
                        PRIMARY KEY(`eventId`, `characterId`),
                        FOREIGN KEY(`eventId`) REFERENCES `timeline_events`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`characterId`) REFERENCES `characters`(`id`) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_character_cross_ref_characterId` ON `timeline_character_cross_ref` (`characterId`)")

                Log.i(TAG, "Migration from version 1 to 2 completed successfully")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_character_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
