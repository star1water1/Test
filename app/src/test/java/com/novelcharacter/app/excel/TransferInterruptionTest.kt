package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TransferInterruption] — 화면 파괴로 전송이 끊길 때의 처분 (B-228).
 *
 * `ExcelImporter`·`ExcelExporter`는 `Toast`·`Activity`를 들고 있어 순수 JVM 시험이 닿지 않는다.
 * **그래서 판정만 떼어 냈고, 이 하네스가 잠그는 것은 그 판정 셋이다:**
 *
 * 1. **끊기지 않는 구간을 끊긴 것으로 말하지 않는다** — 되돌릴 수 없는 두 구간은 끝까지 가서
 *    스스로 결과를 말한다. 여기서 미리 *"중단되었습니다"*라고 하면 곧 이어 오는
 *    *"가져왔습니다"*와 정면으로 어긋난다.
 * 2. **돌던 것이 없으면 침묵한다** — 그러지 않으면 화면을 옮길 때마다 알림이 뜬다.
 * 3. **사용자가 방금 누른 취소를 두 번 말하지 않는다** — 그 고지는 토스트가 이미 했다.
 */
class TransferInterruptionTest {

    // ── 1. 구간의 성질 ────────────────────────────────────────────────────────

    @Test
    fun `되돌릴 수 없는 두 구간은 화면이 사라져도 끊기지 않는다`() {
        // 이미지 되살리기: 트랜잭션 밖에서 filesDir에 파일을 만든다 — 끊으면 가리킬 행이 없는 장이 남는다.
        assertFalse(TransferPhase.IMPORT_RESTORE_IMAGES.stopsOnScreenLoss)
        // 반영: 전략이 여러 표에 걸쳐 실린다 — 끊으면 반쯤 덮인 DB가 남는다.
        assertFalse(TransferPhase.IMPORT_APPLY.stopsOnScreenLoss)
        // SAF 옮겨 쓰기: 목적지가 사용자가 고른 실제 파일이라, 끊기면 반쪽 파일이
        // 멀쩡한 이름으로 남고 **앱이 지울 수도 없다**.
        assertFalse(TransferPhase.EXPORT_SAVE.stopsOnScreenLoss)
    }

    @Test
    fun `읽기만 하거나 캐시에만 쓰는 구간은 끊긴다`() {
        assertTrue(TransferPhase.IMPORT_INTAKE.stopsOnScreenLoss)
        assertTrue(TransferPhase.IMPORT_ANALYZE.stopsOnScreenLoss)
        // 묻는 중: 물을 화면이 사라졌으므로 이어 갈 수단 자체가 없다.
        assertTrue(TransferPhase.IMPORT_ASK.stopsOnScreenLoss)
        // 내보내기: 다 만들어도 건넬 화면이 없다.
        assertTrue(TransferPhase.EXPORT_BUILD.stopsOnScreenLoss)
    }

    @Test
    fun `구간마다 어느 작업의 것인지가 정해져 있다`() {
        val importPhases = TransferPhase.entries.filter { it.kind == TransferKind.IMPORT }
        val exportPhases = TransferPhase.entries.filter { it.kind == TransferKind.EXPORT }
        // 양쪽 다 비어 있지 않아야 한다 — 한쪽이 비면 그 작업의 중단은 영영 고지되지 않는다.
        assertTrue("가져오기 구간이 하나도 없다", importPhases.isNotEmpty())
        assertTrue("내보내기 구간이 하나도 없다", exportPhases.isNotEmpty())
        assertEquals(TransferPhase.entries.size, importPhases.size + exportPhases.size)
    }

    // ── 2. 고지 판정 ──────────────────────────────────────────────────────────

    @Test
    fun `돌던 것이 없으면 아무 말도 하지 않는다`() {
        assertNull(
            "화면을 옮길 때마다 알림이 뜨면 그것은 고지가 아니라 소음이다",
            TransferInterruption.abortedKind(phase = null, userCancelled = false)
        )
    }

    @Test
    fun `끊기지 않으면서 스스로 말하는 구간에서는 중단을 고지하지 않는다`() {
        // 이 구간은 끝까지 가서 스스로 결과를 말한다 — 여기서 말하면 고지가 두 번이고 서로 어긋난다.
        assertNull(
            TransferInterruption.abortedKind(TransferPhase.IMPORT_APPLY, userCancelled = false)
        )
        assertNull(
            TransferInterruption.abortedKind(TransferPhase.EXPORT_SAVE, userCancelled = false)
        )
    }

    /**
     * **끊기지 않는다고 해서 스스로 말하는 것은 아니다.**
     *
     * 이미지 되살리기는 복사 블록만 `NonCancellable`이고, 그 뒤의 가져오기는 이미 끊긴
     * 스코프 위에서 첫 중단점에 죽는다 — 그 catch는 *"고지는 onScreenGone이 이미 했다"*고
     * 믿어 다시 던지는데 실제로는 아무도 안 했다. 종전에는 이 구간이 `stopsOnScreenLoss`
     * 하나로 침묵 갈래에 묶여, 화면을 돌리면 **중단 고지도 완료 결과도 없이** 조용히 죽었다.
     */
    @Test
    fun `끊기지 않지만 스스로 말하지 않는 구간은 중단을 고지한다`() {
        assertEquals(
            TransferKind.IMPORT,
            TransferInterruption.abortedKind(TransferPhase.IMPORT_RESTORE_IMAGES, userCancelled = false)
        )
    }

    /** 축이 둘이라는 사실 자체를 잠근다 — 하나로 겸하면 같은 구멍이 다시 난다. */
    @Test
    fun `두 축은 서로 독립이다`() {
        assertEquals(false, TransferPhase.IMPORT_RESTORE_IMAGES.stopsOnScreenLoss)
        assertEquals(false, TransferPhase.IMPORT_RESTORE_IMAGES.announcesOwnOutcome)
        assertEquals(false, TransferPhase.IMPORT_APPLY.stopsOnScreenLoss)
        assertEquals(true, TransferPhase.IMPORT_APPLY.announcesOwnOutcome)
    }

    @Test
    fun `사용자가 취소를 눌렀으면 중단을 다시 말하지 않는다`() {
        // 사용자는 화면 앞에 있고 토스트가 이미 말했다(B-56이 정한 경계).
        assertNull(
            TransferInterruption.abortedKind(TransferPhase.IMPORT_INTAKE, userCancelled = true)
        )
        assertNull(
            TransferInterruption.abortedKind(TransferPhase.EXPORT_BUILD, userCancelled = true)
        )
    }

    @Test
    fun `끊기는 구간에서 화면이 사라지면 그 작업의 중단을 고지한다`() {
        assertEquals(
            TransferKind.IMPORT,
            TransferInterruption.abortedKind(TransferPhase.IMPORT_INTAKE, userCancelled = false)
        )
        assertEquals(
            TransferKind.IMPORT,
            TransferInterruption.abortedKind(TransferPhase.IMPORT_ANALYZE, userCancelled = false)
        )
        assertEquals(
            TransferKind.IMPORT,
            TransferInterruption.abortedKind(TransferPhase.IMPORT_ASK, userCancelled = false)
        )
        assertEquals(
            TransferKind.EXPORT,
            TransferInterruption.abortedKind(TransferPhase.EXPORT_BUILD, userCancelled = false)
        )
    }

    // ── 3. 전수 ───────────────────────────────────────────────────────────────

    @Test
    fun `모든 구간이 셋 중 하나로 갈린다 — 빠뜨린 구간이 없다`() {
        // 새 구간이 늘면 이 시험이 그것을 어느 갈래에 넣을지 정하게 만든다.
        // (판정을 안 하고 늘리면 그 구간의 중단은 조용해진다 — 이 판이 고치러 온 그 모양이다.)
        //
        // **침묵의 조건은 두 축이 함께 참일 때다** — 종전에는 `!stopsOnScreenLoss` 하나였고,
        // 그래서 '끊기지는 않는데 스스로 말하지도 않는' 구간이 조용히 침묵 갈래에 묶였다.
        for (p in TransferPhase.entries) {
            val silent = TransferInterruption.abortedKind(p, userCancelled = false) == null
            assertEquals(
                "구간 $p 의 '끊기는가 · 스스로 말하는가'와 '고지하는가'가 어긋난다",
                !p.stopsOnScreenLoss && p.announcesOwnOutcome,
                silent
            )
            assertNull(
                "구간 $p: 사용자 취소는 언제나 침묵이다",
                TransferInterruption.abortedKind(p, userCancelled = true)
            )
        }
    }
}
