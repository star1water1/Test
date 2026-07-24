package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 필드 데이터 라이브러리 엔트리 — 한 필드가 가질 수 있는 값 하나의 정규(canonical) 레코드.
 *
 * 캐릭터/사건에 입력된 값이 자동 수확(source=AUTO)되어 등재되고, 사용자는 그 위에
 * 표시 라벨·별칭·카테고리·설명을 큐레이션한다. 큐레이션된 엔트리는 usageCount=0이어도
 * 자동 삭제되지 않는다 (사용자 데이터 무손실 원칙).
 *
 * 별칭(aliasesJson)은 "저장 데이터에 존재하는 변형 표기 → 이 canonical" 매핑이고,
 * displayLabel은 "이 canonical의 표시명"이다. 의미가 다르므로 합치지 않는다.
 * 해석은 항상 필드 단위 인메모리 일괄 로드(FieldValueResolver)라 별칭을 별도 테이블로
 * 정규화하지 않는다 — 엑셀 왕복도 콤마 구분 1컬럼으로 단순해진다.
 */
@Entity(
    tableName = "field_value_entries",
    foreignKeys = [
        ForeignKey(
            entity = FieldDefinition::class,
            parentColumns = ["id"],
            childColumns = ["fieldDefinitionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("fieldDefinitionId"),
        Index(value = ["fieldDefinitionId", "value"], unique = true),
        Index(value = ["code"], unique = true)
    ]
)
data class FieldValueEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fieldDefinitionId: Long,
    /** 정규 토큰 — trim 후 저장. 데이터(character/event_field_values)에 실제 저장되는 형태. */
    val value: String,
    /** 표시 라벨 (구 FieldStatsConfig.valueLabels 승계). 빈 문자열이면 value 그대로 표시. */
    val displayLabel: String = "",
    /** 별칭 JSON 배열 — 이 canonical로 접히는 변형 표기들. */
    val aliasesJson: String = "[]",
    /** 통계 카테고리 (구 FieldStatsConfig.valueCategories 승계). */
    val category: String = "",
    val description: String = "",
    /** 입력 제안·자동완성에서만 숨김. 통계는 항상 포함, 사용 중(usageCount>0)이면 검색 칩에도 포함. */
    val isHidden: Boolean = false,
    /** AUTO(자동 수확) | MANUAL(직접 등록/큐레이션) | IMPORT(엑셀) | AI(AI 정리 적용) */
    val source: String = SOURCE_AUTO,
    /** 사용 횟수 캐시 — 최종 일관성. 라이브러리 화면 진입·임포트 후·유지보수 잡에서 diff-only 재계산. */
    val usageCount: Int = 0,
    /** 엑셀 왕복 안정 식별자 */
    val code: String = generateEntityCode(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun aliases(): List<String> = parseAliases(aliasesJson)

    fun display(): String = displayLabel.ifBlank { value }

    companion object {
        const val SOURCE_AUTO = "AUTO"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_IMPORT = "IMPORT"
        const val SOURCE_AI = "AI"

        fun parseAliases(json: String): List<String> {
            return try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun aliasesToJson(aliases: List<String>): String {
            val arr = org.json.JSONArray()
            aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { arr.put(it) }
            return arr.toString()
        }
    }
}
