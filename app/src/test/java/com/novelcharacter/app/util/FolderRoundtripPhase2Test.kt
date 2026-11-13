package com.novelcharacter.app.util

import com.novelcharacter.app.util.FolderExportPlanner.ImageInput
import com.novelcharacter.app.util.FolderExportPlanner.Scope
import com.novelcharacter.app.util.FolderRoundtripPlanner.Location
import com.novelcharacter.app.util.FolderRoundtripPlanner.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 폴더 왕복 2차 (B-53) — 내용으로 알아보기 · 파일명 태그 · 작품·세계관 사본. */
class FolderRoundtripPhase2Test {
    private val uuidA = "3f9a2c40-7b16-4c1e-8a55-0d1e2f3a4b5c"
    private val pathA = "/files/char_$uuidA.jpg"
    private val tokenA = uuidA.replace("-", "").take(12)
    private val entityPath = "/files/img_88ee0011-2233-4455-6677-8899aabbccdd.jpg"
    private val entityToken = "88ee00112233"

    private fun plan(items: List<ScanItem>, content: Map<String, String> = emptyMap(),
        names: Map<String, List<Long>> = emptyMap(), owners: Map<String, List<Long>> = emptyMap(),
        ambiguous: Set<String> = emptySet()) =
        FolderRoundtripPlanner.plan(items, names, mapOf(tokenA to pathA), owners,
            contentPathById = content, ambiguousContentIds = ambiguous)

    // ── ① 내용으로 알아보기 ──

    @Test fun renamedCopyMatchedByContentMovesInsteadOfBeingImportedAgain() {
        val renamed = ScanItem("f1", listOf("가온"), "휴가사진.jpg")
        val result = plan(listOf(renamed), mapOf("f1" to pathA),
            names = mapOf("가온" to listOf(7L)), owners = mapOf(pathA to listOf(3L)))
        assertTrue(result.imports.isEmpty())
        assertEquals(pathA, result.moves.single().path)
        assertEquals(1, result.contentMatched)
        assertEquals(0, result.unknownTokenFiles)
    }

    @Test fun tokenWinsAndContentIsOnlyAFallback() {
        val tokenFile = ScanItem("f1", emptyList(), "라벨-01.$tokenA.jpg")
        val result = plan(listOf(tokenFile), mapOf("f1" to "/files/other.jpg"), owners = mapOf(pathA to listOf(3L)))
        assertEquals(pathA, result.detaches.single().path)
        assertEquals(0, result.contentMatched)
    }

    @Test fun contentMatchAndTokenCopyOfTheSameImageAreBothHeld() {
        val tokenFile = ScanItem("f1", listOf("가온"), "라벨-01.$tokenA.jpg")
        val renamed = ScanItem("f2", listOf("나래"), "다른이름.jpg")
        val result = plan(listOf(tokenFile, renamed), mapOf("f2" to pathA),
            names = mapOf("가온" to listOf(7L), "나래" to listOf(8L)), owners = mapOf(pathA to listOf(3L)))
        assertEquals(2, result.holds.size)
        assertTrue(result.moves.isEmpty() && result.imports.isEmpty())
    }

    /** 같은 그림이 앱에 두 장이면 어느 쪽의 사본인지 모른다 — 고르지도 편입하지도 않고 남긴다. */
    @Test fun fileMatchingSeveralAppImagesIsHeldInsteadOfGuessedOrImported() {
        val result = plan(listOf(ScanItem("f1", listOf("가온"), "휴가사진.jpg")),
            names = mapOf("가온" to listOf(7L)), ambiguous = setOf("f1"))
        assertEquals(FolderRoundtripPlanner.HoldReason.AMBIGUOUS_CONTENT, result.holds.single().reason)
        assertTrue(result.imports.isEmpty() && result.moves.isEmpty())
        assertEquals(0, result.contentMatched)
        // 토큰이 가리키는 이미지가 있으면 내용의 모호함은 묻지 않는다.
        val tokenFile = ScanItem("f2", emptyList(), "라벨-01.$tokenA.jpg")
        assertTrue(plan(listOf(tokenFile), owners = mapOf(pathA to listOf(3L)), ambiguous = setOf("f2")).holds.isEmpty())
    }

    @Test fun unmatchedPlainFileIsStillANewImport() {
        val result = plan(listOf(ScanItem("f1", emptyList(), "새그림.jpg")))
        assertEquals(1, result.imports.size)
        assertEquals(0, result.contentMatched)
    }

    // ── ② 파일명 태그 ──

    @Test fun tagsComeAfterTheTitleInOrderWithoutBlanksOrRepeats() {
        assertEquals(listOf("바다", "여름 휴가"), FolderFileTags.parse("휴가#바다# 여름 휴가 ##바다.jpg"))
        assertEquals(listOf("초상"), FolderFileTags.parse("#초상.png"))
        assertEquals(listOf("a.b"), FolderFileTags.parse("x#a.b.jpg"))
    }

    @Test fun namesWithoutTagsAndExportedCopiesYieldNothing() {
        assertTrue(FolderFileTags.parse("휴가사진.jpg").isEmpty())
        assertTrue(FolderFileTags.parse("제목#.jpg").isEmpty())
        // 캐릭터 이름에 #이 들어 있어도 내보낸 사본은 태그를 얻지 않는다 — 토큰 자리가 있는 이름이다.
        assertTrue(FolderFileTags.parse("가#온-01.$tokenA.jpg").isEmpty())
        assertTrue(FolderFileTags.parse("가#온-01.${"0".repeat(32)}.jpg").isEmpty())
    }

    @Test fun planCountsImportsThatWillGetNameTags() {
        val result = plan(listOf(ScanItem("f1", emptyList(), "a#바다.jpg"), ScanItem("f2", emptyList(), "b.jpg")))
        assertEquals(2, result.imports.size)
        assertEquals(1, result.importsWithNameTags)
    }

    // ── ③ 작품·세계관 사본 ──

    @Test fun entityOnlyImagesGoOutAsCopiesOnlyInTheAllScope() {
        val image = ImageInput(entityPath, inLibrary = false, entityFolder = "작품-은하전기")
        val tokens = mapOf(entityPath to entityToken)
        val all = FolderExportPlanner.plan(listOf(image), emptyList(), tokens, Scope.ALL)
        assertEquals(listOf(FolderRoundtripPlanner.FOLDER_ENTITY, "작품-은하전기"), all.files.single().folders)
        assertEquals(1, all.entityCount)
        assertEquals(0, all.entityOnlySkipped)
        assertEquals(0, all.characterFolders)
        for (scope in listOf(Scope.ASSIGNED, Scope.UNASSIGNED, Scope.DETACHED)) {
            assertTrue(FolderExportPlanner.plan(listOf(image), emptyList(), tokens, scope).isEmpty)
        }
    }

    @Test fun entityImageWithoutAKnownOwnerIsStillOnlyCounted() {
        val result = FolderExportPlanner.plan(listOf(ImageInput(entityPath, inLibrary = false)),
            emptyList(), mapOf(entityPath to entityToken))
        assertTrue(result.files.isEmpty())
        assertEquals(1, result.entityOnlySkipped)
    }

    @Test fun importNeverReadsTheEntityFolderAtAnyDepth() {
        assertEquals(Location.Skipped, FolderRoundtripPlanner.classify(listOf(FolderRoundtripPlanner.FOLDER_ENTITY)))
        assertEquals(Location.Skipped,
            FolderRoundtripPlanner.classify(listOf(FolderRoundtripPlanner.FOLDER_ENTITY, "작품-은하전기")))
        assertEquals(Location.Skipped,
            FolderRoundtripPlanner.classify(listOf(FolderRoundtripPlanner.FOLDER_ENTITY, "a", "b")))
        val copy = ScanItem("f1", listOf(FolderRoundtripPlanner.FOLDER_ENTITY, "작품-은하전기"), "x-01.$tokenA.jpg")
        val stray = ScanItem("f2", listOf(FolderRoundtripPlanner.FOLDER_ENTITY), "새그림#태그.jpg")
        val result = plan(listOf(copy, stray), owners = mapOf(pathA to listOf(3L)))
        assertTrue(result.isEmpty)
        assertTrue(result.holds.isEmpty() && result.settled.isEmpty())
        assertEquals(0, result.deeperIgnored)
    }

    @Test fun aCharacterNamedLikeTheEntityFolderIsBlockedFromExport() {
        val image = ImageInput(pathA, ownerCharacterIds = listOf(1L))
        val result = FolderExportPlanner.plan(listOf(image),
            listOf(FolderExportPlanner.CharacterInput(1, FolderRoundtripPlanner.FOLDER_ENTITY, "0000000000000001")),
            mapOf(pathA to tokenA))
        assertEquals(FolderExportPlanner.BlockReason.RESERVED_NAME, result.blockedCharacters.single().reason)
    }
}
