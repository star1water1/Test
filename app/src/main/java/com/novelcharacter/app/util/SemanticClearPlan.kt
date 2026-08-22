package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SemanticRole

/**
 * 시맨틱 필드를 **비웠을 때** 파생 이력을 어떻게 정리할지 (순수 로직 — JVM 시험 대상).
 *
 * ## 왜 이 판단이 어려운가 — 부재가 두 가지를 뜻한다
 *
 * 필드를 비우면 값 행 자체가 사라진다(저장 규약이 *"빈 값은 제외되어 들어온다 = 비움 의도"*로
 * 명문화했다). 그래서 동기화 쪽이 받는 값 목록에는 **비운 필드가 아예 없고**, 한 번도 적은
 * 적 없는 필드도 똑같이 없다. 이 둘을 구별하지 못하면 어느 쪽으로든 사고가 난다.
 *
 * - 구별을 포기하고 **아무것도 안 하면** — 생일을 지워도 `__birth`가 남아 알림·홈 배너·
 *   위젯이 계속 울리고, 상태변화 편집 창이 그 값을 필드에 되쓴다(종전 동작).
 * - 부재를 곧 **비움으로 읽으면** — 폼이 그 칸을 그리지도 않은 저장이나 값 전량을 넘기는
 *   경로에서 **연표 사건으로 만든 `__birth`가 지워진다.** 원 결함보다 나쁘다.
 *
 * 그래서 *"이 화면이 그 칸을 그렸는데 빈 채로 돌아왔다"* 또는 *"이 파일이 그 칸을 지웠다"*를
 * **아는 쪽만** 범위를 넘기고, 그 범위 안에서만 비움으로 읽는다.
 */
object SemanticClearPlan {

    /**
     * 비워진 역할들. [clearableFieldIds]가 `null`이면 **아무것도 판정하지 않는다**(빈 집합).
     *
     * @param fields 이 세계관의 필드 정의
     * @param values 지금 저장되는 값 — 빈 값은 값이 없는 것으로 본다
     * @param clearableFieldIds 비움을 판정해도 되는 필드 id
     */
    fun clearedRoles(
        fields: List<FieldDefinition>,
        values: List<CharacterFieldValue>,
        clearableFieldIds: Set<Long>?
    ): Set<SemanticRole> {
        if (clearableFieldIds == null) return emptySet()
        val filled = values.filter { it.value.isNotBlank() }.mapTo(HashSet()) { it.fieldDefinitionId }
        return fields
            .filter { it.id in clearableFieldIds && it.id !in filled }
            .mapNotNullTo(LinkedHashSet()) { SemanticRole.fromConfig(it.config) }
    }

    /**
     * 출생 이력을 어떻게 남길 것인가.
     *
     * @param delete 행을 통째로 지운다 — 연도·월·일이 **전부** 비었을 때만이다
     * @param year 남길 연도. `0`은 *미상*이고, 이 저장소의 다른 계층이 이미 그 뜻으로 읽는다
     */
    data class BirthClear(val delete: Boolean, val year: Int, val month: Int?, val day: Int?)

    /**
     * **지운 칸에 해당하는 만큼만 지운다.**
     *
     * 출생연도를 비웠다고 월·일까지 날리면 사용자가 지우지 않은 것을 지우는 것이다(그 월·일은
     * 생일 필드나 연표 사건에서 왔을 수 있다). 반대로 셋이 다 비면 남길 것이 없으므로 행을
     * 지운다 — 연도 0에 월·일도 없는 이력은 어느 화면에서도 뜻을 갖지 못한다.
     */
    fun planBirthClear(
        existingYear: Int,
        existingMonth: Int?,
        existingDay: Int?,
        clearYear: Boolean,
        clearDate: Boolean
    ): BirthClear {
        val year = if (clearYear) 0 else existingYear
        val month = if (clearDate) null else existingMonth
        val day = if (clearDate) null else existingDay
        return BirthClear(
            delete = year == 0 && month == null && day == null,
            year = year, month = month, day = day
        )
    }
}
