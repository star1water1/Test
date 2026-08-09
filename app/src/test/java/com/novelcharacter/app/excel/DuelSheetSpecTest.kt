package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.util.DuelFieldLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 시트 셋(B-104 ㄹ1)의 왕복 계약.
 *
 * **여기서 지키는 것 셋:** ① 시트 이름이 예약되어 세계관에 빼앗기지 않는다 ② 시트 헤더가
 * 서로를, 그리고 캐릭터 시트를 침범하지 않는다 ③ **필드 연결이 엑셀 글 ↔ 저장 형식으로
 * 왕복해도 순위와 방향이 그대로다**(개발 의도 4번 — 엑셀 왕복 무결성).
 */
class DuelSheetSpecTest {

    private val axisSpec = duelAxisSpec()
    private val matchSpec = duelMatchSpec()
    private val verdictSpec = duelVerdictSpec()

    // ── 이름 ──

    @Test
    fun `대결 시트 셋은 예약명이라 세계관에 빼앗기지 않는다`() {
        for (spec in listOf(axisSpec, matchSpec, verdictSpec)) {
            assertTrue("${spec.sheetName}이 예약명에 없다", spec.sheetName in RESERVED_SHEET_NAMES)
            // 같은 이름의 세계관이 있어도 예약 시트가 원명을 지킨다.
            val used = mutableSetOf<String>()
            val universeSheet = assignSheetName(spec.sheetName, used)
            val reservedSheet = assignSheetName(spec.sheetName, used, ownerOf = spec.sheetName)
            assertNotEquals(spec.sheetName, universeSheet)
            assertEquals(spec.sheetName, reservedSheet)
        }
    }

    /**
     * 첫 열이 서로 달라야 시트 정체가 갈린다. 축 시트의 첫 열을 '이름'으로 두지 않은 것도
     * 이 때문이다 — 그러면 캐릭터 시트와 첫 열이 같아진다.
     */
    @Test
    fun `대결 시트의 첫 열은 캐릭터 시트와 겹치지 않는다`() {
        assertEquals("축이름", axisSpec.firstColumnHeader)
        assertEquals("축", matchSpec.firstColumnHeader)
        assertEquals("축", verdictSpec.firstColumnHeader)
        assertNotEquals("이름", axisSpec.firstColumnHeader)
    }

    /** 기록과 상성은 첫 열이 같으므로 **둘째 열부터** 갈려야 한다(`headersMatchSpec`의 규약). */
    @Test
    fun `기록 시트와 상성 시트는 헤더로 서로를 구별한다`() {
        val matchHeaders = matchSpec.columns.map { it.header }
        val verdictHeaders = verdictSpec.columns.map { it.header }
        assertTrue(headersMatchSpec(matchHeaders, matchSpec))
        assertTrue(headersMatchSpec(verdictHeaders, verdictSpec))
        assertTrue("기록 헤더가 상성 시트로 읽히면 안 된다", !headersMatchSpec(matchHeaders, verdictSpec))
        assertTrue("상성 헤더가 기록 시트로 읽히면 안 된다", !headersMatchSpec(verdictHeaders, matchSpec))
    }

    // ── 어휘 ──

    @Test
    fun `시트가 쓰는 말과 저장값이 서로 짝이 맞는다`() {
        assertEquals(2, DuelSheetLabels.TARGETS.size)
        assertTrue(DuelAxis.TARGET_CHARACTER in DuelAxis.TARGET_TYPES)
        assertTrue(DuelAxis.TARGET_IMAGE in DuelAxis.TARGET_TYPES)
        assertEquals(listOf(DuelSheetLabels.KIND_COUNTER, DuelSheetLabels.KIND_UNDECIDED), DuelSheetLabels.KINDS)
        // 저장값 쪽도 둘뿐이다 — 라벨이 늘면 여기서 먼저 어긋난다.
        assertNotEquals(DuelCounterVerdict.KIND_COUNTER, DuelCounterVerdict.KIND_UNDECIDED)
    }

    /** 대상 열의 드롭다운이 실제 저장값 수와 같아야 한다 — 하나가 빠지면 그 축은 엑셀에서 만들 수 없다. */
    @Test
    fun `대상 드롭다운이 저장값 전부를 덮는다`() {
        val target = axisSpec.columns.first { it.header == "대상" }
        assertEquals(DuelAxis.TARGET_TYPES.size, target.dropdownOptions?.size)
    }

    // ── 필드 연결의 왕복 (개발 의도 4번) ──

    /**
     * 엑셀 칸에 적힌 글 → 저장 형식 → 다시 엑셀 칸. **순위와 방향이 그대로여야 한다.**
     * 여기가 어긋나면 파일을 한 번 내보냈다 들이는 것만으로 영향력 순위가 바뀐다.
     */
    @Test
    fun `필드 연결은 엑셀 글과 저장 형식 사이를 왕복한다`() {
        val text = "mana, -age, attr"
        val stored = DuelFieldLinks.encode(DuelFieldLinks.parseText(text))
        assertEquals(text, DuelFieldLinks.toText(DuelFieldLinks.decode(stored)))

        // 순위는 적은 차례 그대로다 — 정렬하면 1순위가 바뀐다.
        assertEquals(listOf("mana", "age", "attr"), DuelFieldLinks.decode(stored).map { it.key })
        assertEquals(listOf(true, false, true), DuelFieldLinks.decode(stored).map { it.higherWins })
    }

    @Test
    fun `빈 칸은 연결 없음으로 왕복한다`() {
        assertEquals("", DuelFieldLinks.toText(DuelFieldLinks.parseText("")))
        assertEquals("[]", DuelFieldLinks.encode(DuelFieldLinks.parseText("   ")))
    }

    // ── 프로필 필드 열 (B-122) ──

    /**
     * 축 시트의 **고정 열 순서 계약** — `ListPresetSpecColumnOrderTest`와 같은 부류다.
     *
     * 머리글은 `duelAxisSpec()`의 순서로 쓰이는데 **데이터 셀은 `row.createCell(n)`으로 번호를
     * 손수 매긴다**(`ExcelExporter.exportDuelAxes`). 둘이 어긋나도 오류가 나지 않고 **그 뒤 열이
     * 통째로 한 칸씩 밀린다** — `정렬순서` 자리에 축 코드가 들어가고, 그 파일을 되받으면
     * 코드를 숫자로 읽으려다 0이 된다.
     *
     * **B-122가 `산출필드` 뒤에 `프로필필드`를 끼워 넣으면서 실제로 그 위험을 만들었다**
     * (인덱스 6·7·8이 7·8·9로 밀렸다). 순서를 여기 못 박아 두면 다음에 열을 끼우는 사람은
     * 이 시험이 깨지는 것으로 **내보내기 쪽 인덱스도 함께 밀어야 한다는 사실을 알게 된다.**
     *
     * **그 장치가 실제로 한 번 더 일했다** — B-104 소비처 ⓑ·ⓒ가 `후보필터(JSON)` 뒤에
     * `기준축`을 끼우면서 8·9·10이 9·10·11로 밀렸고, 이 시험이 깨져 그 사실을 먼저 알렸다.
     */
    @Test
    fun `축 시트의 열 순서가 고정돼 있다`() {
        assertEquals(
            listOf(
                "축이름", "세계관", "세계관코드", "대상",
                "영향필드", "산출필드", "프로필필드", "후보필터(JSON)",
                "기준축", "정렬순서", "코드", "생성일"
            ),
            axisSpec.columns.map { it.header }
        )
    }

    /**
     * `기준축`은 **다른 불리언 열과 같은 표기**여야 한다 (B-104 소비처 ⓑ·ⓒ).
     *
     * 시트 하나에 `Y/N`과 `예/아니오`가 섞이면 사람이 고칠 때 어느 쪽이 먹는지 알 수 없다.
     * 읽는 쪽은 `parseSheetBoolean`이 둘 다 받지만, **드롭다운이 무엇을 권하는가**는 이 목록이
     * 정한다.
     */
    @Test
    fun `기준축 열은 Y·N 드롭다운이다`() {
        val basis = axisSpec.columns.single { it.header == "기준축" }
        assertEquals(listOf("Y", "N"), basis.dropdownOptions)
    }

    /**
     * 축 시트가 **연결 셋을 전부 싣는다.** 하나라도 빠지면 내보냈다 들이는 왕복에서 그
     * 연결이 파일에 없는 사실이 되고, 밖에서 고칠 길도 사라진다(개발 의도 4번).
     */
    @Test
    fun `축 시트가 연결 세 종류를 모두 싣는다`() {
        val headers = axisSpec.columns.map { it.header }
        assertTrue("영향필드" in headers)
        assertTrue("산출필드" in headers)
        assertTrue("프로필필드" in headers)
        // 연결끼리 붙어 있어야 사람이 한눈에 견준다 — 프로필은 산출 바로 뒤다.
        assertEquals(headers.indexOf("산출필드") + 1, headers.indexOf("프로필필드"))
    }

    /**
     * **읽기 전용 열은 사람이 고칠 자리가 아니다.** 프로필필드는 고쳐야 하는 칸이므로
     * 읽기 전용이 아니어야 한다 — 잠기면 엑셀에서 프로필을 편집할 길이 없다.
     */
    @Test
    fun `프로필필드 열은 사람이 고칠 수 있다`() {
        val profile = axisSpec.columns.first { it.header == "프로필필드" }
        assertTrue(!profile.readOnly)
        // 드롭다운을 달지 않는다 — 여러 키를 쉼표로 잇는 칸이라 한 값 고르기와 성질이 다르다.
        assertEquals(null, profile.dropdownOptions)
    }

    /**
     * 후보 필터 열 (B-168) — 연결 셋과 달리 **JSON 그대로**다. 값 목록·일치 방식이 딸린
     * 구조라 쉼표 문법으로 펴면 값 안의 쉼표와 충돌한다(프리셋 시트의 `필드필터(JSON)` 규약).
     * 잠그지는 않는다 — 형식이 어긋난 손편집은 가져오기가 기존 값을 지키며 경고한다.
     */
    @Test
    fun `후보필터 열은 JSON 칸이고 사람이 고칠 수 있다`() {
        val filter = axisSpec.columns.first { it.header == "후보필터(JSON)" }
        assertTrue(!filter.readOnly)
        assertEquals(null, filter.dropdownOptions)
        // 프로필(연결의 끝) 바로 뒤 — 연결 셋과 정렬·코드 사이에 서서 축의 "구성"이 한 덩이로 읽힌다.
        val headers = axisSpec.columns.map { it.header }
        assertEquals(headers.indexOf("프로필필드") + 1, headers.indexOf("후보필터(JSON)"))
    }
}
