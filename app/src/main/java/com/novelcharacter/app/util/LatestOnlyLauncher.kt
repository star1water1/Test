package com.novelcharacter.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * **마지막 요청만 살아남는** 코루틴 실행기 — 새 요청이 오면 앞선 것을 취소한다.
 *
 * 편집 폼의 작품 스피너 콜백이 쓰는 자리다. 콜백마다 코루틴을 띄우면 둘이 **겹친다**:
 * 회전 복원에서는 스피너 초기 콜백(0번 — 전역 필드 폼)과 곧 따라오는 작품 콜백이 거의
 * 동시에 뜨고, 앞의 것이 DB 조회로 멈춘 사이 뒤의 것이 폼을 다시 짓고 보존값을 복원한다.
 * 그런 뒤 앞의 것이 깨어나면 **이미 남의 것이 된 폼**에 DB 값을 덮어 써서, 회전 한 번에
 * 적던 동적 필드가 소리 없이 DB 값으로 되돌아갔다(DB가 차가운 첫 회전에서만 보이는 경쟁).
 *
 * 앞선 요청은 **뒤의 요청이 대신한다** — 폼은 언제나 지금 선택된 작품의 것이어야 하므로
 * 늦게 끝난 옛 요청의 결과는 쓸 데가 없다.
 */
class LatestOnlyLauncher {
    private var job: Job? = null

    /** 마지막으로 띄운 요청이 아직 끝나지 않았는가. */
    val isRunning: Boolean get() = job?.isActive == true

    fun launch(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job {
        job?.cancel()
        return scope.launch(block = block).also { job = it }
    }
}
