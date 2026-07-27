package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
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
}
