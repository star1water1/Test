package com.novelcharacter.app.ui.novel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.repository.NovelFieldValueMerge
import com.novelcharacter.app.data.model.RecentActivity
import com.novelcharacter.app.util.StandardYearSyncHelper
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import android.util.Log
import kotlinx.coroutines.launch

class NovelViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db = app.database
    private val novelRepository = app.novelRepository
    private val universeRepository = app.universeRepository
    private val characterRepository = app.characterRepository
    private val recentActivityDao = app.recentActivityDao
    private val standardYearSyncHelper = StandardYearSyncHelper(characterRepository, universeRepository)
    val allNovels: LiveData<List<Novel>> = novelRepository.allNovels

    // 데이터 처리 결과 알림 채널 (변수 제어: 모든 의미있는 조작 결과 통보)
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    private val _universeId = MutableLiveData<Long?>()

    private val _universeBorder = MutableLiveData<Pair<String, Float>>(Pair("", 1.5f))
    val universeBorder: LiveData<Pair<String, Float>> = _universeBorder

    fun loadUniverseBorder(universeId: Long) = viewModelScope.launch {
        val universe = universeRepository.getUniverseById(universeId)
        if (universe != null) {
            _universeBorder.value = Pair(universe.borderColor, universe.borderWidthDp)
        }
    }

    val filteredNovels: LiveData<List<Novel>> = _universeId.switchMap { uid ->
        if (uid == null || uid == -1L) {
            novelRepository.allNovels
        } else {
            novelRepository.getNovelsByUniverse(uid)
        }
    }

    fun setUniverseFilter(universeId: Long?) {
        _universeId.value = universeId
    }

    fun insertNovel(
        novel: Novel,
        fieldSubmission: NovelFieldValueMerge.Submission? = null
    ) = viewModelScope.launch {
        try {
            val newId = db.withTransaction {
                val id = novelRepository.insertNovel(novel)
                if (fieldSubmission != null) {
                    // 폼의 권한은 렌더한 필드까지(R-5) — 커버 밖 기존 값은 건드리지 않는다
                    NovelFieldValueMerge.saveWithinCover(db.novelFieldValueDao(), id, fieldSubmission)
                }
                id
            }
            // 커밋 후 값 라이브러리 수확 (실패 무해)
            if (fieldSubmission != null) app.fieldValueLibraryRepository.harvestForNovel(newId)
            reportResult(_result, OpResult.success(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_saved, novel.title)))
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to insert novel", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_save_failed), e.message))
        }
    }

    fun updateNovel(
        novel: Novel,
        fieldSubmission: NovelFieldValueMerge.Submission? = null
    ) = viewModelScope.launch {
        try {
            var preservedFieldValues = 0
            db.withTransaction {
                novelRepository.updateNovel(novel)
                if (fieldSubmission != null) {
                    preservedFieldValues =
                        NovelFieldValueMerge.saveWithinCover(db.novelFieldValueDao(), novel.id, fieldSubmission)
                }
            }
            if (fieldSubmission != null) app.fieldValueLibraryRepository.harvestForNovel(novel.id)
            reportResult(_result, OpResult.success(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_updated, novel.title)))
            // 폼이 렌더하지 못해 보존된 값은 '존재를 알 수 없는 데이터'가 되지 않게 알린다(원칙 04)
            notifyPreservedNovelFieldValues(preservedFieldValues)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to update novel", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_update_failed), e.message))
        }
    }

    private fun notifyPreservedNovelFieldValues(count: Int) {
        if (count <= 0) return
        reportResult(_result, OpResult.success(OpResult.CAT_NOVEL,
            app.getString(R.string.novel_field_values_preserved, count)))
    }

    // ── 작품 커스텀 필드 (확-3) ──

    /**
     * 작품 편집 폼이 렌더할 정의 — 종류를 저장소 한 자리에서 고른다(R-29).
     *
     * **세계관이 없으면 전역 구역이다**(B-129). 종전에는 이 자리가 *성립하지 않는다*고 보아
     * 폼이 필드를 0개로 그렸는데, 무소속 **캐릭터**는 B-119 확장이 이미 전역 필드를 받고 있어
     * 같은 앱 안에서 **작품만 그 구역을 못 보는** 상태였다(원칙 01·05).
     * 전역 구역의 필드는 기본 필드 템플릿의 그림자이고, 그 심기는 저장소가 멱등으로 한다.
     */
    /**
     * 작품 하나를 id로 — **화면 목록이 아니라 표에서 뜬다**(B-198).
     *
     * 값을 고치러 오는 길은 세계관 필터를 지나지 않는다. 목록에서 찾으면 필터 밖 작품은
     * 못 찾고 **아무 일도 안 일어난 것처럼 보인다.**
     */
    suspend fun getNovelById(novelId: Long) = novelRepository.getNovelById(novelId)

    suspend fun getNovelFields(universeId: Long?) =
        if (universeId == null) {
            // 앱의 저장소를 쓴다 — `globalFields`는 **심을 수도 있는**(쓰기) 함수라, 옆에서
            // 새 인스턴스를 지으면 *둘 중 무엇이 맞는가*를 다음 사람이 매번 다시 묻는다.
            app.defaultFieldTemplateRepository
                .globalFields(com.novelcharacter.app.data.model.FieldDefinition.ENTITY_NOVEL)
        } else {
            universeRepository.getNovelFieldsByUniverseList(universeId)
        }

    /**
     * 작품 카드에 얹을 필드값 요약 (B-67 — 사건 카드가 B-5에서 세운 규약 그대로다).
     *
     * 목록에 실제로 그려지는 작품만 조회한다 — 전량 조회는 화면에 보이는 작품 수와 무관하게
     * 값 표 전체를 읽는다. `IN (:목록)`은 [com.novelcharacter.app.util.SqlInChunks]를 지난다 —
     * SQLite 변수 상한 방어이고, 한 덩이에 들어가면 쪼개지 않으므로 흔한 경우가 되레 싸다(R-54).
     *
     * 정의는 **구역을 가리지 않고** 읽는다: 목록이 전 세계관을 걸칠 수 있고(세계관 필터 없음),
     * 무소속 작품은 전역 구역의 정의를 든다(B-129). 어느 정의를 쓸지는 값 행이 고르므로
     * 남의 구역 정의가 섞여 들어와도 카드에 남의 값이 서지 않는다.
     *
     * **셈은 기본 디스패처에서 한다.** DAO는 Room이 알아서 자기 실행기로 보내지만 그 뒤의
     * `groupBy`·[com.novelcharacter.app.util.CardFieldSummary.build]는 **부른 쪽 스레드에서
     * 그대로 돈다** — 호출부가 `lifecycleScope`(주 스레드)라 목록이 방출될 때마다 정의마다의
     * config JSON 파싱과 값 행 묶기가 주 스레드에 얹힌다. 목록은 작품 표가 바뀔 때마다
     * 다시 방출되므로 그 비용이 스크롤에 그대로 붙는다.
     */
    suspend fun getNovelFieldSummaries(
        novelIds: List<Long>
    ): Map<Long, com.novelcharacter.app.util.CardFieldSummary.Summary> {
        if (novelIds.isEmpty()) return emptyMap()
        return try {
            val defs = db.fieldDefinitionDao()
                .getAllFieldsList(com.novelcharacter.app.data.model.FieldDefinition.ENTITY_NOVEL)
            if (defs.isEmpty()) return emptyMap()

            val distinctIds = novelIds.distinct()
            val values = com.novelcharacter.app.util.SqlInChunks.flat(distinctIds) { chunk ->
                db.novelFieldValueDao().getValuesByNovels(chunk)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.novelcharacter.app.util.CardFieldSummary.build(
                    defs = defs,
                    rowsByEntity = values.groupBy({ it.novelId }, { it.fieldDefinitionId to it.value }),
                    entityIds = distinctIds
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // **취소는 실패가 아니다.** 목록이 다시 방출되면 앞선 조회는 취소되는데, 그것까지
            // 아래 갈래가 삼키면 낡은 호출이 살아나 빈 결과를 최신 요약 위에 덮어쓴다.
            throw e
        } catch (e: Exception) {
            // 카드 부가 정보다 — 실패해도 목록 자체는 그려야 한다. 다만 조용히 삼키지는 않는다.
            Log.e("NovelViewModel", "Failed to load novel field summaries", e)
            emptyMap()
        }
    }

    suspend fun getNovelFieldValues(novelId: Long) =
        db.novelFieldValueDao().getValuesByNovelList(novelId)

    /** 작품 편집 자리에서 만든 작품 필드를 심는다. 실패는 예외로 올려 호출부가 알린다. */
    suspend fun insertNovelField(field: com.novelcharacter.app.data.model.FieldDefinition): Long =
        universeRepository.insertField(field)

    /**
     * 생성 창의 '값 사전 등록'을 라이브러리에 심는다 — 필드 관리 경로([FieldViewModel.insertField])와
     * **같은 규칙**이다: 콤마로 가르고, 라이브러리 미지원 타입(NUMBER 등)은 등재해도 어디에도
     * 보이지 않으므로 건너뛴다. 실패는 무해하다(필드 자체는 이미 만들어졌다).
     */
    suspend fun registerInitialValues(
        fieldDefId: Long,
        field: com.novelcharacter.app.data.model.FieldDefinition,
        initialValues: String
    ) {
        // 해석·등재는 저장소가 단일 소스다 — 종전에는 필드 관리 경로와 두 벌이었고,
        // 이쪽만 runCatching으로 실패를 삼켜 같은 조작의 고지가 화면마다 갈렸다.
        app.fieldValueLibraryRepository.registerInitialValues(fieldDefId, field, initialValues)
    }

    /**
     * 폼이 미리 읽어 두는 값 라이브러리 엔트리 — **숨김도 포함**한다.
     * 제안(자동완성)은 숨김을 빼지만 허용 목록 판정은 숨긴 값도 허용 목록의 일부로 본다
     * (용도별 필터링은 호출측이 한다는 저장소 계약).
     */
    suspend fun novelFieldEntries(fieldIds: List<Long>) =
        runCatching { app.fieldValueLibraryRepository.entriesForFields(fieldIds) }
            .getOrDefault(emptyMap())

    /** 허용 목록 위반을 사용자가 '값 추가하고 저장'으로 교정할 때 — 실패는 무해(저장은 계속된다) */
    suspend fun addLibraryValues(byField: List<Pair<Long, List<String>>>) {
        for ((fieldId, tokens) in byField) {
            for (token in tokens) {
                runCatching { app.fieldValueLibraryRepository.addEntry(fieldId, token) }
            }
        }
    }

    // 제안(자동완성)은 더 이상 여기서 따로 조회하지 않는다 (B-129) — 폼이 위 [novelFieldEntries]로
    // 이미 읽어 둔 엔트리에서 `FieldSuggestionEntries`가 고른다. 종전의 세계관 단위 질의는
    // **전역 구역에서 원리적으로 아무것도 돌려주지 못했고**, 같은 필드를 두 번 읽는 것이기도 했다.

    /** 삭제 확인 다이얼로그용 — 함께 삭제될 소속 캐릭터 수 집계(계단식 삭제 사전 고지) */
    suspend fun getNovelDeleteImpact(novelId: Long): Int =
        characterRepository.getCharactersByNovelList(novelId).size

    fun deleteNovel(novel: Novel) = viewModelScope.launch {
        try {
            novelRepository.deleteNovel(novel)
            reportResult(_result, OpResult.success(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_deleted, novel.title)))
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to delete novel", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_delete_failed), e.message))
        }
    }

    fun updateDisplayOrders(novels: List<Novel>) = viewModelScope.launch {
        try {
            novelRepository.updateNovelDisplayOrders(novels)
            // 재정렬은 초고빈도 조작 — 성공 무통보, 실패만 알림 (원칙 04)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to update display orders", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_reorder_failed), e.message))
        }
    }

    fun togglePin(novel: Novel) = viewModelScope.launch {
        try {
            novelRepository.setPinned(novel.id, !novel.isPinned)
            // 핀 토글은 초고빈도 조작 — 성공 무통보, 실패만 알림 (원칙 04)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to toggle pin", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_pin_failed), e.message))
        }
    }

    /** 작품에 속한 캐릭터 중 이미지가 있는 랜덤/지정 캐릭터의 첫 이미지 경로 반환 */
    /** 같은 시드 아래 **결정적으로** 하나를 고른다 — `.random()`을 대신한다(B-103 D3). */
    private fun <T> pickStable(items: List<T>, seed: Long, rowKey: Long): T =
        items[com.novelcharacter.app.util.CharacterRepresentativeImage.randomIndex(seed, rowKey, items.size)]

    fun resolveCharacterImage(novelId: Long, characterId: Long?, seed: Long, callback: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val characters = characterRepository.getCharactersByNovelList(novelId)
                val withImages = characters.filter { char ->
                    val paths = char.imagePaths
                    paths.isNotBlank() && paths != "[]"
                }
                if (withImages.isEmpty()) {
                    callback(null)
                    return@launch
                }

                val target = if (characterId != null) {
                    withImages.find { it.id == characterId } ?: pickStable(withImages, seed, novelId)
                } else {
                    pickStable(withImages, seed, novelId)
                }

                // **대표 판정 단일 소스를 지난다**(B-103 D7). 종전에는 이 함수만 그 정리에서
                // 빠져 날 Gson + randomOrNull()이었고, 대표를 지정해 두어도 작품 카드에서만
                // 효력이 없었다 — 지정은 저장돼 있는데 읽는 코드가 여기 한 줄도 없었다.
                callback(
                    com.novelcharacter.app.util.CharacterRepresentativeImage.path(
                        target.imagePaths,
                        target.representativeImagePath,
                        seed,
                        target.id
                    )
                )
            } catch (e: Exception) {
                Log.e("NovelViewModel", "Failed to resolve character image", e)
                callback(null)
            }
        }
    }

    /** 작품에 속한 이미지 있는 캐릭터 목록 반환 (선택 모드용) */
    suspend fun getCharactersWithImages(novelId: Long): List<com.novelcharacter.app.data.model.Character> {
        return characterRepository.getCharactersByNovelList(novelId).filter { char ->
            char.imagePaths.isNotBlank() && char.imagePaths != "[]"
        }
    }

    fun onStandardYearChanged(novel: Novel, oldStdYear: Int?, newStdYear: Int?) = viewModelScope.launch {
        try {
            standardYearSyncHelper.onStandardYearChanged(novel, oldStdYear, newStdYear)
            // 성공은 작품 저장 알림에 포함 — 실패만 별도 통보 (캐릭터 나이 파급 실패 방지)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to sync standard year change", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_stdyear_sync_failed), e.message))
        }
    }

    fun recordRecentActivity(novelId: Long, title: String) = viewModelScope.launch {
        try {
            recentActivityDao.upsert(
                RecentActivity(entityType = RecentActivity.TYPE_NOVEL, entityId = novelId, title = title)
            )
            recentActivityDao.trimToMax(RecentActivity.MAX_ENTRIES)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to record recent activity", e)
        }
    }
}
