package com.novelcharacter.app.util

import com.novelcharacter.app.util.ImageLinkResolver.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 링크 그룹 확장/병합 판정 계약 — 배정의 그룹 단위 확장(사용자 명시 요구)의 단일 소스. */
class ImageLinkResolverTest {

    private val metas = listOf(
        Meta("a1", "G1"), Meta("a2", "G1"), Meta("a3", "G1"),   // 그룹1: 3장
        Meta("b1", "G2"), Meta("b2", "G2"),                     // 그룹2: 2장
        Meta("solo", null),                                     // 미링크 라이브러리
    )

    @Test fun expand_noLinks_passesThrough() {
        val e = ImageLinkResolver.expand(listOf("solo", "unknown_path"), metas)
        assertEquals(linkedSetOf("solo", "unknown_path"), e.allPaths)
        assertTrue(e.addedByLink.isEmpty())
        assertTrue(e.groupIds.isEmpty())
    }

    @Test fun expand_singleGroupMember_pullsWholeGroup() {
        val e = ImageLinkResolver.expand(listOf("a1"), metas)
        assertEquals(setOf("a1", "a2", "a3"), e.allPaths)
        assertEquals(setOf("a2", "a3"), e.addedByLink)   // 선택 밖 확장분 구분 — 사전 확인용
        assertEquals(setOf("G1"), e.groupIds)
    }

    @Test fun expand_selectionSpanningGroups_pullsBoth() {
        val e = ImageLinkResolver.expand(listOf("a1", "b2", "solo"), metas)
        assertEquals(setOf("a1", "b2", "solo", "a2", "a3", "b1"), e.allPaths)
        assertEquals(setOf("a2", "a3", "b1"), e.addedByLink)
        assertEquals(setOf("G1", "G2"), e.groupIds)
    }

    @Test fun expand_wholeGroupSelected_noAdded() {
        val e = ImageLinkResolver.expand(listOf("b1", "b2"), metas)
        assertEquals(setOf("b1", "b2"), e.allPaths)
        assertTrue(e.addedByLink.isEmpty())
    }

    @Test fun planLink_freshSelection_noMerge() {
        val p = ImageLinkResolver.planLink(listOf("solo", "unknown"), metas)
        assertFalse(p.needsMergeConfirm)
        assertTrue(p.groupsInvolved.isEmpty())
    }

    @Test fun planLink_extendingOneGroup_noMerge() {
        // 기존 그룹 하나에 새 이미지를 더하는 것은 병합이 아니다
        val p = ImageLinkResolver.planLink(listOf("a1", "solo"), metas)
        assertFalse(p.needsMergeConfirm)
        assertEquals(setOf("G1"), p.groupsInvolved)
    }

    @Test fun planLink_spanningTwoGroups_needsMerge() {
        val p = ImageLinkResolver.planLink(listOf("a1", "b1"), metas)
        assertTrue(p.needsMergeConfirm)
        assertEquals(setOf("G1", "G2"), p.groupsInvolved)
    }
}
