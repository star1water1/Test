package com.novelcharacter.app.util

import com.novelcharacter.app.ai.AiPromptPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **첨부 이미지 고르기 규칙의 회귀 가드** (A-7 · 설계 `feature_roadmap_2026-08` 2-2 표).
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 셋이다:**
 * ① *지정 대표가 없으면 그 사실을 말한다* — 조용히 다른 그림을 첫 장에 놓으면 사용자는
 *   **대표를 지정했다고 믿은 채** 엉뚱한 그림으로 받은 값을 검토한다. 화면은 멀쩡해 보이고
 *   결과도 그럴듯해서 **틀렸다는 사실 자체를 아무도 모른다.**
 * ② *직접 고른 것이 랜덤과 대표 강제를 모두 이긴다* — 자율성 우선의 실행 규칙이다.
 *   여기가 뒤집히면 "하필 그 장"을 빼는 유일한 경로가 사라져 랜덤만 남는다.
 * ③ *같은 시드는 같은 결과다* — 다이얼로그가 다시 그려질 때마다 그림이 튀면 사용자는
 *   무엇이 나갈지 실행 전에 알 수 없다(대표 고르기가 시드를 쓰는 것과 같은 이유).
 */
class AiImageAttachTest {

    private val paths = listOf("/a.jpg", "/b.jpg", "/c.jpg", "/d.jpg")

    // ── ① 대표 ───────────────────────────────────────────────────────────────

    @Test
    fun `대표를 켜면 지정한 그림이 첫 장이다`() {
        val plan = AiImageAttach.plan(
            paths, representativePath = "/c.jpg", count = 2,
            includeRepresentative = true, seed = 42L
        )
        assertEquals("/c.jpg", plan.paths.first())
        assertEquals(2, plan.count)
        assertFalse(plan.representativeMissing)
    }

    @Test
    fun `대표를 켰는데 지정이 없으면 그 사실을 말하고 전부 무작위로 채운다`() {
        val plan = AiImageAttach.plan(
            paths, representativePath = null, count = 2,
            includeRepresentative = true, seed = 42L
        )
        assertTrue("고지하지 않으면 사용자는 대표가 실렸다고 믿는다", plan.representativeMissing)
        assertEquals(2, plan.count)
    }

    @Test
    fun `지정 대표가 목록에서 사라졌어도 조용히 다른 그림을 대표라 부르지 않는다`() {
        // 앱 밖에서 파일이 지워졌거나 폴더 왕복이 경로를 바꾼 자리 — 사전 확인이 불가능하다.
        val plan = AiImageAttach.plan(
            paths, representativePath = "/gone.jpg", count = 2,
            includeRepresentative = true, seed = 7L
        )
        assertTrue(plan.representativeMissing)
        assertFalse(plan.paths.contains("/gone.jpg"))
    }

    @Test
    fun `대표를 끄면 고지할 것이 없다`() {
        val plan = AiImageAttach.plan(
            paths, representativePath = null, count = 2,
            includeRepresentative = false, seed = 42L
        )
        assertFalse("끈 것은 '빠진 것'이 아니다", plan.representativeMissing)
    }

    // ── ② 직접 고르기 ────────────────────────────────────────────────────────

    @Test
    fun `직접 고른 것이 대표 강제와 무작위를 모두 이긴다`() {
        val plan = AiImageAttach.plan(
            paths, representativePath = "/a.jpg", count = 3,
            includeRepresentative = true, seed = 1L,
            manual = listOf("/d.jpg", "/b.jpg")
        )
        assertEquals(listOf("/d.jpg", "/b.jpg"), plan.paths)
        assertFalse("사람이 집은 것에는 대표 고지가 붙지 않는다", plan.representativeMissing)
    }

    @Test
    fun `직접 고른 것도 장수 상한을 넘지 못한다`() {
        val plan = AiImageAttach.plan(
            paths, representativePath = null, count = 2,
            includeRepresentative = false, seed = 1L,
            manual = listOf("/a.jpg", "/b.jpg", "/c.jpg")
        )
        assertEquals(2, plan.count)
    }

    @Test
    fun `직접 고른 것이 전부 사라졌으면 규칙으로 되돌아간다`() {
        // 빈 손으로 요청을 보내면 사용자는 "붙였는데 왜 없지"가 되고, 그 이유가 화면 어디에도
        // 없다. 목록에 없는 경로는 애초에 보낼 수 없으므로 규칙이 대신 채운다.
        val plan = AiImageAttach.plan(
            paths, representativePath = "/a.jpg", count = 2,
            includeRepresentative = true, seed = 1L,
            manual = listOf("/gone1.jpg", "/gone2.jpg")
        )
        assertEquals(2, plan.count)
        assertEquals("/a.jpg", plan.paths.first())
    }

    // ── ③ 시드 ───────────────────────────────────────────────────────────────

    @Test
    fun `같은 시드는 같은 결과다`() {
        val a = AiImageAttach.plan(paths, null, 2, includeRepresentative = false, seed = 99L)
        val b = AiImageAttach.plan(paths, null, 2, includeRepresentative = false, seed = 99L)
        assertEquals(a.paths, b.paths)
    }

    @Test
    fun `고른 결과에 같은 그림이 두 번 들어가지 않는다`() {
        for (seed in listOf(1L, 2L, 3L, 55L, -7L, Long.MAX_VALUE)) {
            val plan = AiImageAttach.plan(paths, "/b.jpg", 4, includeRepresentative = true, seed = seed)
            assertEquals("시드 $seed 에서 중복이 생겼다", plan.paths.size, plan.paths.distinct().size)
        }
    }

    // ── 경계 ─────────────────────────────────────────────────────────────────

    @Test
    fun `장수가 0이면 아무것도 붙이지 않는다`() {
        val plan = AiImageAttach.plan(paths, "/a.jpg", 0, includeRepresentative = true, seed = 1L)
        assertTrue(plan.isEmpty)
        assertFalse("보내지 않기로 한 것에는 대표 고지가 붙지 않는다", plan.representativeMissing)
    }

    @Test
    fun `이미지가 없으면 아무것도 붙이지 않는다`() {
        val plan = AiImageAttach.plan(emptyList(), "/a.jpg", 3, includeRepresentative = true, seed = 1L)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `요청 장수가 가진 그림보다 많으면 가진 만큼만 붙인다`() {
        val plan = AiImageAttach.plan(listOf("/a.jpg"), null, 5, includeRepresentative = false, seed = 1L)
        assertEquals(listOf("/a.jpg"), plan.paths)
    }

    @Test
    fun `빈 경로와 중복 경로는 목록에서 걸러진다`() {
        val plan = AiImageAttach.plan(
            listOf("/a.jpg", "", "  ", "/a.jpg", "/b.jpg"), null, 5,
            includeRepresentative = false, seed = 1L
        )
        assertEquals(2, plan.count)
        assertEquals(setOf("/a.jpg", "/b.jpg"), plan.paths.toSet())
    }

    @Test
    fun `장수는 정책 상한으로 잘린다`() {
        val many = (1..30).map { "/img$it.jpg" }
        val plan = AiImageAttach.plan(many, null, 999, includeRepresentative = false, seed = 1L)
        assertEquals(AiPromptPolicy.ATTACH_IMAGES_MAX, plan.count)
    }

    // ── 비용 고지의 단일 소스 ────────────────────────────────────────────────

    @Test
    fun `연인원은 장수 곱하기 요청 수다`() {
        // 짧은 값 추천은 대상을 청킹해 **요청마다 같은 그림을 다시 싣는다**.
        // 고지가 장수만 말하면 사용자는 1장 값을 예상하고 요청 수만큼을 낸다(R-4).
        assertEquals(6, AiPromptPolicy.imageSendCount(2, 3))
        assertEquals(2, AiPromptPolicy.imageSendCount(2, 1))
        assertEquals(0, AiPromptPolicy.imageSendCount(0, 5))
        assertEquals(0, AiPromptPolicy.imageSendCount(3, 0))
    }

    @Test
    fun `첨부 장수 기본값은 사용자가 확정한 1장이다`() {
        // Q3 확정 — 비용이 붙는 기본값은 보수적으로 두고 올리는 것을 사용자 선택으로 남긴다.
        assertEquals(1, AiPromptPolicy.ATTACH_IMAGES_DEFAULT)
        assertEquals(0, AiPromptPolicy.clampAttachImages(-3))
        assertEquals(AiPromptPolicy.ATTACH_IMAGES_MAX, AiPromptPolicy.clampAttachImages(99))
    }
}
