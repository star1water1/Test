package com.novelcharacter.app.excel

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시트명 배정 규약 (4-5, N3).
 *
 * 고정하는 계약: **예약명은 그 소유자만 가질 수 있다.** 종전에는 예약 시트가 캐릭터 시트보다
 * 앞줄에서 생성되는지에 따라 이름을 지켜냈고(호출 순서 의존), 예약 시트 20개 중 7개만,
 * 그중 6개도 조건부로만 보호됐다.
 */
class SheetNameAssignmentTest {

    @Test
    fun `세계관은 어떤 예약명도 차지할 수 없다`() {
        // 예약 시트가 아직 하나도 만들어지지 않은 상태에서 세계관 시트를 먼저 만들어도
        // 예약명을 가져가지 못해야 한다 — 이것이 '순서 의존'을 '규칙'으로 바꾼 지점이다.
        for (reserved in RESERVED_SHEET_NAMES) {
            val used = mutableSetOf<String>()
            val assigned = assignSheetName(reserved, used, ownerOf = null)
            assertFalse(
                "'$reserved' 이름의 세계관이 예약명을 차지했다 → $assigned",
                assigned == reserved
            )
            assertTrue("접미사 형태여야 한다: $assigned", isSuffixedVariantOf(assigned, reserved))
        }
    }

    @Test
    fun `예약 시트는 자기 이름을 그대로 갖는다`() {
        for (reserved in RESERVED_SHEET_NAMES) {
            val used = mutableSetOf<String>()
            assertEquals(reserved, assignSheetName(reserved, used, ownerOf = reserved))
        }
    }

    @Test
    fun `세계관이 먼저 배정돼도 예약 시트가 원명을 지킨다`() {
        val used = mutableSetOf<String>()
        val universeSheet = assignSheetName(UNCLASSIFIED_SHEET_NAME, used, ownerOf = null)
        val unclassified = assignSheetName(UNCLASSIFIED_SHEET_NAME, used, ownerOf = UNCLASSIFIED_SHEET_NAME)
        assertEquals(UNCLASSIFIED_SHEET_NAME, unclassified)
        assertEquals("미분류 캐릭터(2)", universeSheet)
    }

    @Test
    fun `평범한 세계관 이름은 그대로 쓰고 동명은 접미사로 구분한다`() {
        val used = mutableSetOf<String>()
        assertEquals("아르카디아", assignSheetName("아르카디아", used))
        assertEquals("아르카디아(2)", assignSheetName("아르카디아", used))
        assertEquals("아르카디아(3)", assignSheetName("아르카디아", used))
    }

    @Test
    fun `POI의 대소문자 무시 중복 판정을 반영한다`() {
        // createSheet는 'myworld' 다음 'MyWorld'를 중복으로 보고 예외로 죽는다 —
        // 대소문자를 구분하는 집합으로는 못 걸러 내보내기 자체가 실패했다.
        val used = mutableSetOf<String>()
        assertEquals("myworld", assignSheetName("myworld", used))
        assertEquals("MyWorld(2)", assignSheetName("MyWorld", used))
    }

    @Test
    fun `앞뒤 아포스트로피는 제거한다 — POI가 예외로 거부한다`() {
        assertEquals("아르카디아", sanitizeSheetNameBase("'아르카디아'"))
        assertEquals("아르카디아", sanitizeSheetNameBase("아르카디아'"))
        assertEquals("아르's카디아", sanitizeSheetNameBase("아르's카디아"))
    }

    @Test
    fun `금칙문자 제거와 31자 절단은 내보내기 가져오기가 같은 함수를 쓴다`() {
        assertEquals("AB", sanitizeSheetNameBase("A/B"))
        assertEquals("AB", sanitizeSheetNameBase("A[B]".replace("]", "")))
        assertEquals(31, sanitizeSheetNameBase("가".repeat(50)).length)
        assertEquals("Sheet", sanitizeSheetNameBase("///"))
        assertEquals("Sheet", sanitizeSheetNameBase("'''"))
    }

    @Test
    fun `절단으로 이름이 겹쳐도 접미사가 31자를 넘지 않는다`() {
        val used = mutableSetOf<String>()
        val long = "가".repeat(40)
        val first = assignSheetName(long, used)
        val second = assignSheetName(long, used)
        assertEquals(31, first.length)
        assertTrue("31자 초과: ${second.length}", second.length <= 31)
        assertTrue(second.endsWith("(2)"))
    }

    @Test
    fun `접미사 변형 판정은 원명 자신을 포함하지 않는다`() {
        assertFalse(isSuffixedVariantOf("세력", "세력"))
        assertTrue(isSuffixedVariantOf("세력(2)", "세력"))
        assertTrue(isSuffixedVariantOf("세력(12)", "세력"))
        assertFalse(isSuffixedVariantOf("세력관계", "세력"))
        assertFalse(isSuffixedVariantOf("다른(2)", "세력"))
    }

    @Test
    fun `절단된 접미사 시트도 원명의 변형으로 인식한다`() {
        // 31자 한도 때문에 접미사 앞이 잘린 경우 — 가져오기가 되찾을 수 있어야 한다.
        val base = "가".repeat(31)
        val suffixed = "가".repeat(28) + "(2)"
        assertEquals(31, suffixed.length)
        assertTrue(isSuffixedVariantOf(suffixed, base))
    }

    /**
     * 실제 POI로 시트를 만들어 본다 — 배정 규칙이 POI 제약과 어긋나면 **내보내기 자체가
     * 예외로 죽어 백업 파일이 만들어지지 않는다.** 규칙만 검사하는 테스트로는 못 잡는다.
     */
    @Test
    fun `적대적인 세계관 이름으로도 POI가 시트를 만들 수 있다`() {
        val hostile = listOf(
            "'따옴표'", "따옴표'", "'앞따옴표", "A/B", "A\\B", "A[B]C", "A*B?C:D",
            "///", "'''", "가".repeat(40), "가".repeat(40), "myworld", "MyWorld", "MYWORLD",
            UNCLASSIFIED_SHEET_NAME, "세력", "이름 은행", "앱 설정", "세력", ""
        )
        XSSFWorkbook().use { wb ->
            val used = mutableSetOf<String>()
            // 예약 시트를 전부 소유자로 먼저 만든다
            for (reserved in RESERVED_SHEET_NAMES) {
                wb.createSheet(assignSheetName(reserved, used, ownerOf = reserved))
            }
            // 그 다음 적대적 이름의 세계관 시트들 — 예외 없이 만들어져야 한다
            for (name in hostile) {
                val assigned = assignSheetName(name, used, ownerOf = null)
                wb.createSheet(assigned)   // 실패하면 IllegalArgumentException으로 테스트가 깨진다
            }
            assertEquals(RESERVED_SHEET_NAMES.size + hostile.size, wb.numberOfSheets)
        }
    }

    // ── 되찾기 판정의 회귀 방어 (자기 재공격이 잡은 결함) ──

    @Test
    fun `첫 열이 이름인 spec들은 캐릭터 시트와 헤더로 구분돼야 한다`() {
        // 세력이 0건이라 '세력' 시트가 없고, 같은 이름의 세계관 캐릭터 시트가 '세력(2)'로
        // 밀려 있는 상황. 첫 열('이름')만 보는 판정은 캐릭터 시트를 세력 시트로 넘겨준다.
        val characterHeaders = characterSpec(emptyList(), emptyList()).columns.map { it.header }
        val ambiguous = listOf(
            universeSpec(), nameBankSpec(), factionSpec(),
            userPresetTemplateSpec(), searchPresetSpec(), characterListPresetSpec()
        )
        for (spec in ambiguous) {
            assertEquals("이 테스트의 전제: 첫 열이 캐릭터와 같다", "이름", spec.firstColumnHeader)
            assertFalse(
                "'${spec.sheetName}' spec이 캐릭터 시트 헤더를 자기 것으로 인정한다",
                headersMatchSpec(characterHeaders, spec)
            )
        }
    }

    @Test
    fun `캐릭터 시트 지문이 어떤 예약 데이터 시트와도 겹치지 않는다`() {
        // '예약 데이터 시트는 결코 캐릭터 시트가 아니다'라는 판정(looksLikeCharacterSheet)의 전제.
        // 지문 헤더가 예약 spec에 하나라도 들어가면 그 **정상 시트가 거부되어** 카테고리 전체가
        // 가져오기에서 빠진다 — 지문을 늘리거나 spec에 열을 추가할 때 이 테스트가 막는다.
        val reserved = listOf(
            universeSpec(), novelSpec(emptyList()), fieldDefinitionSpec(emptyList()),
            timelineSpec(emptyList()), stateChangeSpec(), relationshipSpec(), relationshipChangeSpec(),
            nameBankSpec(), factionSpec(), factionMembershipSpec(), factionRelationshipSpec(),
            userPresetTemplateSpec(), searchPresetSpec(), characterListPresetSpec(),
            appSettingsSpec(), imageMetaSpec(), fieldValueLibrarySpec(), characterFieldValueSpec(),
            allCharactersSpec()
        )
        for (spec in reserved) {
            val headers = spec.columns.map { it.header }
            if (headers.firstOrNull() != "이름") continue   // 첫 열이 다르면 애초에 구분된다
            val collision = headers.drop(1).filter { it in CHARACTER_SHEET_FINGERPRINT }
            assertTrue(
                "'${spec.sheetName}' spec이 캐릭터 지문과 겹쳐 정상 시트가 거부된다: $collision",
                collision.isEmpty()
            )
        }
    }

    @Test
    fun `캐릭터 시트는 지문으로 스스로를 증명한다`() {
        val headers = characterSpec(emptyList(), emptyList()).columns.map { it.header }
        assertEquals("이름", headers.first())
        assertTrue(
            "캐릭터 시트가 지문을 하나도 갖지 않으면 예약 시트와 구분되지 않는다",
            headers.drop(1).any { it in CHARACTER_SHEET_FINGERPRINT }
        )
    }

    @Test
    fun `진짜 그 시트의 헤더는 인정한다`() {
        for (spec in listOf(factionSpec(), nameBankSpec(), universeSpec(), searchPresetSpec())) {
            assertTrue(spec.sheetName, headersMatchSpec(spec.columns.map { it.header }, spec))
        }
    }

    @Test
    fun `헤더가 spec보다 짧으면 그 시트로 인정하지 않는다`() {
        val spec = factionSpec()
        assertFalse(headersMatchSpec(listOf("이름"), spec))
        assertFalse(headersMatchSpec(emptyList(), spec))
    }

    @Test
    fun `접미사 이름은 원명보다 짧아지지 않는다 — 되찾기 판정이 깨지지 않게`() {
        // 아포스트로피를 한 번 더 다듬으면 이름이 1자 짧아져 isSuffixedVariantOf가
        // 원명의 변형으로 알아보지 못하고, 가져오기가 밀려난 시트를 영영 못 찾는다.
        val base = "가".repeat(27) + "'" + "나".repeat(3)
        assertEquals(31, base.length)
        assertEquals(base, sanitizeSheetNameBase(base))
        val used = mutableSetOf<String>()
        val first = assignSheetName(base, used)
        val second = assignSheetName(base, used)
        assertEquals(base, first)
        assertEquals(31, second.length)
        assertTrue("밀려난 시트를 되찾을 수 있어야 한다", isSuffixedVariantOf(second, base))
    }

    @Test
    fun `예약명 집합에 새 시트를 넣는 것만으로 보호된다`() {
        // RESERVED_SHEET_NAMES가 단일 소스라는 계약 — 내보내기가 이 집합을 직접 본다.
        assertTrue(GUIDE_SHEET_NAME in RESERVED_SHEET_NAMES)
        assertTrue(UNCLASSIFIED_SHEET_NAME in RESERVED_SHEET_NAMES)
        assertTrue(characterFieldValueSpec().sheetName in RESERVED_SHEET_NAMES)
        assertTrue(factionSpec().sheetName in RESERVED_SHEET_NAMES)
        assertTrue(nameBankSpec().sheetName in RESERVED_SHEET_NAMES)
        assertTrue(appSettingsSpec().sheetName in RESERVED_SHEET_NAMES)
    }
}
