package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 백업·내보내기 동선 재편(설계 `backup_export_redesign_2026-08.md`)의 순수 계층 검증.
 *
 * 여기서 지키는 계약은 셋이다:
 * - **시트 계획이 실행과 진행도의 단일 소스인가**(D5) — 갈리면 막대가 100%를 넘거나
 *   못 미친 채 끝난다.
 * - **'완전한 백업' 판정이 사실을 말하는가**(D1) — 하나라도 빠졌는데 완전하다고 말하면
 *   그 고지가 손실 고지보다 나쁘다.
 * - **공간 부족을 실제 예외 모양에서 알아보는가**(D7) — 못 알아보면 R-17이 요구한
 *   갈래가 일반 실패로 뭉개진다.
 */
class ExportPlanAndSpaceTest {

    // ── D5: 시트 계획 ──

    @Test
    fun `기본 옵션은 사용 안내를 포함한 전 시트를 순서대로 든다`() {
        val plan = ExportSheetStep.of(ExportOptions())
        // '사용 안내'는 선택 대상이 아니라 늘 첫 번째다
        assertEquals(ExportSheetStep.INSTRUCTIONS, plan.first())
        // 열거형 전체가 기본 옵션에서 한 번씩 나온다 — 빠진 단계가 있으면 여기서 걸린다
        assertEquals(ExportSheetStep.entries.toList(), plan)
    }

    @Test
    fun `이미지는 시트가 아니라 계획을 늘리지 않는다`() {
        // ZIP 래핑은 시트 순회가 아니므로 onImages가 따로 보고한다
        assertEquals(
            ExportSheetStep.of(ExportOptions(images = false)),
            ExportSheetStep.of(ExportOptions(images = true))
        )
    }

    @Test
    fun `필드 정의를 끄면 등급 체계와 값 라이브러리까지 함께 빠진다`() {
        val plan = ExportSheetStep.of(ExportOptions(fieldDefinitions = false))
        assertFalse(ExportSheetStep.GRADE_SYSTEMS in plan)
        assertFalse(ExportSheetStep.FIELD_DEFINITIONS in plan)
        assertFalse(ExportSheetStep.FIELD_VALUE_LIBRARY in plan)
        assertEquals(ExportSheetStep.entries.size - 3, plan.size)
    }

    @Test
    fun `등급 체계는 필드 정의보다 먼저 온다`() {
        // 파일을 여는 사람이 참조 대상을 먼저 보게 하는 순서 규약
        val plan = ExportSheetStep.of(ExportOptions())
        assertTrue(plan.indexOf(ExportSheetStep.GRADE_SYSTEMS) < plan.indexOf(ExportSheetStep.FIELD_DEFINITIONS))
    }

    @Test
    fun `이미지 메타 시트는 캐릭터 시트보다 먼저 온다`() {
        // 세계관 이름이 "이미지"여도 예약 시트가 원명을 선점해야 가져오기에서 충돌하지 않는다
        val plan = ExportSheetStep.of(ExportOptions())
        assertTrue(plan.indexOf(ExportSheetStep.IMAGE_META) < plan.indexOf(ExportSheetStep.CHARACTERS))
    }

    @Test
    fun `모두 끄면 사용 안내만 남는다`() {
        val nothing = ExportOptions(
            universes = false, novels = false, characters = false, fieldDefinitions = false,
            timeline = false, stateChanges = false, relationships = false, relationshipChanges = false,
            nameBank = false, factions = false, factionMemberships = false, factionRelationships = false,
            presetTemplates = false, searchPresets = false, characterListPresets = false,
            appSettings = false, imageMeta = false, images = false
        )
        assertEquals(listOf(ExportSheetStep.INSTRUCTIONS), ExportSheetStep.of(nothing))
    }

    // ── D1: '완전한 백업' 판정 ──

    @Test
    fun `ALL_WITH_IMAGES만 완전한 백업이다`() {
        assertTrue(ExportOptions.ALL_WITH_IMAGES.isCompleteBackup)
        // 기본값은 이미지가 꺼져 있어 완전하지 않다 — 이것이 반쪽 백업의 출처였다
        assertFalse(ExportOptions.ALL.isCompleteBackup)
        assertFalse(ExportOptions(images = true, characters = false).isCompleteBackup)
        assertFalse(ExportOptions(images = true, imageMeta = false).isCompleteBackup)
    }

    @Test
    fun `가져오기 전용 선택은 완전성 판정에 끼어들지 않는다`() {
        // deleteOptions는 내보내기와 무관하다 — ==로 비교했다면 여기서 틀렸다
        val withDeletes = ExportOptions.ALL_WITH_IMAGES.copy(
            deleteOptions = DeleteOptions(characters = true)
        )
        assertTrue(withDeletes.isCompleteBackup)
    }

    // ── D7: 공간 부족 판별 ──

    @Test
    fun `안드로이드가 싣는 실제 메시지 형태를 알아본다`() {
        assertTrue(ExportSpace.isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(ExportSpace.isOutOfSpace(IOException("No space left on device")))
        assertTrue(ExportSpace.isOutOfSpace(IOException("enospc")))
    }

    @Test
    fun `원인 사슬 안쪽에 있어도 알아본다`() {
        // POI·암호화가 감싸 던지는 경우가 그렇다 — 겉만 보면 놓친다
        val wrapped = RuntimeException("export failed", IOException("write failed: ENOSPC"))
        assertTrue(ExportSpace.isOutOfSpace(wrapped))
    }

    @Test
    fun `공간과 무관한 실패를 공간 부족이라 말하지 않는다`() {
        assertFalse(ExportSpace.isOutOfSpace(IOException("permission denied")))
        assertFalse(ExportSpace.isOutOfSpace(IllegalStateException("no space in the sheet name")))
        assertFalse(ExportSpace.isOutOfSpace(null))
        assertFalse(ExportSpace.isOutOfSpace(IOException(null as String?)))
    }

    @Test
    fun `사슬이 자기 자신을 가리켜도 멈춘다`() {
        // 상한이 없으면 무한 순회로 앱이 멈춘다
        class SelfCause : Exception("loop") {
            override val cause: Throwable get() = this
        }
        assertFalse(ExportSpace.isOutOfSpace(SelfCause()))
    }

    @Test
    fun `필요 용량은 올림이고 최소 1MB다`() {
        // "약 0MB가 필요합니다"는 안내가 아니다
        assertEquals(1, ExportSpace.requiredMegabytes(0L))
        assertEquals(1, ExportSpace.requiredMegabytes(-5L))
        assertEquals(1, ExportSpace.requiredMegabytes(1L))
        assertEquals(1, ExportSpace.requiredMegabytes(1_048_576L))
        assertEquals(2, ExportSpace.requiredMegabytes(1_048_577L))
        assertEquals(1000, ExportSpace.requiredMegabytes(1_048_576_000L))
    }
}
