package com.novelcharacter.app.excel

/**
 * 작업형 내보내기의 진행 보고 창구(규약 R-26 · 설계 `backup_export_redesign` D5).
 *
 * 총량을 아는 순회 구간이 **둘**이고 단위가 다르다 — 시트는 '장(sheet)', 이미지는 '장(件)'이다.
 * 그래서 하나의 합계로 합치지 않고 구간별로 자기 총량을 보고한다. 합치면 "18개 중 3개"와
 * "1,200장 중 700장"이 한 막대에 섞여, 막대가 3%에서 오래 멈춘 뒤 한 번에 뛴다(R-26이
 * 금지하는 거짓말하는 진행도).
 *
 * **취소는 산출물을 만들지 않고 멈추는 것이다.** R-26은 "항목 단위로 완결되는 작업만 취소를
 * 제공한다(반쪽 항목이 남으면 안 된다)"고 정하는데, 내보내기에서 사용자가 받는 '항목'은
 * 파일 하나다. 반쯤 쓴 워크북을 건네면 그 파일로 복원할 때 조용히 유실된다
 * (개발 의도 2번 '변수 제어'). 그래서 취소하면 임시 파일을 지우고 아무것도 건네지 않는다.
 */
class ExportProgressSink(
    /** 시트 구간 진행 — (완료 시트 수, 전체 시트 수). 시작 시 (0, total)이 한 번 온다. */
    val onSheets: (done: Int, total: Int) -> Unit,
    /** 이미지 구간 진행 — (담은 장 수, 전체 장 수). 이미지를 담지 않으면 호출되지 않는다. */
    val onImages: (done: Int, total: Int) -> Unit,
    /** 취소 요청 여부 — 순회가 항목마다 확인한다. */
    val isCancelled: () -> Boolean
)

/**
 * 내보내기가 쓸 시트 단계와 그 순서.
 *
 * **이 목록이 시트 순서의 단일 소스다** — [ExcelExporter.populateWorkbook]이 실행에 쓰고,
 * 진행도가 총량(R-26: "총량 확정 후에 show한다")에 쓴다. 종전처럼 실행은 if 연쇄로 두고
 * 총량만 따로 세면 두 벌이 되어, 갈리는 순간 막대가 100%를 넘거나 못 미친 채 끝난다.
 *
 * 순수 계층이라 하네스가 순서·조건을 그대로 검증한다.
 */
enum class ExportSheetStep {
    INSTRUCTIONS,
    UNIVERSES,
    NOVELS,
    // 등급 체계를 정의보다 먼저 — 시트 순서가 곧 가져오기 순서는 아니지만(가져오기는 이름으로
    // 찾는다), 파일을 여는 사람이 참조 대상을 먼저 보게 된다.
    GRADE_SYSTEMS,
    FIELD_DEFINITIONS,
    FIELD_VALUE_LIBRARY,
    // 이미지 시트는 반드시 캐릭터 시트보다 먼저 — 세계관 이름이 "이미지"여도 예약 시트가
    // 원명을 선점하고 캐릭터 시트는 sanitize("(2)")되어 가져오기에서 충돌하지 않는다.
    IMAGE_META,
    CHARACTERS,
    TIMELINE,
    STATE_CHANGES,
    RELATIONSHIPS,
    RELATIONSHIP_CHANGES,
    NAME_BANK,
    FACTIONS,
    FACTION_MEMBERSHIPS,
    FACTION_RELATIONSHIPS,
    PRESET_TEMPLATES,
    SEARCH_PRESETS,
    CHARACTER_LIST_PRESETS,
    APP_SETTINGS;

    companion object {
        /**
         * 이 선택이 실제로 쓸 단계 목록 — **순서가 곧 파일의 시트 순서다.**
         *
         * '사용 안내'는 선택 대상이 아니라 늘 들어간다(옵션에 대응하는 스위치가 없다).
         * `images`는 시트가 아니라 ZIP 래핑이라 여기에 없다 — 그 구간의 진행은
         * [ExportProgressSink.onImages]가 따로 보고한다.
         */
        fun of(options: ExportOptions): List<ExportSheetStep> = buildList {
            add(INSTRUCTIONS)
            if (options.universes) add(UNIVERSES)
            if (options.novels) add(NOVELS)
            if (options.fieldDefinitions) {
                add(GRADE_SYSTEMS)
                add(FIELD_DEFINITIONS)
                add(FIELD_VALUE_LIBRARY)
            }
            if (options.imageMeta) add(IMAGE_META)
            if (options.characters) add(CHARACTERS)
            if (options.timeline) add(TIMELINE)
            if (options.stateChanges) add(STATE_CHANGES)
            if (options.relationships) add(RELATIONSHIPS)
            if (options.relationshipChanges) add(RELATIONSHIP_CHANGES)
            if (options.nameBank) add(NAME_BANK)
            if (options.factions) add(FACTIONS)
            if (options.factionMemberships) add(FACTION_MEMBERSHIPS)
            if (options.factionRelationships) add(FACTION_RELATIONSHIPS)
            if (options.presetTemplates) add(PRESET_TEMPLATES)
            if (options.searchPresets) add(SEARCH_PRESETS)
            if (options.characterListPresets) add(CHARACTER_LIST_PRESETS)
            if (options.appSettings) add(APP_SETTINGS)
        }
    }
}

/**
 * 사용자가 내보내기를 취소했다.
 *
 * 실패가 아니므로 호출부는 이것을 오류로 보고하지 않는다 — 임시 파일을 지우고 조용히
 * "취소했습니다"만 알린다. 일반 [Exception]과 구분되는 타입인 이유가 그것이다.
 */
class ExportCancelledException : Exception("Export cancelled by user")

/**
 * 저장 공간 부족 판별(설계 D7 — R-17 실패 사유 갈래).
 *
 * 수 GB짜리 zip을 기본 백업으로 승격하면 "쓰다가 공간이 모자란다"가 실제로 일어나는 갈래가
 * 되는데, 그것을 일반 실패 토스트 하나로 뭉뚱그리면 사용자는 **무엇을 하면 되는지 모른다**.
 *
 * 판별을 문자열로 하는 이유: 이 경로의 예외는 `FileOutputStream.write`가 던지는
 * `IOException`이고, 안드로이드는 그 메시지에 `ENOSPC (No space left on device)`를 싣는다.
 * `android.system.ErrnoException`을 직접 보려면 이 파일이 안드로이드 런타임에 묶여
 * 순수 하네스에서 검증할 수 없게 되므로, **원인 사슬 전체를 훑는 문자열 판별**로 두고
 * 대신 하네스가 실제 메시지 형태들로 검증한다.
 */
object ExportSpace {

    /** 안드로이드·JVM이 공간 부족에 싣는 표지. 소문자로 비교한다. */
    private val MARKERS = listOf("enospc", "no space left on device")

    /** 원인 사슬(cause chain) 어디에든 공간 부족 표지가 있으면 true. */
    fun isOutOfSpace(error: Throwable?): Boolean {
        var current = error
        // 사슬이 자기 자신을 가리키는 경우(드물지만 가능)에 무한 순회하지 않도록 상한을 둔다
        var hops = 0
        while (current != null && hops < 32) {
            val message = current.message?.lowercase()
            if (message != null && MARKERS.any { it in message }) return true
            current = current.cause
            hops++
        }
        return false
    }

    /**
     * 사용자에게 보일 필요 용량(MB, 올림). 0바이트여도 최소 1MB로 말한다 —
     * "약 0MB가 필요합니다"는 안내가 아니다.
     */
    fun requiredMegabytes(bytes: Long): Int {
        if (bytes <= 0L) return 1
        val mb = (bytes + 1_048_575L) / 1_048_576L
        return mb.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
