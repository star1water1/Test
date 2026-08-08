package com.novelcharacter.app.data.repository

import androidx.lifecycle.LiveData
import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.DefaultFieldRef
import com.novelcharacter.app.data.model.DefaultFieldTemplate
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.generateEntityCode
import com.novelcharacter.app.util.DefaultFieldPlan

/**
 * 전역 기본 필드(B-119)의 **쓰기 경로** — 심기·전파·강등이 전부 여기를 지난다.
 *
 * 설계 정본은 `docs/feature_roadmap_2026-08.md` **1장**이다.
 *
 * ## 계획과 쓰기를 가른다
 *
 * 무엇을 할지는 [DefaultFieldPlan]이 정하고(순수 — 시험이 잠근다), 이 파일은 **그것을 그대로
 * 쓴다.** 미리보기가 보여 준 것과 실제로 들어가는 것이 갈리지 않는 이유가 그것이다
 * ([GradeSystemRepository]가 전파를 저장과 한 트랜잭션에 묶는 것과 같은 모양).
 *
 * ## 조회는 늘 **전 종류**다 (R-29)
 *
 * 템플릿은 사건·작품에도 열려 있으므로([DefaultFieldTemplate.entityType]) 심긴 필드를 찾을 때
 * 캐릭터만 보면 다른 종류의 연결이 전파에서도 강등에서도 조용히 빠진다.
 *
 * ## 이 저장소에 필드를 지우는 함수가 없는 것은 설계다
 *
 * 템플릿 삭제도 승격 해제도 **강등**뿐이다(설계 1-3). 값이 든 필드가 사라지는 것은 사용자가
 * 그 세계관에서 직접 지울 때뿐이며, 이 기능은 그 경로를 갖지 않는다(개발 의도 2번).
 */
class DefaultFieldTemplateRepository(private val db: AppDatabase) {

    private val templateDao get() = db.defaultFieldTemplateDao()
    private val fieldDao get() = db.fieldDefinitionDao()

    /** 심기 결과 — 화면이 *"새로 심음 N곳 · 기존 필드에 연결 M곳"*을 말하는 재료다. */
    data class PlantResult(val template: DefaultFieldTemplate, val planted: Int, val linked: Int)

    /** 전파 결과 — 실제로 덮은 세계관 수. **고른 수가 아니라 반영된 수다.** */
    data class PropagateResult(val changed: Int)

    val all: LiveData<List<DefaultFieldTemplate>> get() = templateDao.getAll()

    suspend fun getAllList(): List<DefaultFieldTemplate> = templateDao.getAllList()

    companion object {
        /** 전파 미리보기·심기 결과에서 전역 구역(무소속)을 부르는 이름. */
        const val GLOBAL_SCOPE_NAME = "무소속"
    }

    suspend fun getByCode(code: String): DefaultFieldTemplate? = templateDao.getByCode(code)

    suspend fun getBySlot(entityType: String, key: String): DefaultFieldTemplate? =
        templateDao.getBySlot(entityType, key)

    /**
     * 이 템플릿에서 온 필드 전부 — 세계관을 가리지 않고, **종류도 가리지 않는다**(R-29).
     *
     * 표식은 config 안의 키라 SQL로 거를 수 없다. 목록이 짧아(전 세계관의 필드 정의) 메모리에서
     * 거르는 비용이 문제가 되지 않는다 — 목표 규모에서도 수천 행이다
     * (`scalability_performance` 4장. 캐릭터 **값**과 달리 정의는 세계관당 수십 개다).
     */
    suspend fun linkedFields(code: String): List<FieldDefinition> =
        fieldDao.getAllFieldsAllTypes().filter { DefaultFieldRef.codeFromConfig(it.config) == code }

    /** 심긴 필드가 있는 세계관 수 — 관리 화면 목록의 *"세계관 N곳 연결"*. */
    suspend fun linkedUniverseCount(code: String): Int =
        linkedFields(code).map { it.universeId }.distinct().size

    /**
     * 관리 화면 목록의 요약 전량 — 템플릿 code → (연결 수, **다른** 수).
     *
     * **한 번만 읽는다.** 행마다 [planPropagate]를 부르면 템플릿 수만큼 전 필드 스캔이 돌고,
     * 목표 규모(세계관 270 × 세계관당 수십 필드)에서 그것은 화면 하나를 여는 값으로 너무 크다
     * (`scalability_performance` 7장 2단계 — 비용이 *템플릿 수 × 전체 필드 수* 축에 붙는다).
     * 여기서는 필드를 한 번 읽어 code로 묶으므로 **템플릿 수와 무관하게 한 번**이다.
     *
     * '다름' 판정은 [DefaultFieldPlan]의 것과 **같은 함수**를 지난다 — 목록이 말하는 수와
     * 전파 미리보기가 보여 주는 줄 수가 갈리면 목록이 거짓말을 한다.
     */
    suspend fun linkSummaries(templates: List<DefaultFieldTemplate>): Map<String, Pair<Int, Int>> {
        if (templates.isEmpty()) return emptyMap()
        val byCode = templates.associateBy { it.code }
        val linked = HashMap<String, MutableList<FieldDefinition>>()
        for (field in fieldDao.getAllFieldsAllTypes()) {
            val code = DefaultFieldRef.codeFromConfig(field.config) ?: continue
            if (code !in byCode) continue
            linked.getOrPut(code) { ArrayList() }.add(field)
        }
        return templates.associate { template ->
            val fields = linked[template.code].orEmpty()
            // previous = null — 관리 화면은 '직전 템플릿'을 모른다. 그래서 여기서 세는 '다름'은
            // *템플릿과 다른 전부*이고, 그것이 이 요약이 말하려는 바 그대로다.
            // 값을 싣지 않는 것도 일부러다 — 이 요약이 쓰는 것은 `actionable.size`뿐이라
            // `incompatibleValues`를 묻지 않는다(값 표를 목록 화면에서 읽으면 세계관 수만큼의
            // 조회가 목록 하나를 여는 값에 붙는다). **읽는 자리는 전파 미리보기 하나다.**
            val plan = DefaultFieldPlan.planPropagate(
                template, previous = null,
                linked = fields.map {
                    DefaultFieldPlan.LinkedField(it.universeId, universeName = "", field = it)
                }
            )
            template.code to (fields.size to plan.actionable.size)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 심기
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 전 세계관을 대상으로 심기 계획을 세운다 — **쓰지 않는다.**
     *
     * 각 세계관의 **전 종류** 필드를 모집단으로 넘긴다 — 한 종류만 넘기면 다른 종류가 차지한
     * 자리를 못 보고 삽입이 유니크 제약에 걸린다(R-29 — 판정과 쓰기가 같은 모집단을 봐야 한다).
     */
    suspend fun planPlant(template: DefaultFieldTemplate): DefaultFieldPlan.PlantPlan {
        val universes = db.universeDao().getAllUniversesList()
        val byUniverse = fieldDao.getAllFieldsAllTypes().groupBy { it.universeId }
        // 전역 구역(무소속 — universeId null)도 심기 대상이다 (2026.08.07 사용자 확정).
        // 세계관 목록에 없으므로 여기서 명시적으로 더한다 — 더하지 않으면 "전역" 필드가
        // 정작 소속 없는 캐릭터에게만 없는 역설이 그대로 남는다.
        return DefaultFieldPlan.planPlant(
            template,
            universes.map {
                DefaultFieldPlan.Target(it.id, it.name, byUniverse[it.id].orEmpty())
            } + DefaultFieldPlan.Target(null, GLOBAL_SCOPE_NAME, byUniverse[null].orEmpty())
        )
    }

    /**
     * 기존 필드를 템플릿으로 **승격**하고 전 세계관에 심는다.
     *
     * 같은 자리에 이미 템플릿이 있으면 **그것을 돌려주고 심기만 다시 돈다** — 유니크
     * `(entityType, key)`가 둘째 템플릿을 거절하므로(마이그레이션 하네스가 그 거절을 잰다)
     * 여기서 미리 갈라야 사용자에게 예외가 아니라 결과가 간다.
     */
    suspend fun promoteAndPlant(field: FieldDefinition): PlantResult = db.withTransaction {
        val existing = templateDao.getBySlot(field.entityType, field.key)
        val template = existing ?: run {
            val order = (templateDao.getMaxOrder(field.entityType) ?: -1) + 1
            val made = DefaultFieldPlan.promote(
                field,
                displayOrder = order,
                code = generateEntityCode(),
                createdAt = System.currentTimeMillis()
            )
            made.copy(id = templateDao.insert(made))
        }
        plantNow(template)
    }

    /** 템플릿을 새로 만들고 전 세계관에 심는다 (관리 화면의 '새로 만들기'). */
    suspend fun createAndPlant(template: DefaultFieldTemplate): PlantResult = db.withTransaction {
        val order = if (template.displayOrder > 0) template.displayOrder
        else (templateDao.getMaxOrder(template.entityType) ?: -1) + 1
        val row = template.copy(displayOrder = order)
        val saved = row.copy(id = templateDao.insert(row))
        plantNow(saved)
    }

    /** '다시 심기' — 그 사이 생긴 세계관·지워진 필드를 따라잡는다. */
    suspend fun plantAll(template: DefaultFieldTemplate): PlantResult = db.withTransaction {
        plantNow(template)
    }

    /**
     * 세계관 하나에 템플릿 전부를 심는다 — **세계관 신설 경로가 부른다**
     * ([UniverseRepository.insertUniverse]).
     *
     * 심는 자리를 한 곳으로 모은 이유: 세계관을 만드는 길이 하나가 아니다(빈 세계관 · 프리셋 ·
     * 월드패키지 · 엑셀 · 휴지통 복원). 부르는 쪽마다 심기를 적으면 **다음에 생기는 길이 그것을
     * 빠뜨리고**, 그 고장은 *"이 세계관에만 기본 필드가 없다"*로 조용히 난다.
     *
     * **복원과 가져오기는 일부러 부르지 않는다** — 그쪽은 자기 필드를 곧바로 넣으므로 미리
     * 심으면 그 삽입이 유니크 제약에 걸린다. 그 세계관들은 관리 화면의 **'다시 심기'**가 따라
     * 잡는다(설계 1-4에 있는 그 단추이며, 이것이 그 단추가 필요한 이유다).
     *
     * @return 새로 심은 필드 수.
     */
    suspend fun plantIntoUniverse(universeId: Long, universeName: String): Int {
        val templates = templateDao.getAllList()
        if (templates.isEmpty()) return 0
        var planted = 0
        // 갓 만든 세계관이라도 **한 번 세운 목록을 이어서 갱신한다** — 템플릿 둘이 같은 종류면
        // 뒤엣것의 순서가 앞엣것을 알아야 한다(둘 다 0이 되면 순서가 뭉친다).
        val fields = ArrayList(fieldDao.getFieldsByUniverseAllTypes(universeId))
        for (template in templates) {
            val plan = DefaultFieldPlan.planPlant(
                template, listOf(DefaultFieldPlan.Target(universeId, universeName, fields))
            )
            val writes = DefaultFieldPlan.resolvePlant(template, plan)
            for (row in writes.inserts) {
                val id = fieldDao.insert(row)
                fields.add(row.copy(id = id))
                planted++
            }
            for (row in writes.updates) {
                fieldDao.update(row)
                val at = fields.indexOfFirst { it.id == row.id }
                if (at >= 0) fields[at] = row
            }
        }
        return planted
    }

    /** [planPlant] → [DefaultFieldPlan.resolvePlant] → 쓰기. 트랜잭션 안에서만 부른다. */
    private suspend fun plantNow(template: DefaultFieldTemplate): PlantResult {
        val plan = planPlant(template)
        val writes = DefaultFieldPlan.resolvePlant(template, plan)
        if (writes.inserts.isNotEmpty()) fieldDao.insertAll(writes.inserts)
        for (row in writes.updates) fieldDao.update(row)
        return PlantResult(template, writes.inserts.size, writes.updates.size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 편집 · 전파
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 템플릿 정의만 고친다 — **퍼뜨리지 않는다**(설계 1-3: 명시적 전파).
     *
     * 자동으로 퍼뜨리지 않는 것이 이 기능의 성격이다: 기본 필드는 *존재*를 보장하는 것이지
     * *내용*을 강제하는 것이 아니다. 세계관마다 고쳐 둔 것을 편집 한 번이 조용히 되돌리면
     * 그것은 자율성을 되가져가는 것이다.
     */
    suspend fun updateTemplate(template: DefaultFieldTemplate) = templateDao.update(template)

    /**
     * 전파 미리보기 — 세계관별 상태와, 타입이 바뀔 때 **못 버티는 값의 수**를 함께 싣는다.
     *
     * @param previous 편집 직전의 템플릿. 넘기면 *못 받은 것*과 *손댄 것*이 갈려 기본 선택이
     *   정확해진다([DefaultFieldPlan.Divergence] 참조). 관리 화면의 '전파'에서는 null이다.
     */
    suspend fun planPropagate(
        template: DefaultFieldTemplate,
        previous: DefaultFieldTemplate? = null
    ): DefaultFieldPlan.PropagatePlan {
        val names = db.universeDao().getAllUniversesList().associate { it.id to it.name }
        // 어느 행의 값을 읽을지는 **순수 계층의 판정 하나가 정한다**(B-135 —
        // `DefaultFieldPlan.typeChanges`). 여기서 조건을 다시 적으면 그 순간 *채우는 조건*과
        // *세는 조건*이 두 벌이 되고, 갈리는 방향이 나쁘다: 종전에는 이 자리가
        // `previous != null && 직전 템플릿의 타입 비교`라 **템플릿 단위**로 물었고, 그래서
        // 관리 화면의 '전파'(`previous = null`)에서는 값이 한 번도 실리지 않아
        // *"값 N개가 초기화됩니다"* 경고가 정식 경로에서 통째로 죽어 있었다.
        val linked = linkedFields(template.code).map { field ->
            DefaultFieldPlan.LinkedField(
                universeId = field.universeId,
                // 전역 구역(universeId null)의 그림자도 연결 표식으로 함께 잡힌다 —
                // 전파 미리보기에 "무소속" 행으로 선다(타입 영향 분석·백업을 세계관과
                // 똑같이 탄다. 자동 동기화로 두면 타입 변경이 무소속 값을 말없이 깨뜨린다).
                universeName = if (field.universeId == null) GLOBAL_SCOPE_NAME
                else names[field.universeId] ?: "",
                field = field,
                // 값 조회는 **그 세계관에서 타입이 바뀔 때만** 한다 — 그 밖에는 세지 않으므로
                // (설계 1-3) 값 표를 읽는 것은 순전한 낭비다. 타입이 같은 세계관을 건너뛰는
                // 성능 약속은 그대로다.
                values = if (DefaultFieldPlan.typeChanges(field, template)) valuesOf(field)
                else emptyList()
            )
        }
        return DefaultFieldPlan.planPropagate(template, previous, linked)
    }

    /**
     * 고른 세계관에 템플릿 정의를 민다 — 덮기와 백업이 **한 트랜잭션**이다.
     *
     * 백업이 트랜잭션 밖이면 쓰기가 실패했을 때 되돌릴 것 없는 백업만 남고, 백업이 실패했을
     * 때는 되돌릴 수 없는 덮어쓰기가 남는다(R-4가 막으려는 상태 — [FieldViewModel]의
     * 프리셋 병합이 같은 근거로 같은 모양을 쓴다).
     *
     * 스냅샷은 **세계관마다 하나**다. 휴지통 항목이 세계관 단위로 서고, 되돌리기가 그 단위로
     * 이뤄져야 *"두 세계관에 밀었는데 하나만 되돌리고 싶다"*가 가능해진다.
     */
    suspend fun applyPropagate(
        template: DefaultFieldTemplate,
        plan: DefaultFieldPlan.PropagatePlan,
        selected: Set<Long?>
    ): PropagateResult {
        val writes = DefaultFieldPlan.resolvePropagate(template, plan, selected)
        if (writes.isEmpty) return PropagateResult(0)
        val trash = TrashRepository(db)
        db.withTransaction {
            // 덮기 **전에** 백업한다 — 순서가 뒤집히면 백업이 덮인 값을 담는다.
            for ((universeId, backups) in writes.backups.groupBy { it.universeId }) {
                trash.snapshotFieldDefinitions(universeId, backups, template.name)
            }
            for (row in writes.updates) fieldDao.update(row)
        }
        return PropagateResult(writes.updates.size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 강등
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 템플릿을 지운다 — 심긴 필드는 **일반 필드로 강등만** 된다(설계 1-3).
     *
     * 필드도 값도 그대로 남고, 사라지는 것은 *"기본 필드에서 왔다"*는 사실뿐이다.
     * 되돌리려면 그 필드를 다시 승격하면 된다.
     *
     * @return 강등된 필드 수.
     */
    suspend fun deleteTemplate(template: DefaultFieldTemplate): Int = db.withTransaction {
        val linked = linkedFields(template.code)
        // 전역 구역의 그림자는 **값이 있으면 강등, 없으면 삭제**로 가른다 (2026.08.07).
        // 세계관 필드는 일괄 강등이 맞다 — 그 세계관의 관리 화면이 있어 사용자가 처분할 수
        // 있다. 전역 구역에는 정의를 관리할 화면이 없으므로, 값 없는 그림자를 강등으로 남기면
        // **지울 길 없는 행**이 되고, 값 있는 그림자를 지우면 무소속 캐릭터의 값이 사라진다
        // (개발 의도 2번 — 이 기능이 캐릭터 데이터를 지우는 경우는 없다).
        val (global, universal) = linked.partition { it.universeId == null }
        val globalToDelete = ArrayList<Long>()
        val toDemote = ArrayList(universal)
        for (shadow in global) {
            if (valuesOf(shadow).isEmpty()) globalToDelete.add(shadow.id) else toDemote.add(shadow)
        }
        val demoted = DefaultFieldPlan.demote(toDemote)
        for (row in demoted) fieldDao.update(row)
        if (globalToDelete.isNotEmpty()) fieldDao.deleteGlobalByIds(globalToDelete)
        templateDao.delete(template)
        demoted.size
    }

    /** 승격 해제 — [deleteTemplate]과 같은 일이며, 화면이 부르는 이름만 다르다. */
    suspend fun unlinkAll(template: DefaultFieldTemplate): Int = deleteTemplate(template)

    /**
     * 가져오기가 **연결을 찾지 못한 필드**를 일반 필드로 둔다(설계 1-5 — 거부가 아니라 수용·교정).
     *
     * @return 강등된 필드 수. 부르는 쪽이 *"기본 필드 연결 N건을 찾지 못해 일반 필드로
     *   두었습니다"*를 말한다 — 조용히 버리지 않는다(개발 의도 2번).
     */
    suspend fun demoteDangling(fields: List<FieldDefinition>): Int {
        val codes = templateDao.getAllList().map { it.code }.toSet()
        val dangling = fields.filter { field ->
            DefaultFieldRef.codeFromConfig(field.config)?.let { it !in codes } == true
        }
        val demoted = DefaultFieldPlan.demote(dangling)
        if (demoted.isEmpty()) return 0
        db.withTransaction { for (row in demoted) fieldDao.update(row) }
        return demoted.size
    }

    /**
     * 전역 구역의 필드 — **무소속 캐릭터 폼이 읽는 그 목록**이다.
     *
     * 템플릿은 있는데 그림자가 하나도 없으면 **여기서 심는다**(멱등). 마이그레이션 54가
     * 심기를 SQL로 하지 않은 이유가 이것이다 — 템플릿 → 필드 변환은 [DefaultFieldPlan]이
     * 단일 소스이고, SQL로 그 절반을 다시 쓰면 두 벌이 된다. 새 템플릿은 [plantNow]가
     * 전역 구역을 대상에 넣으므로 이 폴백은 **마이그레이션 직후 한 번**만 실제로 심는다.
     */
    suspend fun globalFields(entityType: String = FieldDefinition.ENTITY_CHARACTER): List<FieldDefinition> {
        val existing = fieldDao.getGlobalFieldsList(entityType)
        val templates = templateDao.getAllList().filter { it.entityType == entityType }
        if (templates.isEmpty()) return existing
        // 그림자가 이미 하나라도 있으면 심기 완료 상태다 — 지워진 그림자를 여기서 되살리면
        // 사용자가 '다시 심기'로 되살리기 전까지 지운 상태를 유지할 길이 없다.
        if (existing.any { DefaultFieldRef.codeFromConfig(it.config) != null }) return existing
        db.withTransaction {
            val fields = ArrayList(fieldDao.getGlobalFieldsAllTypes())
            for (template in templateDao.getAllList()) {
                val plan = DefaultFieldPlan.planPlant(
                    template, listOf(DefaultFieldPlan.Target(null, GLOBAL_SCOPE_NAME, fields))
                )
                val writes = DefaultFieldPlan.resolvePlant(template, plan)
                for (row in writes.inserts) fields.add(row.copy(id = fieldDao.insert(row)))
                for (row in writes.updates) {
                    fieldDao.update(row)
                    val at = fields.indexOfFirst { it.id == row.id }
                    if (at >= 0) fields[at] = row
                }
            }
        }
        return fieldDao.getGlobalFieldsList(entityType)
    }

    /** 그 필드에 저장된 값들 — 종류마다 값 표가 다르다. */
    private suspend fun valuesOf(field: FieldDefinition): List<String> = when (field.entityType) {
        FieldDefinition.ENTITY_EVENT ->
            db.eventFieldValueDao().getValuesByFieldDef(field.id).map { it.value }
        FieldDefinition.ENTITY_NOVEL ->
            db.novelFieldValueDao().getValuesByFieldDef(field.id).map { it.value }
        else ->
            db.characterFieldValueDao().getValuesByFieldDef(field.id).map { it.value }
    }
}
