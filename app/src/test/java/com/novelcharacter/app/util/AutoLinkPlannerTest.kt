package com.novelcharacter.app.util

import com.novelcharacter.app.util.AutoLinkPlanner.ImageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐릭터 자동 링크 계획 계약 — "한 캐릭터에 등록된 이미지끼리 자동 링크, 빼면 해제"의 단일 소스.
 * 수동 그룹 불가침·낡은 토큰 치유(가져오기 id 재발급)·전체 재동기화 멱등이 계약의 핵심이다.
 */
class AutoLinkPlannerTest {

    // ── 토큰 형태 ──

    @Test fun token_shape_distinguishesAutoFromManualUuid() {
        assertEquals("char:12", AutoLinkPlanner.autoToken(12))
        assertTrue(AutoLinkPlanner.isAutoToken("char:12"))
        assertFalse(AutoLinkPlanner.isAutoToken("f2b4c6d8-1234-5678-9abc-def012345678"))
        assertFalse(AutoLinkPlanner.isAutoToken(null))
        assertEquals(12L, AutoLinkPlanner.characterIdOf("char:12"))
        assertNull(AutoLinkPlanner.characterIdOf("char:abc"))
        assertNull(AutoLinkPlanner.characterIdOf("G1"))
    }

    // ── 링크 생성 ──

    @Test fun plan_twoUnlinkedImages_formOneGroup() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(1L to listOf("a", "b")),
            states = emptyList()   // 라이브러리 밖 이미지 = 미링크
        )
        assertEquals(mapOf("char:1" to listOf("a", "b")), plan.claims)
        assertTrue(plan.releases.isEmpty())
        assertTrue("char:1" in plan.dirtyAutoTokens)
    }

    @Test fun plan_singleImage_noGroup() {
        val plan = AutoLinkPlanner.plan(listOf(1L to listOf("a")), emptyList())
        assertTrue(plan.isEmpty)
    }

    @Test fun plan_newImageJoinsExistingAutoGroup_writesOnlyDiff() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(1L to listOf("a", "b", "c")),
            states = listOf(ImageState("a", "char:1"), ImageState("b", "char:1"))
        )
        assertEquals(mapOf("char:1" to listOf("c")), plan.claims)   // 기존 멤버 재쓰기 없음
        assertTrue(plan.releases.isEmpty())
    }

    @Test fun plan_steadyState_isEmpty() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(1L to listOf("a", "b")),
            states = listOf(ImageState("a", "char:1"), ImageState("b", "char:1"))
        )
        assertTrue(plan.isEmpty)   // 멱등 — 재실행이 쓰기를 만들지 않는다
    }

    // ── 수동 그룹 불가침 ──

    @Test fun plan_manualGroupMembers_neverTouched() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(1L to listOf("a", "b", "c")),
            states = listOf(ImageState("a", "G1"), ImageState("b", "G1"))
        )
        // a·b는 수동 그룹 — 옮기지도 풀지도 않는다. c 혼자로는 그룹 불성립.
        assertTrue(plan.claims.isEmpty())
        assertTrue(plan.releases.isEmpty())
    }

    @Test fun plan_manualMemberSkipped_restStillGroup() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(1L to listOf("a", "b", "c")),
            states = listOf(ImageState("a", "G1"))
        )
        assertEquals(mapOf("char:1" to listOf("b", "c")), plan.claims)
    }

    // ── 캐릭터에서 빼면 해제 ──

    @Test fun plan_removedImage_released() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(1L to listOf("a", "b")),
            states = listOf(
                ImageState("a", "char:1"), ImageState("b", "char:1"), ImageState("x", "char:1")
            )
        )
        assertEquals(listOf("x"), plan.releases)
        assertTrue(plan.claims.isEmpty())
        assertTrue("char:1" in plan.dirtyAutoTokens)
    }

    @Test fun plan_removalLeavesSingleMember_marksTokenForSingletonCleanup() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(1L to listOf("a")),
            states = listOf(ImageState("a", "char:1"), ImageState("x", "char:1"))
        )
        assertEquals(listOf("x"), plan.releases)
        // 남은 a는 1장 — 잔존 배지 방지를 위해 singleton 정리 대상으로 예약된다
        assertTrue("char:1" in plan.dirtyAutoTokens)
    }

    @Test fun plan_characterGone_membershipReleased() {
        val plan = AutoLinkPlanner.plan(
            characters = emptyList(),
            states = listOf(ImageState("a", "char:5"), ImageState("b", "char:5"))
        )
        assertEquals(setOf("a", "b"), plan.releases.toSet())
        assertTrue(plan.claims.isEmpty())
    }

    // ── 낡은 토큰 치유 (가져오기·복원의 id 재발급) ──

    @Test fun plan_staleTokenFromReissuedId_reclaimedToCurrentId() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(7L to listOf("a", "b")),
            states = listOf(ImageState("a", "char:99"), ImageState("b", "char:99"))
        )
        assertEquals(mapOf("char:7" to listOf("a", "b")), plan.claims)
        assertTrue(plan.releases.isEmpty())   // 재귀속이지 해제가 아니다
        assertTrue("char:99" in plan.dirtyAutoTokens)
        assertTrue("char:7" in plan.dirtyAutoTokens)
    }

    // ── 여러 캐릭터가 같은 이미지를 공유할 때 ──

    @Test fun plan_validMembershipIsStable_notStolenByOtherRegistrant() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(3L to listOf("a", "z"), 7L to listOf("a", "b")),
            states = listOf(ImageState("a", "char:7"), ImageState("b", "char:7"))
        )
        // a는 char:7에 유효하게 묶여 있다 — id가 더 작은 3이 등록해도 빼앗지 않는다.
        // 3은 z 혼자 남아 그룹 불성립.
        assertTrue(plan.claims.isEmpty())
        assertTrue(plan.releases.isEmpty())
    }

    @Test fun plan_unformableGroupDoesNotConsumeSharedImage() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(3L to listOf("x"), 9L to listOf("x", "y")),
            states = emptyList()
        )
        // 캐릭터 3(1장)은 그룹 불성립 — x가 소진되지 않고 9의 그룹으로 묶인다
        assertEquals(mapOf("char:9" to listOf("x", "y")), plan.claims)
    }

    @Test fun plan_newClaimGoesToLowestCharacterId_deterministic() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(5L to listOf("a", "c"), 3L to listOf("a", "b")),
            states = emptyList()
        )
        // a는 3·5 모두의 후보 — id 오름차순으로 3이 먼저 묶는다. 5는 c 혼자 남아 불성립.
        assertEquals(mapOf("char:3" to listOf("a", "b")), plan.claims)
    }

    // ── 종합: 재동기화 한 번으로 수렴 ──

    @Test fun plan_mixedDrift_convergesInOnePass() {
        val plan = AutoLinkPlanner.plan(
            characters = listOf(
                1L to listOf("a", "b", "new"),       // new가 합류해야 함
                2L to listOf("m1", "m2")             // 수동 그룹 — 손대지 않음
            ),
            states = listOf(
                ImageState("a", "char:1"), ImageState("b", "char:1"),
                ImageState("gone", "char:1"),        // 캐릭터 1에서 빠짐 → 해제
                ImageState("m1", "G1"), ImageState("m2", "G1"),
                ImageState("dead", "char:44")        // 캐릭터 없음 → 해제
            )
        )
        assertEquals(mapOf("char:1" to listOf("new")), plan.claims)
        assertEquals(setOf("gone", "dead"), plan.releases.toSet())
    }
}
