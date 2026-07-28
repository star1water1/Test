package com.novelcharacter.app.share

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.copyWithLimit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 월드패키지(.ncworld) 가져오기 (S-5) — [WorldPackageExporter]의 수신 측.
 *
 * 원칙:
 * - **id는 재배선한다.** 패키지의 모든 id는 원 기기의 DB id다. 삽입 순서대로 old→new
 *   매핑을 만들어 참조를 다시 잇고, 매핑이 없는 참조는 조용히 넣지 않고 **세어 고지**한다.
 * - **code는 정체성이다(R-1).** 기존 엔티티와 code가 충돌하면 살아 있는 남을 가로채지 않고
 *   재발급한다([WorldPackageCodes]) — 재발급 건수도 고지한다.
 * - **원자적이다.** DB 삽입 전체가 한 트랜잭션이다 — 절반만 들어온 세계관은 복원이 아니라
 *   유실이다(R-9와 같은 취지). 트랜잭션 실패 시 미리 복사한 이미지 파일도 되돌린다.
 * - **UI 비의존.** 다이얼로그(충돌 3분기 등)는 호출부(ExcelImporter)가 담당한다.
 */
class WorldPackageImporter(context: Context) {

    private val appContext: Context = context.applicationContext
    private val app: NovelCharacterApp get() = appContext.applicationContext as NovelCharacterApp
    private val db get() = app.database
    private val gson = Gson()

    // ── 1단계: ZIP 읽기 (JSON 엔트리 파싱 + 이미지 추출) ──

    sealed class ReadResult {
        data class Ok(val contents: WorldPackageContents) : ReadResult()

        /** 파싱 실패 — 원인별 메시지는 호출부가 [WorldPackageParseResult]로 만든다. */
        data class Failed(val parse: WorldPackageParseResult) : ReadResult()

        /** JSON 엔트리 하나가 메모리 상한을 넘는다. */
        object TooLarge : ReadResult()

        /** 이미지 엔트리가 개수·총량 상한을 넘는다. */
        object ImagesTooLarge : ReadResult()
    }

    /**
     * ZIP에서 JSON 엔트리를 읽어 파싱하고 이미지 엔트리를 [extractDir]에 추출한다.
     * ZIP bomb 방어는 엔트리 헤더 선언이 아니라 실제 해제 바이트로 계수한다(엑셀 경로와 동일).
     */
    fun read(file: File, extractDir: File): ReadResult {
        val jsonEntries = HashMap<String, String>()
        var imageCount = 0
        var imageTotalBytes = 0L
        try {
            ZipFile(file).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    when {
                        name.endsWith(".json") && !name.contains('/') -> {
                            val bos = ByteArrayOutputStream()
                            val copied = zip.getInputStream(entry).use { input ->
                                copyWithLimit(input, bos, MAX_JSON_ENTRY_SIZE)
                            }
                            if (copied > MAX_JSON_ENTRY_SIZE) return ReadResult.TooLarge
                            jsonEntries[name] = bos.toString(Charsets.UTF_8.name())
                        }
                        name.startsWith(WorldPackageEntries.IMAGES_PREFIX) -> {
                            if (imageCount >= MAX_IMAGE_ENTRY_COUNT) return ReadResult.ImagesTooLarge
                            val target = File(extractDir, name)
                            if (!target.canonicalPath.startsWith(extractDir.canonicalPath + File.separator)) {
                                Log.w(TAG, "Skipping suspicious zip entry: $name")
                                continue
                            }
                            target.parentFile?.mkdirs()
                            val copied = zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    copyWithLimit(input, output, MAX_IMAGE_TOTAL_SIZE - imageTotalBytes)
                                }
                            }
                            imageTotalBytes += copied
                            if (imageTotalBytes > MAX_IMAGE_TOTAL_SIZE) return ReadResult.ImagesTooLarge
                            imageCount++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 판별은 통과했는데 본읽기가 실패 — "패키지가 아니다"가 아니라 "손상"이다
            Log.w(TAG, "World package zip read failed", e)
            return ReadResult.Failed(WorldPackageParseResult.Malformed("ZIP"))
        }
        return when (val parsed = WorldPackageParser.parse(jsonEntries)) {
            is WorldPackageParseResult.Success -> ReadResult.Ok(parsed.contents)
            else -> ReadResult.Failed(parsed)
        }
    }

    // ── 2단계: 충돌 판별 ──

    /**
     * @property target 덮어쓰기 대상 — code 일치 세계관, 없으면 유일한 동명 세계관.
     *   null이면(동명이 여럿) 덮어쓰기 대상이 모호하므로 UI는 그 선택지를 내리지 않는다.
     * @property byCode true면 정체성(code) 일치 — 같은 패키지를 다시 가져온 경우다.
     * @property nameMatches 동명 세계관 수 (byCode=false일 때만 의미)
     */
    data class Conflict(val target: Universe?, val byCode: Boolean, val nameMatches: Int)

    suspend fun findConflict(pkgUniverse: Universe): Conflict? {
        val existing = db.universeDao().getAllUniversesList()
        val code: String? = pkgUniverse.code
        val codeMatch = if (code.isNullOrBlank()) null else existing.firstOrNull { it.code == code }
        if (codeMatch != null) return Conflict(codeMatch, byCode = true, nameMatches = 0)
        val name: String? = pkgUniverse.name
        val nameMatches = existing.filter { it.name == name }
        return when {
            nameMatches.isEmpty() -> null
            nameMatches.size == 1 -> Conflict(nameMatches.first(), byCode = false, nameMatches = 1)
            else -> Conflict(null, byCode = false, nameMatches = nameMatches.size)
        }
    }

    // ── 3단계: 가져오기 ──

    enum class Mode { CLEAN, OVERWRITE, AS_NEW }

    data class Outcome(
        val universeName: String,
        val novels: Int,
        val characters: Int,
        val fieldDefinitions: Int,
        val fieldValues: Int,
        val eventFieldValues: Int,
        val tags: Int,
        val stateChanges: Int,
        val relationships: Int,
        val relationshipChanges: Int,
        val events: Int,
        val factions: Int,
        val factionMemberships: Int,
        val factionRelationships: Int,
        val libraryEntries: Int,
        val nameBankNew: Int,
        val nameBankLinked: Int,
        val restoredImages: Int,
        val reissuedCodes: Int,
        val warnings: List<String>
    )

    /**
     * @param overwriteTarget [Mode.OVERWRITE]일 때 휴지통으로 보내고 교체할 기존 세계관.
     * @param extractDir [read]가 이미지를 추출해 둔 디렉토리.
     */
    suspend fun import(
        contents: WorldPackageContents,
        mode: Mode,
        overwriteTarget: Universe?,
        extractDir: File
    ): Outcome {
        val warnings = mutableListOf<String>()
        val version = contents.manifest.schemaVersion
        if (version < 2) {
            warnings.add(appContext.getString(com.novelcharacter.app.R.string.world_package_legacy_v1))
        }
        if (version < 3) {
            warnings.add(appContext.getString(com.novelcharacter.app.R.string.world_package_legacy_v2, version))
        }
        for ((entry, n) in contents.droppedRows) {
            warnings.add("형식 이탈 행 제외: $entry ${n}건")
        }

        // 덮어쓰기: 기존 세계관을 휴지통 스냅샷과 함께 삭제 (deleteUniverse가 자체 트랜잭션 +
        // 작업 단위 스냅샷을 만든다 — R-9). 이후의 코드 적재는 삭제가 끝난 상태를 봐야
        // 지워진 코드가 '사용 중'으로 잡혀 불필요한 재발급이 나지 않는다.
        if (mode == Mode.OVERWRITE) {
            requireNotNull(overwriteTarget) { "OVERWRITE mode requires a target universe" }
            app.universeRepository.deleteUniverse(overwriteTarget)
        }

        // 기존 코드·이름 적재 (전량 조회는 엑셀 임포트와 같은 규모 계급)
        val existingUniverses = db.universeDao().getAllUniversesList()
        val uniReg = WorldPackageCodes.Registry(existingUniverses.map { it.code })
        val novelReg = WorldPackageCodes.Registry(db.novelDao().getAllNovelsList().map { it.code })
        val charReg = WorldPackageCodes.Registry(db.characterDao().getAllCharactersList().map { it.code })
        val eventReg = WorldPackageCodes.Registry(db.timelineDao().getAllEventsList().map { it.code })
        val scReg = WorldPackageCodes.Registry(db.characterStateChangeDao().getAllChangesList().map { it.code })
        val relReg = WorldPackageCodes.Registry(db.characterRelationshipDao().getAllRelationships().map { it.code })
        val relChangeReg = WorldPackageCodes.Registry(db.characterRelationshipChangeDao().getAllChanges().map { it.code })
        val factionReg = WorldPackageCodes.Registry(db.factionDao().getAllFactionsList().map { it.code })
        // 은행 전량을 한 번만 읽어 코드 사전을 만든다 — v1·v2 패키지는 원 기기 은행 전체가
        // 실려 있어, 행마다 점조회하면 트랜잭션 안에서 수천 쿼리가 된다(규모).
        val existingNameBank = db.nameBankDao().getAllNamesList()
        // 마이그레이션 잔재의 빈 code("")끼리 사전 키가 충돌하지 않도록 걸러낸다
        val existingNameBankByCode = existingNameBank
            .filter { it.code.isNotBlank() }
            .associateBy { it.code }
        val nameBankReg = WorldPackageCodes.Registry(existingNameBank.map { it.code })
        val entryReg = WorldPackageCodes.Registry(db.fieldValueEntryDao().getAllList().map { it.code })
        val registries = listOf(
            uniReg, novelReg, charReg, eventReg, scReg, relReg, relChangeReg, factionReg, nameBankReg, entryReg
        )

        val pkgName: String = contents.universe.name
        val finalName = if (mode == Mode.AS_NEW) {
            WorldPackageCodes.uniqueName(existingUniverses.map { it.name }.toSet(), pkgName)
        } else pkgName

        // 이미지 복사 (트랜잭션 밖 — 새 경로를 먼저 확정해야 행에 실을 수 있다).
        // 트랜잭션이 실패하면 복사본을 삭제해 파일도 되돌린다.
        val includesImages = contents.manifest.includesImages
        val copiedFiles = mutableListOf<File>()
        var restoredImages = 0
        var missingImages = 0
        var strippedImagePaths = 0

        fun restoreImagePaths(imagePathsJson: String?, entryPrefix: String): String {
            if (imagePathsJson.isNullOrBlank() || imagePathsJson == "[]") return "[]"
            val paths: List<String?> = try {
                gson.fromJson(imagePathsJson, Array<String>::class.java)?.toList() ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val result = mutableListOf<String>()
            paths.forEachIndexed { index, original ->
                if (original == null) return@forEachIndexed
                val entryFile = File(extractDir, "${WorldPackageEntries.IMAGES_PREFIX}$entryPrefix$index.jpg")
                when {
                    entryFile.exists() -> {
                        // 원 파일명의 접두사·확장자 보존 — 앱관리 이미지 분류(universe_/novel_/img_)가
                        // 복원 후에도 유지된다 (엑셀 경로의 G3 교훈: "char_ 고정 하드코딩" 금지)
                        val origName = File(original).name
                        val prefix = com.novelcharacter.app.util.StorageAnalyzer.IMAGE_PREFIXES
                            .firstOrNull { origName.startsWith(it) } ?: "char_"
                        val ext = origName.substringAfterLast('.', "").ifBlank { "jpg" }
                        val dest = File(appContext.filesDir, "$prefix${UUID.randomUUID()}.$ext")
                        try {
                            entryFile.copyTo(dest)
                            copiedFiles.add(dest)
                            result.add(dest.absolutePath)
                            restoredImages++
                        } catch (e: Exception) {
                            Log.w(TAG, "Image restore failed for $entryPrefix$index", e)
                            missingImages++
                        }
                    }
                    // 같은 기기 재가져오기: 원 경로 파일이 그대로 있으면 재사용한다
                    // (공유 경로의 무음 파괴는 ImageOwnershipGuard가 막는다 — 엑셀 경로와 동일)
                    runCatching { File(original).exists() }.getOrDefault(false) -> result.add(original)
                    includesImages -> missingImages++
                    else -> strippedImagePaths++
                }
            }
            return gson.toJson(result)
        }

        val oldUniverse = contents.universe
        val universeImagePaths = restoreImagePaths(oldUniverse.imagePaths, "universe_")
        val novelImagePaths = contents.novels.associate { it.id to restoreImagePaths(it.imagePaths, "novel_${it.id}_") }
        val charImagePaths = contents.characters.associate { it.id to restoreImagePaths(it.imagePaths, "${it.id}_") }

        var importedUniverseId = 0L
        var danglingRefs = 0
        var crossUniverseRelsDropped = 0
        var detachedEvents = 0
        var duplicateDefsSkipped = 0
        var nameBankSkippedUnrelated = 0
        var nameBankLinkConflicts = 0
        var unresolvedFactionRels = 0
        var nameBankNew = 0
        var nameBankLinked = 0
        var insertedLibraryEntries = 0

        val outcome: Outcome
        try {
            outcome = db.withTransaction {
                // 1. 세계관 — 이미지 연동 참조(캐릭터/작품)는 대상이 아직 없으므로 비워 넣고
                //    마지막에 재배선한다 (FK SET_NULL 제약)
                val universeRow = oldUniverse.copy(
                    id = 0,
                    name = finalName,
                    code = uniReg.claim(oldUniverse.code),
                    displayOrder = db.universeDao().getNextDisplayOrder(),
                    imageCharacterId = null,
                    imageNovelId = null,
                    imagePaths = universeImagePaths
                )
                val newUniverseId = db.universeDao().insert(universeRow)
                importedUniverseId = newUniverseId

                // 2. 필드 정의 (전 entityType — v3). 유니크 (universeId, entityType, key) 방어적 중복 제거.
                val defIdMap = HashMap<Long, Long>()
                val seenDefKeys = HashSet<Pair<String, String>>()
                for (fd in contents.fieldDefinitions) {
                    if (!seenDefKeys.add(fd.entityType to fd.key)) {
                        duplicateDefsSkipped++
                        continue
                    }
                    defIdMap[fd.id] = db.fieldDefinitionDao().insert(fd.copy(id = 0, universeId = newUniverseId))
                }

                // 3. 작품 — imageCharacterId는 캐릭터 삽입 후 재배선
                val novelIdMap = HashMap<Long, Long>()
                val insertedNovelRows = HashMap<Long, com.novelcharacter.app.data.model.Novel>()
                for (novel in contents.novels) {
                    val row = novel.copy(
                        id = 0,
                        universeId = newUniverseId,
                        code = novelReg.claim(novel.code),
                        imageCharacterId = null,
                        imagePaths = novelImagePaths[novel.id] ?: "[]"
                    )
                    val newId = db.novelDao().insert(row)
                    novelIdMap[novel.id] = newId
                    insertedNovelRows[novel.id] = row.copy(id = newId)
                }

                // 4. 캐릭터
                val charIdMap = HashMap<Long, Long>()
                for (character in contents.characters) {
                    val mappedNovel = character.novelId?.let { old ->
                        novelIdMap[old].also { if (it == null) danglingRefs++ }
                    }
                    charIdMap[character.id] = db.characterDao().insert(
                        character.copy(
                            id = 0,
                            novelId = mappedNovel,
                            code = charReg.claim(character.code),
                            imagePaths = charImagePaths[character.id] ?: "[]"
                        )
                    )
                }

                // 5. 캐릭터 필드값
                val fieldValueRows = contents.fieldValues.mapNotNull { v ->
                    val cid = charIdMap[v.characterId]
                    val fid = defIdMap[v.fieldDefinitionId]
                    if (cid == null || fid == null) {
                        danglingRefs++
                        null
                    } else v.copy(id = 0, characterId = cid, fieldDefinitionId = fid)
                }
                fieldValueRows.chunked(CHUNK).forEach { db.characterFieldValueDao().insertAll(it) }

                // 6. 태그
                val tagRows = contents.tags.mapNotNull { t ->
                    val cid = charIdMap[t.characterId] ?: run { danglingRefs++; return@mapNotNull null }
                    t.copy(id = 0, characterId = cid)
                }
                tagRows.chunked(CHUNK).forEach { db.characterTagDao().insertAll(it) }

                // 7. 상태변화
                val stateChangeRows = contents.stateChanges.mapNotNull { sc ->
                    val cid = charIdMap[sc.characterId] ?: run { danglingRefs++; return@mapNotNull null }
                    sc.copy(id = 0, characterId = cid, code = claimNullable(scReg, sc.code))
                }
                stateChangeRows.chunked(CHUNK).forEach { db.characterStateChangeDao().insertAll(it) }

                // 8. 세력 — 관계 파일이 원 code를 갖고 있으므로 old code → new id 매핑을 함께 만든다
                val factionIdMap = HashMap<Long, Long>()
                val factionIdByOldCode = HashMap<String, Long>()
                val factionNameCount = HashMap<String, Int>()
                val factionIdByName = HashMap<String, Long>()
                for (faction in contents.factions) {
                    val newId = db.factionDao().insert(
                        faction.copy(id = 0, universeId = newUniverseId, code = factionReg.claim(faction.code))
                    )
                    factionIdMap[faction.id] = newId
                    val oldCode: String? = faction.code
                    if (!oldCode.isNullOrBlank()) factionIdByOldCode[oldCode] = newId
                    val fname: String? = faction.name
                    if (fname != null) {
                        factionNameCount[fname] = (factionNameCount[fname] ?: 0) + 1
                        factionIdByName[fname] = newId
                    }
                }
                // 이름 해석은 유일한 이름만 (중복 이름으로의 해석은 오배정 — R-1)
                val factionIdByUniqueName = factionIdByName.filterKeys { factionNameCount[it] == 1 }

                // 9. 세력 소속
                val membershipRows = contents.factionMemberships.mapNotNull { m ->
                    val fid = factionIdMap[m.factionId]
                    val cid = charIdMap[m.characterId]
                    if (fid == null || cid == null) {
                        danglingRefs++
                        null
                    } else m.copy(id = 0, factionId = fid, characterId = cid)
                }
                membershipRows.chunked(CHUNK).forEach { db.factionMembershipDao().insertAll(it) }

                // 10. 세력 간 관계 (code가 정하고 이름은 보조 — 순수 매퍼)
                val factionRelResult = WorldPackageFactionRelationships.fromPortable(
                    contents.factionRelationships, factionIdByOldCode, factionIdByUniqueName
                )
                unresolvedFactionRels = factionRelResult.unresolvedCount
                factionRelResult.relationships.chunked(CHUNK).forEach {
                    db.factionRelationshipDao().insertAll(it)
                }

                // 11. 사건 — 이 세계관 소속이던 사건만 새 세계관으로. 다른 세계관 소속이었던
                //     (작품 연계로만 실려 온) 사건은 소속 없이 들어오며 개수를 고지한다.
                val eventIdMap = HashMap<Long, Long>()
                for (event in contents.events) {
                    val mappedUniverseId = when (event.universeId) {
                        oldUniverse.id -> newUniverseId
                        null -> null
                        else -> {
                            detachedEvents++
                            null
                        }
                    }
                    eventIdMap[event.id] = db.timelineDao().insert(
                        event.copy(
                            id = 0,
                            universeId = mappedUniverseId,
                            code = claimNullable(eventReg, event.code)
                        )
                    )
                }

                // 12. 사건↔캐릭터 / 사건↔작품 연결
                for (cr in contents.crossRefs) {
                    val eid = eventIdMap[cr.eventId]
                    val cid = charIdMap[cr.characterId]
                    if (eid == null || cid == null) {
                        danglingRefs++
                        continue
                    }
                    db.timelineDao().insertCrossRef(cr.copy(eventId = eid, characterId = cid))
                }
                for (cr in contents.eventNovelCrossRefs) {
                    val eid = eventIdMap[cr.eventId]
                    val nid = novelIdMap[cr.novelId]
                    if (eid == null || nid == null) {
                        danglingRefs++
                        continue
                    }
                    db.timelineDao().insertEventNovelCrossRef(cr.copy(eventId = eid, novelId = nid))
                }

                // 13. 사건 필드값 (v3)
                val eventValueRows = contents.eventFieldValues.mapNotNull { v ->
                    val eid = eventIdMap[v.eventId]
                    val fid = defIdMap[v.fieldDefinitionId]
                    if (eid == null || fid == null) {
                        danglingRefs++
                        null
                    } else v.copy(id = 0, eventId = eid, fieldDefinitionId = fid)
                }
                eventValueRows.chunked(CHUNK).forEach { db.eventFieldValueDao().insertAll(it) }

                // 14. 관계 — 내보내기는 한쪽만 내보내는 집합에 걸친 관계도 실었을 수 있다.
                //     상대가 패키지에 없으면 삽입할 수 없으므로 버리되 개수를 고지한다.
                val relIdMap = HashMap<Long, Long>()
                for (rel in contents.relationships) {
                    val c1 = charIdMap[rel.characterId1]
                    val c2 = charIdMap[rel.characterId2]
                    if (c1 == null || c2 == null) {
                        crossUniverseRelsDropped++
                        continue
                    }
                    val newId = db.characterRelationshipDao().insert(
                        rel.copy(
                            id = 0,
                            characterId1 = c1,
                            characterId2 = c2,
                            factionId = rel.factionId?.let { factionIdMap[it] },
                            code = claimNullable(relReg, rel.code)
                        )
                    )
                    if (newId != -1L) relIdMap[rel.id] = newId
                }

                // 15. 관계 변화
                val relChangeRows = contents.relationshipChanges.mapNotNull { rc ->
                    val rid = relIdMap[rc.relationshipId] ?: run {
                        // 관계 자체가 위에서 버려졌으면 변화도 함께 버려진다 (같은 사유, 같은 고지)
                        crossUniverseRelsDropped++
                        return@mapNotNull null
                    }
                    rc.copy(
                        id = 0,
                        relationshipId = rid,
                        eventId = rc.eventId?.let { old -> eventIdMap[old].also { if (it == null) danglingRefs++ } },
                        code = claimNullable(relChangeReg, rc.code)
                    )
                }
                relChangeRows.chunked(CHUNK).forEach { db.characterRelationshipChangeDao().insertAll(it) }

                // 16. 값 라이브러리 (v3) — 큐레이션(라벨·별칭·카테고리) 보존.
                //     usageCount는 원 기기 값이며 최종 일관성 규약에 따라 이후 재계산된다.
                val entryRows = contents.fieldValueEntries.mapNotNull { entry ->
                    val fid = defIdMap[entry.fieldDefinitionId] ?: run { danglingRefs++; return@mapNotNull null }
                    entry.copy(id = 0, fieldDefinitionId = fid, code = entryReg.claim(entry.code))
                }
                entryRows.chunked(CHUNK).forEach { chunk ->
                    insertedLibraryEntries += db.fieldValueEntryDao().insertAllIgnore(chunk).count { it != -1L }
                }

                // 17. 이름 은행 — 전역 데이터. 패키지 캐릭터가 실제로 쓰는 이름만 들여온다
                //     (v1·v2 패키지는 원 기기의 은행 전체가 실려 있다 — 무관한 전역 데이터까지
                //     이식하면 수신자의 은행이 오염된다). code가 이미 있으면 중복 삽입하지 않고,
                //     사용 표시만 이 기기의 새 캐릭터로 잇는다. 남이 쓰는 중이면 빼앗지 않는다(B-3).
                for (nb in contents.nameBank) {
                    val newCharId = nb.usedByCharacterId?.let { charIdMap[it] }
                    if (newCharId == null) {
                        nameBankSkippedUnrelated++
                        continue
                    }
                    val code: String? = nb.code
                    val existing = if (code.isNullOrBlank()) null else existingNameBankByCode[code]
                    if (existing != null) {
                        if (!existing.isUsed) {
                            db.nameBankDao().update(
                                existing.copy(isUsed = true, usedByCharacterId = newCharId)
                            )
                            nameBankLinked++
                        } else {
                            nameBankLinkConflicts++
                        }
                    } else {
                        db.nameBankDao().insert(
                            nb.copy(
                                id = 0,
                                code = nameBankReg.claim(code),
                                isUsed = true,
                                usedByCharacterId = newCharId
                            )
                        )
                        nameBankNew++
                    }
                }

                // 18. 이미지 연동 참조 재배선 (세계관·작품의 select_character/select_novel 모드)
                val uniImageChar = oldUniverse.imageCharacterId?.let { charIdMap[it] }
                val uniImageNovel = oldUniverse.imageNovelId?.let { novelIdMap[it] }
                if (uniImageChar != null || uniImageNovel != null) {
                    db.universeDao().update(
                        universeRow.copy(
                            id = newUniverseId,
                            imageCharacterId = uniImageChar,
                            imageNovelId = uniImageNovel
                        )
                    )
                }
                for (novel in contents.novels) {
                    val oldRef = novel.imageCharacterId ?: continue
                    val newRef = charIdMap[oldRef] ?: continue
                    val row = insertedNovelRows[novel.id] ?: continue
                    db.novelDao().update(row.copy(imageCharacterId = newRef))
                }

                Outcome(
                    universeName = finalName,
                    novels = novelIdMap.size,
                    characters = charIdMap.size,
                    fieldDefinitions = defIdMap.size,
                    fieldValues = fieldValueRows.size,
                    eventFieldValues = eventValueRows.size,
                    tags = tagRows.size,
                    stateChanges = stateChangeRows.size,
                    relationships = relIdMap.size,
                    relationshipChanges = relChangeRows.size,
                    events = eventIdMap.size,
                    factions = factionIdMap.size,
                    factionMemberships = membershipRows.size,
                    factionRelationships = factionRelResult.relationships.size,
                    libraryEntries = insertedLibraryEntries,
                    nameBankNew = nameBankNew,
                    nameBankLinked = nameBankLinked,
                    restoredImages = restoredImages,
                    reissuedCodes = registries.sumOf { it.reissuedCount },
                    warnings = warnings
                )
            }
        } catch (e: Exception) {
            // 트랜잭션이 실패했으면 미리 복사한 이미지 파일도 되돌린다 — DB와 파일이 함께 원자적이어야 한다
            copiedFiles.forEach { runCatching { it.delete() } }
            throw e
        }

        if (missingImages > 0) warnings.add("이미지 ${missingImages}개를 패키지에서 찾지 못해 제외했습니다")
        if (strippedImagePaths > 0) warnings.add("이미지 미포함 패키지 — 이미지 연결 ${strippedImagePaths}개 제외")
        if (danglingRefs > 0) warnings.add("패키지 내부 참조 불일치로 제외된 항목 ${danglingRefs}건")
        if (crossUniverseRelsDropped > 0) warnings.add("패키지 밖 캐릭터와 얽힌 관계·관계변화 ${crossUniverseRelsDropped}건 제외")
        if (detachedEvents > 0) warnings.add("다른 세계관 소속이던 사건 ${detachedEvents}건은 세계관 연결 없이 들어왔습니다")
        if (duplicateDefsSkipped > 0) warnings.add("중복 필드 정의 ${duplicateDefsSkipped}건 제외")
        if (nameBankSkippedUnrelated > 0) warnings.add("패키지 캐릭터와 무관한 이름 은행 항목 ${nameBankSkippedUnrelated}건 제외")
        if (nameBankLinkConflicts > 0) warnings.add("이름 은행 사용 표시 ${nameBankLinkConflicts}건은 다른 캐릭터가 사용 중이라 잇지 않았습니다")
        if (unresolvedFactionRels > 0) warnings.add("세력을 찾지 못한 세력 간 관계 ${unresolvedFactionRels}건 제외")
        val totalReissued = outcome.reissuedCodes
        if (totalReissued > 0) warnings.add("기존 항목과 겹쳐 코드를 재발급한 항목 ${totalReissued}건")

        // 값 라이브러리 수확 — 들여온 값이 새 세계관 필드의 라이브러리에 등재되도록 (실패 무해 규약)
        app.fieldValueLibraryRepository.harvestUniverses(setOf(importedUniverseId))

        // 캐릭터 자동 링크 재동기화 — 패키지의 캐릭터·이미지 등록을 지금 묶는다(실패 무해 규약).
        // 패키지는 링크 meta를 싣지 않으므로 이 호출이 들여온 캐릭터의 유일한 링크 생성 경로다.
        runCatching { com.novelcharacter.app.util.CharacterImageAutoLinker.resyncIfEnabled(appContext, db) }

        app.operationLogRepository.logAsync(
            OpResult.success(
                OpResult.CAT_SHARE,
                "월드패키지 가져오기 — 세계관 '$finalName' (캐릭터 ${outcome.characters}명)",
                detail = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
            )
        )

        return outcome.copy(warnings = warnings.toList())
    }

    private fun claimNullable(registry: WorldPackageCodes.Registry, wanted: String?): String? =
        wanted?.takeIf { it.isNotBlank() }?.let { registry.claim(it) }

    companion object {
        private const val TAG = "WorldPackageImporter"
        private const val CHUNK = 500
        private const val MAX_JSON_ENTRY_SIZE = 128L * 1024 * 1024
        private const val MAX_IMAGE_ENTRY_COUNT = 50000
        private const val MAX_IMAGE_TOTAL_SIZE = 8L * 1024 * 1024 * 1024
    }
}
