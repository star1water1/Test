package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "universes",
    foreignKeys = [
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["imageCharacterId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["imageNovelId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("name"), Index("createdAt"), Index(value = ["code"], unique = true),
               Index("imageCharacterId"), Index("imageNovelId")]
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
    val imageNovelId: Long? = null,    // select_novel 모드에서 선택된 작품 ID
    val customRelationshipTypes: String = "", // JSON 배열: 사용자 정의 관계 유형
    val customRelationshipColors: String = ""  // JSON 객체: 관계 유형 → 색상 hex (예: {"연인":"#E91E63"})
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

    /** 관계 유형별 색상 맵. 커스텀 색상이 없으면 기본 색상 반환. */
    fun getRelationshipColorMap(): Map<String, String> {
        val result = DEFAULT_RELATIONSHIP_COLORS.toMutableMap()
        if (customRelationshipColors.isNotBlank()) {
            try {
                val obj = org.json.JSONObject(customRelationshipColors)
                for (key in obj.keys()) {
                    result[key] = obj.getString(key)
                }
            } catch (_: Exception) { }
        }
        return result
    }

    companion object {
        const val IMAGE_MODE_NONE = "none"
        const val IMAGE_MODE_CUSTOM = "custom"
        const val IMAGE_MODE_RANDOM_CHARACTER = "random_character"
        const val IMAGE_MODE_SELECT_CHARACTER = "select_character"
        const val IMAGE_MODE_RANDOM_NOVEL = "random_novel"
        const val IMAGE_MODE_SELECT_NOVEL = "select_novel"

        val DEFAULT_RELATIONSHIP_TYPES = listOf(
            "부모-자식", "연인", "라이벌", "멘토-제자", "동료", "적", "형제자매", "친구", "기타"
        )

        val DEFAULT_RELATIONSHIP_COLORS = mapOf(
            "연인" to "#E91E63",
            "적" to "#212121",
            "라이벌" to "#FF5722",
            "동료" to "#2196F3",
            "친구" to "#4CAF50",
            "부모-자식" to "#9C27B0",
            "형제자매" to "#FF9800",
            "멘토-제자" to "#00BCD4",
            "기타" to "#9E9E9E"
        )
    }
}
