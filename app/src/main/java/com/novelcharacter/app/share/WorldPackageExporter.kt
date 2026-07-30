package com.novelcharacter.app.share

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// WorldPackageManifest·엔트리 이름 상수·schemaVersion 이력은 WorldPackageContents.kt에 있다
// (파서·임포터와 공유하는 순수 계층 — 순수 JVM 하네스가 실행 검증한다).

class WorldPackageExporter(private val context: Context) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class ExportConfig(
        val universeId: Long,
        val novelIds: List<Long>? = null, // null = all novels
        val includeImages: Boolean = true,
        val compressImages: Boolean = false
    )

    /**
     * @property droppedFactionRelationships 내보내는 세계관 밖 세력에 걸쳐 있어 패키지에
     *   싣지 못한 세력 간 관계 수. 0이 아니면 호출부가 반드시 고지할 것(무통보 유실 금지).
     */
    data class ExportResult(
        val file: File,
        val droppedFactionRelationships: Int
    )

    suspend fun export(config: ExportConfig): ExportResult {
        val app = context.applicationContext as NovelCharacterApp
        val db = app.database

        // Load data
        val universe = app.universeRepository.getUniverseById(config.universeId)
            ?: throw IllegalArgumentException("Universe not found")

        val allNovels = app.novelRepository.getNovelsByUniverseList(config.universeId)
        val novels = if (config.novelIds != null) {
            allNovels.filter { it.id in config.novelIds }
        } else allNovels

        val novelIds = novels.map { it.id }.toSet()
        val allCharacters = app.characterRepository.getAllCharactersList()
        val characters = allCharacters.filter { it.novelId in novelIds }
        val characterIds = characters.map { it.id }.toSet()

        // v3: 사건 필드 정의까지 전 entityType을 싣는다 — 캐릭터 필드만 실으면
        // 사건 필드·값·라이브러리가 통째로 유실된다(세계관 삭제 스냅샷과 같은 규칙)
        val fieldDefinitions = db.fieldDefinitionDao().getFieldsByUniverseAllTypes(config.universeId)
        val fieldDefinitionIds = fieldDefinitions.map { it.id }
        val fieldValues = db.characterFieldValueDao().getAllValuesList()
            .filter { it.characterId in characterIds }
        val stateChanges = db.characterStateChangeDao().getAllChangesList()
            .filter { it.characterId in characterIds }
        val tags = db.characterTagDao().getAllTagsList()
            .filter { it.characterId in characterIds }
        val relationships = app.characterRepository.getAllRelationships()
            .filter { it.characterId1 in characterIds || it.characterId2 in characterIds }
        val relChanges = app.characterRepository.getAllRelationshipChanges()
            .filter { rc -> relationships.any { it.id == rc.relationshipId } }
        // 사건: 크로스레프 기반 필터링 (다대다)
        val allEventNovelCrossRefs = db.timelineDao().getAllEventNovelCrossRefs()
        val eventIdsWithNovels = allEventNovelCrossRefs
            .filter { it.novelId in novelIds }
            .map { it.eventId }
            .toSet()
        val events = app.timelineRepository.getAllEventsList()
            .filter { it.id in eventIdsWithNovels || it.universeId == config.universeId }
        val crossRefs = db.timelineDao().getAllCrossRefs()
            .filter { cr -> events.any { it.id == cr.eventId } }
        val eventNovelCrossRefs = allEventNovelCrossRefs
            .filter { cr -> events.any { it.id == cr.eventId } }
        val eventIds = events.map { it.id }.toSet()
        // v3: 사건 필드값 — 내보내는 사건의 값만
        val eventFieldValues = db.eventFieldValueDao().getAllValuesList()
            .filter { it.eventId in eventIds }
        // v4: 작품 필드값 — 내보내는 작품의 값만(확-3). 정의는 위 전 entityType 조회가 이미 담았다.
        val novelFieldValues = db.novelFieldValueDao().getAllValuesList()
            .filter { it.novelId in novelIds }
        // v3: 값 라이브러리 — 이 세계관 필드의 엔트리 전부(큐레이션 포함). IN 청크는 저장소 공통 관례.
        val fieldValueEntries = fieldDefinitionIds.chunked(900)
            .flatMap { db.fieldValueEntryDao().getForFields(it) }
        // v5: 등급 체계(U-1) — 필드 config가 code로 참조하므로 함께 싣지 않으면 수신 기기에서
        // 참조가 전부 허공을 가리킨다(정의는 실효 표로 동작하지만 체계 편집·공유가 사라진다).
        val gradeSystems = db.gradeSystemDao().getByUniverseList(config.universeId)
        // v3: 이름 은행은 내보내는 캐릭터가 사용 중인 이름만 — 전체 은행을 실으면 패키지 공유 시
        // 무관한 전역 데이터가 수신자에게 넘어간다(범위 밖 데이터는 패키지의 것이 아니다)
        val nameBank = db.nameBankDao().getAllNamesList()
            .filter { it.usedByCharacterId?.let(characterIds::contains) == true }
        val factions = db.factionDao().getAllFactionsList()
            .filter { it.universeId == config.universeId }
        val factionIds = factions.map { it.id }.toSet()
        val factionMemberships = db.factionMembershipDao().getAllMembershipsList()
            .filter { it.factionId in factionIds }
        // 세력 간 관계 (B-6) — 전량을 매퍼에 넘긴다: 양쪽 소속 판정(포함/한쪽 걸침/범위 밖)은
        // 매퍼가 하고, 한쪽 걸침은 개수로 돌려받아 고지한다
        val factionRelResult = WorldPackageFactionRelationships.toPortable(
            factions,
            db.factionRelationshipDao().getAllRelationshipsList()
        )

        // Create ZIP
        val fileName = "${universe.name.replace(Regex("[^\\w가-힣]"), "_")}.ncworld"
        val exportsDir = File(context.cacheDir, "exports")
        exportsDir.mkdirs()
        val outputFile = File(exportsDir, fileName)

        try {
            ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
                // Manifest
                val manifest = WorldPackageManifest(
                    universeName = universe.name,
                    includesImages = config.includeImages
                )
                writeJsonEntry(zip, WorldPackageEntries.MANIFEST, manifest)
                writeJsonEntry(zip, WorldPackageEntries.UNIVERSE, universe)
                writeJsonEntry(zip, WorldPackageEntries.FIELD_DEFINITIONS, fieldDefinitions)
                writeJsonEntry(zip, WorldPackageEntries.NOVELS, novels)
                writeJsonEntry(zip, WorldPackageEntries.CHARACTERS, characters)
                writeJsonEntry(zip, WorldPackageEntries.FIELD_VALUES, fieldValues)
                writeJsonEntry(zip, WorldPackageEntries.STATE_CHANGES, stateChanges)
                writeJsonEntry(zip, WorldPackageEntries.TAGS, tags)
                writeJsonEntry(zip, WorldPackageEntries.RELATIONSHIPS, relationships)
                writeJsonEntry(zip, WorldPackageEntries.RELATIONSHIP_CHANGES, relChanges)
                writeJsonEntry(zip, WorldPackageEntries.TIMELINE_EVENTS, events)
                writeJsonEntry(zip, WorldPackageEntries.TIMELINE_CROSS_REFS, crossRefs)
                writeJsonEntry(zip, WorldPackageEntries.TIMELINE_EVENT_NOVEL_CROSS_REFS, eventNovelCrossRefs)
                writeJsonEntry(zip, WorldPackageEntries.NAME_BANK, nameBank)
                writeJsonEntry(zip, WorldPackageEntries.FACTIONS, factions)
                writeJsonEntry(zip, WorldPackageEntries.FACTION_MEMBERSHIPS, factionMemberships)
                writeJsonEntry(zip, WorldPackageEntries.FACTION_RELATIONSHIPS, factionRelResult.items)
                writeJsonEntry(zip, WorldPackageEntries.EVENT_FIELD_VALUES, eventFieldValues)
                writeJsonEntry(zip, WorldPackageEntries.FIELD_VALUE_ENTRIES, fieldValueEntries)
                writeJsonEntry(zip, WorldPackageEntries.NOVEL_FIELD_VALUES, novelFieldValues)
                writeJsonEntry(zip, WorldPackageEntries.GRADE_SYSTEMS, gradeSystems)

                // Images
                if (config.includeImages) {
                    for (char in characters) {
                        writeImageEntries(zip, char.imagePaths, "images/${char.id}_")
                    }
                    // v3: 세계관·작품 직접 등록 이미지 — 엔트리 접두사는
                    // WorldPackageImageEntries 규약(임포터와 공유)을 따른다
                    writeImageEntries(zip, universe.imagePaths, "images/universe_")
                    for (novel in novels) {
                        writeImageEntries(zip, novel.imagePaths, "images/novel_${novel.id}_")
                    }
                }
            }
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        }

        return ExportResult(outputFile, factionRelResult.droppedCount)
    }

    private fun <T> writeJsonEntry(zip: ZipOutputStream, name: String, data: T) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(gson.toJson(data).toByteArray())
        zip.closeEntry()
    }

    /**
     * imagePaths JSON 배열의 i번째 파일을 `{entryPrefix}{i}.jpg` 엔트리로 싣는다.
     * 원본 파일이 없으면 그 인덱스만 건너뛴다(엔트리 결번) — 임포터는 결번을
     * "유실된 이미지"로 세어 고지한다. 확장자 표기는 v1 형식과의 호환을 위해
     * `.jpg`로 고정한다(내용 바이트는 원본 그대로 — 소비자는 내용으로 판별한다).
     */
    private fun writeImageEntries(zip: ZipOutputStream, imagePathsJson: String, entryPrefix: String) {
        if (imagePathsJson.isBlank() || imagePathsJson == "[]") return
        val appDir = context.filesDir
        try {
            val paths = gson.fromJson(imagePathsJson, Array<String>::class.java)
            paths?.forEachIndexed { index, path ->
                val imageFile = File(path)
                if (imageFile.exists() && imageFile.canonicalPath.startsWith(appDir.canonicalPath + File.separator)) {
                    zip.putNextEntry(ZipEntry("$entryPrefix$index.jpg"))
                    imageFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            Log.w("WorldPackageExporter", "Failed to add images for $entryPrefix", e)
        }
    }
}
