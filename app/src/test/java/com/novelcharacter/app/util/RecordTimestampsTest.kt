package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RecordTimestamps] — **수정일은 생성일보다 이를 수 없다.**
 *
 * 사용자가 내보낸 파일의 '필드 템플릿' 두 행이 생성일 2026-08-02 · 수정일 2026-03-20이었다.
 * 생성일은 정체라 파일 값으로 덮지 않고 수정일은 파일 값을 그대로 받는데, 그 둘이 서로를
 * 몰라 **더 오래된 파일을 들이면 짝이 뒤집혔다.**
 */
class RecordTimestampsTest {

    /** 2026-08-02 10:29:05Z · 2026-03-20 14:40:23Z — 실제로 파일에 있던 두 값. */
    private val created = 1785666545516L
    private val stale = 1774017623390L

    @Test
    fun `파일 값이 생성일보다 이르면 생성일로 올려 받는다`() {
        assertEquals(created, RecordTimestamps.orderedUpdatedAt(created, stale))
    }

    @Test
    fun `생성일 뒤의 값은 그대로 받는다`() {
        val later = created + 60_000
        assertEquals(later, RecordTimestamps.orderedUpdatedAt(created, later))
    }

    @Test
    fun `같으면 그대로다`() {
        assertEquals(created, RecordTimestamps.orderedUpdatedAt(created, created))
    }

    /** 한 번 올려 받은 값은 다시 돌려도 그대로다 — 다시 가져와도 값이 흔들리지 않는다. */
    @Test
    fun `멱등이다`() {
        val once = RecordTimestamps.orderedUpdatedAt(created, stale)
        assertEquals(once, RecordTimestamps.orderedUpdatedAt(created, once))
    }

    /**
     * 규칙이 **고치는 것은 뒤집힌 짝뿐**이다. 옛 파일을 들여도 정상인 짝은 건드리지 않는다 —
     * 왕복 충실성(파일의 수정일을 그대로 받는다)을 버리지 않는 것이 이 규칙의 조건이다.
     */
    @Test
    fun `정상인 짝은 건드리지 않는다`() {
        val old = 1_000_000L
        assertEquals(2_000_000L, RecordTimestamps.orderedUpdatedAt(old, 2_000_000L))
        assertEquals(old, RecordTimestamps.orderedUpdatedAt(old, old))
    }
}
