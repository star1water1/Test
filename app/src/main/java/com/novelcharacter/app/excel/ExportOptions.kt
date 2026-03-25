package com.novelcharacter.app.excel

/**
 * 가져오기 시 기존 데이터와의 충돌 처리 전략.
 *
 * [MERGE]: 기존 동작 — 코드/이름 매칭으로 업데이트, 없으면 삽입.
 *          백업에 없는 기존 데이터는 그대로 유지.
 * [OVERWRITE]: 선택된 카테고리의 기존 데이터를 모두 삭제 후 백업 데이터만 삽입.
 */
enum class ImportStrategy {
    MERGE,
    OVERWRITE
}

/**
 * 내보내기/가져오기 시 포함할 항목을 선택하는 옵션.
 * 체크박스 다이얼로그에서 사용자가 선택한 항목을 전달한다.
 */
data class ExportOptions(
    val universes: Boolean = true,
    val novels: Boolean = true,
    val characters: Boolean = true,
    val fieldDefinitions: Boolean = true,
    val timeline: Boolean = true,
    val stateChanges: Boolean = true,
    val relationships: Boolean = true,
    val relationshipChanges: Boolean = true,
    val nameBank: Boolean = true,
    val factions: Boolean = true,
    val factionMemberships: Boolean = true,
    val presetTemplates: Boolean = true,
    val searchPresets: Boolean = true,
    val appSettings: Boolean = true,
    val images: Boolean = false,
    /** MERGE 모드에서 엑셀에 없는 항목을 삭제할지 여부 (미리보기 다이얼로그에서 설정) */
    val deleteNotInExcel: Boolean = false
) {
    fun toBooleanArray() = booleanArrayOf(
        universes, novels, characters, fieldDefinitions,
        timeline, stateChanges, relationships, relationshipChanges,
        nameBank, factions, factionMemberships,
        presetTemplates, searchPresets, appSettings, images
    )

    companion object {
        val ALL = ExportOptions()
        val ALL_WITH_IMAGES = ExportOptions(images = true)

        /** 다이얼로그 표시용 라벨 (순서는 toBooleanArray와 동일) */
        val LABELS = arrayOf(
            "세계관", "작품", "캐릭터", "필드 정의",
            "사건 연표", "상태 변화", "관계", "관계 변화",
            "이름 은행", "세력", "세력 소속",
            "필드 템플릿", "검색 프리셋", "앱 설정",
            "이미지 (파일 크기 증가)"
        )

        private const val FIELD_COUNT = 15

        fun fromBooleanArray(arr: BooleanArray): ExportOptions {
            require(arr.size >= FIELD_COUNT) {
                "Expected at least $FIELD_COUNT elements, got ${arr.size}"
            }
            return ExportOptions(
                universes = arr[0],
                novels = arr[1],
                characters = arr[2],
                fieldDefinitions = arr[3],
                timeline = arr[4],
                stateChanges = arr[5],
                relationships = arr[6],
                relationshipChanges = arr[7],
                nameBank = arr[8],
                factions = arr[9],
                factionMemberships = arr[10],
                presetTemplates = arr[11],
                searchPresets = arr[12],
                appSettings = arr[13],
                images = arr[14]
            )
        }
    }
}
