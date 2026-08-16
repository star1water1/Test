package com.novelcharacter.app.excel

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **안드로이드 조건을 순수 JVM에서 재현한다** — `java.awt`가 없는 런타임 (B-250, 2026.08.17).
 *
 * ## 왜 이 시험이 있는가 — 하네스가 못 보던 축이 하나 더 있었다
 *
 * 실기기에서 **사용자 내보내기와 자동 백업이 함께 죽었다**(크래시 로그 2026.08.17):
 * `SXSSFSheet` 생성자가 `AutoSizeColumnTracker`를 만들고, 그것이 `java.awt`를 무는데
 * **안드로이드에는 `java.awt`가 없다.**
 *
 * 그런데 [ExportWorkbookParityTest]는 초록이었다. 이유가 이 시험의 존재 이유다 —
 * **하네스는 데스크톱 JVM에서 돌고 거기엔 `java.awt`가 있다.** 이 저장소가 이미 이름 붙인
 * 두 부류(`ui/` 밑처럼 *원리적으로* 못 재는 것 · `backup/`처럼 *안 갈라 둬서* 못 재던 것)에
 * **셋째가 붙는다: 하네스와 기기의 런타임이 다르다.** 그리고 이 셋째는 **가리기만 하면 잰다.**
 *
 * > ⚠️ KDoc 안에 `ui/` + 별 두 개를 그대로 적지 말 것 — **코틀린 블록 주석은 중첩되므로**
 * > 그 세 글자가 주석을 하나 더 열어 파일 끝까지 삼킨다(이 파일을 쓰다 실제로 겪었다).
 *
 * ## 이 시험이 재는 것
 *
 * `java.awt`를 거부하는 클래스로더로 POI를 **다시 로드**한다. 그러면 `SheetUtil`·
 * `AutoSizeColumnTracker`의 AWT 참조가 그 로더를 통해 풀리므로 **기기와 같은 자리에서 죽는다** —
 * 실측으로 스택이 기기 로그와 **줄 번호까지 같았다**(`AutoSizeColumnTracker.<init>:117` ·
 * `SXSSFSheet.<init>:106` · `SXSSFWorkbook.createAndRegisterSXSSFSheet:698` ·
 * `SXSSFWorkbook.createSheet:716`).
 *
 * ## 버전으로 풀리지 않는다는 것을 여기서 못박는다
 *
 * POI 5.3.0은 그 자리에 `NoClassDefFoundError` 가드를 넣었다. **그것으로 부족하다** —
 * 가드가 덮는 것은 `FontRenderContext`이고 먼저 무는 것은 `java.awt.geom.Rectangle2D`다.
 * ①이 그 사실을 잰다. 판올림이 그 자리를 마저 고치면 ①이 빨간불이 되어 **알려 준다**
 * (그때 할 일은 이 시험을 지우는 것이 아니라 기대를 뒤집는 것이다 — 그 자리에 주석을 남겼다).
 */
class ExportStreamingAvailabilityTest {

    /**
     * `java.awt`를 거부하는 로더. `parent = null`이라 위임이 부트스트랩까지만 가고,
     * `java.desktop` 모듈(= `java.awt`)은 거기 없다 — 그래서 **가리기가 이중으로 선다**
     * (명시 거부 + 위임 경로 부재). 명시 거부를 함께 두는 것은 이 시험이 *무엇을 가렸는지*를
     * 예외 메시지로 말하게 하려는 것이다.
     */
    private class AwtFreeClassLoader(urls: Array<URL>) : URLClassLoader(urls, null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("java.awt")) throw ClassNotFoundException("hidden for test: $name")
            return super.loadClass(name, resolve)
        }
    }

    private fun awtFreeLoader(): AwtFreeClassLoader {
        val cp = System.getProperty("java.class.path").orEmpty()
        val urls = cp.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        check(urls.isNotEmpty()) { "클래스패스를 못 읽었다 — 이 시험은 아무것도 재지 못한다" }
        return AwtFreeClassLoader(urls)
    }

    /** 예외 사슬의 뿌리 — 무엇이 없어서 죽었는가는 늘 맨 아래에 있다. */
    private fun rootCause(t: Throwable): Throwable {
        var c = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c
    }

    /** 로더 자체가 성립하는가 — 이것이 거짓이면 아래 시험들은 아무것도 증명하지 않는다. */
    @Test
    fun loader_actuallyHidesAwt() {
        try {
            awtFreeLoader().loadClass("java.awt.geom.Rectangle2D")
            fail("가렸다는 클래스가 로드됐다 — 이 시험 전체가 무의미해진다")
        } catch (e: ClassNotFoundException) {
            assertTrue(e.message!!.contains("hidden for test"))
        }
    }

    // ── ① 기기에서 죽던 그 자리 — 재현 ──

    /**
     * **회귀 시험의 본체.** `java.awt`가 없으면 스트리밍 시트를 만들 수 없다.
     *
     * 이 시험이 **빨간불이 되면** POI가 그 자리를 고쳤다는 뜻이다 — 그때는 이 시험을 지우지 말고
     * 기대를 뒤집을 것(그리고 [ExportWorkbooks.isStreamingSupported]가 스스로 `true`로
     * 돌아서므로 **제품 코드는 고칠 것이 없다**).
     */
    @Test
    fun awtFree_streamingSheetCannotBeCreated() {
        val loader = awtFreeLoader()
        val workbookClass = loader.loadClass("org.apache.poi.xssf.streaming.SXSSFWorkbook")
        val workbook = workbookClass.getConstructor().newInstance()
        try {
            workbookClass.getMethod("createSheet", String::class.java).invoke(workbook, "probe")
            fail("java.awt 없이 스트리밍 시트가 만들어졌다 — 기기 크래시의 전제가 사라졌다")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val root = rootCause(e)
            assertTrue(
                "AWT가 아닌 다른 이유로 죽었다: $root",
                root.message.orEmpty().contains("java.awt")
            )
        }
    }

    /** DOM은 그 자리를 지나지 않는다 — 폴백이 성립하는 근거다. */
    @Test
    fun awtFree_domSheetStillWorks() {
        val loader = awtFreeLoader()
        val workbookClass = loader.loadClass("org.apache.poi.xssf.usermodel.XSSFWorkbook")
        val workbook = workbookClass.getConstructor().newInstance()
        workbookClass.getMethod("createSheet", String::class.java).invoke(workbook, "probe")
        // 여기까지 예외가 없으면 통과다. DOM이 함께 죽으면 폴백할 곳이 없다.
    }

    // ── ② 능력 검사가 그 갈래를 옳게 가르는가 ──

    /** `java.awt`가 있는 이 하네스에서는 스트리밍이 된다 — 검사가 무턱대고 false면 안 된다. */
    @Test
    fun probe_reportsSupportedWhenAwtIsPresent() {
        ExportWorkbooks.resetStreamingSupportCache()
        assertTrue(
            "AWT가 있는 런타임인데 스트리밍을 못 한다고 답했다 — 이 오답의 대가는 메모리다",
            ExportWorkbooks.isStreamingSupported()
        )
        ExportWorkbooks.resetStreamingSupportCache()
    }

    /**
     * **AWT가 없으면 검사가 `false`를 답하고, 그래서 [ExportWorkbooks.create]가 DOM을 준다.**
     * 기기에서 앱을 살리는 것이 정확히 이 두 줄이다.
     */
    @Test
    fun probe_reportsUnsupportedAndFallsBackToDomWhenAwtIsMissing() {
        val loader = awtFreeLoader()
        val workbooksClass = loader.loadClass("com.novelcharacter.app.excel.ExportWorkbooks")
        val instance = workbooksClass.getField("INSTANCE").get(null)

        // 캐시를 비울 필요가 없다 — 이 로더가 `ExportWorkbooks`를 **새로 정의**하므로
        // 그 정적 상태도 새것이다. (비우려 해도 `internal`은 JVM 이름이 맹글링돼
        // 이름으로는 못 찾는다 — 실제로 한 번 걸렸다.)
        val supported = workbooksClass.getMethod("isStreamingSupported").invoke(instance) as Boolean
        assertFalse("java.awt가 없는데 스트리밍이 된다고 답했다", supported)

        // 그 답이 실제로 DOM을 고르게 하는가 — 답만 맞고 배선이 끊겨 있으면 앱은 그대로 죽는다.
        val chosen = workbooksClass
            .getMethod("create", Boolean::class.javaPrimitiveType)
            .invoke(instance, supported)
        assertEquals(
            "검사는 DOM이라 했는데 스트리밍 워크북이 나왔다",
            "org.apache.poi.xssf.usermodel.XSSFWorkbook",
            chosen!!.javaClass.name
        )
        // 고른 그것으로 시트가 실제로 만들어지는가(기기에서 죽던 그 호출).
        chosen.javaClass.getMethod("createSheet", String::class.java).invoke(chosen, "probe")
    }

    /** 검사는 프로세스마다 한 번만 잰다 — 매 내보내기마다 워크북을 하나씩 더 만들면 안 된다. */
    @Test
    fun probe_isMeasuredOnlyOnce() {
        ExportWorkbooks.resetStreamingSupportCache()
        val first = ExportWorkbooks.isStreamingSupported()
        val elapsed = measureRepeatedCalls()
        assertEquals("같은 프로세스에서 답이 바뀌었다", first, ExportWorkbooks.isStreamingSupported())
        assertTrue(
            "두 번째 이후 부름이 캐시를 안 탄다(${elapsed}ms) — 내보내기마다 워크북이 하나씩 더 생긴다",
            elapsed < 200
        )
        ExportWorkbooks.resetStreamingSupportCache()
    }

    private fun measureRepeatedCalls(): Long {
        val start = System.nanoTime()
        repeat(1_000) { ExportWorkbooks.isStreamingSupported() }
        return (System.nanoTime() - start) / 1_000_000
    }
}
