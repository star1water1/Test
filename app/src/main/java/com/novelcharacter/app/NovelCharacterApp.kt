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
import com.novelcharacter.app.data.repository.CharacterListPresetRepository
import com.novelcharacter.app.data.repository.FactionRepository
import com.novelcharacter.app.data.repository.TrashRepository
import com.novelcharacter.app.data.repository.OperationLogRepository
import com.novelcharacter.app.data.repository.FieldValueLibraryRepository
import com.novelcharacter.app.data.repository.DuelRepository
import com.novelcharacter.app.data.repository.DefaultFieldTemplateRepository
import com.novelcharacter.app.backup.AutoBackupWorker
import com.novelcharacter.app.backup.BackupEncryptor
import com.novelcharacter.app.backup.BackupStatusStore
import com.novelcharacter.app.notification.BirthdayWorker
import com.novelcharacter.app.data.maintenance.LegacyValueFormats
import com.novelcharacter.app.util.AppLogger
import com.novelcharacter.app.util.ThemeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.util.SemanticAlivePrecedence
import com.novelcharacter.app.util.SingletonStateChanges
import com.novelcharacter.app.util.stringOr

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
    val timelineRepository by lazy { TimelineRepository(database) }
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
    val characterListPresetRepository by lazy { CharacterListPresetRepository(database.characterListPresetDao()) }
    val factionRepository by lazy { FactionRepository(database) }
    val trashRepository by lazy { TrashRepository(database) }
    val operationLogRepository by lazy { OperationLogRepository(database) }
    val fieldValueLibraryRepository by lazy { FieldValueLibraryRepository(database) }
    val duelRepository by lazy { DuelRepository(database) }
    val defaultFieldTemplateRepository by lazy { DefaultFieldTemplateRepository(database) }
    val recentActivityDao by lazy { database.recentActivityDao() }
    val backupStatusStore by lazy { BackupStatusStore(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * **창보다 오래 사는 쓰기** — 화면이 죽어도 끝까지 가야 하는 작업만 여기서 돈다.
     *
     * `Fragment.lifecycleScope`는 회전 한 번에 통째로 취소된다. 그 스코프에서
     * *"파괴적 쓰기 → 그 근거를 전달"*을 이어 하면, **파괴만 커밋되고 전달이 잘리는**
     * 창이 열린다 — 필드 타입 변경의 '호환 불가 값 초기화'가 실제로 그 모양이었다
     * (값 N개는 지워졌는데 타입은 그대로였고, 아무 고지도 없었다. 2026.08.22).
     *
     * 남용하지 말 것 — 여기서 도는 일은 **취소할 방법이 없다.** 사용자가 이미 동의한
     * 되돌릴 수 없는 쓰기에만 쓴다.
     *
     * 예외는 삼켜 로그로 남긴다. `SupervisorJob`이라도 이 스코프의 비보호 코루틴에서
     * 던지면 앱이 즉사한다(이 파일의 다른 자리들이 같은 이유로 `try`를 두르고 있다).
     */
    fun runDetached(tag: String, block: suspend () -> Unit) {
        appScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                AppLogger.error(tag, "detached work failed", e)
            }
        }
    }

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
        // 업데이트 후 첫 실행에만 타는 경로 — DataStore 파일 손상 등으로 예외가 나면
        // appScope의 비보호 코루틴이라 앱이 즉사하므로 반드시 감싼다 (실패 시 시스템 테마 폴백, 다음 실행에 재시도)
        appScope.launch(Dispatchers.IO) {
            try {
                ThemeHelper.migrateCacheIfNeeded(this@NovelCharacterApp)
            } catch (e: Exception) {
                android.util.Log.e("NovelCharacterApp", "Theme cache migration failed — falling back to system theme", e)
            }
        }
        // 휴지통 보관 정책을 정리 경로가 읽을 수 있는 자리에 올린다 (B-74).
        // **읽기 전까지 정리는 통째로 건너뛴다** — 기본값으로 대신 정리하면 사용자가 올려 둔
        // 한도를 모른 채 영구 삭제하게 되고, 그것은 되돌릴 수 없다(TrashRetentionPolicy).
        // 그래서 실패해도 앱을 죽이지 않고 조용히 비워 둔다 — 다음 실행이 다시 시도한다.
        appScope.launch(Dispatchers.IO) {
            com.novelcharacter.app.data.settings.TrashSettingsStore(this@NovelCharacterApp)
                .primeQuietly()
        }
        createNotificationChannel()
        // **콜드 스타트의 메인 스레드에서 빼낸다.** 하는 일은 `listFiles()`(디스크)와
        // `BackupEncryptor.isKeyAvailable()`(AndroidKeyStore — 키스토어 데몬과의 바인더 왕복)인데
        // **산출은 `Log.w` 한 줄뿐**이라, 그 대가를 첫 프레임 앞에 둘 이유가 없다.
        // 바로 위 `TrashSettingsStore.primeQuietly()`가 이미 같은 판단으로 이 스코프에 있다.
        appScope.launch(Dispatchers.IO) { checkBackupKeyAvailability() }
        try {
            scheduleBirthdayCheck()
            scheduleAutoBackup()
        } catch (e: Exception) {
            android.util.Log.e("NovelCharacterApp", "Failed to schedule background workers", e)
        }
        // 생존여부↔사망연도 연동 데이터 마이그레이션 (1회)
        migrateAliveSyncIfNeeded()
        // 밑줄 키 이력의 둘째 줄 정리 (1회)
        repairSingletonStateChangesIfNeeded()
        // 옛 경로가 남긴 규격 밖 값 정리 (1회) — 역전된 수정일 · '#' 빠진 색 · 규격 밖 생일
        repairLegacyValueFormatsIfNeeded()
        // 무소속 캐릭터의 시맨틱 이력 소급 (1회) — 살아 있는 경로가 그들을 빼고 있었다
        backfillUnclassifiedSemanticStateIfNeeded()
        // 필드 데이터 라이브러리 백필 시드 (1회) + 중단된 임포트 후 수확 재시도
        seedFieldValueLibraryIfNeeded()
        // 캐릭터 자동 링크 최초 정리 (1회)
        seedCharacterAutoLinkIfNeeded()
    }

    /**
     * 캐릭터 자동 링크(G4) 최초 백필: 업데이트 직후 기존 캐릭터들의 이미지가 저장을 기다리지
     * 않고 바로 묶이도록 전체 재동기화를 1회 수행한다. 이후에는 저장·배정·가져오기 등 각
     * 진입점이 유지한다. 성공해야만 플래그를 세워 중단 시 다음 실행에 재시도된다(멱등 —
     * 재동기화는 몇 번을 돌려도 같은 상태로 수렴한다). 설정이 꺼져 있으면 하지 않되 플래그도
     * 세우지 않아, 나중에 켜는 순간의 정리(설정 다이얼로그)와 어긋나지 않는다.
     */
    private fun seedCharacterAutoLinkIfNeeded() {
        val prefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
        if (prefs.getBoolean("auto_link_seeded", false)) return

        appScope.launch(Dispatchers.IO) {
            try {
                val result = com.novelcharacter.app.util.CharacterImageAutoLinker
                    .resyncIfEnabled(this@NovelCharacterApp, database)
                if (result != null) {
                    prefs.edit().putBoolean("auto_link_seeded", true).apply()
                    android.util.Log.i("NovelCharacterApp",
                        "Character auto-link seed: ${result.linked} linked, ${result.released} released")
                }
            } catch (e: Exception) {
                android.util.Log.e("NovelCharacterApp", "Character auto-link seed failed — will retry on next launch", e)
            }
        }
    }

    /**
     * 필드 데이터 라이브러리 초기 백필:
     * 1) 구 FieldStatsConfig.valueLabels/valueCategories → 엔트리 이관 (필드 단위 트랜잭션,
     *    빈 칸만 채우는 병합 — 수확 훅과 경합해도 라벨이 유실되지 않는다)
     * 2) 기존 캐릭터·사건 값 + 상태변화 이력 전체 수확
     * 3) 전 필드 usageCount 재계산 (diff-only)
     * 성공해야만 플래그를 세워, 중단 시 다음 실행에 재시도된다 (멱등: 이관은 병합 UPDATE,
     * 수확은 INSERT OR IGNORE). 별도로 harvest_pending 플래그는 엑셀 임포트가 커밋 후
     * 수확 전에 죽었을 때의 재시도 신호다.
     */
    private fun seedFieldValueLibraryIfNeeded() {
        val prefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
        val seeded = prefs.getBoolean("field_library_seeded", false)
        val harvestPending = prefs.getBoolean("field_library_harvest_pending", false)
        if (seeded && !harvestPending) return

        appScope.launch(Dispatchers.IO) {
            try {
                val repo = fieldValueLibraryRepository
                if (!seeded) {
                    var migrated = 0
                    for (fd in database.fieldDefinitionDao().getAllFieldsAllTypes()) {
                        migrated += repo.seedFromStatsConfig(fd)
                    }
                    android.util.Log.i("NovelCharacterApp", "Field library seed: $migrated entries migrated from valueLabels/valueCategories")
                }
                // throwing 변형 사용 — 실패가 삼켜진 채 플래그가 기록되면 재시도 계약이 깨진다
                repo.harvestAllOrThrow()
                // 배치 재집계 — 필드별 호출과 결과는 같고 쿼리 왕복만 줄인다.
                // 플래그를 세우기 전에 **끝나 있어야** 하므로 예약이 아니라 동기 호출이다.
                repo.recountUsageForFieldsOrThrow(
                    database.fieldDefinitionDao().getAllFieldsAllTypes().map { it.id }
                )
                prefs.edit()
                    .putBoolean("field_library_seeded", true)
                    .putBoolean("field_library_harvest_pending", false)
                    .apply()
                android.util.Log.i("NovelCharacterApp", "Field library seed/harvest completed")
            } catch (e: Exception) {
                android.util.Log.e("NovelCharacterApp", "Field library seed failed — will retry on next launch", e)
            }
        }
    }

    /**
     * **밑줄 키 이력의 둘째 줄 정리** (1회, 2026.08.23).
     *
     * `__birth`·`__death`·`__alive`는 캐릭터당 한 행이 불변식인데([SingletonStateChanges])
     * **DB가 그것을 강제하지 않는다.** 2026.08.22 이전의 일괄 삽입은 같은 (캐릭터, 키)를
     * 두 줄로 넣을 수 있었고, 그 수리에는 **소급이 없었다** — 이미 생긴 둘째 줄은 그대로
     * 남아, 같은 캐릭터의 나이가 프로필(165)과 연표(163)에서 갈렸다.
     * (실측: 사용자가 내보낸 파일에서 두 캐릭터가 그 모양이었다. 두 줄의 생성 시각이
     * 밀리초까지 같아 그 일괄 삽입이 범인이라는 것도 그 파일이 보여 줬다.)
     *
     * **읽는 쪽은 이미 정본 하나를 보게 고쳤다** — 이 정리가 없어도 답은 갈리지 않는다.
     * 그래도 지우는 이유는 둘이다: ⓐ 생일 알림은 `__birth` 행을 *전부* 훑으므로
     * (`BirthdayHelper.todayBirthdays`) 둘째 줄의 월·일이 남아 있으면 엉뚱한 날 축하 창이
     * 뜬다. ⓑ 내보낸 파일이 안내와 어긋난 채(*"캐릭터당 한 행"*) 나간다.
     *
     * **휴지통에 담지 않는다** — 파생 행의 정리는 스냅샷을 남기지 않는다는 규약 그대로다
     * (`SemanticFieldSyncHelper.deleteStateChangeByKey`의 근거와 같다: 이 행을 만든 원인의
     * 스냅샷이 이미 있고, 여기서 또 담으면 사용자가 지운 적 없는 항목이 휴지통에 쌓인다).
     * 대신 **무엇을 지웠는지 로그에 남긴다** — 말없이 사라지지는 않는다(개발 의도 2번).
     * 사용자는 설정 > 로그에서 그 줄을 읽을 수 있다.
     *
     * 성공해야만 플래그를 세운다 — 중단되면 다음 실행이 다시 돈다(멱등: 정본은 늘 같은 행이다).
     */
    private fun repairSingletonStateChangesIfNeeded() {
        val prefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
        if (prefs.getBoolean("singleton_state_repaired", false)) return

        appScope.launch(Dispatchers.IO) {
            try {
                val dao = database.characterStateChangeDao()
                val strays = dao.getAllChangesList()
                    .groupBy { it.characterId }
                    .values
                    .flatMap { SingletonStateChanges.strays(it) }
                for (stray in strays) {
                    AppLogger.warn(
                        "SingletonStateRepair",
                        "캐릭터 ${stray.characterId}의 '${stray.fieldKey}' 둘째 줄을 지웠습니다 " +
                            "(연도 ${stray.year}, 값 '${stray.newValue}', 코드 ${stray.code ?: "-"}) — " +
                            "이 키는 캐릭터당 한 행이라 정본 한 줄만 남깁니다"
                    )
                    dao.delete(stray)
                }
                prefs.edit().putBoolean("singleton_state_repaired", true).apply()
                android.util.Log.i(
                    "NovelCharacterApp",
                    "Singleton state-change repair completed (${strays.size} stray rows removed)"
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "NovelCharacterApp",
                    "Singleton state-change repair failed — will retry on next launch", e
                )
            }
        }
    }

    /**
     * **옛 경로가 남긴 규격 밖 값 정리** (1회, 2026.08.24).
     *
     * 무엇을 왜 고치는지는 [LegacyValueFormats]가 든다 — SQL이 그쪽에 사는 것은
     * `tools/check_dao_sql_twins.sh`가 살아 있는 SQL의 자리를 못박아 두었기 때문이다.
     * 여기는 **1회 플래그와 고지**만 든다.
     *
     * 성공해야만 플래그를 세운다 — 중단되면 다음 실행이 다시 돈다(멱등이라 같은 상태로 수렴한다).
     * **말없이 고치지 않는다**: 무엇을 몇 건 고쳤는지 로그에 남기고, 사용자는 설정 > 로그에서
     * 그 줄을 읽는다(개발 의도 2번). 형제 정리들과 같은 규약이다.
     */
    private fun repairLegacyValueFormatsIfNeeded() {
        val prefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
        // **플래그가 v2다** — 2026.08.24에 ③(0이 빠진 생일)이 늘었는데, v1을 이미 마친 설치는
        // 그 플래그 때문에 다시 돌지 않아 새 항목만 조용히 건너뛴다. 세 항목이 전부 멱등이라
        // (그 object의 KDoc) 한 번 더 도는 값은 0건이고, 새 항목만 실제로 잡힌다.
        if (prefs.getBoolean("legacy_value_formats_repaired_v2", false)) return

        appScope.launch(Dispatchers.IO) {
            try {
                val repaired = LegacyValueFormats.repair(database.openHelper.writableDatabase)
                if (repaired.timestamps > 0) {
                    AppLogger.warn(
                        "LegacyValueRepair",
                        "수정일이 생성일보다 이른 행 ${repaired.timestamps}건의 수정일을 생성일로 올렸습니다 " +
                            "— 그 행이 만들어지기 전에 고쳐졌다는 뜻이 되어 정렬이 틀렸습니다"
                    )
                }
                if (repaired.colors > 0) {
                    AppLogger.warn(
                        "LegacyValueRepair",
                        "'#'이 빠진 색 ${repaired.colors}건에 '#'을 붙였습니다 — 색은 그대로이고 표기만 맞췄습니다"
                    )
                }
                if (repaired.birthDates > 0) {
                    AppLogger.warn(
                        "LegacyValueRepair",
                        "0이 빠진 생일 ${repaired.birthDates}건을 MM-DD로 맞췄습니다 " +
                            "— 날짜는 그대로이고 표기만 맞췄습니다(연표 이력은 이미 그 모양이었습니다)"
                    )
                }
                prefs.edit()
                    .putBoolean("legacy_value_formats_repaired", true)
                    .putBoolean("legacy_value_formats_repaired_v2", true)
                    .apply()
                android.util.Log.i(
                    "NovelCharacterApp",
                    "Legacy value format repair completed " +
                        "(timestamps=${repaired.timestamps}, colors=${repaired.colors}, " +
                        "birthDates=${repaired.birthDates})"
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "NovelCharacterApp",
                    "Legacy value format repair failed — will retry on next launch", e
                )
            }
        }
    }

    /**
     * **무소속 캐릭터의 시맨틱 이력 소급** (1회, 2026.08.24).
     *
     * 살아 있는 경로가 무소속을 통째로 빼고 있었다 — 저장·일괄 편집·가져오기가 전부
     * `if (universeId != null)`로 시맨틱 동기화를 감쌌기 때문이다
     * ([UniverseRepository.getFieldsForCharacterScope]의 KDoc이 그 경위를 든다).
     * 그 가드를 걷어도 **이미 어긋난 데이터는 그대로 남는다** — 사용자가 그 캐릭터를 다시
     * 저장해야만 맞춰지는데, 무엇이 어긋났는지 화면에 단서가 없어 다시 저장할 이유가 없다.
     * (실측 2026.08.24: 사용자 파일의 미분류 2명이 생일을 든 채 `__birth` 행이 없었다 —
     * 생일이 있는 47명 중 정확히 그 둘뿐이었고, 둘 다 무소속이었다.)
     *
     * **소급의 몸통은 살아 있는 경로 그 자체다** — [SemanticFieldSyncHelper.syncFieldToStateChange]를
     * 무소속 구역(`null`)으로 부른다. 여기서 대응 규칙을 따로 적으면 그 벌이 언젠가 갈린다
     * (`migrateAliveSyncIfNeeded`가 값→표식 대응을 따로 적었다가 갈렸던 자리 — 그 KDoc).
     *
     * **지우지 않는다.** `clearableFieldIds`를 넘기지 않으므로 이 소급은 **없는 행을 세우기만**
     * 한다(그 인자가 null이면 비움 판정 자체를 하지 않는다 — 그쪽 KDoc). 사용자가 앱에서
     * 지운 이력이 이 정리로 되살아나거나, 값 없는 칸 때문에 있던 이력이 사라지는 일이 없다.
     *
     * 성공해야만 플래그를 세운다 — 중단되면 다음 실행이 다시 돈다(멱등: 같은 값이면 같은 행).
     */
    private fun backfillUnclassifiedSemanticStateIfNeeded() {
        val prefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
        if (prefs.getBoolean("unclassified_semantic_backfilled", false)) return

        appScope.launch(Dispatchers.IO) {
            try {
                // **작품이 없는 캐릭터만이 아니다** — 세계관 없는 작품에 든 캐릭터도 전역
                // 구역을 쓴다(그 질의의 KDoc). `getUnclassifiedCharacterIds`를 쓰면 그 갈래가
                // 소급에서 빠지는데, 살아 있는 경로는 이제 그들도 전역 구역으로 돈다.
                val ids = database.characterDao().getGlobalScopeCharacterIds()
                if (ids.isNotEmpty()) {
                    val helper = com.novelcharacter.app.util.SemanticFieldSyncHelper(
                        characterRepository, universeRepository, novelRepository
                    )
                    // 값은 **한 번에** 뜬다 — 무소속이 수백일 수 있고, 캐릭터마다 물으면
                    // 그 수만큼 질의가 늘어난다(받쳐주는 확장성). 통로는 저장소 공통이다.
                    val valuesById = characterRepository.getValuesForCharacters(ids)
                        .groupBy { it.characterId }
                    for (id in ids) {
                        val values = valuesById[id].orEmpty()
                        if (values.isEmpty()) continue
                        helper.syncFieldToStateChange(id, null, values)
                    }
                }
                prefs.edit().putBoolean("unclassified_semantic_backfilled", true).apply()
                android.util.Log.i(
                    "NovelCharacterApp",
                    "Unclassified semantic backfill completed (${ids.size} characters)"
                )
            } catch (e: Exception) {
                android.util.Log.e(
                    "NovelCharacterApp",
                    "Unclassified semantic backfill failed — will retry on next launch", e
                )
            }
        }
    }

    private fun migrateAliveSyncIfNeeded() {
        val prefs = getSharedPreferences("app_migrations", MODE_PRIVATE)
        // **플래그가 v2다** — 종전 갈래가 *사망·출생 이력이 있는가*만 보고 표식을 정해,
        // **생존여부 칸은 채워져 있는데 이력이 하나도 없는 캐릭터**를 통째로 건너뛰었다.
        // 이미 마친 설치에는 그 구멍이 남아 있으므로(실측: 사용자 데이터에서 두 명) 한 번 더 돈다.
        if (prefs.getBoolean("alive_sync_migrated_v2", false)) return
        // **두 번째 실행이 하는 일은 [syncAliveFor]뿐이다.** 아래 필드 정의 손질
        // (`semanticRole`·`aliveValue`·`deadValue`를 **선택지 차례로** 부여하는 것)은
        // *한 번만* 도는 것을 전제로 쓰인 heuristic이라, 다시 돌면 v1 이후에 만들어진 세계관까지
        // 훑는다. 선택지가 [생존, 사망] 차례가 아닌 필드에서는 그 부여가 **뒤집힌 짝**을 만들고,
        // 그 뒤 [syncAliveFor]가 뒤집힌 표식을 적고 **필드값까지 덮어쓴다** — 되돌릴 자리가 없다.
        // 그래서 v1을 이미 마친 설치에서는 손질을 건너뛴다(콜드 검토 2026.08.24).
        val firstRun = !prefs.getBoolean("alive_sync_migrated", false)

        appScope.launch(Dispatchers.IO) {
            try {
                val db = database
                val allUniverses = db.universeDao().getAllUniversesList()

                for (universe in allUniverses) {
                    val fields = db.fieldDefinitionDao().getFieldsByUniverseList(universe.id)
                    // alive 필드 찾기: key="alive" + type="SELECT" + semanticRole 없음
                    val aliveField = if (!firstRun) null else fields.find { f ->
                        f.key == "alive" && f.fieldType == FieldType.SELECT &&
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
                    val aliveVal = aliveConfig.stringOr("aliveValue", "")
                    val deadVal = aliveConfig.stringOr("deadValue", "")
                    if (aliveVal.isBlank() || deadVal.isBlank()) continue

                    // 해당 세계관 캐릭터 처리 — **작품을 거치지 않고 한 질의로 모은다.**
                    // 종전에는 작품마다 캐릭터를 따로 물어 질의가 작품 수만큼 늘었다.
                    val characters = db.characterDao().getCharactersByUniverseList(universe.id)
                    syncAliveFor(characters, aliveFieldFinal, aliveVal, deadVal)
                }

                // **미분류(작품 미배정) 캐릭터** — 전역 구역의 alive 필드로 같은 셈을 돈다.
                //
                // 종전에는 이 갈래가 아예 없었다. 위 반복이 *작품을 거쳐서만* 캐릭터에 닿으므로
                // `novelId IS NULL`인 캐릭터는 **어떤 반복에도 들어오지 못했고**, 사망 이력이
                // 있어도 `__alive`가 안 생겨 연표·통계·필터에서 그 캐릭터만 생존자로 남았다.
                // 그리고 아래 플래그가 서면 다시는 돌지 않는다.
                //
                // 이 저장소는 같은 부류를 이미 글자로 적어 두었다 — `CharacterStateChangeDao`의
                // *"NULL 외래키는 내부 조인에서 떨어지므로 …는 이 캐릭터들을 절대 만나지 못한다"*(B-13).
                // 거기서는 미분류 갈래를 따로 만들었는데 이 마이그레이션만 그 갈래가 없었다.
                runCatching {
                    val globalAlive = db.fieldDefinitionDao().getGlobalFieldsList()
                        .find {
                            com.novelcharacter.app.data.model.SemanticRole.fromConfig(it.config) ==
                                com.novelcharacter.app.data.model.SemanticRole.ALIVE
                        }
                    if (globalAlive != null) {
                        val cfg = org.json.JSONObject(globalAlive.config)
                        val aliveVal = cfg.stringOr("aliveValue", "")
                        val deadVal = cfg.stringOr("deadValue", "")
                        if (aliveVal.isNotBlank() && deadVal.isNotBlank()) {
                            val ids = db.characterDao().getUnclassifiedCharacterIds()
                            val unassigned = com.novelcharacter.app.util.SqlInChunks
                                .flat(ids) { db.characterDao().getCharactersByIds(it) }
                            syncAliveFor(unassigned, globalAlive, aliveVal, deadVal)
                        }
                    }
                }.onFailure {
                    android.util.Log.w("NovelCharacterApp", "Alive sync (unassigned) skipped", it)
                }

                prefs.edit()
                    .putBoolean("alive_sync_migrated", true)
                    .putBoolean("alive_sync_migrated_v2", true)
                    .apply()
                android.util.Log.i("NovelCharacterApp", "Alive sync migration completed")
            } catch (e: Exception) {
                android.util.Log.e("NovelCharacterApp", "Alive sync migration failed", e)
            }
        }
    }

    /**
     * 생존여부 1회 마이그레이션의 몸통 — **캐릭터 묶음 하나**를 그 구역의 alive 필드에 맞춘다.
     *
     * 세계관 갈래와 미분류 갈래가 **같은 함수**를 탄다(R-33). 종전에는 몸통이 세계관 반복
     * 안에만 있어 미분류 캐릭터를 만날 자리가 아예 없었다.
     *
     * **캐릭터마다 묻지 않는다.** 종전에는 이력과 alive 값을 한 명씩 두 질의로 물어,
     * 캐릭터 수백 명 저장소의 업데이트 후 첫 실행에서 1,000회 안팎의 왕복이 났다.
     * 둘 다 일괄 짝이 있으므로 한 번에 떠서 메모리 색인으로 판정한다(R-54 통로를 지난다).
     */
    private suspend fun syncAliveFor(
        characters: List<com.novelcharacter.app.data.model.Character>,
        aliveField: com.novelcharacter.app.data.model.FieldDefinition,
        aliveVal: String,
        deadVal: String
    ) {
        if (characters.isEmpty()) return
        val ids = characters.map { it.id }
        val changesByChar = com.novelcharacter.app.util.SqlInChunks
            .flat(ids) { database.characterStateChangeDao().getChangesByCharacterIds(it) }
            .groupBy { it.characterId }
        val valueByChar = com.novelcharacter.app.util.SqlInChunks
            .flat(ids) { database.characterFieldValueDao().getValuesForCharacters(it) }
            .filter { it.fieldDefinitionId == aliveField.id }
            .associateBy { it.characterId }

        for (char in characters) {
            val changes = changesByChar[char.id].orEmpty()
            val keys = CharacterStateChange
            val hasDeath = changes.any { it.fieldKey == keys.KEY_DEATH }
            val hasBirth = changes.any { it.fieldKey == keys.KEY_BIRTH }
            val hasAlive = changes.any { it.fieldKey == keys.KEY_ALIVE }
            if (hasAlive) continue // 이미 __alive 있으면 스킵

            val currentValue = valueByChar[char.id]
            // **셋째 갈래가 2026.08.23에 들어왔다** — 값이 이미 있으면 그 값이 곧 답이다.
            // 종전에는 갈래가 둘뿐이라(사망 이력 / 출생 이력 + 빈 값) **이력이 하나도 없는데
            // 생존여부만 골라 둔 캐릭터**가 `else -> continue`로 빠졌다. 그 캐릭터는 값과
            // 이력이 어긋난 채 남아 연표·통계·필터가 혼자 다르게 셌고, 플래그가 서면
            // 다시는 돌지 않았다. 대응은 저장 경로와 **같은 함수**가 든다(R-33).
            val chosen = currentValue?.value.orEmpty()
            val chosenMarker = SemanticAlivePrecedence.aliveMarker(chosen, aliveVal, deadVal)
            val newValue = when {
                hasDeath -> deadVal
                hasBirth && chosenMarker == null -> aliveVal
                chosenMarker != null -> chosen
                else -> continue
            }
            val marker = when {
                // 사망 이력이 있으면 그것이 이긴다(종전 차례 그대로).
                hasDeath -> CharacterStateChange.ALIVE_MARKER_DEAD
                // **사용자가 고른 값이 곧 답이다** — '불명'을 골라 둔 캐릭터에 '생존'을 적으면
                // 사용자의 판단을 앱이 뒤집는 것이다(개발 의도 3번 · SemanticAlivePrecedence의 규칙).
                chosenMarker != null -> chosenMarker
                // 값은 비었고 출생 이력만 있는 갈래 — 종전 그대로 '생존'으로 연다.
                else -> CharacterStateChange.ALIVE_MARKER_ALIVE
            }

            if (currentValue == null || currentValue.value != newValue) {
                if (currentValue != null) {
                    database.characterFieldValueDao().update(currentValue.copy(value = newValue))
                } else {
                    database.characterFieldValueDao().insert(
                        com.novelcharacter.app.data.model.CharacterFieldValue(
                            characterId = char.id, fieldDefinitionId = aliveField.id, value = newValue
                        )
                    )
                }
            }
            database.characterStateChangeDao().insert(
                CharacterStateChange(
                    characterId = char.id, year = 0, fieldKey = keys.KEY_ALIVE, newValue = marker
                )
            )
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
                    appendLine("=== Crash at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} ===")
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
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val birthday = NotificationChannel(
            BIRTHDAY_CHANNEL_ID,
            getString(R.string.notification_channel_birthday_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_birthday_desc)
        }
        manager.createNotificationChannel(birthday)

        // 자동 백업 실패 통지용 채널 (사용자가 개별 제어 가능하도록 별도 채널)
        val backup = NotificationChannel(
            BACKUP_CHANNEL_ID,
            getString(R.string.notification_channel_backup_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_backup_desc)
        }
        manager.createNotificationChannel(backup)

        // 전송(가져오기·내보내기) 결과 통지용 채널 (B-56 · B-228) — 백업과 나눈 것은
        // **따로 끌 수 있어야 하기** 때문이다. 한 채널에 묶으면 전송 알림이 성가셔 끈
        // 사용자가 자동 백업 실패 통지까지 함께 잃는다. 중요도를 DEFAULT로 두는 것도
        // 그 갈래다 — 끝난 일의 보고이지 지금 손대야 하는 사고가 아니다.
        // **B-228부터 내보내기의 중단 고지도 이 채널을 쓴다** — 사용자가 켜고 끄는 단위는
        // '가져오기'가 아니라 '엑셀 주고받기'다. 이름·설명은 여기서 매 시작마다 다시 세우므로
        // 판올림하면 그대로 갱신된다(중요도만 갱신되지 않는데, 그 값은 안 바꿨다).
        val transfer = NotificationChannel(
            TRANSFER_CHANNEL_ID,
            getString(R.string.notification_channel_transfer_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_transfer_desc)
        }
        manager.createNotificationChannel(transfer)
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

        // 이름은 워커가 든다 — 수동 격발('지금 백업')이 별도 이름공간을 쓴다는 사실과
        // 한자리에 있어야 둘이 갈리지 않는다(설계 D2)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AutoBackupWorker.PERIODIC_WORK_NAME,
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
        const val BACKUP_CHANNEL_ID = "backup_channel"
        const val TRANSFER_CHANNEL_ID = "transfer_channel"

    }
}
