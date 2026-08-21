package com.novelcharacter.app.util

/**
 * 복원 미리보기가 **필드값 한 범주**를 세는 동안 들고 다니는 것 (B-187).
 *
 * ## 왜 이것이 하나여야 하는가
 *
 * 한 범주는 **두 경로**에서 값을 받는다 — 본 시트의 필드 열(캐릭터/작품/연표)과 오버플로 시트
 * ('캐릭터 필드값'/'작품 필드값'/'사건 필드값'). 셋 × 둘 = 여섯 자리가 같은 계수에 더해지므로,
 * 계수와 겹과 권위 쌍이 **한 통에** 있어야 한다. 자리마다 지역 변수로 두면 두 경로가
 * 서로의 쓰기를 못 보고, 그러면 같은 파일이 *신규 2*로 세어진다(B-233이 캐릭터에서 겪은 그 모양).
 *
 * ## 세는 단위는 **행이 아니라 칸**이다
 *
 * 이 범주의 `inBackup`은 *파일이 적어 둔 **필드값 칸***이다. 오버플로 시트는 한 행이 한 칸이라
 * 행 수와 같고, 본 시트는 한 행이 필드 열 수만큼의 칸을 낸다. 단위를 칸으로 두는 이유는 셋이다:
 *
 * 1. **한 범주가 두 시트에 걸쳐 있어** 행을 단위로 하면 서로 다른 두 수를 더하게 된다.
 * 2. **사용자가 알아야 할 사실이 칸이다** — *'이 캐릭터가 바뀐다'*가 아니라 *'필드값 40건이 덮인다'*.
 * 3. `existingTotal`(값 표의 행 수)과 **단위가 같아야** `onlyInDb`가 뜻을 갖는다.
 *
 * ## 항등식 — `inBackup = new + update + unchanged + cleared + skipped`
 *
 * B-237이 `CategoryAnalysis`에 세운 것을 **[cleared]까지 넓힌 꼴**이다(비움은 신규도 변경도
 * 아니면서 표를 바꾼다). [FieldValueCellEffect.NONE]만 어디에도 안 들어가는데, 그 갈래는
 * *셀도 비었고 지울 것도 없다* — 파일이 그 자리에 값을 적어 두지 않았다는 뜻이라
 * '파일이 적어 둔 칸'에 세지 않는 것이 맞다. `FieldValueScanTest`가 이 항등식을 잰다.
 */
class FieldValueScan(private val values: FieldValueOverlay) {

    var inBackup: Int = 0; private set
    var newCount: Int = 0; private set
    var updateCount: Int = 0; private set
    var unchangedCount: Int = 0; private set
    var clearedCount: Int = 0; private set
    var skippedCount: Int = 0; private set

    /** 본 시트가 이미 처리한 `(소유자, 필드)` — 오버플로 시트는 그 쌍을 무시한다(가져오기와 같다). */
    private val owned = HashSet<Pair<Long, Long>>()

    /** 지금 DB에 있는 값의 수 — 겹을 실을 때 이미 센 것이라 다시 묻지 않는다. */
    val existingTotal: Int get() = values.loadedRows

    /**
     * `(소유자, 필드)`의 **저장된 값** — 계산 열이 *앱 자신의 출력인가*를 가리는 재료다
     * ([com.novelcharacter.app.util.CalculatedCellEcho]). 없으면 `null`.
     *
     * 세는 일과 무관한 읽기라 계수에 영향을 주지 않는다.
     */
    fun stored(ownerId: Long, fieldId: Long): String? = values.get(ownerId, fieldId)

    /**
     * 칸 하나를 센다 — **여섯 자리가 전부 이 함수를 지난다.**
     *
     * 겹을 함께 갱신하는 것이 요점이다: 같은 `(소유자, 필드)`를 파일이 두 번 적으면
     * 가져오기는 *신규 1 + 갱신 1*을 하는데, 갱신하지 않으면 미리보기가 *신규 2*라 말한다.
     *
     * @param ownerId 캐릭터·작품·사건의 id. **미리보기가 만들 소유자는 음수**라 어떤 기존 값과도
     *   짝지어지지 않는다 — 그래서 그 소유자의 칸은 자연히 전부 '신규'다.
     * @param columnPresent 값을 담을 열이 파일에 있었는가([FieldValueCellPlan] 참조).
     */
    fun count(ownerId: Long, fieldId: Long, cell: String, columnPresent: Boolean = true) {
        owned.add(ownerId to fieldId)
        when (FieldValueCellPlan.effectOf(cell, values.get(ownerId, fieldId), columnPresent)) {
            FieldValueCellEffect.NEW -> {
                inBackup++; newCount++; values.put(ownerId, fieldId, cell)
            }
            FieldValueCellEffect.UPDATE -> {
                inBackup++; updateCount++; values.put(ownerId, fieldId, cell)
            }
            FieldValueCellEffect.UNCHANGED -> {
                inBackup++; unchangedCount++
            }
            FieldValueCellEffect.CLEAR -> {
                inBackup++; clearedCount++; values.remove(ownerId, fieldId)
            }
            FieldValueCellEffect.NONE -> Unit
        }
    }

    /**
     * 파일이 값을 적어 두었는데 **미리보기가 그 칸의 처분을 약속하지 않는다.** 두 부류다:
     *
     * 1. **가져오기가 그 칸을 쓰지 않는다** — 소유자·필드를 확정하지 못했거나(선행 범주가
     *    빠졌다·이름이 모호하다) 열이 통째로 버려졌다.
     * 2. **쓰기는 하되 그 뒤 값이 다른 필드로 옮겨 앉는다** — 세계관을 옮기는 캐릭터가 그
     *    자리다(B-253). 처분은 `(소유자, 필드)` 짝 위에서 나는데 재매핑이 그 짝을 갈아치우므로,
     *    '동일'이라 세어 둔 칸조차 실제로는 옮겨 간다. **모르는 것을 아는 척하지 않는 자리**다.
     *
     * **빈 칸에는 부르지 않는다** — 버려진 열의 빈 칸은 아무 일도 일어나지 않는 자리이고,
     * 그것까지 세면 정상 파일에서도 '건너뜀'에 숫자가 붙는다(R-33 ⑥의 같은 근거).
     */
    fun skip() {
        inBackup++; skippedCount++
    }

    /** 이 `(소유자, 필드)`를 본 시트가 이미 처리했는가 — 오버플로 시트가 묻는다. */
    fun isOwned(ownerId: Long, fieldId: Long): Boolean = (ownerId to fieldId) in owned
}
