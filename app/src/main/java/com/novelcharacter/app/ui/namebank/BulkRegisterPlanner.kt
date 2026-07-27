package com.novelcharacter.app.ui.namebank

import com.novelcharacter.app.data.model.NameBankEntry

/**
 * 이름은행 → 일괄 캐릭터 등록 계획 수립 (순수 JVM — 단위 테스트 대상).
 *
 * 계약 (변수 제어):
 * - 건너뜀·미기록은 조용히 버리지 않고 전부 건수로 집계해 돌려준다 (R-14).
 * - 성별은 은행 값이 대상 세계관 성별 필드의 SELECT 옵션에 **정확히 존재할 때만** 기록한다 —
 *   옵션에 없는 값의 강제 기입·임의 변환("" → "?" 등) 금지 (R-11: "미지정"과 "?"는 다른 의미).
 */
object BulkRegisterPlanner {

    enum class DuplicatePolicy { REGISTER_ALL, SKIP_DUPLICATES }

    data class Options(
        val novelId: Long?,
        val mapGender: Boolean,
        /** 대상 세계관 성별 필드의 SELECT 옵션 — 없으면 빈 목록(매핑 무효) */
        val genderOptions: List<String>,
        val includeOriginNotes: Boolean,
        /** 출처 표기 접두 형식 (예: "출처: %1$s") — 호출측이 리소스에서 주입 */
        val originPrefixFormat: String,
        val policy: DuplicatePolicy
    )

    data class PlannedItem(
        val entry: NameBankEntry,
        /** 기록할 성별 필드값 — 옵션 불일치·매핑 꺼짐이면 null */
        val genderValue: String?,
        val memo: String
    )

    data class Plan(
        val toCreate: List<PlannedItem>,
        /** SKIP_DUPLICATES 정책으로 건너뛴 건수 (기존 캐릭터 + 선택 내 선행 항목과의 중복) */
        val skippedDuplicates: Int,
        /** 성별 값이 있으나 옵션에 없어 기록하지 못한 건수 */
        val genderUnmatched: Int,
        /** 등록 대상 중 이미 사용 표시된 엔트리 수 — 사용 표시가 새 캐릭터로 이동함 */
        val alreadyUsedCount: Int,
        /** 이름이 빈 엔트리 수 (인앱 생성 경로에서는 발생 불가 — 방어적 집계) */
        val blankSkipped: Int
    )

    /** (기존 캐릭터와 이름 충돌 건수, 선택 내부 중복 건수) — 실행 전 고지용 (R-4) */
    fun countCollisions(entries: List<NameBankEntry>, existingNames: Set<String>): Pair<Int, Int> {
        var vsExisting = 0
        var withinSelection = 0
        val seen = HashSet<String>()
        for (entry in entries) {
            val name = entry.name.trim()
            if (name.isEmpty()) continue
            if (name in existingNames) vsExisting++
            if (!seen.add(name)) withinSelection++
        }
        return vsExisting to withinSelection
    }

    fun plan(entries: List<NameBankEntry>, existingNames: Set<String>, options: Options): Plan {
        val toCreate = mutableListOf<PlannedItem>()
        var skippedDuplicates = 0
        var genderUnmatched = 0
        var alreadyUsed = 0
        var blankSkipped = 0
        val plannedNames = HashSet<String>()

        for (entry in entries) {
            val name = entry.name.trim()
            if (name.isEmpty()) {
                blankSkipped++
                continue
            }
            val isDuplicate = name in existingNames || name in plannedNames
            if (isDuplicate && options.policy == DuplicatePolicy.SKIP_DUPLICATES) {
                skippedDuplicates++
                continue
            }
            plannedNames.add(name)
            if (entry.isUsed) alreadyUsed++

            val genderValue: String? = if (options.mapGender && entry.gender.isNotBlank()) {
                if (entry.gender in options.genderOptions) {
                    entry.gender
                } else {
                    genderUnmatched++
                    null
                }
            } else null

            val memo = if (options.includeOriginNotes) {
                buildList {
                    if (entry.origin.isNotBlank()) add(String.format(options.originPrefixFormat, entry.origin))
                    if (entry.notes.isNotBlank()) add(entry.notes)
                }.joinToString("\n")
            } else ""

            toCreate.add(PlannedItem(entry, genderValue, memo))
        }
        return Plan(toCreate, skippedDuplicates, genderUnmatched, alreadyUsed, blankSkipped)
    }
}
