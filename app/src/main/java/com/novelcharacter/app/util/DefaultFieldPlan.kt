package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DefaultFieldPlanKeys
import com.novelcharacter.app.data.model.DefaultFieldRef
import com.novelcharacter.app.data.model.DefaultFieldTemplate
import com.novelcharacter.app.data.model.FieldConfigTransfer
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * **전역 기본 필드의 심기·전파·강등 계획** (B-119) — 순수 계층.
 *
 * 설계 정본은 `docs/feature_roadmap_2026-08.md` **1장**이다.
 *
 * ## 이 계층이 순수한 이유 — [PresetMerge]와 같다
 *
 * 판정(어느 세계관에 심고 어디는 잇는가 · 무엇이 실제로 달라지는가)과 산정(`displayOrder`를
 * 어디에 잇는가)은 DB도 화면도 필요 없다. 떼어 두면 **미리보기가 보여 준 것과 실제로 쓰이는
 * 것이 갈리지 않는다** — 화면은 [planPlant]·[planPropagate]가 만든 것을 그리고, 쓰기는
 * [resolvePlant]·[resolvePropagate]가 만든 것을 그대로 넣는다.
 *
 * ## 이 파일이 지키는 약속 하나
 *
 * **이 기능은 어떤 경우에도 캐릭터 데이터를 지우지 않는다**(설계 1-3, 개발 의도 2번).
 * 템플릿 삭제·승격 해제는 [demote]로 표식만 걷어내고, 심기는 **이미 있는 필드를 덮지 않으며**
 * ([Placement.LINK]), 덮어쓰기는 사용자가 고른 전파에서만 일어나고 그때도 백업이 함께 난다.
 * 이 파일에 필드를 지우는 함수가 없는 것은 빠뜨린 것이 아니라 설계다.
 */
object DefaultFieldPlan {

    // ──────────────────────────────────────────────────────────────────────
    // 공통 — 템플릿이 세계관에 심겼을 때의 모습
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 템플릿이 심겼을 때 필드가 가질 `config` — **비교와 쓰기가 같은 값을 봐야 한다.**
     *
     * 두 가지를 한다:
     * 1. **세계관 한정 참조를 걷어낸다**([FieldConfigTransfer]). 템플릿은 전역이라 등급 체계·
     *    대결 축을 가리킬 수 없는데(설계 1-2), 손으로 고친 엑셀이 그 키를 실어 올 수 있다.
     *    그대로 심으면 **모든 세계관에 유령 참조가 하나씩 생긴다** — 승격이 이미 걷어내므로
     *    보통은 아무 일도 하지 않는 겹방어다.
     * 2. **연결 표식을 적는다**([DefaultFieldRef]). 이것이 심긴 필드가 원본을 가리키는 유일한 끈이다.
     */
    fun linkedConfig(template: DefaultFieldTemplate): String =
        DefaultFieldRef.write(FieldConfigTransfer.demoteAcrossUniverse(template.config), template.code)

    /**
     * 템플릿을 한 세계관의 **보통 필드**로 구체화한다 — 이 함수가 "구체화형"의 실체다(설계 1-2).
     *
     * 돌려주는 것은 평범한 [FieldDefinition]이다. 화면·통계·엑셀·검색은 이 필드가 어디서
     * 왔는지 **몰라도 된다** — 그것이 참조형을 기각하고 이 형태를 고른 이유 전부다.
     */
    fun materialize(
        template: DefaultFieldTemplate,
        universeId: Long?,
        displayOrder: Int
    ): FieldDefinition = FieldDefinition(
        id = 0,
        universeId = universeId,
        key = template.key,
        name = template.name,
        type = template.type,
        config = linkedConfig(template),
        groupName = template.groupName,
        displayOrder = displayOrder,
        isRequired = template.isRequired,
        entityType = template.entityType
    )

    /**
     * 기존 필드를 템플릿으로 **승격**한다.
     *
     * [code]·[createdAt]을 밖에서 받는 것은 이 계층이 순수하기 위해서다 — 안에서
     * `UUID.randomUUID()`·`System.currentTimeMillis()`를 부르면 같은 입력이 같은 출력을 내지
     * 않아 시험이 값을 못 잠근다.
     *
     * config에서 **둘을 걷어낸다**: 세계관 한정 참조(전역에는 가리킬 대상이 없다)와 연결
     * 표식(템플릿이 템플릿을 가리킬 수는 없다 — 승격 대상이 이미 다른 템플릿에 연결돼 있던
     * 경우가 실재한다).
     */
    fun promote(
        field: FieldDefinition,
        displayOrder: Int,
        code: String,
        createdAt: Long
    ): DefaultFieldTemplate = DefaultFieldTemplate(
        key = field.key,
        name = field.name,
        type = field.type,
        config = DefaultFieldRef.remove(FieldConfigTransfer.demoteAcrossUniverse(field.config)),
        groupName = field.groupName,
        displayOrder = displayOrder,
        isRequired = field.isRequired,
        entityType = field.entityType,
        createdAt = createdAt,
        code = code
    )

    // ──────────────────────────────────────────────────────────────────────
    // 심기 — 템플릿 신설 · 승격 · 세계관 신설 · 다시 심기
    // ──────────────────────────────────────────────────────────────────────

    /** 한 세계관의 사정. [fields]는 **전 종류**여야 한다(R-29 — 한 종류만 보면 중복을 놓친다). */
    data class Target(
        val universeId: Long?,
        val universeName: String,
        val fields: List<FieldDefinition>
    )

    /** 이 세계관에서 템플릿이 처할 자리. */
    enum class Placement {
        /** 자리가 비었다 — 새로 심는다. */
        PLANT,

        /**
         * 같은 `(entityType, key)`가 이미 있다 — **덮지 않고 연결만 건다**(설계 1-3).
         * 그 세계관에서 사용자가 정한 설정을 존중한다(자율성 우선).
         */
        LINK,

        /** 이미 이 템플릿에 연결돼 있다 — 할 일이 없다. */
        ALREADY
    }

    data class PlantItem(
        val universeId: Long?,
        val universeName: String,
        val placement: Placement,
        /** 그 자리를 이미 차지한 필드. [Placement.PLANT]면 null이다. */
        val existing: FieldDefinition?,
        /** 새로 심을 때 쓸 순서 — 그 세계관의 같은 종류 최댓값 뒤다. */
        val nextOrder: Int
    )

    /**
     * 심기 계획.
     *
     * 화면은 이것으로 *"새로 심음 N곳 · 기존 필드에 연결 M곳"*을 말한다(설계 1-3) —
     * **두 수를 가르는 것이 요점이다.** 합쳐 "N곳에 적용"이라고 하면 사용자는 자기가 공들여
     * 고쳐 둔 필드가 덮인 줄 안다.
     */
    data class PlantPlan(val items: List<PlantItem>) {
        val plants: List<PlantItem> get() = items.filter { it.placement == Placement.PLANT }
        val links: List<PlantItem> get() = items.filter { it.placement == Placement.LINK }
        val already: List<PlantItem> get() = items.filter { it.placement == Placement.ALREADY }
        val isNoop: Boolean get() = plants.isEmpty() && links.isEmpty()
    }

    /**
     * 어디에 심고 어디를 이을지 정한다 — **쓰지 않는다.**
     *
     * 자리 판정은 `(entityType, key)`다([DefaultFieldPlanKeys]) — 심는 자리의 유니크 제약이
     * 그것이기 때문이다. key만 보면 캐릭터의 `place`와 사건의 `place`가 서로를 중복으로 걸어
     * 멀쩡한 필드를 못 넣는다(R-29).
     */
    fun planPlant(template: DefaultFieldTemplate, targets: List<Target>): PlantPlan {
        val slot = DefaultFieldPlanKeys.slot(template.entityType, template.key)
        val items = targets.map { target ->
            val existing = target.fields.firstOrNull {
                DefaultFieldPlanKeys.slot(it.entityType, it.key) == slot
            }
            // 순서는 **같은 종류의 최댓값 뒤**다 — 종류별로 따로 세는 것은 한 세계관 안에서
            // 두 종류의 순서가 서로 독립이기 때문이다(PresetMerge.resolve와 같은 규칙).
            val nextOrder = (target.fields
                .filter { it.entityType == template.entityType }
                .maxOfOrNull { it.displayOrder } ?: -1) + 1
            val placement = when {
                existing == null -> Placement.PLANT
                DefaultFieldRef.codeFromConfig(existing.config) == template.code -> Placement.ALREADY
                else -> Placement.LINK
            }
            PlantItem(target.universeId, target.universeName, placement, existing, nextOrder)
        }
        return PlantPlan(items)
    }

    /** [resolvePlant]가 내놓는 쓰기 목록 — 이 둘이 한 트랜잭션에 들어간다. */
    data class PlantWrites(
        val inserts: List<FieldDefinition>,
        val updates: List<FieldDefinition>
    ) {
        val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty()
    }

    /**
     * 계획을 실제 쓰기로 바꾼다.
     *
     * **[Placement.LINK]가 바꾸는 것은 config의 표식 한 키뿐이다** — 이름도 타입도 설정도
     * 건드리지 않는다. 그 세계관의 정의를 템플릿에 맞추는 것은 **전파**의 몫이고, 그것은
     * 사용자가 따로 고르는 조작이다(설계 1-3 — 존재를 보장하지 내용을 강제하지 않는다).
     */
    fun resolvePlant(template: DefaultFieldTemplate, plan: PlantPlan): PlantWrites {
        val inserts = plan.plants.map { materialize(template, it.universeId, it.nextOrder) }
        val updates = plan.links.mapNotNull { item ->
            val existing = item.existing ?: return@mapNotNull null
            existing.copy(config = DefaultFieldRef.write(existing.config, template.code))
        }
        return PlantWrites(inserts, updates)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 전파 — 템플릿 편집분을 세계관에 미는 명시적 조작
    // ──────────────────────────────────────────────────────────────────────

    /** 심긴 필드가 템플릿에서 얼마나 벌어져 있는가. */
    enum class Divergence {
        /** 템플릿과 같다 — 밀어도 달라질 것이 없다. */
        SAME,

        /**
         * **직전 템플릿과는 같다** — 즉 그 세계관에서 사용자가 손댄 적이 없고, 방금 한 템플릿
         * 편집을 아직 못 받았을 뿐이다. 미는 것이 *덮어쓰기*가 아니라 *약속을 지키는 것*이라
         * 기본으로 켠다.
         */
        OUTDATED,

        /**
         * 템플릿과도 직전 템플릿과도 다르다 — **그 세계관에서 사용자가 고쳤다.**
         * 기본으로 끈다(설계 1-3). 값 유실이 0인 쪽이 기본이어야 한다.
         */
        DIVERGED
    }

    /**
     * @param values 그 세계관의 이 필드에 저장된 값들. **타입이 바뀌는 세계관에서만 쓴다**
     *   ([typeChanges]) — [incompatibleValues]가 *"밀면 몇 개가 초기화되는가"*를 말한다
     *   (설계 1-3의 `checkTypeChangeImpact`가 미리보기에 실리는 자리).
     */
    data class PropagateItem(
        val universeId: Long?,
        val universeName: String,
        val field: FieldDefinition,
        val divergence: Divergence,
        val changes: Set<PresetMerge.Change>,
        val incompatibleValues: Int = 0
    ) {
        val typeChanges: Boolean get() = PresetMerge.Change.TYPE in changes
    }

    /**
     * 전파 미리보기.
     *
     * **기본 선택이 [Divergence.OUTDATED]뿐인 것이 이 판의 요점이다.** 설계 1-3은 *"다름은
     * 기본 해제"*라고만 적었는데, 그대로 두면 **템플릿을 고친 직후 모든 세계관이 '다름'이 된다**
     * (아직 못 받았으니 당연히 다르다). 그러면 흔한 조작인 *"고쳤으니 전부 밀어라"*가 세계관
     * 수만큼의 체크를 요구하고(원칙 04 위반), 사용자는 곧 '모두 선택'만 누르게 되어 **정작
     * 손대어 둔 세계관까지 함께 덮는다** — 설계가 막으려던 바로 그 결과다.
     *
     * 직전 템플릿을 견주면 둘이 정확히 갈린다: *못 받은 것*은 켜고 *손댄 것*은 끈다.
     * 직전 템플릿을 알 수 없는 자리(관리 화면의 '전파' 단추)에서는 아무것도 켜지 않는다 —
     * 모르는 것을 안다고 하지 않는 쪽이 안전한 기본이다.
     */
    data class PropagatePlan(val items: List<PropagateItem>) {
        val same: List<PropagateItem> get() = items.filter { it.divergence == Divergence.SAME }
        val outdated: List<PropagateItem> get() = items.filter { it.divergence == Divergence.OUTDATED }
        val diverged: List<PropagateItem> get() = items.filter { it.divergence == Divergence.DIVERGED }

        /** 밀 수 있는 것 — 같은 것은 밀어도 달라질 게 없어 선택지로 주지 않는다. */
        val actionable: List<PropagateItem> get() = items.filter { it.divergence != Divergence.SAME }

        val isNoop: Boolean get() = actionable.isEmpty()

        fun defaultSelection(): Set<Long?> = outdated.map { it.universeId }.toSet()
    }

    /**
     * **이 세계관에서 타입이 바뀌는가** — 값을 읽어야 하는지도, 세어야 하는지도 이것이 정한다.
     *
     * 판정을 여기 하나로 둔 이유(B-135): 종전에는 *값을 채우는 조건*이 저장소에 `previous.type
     * != template.type`으로, *세는 조건*이 여기 [diff]에 `field.type != template.type`으로
     * **두 벌** 적혀 있었다. 둘은 같은 것을 묻는 문장이 아니다 — 타입 갈라짐은 **세계관마다**
     * 생기고(심기의 [Placement.LINK]는 config 표식 한 키만 바꾸므로 승격 직후부터 같은 key·
     * 다른 type이 연결된 채 선다), 관리 화면의 '전파'는 `previous = null`로 열린다.
     * 그래서 채우는 조건이 쓰는 조건보다 좁아 `incompatibleValues`가 **항상 0**이었고,
     * *"값 N개가 초기화됩니다"* 경고가 정식 경로에서 통째로 죽어 있었다.
     *
     * **성능 우려는 그대로 지켜진다** — 조건이 세계관 단위라 타입이 같은 세계관의 값 표는
     * 여전히 읽지 않는다. 저장소는 이 함수에 물어서 읽을지를 정하고([LinkedField.values]),
     * 세는 쪽도 이 함수를 지난다.
     */
    fun typeChanges(field: FieldDefinition, template: DefaultFieldTemplate): Boolean =
        field.type != template.type

    /** [planPropagate]의 입력 한 줄 — 한 세계관의 심긴 필드와, 그 필드에 저장된 값들. */
    data class LinkedField(
        val universeId: Long?,
        val universeName: String,
        val field: FieldDefinition,
        val values: List<String> = emptyList()
    )

    /**
     * 세계관별 상태를 잰다 — **쓰지 않는다.**
     *
     * @param previous 편집 직전의 템플릿. 편집 흐름에서만 알 수 있고, 관리 화면의 '전파'에서는
     *   null이다. null이면 [Divergence.OUTDATED]가 나오지 않는다(위 KDoc).
     */
    fun planPropagate(
        template: DefaultFieldTemplate,
        previous: DefaultFieldTemplate?,
        linked: List<LinkedField>
    ): PropagatePlan {
        val items = linked.map { row ->
            val changes = diff(row.field, template)
            val divergence = when {
                changes.isEmpty() -> Divergence.SAME
                previous != null && diff(row.field, previous).isEmpty() -> Divergence.OUTDATED
                else -> Divergence.DIVERGED
            }
            PropagateItem(
                universeId = row.universeId,
                universeName = row.universeName,
                field = row.field,
                divergence = divergence,
                changes = changes,
                // 타입이 그대로면 값은 전부 살아남는다 — 세지 않는다.
                // 조건은 [typeChanges] 하나다(= 저장소가 값을 채운 조건. B-135).
                incompatibleValues = if (typeChanges(row.field, template)) {
                    FieldTypeCompatibility.incompatibleCount(row.values, template.type)
                } else 0
            )
        }
        return PropagatePlan(items)
    }

    /** [resolvePropagate]의 산출 — 덮을 행과, 덮이기 **직전**의 행(같은 순서)이다. */
    data class PropagateWrites(
        val updates: List<FieldDefinition>,
        /**
         * 되돌리기 스냅샷의 내용. **고른 것에만 남는다**([PresetMerge.Resolution.backups]와 같은
         * 규칙) — 밀지 않은 것은 바뀐 게 없어 되돌릴 대상이 아니다.
         */
        val backups: List<FieldDefinition>
    ) {
        val isEmpty: Boolean get() = updates.isEmpty()
    }

    /**
     * 고른 세계관에 템플릿 정의를 민다.
     *
     * **`displayOrder`와 `key`는 건드리지 않는다**([PresetMerge.resolve]와 같은 근거):
     * 순서는 그 세계관에서 사용자가 끌어다 정한 것이고, key를 바꾸면 그것은 전파가 아니라
     * 새 필드라 저장된 값이 옛 정의를 가리킨 채 고아가 된다.
     */
    fun resolvePropagate(
        template: DefaultFieldTemplate,
        plan: PropagatePlan,
        selected: Set<Long?>
    ): PropagateWrites {
        val updates = ArrayList<FieldDefinition>()
        val backups = ArrayList<FieldDefinition>()
        for (item in plan.items) {
            if (item.universeId !in selected) continue
            // 같은 정의는 밀어도 결과가 같다 — 쓰기도 백업도 만들지 않는다.
            // (화면이 선택지를 주지 않지만, 고른 목록이 화면 밖에서 올 수도 있다.)
            if (item.divergence == Divergence.SAME) continue
            backups.add(item.field)
            updates.add(
                item.field.copy(
                    name = template.name,
                    type = template.type,
                    config = linkedConfig(template),
                    groupName = template.groupName,
                    isRequired = template.isRequired
                )
            )
        }
        return PropagateWrites(updates, backups)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 강등 — 템플릿 삭제 · 승격 해제 · 연결을 찾지 못한 가져오기
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 연결 표식만 걷어낸다 — **필드도 값도 그대로 남는다.**
     *
     * 이 기능이 캐릭터 데이터를 지우는 경로는 없다(설계 1-3). 값이 든 필드가 사라지는 것은
     * 사용자가 그 세계관에서 직접 지울 때뿐이다.
     *
     * 표식이 없던 필드는 [DefaultFieldRef.remove]가 **원문을 그대로** 돌려주므로 결과가 입력과
     * 같고, 그런 행은 걸러 낸다 — 아무것도 바뀌지 않는 UPDATE를 세계관 수만큼 돌리면
     * 전파 결과 고지의 숫자가 거짓이 된다.
     */
    fun demote(fields: List<FieldDefinition>): List<FieldDefinition> =
        fields.mapNotNull { field ->
            val next = DefaultFieldRef.remove(field.config)
            if (next == field.config) null else field.copy(config = next)
        }

    // ──────────────────────────────────────────────────────────────────────

    /**
     * 심긴 필드가 템플릿에서 벌어진 갈래.
     *
     * `config`는 **연결 표식을 얹은 템플릿 config**와 견준다([linkedConfig]) — 필드 쪽에는
     * 표식이 있고 템플릿 쪽에는 없으므로, 날것으로 비교하면 **손대지 않은 필드까지 전부
     * "설정이 다름"이 되어** 미리보기가 통째로 거짓말을 한다.
     *
     * 비교는 [PresetMerge.configEquals]다 — 키 순서가 흔들려도 뜻이 같으면 같다고 봐야 한다
     * ([linkedConfig]가 실제로 재직렬화한다).
     *
     * **`displayOrder`는 갈래가 아니다** — 순서는 세계관마다 사용자의 것이고, 전파가 건드리지
     * 않으므로 "다름"으로 셀 이유도 없다.
     */
    private fun diff(field: FieldDefinition, template: DefaultFieldTemplate): Set<PresetMerge.Change> {
        val changes = linkedSetOf<PresetMerge.Change>()
        if (field.name != template.name) changes.add(PresetMerge.Change.NAME)
        if (typeChanges(field, template)) changes.add(PresetMerge.Change.TYPE)
        if (!PresetMerge.configEquals(field.config, linkedConfig(template))) {
            changes.add(PresetMerge.Change.CONFIG)
        }
        if (field.groupName != template.groupName) changes.add(PresetMerge.Change.GROUP)
        if (field.isRequired != template.isRequired) changes.add(PresetMerge.Change.REQUIRED)
        return changes
    }
}
