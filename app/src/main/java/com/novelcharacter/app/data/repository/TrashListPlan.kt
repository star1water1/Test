package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.dao.TrashSnapshotSummary

/**
 * 휴지통 화면이 그릴 **행 목록**을 짠다 — 순수 로직 (B-16 · B-50).
 *
 * [TrashGrouping]이 묶고 [TrashFilter]가 거른 결과를 받아, *무엇이 몇 줄로 보이는가*를 정한다.
 * Fragment에 두지 않는 이유는 앞의 둘과 같다: **여기서 빠진 항목은 사용자에게 없는 것과
 * 구별되지 않는데**, Fragment 안에서는 그 판정을 실행 검증할 수 없다.
 *
 * **문구는 만들지 않는다.** 행은 종류·이름 같은 *원자료*만 들고, 사람이 읽는 문구는 화면이
 * 붙일 때 만든다 — 문구를 여기서 만들면 이 계층이 `Context`를 쥐게 되어 다시 검증 밖으로 나간다
 * (머리글을 DB 컬럼에 굳히지 않는 것과 같은 이유다).
 */
object TrashListPlan {

    sealed class Row {
        /**
         * 이 행이 **무엇인가**를 말하는 키. 갱신할 때 같은 행을 알아보는 데 쓰고(DiffUtil),
         * 접힘 상태를 기억하는 데도 같은 것을 쓴다 — 두 벌로 적으면 한쪽만 고쳐도 컴파일되고,
         * 그때 증상은 *"펼쳐 둔 묶음이 갱신마다 도로 접힌다"*처럼 원인에서 멀다.
         */
        abstract val stableKey: String

        /**
         * 묶음 머리글.
         *
         * @param itemCount 묶음 **전체** 항목 수 — 일괄 동작이 실제로 건드리는 수다.
         *   필터가 걸려 있어도 '전체 복원'은 작업 전체에 작용하므로(R-9) 이 수는 걸러지지 않는다.
         * @param matchedCount 지금 보이는 항목 수. 필터가 없으면 [itemCount]와 같다.
         * @param allowsBulkActions '전체 복원'·'묶음 영구 삭제'를 내주는가.
         *   **접기와 갈라 두는 것이 요점이다** — 편집 직전 백업은 원본이 살아 있어 복원이
         *   되돌리기가 아니라 **복제**라 이 둘을 안 내주지만, 접기는 내준다.
         */
        data class Header(
            val opKey: String,
            val editBackup: Boolean,
            val rootType: String,
            val rootName: String,
            val itemCount: Int,
            val matchedCount: Int,
            val newestAt: Long,
            val expanded: Boolean,
            val allowsBulkActions: Boolean
        ) : Row() {
            override val stableKey: String get() = operationRowKey(opKey, editBackup)
        }

        /** @param siblingCount 같은 삭제 작업에 함께 있는 다른 항목 수 (개별 영구 삭제 고지용) */
        data class Item(
            val snapshot: TrashSnapshotSummary,
            val siblingCount: Int = 0
        ) : Row() {
            override val stableKey: String get() = "item:${snapshot.id}"
        }
    }

    /**
     * 머리글 행의 정체성 키 — 접힘 기억과 DiffUtil의 **단일 소스**.
     *
     * 한 작업이 삭제·편집 백업 두 묶음으로 갈릴 수 있어 **종류까지** 넣는다
     * ([TrashGrouping]이 (작업, 종류)로 묶는 것과 같은 축이다).
     */
    fun operationRowKey(opKey: String, editBackup: Boolean): String = "op:$opKey:$editBackup"

    /**
     * @param expandChoices 사용자가 **직접 접거나 편** 묶음만 담는다([Row.stableKey] → 펼침 여부).
     *   여기 없는 묶음은 아래 기본값을 따른다.
     *
     * ## 기본값이 상황에 따라 다르다 — 그리고 사용자가 언제나 뒤집을 수 있다
     *
     * **평소 기본은 접힘이다.** 실사용 표본에서 세계관 하나가 캐릭터 수십 명을 거느리고
     * 목표 규모(표본 ×30)에서는 한 번의 세계관 삭제가 수백 행을 만든다. 접되 머리글이
     * *무엇이 몇 건인지* 말하므로 원칙 04가 금지하는 '존재를 알 수 없는 데이터'가 되지 않는다.
     *
     * **거르는 중의 기본은 펼침이다.** 접힌 채로 두면 찾은 것이 머리글 뒤에 숨어 검색 결과가
     * "없다"와 구별되지 않는다.
     *
     * **그래도 기본은 기본일 뿐이라 [expandChoices]가 언제나 이긴다.** 거르는 중이라고 펼침을
     * 강제하면 머리글을 눌러도 아무 일이 없는 **죽은 버튼**이 되는데, 그것은 고장과 구별되지
     * 않는다(원칙 02 — 겉핥기 기능은 없는 것보다 못하다).
     */
    fun build(
        snapshots: List<TrashSnapshotSummary>,
        criteria: TrashFilter.Criteria = TrashFilter.Criteria(),
        expandChoices: Map<String, Boolean> = emptyMap()
    ): List<Row> {
        val matches = TrashFilter.apply(TrashGrouping.group(snapshots), criteria)
        val rows = ArrayList<Row>(snapshots.size + matches.size)
        for (match in matches) {
            val group = match.group
            // 항목이 하나뿐인 묶음은 접을 것이 없다 — 머리글을 만들면 같은 내용이 두 줄이 된다.
            if (!group.isCollapsible) {
                match.matched.forEach { rows.add(Row.Item(it, siblingCount = 0)) }
                continue
            }
            val root = group.root
            // 고른 것이 있으면 그것이, 없으면 상황별 기본값이 정한다.
            val expanded = expandChoices[operationRowKey(group.opKey, group.isEditBackup)]
                ?: criteria.isActive
            rows.add(
                Row.Header(
                    opKey = group.opKey,
                    editBackup = group.isEditBackup,
                    rootType = root.entityType,
                    rootName = root.entityName,
                    itemCount = group.size,
                    matchedCount = match.matched.size,
                    newestAt = group.newestAt,
                    // 걸러 보는 중에는 펼쳐 둔다 — 접힌 채로 두면 찾은 것이 머리글 뒤에 숨어
                    // 검색 결과가 "없다"와 구별되지 않는다.
                    expanded = expanded,
                    allowsBulkActions = group.needsHeader
                )
            )
            if (!expanded) continue
            // 편집 백업은 항목끼리 의존하지 않으므로 '형제를 못 쓰게 만든다'는 경고가
            // 해당하지 않는다 — 일괄 동작이 없는 묶음에는 형제 수를 세지 않는다.
            val siblings = if (group.needsHeader) group.size - 1 else 0
            match.matched.forEach { rows.add(Row.Item(it, siblings)) }
        }
        return rows
    }
}
