package com.novelcharacter.app.excel

import com.novelcharacter.app.R

/**
 * 이미지 수록 고지의 **문구 자원 표** — 판정은 [ImageZipReport]가 순수하게 하고, 여기서는
 * 그 갈래를 화면 문구에 잇기만 한다.
 *
 * **표를 하나로 두는 이유**(B-225): 이미지를 싣는 산출물이 둘이다 — 엑셀 백업(ZIP 래핑)과
 * 월드패키지. 같은 사유를 두 화면이 다른 말로 부르면 사용자가 같은 일을 두 번 배워야 하고,
 * 한쪽에만 사유가 늘면 다른 쪽은 조용히 낡는다. 실제로 그 비대칭이 이 행의 본체였다 —
 * 엑셀은 손실을 3종으로 갈라 말하는데 월드패키지는 계수 자체가 없었다.
 *
 * 이 파일이 `R`을 참조하므로 순수 계층이 아니다. **판정을 여기 들이지 말 것** — 순수
 * JVM 하네스가 못 닿는 자리가 되고, 그것이 종전에 '완전한 백업입니다'가 시험 없이
 * 나가던 이유였다.
 */
object ImageNoticeRes {

    /** 제외 사유 → "· 파일을 찾을 수 없는 참조: %1$d건" 꼴의 내역 한 줄. */
    fun lossReason(reason: ImageLossReason): Int = when (reason) {
        ImageLossReason.MISSING -> R.string.export_images_detail_missing
        ImageLossReason.OUTSIDE_APP_DIR -> R.string.export_images_detail_outside
        ImageLossReason.FAILED -> R.string.export_images_detail_failed
    }
}
