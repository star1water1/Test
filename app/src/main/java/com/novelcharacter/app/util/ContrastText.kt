package com.novelcharacter.app.util

/**
 * 색 바탕 위 글자색 고르기 — **배경을 보고 어두운 글자와 밝은 글자 중 대비가 큰 쪽**을 준다.
 *
 * 배지·파이 조각처럼 바탕이 데이터 색(등급·완성도·검색 유형·차트 팔레트)인 자리는 그 색이
 * 테마마다, 값마다 바뀐다. 그런 자리에 흰 글자나 `on_primary`를 고정하면 어느 한 테마에서
 * 반드시 안 읽힌다(실측: 라이트의 `accent` 위 흰 글자 2.7:1, 다크의 차트 팔레트 위 흰 글자
 * 1.5~3.5:1). 그래서 글자색을 바탕에서 **계산한다.**
 *
 * 판정은 WCAG 2 상대 휘도·대비비다. 순수 로직(JVM 시험이 잠근다) — 색은 ARGB `Int`로 받는다.
 * 알파는 무시하고 RGB만 본다(반투명 바탕은 그 색이 불투명하다고 보고 고른다).
 */
object ContrastText {

    /** 어두운 글자 — `on_surface`(라이트)와 같은 값이다. */
    const val DARK: Int = 0xFF191C20.toInt()

    /** 밝은 글자. */
    const val LIGHT: Int = 0xFFFFFFFF.toInt()

    /** [background] 위에서 [DARK]·[LIGHT] 중 대비가 큰 쪽. 같으면 어두운 쪽이다. */
    fun on(background: Int): Int =
        if (contrast(DARK, background) >= contrast(LIGHT, background)) DARK else LIGHT

    /** 두 색의 WCAG 대비비(1.0~21.0). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG 상대 휘도(0.0~1.0). */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
