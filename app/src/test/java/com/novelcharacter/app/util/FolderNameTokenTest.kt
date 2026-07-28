package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 이름 토큰 규약 계약 — 정리 폴더 왕복의 정체성 축(결정 D4).
 * 핵심은 셋이다: 접두 목록에 의존하지 않는 토큰 추출, 겹칠 때만 결정적으로 연장하는 사전,
 * 그리고 **개명으로 끊긴 토큰을 별칭으로 잇는 것**(설계 9장 C-1).
 */
class FolderNameTokenTest {

    private fun path(name: String) = "/data/user/0/app/files/$name"

    private val uuidA = "3f9a2c40-7b16-4c1e-8a55-0d1e2f3a4b5c"
    private val uuidB = "88ee0011-2233-4455-6677-8899aabbccdd"

    // ── 내부 파일명 → 토큰 ──

    @Test fun fullToken_readsUuidRegardlessOfPrefix() {
        val expected = uuidA.replace("-", "")
        assertEquals(expected, FolderNameToken.fullTokenOfFileName("char_$uuidA.jpg"))
        assertEquals(expected, FolderNameToken.fullTokenOfFileName("img_$uuidA.png"))
        assertEquals(expected, FolderNameToken.fullTokenOfFileName("universe_$uuidA.webp"))
        // 접두가 늘어도 규칙이 그대로 성립해야 한다 — IMAGE_PREFIXES를 참조하지 않는 이유다.
        assertEquals(expected, FolderNameToken.fullTokenOfFileName("newkind_$uuidA.jpg"))
    }

    @Test fun fullToken_isCaseInsensitiveAndLowercased() {
        assertEquals(uuidA.replace("-", ""), FolderNameToken.fullTokenOfFileName("char_${uuidA.uppercase()}.JPG"))
    }

    @Test fun fullToken_rejectsNonUuidNames() {
        assertNull(FolderNameToken.fullTokenOfFileName("사진.jpg"))
        assertNull(FolderNameToken.fullTokenOfFileName("char_12345.jpg"))
        assertNull(FolderNameToken.fullTokenOfFileName("char_$uuidA-extra.jpg"))
        assertFalse(FolderNameToken.isTokenizable(path("legacy_image.jpg")))
        assertTrue(FolderNameToken.isTokenizable(path("char_$uuidA.jpg")))
    }

    // ── 내보내기 파일명 조립·해석 ──

    @Test fun exportFileName_assemblesLabelNumberToken() {
        val token = uuidA.replace("-", "").take(12)
        assertEquals("가온-01.$token.jpg", FolderNameToken.exportFileName("가온", 1, token, "jpg"))
        assertEquals("가온-12.$token.png", FolderNameToken.exportFileName("가온", 12, token, ".PNG"))
        assertEquals("가온-105.$token.jpg", FolderNameToken.exportFileName("가온", 105, token, ""))
    }

    @Test fun tokenCandidate_readsSecondToLastDotSegment() {
        val short = "3f9a2c407b16"
        val full = uuidA.replace("-", "")
        assertEquals(short, FolderNameToken.tokenCandidateOf("가온-01.$short.jpg"))
        assertEquals(full, FolderNameToken.tokenCandidateOf("가온-01.$full.jpg"))
        // 라벨 안의 마침표는 해석에 영향을 주지 않는다 — 항상 끝에서 두 번째다.
        assertEquals(short, FolderNameToken.tokenCandidateOf("가온.2세-01.$short.jpg"))
        assertEquals(short, FolderNameToken.tokenCandidateOf("가온-01.${short.uppercase()}.jpg"))
    }

    @Test fun tokenCandidate_rejectsOrdinaryNames() {
        assertNull(FolderNameToken.tokenCandidateOf("사진.jpg"))
        assertNull(FolderNameToken.tokenCandidateOf("2026.07.28 사진.jpg"))
        assertNull(FolderNameToken.tokenCandidateOf("가온-01.3f9a2c407b1.jpg"))   // 11자
        assertNull(FolderNameToken.tokenCandidateOf("가온-01.3f9a2c407b1z.jpg"))  // 16진 아님
    }

    // ── 라벨 정리 ──

    @Test fun sanitizeLabel_replacesForbiddenCharsAndTrims() {
        assertEquals("가온_1", FolderNameToken.sanitizeLabel("가온/1"))
        assertEquals("a_b_c", FolderNameToken.sanitizeLabel("a:b|c"))
        assertEquals("가온", FolderNameToken.sanitizeLabel("  가온  "))
        assertEquals("가온", FolderNameToken.sanitizeLabel(".가온."))
        assertEquals("가_온", FolderNameToken.sanitizeLabel("가\n온"))
    }

    @Test fun sanitizeLabel_fallsBackWhenEmptied() {
        assertEquals(FolderNameToken.FALLBACK_LABEL, FolderNameToken.sanitizeLabel(""))
        assertEquals(FolderNameToken.FALLBACK_LABEL, FolderNameToken.sanitizeLabel("   "))
        assertEquals(FolderNameToken.FALLBACK_LABEL, FolderNameToken.sanitizeLabel("..."))
    }

    @Test fun sanitizeLabel_capsLength() {
        val long = "가".repeat(100)
        val result = FolderNameToken.sanitizeLabel(long)
        assertEquals(FolderNameToken.MAX_LABEL_LENGTH, result.length)
    }

    // ── 사전 ──

    @Test fun dictionary_usesShortTokenWhenUnique() {
        val a = path("char_$uuidA.jpg")
        val b = path("img_$uuidB.png")
        val dict = FolderNameToken.buildDictionary(listOf(a, b))

        assertEquals("3f9a2c407b16", dict.tokenByPath[a])
        assertEquals("88ee00112233", dict.tokenByPath[b])
        assertEquals(a, dict.pathByToken["3f9a2c407b16"])
        // 전체 토큰으로도 찾을 수 있어야 한다(연장 전후 사본이 모두 돌아온다).
        assertEquals(a, dict.pathByToken[uuidA.replace("-", "")])
        assertTrue(dict.legacyPaths.isEmpty())
    }

    @Test fun dictionary_extendsOnlyCollidingFiles() {
        // 앞 12자가 같고 뒤가 다른 두 UUID
        val c1 = "3f9a2c40-7b16-4c1e-8a55-000000000001"
        val c2 = "3f9a2c40-7b16-4c1e-8a55-000000000002"
        val lonely = path("img_$uuidB.jpg")
        val p1 = path("char_$c1.jpg")
        val p2 = path("char_$c2.jpg")
        val dict = FolderNameToken.buildDictionary(listOf(p1, p2, lonely))

        assertEquals(c1.replace("-", ""), dict.tokenByPath[p1])
        assertEquals(c2.replace("-", ""), dict.tokenByPath[p2])
        assertEquals("88ee00112233", dict.tokenByPath[lonely])   // 겹치지 않은 파일은 그대로 12자
        // 모호한 짧은 토큰은 사전에 실리지 않는다 — 정확 일치만 신뢰한다.
        assertNull(dict.pathByToken["3f9a2c407b16"])
        assertEquals(p1, dict.pathByToken[c1.replace("-", "")])
        assertEquals(p2, dict.pathByToken[c2.replace("-", "")])
    }

    @Test fun dictionary_collectsLegacyPathsSeparately() {
        val legacy = path("char_old_image.jpg")
        val dict = FolderNameToken.buildDictionary(listOf(legacy, path("char_$uuidA.jpg")))
        assertEquals(listOf(legacy), dict.legacyPaths)
        assertNull(dict.tokenByPath[legacy])
    }

    // ── 개명 별칭 (C-1) ──

    @Test fun dictionary_aliasKeepsOldTokenPointingAtRenamedFile() {
        val old = path("char_$uuidA.jpg")
        val new = path("char_$uuidB.jpg")
        val dict = FolderNameToken.buildDictionary(listOf(new), mapOf(old to new))

        // 재압축·복원 전에 내보낸 사본의 토큰으로도 현재 파일을 찾는다.
        assertEquals(new, dict.pathByToken["3f9a2c407b16"])
        assertEquals(new, dict.pathByToken[uuidA.replace("-", "")])
        assertEquals(new, dict.pathByToken["88ee00112233"])
    }

    @Test fun dictionary_aliasFollowsChains() {
        val v1 = path("char_$uuidA.jpg")
        val v2 = path("char_$uuidB.jpg")
        val v3 = path("char_11112222-3333-4444-5555-666677778888.jpg")
        val dict = FolderNameToken.buildDictionary(listOf(v3), mapOf(v1 to v2, v2 to v3))
        assertEquals(v3, dict.pathByToken["3f9a2c407b16"])
    }

    @Test fun dictionary_aliasIgnoredWhenTargetGone() {
        val old = path("char_$uuidA.jpg")
        val gone = path("char_$uuidB.jpg")
        val dict = FolderNameToken.buildDictionary(emptyList(), mapOf(old to gone))
        assertTrue(dict.pathByToken.isEmpty())
    }

    @Test fun dictionary_aliasNeverOverridesLiveFile() {
        // 지운 파일의 옛 토큰이 우연히 살아 있는 파일의 토큰과 같아도 현재 파일이 이긴다.
        val live = path("char_$uuidA.jpg")
        val ghost = path("img_$uuidA.jpg")      // 같은 UUID를 가진 옛 경로
        val dict = FolderNameToken.buildDictionary(listOf(live), mapOf(ghost to live))
        assertEquals(live, dict.pathByToken["3f9a2c407b16"])
    }

    @Test fun dictionary_aliasCycleDoesNotHang() {
        val a = path("char_$uuidA.jpg")
        val b = path("char_$uuidB.jpg")
        val dict = FolderNameToken.buildDictionary(emptyList(), mapOf(a to b, b to a))
        assertTrue(dict.pathByToken.isEmpty())
    }
}
