package com.novelcharacter.app.util

import com.novelcharacter.app.util.FolderExportPlanner.BlockReason
import com.novelcharacter.app.util.FolderExportPlanner.CharacterInput
import com.novelcharacter.app.util.FolderExportPlanner.ExistingCopy
import com.novelcharacter.app.util.FolderExportPlanner.ImageInput
import com.novelcharacter.app.util.FolderExportPlanner.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 내보내기 계획 계약 — 설계 3장 폴더 규약의 **역방향**.
 *
 * 특히 지키는 것: **되돌아올 수 없는 이름은 내보내지 않는다**(C-14 — 동명·예약·불안전 이름),
 * **재내보내기 정리는 이번 배치의 사본만 건드린다**(C-15 — 남의 대기 작업을 지우지 않는다),
 * **자동 링크는 세트가 아니다**(캐릭터 폴더가 이미 같은 사실을 말한다).
 */
class FolderExportPlannerTest {

    private val uuidA = "3f9a2c40-7b16-4c1e-8a55-0d1e2f3a4b5c"
    private val uuidB = "88ee0011-2233-4455-6677-8899aabbccdd"
    private val uuidC = "12340000-5678-4444-9999-aaaabbbbcccc"
    private val pathA = "/files/char_$uuidA.jpg"
    private val pathB = "/files/img_$uuidB.png"
    private val pathC = "/files/img_$uuidC.jpg"
    private val tokenA = uuidA.replace("-", "").take(12)
    private val tokenB = uuidB.replace("-", "").take(12)
    private val tokenC = uuidC.replace("-", "").take(12)

    private val tokens = mapOf(pathA to tokenA, pathB to tokenB, pathC to tokenC)

    /** 캐릭터 1명 — code는 id에서 결정적으로 만든다(테스트가 폴더명을 예측할 수 있게). */
    private fun ch(id: Long, name: String) = CharacterInput(id, name, codeOf(id))
    private fun ch(id: Int, name: String) = ch(id.toLong(), name)
    private fun codeOf(id: Long) = id.toString().padStart(16, '0')

    private fun plan(
        images: List<ImageInput>,
        characters: List<CharacterInput> = emptyList(),
        scope: Scope = Scope.ALL,
        tokenByPath: Map<String, String> = tokens,
        existingPaths: Set<String>? = null
    ) = FolderExportPlanner.plan(images, characters, tokenByPath, scope, existingPaths)

    // ── 버킷 ──

    @Test fun assignedImageGoesToCharacterFolder() {
        val p = plan(
            listOf(ImageInput(pathA, ownerCharacterIds = listOf(7))),
            listOf(ch(7, "가온"))
        )
        assertEquals(1, p.files.size)
        assertEquals(listOf("가온"), p.files[0].folders)
        assertEquals("가온-01.$tokenA.jpg", p.files[0].fileName)
        assertEquals(1, p.characterFolders)
    }

    @Test fun sharedImageGoesToSharedFolderWithOwnerHintLabel() {
        val p = plan(
            listOf(ImageInput(pathA, ownerCharacterIds = listOf(7, 9))),
            listOf(ch(7, "가온"), ch(9, "나린"))
        )
        assertEquals(listOf("_공유"), p.files[0].folders)
        assertEquals(1, p.sharedCount)
        // 라벨은 사람용 힌트 — 소유자 중 한 명의 이름이 붙는다(정체성은 토큰이 진다).
        assertTrue(p.files[0].fileName.startsWith("가온-01."))
    }

    /** `_공유/`는 한 폴더 안에서도 주인이 다르다 — 폴더 대표 라벨을 쓰면 남의 이름이 붙는다. */
    @Test fun sharedFolderLabelsEachFileByItsOwnOwner() {
        val p = plan(
            listOf(
                ImageInput(pathA, ownerCharacterIds = listOf(7, 9)),
                ImageInput(pathB, ownerCharacterIds = listOf(9, 11))
            ),
            listOf(ch(7, "가온"), ch(9, "나린"), ch(11, "다올"))
        )
        val names = p.files.map { it.fileName }
        assertTrue(names.any { it.startsWith("가온-") })
        assertTrue(names.any { it.startsWith("나린-") })
    }

    @Test fun unassignedWithoutGroupGoesToUnassignedRoot() {
        val p = plan(listOf(ImageInput(pathB)))
        assertEquals(listOf("_미배정"), p.files[0].folders)
        assertEquals(1, p.unassignedCount)
        assertEquals("이미지-01.$tokenB.png", p.files[0].fileName)
    }

    @Test fun manualLinkGroupBecomesNumberedSetFolder() {
        val p = plan(
            listOf(
                ImageInput(pathA, linkGroupId = "manual-uuid"),
                ImageInput(pathB, linkGroupId = "manual-uuid"),
                ImageInput(pathC, linkGroupId = "other-uuid")
            )
        )
        assertEquals(2, p.setFolders)
        val byPath = p.files.associateBy { it.sourcePath }
        assertEquals(byPath.getValue(pathA).folders, byPath.getValue(pathB).folders)
        assertEquals(listOf("_미배정", "세트-1"), byPath.getValue(pathA).folders)
        assertEquals(listOf("_미배정", "세트-2"), byPath.getValue(pathC).folders)
    }

    /** 자동 링크는 배정에서 파생된 결과다 — 캐릭터 폴더가 이미 같은 사실을 말한다. */
    @Test fun autoLinkGroupIsNotASet() {
        val p = plan(listOf(ImageInput(pathA, linkGroupId = AutoLinkPlanner.autoToken(7))))
        assertEquals(listOf("_미배정"), p.files[0].folders)
        assertEquals(0, p.setFolders)
    }

    @Test fun numberingRestartsPerFolderAndIsDeterministic() {
        val images = listOf(
            ImageInput(pathC, ownerCharacterIds = listOf(7)),
            ImageInput(pathA, ownerCharacterIds = listOf(7)),
            ImageInput(pathB)
        )
        val characters = listOf(ch(7, "가온"))
        val first = plan(images, characters).files.map { it.relativePath }
        val second = plan(images.reversed(), characters).files.map { it.relativePath }
        assertEquals(first.sorted(), second.sorted())
        assertTrue(first.contains("가온/가온-01.$tokenA.jpg"))
        assertTrue(first.contains("가온/가온-02.$tokenC.jpg"))
        assertTrue(first.contains("_미배정/이미지-01.$tokenB.png"))
    }

    // ── 되돌아올 수 없는 이름은 내보내지 않는다 (C-14) ──

    @Test fun duplicateCharacterNamesAreBlockedAndReported() {
        val p = plan(
            listOf(
                ImageInput(pathA, ownerCharacterIds = listOf(7)),
                ImageInput(pathB, ownerCharacterIds = listOf(9))
            ),
            listOf(ch(7, "가온"), ch(9, "가온"))
        )
        // **동명은 더 이상 막지 않는다** — 폴더명에 코드를 병기해 되돌아올 수 있게 만든다.
        // 종전에는 둘 다 제외돼 그 캐릭터의 이미지가 정리 폴더로 나가지도 못했다.
        assertEquals(2, p.files.size)
        assertEquals(0, p.blockedCharacters.size)
        assertEquals(0, p.skippedTotal)
        val folders = p.files.map { it.folders }.toSet()
        assertEquals(
            setOf(listOf("가온#${codeOf(7)}"), listOf("가온#${codeOf(9)}")),
            folders
        )
        // 라벨은 사람용이라 코드가 붙지 않는다 — 파일명은 읽을 수 있어야 한다.
        assertTrue(p.files.all { it.fileName.startsWith("가온-") })
    }

    @Test fun uniqueNameKeepsPlainFolder() {
        val p = plan(
            listOf(ImageInput(pathA, ownerCharacterIds = listOf(7))),
            listOf(ch(7, "가온"), ch(9, "나린"))
        )
        // 이름이 유일하면 코드를 붙이지 않는다 — 대부분의 폴더는 깨끗하게 남아야 한다.
        assertEquals(listOf("가온"), p.files[0].folders)
    }

    @Test fun threeWayDuplicateSplitsIntoThreeFolders() {
        val p = plan(
            listOf(
                ImageInput(pathA, ownerCharacterIds = listOf(7)),
                ImageInput(pathB, ownerCharacterIds = listOf(9)),
                ImageInput(pathC, ownerCharacterIds = listOf(11))
            ),
            listOf(ch(7, "가온"), ch(9, "가온"), ch(11, "가온"))
        )
        // 코드가 유일하므로 N명이면 N개 폴더로 그냥 갈라진다 — 2명과 구조가 다르지 않다.
        assertEquals(3, p.files.map { it.folders }.toSet().size)
        assertEquals(0, p.blockedCharacters.size)
    }

    @Test fun duplicateOfReservedNameIsRescuedByCode() {
        val p = plan(
            listOf(
                ImageInput(pathA, ownerCharacterIds = listOf(7)),
                ImageInput(pathB, ownerCharacterIds = listOf(9))
            ),
            listOf(ch(7, "_공유"), ch(9, "_공유"))
        )
        // 코드가 붙으면 예약 폴더명과 겹칠 수 없다 — 동명이 오히려 구제 경로가 된다.
        assertEquals(2, p.files.size)
        assertEquals(0, p.blockedCharacters.size)
    }

    @Test fun duplicateGroupIsBlockedTogetherWhenTooLong() {
        val long = "다올".repeat(30) // 60자 + '#' + 코드 16자 = 77자 > 상한 64
        val p = plan(
            listOf(
                ImageInput(pathA, ownerCharacterIds = listOf(7)),
                ImageInput(pathB, ownerCharacterIds = listOf(9))
            ),
            listOf(ch(7, long), ch(9, long))
        )
        // 같은 이름이면 코드 길이도 같으므로 **통째로** 막힌다 — 한쪽만 나가는 비대칭이 없다.
        assertEquals(0, p.files.size)
        assertEquals(1, p.blockedCharacters.size)
        assertEquals(BlockReason.UNSAFE_NAME, p.blockedCharacters[0].reason)
        assertEquals(2, p.blockedCharacters[0].imageCount)
    }

    @Test fun reservedFolderNameIsBlocked() {
        val p = plan(
            listOf(ImageInput(pathA, ownerCharacterIds = listOf(7))),
            listOf(ch(7, "_공유"))
        )
        assertTrue(p.files.isEmpty())
        assertEquals(BlockReason.RESERVED_NAME, p.blockedCharacters[0].reason)
    }

    @Test fun unsafeFolderNameIsBlocked() {
        val p = plan(
            listOf(ImageInput(pathA, ownerCharacterIds = listOf(7)), ImageInput(pathB, ownerCharacterIds = listOf(9))),
            listOf(ch(7, "가온/나린"), ch(9, "다올".repeat(40)))
        )
        assertTrue(p.files.isEmpty())
        assertEquals(2, p.blockedCharacters.size)
        assertTrue(p.blockedCharacters.all { it.reason == BlockReason.UNSAFE_NAME })
    }

    /** 예약 접두(`_`)로 시작해도 예약 이름 자체가 아니면 받아오기가 캐릭터로 되돌린다. */
    @Test fun underscorePrefixedNameIsExportableWhenNotReserved() {
        val p = plan(
            listOf(ImageInput(pathA, ownerCharacterIds = listOf(7))),
            listOf(ch(7, "_가온"))
        )
        assertEquals(listOf("_가온"), p.files[0].folders)
        assertTrue(p.blockedCharacters.isEmpty())
    }

    /** 폴더명은 트림해 만든다 — 받아오기도 트림해 맞추므로 왕복이 성립한다. */
    @Test fun surroundingSpacesAreTrimmedNotBlocked() {
        val p = plan(
            listOf(ImageInput(pathA, ownerCharacterIds = listOf(7))),
            listOf(ch(7, "  가온  "))
        )
        assertEquals(listOf("가온"), p.files[0].folders)
        assertTrue(p.blockedCharacters.isEmpty())
    }

    // ── 범위·제외 ──

    @Test fun scopeFiltersByAssignment() {
        val images = listOf(
            ImageInput(pathA, ownerCharacterIds = listOf(7)),
            ImageInput(pathB)
        )
        val characters = listOf(ch(7, "가온"))
        assertEquals(2, plan(images, characters, Scope.ALL).files.size)
        assertEquals(listOf(pathA), plan(images, characters, Scope.ASSIGNED).files.map { it.sourcePath })
        assertEquals(listOf(pathB), plan(images, characters, Scope.UNASSIGNED).files.map { it.sourcePath })
    }

    @Test fun legacyFileNameIsSkippedAndCounted() {
        val p = plan(listOf(ImageInput(pathA)), tokenByPath = emptyMap())
        assertTrue(p.files.isEmpty())
        assertEquals(1, p.legacySkipped)
        assertEquals(1, p.skippedTotal)
    }

    /** 작품·세계관 전용(캐릭터 소유도 라이브러리 행도 없음)은 v1 범위 밖 — 개수만 고지한다. */
    @Test fun entityOnlyImageIsSkippedAndCounted() {
        val p = plan(listOf(ImageInput(pathA, inLibrary = false)))
        assertTrue(p.files.isEmpty())
        assertEquals(1, p.entityOnlySkipped)
    }

    @Test fun entityOwnedImageStillExportsWhenAssignedToCharacter() {
        val p = plan(
            listOf(ImageInput(pathA, ownerCharacterIds = listOf(7), inLibrary = false)),
            listOf(ch(7, "가온"))
        )
        assertEquals(1, p.files.size)
        assertEquals(0, p.entityOnlySkipped)
    }

    @Test fun missingFileIsSkippedAndCounted() {
        val p = plan(listOf(ImageInput(pathA), ImageInput(pathB)), existingPaths = setOf(pathB))
        assertEquals(listOf(pathB), p.files.map { it.sourcePath })
        assertEquals(1, p.missingSkipped)
    }

    @Test fun duplicateInputPathIsExportedOnce() {
        val p = plan(listOf(ImageInput(pathA), ImageInput(pathA)))
        assertEquals(1, p.files.size)
    }

    /** 사라진 캐릭터를 가리키는 배정은 미배정처럼 다룬다 — 유실보다 낫다. */
    @Test fun unknownOwnerFallsBackToUnassigned() {
        val p = plan(listOf(ImageInput(pathA, ownerCharacterIds = listOf(404))))
        assertEquals(listOf("_미배정"), p.files[0].folders)
    }

    @Test fun totalBytesSumsExportedFilesOnly() {
        val p = plan(
            listOf(ImageInput(pathA, sizeBytes = 100), ImageInput(pathB, sizeBytes = 50)),
            tokenByPath = mapOf(pathA to tokenA)
        )
        assertEquals(100L, p.totalBytes)
    }

    // ── 재내보내기 정리 (C-15) ──

    private fun exportOf(vararg images: ImageInput) = plan(images.toList()).files

    @Test fun cleanupTargetsPreviousCopiesOfThisBatchOnly() {
        val files = exportOf(ImageInput(pathA))
        val existing = listOf(
            ExistingCopy("doc-a", listOf("가온"), "가온-01.$tokenA.jpg"),
            // 이번 배치에 없는 토큰 — 사용자가 옮겨 두고 아직 받아오지 않은 작업일 수 있다.
            ExistingCopy("doc-b", listOf("나린"), "나린-01.$tokenB.png"),
            ExistingCopy("doc-plain", listOf("나린"), "사진.jpg")
        )
        val cleanup = FolderExportPlanner.planCleanup(existing, files, mapOf(tokenA to pathA, tokenB to pathB))
        assertEquals(listOf("doc-a"), cleanup.staleIds)
    }

    /** 위치가 달라진 사본 = 아직 받아오지 않은 배치 의도 — 지우기 전에 고지 대상이다. */
    @Test fun cleanupFlagsRearrangedCopiesSeparately() {
        val files = exportOf(ImageInput(pathA), ImageInput(pathB))
        val existing = listOf(
            ExistingCopy("moved", listOf("나린"), "가온-01.$tokenA.jpg"),
            ExistingCopy("inPlace", listOf("_미배정"), "이미지-01.$tokenB.png")
        )
        val cleanup = FolderExportPlanner.planCleanup(existing, files, mapOf(tokenA to pathA, tokenB to pathB))
        assertEquals(setOf("moved", "inPlace"), cleanup.staleIds.toSet())
        assertEquals(listOf("moved"), cleanup.rearrangedIds)
    }

    /** `_처리됨/`은 사용자의 처리 이력이고 받아오기가 스캔에서 빼므로 중복을 만들지 않는다. */
    @Test fun cleanupNeverTouchesProcessedFolder() {
        val files = exportOf(ImageInput(pathA))
        val existing = listOf(ExistingCopy("archived", listOf("_처리됨", "가온"), "가온-01.$tokenA.jpg"))
        val cleanup = FolderExportPlanner.planCleanup(existing, files, mapOf(tokenA to pathA))
        assertTrue(cleanup.isEmpty)
    }

    /** 재압축 개명으로 토큰 문자열이 달라도 같은 이미지다 — 비교는 경로까지 풀어서 한다. */
    @Test fun cleanupResolvesRenamedTokensThroughDictionary() {
        val files = exportOf(ImageInput(pathA))
        val oldToken = "aaaabbbbcccc"
        val existing = listOf(ExistingCopy("old", listOf("가온"), "가온-01.$oldToken.jpg"))
        val cleanup = FolderExportPlanner.planCleanup(
            existing, files, mapOf(tokenA to pathA, oldToken to pathA)
        )
        assertEquals(listOf("old"), cleanup.staleIds)
    }

    /**
     * 지우지 못한 사본의 이미지는 새로 쓰지 않아야 한다 — 그러려면 사본이 어느 이미지 것인지
     * 알아야 한다. 이 지도가 없으면 실행 계층이 충돌 위에 덮어써 토큰이 깨진다.
     */
    @Test fun cleanupMapsEachCopyToItsSourceImage() {
        val files = exportOf(ImageInput(pathA))
        val existing = listOf(ExistingCopy("doc-a", listOf("가온"), "가온-01.$tokenA.jpg"))
        val cleanup = FolderExportPlanner.planCleanup(existing, files, mapOf(tokenA to pathA))
        assertEquals(mapOf("doc-a" to pathA), cleanup.sourcePathById)
    }

    @Test fun cleanupIsEmptyWhenNothingIsExported() {
        val existing = listOf(ExistingCopy("doc-a", listOf("가온"), "가온-01.$tokenA.jpg"))
        assertTrue(FolderExportPlanner.planCleanup(existing, emptyList(), mapOf(tokenA to pathA)).isEmpty)
    }

    // ── 폴더명 안전 판정 ──

    @Test fun folderNameSafetyRules() {
        assertTrue(FolderNameToken.isFolderNameSafe("가온"))
        assertTrue(FolderNameToken.isFolderNameSafe("  가온 "))
        assertTrue(FolderNameToken.isFolderNameSafe("가온 2세"))
        assertFalse(FolderNameToken.isFolderNameSafe(""))
        assertFalse(FolderNameToken.isFolderNameSafe("   "))
        assertFalse(FolderNameToken.isFolderNameSafe("가온/나린"))
        assertFalse(FolderNameToken.isFolderNameSafe("가온?"))
        assertFalse(FolderNameToken.isFolderNameSafe(".가온"))
        assertFalse(FolderNameToken.isFolderNameSafe("가온."))
        assertFalse(FolderNameToken.isFolderNameSafe("\uac00\u0001\uc628"))  // 제어문자
        assertFalse(FolderNameToken.isFolderNameSafe("가".repeat(FolderNameToken.MAX_FOLDER_NAME_LENGTH + 1)))
    }

    // ── 왕복 ──

    /**
     * 내보낸 배치를 그대로 받아오면 **아무 일도 일어나지 않아야 한다** — 이미 그 자리가 맞다.
     * 이 성질이 깨지면 내보내기만 해도 배정이 흔들린다.
     */
    @Test fun exportedLayoutIsAFixedPointOfImport() {
        val images = listOf(
            ImageInput(pathA, ownerCharacterIds = listOf(7)),
            ImageInput(pathB),
            ImageInput(pathC, ownerCharacterIds = listOf(7, 9))
        )
        val characters = listOf(ch(7, "가온"), ch(9, "나린"))
        val exported = plan(images, characters).files

        val items = exported.map {
            FolderRoundtripPlanner.ScanItem(it.sourcePath, it.folders, it.fileName)
        }
        val back = FolderRoundtripPlanner.plan(
            items,
            characterIdsByName = mapOf("가온" to listOf(7L), "나린" to listOf(9L)),
            pathByToken = mapOf(tokenA to pathA, tokenB to pathB, tokenC to pathC),
            characterIdsByPath = mapOf(pathA to listOf(7L), pathC to listOf(7L, 9L))
        )
        assertTrue(back.imports.isEmpty())
        assertTrue(back.moves.isEmpty())
        assertTrue(back.detaches.isEmpty())
        assertTrue(back.linkSets.isEmpty())
        // 공유 이미지만 "위치 반영 제외"로 보류된다 — 파일은 폴더에 그대로 남는다.
        assertEquals(1, back.holds.size)
        assertEquals(FolderRoundtripPlanner.HoldReason.SHARED_FOLDER, back.holds[0].reason)
        assertEquals(0, back.deeperIgnored)
        assertEquals(0, back.unknownTokenFiles)
    }

    /**
     * **동명이인도 왕복 고정점이어야 한다** — 이 기능의 핵심 성질.
     *
     * 코드를 병기해 내보낸 두 `가온` 폴더를 그대로 받아오면 배정이 흔들리지 않아야 한다.
     * 여기가 깨지면 내보내기만 해도 두 동명 캐릭터의 이미지가 서로 뒤바뀔 수 있고, 그것이
     * 정확히 종전에 동명을 통째로 제외했던 이유다.
     */
    @Test fun duplicateNamesAreAlsoAFixedPointOfImport() {
        val images = listOf(
            ImageInput(pathA, ownerCharacterIds = listOf(7)),
            ImageInput(pathB, ownerCharacterIds = listOf(9))
        )
        val characters = listOf(ch(7, "가온"), ch(9, "가온"))
        val exported = plan(images, characters).files
        assertEquals(2, exported.size)

        val items = exported.map {
            FolderRoundtripPlanner.ScanItem(it.sourcePath, it.folders, it.fileName)
        }
        val resolver = FolderRoundtripPlanner.CharacterFolderResolver(
            characterIdsByName = mapOf("가온" to listOf(7L, 9L)),
            characterIdByCode = mapOf(codeOf(7) to 7L, codeOf(9) to 9L)
        )
        val back = FolderRoundtripPlanner.plan(
            items,
            characterIdsByName = mapOf("가온" to listOf(7L, 9L)),
            pathByToken = mapOf(tokenA to pathA, tokenB to pathB),
            characterIdsByPath = mapOf(pathA to listOf(7L), pathB to listOf(9L)),
            resolver = resolver
        )
        assertTrue("동명 왕복에서 이동이 생겼다 — 배정이 뒤바뀔 수 있다", back.moves.isEmpty())
        assertTrue(back.imports.isEmpty())
        assertTrue(back.detaches.isEmpty())
        assertTrue(back.linkSets.isEmpty())
        assertTrue(back.holds.isEmpty())
        // 코드로 확정됐으므로 물어볼 것이 없다.
        assertTrue(back.ambiguousFolders.isEmpty())
        assertTrue(back.unknownCodeFolders.isEmpty())
    }

    /**
     * 코드 없이 손으로 만든 동명 폴더는 **묻고, 답한 대로 배정한다.**
     * 답하기 전에는 종전과 같이 아무것도 배정하지 않는다.
     */
    @Test fun handMadeDuplicateFolderAsksThenAssigns() {
        val items = listOf(FolderRoundtripPlanner.ScanItem("doc1", listOf("가온"), "새사진.jpg"))
        val byName = mapOf("가온" to listOf(7L, 9L))

        val asked = FolderRoundtripPlanner.plan(items, byName, emptyMap(), emptyMap())
        assertEquals(listOf("가온"), asked.ambiguousFolders)
        // 배정은 보류하되 편입은 한다(부드러운 실패 — 링크 세트로 묶인다).
        assertEquals(1, asked.imports.size)
        assertEquals(null, asked.imports[0].assignCharacterId)

        val answered = FolderRoundtripPlanner.plan(
            items, byName, emptyMap(), emptyMap(),
            FolderRoundtripPlanner.CharacterFolderResolver(byName, choices = mapOf("가온" to 9L))
        )
        assertTrue(answered.ambiguousFolders.isEmpty())
        assertEquals(9L, answered.imports[0].assignCharacterId)
    }

    /** 세트 폴더도 왕복해야 한다 — 내보낸 묶음이 받아오기에서 같은 묶음으로 돌아온다. */
    @Test fun exportedSetFolderComesBackAsOneLinkSet() {
        val exported = plan(
            listOf(
                ImageInput(pathA, linkGroupId = "manual-uuid"),
                ImageInput(pathB, linkGroupId = "manual-uuid")
            )
        ).files
        val items = exported.map {
            FolderRoundtripPlanner.ScanItem(it.sourcePath, it.folders, it.fileName)
        }
        val back = FolderRoundtripPlanner.plan(
            items, emptyMap(), mapOf(tokenA to pathA, tokenB to pathB), emptyMap()
        )
        assertEquals(1, back.linkSets.size)
        assertEquals(2, back.linkSets[0].size)
        assertNotNull(back.linkSets[0].key)
        assertEquals(0, back.deeperIgnored)
    }

    // ══ 뗀 것 서랍 분화 (B-107 D4·D9) ═══════════════════════════════════════════════

    private val detachedDrawer = FolderRoundtripPlanner.FOLDER_DETACHED
    private val unassignedDrawer = FolderRoundtripPlanner.FOLDER_UNASSIGNED

    @Test fun detachedImageGoesToItsOwnDrawer() {
        val p = plan(listOf(ImageInput(pathB, isDetached = true)))
        assertEquals(listOf(detachedDrawer), p.files[0].folders)
        assertEquals(1, p.detachedCount)
        assertEquals(0, p.unassignedCount)
    }

    @Test fun neverDetachedImageStaysInUnassigned() {
        val p = plan(listOf(ImageInput(pathB)))
        assertEquals(listOf(unassignedDrawer), p.files[0].folders)
        assertEquals(1, p.unassignedCount)
        assertEquals(0, p.detachedCount)
    }

    /**
     * 배정이 살아 있으면 서랍 판정을 하지 않는다 — 그 이미지는 캐릭터 폴더로 간다.
     * (뗀 표식은 캐릭터 축의 **이력**이라 다시 붙은 뒤에도 남아 있을 수 있다.)
     */
    @Test fun assignedImageIgnoresDetachedMark() {
        val p = plan(
            listOf(ImageInput(pathA, ownerCharacterIds = listOf(7), isDetached = true)),
            listOf(ch(7, "가온"))
        )
        assertEquals(listOf("가온"), p.files[0].folders)
        assertEquals(0, p.detachedCount)
    }

    /** 세트 규칙은 두 서랍이 **완전히 대칭**이다(스캔 깊이도 그대로 2). */
    @Test fun detachedDrawerGetsSetSubfoldersToo() {
        val p = plan(
            listOf(
                ImageInput(pathB, linkGroupId = "grp-1", isDetached = true),
                ImageInput(pathC, linkGroupId = "grp-1", isDetached = true)
            )
        )
        assertTrue(p.files.all { it.folders.size == 2 && it.folders[0] == detachedDrawer })
        assertEquals(1, p.setFolders)
        // 세트 폴더는 서랍 직속 계수에 들어가지 않는다(단위가 다르다).
        assertEquals(0, p.detachedCount)
    }

    // ── 범위 ──

    @Test fun scopeDetached_exportsOnlyTheDrawer() {
        val p = plan(
            listOf(
                ImageInput(pathA, ownerCharacterIds = listOf(7)),
                ImageInput(pathB),
                ImageInput(pathC, isDetached = true)
            ),
            listOf(ch(7, "가온")),
            scope = Scope.DETACHED
        )
        assertEquals(listOf(pathC), p.files.map { it.sourcePath })
    }

    /** 인앱 칩과 같은 배타 규약 — 한쪽에서 갈라 놓고 다른 쪽에서 섞으면 요청이 반만 지켜진다. */
    @Test fun scopeUnassigned_excludesDetached() {
        val p = plan(
            listOf(ImageInput(pathB), ImageInput(pathC, isDetached = true)),
            scope = Scope.UNASSIGNED
        )
        assertEquals(listOf(pathB), p.files.map { it.sourcePath })
    }

    @Test fun scopeAll_stillIncludesBothDrawers() {
        val p = plan(listOf(ImageInput(pathB), ImageInput(pathC, isDetached = true)))
        assertEquals(2, p.files.size)
        assertEquals(1, p.unassignedCount)
        assertEquals(1, p.detachedCount)
    }

    // ── 예약 이름 (D9) ──

    /**
     * **막는 쪽과 읽는 쪽이 같은 목록을 봐야 한다.** `_기타`가 빠져 있어서, 이름이 `_기타`인
     * 캐릭터는 내보내기가 막지 않는데 받아오기는 서랍으로 읽어 이미지가 통째로 서랍행이 됐다.
     */
    @Test fun reservedNames_coverEveryFolderImportReadsAsReserved() {
        val reserved = listOf(
            FolderRoundtripPlanner.FOLDER_PROCESSED,
            FolderRoundtripPlanner.FOLDER_UNASSIGNED,
            FolderRoundtripPlanner.FOLDER_SHARED,
            FolderRoundtripPlanner.FOLDER_MISC,
            FolderRoundtripPlanner.FOLDER_DETACHED,
            FolderRoundtripPlanner.FOLDER_DELETE_APPROVAL
        )
        for ((index, name) in reserved.withIndex()) {
            val id = (index + 1).toLong()
            val p = plan(
                listOf(ImageInput(pathA, ownerCharacterIds = listOf(id))),
                listOf(ch(id, name))
            )
            assertEquals(
                "예약 이름 '$name'을 캐릭터 폴더로 내보내면 받아오기가 그 폴더를 예약으로 읽는다",
                listOf(BlockReason.RESERVED_NAME),
                p.blockedCharacters.map { it.reason }
            )
        }
    }
}
