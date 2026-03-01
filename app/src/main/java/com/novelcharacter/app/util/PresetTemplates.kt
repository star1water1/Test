package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe

/**
 * 프리셋 세계관 템플릿.
 * 각 템플릿은 세계관 정보 + 필드 정의 목록을 포함.
 */
object PresetTemplates {

    data class PresetTemplate(
        val universe: Universe,
        val fields: List<FieldDefinition>  // universeId=0 (삽입 시 교체)
    )

    fun getTemplates(): List<PresetTemplate> = listOf(
        createStarAdventureTemplate(),
        createLibraMageTemplate()
    )

    /**
     * 별님대모험 세계관 프리셋
     * - 판타지 액션 세계관: 전투력 등급, 마력, 권능, 초월자 시스템
     */
    private fun createStarAdventureTemplate(): PresetTemplate {
        val universe = Universe(
            name = "별님대모험",
            description = "전투력 등급과 초월자 시스템이 있는 판타지 세계관"
        )

        var order = 0
        val fields = listOf(
            // 기본 정보 그룹
            FieldDefinition(
                universeId = 0, key = "age", name = "나이",
                type = "NUMBER", groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "gender", name = "성별",
                type = "SELECT", config = """{"options":["남","여","?"]}""",
                groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "race", name = "종족",
                type = "TEXT", groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "height", name = "키",
                type = "TEXT", groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "body_type", name = "체형",
                type = "BODY_SIZE", config = """{"separator":"-","inputReplace":{" ":"-"}}""",
                groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "alive", name = "생존 여부",
                type = "SELECT", config = """{"options":["생존","사망","불명"]}""",
                groupName = "기본 정보", displayOrder = order++
            ),

            // 직업/소속 그룹
            FieldDefinition(
                universeId = 0, key = "job_title", name = "직업/직위",
                type = "TEXT", groupName = "직업/소속", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "affiliation", name = "소속",
                type = "TEXT", groupName = "직업/소속", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "residence", name = "거주지",
                type = "TEXT", groupName = "직업/소속", displayOrder = order++
            ),

            // 전투력 그룹
            FieldDefinition(
                universeId = 0, key = "combat_rank", name = "전투력 등급",
                type = "GRADE",
                config = """{"grades":{"C":0.5,"B":1,"A":2,"S":3},"allowNegative":false}""",
                groupName = "전투력", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "magic_power", name = "마력",
                type = "TEXT", groupName = "전투력", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "authority", name = "권능",
                type = "TEXT", groupName = "전투력", displayOrder = order++
            ),

            // 초월자 그룹
            FieldDefinition(
                universeId = 0, key = "is_transcendent", name = "초월자 여부",
                type = "SELECT", config = """{"options":["예","아니오"]}""",
                groupName = "초월자", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "transcendent_number", name = "초월자 넘버",
                type = "NUMBER", groupName = "초월자", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "transcendent_generation", name = "초월자 세대",
                type = "NUMBER", groupName = "초월자", displayOrder = order++
            ),

            // 성격/외모 그룹
            FieldDefinition(
                universeId = 0, key = "personality", name = "성격",
                type = "MULTI_TEXT", groupName = "성격/외모", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "likes", name = "좋아하는 것",
                type = "MULTI_TEXT", groupName = "성격/외모", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "dislikes", name = "싫어하는 것",
                type = "MULTI_TEXT", groupName = "성격/외모", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "appearance", name = "외모 특징",
                type = "TEXT", groupName = "성격/외모", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "special_notes", name = "특이사항",
                type = "TEXT", groupName = "성격/외모", displayOrder = order++
            )
        )

        return PresetTemplate(universe, fields)
    }

    /**
     * 천칭의 마법사 세계관 프리셋
     * - 마법 체계: 오라 친화, 신체 조절, 마나 친화, 종합 등급
     * - 등급에 음수(-C, -B 등) 허용
     * - 계산식 필드로 종합 전투력 자동 산출
     */
    private fun createLibraMageTemplate(): PresetTemplate {
        val universe = Universe(
            name = "천칭의 마법사",
            description = "오라·마나·신체 기반 마법 체계와 등급 시스템이 있는 세계관"
        )

        var order = 0
        val fields = listOf(
            // 기본 정보
            FieldDefinition(
                universeId = 0, key = "age", name = "나이",
                type = "NUMBER", groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "gender", name = "성별",
                type = "SELECT", config = """{"options":["남","여","?"]}""",
                groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "race", name = "종족",
                type = "TEXT", groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "height", name = "키",
                type = "TEXT", groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "body_size", name = "신체 사이즈",
                type = "BODY_SIZE", config = """{"separator":"-","inputReplace":{" ":"-"}}""",
                groupName = "기본 정보", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "alive", name = "생존 여부",
                type = "SELECT", config = """{"options":["생존","사망","불명"]}""",
                groupName = "기본 정보", displayOrder = order++
            ),

            // 직업/소속
            FieldDefinition(
                universeId = 0, key = "job_title", name = "직업/직위",
                type = "TEXT", groupName = "직업/소속", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "affiliation", name = "소속",
                type = "TEXT", groupName = "직업/소속", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "residence", name = "거주지",
                type = "TEXT", groupName = "직업/소속", displayOrder = order++
            ),

            // 마법 능력치 (등급, 음수 허용)
            FieldDefinition(
                universeId = 0, key = "aura_affinity", name = "오라 친화",
                type = "GRADE",
                config = """{"grades":{"C":1,"B":2,"A":3,"S":4},"allowNegative":true}""",
                groupName = "마법 능력치", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "body_control", name = "신체 조절",
                type = "GRADE",
                config = """{"grades":{"C":1,"B":2,"A":3,"S":4},"allowNegative":true}""",
                groupName = "마법 능력치", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "mana_affinity", name = "마나 친화",
                type = "GRADE",
                config = """{"grades":{"C":1,"B":2,"A":3,"S":4},"allowNegative":true}""",
                groupName = "마법 능력치", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "total_combat", name = "종합 전투력",
                type = "CALCULATED",
                config = """{"formula":"field('aura_affinity')+field('body_control')+field('mana_affinity')"}""",
                groupName = "마법 능력치", displayOrder = order++
            ),

            // 마법 상세
            FieldDefinition(
                universeId = 0, key = "magic_type", name = "마법 계통",
                type = "TEXT", groupName = "마법 상세", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "special_magic", name = "고유 마법",
                type = "TEXT", groupName = "마법 상세", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "authority", name = "권능/특수능력",
                type = "TEXT", groupName = "마법 상세", displayOrder = order++
            ),

            // 성격/외모
            FieldDefinition(
                universeId = 0, key = "personality", name = "성격",
                type = "MULTI_TEXT", groupName = "성격/외모", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "likes", name = "좋아하는 것",
                type = "MULTI_TEXT", groupName = "성격/외모", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "dislikes", name = "싫어하는 것",
                type = "MULTI_TEXT", groupName = "성격/외모", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "appearance", name = "외모 특징",
                type = "TEXT", groupName = "성격/외모", displayOrder = order++
            ),
            FieldDefinition(
                universeId = 0, key = "special_notes", name = "특이사항",
                type = "TEXT", groupName = "성격/외모", displayOrder = order++
            )
        )

        return PresetTemplate(universe, fields)
    }
}
