package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelImageFit] 하네스 — **얼굴이 잘리지 않는가**를 숫자로 잰다.
 *
 * 사용자 지적이 *"세로로 긴 사진에서 얼굴이 잘린다"*였고, 그 증상은 화면에서만 보이며
 * 컴파일도 정적 검사도 통과한다(「세션 착수 규칙」 4번). 그래서 **위쪽이 남는가**를
 * 실행으로 고정한다 — 이 계약이 깨지면 여기서 먼저 깨진다.
 */
class DuelImageFitTest {

    /** 가로로 넓은 카드 + 세로로 긴 그림 = 사용자가 겪은 바로 그 조합. */
    @Test
    fun `wide card with tall image keeps the top`() {
        val crop = DuelImageFit.topCrop(viewWidth = 1080, viewHeight = 500, bitmapWidth = 2048, bitmapHeight = 3072)!!

        // 폭을 채우는 배율이라 세로가 넘친다.
        assertEquals(1080f / 2048f, crop.scale, 0.0001f)
        assertTrue("세로가 넘쳐야 한다", 3072 * crop.scale > 500f)
        // 넘친 세로는 **아래쪽만** 잘린다 — 위가 0이라 얼굴이 남는다.
        assertEquals(0f, crop.dy, 0.0001f)
        // 가로는 딱 맞으므로 옮길 것이 없다.
        assertEquals(0f, crop.dx, 0.0001f)
    }

    /** 나란히 놓은 좁고 긴 카드 — 이번에는 가로가 넘치고, 인물은 가로로 가운데에 있으므로 가운데를 남긴다. */
    @Test
    fun `narrow card with tall image centers horizontally`() {
        val crop = DuelImageFit.topCrop(viewWidth = 400, viewHeight = 1400, bitmapWidth = 2048, bitmapHeight = 3072)!!

        assertEquals(1400f / 3072f, crop.scale, 0.0001f)
        val scaledWidth = 2048 * crop.scale
        assertTrue("가로가 넘쳐야 한다", scaledWidth > 400f)
        assertEquals((400f - scaledWidth) / 2f, crop.dx, 0.0001f)
        assertEquals(0f, crop.dy, 0.0001f)
    }

    /** 카드와 그림의 비율이 같으면 아무것도 잘리지 않는다. */
    @Test
    fun `matching aspect crops nothing`() {
        val crop = DuelImageFit.topCrop(viewWidth = 600, viewHeight = 900, bitmapWidth = 200, bitmapHeight = 300)!!

        assertEquals(3f, crop.scale, 0.0001f)
        assertEquals(0f, crop.dx, 0.0001f)
        assertEquals(0f, crop.dy, 0.0001f)
    }

    /** 아직 재지 않은 화면(폭·높이 0)에서는 계산할 것이 없다 — 0으로 나누지 않는다. */
    @Test
    fun `unmeasured view yields nothing`() {
        assertNull(DuelImageFit.topCrop(0, 500, 100, 100))
        assertNull(DuelImageFit.topCrop(500, 0, 100, 100))
        assertNull(DuelImageFit.topCrop(500, 500, 0, 100))
        assertNull(DuelImageFit.topCrop(500, 500, 100, 0))
    }

    /** 저장된 설정이 깨져 있어도 화면은 뜬다 — 모르는 값은 기본값으로 읽는다. */
    @Test
    fun `unknown stored values fall back to defaults`() {
        assertEquals(DuelImageFit.Layout.SIDE_BY_SIDE, DuelImageFit.Layout.of(null))
        assertEquals(DuelImageFit.Layout.SIDE_BY_SIDE, DuelImageFit.Layout.of("무엇인가"))
        assertEquals(DuelImageFit.Layout.STACKED, DuelImageFit.Layout.of("STACKED"))
        assertEquals(DuelImageFit.Fit.FILL_TOP, DuelImageFit.Fit.of(null))
        assertEquals(DuelImageFit.Fit.FILL_TOP, DuelImageFit.Fit.of(""))
        assertEquals(DuelImageFit.Fit.WHOLE, DuelImageFit.Fit.of("WHOLE"))
    }
}
