package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImportedFormulaAudit] 하네스 — 엑셀로 들어온 수식의 검증 배선 (B-54).
 *
 * **순수 계층([FormulaValidator])은 이미 시험이 있다.** 이 판이 새로 만드는 위험은 전부
 * *배선*에 있고 그것이 셋이다: ① 언제 도는가(적재 뒤인가 행마다인가) ② 어느 키를 아는 키로
 * 치는가 ③ 어느 필드를 이번 파일 탓으로 세는가. 셋 다 여기서 갈린다.
 */
class ImportedFormulaAuditTest {

    private fun calc(key: String, formula: String, name: String = key) = FieldDefinition(
        universeId = 1L, key = key, name = name, type = "CALCULATED",
        config = """{"formula":${org.json.JSONObject.quote(formula)}}"""
    )

    private fun number(key: String, name: String = key) = FieldDefinition(
        universeId = 1L, key = key, name = name, type = "NUMBER"
    )

    // ── ① 언제 도는가 ──

    /**
     * **이 판의 방어선 하나.** 행을 읽으면서 검사하면 *뒷 행에 정의된 필드*를 참조하는 수식이
     * 전부 '없는 키'로 잡힌다 — 시트의 행 차례는 사용자가 정하는 것이지 의존 순서가 아니다.
     * [ImportedFormulaAudit.audit]이 **구역의 최종 목록**을 받는 것이 그 처방이고,
     * 그것을 되돌리면(건드린 것만 넘기면) 바로 아래 시험이 빨간불이 된다.
     */
    @Test
    fun `수식이 시트에서 나중에 정의된 필드를 가리켜도 경고하지 않는다`() {
        val fields = listOf(
            calc("total", "field('mana') + field('power')"),
            number("mana"),
            number("power")
        )
        assertTrue(
            "적재 뒤에 검사하는데도 뒷 행 참조가 '없는 키'로 잡혔다",
            ImportedFormulaAudit.audit(fields, setOf("total")).isEmpty()
        )
    }

    // ── ② 어느 키를 아는 키로 치는가 ──

    @Test
    fun `정말로 없는 키는 잡는다`() {
        val fields = listOf(calc("total", "field('mana') + field('없는키')"), number("mana"))
        val findings = ImportedFormulaAudit.audit(fields, setOf("total"))
        assertEquals(1, findings.size)
        assertEquals(
            listOf(FormulaValidator.Problem.UnknownKeys(listOf("없는키"))),
            findings[0].problems
        )
    }

    /**
     * **구역 전체를 넘기지 않으면 아는 키가 없는 키가 된다.** 이것이 무너지면 멀쩡한 파일을
     * 다시 들이는 것만으로 경고가 쏟아지고, 그 소음에 진짜 오타가 묻힌다 — 거짓 경고는
     * 침묵만큼 나쁘다.
     */
    @Test
    fun `건드리지 않은 옛 필드도 아는 키로 친다`() {
        val fields = listOf(calc("total", "field('legacy')"), number("legacy"))
        // 이번 파일은 'total' 한 줄만 담고 있었다 — 'legacy'는 이미 앱에 있던 필드다.
        assertTrue(ImportedFormulaAudit.audit(fields, setOf("total")).isEmpty())
    }

    /**
     * **순환 참조는 구역 전체를 따라가야 보인다.** 건드린 필드만 담으면 A→B→A에서 B가 옛
     * 필드일 때 고리가 끊긴 것처럼 보여 `오류`로 표시될 값이 조용히 통과한다.
     */
    @Test
    fun `옛 필드를 거쳐 돌아오는 순환도 잡는다`() {
        val fields = listOf(calc("a", "field('b')"), calc("b", "field('a')"))
        val findings = ImportedFormulaAudit.audit(fields, setOf("a"))
        assertEquals(1, findings.size)
        assertTrue(
            "옛 필드를 거쳐 돌아오는 고리를 놓쳤다",
            findings[0].problems.any { it is FormulaValidator.Problem.CircularReference }
        )
    }

    /**
     * 자기 자신을 `calculatedFormulas`에서 빼지 않으면 **모든 수식이 자기를 통해 돌아오는
     * 것으로 보인다** — 편집 창이 편집 중인 필드를 빼는 것과 같은 규약이다.
     */
    @Test
    fun `자기 자신은 순환 경로에 들어가지 않는다`() {
        val fields = listOf(calc("total", "field('mana')"), number("mana"))
        assertTrue(ImportedFormulaAudit.audit(fields, setOf("total")).isEmpty())
    }

    // ── ③ 어느 필드를 이번 파일 탓으로 세는가 ──

    /**
     * **이 판의 방어선 둘.** 예전부터 깨져 있던 수식까지 세면 이번 파일과 무관한 옛 흔적을
     * 이 파일 탓으로 고지하게 되고, 사용자는 자기가 방금 고친 것과 경고를 맞춰 볼 수 없다
     * (`unusedConfigKeys`가 *"파일이 말한 config"*만 재는 것과 같은 근거).
     */
    @Test
    fun `이번 파일이 건드리지 않은 깨진 수식은 고지하지 않는다`() {
        val fields = listOf(
            calc("broken", "field('없는키')"),
            calc("fine", "1 + 2")
        )
        assertTrue(
            "옛 흔적을 이 파일 탓으로 고지했다",
            ImportedFormulaAudit.audit(fields, setOf("fine")).isEmpty()
        )
        // 같은 필드를 이번 파일이 건드렸다면 말한다 — 좁히기가 침묵으로 굳지 않았음을 잠근다.
        assertEquals(1, ImportedFormulaAudit.audit(fields, setOf("broken")).size)
    }

    @Test
    fun `건드린 것이 없으면 아무것도 읽지 않는다`() {
        val fields = listOf(calc("broken", "field('없는키')"))
        assertTrue(ImportedFormulaAudit.audit(fields, emptySet()).isEmpty())
    }

    // ── 무엇을 잡는가 — NaN조차 내지 않는 부류가 이 행의 착수 근거다 ──

    /**
     * **화면의 `오류` 표시로는 못 잡는 것들.** 평가가 NaN을 낼 때만 `오류`가 뜨는데
     * 알아보지 못한 글자는 조용히 버려져 **그럴듯한 수**가 남는다(`sqrt(4)` → `4`).
     * 값이 틀렸다는 신호 자체가 없어 그대로 통계·엑셀로 퍼지는 것이 B-54의 착수 근거다.
     */
    @Test
    fun `조용히 버려지는 조각을 잡는다`() {
        val fields = listOf(calc("total", "sqrt(field('mana'))"), number("mana"))
        val findings = ImportedFormulaAudit.audit(fields, setOf("total"))
        assertEquals(1, findings.size)
        assertTrue(
            findings[0].problems.any { it is FormulaValidator.Problem.UnknownFunctions }
        )
    }

    @Test
    fun `자기참조를 잡는다`() {
        val fields = listOf(calc("total", "field('total') + 1"))
        val findings = ImportedFormulaAudit.audit(fields, setOf("total"))
        assertEquals(
            listOf(FormulaValidator.Problem.SelfReference("total")),
            findings[0].problems
        )
    }

    // ── 무엇을 잡지 않는가 ──

    /** 계산 필드가 아닌 것은 수식이 없다 — config에 남은 흔적을 검사하면 거짓 경고가 된다. */
    @Test
    fun `계산 필드가 아니면 검사하지 않는다`() {
        val stale = FieldDefinition(
            universeId = 1L, key = "old", name = "옛 필드", type = "NUMBER",
            config = """{"formula":"field('없는키')"}"""
        )
        assertTrue(ImportedFormulaAudit.audit(listOf(stale), setOf("old")).isEmpty())
    }

    /** 수식이 비었으면 검사할 것이 없다(빈 수식은 [FormulaValidator]도 통과시킨다). */
    @Test
    fun `수식이 비었으면 검사하지 않는다`() {
        val empty = FieldDefinition(
            universeId = 1L, key = "c", name = "c", type = "CALCULATED", config = """{"formula":""}"""
        )
        val noKey = FieldDefinition(
            universeId = 1L, key = "d", name = "d", type = "CALCULATED", config = "{}"
        )
        assertTrue(ImportedFormulaAudit.audit(listOf(empty, noKey), setOf("c", "d")).isEmpty())
    }

    /**
     * **설정이 깨진 것은 이 검사의 몫이 아니다** — 가져오기가 이미
     * *"설정(JSON)이 올바른 형식이 아닙니다"*로 따로 말한다. 여기서 또 말하면 한 사실이
     * 두 줄로 뜨고, 사용자는 서로 다른 두 문제로 읽는다.
     */
    @Test
    fun `설정이 깨진 필드는 조용히 건너뛴다`() {
        val broken = FieldDefinition(
            universeId = 1L, key = "c", name = "c", type = "CALCULATED", config = "{절단된"
        )
        assertTrue(ImportedFormulaAudit.audit(listOf(broken), setOf("c")).isEmpty())
    }

    /** 이름과 키를 함께 든다 — 같은 이름의 필드가 둘일 수 있어 이름만으로는 못 가린다. */
    @Test
    fun `찾은 것은 키와 이름을 함께 든다`() {
        val fields = listOf(calc("total_power", "field('없는키')", name = "총합"))
        val finding = ImportedFormulaAudit.audit(fields, setOf("total_power")).single()
        assertEquals("total_power", finding.key)
        assertEquals("총합", finding.name)
    }
}
