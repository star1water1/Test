package com.novelcharacter.app.data.database

import android.content.Context
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
    version = 3,
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
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // FieldDefinition에 (universeId, key) 유니크 인덱스 추가
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_field_definitions_universeId_key` ON `field_definitions` (`universeId`, `key`)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novel_character_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
