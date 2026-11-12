package com.novelcharacter.app.util

import com.novelcharacter.app.util.SemanticYearEditPrecedence.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** `__birth`·`__death` 연도만 고친 파일의 처분 (S11 3-ㅇ-14 — 고쳤다고 말하고 도로 덮이던 자리). */
class SemanticYearEditPrecedenceTest {

    @Test
    fun `필드가 옛 연도 그대로면 이력의 편집을 필드로 옮긴다`() {
        assertEquals(Decision.APPLY_STATE, SemanticYearEditPrecedence.decide(970, 980, "970"))
        assertEquals(Decision.APPLY_STATE, SemanticYearEditPrecedence.decide(970, 980, " 970 "))
    }

    @Test
    fun `필드가 비었으면 이력의 편집을 필드로 옮긴다`() {
        assertEquals(Decision.APPLY_STATE, SemanticYearEditPrecedence.decide(970, 980, null))
        assertEquals(Decision.APPLY_STATE, SemanticYearEditPrecedence.decide(970, 980, ""))
    }

    @Test
    fun `필드가 이미 새 연도면 맞는다`() {
        assertEquals(Decision.CONSISTENT, SemanticYearEditPrecedence.decide(970, 980, "980"))
    }

    @Test
    fun `필드가 둘 다와 다르면 필드를 따르되 충돌이다`() {
        assertEquals(Decision.FIELD_WINS_CONFLICT, SemanticYearEditPrecedence.decide(970, 980, "990"))
    }

    @Test
    fun `연도로 읽을 수 없는 필드값은 연도를 말하지 않으므로 이력을 옮긴다`() {
        // 이긴다고 하면 뒤 동기화가 그 칸을 건너뛰고 나이에서 옛 연도를 역산한다(PR #436 리뷰).
        assertEquals(Decision.APPLY_STATE, SemanticYearEditPrecedence.decide(970, 980, "약 970"))
    }

    @Test
    fun `모름(0)으로 고친 편집도 같은 규칙이다`() {
        assertEquals(Decision.APPLY_STATE, SemanticYearEditPrecedence.decide(970, 0, "970"))
        assertEquals(Decision.FIELD_WINS_CONFLICT, SemanticYearEditPrecedence.decide(970, 0, "990"))
    }
}
