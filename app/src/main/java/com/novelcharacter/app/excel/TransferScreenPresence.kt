package com.novelcharacter.app.excel

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * 전송(내보내기·가져오기)의 **종결 고지를 어디로 보낼지**를 가르는 단 하나의 판정.
 *
 * ## 무엇이 틀렸었나
 *
 * 종전에는 `ExcelExporter`와 `ExcelImporter`가 **같은 함수를 각자 복제**해 들고 있었고,
 * 그 판정이 *"액티비티 인스턴스가 살아 있는가"*(`!isFinishing && !isDestroyed`)였다.
 * 그런데 `EXPORT_SAVE`·가져오기 본구간은 **화면이 사라져도 끝까지 도는** 구간이다 —
 * 홈 키로 나가면 액티비티는 `onStop`일 뿐 파괴되지 않으므로 그 판정이 참이 되어,
 * 종결 고지가 **토스트 갈래**로 빠졌다. API 30+는 백그라운드 토스트를 막으므로
 * 사용자는 **아무 고지도 못 받고**, 알림도 보관함도 남지 않아 다음 진입에서 이을 것도 없었다.
 * 정작 고지가 가장 필요한 '백그라운드 완료'가 판정에서 빠져 있던 셈이다.
 *
 * ## 그래서 잣대를 바꿨다 — 생존이 아니라 **가시성**
 *
 * `STARTED` 이상이면 화면이 보이는 중이라 토스트가 실제로 뜨고, 그 아래면 보이지 않으므로
 * 알림 + 보관함으로 보낸다(다음 진입의 '지난 작업 결과'가 그것을 잇는다).
 * 회전은 종전대로 화면 파괴 경로가 담당하므로 소음이 늘지 않는다.
 *
 * ## 왜 한 자리인가
 *
 * 같은 함수가 두 파일에 복제돼 있으면 **한쪽만 고쳐진다** — 실제로 이 결함이 내보내기에서
 * 발견됐는데 가져오기가 글자 그대로 같은 침묵을 공유하고 있었다(R-33·R-34가 막는 그 모양).
 */
object TransferScreenPresence {

    /**
     * 지금 무언가를 **띄우거나 물을 수 있는** 화면이 있는가.
     *
     * `LifecycleOwner`가 아닌 액티비티는 가시성을 물을 길이 없으므로 종전 판정(생존)으로
     * 떨어진다 — 앱의 화면은 전부 `ComponentActivity` 계보라 실제로는 지나지 않는 갈래이고,
     * 여기서 `false`로 단정하면 멀쩡한 화면에 대고 알림을 쏘게 된다.
     */
    fun canShow(activity: Activity?): Boolean {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return false
        val owner = activity as? LifecycleOwner ?: return true
        return owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
