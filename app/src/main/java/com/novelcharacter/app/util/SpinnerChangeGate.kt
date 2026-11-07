package com.novelcharacter.app.util

import java.util.WeakHashMap

/**
 * 스피너의 `onItemSelected`가 **사용자가 고른 것**인지 가른다.
 *
 * `Spinner.setSelection`의 콜백은 그 자리에서 오지 않는다 — 다음 레이아웃 때 **뒤늦게**
 * 온다. 그래서 *"적재 중에는 더티를 세우지 않는다"*를 플래그(적재 동안만 켜는 억제)로
 * 지키면, 콜백이 도착할 즈음 플래그는 이미 내려가 있어 **열기만 한 캐릭터가 미저장이 된다.**
 * 종전에는 리스너가 *첫 콜백 한 번만* 삼켰는데, 첫 레이아웃이 적재보다 먼저 오면 그 한 번을
 * 초기 위치가 써 버려 적재 콜백이 더티를 세웠다(값 있는 SELECT 필드의 캐릭터에서 거의
 * 늘 — S5 에뮬레이터 2026-11-07).
 *
 * 그래서 **시점이 아니라 위치로** 가른다: 코드가 고를 때마다 그 위치를 기준선으로 적고
 * ([markProgrammatic]), 콜백의 위치가 기준선과 **다를 때만** 사용자 선택으로 센다
 * ([onSelected]). 콜백이 언제 오든 판정이 같다. 같은 항목을 다시 고르는 것은 스피너가
 * 콜백을 내지 않으므로 놓칠 것이 없다.
 *
 * 키는 위젯이다 — 폼을 다시 세우면 옛 위젯은 버려지므로 약한 참조로 든다.
 */
class SpinnerChangeGate {

    private val baseline = WeakHashMap<Any, Int>()

    /** 코드가 [position]을 골랐다(또는 고를 참이다) — 그 위치의 콜백은 사용자 선택이 아니다. */
    fun markProgrammatic(spinner: Any, position: Int) {
        baseline[spinner] = position
    }

    /**
     * 콜백 하나를 받는다. 사용자가 **바꾼** 것이면 true.
     *
     * 기준선이 없는 위젯(등록 전)의 콜백은 바꾼 것으로 세지 않는다 — 처음 보는 위치를
     * 기준선으로 삼을 뿐이다.
     */
    fun onSelected(spinner: Any, position: Int): Boolean {
        val before = baseline.put(spinner, position)
        return before != null && before != position
    }

    fun clear() = baseline.clear()
}
