package com.novelcharacter.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 월드패키지 이미지 엔트리의 단일 소스 (B-234).
 *
 * **이 시험이 잠그는 것은 둘이다:** ① 내보내기와 가져오기가 *같은 이름*을 짓는가
 * ② **잘린 장이 결번과 같이 다뤄지는가** — 잘린 장을 "있다"로 보면 받는 기기가
 * 내용이 깨진 그림을 멀쩡한 것으로 복원한다(이 행이 고친 그 모양).
 */
class WorldPackageImagesTest {

    @Test
    fun `엔트리 이름 - images 접두사는 이 자리에서 한 번만 붙는다`() {
        assertEquals("images/3_0.jpg", WorldPackageImages.entryName("3_", 0))
        assertEquals("images/universe_2.jpg", WorldPackageImages.entryName("universe_", 2))
        assertEquals("images/novel_5_1.jpg", WorldPackageImages.entryName("novel_5_", 1))
    }

    @Test
    fun `엔트리 이름 - 접두사에 images를 미리 붙여 부르면 두 번 붙는다(규약이 하나라는 증명)`() {
        // 종전의 내보내기 규약(`"images/3_"`)을 그대로 넘기면 이름이 어긋난다 —
        // 두 쪽이 *엔티티 접두사만* 들고 다녀야 한다는 계약을 이 자리가 잠근다.
        assertEquals("images/images/3_0.jpg", WorldPackageImages.entryName("images/3_", 0))
    }

    @Test
    fun `복원 판정 - 엔트리가 있고 잘리지 않았으면 복원한다`() {
        assertTrue(WorldPackageImages.isRestorable("images/3_0.jpg", true, emptySet()))
    }

    @Test
    fun `복원 판정 - 엔트리가 없으면 복원하지 않는다(결번)`() {
        assertFalse(WorldPackageImages.isRestorable("images/3_0.jpg", false, emptySet()))
    }

    @Test
    fun `복원 판정 - 잘린 장은 엔트리가 있어도 없는 것으로 본다`() {
        val truncated = setOf("images/3_1.jpg")
        assertFalse(WorldPackageImages.isRestorable("images/3_1.jpg", true, truncated))
        // 같은 엔티티의 다른 장은 그대로 산다 — 한 장의 실패가 이웃을 끌고 가지 않는다.
        assertTrue(WorldPackageImages.isRestorable("images/3_0.jpg", true, truncated))
        assertTrue(WorldPackageImages.isRestorable("images/3_2.jpg", true, truncated))
    }

    @Test
    fun `복원 판정 - 이름이 겹치는 다른 엔티티를 함께 끌고 가지 않는다`() {
        // `3_1`과 `13_1`은 접두사가 겹쳐 보이지만 다른 캐릭터다 — 집합 대조라 부분 일치가 없다.
        val truncated = setOf("images/3_1.jpg")
        assertTrue(WorldPackageImages.isRestorable("images/13_1.jpg", true, truncated))
        assertTrue(WorldPackageImages.isRestorable("images/novel_3_1.jpg", true, truncated))
    }

    @Test
    fun `목록 읽기 - null과 공란은 버린다`() {
        val set = WorldPackageImages.truncatedSet(
            listOf("images/3_0.jpg", null, "", "   ", "images/universe_1.jpg")
        )
        assertEquals(setOf("images/3_0.jpg", "images/universe_1.jpg"), set)
    }

    @Test
    fun `목록 읽기 - 엔트리가 없으면 빈 집합이고, 그때 동작은 종전과 같다`() {
        assertEquals(emptySet<String>(), WorldPackageImages.truncatedSet(null))
        assertEquals(emptySet<String>(), WorldPackageImages.truncatedSet(emptyList()))
        assertTrue(WorldPackageImages.isRestorable("images/3_0.jpg", true, emptySet()))
    }

    @Test
    fun `목록 읽기 - 앞뒤 공백은 다듬는다(손편집 파일)`() {
        val set = WorldPackageImages.truncatedSet(listOf(" images/3_0.jpg "))
        assertEquals(setOf("images/3_0.jpg"), set)
        assertFalse(WorldPackageImages.isRestorable("images/3_0.jpg", true, set))
    }
}
