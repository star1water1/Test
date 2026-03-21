package com.novelcharacter.app.excel

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
    val presetTemplates: Boolean = true,
    val searchPresets: Boolean = true,
    val appSettings: Boolean = true,
    val images: Boolean = false
) {
    fun toBooleanArray() = booleanArrayOf(
        universes, novels, characters, fieldDefinitions,
        timeline, stateChanges, relationships, relationshipChanges,
        nameBank, presetTemplates, searchPresets, appSettings, images
    )

    companion object {
        val ALL = ExportOptions()
        val ALL_WITH_IMAGES = ExportOptions(images = true)

        /** 다이얼로그 표시용 라벨 (순서는 toBooleanArray와 동일) */
        val LABELS = arrayOf(
            "세계관", "작품", "캐릭터", "필드 정의",
            "사건 연표", "상태 변화", "관계", "관계 변화",
            "이름 은행", "필드 템플릿", "검색 프리셋", "앱 설정",
            "이미지 (파일 크기 증가)"
        )

        fun fromBooleanArray(arr: BooleanArray) = ExportOptions(
            universes = arr[0],
            novels = arr[1],
            characters = arr[2],
            fieldDefinitions = arr[3],
            timeline = arr[4],
            stateChanges = arr[5],
            relationships = arr[6],
            relationshipChanges = arr[7],
            nameBank = arr[8],
            presetTemplates = arr[9],
            searchPresets = arr[10],
            appSettings = arr[11],
            images = arr[12]
        )
    }
}
