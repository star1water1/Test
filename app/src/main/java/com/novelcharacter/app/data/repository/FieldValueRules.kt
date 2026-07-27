package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.util.FieldValueResolver
import com.novelcharacter.app.util.FieldValueTokenizer

/**
 * 값 라이브러리의 순수 판정 규칙.
 *
 * [FieldValueLibraryRepository]의 companion에서 분리했다 — DB 의존이 없는 판정 로직은
 * 순수 JVM 하네스(tools/run_jvm_tests.sh)가 실제로 실행 검증할 수 있어야 한다
 * (RestoreModes·RestoreTally와 같은 취지). 저장소 companion은 이 객체로 위임하므로
 * 기존 호출부는 그대로 동작한다.
 */
object FieldValueRules {

    /** RESTRICTED 모드 위반 토큰 — 허용값(값+별칭) 밖의 토큰을 돌려준다. */
    fun validateRestricted(
        fd: FieldDefinition,
        raw: String,
        entries: List<FieldValueEntry>
    ): List<String> {
        if (raw.isBlank()) return emptyList()
        val allowed = HashSet<String>()
        for (e in entries) {
            allowed.add(e.value)
            allowed.addAll(e.aliases())
        }
        return FieldValueTokenizer.tokenize(fd, raw).filter { it !in allowed }
    }

    /** 값/별칭 충돌 검사 — (충돌 엔트리, 별칭과의 충돌 여부) */
    fun conflictOf(
        entries: List<FieldValueEntry>,
        token: String,
        excludeId: Long?
    ): Pair<FieldValueEntry, Boolean>? {
        for (e in entries) {
            if (excludeId != null && e.id == excludeId) continue
            if (e.value == token) return e to false
            if (token in e.aliases()) return e to true
        }
        return null
    }

    // ===== usageCount 재집계 (U-0) =====

    /**
     * 집계 산수의 **단일 소스** — 엔트리와 원시 값 목록에서 `usageCount`가 달라진 엔트리만 낸다.
     *
     * 토큰화·별칭 정규화·diff까지 여기서 끝난다. DB에 닿지 않으므로
     * "수확 뒤 집계가 맞는가"를 합성 데이터로 실행 검증할 수 있다.
     *
     * @return usageCount가 바뀐 엔트리의 **갱신본**. 무변화 엔트리는 담지 않는다(0건 쓰기 계약).
     */
    fun recountedEntries(
        fd: FieldDefinition,
        entries: List<FieldValueEntry>,
        rawValues: List<String>
    ): List<FieldValueEntry> {
        if (entries.isEmpty()) return emptyList()
        val resolver = FieldValueResolver(entries)
        val counts = HashMap<String, Int>()
        for (raw in rawValues) {
            for (token in FieldValueTokenizer.tokenize(fd, raw)) {
                val canonical = resolver.canonical(token)
                counts[canonical] = (counts[canonical] ?: 0) + 1
            }
        }
        return entries.mapNotNull { e ->
            val actual = counts[e.value] ?: 0
            if (actual != e.usageCount) e.copy(usageCount = actual) else null
        }
    }

    /**
     * 캐릭터 저장 뒤 재집계할 필드 — **그 캐릭터가 건드릴 수 있는 필드 전체**다.
     *
     * '새 토큰이 생긴 필드'로 좁히면 안 된다: 이미 라이브러리에 있는 값으로 바꾸거나 값을
     * 지우면 새 토큰이 하나도 생기지 않지만 집계는 달라진다('남'→'여'는 양쪽 수가 모두
     * 틀어진다). 좁히는 순간 그 편집이 조용히 반영되지 않아 **U-0과 같은 실패 형태**가 남는다.
     *
     * 세계관을 모르는 캐릭터(작품 미배정)라도 건드린 필드는 대상에 남긴다 — 세계관을 못 찾았다고
     * 통째로 건너뛰면 그 값이 영원히 0으로 남는다.
     */
    fun recountTargetsForCharacter(
        universeId: Long?,
        supportedDefs: Collection<FieldDefinition>,
        touchedFieldIds: Set<Long>
    ): Set<Long> {
        val targets = LinkedHashSet(touchedFieldIds)
        if (universeId != null) {
            supportedDefs
                .filter { it.universeId == universeId && it.entityType == FieldDefinition.ENTITY_CHARACTER }
                .forEach { targets.add(it.id) }
        }
        return targets
    }
}
