package com.novelcharacter.app.util

import android.util.Log
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
            Log.w("PresetTemplates", "Failed to parse fieldsJson for preset '${preset.name}'", e)
            emptyList()
        }
        val fields = toFieldDefinitions(fieldDataList)
        return PresetTemplate(
            universe = Universe(name = preset.name, description = preset.description),
            fields = fields,
            isBuiltIn = preset.isBuiltIn,
            userPresetId = preset.id
        )
    }

    /**
     * FieldDefinition 리스트를 JSON으로 직렬화 (저장용).
     *
     * 등급 체계 참조(U-1)는 여기서 벗긴다 — 프리셋은 세계관을 넘나드는 템플릿이라 특정
     * 세계관의 체계 code를 담으면 적용되는 곳마다 유령 참조가 된다. 실효 표(`grades`)는
     * config에 물질화되어 있어 벗겨도 등급 표는 그대로 템플릿에 남는다.
     */
    fun fieldsToJson(fields: List<FieldDefinition>): String {
        val dataList = fields.map { fd ->
            FieldTemplateData(
                key = fd.key,
                name = fd.name,
                type = fd.type,
                config = com.novelcharacter.app.data.model.GradeSystemRef.demote(fd.config),
                groupName = fd.groupName,
                isRequired = fd.isRequired,
                entityType = fd.entityType
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
        return toFieldDefinitions(dataList)
    }

    /**
     * 직렬화 데이터 → 필드 정의. 복원 경로 두 곳([fromUserPreset]·[fieldsFromJson])이 같은 규칙을
     * 쓰도록 한 자리에 둔다 — 갈라져 있으면 미리보기와 실제 적용이 다른 것을 보여 준다.
     *
     * `displayOrder`는 **종류별로** 센다. 캐릭터·사건 필드가 한 목록에 섞여 있어 전역 색인을 쓰면
     * 사건 필드의 순서가 캐릭터 필드 개수만큼 밀려 시작한다(같은 세계관 안에서 두 종류의 순서는
     * 서로 독립이다).
     */
    private fun toFieldDefinitions(dataList: List<FieldTemplateData>): List<FieldDefinition> {
        val orderByEntity = mutableMapOf<String, Int>()
        return dataList.map { data ->
            // 구버전 프리셋에는 이 키가 없다 — Gson은 키가 없으면 non-null 선언에도 null을 주입하므로
            // 필드를 nullable로 두고 여기서 캐릭터로 떨어뜨린다(R-2). 옛 프리셋은 전부 캐릭터 필드였다.
            val entityType = data.entityType ?: FieldDefinition.ENTITY_CHARACTER
            val order = orderByEntity.getOrDefault(entityType, 0)
            orderByEntity[entityType] = order + 1
            FieldDefinition(
                universeId = 0,
                key = data.key,
                name = data.name,
                type = data.type,
                // 저장 시점에 벗겼지만(fieldsToJson) 손편집 JSON·엑셀 '필드 템플릿' 시트로
                // 들어온 데이터는 그 경로를 안 거쳤다 — 복원 쪽에서도 벗겨야 구멍이 없다.
                config = com.novelcharacter.app.data.model.GradeSystemRef.demote(data.config),
                groupName = data.groupName,
                displayOrder = order,
                isRequired = data.isRequired,
                entityType = entityType
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
        val isRequired: Boolean = false,
        /**
         * 필드 종류(캐릭터/사건). **nullable인 것은 일부러다** — 이 키가 없는 구버전 프리셋 JSON을
         * Gson이 읽을 때 non-null 선언이면 null이 주입돼 이후 비교가 전부 어긋난다(R-2).
         * 읽는 쪽([toFieldDefinitions])이 캐릭터로 떨어뜨린다.
         */
        val entityType: String? = null
    )

    // ===== 기본 제공 템플릿 =====

    private fun createStarAdventureTemplate(): PresetTemplate {
        val universe = Universe(
            name = "별님대모험",
            description = "전투력 등급과 초월자 시스템이 있는 판타지 세계관"
        )

        val fields = listOf(
            FieldDefinition(universeId = 0, key = "birth_year", name = "출생연도", type = "NUMBER", config = """{"semanticRole":"birth_year"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "birth_date", name = "생일(월/일)", type = "TEXT", config = """{"semanticRole":"birth_date","placeholder":"MM-DD","structuredInput":{"enabled":true,"separator":"-","parts":[{"label":"월","suffix":"","inputType":"number"},{"label":"일","suffix":"","inputType":"number"}]}}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "age", name = "나이", type = "NUMBER", config = """{"semanticRole":"age"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "gender", name = "성별", type = "SELECT", config = """{"options":["남","여","?"]}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "race", name = "종족", type = "TEXT", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "height", name = "키", type = "TEXT", config = """{"semanticRole":"height"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "weight", name = "체중", type = "TEXT", config = """{"semanticRole":"weight"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "body_type", name = "체형", type = "BODY_SIZE", config = """{"separator":"-","inputReplace":{" ":"-"},"semanticRole":"body_size","structuredInput":{"enabled":true,"separator":"-","parts":[{"label":"B","suffix":"cm","inputType":"number"},{"label":"W","suffix":"cm","inputType":"number"},{"label":"H","suffix":"cm","inputType":"number"}]}}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "alive", name = "생존 여부", type = "SELECT", config = """{"options":["생존","사망","불명"],"semanticRole":"alive","aliveValue":"생존","deadValue":"사망"}""", groupName = "기본 정보"),
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
            FieldDefinition(universeId = 0, key = "birth_date", name = "생일(월/일)", type = "TEXT", config = """{"semanticRole":"birth_date","placeholder":"MM-DD","structuredInput":{"enabled":true,"separator":"-","parts":[{"label":"월","suffix":"","inputType":"number"},{"label":"일","suffix":"","inputType":"number"}]}}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "age", name = "나이", type = "NUMBER", config = """{"semanticRole":"age"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "gender", name = "성별", type = "SELECT", config = """{"options":["남","여","?"]}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "race", name = "종족", type = "TEXT", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "height", name = "키", type = "TEXT", config = """{"semanticRole":"height"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "weight", name = "체중", type = "TEXT", config = """{"semanticRole":"weight"}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "body_size", name = "신체 사이즈", type = "BODY_SIZE", config = """{"separator":"-","inputReplace":{" ":"-"},"semanticRole":"body_size","structuredInput":{"enabled":true,"separator":"-","parts":[{"label":"B","suffix":"cm","inputType":"number"},{"label":"W","suffix":"cm","inputType":"number"},{"label":"H","suffix":"cm","inputType":"number"}]}}""", groupName = "기본 정보"),
            FieldDefinition(universeId = 0, key = "alive", name = "생존 여부", type = "SELECT", config = """{"options":["생존","사망","불명"],"semanticRole":"alive","aliveValue":"생존","deadValue":"사망"}""", groupName = "기본 정보"),
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
