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
import com.novelcharacter.app.data.dao.CharacterTagDao
import com.novelcharacter.app.data.dao.FieldDefinitionDao
import com.novelcharacter.app.data.dao.NovelDao
import com.novelcharacter.app.data.dao.TimelineDao
import com.novelcharacter.app.data.dao.UniverseDao
import com.novelcharacter.app.data.dao.CharacterRelationshipDao
import com.novelcharacter.app.data.dao.NameBankDao
import com.novelcharacter.app.data.dao.RecentActivityDao
import com.novelcharacter.app.data.dao.SearchPresetDao
import com.novelcharacter.app.data.dao.CharacterRelationshipChangeDao
import com.novelcharacter.app.data.dao.UserPresetTemplateDao
import com.novelcharacter.app.data.model.RecentActivity
import com.novelcharacter.app.data.model.UserPresetTemplate
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.NameBankEntry
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
        CharacterStateChange::class,
        CharacterTag::class,
        NameBankEntry::class,
        CharacterRelationship::class,
        CharacterRelationshipChange::class,
        RecentActivity::class,
        SearchPreset::class,
        UserPresetTemplate::class
    ],
    version = 14,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun characterDao(): CharacterDao
    abstract fun timelineDao(): TimelineDao
    abstract fun universeDao(): UniverseDao
    abstract fun fieldDefinitionDao(): FieldDefinitionDao
    abstract fun characterFieldValueDao(): CharacterFieldValueDao
    abstract fun characterStateChangeDao(): CharacterStateChangeDao
    abstract fun characterTagDao(): CharacterTagDao
    abstract fun nameBankDao(): NameBankDao
    abstract fun characterRelationshipDao(): CharacterRelationshipDao
    abstract fun recentActivityDao(): RecentActivityDao
    abstract fun searchPresetDao(): SearchPresetDao
    abstract fun characterRelationshipChangeDao(): CharacterRelationshipChangeDao
    abstract fun userPresetTemplateDao(): UserPresetTemplateDao

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
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_character_cross_ref_eventId` ON `timeline_character_cross_ref` (`eventId`)")

                Log.i(TAG, "Migration from version 1 to 2 completed successfully")
            }
        }

        /**
         * Migration from version 2 to 3:
         * - Added memo column to characters table
         * - Added character_tags table
         * - Added name_bank table
         * - Added character_relationships table
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 2 to 3")

                // Feature 5: Add memo column to characters
                db.execSQL("ALTER TABLE `characters` ADD COLUMN `memo` TEXT NOT NULL DEFAULT ''")

                // Feature 4: Create character_tags table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `character_tags` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `characterId` INTEGER NOT NULL,
                        `tag` TEXT NOT NULL,
                        FOREIGN KEY(`characterId`) REFERENCES `characters`(`id`) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_tags_characterId` ON `character_tags` (`characterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_tags_tag` ON `character_tags` (`tag`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_character_tags_characterId_tag` ON `character_tags` (`characterId`, `tag`)")

                // Feature 8: Create name_bank table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `name_bank` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `gender` TEXT NOT NULL DEFAULT '',
                        `origin` TEXT NOT NULL DEFAULT '',
                        `notes` TEXT NOT NULL DEFAULT '',
                        `isUsed` INTEGER NOT NULL DEFAULT 0,
                        `usedByCharacterId` INTEGER DEFAULT NULL,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`usedByCharacterId`) REFERENCES `characters`(`id`) ON DELETE SET NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_name_bank_isUsed` ON `name_bank` (`isUsed`)")

                // Feature 1: Create character_relationships table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `character_relationships` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `characterId1` INTEGER NOT NULL,
                        `characterId2` INTEGER NOT NULL,
                        `relationshipType` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`characterId1`) REFERENCES `characters`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`characterId2`) REFERENCES `characters`(`id`) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_relationships_characterId1` ON `character_relationships` (`characterId1`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_relationships_characterId2` ON `character_relationships` (`characterId2`)")

                Log.i(TAG, "Migration from version 2 to 3 completed successfully")
            }
        }

        /**
         * Migration from version 3 to 4:
         * - Added missing indexes on createdAt columns for universes, novels,
         *   character_relationships, and name_bank tables.
         * - Added missing indexes on universes.name and name_bank.usedByCharacterId.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 3 to 4")

                // Add indexes on universes table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_universes_name` ON `universes` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_universes_createdAt` ON `universes` (`createdAt`)")

                // Add createdAt index on novels table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_novels_createdAt` ON `novels` (`createdAt`)")

                // Add createdAt index on character_relationships table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_relationships_createdAt` ON `character_relationships` (`createdAt`)")

                // Add missing indexes on name_bank table
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_name_bank_usedByCharacterId` ON `name_bank` (`usedByCharacterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_name_bank_createdAt` ON `name_bank` (`createdAt`)")

                Log.i(TAG, "Migration from version 3 to 4 completed successfully")
            }
        }

        /**
         * Migration from version 4 to 5:
         * - Added code column to universes, novels, and characters tables
         *   for stable identity-based Excel round-tripping.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 4 to 5")

                // Add code columns
                db.execSQL("ALTER TABLE `universes` ADD COLUMN `code` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `code` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `characters` ADD COLUMN `code` TEXT NOT NULL DEFAULT ''")

                // Backfill existing rows with unique codes using SQLite's randomblob
                // Use randomblob(8) for 16 hex chars to avoid birthday-paradox collisions
                // with large datasets (randomblob(4) has ~1% collision chance at ~9300 rows)
                db.execSQL("UPDATE `universes` SET `code` = lower(hex(randomblob(8))) WHERE `code` = ''")
                db.execSQL("UPDATE `novels` SET `code` = lower(hex(randomblob(8))) WHERE `code` = ''")
                db.execSQL("UPDATE `characters` SET `code` = lower(hex(randomblob(8))) WHERE `code` = ''")

                // Create unique indexes
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_universes_code` ON `universes` (`code`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_novels_code` ON `novels` (`code`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_characters_code` ON `characters` (`code`)")

                Log.i(TAG, "Migration from version 4 to 5 completed successfully")
            }
        }

        /**
         * Migration from version 5 to 6:
         * - Added displayOrder column to universes, novels, and characters
         *   for user-defined card ordering.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 5 to 6")

                // Add displayOrder columns
                db.execSQL("ALTER TABLE `universes` ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `characters` ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0")

                // Backfill: set displayOrder based on existing sort order
                // Universe/Novel: createdAt DESC -> assign ascending order
                db.execSQL("""
                    UPDATE `universes` SET `displayOrder` = (
                        SELECT COUNT(*) FROM `universes` u2 WHERE u2.`createdAt` > `universes`.`createdAt`
                            OR (u2.`createdAt` = `universes`.`createdAt` AND u2.`id` < `universes`.`id`)
                    )
                """)
                db.execSQL("""
                    UPDATE `novels` SET `displayOrder` = (
                        SELECT COUNT(*) FROM `novels` n2 WHERE n2.`createdAt` > `novels`.`createdAt`
                            OR (n2.`createdAt` = `novels`.`createdAt` AND n2.`id` < `novels`.`id`)
                    )
                """)
                // Character: name ASC
                db.execSQL("""
                    UPDATE `characters` SET `displayOrder` = (
                        SELECT COUNT(*) FROM `characters` c2 WHERE c2.`name` < `characters`.`name`
                            OR (c2.`name` = `characters`.`name` AND c2.`id` < `characters`.`id`)
                    )
                """)

                Log.i(TAG, "Migration from version 5 to 6 completed successfully")
            }
        }

        /**
         * Migration from version 6 to 7:
         * - Added anotherName column to characters for aliases/alternate names.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 6 to 7")

                db.execSQL("ALTER TABLE `characters` ADD COLUMN `anotherName` TEXT NOT NULL DEFAULT ''")

                Log.i(TAG, "Migration from version 6 to 7 completed successfully")
            }
        }

        /**
         * Migration from version 7 to 8:
         * - Added borderColor, borderWidthDp to universes for card border customization
         * - Added borderColor, borderWidthDp, inheritUniverseBorder to novels
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 7 to 8")

                // Universe border customization
                db.execSQL("ALTER TABLE `universes` ADD COLUMN `borderColor` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `universes` ADD COLUMN `borderWidthDp` REAL NOT NULL DEFAULT 1.5")

                // Novel border customization with inheritance
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `borderColor` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `borderWidthDp` REAL NOT NULL DEFAULT 1.5")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `inheritUniverseBorder` INTEGER NOT NULL DEFAULT 1")

                Log.i(TAG, "Migration from version 7 to 8 completed successfully")
            }
        }

        /**
         * Migration from version 8 to 9:
         * - Added unique index on field_definitions(universeId, key)
         * - Added unique index on character_relationships(characterId1, characterId2, relationshipType)
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 8 to 9")

                // Unique constraint: one field key per universe
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_field_definitions_universeId_key` ON `field_definitions` (`universeId`, `key`)")

                // Unique constraint: no duplicate relationships between same pair with same type
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_character_relationships_characterId1_characterId2_relationshipType` ON `character_relationships` (`characterId1`, `characterId2`, `relationshipType`)")

                Log.i(TAG, "Migration from version 8 to 9 completed successfully")
            }
        }

        /**
         * Migration from version 9 to 10:
         * - Added isPinned column to characters and novels
         * - Created recent_activities table for recent work tracking
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 9 to 10")

                // Add isPinned to characters and novels
                db.execSQL("ALTER TABLE `characters` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0")

                // Create recent_activities table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `recent_activities` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `accessedAt` INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recent_activities_entityType_entityId` ON `recent_activities` (`entityType`, `entityId`)")

                Log.i(TAG, "Migration from version 9 to 10 completed successfully")
            }
        }

        /**
         * Migration from version 10 to 11:
         * - Created search_presets table for search preset storage
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 10 to 11")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `search_presets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `query` TEXT NOT NULL DEFAULT '',
                        `filtersJson` TEXT NOT NULL DEFAULT '{}',
                        `sortMode` TEXT NOT NULL DEFAULT 'relevance',
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        `isDefault` INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_search_presets_name` ON `search_presets` (`name`)")

                Log.i(TAG, "Migration from version 10 to 11 completed successfully")
            }
        }

        /**
         * Migration from version 11 to 12:
         * - Created character_relationship_changes table for temporal relationship tracking
         * - Added displayOrder and isTemporary columns to timeline_events
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 11 to 12")

                // Create character_relationship_changes table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `character_relationship_changes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `relationshipId` INTEGER NOT NULL,
                        `year` INTEGER NOT NULL,
                        `month` INTEGER,
                        `day` INTEGER,
                        `relationshipType` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `intensity` INTEGER NOT NULL DEFAULT 5,
                        `isBidirectional` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`relationshipId`) REFERENCES `character_relationships`(`id`) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_relationship_changes_relationshipId` ON `character_relationship_changes` (`relationshipId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_character_relationship_changes_year` ON `character_relationship_changes` (`year`)")

                // Add displayOrder and isTemporary to timeline_events
                db.execSQL("ALTER TABLE `timeline_events` ADD COLUMN `displayOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `timeline_events` ADD COLUMN `isTemporary` INTEGER NOT NULL DEFAULT 0")

                // Backfill displayOrder based on year ordering
                db.execSQL("""
                    UPDATE `timeline_events` SET `displayOrder` = (
                        SELECT COUNT(*) FROM `timeline_events` t2
                        WHERE t2.`year` < `timeline_events`.`year`
                            OR (t2.`year` = `timeline_events`.`year` AND t2.`id` < `timeline_events`.`id`)
                    )
                """)

                Log.i(TAG, "Migration from version 11 to 12 completed successfully")
            }
        }

        /**
         * Migration from version 12 to 13:
         * - Added updatedAt column to characters table
         * Note: Column may already exist if the app was fresh-installed at version 12
         * (Room auto-creates all columns from entity definition on fresh install).
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 12 to 13")

                // Check if column already exists before adding (idempotent migration)
                val cursor = db.query("PRAGMA table_info(characters)")
                val columns = mutableSetOf<String>()
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0) {
                        columns.add(cursor.getString(nameIndex))
                    }
                }
                cursor.close()

                if ("updatedAt" !in columns) {
                    db.execSQL("ALTER TABLE `characters` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                    // Backfill: set updatedAt = createdAt for existing rows
                    db.execSQL("UPDATE `characters` SET `updatedAt` = `createdAt` WHERE `updatedAt` = 0")
                }

                Log.i(TAG, "Migration from version 12 to 13 completed successfully")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating database from version 13 to 14")

                // 1. 사용자 정의 프리셋 템플릿 테이블
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `user_preset_templates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `fieldsJson` TEXT NOT NULL DEFAULT '[]',
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_preset_templates_name` ON `user_preset_templates` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_preset_templates_createdAt` ON `user_preset_templates` (`createdAt`)")

                // 2. 세계관에 이미지 필드 추가
                db.execSQL("ALTER TABLE `universes` ADD COLUMN `imagePath` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `universes` ADD COLUMN `imageMode` TEXT NOT NULL DEFAULT 'none'")

                // 3. 작품에 이미지 필드 추가
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `imagePath` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `imageMode` TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE `novels` ADD COLUMN `imageCharacterId` INTEGER DEFAULT NULL")

                Log.i(TAG, "Migration from version 13 to 14 completed successfully")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_character_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
