package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "universes",
    indices = [Index("name"), Index("createdAt"), Index(value = ["code"], unique = true)]
)
data class Universe(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val code: String = generateEntityCode(),
    val displayOrder: Long = 0,
    val borderColor: String = "",
    val borderWidthDp: Float = 1.5f,
    val imagePath: String = "",       // 직접 등록한 이미지 경로
    val imageMode: String = "none",   // none, custom, random_character, select_character
    val imageCharacterId: Long? = null, // select_character 모드에서 선택된 캐릭터 ID
    val customRelationshipTypes: String = "" // JSON 배열: 사용자 정의 관계 유형
) {
    /** 이 세계관의 관계 유형 목록. 커스텀 정의가 없으면 기본 유형 반환. */
    fun getRelationshipTypes(): List<String> {
        if (customRelationshipTypes.isBlank()) return DEFAULT_RELATIONSHIP_TYPES
        return try {
            val arr = org.json.JSONArray(customRelationshipTypes)
            (0 until arr.length()).map { arr.getString(it) }.ifEmpty { DEFAULT_RELATIONSHIP_TYPES }
        } catch (_: Exception) {
            DEFAULT_RELATIONSHIP_TYPES
        }
    }

    companion object {
        const val IMAGE_MODE_NONE = "none"
        const val IMAGE_MODE_CUSTOM = "custom"
        const val IMAGE_MODE_RANDOM_CHARACTER = "random_character"
        const val IMAGE_MODE_SELECT_CHARACTER = "select_character"

        val DEFAULT_RELATIONSHIP_TYPES = listOf(
            "부모-자식", "연인", "라이벌", "멘토-제자", "동료", "적", "형제자매", "친구", "기타"
        )
    }
}
