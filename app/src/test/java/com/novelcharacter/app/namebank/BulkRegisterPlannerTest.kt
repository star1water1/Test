package com.novelcharacter.app.namebank

import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.ui.namebank.BulkRegisterPlanner
import com.novelcharacter.app.ui.namebank.BulkRegisterPlanner.DuplicatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 일괄 캐릭터 등록 계획 수립 검증.
 * 핵심 계약: 건너뜀·미기록은 전부 건수로 집계(R-14),
 * 성별은 옵션 정확 일치 시에만 기록·임의 변환 금지(R-11).
 */
class BulkRegisterPlannerTest {

    private var nextId = 1L
    private fun entry(
        name: String,
        gender: String = "",
        origin: String = "",
        notes: String = "",
        isUsed: Boolean = false
    ) = NameBankEntry(
        id = nextId++, name = name, gender = gender, origin = origin, notes = notes, isUsed = isUsed
    )

    private fun options(
        mapGender: Boolean = true,
        genderOptions: List<String> = listOf("남", "여", "?"),
        includeOriginNotes: Boolean = true,
        policy: DuplicatePolicy = DuplicatePolicy.REGISTER_ALL
    ) = BulkRegisterPlanner.Options(
        novelId = 1L,
        mapGender = mapGender,
        genderOptions = genderOptions,
        includeOriginNotes = includeOriginNotes,
        originPrefixFormat = "출처: %1\$s",
        policy = policy
    )

    // ===== 중복 정책 =====

    @Test
    fun skipPolicy_existingAndWithinSelection_skippedAndCounted() {
        val entries = listOf(entry("한서린"), entry("강도윤"), entry("한서린"))
        val plan = BulkRegisterPlanner.plan(
            entries, existingNames = setOf("강도윤"), options(policy = DuplicatePolicy.SKIP_DUPLICATES)
        )
        // "강도윤"은 기존 캐릭터와 충돌, 두 번째 "한서린"은 선택 내 선행 항목과 충돌
        assertEquals(1, plan.toCreate.size)
        assertEquals("한서린", plan.toCreate[0].entry.name)
        assertEquals(2, plan.skippedDuplicates)
    }

    @Test
    fun registerAllPolicy_duplicatesCreated() {
        val entries = listOf(entry("한서린"), entry("한서린"))
        val plan = BulkRegisterPlanner.plan(
            entries, existingNames = setOf("한서린"), options(policy = DuplicatePolicy.REGISTER_ALL)
        )
        assertEquals(2, plan.toCreate.size)
        assertEquals(0, plan.skippedDuplicates)
    }

    // ===== 성별 매핑 =====

    @Test
    fun gender_matchingOption_mapped() {
        val plan = BulkRegisterPlanner.plan(listOf(entry("가", gender = "남")), emptySet(), options())
        assertEquals("남", plan.toCreate[0].genderValue)
        assertEquals(0, plan.genderUnmatched)
    }

    @Test
    fun gender_blank_notRecordedNotCounted() {
        // "미지정"("")을 "?"로 임의 변환하지 않는다 — 두 값은 다른 의미 (R-11)
        val plan = BulkRegisterPlanner.plan(listOf(entry("가", gender = "")), emptySet(), options())
        assertNull(plan.toCreate[0].genderValue)
        assertEquals(0, plan.genderUnmatched)
    }

    @Test
    fun gender_unmatchedOption_skippedAndCounted() {
        val plan = BulkRegisterPlanner.plan(
            listOf(entry("가", gender = "무성")), emptySet(), options(genderOptions = listOf("남", "여"))
        )
        assertNull(plan.toCreate[0].genderValue)
        assertEquals(1, plan.genderUnmatched)
    }

    @Test
    fun gender_mappingOff_notRecorded() {
        val plan = BulkRegisterPlanner.plan(
            listOf(entry("가", gender = "남")), emptySet(), options(mapGender = false)
        )
        assertNull(plan.toCreate[0].genderValue)
        assertEquals(0, plan.genderUnmatched)
    }

    // ===== 메모 조합 =====

    @Test
    fun memo_originAndNotes_joined() {
        val plan = BulkRegisterPlanner.plan(
            listOf(entry("가", origin = "북유럽 신화", notes = "차가운 인상")), emptySet(), options()
        )
        assertEquals("출처: 북유럽 신화\n차가운 인상", plan.toCreate[0].memo)
    }

    @Test
    fun memo_originOnly_notesOnly_toggleOff() {
        val originOnly = BulkRegisterPlanner.plan(listOf(entry("가", origin = "설화")), emptySet(), options())
        assertEquals("출처: 설화", originOnly.toCreate[0].memo)

        val notesOnly = BulkRegisterPlanner.plan(listOf(entry("나", notes = "메모만")), emptySet(), options())
        assertEquals("메모만", notesOnly.toCreate[0].memo)

        val off = BulkRegisterPlanner.plan(
            listOf(entry("다", origin = "설화", notes = "메모")), emptySet(), options(includeOriginNotes = false)
        )
        assertEquals("", off.toCreate[0].memo)
    }

    // ===== 사용됨·빈 이름 =====

    @Test
    fun usedEntries_countedForNotice() {
        val plan = BulkRegisterPlanner.plan(
            listOf(entry("가", isUsed = true), entry("나")), emptySet(), options()
        )
        assertEquals(2, plan.toCreate.size)
        assertEquals(1, plan.alreadyUsedCount)
    }

    @Test
    fun blankName_skippedDefensively() {
        val plan = BulkRegisterPlanner.plan(listOf(entry("  "), entry("가")), emptySet(), options())
        assertEquals(1, plan.toCreate.size)
        assertEquals(1, plan.blankSkipped)
        assertEquals(0, plan.skippedDuplicates)
    }

    // ===== 사전 고지 집계 =====

    @Test
    fun countCollisions_separatesExistingAndWithinSelection() {
        val entries = listOf(entry("한서린"), entry("한서린"), entry("강도윤"), entry("유하나"))
        val (vsExisting, within) = BulkRegisterPlanner.countCollisions(entries, setOf("강도윤"))
        assertEquals(1, vsExisting)
        assertEquals(1, within)
    }

    @Test
    fun countCollisions_overlapCountedOnce_sumMatchesSkipTotal() {
        // 기존명과도 겹치고 선택 내에서도 반복되는 이름 — 엔트리당 한쪽에만 집계되어
        // 두 수치의 합이 SKIP 정책의 실제 건너뜀 건수와 일치해야 한다 (고지·실행 정합)
        val entries = listOf(entry("강도윤"), entry("강도윤"))
        val (vsExisting, within) = BulkRegisterPlanner.countCollisions(entries, setOf("강도윤"))
        assertEquals(1, vsExisting)
        assertEquals(1, within)

        val plan = BulkRegisterPlanner.plan(
            entries, existingNames = setOf("강도윤"), options(policy = DuplicatePolicy.SKIP_DUPLICATES)
        )
        assertEquals(vsExisting + within, plan.skippedDuplicates)
        assertTrue(plan.toCreate.isEmpty())
    }

    @Test
    fun trimmedNames_matchForDuplicates() {
        val plan = BulkRegisterPlanner.plan(
            listOf(entry(" 한서린 ")), existingNames = setOf("한서린"),
            options(policy = DuplicatePolicy.SKIP_DUPLICATES)
        )
        assertTrue(plan.toCreate.isEmpty())
        assertEquals(1, plan.skippedDuplicates)
    }
}
