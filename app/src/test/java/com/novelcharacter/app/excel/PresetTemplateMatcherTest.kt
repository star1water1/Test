package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 필드 템플릿 매칭 — 동명 템플릿이 이름 맵으로 접혀 사용자의 편집이 유실되던 결함의 회귀 가드.
 * DB는 동명 템플릿을 허용하므로(name 유니크 인덱스 없음) 정체성은 '생성일'이다.
 */
class PresetTemplateMatcherTest {

    private fun matcher(vararg c: Triple<Long, String, Long>) =
        PresetTemplateMatcher(c.map { PresetTemplateMatcher.Candidate(it.first, it.second, it.third) })

    @Test
    fun sameName_differentCreatedAt_bothMatchedDistinctly() {
        // 핵심 회귀: '기본 템플릿'이 2개면 두 행이 각각 다른 항목을 갱신해야 한다.
        // 이름 맵이었을 땐 둘 다 같은 항목을 덮어써 한쪽 편집이 사라졌다.
        val m = matcher(Triple(1L, "기본 템플릿", 100L), Triple(2L, "기본 템플릿", 200L))
        val a = m.claim("기본 템플릿", 100L, 1)
        val b = m.claim("기본 템플릿", 200L, 2)
        assertTrue(a is PresetTemplateMatcher.Match.Matched && a.id == 1L)
        assertTrue(b is PresetTemplateMatcher.Match.Matched && b.id == 2L)
    }

    @Test
    fun sameName_noCreatedAtColumn_pairedInSheetOrderWithWarning() {
        // 생성일 열을 지운 파일: 이름만으로는 확정할 수 없으므로 순서대로 짝짓되 반드시 고지한다
        val m = matcher(Triple(1L, "기본", 100L), Triple(2L, "기본", 200L))
        val a = m.claim("기본", null, 1) as PresetTemplateMatcher.Match.Matched
        val b = m.claim("기본", null, 2) as PresetTemplateMatcher.Match.Matched
        assertEquals(1L, a.id)
        assertEquals(2L, b.id)          // claim되어 뒤 행이 앞 항목을 다시 가져가지 못한다
        assertTrue(a.nameBased && b.nameBased)
        assertTrue(a.warnings.any { it.contains("생성일") })
    }

    @Test
    fun renameByCreatedAt_keepsIdentity() {
        // 엑셀에서 이름만 바꾼 경우 — 새 항목을 만들지 않고 같은 템플릿을 rename한다
        val m = matcher(Triple(7L, "옛 이름", 100L))
        val r = m.claim("새 이름", 100L, 1) as PresetTemplateMatcher.Match.Matched
        assertEquals(7L, r.id)
        assertTrue(!r.nameBased)
        assertTrue(r.warnings.any { it.contains("이름을 '새 이름'") })
    }

    @Test
    fun duplicateRowInFile_lastWins_noDuplicateInsert() {
        // 같은 행이 파일에 두 번 있어도 새 항목을 만들지 않는다(왕복 멱등성)
        val m = matcher(Triple(1L, "A", 100L))
        m.claim("A", 100L, 1)
        val second = m.claim("A", 100L, 2) as PresetTemplateMatcher.Match.Matched
        assertEquals(1L, second.id)
        assertTrue(second.warnings.any { it.contains("마지막 행 우선") })
    }

    @Test
    fun unknownTemplate_isNewWithWarning() {
        val m = matcher(Triple(1L, "A", 100L))
        val r = m.claim("B", 999L, 1) as PresetTemplateMatcher.Match.New
        assertTrue(r.warnings.any { it.contains("새로 만듭니다") })
    }

    @Test
    fun newRow_withoutCreatedAt_isNewWithoutNoise() {
        // 손으로 추가한 행(생성일 빈칸)은 정상 신규 — 경고를 내지 않는다(거짓 경고 금지)
        val m = matcher(Triple(1L, "A", 100L))
        val r = m.claim("B", null, 1) as PresetTemplateMatcher.Match.New
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun registeredInsert_isNotDuplicatedByLaterIdenticalRow() {
        val m = matcher()
        assertTrue(m.claim("새것", 500L, 1) is PresetTemplateMatcher.Match.New)
        m.register(id = 9L, name = "새것", createdAt = 500L, rowIndex = 1)
        val again = m.claim("새것", 500L, 2) as PresetTemplateMatcher.Match.Matched
        assertEquals(9L, again.id)
    }

    @Test
    fun createdAtMismatch_fallsBackToNameWithWarning() {
        // 생성일을 고친 파일: 조용히 새 항목을 만들지 않고 이름으로 이어 붙이되 고지한다
        val m = matcher(Triple(3L, "A", 100L))
        val r = m.claim("A", 777L, 1) as PresetTemplateMatcher.Match.Matched
        assertEquals(3L, r.id)
        assertTrue(r.nameBased)
        assertTrue(r.warnings.any { it.contains("'생성일' 열은 수정하지 마세요") })
    }
}
