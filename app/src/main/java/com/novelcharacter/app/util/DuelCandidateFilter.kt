package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldFilter

/**
 * **대결 후보 필터** (B-168) — *"이 대결에선 여성끼리만"* 을 축에 거는 것.
 *
 * 사용자 요청 원문(2026.08.08): *"이 대결에선 여성끼리만, 이 대결에서는 특정 속성 값이
 * 무엇인 캐릭터끼리만, 이런식으로 편하게 필터로 지정"*.
 *
 * ## 필터 언어를 새로 만들지 않았다 (원칙 05)
 * 조건은 [FieldFilter] 그대로다 — 값 간 OR · 필터 간 AND · `exact`/`contains`. 커스텀 필드의
 * 실제 대조도 `FieldFilterHelper`가 그대로 한다(값 라이브러리 별칭·토큰 규칙 포함). 여기가
 * 더하는 것은 둘뿐이다: **필드를 id가 아니라 키로 가리키는 해석**(축은 복원·엑셀을 타므로
 * id가 못 미덥다 — `PortableFieldFilters`가 겪은 그 부류)과 **시스템 열의 대조**(태그·작품·
 * 이명은 `FieldDefinition`이 아니라 표의 열이라 id가 아예 없다 — B-167이 연 `sys:` 어휘를
 * 그대로 쓴다. *"태그가 Y인 캐릭터끼리"* 가 역할 표식 없이 성립하는 이유다).
 *
 * ## 필터는 **새로 붙일 짝**만 좁힌다 — 쌓인 판은 건드리지 않는다 (설계 물음 ⓓ)
 * 점수 적합은 축 전체의 판으로 돈다(설계 9-3 — *"필터가 점수를 바꾸지 않는다"*). 필터를
 * 나중에 좁혀도 이미 겨룬 참가자의 전적은 순위표에 **표식과 함께** 남는다([participants]) —
 * 지우면 남의 점수까지 조용히 움직이고, 되돌릴 수도 없다(개발 의도 2번). 그래서 이 기능에는
 * 되돌리기 어려운 축이 없다: 필터는 언제 바꿔도 데이터를 잃지 않는다.
 *
 * ## 해석 실패는 조용히 넓히지 않는다
 * 지워진 필드·모르는 `sys:` 키를 든 필터는 **후보를 0으로 만든다**([Resolution.unresolved]).
 * 건너뛰면 필터가 소리 없이 넓어져 *사용자가 좁혀 둔 범위 밖의 짝*이 만들어진다 — 목록
 * 정렬이 기본으로 되돌아가는 것과 달리 이쪽은 **데이터가 생기는 자리**라 실패는 닫히는
 * 쪽이어야 한다. 화면이 그 사실과 필드 이름을 말한다(검증 → 알림 → 바로잡을 경로).
 */
object DuelCandidateFilter {

    /**
     * 저장 형식은 `FieldFilterHelper`의 JSON 규약 그대로다 — 비어 있으면 `"{}"`.
     *
     * **정화를 여기서 한다.** 엑셀 칸은 사람이 적는 자리라 값 목록이 빠지거나 null이 섞인
     * JSON이 올 수 있는데, Gson은 Kotlin 기본값을 실행하지 않아 **non-null 선언에도 null을
     * 주입한다**(R-2가 경계하는 그 지뢰). 여기서 걸러야 대조 경로 어디에서도 터지지 않는다.
     */
    fun parse(json: String?): List<FieldFilter> =
        if (json.isNullOrBlank()) emptyList() else sanitize(FieldFilterHelper.filtersFromJson(json))

    fun encode(filters: List<FieldFilter>): String = FieldFilterHelper.filtersToJson(filters)

    /** Gson이 주입했을 수 있는 null을 걷어낸다 — 값이 하나도 없는 조건은 뜻이 없어 버린다. */
    private fun sanitize(filters: List<FieldFilter?>?): List<FieldFilter> =
        filters.orEmpty().mapNotNull { filter ->
            if (filter == null) return@mapNotNull null
            @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL")
            val values = (filter.values ?: emptyList())
                .mapNotNull { value -> value?.trim() }
                .filter { it.isNotEmpty() }
            if (values.isEmpty()) return@mapNotNull null
            @Suppress("USELESS_ELVIS")
            FieldFilter(
                fieldId = filter.fieldId,
                fieldName = filter.fieldName ?: "",
                values = values,
                matchMode = (filter.matchMode ?: "exact").ifBlank { "exact" },
                fieldKey = filter.fieldKey?.takeIf { it.isNotBlank() }
            )
        }

    /**
     * 엑셀 `후보필터(JSON)` 칸의 해석 (설계 물음 — 왕복).
     *
     * | 칸 | 뜻 | 결과 |
     * |---|---|---|
     * | 빈 칸 · `{}` · `[]` | 필터를 지워라 | [SheetCell.Value]\(null\) |
     * | 조건이 읽히는 JSON | 이 필터로 바꿔라 | [SheetCell.Value]\(정화해 다시 담은 JSON\) |
     * | 그 밖 | **읽을 수 없다** | [SheetCell.Malformed] — 기존 값을 지키고 경고한다 |
     *
     * 읽을 수 없는 칸을 *지워라*로 접지 않는 것이 요점이다 — 괄호 하나 틀린 손편집이
     * 멀쩡한 필터를 조용히 지우면 안 된다(개발 의도 4번 — 거부가 아니라 유연한 수용·교정,
     * 단 무엇을 못 받았는지는 말한다).
     */
    sealed interface SheetCell {
        data class Value(val json: String?) : SheetCell
        object Malformed : SheetCell
    }

    fun fromSheetCell(text: String): SheetCell {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "{}" || trimmed == "[]") return SheetCell.Value(null)
        val parsed = parse(trimmed)
        return if (parsed.isEmpty()) SheetCell.Malformed else SheetCell.Value(encode(parsed))
    }

    /**
     * 필터 하나가 무엇을 가리키는가.
     *
     * @property field 커스텀 필드로 해석됐으면 그 정의. **진짜 필드가 이긴다**(B-167의 규칙
     *   그대로) — 사용자가 `sys:tags`라는 키의 필드를 만들었으면 그 필드가 답한다.
     * @property column 시스템 열로 해석됐으면 그 열.
     * @property unresolved 어느 쪽으로도 해석되지 않았다 — 지워진 필드이거나 모르는 `sys:` 키다.
     */
    data class Resolved(
        val filter: FieldFilter,
        val field: FieldDefinition? = null,
        val column: DuelSystemFields.Column? = null
    ) {
        // 게터로 두면 안 된다 — 게터 본문에서 `field`는 프로퍼티가 아니라 backing field
        // 소프트 키워드로 읽혀 "초기화되지 않은 프로퍼티"가 된다(실제로 겪었다).
        val unresolved: Boolean = field == null && column == null
    }

    /**
     * 필터들을 이 세계관의 필드 목록으로 해석한다.
     *
     * 사다리는 키 → 시스템 열 → (구식) id 순이다. 키가 정본인 것은 클래스 KDoc의 이유이고,
     * id 폴백을 남긴 것은 **손으로 만든 엑셀 파일**이 키 없이 id만 적을 수 있어서다 —
     * 그때도 id가 실재하는 필드를 가리키면 살린다(이름이 아니라 id 존재로만 판정하는 것은
     * 이 필터가 세계관 안에서만 도는 덕이다 — `PortableFieldFilters`가 경계한 남의 세계관
     * 오배정이 여기서는 성립하지 않는다).
     */
    fun resolve(filters: List<FieldFilter>, fields: List<FieldDefinition>): List<Resolved> {
        if (filters.isEmpty()) return emptyList()
        val byKey = fields.associateBy { it.key }
        val byId = fields.associateBy { it.id }
        return filters.map { filter ->
            val key = filter.fieldKey
            when {
                key != null && byKey.containsKey(key) -> Resolved(filter, field = byKey[key])
                key != null && DuelSystemFields.isSystemKey(key) ->
                    Resolved(filter, column = DuelSystemFields.columnOf(key))
                key == null && byId.containsKey(filter.fieldId) ->
                    Resolved(filter, field = byId[filter.fieldId])
                else -> Resolved(filter)
            }
        }
    }

    /**
     * 시스템 열 필터 하나의 대조.
     *
     * `exact`는 **토큰 단위**다 — 이명·태그는 쉼표로 나뉘고([DuelSystemFields.tokensOf])
     * 메모는 통째로 한 토큰이다(나누면 문장 하나가 값 여럿이 된다 — B-167 ⓓ의 규칙 그대로).
     * `contains`는 원문 부분 일치다(`FieldFilterHelper`의 LIKE와 같은 뜻 — 대소문자 무시).
     */
    fun matchesSystem(
        column: DuelSystemFields.Column,
        filter: FieldFilter,
        character: Character,
        extras: DuelSystemFields.Extras
    ): Boolean {
        val raw = DuelSystemFields.valueOf(column, character, extras)
        val targets = filter.values.map { it.trim() }.filter { it.isNotEmpty() }
        if (targets.isEmpty()) return true
        return if (filter.matchMode == "contains") {
            targets.any { raw.contains(it, ignoreCase = true) }
        } else {
            val tokens = DuelSystemFields.tokensOf(column, raw)
            targets.any { it in tokens }
        }
    }

    /**
     * 후보 집합 — 필터 간 AND의 마지막 조립.
     *
     * @param customMatchedIds 커스텀 필드 필터들이 낸 캐릭터 id 교집합
     *   (`FieldFilterHelper.applyFieldFilters` — 그쪽이 이미 AND로 묶는다). **null이면 커스텀
     *   필터가 없는 것**이고, 빈 집합이면 *아무도 통과하지 못한 것*이다 — 둘을 같게 다루면
     *   커스텀 필터가 전원을 걸러 냈을 때 조용히 전원 통과로 뒤집힌다.
     * @param extrasById 시스템 열 재료(작품 제목·태그). 걸린 열이 없으면 비워 넘긴다.
     * @return 후보의 코드 집합. **해석 실패 필터가 하나라도 있으면 비어 있다**(클래스 KDoc).
     */
    fun candidateCodes(
        characters: List<Character>,
        resolved: List<Resolved>,
        customMatchedIds: Set<Long>?,
        extrasById: Map<Long, DuelSystemFields.Extras>
    ): Set<String> {
        if (resolved.any { it.unresolved }) return emptySet()
        val systemFilters = resolved.filter { it.column != null }
        return characters.asSequence()
            .filter { customMatchedIds == null || it.id in customMatchedIds }
            .filter { character ->
                systemFilters.all {
                    matchesSystem(
                        it.column!!, it.filter, character,
                        extrasById[character.id] ?: DuelSystemFields.Extras()
                    )
                }
            }
            .map { it.code }
            .toSet()
    }

    /**
     * 적합·순위표에 넘길 참가자 — **후보 ∪ 판이 있는 참가자** (설계 물음 ⓓ의 처분).
     *
     * 판이 있는 비후보를 빼면 그 판이 고아가 되어 적합에서 빠지고, **남의 점수까지 움직인다**
     * (BT는 결과 집합의 함수다). 넣되 후보가 아니라는 것은 화면이 표식으로 말한다.
     */
    fun participants(
        all: List<Character>,
        candidateCodes: Set<String>?,
        codesWithMatches: Set<String>
    ): List<Character> =
        if (candidateCodes == null) all
        else all.filter { it.code in candidateCodes || it.code in codesWithMatches }
}
