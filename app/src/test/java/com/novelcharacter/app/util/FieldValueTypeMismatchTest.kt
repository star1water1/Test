package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 타입 불일치 판정의 계약 (B-156).
 *
 * 이 시험이 지키는 것은 두 방향이다:
 * - **못 잡으면** 사용자가 고칠 길이 다시 없어진다(값이 수식에서 0으로 섞여 든다).
 * - **잘못 잡으면** 정상인 값 수백 개가 "틀렸다"고 뜬다 — 그러면 목록 자체를 아무도 안 본다.
 *   [FieldTypeCompatibility]를 그대로 재사용했을 때 실제로 그렇게 되며, 아래 등급·체형
 *   시험이 그 함정을 재현해 잠근다.
 */
class FieldValueTypeMismatchTest {

    private fun field(type: FieldType, config: String = "{}") = FieldDefinition(
        id = 1, universeId = 1, key = "k", name = "필드", type = type.name, config = config
    )

    private val gradeField = field(
        FieldType.GRADE, """{"grades":{"S":10,"A":8,"B":6},"allowNegative":false}"""
    )

    // ===== 숫자 =====

    @Test
    fun `숫자 필드에 수가 아닌 값이 남아 있으면 잡는다`() {
        assertEquals(
            FieldValueTypeMismatch.Reason.NOT_A_NUMBER,
            FieldValueTypeMismatch.reasonFor(field(FieldType.NUMBER), "매우 강함")
        )
    }

    @Test
    fun `숫자로 읽히면 잡지 않는다 — 소수와 음수도 수다`() {
        listOf("170", "170.5", "-3", "1e3").forEach {
            assertNull(it, FieldValueTypeMismatch.reasonFor(field(FieldType.NUMBER), it))
        }
    }

    /**
     * **위임 계약** — 전파 미리보기가 *"값 N개가 맞지 않게 됩니다"*라 센 그 값을 건강도가
     * 반드시 찾아야 한다. 두 규칙이 갈리면 사용자는 **고지받은 것을 고치러 갈 수 없다**(R-7).
     */
    @Test
    fun `숫자 판정은 전파가 세는 규칙과 글자 그대로 같다`() {
        listOf("170", "170.5", "-3", "abc", "1,000", " ", "12cm").forEach { v ->
            val mismatched = FieldValueTypeMismatch.reasonFor(field(FieldType.NUMBER), v) != null
            val incompatible = v.isNotBlank() &&
                !FieldTypeCompatibility.isValueCompatible(v, FieldType.NUMBER.name)
            assertEquals(v, incompatible, mismatched)
        }
    }

    // ===== 등급 =====

    @Test
    fun `등급 표에 없는 라벨은 잡는다`() {
        assertEquals(
            FieldValueTypeMismatch.Reason.UNKNOWN_GRADE,
            FieldValueTypeMismatch.reasonFor(gradeField, "짱셈")
        )
    }

    /**
     * 이 시험이 이 판의 방어선이다. [FieldTypeCompatibility]는 *"정수 텍스트만 등급으로
     * 살아남는다"*고 답하므로, 그것을 현재형에 그대로 쓰면 **정상 등급 값 전부가 걸린다.**
     */
    @Test
    fun `표에 있는 등급 라벨은 정수가 아니어도 잡지 않는다`() {
        listOf("S", "A", "B").forEach {
            assertNull(it, FieldValueTypeMismatch.reasonFor(gradeField, it))
        }
        // 되돌리기 방지: 전파의 판정이라면 셋 다 "안 맞는다"고 답한다.
        listOf("S", "A", "B").forEach {
            assertEquals(false, FieldTypeCompatibility.isValueCompatible(it, FieldType.GRADE.name))
        }
    }

    @Test
    fun `등급 표가 통째로 없는 필드는 그 값들이 전부 잡힌다 — 수식에서 0으로 읽히기 때문이다`() {
        assertEquals(
            FieldValueTypeMismatch.Reason.UNKNOWN_GRADE,
            FieldValueTypeMismatch.reasonFor(field(FieldType.GRADE), "S")
        )
    }

    // ===== 체형 =====

    @Test
    fun `체형 필드에 수치가 하나도 없으면 잡는다`() {
        assertEquals(
            FieldValueTypeMismatch.Reason.NOT_MEASUREMENTS,
            FieldValueTypeMismatch.reasonFor(field(FieldType.BODY_SIZE), "보통 체형")
        )
    }

    /**
     * 같은 함정의 반대편 — [FieldTypeCompatibility]는 체형에 **언제나 `false`**를 답한다.
     * 그대로 쓰면 정상 체형 값 전부가 걸린다.
     */
    @Test
    fun `파트 하나라도 수치면 잡지 않는다 — 부분 입력은 정상이다`() {
        listOf("86-60-88", "86 / 60 / 88", "170", "86-미상-88").forEach {
            assertNull(it, FieldValueTypeMismatch.reasonFor(field(FieldType.BODY_SIZE), it))
        }
        assertEquals(
            false,
            FieldTypeCompatibility.isValueCompatible("86-60-88", FieldType.BODY_SIZE.name)
        )
    }

    // ===== 세지 않는 것 =====

    @Test
    fun `빈 값은 어느 타입에서도 잡지 않는다`() {
        FieldType.entries.forEach { t ->
            assertNull(t.name, FieldValueTypeMismatch.reasonFor(field(t), "   "))
        }
    }

    @Test
    fun `글자를 담는 타입은 어떤 문자열도 잡지 않는다 — 선택지 밖 값은 보존하기로 확정한 것이다`() {
        listOf(FieldType.TEXT, FieldType.SELECT, FieldType.MULTI_TEXT).forEach { t ->
            assertNull(t.name, FieldValueTypeMismatch.reasonFor(field(t), "목록에 없는 값"))
        }
    }

    @Test
    fun `계산 필드의 저장값은 잡지 않는다 — 수식이 덮어 쓰므로 값의 잘못이 아니다`() {
        assertNull(FieldValueTypeMismatch.reasonFor(field(FieldType.CALCULATED), "옛 계산 결과"))
    }
}
