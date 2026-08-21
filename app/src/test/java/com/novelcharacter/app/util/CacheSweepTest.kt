package com.novelcharacter.app.util

import com.novelcharacter.app.excel.ActiveTransfers
import com.novelcharacter.app.excel.TransferPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [CacheSweep.sweep] · [ActiveTransfers] — 캐시 비우기의 **범위와 안전장치** (2026.08.21).
 *
 * 이 하네스가 잠그는 것은 셋이다:
 *
 * 1. **세는 범위와 지우는 범위가 같다** — `cacheDir` 전체이고 하위 디렉터리도 포함이다.
 *    종전에는 `exports/`의 최상위 파일만 지워, 화면이 1.2GB라 말하고 버튼이 120MB를 지웠다.
 * 2. **약속된 것은 지운다고 해서 지워지지 않는다** — 저장을 기다리는 내보내기가 그것이다.
 *    지우면 재시도 창의 *"보관해 두었습니다"*가 거짓이 된다.
 * 3. **도는 전송을 알아본다** — 구간 대입 하나가 곧 그 사실이다.
 */
class CacheSweepTest {

    @After
    fun tearDown() = ActiveTransfers.resetForTest()

    private fun tempRoot(): File =
        File.createTempFile("sweep-test", "").let { f ->
            f.delete(); f.mkdirs(); f
        }

    private fun write(file: File, bytes: Int): File {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
        return file
    }

    // ── 1. 범위 ───────────────────────────────────────────────────────────

    @Test
    fun `하위 디렉터리까지 지운다 — exports 밖도 한 바이트도 남기지 않는다`() {
        val root = tempRoot()
        write(File(root, "exports/backup.xlsx"), 100)
        write(File(root, "poi-temp/poi123.tmp"), 200)
        write(File(root, "world_import_1/images/a.png"), 300)
        write(File(root, "restore_9.xlsx"), 400)

        val r = CacheSweep.sweep(root, emptySet())

        assertEquals(1000L, r.freedBytes)
        assertEquals(4, r.deletedFiles)
        assertEquals(0, r.keptFiles)
        assertEquals(0, r.failedFiles)
        assertEquals("남은 것이 있으면 안 된다", 0, root.listFiles()?.size ?: -1)
    }

    @Test
    fun `뿌리 자신은 지우지 않는다 — 다음 전송이 곧 쓴다`() {
        val root = tempRoot()
        write(File(root, "a.tmp"), 10)
        CacheSweep.sweep(root, emptySet())
        assertTrue("cacheDir가 사라지면 다음 전송이 만들 자리를 잃는다", root.isDirectory)
    }

    @Test
    fun `비워진 디렉터리는 함께 정리한다 — 껍데기가 회차마다 쌓이지 않게`() {
        val root = tempRoot()
        write(File(root, "world_import_1/images/a.png"), 10)
        CacheSweep.sweep(root, emptySet())
        assertFalse(File(root, "world_import_1").exists())
    }

    // ── 2. 약속된 것 ──────────────────────────────────────────────────────

    @Test
    fun `저장을 기다리는 파일은 남고 그 디렉터리도 함께 남는다`() {
        val root = tempRoot()
        val retained = write(File(root, "exports/pending.xlsx"), 500)
        write(File(root, "exports/old.xlsx"), 100)
        write(File(root, "poi-temp/x.tmp"), 50)

        val r = CacheSweep.sweep(root, setOf(retained.absolutePath))

        assertTrue("보관물을 지우면 '보관해 두었습니다'가 거짓이 된다", retained.exists())
        assertEquals(1, r.keptFiles)
        assertEquals(150L, r.freedBytes)
        assertTrue("지켜진 것이 든 디렉터리는 남아야 한다", File(root, "exports").isDirectory)
        assertFalse(File(root, "poi-temp").exists())
    }

    @Test
    fun `지켜지는 디렉터리는 그 아래 전부가 지켜진다`() {
        val root = tempRoot()
        val guarded = File(root, "world_import_7")
        write(File(guarded, "images/a.png"), 10)
        write(File(guarded, "book.xlsx"), 20)
        write(File(root, "loose.tmp"), 30)

        val r = CacheSweep.sweep(root, setOf(guarded.absolutePath))

        assertTrue(File(guarded, "images/a.png").exists())
        assertTrue(File(guarded, "book.xlsx").exists())
        assertEquals(30L, r.freedBytes)
    }

    @Test
    fun `이름이 앞부분만 같은 형제는 지켜지지 않는다`() {
        val root = tempRoot()
        val guarded = write(File(root, "restore_1.xlsx"), 10)
        val sibling = write(File(root, "restore_1.xlsx.bak"), 20)

        CacheSweep.sweep(root, setOf(guarded.absolutePath))

        assertTrue(guarded.exists())
        assertFalse("경계를 안 보면 형제까지 지켜져 회수가 줄어든다", sibling.exists())
    }

    @Test
    fun `뿌리 밖을 가리키는 심링크로는 걸어 들어가지 않는다`() {
        val root = tempRoot()
        val outside = tempRoot()
        val treasure = write(File(outside, "user-image.png"), 999)
        val link = File(root, "escape").toPath()
        try {
            java.nio.file.Files.createSymbolicLink(link, outside.toPath())
        } catch (e: Exception) {
            return // 심링크를 못 만드는 환경이면 이 확인은 건너뛴다
        }
        write(File(root, "junk.tmp"), 10)

        val r = CacheSweep.sweep(root, emptySet())

        assertTrue("캐시 비우기가 캐시 밖 사용자 파일을 지웠다", treasure.exists())
        assertEquals(10L, r.freedBytes)
        assertEquals(1, r.keptFiles)
    }

    // ── 3. 도는 전송 ──────────────────────────────────────────────────────

    @Test
    fun `구간이 서면 돌고 있는 것이고 내려가면 끝난 것이다`() {
        val owner = Any()
        assertFalse(ActiveTransfers.isRunning)
        ActiveTransfers.trackPhase(owner, TransferPhase.IMPORT_INTAKE)
        assertTrue(ActiveTransfers.isRunning)
        // 구간 이동은 여전히 도는 것이다
        ActiveTransfers.trackPhase(owner, TransferPhase.IMPORT_APPLY)
        assertTrue(ActiveTransfers.isRunning)
        ActiveTransfers.trackPhase(owner, null)
        assertFalse(ActiveTransfers.isRunning)
    }

    @Test
    fun `한 전송이 끝나도 다른 전송이 돌고 있으면 돈다고 말한다`() {
        val a = Any()
        val b = Any()
        ActiveTransfers.trackPhase(a, TransferPhase.EXPORT_BUILD)
        ActiveTransfers.trackPhase(b, TransferPhase.IMPORT_ANALYZE)
        ActiveTransfers.trackPhase(a, null)
        assertTrue("둘 중 하나만 끝났다", ActiveTransfers.isRunning)
        ActiveTransfers.trackPhase(b, null)
        assertFalse(ActiveTransfers.isRunning)
    }

    @Test
    fun `같은 구간을 두 번 세워도 한 번 내리면 끝난다 — 카운터가 어긋나지 않는다`() {
        val owner = Any()
        ActiveTransfers.trackPhase(owner, TransferPhase.EXPORT_BUILD)
        ActiveTransfers.trackPhase(owner, TransferPhase.EXPORT_BUILD)
        ActiveTransfers.trackPhase(owner, null)
        assertFalse("증감 카운터였다면 여기서 1이 남아 영영 '도는 중'이 된다", ActiveTransfers.isRunning)
    }

    @Test
    fun `구간을 두지 않는 전송은 running 이 든다 — 예외로 빠져나가도 내려놓는다`() {
        val owner = Any()
        runCatching {
            ActiveTransfers.running(owner) {
                assertTrue(ActiveTransfers.isRunning)
                throw IllegalStateException("자동 백업이 터졌다")
            }
        }
        assertFalse("등재가 남으면 비우기가 영영 거절된다", ActiveTransfers.isRunning)
    }

    @Test
    fun `약속된 경로는 hold 와 release 로 여닫는다`() {
        val f = File("/tmp/never-created-portable.enc")
        assertEquals(emptySet<String>(), ActiveTransfers.protectedPaths(null))
        ActiveTransfers.hold(f)
        assertEquals(setOf(f.absolutePath), ActiveTransfers.protectedPaths(null))
        // 보관물은 프로세스 밖(prefs)에 살아 부르는 쪽이 넘긴다
        assertEquals(
            setOf(f.absolutePath, "/tmp/retained.xlsx"),
            ActiveTransfers.protectedPaths("/tmp/retained.xlsx")
        )
        ActiveTransfers.release(f)
        assertEquals(setOf("/tmp/retained.xlsx"), ActiveTransfers.protectedPaths("/tmp/retained.xlsx"))
    }
}
