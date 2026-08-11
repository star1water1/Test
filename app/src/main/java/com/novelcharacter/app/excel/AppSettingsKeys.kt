package com.novelcharacter.app.excel

/**
 * '앱 설정' 시트가 **무엇을 싣는가**의 단일 소스 — 키·형식·처분의 순수 선언 (B-105).
 *
 * ## 왜 있는가 — 개수가 아니라 구조가 문제였다
 *
 * 종전 내보내기는 `writeTextRow`/`writeNumberRow`를 **손으로 나열**하고 가져오기는
 * `when (key)`를 **손수 분기**했다. 코드 주석이 스스로 그렇게 적어 두었다:
 * *"key/value 구조라 항목 추가는 가져오기(when 분기)와 짝으로 확장한다"* —
 * **설정 하나를 늘릴 때마다 두 곳을 손대야 하니 늘지 않는 것이 당연했다.**
 * 그래서 실린 것은 11개뿐이고 저장소는 스무 곳이 넘었다(원칙 01이 정면으로 겨누는 자리).
 *
 * ## 이 파일과 [AppSettingsBindings]가 갈린 이유
 *
 * **여기는 순수하고 저기는 `Context`를 쥔다.** 한 파일에 합치면 선언까지 `Context`에
 * 딸려 가 **순수 JVM 시험이 원리적으로 못 본다**(B-132가 난 자리이고, B-108이 판정만
 * 갈라 낸 것과 같은 근거다). 시트의 왕복 계약 — *어떤 키가 있고, 어떤 형식이며, 실리는가* —
 * 은 여기 있고 시험이 잠근다. 실제 읽기·쓰기만 저쪽이다.
 *
 * **두 벌이 갈리는 것은 기계가 본다** — `tools/check_app_settings_catalog.sh`(규약 R-45):
 * ① [SPECS]의 키가 전부 바인딩을 갖는가 ② 앱의 설정 저장소가 전부 이 카탈로그에
 * 등재되거나 [EXCLUDED_STORES]에 **사유와 함께** 빠져 있는가. 그 검사가 없으면
 * 이 분리가 곧 종전의 '두 곳'을 이름만 바꿔 되살린다.
 *
 * ## 처분 3분류 — 사용자 확정 3번(ㄱ1·ㄴ1)
 *
 * - [Disposition.PORTABLE] **실어야 하는 것** — 새 기기에서 다시 맞추기 싫은 것.
 * - [Disposition.SECRET] **평문으로 새면 안 되는 것** — API 키. 엑셀은 평문이고 사용자가
 *   남에게 보내기도 하는 파일이라 **기본 제외 + 별도 동의**다. 편의가 아니라 안전 문제다.
 * - **싣지 않는 것** — 기기 로컬 화면 흔적(마지막 스크롤 위치·마지막 선택 작품 등).
 *   왕복해 봐야 소음이다. 이쪽은 키가 아니라 저장소 단위라 [EXCLUDED_STORES]에 적는다.
 */
object AppSettingsKeys {

    /** 셀에 적히는 형식. [NUMBER]만 숫자 셀이고 나머지는 글자다(종전 왕복과 같은 모양). */
    enum class Kind { TEXT, NUMBER, BOOLEAN }

    /** 확정 3번의 3분류 중 **싣는** 둘. 싣지 않는 것은 [EXCLUDED_STORES]가 든다. */
    enum class Disposition { PORTABLE, SECRET }

    /**
     * 한 설정의 선언.
     *
     * @param key 시트의 `설정키` 셀 값. **바꾸면 옛 파일이 그 설정을 잃는다** — 이름을
     *   갈고 싶으면 옛 이름을 [ALIASES]에 남길 것.
     */
    data class Spec(
        val key: String,
        val kind: Kind,
        val disposition: Disposition = Disposition.PORTABLE
    )

    // ── 화면 ──
    val THEME_MODE = Spec("theme_mode", Kind.NUMBER)

    // ── 백업 ──
    val BACKUP_INCLUDE_IMAGES = Spec("backup_include_images", Kind.BOOLEAN)
    val BACKUP_MAX_BACKUPS = Spec("backup_max_backups", Kind.NUMBER)

    // ── 휴지통 보관 정책 (B-74) ──
    // 초과분의 결과가 **영구 삭제**라, 기기를 옮길 때 함께 가야 하는 설정이다.
    val TRASH_MAX_OPERATIONS = Spec("trash_max_operations", Kind.NUMBER)
    val TRASH_RETENTION_DAYS = Spec("trash_retention_days", Kind.NUMBER)

    // ── 필드 가져오기 (B-63 · 확정 14번) ──
    // 종류를 바꿔 심어도 되는 필드 타입 목록. 콤마로 잇는다 — 타입은 앞으로 늘 수 있어
    // 스위치를 타입마다 두면 옛 파일에 그 키가 없을 때 켬·끔을 가릴 수 없다.
    val FIELD_IMPORT_CONVERTIBLE_TYPES = Spec("field_import_convertible_types", Kind.TEXT)

    // ── 이미지 저장 ──
    val IMAGE_COMPRESS_ENABLED = Spec("image_compress_enabled", Kind.BOOLEAN)
    val IMAGE_QUALITY_PERCENT = Spec("image_quality_percent", Kind.NUMBER)
    val IMAGE_CAP_DIMENSION = Spec("image_cap_dimension", Kind.BOOLEAN)
    val IMAGE_MAX_LONG_EDGE_PX = Spec("image_max_long_edge_px", Kind.NUMBER)
    val IMAGE_SKIP_BELOW_ENABLED = Spec("image_skip_below_enabled", Kind.BOOLEAN)
    val IMAGE_SKIP_BELOW_BYTES = Spec("image_skip_below_bytes", Kind.NUMBER)
    val IMAGE_EDITOR_REMOVE_POLICY = Spec("image_editor_remove_policy", Kind.TEXT)
    val IMAGE_AUTO_LINK_BY_CHARACTER = Spec("image_auto_link_by_character", Kind.BOOLEAN)

    // ── AI 연동 ──
    /**
     * 프로바이더 목록 — `AiProviderCodec`가 낸 JSON 그대로.
     *
     * **키는 여기 없다**(`AiProviderConfig`에 그 칸이 없고, 키는 [AI_API_KEYS]가 따로 든다).
     * 그래서 이 행은 평문 위험 없이 실린다 — 새 기기는 주소·모델·순서를 그대로 받고
     * 키만 다시 넣으면 된다.
     */
    val AI_PROVIDERS = Spec("ai_providers", Kind.TEXT)
    val AI_ACTIVE_PROVIDER = Spec("ai_active_provider", Kind.TEXT)
    val AI_USAGE_EXAMPLE_COUNT = Spec("ai_usage_example_count", Kind.NUMBER)
    val AI_STYLE_SAMPLE_COUNT = Spec("ai_style_sample_count", Kind.NUMBER)
    val AI_MIN_CONFIDENCE = Spec("ai_min_confidence", Kind.TEXT)
    val AI_CREATIVITY = Spec("ai_creativity", Kind.TEXT)
    val AI_ATTACH_IMAGE_COUNT = Spec("ai_attach_image_count", Kind.NUMBER)
    val AI_ATTACH_REPRESENTATIVE_FIRST = Spec("ai_attach_representative_first", Kind.BOOLEAN)
    val AI_IMAGE_TAG_POLICY = Spec("ai_image_tag_policy", Kind.TEXT)
    val AI_IMAGE_TAG_BATCH_SIZE = Spec("ai_image_tag_batch_size", Kind.NUMBER)
    val AI_NAME_SUGGEST_BATCH_SIZE = Spec("ai_name_suggest_batch_size", Kind.NUMBER)

    /**
     * API 키 전부 — `{"프로바이더id":"키"}` JSON 한 칸.
     *
     * **기본 제외다**(사용자 확정 3번 ㄴ1). 켜려면 내보내기에서 별도 동의를 눌러야 하고,
     * 그때도 경고를 먼저 본다 — **엑셀은 평문이고 사용자가 남에게 보내기도 하는 파일**이다.
     * 한 칸에 모으는 것은 프로바이더 수가 가변이라 [SPECS]에 정적으로 적을 수 없어서다.
     */
    val AI_API_KEYS = Spec("ai_api_keys", Kind.TEXT, Disposition.SECRET)

    // ── 통계 기준 ──
    val STATS_COMPLETION_REQUIRED_WEIGHT = Spec("stats_completion_required_weight", Kind.NUMBER)
    val STATS_PATTERN_TYPES = Spec("stats_pattern_types", Kind.TEXT)

    // ── 보충 기준 ──
    val SUPPLEMENT_CHECK_IMAGES = Spec("supplement_check_images", Kind.BOOLEAN)
    val SUPPLEMENT_CHECK_MEMO = Spec("supplement_check_memo", Kind.BOOLEAN)
    val SUPPLEMENT_CHECK_ALIASES = Spec("supplement_check_aliases", Kind.BOOLEAN)
    val SUPPLEMENT_CHECK_NOVEL = Spec("supplement_check_novel", Kind.BOOLEAN)
    val SUPPLEMENT_CHECK_TAGS = Spec("supplement_check_tags", Kind.BOOLEAN)
    val SUPPLEMENT_CHECK_CUSTOM_FIELDS = Spec("supplement_check_custom_fields", Kind.BOOLEAN)
    val SUPPLEMENT_CHECK_RELATIONSHIPS = Spec("supplement_check_relationships", Kind.BOOLEAN)
    val SUPPLEMENT_CHECK_EVENTS = Spec("supplement_check_events", Kind.BOOLEAN)
    val SUPPLEMENT_CHECK_FACTIONS = Spec("supplement_check_factions", Kind.BOOLEAN)
    val SUPPLEMENT_FIELD_THRESHOLD = Spec("supplement_field_threshold", Kind.NUMBER)
    val SUPPLEMENT_AUTO_SAVE_ON_EXIT = Spec("supplement_auto_save_on_exit", Kind.BOOLEAN)

    // ── 어시스턴트 ──
    val ASSISTANT_CATEGORIES = Spec("assistant_categories", Kind.TEXT)

    /**
     * 시트에 실리는 설정 전수 — **순서가 곧 시트의 행 순서**다.
     *
     * 갈래별로 묶어 두는 것은 사람이 엑셀에서 훑기 위해서다(이 사용자는 엑셀 우선
     * 작업자다 — `docs/usage_reality_check_2026-07.md`). 알파벳순으로 늘어놓으면
     * `ai_`·`image_`·`supplement_`가 서로를 끊는다.
     */
    val SPECS: List<Spec> = listOf(
        THEME_MODE,
        BACKUP_INCLUDE_IMAGES, BACKUP_MAX_BACKUPS,
        TRASH_MAX_OPERATIONS, TRASH_RETENTION_DAYS,
        FIELD_IMPORT_CONVERTIBLE_TYPES,
        IMAGE_COMPRESS_ENABLED, IMAGE_QUALITY_PERCENT, IMAGE_CAP_DIMENSION,
        IMAGE_MAX_LONG_EDGE_PX, IMAGE_SKIP_BELOW_ENABLED, IMAGE_SKIP_BELOW_BYTES,
        IMAGE_EDITOR_REMOVE_POLICY, IMAGE_AUTO_LINK_BY_CHARACTER,
        AI_PROVIDERS, AI_ACTIVE_PROVIDER,
        AI_USAGE_EXAMPLE_COUNT, AI_STYLE_SAMPLE_COUNT, AI_MIN_CONFIDENCE, AI_CREATIVITY,
        AI_ATTACH_IMAGE_COUNT, AI_ATTACH_REPRESENTATIVE_FIRST,
        AI_IMAGE_TAG_POLICY, AI_IMAGE_TAG_BATCH_SIZE, AI_NAME_SUGGEST_BATCH_SIZE,
        AI_API_KEYS,
        STATS_COMPLETION_REQUIRED_WEIGHT, STATS_PATTERN_TYPES,
        SUPPLEMENT_CHECK_IMAGES, SUPPLEMENT_CHECK_MEMO, SUPPLEMENT_CHECK_ALIASES,
        SUPPLEMENT_CHECK_NOVEL, SUPPLEMENT_CHECK_TAGS, SUPPLEMENT_CHECK_CUSTOM_FIELDS,
        SUPPLEMENT_CHECK_RELATIONSHIPS, SUPPLEMENT_CHECK_EVENTS, SUPPLEMENT_CHECK_FACTIONS,
        SUPPLEMENT_FIELD_THRESHOLD, SUPPLEMENT_AUTO_SAVE_ON_EXIT,
        ASSISTANT_CATEGORIES
    )

    /**
     * 옛 이름 → 현행 키. **이름을 갈 때 여기 한 줄을 남기면 옛 파일이 그대로 들어온다.**
     *
     * 지금은 비어 있다 — 종전 11개의 이름을 하나도 바꾸지 않았기 때문이고, **그것이 판단이다**:
     * 이 슬라이스는 구조를 갈았지 시트의 말을 갈지 않았다. 왕복 무결성은 *같은 파일이 같은 뜻*
     * 이어야 성립하고, 구조 개편을 이유로 옛 파일의 뜻을 바꾸면 그 자리에서 깨진다.
     */
    val ALIASES: Map<String, String> = emptyMap()

    /** 키로 찾는다. 옛 이름도 [ALIASES]를 지나 같은 설정에 닿는다. */
    fun specOf(key: String): Spec? {
        val normalized = key.trim()
        val canonical = ALIASES[normalized] ?: normalized
        return SPECS.firstOrNull { it.key == canonical }
    }

    /** 이 선택으로 시트에 나갈 설정 — [includeSecrets]가 꺼져 있으면 비밀은 빠진다. */
    fun exported(includeSecrets: Boolean): List<Spec> =
        if (includeSecrets) SPECS else SPECS.filter { it.disposition != Disposition.SECRET }

    // ── 셀 값의 표기 — 순수이므로 여기 있다(시트가 무슨 글자를 담는가도 왕복 계약이다) ──

    /** 불리언의 셀 표기. 읽기는 `parseSheetBoolean`이 관대하게 받는다(Y/예/1/TRUE…). */
    fun formatBoolean(value: Boolean): String = if (value) "Y" else "N"

    /**
     * 숫자의 셀 표기 — 정수면 소수점을 붙이지 않는다(`3.0`이 아니라 `3`).
     *
     * **[Float]를 [Double]로 넓혀서 적으면 안 된다.** `1.3f.toDouble()`은
     * `1.2999999523162842`이고, 그대로 셀에 적으면 **사용자가 엑셀에서 보는 것이 그 숫자**이며
     * 왕복도 같은 글자로 돌아오지 않는다(완성도 가중이 실제로 `Float`다).
     * `Float`는 그 폭 그대로 문자열로 만든다 — `1.3f.toString()`은 `"1.3"`이다.
     */
    fun formatNumber(value: Number): String = when (value) {
        is Float -> if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        else -> {
            val d = value.toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
    }

    /**
     * 셀 글자 → 정수. **[Double]을 거치는 것이 일부러다** — 숫자 셀은 `51200`을 `51200.0`으로
     * 돌려주기도 하므로 `toIntOrNull()`로 바로 받으면 멀쩡한 값이 *"숫자가 아닙니다"*가 된다.
     */
    fun parseIntCell(value: String): Int? = value.trim().toDoubleOrNull()?.toInt()

    /**
     * 싣지 않는 저장소와 **그 사유** — 확정 3번의 ⓒ('실을 값어치가 없는 것').
     *
     * **사유를 함께 적는 것이 이 목록의 값이다.** 이름만 나열하면 다음 사람이
     * *"왜 빠졌지"*를 알 수 없어 다시 조사하거나, 값어치가 없다고 지레 넘긴다.
     * 검사 스크립트는 *등재돼 있는가*만 보고 사유의 내용은 사람이 읽는다.
     */
    val EXCLUDED_STORES: Map<String, String> = mapOf(
        "app_migrations" to
            "앱 내부 이행 기록이다. 실어서 되돌리면 **아직 안 한 이행을 했다고 표시**하게 되어 " +
            "그 기기의 데이터가 조용히 옛 형식으로 남는다 — 소음이 아니라 위험이다.",
        "onboarding_prefs" to "첫 실행 안내를 봤는가. 기기마다 처음이 다르다.",
        "character_edit_drafts" to "저장하지 않은 편집 초안. 그 기기의 화면 상태다.",
        "folder_roundtrip_prefs" to "폴더 왕복이 기억한 기기 경로·URI. 다른 기기에서는 가리킬 곳이 없다.",
        "theme_cache" to "테마 캐시 — 값 자체는 ${THEME_MODE.key}로 실린다(저장소가 아니라 키로 등재된 자리).",
        "settings" to "테마의 DataStore 본체. 값은 ${THEME_MODE.key}로 실린다.",
        "image_settings" to "이미지 저장 설정의 DataStore. 값은 `image_`로 시작하는 키들로 실린다.",
        "trash_settings" to
            "휴지통 보관 정책의 DataStore. 값은 ${TRASH_MAX_OPERATIONS.key}·${TRASH_RETENTION_DAYS.key}로 실린다.",
        "field_import_settings" to
            "필드 가져오기의 종류 변환 허용 타입 DataStore. 값은 ${FIELD_IMPORT_CONVERTIBLE_TYPES.key}로 실린다.",
        "backup_status" to
            "마지막 백업의 성공·실패 시각과 사유. **설정이 아니라 그 기기에서 일어난 일의 기록이다** — " +
            "옮기면 새 기기가 하지도 않은 백업을 했다고 말한다.",
        "character_list_ui" to "목록 화면의 마지막 보기 상태(스크롤·펼침).",
        "character_detail_ui_state" to "상세 화면의 마지막 정렬.",
        "search_ui_state" to "검색 화면의 마지막 입력·펼침.",
        "timeline_ui_state" to "연표 화면의 마지막 축척·위치.",
        "graph_ui_state" to "관계도 화면의 마지막 배치.",
        "field_manage_ui_state" to "필드 관리 화면의 마지막 보기.",
        "field_library_ui_state" to "값 라이브러리 화면의 마지막 보기.",
        "namebank_ui_state" to "이름 은행 화면의 마지막 보기.",
        "analysis_ui_state" to "분석 홈의 마지막 탭·펼침.",
        "duel_entry" to "대결 입장 화면의 마지막 선택.",
        "duel_view" to
            "대결 카드 배치·그림 맞춤. **취향이라 기기 설정이다** — 같은 데이터를 두고도 " +
            "기기마다 달리 보고 싶을 수 있고, 갈려도 데이터가 갈리지 않는다(그 저장소의 KDoc이 든 근거).",
        "duel_image_basis" to
            "기준 이미지 축을 얼마나 세게 쓰는가. 위와 같은 근거로 기기 설정이다 — " +
            "**어느 축을 따르는가는 DB가 들어** 이미 백업·엑셀을 따라 넘어간다.",
        "ai_keys" to "API 키의 암호화 저장소. 값은 ${AI_API_KEYS.key}로 **동의했을 때만** 실린다.",
        "ai_providers" to "프로바이더 저장소. 값은 ${AI_PROVIDERS.key}·${AI_ACTIVE_PROVIDER.key}로 실린다.",
        "ai_prompt_settings" to "AI 프롬프트 설정 저장소. 값은 `ai_`로 시작하는 키들로 실린다.",
        "stats_prefs" to "통계 설정 저장소. 값은 `stats_`로 시작하는 키들로 실린다.",
        "supplement_criteria" to "보충 기준 저장소. 값은 `supplement_check_`·`supplement_field_threshold`로 실린다.",
        "supplement_ui_state" to
            "보충 화면 상태. 그중 자동 저장 스위치만 ${SUPPLEMENT_AUTO_SAVE_ON_EXIT.key}로 실린다 — " +
            "이름이 `ui_state`라고 전부 화면 흔적인 것은 아니다.",
        "assistant_prefs" to
            "어시스턴트 저장소. 카테고리 on/off는 ${ASSISTANT_CATEGORIES.key}로 실리고, " +
            "**카드 숨김은 싣지 않는다** — 숨김은 그 시점의 수치와 짝인 상태라 다른 기기로 옮기면 " +
            "엉뚱한 카드가 숨는다.",
        "backup_settings" to "백업 설정 저장소(DataStore). 값은 `backup_`으로 시작하는 키들로 실린다."
    )
}
