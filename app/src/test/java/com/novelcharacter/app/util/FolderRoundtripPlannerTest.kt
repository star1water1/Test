package com.novelcharacter.app.util

import com.novelcharacter.app.util.FolderRoundtripPlanner.HoldReason
import com.novelcharacter.app.util.FolderRoundtripPlanner.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 받아오기 계획 계약 — 설계 3장 폴더 규약의 단일 소스.
 *
 * 특히 지키는 것: **보류는 실패가 아니다**(임의 선택 금지), **현재 소유 상태가 폴더 위치를
 * 이긴다**(C-2), **1장짜리 세트는 만들지 않는다**(인앱 링크 규약과 동일).
 */
class FolderRoundtripPlannerTest {

    private val uuidA = "3f9a2c40-7b16-4c1e-8a55-0d1e2f3a4b5c"
    private val uuidB = "88ee0011-2233-4455-6677-8899aabbccdd"
    private val pathA = "/files/char_$uuidA.jpg"
    private val pathB = "/files/img_$uuidB.jpg"
    private val tokenA = uuidA.replace("-", "").take(12)
    private val tokenB = uuidB.replace("-", "").take(12)

    private fun newFile(id: String, vararg folders: String, name: String = "$id.jpg") =
        ScanItem(id, folders.toList(), name)

    private fun tokenFile(id: String, token: String, vararg folders: String) =
        ScanItem(id, folders.toList(), "라벨-01.$token.jpg")

    private val dict = mapOf(tokenA to pathA, tokenB to pathB)

    private fun plan(
        items: List<ScanItem>,
        names: Map<String, List<Long>> = emptyMap(),
        owners: Map<String, List<Long>> = emptyMap()
    ) = FolderRoundtripPlanner.plan(items, names, dict, owners)

    // ── 자리 해석 ──

    @Test fun classify_readsReservedFoldersAndDepth() {
        val c = FolderRoundtripPlanner::classify
        assertTrue(c(emptyList()) is FolderRoundtripPlanner.Location.Root)
        assertTrue(c(listOf("가온")) is FolderRoundtripPlanner.Location.Named)
        assertTrue(c(listOf("_미배정")) is FolderRoundtripPlanner.Location.UnassignedRoot)
        assertTrue(c(listOf("_미배정", "세트1")) is FolderRoundtripPlanner.Location.UnassignedSet)
        assertTrue(c(listOf("_공유")) is FolderRoundtripPlanner.Location.Shared)
        assertTrue(c(listOf("_처리됨", "가온")) is FolderRoundtripPlanner.Location.Skipped)
        assertTrue(c(listOf("가온", "하위")) is FolderRoundtripPlanner.Location.TooDeep)
        assertTrue(c(listOf("_미배정", "세트1", "더")) is FolderRoundtripPlanner.Location.TooDeep)
    }

    @Test fun shouldDescend_stopsAtProcessedAndMaxDepth() {
        assertTrue(FolderRoundtripPlanner.shouldDescend(emptyList()))
        assertTrue(FolderRoundtripPlanner.shouldDescend(listOf("_미배정")))
        assertTrue(FolderRoundtripPlanner.shouldDescend(listOf("가온")))
        assertEquals(false, FolderRoundtripPlanner.shouldDescend(listOf("_처리됨")))
        assertEquals(false, FolderRoundtripPlanner.shouldDescend(listOf("_미배정", "세트1")))
    }

    @Test fun processedFolder_isNotEvenCounted() {
        // `_처리됨/` 하위를 깊이 위반으로 세면 받아올 때마다 "무시된 파일 N장"이 이력만큼 불어난다.
        val p = plan(listOf(newFile("x", "_처리됨", "가온")))
        assertTrue(p.isEmpty)
        assertEquals(0, p.deeperIgnored)
    }

    @Test fun tooDeepFiles_areCountedNotProcessed() {
        val p = plan(listOf(newFile("x", "가온", "하위")))
        assertTrue(p.imports.isEmpty())
        assertEquals(1, p.deeperIgnored)
    }

    // ── 신규 파일 편입 ──

    @Test fun newFileAtRoot_importsUnassignedWithoutLink() {
        val p = plan(listOf(newFile("a")))
        assertEquals(1, p.imports.size)
        assertNull(p.imports[0].assignCharacterId)
        assertNull(p.imports[0].setKey)
        assertTrue(p.linkSets.isEmpty())
    }

    @Test fun newFileInCharacterFolder_assignsThatCharacter() {
        val p = plan(listOf(newFile("a", "가온")), names = mapOf("가온" to listOf(7L)))
        assertEquals(7L, p.imports[0].assignCharacterId)
        // 캐릭터 폴더는 수동 세트를 만들지 않는다 — 자동 링크가 묶는다.
        assertTrue(p.linkSets.isEmpty())
        assertNull(p.imports[0].setKey)
    }

    @Test fun characterFolderName_isTrimmedBeforeMatching() {
        val p = plan(listOf(newFile("a", " 가온 ")), names = mapOf("가온" to listOf(7L)))
        assertEquals(7L, p.imports[0].assignCharacterId)
    }

    @Test fun newFilesInUnknownFolder_formLinkSet() {
        val p = plan(listOf(newFile("a", "여행"), newFile("b", "여행")))
        assertEquals(2, p.imports.size)
        assertTrue(p.imports.all { it.assignCharacterId == null && it.setKey == "여행" })
        assertEquals(1, p.linkSets.size)
        assertEquals(listOf("a", "b"), p.linkSets[0].newItemIds)
        assertEquals(2, p.linkSets[0].size)
    }

    @Test fun singleFileFolder_makesNoSet() {
        val p = plan(listOf(newFile("a", "여행")))
        assertTrue(p.linkSets.isEmpty())
        assertNull(p.imports[0].setKey)   // 세트가 성립하지 않으면 링크 없는 미배정으로 되돌린다
    }

    @Test fun unassignedSubFolder_formsLinkSet() {
        val p = plan(listOf(newFile("a", "_미배정", "세트1"), newFile("b", "_미배정", "세트1")))
        assertEquals(1, p.linkSets.size)
        assertEquals("_미배정/세트1", p.linkSets[0].key)
    }

    @Test fun unassignedRoot_importsWithoutLink() {
        val p = plan(listOf(newFile("a", "_미배정"), newFile("b", "_미배정")))
        assertTrue(p.linkSets.isEmpty())
        assertTrue(p.imports.all { it.setKey == null })
    }

    @Test fun sharedFolder_importsNewFileUnassigned() {
        val p = plan(listOf(newFile("a", "_공유")))
        assertEquals(1, p.imports.size)
        assertNull(p.imports[0].assignCharacterId)
        assertTrue(p.holds.isEmpty())
    }

    // ── 동명 캐릭터 (부드러운 실패) ──

    @Test fun sameNameCharacters_holdAssignmentButStillLink() {
        val p = plan(
            listOf(newFile("a", "가온"), newFile("b", "가온")),
            names = mapOf("가온" to listOf(7L, 9L))
        )
        assertEquals(listOf("가온"), p.ambiguousFolders)
        assertTrue(p.imports.all { it.assignCharacterId == null })
        // 세트로 들어가므로 인앱에서 한 장만 배정하면 전체가 따라간다(결정 D3).
        assertEquals(1, p.linkSets.size)
        assertEquals(2, p.linkSets[0].size)
    }

    // ── 토큰 파일: 이동 ──

    @Test fun tokenFileInCharacterFolder_movesAssignment() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "가온")),
            names = mapOf("가온" to listOf(7L)),
            owners = mapOf(pathA to listOf(3L))
        )
        assertEquals(1, p.moves.size)
        assertEquals(pathA, p.moves[0].path)
        assertEquals(listOf(3L), p.moves[0].fromCharacterIds)
        assertEquals(7L, p.moves[0].toCharacterId)
    }

    @Test fun tokenFileAlreadyOnThatCharacter_isNoOp() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "가온")),
            names = mapOf("가온" to listOf(7L)),
            owners = mapOf(pathA to listOf(7L))
        )
        assertTrue(p.isEmpty)
        assertEquals(0, p.actionCount)
    }

    @Test fun unassignedTokenFileInCharacterFolder_becomesAssignment() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "가온")),
            names = mapOf("가온" to listOf(7L))
        )
        assertEquals(1, p.moves.size)
        assertTrue(p.moves[0].fromCharacterIds.isEmpty())
    }

    // ── 토큰 파일: 해제 ──

    @Test fun tokenFileAtRoot_detachesAllCharacters() {
        val p = plan(listOf(tokenFile("t", tokenA)), owners = mapOf(pathA to listOf(3L)))
        assertEquals(1, p.detaches.size)
        assertEquals(listOf(3L), p.detaches[0].fromCharacterIds)
    }

    @Test fun unassignedTokenFileAtRoot_isNoOp() {
        val p = plan(listOf(tokenFile("t", tokenA)))
        assertTrue(p.isEmpty)
    }

    @Test fun tokenFileInUnassignedSet_detachesAndLinks() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "_미배정", "세트1"), newFile("n", "_미배정", "세트1")),
            owners = mapOf(pathA to listOf(3L))
        )
        assertEquals(1, p.detaches.size)
        assertEquals(1, p.linkSets.size)
        assertEquals(listOf(pathA), p.linkSets[0].existingPaths)
        assertEquals(listOf("n"), p.linkSets[0].newItemIds)
    }

    @Test fun tokenFileInUnknownFolder_keepsAssignmentButJoinsSet() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "여행"), tokenFile("u", tokenB, "여행")),
            owners = mapOf(pathA to listOf(3L))
        )
        assertTrue(p.moves.isEmpty())
        assertTrue(p.detaches.isEmpty())
        assertEquals(listOf(pathA, pathB), p.linkSets[0].existingPaths)
    }

    // ── 보류: 현재 소유 상태가 폴더 위치를 이긴다 (C-2) ──

    @Test fun sharedOwners_holdMoveEvenInCharacterFolder() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "가온")),
            names = mapOf("가온" to listOf(7L)),
            owners = mapOf(pathA to listOf(3L, 5L))
        )
        assertTrue(p.moves.isEmpty())
        assertEquals(listOf(HoldReason.SHARED_OWNERS), p.holds.map { it.reason })
    }

    @Test fun sharedOwners_holdDetachAtRoot() {
        val p = plan(listOf(tokenFile("t", tokenA)), owners = mapOf(pathA to listOf(3L, 5L)))
        assertTrue(p.detaches.isEmpty())
        assertEquals(listOf(HoldReason.SHARED_OWNERS), p.holds.map { it.reason })
    }

    @Test fun sharedOwners_inSetFolder_holdDetachButStillLink() {
        // 링크는 되돌릴 수 있고 파괴적이지 않다 — 묶음에는 넣되 해제는 보류한다.
        val p = plan(
            listOf(tokenFile("t", tokenA, "_미배정", "세트1"), newFile("n", "_미배정", "세트1")),
            owners = mapOf(pathA to listOf(3L, 5L))
        )
        assertTrue(p.detaches.isEmpty())
        assertEquals(listOf(HoldReason.SHARED_OWNERS), p.holds.map { it.reason })
        assertEquals(listOf(pathA), p.linkSets[0].existingPaths)
    }

    // ── 보류: 같은 이미지가 두 자리에 ──

    @Test fun duplicateToken_holdsBothAndChangesNothing() {
        val p = plan(
            listOf(tokenFile("t1", tokenA, "가온"), tokenFile("t2", tokenA)),
            names = mapOf("가온" to listOf(7L)),
            owners = mapOf(pathA to listOf(3L))
        )
        assertTrue(p.moves.isEmpty())
        assertTrue(p.detaches.isEmpty())
        assertEquals(2, p.holds.size)
        assertTrue(p.holds.all { it.reason == HoldReason.DUPLICATE_TOKEN })
    }

    @Test fun sameFileViaShortAndFullToken_isStillDuplicate() {
        // 12자 사본과 32자 사본이 같은 이미지를 가리켜도 중복이다 — 판정은 경로로 한다.
        val full = uuidA.replace("-", "")
        val p = FolderRoundtripPlanner.plan(
            listOf(tokenFile("t1", tokenA, "가온"), tokenFile("t2", full)),
            mapOf("가온" to listOf(7L)),
            dict + (full to pathA),
            mapOf(pathA to listOf(3L))
        )
        assertEquals(2, p.holds.size)
        assertTrue(p.holds.all { it.reason == HoldReason.DUPLICATE_TOKEN })
    }

    // ── 보류: `_공유/`의 토큰 파일 ──

    @Test fun tokenFileInSharedFolder_isExcluded() {
        val p = plan(listOf(tokenFile("t", tokenA, "_공유")), owners = mapOf(pathA to listOf(3L, 5L)))
        assertTrue(p.actionCount == 0)
        assertEquals(listOf(HoldReason.SHARED_FOLDER), p.holds.map { it.reason })
    }

    // ── 토큰꼴이지만 사전에 없음 (C-1) ──

    @Test fun unknownToken_importsAsNewButIsCounted() {
        val stranger = "aaaabbbbcccc"
        val p = plan(listOf(tokenFile("t", stranger, "가온")), names = mapOf("가온" to listOf(7L)))
        assertEquals(1, p.imports.size)
        assertEquals(7L, p.imports[0].assignCharacterId)
        assertEquals(1, p.unknownTokenFiles)
    }

    @Test fun ordinaryFileName_isNotCountedAsUnknownToken() {
        val p = plan(listOf(newFile("a", name = "여행 사진.jpg")))
        assertEquals(1, p.imports.size)
        assertEquals(0, p.unknownTokenFiles)
    }

    // ── 결정성 ──

    @Test fun plan_isDeterministicForSameInput() {
        val items = listOf(
            newFile("a", "여행"), newFile("b", "여행"),
            tokenFile("t", tokenA, "가온"), newFile("c")
        )
        val names = mapOf("가온" to listOf(7L))
        val owners = mapOf(pathA to listOf(3L))
        val first = plan(items, names, owners)
        val second = plan(items, names, owners)
        assertEquals(first.imports.map { it.item.id }, second.imports.map { it.item.id })
        assertEquals(first.linkSets, second.linkSets)
        assertEquals(first.moves, second.moves)
    }
}
