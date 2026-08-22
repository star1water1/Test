package com.novelcharacter.app.util

import com.novelcharacter.app.util.FolderRoundtripPlanner.HoldReason
import com.novelcharacter.app.util.FolderRoundtripPlanner.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        owners: Map<String, List<Long>> = emptyMap(),
        linked: Set<String> = emptySet(),
        detached: Set<String> = emptySet()
    ) = FolderRoundtripPlanner.plan(
        items, names, dict, owners,
        FolderRoundtripPlanner.CharacterFolderResolver(names), linked, detached
    )

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
        // 세트 폴더 **안**을 나열해야 그 안의 파일이 보인다 — 내보내기가 그 자리에 쓴다.
        assertTrue(FolderRoundtripPlanner.shouldDescend(listOf("_미배정", "세트1")))
        assertTrue(FolderRoundtripPlanner.shouldDescend(listOf("_분리됨", "세트1")))
        // 그 아래는 규약 밖이다(classify가 TooDeep으로 받는 깊이).
        assertEquals(false, FolderRoundtripPlanner.shouldDescend(listOf("_미배정", "세트1", "더")))
    }

    /**
     * **두 판정이 짝이어야 한다** — [FolderRoundtripPlanner.classify]가 정상으로 받는 깊이는
     * [FolderRoundtripPlanner.shouldDescend]가 **반드시 나열한다.**
     *
     * 이 불변식이 없던 동안 둘이 한 칸씩 어긋나 있었고, 그래서 `_미배정/<세트>/`·
     * `_분리됨/<세트>/`를 다루는 규칙 전부가 **도달 불가 코드**였다(내보내기는 그 자리에 쓰는데
     * 받아오기가 그 폴더 안을 열지 않았다). 함수를 따로 재는 시험은 그 어긋남을 못 본다.
     */
    @Test fun shouldDescend_reachesEveryDepthClassifyAccepts() {
        val paths = listOf(
            listOf("_미배정", "세트1"), listOf("_분리됨", "세트1"),
            listOf("_미배정"), listOf("_분리됨"), listOf("_공유"), listOf("_기타"), listOf("가온")
        )
        for (path in paths) {
            val location = FolderRoundtripPlanner.classify(path)
            if (location is FolderRoundtripPlanner.Location.TooDeep) continue
            if (location is FolderRoundtripPlanner.Location.Skipped) continue
            // 그 자리에 놓인 파일을 보려면 그 폴더 안으로 들어가야 한다.
            assertTrue(
                "classify는 $path 를 $location 로 받는데 나열이 그 안으로 안 내려간다",
                FolderRoundtripPlanner.shouldDescend(path)
            )
        }
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

    @Test fun unassignedAndUnlinkedTokenFileAtRoot_isNoOp() {
        val p = plan(listOf(tokenFile("t", tokenA)))
        assertTrue(p.isEmpty)
    }

    // ── 되돌리는 자리는 배정도 묶음도 없앤다 ──
    //
    // 배정이 없고 묶음만 있는 이미지도 여기서는 할 일이 있다. 이 갈래가 없으면
    // `<기타 이름>/`·`_미배정/<세트명>/`이 만든 세트를 폴더로 되돌릴 길이 없다.

    @Test fun unassignedButLinkedTokenFileAtRoot_unlinks() {
        val p = plan(listOf(tokenFile("t", tokenA)), linked = setOf(pathA))
        assertEquals(1, p.detaches.size)
        assertTrue(p.detaches[0].fromCharacterIds.isEmpty())
        assertTrue(p.detaches[0].unlinks)
    }

    @Test fun unassignedButLinkedTokenFileInUnassignedRoot_unlinks() {
        val p = plan(listOf(tokenFile("t", tokenA, "_미배정")), linked = setOf(pathA))
        assertEquals(1, p.detaches.size)
        assertTrue(p.detaches[0].unlinks)
    }

    @Test fun assignedAndLinkedTokenFileAtRoot_detachesAndUnlinks() {
        val p = plan(
            listOf(tokenFile("t", tokenA)),
            owners = mapOf(pathA to listOf(3L)),
            linked = setOf(pathA)
        )
        assertEquals(1, p.detaches.size)
        assertEquals(listOf(3L), p.detaches[0].fromCharacterIds)
        assertTrue(p.detaches[0].unlinks)
    }

    /** 소유자가 둘 이상이면 배정도 묶음도 건드리지 않는다 — 반쪽 반영은 보류가 아니다(C-2). */
    @Test fun sharedOwnersAtRoot_holdsWithoutUnlinking() {
        val p = plan(
            listOf(tokenFile("t", tokenA)),
            owners = mapOf(pathA to listOf(3L, 4L)),
            linked = setOf(pathA)
        )
        assertTrue(p.detaches.isEmpty())
        assertEquals(1, p.holds.size)
        assertEquals(HoldReason.SHARED_OWNERS, p.holds[0].reason)
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

    /**
     * 묶는 자리에서는 묶음을 풀지 않는다 — 여기서 풀면 뒤이은 링크 세트가 기존 그룹을
     * 흡수하지 못해, 규약상 **병합**이어야 할 것이 조용히 **이동**이 된다.
     */
    @Test fun linkedTokenFileInUnassignedSet_doesNotUnlink() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "_미배정", "세트1"), newFile("n", "_미배정", "세트1")),
            owners = mapOf(pathA to listOf(3L)),
            linked = setOf(pathA)
        )
        assertEquals(1, p.detaches.size)
        assertTrue(p.detaches[0].unlinks.not())
    }

    /** 묶는 자리의 미배정+묶음 이미지는 해제 없이 세트에만 들어간다(할 일이 링크뿐이다). */
    @Test fun unassignedLinkedTokenFileInUnassignedSet_joinsSetOnly() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "_미배정", "세트1"), newFile("n", "_미배정", "세트1")),
            linked = setOf(pathA)
        )
        assertTrue(p.detaches.isEmpty())
        assertEquals(listOf(pathA), p.linkSets[0].existingPaths)
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

    // ── 서랍 `기타/`·`_기타/` (설계 image_folder_tag_ai 2장, D-1·D-2) ──

    /** 서랍의 신규 파일은 **낱개**로 들어온다 — 여러 장이어도 세트가 서지 않는다. */
    @Test fun miscFolder_newFilesAreImportedIndividuallyWithoutLink() {
        val p = plan(listOf(newFile("a", "기타"), newFile("b", "기타"), newFile("c", "기타")))
        assertEquals(3, p.imports.size)
        assertTrue(p.imports.all { it.setKey == null && it.assignCharacterId == null })
        assertTrue("서랍은 세트를 만들지 않는다", p.linkSets.isEmpty())
        assertEquals(3, p.miscImported)
    }

    /** 서랍 편입 수는 다른 자리의 '배정·세트 없는 편입'과 섞이지 않는다. */
    @Test fun miscImported_doesNotCountOtherUnassignedImports() {
        val p = plan(listOf(newFile("a", "기타"), newFile("b"), newFile("c", "_미배정"), newFile("d", "_공유")))
        assertEquals(4, p.imports.size)
        assertEquals(1, p.miscImported)
    }

    /** 예약 접두판도 같은 함수를 탄다 — 둘의 뜻이 갈리면 규약이 둘이 된다. */
    @Test fun reservedMiscFolder_behavesSameAsPlainName() {
        val plain = plan(listOf(newFile("a", "기타"), newFile("b", "기타")))
        val reserved = plan(listOf(newFile("a", "_기타"), newFile("b", "_기타")))
        assertEquals(plain.imports.map { it.setKey }, reserved.imports.map { it.setKey })
        assertEquals(plain.linkSets, reserved.linkSets)
    }

    /** `_기타/`가 "이름이 `_기타`인 캐릭터"를 찾으면 안 된다(classify 예약 분기 누락 회귀). */
    @Test fun reservedMiscFolder_isNotTreatedAsCharacterName() {
        val p = plan(listOf(newFile("a", "_기타")), names = mapOf("_기타" to listOf(9L)))
        assertEquals(1, p.imports.size)
        assertNull("예약 폴더는 캐릭터로 해석되지 않는다", p.imports[0].assignCharacterId)
    }

    /** 서랍의 토큰 파일은 **묶음만** 풀린다 — 캐릭터 배정은 그대로다(D-2). */
    @Test fun miscFolder_tokenFileUnlinksOnlyAndKeepsAssignment() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "기타")),
            owners = mapOf(pathA to listOf(7L)),
            linked = setOf(pathA)
        )
        assertEquals(1, p.unlinkOnly.size)
        assertEquals(pathA, p.unlinkOnly[0].path)
        assertTrue("배정을 떼는 계획이 서면 안 된다", p.detaches.isEmpty())
        assertTrue(p.moves.isEmpty())
    }

    /** 이미 낱개인 토큰 파일은 할 일이 없다 — 계수도 하지 않는다. */
    @Test fun miscFolder_alreadyUnlinkedTokenFileIsNoOp() {
        val p = plan(listOf(tokenFile("t", tokenA, "기타")), owners = mapOf(pathA to listOf(7L)))
        assertTrue(p.unlinkOnly.isEmpty())
        assertTrue(p.detaches.isEmpty())
        assertEquals(0, p.actionCount)
    }

    /**
     * 소유자가 둘 이상이어도 서랍은 보류하지 않는다 — C-2 보류는 **배정을 건드릴 때**의
     * 안전장치인데 서랍은 배정을 건드리지 않는다.
     */
    @Test fun miscFolder_sharedOwnersStillUnlink() {
        val p = plan(
            listOf(tokenFile("t", tokenA, "기타")),
            owners = mapOf(pathA to listOf(7L, 8L)),
            linked = setOf(pathA)
        )
        assertEquals(1, p.unlinkOnly.size)
        assertTrue("서랍에서는 보류가 생기지 않는다", p.holds.isEmpty())
    }

    // ── D-1: 캐릭터가 우선 ──

    /** '기타'라는 캐릭터가 실재하면 `기타/`는 그 캐릭터 폴더다. 그리고 그 사실을 고지한다. */
    @Test fun miscPlainName_losesToRealCharacterAndIsReported() {
        val p = plan(listOf(newFile("a", "기타")), names = mapOf("기타" to listOf(42L)))
        assertEquals(1, p.imports.size)
        assertEquals(42L, p.imports[0].assignCharacterId)
        assertEquals(listOf("기타"), p.miscReadAsCharacter)
    }

    /** 예약 접두판은 캐릭터가 있어도 서랍이다 — 빠져나갈 길이 실제로 있어야 고지가 쓸모 있다. */
    @Test fun reservedMiscFolder_winsEvenWhenCharacterNamedMiscExists() {
        val p = plan(listOf(newFile("a", "_기타")), names = mapOf("기타" to listOf(42L)))
        assertNull(p.imports[0].assignCharacterId)
        assertTrue(p.miscReadAsCharacter.isEmpty())
    }

    /** 동명이인은 서랍으로 새지 않고 기존 해소 사다리(물어보기)를 탄다. */
    @Test fun miscPlainName_withAmbiguousCharactersStillAsks() {
        val p = plan(listOf(newFile("a", "기타")), names = mapOf("기타" to listOf(1L, 2L)))
        assertEquals(listOf("기타"), p.ambiguousFolders)
        assertTrue("서랍으로 강등되면 질문이 사라진다", p.miscReadAsCharacter.isEmpty())
    }

    // ── AI 태그 대상 수집 ──

    /** '그 외' 폴더만 대상이다. 신규·토큰 파일 모두 "이번에 그 폴더에서 온" 것이다(D-4). */
    @Test fun aiTagFolders_collectsOnlyNonCharacterNamedFolders() {
        val p = plan(
            listOf(
                newFile("a", "여행"), tokenFile("t", tokenA, "여행"),
                newFile("b", "가온"), newFile("c", "기타"), newFile("d", "_미배정", "세트")
            ),
            names = mapOf("가온" to listOf(7L))
        )
        // 신규는 항목 id로(경로가 편입 뒤에 정해진다), 토큰 파일은 이미 있는 경로로 나뉜다.
        assertEquals(setOf("여행"), p.aiTagFolders.keys)
        assertEquals(listOf("a"), p.aiTagFolders["여행"])
        assertEquals(listOf(pathA), p.aiTagExistingPaths["여행"])
    }

    /** 동명 보류 폴더는 대상이 아니다 — 그 이름은 캐릭터를 가리키려던 것이라 태그가 되면 안 된다. */
    @Test fun aiTagFolders_excludesAmbiguousFolders() {
        val p = plan(listOf(newFile("a", "가온"), newFile("b", "가온")), names = mapOf("가온" to listOf(1L, 2L)))
        assertTrue(p.aiTagFolders.isEmpty())
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

    // ══ 뗀 것 서랍 · _삭제승인 (B-107 D5·D6) ══════════════════════════════════════════
    //
    // 이 묶음이 지키는 것은 **세 폴더가 정리 작업의 세 갈래와 1:1이라는 것**이다 —
    // `_분리됨`=판단 안 함 · `_미배정`=살림 · `_삭제승인`=지움. 셋 중 하나라도 뜻이 겹치면
    // 폴더로 정리하는 사람의 왕복이 반쪽이 된다.

    @Test fun classify_readsNewReservedFolders() {
        val detachedFolder = FolderRoundtripPlanner.FOLDER_DETACHED
        val deleteFolder = FolderRoundtripPlanner.FOLDER_DELETE_APPROVAL
        assertEquals(
            FolderRoundtripPlanner.Location.DetachedRoot,
            FolderRoundtripPlanner.classify(listOf(detachedFolder))
        )
        assertEquals(
            FolderRoundtripPlanner.Location.DetachedSet("세트-1"),
            FolderRoundtripPlanner.classify(listOf(detachedFolder, "세트-1"))
        )
        assertEquals(
            FolderRoundtripPlanner.Location.DeleteApproval,
            FolderRoundtripPlanner.classify(listOf(deleteFolder))
        )
        // 삭제에는 세트가 없다 — 하위를 규약 밖으로 보내 조용히 지우는 일이 없게 한다.
        assertEquals(
            FolderRoundtripPlanner.Location.TooDeep,
            FolderRoundtripPlanner.classify(listOf(deleteFolder, "아무거나"))
        )
    }

    /** `_분리됨/`은 배정을 떼되 표식을 **남긴다** — "아직 판단 안 함". */
    @Test fun detachedFolder_detachesAndKeepsMark() {
        val plan = plan(
            listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_DETACHED)),
            owners = mapOf(pathA to listOf(7L))
        )
        assertEquals(1, plan.detaches.size)
        assertEquals(listOf(7L), plan.detaches[0].fromCharacterIds)
        assertTrue(plan.detaches[0].keepsDetachedMark)
    }

    /** `_미배정/`은 같은 배정 해제이되 표식을 **지운다** — "다시 쓸 것으로 되돌림". */
    @Test fun unassignedFolder_detachesAndClearsMark() {
        val plan = plan(
            listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_UNASSIGNED)),
            owners = mapOf(pathA to listOf(7L))
        )
        assertEquals(1, plan.detaches.size)
        assertFalse(plan.detaches[0].keepsDetachedMark)
    }

    /**
     * **이 슬라이스에서 가장 놓치기 쉬운 자리.** 서랍에 있던 파일은 배정도 묶음도 없어서,
     * `detachedPaths`를 안 넘기면 `_미배정/`에 옮겨도 "할 일 없음"으로 보인다 —
     * 그러면 폴더로 서랍을 비우는 길이 통째로 막힌다.
     */
    @Test fun markOnlyFile_movedToUnassigned_hasWorkToDo() {
        val items = listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_UNASSIGNED))
        assertTrue(plan(items).detaches.isEmpty())   // 집합을 안 주면 못 본다(회귀 방지)
        val seen = plan(items, detached = setOf(pathA))
        assertEquals(1, seen.detaches.size)
        assertEquals(emptyList<Long>(), seen.detaches[0].fromCharacterIds)
        assertFalse(seen.detaches[0].keepsDetachedMark)
    }

    /** 이미 서랍에 있는 것을 서랍에 그대로 두면 할 일이 없다(계수도 하지 않는다). */
    @Test fun markOnlyFile_leftInDetachedFolder_isNoOp() {
        val plan = plan(
            listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_DETACHED)),
            detached = setOf(pathA)
        )
        assertTrue(plan.detaches.isEmpty())
        assertEquals(0, plan.actionCount)
    }

    /** `_분리됨/<세트명>/`은 `_미배정/<세트명>/`의 짝이다 — 묶는 자리라 묶음을 풀지 않는다. */
    @Test fun detachedSet_keepsLinksAndMark() {
        val plan = plan(
            listOf(
                tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_DETACHED, "세트-1"),
                tokenFile("f2", tokenB, FolderRoundtripPlanner.FOLDER_DETACHED, "세트-1")
            ),
            owners = mapOf(pathA to listOf(7L))
        )
        assertEquals(1, plan.detaches.size)
        assertFalse(plan.detaches[0].unlinks)
        assertTrue(plan.detaches[0].keepsDetachedMark)
        assertEquals(1, plan.linkSets.size)
    }

    /** 두 서랍의 같은 이름 세트는 **다른 묶음**이다 — 키가 겹치면 하나로 합쳐진다. */
    @Test fun sameSetNameInTwoDrawers_staysSeparate() {
        val plan = plan(
            listOf(
                tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_DETACHED, "세트-1"),
                newFile("n1", FolderRoundtripPlanner.FOLDER_DETACHED, "세트-1"),
                tokenFile("f2", tokenB, FolderRoundtripPlanner.FOLDER_UNASSIGNED, "세트-1"),
                newFile("n2", FolderRoundtripPlanner.FOLDER_UNASSIGNED, "세트-1")
            )
        )
        assertEquals(2, plan.linkSets.size)
    }

    // ── _삭제승인 ──

    @Test fun deleteApproval_plansDeletionWithOwners() {
        val plan = plan(
            listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_DELETE_APPROVAL)),
            owners = mapOf(pathA to listOf(7L, 8L))
        )
        assertEquals(1, plan.deletes.size)
        assertEquals(pathA, plan.deletes[0].path)
        assertEquals(listOf(7L, 8L), plan.deletes[0].ownerCharacterIds)
    }

    /**
     * **여러 캐릭터가 쓰는 이미지도 보류하지 않는다.** `_공유/`가 보류인 근거는 "이동의 의미가
     * 모호해서"인데 삭제는 모호하지 않다 — 파괴적일 뿐이고, 파괴는 확인창으로 다룬다(R-4).
     */
    @Test fun deleteApproval_doesNotHoldSharedImages() {
        val plan = plan(
            listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_DELETE_APPROVAL)),
            owners = mapOf(pathA to listOf(7L, 8L))
        )
        assertTrue(plan.holds.isEmpty())
    }

    /** 앱이 모르는 파일은 지울 것이 없다 — 편입해 두고 지우면 한 왕복에서 만들었다 없앤다. */
    @Test fun deleteApproval_ignoresUnknownFiles() {
        val plan = plan(listOf(newFile("n1", FolderRoundtripPlanner.FOLDER_DELETE_APPROVAL)))
        assertTrue(plan.deletes.isEmpty())
        assertTrue(plan.imports.isEmpty())
        assertEquals(0, plan.actionCount)
    }

    /** 삭제도 파일을 만지는 일이라 진행도 총량에 든다(규약 R-26). */
    @Test fun deletes_countTowardProgressTotal() {
        val plan = plan(
            listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_DELETE_APPROVAL)),
            owners = mapOf(pathA to listOf(7L))
        )
        assertEquals(1, plan.actionCount)
        assertFalse(plan.isEmpty)
    }
    // ── 뗀 표식 · 예약 접두 (2026.08.22 수리분의 계약) ──

    /**
     * `_미배정/<세트명>/`도 뗀 표식을 지운다 — 그 폴더의 뜻은 세트든 낱개든 '살림'이다(D5).
     * 종전에는 `owners`만 보고 판정해서, **묶여 있던 뗀 이미지를 폴더로 살릴 길이 아예 없었다**
     * (배정도 없어 '할 일 없음'으로 보였다).
     */
    @Test fun markOnlyFile_movedToUnassignedSet_clearsMark() {
        val items = listOf(
            tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_UNASSIGNED, "세트-1"),
            tokenFile("f2", tokenB, FolderRoundtripPlanner.FOLDER_UNASSIGNED, "세트-1")
        )
        val seen = plan(items, detached = setOf(pathA, pathB))
        assertEquals(2, seen.detaches.size)
        assertTrue(seen.detaches.none { it.keepsDetachedMark })
        // 묶는 자리이므로 묶음은 풀지 않는다(그래야 아래 세트가 기존 그룹을 흡수한다).
        assertTrue(seen.detaches.none { it.unlinks })
        assertEquals(1, seen.linkSets.size)
    }

    /** `_분리됨/<세트명>/`은 반대다 — 표식을 남긴다. 두 서랍이 대칭이면서 뜻이 반대인 자리. */
    @Test fun detachedSet_keepsMark() {
        val items = listOf(
            tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_DETACHED, "세트-1"),
            tokenFile("f2", tokenB, FolderRoundtripPlanner.FOLDER_DETACHED, "세트-1")
        )
        val seen = plan(items, owners = mapOf(pathA to listOf(7L), pathB to listOf(7L)))
        assertEquals(2, seen.detaches.size)
        assertTrue(seen.detaches.all { it.keepsDetachedMark })
    }

    /**
     * 예약을 쓰려다 빗나간 폴더는 **'그 외 이름'이 아니다** — 묶지도 배정하지도 않고 이름을 고지한다.
     * 종전에는 `_삭제 승인/`(오타) 같은 폴더가 조용히 수동 링크 묶음이 됐다.
     */
    @Test fun unknownReservedFolder_isReportedNotLinked() {
        val items = listOf(
            newFile("n1", "_삭제 승인"),
            newFile("n2", "_삭제 승인")
        )
        val p = plan(items)
        assertEquals(listOf("_삭제 승인"), p.unknownReservedFolders)
        assertTrue(p.imports.isEmpty())
        assertTrue(p.linkSets.isEmpty())
        assertEquals(0, p.actionCount)
    }

    /** 그러나 **그 이름의 캐릭터가 실재하면** 캐릭터가 이긴다(D-1) — 내보내기가 그 이름을 쓴다. */
    @Test fun underscoreNamedCharacterStillWins() {
        val p = plan(listOf(newFile("n1", "_비밀")), names = mapOf("_비밀" to listOf(7L)))
        assertTrue(p.unknownReservedFolders.isEmpty())
        assertEquals(1, p.imports.size)
        assertEquals(7L, p.imports[0].assignCharacterId)
    }

    /** 세트에 참여한 기존 파일은 **스캔 항목 id를 함께** 들고 나온다 — 실행부가 `_처리됨/`으로 옮긴다. */
    @Test fun linkSetCarriesScanItemIdsOfExistingMembers() {
        val items = listOf(
            tokenFile("f1", tokenA, "풍경"),
            tokenFile("f2", tokenB, "풍경")
        )
        val p = plan(items)
        assertEquals(1, p.linkSets.size)
        assertEquals(listOf("f1", "f2"), p.linkSets[0].existing.map { it.itemId })
        assertEquals(listOf(pathA, pathB), p.linkSets[0].existingPaths)
    }

    // ── 이미 처리한 파일은 '맥락' (지문 스킵 축 × 세트 정족수 축) ──

    private fun handledTokenFile(id: String, token: String, vararg folders: String) =
        ScanItem(id, folders.toList(), "라벨-01.$token.jpg", alreadyHandled = true)

    /**
     * 내보낸 세트 폴더에 사진 한 장을 더 넣으면 **그 묶음에 들어간다.**
     *
     * 종전에는 내보낸 사본이 지문으로 걸러져 계획에 안 실렸고, 그래서 폴더에 새 파일 1장뿐인
     * 것으로 보여 "2장 이상"에 걸려 **낱개로 편입**됐다(고지도 없었다).
     */
    @Test fun newFileJoinsAlreadyExportedSetFolder() {
        val items = listOf(
            handledTokenFile("h1", tokenA, FolderRoundtripPlanner.FOLDER_UNASSIGNED, "세트-1"),
            newFile("n1", FolderRoundtripPlanner.FOLDER_UNASSIGNED, "세트-1")
        )
        val p = plan(items)
        assertEquals(1, p.linkSets.size)
        assertEquals(listOf("n1"), p.linkSets[0].newItemIds)
        assertEquals(listOf(pathA), p.linkSets[0].existingPaths)
        // 신규는 세트로 편입되고, 이미 처리한 사본은 **행동을 만들지 않는다**.
        assertEquals(1, p.imports.size)
        assertEquals("${FolderRoundtripPlanner.FOLDER_UNASSIGNED}/세트-1", p.imports[0].setKey)
        assertTrue(p.moves.isEmpty() && p.detaches.isEmpty() && p.unlinkOnly.isEmpty())
    }

    /** '그 외 이름' 폴더도 같다 — 맥락 파일이 정족수를 채운다. */
    @Test fun newFileJoinsAlreadyExportedPlainFolder() {
        val items = listOf(
            handledTokenFile("h1", tokenA, "풍경"),
            newFile("n1", "풍경")
        )
        val p = plan(items)
        assertEquals(1, p.linkSets.size)
        assertEquals(listOf(pathA), p.linkSets[0].existingPaths)
    }

    /** 맥락 파일만 있으면 세트도 행동도 없다 — 아무 일도 일어나지 않는다. */
    @Test fun handledFilesAloneProduceNothing() {
        val p = plan(listOf(handledTokenFile("h1", tokenA, "풍경")))
        assertTrue(p.linkSets.isEmpty())
        assertEquals(0, p.actionCount)
    }

    /** 맥락 파일은 **캐릭터 폴더에서도** 배정을 움직이지 않는다(지난 왕복에서 끝난 일이다). */
    @Test fun handledFileInCharacterFolderDoesNotMove() {
        val p = plan(
            listOf(handledTokenFile("h1", tokenA, "가온")),
            names = mapOf("가온" to listOf(7L)),
            owners = mapOf(pathA to listOf(9L))
        )
        assertTrue(p.moves.isEmpty())
        assertEquals(0, p.actionCount)
    }

    /** '살림'은 셀 수 있어야 한다 — 배정도 묶음도 없이 표식만 있던 것이 이 축에만 잡힌다. */
    @Test fun reviveIsCountable() {
        val p = plan(
            listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_UNASSIGNED)),
            detached = setOf(pathA)
        )
        assertEquals(1, p.detaches.count { it.hadDetachedMark && !it.keepsDetachedMark })
        // 배정도 묶음도 없으므로 다른 두 축은 0이다 — 그래서 이 축이 없으면 아무 말도 못 한다.
        assertEquals(0, p.detaches.count { it.fromCharacterIds.isNotEmpty() })
        assertEquals(0, p.detaches.count { it.unlinks })
    }

    /** 표식이 없던 이미지를 되돌리는 것은 '살림'이 아니다 — 세면 없는 일을 했다고 말한다. */
    @Test fun revivingUnmarkedImageIsNotCounted() {
        val p = plan(
            listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_UNASSIGNED)),
            owners = mapOf(pathA to listOf(7L))
        )
        assertEquals(1, p.detaches.size)
        assertFalse(p.detaches[0].hadDetachedMark)
    }

    /**
     * '토큰꼴인데 사전에 없다'는 **편입되는 것만** 센다.
     *
     * 종전에는 자리 해석 전에 세어, `_삭제승인/`에 든 미지 토큰 파일까지 "새 이미지로
     * 편입합니다"라고 예고했다 — 그 자리는 편입도 삭제도 하지 않는다(거짓 고지).
     */
    @Test fun unknownTokenCountFollowsDisposition() {
        val ghost = "0123456789ab"
        val inDelete = ScanItem("d1", listOf(FolderRoundtripPlanner.FOLDER_DELETE_APPROVAL), "라벨-01.$ghost.jpg")
        val p = plan(listOf(inDelete))
        assertEquals(0, p.unknownTokenFiles)
        assertEquals(1, p.deleteApprovalUnknown)
        assertTrue(p.imports.isEmpty() && p.deletes.isEmpty())

        // 편입되는 자리에서는 그대로 센다.
        val atRoot = ScanItem("r1", emptyList(), "라벨-01.$ghost.jpg")
        val q = plan(listOf(atRoot))
        assertEquals(1, q.unknownTokenFiles)
        assertEquals(1, q.imports.size)
    }

    // ── 확정 무동작(settled) — 셋째 처분 ──

    /**
     * 이미 제자리인 파일은 **확정 무동작**으로 잡힌다 — 그래야 `_처리됨/`으로 옮겨져
     * 진입 배너가 같은 파일을 영원히 "새 이미지"라 말하지 않는다.
     */
    @Test fun alreadyInPlaceFilesAreSettled() {
        // ⓐ 이미 그 캐릭터에만 배정된 캐릭터 폴더 파일
        val a = plan(listOf(tokenFile("f1", tokenA, "가온")),
            names = mapOf("가온" to listOf(7L)), owners = mapOf(pathA to listOf(7L)))
        assertEquals(listOf("f1"), a.settled.map { it.id })
        assertEquals(0, a.actionCount)

        // ⓑ 되돌리는 자리의 이미 되돌아온 파일
        val b = plan(listOf(tokenFile("f2", tokenA, FolderRoundtripPlanner.FOLDER_UNASSIGNED)))
        assertEquals(listOf("f2"), b.settled.map { it.id })

        // ⓒ `_분리됨/`의 이미 뗀 파일
        val c = plan(listOf(tokenFile("f3", tokenA, FolderRoundtripPlanner.FOLDER_DETACHED)))
        assertEquals(listOf("f3"), c.settled.map { it.id })

        // ⓓ 서랍의 이미 낱개인 파일
        val d = plan(listOf(tokenFile("f4", tokenA, FolderRoundtripPlanner.FOLDER_MISC)))
        assertEquals(listOf("f4"), d.settled.map { it.id })
    }

    /**
     * **정족수를 못 채운 세트에 남은 토큰 파일도 확정 무동작이다** (2026.08.22).
     *
     * 종전에는 이 갈래가 `actions`에도 `settled`에도 없어, 실행부가 `_처리됨/`으로 옮기지
     * 못하고 지문도 안 찍혔다. 그래서 이미지 탭 배너가 **매번** *"정리 폴더에 새 이미지 1장"*
     * 이라 말했다 — 몇 번을 받아와도 화면이 똑같은, 끊을 수 없는 자리였다.
     * 내보낸 사본 한 장을 새 폴더로 옮기거나 내보낸 세트에서 한 장만 빼내면 이 상태가 된다.
     */
    @Test fun underQuorumSetMembersAreSettled() {
        // '그 외 이름' 폴더에 토큰 파일이 한 장뿐 — 세트(2장)가 성립하지 않는다.
        val p = plan(listOf(tokenFile("f1", tokenA, "풍경")))
        assertTrue("세트가 서면 안 된다", p.linkSets.isEmpty())
        assertEquals(0, p.actionCount)
        assertEquals(listOf("f1"), p.settled.map { it.id })
    }

    /** 두 장이면 종전대로 세트가 서고, 그때는 확정 무동작이 아니다(할 일이 있다). */
    @Test fun quorumSetStillFormsAndIsNotSettled() {
        val p = plan(listOf(
            tokenFile("f1", tokenA, "풍경"),
            tokenFile("f2", tokenB, "풍경")
        ))
        assertEquals(1, p.linkSets.size)
        assertTrue(p.settled.isEmpty())
    }

    /** 할 일이 있으면 확정 무동작이 아니다 — 둘을 겸하면 같은 파일을 두 번 처분한다. */
    @Test fun filesWithWorkAreNotSettled() {
        val p = plan(listOf(tokenFile("f1", tokenA, "가온")),
            names = mapOf("가온" to listOf(7L)), owners = mapOf(pathA to listOf(9L)))
        assertEquals(1, p.moves.size)
        assertTrue(p.settled.isEmpty())
    }

    /** 맥락으로 들어온 파일은 확정 무동작이 아니다 — 이번에 확정된 것이 아니다. */
    @Test fun handledContextFilesAreNotSettled() {
        val p = plan(listOf(handledTokenFile("h1", tokenA, FolderRoundtripPlanner.FOLDER_UNASSIGNED)))
        assertTrue(p.settled.isEmpty())
    }

    /** 보류는 확정이 아니다 — 폴더에 남아 있는 것이 미처리의 표식이라는 규약이 거기 걸려 있다. */
    @Test fun heldFilesAreNotSettled() {
        val p = plan(listOf(tokenFile("f1", tokenA, FolderRoundtripPlanner.FOLDER_SHARED)))
        assertEquals(1, p.holds.size)
        assertTrue(p.settled.isEmpty())
    }

}
