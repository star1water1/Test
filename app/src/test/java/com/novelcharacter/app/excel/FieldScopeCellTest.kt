package com.novelcharacter.app.excel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시트의 세계관 칸이 무엇을 뜻하는가 (B-119 확장 · B-130).
 *
 * **빈 칸은 '못 찾았다'가 아니라 '무소속'이라는 값이다.** 이 한 줄을 각 경로가 따로 적고
 * 있어서 갈렸다 — '필드 정의'는 알고 오버플로 두 시트는 몰라, 내보내기가 빈 칸으로 내보낸
 * 전역 값을 가져오기가 버렸다(B-130). 판정을 한 함수로 모았으므로 이 시험이 그 함수를 잠근다.
 */
class FieldScopeCellTest {

    /** 두 칸이 모두 비면 전역이다 — 이것이 이 파일 형식의 약속이다(설계 1-9). */
    @Test fun 이름과_코드가_모두_비면_전역이다() {
        assertTrue(FieldScopeCell.isGlobal("", ""))
        assertTrue("공백만 있는 칸도 빈 칸이다 — 손으로 편집한 파일에서 흔하다", FieldScopeCell.isGlobal("  ", " "))
    }

    /** 코드 열이 없는 시트('필드 데이터')는 이름만으로 판정한다 — 기본값이 그 자리다. */
    @Test fun 코드_열이_없는_시트는_이름만으로_판정한다() {
        assertTrue(FieldScopeCell.isGlobal(""))
        assertFalse(FieldScopeCell.isGlobal("이세계"))
    }

    /**
     * **이름이 비어도 코드가 있으면 전역이 아니다.**
     * 코드는 안정 식별자라 이름 칸을 지운 파일에서도 세계관이 해석된다(R-1) —
     * 그것을 전역으로 읽으면 멀쩡한 세계관 필드가 무소속 구역으로 넘어가고,
     * 그 순간 **그 세계관의 캐릭터에게서 필드가 사라진다.**
     */
    @Test fun 코드만_있으면_전역이_아니다() {
        assertFalse(FieldScopeCell.isGlobal("", "UNIV-7"))
    }

    /** 이름이 있으면 코드가 비어도 전역이 아니다 — 이름으로 해석되는 평범한 행이다. */
    @Test fun 이름만_있으면_전역이_아니다() {
        assertFalse(FieldScopeCell.isGlobal("이세계", ""))
    }

    /**
     * 고지 어휘도 한 곳에서 낸다 — 경고 문구가 경로마다 다르면
     * 사용자는 같은 일이 일어났다는 것을 알아볼 수 없다.
     */
    @Test fun 전역_구역의_이름은_한_곳에서_난다() {
        assertTrue(FieldScopeCell.GLOBAL_LABEL.contains("전역"))
        assertTrue(FieldScopeCell.GLOBAL_LABEL.contains("무소속"))
    }
}
