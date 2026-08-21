package com.novelcharacter.app.excel

/**
 * 전송(가져오기·내보내기)이 **화면 파괴로 끊길 때**의 처분 — 순수 판정 (B-228).
 *
 * ## 이 파일이 생긴 이유
 *
 * B-56은 *"작업은 화면이 사라져도 계속 돈다"*를 전제로 결과 고지를 화면 밖으로 보냈다.
 * **그 전제가 코드에서 성립하지 않았다** — `ExcelTransferController`의 `onDestroy`가
 * `ExcelExporter.cancel()`·`ExcelImporter.cleanup()`(당시 이름)으로 **진행 중인 작업을 통째로
 * 취소했다.** 프래그먼트 파괴는 화면 전환·회전으로 늘 일어나므로, 그때마다 전송이 끊겼다.
 *
 * 더 나쁜 것은 **그 끊김이 무고지**였다는 점이다. 취소된 코루틴 안에서는
 * `withContext(Dispatchers.Main)`이 그 자리에서 던지므로, 고지를 하려던 catch 블록이
 * **고지에 닿기 전에 다시 튕겨 나간다** — 토스트도 이력도 알림도 하나도 남지 않는다.
 * 사용자가 보는 것은 *"진행 창이 사라지고 아무 일도 일어나지 않은 것"*뿐이다.
 *
 * ## 두 가지를 가른다
 *
 * **ⓐ 끊어도 되는 구간과 끊으면 안 되는 구간.** [TransferPhase.stopsOnScreenLoss]가 그 판정이다.
 * 이미 코드 주석이 *"이 구간부터는 취소를 제공하지 않는다"*라고 선언한 자리가 둘 있었는데
 * (이미지 되살리기 · 반영), **화면 파괴는 그 선언을 무시하고 끊고 있었다.** 선언이 옳으므로
 * 코드를 선언에 맞춘다 — 그 구간은 `NonCancellable`로 감싸 끝까지 가고, 결과는 스스로 말한다.
 *
 * **ⓑ 사용자의 취소와 화면의 파괴.** 사용자가 취소 버튼을 눌렀으면 그 사람은 화면 앞에 있고
 * 토스트가 이미 말한다. 화면이 파괴된 것은 사용자가 고른 것이 아니므로 **화면 밖으로** 말해야
 * 한다(알림 + 다음 진입 보관함). 둘을 가르지 않으면 취소 한 번에 고지가 두 번 뜬다.
 *
 * ## 왜 순수 클래스인가
 *
 * `ExcelImporter`·`ExcelExporter`는 `Toast`·`Activity`를 들고 있어 순수 JVM 시험이 닿지 않는다
 * (B-56이 *"이 자리의 방어선은 시험이 아니라 타입이다"*라고 적어 둔 그 자리다). **판정만
 * 떼어 내면 그 판정은 시험이 잠글 수 있다** — 무엇을 말하는가가 아니라 **말하는가 마는가**가
 * 이 판의 핵심이고, 그것이 여기 있다.
 */
enum class TransferKind {
    IMPORT,
    EXPORT
}

/**
 * 전송 작업의 구간.
 *
 * 구간을 나누는 잣대는 화면이 아니라 **끊겼을 때 무엇이 남는가**다.
 */
enum class TransferPhase(
    val kind: TransferKind,
    /**
     * 화면이 사라지면 이 구간은 **끊긴다**.
     *
     * `false`인 구간은 화면이 없어져도 끝까지 간다 — 끊으면 되돌릴 수 없는 반쪽이 남기 때문이고
     * (R-26: 조용한 반쪽 상태 금지), 그 구간은 사용자에게도 취소를 제공하지 않는다.
     * **두 사실은 같은 판정의 앞뒤다** — 사용자에게 못 끊게 해 놓고 화면 파괴로 끊으면
     * 계약이 한쪽에서만 지켜진다.
     */
    val stopsOnScreenLoss: Boolean,
    /**
     * 이 구간은 **끝까지 가서 스스로 결과를 말하는가.**
     *
     * [stopsOnScreenLoss] 하나가 *끊기는가*와 *스스로 말하는가*를 겸하고 있었던 것이
     * 침묵의 뿌리다 — 끊기지 않는다고 해서 반드시 자기 결과를 말하는 것은 아니다.
     * 축을 갈라 두면 **끊기지도 않고 스스로 말하지도 않는 구간**이 드러나고,
     * 그 구간에서만 중단 고지가 나간다.
     */
    val announcesOwnOutcome: Boolean = true
) {
    /** 파일 복사·압축 해제 — 캐시에만 쓴다. 끊어도 남는 것이 없다(뒤처리는 `finally`가 한다). */
    IMPORT_INTAKE(TransferKind.IMPORT, stopsOnScreenLoss = true),

    /** 옵션·미리보기·충돌 해결을 **묻는 중** — 물을 화면이 사라졌으므로 이어 갈 수 없다. */
    IMPORT_ASK(TransferKind.IMPORT, stopsOnScreenLoss = true),

    /** 분석 — 읽기만 한다. 끊어도 바뀐 것이 없다. */
    IMPORT_ANALYZE(TransferKind.IMPORT, stopsOnScreenLoss = true),

    /**
     * 이미지 되살리기 — **트랜잭션 밖에서 파일을 만든다.**
     * 끊으면 가리킬 행이 없는 파일이 남는다(B-77이 되돌리기를 세운 그 자리).
     */
    IMPORT_RESTORE_IMAGES(
        TransferKind.IMPORT,
        stopsOnScreenLoss = false,
        // **끝까지 가는 것은 이미지 복사 블록뿐이다.** 그 블록을 빠져나온 뒤 부르는
        // `importFromXlsx`는 이미 끊긴 스코프 위에서 첫 중단점에 죽고, 그 catch는
        // *"고지는 onScreenGone이 이미 했다"*고 믿어 그대로 다시 던진다 — 실제로는
        // 아무도 안 했다. 형제 IMPORT_APPLY는 결과 창까지 통째로 NonCancellable 안이라
        // 같은 구멍이 없다. 그 차이를 이 칸이 말한다.
        announcesOwnOutcome = false
    ),

    /**
     * 반영 — 전략(덮어쓰기·병합)이 여러 표에 걸쳐 실린다.
     * 끊으면 반쯤 덮인 DB가 남는다.
     */
    IMPORT_APPLY(TransferKind.IMPORT, stopsOnScreenLoss = false),

    /**
     * 내보내기 — 산출물은 임시 파일이고, 건네려면(공유 시트·SAF) 화면이 있어야 한다.
     * 화면이 없으면 **끝까지 만들어도 줄 곳이 없으므로** 끊고 임시 파일을 지운다.
     */
    EXPORT_BUILD(TransferKind.EXPORT, stopsOnScreenLoss = true),

    /**
     * 사용자가 고른 자리(SAF)로 옮겨 쓰는 중 — **끊으면 그 자리에 반쪽 파일이
     * 멀쩡한 이름으로 남는다.** 만들다 만 임시 파일과 달리 앱이 지울 수 없는 자리이고,
     * 나중에 그것으로 복원하면 조용히 유실된다.
     */
    EXPORT_SAVE(TransferKind.EXPORT, stopsOnScreenLoss = false)
}

/**
 * 화면이 사라졌을 때 **중단을 고지할 것인가**를 정한다.
 *
 * 세 번 침묵한다. 침묵의 근거가 서로 다르므로 셋을 각각 시험이 잠근다:
 * 1. **돌던 것이 없다** — 그냥 화면을 떠난 것이다. 여기서 말하면 화면을 옮길 때마다 알림이 뜬다.
 * 2. **끊기지 않으면서 스스로 결과를 말하는 구간이다.**
 *    여기서 미리 *"중단되었습니다"*라고 하면 곧 이어 오는 *"가져왔습니다"*와 정면으로 어긋난다.
 *    **두 조건을 함께 봐야 한다** — 종전에는 *끊기지 않는다*만 보고 침묵했는데, 끊기지
 *    않는다고 해서 반드시 자기 결과를 말하는 것은 아니다([TransferPhase.IMPORT_RESTORE_IMAGES]).
 *    그 구간에서 화면을 돌리면 진행 창만 사라지고 중단 고지도 완료 결과도 없이 가져오기가
 *    조용히 죽었다 — 알림·보관함·작업 이력 어디에도 한 줄이 안 남았다.
 * 3. **사용자가 방금 취소를 눌렀다** — 그 고지는 토스트가 이미 했다(B-56이 정한 경계:
 *    거절·취소는 토스트, 작업이 돈 뒤의 종결 고지만 화면 밖으로).
 */
object TransferInterruption {

    /**
     * @param phase 지금 돌고 있는 구간. 도는 것이 없으면 null이다.
     * @param userCancelled 사용자가 이 회차의 취소를 이미 눌렀는가.
     * @return 중단을 고지할 작업 종류. 고지하지 않으면 null이다.
     */
    fun abortedKind(phase: TransferPhase?, userCancelled: Boolean): TransferKind? {
        if (phase == null) return null
        if (userCancelled) return null
        // 끊기지 않으면서 **스스로 결과를 말하는** 구간만 침묵한다.
        if (!phase.stopsOnScreenLoss && phase.announcesOwnOutcome) return null
        return phase.kind
    }
}
