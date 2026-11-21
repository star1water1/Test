package com.novelcharacter.app.util

import android.content.Context
import android.net.Uri
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * **사용자가 고른 이미지를 끝까지 들인다** — 세 화면(캐릭터 편집·세계관 목록·작품 목록)의
 * 공용 경로.
 *
 * ## 왜 공용인가 — 같은 결함이 세 자리에 있었다
 *
 * 셋 다 `viewLifecycleOwner.lifecycleScope`에서 `ImageImportHelper.importImage`를 돌렸고,
 * 그래서 **회전 한 번이면 루프가 끊겼다.** 끊긴 뒤의 처분만 자리마다 달랐다:
 *
 * | 자리 | 종전 처분 |
 * |---|---|
 * | 캐릭터 편집 | 남은 몫에 *"다시 고르세요"*, 쓰다 끊긴 한 장은 고아가 되고 **고지에 없었다** |
 * | 세계관·작품 목록 | **아무 말도 없었다** — 쓴 파일이 죽은 폼의 목록에 담겨 주인 없이 남았다 |
 *
 * 목록 쪽은 더 나빴다: `catch (e: Exception)`이 `CancellationException`까지 삼켜
 * **취소를 저장 실패로 셌고**, 고지는 `isAdded`에 걸려 있어 그 거짓조차 나가지 않았다.
 * 세 자리의 주석이 입을 모아 *"같은 조작이 화면마다 다르게 굴면 안 된다"*고 적어 둔 채였다.
 *
 * ## 계약
 *
 * - **고른 것은 버리지 않는다.** 루프가 [NonCancellable]이라 회전·화면 종료가 중단시키지
 *   못한다. [ImageImportHelper.importImage]는 제 안에서 `Dispatchers.IO`로 내려가므로
 *   메인 스레드를 쥐지 않는다. 쓰다 끊긴 파일이 생기지 않는 것이 이 선택의 핵심이다 —
 *   **고아의 발생 조건 자체가 없어진다.**
 * - **폼이 사라진 뒤의 몫은 라이브러리에 미배정으로 담는다**([ImageOwnershipGuard.adoptOrphans]).
 *   지우지 않는다 — 붙은 적이 없는 새 파일이라 지우면 방금 고른 것이 통째로 사라진다.
 * - **네 몫을 따로 말한다**([PickedImageNotices]). 0건은 말하지 않는다.
 * - **고지는 화면이 사라져도 간다** — 앱 컨텍스트로, 줄마다 토스트 하나로([MultiLineNotice]).
 */
object PickedImageBatch {

    /**
     * [uris]를 전부 들이고, 폼이 받지 못한 몫을 라이브러리에 담고 결과를 고지한다.
     *
     * @param prefix 저장 파일 이름의 앞머리(`char`·`universe`·`novel`)
     * @param formAlive 지금 [onSaved]로 넘겨도 되는가 — 거짓이면 그 장은 *미배정* 몫이 된다.
     *   **화면이 살아 있는지를 묻는 것**이므로 부르는 쪽의 수명 신호를 그대로 쓴다.
     * @param onSaved 폼이 받을 수 있는 장. **메인 스레드에서 불린다** — 화면을 바로 고쳐도 된다.
     */
    suspend fun importAndReport(
        app: NovelCharacterApp,
        uris: List<Uri>,
        prefix: String,
        formAlive: () -> Boolean,
        onSaved: (String) -> Unit
    ) {
        if (uris.isEmpty()) return
        val ctx: Context = app
        val settings = ImageSettingsStore(ctx).getSettings()
        val stranded = mutableListOf<String>()
        var processed = 0
        var failed = 0
        try {
            // **고른 것은 끝까지 가져온다.** 이 블록이 끊기지 않는 것이 이 함수의 요점이다.
            withContext(NonCancellable) {
                for (uri in uris) {
                    val path = try {
                        ImageImportHelper.importImage(ctx, uri, prefix, settings)
                    } catch (e: CancellationException) {
                        // 취소는 저장 실패가 아니다 — 삼키면 고지가 거짓을 말한다. 이 블록은
                        // 취소를 보지 않으므로 닿을 길은 사실상 없지만, 삼키지 않는 것이 규약이다.
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    // 성공이든 실패든 **이 장의 시도는 끝났다** — `continue`보다 앞에 둔다.
                    processed++
                    if (path == null) {
                        failed++
                        continue
                    }
                    if (!formAlive()) {
                        stranded.add(path)
                        continue
                    }
                    onSaved(path)
                }
            }
        } finally {
            report(app, stranded, notAttempted = uris.size - processed, failed = failed)
        }
    }

    /**
     * 폼이 받지 못한 몫을 담고 네 몫을 고지한다.
     *
     * `finally`에 있는 것은 의도다 — 바깥 스코프가 끊겨도 **담는 일과 말하는 일은 끝낸다.**
     */
    private suspend fun report(
        app: NovelCharacterApp,
        stranded: List<String>,
        notAttempted: Int,
        failed: Int
    ) {
        if (stranded.isEmpty() && notAttempted <= 0 && failed <= 0) return
        val ctx: Context = app
        withContext(NonCancellable + Dispatchers.IO) {
            // `adoptOrphans`는 참조·보류·meta·드래프트가 보호하는 경로를 건너뛰므로
            // 정상적으로 붙은 것은 건드리지 않는다. 예외 하나에 0을 돌려주므로
            // **담지 못한 몫**이 따로 세어진다(그 침묵이 종전의 결함이었다).
            val adopted = if (stranded.isEmpty()) 0 else runCatching {
                ImageOwnershipGuard.adoptOrphans(app.database, ctx, stranded)
            }.getOrDefault(0)
            val lines = PickedImageNotices.of(adopted, stranded.size, notAttempted, failed)
                .map { (notice, count) -> ctx.getString(stringOf(notice), count) }
            if (lines.isEmpty()) return@withContext
            withContext(Dispatchers.Main) { MultiLineNotice.toastEachLine(ctx, lines) }
        }
    }

    /** 고지 글자 — 세 화면이 같은 자원을 쓴다(같은 사실을 화면마다 다르게 말하지 않는다). */
    private fun stringOf(notice: PickedImageNotices.Notice): Int = when (notice) {
        PickedImageNotices.Notice.STRANDED_ADOPTED -> R.string.image_import_stranded_adopted
        PickedImageNotices.Notice.STRANDED_FAILED -> R.string.image_import_stranded_failed
        PickedImageNotices.Notice.NOT_ATTEMPTED -> R.string.image_import_interrupted
        PickedImageNotices.Notice.SAVE_FAILED -> R.string.image_import_failed_count
    }
}
