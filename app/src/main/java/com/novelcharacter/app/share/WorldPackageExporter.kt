package com.novelcharacter.app.share

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.*
// 취소 예외는 엑셀 내보내기(D5)가 정의한 것을 그대로 쓴다 — "취소했다"가 두 가지 뜻을
// 갖지 않게 하려는 것이다. 두 작업의 취소 의미가 같다(산출물을 만들지 않고 멈춘다).
import com.novelcharacter.app.excel.ExportCancelledException
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
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

    /**
     * 월드패키지 내보내기의 진행 보고 창구(규약 R-26 · B-51 완결).
     *
     * 순회 구간이 **둘**이고 단위가 다르다 — 자료는 '절(entry)', 이미지는 '장'이다. 그래서
     * 하나로 합치지 않고 각자 총량으로 보고한다(D5의 [com.novelcharacter.app.excel.ExportProgressSink]와 같은 이유).
     *
     * 종전에는 불확정 스피너뿐이라 대형 세계관에서 "도는 중"과 "멈춤"이 구분되지 않았다.
     *
     * **취소는 산출물을 만들지 않고 멈추는 것이다** — 사용자가 받는 항목이 파일 하나이므로
     * 반쯤 쓴 패키지를 건네면 상대 기기에서 조용히 깨진다(D5가 내보내기에서 정한 것과 같다).
     */
    class ProgressSink(
        /** 자료 구간 — (기록한 절, 전체 절). */
        val onSections: (done: Int, total: Int) -> Unit = { _, _ -> },
        /** 이미지 구간 — (훑은 장, 전체 장). 이미지를 담지 않으면 불리지 않는다. */
        val onImages: (done: Int, total: Int) -> Unit = { _, _ -> },
        /** 취소 요청 여부 — 순회가 항목마다 확인한다. */
        val isCancelled: () -> Boolean = { false }
    )

    /** imagePaths JSON이 담은 경로 수. 형식이 깨졌으면 0(내보내기를 막을 일은 아니다). */
    private fun countImagePaths(imagePathsJson: String): Int {
        if (imagePathsJson.isBlank() || imagePathsJson == "[]") return 0
        return runCatching {
            gson.fromJson(imagePathsJson, Array<String>::class.java)?.size ?: 0
        }.getOrDefault(0)
    }

    suspend fun export(config: ExportConfig, progress: ProgressSink = ProgressSink()): ExportResult {
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
            val manifest = WorldPackageManifest(
                universeName = universe.name,
                includesImages = config.includeImages
            )

            // **이 목록이 절 순서의 단일 소스다**(규약 R-26 · B-51) — 실행이 이 목록을 돌고
            // 진행도 총량도 이 목록의 크기다. 종전처럼 실행은 호출 나열로 두고 총량만 따로 세면
            // 두 벌이 되어, 갈리는 순간 막대가 100%를 넘거나 못 미친 채 끝난다.
            val sections: List<Pair<String, Any>> = listOf(
                WorldPackageEntries.MANIFEST to manifest,
                WorldPackageEntries.UNIVERSE to universe,
                WorldPackageEntries.FIELD_DEFINITIONS to fieldDefinitions,
                WorldPackageEntries.NOVELS to novels,
                WorldPackageEntries.CHARACTERS to characters,
                WorldPackageEntries.FIELD_VALUES to fieldValues,
                WorldPackageEntries.STATE_CHANGES to stateChanges,
                WorldPackageEntries.TAGS to tags,
                WorldPackageEntries.RELATIONSHIPS to relationships,
                WorldPackageEntries.RELATIONSHIP_CHANGES to relChanges,
                WorldPackageEntries.TIMELINE_EVENTS to events,
                WorldPackageEntries.TIMELINE_CROSS_REFS to crossRefs,
                WorldPackageEntries.TIMELINE_EVENT_NOVEL_CROSS_REFS to eventNovelCrossRefs,
                WorldPackageEntries.NAME_BANK to nameBank,
                WorldPackageEntries.FACTIONS to factions,
                WorldPackageEntries.FACTION_MEMBERSHIPS to factionMemberships,
                WorldPackageEntries.FACTION_RELATIONSHIPS to factionRelResult.items,
                WorldPackageEntries.EVENT_FIELD_VALUES to eventFieldValues,
                WorldPackageEntries.FIELD_VALUE_ENTRIES to fieldValueEntries,
                WorldPackageEntries.NOVEL_FIELD_VALUES to novelFieldValues,
                WorldPackageEntries.GRADE_SYSTEMS to gradeSystems
            )

            // 이미지 구간도 같은 원칙 — 도는 목록과 총량이 한 값에서 나온다.
            // v3: 세계관·작품 직접 등록 이미지의 엔트리 접두사는 WorldPackageImageEntries
            // 규약(임포터와 공유)을 따른다.
            val imageGroups: List<Pair<String, String>> = buildList {
                for (char in characters) add(char.imagePaths to "images/${char.id}_")
                add(universe.imagePaths to "images/universe_")
                for (novel in novels) add(novel.imagePaths to "images/novel_${novel.id}_")
            }
            val imageTotal = if (config.includeImages) imageGroups.sumOf { countImagePaths(it.first) } else 0

            ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
                progress.onSections(0, sections.size)
                sections.forEachIndexed { index, (name, data) ->
                    if (progress.isCancelled()) throw ExportCancelledException()
                    writeJsonEntry(zip, name, data)
                    progress.onSections(index + 1, sections.size)
                }

                // Images
                if (config.includeImages) {
                    // 이미지 구간은 무압축(설계 D8 — ImageZipHelper와 짝. R-16).
                    // 이미 압축된 형식에 deflate를 걸어 얻는 것이 없으므로 CPU만 지불하게 된다.
                    // 위 JSON 엔트리들은 압축이 실효가 있어 기본 수준으로 이미 기록됐다.
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    var written = 0
                    progress.onImages(0, imageTotal)
                    for ((json, prefix) in imageGroups) {
                        if (progress.isCancelled()) throw ExportCancelledException()
                        writeImageEntries(zip, json, prefix) {
                            written++
                            progress.onImages(written, imageTotal)
                        }
                    }
                    zip.setLevel(Deflater.DEFAULT_COMPRESSION)
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
    private fun writeImageEntries(
        zip: ZipOutputStream,
        imagePathsJson: String,
        entryPrefix: String,
        onEach: () -> Unit = {}
    ) {
        if (imagePathsJson.isBlank() || imagePathsJson == "[]") return
        val appDir = context.filesDir
        try {
            val paths = gson.fromJson(imagePathsJson, Array<String>::class.java)
            paths?.forEachIndexed { index, path ->
                // 훑은 장을 센다 — 실제로 담긴 장이 아니라. 원본이 없는 인덱스는 결번으로
                // 건너뛰므로(아래) 담긴 수로 세면 막대가 총량에 못 미친 채 끝난다.
                onEach()
                val imageFile = File(path)
                // 기기 밖으로 나가는 자리다 — 판정은 [ImagePathMatch.isInside] (B-106 ⓐ · R-39).
                // 종전 한 줄이 던지면 바깥 catch가 **그 엔티티의 남은 장을 통째로 버렸다.**
                if (imageFile.exists() &&
                    com.novelcharacter.app.util.ImagePathMatch.isInside(path, appDir)) {
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
