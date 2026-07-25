package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐릭터 시트 열 정체성 — 커스텀 필드명이 고정 열(또는 그 별칭)과 겹쳐도
 * 내보내기/가져오기가 같은 규칙으로 열을 확정하는지.
 */
class CharacterHeaderIdentityTest {

    @Test
    fun aliasFolding_isSharedWithImporter() {
        // '비고'는 별칭상 '메모'로 접힌다 — 이름이 달라도 고정 열 충돌이다
        assertEquals("메모", ExcelHeaderAliases.canonical("비고"))
        assertEquals("메모", ExcelHeaderAliases.canonical("note"))
        assertEquals("태그", ExcelHeaderAliases.canonical("tags"))
        assertEquals("이름", ExcelHeaderAliases.canonical("캐릭터명"))
        // 등록되지 않은 헤더는 원본 유지
        assertEquals("머리카락색", ExcelHeaderAliases.canonical("머리카락색"))
    }

    @Test
    fun fixedHeaderCollision_detectedIncludingAliases() {
        assertTrue(collidesWithFixedHeader("메모"))
        assertTrue(collidesWithFixedHeader("비고"))   // 별칭 경유 충돌
        assertTrue(collidesWithFixedHeader("태그"))
        assertTrue(collidesWithFixedHeader("생성일")) // 상수에 누락돼 있던 고정 열
        assertFalse(collidesWithFixedHeader("머리카락색"))
        assertFalse(collidesWithFixedHeader("출신지"))
    }

    @Test
    fun header_disambiguatesWithFieldKey() {
        assertEquals("머리카락색", characterFieldHeader("머리카락색", "hair", disambiguate = false))
        assertEquals("메모(user_memo)", characterFieldHeader("메모", "user_memo", disambiguate = true))
    }

    @Test
    fun normalization_ignoresSpacingAndParens() {
        // 정규화는 공백·밑줄·하이픈·괄호를 제거한다 (전각 괄호 포함)
        assertEquals("이름(First)", ExcelHeaderAliases.canonical("first_name"))
        assertEquals("이미지경로", ExcelHeaderAliases.canonical("이미지 경로"))
        assertEquals("파일명", ExcelHeaderAliases.canonical("파일 명"))
    }

    @Test
    fun aliasMap_neverMapsTwoCanonicalsToSameToken() {
        // 별칭 등록은 선점 우선 — 한 토큰이 두 표준명을 가리키면 열 해석이 갈린다
        val byToken = HashMap<String, String>()
        for ((token, canonical) in ExcelHeaderAliases.map) {
            val prev = byToken.put(token, canonical)
            if (prev != null) {
                throw AssertionError("토큰 '$token'이 '$prev'와 '$canonical' 두 표준명에 매핑됨")
            }
        }
        assertTrue(byToken.isNotEmpty())
    }
}
