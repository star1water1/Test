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

    @Test fun shrinkingTheListNeverPointsSomewhereElse() {
        // **B-106 ⓑ가 없앤 병을 못 박는다.** 작품·세계관 카드는 인덱스를 **저장**해 두고
        // `idx % size`로 꺼냈고, 그래서 카드에서 이미지를 하나 빼면 남은 그림이 아니라
        // **엉뚱한 그림**이 떴다(3장짜리에서 2번을 골라 뒀는데 한 장을 빼면 0번이 된다).
        //
        // 여기서 재는 것은 *어떤 그림이 뽑히는가*가 아니라 **뽑힌 것이 언제나 현재 목록 안을
        // 가리키는가**다 — 크기를 그때그때 넘기는 한 그 사실이 깨질 수 없고, 다시 저장 방식으로
        // 돌아가면 이 시험이 원리적으로 못 세우게 된다(그때는 이 함수에 크기가 안 온다).
        val seed = 4242L
        for (id in 1L..50L) {
            for (size in 1..8) {
                val idx = CharacterRepresentativeImage.randomIndex(seed, id, size)
                assertTrue("id=$id size=$size idx=$idx 이 목록 밖", idx in 0 until size)
            }
        }
    }

    @Test fun sameSeedAndSizeIsStableAcrossRebinds() {
        // 스크롤 재바인드·정렬·필터에 카드 그림이 튀면 안 된다(진입 1회 재추첨 — 확정 7-3).
        // 어댑터가 bind마다 부르는 함수이므로 순수 함수로서 같은 답을 내야 한다.
        val first = CharacterRepresentativeImage.randomIndex(999L, 12L, 5)
        repeat(50) { assertEquals(first, CharacterRepresentativeImage.randomIndex(999L, 12L, 5)) }
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

    // ── 가중 추첨 (B-104 소비처 ⓑ · 설계 13-5) ──
    //
    // 여기서 지키는 것 넷: **무게가 없으면 한 비트도 안 바뀐다**(사용자에게 한 약속) ·
    // **지정한 대표가 여전히 무게를 이긴다**(사다리 1번은 그대로다) · **같은 시드면 결과가
    // 고정**(스크롤 재바인드에 안 튄다) · **무게가 실제로 분포를 기울인다**.

    private fun canon(path: String) = ImagePathMatch.canonical(path)

    @Test fun weights_null_isIdenticalToUniform() {
        val paths = listOf(a, b, c)
        for (seed in 1L..200L) {
            assertEquals(
                "무게가 없으면 종전 경로 그대로여야 한다",
                CharacterRepresentativeImage.randomIndex(seed, 5L, paths.size),
                CharacterRepresentativeImage.weightedIndex(seed, 5L, paths, null)
            )
        }
    }

    @Test fun weights_emptyMap_isIdenticalToUniform() {
        val paths = listOf(a, b, c)
        assertEquals(
            CharacterRepresentativeImage.randomIndex(7L, 5L, paths.size),
            CharacterRepresentativeImage.weightedIndex(7L, 5L, paths, RepresentativeWeighting.Weights(emptyMap(), 1.0))
        )
    }

    @Test fun weights_doNotBeatPinnedRepresentative() {
        // 무게가 c에 쏠려 있어도 지정한 대표(a)가 이긴다 — 사다리 1번은 무게 위에 있다.
        val pick = CharacterRepresentativeImage.pickFrom(
            listOf(a, b, c), a, 42L, 9L,
            RepresentativeWeighting.Weights(mapOf(canon(c) to 1.0, canon(a) to 0.001, canon(b) to 0.001), 1.0)
        )
        assertEquals(CharacterRepresentativeImage.Source.PINNED, pick.source)
        assertEquals(a, pick.path)
    }

    @Test fun weights_sameSeedGivesSameResult() {
        val paths = listOf(a, b, c)
        val weights = RepresentativeWeighting.Weights(mapOf(canon(a) to 1.0, canon(b) to 0.3, canon(c) to 0.1), 1.0)
        val first = CharacterRepresentativeImage.weightedIndex(99L, 3L, paths, weights)
        repeat(50) {
            assertEquals(first, CharacterRepresentativeImage.weightedIndex(99L, 3L, paths, weights))
        }
    }

    @Test fun weights_shiftTheDistribution() {
        // 열 배 무거운 그림이 실제로 더 자주 뽑히는가 — 시드를 많이 돌려 분포로 본다.
        val paths = listOf(a, b)
        val weights = RepresentativeWeighting.Weights(mapOf(canon(a) to 1.0, canon(b) to 0.1), 1.0)
        var first = 0
        val trials = 3000
        for (seed in 1L..trials) {
            if (CharacterRepresentativeImage.weightedIndex(seed, 1L, paths, weights) == 0) first++
        }
        // 이론값은 1/1.1 ≈ 0.909. 넉넉히 잡아도 균등(0.5)과는 확실히 갈린다.
        assertTrue("무게가 분포를 기울여야 한다 (실제 $first/$trials)", first > (trials * 0.85).toInt())
        assertTrue("그래도 고정은 아니다 — 아래 순위도 뽑힌다", first < trials)
    }

    @Test fun weights_unknownPathUsesTheAnchorWeight() {
        // 표에 없는 경로는 **표가 스스로 들고 온 앵커 무게**다 — 0으로 치면 새로 넣은 그림이
        // 영영 안 뜨고, 1로 치면 그것이 곧 1위의 무게라 새 그림이 가장 자주 뜬다.
        val paths = listOf(a, b)
        val weights = RepresentativeWeighting.Weights(mapOf(canon(a) to 0.0001), unknown = 1.0)
        var second = 0
        for (seed in 1L..500L) {
            if (CharacterRepresentativeImage.weightedIndex(seed, 2L, paths, weights) == 1) second++
        }
        assertTrue("표에 없는 그림이 거의 언제나 뽑혀야 한다 (실제 $second/500)", second > 450)

        // 앵커가 가벼우면 반대로 거의 안 뽑힌다 — 값이 실제로 쓰인다는 뜻이다.
        val light = RepresentativeWeighting.Weights(mapOf(canon(a) to 1.0), unknown = 0.0001)
        var lightSecond = 0
        for (seed in 1L..500L) {
            if (CharacterRepresentativeImage.weightedIndex(seed, 2L, paths, light) == 1) lightSecond++
        }
        assertTrue("앵커 무게가 그대로 반영돼야 한다 (실제 $lightSecond/500)", lightSecond < 50)
    }

    @Test fun weights_zeroOrBrokenValuesDoNotFreezeThePick() {
        // 0·음수·NaN이 섞여 들어와도 그 그림이 영영 안 뜨는 일은 없다(앵커 무게로 접는다).
        val paths = listOf(a, b, c)
        val weights = RepresentativeWeighting.Weights(
            mapOf(canon(a) to 0.0, canon(b) to -5.0, canon(c) to Double.NaN), unknown = 1.0
        )
        val seen = (1L..300L)
            .map { CharacterRepresentativeImage.weightedIndex(it, 4L, paths, weights) }
            .toSet()
        assertEquals("셋 다 뽑혀야 한다", setOf(0, 1, 2), seen)
    }

    @Test fun weights_nullEntriesDoNotCrash() {
        // **선언은 `List<String>`이지만 런타임에 null이 섞인다** — 어댑터가 `imagePaths`를
        // 날 Gson으로 읽고 `[null]` 같은 값이 실제로 들어온다. 여기서 죽으면 목록이 통째로 못 뜬다.
        @Suppress("UNCHECKED_CAST")
        val paths = listOf(a, null, b) as List<String>
        val weights = RepresentativeWeighting.Weights(mapOf(canon(a) to 1.0), unknown = 0.5)
        val seen = (1L..200L)
            .map { CharacterRepresentativeImage.weightedIndex(it, 6L, paths, weights) }
            .toSet()
        assertTrue("셈이 돌아야 한다", seen.isNotEmpty())
        assertTrue("범위를 벗어나지 않는다", seen.all { it in paths.indices })
    }
}
