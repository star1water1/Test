package com.novelcharacter.app.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.UserPresetTemplate

/**
 * 프리셋 세계관 템플릿.
 * 기본 제공 템플릿 + 사용자 정의 템플릿을 통합 관리.
 */
object PresetTemplates {

    data class PresetTemplate(
        val universe: Universe,
        val fields: List<FieldDefinition>,  // universeId=0 (삽입 시 교체)
        val isBuiltIn: Boolean = true,
        val userPresetId: Long? = null      // 사용자 정의 템플릿의 DB ID
    )

    private val gson = Gson()
    private val FIELD_TEMPLATE_LIST_TYPE: java.lang.reflect.Type =
        object : TypeToken<List<FieldTemplateData>>() {}.type

    fun getBuiltInTemplates(): List<PresetTemplate> = listOf(
        createStarAdventureTemplate(),
        createLibraMageTemplate()
    )

    /** 사용자 정의 템플릿을 PresetTemplate으로 변환 */
    fun fromUserPreset(preset: UserPresetTemplate): PresetTemplate {
        val fieldDataList: List<FieldTemplateData> = try {
            gson.fromJson(preset.fieldsJson, FIELD_TEMPLATE_LIST_TYPE) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val fields = fieldDataList.mapIndexed { index, data ->
            FieldDefinition(
                universeId = 0,
                key = data.key,
                name = data.name,
                type = data.type,
                config = data.config,
                groupName = data.groupName,
                displayOrder = index,
                isRequired = data.isRequired
            )
        }
        return PresetTemplate(
            universe = Universe(name = preset.name, description = preset.description),
            fields = fields,
            isBuiltIn = preset.isBuiltIn,
            userPresetId = preset.id
        )
    }

    /** FieldDefinition 리스트를 JSON으로 직렬화 (저장용) */
    fun fieldsToJson(fields: List<FieldDefinition>): String {
        val dataList = fields.map { fd ->
            FieldTemplateData(
                key = fd.key,
                name = fd.name,
                type = fd.type,
                config = fd.config,
                groupName = fd.groupName,
                isRequired = fd.isRequired
            )
        }
        return gson.toJson(dataList)
    }

    /** JSON에서 필드 목록 복원 (미리보기용) */
    fun fieldsFromJson(json: String): List<FieldDefinition> {
        val dataList: List<FieldTemplateData> = try {
            gson.fromJson(json, FIELD_TEMPLATE_LIST_TYPE) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return dataList.mapIndexed { index, data ->
            FieldDefinition(
                universeId = 0,
                key = data.key,
                name = data.name,
                type = data.type,
                config = data.config,
                groupName = data.groupName,
                displayOrder = index,
                isRequired = data.isRequired
            )
        }
    }

    // 내부 직렬화용 데이터 클래스 (DB 컬럼이 아닌 JSON 필드)
    data class FieldTemplateData(
        val key: String,
        val name: String,
        val type: String,
        val config: String = "{}",
        val groupName: String = "기본 정보",
        val isRequired: Boolean = false
    )

    // ===== 기본 제공 템플릿 =====

    private fun createStarAdventureTemplate(): PresetTemplate {
        val universe = Universe(
            name = "별님대모험",
            description = "전투력 등급과 초월자 시스템이 있는 판타지 세계관"
        )

        val fields = listOf(
            FieldDefinition(universeId = 0, key = "birth_year", name = "출생연도", type = "NUMBER", config = """{"semanticRole":"birth_year"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "birth_date", name = "생일(월/일)", type = "TEXT", config = """{"semanticRole":"birth_date","placeholder":"MM-DD"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "age", name = "나이", type = "NUMBER", config = """{"semanticRole":"age"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "gender", name = "성별", type = "SELECT", config = """{"options":["남","여","?"]}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "race", name = "종족", type = "TEXT", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "height", name = "키", type = "TEXT", config = """{"semanticRole":"height"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "body_type", name = "체형", type = "BODY_SIZE", config = """{"separator":"-","inputReplace":{" ":"-"},"semanticRole":"body_size"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "alive", name = "생존 여부", type = "SELECT", config = """{"options":["생존","사망","불명"]}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "job_title", name = "직업/직위", type = "TEXT", groupName = "직업/소속"),
            FieldDefinition(universeId = 0, key = "affiliation", name = "소속", type = "TEXT", groupName = "직업/소속"),
            FieldDefinition(universeId = 0, key = "residence", name = "거주지", type = "TEXT", groupName = "직업/소속"),
            FieldDefinition(universeId = 0, key = "combat_rank", name = "전투력 등급", type = "GRADE", config = """{"grades":{"C":0.5,"B":1,"A":2,"S":3},"allowNegative":false}""", groupName = "전투력"),
            FieldDefinition(universeId = 0, key = "magic_power", name = "마력", type = "TEXT", groupName = "전투력"),
            FieldDefinition(universeId = 0, key = "authority", name = "권능", type = "TEXT", groupName = "전투력"),
            FieldDefinition(universeId = 0, key = "is_transcendent", name = "초월자 여부", type = "SELECT", config = """{"options":["예","아니오"]}""", groupName = "초월자"),
            FieldDefinition(universeId = 0, key = "transcendent_number", name = "초월자 넘버", type = "NUMBER", groupName = "초월자"),
            FieldDefinition(universeId = 0, key = "transcendent_generation", name = "초월자 세대", type = "NUMBER", groupName = "초월자"),
            FieldDefinition(universeId = 0, key = "personality", name = "성격", type = "MULTI_TEXT", groupName = "성격/외모"),
            FieldDefinition(universeId = 0, key = "likes", name = "좋아하는 것", type = "MULTI_TEXT", groupName = "성격/외모"),
            FieldDefinition(universeId = 0, key = "dislikes", name = "싫어하는 것", type = "MULTI_TEXT", groupName = "성격/외모"),
            FieldDefinition(universeId = 0, key = "appearance", name = "외모 특징", type = "TEXT", groupName = "성격/외모"),
            FieldDefinition(universeId = 0, key = "special_notes", name = "특이사항", type = "TEXT", groupName = "성격/외모")
        ).mapIndexed { index, field -> field.copy(displayOrder = index) }

        return PresetTemplate(universe, fields)
    }

    private fun createLibraMageTemplate(): PresetTemplate {
        val universe = Universe(
            name = "천칭의 마법사",
            description = "오러·마나·신체 기반 마법 체계와 등급 시스템이 있는 세계관"
        )

        val fields = listOf(
            FieldDefinition(universeId = 0, key = "birth_year", name = "출생연도", type = "NUMBER", config = """{"semanticRole":"birth_year"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "birth_date", name = "생일(월/일)", type = "TEXT", config = """{"semanticRole":"birth_date","placeholder":"MM-DD"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "age", name = "나이", type = "NUMBER", config = """{"semanticRole":"age"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "gender", name = "성별", type = "SELECT", config = """{"options":["남","여","?"]}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "race", name = "종족", type = "TEXT", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "height", name = "키", type = "TEXT", config = """{"semanticRole":"height"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "body_size", name = "신체 사이즈", type = "BODY_SIZE", config = """{"separator":"-","inputReplace":{" ":"-"},"semanticRole":"body_size"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "alive", name = "생존 여부", type = "SELECT", config = """{"options":["생존","사망","불명"]}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "job_title", name = "직업/직위", type = "TEXT", groupName = "직업/소속"),
            FieldDefinition(universeId = 0, key = "affiliation", name = "소속", type = "TEXT", groupName = "직업/소속"),
            FieldDefinition(universeId = 0, key = "residence", name = "거주지", type = "TEXT", groupName = "직업/소속"),
            // 6대 잠재 능력치
            FieldDefinition(universeId = 0, key = "special", name = "특수", type = "GRADE", config = """{"grades":{"C":1,"B":2,"A":3,"S":4},"allowNegative":true}""", groupName = "잠재 능력치"),
            FieldDefinition(universeId = 0, key = "intelligence", name = "지력", type = "GRADE", config = """{"grades":{"C":1,"B":2,"A":3,"S":4},"allowNegative":false}""", groupName = "잠재 능력치"),
            FieldDefinition(universeId = 0, key = "mana_affinity", name = "마나친화", type = "GRADE", config = """{"grades":{"C":0.5,"B":1,"A":2,"S":3},"allowNegative":false}""", groupName = "잠재 능력치"),
            FieldDefinition(universeId = 0, key = "mana_control", name = "마나제어", type = "GRADE", config = """{"grades":{"C":1,"B":2,"A":3,"S":4},"allowNegative":false}""", groupName = "잠재 능력치"),
            FieldDefinition(universeId = 0, key = "aura_affinity", name = "오러친화", type = "GRADE", config = """{"grades":{"C":0.5,"B":1,"A":2,"S":3},"allowNegative":false}""", groupName = "잠재 능력치"),
            FieldDefinition(universeId = 0, key = "body_control", name = "신체제어", type = "GRADE", config = """{"grades":{"C":0.5,"B":1,"A":2,"S":3},"allowNegative":false}""", groupName = "잠재 능력치"),
            // 계산 필드
            FieldDefinition(universeId = 0, key = "total_potential", name = "종합잠재력", type = "CALCULATED", config = """{"formula":"field('special')+field('intelligence')+field('mana_affinity')+field('mana_control')+field('aura_affinity')+field('body_control')"}""", groupName = "잠재 능력치"),
            FieldDefinition(universeId = 0, key = "spec_potential", name = "특화잠재력", type = "CALCULATED", config = """{"formula":"max(field('mana_affinity')+field('mana_control'),field('aura_affinity')+field('body_control'))"}""", groupName = "잠재 능력치"),
            FieldDefinition(universeId = 0, key = "magic_type", name = "마법 계통", type = "TEXT", groupName = "마법 상세"),
            FieldDefinition(universeId = 0, key = "special_magic", name = "고유 마법", type = "TEXT", groupName = "마법 상세"),
            FieldDefinition(universeId = 0, key = "authority", name = "권능/특수능력", type = "TEXT", groupName = "마법 상세"),
            FieldDefinition(universeId = 0, key = "personality", name = "성격", type = "MULTI_TEXT", groupName = "성격/외모"),
            FieldDefinition(universeId = 0, key = "likes", name = "좋아하는 것", type = "MULTI_TEXT", groupName = "성격/외모"),
            FieldDefinition(universeId = 0, key = "dislikes", name = "싫어하는 것", type = "MULTI_TEXT", groupName = "성격/외모"),
            FieldDefinition(universeId = 0, key = "appearance", name = "외모 특징", type = "TEXT", groupName = "성격/외모"),
            FieldDefinition(universeId = 0, key = "special_notes", name = "특이사항", type = "TEXT", groupName = "성격/외모")
        ).mapIndexed { index, field -> field.copy(displayOrder = index) }

        return PresetTemplate(universe, fields)
    }
}
