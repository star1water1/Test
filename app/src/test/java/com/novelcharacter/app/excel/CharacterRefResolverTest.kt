package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.Character
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐릭터 참조 해석 사다리 — 미리보기(`analyze*`)와 가져오기(`import*`)가 **같은 판정**을
 * 쓰게 한 뒤(B-232 · 규약 R-33), 그 판정 자체가 맞는지를 잠근다.
 *
 * 지키는 것: 동명이인에서 **아무나 고르지 않는다.** 종전 미리보기의 first-match는
 * 동명이인 행을 '실행된다'고 예고했고 가져오기는 그 행을 거부했다 — 거짓 예고다.
 */
class CharacterRefResolverTest {

    private fun ch(id: Long, name: String, novelId: Long?) =
        Character(id = id, name = name, novelId = novelId, code = "c$id")

    private val hanA = ch(1, "한서린", 10)
    private val hanB = ch(2, "한서린", 20)
    private val solo = ch(3, "유일한", 10)

    // ── codeFirst — 캐릭터코드 열이 있는 시트(관계 · 관계 변화) ──────────────────

    @Test
    fun codeFirst_codeWins_nameIsNotConsulted() {
        // 코드가 권위다 — 동명이인이 몇이든 코드가 답했으면 모호가 아니다
        val r = CharacterRefLadder.codeFirst(hanB, "한서린", listOf(hanA, hanB))
        assertEquals(hanB, (r as CharLookupResult.Found).character)
    }

    @Test
    fun codeFirst_unresolvedCode_fallsBackToUniqueName() {
        // 두 기기를 병합하는 파일은 코드가 전부 상대 기기 것이다 — 그때 거부하면
        // 해소 가능한 행이 통째로 밀린다(F1-C와 같은 처분)
        val r = CharacterRefLadder.codeFirst(null, "유일한", listOf(solo))
        assertEquals(solo, (r as CharLookupResult.Found).character)
    }

    @Test
    fun codeFirst_duplicateName_isAmbiguousNotFirstMatch() {
        // 핵심 회귀(B-232): 종전 미리보기는 여기서 hanA를 골라 '갱신 1건'이라 예고했고
        // 가져오기는 같은 행을 거부했다
        val r = CharacterRefLadder.codeFirst(null, "한서린", listOf(hanA, hanB))
        assertTrue(r is CharLookupResult.Ambiguous && r.count == 2)
    }

    @Test
    fun codeFirst_blankName_isNotFound() {
        assertEquals(CharLookupResult.NotFound, CharacterRefLadder.codeFirst(null, "", listOf(hanA)))
    }

    @Test
    fun codeFirst_noMatch_isNotFound() {
        // '없다'와 '모호하다'는 다른 사실이다 — 없는 것은 캐릭터 시트가 함께 오면 생긴다(B-102 ⓑ)
        assertEquals(CharLookupResult.NotFound, CharacterRefLadder.codeFirst(null, "없는이름", emptyList()))
    }

    // ── nameWithNovelHint — '작품' 열로 좁히는 시트(상태 변화 · 연표 참가자) ──────

    @Test
    fun nameHint_uniqueName_resolvesWithoutHint() {
        // 후보가 하나면 힌트 없이도 확정 — '작품' 열이 없는 구버전 파일이 무조건 실패하면 안 된다
        val r = CharacterRefLadder.nameWithNovelHint("유일한", listOf(solo), preferredNovelId = null)
        assertEquals(solo, (r as CharLookupResult.Found).character)
    }

    @Test
    fun nameHint_duplicateName_narrowedByNovel() {
        val r = CharacterRefLadder.nameWithNovelHint("한서린", listOf(hanA, hanB), preferredNovelId = 20L)
        assertEquals(hanB, (r as CharLookupResult.Found).character)
    }

    @Test
    fun nameHint_duplicateName_noHint_isAmbiguous() {
        val r = CharacterRefLadder.nameWithNovelHint("한서린", listOf(hanA, hanB), preferredNovelId = null)
        assertTrue(r is CharLookupResult.Ambiguous && r.count == 2)
    }

    @Test
    fun nameHint_hintMatchesNeither_isAmbiguous() {
        // 힌트 작품에 후보가 없으면 추측하지 않는다 — 오배정보다 모호 선언 후 안내가 낫다(4-3)
        val r = CharacterRefLadder.nameWithNovelHint("한서린", listOf(hanA, hanB), preferredNovelId = 99L)
        assertTrue(r is CharLookupResult.Ambiguous && r.count == 2)
    }

    @Test
    fun nameHint_stillDuplicateInsideNovel_isAmbiguous() {
        // 같은 작품 안 동명 둘은 작품 힌트로도 갈리지 않는다 — 캐릭터코드만이 답이다
        val twins = listOf(ch(4, "쌍둥이", 10), ch(5, "쌍둥이", 10))
        val r = CharacterRefLadder.nameWithNovelHint("쌍둥이", twins, preferredNovelId = 10L)
        assertTrue(r is CharLookupResult.Ambiguous && r.count == 2)
    }

    @Test
    fun nameHint_unassignedCharacterIsNotPickedByNovelHint() {
        // 미분류(작품 없음) 후보는 작품 힌트로 좁혀지지 않는다 — 좁힘의 재료가 없다
        val unassigned = ch(6, "한서린", null)
        val r = CharacterRefLadder.nameWithNovelHint("한서린", listOf(unassigned, hanA), preferredNovelId = 99L)
        assertTrue(r is CharLookupResult.Ambiguous && r.count == 2)
    }

    @Test
    fun nameHint_blankName_isNotFound() {
        assertEquals(
            CharLookupResult.NotFound,
            CharacterRefLadder.nameWithNovelHint("", listOf(hanA), preferredNovelId = 10L)
        )
    }

    // ── 두 사다리가 같은 사실에서 갈리지 않는가 ────────────────────────────────

    @Test
    fun bothLadders_agreeOnDuplicateNameWithoutHint() {
        // 시트마다 해소 힌트가 달라 사다리는 둘이지만, **힌트가 없을 때의 답은 같아야 한다** —
        // 갈리면 같은 파일의 두 시트가 같은 이름을 다르게 처분한다
        val byCode = CharacterRefLadder.codeFirst(null, "한서린", listOf(hanA, hanB))
        val byName = CharacterRefLadder.nameWithNovelHint("한서린", listOf(hanA, hanB), null)
        assertEquals(byCode, byName)
    }
}
