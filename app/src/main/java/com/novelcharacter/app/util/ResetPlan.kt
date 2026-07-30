package com.novelcharacter.app.util

/**
 * **앱 초기화가 무엇을 비우는지의 단일 소스 (S-13 / 색출 로드맵 7).**
 *
 * ## 왜 선언으로 두는가
 *
 * 초기화는 "모든 데이터가 삭제되며 되돌릴 수 없습니다"라고 약속한다. 그런데 실제 삭제는
 * `SettingsFragment.executeReset`의 DAO 호출 목록이고, 그 목록은 **엔티티가 늘어나도 아무것도
 * 알려주지 않는다.** 실제로 그렇게 어긋나 있었다 — 26개 테이블 중 **5개**가 초기화 뒤에도
 * 살아남았다(`trash_snapshots` · `operation_logs` · `character_list_presets` ·
 * `image_meta` · `image_tags`).
 *
 * 그 결과가 단순한 잔재가 아니었다: 휴지통에 과거 삭제 항목이 남아 **복원 가능해 보이는데**,
 * 이미지 파일은 이미 지워졌으므로 복원하면 깨진 `imagePaths`를 단 캐릭터가 되살아난다
 * (거짓 예고 + 부분 파손).
 *
 * 그래서 "무엇이 어떻게 비워지는가"를 **코드가 읽을 수 있는 선언**으로 옮기고, 순수 JVM
 * 테스트가 이 선언과 실제 엔티티 목록을 대조한다. 엔티티를 추가하고 이 표를 갱신하지 않으면
 * 테스트가 깨진다 — 그것이 목적이다.
 *
 * ## 규약
 *
 * - [Clearing.EXPLICIT] — `executeReset`이 DAO로 직접 지운다. [explicitOrder]가 그 순서다.
 * - [Clearing.CASCADE] — FK `onDelete = CASCADE`로 부모가 지워질 때 함께 사라진다.
 *   **부모 자신이 비워지지 않으면 이 보장은 성립하지 않는다** — `image_tags`가 정확히 그랬다
 *   (부모 `image_meta`가 초기화 목록에 없어, CASCADE인데도 영원히 남았다).
 *   [brokenCascades]가 이 함정을 잡는다.
 */
object ResetPlan {

    enum class Clearing { EXPLICIT, CASCADE }

    /**
     * @param table Room `tableName`
     * @param how 어떻게 비워지는가
     * @param via [Clearing.CASCADE]일 때 부모 테이블 (EXPLICIT이면 null)
     */
    data class TableClear(val table: String, val how: Clearing, val via: String? = null)

    private fun explicit(table: String) = TableClear(table, Clearing.EXPLICIT)
    private fun cascade(table: String, via: String) = TableClear(table, Clearing.CASCADE, via)

    /**
     * DB 엔티티 26종 전부의 처분. `AppDatabase.entities`와 1:1이어야 하며,
     * `tools/verify_reset_coverage.py`가 실제 소스와 대조한다.
     */
    val plan: List<TableClear> = listOf(
        // ── 직접 삭제 (FK로 지워지지 않거나, 순서를 통제해야 하는 것) ──
        explicit("character_relationship_changes"),
        explicit("character_relationships"),
        explicit("faction_memberships"),
        explicit("factions"),
        explicit("character_state_changes"),
        explicit("timeline_character_cross_ref"),
        explicit("timeline_event_novel_cross_ref"),
        explicit("timeline_events"),
        explicit("characters"),
        explicit("field_definitions"),
        explicit("novels"),
        explicit("universes"),
        explicit("name_bank"),
        explicit("user_preset_templates"),
        explicit("search_presets"),
        explicit("recent_activities"),
        // S-13이 추가한 넷 — FK가 없어 어떤 부모로도 지워지지 않는 독립 테이블이다.
        explicit("trash_snapshots"),
        explicit("operation_logs"),
        explicit("character_list_presets"),
        explicit("image_meta"),

        // ── 부모 CASCADE로 사라지는 것 ──
        cascade("character_field_values", via = "characters"),
        cascade("character_tags", via = "characters"),
        cascade("event_field_values", via = "timeline_events"),
        // 작품 커스텀 필드값(확-3) — novels가 explicit로 지워지므로 함께 사라진다.
        cascade("novel_field_values", via = "novels"),
        cascade("faction_relationships", via = "factions"),
        cascade("field_value_entries", via = "field_definitions"),
        // image_meta를 지우게 된 뒤에야 이 CASCADE가 실제로 성립한다.
        cascade("image_tags", via = "image_meta")
    )

    /** `executeReset`이 직접 지우는 순서 — FK 자식이 부모보다 앞선다. */
    val explicitOrder: List<String> = plan.filter { it.how == Clearing.EXPLICIT }.map { it.table }

    val allTables: Set<String> = plan.map { it.table }.toSet()

    /** [entityTables] 중 이 계획이 처분을 밝히지 않은 것 — 초기화 후 살아남을 테이블이다. */
    fun uncovered(entityTables: Collection<String>): List<String> =
        entityTables.filterNot { it in allTables }.sorted()

    /** 이 계획에는 있으나 실제 엔티티에는 없는 테이블 (표가 낡았다는 신호). */
    fun unknown(entityTables: Collection<String>): List<String> =
        allTables.filterNot { it in entityTables }.sorted()

    /**
     * 부모가 비워지지 않는 CASCADE 항목 — **비워진다고 적혀 있지만 실제로는 남는** 테이블.
     * `image_tags`(부모 `image_meta` 누락)가 실제 사례였다.
     */
    fun brokenCascades(): List<TableClear> {
        val cleared = allTables
        return plan.filter { it.how == Clearing.CASCADE && it.via !in cleared }
    }
}
