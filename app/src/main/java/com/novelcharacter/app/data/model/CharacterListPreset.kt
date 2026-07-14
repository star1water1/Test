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
        const val MAX_PRESETS = 20
    }
}
