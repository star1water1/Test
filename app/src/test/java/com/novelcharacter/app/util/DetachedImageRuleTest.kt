package com.novelcharacter.app.util

import com.novelcharacter.app.util.DetachedImageRule.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 뗀 표식 판정의 계약(B-107 D2).
 *
 * 이 판정이 단일 소스인 이유는 붙는 자리가 셋이고 푸는 자리가 셋이기 때문이다 — 규칙을
 * 자리마다 적으면 "뗐다가 다시 붙였는데 서랍에 남아 있다"가 언제 생겨도 원인을 못 찾는다.
 * 그래서 **한 함수가 양방향을 다 내는가**를 여기서 못 박는다.
 */
class DetachedImageRuleTest {

    private fun c(path: String, code: String? = "c001") = Candidate(path, code)

    // ===== 불변식: 캐릭터가 쓰고 있으면 뗀 것이 아니다 =====

    @Test fun ownerless_getsMarked() {
        val d = DetachedImageRule.decide(listOf(c("/f/a.jpg")), emptySet())
        assertEquals(listOf("/f/a.jpg"), d.toMark.map { it.path })
        assertTrue(d.toClear.isEmpty())
    }

    @Test fun stillOwned_getsCleared() {
        val d = DetachedImageRule.decide(listOf(c("/f/a.jpg")), setOf("/f/a.jpg"))
        assertEquals(listOf("/f/a.jpg"), d.toClear)
        assertTrue(d.toMark.isEmpty())
    }

    /** 한 조작이 양방향을 함께 낸다 — 편집창에서 하나 빼고 하나 넣고 저장하는 경우다. */
    @Test fun oneCall_producesBothDirections() {
        val d = DetachedImageRule.decide(
            listOf(c("/f/removed.jpg"), c("/f/kept.jpg")),
            setOf("/f/kept.jpg")
        )
        assertEquals(listOf("/f/removed.jpg"), d.toMark.map { it.path })
        assertEquals(listOf("/f/kept.jpg"), d.toClear)
    }

    /** 붙일 것도 지울 것도 없으면 DB를 만지지 않는다(호출부의 단축 조건). */
    @Test fun emptyInput_isEmptyDecision() {
        assertTrue(DetachedImageRule.decide(emptyList(), emptySet()).isEmpty)
    }

    // ===== 출처(code) — 경로마다 다를 수 있다 =====

    /** 일괄 해제는 여러 캐릭터에 걸쳐 일어난다 — 하나로 뭉뚱그리면 남의 이름이 붙는다. */
    @Test fun eachPathKeepsItsOwnSourceCode() {
        val d = DetachedImageRule.decide(
            listOf(c("/f/a.jpg", "c001"), c("/f/b.jpg", "c002")),
            emptySet()
        )
        assertEquals(listOf("c001", "c002"), d.toMark.map { it.fromCode })
    }

    /** 출처를 모르면 null이다 — 지어내면 화면이 없는 캐릭터를 말한다. */
    @Test fun unknownSource_staysNull() {
        val d = DetachedImageRule.decide(listOf(c("/f/a.jpg", null)), emptySet())
        assertEquals(listOf<String?>(null), d.toMark.map { it.fromCode })
    }

    /** 칸의 뜻이 "마지막으로 뗀 캐릭터"이므로 같은 파일이 겹치면 뒤엣것이 이긴다. */
    @Test fun duplicatePath_lastSourceWins() {
        val d = DetachedImageRule.decide(
            listOf(c("/f/a.jpg", "c001"), c("/f/a.jpg", "c002")),
            emptySet()
        )
        assertEquals(1, d.toMark.size)
        assertEquals("c002", d.toMark[0].fromCode)
    }

    // ===== 경로 대조 =====

    /** 빈 경로는 "지정 없음"이라 어떤 파일도 가리키지 않는다 — 표식의 대상이 아니다. */
    @Test fun blankPaths_areIgnored() {
        val d = DetachedImageRule.decide(listOf(c(""), c("   ")), emptySet())
        assertTrue(d.isEmpty)
    }

    /**
     * 소유 집합은 canonical로 들어온다. 후보가 상대 경로꼴이어도 같은 파일이면 소유로 잡혀야
     * 하고, 아니면 이미 쓰고 있는 이미지에 뗀 표식이 붙는다.
     */
    @Test fun ownershipMatchIsCanonical() {
        val raw = "/f/./sub/../a.jpg"
        val d = DetachedImageRule.decide(listOf(c(raw)), setOf(ImagePathMatch.canonical(raw)))
        assertEquals(listOf(raw), d.toClear)
    }

    /** 지울 목록은 **호출부가 넘긴 저장형 그대로** 돌려준다(DB 갱신이 정확 일치로 돌기 때문). */
    @Test fun decisionKeepsCallerPathForm() {
        val stored = "/f/./a.jpg"
        val d = DetachedImageRule.decide(listOf(c(stored)), emptySet())
        assertEquals(stored, d.toMark[0].path)
    }
}
