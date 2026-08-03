package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대표 이미지 판정 계약(B-103 결정 D2·D3·D5).
 *
 * 지키는 것 넷: **사다리 순서**(지정이 랜덤을 이긴다) · **`pinnedMissing`은 조용히 넘어가지
 * 않는다** · **같은 시드 안에서는 결과가 고정**(스크롤 재바인드에 그림이 튀지 않는 조건) ·
 * **쓰기가 지나가도 포인터가 어긋나지 않는다**(D5).
 */
class CharacterRepresentativeImageTest {

    private val dir = "/data/user/0/com.novelcharacter.app/files"
    private val a = "$dir/char_a.jpg"
    private val b = "$dir/char_b.jpg"
    private val c = "$dir/char_c.jpg"

    private fun json(vararg paths: String) =
        paths.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    // ── 사다리 네 갈래 ──

    @Test fun pinned_winsOverRandom() {
        // 어떤 시드로 뽑아도 지정한 장이 나와야 한다 — 이것이 사용자 요청 ②의 본체다.
        for (seed in 1L..200L) {
            val pick = CharacterRepresentativeImage.pick(json(a, b, c), b, seed, characterId = 7L)
            assertEquals(b, pick.path)
            assertEquals(CharacterRepresentativeImage.Source.PINNED, pick.source)
            assertEquals(1, pick.index)
            assertFalse(pick.pinnedMissing)
        }
    }

    @Test fun noPin_fallsBackToRandom() {
        val pick = CharacterRepresentativeImage.pick(json(a, b, c), null, seed = 42L, characterId = 7L)
        assertEquals(CharacterRepresentativeImage.Source.RANDOM, pick.source)
        assertTrue(pick.path in listOf(a, b, c))
        assertFalse(pick.pinnedMissing)
        // 빈 문자열도 "지정 없음"이다(칸의 기본값).
        assertEquals(pick, CharacterRepresentativeImage.pick(json(a, b, c), "", 42L, 7L))
    }

    @Test fun emptyList_isNone() {
        val pick = CharacterRepresentativeImage.pick("[]", null, seed = 42L, characterId = 7L)
        assertNull(pick.path)
        assertEquals(CharacterRepresentativeImage.Source.NONE, pick.source)
        assertEquals(-1, pick.index)
        assertFalse(pick.pinnedMissing)
    }

    @Test fun brokenJson_isNoneNotCrash() {
        // 외부에서 편집된 값이 들어올 수 있다 — 거부가 아니라 수용(개발 의도 4번).
        for (bad in listOf(null, "", "   ", "not json", "{\"a\":1}", "[]", "[null]", "[\"\"]")) {
            val pick = CharacterRepresentativeImage.pick(bad, null, 1L, 1L)
            assertEquals("입력=$bad", CharacterRepresentativeImage.Source.NONE, pick.source)
        }
    }

    @Test fun nonStringEntriesCoerceLikeTheRestOfTheApp() {
        // Gson은 `[1,2,3]`을 문자열 "1","2","3"으로 강제한다. **이것은 이 파일이 새로 만든
        // 규칙이 아니라 앱이 줄곧 해 온 파싱이다**(종전 `CharacterImageLoader.firstImagePath`도
        // 같은 `GsonTypes.STRING_LIST`를 쓴다). 여기서 별도 필터를 세우면 "무엇이 경로꼴인가"를
        // 이 파일이 혼자 정하게 되고, 다른 파싱 자리와 규칙이 갈린다.
        // 실제 피해는 없다 — 로더가 filesDir 하위인지 검사해 걸러내고 placeholder를 보여 준다.
        assertEquals(listOf("1", "2", "3"), CharacterRepresentativeImage.paths("[1,2,3]"))
    }

    @Test fun blankEntriesAreDropped() {
        assertEquals(listOf(a, b), CharacterRepresentativeImage.paths(json(a, "", "   ", b)))
    }

    // ── pinnedMissing — ㄷ1의 사후 고지 신호 ──

    @Test fun pinnedNotInList_reportsMissingAndFallsBackToRandom() {
        val pick = CharacterRepresentativeImage.pick(json(a, b), c, seed = 42L, characterId = 7L)
        assertTrue(pick.pinnedMissing)
        assertEquals(CharacterRepresentativeImage.Source.RANDOM, pick.source)
        assertTrue(pick.path in listOf(a, b))
    }

    @Test fun pinnedButListEmpty_reportsMissing() {
        // 목록이 비었어도 "사용자가 정한 것이 안 보인다"는 사실은 그대로 낸다 —
        // 조용히 삼키면 D6ⓑ의 고지가 이 상태에서만 빠진다.
        val pick = CharacterRepresentativeImage.pick("[]", c, seed = 42L, characterId = 7L)
        assertTrue(pick.pinnedMissing)
        assertEquals(CharacterRepresentativeImage.Source.NONE, pick.source)
        assertNull(pick.path)
    }

    @Test fun pinnedMatchesThroughPathNormalization() {
        // 폴더 왕복·재매핑이 같은 파일을 다른 표기로 넘겨도 대표는 살아 있어야 한다.
        val pick = CharacterRepresentativeImage.pick(json(a, b), "$dir/./char_b.jpg", 1L, 1L)
        assertEquals(CharacterRepresentativeImage.Source.PINNED, pick.source)
        assertEquals(b, pick.path)
        // 저장된 표기가 아니라 **목록에 실린 표기**를 돌려준다(로더가 그 문자열로 파일을 연다).
        assertEquals(b, pick.path)
    }

    // ── 시드 (D3) ──

    @Test fun sameSeed_isStable() {
        // 같은 화면 안에서 스크롤·검색·정렬·저장이 그림을 바꾸지 않는 조건.
        val first = CharacterRepresentativeImage.pick(json(a, b, c), null, seed = 99L, characterId = 5L)
        repeat(50) {
            assertEquals(first, CharacterRepresentativeImage.pick(json(a, b, c), null, 99L, 5L))
        }
    }

    @Test fun seedChange_movesTheChoice() {
        // 진입마다 다른 그림이 나와야 한다(ㄴ3). 시드 100개 중 최소 두 결과가 갈리면 충분하다.
        val seen = (1L..100L)
            .map { CharacterRepresentativeImage.randomIndex(it, characterId = 5L, size = 3) }
            .toSet()
        assertEquals(setOf(0, 1, 2), seen)
    }

    @Test fun charactersAreIndependentWithinOneSeed() {
        // 한 시드 안에서 캐릭터들이 나란히 같은 방향으로 움직이면(=id를 그냥 더하면)
        // 목록 전체가 한 칸씩 밀리는 꼴이 된다. 이웃한 id가 흩어지는지 본다.
        val seed = 12345L
        val counts = IntArray(4)
        for (id in 1L..400L) counts[CharacterRepresentativeImage.randomIndex(seed, id, 4)]++
        // 균등하지 않아도 되지만, 한 칸으로 쏠리거나 빈 칸이 있으면 혼합이 죽은 것이다.
        counts.forEachIndexed { i, n ->
            assertTrue("칸 $i 이 ${n}건 — 분포가 죽었다(${counts.toList()})", n in 60..160)
        }
    }

    @Test fun indexIsAlwaysInRange() {
        for (size in 1..17) {
            for (seed in -50L..50L) {
                val idx = CharacterRepresentativeImage.randomIndex(seed, characterId = -3L, size = size)
                assertTrue("size=$size seed=$seed idx=$idx", idx in 0 until size)
            }
        }
    }

    @Test fun singleImage_isAlwaysThatOne() {
        val pick = CharacterRepresentativeImage.pick(json(a), null, seed = 7L, characterId = 7L)
        assertEquals(a, pick.path)
        assertEquals(0, pick.index)
    }

    // ── 쓰기 정합 (D5) ──

    @Test fun retain_keepsPointerWhenStillPresent() {
        assertEquals(b, CharacterRepresentativeImage.retain(b, listOf(a, b, c)))
    }

    @Test fun retain_clearsPointerWhenGone() {
        // 삭제·정리·폴더 왕복 제외 — 경로가 사라지는 갈래.
        assertEquals("", CharacterRepresentativeImage.retain(b, listOf(a, c)))
        assertEquals("", CharacterRepresentativeImage.retain(b, emptyList()))
    }

    @Test fun retain_returnsTheFormStoredInTheList() {
        // 목록에 실린 표기를 돌려준다 — 저장 문자열과 목록 문자열이 어긋나면
        // 다음 대조가 정규화에 기대게 되고, 정규화가 실패하는 날 포인터가 죽는다.
        assertEquals(b, CharacterRepresentativeImage.retain("$dir/./char_b.jpg", listOf(a, b)))
    }

    @Test fun follow_tracksRenames() {
        // 이동·개명·되돌리기 · 폴더 왕복 편입 · 엑셀 재매핑 · 월드패키지 복원 — 경로가 바뀌는 갈래.
        val moved = "$dir/sub/char_b.jpg"
        assertEquals(moved, CharacterRepresentativeImage.follow(b, mapOf(b to moved)))
        // 정규화가 필요한 표기로 들어와도 이어진다.
        assertEquals(moved, CharacterRepresentativeImage.follow("$dir/./char_b.jpg", mapOf(b to moved)))
        // 해당 없으면 그대로.
        assertEquals(a, CharacterRepresentativeImage.follow(a, mapOf(b to moved)))
        assertEquals("", CharacterRepresentativeImage.follow("", mapOf(b to moved)))
    }

    @Test fun resolveAfterWrite_followsThenRetains() {
        val moved = "$dir/sub/char_b.jpg"
        // 옮겨졌고 새 목록에 있다 → 따라간다.
        assertEquals(
            moved,
            CharacterRepresentativeImage.resolveAfterWrite(b, listOf(a, moved), mapOf(b to moved))
        )
        // 옮겨졌는데 새 목록에 없다 → 비운다.
        assertEquals(
            "",
            CharacterRepresentativeImage.resolveAfterWrite(b, listOf(a), mapOf(b to moved))
        )
        // 목록 통째 교체(폼 저장)에서 살아남는다.
        assertEquals(b, CharacterRepresentativeImage.resolveAfterWrite(b, listOf(c, b)))
    }

    @Test fun wasDropped_flagsOnlyRealLosses() {
        assertTrue(CharacterRepresentativeImage.wasDropped(b, ""))
        assertTrue(CharacterRepresentativeImage.wasDropped(b, null))
        assertFalse(CharacterRepresentativeImage.wasDropped(b, b))
        assertFalse(CharacterRepresentativeImage.wasDropped("", ""))
        assertFalse(CharacterRepresentativeImage.wasDropped(null, ""))
    }

    // ── 구버전 데이터 (R-2) ──

    @Test fun nullPointerBehavesLikeNoPin() {
        // 휴지통 payload·월드패키지는 Gson이 Unsafe로 객체를 만들어 non-null 선언에도
        // null이 주입된다. null과 "" 는 같게 다뤄야 한다.
        val withNull = CharacterRepresentativeImage.pick(json(a, b), null, 3L, 3L)
        val withEmpty = CharacterRepresentativeImage.pick(json(a, b), "", 3L, 3L)
        assertEquals(withEmpty, withNull)
        assertFalse(withNull.pinnedMissing)
    }

    @Test fun whitespacePointerIsNoPin() {
        val pick = CharacterRepresentativeImage.pick(json(a, b), "   ", 3L, 3L)
        assertFalse(pick.pinnedMissing)
        assertEquals(CharacterRepresentativeImage.Source.RANDOM, pick.source)
    }

    // ── 목록을 이미 들고 있는 호출부 ──

    @Test fun pickFrom_matchesPick() {
        val paths = listOf(a, b, c)
        assertEquals(
            CharacterRepresentativeImage.pick(json(a, b, c), c, 11L, 2L),
            CharacterRepresentativeImage.pickFrom(paths, c, 11L, 2L)
        )
    }

    @Test fun newSeed_variesAcrossCalls() {
        val seeds = (1..64).map { CharacterRepresentativeImage.newSeed() }.toSet()
        assertNotEquals(1, seeds.size)
    }
}
