package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.FieldFilter

/**
 * **다른 세계관의 대결 축을 이 세계관으로 옮기는 규칙** (B-116).
 *
 * 사용자 요청의 뿌리는 원칙 01·05다 — 이 앱은 **필드에 대해 이미 같은 것을 하고 있고**
 * (`FieldViewModel.collectSources` → 다른 세계관의 필드를 골라 심는다), '강함'·'아름다움'처럼
 * 축 이름은 작품을 넘어 반복된다. 매번 다시 만드는 것은 마찰이다(원칙 04).
 *
 * ## 옮기는 것은 **축의 정의**뿐이다
 * 쌓인 판은 따라가지 않는다(4장 B-116 행 ⓑ) — 참가자가 남의 세계관 캐릭터라 코드가 이쪽에서
 * 아무것도 가리키지 않는다. 판이 따라오면 순위표가 **존재하지 않는 참가자의 점수**를 낸다.
 *
 * ## 새로 나야 하는 값 넷 — 그래서 [transfer]가 전부 인자로 받는다
 *
 * | 값 | 왜 새로 나야 하는가 |
 * |---|---|
 * | `id` | 새 행이다 |
 * | `code` | **전역 유니크다**(`DuelAxis` 인덱스). 그대로 두면 삽입이 유니크 위반으로 죽고, 죽지 않더라도 `duelGrade.axisCode`를 든 남의 필드 config가 **이 축을 정확히 찾는다** — 오배정은 생략보다 나쁘다(R-1 · R-35) |
 * | `universeId` | 옮기는 자리 그 자체 |
 * | `displayOrder` | 받는 목록의 끝에 붙는다 |
 *
 * **[transfer]가 이 넷을 인자로 받는 것은 순수하기 위해서만이 아니다** — 무엇이 반드시
 * 새로 나야 하는지가 **서명에 적혀** 다음 사람이 `copy()` 한 줄로 우회할 수 없다.
 *
 * ## 함께 내리는 것 하나: [DuelAxis.isBasisAxis]
 * 기준 축은 *"이 세계관의 대표 이미지 추첨과 정리를 무엇이 이끄는가"*이고 **세계관당 하나**다.
 * 켜진 채로 들어오면 `DuelRepository.saveAxis`가 같은 트랜잭션에서 **받는 세계관의 기존 기준을
 * 조용히 내린다** — 사용자는 축을 하나 가져왔을 뿐인데 대표 그림이 바뀌고, 그 둘 사이에
 * 아무 연결도 안 보인다(2026.08.11에 같은 모양을 한 번 겪었다 — `clearBasisExcept`의 KDoc).
 * 그래서 **받은 축은 언제나 기준이 아니다.** 켜고 싶으면 축 편집 창에서 켠다(그 창은 지금
 * 무엇이 기준인지 먼저 말한다).
 *
 * ## 후보 필터는 **키만** 데려온다
 * [FieldFilter.fieldId]는 세계관을 넘는 순간 뜻을 잃는데, `DuelCandidateFilter.resolve`의
 * 사다리에는 **키가 없을 때 id로 찾는 갈래**가 있다(손으로 만든 엑셀 파일을 살리려고 남긴
 * 폴백이다). 그 id가 받는 세계관에서 우연히 실재하면 **엉뚱한 필드로 정확히 해석된다** —
 * R-1이 말하는 오배정이고, 조용해서 더 나쁘다. 그래서 옮길 때 id를 0으로 지운다:
 * 키가 있으면 키로 풀리고(`roster()`가 그때 id를 이쪽 것으로 다시 박는다), 키가 없으면
 * **해석 실패로 닫힌다**(후보 0 + 화면 고지) — 조용히 넓어지는 것보다 낫다.
 */
object DuelAxisTransfer {

    /**
     * 옮길 축 하나의 계획 — 화면이 **누르기 전에** 무엇이 될지 말하는 재료다(원칙 04).
     *
     * @property source 원본 축(남의 세계관 것). 이름·대상을 화면이 그대로 쓴다.
     * @property duplicateName 받는 세계관에 **같은 대상·같은 이름**의 축이 이미 있다.
     *   있으면 옮기지 않는다 — 이름 유니크가 `(세계관, 대상, 이름)`이라 넣으면 삽입이 죽고,
     *   덮어쓰면 **받는 쪽에 쌓인 판이 남의 축 정의를 뒤집어쓴다.** 필드 가져오기가
     *   *"이미 있는 것은 기본으로 손대지 않는다"*로 정해 둔 자리와 같은 처분이다.
     * @property missingKeys 이 세계관에 **없는** 필드 키 — 연결 셋과 후보 필터를 함께 센다.
     *   **가져오되 센다**(4장 B-116 행 ⓐ의 처분). 빼 버리면 받는 세계관에 그 필드를 나중에
     *   만들어도 연결이 돌아오지 않고, 조용히 두면 축을 열기 전에는 알 수 없다.
     */
    data class Plan(
        val source: DuelAxis,
        val duplicateName: Boolean,
        val missingKeys: List<String>
    ) {
        /** 이 계획이 실제로 들어가는가 — 화면의 기본 선택과 옮기는 쪽의 걸러내기가 같은 술어를 본다. */
        val importable: Boolean get() = !duplicateName
    }

    /**
     * 키 하나가 받는 세계관에서 **살아 있는가**.
     *
     * `sys:` 키는 세계관을 타지 않는다 — 캐릭터 표의 열이라 어느 세계관에서나 같은 자리다.
     * **이 앱이 모르는 `sys:` 키(엑셀에서 온 오타)도 결손으로 세지 않는다**: 그것은
     * *"이 세계관에 없다"*가 아니라 *"어디에도 없다"*이고, 원본에서도 이미 죽어 있었으며
     * 이미 다른 자리가 말한다(`DuelFieldLinks.Axis.unknownSystemKeys`). 여기서 함께 세면
     * **옮기기가 고칠 수 있는 결손**과 **옮기기와 무관한 오타**가 한 숫자에 섞인다.
     */
    private fun resolves(key: String, targetFieldKeys: Set<String>): Boolean =
        key in targetFieldKeys || DuelSystemFields.isSystemKey(key)

    /**
     * 이 축이 가리키는 키 중 받는 세계관에 없는 것 — **차례를 지키고 중복은 걷어낸다.**
     *
     * 연결 셋(영향·산출·프로필)과 후보 필터를 **함께** 본다. 필터를 빼면 *"필드는 다 있는데
     * 후보가 0"*인 축이 조용히 들어온다 — 필터의 해석 실패는 후보를 닫으므로(그 처분은
     * `DuelCandidateFilter`의 KDoc) 사용자가 화면에서 보는 것은 *"아무도 없다"*뿐이다.
     *
     * @param filterlessLabel 키가 아예 없는 필터(손으로 만든 엑셀 파일에서 온다)를 목록에
     *   어떻게 적을지. 이름이 있으면 그 이름이 낫고, 없으면 부르는 쪽이 문구를 정한다 —
     *   순수 계층이 말글을 들지 않는다.
     */
    fun missingKeys(
        axis: DuelAxis,
        targetFieldKeys: Set<String>,
        filterlessLabel: String = "?"
    ): List<String> {
        val missing = LinkedHashSet<String>()
        val links = axis.fieldLinks
        for (link in links.influences + links.outcomes + links.profiles) {
            if (!resolves(link.key, targetFieldKeys)) missing.add(link.key)
        }
        for (filter in DuelCandidateFilter.parse(axis.candidateFiltersJson)) {
            val key = filter.fieldKey
            if (key == null) {
                // 키 없는 필터는 **이 세계관에서 되살릴 길이 없다** — id는 남의 것이라 지우고
                // 넘기므로(클래스 KDoc) 해석이 닫힌다. 이름이라도 적어 어느 조건인지 말한다.
                missing.add(filter.fieldName.ifBlank { filterlessLabel })
            } else if (!resolves(key, targetFieldKeys)) {
                missing.add(key)
            }
        }
        return missing.toList()
    }

    /**
     * 고를 수 있는 축들의 계획 — 화면이 목록을 그리는 재료.
     *
     * @param targetAxes 받는 세계관에 이미 있는 축 전부. 이름 중복은 **같은 대상 안에서만**
     *   본다(캐릭터 축 '아름다움'과 이미지 축 '아름다움'은 서로 다른 것을 겨루는 다른 축이라
     *   `DuelAxis`의 유니크 인덱스도 대상을 함께 묶는다).
     */
    fun plan(
        sources: List<DuelAxis>,
        targetAxes: List<DuelAxis>,
        targetFieldKeys: Set<String>,
        filterlessLabel: String = "?"
    ): List<Plan> {
        val taken = targetAxes.map { it.targetType to it.name }.toSet()
        return sources.map { axis ->
            Plan(
                source = axis,
                duplicateName = (axis.targetType to axis.name) in taken,
                missingKeys = missingKeys(axis, targetFieldKeys, filterlessLabel)
            )
        }
    }

    /**
     * 옮긴 축 — 넣을 준비가 끝난 새 행.
     *
     * @param code **반드시 새로 뽑은 것**(`generateEntityCode()`). 클래스 KDoc의 표를 볼 것.
     * @param createdAt 새 행의 만든 시각. 원본의 것을 물려주지 않는 것은 *이 세계관에서
     *   언제 생겼는가*가 이 행의 사실이기 때문이다 — 목록의 안정 정렬 꼬리(`id`)와 달리
     *   이 값은 사람이 보는 자리에도 쓰인다.
     */
    fun transfer(
        source: DuelAxis,
        targetUniverseId: Long,
        displayOrder: Int,
        code: String,
        createdAt: Long
    ): DuelAxis = source.copy(
        id = 0L,
        universeId = targetUniverseId,
        displayOrder = displayOrder,
        code = code,
        createdAt = createdAt,
        // 받은 축은 언제나 기준이 아니다 — 클래스 KDoc의 근거.
        isBasisAxis = false,
        candidateFiltersJson = transferFilters(source.candidateFiltersJson)
    )

    /**
     * 후보 필터를 세계관 경계 너머로 — **id를 지우고 키만 남긴다**(클래스 KDoc).
     *
     * 값이 없으면 `null`로 둔다. 저장 표기를 하나로 두는 것은 `DuelAxisListFragment`가
     * 세운 규약과 같다(*"조건이 없으면 `{}`가 아니라 null"*).
     */
    private fun transferFilters(json: String?): String? {
        val filters = DuelCandidateFilter.parse(json)
        if (filters.isEmpty()) return null
        return DuelCandidateFilter.encode(filters.map { it.copy(fieldId = 0L) })
    }
}
