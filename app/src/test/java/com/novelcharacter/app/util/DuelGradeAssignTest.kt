package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelGradeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 순위 → 등급 배정의 계산 계약 (B-113).
 *
 * 이 하네스가 못 박는 것은 **읽어서는 알 수 없는 넷**이다:
 *
 * 1. **분모와 분자가 둘 다 점수 보유자다** — 순위표 줄의 등수([DuelScoreIndex.Entry.rank])를
 *    쓰면 백분위가 100%를 넘고, 100%를 넘는 값은 어떤 컷에도 걸리지 않아 **말없이 최하
 *    등급이 된다.** 그래서 이 하네스는 `rank`에 일부러 쓰레기 값을 넣는다.
 * 2. **경계 표식은 자기 자신을 세지 않는다** — 오차만큼 낮춘 점수로 물을 때 자기 원래 점수가
 *    자기 위에 있는 것으로 세어지면, 오차가 1이어도 전원이 '경계'가 된다. 표식이 전원에게
 *    붙으면 아무 말도 안 하는 것과 같다.
 * 3. **0% 구간은 오류가 아니다** — 이 축에서 안 쓰는 등급이고, 검증은 단조 **비**감소다.
 * 4. **판별 근거가 없으면 묶음을 줄인다** — 직전 배정표가 없는데 "지난 반영값"이라고 이름
 *    붙이면 그 오판은 **손값을 기본 체크된 채로 덮는** 방향으로 틀린다.
 */
class DuelGradeAssignTest {

    private val labels4 = listOf("S", "A", "B", "C")

    /**
     * 점수표를 손으로 세운다 — `rank`는 **일부러 0**이다(계약 1). 배정이 그 값을 쓰면
     * 전원이 백분위 0%가 되어 이 하네스가 통째로 무너진다.
     */
    private fun scores(vararg triples: Triple<String, Int, Int>): DuelScoreIndex.AxisScores =
        DuelScoreIndex.AxisScores(
            axisId = 1L,
            axisCode = "ax1",
            axisName = "강함",
            universeId = 1L,
            byCode = triples.associate { (code, score, error) ->
                code to DuelScoreIndex.Entry(code = code, score = score, scoreError = error, rank = 0, played = 5)
            },
            unplayed = 0,
            scanned = 0,
            orphanMatches = 0,
            missingParticipants = 0,
            excludedMatches = 0,
            malformedMatches = 0,
            notConverged = false
        )

    private fun cuts(vararg pairs: Pair<String, Double>) =
        pairs.map { (label, percent) -> DuelGradeRef.Cut(label, percent) }

    // ── 라벨 차례 · 기본 초안 ──

    @Test
    fun `orderedLabels - 숫자가 서열이고 높은 등급이 앞이다`() {
        val grades = linkedMapOf("C" to 0.5, "B" to 1.0, "A" to 2.0, "S" to 3.0)
        assertEquals(labels4, DuelGradeAssign.orderedLabels(grades))
    }

    @Test
    fun `orderedLabels - 같은 숫자끼리는 표의 차례를 지킨다`() {
        val grades = linkedMapOf("가" to 1.0, "나" to 1.0, "다" to 2.0)
        assertEquals(listOf("다", "가", "나"), DuelGradeAssign.orderedLabels(grades))
    }

    @Test
    fun `evenCuts - 등급 수로 균등 분할하고 마지막 등급은 컷을 갖지 않는다`() {
        val even = DuelGradeAssign.evenCuts(labels4)
        assertEquals(listOf("S", "A", "B"), even.map { it.label })
        assertEquals(listOf(25.0, 50.0, 75.0), even.map { it.topPercent })
        assertTrue(DuelGradeAssign.evenCuts(listOf("S")).isEmpty())
    }

    @Test
    fun `evenCuts - 9단도 소수 한 자리로 떨어진다 (실사용 표본의 등급 수)`() {
        val nine = listOf("SSS", "SS", "S", "A", "B", "C", "D", "E", "F")
        val even = DuelGradeAssign.evenCuts(nine)
        assertEquals(8, even.size)
        assertEquals(11.1, even.first().topPercent, 0.0001)
        assertEquals(88.9, even.last().topPercent, 0.0001)
        assertTrue(DuelGradeAssign.validate(even, nine).isEmpty())
    }

    // ── 검증 ──

    @Test
    fun `validate - 균등 분할은 통과한다`() {
        assertTrue(DuelGradeAssign.validate(DuelGradeAssign.evenCuts(labels4), labels4).isEmpty())
    }

    @Test
    fun `validate - 0퍼센트 구간은 허용된다 (안 쓰는 등급)`() {
        // 설계 4-4 ⓒ — 구분선 둘이 겹친 상태. '단조 증가'로 검사하면 이것이 오류가 되어
        // 사용자가 등급 하나를 비워 둘 방법이 없어진다.
        val withZero = cuts("S" to 5.0, "A" to 5.0, "B" to 60.0)
        assertTrue(DuelGradeAssign.validate(withZero, labels4).isEmpty())
    }

    @Test
    fun `validate - 상위 퍼센트가 앞 등급보다 작으면 잡는다`() {
        val problems = DuelGradeAssign.validate(cuts("S" to 30.0, "A" to 20.0, "B" to 60.0), labels4)
        val decreasing = problems.filterIsInstance<DuelGradeAssign.Problem.Decreasing>().single()
        assertEquals("A", decreasing.label)
        assertEquals(30.0, decreasing.previous, 0.0001)
    }

    @Test
    fun `validate - 0~100 밖은 잡는다`() {
        val problems = DuelGradeAssign.validate(cuts("S" to -1.0, "A" to 20.0, "B" to 140.0), labels4)
        assertEquals(
            listOf("S", "B"),
            problems.filterIsInstance<DuelGradeAssign.Problem.OutOfRange>().map { it.label }
        )
    }

    @Test
    fun `validate - 유령 라벨과 뒤바뀐 차례를 잡는다`() {
        // 등급 체계가 편집돼 실효 표가 바뀌면 컷이 실재하지 않는 라벨을 가리킨다. 이대로
        // 배정하면 어떤 등급에도 없는 문자열이 캐릭터 값으로 심긴다.
        val problems = DuelGradeAssign.validate(cuts("S" to 5.0, "없는등급" to 20.0, "B" to 60.0), labels4)
        val mismatch = problems.filterIsInstance<DuelGradeAssign.Problem.LabelMismatch>().single()
        assertEquals(1, mismatch.index)
        assertEquals("A", mismatch.expected)
        assertEquals("없는등급", mismatch.actual)
    }

    @Test
    fun `validate - 컷 수가 라벨 수 빼기 1이 아니면 잡는다`() {
        val problems = DuelGradeAssign.validate(cuts("S" to 5.0), labels4)
        val count = problems.filterIsInstance<DuelGradeAssign.Problem.CountMismatch>().single()
        assertEquals(3, count.expected)
        assertEquals(1, count.actual)
    }

    @Test
    fun `validate - 라벨이 없거나 하나뿐이면 나눌 것이 없다`() {
        assertEquals(listOf(DuelGradeAssign.Problem.NoLabels), DuelGradeAssign.validate(emptyList(), emptyList()))
        assertEquals(listOf(DuelGradeAssign.Problem.SingleLabel), DuelGradeAssign.validate(emptyList(), listOf("S")))
    }

    // ── 배정 ──

    @Test
    fun `assign - 백분위는 점수 보유자끼리의 등수 나누기 점수 보유 인원이다`() {
        val axis = scores(
            Triple("a", 1600, 0), Triple("b", 1500, 0), Triple("c", 1400, 0), Triple("d", 1300, 0)
        )
        val result = DuelGradeAssign.assign(axis, DuelGradeAssign.evenCuts(labels4), labels4).associateBy { it.code }
        assertEquals(listOf(25.0, 50.0, 75.0, 100.0), listOf("a", "b", "c", "d").map { result[it]!!.percentile })
        assertEquals(listOf("S", "A", "B", "C"), listOf("a", "b", "c", "d").map { result[it]!!.label })
    }

    @Test
    fun `assign - 백분위가 컷과 정확히 같으면 그 등급이다`() {
        // 5명 20%씩처럼 흔한 경우에 백분위는 컷과 정확히 같아진다. 부동소수 비교가 헐거우면
        // "상위 20%인데 A가 아니다"가 나오고, 사용자는 컷을 잘못 적었다고 읽는다.
        val axis = scores(
            Triple("a", 1600, 0), Triple("b", 1550, 0), Triple("c", 1500, 0),
            Triple("d", 1450, 0), Triple("e", 1400, 0)
        )
        val five = listOf("S", "A", "B", "C", "D")
        val result = DuelGradeAssign.assign(axis, DuelGradeAssign.evenCuts(five), five).associateBy { it.code }
        assertEquals("S", result["a"]!!.label)
        assertEquals("A", result["b"]!!.label)
        assertEquals("D", result["e"]!!.label)
    }

    @Test
    fun `assign - 동점은 같은 등수라 같은 등급을 받는다`() {
        val axis = scores(
            Triple("a", 1600, 0), Triple("b", 1500, 0), Triple("c", 1500, 0), Triple("d", 1300, 0)
        )
        val result = DuelGradeAssign.assign(axis, DuelGradeAssign.evenCuts(labels4), labels4).associateBy { it.code }
        assertEquals(result["b"]!!.label, result["c"]!!.label)
        assertEquals(result["b"]!!.percentile, result["c"]!!.percentile, 0.0001)
    }

    @Test
    fun `assign - 점수 보유자만 대상이다 (무기록은 애초에 들어오지 않는다)`() {
        // 계약 2 — 앵커 1500으로 'C'를 지어내는 것이 바로 그 계약이 막는 병이다.
        val axis = scores(Triple("a", 1600, 0), Triple("b", 1400, 0))
        val result = DuelGradeAssign.assign(axis, DuelGradeAssign.evenCuts(labels4), labels4)
        assertEquals(setOf("a", "b"), result.map { it.code }.toSet())
    }

    @Test
    fun `assign - 오차가 컷을 가로지르면 경계로 표시한다`() {
        val axis = scores(
            Triple("a", 1600, 0), Triple("b", 1550, 0), Triple("c", 1500, 60), Triple("d", 1400, 0)
        )
        val result = DuelGradeAssign.assign(axis, DuelGradeAssign.evenCuts(labels4), labels4).associateBy { it.code }
        // c는 3위(75% → B)지만 오차를 더하면 2위(50% → A)라 컷 위에 서 있다.
        assertEquals("B", result["c"]!!.label)
        assertTrue(result["c"]!!.onBoundary)
        assertFalse(result["a"]!!.onBoundary)
    }

    @Test
    fun `assign - 경계 판정은 자기 자신을 세지 않는다`() {
        // 자기 원래 점수를 함께 세면 오차가 1이어도 등수가 한 칸 밀려 전원이 경계가 된다.
        val axis = scores(
            Triple("a", 1600, 1), Triple("b", 1500, 1), Triple("c", 1400, 1), Triple("d", 1300, 1)
        )
        val result = DuelGradeAssign.assign(axis, DuelGradeAssign.evenCuts(labels4), labels4)
        assertTrue("오차 1로 전원이 경계가 되었다", result.none { it.onBoundary })
    }

    @Test
    fun `assign - 라벨이 둘 미만이거나 점수가 없으면 빈 결과다`() {
        assertTrue(DuelGradeAssign.assign(scores(), DuelGradeAssign.evenCuts(labels4), labels4).isEmpty())
        assertTrue(DuelGradeAssign.assign(scores(Triple("a", 1500, 0)), emptyList(), listOf("S")).isEmpty())
    }

    // ── 경계 제안 ──

    @Test
    fun `suggestCuts - 오차가 겹치지 않는 큰 간격으로 경계를 옮긴다`() {
        val axis = scores(
            Triple("a", 1800, 10), Triple("b", 1790, 10),
            Triple("c", 1500, 10), Triple("d", 1490, 10),
            Triple("e", 1200, 10), Triple("f", 1190, 10)
        )
        val suggestion = DuelGradeAssign.suggestCuts(axis, listOf("S", "A", "B"))
        assertEquals(2, suggestion.moved)
        assertEquals(2, suggestion.requested)
        assertEquals(listOf(33.3, 66.7), suggestion.cuts.map { it.topPercent })
        assertEquals(listOf("S", "A"), suggestion.cuts.map { it.label })
    }

    @Test
    fun `suggestCuts - 오차 안의 간격은 표본 소음이라 제안하지 않는다`() {
        // 겹침 조건이 이 기능의 전부다. 이것이 없으면 앱이 없는 구조를 지어내 사용자에게
        // 확신을 판다(원칙 02가 금지하는 겉핥기).
        val axis = scores(
            Triple("a", 1520, 200), Triple("b", 1510, 200), Triple("c", 1500, 200), Triple("d", 1490, 200)
        )
        val suggestion = DuelGradeAssign.suggestCuts(axis, labels4)
        assertEquals(0, suggestion.moved)
        assertEquals(DuelGradeAssign.evenCuts(labels4), suggestion.cuts)
    }

    @Test
    fun `suggestCuts - 못 찾은 경계는 균등 자리에 그대로 두고 몇 개를 옮겼는지 말한다`() {
        // 라벨은 넷인데 자연 단절은 하나뿐인 경우. 없는 경계를 지어내지 않는다.
        val axis = scores(
            Triple("a", 1800, 5), Triple("b", 1795, 5), Triple("c", 1000, 5), Triple("d", 998, 5)
        )
        val suggestion = DuelGradeAssign.suggestCuts(axis, labels4)
        assertEquals(1, suggestion.moved)
        assertEquals(3, suggestion.requested)
        assertTrue(DuelGradeAssign.validate(suggestion.cuts, labels4).isEmpty())
    }

    @Test
    fun `suggestCuts - 제안은 언제나 검증을 통과한다`() {
        // [경계 제안]을 누른 죄로 오류 문구를 보게 해서는 안 된다.
        val axis = scores(
            Triple("a", 2000, 5), Triple("b", 1900, 5), Triple("c", 1400, 5),
            Triple("d", 1390, 5), Triple("e", 900, 5)
        )
        val suggestion = DuelGradeAssign.suggestCuts(axis, labels4)
        assertTrue(DuelGradeAssign.validate(suggestion.cuts, labels4).isEmpty())
    }

    @Test
    fun `suggestCuts - 인원이 둘 미만이면 균등 분할 그대로다`() {
        assertEquals(
            DuelGradeAssign.evenCuts(labels4),
            DuelGradeAssign.suggestCuts(scores(Triple("a", 1500, 0)), labels4).cuts
        )
    }

    // ── 표가 바뀐 뒤의 재조정 ──

    @Test
    fun `reconcile - 바뀐 것이 없으면 그대로다`() {
        val even = DuelGradeAssign.evenCuts(labels4)
        val result = DuelGradeAssign.reconcile(even, labels4, labels4)
        assertEquals(even, result.cuts)
        assertFalse(result.changed)
    }

    @Test
    fun `reconcile - 가운데 등급을 지우면 그 구간이 다음 등급에 합쳐진다`() {
        // 설계 4-2 — 누적 표현이라 컷을 지우는 것만으로 합쳐진다. A가 갖던 (5,20]은
        // B에게 가고, B는 (5,60]이 된다. 다른 캐릭터의 등급은 그대로다.
        val before = cuts("S" to 5.0, "A" to 20.0, "B" to 60.0)
        val result = DuelGradeAssign.reconcile(before, labels4, listOf("S", "B", "C"))
        assertEquals(cuts("S" to 5.0, "B" to 60.0), result.cuts)
        assertEquals(listOf("A"), result.merged)
        assertTrue(result.added.isEmpty())
    }

    @Test
    fun `reconcile - 마지막 등급을 지우면 위 등급이 나머지를 흡수한다`() {
        val before = cuts("S" to 5.0, "A" to 20.0, "B" to 60.0)
        val result = DuelGradeAssign.reconcile(before, labels4, listOf("S", "A", "B"))
        assertEquals(cuts("S" to 5.0, "A" to 20.0), result.cuts)
    }

    @Test
    fun `reconcile - 새 등급은 0퍼센트 구간으로 들어온다 (아무도 등급이 바뀌지 않는다)`() {
        // 등급을 하나 더했다는 이유로 캐릭터들의 등급이 조용히 바뀌면 변수 제어 위반이다.
        val before = cuts("S" to 5.0, "A" to 20.0, "B" to 60.0)
        val result = DuelGradeAssign.reconcile(before, labels4, listOf("S", "새등급", "A", "B", "C"))
        assertEquals(cuts("S" to 5.0, "새등급" to 5.0, "A" to 20.0, "B" to 60.0), result.cuts)
        assertEquals(listOf("새등급"), result.added)
    }

    @Test
    fun `reconcile - 맨 위에 등급이 붙어도 기존 배정은 그대로다`() {
        val before = cuts("S" to 5.0, "A" to 20.0, "B" to 60.0)
        val result = DuelGradeAssign.reconcile(before, labels4, listOf("초월", "S", "A", "B", "C"))
        assertEquals(cuts("초월" to 0.0, "S" to 5.0, "A" to 20.0, "B" to 60.0), result.cuts)
    }

    @Test
    fun `reconcile - 맨 아래에 등급이 붙으면 옛 마지막 등급이 100퍼센트를 받는다`() {
        // 이 한 자리가 [oldLabels]를 받는 이유다. 앞 등급의 값을 물려주면 C가 통째로 비고
        // 새 등급이 C의 사람들을 가져간다 — 사용자는 등급 하나를 더했을 뿐이다.
        val before = cuts("S" to 5.0, "A" to 20.0, "B" to 60.0)
        val result = DuelGradeAssign.reconcile(before, labels4, labels4 + "바닥")
        assertEquals(cuts("S" to 5.0, "A" to 20.0, "B" to 60.0, "C" to 100.0), result.cuts)
        assertTrue("옛 마지막 등급은 '새로 생긴 것'이 아니다", result.added.isEmpty())
    }

    @Test
    fun `reconcile - 어긋난 값도 단조 비감소로 바로잡는다`() {
        // 손으로 고친 엑셀 JSON에서 오는 모양. 재조정을 거친 컷은 언제나 검증을 통과한다.
        val broken = cuts("S" to 80.0, "A" to 10.0, "B" to 150.0)
        val result = DuelGradeAssign.reconcile(broken, labels4, labels4)
        assertTrue(DuelGradeAssign.validate(result.cuts, labels4).isEmpty())
        assertEquals(listOf(80.0, 80.0, 100.0), result.cuts.map { it.topPercent })
    }

    @Test
    fun `reconcile - 등급이 하나만 남으면 컷이 사라진다`() {
        val result = DuelGradeAssign.reconcile(DuelGradeAssign.evenCuts(labels4), labels4, listOf("S"))
        assertTrue(result.cuts.isEmpty())
        assertEquals(listOf("S", "A", "B"), result.merged)
    }

    // ── 미리보기 ──

    private fun assignment(code: String, label: String) =
        DuelGradeAssign.Assignment(code, label, 10.0, false)

    @Test
    fun `preview - 빈 칸·지난 반영값·손값으로 갈린다`() {
        val assignments = listOf(
            assignment("empty", "S"), assignment("reapply", "S"), assignment("manual", "S")
        )
        val current = mapOf("empty" to "", "reapply" to "A", "manual" to "SSS")
        val lastApplied = DuelGradeRef.LastApplied(1L, mapOf("reapply" to "A", "manual" to "B"))

        val preview = DuelGradeAssign.preview(assignments, current, lastApplied)
        assertEquals(listOf("empty"), preview.rowsOf(DuelGradeAssign.Bucket.EMPTY).map { it.code })
        assertEquals(listOf("reapply"), preview.rowsOf(DuelGradeAssign.Bucket.REAPPLY).map { it.code })
        // manual의 현재 값 SSS는 직전 반영이 쓴 B가 아니다 — 그 사이에 사용자가 고친 것이다.
        assertEquals(listOf("manual"), preview.rowsOf(DuelGradeAssign.Bucket.MANUAL).map { it.code })
        assertEquals(listOf("empty", "reapply"), preview.defaultChecked.map { it.code })
    }

    @Test
    fun `preview - 값이 안 바뀌는 캐릭터는 줄이 아니라 수로 센다`() {
        val preview = DuelGradeAssign.preview(
            listOf(assignment("a", "S"), assignment("b", "A")),
            mapOf("a" to "S", "b" to ""),
            null
        )
        assertEquals(1, preview.unchanged)
        assertEquals(listOf("b"), preview.rows.map { it.code })
    }

    @Test
    fun `preview - 직전 배정표가 없으면 지난 반영값 묶음을 만들지 않는다`() {
        // 판별 근거가 없는데 이름을 붙이면 앱이 모르는 것을 아는 척하는 것이고, 그 오판은
        // 손값을 기본 체크된 채로 덮는 방향으로 틀린다.
        val assignments = listOf(assignment("x", "S"))
        val current = mapOf("x" to "A")

        val noTrace = DuelGradeAssign.preview(assignments, current, null)
        assertFalse(noTrace.traceable)
        assertEquals(listOf(DuelGradeAssign.Bucket.MANUAL), noTrace.rows.map { it.bucket })
        assertTrue(noTrace.defaultChecked.isEmpty())

        val omitted = DuelGradeAssign.preview(
            assignments, current, DuelGradeRef.LastApplied(1L, emptyMap(), omitted = true)
        )
        assertFalse(omitted.traceable)
        assertEquals(listOf(DuelGradeAssign.Bucket.MANUAL), omitted.rows.map { it.bucket })
    }

    @Test
    fun `preview - 빈 칸은 흔적이 없어도 기본 체크다`() {
        val preview = DuelGradeAssign.preview(listOf(assignment("x", "S")), mapOf("x" to ""), null)
        assertEquals(listOf(DuelGradeAssign.Bucket.EMPTY), preview.rows.map { it.bucket })
        assertEquals(1, preview.defaultChecked.size)
    }
}
