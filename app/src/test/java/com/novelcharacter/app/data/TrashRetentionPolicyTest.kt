package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.repository.TrashRetentionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 휴지통 보관 정책의 현행 값 (B-74).
 *
 * **이 시험의 방어선은 둘이고 둘 다 되돌릴 수 없는 쪽을 막는다:**
 * ① *읽기 전에는 값을 내주지 않는다* — 기본값으로 대신 정리하면 사용자가 올려 둔 한도를
 *    모른 채 영구 삭제하게 된다.
 * ② *범위를 벗어난 값을 좁힌다* — 0 이하가 그대로 흐르면 `selectOverflow`가 보호되지 않은
 *    전부를 대상으로 삼아 **휴지통이 삭제 즉시 비워진다.** 엑셀 '앱 설정' 시트는 사람이 손으로
 *    적는 칸이라 그 값이 실제로 들어온다.
 */
class TrashRetentionPolicyTest {

    @Before fun clear() = TrashRetentionPolicy.resetForTest()
    @After fun cleanup() = TrashRetentionPolicy.resetForTest()

    @Test
    fun `읽기 전에는 현행 값이 없다 — 그때 정리는 건너뛴다`() {
        assertNull("추측한 값을 내주면 동의 없는 영구 삭제가 된다", TrashRetentionPolicy.current())
        // 반면 화면에 적을 값은 있어야 한다 — 지우는 쪽과 적는 쪽을 가른 이유가 이것이다.
        assertEquals(
            TrashSnapshot.DEFAULT_MAX_OPERATIONS,
            TrashRetentionPolicy.currentOrDefault().maxOperations
        )
    }

    @Test
    fun `실어 준 값이 현행이 된다`() {
        TrashRetentionPolicy.publish(TrashRetentionPolicy.Settings(100, 90))
        val now = TrashRetentionPolicy.current()
        assertNotNull(now)
        assertEquals(100, now!!.maxOperations)
        assertEquals(90, now.retentionDays)
    }

    @Test
    fun `0 이하는 좁혀 받는다 — 그대로 두면 휴지통이 즉시 비워진다`() {
        val s = TrashRetentionPolicy.sanitize(TrashRetentionPolicy.Settings(0, 0))
        assertEquals(TrashRetentionPolicy.MIN_OPERATIONS, s.maxOperations)
        assertEquals(TrashRetentionPolicy.MIN_DAYS, s.retentionDays)
        assertTrue("한도가 양수여야 selectOverflow가 '전부'를 뽑지 않는다", s.maxOperations > 0)

        val neg = TrashRetentionPolicy.sanitize(TrashRetentionPolicy.Settings(-5, -30))
        assertEquals(TrashRetentionPolicy.MIN_OPERATIONS, neg.maxOperations)
        assertEquals(TrashRetentionPolicy.MIN_DAYS, neg.retentionDays)
    }

    @Test
    fun `터무니없이 큰 값도 상한으로 좁힌다`() {
        val s = TrashRetentionPolicy.sanitize(TrashRetentionPolicy.Settings(999_999, 999_999))
        assertEquals(TrashRetentionPolicy.MAX_OPERATIONS_CAP, s.maxOperations)
        assertEquals(TrashRetentionPolicy.MAX_DAYS_CAP, s.retentionDays)
    }

    @Test
    fun `publish도 좁혀서 싣는다 — 좁히지 않는 문이 있으면 그 문으로 들어온다`() {
        // sanitize를 부르는 것은 부르는 쪽의 예의가 아니라 이 객체의 규칙이어야 한다.
        TrashRetentionPolicy.publish(TrashRetentionPolicy.Settings(0, 0))
        assertEquals(TrashRetentionPolicy.MIN_OPERATIONS, TrashRetentionPolicy.current()!!.maxOperations)
    }

    @Test
    fun `기본값은 종전 상수 그대로다 — 설정을 안 건드린 사용자의 동작이 바뀌지 않는다`() {
        val d = TrashRetentionPolicy.Settings()
        assertEquals(30, d.maxOperations)
        assertEquals(30, d.retentionDays)
        assertEquals(30L * 24 * 60 * 60 * 1000, d.retentionMs)
    }

    @Test
    fun `선택지는 전부 좁히기를 통과한다 — 고를 수 있는데 안 먹는 칸이 없어야 한다`() {
        for (c in TrashRetentionPolicy.MAX_OPERATION_CHOICES) {
            assertEquals(
                "선택지 ${c}건이 좁혀지면 사용자가 고른 값과 실제가 갈린다",
                c, TrashRetentionPolicy.sanitize(TrashRetentionPolicy.Settings(maxOperations = c)).maxOperations
            )
        }
        for (d in TrashRetentionPolicy.RETENTION_DAY_CHOICES) {
            assertEquals(
                "선택지 ${d}일이 좁혀지면 사용자가 고른 값과 실제가 갈린다",
                d, TrashRetentionPolicy.sanitize(TrashRetentionPolicy.Settings(retentionDays = d)).retentionDays
            )
        }
    }
}
