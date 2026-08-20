package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 링크 묶음 접기·표본·전개 계약 — 묶어 보기(그리드·피커 공용)와 AI 묶음 단위 전송의 단일 소스. */
class LinkGroupFoldTest {

    private val groupOf = mapOf(
        "a1" to "G1", "a2" to "G1", "a3" to "G1",
        "b1" to "G2", "b2" to "G2",
    )

    private fun g(p: String): String? = groupOf[p]

    // ===== fold =====

    @Test fun fold_collapsesGroupToFirstMemberPosition() {
        val folded = LinkGroupFold.fold(listOf("solo1", "a2", "b1", "a1", "solo2", "b2"), ::g)
        // 그룹 칸은 첫 등장 자리 · 대표는 그 자리의 항목이다(현재 정렬이 대표를 정한다)
        assertEquals(listOf("solo1", "a2", "b1", "solo2"), folded.map { it.representative })
        assertEquals(listOf("a2", "a1"), folded[1].members)
        assertEquals(listOf("b1", "b2"), folded[2].members)
        assertEquals(2, folded[1].size) // G1: 화면에 보이는 2장만 접힌다(a3은 입력에 없다)
    }

    @Test fun fold_unlinkedItemsPassThroughOnePerCell() {
        val folded = LinkGroupFold.fold(listOf("solo1", "solo2"), ::g)
        assertEquals(2, folded.size)
        assertTrue(folded.all { it.size == 1 })
    }

    @Test fun fold_singleVisibleMemberStillOneCell() {
        // 필터가 그룹의 나머지를 가렸으면 보이는 1장만으로 한 칸 — 개수도 보이는 수다
        val folded = LinkGroupFold.fold(listOf("a1", "solo1"), ::g)
        assertEquals(listOf("a1", "solo1"), folded.map { it.representative })
        assertEquals(1, folded[0].size)
    }

    @Test fun fold_emptyList() {
        assertTrue(LinkGroupFold.fold(emptyList<String>(), ::g).isEmpty())
    }

    // ===== sampleForAi =====

    @Test fun sample_takesFirstNPerGroupAndKeepsUnlinked() {
        val plan = LinkGroupFold.sampleForAi(
            listOf("a1", "a2", "a3", "solo", "b1", "b2"), ::g, perGroup = 1
        )
        assertEquals(listOf("a1", "solo", "b1"), plan.sendPaths)
        assertEquals(listOf("a1", "a2", "a3"), plan.membersBySentPath["a1"])
        assertEquals(listOf("b1", "b2"), plan.membersBySentPath["b1"])
        // 미링크는 전개 표에 오르지 않는다 — 그 장의 태그는 그 장에만 붙는다
        assertTrue("solo" !in plan.membersBySentPath)
        assertEquals(2, plan.sampledGroups)
        assertEquals(5, plan.expandedTotal)
    }

    @Test fun sample_perGroupTwo_sendsTwoSamples() {
        val plan = LinkGroupFold.sampleForAi(listOf("a1", "a2", "a3"), ::g, perGroup = 2)
        assertEquals(listOf("a1", "a2"), plan.sendPaths)
        // 표본 둘 다 같은 전원을 가리킨다 — 어느 쪽의 태그든 전원에 붙을 수 있어야 한다
        assertEquals(plan.membersBySentPath["a1"], plan.membersBySentPath["a2"])
    }

    @Test fun sample_groupWithSingleVisibleMemberIsNotAGroup() {
        // 보이는 식구가 1장이면 묶음이 아니다 — 표본도 전개도 없이 그대로 실린다
        val plan = LinkGroupFold.sampleForAi(listOf("a1", "solo"), ::g, perGroup = 1)
        assertEquals(listOf("a1", "solo"), plan.sendPaths)
        assertTrue(plan.membersBySentPath.isEmpty())
        assertEquals(0, plan.sampledGroups)
    }

    @Test fun sample_perGroupLargerThanGroup_sendsWholeGroup() {
        val plan = LinkGroupFold.sampleForAi(listOf("b1", "b2"), ::g, perGroup = 5)
        assertEquals(listOf("b1", "b2"), plan.sendPaths)
        assertEquals(1, plan.sampledGroups)
    }

    // ===== expandPicked =====

    @Test fun expand_spreadsTagsToAllMembers() {
        val out = LinkGroupFold.expandPicked(
            picked = mapOf("a1" to listOf("은발", "갑옷"), "solo" to listOf("배경")),
            membersBySentPath = mapOf("a1" to listOf("a1", "a2", "a3"))
        )
        assertEquals(listOf("은발", "갑옷"), out["a2"])
        assertEquals(listOf("은발", "갑옷"), out["a3"])
        assertEquals(listOf("배경"), out["solo"])   // 표에 없는 경로는 그대로 통과
    }

    @Test fun expand_twoSamplesOfSameGroup_unionPreservesOrder() {
        val out = LinkGroupFold.expandPicked(
            picked = mapOf("a1" to listOf("은발", "실내"), "a2" to listOf("갑옷", "은발")),
            membersBySentPath = mapOf(
                "a1" to listOf("a1", "a2", "a3"),
                "a2" to listOf("a1", "a2", "a3")
            )
        )
        assertEquals(listOf("은발", "실내", "갑옷"), out["a3"])
    }

    @Test fun expand_emptyPicked() {
        assertTrue(LinkGroupFold.expandPicked(emptyMap(), emptyMap()).isEmpty())
    }
}
