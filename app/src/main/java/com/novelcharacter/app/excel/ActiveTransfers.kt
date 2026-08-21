package com.novelcharacter.app.excel

import java.io.File

/**
 * **지금 이 프로세스에서 전송이 돌고 있는가** — 그리고 **어떤 캐시 파일이 약속되어 있는가**.
 *
 * ## 왜 생겼는가 (2026.08.21 사용자 판정 — *"설계 원칙과 최고 퍼포먼스를 기준으로"*)
 *
 * 저장공간 화면은 캐시를 **`cacheDir` 전체**로 세는데(전송 임시 파일은 `exports/` 말고도
 * `poi-temp`·`world_import_*`·복호화 산출물 등 여러 자리에 난다) 비우기는 `exports/`의
 * **최상위 파일만** 지웠다. 세는 것과 지우는 것이 갈리면 *"앱 캐시 1.2GB"*를 본 사용자가
 * [비우기]를 눌러도 대부분이 그대로 남는다 — **화면의 쓸모가 반이 된다**(원칙 02).
 *
 * 범위를 맞추는 데 걸린 것은 하나였다: **돌고 있는 전송의 임시 파일을 앗아가면 안 된다**
 * (개발 의도 2 — 사용자의 데이터가 말없이 유실되지 않는다). 그것을 알 방법이 없어서
 * 종전 판이 범위를 넓히지 않고 사용자 판정으로 넘겼다. 이 파일이 그 방법이다.
 *
 * ## 왜 새 상태가 아니라 이미 있는 사실인가
 *
 * [ExcelImporter]·[ExcelExporter]는 **이미** `phase: TransferPhase?`를 정확히 들고 있다 —
 * 회차가 시작될 때 세우고 `finally`에서 내린다(B-228이 세운 불변식이고,
 * [TransferInterruption]의 중단 고지가 그 불변식에 걸려 있다). 그 대입이 곧
 * *"지금 도는가"*이므로 **새 상태를 만들지 않고 그 대입을 여기로 흘린다.**
 * 두 벌이 되지 않으니 한쪽만 낡을 수 없다.
 *
 * 다만 워크북을 **직접** 세우는 자리는 구간을 두지 않는다(자동 백업 워커는
 * `ExcelExporter.populateWorkbook`만 부른다). 그런 자리는 [running]으로 감싼다.
 *
 * ## 약속된 경로 — 도는 것이 없어도 지켜야 하는 것
 *
 * 전송이 끝났는데도 지워서는 안 되는 캐시 파일이 둘 있다.
 *
 * - **저장(SAF)에 실패해 보관 중인 내보내기**([ExportRetryStore]) — 재시도 창이
 *   *"보관해 두었습니다"*라고 약속했다. 내보내기의 회전 정리는 이미 이 파일만은 빼고
 *   지운다. **비우기만 그 규약 밖에 있었다** — 지우면 그 약속이 거짓이 된다.
 *   프로세스가 죽어도 유효하므로 [protectedPaths]가 SharedPreferences에서 읽는다.
 * - **자리 고르기를 기다리는 이식 가능 백업** — 사용자가 SAF 창을 띄워 둔 사이의 파일.
 *   프로세스 수명이라 [hold]/[release]로 든다(프로세스가 죽으면 그 파일은 정말 고아다).
 *
 * ## 실패 모양
 *
 * 등재가 **남는** 쪽으로 틀리면 비우기가 그 파일을 한 번 더 두고 갈 뿐이다(회수가 는다).
 * 등재가 **빠지는** 쪽으로 틀리면 도는 전송이 깨진다. 그래서 판정은 *"하나라도 돌면
 * 비우지 않는다"*로 **보수적으로** 잡았다 — 비우기는 미룰 수 있지만 깨진 전송은 못 되돌린다.
 */
object ActiveTransfers {

    /**
     * 지금 구간이 서 있는 전송 객체들.
     *
     * **수가 아니라 객체를 담는 것이 요점이다.** 증감 카운터는 두 스레드가 같은 회차를
     * 동시에 옮길 때 어긋날 수 있고, 한 번 어긋나면 그 뒤로 영영 어긋난 채 남는다
     * (0 아래로 내려간 카운터는 전송이 도는데도 '안 돈다'고 말한다 — 가장 나쁜 실패다).
     * 집합은 같은 객체를 두 번 넣어도 한 번이고, 지우면 확실히 지워진다.
     */
    private val running: MutableSet<Any> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())

    /** 프로세스 수명 동안 지켜야 하는 캐시 경로(절대 경로). */
    private val held: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** 지금 돌고 있는 전송이 하나라도 있는가. */
    val isRunning: Boolean get() = running.isNotEmpty()

    /**
     * 구간 대입을 그대로 받는다 — [ExcelImporter]·[ExcelExporter]의 `phase` 세터가 부른다.
     *
     * `null`이 아니면 그 회차는 돌고 있는 것이고, `null`이면 끝난 것이다. **판정을 여기서
     * 다시 적지 않는 것이 요점**이다(구간이 늘어도 이 함수는 그대로다).
     */
    fun trackPhase(owner: Any, next: TransferPhase?) {
        if (next != null) running.add(owner) else running.remove(owner)
    }

    /**
     * 구간을 두지 않는 전송을 감싼다(자동 백업 워커처럼 워크북만 직접 세우는 자리).
     * `finally`로 반드시 내려놓는다 — 예외로 빠져나가도 등재가 남지 않는다.
     */
    inline fun <T> running(owner: Any, block: () -> T): T {
        enter(owner)
        try {
            return block()
        } finally {
            exit(owner)
        }
    }

    /** [running]이 쓰는 진입. 직접 부르지 말고 [running]을 쓸 것. */
    fun enter(owner: Any) {
        running.add(owner)
    }

    /** [running]이 쓰는 이탈. 직접 부르지 말고 [running]을 쓸 것. */
    fun exit(owner: Any) {
        running.remove(owner)
    }

    /**
     * 이 캐시 파일은 아직 약속되어 있다 — 비우기가 건너뛴다.
     *
     * **소유권이 다른 자리로 넘어가는 경로가 있어서**(복호화 산출물은 가져오기가 지운다)
     * [release]를 부를 자리가 늘 있지는 않다. 그것은 해롭지 않다 — 등재는 *경로*를 지킬 뿐
     * 없는 파일을 되살리지 않는다. 다만 프로세스가 오래 살면 죽은 경로가 쌓이므로
     * **여기서 한 번 훑어 없는 것을 걷는다**(부르는 횟수가 전송 수만큼이라 비용이 없다).
     */
    fun hold(file: File) {
        // 술어를 받는 `removeAll`은 같은 이름의 멤버(`Collection`을 받는 것)와 겹쳐
        // 플랫폼 타입 수신자에서 해석이 갈린다 — 걷을 것을 먼저 모아 명시로 넘긴다.
        val dead = held.filter { !File(it).exists() }
        if (dead.isNotEmpty()) held.removeAll(dead.toSet())
        held.add(file.absolutePath)
    }

    /** 약속이 닫혔다(건넸거나 버렸다). */
    fun release(file: File) {
        held.remove(file.absolutePath)
    }

    /**
     * 비우기가 **건드리지 않을** 절대 경로 전량.
     *
     * @param retryPath [ExportRetryStore.rawPath]가 낸 값 — 프로세스 밖(prefs)에 사는
     *   약속이라 부르는 쪽이 읽어 넘긴다. 이 객체가 `Context`를 들지 않게 하는 것이 이유다
     *   (그래야 순수 JVM 시험이 이 판정에 닿는다).
     */
    fun protectedPaths(retryPath: String?): Set<String> =
        if (retryPath == null) held.toSet() else held + retryPath

    /** 시험 전용 — 회차 사이에 상태가 새지 않게 한다. */
    internal fun resetForTest() {
        running.clear()
        held.clear()
    }
}
