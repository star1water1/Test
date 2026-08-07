package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 전역 기본 필드의 엑셀 왕복 (B-119 — 설계 1-5).
 *
 * **이 시험이 지키는 가장 큰 것은 R-36이다.** `필드 정의` 시트에 `기본필드코드`를 더하는 순간
 * **그때까지 내보낸 모든 파일**이 *"그 열이 없는 파일"*이 된다. 없는 열을 빈 칸("지워라")으로
 * 읽으면 **다시 들이는 것만으로 전 세계관의 연결이 지워진다** — 개발 의도 4번(엑셀 왕복
 * 무결성)이 정면으로 깨지는 자리이며, B-122가 `프로필필드`에서 겪은 그 함정의 재발이다.
 */
class DefaultFieldSheetTest {

    private fun headers(spec: SheetSpec) = spec.columns.map { it.header }

    // ──────────────────────────────────────────────────────────────────────
    // 시트 자체
    // ──────────────────────────────────────────────────────────────────────

    @Test fun 기본_필드_시트는_예약명이다() {
        assertTrue(
            "예약명이 아니면 '기본 필드'라는 이름의 세계관이 이 시트 자리를 빼앗는다",
            defaultFieldSpec().sheetName in RESERVED_SHEET_NAMES
        )
    }

    /**
     * 첫 열이 '이름'이면 `findSheetForUniverse`의 캐릭터 시트 지문에 걸린다 —
     * '필드 정의'·'등급 체계' 시트와 같은 두 겹 방어다.
     */
    @Test fun 첫_열이_이름이_아니다() {
        assertEquals("필드키", headers(defaultFieldSpec()).first())
    }

    /** 전역이라 소속이 없다 — 세계관 열이 있으면 무엇을 적어도 뜻이 없는 칸이 된다(설계 1-5). */
    @Test fun 세계관_열이_없다() {
        val h = headers(defaultFieldSpec())
        assertFalse("세계관", "세계관" in h)
        assertFalse("세계관코드", "세계관코드" in h)
    }

    /** `필드 정의` 시트와 **같은 열에서 세계관을 뺀 것** + 코드다. 새 어휘를 만들지 않는다. */
    @Test fun 필드_정의_시트와_같은_어휘를_쓴다() {
        val defaults = headers(defaultFieldSpec()).toSet()
        val fieldDef = headers(fieldDefinitionSpec(emptyList())).toSet()
        val shared = listOf(
            "필드키", "필드명", "타입", "설정(JSON)", "그룹", "순서", "필수여부", "대상",
            FieldConfigColumns.COLUMN_AI_SUGGEST, FieldConfigColumns.COLUMN_DESCRIPTION
        )
        for (header in shared) {
            assertTrue("'$header'가 기본 필드 시트에 없다", header in defaults)
            assertTrue("'$header'가 필드 정의 시트에 없다", header in fieldDef)
        }
        assertTrue("템플릿의 정체는 코드다(R-1)", "코드" in defaults)
    }

    @Test fun 필드_정의_시트가_기본필드코드_열을_갖는다() {
        assertTrue("기본필드코드" in headers(fieldDefinitionSpec(emptyList())))
    }

    /** 열을 뒤에 더한다 — 앞에 끼우면 위치 폴백을 쓰는 기존 파일의 해석이 밀린다. */
    @Test fun 기본필드코드는_필드_정의_시트의_마지막_열이다() {
        assertEquals("기본필드코드", headers(fieldDefinitionSpec(emptyList())).last())
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-36 — 없는 열과 빈 칸은 다른 사실이다
    // ──────────────────────────────────────────────────────────────────────

    /**
     * **이 판의 방어선.** 열이 없으면 KEEP이어야 한다 — B-119 이전에 내보낸 모든 파일이
     * 이 경우이고, CLEAR로 읽으면 다시 들이는 것만으로 연결이 전부 지워진다.
     */
    @Test fun 열이_없으면_기존_연결을_지킨다() {
        assertEquals(
            RefIntent.KEEP,
            refColumnIntent(nameColumnPresent = false, codeColumnPresent = false, nameCell = "", codeCell = "")
        )
    }

    /** 열이 있는데 칸이 비면 **해제 의도**다 — 사용자가 명시적으로 비운 칸을 무음 폐기하지 않는다. */
    @Test fun 열이_있고_칸이_비면_해제다() {
        assertEquals(
            RefIntent.CLEAR,
            refColumnIntent(nameColumnPresent = false, codeColumnPresent = true, nameCell = "", codeCell = "")
        )
    }

    @Test fun 열이_있고_코드가_있으면_조회한다() {
        assertEquals(
            RefIntent.LOOKUP,
            refColumnIntent(nameColumnPresent = false, codeColumnPresent = true, nameCell = "", codeCell = "DFT-1")
        )
    }

    /**
     * 공백만 든 칸은 **빈 칸과 같다.** 엑셀에서 지운 자리에 공백이 남는 일이 흔한데, 그것을
     * 코드로 읽으면 조회가 실패해 "찾지 못했다"는 경고가 뜨고 강등된다 — 결과는 같아도
     * 사용자에게는 자기가 하지 않은 오류로 보인다.
     */
    @Test fun 공백만_든_칸은_해제다() {
        assertEquals(
            RefIntent.CLEAR,
            refColumnIntent(nameColumnPresent = false, codeColumnPresent = true, nameCell = "", codeCell = "   ")
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 내보내기 계획
    // ──────────────────────────────────────────────────────────────────────

    /**
     * **가져오기 순서가 이 순서를 따른다** — `필드 정의`의 `기본필드코드`가 이 시트의 템플릿을
     * 찾아야 한다. 뒤에 두면 신규 기기 복원(빈 DB)에서 같은 파일 안에 템플릿이 실려 있는데도
     * 연결이 전부 강등된다(등급 체계가 필드 정의보다 앞인 것과 같은 근거).
     */
    @Test fun 기본_필드_시트가_필드_정의보다_먼저_나간다() {
        val plan = ExportSheetStep.of(ExportOptions())
        assertTrue(
            plan.indexOf(ExportSheetStep.DEFAULT_FIELDS) < plan.indexOf(ExportSheetStep.FIELD_DEFINITIONS)
        )
    }

    @Test fun 필드_정의를_끄면_기본_필드도_빠진다() {
        assertFalse(
            ExportSheetStep.DEFAULT_FIELDS in ExportSheetStep.of(ExportOptions(fieldDefinitions = false))
        )
    }

    /** 셀 한도는 단일 소스를 쓴다 — 설정(JSON)을 통째로 싣는 열이 여기에도 있다. */
    @Test fun 설정_JSON_열이_셀_한도_규약_아래_있다() {
        assertTrue("설정(JSON)" in headers(defaultFieldSpec()))
        assertEquals(32767, EXCEL_CELL_TEXT_LIMIT)
    }
}
