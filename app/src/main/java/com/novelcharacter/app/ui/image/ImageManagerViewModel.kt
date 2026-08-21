package com.novelcharacter.app.ui.image

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.maintenance.SystemMaintenanceService
import com.novelcharacter.app.util.FolderRoundtripPrefs
import com.novelcharacter.app.util.GsonTypes
import com.novelcharacter.app.util.ImageAdoption
import com.novelcharacter.app.util.ImageFilterHelper
import com.novelcharacter.app.util.ImageImportHelper
import com.novelcharacter.app.util.ImageLinkResolver
import com.novelcharacter.app.util.ImagePathMatch
import com.novelcharacter.app.util.ImageSettingsStore
import com.novelcharacter.app.util.ImageTagApplyPlanner
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.StorageAnalyzer
import com.novelcharacter.app.util.SqlInChunks
import com.novelcharacter.app.util.logResult
import com.novelcharacter.app.util.toastResult
import com.novelcharacter.app.util.withImagePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 이미지 관리 탭의 데이터 소스. filesDir의 모든 이미지 파일을 개별 항목으로 조립하고,
 * 각 파일이 어느 엔티티(캐릭터/작품/세계관)에서 참조되는지·고아인지·휴지통 보류인지 분류한다.
 * 참조 집합 조립은 [ImageZipHelper]/[StorageAnalyzer]의 기존 규칙(접두·확장자·canonical)을 재사용한다.
 */
class ImageManagerViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db: AppDatabase = app.database
    private val gson = Gson()

    // 기본 필터·태그·정렬은 세션을 넘는 작업 컨텍스트 — SharedPreferences로 영속(재방문 시 복원).
    // 검색어는 일회성 조회라 세션(SavedStateHandle) 한정 — 낡은 검색어가 재방문 시
    // 이미지를 조용히 숨기지 않게 한다(변수 제어).
    private val prefs = application.getSharedPreferences(
        "image_manager_ui_state", android.content.Context.MODE_PRIVATE
    )

    enum class Sort { SIZE, NAME, DATE }

    enum class ViewMode { GRID, GALLERY }

    companion object {
        /**
         * 기준마다의 기본 방향 — 방향 축이 생기기 전의 **고정 방향과 같다**(크기·날짜는
         * 큰/새 것부터, 이름은 가나다). 기준을 바꿀 때 컨트롤 시트가 이 값을 제안한다
         * (캐릭터 목록 시트의 `defaultAscending`과 같은 관행).
         */
        fun defaultAscending(sort: Sort): Boolean = when (sort) {
            Sort.NAME -> true
            Sort.SIZE, Sort.DATE -> false
        }
    }

    init {
        // 콜드 스타트 시딩 — SavedStateHandle이 이미 살아있으면(회전/저메모리 복원) 그쪽이 우선
        if (!savedState.contains("filter_base")) {
            savedState["filter_base"] =
                prefs.getString("filter_base", null) ?: ImageFilterHelper.BaseFilter.ALL.name
        }
        if (!savedState.contains("filter_link")) {
            savedState["filter_link"] =
                prefs.getString("filter_link", null) ?: ImageFilterHelper.LinkFilter.ANY.name
        }
        if (!savedState.contains("filter_tags")) {
            savedState["filter_tags"] = ArrayList(loadPersistedTags())
        }
        if (!savedState.contains("filter_tag_presence")) {
            savedState["filter_tag_presence"] =
                prefs.getString("filter_tag_presence", null) ?: ImageFilterHelper.TagFilter.ANY.name
        }
        if (!savedState.contains("sort")) {
            savedState["sort"] = prefs.getString("sort", null) ?: Sort.SIZE.name
        }
        if (!savedState.contains("sort_asc")) {
            // 방향 축 신설의 이행 — 저장된 방향이 없으면 **종전 고정 방향**을 그대로 제안한다
            // (크기·날짜는 큰/새 것부터, 이름은 가나다). 그래야 갱신 직후 화면이 어제와 같다.
            val seededSort = runCatching { Sort.valueOf(savedState["sort"] ?: Sort.SIZE.name) }
                .getOrDefault(Sort.SIZE)
            savedState["sort_asc"] = if (prefs.contains("sort_asc")) {
                prefs.getBoolean("sort_asc", defaultAscending(seededSort))
            } else {
                defaultAscending(seededSort)
            }
        }
        if (!savedState.contains("view_mode")) {
            savedState["view_mode"] = prefs.getString("view_mode", null) ?: ViewMode.GRID.name
        }
        if (!savedState.contains("group_view")) {
            savedState["group_view"] = prefs.getBoolean("group_view", false)
        }
    }

    private fun loadPersistedTags(): List<String> = runCatching {
        gson.fromJson<List<String?>>(
            prefs.getString("filter_tags", null) ?: "[]", GsonTypes.STRING_LIST
        )
    }.getOrNull()?.filterNotNull() ?: emptyList()

    /**
     * 필터/검색/정렬 상태 — SavedStateHandle 영속. ViewPager가 원거리 탭의 프래그먼트(와 이 VM)를
     * 파기해도 FragmentStateAdapter의 상태 저장을 타고 복원된다(탭 전환 시 필터 리셋 방지, D10).
     * 기본 필터·태그·정렬은 prefs에도 기록해 콜드 스타트를 넘는다(위 주석 참조).
     */
    var criteria: ImageFilterHelper.Criteria
        get() = ImageFilterHelper.Criteria(
            base = runCatching {
                ImageFilterHelper.BaseFilter.valueOf(savedState["filter_base"] ?: ImageFilterHelper.BaseFilter.ALL.name)
            }.getOrDefault(ImageFilterHelper.BaseFilter.ALL),
            link = runCatching {
                ImageFilterHelper.LinkFilter.valueOf(savedState["filter_link"] ?: ImageFilterHelper.LinkFilter.ANY.name)
            }.getOrDefault(ImageFilterHelper.LinkFilter.ANY),
            tags = savedState.get<ArrayList<String>>("filter_tags")?.toSet() ?: emptySet(),
            query = savedState["filter_query"] ?: "",
            tagPresence = runCatching {
                ImageFilterHelper.TagFilter.valueOf(
                    savedState["filter_tag_presence"] ?: ImageFilterHelper.TagFilter.ANY.name
                )
            }.getOrDefault(ImageFilterHelper.TagFilter.ANY),
            // **걸러낼 후보는 세션 한정이다**(B-104 ⓒ) — 다른 축과 달리 prefs에 남기지 않는다.
            // 이 필터는 *"지금 정리하는 중"*의 작업 상태이고, 켠 채로 탭을 떠났다가 며칠 뒤
            // 다시 열면 **이미지 대부분이 사라진 화면**을 보게 된다(그 사이 판이 쌓여 후보가
            // 달라져 있을 수도 있다). 검색어를 세션 한정으로 둔 것과 같은 근거다.
            prune = runCatching {
                ImageFilterHelper.PruneFilter.valueOf(
                    savedState["filter_prune"] ?: ImageFilterHelper.PruneFilter.ANY.name
                )
            }.getOrDefault(ImageFilterHelper.PruneFilter.ANY)
        )
        set(value) {
            savedState["filter_base"] = value.base.name
            savedState["filter_link"] = value.link.name
            savedState["filter_tags"] = ArrayList(value.tags)
            savedState["filter_query"] = value.query
            savedState["filter_tag_presence"] = value.tagPresence.name
            savedState["filter_prune"] = value.prune.name
            prefs.edit()
                .putString("filter_base", value.base.name)
                .putString("filter_link", value.link.name)
                .putString("filter_tags", gson.toJson(ArrayList(value.tags)))
                .putString("filter_tag_presence", value.tagPresence.name)
                .apply()
        }

    var sort: Sort
        get() = runCatching { Sort.valueOf(savedState["sort"] ?: Sort.SIZE.name) }.getOrDefault(Sort.SIZE)
        set(value) {
            savedState["sort"] = value.name
            prefs.edit().putString("sort", value.name).apply()
        }

    /** 정렬 방향 — 기준([sort])과 독립 축. 영속 관례는 [sort]와 같다. */
    var sortAscending: Boolean
        get() = savedState["sort_asc"] ?: defaultAscending(sort)
        set(value) {
            savedState["sort_asc"] = value
            prefs.edit().putBoolean("sort_asc", value).apply()
        }

    /**
     * 묶어 보기 — 링크 묶음을 목록에서 대표 한 장으로 접는다. 접기 규칙은
     * [com.novelcharacter.app.util.LinkGroupFold]가 단일 소스다(라이브러리 피커와 공유).
     */
    var groupView: Boolean
        get() = savedState["group_view"] ?: false
        set(value) {
            savedState["group_view"] = value
            prefs.edit().putBoolean("group_view", value).apply()
        }

    /** 그리드 ↔ 갤러리(페이저) 보기 모드 — criteria/sort와 동일한 영속 관례 */
    var viewMode: ViewMode
        get() = runCatching { ViewMode.valueOf(savedState["view_mode"] ?: ViewMode.GRID.name) }
            .getOrDefault(ViewMode.GRID)
        set(value) {
            savedState["view_mode"] = value.name
            prefs.edit().putString("view_mode", value.name).apply()
        }

    /** 갤러리 페이지 위치 — 회전·프래그먼트 재생성 생존용(세션 한정, prefs 미기록) */
    var galleryPosition: Int
        get() = savedState["gallery_pos"] ?: 0
        set(value) { savedState["gallery_pos"] = value }

    /**
     * 갤러리에서 보던 항목의 path — 정체성 추적용. 목록이 재정렬·필터·재압축으로 바뀌어도
     * 같은 이미지를 계속 보여주기 위한 1차 기준이고, [galleryPosition]은 path 소실 시 폴백이다.
     */
    var galleryPath: String?
        get() = savedState["gallery_path"]
        set(value) { savedState["gallery_path"] = value }

    enum class OwnerType { CHARACTER, NOVEL, UNIVERSE }
    data class Owner(val type: OwnerType, val name: String, val id: Long)
    /** UNASSIGNED = 라이브러리(image_meta) 관리 중이나 아직 어떤 엔티티에도 배정되지 않은 이미지(고아 아님·보호됨). */
    enum class Status { REFERENCED, ORPHAN, TRASH_HELD, UNASSIGNED }
    /** 라이브러리 메타(있으면 라이브러리 관리 이미지): 태그·링크 그룹. */
    /**
     * @param detachedAt 캐릭터에서 뗀 시각. null=뗀 적 없음(B-107 D1).
     * @param detachedFromCode 마지막으로 뗀 캐릭터의 안정 식별자. 화면이 이름을 찾아 보여 준다.
     */
    data class MetaInfo(
        val imageId: Long,
        val linkGroupId: String?,
        val tags: List<String>,
        val detachedAt: Long? = null,
        val detachedFromCode: String? = null,
        /**
         * [detachedFromCode]가 가리키는 캐릭터의 현재 이름. **코드는 있는데 이 값이 null이면
         * 그 캐릭터가 지워진 것**이고, 화면은 그렇게 말한다(둘을 한 값으로 접으면 "출처 없음"과
         * "지워진 캐릭터"가 구별되지 않는다).
         */
        val detachedFromName: String? = null
    )
    data class ManagedImage(
        val path: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val owners: List<Owner>,
        val status: Status,
        val meta: MetaInfo? = null,
        /**
         * 이 이미지를 **대표로 지정한** 캐릭터 이름들(B-103 D6ⓐ).
         *
         * 삭제 확인창이 결과를 미리 말하려면 필요하다 — 소유자 목록만으로는
         * "쓰는 사람"과 "대표로 삼은 사람"을 구별할 수 없다.
         */
        val representativeOf: List<String> = emptyList(),
        /**
         * 대조용 정규 경로 — **여기서 들고 다니는 것이 요점이다** (B-104 소비처 ⓒ).
         *
         * `buildItems`가 이미 IO 스레드에서 파일마다 한 번 계산하는 값이라(`f.canonicalPath`)
         * 담아 두면 공짜다. 담지 않으면 **화면이 필터를 걸 때마다 다시 계산하는데**, 그 자리가
         * 하필 `ImageFilterHelper.apply`의 `Facts` 조립이라 **항목 전부에 대해 무조건 돈다**
         * (필터가 하나라도 걸려 있으면 단축 평가가 없다). 그러면 검색어 한 글자에
         * **이미지 수만큼의 파일 시스템 호출이 메인 스레드에** 붙는다.
         *
         * 비어 있으면 [path]로 갈음한다 — 정규화가 실패한 경우이며, 실패 처분은
         * [com.novelcharacter.app.util.ImagePathMatch]의 규약과 같다(원본을 그대로 쓴다).
         */
        val canonicalPath: String = path,
        /**
         * 묶어 보기에서 이 칸이 대표하는 **화면에 보이는** 묶음 장수. 1이면 접힌 것이 없다.
         *
         * 접기는 표시 계층의 일이라 [buildItems]는 언제나 1로 만든다 — 화면이
         * [com.novelcharacter.app.util.LinkGroupFold]로 접은 결과를 `copy`로 담는 칸이다.
         * 데이터 클래스 동등성에 들어가므로 필터가 식구 수를 바꾸면 DiffUtil이 다시 그린다.
         */
        val stackCount: Int = 1
    )
    data class Summary(
        val totalBytes: Long,
        val totalCount: Int,
        val referencedCount: Int,
        val orphanCount: Int,
        val trashCount: Int,
        val unassignedCount: Int = 0,
        /**
         * 뗀 것 서랍의 크기(B-107 D3). [unassignedCount]와 **배타다** — 칩이 갈라 놓았으니
         * 요약 줄도 갈라 세어야 두 수의 합이 사용자가 보는 것과 맞는다.
         */
        val detachedCount: Int = 0
    )

    /** 탭 임포트 결과. */
    data class ImportResult(val imported: Int, val failed: Int)

    /** 재압축 대상 1건의 커밋 계획(임시 산출물 준비 완료). */
    data class RecompressPlan(
        val item: ManagedImage,
        val tempPath: String,
        val newSize: Long,
        val prefix: String
    )

    /** 재압축에서 제외된 1건 + 사유. */
    data class RecompressSkip(
        val item: ManagedImage,
        val reason: ImageImportHelper.SkipReason
    )

    /** 재압축 미리보기 — 확인 다이얼로그에 실제 전/후 크기와 스킵 내역을 보여주기 위한 결과. */
    data class RecompressPreview(
        val plans: List<RecompressPlan>,
        val skips: List<RecompressSkip>,
        val totalBefore: Long,
        val totalAfter: Long,
        /**
         * 사용자가 준비 단계를 취소했다(B-51 · R-26).
         *
         * 준비는 원본을 건드리지 않고 임시 파일만 만들므로 취소는 **산출물 없음**이다 —
         * 반쯤 만든 미리보기로 "이만큼 줄어듭니다"라고 말하면 그 숫자가 거짓이 된다.
         * 그래서 여기까지 만든 임시 파일은 지우고 빈 미리보기를 돌려준다.
         */
        val cancelled: Boolean = false
    ) {
        val savings: Long get() = (totalBefore - totalAfter).coerceAtLeast(0L)
    }

    /** 재압축 커밋 결과. failed = 준비됐으나 커밋(개명·경로교체)에 실패해 반영 못 한 건수(조용한 증발 방지). */
    data class RecompressResult(
        val recompressed: Int, val freed: Long, val skipped: Int, val failed: Int = 0,
        /** 커밋된 건의 (원경로 → 새경로) — 갤러리 현재 항목 추적 등 경로 추종용. 실패 반환 경로는 빈 맵 */
        val pathRemap: Map<String, String> = emptyMap()
    )

    /** 일괄 삭제 결과. */
    data class BulkDeleteResult(val deleted: Int, val freed: Long, val failed: Int)

    // 사용자 확인을 기다리는 재압축 미리보기(임시 파일 보유). 커밋/취소 시 소비.
    // @Volatile: buildItems()는 IO 스레드에서 이 값을 읽고 prepare/commit은 메인에서 쓰므로 가시성 보장.
    @Volatile private var pendingPreview: RecompressPreview? = null

    // ===== 재압축 되돌리기 — 재압축 시 원본을 영구 삭제하지 않고 백업 디렉터리로 옮겨 스낵바 "실행취소"로 복원 가능하게 함 =====
    private data class RecompressUndoEntry(
        val originalPath: String,   // 재압축 전 원본 경로(복원 목적지)
        val backupPath: String,     // recompress_backup/ 로 옮긴 백업 파일
        val finalPath: String,      // 재압축 산출물(복원 시 삭제)
        val owners: List<Owner>
    )
    @Volatile private var pendingUndo: List<RecompressUndoEntry>? = null
    private val recompressBackupDirName = "recompress_backup"
    private val backupRetentionMs = 7L * 24 * 60 * 60 * 1000  // 7일

    fun hasRecompressUndo(): Boolean = !pendingUndo.isNullOrEmpty()

    private fun recompressBackupDir(): File =
        File(getApplication<Application>().filesDir, recompressBackupDirName).apply { if (!exists()) mkdirs() }

    // 미리보기 준비 중(임시 파일 생성 중) 플래그. pendingPreview는 buildPreview가 끝난 뒤에야 세팅되므로,
    // 그 사이 창에서 동시 load()의 sweep가 준비 중 임시 파일을 지워 재압축이 조용히 무효화되는 것을 막는다.
    @Volatile private var preparingRecompress = false

    private val _images = MutableLiveData<List<ManagedImage>>(emptyList())
    val images: LiveData<List<ManagedImage>> = _images

    private val _summary = MutableLiveData(Summary(0, 0, 0, 0, 0))
    val summary: LiveData<Summary> = _summary

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    // ──────────────────────────────────────────────────────────────────────
    // 걸러낼 후보 (B-104 소비처 ⓒ — 설계 13-5)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 칩이 켜졌을 때의 상태. **꺼져 있으면 계산하지 않는다** — 질의조차 열지 않으므로
     * 이 기능을 쓰지 않는 사용자에게 비용이 0이다.
     *
     * 갈래를 셋으로 두는 것은 화면이 할 말이 다르기 때문이다: *"계산 중"* · *"기준 축이
     * 없다(대결 축에서 지정하세요)"* · *"기준은 있는데 조건에 맞는 그림이 없다"*.
     * 한 갈래로 뭉치면 빈 화면이 고장과 구별되지 않는다(원칙 02).
     */
    sealed interface PruneState {
        /** 칩이 꺼져 있다 — 아무것도 계산하지 않았다. */
        object Off : PruneState

        object Loading : PruneState

        /**
         * @property byPath 후보의 **정규 경로 → 그 후보가 왜 후보인가**.
         *   **근거를 함께 들고 다니는 것이 요점이다** — 지우기 직전 화면에서 *"왜 이것이
         *   후보인가"*를 말할 수 없으면 앱이 지어낸 서열이 된다(원칙 02). 경로만 담아 두면
         *   화면이 그 물음에 답할 재료가 아예 없다.
         * @property basisUniverses 기준 이미지 축을 가진 세계관 수. **0이면 지정이 없다** —
         *   화면은 후보 0건이 아니라 *"기준 축을 먼저 지정하세요"*를 말해야 한다.
         * @property scannedCharacters 점수를 낸 캐릭터 수 — *"몇 명분을 봤는가"*의 규모.
         */
        data class Ready(
            val byPath: Map<String, com.novelcharacter.app.util.DuelImagePrune.Candidate>,
            val basisUniverses: Int,
            val scannedCharacters: Int
        ) : PruneState {
            val paths: Set<String> get() = byPath.keys
            val hasBasis: Boolean get() = basisUniverses > 0
        }
    }

    private val _pruneState = MutableLiveData<PruneState>(PruneState.Off)
    val pruneState: LiveData<PruneState> = _pruneState

    /**
     * *"제안까지가 끝"* 고지를 아직 안 했는가 — **화면이 아니라 여기서 센다.**
     *
     * 조각 필드로 세면 회전 한 번에 도로 `false`가 되어(관측은 뷰 재생성마다 되돌아온다)
     * **같은 말이 회전마다 반복된다.** 칩을 다시 켤 때만 되살린다.
     */
    private var pruneNoticePending = false

    /**
     * 돌고 있는 후보 계산. **끄거나 다시 켤 때 반드시 끊는다.**
     *
     * 안 끊으면 칩을 껐는데 **뒤늦게 끝난 계산이 `Off`를 덮어** 꺼진 칩에 개수가 붙는다
     * (그리고 `PruneState.Off`가 스스로 선언한 *"아무것도 계산하지 않았다"*가 거짓이 된다).
     * 빠르게 껐다 켜면 계산 둘이 순서 보장 없이 경쟁한다.
     */
    private var pruneJob: kotlinx.coroutines.Job? = null

    /** 고지할 차례인가 — 한 번 읽으면 소진된다. */
    fun consumePruneNotice(): Boolean {
        if (!pruneNoticePending) return false
        pruneNoticePending = false
        return true
    }

    /**
     * 칩을 켜고 끈다 — 켜는 순간 계산이 돈다.
     *
     * **`load()`에 얹지 않은 것이 요점이다.** 이미지 탭은 `onResume`마다 목록을 다시 읽는데,
     * 거기 얹으면 칩을 쓰지 않는 사용자도 탭에 올 때마다 대결 표를 훑게 된다.
     */
    fun setPruneFilter(on: Boolean) {
        criteria = criteria.copy(
            prune = if (on) ImageFilterHelper.PruneFilter.CANDIDATE else ImageFilterHelper.PruneFilter.ANY
        )
        // 앞선 계산은 결과가 무엇이든 이제 낡았다 — 켜든 끄든 먼저 끊는다.
        pruneJob?.cancel()
        pruneJob = null
        if (!on) { _pruneState.value = PruneState.Off; pruneNoticePending = false; return }
        pruneNoticePending = true
        _pruneState.value = PruneState.Loading
        pruneJob = viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { computePruneCandidates() }
            _pruneState.value = state
        }
    }

    /**
     * 기준 이미지 축이 지정된 **모든 세계관**의 후보를 모은다.
     *
     * **훑는 일은 저장소가 한다**([DuelRepository.basisImageScores]) — 대표 추첨(ⓑ)이 쓰는
     * 그 진입점이다. 여기서 따로 훑으면 두 소비처가 다른 순위를 볼 수 있다.
     */
    private suspend fun computePruneCandidates(): PruneState {
        val options = com.novelcharacter.app.util.DuelImageBasisPrefs.pruneOptions(getApplication())
        val scan = app.duelRepository.basisImageScores()
        val byPath = LinkedHashMap<String, com.novelcharacter.app.util.DuelImagePrune.Candidate>()
        for (perCharacter in scan.byCharacter.values) {
            for (candidate in com.novelcharacter.app.util.DuelImagePrune.candidates(perCharacter.scores, options)) {
                byPath[ImagePathMatch.canonical(candidate.path)] = candidate
            }
        }
        return PruneState.Ready(byPath, scan.basisUniverses, scan.byCharacter.size)
    }

    fun load() {
        _loading.value = true
        viewModelScope.launch {
            val (items, summary) = withContext(Dispatchers.IO) {
                sweepOldRecompressBackups()  // 오래된 재압축 백업 정리(수명 관리)
                buildItems()
            }
            _images.value = items
            _summary.value = summary
            _loading.value = false
        }
    }

    private suspend fun buildItems(): Pair<List<ManagedImage>, Summary> {
        val filesDir = getApplication<Application>().filesDir

        // 중단된 재압축 미리보기의 잔여 임시 파일 청소(활성 미리보기가 없고, 준비 중도 아닐 때만 —
        // 대기 중인 커밋과 '준비 중 생성되는 임시 파일'을 모두 보호. 준비 창 sweep는 재압축 무효화 버그였다).
        if (pendingPreview == null && !preparingRecompress) sweepRecompressTempFiles()

        // 소유자 역맵: canonical 경로 → 참조하는 엔티티 목록(같은 이미지를 여러 곳이 쓸 수 있음)
        val ownerMap = HashMap<String, MutableList<Owner>>()
        fun addOwners(imagePathsJson: String, type: OwnerType, name: String, id: Long) {
            for (p in parsePaths(imagePathsJson)) {
                // 정규화 실패를 건너뛰지 않는다 (B-106 ⓐ) — 건너뛰면 **참조가 있는 이미지가
                // 소유자 0으로 보이고**, 이 화면은 그것을 곧 '미배정'으로 그린다.
                val canon = ImagePathMatch.canonical(p)
                if (canon.isEmpty()) continue
                ownerMap.getOrPut(canon) { mutableListOf() }.add(Owner(type, name, id))
            }
        }
        // 대표로 지정한 캐릭터 역맵 — 삭제 확인창의 사전 고지(B-103 D6ⓐ)가 쓴다.
        val representativeMap = HashMap<String, MutableList<String>>()
        // 뗀 출처를 사람이 읽는 이름으로 옮기는 표(B-107 D1). 코드로 남기는 이유가 여기서
        // 값을 한다 — 캐릭터가 지워졌으면 이 표에 없고, 화면이 "지워진 캐릭터"라고 말한다.
        val nameByCode = HashMap<String, String>()
        for (c in db.characterDao().getAllCharactersList()) {
            addOwners(c.imagePaths, OwnerType.CHARACTER, c.name, c.id)
            val rep = ImagePathMatch.canonical(c.representativeImagePath)
            if (rep.isNotEmpty()) representativeMap.getOrPut(rep) { mutableListOf() }.add(c.name)
            nameByCode[c.code] = c.name
        }
        for (n in db.novelDao().getAllNovelsList()) addOwners(n.imagePaths, OwnerType.NOVEL, n.title, n.id)
        for (u in db.universeDao().getAllUniversesList()) addOwners(u.imagePaths, OwnerType.UNIVERSE, u.name, u.id)

        val trashHeld = StorageAnalyzer.collectTrashHeldPaths(db, gson)

        // 라이브러리 메타 + 태그 로드 → canonical 경로 → MetaInfo 역맵.
        // 스테일 정리: 파일이 사라졌고(24h 경과) 엔티티 참조도 없는 meta 행은 지워 집계를 진실하게 유지한다
        // (임포트/복원 in-flight 보호 — 24h 가드는 고아 정리와 동일 기준).
        val metas = runCatching { db.imageMetaDao().getAllList() }.getOrDefault(emptyList())
        val allTags = runCatching { db.imageTagDao().getAllList() }.getOrDefault(emptyList())
        val tagsByImage = allTags.groupBy({ it.imageId }, { it.tag })
        val now = System.currentTimeMillis()
        val staleMetaPaths = ArrayList<String>()
        val metaMap = HashMap<String, MetaInfo>()
        for (m in metas) {
            val exists = runCatching { File(m.path).exists() }.getOrDefault(false)
            val canon = ImagePathMatch.canonical(m.path)
            if (!exists) {
                if (now - m.importedAt > 24L * 60 * 60 * 1000 && !ownerMap.containsKey(canon)) {
                    staleMetaPaths.add(m.path)
                }
                continue
            }
            metaMap[canon] = MetaInfo(
                m.id, m.linkGroupId, tagsByImage[m.id]?.sorted() ?: emptyList(),
                m.detachedAt, m.detachedFromCode,
                detachedFromName = m.detachedFromCode?.let { nameByCode[it] }
            )
        }
        // 나누지 않으면 `runCatching`이 상한 초과를 삼켜 **지운 줄 알았는데 그대로**가 된다 —
        // B-51이 실제로 그렇게 조용히 유실됐다 (B-242).
        runCatching {
            SqlInChunks.each(staleMetaPaths) { db.imageMetaDao().deleteByPaths(it) }
        }

        val files = filesDir.listFiles { f ->
            f.isFile && StorageAnalyzer.isImageFile(f.name) &&
                !f.name.contains(ImageImportHelper.RECOMPRESS_TEMP_MARKER)
        } ?: emptyArray()
        val items = ArrayList<ManagedImage>(files.size)
        var totalBytes = 0L
        var refCount = 0
        var orphanCount = 0
        var trashCount = 0
        var unassignedCount = 0
        var detachedCount = 0
        for (f in files) {
            val canon = ImagePathMatch.canonical(f.absolutePath)
            val owners = ownerMap[canon] ?: emptyList()
            val meta = metaMap[canon]
            // 우선순위: 참조 > 휴지통 보류 > 라이브러리 미배정 > 고아
            val status = when {
                owners.isNotEmpty() -> Status.REFERENCED
                trashHeld.contains(canon) -> Status.TRASH_HELD
                meta != null -> Status.UNASSIGNED
                else -> Status.ORPHAN
            }
            val size = f.length()
            totalBytes += size
            when (status) {
                Status.REFERENCED -> refCount++
                Status.ORPHAN -> orphanCount++
                Status.TRASH_HELD -> trashCount++
                Status.UNASSIGNED -> unassignedCount++
            }
            // 서랍은 상태 축과 **직교하지 않는다** — `UNASSIGNED`에서 뗀 것을 빼고 따로 센다.
            // 필터(`ImageFilterHelper.matchesBase`)와 같은 규칙이어야 요약과 목록이 맞는다.
            if (meta?.detachedAt != null) {
                detachedCount++
                if (status == Status.UNASSIGNED) unassignedCount--
            }
            items.add(ManagedImage(
                f.absolutePath, size, f.lastModified(), owners, status, meta,
                representativeOf = representativeMap[canon] ?: emptyList(),
                // 이미 여기서 한 번 계산한 값이다 — 화면이 다시 계산하지 않게 함께 싣는다.
                canonicalPath = canon
            ))
        }
        items.sortByDescending { it.sizeBytes }  // 기본 정렬: 큰 것부터(정리 우선)
        return items to Summary(
            totalBytes, items.size, refCount, orphanCount, trashCount, unassignedCount, detachedCount
        )
    }

    private fun parsePaths(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching { gson.fromJson<List<String?>>(json, GsonTypes.STRING_LIST) }
            .getOrNull()?.filterNotNull() ?: emptyList()
    }

    /**
     * 이미지 삭제. 참조본이면 소유 엔티티들의 imagePaths에서도 경로를 제거(정합 유지)한 뒤 파일을 지운다.
     * 완료 시 확보 바이트(성공) 또는 null(실패)을 콜백으로 돌려주고 목록을 재로딩한다.
     */
    fun deleteImage(item: ManagedImage, onDone: (Long?) -> Unit) {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { deleteInternal(item) }
            load()
            onDone(freed)
        }
    }

    /**
     * 처분 자체는 [com.novelcharacter.app.util.ImageDeletionService]가 단일 소스다(B-107 D6) —
     * `_삭제승인/`(폴더 받아오기)이 같은 함수를 부른다. 여기서는 목록이 이미 아는 소유자를
     * 그 자료형으로 옮겨 주기만 한다(전수를 다시 훑지 않는다).
     */
    private suspend fun deleteInternal(item: ManagedImage): Long? =
        com.novelcharacter.app.util.ImageDeletionService.delete(
            db = db,
            path = item.path,
            owners = com.novelcharacter.app.util.ImageDeletionService.Owners(
                characterIds = item.owners.filter { it.type == OwnerType.CHARACTER }.map { it.id },
                novelIds = item.owners.filter { it.type == OwnerType.NOVEL }.map { it.id },
                universeIds = item.owners.filter { it.type == OwnerType.UNIVERSE }.map { it.id }
            ),
            linkGroupId = item.meta?.linkGroupId,
            gson = gson
        )

    private fun removePath(json: String, path: String, canon: String): String {
        val kept = parsePaths(json).filter {
            val c = ImagePathMatch.canonical(it)
            it != path && c != canon
        }
        return gson.toJson(kept)
    }

    /** 고아 이미지 일괄 정리 — 기존 SystemMaintenanceService 재사용(참조 손상 시 안전 중단). */
    fun cleanOrphans(onDone: (SystemMaintenanceService.OrphanCleanupResult) -> Unit) {
        viewModelScope.launch {
            val service = SystemMaintenanceService(getApplication(), db)
            val result = service.cleanOrphanImageFiles()
            load()
            onDone(result)
        }
    }

    /**
     * 여러 이미지를 일괄 삭제한다. 각 건은 [deleteInternal] 재사용(참조본이면 소유 엔티티 경로도 함께 제거).
     * 개별 결과를 집계해 돌려주고 목록을 재로딩한다.
     *
     * 진행도는 [onProgress](메인 스레드)로 올라온다 — 총량은 인자 리스트 크기다(규약 R-26 · B-51).
     *
     * **취소는 "중단 시점까지 반영 + 요약"이다.** 한 건 한 건이 그 자체로 완결되므로
     * (파일 삭제 + 소유 엔티티 경로 제거) 끊어도 반쪽 항목이 남지 않는다 — R-26 원문 그대로다.
     * 몇 장까지 지웠는지는 [BulkDeleteResult.deleted]가 들고 있어 호출부가 요약할 수 있다.
     */
    fun deleteImages(
        items: List<ManagedImage>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        onDone: (BulkDeleteResult) -> Unit
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var deleted = 0
                var freed = 0L
                var failed = 0
                var processed = 0
                for (item in items) {
                    if (isCancelled()) break
                    val f = deleteInternal(item)
                    if (f != null) { deleted++; freed += f } else failed++
                    processed++
                    onProgress(processed, items.size)
                }
                BulkDeleteResult(deleted, freed, failed)
            }
            load()
            onDone(result)
        }
    }

    /**
     * 재압축 미리보기 준비 — 대상들을 현재 압축 설정으로 **임시 파일**에 재인코드해 정확한 전/후 크기를 계산한다.
     * 이 단계는 원본을 건드리지 않으며, 결과([RecompressPreview])를 [pendingPreview]에 보관해 확인 뒤 커밋한다.
     * 참조본이 아닌 것(고아/휴지통)과 임계값 미만·손상·이득 없음은 스킵(사유 포함).
     */
    fun prepareRecompress(
        items: List<ManagedImage>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        onReady: (RecompressPreview) -> Unit
    ) {
        _loading.value = true
        // pendingPreview가 세팅되기 전 창을 보호(동시 load()의 sweep가 준비 중 임시 파일을 지우지 않도록).
        preparingRecompress = true
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) { buildPreview(items, onProgress, isCancelled) }
                pendingPreview = preview
                onReady(preview)
            } finally {
                // 성공/실패 모두 스피너 해제 — buildPreview 예외 시 로딩이 멈춰 걸리던 문제 방지(변수 제어).
                // pendingPreview 세팅 이후이므로 sweep 게이트는 여전히 보호됨(pendingPreview != null).
                _loading.value = false
                preparingRecompress = false
            }
        }
    }

    private suspend fun buildPreview(
        items: List<ManagedImage>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): RecompressPreview {
        sweepRecompressTempFiles()  // 직전에 중단된 미리보기 잔여물 정리
        val settings = ImageSettingsStore(getApplication()).getSettings()
        val plans = ArrayList<RecompressPlan>()
        val skips = ArrayList<RecompressSkip>()
        var processed = 0
        for (item in items) {
            if (isCancelled()) {
                // 취소 = 산출물 없음. 여기까지 만든 임시 파일을 지우고 빈 미리보기를 돌려준다.
                plans.forEach { runCatching { File(it.tempPath).delete() } }
                return RecompressPreview(emptyList(), emptyList(), 0L, 0L, cancelled = true)
            }
            processed++
            onProgress(processed, items.size)
            // 참조본과 라이브러리 미배정 모두 재압축 가능한 사용자 자산. 고아·휴지통 보류만 스킵.
            if (item.status == Status.ORPHAN || item.status == Status.TRASH_HELD) {
                skips.add(RecompressSkip(item, ImageImportHelper.SkipReason.NOT_REFERENCED))
                continue
            }
            val prefix = prefixOf(item.path)
            val outcome = ImageImportHelper.recompressToTemp(
                getApplication(), File(item.path), prefix, settings
            )
            val temp = outcome.tempFile
            if (temp != null) {
                plans.add(RecompressPlan(item, temp.absolutePath, temp.length(), prefix))
            } else {
                skips.add(RecompressSkip(item, outcome.skip ?: ImageImportHelper.SkipReason.ERROR))
            }
        }
        val totalBefore = plans.sumOf { it.item.sizeBytes }
        val totalAfter = plans.sumOf { it.newSize }
        return RecompressPreview(plans, skips, totalBefore, totalAfter)
    }

    /** 재압축 취소 — 준비된 임시 파일을 삭제하고 미리보기를 폐기한다. */
    fun discardRecompress() {
        val preview = pendingPreview ?: return
        pendingPreview = null
        viewModelScope.launch(Dispatchers.IO) {
            preview.plans.forEach { runCatching { File(it.tempPath).delete() } }
        }
    }

    /**
     * 재압축 커밋 — 준비된 임시 파일을 정식 이름으로 개명하고, 소유 엔티티들의 경로를 교체(트랜잭션)한 뒤
     * 원본을 삭제한다. 파일 개명(1) → DB 경로 교체(2) → 원본 삭제(3) 순서라, 도중 실패해도
     * 원본은 살아 있고 새 파일만 고아로 남아(안전) 이후 정리 대상이 된다.
     */
    /**
     * @param onProgress (처리한 장, 전체 장, 구간). 총량은 계획 수다(규약 R-26 · B-51).
     *
     * **취소를 제공하지 않는다.** 2단계(소유 엔티티 경로 교체)가 **한 트랜잭션**이라
     * 그 안에는 "여기까지 반영"이라는 경계가 없다 — 끊으면 전부 되돌아가거나 전부 들어간다.
     * 되돌릴 길은 이미 있다(커밋 뒤 [undoLastRecompress]). 오래 걸리는 쪽은 준비 단계이고,
     * 그쪽은 취소를 받는다([prepareRecompress]).
     */
    fun commitRecompress(
        onProgress: (Int, Int, RecompressStage) -> Unit = { _, _, _ -> },
        onDone: (RecompressResult) -> Unit
    ) {
        val preview = pendingPreview
        pendingPreview = null
        if (preview == null || preview.plans.isEmpty()) {
            onDone(RecompressResult(0, 0L, preview?.skips?.size ?: 0))
            return
        }
        viewModelScope.launch {
            // 백스톱: 어떤 예외에도 크래시 대신 '전량 실패'로 통보하고 목록을 재로딩한다(변수 제어).
            val result = try {
                withContext(Dispatchers.IO) { commitInternal(preview, onProgress) }
            } catch (e: Exception) {
                RecompressResult(0, 0L, preview.skips.size, preview.plans.size)
            }
            // 갤러리에서 보던 이미지가 개명됐으면 추적 경로를 갱신 — load()의 목록 반영보다
            // 반드시 선행해야 재정렬 후에도 같은 이미지가 유지된다
            result.pathRemap[galleryPath]?.let { galleryPath = it }
            // 재압축은 파일을 새 UUID로 개명한다 = 정리 폴더 토큰이 끊긴다. 별칭을 남겨
            // 개명 전에 내보낸 사본이 돌아와도 같은 이미지로 인식되게 한다(설계 9장 C-1).
            runCatching {
                com.novelcharacter.app.util.FolderRoundtripPrefs
                    .recordRenames(getApplication(), result.pathRemap)
            }
            // 같은 개명을 대결의 이미지 축도 따라가야 한다(R-42) — 참가자 코드가 곧 경로라,
            // 여기서 옮기지 않으면 쌓아 둔 판이 통째로 고아가 된다.
            runCatching { app.duelRepository.followImageRenames(result.pathRemap) }
            load()
            onDone(result)
        }
    }

    /**
     * 재압축 커밋의 구간 — 단위는 같지만(장) 하는 일이 달라 문구를 나눈다.
     *
     * 사이의 2단계(DB 경로 교체)는 한 트랜잭션이라 항목 경계가 없어 구간으로 세지 않는다.
     */
    enum class RecompressStage { REPLACE, BACKUP }

    private suspend fun commitInternal(
        preview: RecompressPreview,
        onProgress: (Int, Int, RecompressStage) -> Unit = { _, _, _ -> }
    ): RecompressResult {
        // 직전 되돌리기 창은 지났으므로 이전 백업을 정리하고(무한 누적 방지), 오래된 백업(7일)도 함께 쓸어낸다.
        pendingUndo?.forEach { runCatching { File(it.backupPath).delete() } }
        pendingUndo = null
        sweepOldRecompressBackups()
        val filesDir = getApplication<Application>().filesDir

        // 1단계: 임시 파일 → 정식 이름 개명(파일 시스템). 사라졌거나 실패한 건은 스킵.
        val committed = ArrayList<Triple<RecompressPlan, String, String>>() // plan, finalPath, oldCanon
        var renamed = 0
        for (plan in preview.plans) {
            renamed++
            onProgress(renamed, preview.plans.size, RecompressStage.REPLACE)
            val temp = File(plan.tempPath)
            if (!temp.exists()) continue
            val finalFile = File(filesDir, "${plan.prefix}_${UUID.randomUUID()}.jpg")
            // 봉쇄 판정의 단일 소스 (B-106 ⓐ · R-39) — 실패하면 막는다.
            val guardOk = ImagePathMatch.isInside(finalFile.path, filesDir)
            if (!guardOk) { temp.delete(); continue }
            val moved = temp.renameTo(finalFile) || runCatching {
                finalFile.writeBytes(temp.readBytes()); temp.delete(); true
            }.getOrDefault(false)
            if (!moved) { temp.delete(); continue }
            val oldCanon = ImagePathMatch.canonical(plan.item.path)
            committed.add(Triple(plan, finalFile.absolutePath, oldCanon))
        }

        // 2단계: 소유 엔티티 경로 교체(트랜잭션). 실패 시 개명한 정식 파일은 아직 미참조이므로 롤백 삭제하고
        // (원본은 그대로 살아 있음) 전량 실패로 통보 — 크래시·조용한 증발 방지(변수 제어).
        try {
            db.withTransaction {
                for ((plan, finalPath, oldCanon) in committed) {
                    for (owner in plan.item.owners) {
                        when (owner.type) {
                            OwnerType.CHARACTER -> db.characterDao().getCharacterById(owner.id)?.let { e ->
                                db.characterDao().update(e.withImagePaths(
                                    replacePath(e.imagePaths, plan.item.path, oldCanon, finalPath),
                                    mapOf(plan.item.path to finalPath, oldCanon to finalPath)
                                ))
                            }
                            OwnerType.NOVEL -> db.novelDao().getNovelById(owner.id)?.let { e ->
                                db.novelDao().update(e.copy(imagePaths = replacePath(e.imagePaths, plan.item.path, oldCanon, finalPath)))
                            }
                            OwnerType.UNIVERSE -> db.universeDao().getUniverseById(owner.id)?.let { e ->
                                db.universeDao().update(e.copy(imagePaths = replacePath(e.imagePaths, plan.item.path, oldCanon, finalPath)))
                            }
                        }
                    }
                    // 라이브러리 meta 경로도 함께 갱신(태그·링크는 imageId에 붙어 있어 자동 승계).
                    // 저장형이 canonical 변형일 가능성 대비 두 표기 모두 시도 — 행은 최대 1개라 중복 갱신 없음.
                    db.imageMetaDao().updatePath(plan.item.path, finalPath)
                    if (oldCanon != plan.item.path) db.imageMetaDao().updatePath(oldCanon, finalPath)
                }
            }
        } catch (e: Exception) {
            committed.forEach { runCatching { File(it.second).delete() } }  // 미참조 개명본 롤백 삭제
            return RecompressResult(0, 0L, preview.skips.size, preview.plans.size)
        }

        // 3단계: 원본을 영구 삭제하지 않고 백업 디렉터리로 옮긴다(되돌리기용). freed = 원본 − 재압축본(백업 정리 후 실제 확보).
        val backupDir = recompressBackupDir()
        val undo = ArrayList<RecompressUndoEntry>()
        var freed = 0L
        var backedUpCount = 0
        for ((plan, finalPath, _) in committed) {
            backedUpCount++
            onProgress(backedUpCount, committed.size, RecompressStage.BACKUP)
            val orig = File(plan.item.path)
            val origLen = orig.length()
            val newLen = File(finalPath).length()
            val backup = File(backupDir, "${UUID.randomUUID()}_${orig.name}")
            val backedUp = orig.renameTo(backup) || runCatching {
                backup.writeBytes(orig.readBytes()); orig.delete(); true
            }.getOrDefault(false)
            if (backedUp) {
                runCatching { backup.setLastModified(System.currentTimeMillis()) }  // 수명은 백업 생성 시점 기준
                undo.add(RecompressUndoEntry(plan.item.path, backup.absolutePath, finalPath, plan.item.owners))
                freed += (origLen - newLen).coerceAtLeast(0L)
            } else if (orig.delete()) {
                // 백업 이동 실패 → 공간 확보 우선으로 원본 삭제(이 건만 되돌리기 불가). 커밋 자체는 유효.
                freed += (origLen - newLen).coerceAtLeast(0L)
            }
        }
        pendingUndo = undo.ifEmpty { null }
        // 준비됐으나 커밋 못 한 건(개명·이동 실패 등) = 계획 − 반영. 조용히 증발하지 않도록 집계·통보.
        val failed = preview.plans.size - committed.size
        return RecompressResult(committed.size, freed, preview.skips.size, failed,
            pathRemap = committed.associate { (plan, finalPath, _) -> plan.item.path to finalPath })
    }

    /**
     * 방금 재압축한 원본을 복원한다: 백업 → 원위치, DB 경로를 재압축본 → 원본으로 되돌리고(트랜잭션) 재압축본 삭제.
     *
     * 진행도는 [onProgress]로 올라온다 — 총량은 되돌릴 항목 수다(규약 R-26 · B-51).
     *
     * **취소를 제공하지 않는다.** 되돌리기는 이미 "직전 작업을 무르는" 복구 행위인데 그것을
     * 다시 중간에 끊으면 어느 이미지가 원본이고 어느 것이 재압축본인지 사용자가 알 길이 없다.
     * 표시는 하되 끝까지 간다.
     */
    fun undoLastRecompress(
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDone: (Boolean) -> Unit
    ) {
        val entries = pendingUndo
        if (entries.isNullOrEmpty()) { onDone(false); return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { restoreRecompress(entries, onProgress) }
            pendingUndo = null
            load()
            onDone(ok)
        }
    }

    private suspend fun restoreRecompress(
        entries: List<RecompressUndoEntry>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Boolean {
        var allOk = true
        var processed = 0
        for (e in entries) {
            processed++
            onProgress(processed, entries.size)
            val backup = File(e.backupPath)
            val original = File(e.originalPath)
            // 1) 백업 → 원위치 복원
            val restored = backup.exists() && (backup.renameTo(original) || runCatching {
                original.writeBytes(backup.readBytes()); backup.delete(); true
            }.getOrDefault(false))
            if (!restored) { allOk = false; continue }
            // 2) DB 경로를 재압축본 → 원본으로 되돌림(트랜잭션) 후 3) 재압축 산출물 삭제
            val finalCanon = ImagePathMatch.canonical(e.finalPath)
            runCatching {
                db.withTransaction {
                    for (owner in e.owners) {
                        when (owner.type) {
                            OwnerType.CHARACTER -> db.characterDao().getCharacterById(owner.id)?.let { c ->
                                db.characterDao().update(c.withImagePaths(
                                    replacePath(c.imagePaths, e.finalPath, finalCanon, e.originalPath),
                                    mapOf(e.finalPath to e.originalPath, finalCanon to e.originalPath)
                                ))
                            }
                            OwnerType.NOVEL -> db.novelDao().getNovelById(owner.id)?.let { n ->
                                db.novelDao().update(n.copy(imagePaths = replacePath(n.imagePaths, e.finalPath, finalCanon, e.originalPath)))
                            }
                            OwnerType.UNIVERSE -> db.universeDao().getUniverseById(owner.id)?.let { u ->
                                db.universeDao().update(u.copy(imagePaths = replacePath(u.imagePaths, e.finalPath, finalCanon, e.originalPath)))
                            }
                        }
                    }
                    // 라이브러리 meta 경로 역갱신(재압축본 → 원본).
                    db.imageMetaDao().updatePath(e.finalPath, e.originalPath)
                    if (finalCanon != e.finalPath) db.imageMetaDao().updatePath(finalCanon, e.originalPath)
                }
                File(e.finalPath).delete()
                // 되돌리기도 개명이다(재압축본 → 원본) — 별칭을 남기지 않으면 재압축 뒤에
                // 내보낸 사본의 토큰이 사라진 파일을 가리킨 채 끊긴다(C-1).
                com.novelcharacter.app.util.FolderRoundtripPrefs
                    .recordRenames(getApplication(), mapOf(e.finalPath to e.originalPath))
                // 되돌리기도 개명이라 대결의 이미지 축이 함께 물러나야 한다(R-42) —
                // 안 물리면 커밋 때 옮겨 둔 판이 이제 없는 재압축본을 가리킨다.
                app.duelRepository.followImageRenames(mapOf(e.finalPath to e.originalPath))
            }.onFailure { allOk = false }
        }
        return allOk
    }

    // ===== 라이브러리(임포트·태그) =====

    /**
     * 이미지 탭 직접 임포트 — 시스템 픽커의 URI들을 img_ 접두로 filesDir에 복사(압축 설정 적용)하고
     * **즉시** image_meta에 등록해 미배정 상태로 편입한다(파일 생성 직후 meta 선삽입이라 고아 정리·
     * 24h 가드와 이중으로 보호됨). 실패 건수는 집계해 통보한다(변수 제어).
     */
    fun importImages(uris: List<android.net.Uri>, onDone: (ImportResult) -> Unit) {
        _loading.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val settings = ImageSettingsStore(getApplication()).getSettings()
                var imported = 0
                var failed = 0
                val now = System.currentTimeMillis()
                for (uri in uris) {
                    val path = ImageImportHelper.importImage(getApplication(), uri, "img", settings)
                    if (path != null) {
                        runCatching {
                            db.imageMetaDao().insert(com.novelcharacter.app.data.model.ImageMeta(path = path, importedAt = now))
                        }
                        imported++
                    } else {
                        failed++
                    }
                }
                ImportResult(imported, failed)
            }
            load()
            onDone(result)
        }
    }

    /**
     * 단일 편집 시트의 태그 교체 — **링크 묶음 전원에** 적용한다(공유 불변식 —
     * [com.novelcharacter.app.util.LinkGroupFold] 헤더). 시트가 묶음 합집합을 보여 주고
     * 사전 고지를 달므로(호출측), 전원 교체가 곧 사용자가 화면에서 본 것 그대로다 —
     * 형제만 가진 태그가 몰래 지워질 자리가 없다(합집합에 이미 보였다).
     * 필요 시 라이브러리로 입양(adopt) 후 교체. @param onDone 반영된 장수.
     */
    fun replaceTags(path: String, tags: List<String>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val applied = withContext(Dispatchers.IO) {
                runCatching {
                    val targets = liveTagRoster(listOf(path))[path] ?: listOf(path)
                    val now = System.currentTimeMillis()
                    db.withTransaction {
                        val idByPath = ImageAdoption.adoptAll(db, targets, now)
                        var done = 0
                        for (t in targets) {
                            val id = idByPath[t] ?: continue
                            db.imageTagDao().replaceAllForImage(
                                id, tags.map { com.novelcharacter.app.data.model.ImageTag(imageId = id, tag = it) }
                            )
                            done++
                        }
                        done
                    }
                }.getOrDefault(0)
            }
            load()
            onDone(applied)
        }
    }

    /** 태그 제안 목록 — 이미지 태그 ∪ 캐릭터 태그(어휘 공유 → 편집창 추천 매칭 정확도 향상). */
    suspend fun getTagSuggestions(): List<String> = withContext(Dispatchers.IO) {
        val imageTags = runCatching { db.imageTagDao().getAllDistinctTags() }.getOrDefault(emptyList())
        val charTags = runCatching { db.characterTagDao().getAllDistinctTags() }.getOrDefault(emptyList())
        (imageTags + charTags).distinct().sorted()
    }

    /** 태그 필터 시트용 — 이미지 태그 distinct 목록. */
    suspend fun getAllImageTags(): List<String> = withContext(Dispatchers.IO) {
        runCatching { db.imageTagDao().getAllDistinctTags() }.getOrDefault(emptyList())
    }

    /** 선택 경로들의 distinct 태그(일괄 태그 제거 시트용) — 현재 목록의 meta id 기준. */
    suspend fun getDistinctTagsForPaths(paths: Collection<String>): List<String> = withContext(Dispatchers.IO) {
        val ids = metaIdsForPaths(paths)
        // 질의의 `DISTINCT`·`ORDER BY`는 **조각 안에서만** 성립한다 — 나눠 물으면 같은 태그가
        // 조각마다 한 번씩 나오고 순서도 조각 경계에서 되감긴다. 그래서 접고 정렬하는 일을
        // 여기로 올린다(칩 목록은 정렬된 것이 계약이다. B-242).
        runCatching {
            SqlInChunks.flat(ids) { db.imageTagDao().getDistinctTagsForImages(it) }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }

    // ===== 배정 / 해제 / 링크 (G2) =====

    data class AssignResult(
        val assigned: Int, val alreadyOwned: Int, val viaLink: Int,
        val modeChanged: Boolean, val failed: Boolean = false
    )
    data class UnassignResult(val cleared: Int, val adopted: Int, val failed: Boolean = false)
    /**
     * 링크 해제 결과. [autoRelinkable] = 자동 링크(캐릭터 묶음)였고 그 캐릭터에 여전히 등록되어
     * 있어, 자동 링크 설정이 켜진 동안 다음 재동기화(저장·배정 등)가 도로 묶을 이미지 수 —
     * 해제가 조용히 되돌아가지 않도록 고지에 쓴다(변수 제어).
     */
    data class UnlinkResult(val cleared: Int, val autoRelinkable: Int)
    sealed class LinkOutcome {
        data class Done(val linked: Int, val merged: Boolean) : LinkOutcome()
        data class NeedsMerge(val groups: Int) : LinkOutcome()
        object Failed : LinkOutcome()
    }
    /** 배정 대상 목록 행(피커 표시용). */
    data class PickRow(val id: Long, val title: String, val subtitle: String)

    /** '새 캐릭터 만들기'가 고를 수 있는 작품 목록 — 비워 두면 미분류 캐릭터가 된다. */
    suspend fun getNovelChoices(): List<PickRow> = withContext(Dispatchers.IO) {
        val unis = db.universeDao().getAllUniversesList().associateBy({ it.id }, { it.name })
        db.novelDao().getAllNovelsList().sortedBy { it.title }
            .map { PickRow(it.id, it.title, it.universeId?.let { u -> unis[u] } ?: "") }
    }

    /**
     * 이미지 탭에서 캐릭터를 즉시 만든다 — **러프 입력 경로**(원칙 04의 이중 경로).
     *
     * 이름만 받고 나머지는 기본값으로 둔다. 정밀 조정은 캐릭터 편집 화면이 한다.
     * `novelId`가 null이면 **미분류 캐릭터**가 되는데, 그것은 이 앱이 이미 지원하는 상태다
     * (엑셀에도 '미분류 캐릭터' 시트가 있다) — 작품을 강제하면 이미지 한 장 붙이려고
     * 작품부터 만들어야 해서 막힌 자리가 그대로 남는다.
     *
     * @return 만들어진 캐릭터의 [PickRow]. 배정은 호출부가 기존 경로로 잇는다.
     */
    suspend fun createCharacterForAssign(name: String, novelId: Long?): PickRow =
        withContext(Dispatchers.IO) {
            val character = com.novelcharacter.app.data.model.Character(
                name = name.trim(),
                novelId = novelId
            )
            val newId = db.characterDao().insert(character)
            val subtitle = novelId?.let { id ->
                db.novelDao().getAllNovelsList().firstOrNull { it.id == id }?.title
            } ?: ""
            PickRow(newId, character.name, subtitle)
        }

    /**
     * 경로 → 그 경로가 속한 링크 묶음 전원(현재 목록 기준, 2장 이상만) — 태그 공유 불변식
     * ([com.novelcharacter.app.util.LinkGroupFold] 헤더)의 전개 표 재료. **적용 직전에 떠서**
     * `expandPicked`에 먹인다 — 실행 시점 표를 들고 다니면 검토 중 링크를 바꿨을 때 낡은
     * 명단에 붙는다(그 함수 계약이 정본).
     */
    private fun liveTagRoster(paths: Collection<String>): Map<String, List<String>> {
        val items = _images.value ?: return emptyMap()
        val membersByGroup = HashMap<String, MutableList<String>>()
        for (item in items) {
            val g = item.meta?.linkGroupId ?: continue
            membersByGroup.getOrPut(g) { mutableListOf() }.add(item.path)
        }
        val byPath = currentItemsByPath()
        val out = HashMap<String, List<String>>()
        for (p in paths) {
            val g = byPath[p]?.meta?.linkGroupId ?: continue
            val members = membersByGroup[g] ?: continue
            if (members.size >= 2) out[p] = members
        }
        return out
    }

    /** 링크 묶음 토큰 → 현재 목록의 전체 식구 수 — AI 표본 계획의 묶음 판정 재료. */
    fun linkGroupFullSizes(): Map<String, Int> =
        (_images.value ?: emptyList()).mapNotNull { it.meta?.linkGroupId }
            .groupingBy { it }.eachCount()

    /**
     * 이 경로가 속한 링크 묶음 전원(자신 포함, 현재 목록 기준). 미링크·외톨이면 자신뿐.
     * 단일 태그 편집 시트가 합집합을 보여 줄 재료다 — **필터로 가려진 식구도 든다**
     * (전개도 전 목록 기준이라, 화면 목록으로 세면 고지와 실제가 갈린다).
     */
    fun linkedFamily(path: String): List<ManagedImage> {
        val items = _images.value ?: return emptyList()
        val self = items.firstOrNull { it.path == path } ?: return emptyList()
        val g = self.meta?.linkGroupId ?: return listOf(self)
        val members = items.filter { it.meta?.linkGroupId == g }
        return if (members.size >= 2) members else listOf(self)
    }

    /** 현재 목록 기준 링크 meta(경로↔그룹) — 확장·계획 판정의 입력. */
    private fun currentMetas(): List<ImageLinkResolver.Meta> =
        (_images.value ?: emptyList()).mapNotNull { item ->
            item.meta?.let { ImageLinkResolver.Meta(item.path, it.linkGroupId) }
        }

    private fun currentItemsByPath(): Map<String, ManagedImage> =
        (_images.value ?: emptyList()).associateBy { it.path }

    private fun metaIdsForPaths(paths: Collection<String>): List<Long> {
        val byPath = currentItemsByPath()
        return paths.mapNotNull { byPath[it]?.meta?.imageId }
    }

    /** 선택을 링크 그룹 전체로 확장(사전 확인 다이얼로그용 — D5: 조용한 확대 금지). */
    fun expandWithLinkedGroups(paths: Collection<String>): ImageLinkResolver.Expansion =
        ImageLinkResolver.expand(paths, currentMetas())

    /** 배정 대상 목록 — 캐릭터(부제=작품명)/작품(부제=세계관명)/세계관. */
    suspend fun getAssignTargets(type: OwnerType): List<PickRow> = withContext(Dispatchers.IO) {
        when (type) {
            OwnerType.CHARACTER -> {
                val novels = db.novelDao().getAllNovelsList().associateBy({ it.id }, { it.title })
                db.characterDao().getAllCharactersList().sortedBy { it.name }
                    .map { PickRow(it.id, it.name, it.novelId?.let { n -> novels[n] } ?: "") }
            }
            OwnerType.NOVEL -> {
                val unis = db.universeDao().getAllUniversesList().associateBy({ it.id }, { it.name })
                db.novelDao().getAllNovelsList().sortedBy { it.title }
                    .map { PickRow(it.id, it.title, it.universeId?.let { u -> unis[u] } ?: "") }
            }
            OwnerType.UNIVERSE ->
                db.universeDao().getAllUniversesList().sortedBy { it.name }
                    .map { PickRow(it.id, it.name, "") }
        }
    }

    /**
     * 배정 = 경로 공유(D6): 링크 그룹 확장(D5) 후 대상 엔티티 imagePaths에 append(중복 canonical 스킵).
     * 작품/세계관은 imageMode==none이고 **기존 이미지가 0장이던 경우에만** custom으로 자동 전환+고지
     * (사용자가 의도적으로 none 설정한 카드는 존중).
     */
    /**
     * @param onProgress (처리한 장, 전체 장). 총량은 링크 그룹으로 넓힌 뒤의 경로 수다 —
     *   선택한 수가 아니라 **실제로 도는 수**여야 막대가 갈리지 않는다(규약 R-26 · B-51).
     *
     * **취소를 제공하지 않는다.** 배정은 대상 엔티티 한 행을 통째로 갈아 끼우는 한 트랜잭션이라
     * 중간 경계가 없다. 경로마다 `canonicalPath`를 묻는(파일 시스템 호출) 대목이 실제 비용이고,
     * 그것이 어디까지 갔는지를 보여 주는 것이 이 진행도의 몫이다.
     */
    fun assignToTarget(
        paths: List<String>,
        type: OwnerType,
        targetId: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDone: (AssignResult) -> Unit
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val expansion = expandWithLinkedGroups(paths)
                    var assigned = 0
                    var already = 0
                    var modeChanged = false
                    val appendTotal = expansion.allPaths.size
                    var appended = 0
                    val reportAppend: () -> Unit = { onProgress(++appended, appendTotal) }
                    db.withTransaction {
                        when (type) {
                            OwnerType.CHARACTER -> {
                                val c = requireNotNull(db.characterDao().getCharacterById(targetId))
                                val (json, added, skipped) = appendPaths(c.imagePaths, expansion.allPaths, reportAppend)
                                db.characterDao().update(c.withImagePaths(json))
                                assigned = added; already = skipped
                                // 다시 붙였으면 서랍에서 뺀다(B-107 D2 — 푸는 자리 ①).
                                // 판정은 단일 소스가 하므로 여기서 "뗀 것이었나"를 묻지 않는다.
                                com.novelcharacter.app.util.DetachedImageMarker
                                    .sync(db, expansion.allPaths, c.code)
                            }
                            OwnerType.NOVEL -> {
                                val n = requireNotNull(db.novelDao().getNovelById(targetId))
                                val wasEmpty = parsePaths(n.imagePaths).isEmpty()
                                val (json, added, skipped) = appendPaths(n.imagePaths, expansion.allPaths, reportAppend)
                                var updated = n.copy(imagePaths = json)
                                if (n.imageMode == com.novelcharacter.app.data.model.Novel.IMAGE_MODE_NONE && wasEmpty && added > 0) {
                                    updated = updated.copy(imageMode = com.novelcharacter.app.data.model.Novel.IMAGE_MODE_CUSTOM)
                                    modeChanged = true
                                }
                                db.novelDao().update(updated)
                                assigned = added; already = skipped
                            }
                            OwnerType.UNIVERSE -> {
                                val u = requireNotNull(db.universeDao().getUniverseById(targetId))
                                val wasEmpty = parsePaths(u.imagePaths).isEmpty()
                                val (json, added, skipped) = appendPaths(u.imagePaths, expansion.allPaths, reportAppend)
                                var updated = u.copy(imagePaths = json)
                                if (u.imageMode == com.novelcharacter.app.data.model.Universe.IMAGE_MODE_NONE && wasEmpty && added > 0) {
                                    updated = updated.copy(imageMode = com.novelcharacter.app.data.model.Universe.IMAGE_MODE_CUSTOM)
                                    modeChanged = true
                                }
                                db.universeDao().update(updated)
                                assigned = added; already = skipped
                            }
                        }
                    }
                    // 캐릭터 배정 = 등록 변경 — 자동 링크를 재동기화해 새 식구를 묶는다(설정 켜짐 시)
                    if (type == OwnerType.CHARACTER) {
                        runCatching {
                            com.novelcharacter.app.util.CharacterImageAutoLinker
                                .resyncIfEnabled(getApplication(), db)
                        }
                    }
                    AssignResult(assigned, already, expansion.addedByLink.size, modeChanged)
                } catch (e: Exception) {
                    AssignResult(0, 0, 0, false, failed = true)
                }
            }
            load()
            onDone(result)
        }
    }

    /**
     * imagePaths JSON에 경로들을 append(중복 canonical 스킵). @return (새 JSON, 추가 수, 스킵 수)
     *
     * [onEach]는 경로 하나를 훑을 때마다 불린다 — 이 루프가 경로마다 `canonicalPath`를 묻는
     * 파일 시스템 호출이라 수백 장에서 실제로 걸리는 자리다(B-51 진행도의 대상).
     */
    private fun appendPaths(
        json: String,
        adds: Collection<String>,
        onEach: () -> Unit = {}
    ): Triple<String, Int, Int> {
        val current = parsePaths(json)
        val canonSet = current.mapTo(HashSet()) { ImagePathMatch.canonical(it) }
        val result = current.toMutableList()
        var added = 0
        var skipped = 0
        for (p in adds) {
            onEach()
            val c = ImagePathMatch.canonical(p)
            if (c in canonSet) { skipped++ } else { result.add(p); canonSet.add(c); added++ }
        }
        return Triple(gson.toJson(result), added, skipped)
    }

    /**
     * 배정 해제 — 지정 소유자([ownerFilter], null=전체)에게서 경로를 제거한다(파일 유지).
     * 소유자 0이 된 비-라이브러리 경로는 자동 입양(미배정 복귀) — 명시적 비삭제 행위가
     * 이후 고아 정리에 지워질 고아를 만들지 않게 한다(변수 제어).
     */
    /**
     * @param onProgress (처리한 장, 전체 장). 총량은 인자 리스트 크기다(규약 R-26 · B-51).
     *
     * **취소를 제공하지 않는다.** 경로마다 소유 엔티티를 고쳐 쓰는 일이 **한 트랜잭션**이라
     * 중간 경계가 없다 — 끊으면 전부 되돌아가거나 전부 들어간다.
     */
    fun unassign(
        paths: List<String>,
        ownerFilter: List<Owner>?,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDone: (UnassignResult) -> Unit
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val byPath = currentItemsByPath()
                    var cleared = 0
                    var adopted = 0
                    var processed = 0
                    // 뗀 표식의 후보 — 캐릭터에서 뗀 것만 모은다. 출처(code)를 경로마다 들고
                    // 가는 이유는 일괄 해제가 **여러 캐릭터에 걸쳐** 일어나기 때문이다.
                    val detachCandidates = ArrayList<com.novelcharacter.app.util.DetachedImageRule.Candidate>()
                    db.withTransaction {
                        // 경로마다 왕복 셋을 치르던 `adopt`를 목록 하나로 내린다 (B-244).
                        //
                        // **앞으로 모아도 답이 같은 근거:** 입양 여부를 정하는 셋이 전부 루프
                        // 밖에서 이미 정해져 있다 — 스냅샷(`byPath`)의 소유·meta와 파일 존재다.
                        // 루프가 쓰는 것(소유 엔티티의 `imagePaths`)은 그 판정에 들어가지 않으므로
                        // 제가 읽는 것을 바꾸지 않는다(B-239의 잣대).
                        //
                        // 순서가 뒤바뀌는 자리 하나를 확인해 두었다: 뒤 항목의 입양이 앞 항목의
                        // `promoteToUserByPaths`보다 **먼저** 일어날 수 있다. 그래도 결과가 같다 —
                        // 새로 넣는 행은 이미 사용자 소유(`adoptSource = null`)라 승격이 무의미하고,
                        // 승격은 null을 null로 덮을 뿐이다.
                        //
                        // **판정은 여기 한 번뿐이고 루프는 그 답을 볼 뿐이다.** 아래에서 같은
                        // 조건을 다시 적으면 `File(path).exists()`가 **두 번** 평가되어, 그 사이에
                        // 파일이 생기면 위에서 안 뽑은 경로를 아래가 요구한다(= 전체 실패).
                        // 같은 판정이 두 자리에 살면 한쪽이 낡는다 — 이 저장소가 반복해 겪은 모양이다.
                        val adoptTargets = paths.filter { path ->
                            val item = byPath[path] ?: return@filter false
                            val targets = ownerFilter?.filter { it in item.owners } ?: item.owners
                            targets.isNotEmpty() &&
                                (item.owners - targets.toSet()).isEmpty() &&
                                item.meta == null &&
                                File(path).exists()
                        }
                        val adoptTargetSet = adoptTargets.toHashSet()
                        // 빈 목록이면 [ImageAdoption]이 질의 없이 곧장 돌아온다.
                        ImageAdoption.adoptAll(db, adoptTargets, System.currentTimeMillis())
                        for (path in paths) {
                            onProgress(++processed, paths.size)
                            val item = byPath[path] ?: continue
                            val targets = ownerFilter?.filter { it in item.owners } ?: item.owners
                            if (targets.isEmpty()) continue
                            val canon = ImagePathMatch.canonical(path)
                            for (owner in targets) {
                                when (owner.type) {
                                    OwnerType.CHARACTER -> db.characterDao().getCharacterById(owner.id)?.let { c ->
                                        db.characterDao().update(c.withImagePaths(removePath(c.imagePaths, path, canon)))
                                        detachCandidates.add(
                                            com.novelcharacter.app.util.DetachedImageRule
                                                .Candidate(path, c.code)
                                        )
                                    }
                                    OwnerType.NOVEL -> db.novelDao().getNovelById(owner.id)?.let { n ->
                                        db.novelDao().update(n.copy(imagePaths = removePath(n.imagePaths, path, canon)))
                                    }
                                    OwnerType.UNIVERSE -> db.universeDao().getUniverseById(owner.id)?.let { u ->
                                        db.universeDao().update(u.copy(imagePaths = removePath(u.imagePaths, path, canon)))
                                    }
                                }
                            }
                            cleared++
                            // 종전의 `remaining.isEmpty() && item.meta == null && File(path).exists()`가
                            // 그대로 위 `adoptTargets`의 술어다 — 여기서는 그 답만 본다.
                            if (path in adoptTargetSet) {
                                adopted++
                            } else if (item.meta != null) {
                                // 명시적 "파일은 남긴다" 행위 — 자동 입양 행이었더라도 사용자
                                // 소유로 승격해, 링크 해제 후 자동 반납(→고아화)되지 않게 한다.
                                db.imageMetaDao().promoteToUserByPaths(listOf(path, canon))
                            }
                        }
                        // 소유를 다 고친 **뒤에** 판정한다 — 같은 트랜잭션이라 표식과 소유가
                        // 함께 들어가거나 함께 되돌아간다(B-107 D2 — 붙는 자리 ①).
                        com.novelcharacter.app.util.DetachedImageMarker.sync(db, detachCandidates)
                    }
                    // 캐릭터에서 빠진 이미지의 자동 링크를 풀어 준다(설정 켜짐 시)
                    runCatching {
                        com.novelcharacter.app.util.CharacterImageAutoLinker
                            .resyncIfEnabled(getApplication(), db)
                    }
                    UnassignResult(cleared, adopted)
                } catch (e: Exception) {
                    UnassignResult(0, 0, failed = true)
                }
            }
            load()
            onDone(result)
        }
    }

    /**
     * 뗀 표식을 지운다 — 서랍에서 뺀다(B-107 D2의 푸는 길 ②).
     *
     * **소유를 보지 않는다.** 판정을 태우면 캐릭터가 안 쓰는 이미지는 지워도 곧바로 다시 붙어
     * 버튼이 듣지 않는다. 안 쓰면서 서랍에도 두지 않는 것은 사용자의 자유다.
     */
    fun clearDetachedMark(paths: List<String>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) {
                runCatching {
                    com.novelcharacter.app.util.DetachedImageMarker.clearMark(db, paths)
                    paths.size
                }.getOrDefault(0)
            }
            load()
            onDone(cleared)
        }
    }

    /**
     * 링크 생성(D5): ≥2장, 전원 라이브러리 입양. 기존 그룹 1개에 걸치면 그 그룹으로 흡수,
     * 2개 이상 걸치면 [LinkOutcome.NeedsMerge]를 돌려 확인 후 [confirmMerge]=true로 재호출.
     */
    fun linkImages(paths: List<String>, confirmMerge: Boolean, onDone: (LinkOutcome) -> Unit) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val plan = ImageLinkResolver.planLink(paths, currentMetas())
                    if (plan.needsMergeConfirm && !confirmMerge) {
                        LinkOutcome.NeedsMerge(plan.groupsInvolved.size)
                    } else {
                        db.withTransaction {
                            val now = System.currentTimeMillis()
                            // 경로마다 왕복 셋을 치르던 `adopt`를 목록 하나로 내린다 (B-244).
                            val idByPath = ImageAdoption.adoptAll(db, paths, now)
                            val ids = paths.map { idByPath.getValue(it) }.toMutableSet()
                            // 토큰마다 묻던 `getByGroup`을 일괄 조회 한 번으로 내린다 (B-241).
                            // **겹이 필요 없는 자리다** — 이 블록의 읽기는 전부 아래 `setGroup`
                            // 앞에서 끝나므로, 제가 읽는 것을 쓰는 가져오기 쪽 루프와 다르다
                            // (그쪽 처방이 `ImageLinkGroupPlanner`다 — B-239).
                            SqlInChunks.flat(plan.groupsInvolved) { db.imageMetaDao().getByGroups(it) }
                                .forEach { ids.add(it.id) }
                            val groupId = plan.groupsInvolved.firstOrNull() ?: UUID.randomUUID().toString()
                            SqlInChunks.each(ids.toList()) { db.imageMetaDao().setGroup(it, groupId) }
                        }
                        // 다시 묶였으면 '흩어진 나머지'가 아니다 — 장부 ③에서 뺀다.
                        // 안 빼면 사용자가 고친 뒤에도 어시스턴트 카드가 계속 뜬다.
                        runCatching {
                            FolderRoundtripPrefs.removeScatteredPaths(getApplication(), paths)
                        }
                        LinkOutcome.Done(paths.size, plan.needsMergeConfirm)
                    }
                } catch (e: Exception) {
                    LinkOutcome.Failed
                }
            }
            if (outcome !is LinkOutcome.NeedsMerge) load()
            onDone(outcome)
        }
    }

    /**
     * 링크 해제 — 대상 경로들의 그룹을 지우고, 1장만 남은 그룹은 자동 정리.
     * 자동 링크(캐릭터 묶음)를 풀면서 그 캐릭터 등록이 그대로면 다음 재동기화가 도로 묶으므로,
     * 그 수를 [UnlinkResult.autoRelinkable]로 세어 호출부가 고지한다(설정 꺼짐이면 0).
     */
    fun unlinkImages(paths: List<String>, onDone: (UnlinkResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val byPath = currentItemsByPath()
                    val targets = paths.mapNotNull { p ->
                        byPath[p]?.meta?.let { m -> if (m.linkGroupId != null) Triple(p, m.imageId, m.linkGroupId) else null }
                    }
                    if (targets.isEmpty()) UnlinkResult(0, 0)
                    else {
                        db.withTransaction {
                            SqlInChunks.each(targets.map { it.second }) {
                                db.imageMetaDao().setGroup(it, null)
                            }
                            // 토큰마다 돌던 정리를 일괄판 하나로 접는다 (B-241) — 묶음이 행을
                            // 나눠 가지므로 서로의 인원을 못 바꾸고, 그래서 갈라 불러도 답이
                            // 같다(`clearSingletonGroups`의 주석이 그 근거의 단일 소스다).
                            SqlInChunks.each(targets.mapNotNullTo(LinkedHashSet()) { it.third }) {
                                db.imageMetaDao().clearSingletonGroups(it)
                            }
                        }
                        UnlinkResult(targets.size, countAutoRelinkable(targets))
                    }
                } catch (e: Exception) { UnlinkResult(0, 0) }
            }
            load()
            onDone(result)
        }
    }

    /** 풀린 자동 링크 중 캐릭터 등록이 그대로라 재동기화가 도로 묶을 것들을 센다. */
    private suspend fun countAutoRelinkable(targets: List<Triple<String, Long, String?>>): Int {
        val autoTargets = targets.filter { com.novelcharacter.app.util.AutoLinkPlanner.isAutoToken(it.third) }
        if (autoTargets.isEmpty()) return 0
        if (!com.novelcharacter.app.util.ImageSettingsStore(getApplication()).getAutoLinkByCharacter()) return 0
        val byPath = currentItemsByPath()
        return autoTargets.count { (path, _, group) ->
            val ownerId = com.novelcharacter.app.util.AutoLinkPlanner.characterIdOf(group) ?: return@count false
            byPath[path]?.owners?.any { it.type == OwnerType.CHARACTER && it.id == ownerId } == true
        }
    }

    /**
     * 일괄 태그 추가 — **링크 묶음으로 넓힌 뒤**(공유 불변식 — 배정 [assignToTarget]과 같은
     * 확장, 시트가 사전 고지한다) 전원 입양, 태그 삽입(중복 IGNORE). @return 반영 이미지 수
     *
     * @param onProgress (처리한 장, 전체 장). 총량은 **링크로 넓힌 뒤의 장수**다 —
     *   선택한 수가 아니라 실제로 도는 수여야 막대가 갈리지 않는다(규약 R-26 · B-51).
     *   호출측(일괄 시트)이 이미 넓힌 목록을 넘겨도 확장은 멱등이라 답이 같다.
     *
     * **취소를 제공하지 않는다** — 한 트랜잭션이라 중간 경계가 없다.
     */
    fun addTagsToImages(
        paths: List<String>,
        tags: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDone: (Int) -> Unit
    ) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    val targets = expandWithLinkedGroups(paths).allPaths.toList()
                    var processed = 0
                    db.withTransaction {
                        val now = System.currentTimeMillis()
                        // 경로마다 왕복 셋을 치르던 `adopt`를 목록 하나로 내린다 (B-244).
                        // 진행도는 그대로 경로마다 오른다 — 남은 일(태그 쓰기)이 여전히 경로마다다.
                        val idByPath = ImageAdoption.adoptAll(db, targets, now)
                        for (p in targets) {
                            onProgress(++processed, targets.size)
                            val id = idByPath.getValue(p)
                            db.imageTagDao().insertAll(tags.map { com.novelcharacter.app.data.model.ImageTag(imageId = id, tag = it) })
                        }
                    }
                    targets.size
                } catch (e: Exception) { 0 }
            }
            load()
            onDone(count)
        }
    }

    /**
     * 일괄 태그 제거 — 라이브러리 이미지들에서 지정 태그 제거. @return 대상 이미지 수
     *
     * @param onProgress (처리한 장, 전체 장). 총량은 대상 이미지 수다(규약 R-26 · B-51).
     *
     * **삭제 질의를 [SqlInChunks]로 끊어 보낸다.** 종전에는 선택분 전량을 `IN (:imageIds)` 하나에
     * 실었는데, SQLite의 바인딩 변수 상한(999)을 넘으면 질의 자체가 예외로 죽는다 —
     * 그런데 이 함수의 `catch`가 그것을 0으로 삼켜 **"태그를 지웠다고 생각했는데 그대로"**가
     * 된다(조용한 유실). **이 자리가 R-54의 `reservedBinds`가 있는 이유다** — 질의가
     * `imageId IN (…) AND tag IN (…)`이라 두 목록이 한 예산을 나눠 쓴다.
     * 끊어 보내면 진행도의 단위도 생긴다.
     */
    fun removeTagsFromImages(
        paths: List<String>,
        tags: List<String>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDone: (Int) -> Unit
    ) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    // 링크 묶음으로 넓힌다 — 추가만 넓히고 제거를 안 넓히면 지운 태그가
                    // 형제에 남아 묶음이 도로 갈라진다(공유 불변식의 대칭 축. 시트가 사전 고지).
                    val ids = metaIdsForPaths(expandWithLinkedGroups(paths).allPaths)
                    var processed = 0
                    // 질의는 `imageId IN (…) AND tag IN (…)`이라 **두 목록이 함께** 변수를 쓴다 —
                    // 태그 몫을 빼지 않으면 태그가 많을 때 다시 상한에 닿는다. 그 빼기는
                    // [SqlInChunks.sizeFor]가 단일 소스이고, 여기서 손으로 되풀이하지 않는다.
                    SqlInChunks.each(ids, reservedBinds = tags.size) { chunk ->
                        db.imageTagDao().deleteTagsFromImages(chunk, tags)
                        processed += chunk.size
                        onProgress(processed, ids.size)
                    }
                    ids.size
                } catch (e: Exception) { 0 }
            }
            load()
            onDone(count)
        }
    }

    /** 7일 지난 재압축 백업 정리 — subdir라 고아 정리 대상이 아니므로 여기서 수명 관리(무한 누적 방지). */
    private fun sweepOldRecompressBackups() {
        val dir = File(getApplication<Application>().filesDir, recompressBackupDirName)
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - backupRetentionMs
        dir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() } }
    }

    /** filesDir의 재압축 임시 파일(표식 포함)을 모두 삭제. */
    private fun sweepRecompressTempFiles() {
        val filesDir = getApplication<Application>().filesDir
        filesDir.listFiles { f ->
            f.isFile && f.name.contains(ImageImportHelper.RECOMPRESS_TEMP_MARKER)
        }?.forEach { runCatching { it.delete() } }
    }

    /** 파일명 접두(char/novel/universe)를 추출 — 재압축 산출물 명명 규약 유지. 그 외는 img. */
    private fun prefixOf(path: String): String {
        val base = File(path).name.substringBefore('_', "")
        return when (base) {
            "char", "novel", "universe" -> base
            else -> "img"
        }
    }

    // ===== 정리 폴더 왕복 (받아오기) =====

    /** 정리 폴더 나열·계획 결과. [accessible]=false면 권한 소실(폴더 삭제·권한 회수). */
    data class FolderScanOutcome(
        val bundle: com.novelcharacter.app.util.OrganizeFolderService.PlanBundle?,
        val accessible: Boolean
    )

    suspend fun getOrganizeFolderUri(): String? =
        ImageSettingsStore(getApplication()).getOrganizeFolderUri()

    fun setOrganizeFolderUri(uri: String?, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            ImageSettingsStore(getApplication()).setOrganizeFolderUri(uri)
            // 폴더가 바뀌면 지문·진입 감지 캐시는 의미를 잃는다(별칭은 폴더와 무관해 남긴다).
            withContext(Dispatchers.IO) {
                com.novelcharacter.app.util.FolderRoundtripPrefs.clearFolderScopedState(getApplication())
            }
            onDone()
        }
    }

    /**
     * 정리 폴더를 나열해 계획까지 세운다. 실행은 [applyOrganizePlan]이 하며, 그 사이에
     * 반드시 사용자 확인을 거친다(조용한 대량 반영 금지).
     */
    fun scanOrganizeFolder(onDone: (FolderScanOutcome) -> Unit) {
        _loading.value = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val uriString = ImageSettingsStore(getApplication()).getOrganizeFolderUri()
                    ?: return@withContext FolderScanOutcome(null, accessible = false)
                val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull()
                    ?: return@withContext FolderScanOutcome(null, accessible = false)
                val service = com.novelcharacter.app.util.OrganizeFolderService
                val scan = service.scan(getApplication(), uri)
                    ?: return@withContext FolderScanOutcome(null, accessible = false)
                FolderScanOutcome(service.buildPlan(getApplication(), db, scan), accessible = true)
            }
            _loading.value = false
            onDone(outcome)
        }
    }

    /**
     * 동명 폴더의 선택을 반영해 **같은 스캔으로 계획만 다시 세운다**.
     *
     * 다시 스캔하지 않는 것이 중요하다 — 사용자가 고르는 동안 폴더가 바뀌었다면 그 답은
     * 다른 계획에 붙게 되고, 사용자는 자기가 본 적 없는 배치를 승인한 셈이 된다.
     */
    fun replanOrganizeFolder(
        scan: com.novelcharacter.app.util.OrganizeFolderService.ScanResult,
        choices: Map<String, Long>,
        onDone: (com.novelcharacter.app.util.OrganizeFolderService.PlanBundle) -> Unit
    ) {
        _loading.value = true
        viewModelScope.launch {
            val bundle = withContext(Dispatchers.IO) {
                com.novelcharacter.app.util.OrganizeFolderService
                    .buildPlan(getApplication(), db, scan, choices)
            }
            _loading.value = false
            onDone(bundle)
        }
    }

    /**
     * 계획을 반영한다. 진행도는 [onProgress](메인 스레드)로 올라오고, [isCancelled]가 true가
     * 되면 그 항목까지 반영하고 멈춘다.
     */
    fun applyOrganizePlan(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.PlanBundle,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
        onDone: (com.novelcharacter.app.util.OrganizeFolderService.ApplyResult) -> Unit
    ) {
        viewModelScope.launch {
            val uriString = ImageSettingsStore(getApplication()).getOrganizeFolderUri()
            val uri = uriString?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            if (uri == null) {
                onDone(com.novelcharacter.app.util.OrganizeFolderService.ApplyResult())
                return@launch
            }
            val result = runCatching {
                com.novelcharacter.app.util.OrganizeFolderService.applyPlan(
                    getApplication(), db, uri, bundle,
                    onProgress = { done, total -> withContext(Dispatchers.Main) { onProgress(done, total) } },
                    isCancelled = isCancelled
                )
            }.getOrElse {
                // 어떤 예외에도 크래시 대신 '반영 없음'으로 통보한다(변수 제어).
                com.novelcharacter.app.util.OrganizeFolderService.ApplyResult()
            }
            load()
            onDone(result)
        }
    }

    /** AI 태그 제안 결과 + 폴더별 적용 대상 경로(D-4: 이번에 그 폴더에서 온 이미지만). */
    data class TagSuggestOutcome(
        val result: com.novelcharacter.app.ai.ImageFolderTagSuggester.Result,
        val pathsByFolder: Map<String, List<String>>
    )

    val folderTagRunning = MutableLiveData(false)

    /**
     * 폴더 태그 제안의 결과 — **이쪽도 유료 응답이라 회전을 넘긴다** (B-136).
     *
     * 이미지판([aiTagResult])과 같은 결함이 이 선행 기능에도 그대로 있었다. 실행이 뷰 스코프에
     * 있어 회전이 요청을 끊었고, 결과는 컨트롤러의 지역 변수에만 살아 되살아난 시트가 빈
     * 껍데기였다. **한 시트만의 이탈이 아니라 검토 시트 부류가 통째로 빠져 있던 것**이라
     * 둘을 같은 자리에서 세운다.
     */
    val folderTagResult = MutableLiveData<TagSuggestOutcome?>()

    fun clearFolderTagResult() { folderTagResult.value = null }

    /**
     * 폴더 태그 제안 실행 — [viewModelScope]에서 돌아 **회전을 넘긴다**.
     *
     * @return 이미 실행 중이면 false(무통보 무시 금지 — 호출측이 알린다).
     */
    fun runFolderTagSuggest(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.PlanBundle,
        applied: com.novelcharacter.app.util.OrganizeFolderService.ApplyResult,
        folders: List<String>
    ): Boolean {
        if (folderTagRunning.value == true) return false
        folderTagRunning.value = true
        viewModelScope.launch {
            try {
                folderTagResult.value = suggestFolderTags(bundle, applied, folders)
            } finally {
                folderTagRunning.value = false
            }
        }
        return true
    }

    /**
     * 폴더 이름으로 태그를 제안받는다(설계 image_folder_tag_ai 3장).
     *
     * 어휘는 **기존 이미지 태그 전량 + '어휘에 포함'이 켜진 필드의 값**이다. 후자를 켠 필드가
     * 하나도 없어도 동작한다 — 그때는 기존 태그만으로 표기를 맞춘다.
     *
     * **바깥에 열지 않는다** — 입구는 [runFolderTagSuggest] 하나다(B-136과 같은 이유).
     */
    private suspend fun suggestFolderTags(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.PlanBundle,
        applied: com.novelcharacter.app.util.OrganizeFolderService.ApplyResult,
        folders: List<String>
    ): TagSuggestOutcome = withContext(Dispatchers.IO) {
        val plan = bundle.plan
        // 신규는 편입 뒤 경로로, 토큰 파일은 이미 아는 경로로 — 둘 다 "이번에 그 폴더에서 온" 것.
        val pathsByFolder = folders.associateWith { folder ->
            val fresh = plan.aiTagFolders[folder].orEmpty().mapNotNull { applied.importedPathById[it] }
            (fresh + plan.aiTagExistingPaths[folder].orEmpty()).distinct()
        }.filterValues { it.isNotEmpty() }
        if (pathsByFolder.isEmpty()) {
            return@withContext TagSuggestOutcome(
                com.novelcharacter.app.ai.ImageFolderTagSuggester.Result(), emptyMap()
            )
        }

        val vocab = loadTagVocabulary()
        val policy = com.novelcharacter.app.ai.AiPromptSettings(getApplication()).imageTagPolicy
        val suggester = com.novelcharacter.app.ai.ImageFolderTagSuggester(
            com.novelcharacter.app.ai.AiService(getApplication())
        )
        val result = suggester.suggest(
            pathsByFolder.keys.toList(),
            pathsByFolder.mapValues { it.value.size },
            vocab,
            policy,
            // 사용자가 고친 메시지 양식 (2026.08.20). 손댄 적이 없으면 기본 양식이다.
            com.novelcharacter.app.ai.AiPromptSettings(getApplication()).asTemplateSource()
        )
        TagSuggestOutcome(result, pathsByFolder)
    }

    /**
     * 어휘를 조립한다 — 두 AI 태그 기능의 **공용 재료** (설계 feature_roadmap 2-3).
     *
     * 조립 규칙 자체는 [com.novelcharacter.app.ai.ImageTagVocabulary]가 들고, 여기서는 DB를 읽어
     * 그 함수에 넘기는 일만 한다. 실패해도 빈 어휘로 진행한다 — 어휘는 표기 일관성을 위한
     * 참고이지 요청의 전제가 아니다(못 읽었다고 기능 전체를 막으면 되레 손해다).
     */
    private suspend fun loadTagVocabulary(): com.novelcharacter.app.ai.ImageTagVocabulary.Vocabulary {
        val tagCounts = runCatching {
            db.imageTagDao().getAllList().groupingBy { it.tag }.eachCount()
        }.getOrDefault(emptyMap())
        val vocabFields = runCatching {
            db.fieldDefinitionDao().getAllFieldsAllTypes()
                .filter { com.novelcharacter.app.data.model.FieldAiPolicy.isImageTagVocabEnabled(it.config) }
                .map { db.fieldValueEntryDao().getByField(it.id) }
        }.getOrDefault(emptyList())
        return com.novelcharacter.app.ai.ImageTagVocabulary.build(tagCounts, vocabFields)
    }

    /**
     * 결정형 진행도(R-26)의 재료 — **회전 뒤 다시 띄우는 다이얼로그가 이것으로 눈금을 되찾는다.**
     * 실행이 ViewModel로 올라갔으니 진행도도 함께 올라와야 한다(뷰에 두면 회전이 0으로 되돌린다).
     */
    data class AiTagProgress(
        val doneRequests: Int,
        val totalRequests: Int,
        val doneImages: Int,
        val totalImages: Int
    )

    val aiTagRunning = MutableLiveData(false)
    val aiTagProgress = MutableLiveData<AiTagProgress?>()

    /**
     * 일괄 AI 태깅의 결과 — **유료 응답을 들고 회전을 넘긴다** (B-136).
     *
     * 종전에는 이 결과가 Fragment의 지역 변수에만 살아서, 검토 중 회전하면 **이미 결제한
     * 응답이 통째로 사라지고** 빈 껍데기 시트만 남았다. 저장소가 같은 부류에 이미 세워 둔
     * 관행(`CharacterViewModel.aiSuggestResult` — *유료 응답은 ViewModel이 들고 회전을
     * 넘긴다*)을 이 경로에도 그대로 세운다.
     *
     * **결과 하나로 충분하다** — 고지도 되받기 목록도 전부 이것에서 다시 뽑히므로 회전 뒤
     * 화면이 이 값 하나로 재구성된다. 판을 돌린 장수를 곁들여 들지 않는 이유가 그것이다:
     * 되받아 볼 만한지는 [com.novelcharacter.app.ai.ImageBatchTagSuggester.retryablePaths]가
     * **그 배치에 실렸던 장수로** 판정하는 편이 정확하다(판 단위 설정값은 합친 결과에서
     * 이미 낡는다 — 되받기는 1장씩 돌지만 남은 접힌 배치는 5장짜리일 수 있다).
     *
     * 소비(clear)는 **검토 시트의 액션 시점**이다 — 그래야 검토 중 회전에도 결과가 산다.
     */
    val aiTagResult = MutableLiveData<com.novelcharacter.app.ai.ImageBatchTagSuggester.Result?>()

    /**
     * 검토 시트가 행마다 "묶음 N장에 함께 붙음"을 말할 재료 — 제안 경로 → 묶음 식구 수(2 이상만).
     *
     * **살아 있는 명단으로 센다** — 적용도 같은 명단으로 펴므로([applyTagWork]) 고지와 실제가
     * 같은 것을 본다. 실행 시점 표(aiTagGroupExpand)를 걷어낸 뒤로 되받기(1장씩)·전원 전송
     * 실행의 행에도 장수가 선다 — 그 행의 태그도 이제 실제로 식구에게 붙기 때문이다.
     */
    fun aiTagGroupSizes(): Map<String, Int> {
        val paths = aiTagResult.value?.suggestions?.map { it.path } ?: return emptyMap()
        return liveTagRoster(paths).mapValues { it.value.size }
    }

    /**
     * 요약 고지용 (묶음 수, 전체 장수) — **행 수가 아니라 묶음 수를 센다.**
     *
     * 제안은 경로마다 한 행이라, 한 묶음의 두 장이 각각 제안을 받으면 같은 묶음이 두 행으로
     * 산다. 행 수로 고지하면 묶음 하나가 둘로, 3장이 6장으로 불어 **사용자가 배로 부풀린
     * 수에 동의하게 된다**(R-14가 세운 것은 개수로 알린다이지 많아 보이게 한다가 아니다).
     * 식구 목록의 내용 동등성으로 접는다 — 서로 다른 묶음의 식구 목록은 겹칠 수 없다.
     */
    fun aiTagGroupNoticeStats(): Pair<Int, Int> {
        val paths = aiTagResult.value?.suggestions?.map { it.path } ?: return 0 to 0
        val distinctGroups = liveTagRoster(paths).values.distinct()
        return distinctGroups.size to distinctGroups.sumOf { it.size }
    }

    /** 경로 → 링크 그룹 토큰. AI 묶음 단위 표본([com.novelcharacter.app.util.LinkGroupFold])이 쓴다. */
    fun linkGroupIds(paths: Collection<String>): Map<String, String?> {
        val byPath = currentItemsByPath()
        return paths.associateWith { byPath[it]?.meta?.linkGroupId }
    }

    fun clearAiTagResult() {
        aiTagResult.value = null
    }

    /**
     * 취소 요청 — 실행이 뷰 밖으로 나갔으므로 **취소 깃발도 뷰 밖에 있어야 한다.**
     * 종전에는 진행 다이얼로그의 손잡이가 이 깃발을 들고 있어, 회전으로 그 창이 사라지면
     * 실행이 취소를 영영 못 본다. 취소는 즉시 중단이 아니라 **더 시작하지 않음**이다.
     */
    @Volatile
    private var aiTagCancelled = false

    fun cancelAiTagRun() { aiTagCancelled = true }

    /**
     * 일괄 AI 태깅 실행 — [viewModelScope]에서 돌아 **회전을 넘긴다**.
     *
     * @param carryOver 되받기('1장씩 다시 보내기')일 때 **앞 실행의 결과**. 새 결과를 그 위에
     *   얹어 이미 결제한 제안을 살린다(B-140 — 합치는 규칙은
     *   [com.novelcharacter.app.ai.ImageBatchTagSuggester.mergeRetry]가 든다).
     *   전개 표는 받지 않는다 — 묶음 전개는 적용 시점에 살아 있는 명단으로 한다
     *   ([applyTagWork]의 공유 불변식 초크포인트).
     * @return 이미 실행 중이면 false — 호출측이 반드시 사용자에게 알릴 것(무통보 무시 금지).
     */
    fun runImageTagSuggest(
        paths: List<String>,
        perRequest: Int,
        carryOver: com.novelcharacter.app.ai.ImageBatchTagSuggester.Result? = null
    ): Boolean {
        if (aiTagRunning.value == true) return false
        // **부를 것이 없으면 true다** — 여기서 false를 내면 호출측이 *"이미 실행 중입니다"*라는
        // **틀린 사유**를 말한다(B-121 ④가 잘림과 번호 사고를 가른 것과 같은 부류 — 고지가
        // 틀린 곳을 고치라고 시키면 없느니만 못하다). 호출측 가드가 이미 막는 자리라
        // 실제로 뜨지는 않지만, 사유를 하나로 뭉뚱그려 두면 다음 사람이 그 위에 쌓는다.
        if (paths.isEmpty()) return true
        aiTagCancelled = false
        // **총량을 먼저 세운다** — 진행 창은 `aiTagRunning`을 보고 서면서 이 값으로 총량을
        // 정하므로, 순서가 뒤집히면 첫 순간에 총량 0(불확정 막대)으로 떴다가 곧바로 바뀐다.
        aiTagProgress.value = AiTagProgress(
            0,
            com.novelcharacter.app.ai.AiPromptPolicy.imageTagBatchRequestCount(paths.size, perRequest),
            0,
            paths.size
        )
        aiTagRunning.value = true
        viewModelScope.launch {
            try {
                val fresh = suggestImageTags(
                    paths = paths,
                    perRequest = perRequest,
                    onProgress = { done, total, doneImages, totalImages ->
                        aiTagProgress.value = AiTagProgress(done, total, doneImages, totalImages)
                    },
                    isCancelled = { aiTagCancelled }
                )
                aiTagResult.value = carryOver?.let {
                    com.novelcharacter.app.ai.ImageBatchTagSuggester.mergeRetry(it, fresh)
                } ?: fresh
            } finally {
                aiTagRunning.value = false
                aiTagProgress.value = null
            }
        }
        return true
    }

    /**
     * 고른 이미지들을 **내용까지 보내** 태그를 제안받는다 (B-121, 설계 2-3).
     *
     * 폴더 이름을 읽는 [suggestFolderTags]와 어휘는 같지만 성질이 다르다 — 그림이 기기 밖으로
     * 나가고 비용이 장수에 붙는다. 그래서 호출측이 **실행 전에 요청 수·장수를 고지**하고
     * 이 함수는 결정형 진행도(R-26)의 재료를 흘려보낸다.
     *
     * **바깥에 열지 않는다** — 유료 응답이 뷰의 지역 변수로 흘러 회전에 사라지던 것이 B-136이라,
     * 입구는 [runImageTagSuggest] 하나다(그쪽이 결과를 [aiTagResult]에 보관한다).
     *
     * @param perRequest 한 요청에 실을 장수(사용자 설정). 범위는 `AiPromptPolicy`가 든다.
     */
    private suspend fun suggestImageTags(
        paths: List<String>,
        perRequest: Int,
        onProgress: suspend (done: Int, total: Int, doneImages: Int, totalImages: Int) -> Unit,
        isCancelled: () -> Boolean
    ): com.novelcharacter.app.ai.ImageBatchTagSuggester.Result = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext com.novelcharacter.app.ai.ImageBatchTagSuggester.Result()
        val vocab = loadTagVocabulary()
        val policy = com.novelcharacter.app.ai.AiPromptSettings(getApplication()).imageTagPolicy
        val suggester = com.novelcharacter.app.ai.ImageBatchTagSuggester(
            com.novelcharacter.app.ai.AiService(getApplication())
        )
        suggester.suggest(
            paths = paths,
            perRequest = perRequest,
            vocab = vocab,
            policyRaw = policy,
            // 경로와 짝지어 넘긴다 — 못 읽은 장이 있어도 응답의 번호가 실린 목록을 그대로 가리킨다.
            // 뺀 사유(못 읽음 / 앱 저장소 밖)는 준비기가 세어 그대로 얹는다 (B-141).
            loader = { batch ->
                val prepared = com.novelcharacter.app.util.AiImagePreparer
                    .prepareEach(batch, getApplication<Application>().filesDir)
                com.novelcharacter.app.ai.ImageBatchTagSuggester.LoadResult(
                    loaded = prepared.loaded.map { (path, image) ->
                        com.novelcharacter.app.ai.ImageBatchTagSuggester.Loaded(path, image)
                    },
                    unreadable = prepared.skipped,
                    blocked = prepared.blocked
                )
            },
            onProgress = { done, total, doneImages, totalImages ->
                withContext(Dispatchers.Main) { onProgress(done, total, doneImages, totalImages) }
            },
            isCancelled = isCancelled,
            // 사용자가 고친 메시지 양식 (2026.08.20). 손댄 적이 없으면 기본 양식이다.
            templates = com.novelcharacter.app.ai.AiPromptSettings(getApplication()).asTemplateSource()
        )
    }

    /**
     * 태그 적용의 결과 — **실패를 0과 가른다** (R-17 · B-143).
     *
     * 한 숫자로 합칠 수 없는 이유는 **0이 이 경로의 정상 결과이기 때문**이다: 고른 태그가
     * 이미 전부 붙어 있으면 [Done]`(0, 0)`이 나온다. 그러므로 형제 쓰기들처럼 *실패 시 0을
     * 말하는* 관행([addTagsToImages] 등)을 그대로 가져오면 **"이미 다 있었다"와 "하나도
     * 저장하지 못했다"가 화면에서 같은 문장이 된다.** 갈래를 타입으로 두면 새 호출자도
     * 실패를 다루지 않고는 지날 수 없다(`when`이 exhaustive라 컴파일이 막는다).
     */
    sealed class TagApplyOutcome {
        data class Done(val tags: Int, val images: Int) : TagApplyOutcome()
        object Failed : TagApplyOutcome()
    }

    /**
     * 태그 적용의 **끝맺음** — 문장을 만들고, 이력을 남기고, 화면에 넘긴다 (B-164).
     *
     * ## 왜 뷰가 아니라 여기인가
     *
     * 종전에는 문장 조립과 이력 기록을 **호출측 콜백이 함께** 했다
     * (`if (!isAdded) return` → `reportAndNotify`). 화면이 붙어 있는 정상 경로에서는 옳지만,
     * 사용자가 적용 직후 다른 화면으로 넘어가면 **그 return이 기록까지 함께 막았다** —
     * 하필 **유료 응답이 걸린 실패**가 어디에도 남지 않아 나중에 되짚을 수 없었다.
     *
     * 그래서 **뷰가 이력의 문지기가 되지 못하게** 자리를 옮겼다. 이력은 이 함수가 무조건
     * 남기므로, 호출측이 무엇을 하든(혹은 아무것도 못 하든) 기록은 선다 — 검사로 지키는
     * 것이 아니라 **구조적으로 막을 자리가 없어진 것**이 요점이다.
     *
     * ## 고지도 잃지 않는다
     *
     * [onDone]은 *"화면이 알렸는가"*를 돌려준다. 못 알렸으면 **토스트로 대신 알린다**
     * ([toastResult] — 뷰 계층과 무관하게 닿는다). 화면이 있을 때 토스트로 통일하지 않는
     * 이유는 스낵바가 '상세' 액션을 달 수 있어서다.
     *
     * **이력을 [onDone]보다 *먼저* 남기는 것이 요점이다.** 순서를 뒤집으면 *"무조건 남는다"*가
     * **고지 경로가 던지지 않는 한**으로 조용히 약해진다 — 이 함수가 없애려는 것이 바로
     * *뷰의 사정이 기록을 좌우하는* 그 모양이라, 같은 모양을 한 겹 얇게 남겨 둘 이유가 없다.
     */
    private fun finishTagApply(outcome: TagApplyOutcome, onDone: (OpResult) -> Boolean) {
        val result = when (outcome) {
            is TagApplyOutcome.Done -> OpResult.success(
                OpResult.CAT_MAINTENANCE,
                app.getString(R.string.image_tag_review_applied, outcome.tags, outcome.images)
            )
            TagApplyOutcome.Failed -> OpResult.failure(
                OpResult.CAT_MAINTENANCE,
                app.getString(R.string.image_tag_review_apply_failed)
            )
        }
        logResult(result)
        if (!onDone(result)) toastResult(result)
    }

    /**
     * 태그 적용의 **공통 손** — 이미지판([applyImageTags])과 폴더판([applyFolderTags])이 이것을 쓴다.
     * 둘이 같은 규약을 지녀야 하는 이유는 아래 [applyImageTags] 주석에 있다(B-143 · B-163).
     *
     * **경로마다 묻던 `getTagsByImageList`를 일괄 조회 한 번으로 내렸다 (B-241).** 폴더판은 고른
     * 폴더 아래 **전량**을 도므로 조회 수가 이미지 수에 비례했고, `image_tags.imageId`에 인덱스가
     * 있어(모델 [com.novelcharacter.app.data.model.ImageTag]의 `Index("imageId")`) 하나하나가
     * 풀스캔은 아니었다 — 없앤 값은 **왕복 × 이미지 수**다. 쓰기도 한 번으로 접었다
     * (`insertAll`이 이미 목록을 받는다 — `@Insert`라 `IN` 절이 아니고 바인드 상한과 무관하다).
     *
     * **읽기를 앞으로 모아도 답이 같은 근거와 겹 갱신의 정본은 [ImageTagApplyPlanner]다** —
     * 같은 이미지가 두 번 돌면 앞 차례의 쓰기가 뒤 차례의 '이미 가진 태그'를 바꾸기 때문이다.
     *
     * **남아 있던 `adopt`도 걷었다 (B-244).** 삽입-또는-조회라 일괄판이 없던 자리이고, B-241이
     * *"이 함수에 남은 왕복의 대부분"*이라 적어 둔 그 자리다(경로마다 최대 셋). 통로는
     * [com.novelcharacter.app.util.ImageAdoption]이고 판정은 그 짝의 순수 계층에 있다.
     * **이제 이 함수의 왕복은 목록 길이와 무관하다.**
     */
    private suspend fun applyTagWork(work: List<Pair<String, List<String>>>): TagApplyOutcome.Done {
        // **링크 묶음 전개 — 공유 불변식의 초크포인트.** 어느 경로(이미지판·폴더판·되받기)로
        // 온 태그든 **적용 직전의 살아 있는 명단**으로 묶음 전원에 편다. 실행 시점의 전개
        // 표를 들고 다니던 종전 모양(aiTagGroupExpand)은 ⓐ 검토 중 링크를 바꾸면 낡은
        // 명단에 붙고 ⓑ 표에 없는 실행(되받기 1장씩·전원 전송)의 태그가 식구에게 못 가는
        // 두 결함을 함께 들었다. 같은 경로가 두 번 실려도 먼저 합쳐 한 번에 편다
        // (합집합·순서 규칙은 [com.novelcharacter.app.util.LinkGroupFold.expandPicked]가 정본).
        val merged = LinkedHashMap<String, MutableList<String>>()
        for ((path, tags) in work) {
            val bucket = merged.getOrPut(path) { mutableListOf() }
            for (t in tags) if (t !in bucket) bucket.add(t)
        }
        val expanded = com.novelcharacter.app.util.LinkGroupFold
            .expandPicked(merged, liveTagRoster(merged.keys))
            .map { it.key to it.value }
        val now = System.currentTimeMillis()
        // 경로마다 왕복 셋을 치르던 `adopt`를 목록 하나로 내린다 (B-244) — **이 함수에 남아
        // 있던 왕복의 대부분이 그 자리였다.** 폴더판은 고른 폴더 아래 이미지 전량을 돌아
        // `N`에 상한이 없다. 중복을 접고 순서를 보존하는 것은 [ImageAdoption]의 계약이라,
        // 종전의 `if (path !in imageIdByPath)`와 답이 글자 그대로 같다.
        val imageIdByPath = ImageAdoption.adoptAll(
            db, expanded.filter { it.second.isNotEmpty() }.map { it.first }, now
        )
        val existingByImageId: Map<Long, Set<String>> =
            SqlInChunks.flat(imageIdByPath.values.toList()) { db.imageTagDao().getTagsByImages(it) }
                .groupBy({ it.imageId }, { it.tag })
                .mapValues { (_, tags) -> tags.toSet() }

        val plan = ImageTagApplyPlanner.plan(expanded, imageIdByPath, existingByImageId)
        if (plan.inserts.isNotEmpty()) {
            db.imageTagDao().insertAll(
                plan.inserts.flatMap { ins ->
                    ins.freshTags.map {
                        com.novelcharacter.app.data.model.ImageTag(imageId = ins.imageId, tag = it)
                    }
                }
            )
        }
        return TagApplyOutcome.Done(plan.tagCount, plan.touchedPaths)
    }

    /**
     * 검토 시트에서 고른 태그를 **이미지별로** 적용한다 — 폴더판([applyFolderTags])과 같은 규칙:
     * 기존 태그와 **합친다**(덮지 않는다).
     *
     * **집계는 트랜잭션의 반환값이다.** 종전에는 누적 변수가 [withContext] 밖에 살고 증가만
     * 람다 안에서 일어나, 롤백돼도 **되돌아가지 않은 부분 누적치**가 그대로 남아 성공 고지에
     * 실렸다(B-143). 형제 쓰기들이 전부 집계를 트랜잭션 성공 이후로 확정하는 그 형태를 따른다.
     *
     * **유료 응답의 소비도 이 함수가 든다 — 성공했을 때만 버린다** (R-38 · B-163).
     * 검토 시트는 [onDone]을 기다리지 않고 곧바로 닫히므로, 호출측이 누른 시점에 비우면
     * **실패를 알았을 때 되돌아가 다시 적용할 자리가 없다**(다시 얻으려면 재결제다).
     * 그래서 누른 시점에 들어 두었다가 **실패하면 되살린다** — 되살리는 자리가 뷰가 아니라
     * 여기인 것이 요점이다: 적용 중에 회전이 나도 재생성된 화면이 그 값을 보고 시트를 다시 세운다.
     */
    fun applyImageTags(
        picked: Map<String, List<String>>,
        onDone: (OpResult) -> Boolean
    ) {
        // 누른 **그 시점**에 든다. 코루틴 안에서 읽으면 이미 시트가 닫히며 비운 뒤다.
        val pending = aiTagResult.value
        // 묶음 전개는 여기서 하지 않는다 — 적용 직전의 살아 있는 명단으로 [applyTagWork]가
        // 편다(공유 불변식의 초크포인트. 폴더판·되받기와 같은 자리를 지난다).
        val work = picked.map { it.key to it.value }
        aiTagResult.value = null
        viewModelScope.launch {
            val outcome: TagApplyOutcome = withContext(Dispatchers.IO) {
                try {
                    db.withTransaction { applyTagWork(work) }
                } catch (e: Exception) {
                    TagApplyOutcome.Failed
                }
            }
            load()
            // 되살리기는 고지보다 **먼저** 한다 — 사용자가 실패 문구를 읽는 순간 이미 검토 창이
            // 돌아와 있어야 "다시 적용해 주세요"가 가리킬 자리가 있다.
            if (outcome is TagApplyOutcome.Failed) aiTagResult.value = pending
            finishTagApply(outcome, onDone)
        }
    }

    /**
     * 검토 시트에서 고른 태그를 적용한다. 기존 태그와 **합친다**(덮지 않는다) — 사용자가 이미
     * 붙여 둔 태그를 AI 제안이 지우면 그것이 곧 무통보 유실이다.
     *
     * 집계를 트랜잭션의 반환값으로 확정하는 것도, **성공했을 때만 유료 응답을 버리는 것**도
     * 이유는 이미지판([applyImageTags])과 같다 — 두 함수가 같은 결함을 함께 들고 있었다
     * (B-143 · B-163). 한쪽만 고치면 다른 쪽이 다음 사람의 본보기가 된다.
     */
    fun applyFolderTags(
        picked: Map<String, List<String>>,
        pathsByFolder: Map<String, List<String>>,
        onDone: (OpResult) -> Boolean
    ) {
        val pending = folderTagResult.value
        folderTagResult.value = null
        viewModelScope.launch {
            val outcome: TagApplyOutcome = withContext(Dispatchers.IO) {
                try {
                    db.withTransaction {
                        // 폴더의 태그를 그 폴더의 경로마다 편다 — 종전 이중 루프와 같은 순서다.
                        applyTagWork(
                            picked.flatMap { (folder, tags) ->
                                if (tags.isEmpty()) emptyList()
                                else pathsByFolder[folder].orEmpty().map { it to tags }
                            }
                        )
                    }
                } catch (e: Exception) {
                    TagApplyOutcome.Failed
                }
            }
            load()
            if (outcome is TagApplyOutcome.Failed) folderTagResult.value = pending
            finishTagApply(outcome, onDone)
        }
    }

    /**
     * 진입 감지 — 정리 폴더의 신규 후보 개수만 센다(D5). 폴더가 없거나 접근이 안 되면 0을
     * 돌려주고 **조용히 생략한다**(수동 메뉴가 항상 있다 — 진입마다 실패를 알리면 소음이다).
     */
    suspend fun countOrganizeFolderCandidates(): Int = withContext(Dispatchers.IO) {
        val uriString = ImageSettingsStore(getApplication()).getOrganizeFolderUri() ?: return@withContext 0
        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: return@withContext 0
        val scan = com.novelcharacter.app.util.OrganizeFolderService.scan(getApplication(), uri)
            ?: return@withContext 0
        scan.files.size
    }

    // ===== 정리 폴더 왕복 (내보내기) =====

    /** 정리 폴더 내보내기 계획 결과. [accessible]=false면 권한 소실(폴더 삭제·권한 회수). */
    data class FolderExportOutcome(
        val bundle: com.novelcharacter.app.util.OrganizeFolderService.ExportBundle?,
        val accessible: Boolean
    )

    /**
     * 내보내기 계획을 세운다. 실행은 [runOrganizeExport]가 하며, 그 사이에 반드시 사용자
     * 확인을 거친다 — 용량과 **이전 사본 정리**를 미리 보여야 한다(조용한 덮어쓰기 금지).
     */
    fun planOrganizeExport(
        scope: com.novelcharacter.app.util.FolderExportPlanner.Scope,
        onDone: (FolderExportOutcome) -> Unit
    ) {
        _loading.value = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val uri = organizeFolderUri() ?: return@withContext FolderExportOutcome(null, false)
                val bundle = com.novelcharacter.app.util.OrganizeFolderService
                    .buildExportPlan(getApplication(), db, uri, scope)
                    ?: return@withContext FolderExportOutcome(null, false)
                FolderExportOutcome(bundle, accessible = true)
            }
            _loading.value = false
            onDone(outcome)
        }
    }

    /**
     * 사본을 내보낸다. 진행도는 [onProgress](메인 스레드)로 올라오고, [isCancelled]가 true가
     * 되면 그 파일까지 쓰고 멈춘다. 앱 원본은 읽기만 하므로 실패해도 데이터는 그대로다.
     */
    fun runOrganizeExport(
        bundle: com.novelcharacter.app.util.OrganizeFolderService.ExportBundle,
        onProgress: (Int, Int) -> Unit,
        isCancelled: () -> Boolean,
        onDone: (com.novelcharacter.app.util.OrganizeFolderService.ExportResult) -> Unit
    ) {
        viewModelScope.launch {
            val uri = organizeFolderUri()
            if (uri == null) {
                onDone(com.novelcharacter.app.util.OrganizeFolderService.ExportResult())
                return@launch
            }
            val result = runCatching {
                com.novelcharacter.app.util.OrganizeFolderService.runExport(
                    getApplication(), uri, bundle,
                    onProgress = { done, total -> withContext(Dispatchers.Main) { onProgress(done, total) } },
                    isCancelled = isCancelled
                )
            }.getOrElse {
                // 어떤 예외에도 크래시 대신 '내보낸 것 없음'으로 통보한다(변수 제어).
                com.novelcharacter.app.util.OrganizeFolderService.ExportResult()
            }
            onDone(result)
        }
    }

    private suspend fun organizeFolderUri(): android.net.Uri? {
        val raw = ImageSettingsStore(getApplication()).getOrganizeFolderUri() ?: return null
        return runCatching { android.net.Uri.parse(raw) }.getOrNull()
    }

    /** imagePaths JSON에서 [oldPath]/[oldCanon]에 해당하는 항목을 [newPath]로 교체해 재직렬화. */
    private fun replacePath(json: String, oldPath: String, oldCanon: String, newPath: String): String {
        val updated = parsePaths(json).map {
            val c = ImagePathMatch.canonical(it)
            if (it == oldPath || c == oldCanon) newPath else it
        }
        return gson.toJson(updated)
    }
}
