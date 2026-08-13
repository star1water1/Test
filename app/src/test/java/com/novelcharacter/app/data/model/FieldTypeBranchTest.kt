package com.novelcharacter.app.data.model

import com.novelcharacter.app.util.FieldTypeCompatibility
import com.novelcharacter.app.util.FieldValueSorter
import com.novelcharacter.app.util.FieldValueTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 타입 분기를 enum으로 좁힌 것의 계약 (B-55, 2026.08.13).
 *
 * ## 여기서 재는 것과 **못 재는 것**
 *
 * 이 판의 본체는 *"새 타입이 화면에서 조용히 빠지지 않는다"*인데, **그것은 시험으로 잴 수 없다** —
 * 타입을 하나 더해 보는 시험은 `FieldType`에 상수를 더해야 하고 그러면 그 시험 자신이 컴파일되지
 * 않는다. 그 보장을 실제로 세우는 것은 **컴파일러의 exhaustive 검사**이고, 이 파일은 그 검사가
 * *걸려 있는지*를 간접으로만 잰다(아래 ③).
 *
 * 그래서 여기서 재는 것은 셋이다:
 * ① **경계가 실제로 좁혀지는가** — 저장 글자 ↔ enum 왕복.
 * ② **모르는 타입의 처분** — 이 판이 새로 만든 상태다(종전 `else` 가지가 답하던 자리).
 * ③ **한 벌이어야 하는 판정이 한 벌인가** — 세 벌이던 "수를 내는가"가 이제 하나다.
 *
 * **못 재는 것을 적어 두는 이유:** 이 파일을 *"새 타입이 안 빠진다"*의 보장으로 읽으면
 * 다음 사람이 컴파일 경고를 끄고도 초록을 믿는다. 울타리(`tools/check_field_type_branch.sh`)와
 * 컴파일러가 본체이고 여기는 그 둘이 다루지 않는 값의 계약만 잡는다.
 */
class FieldTypeBranchTest {

    private fun fd(type: String, config: String = "{}") =
        FieldDefinition(universeId = 1L, key = "k", name = "n", type = type, config = config)

    // ===== ① 경계가 좁혀지는가 =====

    @Test
    fun `저장 글자가 enum으로 좁혀진다`() {
        for (t in FieldType.entries) {
            assertEquals("${t.name} 왕복", t, fd(t.name).fieldType)
        }
    }

    @Test
    fun `fromName은 모든 상수를 찾는다 — 표로 바꾼 뒤에도`() {
        // 선형 훑기를 표로 바꾼 것이 이 판의 성능 몫이라, 표가 한 칸이라도 비면
        // 그 타입만 앱 전체에서 '모르는 타입'이 된다. 조용한 실패라 여기서 전수로 잡는다.
        for (t in FieldType.entries) {
            assertEquals(t, FieldType.fromName(t.name))
        }
        assertEquals(FieldType.entries.size, FieldType.entries.map { it.name }.toSet().size)
    }

    // ===== ② 모르는 타입의 처분 — 이 판이 만든 상태다 =====

    @Test
    fun `모르는 타입은 null이고 어느 분기에도 조용히 들지 않는다`() {
        // 월드패키지·프리셋 JSON·손으로 고친 DB는 '필드 정의' 시트의 관문을 안 지난다.
        val alien = fd("QUANTUM")
        assertNull(alien.fieldType)
        assertFalse("수로 읽지 않는다", FieldValueSorter.isNumericSortType(alien.fieldType))
        assertNull("수치 비교키가 없다", FieldValueSorter.numericValue(alien, "3.5", null))
        assertFalse("값 카탈로그 대상이 아니다", FieldValueTokenizer.supportsLibrary(alien))
        assertFalse("서술형 대상이 아니다", NarrativeMode.isEligibleType(alien.fieldType))
    }

    @Test
    fun `모르는 타입의 값도 버리지 않고 그대로 들고 있는다`() {
        // 개발 의도 2번 — 거부가 아니라 수용. 분포·순위는 값을 **글자 그대로** 세므로
        // 모르는 타입에서도 거짓을 말하지 않는다.
        val alien = fd("QUANTUM")
        assertEquals(listOf("사과"), FieldValueTokenizer.tokenize(alien, "사과"))
        assertEquals(listOf("사과"), FieldValueTokenizer.splitForStats(alien, "사과"))
        assertEquals("사과", FieldValueSorter.textValue(alien, "  사과  "))
        assertEquals(
            "분석 방법은 값을 글자로 세는 둘만 열린다",
            listOf(FieldStatsConfig.StatsType.DISTRIBUTION, FieldStatsConfig.StatsType.RANKING),
            FieldStatsConfig.StatsType.forFieldType(alien.fieldType)
        )
    }

    // ===== ③ 한 벌이어야 하는 판정이 한 벌인가 =====

    @Test
    fun `수치 요약이 열리면 수치 정렬도 된다 — 역은 GRADE에서만 성립하지 않는다`() {
        // 종전에는 "이 타입이 수를 내는가"가 **세 벌**이었다(정렬 · 통계 순위 · 읽기 화면 백분위).
        // 셋이 우연히 같았을 뿐이고, 갈리면 목록에서 수로 줄 세워지는 필드가 통계에서는
        // 빈도로 세진다. 이제 [FieldValueSorter.isNumericSortType] 하나가 답한다.
        //
        // **그런데 분석 방법 표는 그것과 같은 물음이 아니다** — 이 시험을 처음 '같은 답'으로
        // 적었다가 GRADE에서 걸렸고, 걸린 것이 맞다:
        //
        // | | 줄을 세울 수 있는가(순서형) | 평균을 낼 수 있는가(등간형) |
        // |---|---|---|
        // | NUMBER · CALCULATED · BODY_SIZE | ○ | ○ |
        // | **GRADE** | ○ (C<B<A<S) | **✕** |
        // | TEXT · SELECT · MULTI_TEXT | ✕ | ✕ |
        //
        // 등급은 **순서형**이다. `S`와 `A`의 간격이 `B`와 `C`의 간격과 같다는 보장이 없어
        // *"평균 등급 2.3"*은 뜻이 없는 수다(그 수는 config의 임의 매핑이 정한다). 줄 세우기는
        // 그 간격을 안 쓰므로 성립한다. **그래서 한쪽으로만 함의가 흐른다.**
        //
        // 잠그는 것은 그 함의다 — 수치 요약을 새 타입에 열면서 정렬을 안 이어 주면 여기서 걸린다
        // (그 조합은 화면에 *"평균은 내는데 순위표에서는 빈도로 세는"* 모양으로 나타난다).
        for (t in FieldType.entries) {
            val numericOpens = FieldStatsConfig.StatsType.NUMERIC in
                FieldStatsConfig.StatsType.forFieldType(t)
            if (numericOpens) {
                assertTrue(
                    "$t — 수치 요약은 여는데 수치 정렬 대상이 아니다",
                    FieldValueSorter.isNumericSortType(t)
                )
            }
        }
        // 역이 깨지는 자리는 지금 GRADE 하나뿐이다. 하나 더 생기면 위 표를 다시 적어야 하므로
        // 그 사실 자체를 센다 — 조용히 둘이 되면 표가 낡은 채로 남는다.
        val rankableButNotAveragable = FieldType.entries.filter {
            FieldValueSorter.isNumericSortType(it) &&
                FieldStatsConfig.StatsType.NUMERIC !in FieldStatsConfig.StatsType.forFieldType(it)
        }
        assertEquals(listOf(FieldType.GRADE), rankableButNotAveragable)
    }

    @Test
    fun `등급은 정수가 아니라 라벨이다 — 미래형 판정에만 정수를 묻는다`() {
        // B-156이 이미 한 번 겪은 자리다. `isValueCompatible`은 *"저 타입으로 바꾸면
        // 살아남는가"*(미래형)라 등급을 정수로 보는데, **현재형**(지금 제 타입으로 읽히는가)에
        // 그 잣대를 쓰면 정상 등급 값 전부가 불일치가 된다.
        val grade = fd(FieldType.GRADE.name, """{"grades":{"C":1,"B":2,"A":3,"S":4}}""")
        assertFalse("미래형: 라벨 S는 정수가 아니다", FieldTypeCompatibility.isValueCompatible("S", FieldType.GRADE))
        assertNull("현재형: 라벨 S는 멀쩡한 등급 값이다", com.novelcharacter.app.util.FieldValueTypeMismatch.reasonFor(grade, "S"))
        assertNotNull("현재형: 표에 없는 라벨만 걸린다", com.novelcharacter.app.util.FieldValueTypeMismatch.reasonFor(grade, "Z"))
    }

    @Test
    fun `모르는 타입으로의 변경은 값이 살아남는다고 답한다 — 그러나 그 답은 이제 명시적이다`() {
        // 종전 `else -> true`가 이 자리를 덮고 있었고 **방향이 나빴다**: 새 타입을 더하면
        // 아무 말 없이 "값이 전부 살아남는다"고 답했다. 이제 `when`이 전부를 덮으므로
        // 새 타입은 그 함수에서 컴파일을 깨고 답을 받아 간다 — 값은 같아도 성격이 다르다.
        assertTrue(FieldTypeCompatibility.isValueCompatible("아무거나", null))
        assertEquals(0, FieldTypeCompatibility.incompatibleCount(listOf("a", "b"), null))
    }

    // ===== 곁: 목록이 어댑터에 넘어가도 안전한가 =====

    @Test
    fun `labels는 부를 때마다 새 목록이다`() {
        // ArrayAdapter는 넘겨받은 목록을 제 것으로 여기고 고친다(add/clear). 한 벌을 돌려쓰면
        // 어댑터 하나가 나머지 화면의 타입 목록을 함께 지운다 — 캐시하려는 유혹이 바로 그 자리다.
        val a = FieldType.labels()
        val b = FieldType.labels()
        assertEquals(a, b)
        assertFalse("같은 인스턴스를 돌려쓰면 안 된다", a === b)
        assertEquals(FieldType.entries.size, a.size)
    }
}
