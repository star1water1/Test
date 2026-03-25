package com.novelcharacter.app

import android.app.Application
import com.novelcharacter.app.R
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.TimelineRepository
import com.novelcharacter.app.data.repository.UniverseRepository
import com.novelcharacter.app.data.repository.NameBankRepository
import com.novelcharacter.app.data.repository.SearchPresetRepository
import com.novelcharacter.app.data.repository.FactionRepository
import com.novelcharacter.app.backup.AutoBackupWorker
import com.novelcharacter.app.backup.BackupEncryptor
import com.novelcharacter.app.backup.BackupStatusStore
import com.novelcharacter.app.notification.BirthdayWorker
import com.novelcharacter.app.util.AppLogger
import com.novelcharacter.app.util.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class NovelCharacterApp : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }
    val novelRepository by lazy { NovelRepository(database, database.novelDao()) }
    val characterRepository by lazy {
        CharacterRepository(
            database,
            database.characterDao(),
            database.characterFieldValueDao(),
            database.characterStateChangeDao(),
            database.characterTagDao(),
            database.characterRelationshipDao(),
            database.nameBankDao()
        )
    }
    val timelineRepository by lazy { TimelineRepository(database.timelineDao()) }
    val universeRepository by lazy {
        UniverseRepository(
            database,
            database.universeDao(),
            database.fieldDefinitionDao(),
            database.novelDao()
        )
    }
    val nameBankRepository by lazy { NameBankRepository(database.nameBankDao()) }
    val searchPresetRepository by lazy { SearchPresetRepository(database.searchPresetDao()) }
    val factionRepository by lazy { FactionRepository(database) }
    val recentActivityDao by lazy { database.recentActivityDao() }
    val backupStatusStore by lazy { BackupStatusStore(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        installCrashLogger()
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(filesDir)
        // Apply saved theme from SharedPreferences cache (non-blocking)
        ThemeHelper.applyTheme(ThemeHelper.getSavedTheme(this))
        // Migrate DataStore → SharedPreferences cache on first launch
        appScope.launch(Dispatchers.IO) {
            ThemeHelper.migrateCacheIfNeeded(this@NovelCharacterApp)
        }
        createNotificationChannel()
        checkBackupKeyAvailability()
        try {
            scheduleBirthdayCheck()
            scheduleAutoBackup()
        } catch (e: Exception) {
            android.util.Log.e("NovelCharacterApp", "Failed to schedule background workers", e)
        }
        // 생존여부↔사망연도 연동 데이터 마이그레이션 (1회)
        migrateAliveSyncIfNeeded()
    }

    private fun migrateAliveSyncIfNeeded() {
        val prefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
        if (prefs.getBoolean("alive_sync_migrated", false)) return

        appScope.launch(Dispatchers.IO) {
            try {
                val db = database
                val allUniverses = db.universeDao().getAllUniversesList()

                for (universe in allUniverses) {
                    val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
                    // alive 필드 찾기: key="alive" + type="SELECT" + semanticRole 없음
                    val aliveField = fields.find { f ->
                        f.key == "alive" && f.type == "SELECT" &&
                            com.novelcharacter.app.data.model.SemanticRole.fromConfig(f.config) == null
                    }
                    if (aliveField != null) {
                        // semanticRole + 매핑 자동 부여
                        val config = try { org.json.JSONObject(aliveField.config) } catch (_: Exception) { org.json.JSONObject() }
                        config.put("semanticRole", "alive")
                        val options = config.optJSONArray("options")
                        if (options != null && options.length() >= 2) {
                            config.put("aliveValue", options.getString(0))
                            config.put("deadValue", options.getString(1))
                        }
                        db.fieldDefinitionDao().update(aliveField.copy(config = config.toString()))
                    }

                    // 이제 semanticRole이 alive인 필드 (방금 업데이트 포함)
                    val updatedFields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
                    val aliveFieldFinal = updatedFields.find {
                        com.novelcharacter.app.data.model.SemanticRole.fromConfig(it.config) == com.novelcharacter.app.data.model.SemanticRole.ALIVE
                    } ?: continue
                    val aliveConfig = try { org.json.JSONObject(aliveFieldFinal.config) } catch (_: Exception) { continue }
                    val aliveVal = aliveConfig.optString("aliveValue", "")
                    val deadVal = aliveConfig.optString("deadValue", "")
                    if (aliveVal.isBlank() || deadVal.isBlank()) continue

                    // 해당 세계관 캐릭터 처리
                    val novels = db.novelDao().getNovelsByUniverseList(universe.id)
                    for (novel in novels) {
                        val characters = db.characterDao().getCharactersByNovelList(novel.id)
                        for (char in characters) {
                            val changes = db.characterStateChangeDao().getChangesByCharacterList(char.id)
                            val hasDeath = changes.any { it.fieldKey == com.novelcharacter.app.data.model.CharacterStateChange.KEY_DEATH }
                            val hasBirth = changes.any { it.fieldKey == com.novelcharacter.app.data.model.CharacterStateChange.KEY_BIRTH }
                            val hasAlive = changes.any { it.fieldKey == com.novelcharacter.app.data.model.CharacterStateChange.KEY_ALIVE }

                            if (hasAlive) continue // 이미 __alive 있으면 스킵

                            val currentValue = db.characterFieldValueDao().getValue(char.id, aliveFieldFinal.id)

                            if (hasDeath) {
                                // 사망연도 있는 캐릭터 → alive=사망 + __alive="dead"
                                if (currentValue == null || currentValue.value != deadVal) {
                                    if (currentValue != null) {
                                        db.characterFieldValueDao().update(currentValue.copy(value = deadVal))
                                    } else {
                                        db.characterFieldValueDao().insert(
                                            com.novelcharacter.app.data.model.CharacterFieldValue(
                                                characterId = char.id, fieldDefinitionId = aliveFieldFinal.id, value = deadVal
                                            )
                                        )
                                    }
                                }
                                db.characterStateChangeDao().insert(
                                    com.novelcharacter.app.data.model.CharacterStateChange(
                                        characterId = char.id, year = 0,
                                        fieldKey = com.novelcharacter.app.data.model.CharacterStateChange.KEY_ALIVE,
                                        newValue = "dead"
                                    )
                                )
                            } else if (hasBirth && (currentValue == null || currentValue.value.isBlank())) {
                                // 출생연도만 있고 alive 비어있음 → alive=생존 + __alive="alive"
                                if (currentValue != null) {
                                    db.characterFieldValueDao().update(currentValue.copy(value = aliveVal))
                                } else {
                                    db.characterFieldValueDao().insert(
                                        com.novelcharacter.app.data.model.CharacterFieldValue(
                                            characterId = char.id, fieldDefinitionId = aliveFieldFinal.id, value = aliveVal
                                        )
                                    )
                                }
                                db.characterStateChangeDao().insert(
                                    com.novelcharacter.app.data.model.CharacterStateChange(
                                        characterId = char.id, year = 0,
                                        fieldKey = com.novelcharacter.app.data.model.CharacterStateChange.KEY_ALIVE,
                                        newValue = "alive"
                                    )
                                )
                            }
                        }
                    }
                }

                prefs.edit().putBoolean("alive_sync_migrated", true).apply()
                android.util.Log.i("NovelCharacterApp", "Alive sync migration completed")
            } catch (e: Exception) {
                android.util.Log.e("NovelCharacterApp", "Alive sync migration failed", e)
            }
        }
    }

    /**
     * 릴리스 빌드 크래시 디버깅용.
     * 크래시 발생 시 스택 트레이스를 /data/data/<pkg>/files/crash_log.txt 에 저장.
     * 설정 > 크래시 로그 확인 또는 파일 탐색기로 확인 가능.
     */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = java.io.File(filesDir, "crash_log.txt")
                // 기존 로그가 1MB 초과 시 초기화 (무한 증가 방지)
                if (logFile.exists() && logFile.length() > 1024 * 1024) {
                    logFile.delete()
                }
                logFile.appendText(buildString {
                    appendLine("=== Crash at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} ===")
                    appendLine("Thread: ${thread.name}")
                    appendLine(throwable.stackTraceToString())
                })
            } catch (_: Exception) {
                // 로그 저장 실패 시 무시
            }
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                Runtime.getRuntime().exit(1)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            BIRTHDAY_CHANNEL_ID,
            getString(R.string.notification_channel_birthday_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_birthday_desc)
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(channel)
    }

    private fun scheduleBirthdayCheck() {
        val workRequest = PeriodicWorkRequestBuilder<BirthdayWorker>(
            1, TimeUnit.DAYS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "birthday_check",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun scheduleAutoBackup() {
        val workRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            1, TimeUnit.DAYS
        ).setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "auto_backup",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun checkBackupKeyAvailability() {
        val backupDir = java.io.File(filesDir, "backups")
        val hasBackups = backupDir.exists() && (backupDir.listFiles()?.isNotEmpty() == true)
        if (hasBackups && !BackupEncryptor.isKeyAvailable()) {
            android.util.Log.w("NovelCharacterApp",
                "Backup encryption key is missing! Previous backups cannot be decrypted. " +
                "This may happen after a factory reset or KeyStore wipe.")
        }
    }

    companion object {
        const val BIRTHDAY_CHANNEL_ID = "birthday_channel"
    }
}
