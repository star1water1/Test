package com.novelcharacter.app.ui.duel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelGradeRef
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.repository.DuelRepository
import com.novelcharacter.app.util.DuelCategoryStats
import com.novelcharacter.app.util.DuelSystemFields
import com.novelcharacter.app.util.FieldValueTokenizer
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 대결 화면 넷이 함께 쓰는 읽기·쓰기 통로 (B-104 화면 계층).
 *
 * **계산을 주 스레드에서 하지 않는다.** 점수 적합은 캐릭터 수의 제곱에 붙는 비용이고
 * 실측이 900명·18,000판에서 **적합 128ms + 짝 고르기 147ms + 상성 48ms**다
 * (`docs/duel_system_design_2026-08.md` 6장). 한 판을 누를 때마다 이만큼을 주 스레드에서
 * 돌리면 목표 규모의 위쪽에서 화면이 눈에 띄게 멈춘다 — 그래서 [stateOf]가
 * [Dispatchers.Default]로 넘긴다. Room의 중단 함수는 스스로 자기 실행기로 옮기므로
 * 이 감싸기와 겹치지 않는다.
 *
 * **참가자가 무엇인가는 축이 정하고, 그것을 아는 것은 이 계층이다**
 * ([DuelRepository.stateOf]의 계약). 캐릭터 축은 세계관의 캐릭터 코드이며,
 * **이미지 축은 아직 열지 않았다** — 폴더 왕복이 파일을 개명하면 참가자 코드(=경로)도
 * 함께 옮겨야 하는데 그 경로가 아직 없다(설계 4장 ①의 ⚠️). 열려면 그 슬라이스가 먼저다.
 */
class DuelViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val duelRepository = app.duelRepository
    private val characterRepository = app.characterRepository

    /**
     * 조작 결과 — 화면이 관측해 알리고, [reportResult]가 **작업 이력에도 함께 남긴다.**
     *
     * **한 판 한 판은 여기 오지 않는다**([OpResult.CAT_DUEL]의 설명). 대결은 단순반복이라
     * 누름마다 알리면 그 알림이 다음 짝을 가린다 — 화면이 곧바로 다음 둘을 띄우는 것 자체가
     * 이미 *"들어갔다"*는 신호다. 여기 오는 것은 **판이 무더기로 오가는 조작**뿐이다.
     */
    private val _result = MutableLiveData<OpResult?>()
    val result: androidx.lifecycle.LiveData<OpResult?> get() = _result

    fun clearResult() { _result.value = null }

    /**
     * 한 축을 화면에 올리는 데 필요한 전부.
     *
     * @property charactersByCode 참가자 코드 → 캐릭터. 순수 계층이 내놓는 것은 코드뿐이라
     *   이름·이미지는 여기서 붙인다.
     */
    data class Loaded(
        val axis: DuelAxis,
        val state: DuelRepository.AxisState,
        val charactersByCode: Map<String, Character>,
        val verdicts: List<DuelCounterVerdict>
    )

    // ──────────────────────────────────────────────────────────────────────
    // 축
    // ──────────────────────────────────────────────────────────────────────

    suspend fun axes(universeId: Long): List<DuelAxis> = duelRepository.axes(universeId)

    suspend fun axis(id: Long): DuelAxis? = duelRepository.axis(id)

    suspend fun saveAxis(axis: DuelAxis): DuelAxis = duelRepository.saveAxis(axis)

    /** 축 저장을 알리고 이력에 남긴다 — 순서 바꾸기처럼 사용자가 이미 본 조작은 부르지 않는다. */
    fun reportAxisSaved(axis: DuelAxis, isNew: Boolean) {
        reportResult(
            _result,
            OpResult.success(
                OpResult.CAT_DUEL,
                app.getString(
                    if (isNew) R.string.duel_op_axis_added else R.string.duel_op_axis_edited,
                    axis.name
                )
            )
        )
    }

    /** 같은 세계관·같은 대상에 같은 이름이 있는가 — 유니크 인덱스가 던지기 **전에** 묻는다. */
    suspend fun nameTaken(axis: DuelAxis): Boolean {
        val existing = app.database.duelAxisDao()
            .getByUniverseAndName(axis.universeId, axis.targetType, axis.name)
        return existing != null && existing.id != axis.id
    }

    /** 함께 사라질 판 수 — 삭제를 **묻기 전에** 규모를 알린다(R-4). */
    suspend fun matchCount(axisId: Long): Int = app.database.duelMatchDao().countByAxis(axisId)

    /**
     * 축을 지운다. 판·처분이 FK CASCADE로 함께 죽으므로 저장소가 **지우기 전에** 휴지통에 담는다.
     * @return 함께 들어간 판 수.
     */
    suspend fun deleteAxis(axis: DuelAxis): Int {
        val matches = duelRepository.deleteAxis(axis, app.trashRepository)
        reportResult(
            _result,
            OpResult.success(
                OpResult.CAT_DUEL,
                app.getString(R.string.duel_op_axis_deleted, axis.name, matches)
            )
        )
        return matches
    }

    // ──────────────────────────────────────────────────────────────────────
    // 필드 연결 (층 C)
    // ──────────────────────────────────────────────────────────────────────

    /** 이 세계관의 캐릭터 필드 — 축에 걸 수 있는 후보다. 차례는 필드 관리에서 정한 그대로다. */
    suspend fun characterFields(universeId: Long): List<FieldDefinition> =
        app.database.fieldDefinitionDao()
            .getFieldsByUniverseList(universeId, FieldDefinition.ENTITY_CHARACTER)

    /** 시스템 열의 사람이 읽는 이름 — 말글은 화면 계층의 몫이라 여기서 문자열로 바꾼다. */
    fun systemFieldLabel(column: DuelSystemFields.Column): String = app.getString(
        when (column) {
            DuelSystemFields.Column.NAME -> R.string.duel_system_field_name
            DuelSystemFields.Column.FIRST_NAME -> R.string.duel_system_field_first_name
            DuelSystemFields.Column.LAST_NAME -> R.string.duel_system_field_last_name
            DuelSystemFields.Column.ANOTHER_NAME -> R.string.duel_system_field_another_name
            DuelSystemFields.Column.MEMO -> R.string.duel_system_field_memo
            DuelSystemFields.Column.NOVEL -> R.string.duel_system_field_novel
            DuelSystemFields.Column.TAGS -> R.string.duel_system_field_tags
        }
    )

    /**
     * 연결에 뜨는 **모든** 키의 이름 — 커스텀 필드와 시스템 열을 함께 든다 (B-167).
     *
     * **시스템 열을 먼저 넣고 진짜 필드로 덮는다.** 그 차례가 곧
     * [DuelSystemFields.shadowed]의 규칙이다 — 사용자가 `sys:memo`라는 키의 필드를 만들었으면
     * 그 이름이 남아야 하고, 화면 셋(카드·기록·순위표)이 각자 정하면 같은 키가 화면마다 다른
     * 이름으로 뜬다.
     */
    suspend fun linkLabels(universeId: Long): Map<String, String> =
        linkLabels(characterFields(universeId))

    /**
     * 필드를 **이미 읽어 둔** 쪽을 위한 갈래 — 같은 세계관을 두 번 훑지 않게 한다.
     *
     * 축 편집 창과 대결 화면이 역할 필드(성별·나이)를 집으려고 이미 목록을 들고 있다.
     */
    fun linkLabels(fields: List<FieldDefinition>): Map<String, String> {
        val labels = LinkedHashMap<String, String>()
        DuelSystemFields.Column.entries.forEach { labels[it.key] = systemFieldLabel(it) }
        fields.forEach { labels[it.key] = it.name }
        return labels
    }

    /**
     * 참가자들의 **연결 필드 값** — `참가자 코드 → (필드 키 → 값)`.
     *
     * @param keys 볼 필드의 키. 비어 있으면 아무것도 읽지 않는다 — 연결이 없는 축에서
     *   캐릭터 전원의 필드값을 훑으면 그 비용이 순전히 낭비다(목표 규모는 캐릭터 수백 명이다).
     *   **시스템 열 키(`sys:`)가 섞여 있어도 된다**(B-167) — 그쪽은 캐릭터 행에서 온다.
     *
     * **한 번에 읽는 것이 요점이다.** 짝이 뜰 때마다 둘씩 읽으면 수백 판을 누르는 동안 질의가
     * 그만큼 늘어난다 — 대결은 *단순반복*이 성질인 기능이라 판당 비용이 그대로 누적된다.
     *
     * **시스템 열은 질의를 늘리지 않는다** — 이름·이명·메모는 이미 받아 둔 [characters] 행에
     * 들어 있다. 작품 제목과 태그만 다른 표에 살고, 그 둘은 **실제로 걸렸을 때만** 읽는다
     * (위의 *"연결이 없으면 읽지 않는다"*와 같은 규약).
     */
    suspend fun fieldValuesOf(
        universeId: Long,
        characters: List<Character>,
        keys: Collection<String>
    ): Map<String, Map<String, String>> {
        if (keys.isEmpty() || characters.isEmpty()) return emptyMap()
        return withContext(Dispatchers.Default) {
            val wanted = keys.toSet()
            val fields = app.database.fieldDefinitionDao()
                .getFieldsByUniverseList(universeId, FieldDefinition.ENTITY_CHARACTER)
            val result = HashMap<String, MutableMap<String, String>>()

            // ① 커스텀 필드. **가려진 시스템 키도 여기서 답해진다** — 사용자가 `sys:memo`라는
            // 키의 필드를 만들었으면 그 필드가 이 걸러내기에 걸리고, 아래 ②가 그것을 비껴간다.
            val fieldsById = fields.filter { it.key in wanted }.associateBy({ it.id }, { it.key })
            if (fieldsById.isNotEmpty()) {
                val codeById = characters.associate { it.id to it.code }
                // **`IN`을 청크로 나눈다** — SQLite의 변수 상한은 999이고 목표 규모는 캐릭터 수백
                // 명이라 한 번에 넣으면 언젠가 넘는다. 넘으면 예외가 나고, 그것을 삼키면 값이
                // 조용히 사라진다(이 저장소가 이미 한 번 겪은 부류 — `removeTagsFromImages`).
                val values = characters.map { it.id }
                    .chunked(900)
                    .flatMap { app.database.characterFieldValueDao().getValuesForCharacters(it) }
                for (row in values) {
                    val key = fieldsById[row.fieldDefinitionId] ?: continue
                    val code = codeById[row.characterId] ?: continue
                    if (row.value.isBlank()) continue
                    result.getOrPut(code) { HashMap() }[key] = row.value
                }
            }

            // ② 시스템 열 (B-167) — 진짜 필드가 같은 키를 들었으면 ①이 이미 답했으므로 뺀다.
            val takenKeys = fields.map { it.key }.toSet()
            val systemKeys = wanted.filter { key ->
                DuelSystemFields.columnOf(key)?.let { it.key !in takenKeys } == true
            }
            if (systemKeys.isNotEmpty()) {
                val extrasById = systemExtrasOf(characters, systemKeys)
                for (character in characters) {
                    val values = DuelSystemFields.valuesOf(
                        character, systemKeys, extrasById[character.id] ?: DuelSystemFields.Extras()
                    )
                    if (values.isNotEmpty()) result.getOrPut(character.code) { HashMap() } += values
                }
            }
            result
        }
    }

    /**
     * 캐릭터 행에 **없는** 시스템 열 재료 — 작품 제목과 태그.
     *
     * **걸린 열만 읽는다.** `sys:tags`가 없는 축에서 태그 표를 훑는 것은 순전히 낭비이고,
     * 그것이 이 함수가 [DuelSystemFields.valuesOf] 안이 아니라 밖에 있는 이유다
     * (순수 계층은 질의를 몰라야 시험이 실제로 돈다).
     */
    private suspend fun systemExtrasOf(
        characters: List<Character>,
        systemKeys: Collection<String>
    ): Map<Long, DuelSystemFields.Extras> {
        val columns = systemKeys.mapNotNull { DuelSystemFields.columnOf(it) }.toSet()
        val wantsNovel = DuelSystemFields.Column.NOVEL in columns
        val wantsTags = DuelSystemFields.Column.TAGS in columns
        if (!wantsNovel && !wantsTags) return emptyMap()

        val titleByNovelId: Map<Long, String> = if (!wantsNovel) {
            emptyMap()
        } else {
            // 중복을 걷어내므로 작품 수는 캐릭터 수보다 훨씬 적지만 **청크는 그대로 나눈다** —
            // 상한은 *실제로 넘는가*가 아니라 *넘을 수 있는가*로 봐야 하고(캐릭터마다 다른 작품이면
            // 둘이 같은 수다), 넘으면 예외가 나서 값이 통째로 사라진다.
            characters.mapNotNull { it.novelId }
                .distinct()
                .chunked(900)
                .flatMap { app.database.novelDao().getNovelsByIds(it) }
                .associate { it.id to it.title }
        }
        val tagsById: Map<Long, List<String>> = if (!wantsTags) {
            emptyMap()
        } else {
            // 위 ①과 같은 청크 규약 — 이쪽도 캐릭터 수만큼의 `IN` 인자가 든다.
            characters.map { it.id }
                .chunked(900)
                .flatMap { app.database.characterTagDao().getTagsForCharacters(it) }
                .groupBy({ it.characterId }, { it.tag })
        }

        return characters.associate { character ->
            character.id to DuelSystemFields.Extras(
                novelTitle = character.novelId?.let { titleByNovelId[it] }.orEmpty(),
                tags = tagsById[character.id].orEmpty()
            )
        }
    }

    /**
     * **범주형 영향 필드의 집계** (B-112) — 상성 상세가 *"왜 그런가"*를 대는 재료.
     *
     * 차례가 없는 값(속성·소속)은 [DuelFieldLinks.predict]에서 넘어가므로 종전에는 **걸어도
     * 화면 표시가 전부**였다. 여기서 그 값들이 실제로 일을 한다.
     *
     * **토큰 나누기는 [FieldValueTokenizer]가 한다** — 통계가 쓰는 것과 같은 함수라야
     * *"태그 셋 단 캐릭터"*를 두 화면이 같은 수로 센다(각자 콤마를 나누면 반드시 갈린다).
     *
     * @param state 이미 읽어 둔 축 상태. 점수 적합([DuelRepository.AxisState.fit])이
     *   **예상 승수의 출처**라 여기서 다시 적합하지 않는다 — 다시 돌리면 순위표와 이 표가
     *   서로 다른 적합에서 나온다(파생값을 저장하지 않는 이유와 같은 병이다).
     * @return 필드 이름과 그 집계. 범주로 읽히지 않는 필드는 빠진다.
     */
    suspend fun categoryReports(
        axis: DuelAxis,
        characters: List<Character>,
        state: DuelRepository.AxisState
    ): List<Pair<String, DuelCategoryStats.Report>> {
        val influences = axis.fieldLinks.influences
        if (influences.isEmpty()) return emptyList()
        val defs = characterFields(axis.universeId).associateBy { it.key }
        val values = fieldValuesOf(axis.universeId, characters, influences.map { it.key })
        if (values.isEmpty()) return emptyList()

        return withContext(Dispatchers.Default) {
            influences.mapNotNull { link ->
                val def = defs[link.key]
                // 진짜 필드가 이긴다 — 같은 키의 필드가 있으면 시스템 열로 읽지 않는다(B-167).
                val column = if (def != null) null else DuelSystemFields.columnOf(link.key)
                // 지워진 필드는 이름도 타입도 알 수 없다 — 토큰을 어떻게 나눌지 모르므로 뺀다.
                // 시스템 열은 그 규칙을 [DuelSystemFields.Column.multiToken]이 들고 있어 남는다.
                if (def == null && column == null) return@mapNotNull null
                val tokensById = HashMap<Long, List<String>>()
                for ((code, byKey) in values) {
                    val raw = byKey[link.key] ?: continue
                    val id = state.records.idOf(code) ?: continue
                    val tokens = if (def != null) {
                        FieldValueTokenizer.splitForStats(def, raw)
                    } else {
                        DuelSystemFields.tokensOf(column!!, raw)
                    }
                    if (tokens.isNotEmpty()) tokensById[id] = tokens
                }
                if (!DuelCategoryStats.isCategorical(tokensById)) return@mapNotNull null
                val report = DuelCategoryStats.report(
                    key = link.key,
                    matches = state.records.matches,
                    fit = state.fit,
                    tokensById = tokensById
                )
                if (report.any) (def?.name ?: systemFieldLabel(column!!)) to report else null
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 상태
    // ──────────────────────────────────────────────────────────────────────

    /**
     * **이 축을 가리키는 대결 등급 산정 필드들** (B-113) — 순위표의 [등급 반영] 진입이 쓴다.
     *
     * 소속 검사가 여기서도 선다: 축 code는 전역 유니크라 config가 남의 세계관 축을 정확히
     * 가리킬 수 있으므로, **이 축의 세계관에 있는 필드만** 본다(설계 4-2 ⓑ의 이중 방어).
     * 이미지 축은 참가자가 캐릭터가 아니라 애초에 대상이 아니다.
     */
    suspend fun gradeApplyFieldsFor(axis: DuelAxis): List<FieldDefinition> {
        if (axis.isImageAxis) return emptyList()
        return app.database.fieldDefinitionDao()
            // 종류를 **명시한다** — DAO 기본값이 캐릭터라 맞는 결과가 나오지만, 기본값에
            // 기대는 것이 R-29가 지목한 실패 형태다(잊으면 오류가 아니라 잘못된 정답).
            .getFieldsByUniverseList(axis.universeId, FieldDefinition.ENTITY_CHARACTER)
            .filter { DuelGradeRef.axisCodeFromConfig(it.config) == axis.code }
    }

    /** 이 축의 참가자 — 캐릭터 축은 세계관의 캐릭터가 그대로 참가자다. */
    suspend fun participants(axis: DuelAxis): List<Character> = participantsOf(axis.universeId)

    /** 세계관의 참가자. 축 목록처럼 **여러 축이 같은 참가자를 보는 자리**가 이것을 직접 부른다. */
    suspend fun participantsOf(universeId: Long): List<Character> =
        characterRepository.getCharactersByUniverseList(universeId)
            // 순서가 순수 계층의 id 배정을 정한다 — 안정된 순서로 넘겨야 결과도 안정된다.
            .sortedBy { it.id }

    /**
     * 축의 현재 상태. 무거운 계산이라 [Dispatchers.Default]에서 돈다.
     *
     * @param characters [participants]가 낸 목록. 화면이 이미 들고 있으면 다시 읽지 않는다 —
     *   매 판 뒤 다시 계산하는 경로라 캐릭터 표를 그때마다 훑으면 그것이 더 비싸진다.
     */
    suspend fun load(axis: DuelAxis, characters: List<Character>): Loaded =
        withContext(Dispatchers.Default) {
            val byCode = characters.associateBy { it.code }
            val state = duelRepository.stateOf(axis, characters.map { it.code })
            Loaded(axis, state, byCode, duelRepository.verdicts(axis.id))
        }

    // ──────────────────────────────────────────────────────────────────────
    // 기록 · 처분
    // ──────────────────────────────────────────────────────────────────────

    /** 한 판. 승자가 두 참가자 중 어느 쪽도 아니면 저장소가 거절하고 null을 낸다. */
    suspend fun record(axisId: Long, aCode: String, bCode: String, winnerCode: String?): DuelMatch? =
        duelRepository.record(axisId, aCode, bCode, winnerCode)

    /** 층 B ① — 그 판을 지운다. 점수는 다시 적합하면 그것이 정확한 답이다. */
    suspend fun undo(match: DuelMatch) = duelRepository.undo(match)

    /**
     * 층 B ②·③ — 같은 관계에 다시 판정하면 덮어쓴다.
     *
     * ③은 **그 짝을 점수 적합에서 뺀다.** 즉 판정 하나가 순위표의 다른 줄까지 움직이므로
     * (둘과 무관한 제3자의 순위가 달라진다 — 설계 3장) 조용히 지나가서는 안 된다.
     */
    suspend fun recordVerdict(
        axisId: Long,
        members: List<String>,
        kind: String,
        relation: String
    ): DuelCounterVerdict? {
        val saved = duelRepository.recordVerdict(axisId, members, kind) ?: return null
        reportResult(
            _result,
            OpResult.success(
                OpResult.CAT_DUEL,
                app.getString(
                    if (kind == DuelCounterVerdict.KIND_COUNTER) {
                        R.string.duel_op_verdict_counter
                    } else {
                        R.string.duel_op_verdict_undecided
                    },
                    relation
                )
            )
        )
        return saved
    }

    /** 처분을 물린다 — 되돌리면 그 짝이 점수 적합으로 돌아온다(여기도 순위가 움직인다). */
    suspend fun clearVerdict(verdict: DuelCounterVerdict, relation: String) {
        duelRepository.clearVerdict(verdict)
        reportResult(
            _result,
            OpResult.success(OpResult.CAT_DUEL, app.getString(R.string.duel_op_verdict_cleared, relation))
        )
    }

    suspend fun verdictById(axisId: Long, id: Long): DuelCounterVerdict? =
        duelRepository.verdicts(axisId).firstOrNull { it.id == id }

    /**
     * 이 짝의 판들 — 층 B ①(*"잘못 눌렀다"*)이 상성 상세에서 지울 대상.
     *
     * 상세 화면에서 되돌리는 것은 *"방금 그거"*가 아니라 **그 관계를 만든 판들**이라,
     * 대결 화면의 되돌리기와 집는 대상이 다르다.
     */
    suspend fun matchesBetween(axisId: Long, codes: List<String>): List<DuelMatch> {
        val members = codes.toSet()
        return app.database.duelMatchDao().getByAxis(axisId)
            .filter { it.aCode in members && it.bCode in members }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 기록 화면 — 보기와 손편집
    // ──────────────────────────────────────────────────────────────────────

    suspend fun recentMatches(axisId: Long, limit: Int): List<DuelMatch> =
        duelRepository.recentMatches(axisId, limit)

    /**
     * 판 하나의 승자를 고친다.
     *
     * **이력에 남긴다.** 대결의 누름 하나하나는 이력에 오지 않지만([OpResult.CAT_DUEL]),
     * **손으로 고친 것은 다르다** — 반복 조작이 아니라 판정을 뒤집는 일이고, 그 판이 걸린
     * 짝의 순위가 함께 움직인다. 무더기로 오가는 것만 남긴다는 규칙의 뜻이 *"사용자가 하나를
     * 콕 집어 바꾼 것은 남기지 않는다"*는 아니다.
     */
    suspend fun editWinner(
        matchCode: String,
        winnerCode: String?,
        relation: String,
        winnerLabel: String
    ): DuelMatch? {
        val match = app.database.duelMatchDao().getByCode(matchCode) ?: return null
        val updated = duelRepository.updateWinner(match, winnerCode) ?: return null
        reportResult(
            _result,
            OpResult.success(
                OpResult.CAT_DUEL,
                app.getString(R.string.duel_op_match_edited, relation, winnerLabel)
            )
        )
        return updated
    }

    /** 판 하나를 지운다 — **되돌릴 수 없다**(휴지통을 거치지 않는다). 이력이 유일한 자취다. */
    suspend fun deleteMatch(matchCode: String, relation: String) {
        val match = app.database.duelMatchDao().getByCode(matchCode) ?: return
        duelRepository.undo(match)
        reportResult(
            _result,
            OpResult.success(OpResult.CAT_DUEL, app.getString(R.string.duel_op_match_deleted, relation))
        )
    }

    /**
     * 층 B ①의 실행 — **되돌릴 수 없다.** 축 삭제와 달리 휴지통을 거치지 않으므로(판 하나하나가
     * 스냅샷을 갖지 않는다) **이력에 남기는 것이 유일한 자취**다.
     */
    suspend fun deleteMatches(matches: List<DuelMatch>, relation: String) {
        matches.forEach { duelRepository.undo(it) }
        reportResult(
            _result,
            OpResult.success(
                OpResult.CAT_DUEL,
                app.getString(R.string.duel_op_matches_deleted, relation, matches.size)
            )
        )
    }
}
