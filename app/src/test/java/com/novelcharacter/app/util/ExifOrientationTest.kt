package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXIF 방향 표의 계약 — **틀리기 쉬운 것은 손이 아니라 표다.**
 *
 * 90과 270을 뒤집으면 사진이 반대로 눕고, 반전을 겸하는 넷(2·4·5·7)에서 반전을 잃으면
 * 좌우가 뒤집힌다. 둘 다 화면을 봐야 알 수 있는 부류라 여기서 잠근다.
 */
class ExifOrientationTest {

    @Test fun normalAndUndefinedDoNothing() {
        assertTrue(ExifOrientation.transformOf(ExifOrientation.NORMAL).isIdentity)
        assertTrue(ExifOrientation.transformOf(ExifOrientation.UNDEFINED).isIdentity)
    }

    /** **모르는 값은 손대지 않는다** — 임의로 돌리면 멀쩡한 사진을 눕힌다. */
    @Test fun unknownValuesDoNothing() {
        for (v in listOf(-3, 9, 42, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertTrue("orientation=$v", ExifOrientation.transformOf(v).isIdentity)
        }
    }

    @Test fun theEightValuesMapToTheirTiffMeaning() {
        assertEquals(ExifOrientation.Transform(0, false), ExifOrientation.transformOf(1))
        assertEquals(ExifOrientation.Transform(0, true), ExifOrientation.transformOf(2))
        assertEquals(ExifOrientation.Transform(180, false), ExifOrientation.transformOf(3))
        assertEquals(ExifOrientation.Transform(180, true), ExifOrientation.transformOf(4))
        assertEquals(ExifOrientation.Transform(90, true), ExifOrientation.transformOf(5))
        assertEquals(ExifOrientation.Transform(90, false), ExifOrientation.transformOf(6))
        assertEquals(ExifOrientation.Transform(270, true), ExifOrientation.transformOf(7))
        assertEquals(ExifOrientation.Transform(270, false), ExifOrientation.transformOf(8))
    }

    /** 폰 카메라가 가장 흔히 쓰는 둘 — 여기가 뒤집히면 세로 사진이 반대로 눕는다. */
    @Test fun theTwoCommonCameraValuesRotateTheRightWay() {
        assertEquals(90, ExifOrientation.transformOf(6).degrees)
        assertEquals(270, ExifOrientation.transformOf(8).degrees)
        assertFalse(ExifOrientation.transformOf(6).mirrored)
        assertFalse(ExifOrientation.transformOf(8).mirrored)
    }

    @Test fun valueOfIsTheInverseOfTransformOf() {
        for (v in 1..8) {
            assertEquals(v, ExifOrientation.valueOf(ExifOrientation.transformOf(v)))
        }
    }

    /** 네 번 돌리면 제자리 — 인앱 회전이 값을 누적해도 표를 벗어나지 않는다. */
    @Test fun fourQuarterTurnsReturnToStart() {
        for (v in 1..8) {
            var cur = v
            repeat(4) { cur = ExifOrientation.rotatedBy(cur, 90) }
            assertEquals("orientation=$v", v, cur)
        }
    }

    /** 회전은 **좌우 반전을 보존한다** — 잃으면 돌리기만 했는데 좌우가 뒤집힌다. */
    @Test fun rotationPreservesMirroring() {
        for (v in listOf(2, 4, 5, 7)) {
            val rotated = ExifOrientation.rotatedBy(v, 90)
            assertTrue("orientation=$v", ExifOrientation.transformOf(rotated).mirrored)
        }
        for (v in listOf(1, 3, 6, 8)) {
            val rotated = ExifOrientation.rotatedBy(v, 90)
            assertFalse("orientation=$v", ExifOrientation.transformOf(rotated).mirrored)
        }
    }

    @Test fun rotationAccumulatesClockwise() {
        assertEquals(6, ExifOrientation.rotatedBy(1, 90))    // 0 → 90
        assertEquals(3, ExifOrientation.rotatedBy(6, 90))    // 90 → 180
        assertEquals(8, ExifOrientation.rotatedBy(3, 90))    // 180 → 270
        assertEquals(1, ExifOrientation.rotatedBy(8, 90))    // 270 → 0
    }

    /** 음수·360 이상도 받는다(반시계 회전 버튼을 나중에 붙여도 표가 버틴다). */
    @Test fun rotationNormalizesOutOfRangeDegrees() {
        assertEquals(ExifOrientation.rotatedBy(1, 270), ExifOrientation.rotatedBy(1, -90))
        assertEquals(ExifOrientation.rotatedBy(1, 90), ExifOrientation.rotatedBy(1, 450))
        assertEquals(1, ExifOrientation.rotatedBy(1, 0))
        assertEquals(1, ExifOrientation.rotatedBy(1, 360))
    }
}
