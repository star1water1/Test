package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CardImageAdoption] — 세계관·작품 카드에 그림이 처음 붙었을 때의 표시 방식 승격.
 *
 * 인앱 이미지 배정이 이미 하던 판정이고, 엑셀 가져오기만 그것을 몰라 **같은 일이 경로에 따라
 * 다른 답을 냈다**(실측 2026.08.24 사용자 파일: 세계관 8·작품 15가 전부 `custom`이 아니었다).
 * 아래는 그 규칙이 **한 방향으로만 움직인다**는 계약을 고정한다.
 */
class CardImageAdoptionTest {

    @Test
    fun `그림이 없던 none 카드에 그림이 붙으면 custom으로 올린다`() {
        assertEquals(
            Universe.IMAGE_MODE_CUSTOM,
            CardImageAdoption.adoptedModeOrNull(
                Universe.IMAGE_MODE_NONE, hadImages = false, hasImages = true
            )
        )
    }

    @Test
    fun `none인데 그림이 이미 있던 카드는 그대로 둔다`() {
        // 사용자가 일부러 안 보이게 둔 카드다 — 파일 한 번에 켜지지 않는다.
        assertNull(
            CardImageAdoption.adoptedModeOrNull(
                Universe.IMAGE_MODE_NONE, hadImages = true, hasImages = true
            )
        )
    }

    @Test
    fun `연동 모드는 덮지 않는다`() {
        for (mode in listOf(
            Universe.IMAGE_MODE_RANDOM_CHARACTER,
            Universe.IMAGE_MODE_SELECT_CHARACTER,
            Universe.IMAGE_MODE_RANDOM_NOVEL,
            Universe.IMAGE_MODE_SELECT_NOVEL,
            Universe.IMAGE_MODE_CUSTOM
        )) {
            assertNull(
                "그림의 출처가 따로 있는 모드는 그대로 둔다: $mode",
                CardImageAdoption.adoptedModeOrNull(mode, hadImages = false, hasImages = true)
            )
        }
    }

    @Test
    fun `그림이 붙지 않으면 아무것도 하지 않는다`() {
        assertNull(
            CardImageAdoption.adoptedModeOrNull(
                Universe.IMAGE_MODE_NONE, hadImages = false, hasImages = false
            )
        )
        // 그림을 **지우는** 편집도 승격 대상이 아니다.
        assertNull(
            CardImageAdoption.adoptedModeOrNull(
                Universe.IMAGE_MODE_NONE, hadImages = true, hasImages = false
            )
        )
    }

    @Test
    fun `모드를 모르는 카드는 건드리지 않는다`() {
        assertNull(CardImageAdoption.adoptedModeOrNull(null, hadImages = false, hasImages = true))
        assertNull(CardImageAdoption.adoptedModeOrNull("", hadImages = false, hasImages = true))
        assertNull(CardImageAdoption.adoptedModeOrNull("이상한값", hadImages = false, hasImages = true))
    }

    @Test
    fun `세계관과 작품의 모드 상수가 같은 글자다`() {
        // 갈리는 순간 위 판정이 한쪽에만 맞는 답을 내는데, 그 실패는 조용하다.
        assertTrue(CardImageAdoption.modesAgree())
        assertEquals(Universe.IMAGE_MODE_NONE, Novel.IMAGE_MODE_NONE)
        assertEquals(Universe.IMAGE_MODE_CUSTOM, Novel.IMAGE_MODE_CUSTOM)
    }
}
