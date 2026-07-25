package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.Faction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 세력 참조 해석 — 전 세계관 first-match가 B 캐릭터를 A 세력에 무경고로 소속시키던 결함의 회귀 가드.
 * factions는 code만 유니크하고 이름은 세계관 간 중복이 허용된다.
 */
class FactionRefResolverTest {

    private fun faction(id: Long, universeId: Long, name: String, code: String = "c$id") =
        Faction(id = id, universeId = universeId, name = name, code = code, autoRelationType = "동료")

    private val knightsA = faction(1, 10, "기사단", "codeA")
    private val knightsB = faction(2, 20, "기사단", "codeB")
    private val guildA = faction(3, 10, "상인 길드", "codeC")
    private val index = FactionIndex(listOf(knightsA, knightsB, guildA))

    @Test
    fun code_winsOverName() {
        val r = index.resolve("상인 길드", "codeB", preferredUniverseId = 10L) as FactionLookupResult.Found
        assertEquals(knightsB, r.faction)
        assertTrue(r.matchedByCode)
    }

    @Test
    fun uniqueName_resolvesWithoutHint() {
        // 후보가 하나면 힌트 없이도 확정 — 미분류 캐릭터처럼 힌트 없는 행이 무조건 실패하면 안 된다
        val r = index.resolve("상인 길드", "", preferredUniverseId = null) as FactionLookupResult.Found
        assertEquals(guildA, r.faction)
        assertTrue(!r.matchedByCode)
    }

    @Test
    fun duplicateName_narrowedByUniverse() {
        // 핵심 회귀: 세계관 20 캐릭터는 세계관 20의 '기사단'에 붙어야 한다(예전엔 first-match로 A에 붙었다)
        val r = index.resolve("기사단", "", preferredUniverseId = 20L) as FactionLookupResult.Found
        assertEquals(knightsB, r.faction)
    }

    @Test
    fun duplicateName_noHint_isAmbiguousNotFirstMatch() {
        val r = index.resolve("기사단", "", preferredUniverseId = null)
        assertTrue(r is FactionLookupResult.Ambiguous && r.count == 2)
    }

    @Test
    fun duplicateName_hintMatchesNeither_isAmbiguous() {
        // 힌트 세계관에 후보가 없으면 추측하지 않는다 — 오배정보다 미해석 후 안내가 낫다
        val r = index.resolve("기사단", "", preferredUniverseId = 99L)
        assertTrue(r is FactionLookupResult.Ambiguous)
    }

    @Test
    fun unknownCode_fallsBackToName() {
        // 매칭 규약: 코드 우선 → 자연키 폴백. 예전에는 코드를 못 찾으면 행 전체를 실패시켰다.
        val r = index.resolve("상인 길드", "존재하지않는코드", preferredUniverseId = null) as FactionLookupResult.Found
        assertEquals(guildA, r.faction)
        assertTrue(!r.matchedByCode)   // 호출부가 폴백 사실을 고지한다
    }

    @Test
    fun blankName_andNoCode_isNotFound() {
        assertTrue(index.resolve("", "", null) is FactionLookupResult.NotFound)
        assertTrue(index.resolve("없는세력", "", null) is FactionLookupResult.NotFound)
    }

    // ── 세력 관계 시트: 세계관 열이 없어 상대 세력을 힌트로 쓴다 ──

    @Test
    fun pair_confirmedSideDisambiguatesTheOther() {
        val (r1, r2) = resolveFactionPair(index, "상인 길드", "", "기사단", "")
        assertEquals(guildA, (r1 as FactionLookupResult.Found).faction)
        assertEquals(knightsA, (r2 as FactionLookupResult.Found).faction)  // 세계관 10으로 좁혀짐
    }

    @Test
    fun pair_worksInEitherOrder() {
        // 앞쪽이 모호해도 뒤쪽이 확정되면 그 세계관으로 되돌아와 해소한다
        val (r1, r2) = resolveFactionPair(index, "기사단", "", "상인 길드", "")
        assertEquals(knightsA, (r1 as FactionLookupResult.Found).faction)
        assertEquals(guildA, (r2 as FactionLookupResult.Found).faction)
    }

    @Test
    fun pair_bothAmbiguous_staysAmbiguous() {
        val onlyDupes = FactionIndex(listOf(knightsA, knightsB))
        val (r1, r2) = resolveFactionPair(onlyDupes, "기사단", "", "기사단", "")
        assertTrue(r1 is FactionLookupResult.Ambiguous && r2 is FactionLookupResult.Ambiguous)
    }

    @Test
    fun ambiguityMessage_namesUniversesAndCorrectionPath() {
        val r = index.resolve("기사단", "", null) as FactionLookupResult.Ambiguous
        val msg = factionAmbiguityMessage("세력 소속 행 3", "기사단", r, "세력코드") {
            mapOf(10L to "세계관A", 20L to "세계관B")[it]
        }
        assertTrue(msg.contains("세계관A") && msg.contains("세계관B"))
        assertTrue(msg.contains("세력코드"))
    }

    // ── 참조 열 쌍 규약 (편집 가능한 이름 열 + readOnly 코드 열) ──

    @Test
    fun refIntent_bothColumnsAbsent_keeps() {
        assertEquals(RefIntent.KEEP, refColumnIntent(false, false, "", ""))
    }

    @Test
    fun refIntent_nameColumnBlank_clears_ignoringStaleCode() {
        // 핵심 회귀: 사용자가 '세력'을 비웠는데 회색 '세력코드' 잔재 때문에 해제되지 않던 비대칭
        assertEquals(RefIntent.CLEAR, refColumnIntent(true, true, "", "codeA"))
        assertEquals(RefIntent.CLEAR, refColumnIntent(true, false, "  ", ""))
    }

    @Test
    fun refIntent_nameFilled_looksUp() {
        assertEquals(RefIntent.LOOKUP, refColumnIntent(true, true, "기사단", ""))
        assertEquals(RefIntent.LOOKUP, refColumnIntent(true, true, "기사단", "codeA"))
    }

    @Test
    fun refIntent_codeOnlyFile_codeDecidesPresence() {
        // 구버전 파일·이름 열 삭제: 코드 열이 유무까지 결정한다
        assertEquals(RefIntent.LOOKUP, refColumnIntent(false, true, "", "codeA"))
        assertEquals(RefIntent.CLEAR, refColumnIntent(false, true, "", ""))
    }
}
