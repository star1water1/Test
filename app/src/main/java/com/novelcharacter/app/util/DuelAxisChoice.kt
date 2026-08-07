package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelAxis

/**
 * 필드 편집 창의 **대결 축 고르기** — 무엇을 목록에 싣고 무엇을 고른 채로 열 것인가 (B-134).
 *
 * 설계 정본은 `docs/feature_roadmap_2026-08.md` **4장**(대결 등급 산정)이다.
 *
 * ## 왜 순수 계층으로 떼어냈는가 — 화면이 못 지키는 불변식이라서
 *
 * 종전 배선은 *"가리키던 축 code를 못 찾으면 [android.widget.Spinner.setSelection]을 부르지
 * 않는다"*였고, 주석은 그 자리에 **"조용히 0번 축으로 옮기면 다른 축의 순위가 이 필드에
 * 배정된다(오배정)"**라고 적어 두었다. **그런데 안 부르는 것이 곧 0번이다** —
 * `AbsSpinner.setAdapter`는 항목이 하나라도 있으면 그 자리에서 0번을 선택한다.
 * 그래서 막으려던 오배정이 정확히 일어났다(R-1이 금지하는 그 부류이며, 오배정은 생략보다
 * 나쁘다 — 사용자는 틀린 값을 *채워진 값*으로 본다).
 *
 * **어댑터를 채우는 것과 선택을 정하는 것을 한 계산으로 묶으면 그 틈이 없어진다.** 여기서
 * 나온 [Choice.items]가 곧 스피너의 항목이고 [Choice.selection]이 곧 초기 선택이므로,
 * 화면은 **자기가 판정할 것이 없다** — 위치로 항목을 되찾는 일([Choice.axisAt])까지
 * 이 계산이 든다.
 *
 * ## 댕글링일 때 자리 하나를 더 세우는 이유
 *
 * 끊긴 참조를 **선택 없음으로 표시**해야 하는데, 스피너에는 "아무것도 안 고른 상태"가 없다
 * (항목이 있으면 언제나 하나가 선택돼 있다). 그래서 [items] 맨 앞에 **null 한 자리**를 세운다 —
 * 화면은 그 자리를 *"축을 고르세요"*로 그리고, [axisAt]은 그 자리에서 null을 돌려준다.
 * 그러면 사유 줄(`duel_grade_axis_missing`)이 **살아나고**(종전에는 도달 불가라 죽은 문자열이었다)
 * 저장은 화면이 만든 값 대신 저장된 약속을 그대로 실어 나른다.
 *
 * **자리를 세우지 않고 불리언 하나로 두면 안 된다** — 그러면 목록의 0번이 실재하는 첫 축이라,
 * 사용자가 그 첫 축을 고르는 순간 *선택이 바뀌지 않아* `onItemSelected`가 아예 오지 않는다.
 * 고쳤는데 사유 줄이 그대로 남는 것이고, 그 상태는 사용자가 풀 길이 없다.
 *
 * ## 끊긴 code를 화면이 지우지는 않는다
 *
 * 축은 휴지통에서 되살아날 수 있고, 그때 저장돼 있던 컷이 그대로 다시 들어맞는다
 * (`tools/check_config_boundary.sh`의 예외 목록이 복원을 두고 같은 판단을 적어 두었다).
 * 그래서 이 계산은 **보여 주는 방식만** 정하고 config는 건드리지 않는다.
 */
object DuelAxisChoice {

    /**
     * @property items 스피너에 실을 항목. **null 한 자리는 "축을 고르세요" 자리다**(댕글링일 때만
     *   맨 앞에 선다). 축이 하나도 없으면 비어 있다 — 그때 할 말은 *"이 세계관에 축이 없다"*이지
     *   *"고르세요"*가 아니라서, 그 문구는 화면이 따로 든다.
     * @property selection 창을 열 때의 선택 위치. [items]가 비어 있어도 0이다(스피너는 자리
     *   표시용 항목 하나를 그린다).
     * @property dangling 가리키던 축을 목록에서 찾지 못했다 — 축이 지워졌거나 다른 세계관 것이다.
     */
    data class Choice(
        val items: List<DuelAxis?>,
        val selection: Int,
        val dangling: Boolean
    ) {
        /** 스피너 위치 → 축. 자리 표시용 항목과 범위 밖은 null이다(고른 축이 없다는 뜻). */
        fun axisAt(position: Int): DuelAxis? = items.getOrNull(position)
    }

    /**
     * @param axes 이 세계관의 캐릭터 대결 축들(화면이 이미 걸러 온 목록).
     * @param storedCode config가 가리키던 축 code. 새 필드거나 아직 안 고른 상태면 null이다 —
     *   **그때는 첫 축을 고른 채로 연다.** 저장된 약속이 없으니 어긋날 것이 없고, 고르라고
     *   한 번 더 시키는 것은 마찰이다(원칙 04). 오배정이 성립하려면 *"가리키던 것과 다른 것을
     *   가리키게 되는"* 일이 있어야 하는데 가리키던 것이 없다.
     */
    fun resolve(axes: List<DuelAxis>, storedCode: String?): Choice {
        if (axes.isEmpty()) return Choice(emptyList(), 0, dangling = false)
        val code = storedCode?.takeIf { it.isNotBlank() }
            ?: return Choice(axes, 0, dangling = false)
        val index = axes.indexOfFirst { it.code == code }
        if (index >= 0) return Choice(axes, index, dangling = false)
        return Choice(listOf(null) + axes, 0, dangling = true)
    }
}
