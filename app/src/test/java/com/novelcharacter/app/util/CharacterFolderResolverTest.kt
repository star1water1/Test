package com.novelcharacter.app.util

import com.novelcharacter.app.util.FolderRoundtripPlanner.CharacterFolderResolver
import com.novelcharacter.app.util.FolderRoundtripPlanner.CharacterFolderResolver.Result
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 동명이인 폴더 해소 사다리의 계약.
 *
 * 이 사다리가 지키는 것은 하나다 — **모호하면 고르지 않는다.** 이름이 가리키는 대상이
 * 둘 이상일 때 아무나 집으면 그것은 오배정이고, 이 기능에서 오배정은 되돌리기가 특히
 * 어렵다(수동 링크 묶음은 자동 링크와 달리 재동기화가 풀어 주지 않는다).
 */
class CharacterFolderResolverTest {

    private val codeGaon1 = "a1b2c3d4e5f60718"
    private val codeGaon2 = "9f8e7d6c5b4a3928"

    /** 가온이 2명(7·9), 나린이 1명(11). 가온 7은 작품 100, 가온 9는 작품 200. */
    private fun resolver(choices: Map<String, Long> = emptyMap()) = CharacterFolderResolver(
        characterIdsByName = mapOf("가온" to listOf(7L, 9L), "나린" to listOf(11L)),
        characterIdByCode = mapOf(codeGaon1 to 7L, codeGaon2 to 9L),
        novelIdByCharacterId = mapOf(7L to 100L, 9L to 200L, 11L to 100L),
        novelIdsByTitle = mapOf("은하전기" to listOf(100L), "검의노래" to listOf(200L)),
        choices = choices
    )

    // ── 1단계: 정확 일치 ──

    @Test fun uniqueNameResolves() {
        assertEquals(Result.Found(11L), resolver().resolve("나린"))
    }

    @Test fun unknownNameIsNotCharacter() {
        assertEquals(Result.NotCharacter, resolver().resolve("없는이름"))
    }

    /**
     * **사다리의 순서가 실제로 중요한 자리.** 사용자가 이미 `가온(가명)`이라는 *이름*을 쓰고
     * 있으면 그 폴더는 그 캐릭터의 것이지 "가온인데 작품이 가명"이 아니다. 정확 일치를 먼저
     * 보지 않으면 이 규약이 사용자가 쓰던 이름을 빼앗는다.
     */
    @Test fun exactNameWithParenthesesWinsOverNovelHint() {
        val r = CharacterFolderResolver(
            characterIdsByName = mapOf("가온(가명)" to listOf(42L), "가온" to listOf(7L, 9L)),
            novelIdByCharacterId = mapOf(7L to 100L, 9L to 200L),
            novelIdsByTitle = mapOf("가명" to listOf(100L))
        )
        assertEquals(Result.Found(42L), r.resolve("가온(가명)"))
    }

    // ── 2단계: 코드 ──

    @Test fun codeSuffixResolvesToThatCharacter() {
        assertEquals(Result.Found(7L), resolver().resolve("가온#$codeGaon1"))
        assertEquals(Result.Found(9L), resolver().resolve("가온#$codeGaon2"))
    }

    /**
     * 코드가 있는데 못 찾으면 **이름으로 폴백하지 않는다.** 코드를 적었다는 것은 대상을
     * 특정하려는 의도이고, 이름 폴백은 동명일 때 곧바로 오배정이 된다.
     */
    @Test fun unknownCodeDoesNotFallBackToName() {
        assertEquals(Result.UnknownCode, resolver().resolve("가온#0000000000000000"))
    }

    /** 코드가 붙어 있으면 이름이 달라도 코드가 이긴다 — 사용자가 폴더 앞부분만 고쳐도 안전하다. */
    @Test fun codeWinsOverRenamedLabel() {
        assertEquals(Result.Found(7L), resolver().resolve("아무이름#$codeGaon1"))
    }

    /** 코드 형태가 아닌 `#`은 이름의 일부다 — `C#으로 배우기` 같은 이름을 빼앗지 않는다. */
    @Test fun nonCodeHashIsPartOfName() {
        val r = CharacterFolderResolver(characterIdsByName = mapOf("C#으로 배우기" to listOf(5L)))
        assertEquals(Result.Found(5L), r.resolve("C#으로 배우기"))
    }

    @Test fun shortHexAfterHashIsNotCode() {
        // 16자가 아니면 코드가 아니다 — `가온#1`은 그냥 그런 이름의 폴더다.
        assertEquals(Result.NotCharacter, resolver().resolve("가온#1"))
    }

    // ── 3단계: 작품 힌트 ──

    @Test fun novelHintNarrowsDuplicates() {
        assertEquals(Result.Found(7L), resolver().resolve("가온(은하전기)"))
        assertEquals(Result.Found(9L), resolver().resolve("가온(검의노래)"))
    }

    @Test fun novelHintForUnknownNovelStaysAmbiguous() {
        assertEquals(Result.Ambiguous, resolver().resolve("가온(없는작품)"))
    }

    /** 같은 제목의 작품이 둘이면 좁히는 것 자체가 근거 없는 선택이다. */
    @Test fun duplicateNovelTitleStaysAmbiguous() {
        val r = CharacterFolderResolver(
            characterIdsByName = mapOf("가온" to listOf(7L, 9L)),
            novelIdByCharacterId = mapOf(7L to 100L, 9L to 200L),
            novelIdsByTitle = mapOf("같은제목" to listOf(100L, 200L))
        )
        assertEquals(Result.Ambiguous, r.resolve("가온(같은제목)"))
    }

    /**
     * **같은 작품 안의 동명이인은 작품명으로도 못 가른다.** 작품 힌트가 선택 창을 대체하지
     * 못하고 줄이기만 한다는 사실을 고정한다.
     */
    @Test fun sameNovelDuplicatesStayAmbiguous() {
        val r = CharacterFolderResolver(
            characterIdsByName = mapOf("가온" to listOf(7L, 9L)),
            novelIdByCharacterId = mapOf(7L to 100L, 9L to 100L),
            novelIdsByTitle = mapOf("은하전기" to listOf(100L))
        )
        assertEquals(Result.Ambiguous, r.resolve("가온(은하전기)"))
    }

    // ── 4단계: 물어본다 ──

    @Test fun bareDuplicateNameIsAmbiguous() {
        assertEquals(Result.Ambiguous, resolver().resolve("가온"))
    }

    @Test fun userChoiceResolvesAmbiguity() {
        assertEquals(Result.Found(9L), resolver(choices = mapOf("가온" to 9L)).resolve("가온"))
    }

    /** 물어본 키와 답한 키가 같아야 한다 — 다르면 사용자가 고른 답이 조용히 무시된다. */
    @Test fun choiceKeyMatchesAmbiguousKey() {
        val r = resolver()
        val key = r.keyOf("  가온  ")
        assertEquals("가온", key)
        assertEquals(Result.Found(7L), resolver(choices = mapOf(key to 7L)).resolve("  가온  "))
    }

    // ── 표시 이름 ──

    @Test fun displayNameStripsCodeAndHint() {
        assertEquals("가온", resolver().displayName("가온#$codeGaon1"))
        assertEquals("가온", resolver().displayName("가온(은하전기)"))
        assertEquals("나린", resolver().displayName("나린"))
    }
}
