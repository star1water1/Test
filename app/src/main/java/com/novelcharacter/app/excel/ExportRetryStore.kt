package com.novelcharacter.app.excel

import android.content.Context
import java.io.File

/**
 * 저장(SAF)에 실패한 내보내기 파일의 보관처 (원칙 04 · R-65).
 *
 * ## 왜 인스턴스 상태가 아니라 영속 저장인가
 *
 * [ExcelExporter.writeToUri]는 화면이 사라져도 끝까지 도는 구간([TransferPhase.EXPORT_SAVE])이라,
 * 실패가 도착했을 때 컨트롤러([ExcelTransferController])가 이미 죽어 있을 수 있다.
 * 죽은 컨트롤러의 `pendingExportFile`은 복원할 곳이 없고, var 콜백 프로퍼티가 회전에
 * null이 되는 것은 이 저장소의 반복 결함이다(R-65). 그래서 **실패 처분이 콜백보다 먼저
 * 여기에 경로를 적는다** — 컨트롤러가 살아 있으면 즉시, 죽어 있으면 다음 진입의 보관함
 * 창이 이것을 읽어 재시도를 세운다. 몇 분짜리 내보내기를 처음부터 다시 돌리게 하지 않는다.
 *
 * [com.novelcharacter.app.util.TransferNoticeRelay]와 같은 결(SharedPreferences 한 칸)이되
 * **읽어도 지우지 않는다** — 고지는 한 번 내면 끝이지만 이 파일은 사용자가 다시 저장하거나
 * 버리기 전까지 유효하다. 닫는 길은 그 두 처분([clear])과 파일 소멸([peek]의 실존 확인)뿐이다.
 */
object ExportRetryStore {

    private const val PREFS = "export_retry"
    private const val KEY_PATH = "source_path"

    /**
     * 저장을 기다리는 원본의 경로를 보관한다. **한 칸이라 덮어쓴다** — 재실패는 같은
     * 경로를, 새 저장 회차의 시작은 앞선 보관을 대체한다(사용자가 보관을 두고 새로
     * 내보냈다면 알아야 할 것은 마지막 것이다 — TransferNoticeRelay와 같은 판정).
     */
    fun store(context: Context, file: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PATH, file.absolutePath)
            .apply()
    }

    /**
     * 보관된 파일 — **실제로 남아 있을 때만.** 캐시 정리 등으로 사라진 경로는 그 자리에서
     * 지우고 null을 돌려준다(없는 파일을 두고 다시 저장하겠느냐고 물을 수는 없다).
     */
    fun peek(context: Context): File? {
        val path = rawPath(context) ?: return null
        val file = File(path)
        if (!file.exists()) {
            clear(context)
            return null
        }
        return file
    }

    /**
     * 보관된 경로 원문 — 실존 확인 없이 읽는다. [ExcelExporter]의 캐시 회전 정리가
     * 이 파일만은 지우지 않기 위해 쓴다(지우면 "보관해 두었습니다"가 거짓이 된다).
     */
    fun rawPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PATH, null)

    /** 사용자가 다시 저장했거나 버렸다 — 보관을 닫는다. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PATH)
            .apply()
    }
}
