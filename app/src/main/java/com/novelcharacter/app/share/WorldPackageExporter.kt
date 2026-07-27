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

/**
 * schemaVersion 이력:
 * - 1: 최초 형식
 * - 2: faction_relationships.json 추가 (B-6 — 세력 간 관계, code 참조).
 *   임포터는 v1 패키지에서 "세력 간 관계 없음"을 구버전 형식으로 안내할 수 있다.
 */
data class WorldPackageManifest(
    val schemaVersion: Int = 2,
    val appVersion: String = "1.0",
    val createdAt: Long = System.currentTimeMillis(),
    val universeName: String,
    val includesImages: Boolean
)

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

        val fieldDefinitions = app.universeRepository.getFieldsByUniverseList(config.universeId)
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
        val nameBank = db.nameBankDao().getAllNamesList()
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
                writeJsonEntry(zip, "manifest.json", manifest)
                writeJsonEntry(zip, "universe.json", universe)
                writeJsonEntry(zip, "field_definitions.json", fieldDefinitions)
                writeJsonEntry(zip, "novels.json", novels)
                writeJsonEntry(zip, "characters.json", characters)
                writeJsonEntry(zip, "field_values.json", fieldValues)
                writeJsonEntry(zip, "state_changes.json", stateChanges)
                writeJsonEntry(zip, "tags.json", tags)
                writeJsonEntry(zip, "relationships.json", relationships)
                writeJsonEntry(zip, "relationship_changes.json", relChanges)
                writeJsonEntry(zip, "timeline_events.json", events)
                writeJsonEntry(zip, "timeline_cross_refs.json", crossRefs)
                writeJsonEntry(zip, "timeline_event_novel_cross_refs.json", eventNovelCrossRefs)
                writeJsonEntry(zip, "name_bank.json", nameBank)
                writeJsonEntry(zip, "factions.json", factions)
                writeJsonEntry(zip, "faction_memberships.json", factionMemberships)
                writeJsonEntry(zip, "faction_relationships.json", factionRelResult.items)

                // Images
                if (config.includeImages) {
                    val appDir = context.filesDir
                    for (char in characters) {
                        if (char.imagePaths.isBlank() || char.imagePaths == "[]") continue
                        try {
                            val paths = gson.fromJson(char.imagePaths, Array<String>::class.java)
                            paths?.forEachIndexed { index, path ->
                                val imageFile = File(path)
                                if (imageFile.exists() && imageFile.canonicalPath.startsWith(appDir.canonicalPath + File.separator)) {
                                    zip.putNextEntry(ZipEntry("images/${char.id}_$index.jpg"))
                                    imageFile.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("WorldPackageExporter", "Failed to add image for character ${char.id}", e)
                        }
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
}
