package com.novelcharacter.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 캐릭터 탭의 필터 + 정렬 조합을 저장하는 프리셋.
 *
 * Global Search의 `SearchPreset`과 목적이 겹치지만 상태(태그·필드정렬·BODY_SIZE 파트)가 달라
 * 전용 엔티티로 둔다(SearchPreset 무회귀). **작품 스코프는 저장하지 않는다** —
 * 프리셋 적용이 보고 있던 작품으로 순간이동시키지 않도록(스코프 상대적).
 */
@Entity(
    tableName = "character_list_presets",
    indices = [Index(value = ["name"], unique = true)]
)
data class CharacterListPreset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 선택 태그 목록 (JSON 문자열 배열) */
    val tagsJson: String = "[]",
    /** FieldFilter 목록 (FieldFilterHelper 직렬화 규약: 비어 있으면 "{}") */
    val fieldFiltersJson: String = "{}",
    /** 정렬 종류 (SORT_* 상수) */
    val sortKind: String = SORT_MANUAL,
    /** sortKind == SORT_FIELD일 때 대상 필드 key (세계관 간 이식성을 위해 id 아닌 key) */
    val sortFieldKey: String? = null,
    val sortAscending: Boolean = true,
    /** BODY_SIZE 필드 정렬 시 파트 인덱스 */
    val bodySizePartIndex: Int? = null,
    /**
     * `sortKind == SORT_DUEL`일 때 대상 대결 축의 **코드**(B-117).
     *
     * id가 아니라 코드인 것은 [sortFieldKey]가 key인 것과 같은 이유이고(세계관 간 이식·엑셀
     * 왕복), 대결 쪽에서는 한 겹 더 강하다 — **판·처분·휴지통·엑셀이 전부 축을 코드로 가리킨다**
     * (R-1). 여기만 id로 두면 복원이 id를 재발급했을 때 프리셋이 **남의 축**을 가리킨다.
     *
     * 지워진 축의 코드가 남아 있어도 무해하다: 정렬이 값을 찾지 못해 기본 정렬로 되돌아가고,
     * 그 사실을 목록이 말한다(값을 조용히 버리지 않는다 — 개발 의도 2번).
     */
    val sortDuelAxisCode: String? = null,
    /**
     * 작품 필터 차원으로 선택한 작품 id 목록 (JSON 배열). 위의 "작품 스코프 저장 안 함"은 **내비게이션**
     * 스코프(어느 작품 화면에서 보는가) 얘기이고, 이건 전역 목록에서 작품으로 거르는 **필터** 차원이라 별개다.
     * 삭제된 작품 id는 적용 시 조용히 무해(매칭 없음)하고 칩에서도 자연 배제된다.
     */
    // defaultValue는 Room이 SQL에 **그대로** 박으므로 문자열 리터럴은 직접 따옴표로 감싸야 한다.
    // "[]"(따옴표 없음)로 두면 기대 스키마가 DEFAULT [] 가 되어 마이그레이션의 DEFAULT '[]' 와 불일치 →
    // v38→v39 업그레이드 시 검증 실패로 크래시(빈 문자열 default는 비교가 생략돼 우연히 통과했을 뿐, 일반화 불가).
    @ColumnInfo(defaultValue = "'[]'")
    val novelIdsJson: String = "[]",
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SORT_MANUAL = "manual"     // 기본(수동): 핀 최상단 + displayOrder
        const val SORT_NAME = "name"
        const val SORT_CREATED = "created"
        const val SORT_RECENT = "recent"     // 최근 수정
        const val SORT_FIELD = "field"       // 커스텀 필드값
        const val SORT_DUEL = "duel"         // 대결 점수 (B-117) — 대상 축은 sortDuelAxisCode
        // 개수 한도는 `util.PresetLimit`가 든다 — 검색 프리셋과 **같은 수를 두 벌로 두면**
        // 한쪽만 바뀌고, 실제로 그 두 벌이 법과 권고로 갈려 있었다(B-75).

        /**
         * 저장·가져오기가 받아들이는 정렬 종류 전부. **여기 없는 값은 [SORT_MANUAL]로 떨어진다.**
         *
         * 목록을 상수로 둔 것은 엑셀 가져오기가 같은 판정을 해야 하기 때문이다 — 종전에는
         * 그쪽이 리터럴 다섯을 따로 나열하고 있어 **새 정렬을 더할 때마다 한 자리가 뒤처졌다**
         * (외부에서 편집한 파일에 `duel`이 적혀 있어도 조용히 '수동'이 된다).
         */
        val SORT_KINDS: Set<String> = setOf(
            SORT_MANUAL, SORT_NAME, SORT_CREATED, SORT_RECENT, SORT_FIELD, SORT_DUEL
        )
    }
}
