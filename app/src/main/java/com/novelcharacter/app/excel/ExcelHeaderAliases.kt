package com.novelcharacter.app.excel

/**
 * 엑셀 헤더 정규화·별칭 해석의 단일 소스 (순수 JVM — 단위 테스트 대상).
 *
 * 가져오기(관대한 헤더 인식)와 내보내기(고정 열과 충돌하는 커스텀 필드명 감지)가 **같은 규칙**을
 * 써야 한다. 서로 다른 규칙을 쓰면 내보낸 헤더를 가져오기가 다른 열로 해석해 값이 뒤바뀐다.
 */
object ExcelHeaderAliases {


    val map: Map<String, String> = buildMap {
        fun alias(canonical: String, vararg aliases: String) {
            put(canonical.normalizeHeader(), canonical)
            for (a in aliases) {
                val key = a.normalizeHeader()
                // Only add if not already claimed by another canonical
                if (key !in this) put(key, canonical)
            }
        }
        // Core identifiers (order matters: first registration wins)
        alias("이름", "name", "캐릭터명")
        alias("설명", "description", "desc")
        alias("코드", "code")
        alias("정렬순서", "sort_order", "display_order", "displayorder")
        alias("제목", "title", "작품제목")
        alias("세계관", "universe")
        alias("세계관코드", "universe_code")
        alias("이명", "another_name", "별칭", "alias")
        alias("성", "last_name", "lastName", "family_name")
        alias("이름(First)", "first_name", "firstName", "given_name")
        alias("이미지경로", "image_path", "이미지 경로", "image_file", "imagepath", "imagepaths")
        alias("이미지모드", "image_mode", "이미지 모드")
        alias("이미지캐릭터코드", "이미지캐릭터ID", "image_character_id", "image_character_code", "이미지 캐릭터 ID")
        alias("이미지작품코드", "이미지작품ID", "image_novel_id", "image_novel_code", "이미지 작품 ID")
        alias("작품", "novel")
        alias("메모", "memo", "비고", "note", "notes")
        alias("태그", "tags", "tag")
        alias("작품코드", "novel_code")
        // Field definition (unique aliases only)
        alias("필드키", "field_key")
        alias("필드명", "field_name")
        alias("타입", "type", "field_type")
        alias("설정(JSON)", "config", "configuration")
        alias("그룹", "group", "그룹명", "group_name")
        alias("순서", "order")
        alias("필수여부", "required", "is_required")
        alias("대상", "entity_type", "대상엔티티", "entity")
        // Timeline (unique aliases only)
        alias("연도", "year")
        alias("월", "month")
        alias("일", "day")
        alias("역법", "calendar", "calendar_type")
        alias("사건 유형", "event_type", "사건유형")
        alias("사건 설명", "event_description", "사건설명")
        alias("관련 작품", "related_novel", "관련작품")
        alias("관련 캐릭터", "related_characters", "관련캐릭터")
        alias("관련작품코드", "related_novel_code")
        // State change
        alias("캐릭터", "character", "character_name")
        alias("새 값", "new_value", "새값")
        alias("캐릭터코드", "character_code")
        // Relationship
        alias("캐릭터1", "character1")
        alias("캐릭터2", "character2")
        alias("관계 유형", "relationship_type", "관계유형")
        alias("캐릭터1코드", "character1_code")
        alias("캐릭터2코드", "character2_code")
        // 관계 변화 — 연결 사건 참조 (코드 우선, ID는 구버전 파일 폴백)
        alias("연결사건코드", "linked_event_code", "event_code")
        alias("연결사건ID", "linked_event_id")
        // 관계 변화 — 부모 관계 식별자 (변화 시점 유형인 '관계 유형'과 구분되는 별개 열)
        alias("부모관계유형", "parent_relationship_type", "부모 관계 유형", "parentrelationshiptype")
        // Relationship extra
        alias("강도", "intensity")
        alias("양방향", "bidirectional", "is_bidirectional")
        alias("표시순서", "display_order_rel")
        // Universe custom relationship
        alias("커스텀관계유형", "custom_relationship_types", "커스텀 관계 유형")
        alias("커스텀관계색상", "custom_relationship_colors", "커스텀 관계 색상")
        // Novel extra
        alias("표준연도", "standard_year", "표준 연도")
        // Timeline extra
        alias("임시배치", "is_temporary", "임시 배치", "temporary")
        // Name bank
        alias("성별", "gender", "sex")
        alias("출처", "origin", "문화권")
        alias("사용여부", "is_used")
        alias("사용 캐릭터", "used_by", "사용캐릭터")
        alias("사용캐릭터코드", "used_character_code")
        // Border color (Sprint D)
        alias("테두리색", "border_color", "bordercolor")
        alias("테두리두께", "border_width", "borderwidth")
        // User preset template
        alias("기본제공", "is_built_in", "builtin")
        alias("생성일", "created_at", "createdat")
        alias("수정일", "updated_at", "updatedat")
        // Search preset
        alias("검색어", "query", "search_query")
        alias("필터(JSON)", "filters", "filter_json")
        alias("정렬모드", "sort_mode", "sortmode")
        alias("기본값", "is_default", "default")
        // 캐릭터 목록 프리셋
        alias("태그(JSON)", "tags_json", "tagsjson")
        alias("필드필터(JSON)", "field_filters_json", "fieldfiltersjson")
        alias("정렬종류", "sort_kind", "sortkind")
        alias("정렬필드키", "sort_field_key", "sortfieldkey")
        alias("정렬오름차순", "sort_ascending", "오름차순", "ascending")
        alias("신체파트번호", "body_size_part_index", "신체파트", "bodypartindex")
        alias("작품코드목록", "novel_codes", "작품 코드 목록", "novelcodes")
        // App settings
        alias("설정키", "setting_key", "key")
        alias("설정값", "setting_value", "value")
        // Faction
        alias("색상", "color", "faction_color")
        alias("자동관계유형", "auto_relation_type", "autorelationtype")
        alias("자동관계강도", "auto_relation_intensity", "autorelationintensity")
        // Faction membership
        alias("세력", "faction", "faction_name")
        alias("가입연도", "join_year", "joinyear")
        alias("탈퇴연도", "leave_year", "leaveyear")
        alias("탈퇴유형", "leave_type", "leavetype")
        alias("탈퇴후관계유형", "departed_relation_type", "departedrelationtype")
        alias("탈퇴후강도", "departed_intensity", "departedintensity")
        alias("세력코드", "faction_code", "factioncode")
        // 이미지 시트 (G3)
        alias("파일명", "filename", "file_name", "파일 명")
        alias("링크그룹", "link_group", "linkgroup", "링크 그룹")
    }

    /** 원본 헤더 → 표준 헤더명. 등록된 별칭이 없으면 원본을 그대로 돌려준다. */
    fun canonical(rawHeader: String): String =
        map[rawHeader.normalizeHeader()] ?: rawHeader
}

/**
 * 별칭 매칭용 헤더 정규화: 소문자화 + 공백·밑줄·하이픈·괄호 제거.
 */
internal fun String.normalizeHeader(): String =
    this.trim().lowercase().replace(Regex("[\\s_\\-()（）]"), "")
