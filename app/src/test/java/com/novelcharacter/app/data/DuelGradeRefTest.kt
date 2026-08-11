package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.DuelGradeRef
import com.novelcharacter.app.excel.EXCEL_CELL_TEXT_LIMIT
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 등급 산정 속성(B-113)의 config 표현 계약 — [DuelGradeRef]가 단일 소스다.
 *
 * 지키는 것 넷:
 *
 * 1. **반쯤 살아 있는 상태를 만들지 않는다** — 축 code 없는 약속은 통째로 없는 것으로 읽는다.
 * 2. **수술적 편집** — 자기 키만 만지고 다른 config 키를 보존한다(등급 표·재정의가 옆에 산다).
 * 3. **강등은 흔적을 남기지 않는다** — 세계관 경계를 넘는 복사에서 통째로 걷어낸다.
 *    등급 체계 참조와 달리 남길 잔여가 없다(다른 세계관에는 그 순위 자체가 없다).
 * 4. **빈 배정과 생략된 배정은 다르다** — 전자는 "그때 아무에게도 안 썼다", 후자는 "무엇을
 *    썼는지 모른다"이고, 미리보기의 기본 체크가 그 구분 위에 선다(Q1).
 */
class DuelGradeRefTest {

    private val cuts = listOf(
        DuelGradeRef.Cut("S", 5.0),
        DuelGradeRef.Cut("A", 20.0),
        DuelGradeRef.Cut("B", 60.0)
    )

    // ── 읽기 ──

    @Test
    fun `fromConfig - 키 없음·손상 JSON은 null`() {
        assertNull(DuelGradeRef.fromConfig("{}"))
        assertNull(DuelGradeRef.fromConfig("not json"))
        assertNull(DuelGradeRef.fromConfig(""))
    }

    @Test
    fun `fromConfig - 축 code가 비면 컷이 있어도 통째로 없는 것이다`() {
        // 어느 순위를 나눌지 말하지 않는 약속은 실행할 수 없다. 반쯤 살려 두면 화면이
        // 그것을 켜진 것처럼 그린다.
        val config = """{"duelGrade":{"axisCode":"","cuts":[{"label":"S","topPercent":5}]}}"""
        assertNull(DuelGradeRef.fromConfig(config))
    }

    @Test
    fun `fromConfig - 라벨이 비거나 수치가 아닌 컷 항목은 건너뛴다`() {
        val config = """
            {"duelGrade":{"axisCode":"ax1","cuts":[
              {"label":"S","topPercent":5},
              {"label":"","topPercent":10},
              {"label":"A","topPercent":"abc"},
              {"label":"B","topPercent":"60"}
            ]}}
        """.trimIndent()
        val spec = DuelGradeRef.fromConfig(config)!!
        assertEquals(listOf("S", "B"), spec.cuts.map { it.label })
        // 숫자 문자열은 해석한다 — 손으로 고친 엑셀 JSON이 자주 그 모양이다.
        assertEquals(60.0, spec.cuts[1].topPercent, 0.0001)
    }

    // ── 쓰기 ──

    @Test
    fun `write - 왕복하면 같은 약속이 나온다`() {
        val spec = DuelGradeRef.Spec("ax1", cuts, DuelGradeRef.LastApplied(1234L, mapOf("CH-1" to "S")))
        val read = DuelGradeRef.fromConfig(DuelGradeRef.write("{}", spec))!!
        assertEquals("ax1", read.axisCode)
        assertEquals(cuts, read.cuts)
        assertEquals(1234L, read.lastApplied!!.at)
        assertEquals(mapOf("CH-1" to "S"), read.lastApplied!!.assignments)
        assertFalse(read.lastApplied!!.omitted)
    }

    @Test
    fun `write - 다른 config 키를 보존한다`() {
        val config = """{"grades":{"C":0.5,"S":3},"gradeSystem":"gs01","required":true}"""
        val next = DuelGradeRef.write(config, DuelGradeRef.Spec("ax1", cuts))
        val json = JSONObject(next)
        assertEquals("gs01", json.getString("gradeSystem"))
        assertTrue(json.getBoolean("required"))
        assertEquals(3.0, json.getJSONObject("grades").getDouble("S"), 0.0001)
        assertEquals("ax1", DuelGradeRef.fromConfig(next)!!.axisCode)
    }

    @Test
    fun `write - 손상 JSON은 원문 그대로 (다른 키까지 파괴하지 않는다)`() {
        assertEquals("not json", DuelGradeRef.write("not json", DuelGradeRef.Spec("ax1", cuts)))
    }

    @Test
    fun `write - 빈 배정과 생략된 배정이 되읽을 때 갈린다`() {
        val empty = DuelGradeRef.write("{}", DuelGradeRef.Spec("ax1", cuts, DuelGradeRef.LastApplied(1L, emptyMap())))
        assertFalse(DuelGradeRef.fromConfig(empty)!!.lastApplied!!.omitted)

        val omitted = DuelGradeRef.write(
            "{}", DuelGradeRef.Spec("ax1", cuts, DuelGradeRef.LastApplied(1L, emptyMap(), omitted = true))
        )
        assertTrue(DuelGradeRef.fromConfig(omitted)!!.lastApplied!!.omitted)
    }

    // ── 한도 ──

    @Test
    fun `write - 한도를 넘기면 배정표만 버리고 시각은 남긴다`() {
        // 인원이 많으면 배정표가 config를 삼킨다. 흔적을 통째로 버리면 "언제 반영했는가"까지
        // 잃으므로 가장 큰 조각만 버린다.
        val many = (1..4000).associate { "CHARACTER-CODE-$it" to "SSS" }
        val next = DuelGradeRef.write("{}", DuelGradeRef.Spec("ax1", cuts, DuelGradeRef.LastApplied(99L, many)))
        assertTrue(next.length <= DuelGradeRef.MAX_CONFIG_CHARS)
        val read = DuelGradeRef.fromConfig(next)!!
        assertEquals(99L, read.lastApplied!!.at)
        assertTrue(read.lastApplied!!.omitted)
        assertTrue(read.lastApplied!!.assignments.isEmpty())
        // 약속 자체(축·컷)는 한도와 무관하게 살아남는다 — 그것을 잃으면 기능이 꺼진다.
        assertEquals("ax1", read.axisCode)
        assertEquals(cuts, read.cuts)
    }

    @Test
    fun `한도는 엑셀 셀 상한 아래에 여유를 두고 있다`() {
        // 이 관계가 한도의 존재 이유다. '필드 정의' 시트는 config를 셀 하나에 싣고, 내보내기는
        // 상한을 넘는 문자열을 **자른다**(ExcelExporter.setTextSafe). 잘린 config는 JSON으로
        // 깨져 돌아와 등급 표·재정의까지 함께 잃는다. 어느 쪽 상수가 바뀌어도 여기서 걸린다.
        assertTrue(
            "config 예산이 엑셀 셀 상한을 넘어섰다",
            DuelGradeRef.MAX_CONFIG_CHARS < EXCEL_CELL_TEXT_LIMIT
        )
        assertTrue(
            "config에는 실효 표·재정의도 함께 산다 — 셀 상한의 절반 아래로 둘 것",
            DuelGradeRef.MAX_CONFIG_CHARS <= EXCEL_CELL_TEXT_LIMIT / 2
        )
    }

    // ── 강등 ──

    @Test
    fun `remove - 속성만 걷어내고 나머지는 그대로 둔다`() {
        val config = DuelGradeRef.write("""{"grades":{"S":3},"gradeSystem":"gs01"}""", DuelGradeRef.Spec("ax1", cuts))
        val demoted = DuelGradeRef.remove(config)
        assertNull(DuelGradeRef.fromConfig(demoted))
        val json = JSONObject(demoted)
        assertEquals("gs01", json.getString("gradeSystem"))
        assertEquals(3.0, json.getJSONObject("grades").getDouble("S"), 0.0001)
    }

    @Test
    fun `remove - 지울 키가 없으면 원문 문자열을 그대로 돌려준다`() {
        // 재직렬화는 키 순서를 흔들어 무관한 config까지 "바뀐 것"으로 만든다 — 프리셋 왕복·
        // 전파의 변경 감지가 그 문자열 비교 위에 있다(GradeSystemRef.demote와 같은 규칙).
        val config = """{"grades":{"S":3},"gradeSystem":"gs01"}"""
        assertSame(config, DuelGradeRef.remove(config))
        assertSame("not json", DuelGradeRef.remove("not json"))
    }

    // ── 등급 체계 편집 추종 ──

    @Test
    fun `renameCuts - 라벨 개명을 따라간다`() {
        val renamed = DuelGradeRef.renameCuts(cuts, mapOf("S" to "SS", "B" to "b"))
        assertEquals(listOf("SS", "A", "b"), renamed.map { it.label })
        // 비율은 건드리지 않는다 — 이름만 바뀐 것이다.
        assertEquals(listOf(5.0, 20.0, 60.0), renamed.map { it.topPercent })
        assertEquals(cuts, DuelGradeRef.renameCuts(cuts, emptyMap()))
    }

    @Test
    fun `axisCodeFromConfig - 축만 묻는 자리의 지름길`() {
        val config = DuelGradeRef.write("{}", DuelGradeRef.Spec("ax7", cuts))
        assertEquals("ax7", DuelGradeRef.axisCodeFromConfig(config))
        assertNull(DuelGradeRef.axisCodeFromConfig("{}"))
    }

    // ── 코드 재발급 추종 (B-118 — 월드패키지가 축을 함께 담게 된 뒤) ──

    @Test
    fun `remapCodes - 축과 배정 캐릭터를 함께 옮긴다`() {
        // **둘이 한 몸인 것이 요점이다.** 축만 옮기고 배정 키를 두면 재발급으로 비게 된 옛
        // 코드가 이 기기의 다른 캐릭터를 가리키고, 다음 반영이 그 남의 손값을 "직전에 내가 쓴
        // 값"으로 판정해 **기본 체크된 채로 덮는다**(가장 나쁜 방향이다).
        val config = DuelGradeRef.write(
            """{"grades":{"S":3}}""",
            DuelGradeRef.Spec("ax1", cuts, DuelGradeRef.LastApplied(9L, mapOf("CH-1" to "S", "CH-2" to "A")))
        )
        val moved = DuelGradeRef.remapCodes(
            config,
            axisCodeRemap = mapOf("ax1" to "ax9"),
            characterCodeRemap = mapOf("CH-1" to "CH-NEW", "CH-2" to "CH-2")
        )
        val spec = DuelGradeRef.fromConfig(moved)!!
        assertEquals("ax9", spec.axisCode)
        assertEquals(mapOf("CH-NEW" to "S", "CH-2" to "A"), spec.lastApplied!!.assignments)
        assertEquals(9L, spec.lastApplied!!.at)
        assertEquals(cuts, spec.cuts)
        // 수술적 편집 — 옆에 사는 키는 그대로다
        assertEquals(3, JSONObject(moved).getJSONObject("grades").getInt("S"))
    }

    @Test
    fun `remapCodes - 표에 없는 배정 키는 버린다`() {
        // 그 캐릭터는 이 패키지에 오지 않았으므로 흔적의 주체가 없다. 남기면 오배정이고,
        // 버리면 그 값들이 다음 반영에서 손값으로 다뤄진다(기본 해제 = 덮지 않는다).
        val config = DuelGradeRef.write(
            "{}",
            DuelGradeRef.Spec("ax1", cuts, DuelGradeRef.LastApplied(9L, mapOf("CH-1" to "S", "CH-GONE" to "A")))
        )
        val moved = DuelGradeRef.remapCodes(config, mapOf("ax1" to "ax1"), mapOf("CH-1" to "CH-1"))
        assertEquals(mapOf("CH-1" to "S"), DuelGradeRef.fromConfig(moved)!!.lastApplied!!.assignments)
        // 흔적 자체는 살아 있다 — 생략(omitted)과 혼동되면 미리보기가 3분류를 잘못 줄인다.
        assertFalse(DuelGradeRef.fromConfig(moved)!!.lastApplied!!.omitted)
    }

    @Test
    fun `remapCodes - 바뀔 것이 없으면 원문 문자열을 그대로 돌려준다`() {
        val config = DuelGradeRef.write("{}", DuelGradeRef.Spec("ax1", cuts))
        assertSame(config, DuelGradeRef.remapCodes(config, emptyMap(), emptyMap()))
        // 대결과 무관한 config·손상 JSON도 손대지 않는다
        val plain = """{"grades":{"S":3}}"""
        assertSame(plain, DuelGradeRef.remapCodes(plain, mapOf("ax1" to "ax9"), emptyMap()))
        assertSame("not json", DuelGradeRef.remapCodes("not json", mapOf("ax1" to "ax9"), emptyMap()))
    }

    @Test
    fun `remapCodes - 표에 없는 축은 그대로 둔다 - 걷어내는 판단은 호출부의 몫이다`() {
        // 이 함수는 기계적으로 옮기기만 한다. *패키지에 함께 왔는가*를 보고 remap과 remove를
        // 가르는 것은 배선의 몫이고(월드패키지 임포터), 그 자리가 걷어낸 수를 센다.
        val config = DuelGradeRef.write("{}", DuelGradeRef.Spec("axOther", cuts))
        val moved = DuelGradeRef.remapCodes(config, mapOf("ax1" to "ax9"), emptyMap())
        assertEquals("axOther", DuelGradeRef.fromConfig(moved)!!.axisCode)
    }
}
