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
    /** 캐릭터 명대사 (사용자 요청 2026.08.20) — 캐릭터의 자식 표라 상태 변화 옆이다. */
    val quotes: Boolean = true,
    val relationships: Boolean = true,
    val relationshipChanges: Boolean = true,
    val nameBank: Boolean = true,
    val factions: Boolean = true,
    val factionMemberships: Boolean = true,
    val factionRelationships: Boolean = true,
    val presetTemplates: Boolean = true,
    val searchPresets: Boolean = true,
    /** 캐릭터 목록 프리셋(필터+정렬 조합) — 작품 필터는 작품코드로 왕복 */
    val characterListPresets: Boolean = true,
    val appSettings: Boolean = true,
    /** 이미지 라이브러리 메타(태그·링크 그룹) 시트 — 기본 true라 자동 백업(ExportOptions())에도 포함된다 */
    val imageMeta: Boolean = true,
    /**
     * 대결(B-104) 시트 셋 — 축·기록·상성. **한 스위치인 것은 셋이 서로를 가리키기 때문이다**:
     * 축 없이 기록만 들이면 붙을 자리가 없고, 기록 없이 축만 들이면 빈 축이 된다.
     * 사용자가 셋을 따로 끄고 켤 실익이 없는데 스위치만 셋으로 늘면 그 조합이 전부 경로가 된다.
     */
    val duels: Boolean = true,
    val images: Boolean = false,
    /**
     * **API 키를 함께 내보내는가** — 기본 제외 (B-105, 사용자 확정 3번 ㄴ1).
     *
     * **일부러 [toBooleanArray]에 넣지 않았다.** 그 배열은 '전체 선택' 한 번에 전부 켜지고
     * [isCompleteBackup]의 판정 대상이기도 하다 — 거기 넣으면 **'전체 백업'을 누른 것만으로
     * 평문 키가 파일에 실린다.** 확정이 요구한 것은 항목 하나가 아니라 *별도 동의*이고,
     * 그 뜻은 **다른 선택에 딸려 켜지지 않는 것**이다. 그래서 자동 백업(`ExportOptions()`)도
     * 영영 키를 싣지 않는다.
     *
     * [appSettings]가 꺼져 있으면 이 값은 무의미하다 — 시트 자체가 안 나간다.
     */
    val aiKeys: Boolean = false,
    /** MERGE 모드에서 엑셀에 없는 항목을 카테고리별로 삭제할지 여부 */
    val deleteOptions: DeleteOptions = DeleteOptions()
) {
    /**
     * 이 선택이 **완전한 백업**인가 — 모든 항목과 이미지가 다 켜져 있는가(설계 D1의 완료 고지 조건).
     *
     * `deleteOptions`는 가져오기 전용 선택이라 판정에서 뺀다(그래서 `==` 비교가 아니라
     * 항목 배열을 본다). '전체 백업' 버튼뿐 아니라 '골라서'에서 사용자가 전부 켠 경우도
     * 참이다 — 결과가 같으면 같은 말을 해야 한다.
     */
    val isCompleteBackup: Boolean
        get() = toBooleanArray().all { it }

    // images는 반드시 마지막 유지 — ExcelImporter 옵션 다이얼로그가 checked[size-1]=hasImages로 참조한다
    fun toBooleanArray() = booleanArrayOf(
        universes, novels, characters, fieldDefinitions,
        timeline, stateChanges, relationships, relationshipChanges,
        nameBank, factions, factionMemberships, factionRelationships,
        presetTemplates, searchPresets, characterListPresets, appSettings, imageMeta, duels,
        quotes, images
    )

    companion object {
        val ALL = ExportOptions()
        val ALL_WITH_IMAGES = ExportOptions(images = true)

        /** 다이얼로그 표시용 라벨 (순서는 toBooleanArray와 동일) */
        val LABELS = arrayOf(
            "세계관", "작품", "캐릭터", "필드 정의",
            "사건 연표", "상태 변화", "관계", "관계 변화",
            "이름 은행", "세력", "세력 소속", "세력 관계",
            "필드 템플릿", "검색 프리셋", "목록 프리셋", "앱 설정",
            "이미지 태그·링크",
            "대결 (축·기록·상성)",
            "명대사",
            "이미지 (파일 크기 증가)"
        )

        private const val FIELD_COUNT = 20

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
                factionRelationships = arr[11],
                presetTemplates = arr[12],
                searchPresets = arr[13],
                characterListPresets = arr[14],
                appSettings = arr[15],
                imageMeta = arr[16],
                duels = arr[17],
                quotes = arr[18],
                images = arr[19]
            )
        }
    }
}

/**
 * MERGE 모드에서 "엑셀에 없는 항목 삭제" 시 카테고리별 선택.
 * 각 필드가 true이면 해당 카테고리의 미매칭 항목을 삭제한다.
 */
data class DeleteOptions(
    val characters: Boolean = false,
    val timeline: Boolean = false,
    val stateChanges: Boolean = false,
    val quotes: Boolean = false,
    val relationships: Boolean = false,
    val relationshipChanges: Boolean = false,
    val nameBank: Boolean = false,
    val factions: Boolean = false,
    val factionMemberships: Boolean = false,
    val factionRelationships: Boolean = false
) {
    val hasAny: Boolean get() = characters || timeline || stateChanges || quotes || relationships ||
        relationshipChanges || nameBank || factions || factionMemberships || factionRelationships
}
