package com.novelcharacter.app.util

/**
 * AI 검토 창의 **회차 체크 상태** — 뷰 밖에 산다 (R-38, 순수 로직).
 *
 * ## 무엇을 막는가
 *
 * 검토 창은 *이미 결제된* 응답을 늘어놓고 사용자에게 고르게 한다. 그 체크를 위젯에만 두면
 * 회전 한 번에 판단이 통째로 풀리는데, **체크가 풀린 화면은 "내가 아직 안 골랐다"와
 * 구별되지 않는다**(R-28의 원문). 사용자는 자기가 껐던 것이 다시 켜진 줄 모른 채 [적용]을
 * 누르고, 마음에 안 들던 초안이 그대로 깔린다.
 *
 * ## 왜 `seeded`인가 — *만졌는가*로는 부족했다
 *
 * 종전 구현은 `touched`(사용자가 한 번이라도 만졌는가)였고 그것이 결함이었다(콜드 검토
 * 2026.08.21). 만진 뒤로는 전 행이 [isChecked] 하나만 보는데 그 집합에는 *만진 행*만 들어
 * 있어서, 체크 하나를 켜고 회전하면 **기본으로 켜져 있던 나머지가 통째로 꺼졌다** — 이
 * 상태가 없애려던 것과 정확히 같은 부류가 반대 방향으로 남았다.
 *
 * 지금은 첫 조립에서 기본 규칙의 결과를 [seedDefaults]가 **그대로 심는다.** 그러면
 * [isChecked] 하나가 언제나 전 행의 답이다.
 *
 * ## 왜 제네릭인가
 *
 * 키가 필드키(문자열)인 창과 필드 id(Long)인 창이 함께 있다. **규칙을 두 벌로 적으면
 * 반드시 갈린다** — 위의 `touched` → `seeded` 수정이 한쪽에만 적용되는 것이 그 모양이다.
 */
class AiCheckState<K> {

    private var seeded = false
    private val checked = mutableSetOf<K>()

    /**
     * 회차의 **첫 조립에서만** 기본 선택을 심는다. 두 번째부터는 아무 일도 하지 않는다 —
     * 그래야 회전으로 다시 조립돼도 사용자의 판단이 기본 규칙에 덮이지 않는다.
     */
    fun seedDefaults(defaultOn: Collection<K>) {
        if (seeded) return
        seeded = true
        checked.clear()
        checked.addAll(defaultOn)
    }

    fun setChecked(key: K, on: Boolean) {
        if (on) checked.add(key) else checked.remove(key)
    }

    fun isChecked(key: K): Boolean = key in checked

    /** 회차가 끝났다 — 다음 회차는 다시 기본 규칙에서 시작한다. */
    fun clear() {
        seeded = false
        checked.clear()
    }
}
