package com.novelcharacter.app.share

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.*
// 취소 예외는 엑셀 내보내기(D5)가 정의한 것을 그대로 쓴다 — "취소했다"가 두 가지 뜻을
// 갖지 않게 하려는 것이다. 두 작업의 취소 의미가 같다(산출물을 만들지 않고 멈춘다).
import com.novelcharacter.app.excel.ExportCancelledException
// 이미지 수록 집계는 엑셀 백업과 같은 벌을 쓴다(B-225) — 두 경로가 하는 일이 같다.
import com.novelcharacter.app.excel.ImageZipReport
import com.novelcharacter.app.util.SqlInChunks
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.novelcharacter.app.util.RegexCharClasses

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
     * @property droppedDuelMatches 참가자가 이번 패키지 밖이라 싣지 못한 대결 판 수
     *   (B-118 — 작품 일부만 고른 내보내기, 또는 참가자가 지워진 고아 판).
     * @property droppedDuelVerdicts 같은 이유로 싣지 못한 상성 판정 수.
     * @property images 이미지 수록 집계(B-225) — 싣지 못한 장을 **사유별로** 든다.
     *   0이 아니면 호출부가 반드시 고지할 것. 종전에는 이 축만 계수가 없어, 한 장의 I/O
     *   오류가 그 엔티티의 남은 장까지 무음으로 떨어뜨렸다(엑셀 백업은 같은 일을 3종으로
     *   고지하고 있었다 — 그 비대칭이 이 행의 본체다).
     */
    data class ExportResult(
        val file: File,
        val droppedFactionRelationships: Int,
        val droppedDuelMatches: Int,
        val droppedDuelVerdicts: Int,
        val images: ImageZipReport = ImageZipReport.NOT_REQUESTED
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

    /**
     * imagePaths JSON이 담은 경로들. 형식이 깨졌으면 빈 목록(내보내기를 막을 일은 아니다).
     * 이미지 축의 참가자가 곧 이 경로라, 대결을 거르는 범위도 여기서 나온다(B-118).
     */
    private fun imagePathsOf(imagePathsJson: String?): List<String> {
        if (imagePathsJson.isNullOrBlank() || imagePathsJson == "[]") return emptyList()
        return runCatching {
            gson.fromJson(imagePathsJson, Array<String>::class.java)
                ?.filterNotNull()
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * imagePaths JSON이 담은 경로 수. 형식이 깨졌으면 0(내보내기를 막을 일은 아니다).
     *
     * ⚠️ **[imagePathsOf]로 갈음하지 말 것.** 이 값은 진행도의 **총량**이고, 실제로 도는
     * [writeImageEntries]는 null·공란 자리도 한 장으로 세어 지나간다(결번은 임포터가
     * "유실된 이미지"로 고지한다). 걸러낸 수로 총량을 잡으면 막대가 총량을 넘는다 —
     * *도는 목록과 총량이 한 값에서 나온다*는 R-26의 규칙이 이 자리에 걸린다.
     */
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

        val novelIds = novels.map { it.id }

        // ── 범위 질의 (R-54 통로) ────────────────────────────────────────────────
        // 종전에는 아래 열넷이 전부 `getAll…List()`로 **테이블을 통째로 올린 뒤 메모리에서
        // 걸렀다.** 세계관 하나를 내보내면서 나머지 세계관의 행을 전부 읽는 모양이라, 비용이
        // *내보내는 것*이 아니라 **저장소 전체**에 붙어 있었다(목표 규모의 세계관은 270개다 —
        // 필드값 36,510행을 읽어 그 일부만 쓴다). 같은 함수에서 `fieldValueEntries` 한 자리만
        // 이미 [SqlInChunks]를 지나고 있었으니, **파일이 세운 규약을 나머지가 못 따라간 것**이다.
        //
        // 걸러 내던 외래키는 **전부 인덱스가 있어**(각 엔티티의 `indices`) 이 질의들은 색인
        // 조회다. 셋(`relChanges`·`crossRefs`·`eventNovelCrossRefs`)은 종전에 `.any { … }`로
        // 반대쪽 전체를 훑는 **곱**이었고, 관계·사건은 상한이 없는 축이라 그 곱에도 상한이 없었다.
        //
        // **순서는 [WorldPackageScope]의 정렬 계약이 세운다** — 질의에 맡기지 않는 이유는
        // [SqlInChunks]가 나눈 조각을 이어 붙이면 `ORDER BY`가 조각 경계에서 어긋나기 때문이다.
        // 계약은 마지막 키로 기본키를 두어 **전순서**이므로, 같은 세계관을 두 번 내보내면
        // 같은 파일이 나온다(종전에는 `ORDER BY`가 없던 다섯 절이 그 보장을 못 했다).
        val characters = SqlInChunks.flat(novelIds) { db.characterDao().getCharactersByNovelIds(it) }
            .sortedWith(WorldPackageScope.CHARACTERS)
        val characterIds = characters.map { it.id }

        // v3: 사건 필드 정의까지 전 entityType을 싣는다 — 캐릭터 필드만 실으면
        // 사건 필드·값·라이브러리가 통째로 유실된다(세계관 삭제 스냅샷과 같은 규칙)
        val fieldDefinitions = db.fieldDefinitionDao().getFieldsByUniverseAllTypes(config.universeId)
        val fieldDefinitionIds = fieldDefinitions.map { it.id }
        val fieldValues = SqlInChunks.flat(characterIds) {
            db.characterFieldValueDao().getValuesForCharacters(it)
        }.sortedWith(WorldPackageScope.CHARACTER_FIELD_VALUES)
        val stateChanges = SqlInChunks.flat(characterIds) {
            db.characterStateChangeDao().getChangesByCharacterIds(it)
        }.sortedWith(WorldPackageScope.STATE_CHANGES)
        val tags = SqlInChunks.flat(characterIds) {
            db.characterTagDao().getTagsForCharacters(it)
        }.sortedWith(WorldPackageScope.TAGS)
        // 명대사 (사용자 요청 2026.08.20) — 태그와 같은 통로다.
        val quotes = SqlInChunks.flat(characterIds) {
            db.characterQuoteDao().getQuotesForCharacters(it)
        }.sortedWith(WorldPackageScope.QUOTES)
        // 관계는 끝마다 따로 묻는다 — 한 질의에 두 목록을 실으면 바인드 변수가 갑절이 된다
        // (사유는 [WorldPackageScope.relationshipsInScope]). 겹은 그 함수가 없앤다.
        val relationships = WorldPackageScope.relationshipsInScope(
            SqlInChunks.flat(characterIds) { db.characterRelationshipDao().getRelationshipsByEnd1(it) },
            SqlInChunks.flat(characterIds) { db.characterRelationshipDao().getRelationshipsByEnd2(it) }
        )
        val relationshipIds = relationships.map { it.id }
        val relChanges = SqlInChunks.flat(relationshipIds) {
            db.characterRelationshipChangeDao().getChangesForRelationships(it)
        }.sortedWith(WorldPackageScope.RELATIONSHIP_CHANGES)

        // 사건: 크로스레프 기반 필터링 (다대다) — 작품에 걸린 것과 세계관에 속한 것의 합집합
        val eventIdsWithNovels = SqlInChunks.flat(novelIds) {
            db.timelineDao().getEventNovelCrossRefsByNovelIds(it)
        }.map { it.eventId }.distinct()
        val events = WorldPackageScope.eventsInScope(
            SqlInChunks.flat(eventIdsWithNovels) { db.timelineDao().getEventsByIds(it) },
            db.timelineDao().getEventsByUniverseList(config.universeId)
        )
        val eventIds = events.map { it.id }
        val crossRefs = SqlInChunks.flat(eventIds) {
            db.timelineDao().getCrossRefsByEventIds(it)
        }.sortedWith(WorldPackageScope.CROSS_REFS)
        // ⚠️ 작품 크로스레프는 **사건 축으로** 다시 묻는다 — 내보내는 사건이 *이번에 안 싣는
        // 작품*에도 걸려 있으면 그 줄도 패키지의 것이다(종전 구현이 그랬다). 위 `eventIdsWithNovels`
        // 질의(작품 축)로 갈음하면 그 줄들이 조용히 빠진다.
        val eventNovelCrossRefs = SqlInChunks.flat(eventIds) {
            db.timelineDao().getEventNovelCrossRefsByEventIds(it)
        }.sortedWith(WorldPackageScope.EVENT_NOVEL_CROSS_REFS)
        // v3: 사건 필드값 — 내보내는 사건의 값만
        val eventFieldValues = SqlInChunks.flat(eventIds) {
            db.eventFieldValueDao().getValuesByEvents(it)
        }.sortedWith(WorldPackageScope.EVENT_FIELD_VALUES)
        // v4: 작품 필드값 — 내보내는 작품의 값만(확-3). 정의는 위 전 entityType 조회가 이미 담았다.
        val novelFieldValues = SqlInChunks.flat(novelIds) {
            db.novelFieldValueDao().getValuesByNovels(it)
        }.sortedWith(WorldPackageScope.NOVEL_FIELD_VALUES)
        // v3: 값 라이브러리 — 이 세계관 필드의 엔트리 전부(큐레이션 포함).
        // IN 목록은 저장소 공통 통로([SqlInChunks] · R-54)를 지난다.
        val fieldValueEntries = SqlInChunks.flat(fieldDefinitionIds) {
            db.fieldValueEntryDao().getForFields(it)
        }
        // v5: 등급 체계(U-1) — 필드 config가 code로 참조하므로 함께 싣지 않으면 수신 기기에서
        // 참조가 전부 허공을 가리킨다(정의는 실효 표로 동작하지만 체계 편집·공유가 사라진다).
        val gradeSystems = db.gradeSystemDao().getByUniverseList(config.universeId)
        // v3: 이름 은행은 내보내는 캐릭터가 사용 중인 이름만 — 전체 은행을 실으면 패키지 공유 시
        // 무관한 전역 데이터가 수신자에게 넘어간다(범위 밖 데이터는 패키지의 것이 아니다)
        val nameBank = SqlInChunks.flat(characterIds) {
            db.nameBankDao().getNamesByUsedCharacterIds(it)
        }.sortedWith(WorldPackageScope.NAME_BANK)
        val factions = db.factionDao().getFactionsByUniverseList(config.universeId)
        val factionIds = factions.map { it.id }
        val factionMemberships = SqlInChunks.flat(factionIds) {
            db.factionMembershipDao().getMembershipsByFactionIds(it)
        }.sortedWith(WorldPackageScope.FACTION_MEMBERSHIPS)
        // 세력 간 관계 (B-6) — 전량을 매퍼에 넘긴다: 양쪽 소속 판정(포함/한쪽 걸침/범위 밖)은
        // 매퍼가 하고, 한쪽 걸침은 개수로 돌려받아 고지한다
        val factionRelResult = WorldPackageFactionRelationships.toPortable(
            factions,
            db.factionRelationshipDao().getAllRelationshipsList()
        )

        // v6: 대결 (B-118) — 축은 **세계관 단위**라 전부 싣고, 판·상성은 참가자가 이번
        // 패키지에 함께 가는 것만 싣는다. 거르는 규칙과 계수는 [WorldPackageDuels]가 단일
        // 소스다(순수 계층 — 참가자 재배선의 위험이 전부 거기 있고, 배선에 묻으면 시험이 없다).
        val duelAxes = db.duelAxisDao().getByUniverseList(config.universeId)
        val duelMatches = duelAxes.flatMap { db.duelMatchDao().getByAxis(it.id) }
        val duelVerdicts = duelAxes.flatMap { db.duelCounterVerdictDao().getByAxis(it.id) }
        // 이미지 축의 참가자는 **경로**다 — 실리는 캐릭터의 imagePaths가 그 범위다.
        val duelResult = WorldPackageDuels.toPortable(
            axes = duelAxes,
            matches = duelMatches,
            verdicts = duelVerdicts,
            characterCodesInScope = characters.map { it.code }.filter { it.isNotBlank() }.toSet(),
            imagePathsInScope = characters.flatMap { imagePathsOf(it.imagePaths) }.toSet()
        )

        // Create ZIP
        val fileName = "${universe.name.replace(Regex("[^${RegexCharClasses.FILENAME_KEEP}]"), "_")}.ncworld"
        val exportsDir = File(context.cacheDir, "exports")
        exportsDir.mkdirs()
        val outputFile = File(exportsDir, fileName)
        // 집계는 try 밖에 둔다 — 반환 시점에 읽어야 한다(ImageZipHelper의 failedPaths와 같은 이유).
        val imageTally = ImageTally()

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
                WorldPackageEntries.QUOTES to quotes,
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
                WorldPackageEntries.GRADE_SYSTEMS to gradeSystems,
                WorldPackageEntries.DUEL_AXES to duelResult.axes,
                WorldPackageEntries.DUEL_MATCHES to duelResult.matches,
                WorldPackageEntries.DUEL_VERDICTS to duelResult.verdicts
            )

            // 이미지 구간도 같은 원칙 — 도는 목록과 총량이 한 값에서 나온다.
            // v3: 세계관·작품 직접 등록 이미지도 같은 규약으로 싣는다(임포터와 공유).
            // 접두사는 **엔티티 몫만** 든다 — `images/`를 붙이는 자리는
            // [WorldPackageImages.entryName] 하나다(B-234. 종전에는 내보내기만 접두사에
            // 미리 붙여 들고 다녀 가져오기와 규약이 갈려 있었고, 이 주석은 그 시절
            // `WorldPackageImageEntries`라는 **없는 이름**을 가리키고 있었다).
            val imageGroups: List<Pair<String, String>> = buildList {
                for (char in characters) add(char.imagePaths to "${char.id}_")
                add(universe.imagePaths to "universe_")
                for (novel in novels) add(novel.imagePaths to "novel_${novel.id}_")
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
                        // 바깥 확인도 남긴다 — 목록이 비었거나 못 읽는 항목은 아래에서 곧바로
                        // 돌아오므로 장 단위 확인을 한 번도 지나지 않는다.
                        if (progress.isCancelled()) throw ExportCancelledException()
                        writeImageEntries(
                            zip, json, prefix, imageTally,
                            isCancelled = { progress.isCancelled() }
                        ) {
                            written++
                            progress.onImages(written, imageTotal)
                        }
                    }
                    zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                    // **이미지 구간 뒤라야 한다** — 어느 장이 잘렸는지는 다 써 봐야 안다.
                    // 실패가 없어도 싣는다: 있으면 "잘린 장은 이것뿐"이고 없으면 "모른다"라,
                    // 빈 목록이 *아는 것*과 *모르는 것*을 가른다. JSON이라 압축을 되돌린 뒤다.
                    writeJsonEntry(zip, WorldPackageEntries.IMAGE_FAILURES, imageTally.truncated)
                }
            }
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        }

        return ExportResult(
            file = outputFile,
            droppedFactionRelationships = factionRelResult.droppedCount,
            droppedDuelMatches = duelResult.droppedMatches,
            droppedDuelVerdicts = duelResult.droppedVerdicts,
            images = imageTally.toReport(requested = config.includeImages)
        )
    }

    private fun <T> writeJsonEntry(zip: ZipOutputStream, name: String, data: T) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(gson.toJson(data).toByteArray())
        zip.closeEntry()
    }

    /**
     * 이미지 수록 집계 누적기 — **사유를 아는 자리에서 센다**(R-39: 호출측이
     * `요청 수 - 실린 수`로 빼면 사유가 다시 한 숫자로 합쳐진다).
     *
     * 집계 벌은 엑셀 백업과 같은 [ImageZipReport]다 — 두 경로가 하는 일이 같으므로
     * 고지 어휘도 같아야 한다(B-225. 종전 비대칭: 엑셀은 손실 3종을 말하고 이쪽은 침묵했다).
     */
    private class ImageTally {
        var included = 0
        var missing = 0
        var outsideAppDir = 0
        var unreadableLists = 0
        private val samples = ArrayList<String>(ImageZipReport.SAMPLE_LIMIT)

        /**
         * 내용이 잘린 채 zip에 남은 엔트리들(B-234). **`failed`를 이 목록과 같은 자리에서만
         * 올리는 것이 이 벌의 요점이다** — 세는 것과 적는 것이 갈리면, 새 실패 갈래가
         * *세어지기만 하고 적히지 않아* 받는 기기가 다시 잘린 그림을 복원한다.
         * 그래서 [failed]는 private이고 올리는 길은 [fail] 하나다(검사가 아니라 구조로 막는다).
         */
        private val truncatedEntries = ArrayList<String>()
        private var failed = 0

        val truncated: List<String> get() = truncatedEntries

        /**
         * 한 장이 **쓰다가** 실패했다 — 엔트리는 이미 열려 지금까지 쓴 바이트로 닫힌다.
         * 그 이름을 함께 받는 것은 선택이 아니다(위 주석).
         */
        fun fail(entryName: String, path: String) {
            failed++
            truncatedEntries.add(entryName)
            sample(path)
        }

        fun sample(path: String) {
            if (samples.size < ImageZipReport.SAMPLE_LIMIT) samples.add(File(path).name)
        }

        fun toReport(requested: Boolean) = ImageZipReport(
            requested = requested,
            created = true,                       // 패키지 파일 자체는 언제나 만들어진다
            referencedCount = included + missing + outsideAppDir + failed,
            includedCount = included,
            missingCount = missing,
            outsideAppDirCount = outsideAppDir,
            failedCount = failed,
            unreadableRefCount = unreadableLists,
            // 복사해 넘긴다 — 벌이 data class라 넘긴 목록을 계속 들고 있고, 누적기를 그대로
            // 주면 결과가 나중에 바뀔 수 있는 모양이 된다(엑셀 쪽은 새 목록을 만들어 넘긴다).
            sampleNames = samples.toList()
        )
    }

    /**
     * imagePaths JSON 배열의 i번째 파일을 `{entryPrefix}{i}.jpg` 엔트리로 싣는다.
     * 원본 파일이 없으면 그 인덱스만 건너뛴다(엔트리 결번) — 임포터는 결번을
     * "유실된 이미지"로 세어 고지한다. 확장자 표기는 v1 형식과의 호환을 위해
     * `.jpg`로 고정한다(내용 바이트는 원본 그대로 — 소비자는 내용으로 판별한다).
     *
     * **실패는 장 단위로 가둔다(B-225).** 종전에는 `try`가 순회 **바깥**을 감싸고 있어서
     * 한 장의 I/O 오류가 **그 엔티티의 남은 장을 통째로** 결번으로 만들었다 — 그리고
     * 그 사실은 Logcat에만 남아 사용자에게도, 받는 기기에도 나타나지 않았다.
     * 이제 장마다 가두고 사유별로 세어 [tally]에 싣는다(감사 5장: *부분 실패가 전량 실패보다
     * 조용하면 안 된다*).
     */
    /**
     * [isCancelled]는 **장마다** 물어야 한다 (B-235). 종전에는 호출부의 바깥 루프(엔티티 단위)에만
     * 있어, 그림 수십 장을 가진 캐릭터 하나를 다 쓸 때까지 취소가 반영되지 않았다 — 취소 버튼이
     * 고장과 구분되지 않는다(R-17의 취지). 엑셀 쪽 `ImageZipHelper`는 처음부터 장마다 물었고,
     * **같은 일을 하는 두 경로가 갈려 있던 자리**다.
     *
     * 기본값을 주지 않는 것은 일부러다 — 새 호출부가 잊으면 취소가 *조용히* 성립하지 않는다.
     */
    private fun writeImageEntries(
        zip: ZipOutputStream,
        imagePathsJson: String,
        entryPrefix: String,
        tally: ImageTally,
        isCancelled: () -> Boolean,
        onEach: () -> Unit = {}
    ) {
        if (imagePathsJson.isBlank() || imagePathsJson == "[]") return
        val appDir = context.filesDir
        val paths = try {
            gson.fromJson(imagePathsJson, Array<String>::class.java)
        } catch (e: Exception) {
            // 목록을 못 읽으면 이 항목의 이미지는 **몇 장인지도 알 수 없다** — 그래서
            // 누락 '장'이 아니라 못 읽은 '항목'으로 센다(ImageZipReport.unreadableRefCount).
            Log.w("WorldPackageExporter", "Unreadable imagePaths for $entryPrefix", e)
            tally.unreadableLists++
            return
        }
        if (paths == null) { tally.unreadableLists++; return }
        paths.forEachIndexed { index, path ->
            // **확인은 각 장의 머리, try 밖이다.** 아래 `catch (e: Exception)`은 *읽기 실패 한 장*을
            // 삼켜 다음 장으로 넘어가는 것이 일이므로, 취소 확인을 그 안에 넣으면
            // [ExportCancelledException]이 실패 한 장으로 세어지고 내보내기가 그대로 계속된다.
            if (isCancelled()) throw ExportCancelledException()
            // 훑은 장을 센다 — 실제로 담긴 장이 아니라. 원본이 없는 인덱스는 결번으로
            // 건너뛰므로(아래) 담긴 수로 세면 막대가 총량에 못 미친 채 끝난다.
            onEach()
            // 빈 자리는 참조가 아니다 — 임포터도 같은 자리를 건너뛴다(restoreImagePaths).
            if (path.isNullOrBlank()) return@forEachIndexed
            val imageFile = File(path)
            // 기기 밖으로 나가는 자리다 — 판정은 [ImagePathMatch.isInside] (B-106 ⓐ · R-39).
            // 막힌 것과 못 읽은 것은 **처방이 달라** 갈라 센다(R-39: 파일 확인 / 앱에 들이기).
            when {
                !imageFile.exists() -> { tally.missing++; tally.sample(path) }
                !com.novelcharacter.app.util.ImagePathMatch.isInside(path, appDir) -> {
                    tally.outsideAppDir++; tally.sample(path)
                }
                else -> {
                    val entryName = WorldPackageImages.entryName(entryPrefix, index)
                    try {
                        // **엔트리를 여는 것은 원본을 연 뒤다.** 열기 실패가 이 부류의 대부분인데,
                        // 순서가 반대면 그 흔한 실패마다 0바이트 엔트리가 남고 받는 기기는 그것을
                        // 멀쩡한 이미지로 복원한다(결번이라야 "유실"로 세어 고지된다).
                        imageFile.inputStream().use { input ->
                            zip.putNextEntry(ZipEntry(entryName))
                            input.copyTo(zip)
                            zip.closeEntry()
                        }
                        tally.included++
                    } catch (e: Exception) {
                        // **여기까지 왔으면 엔트리가 이미 열려 있을 수 있다**(`copyTo`가 중간에
                        // 던진 갈래). 아래 `closeEntry()`는 지금까지 쓴 바이트로 그 엔트리를
                        // **정상 종료**하므로 zip은 온전하고 **내용만 잘린 장**이 남는다 —
                        // 결번이 아니라서 받는 기기가 유실로 세지 못한다. 그래서 이름을 함께
                        // 적어 `image_failures.json`으로 보낸다(B-234 · 확정 19장 5번).
                        Log.w("WorldPackageExporter", "Failed to add image $entryName", e)
                        tally.fail(entryName, path)
                        try { zip.closeEntry() } catch (_: Exception) { }
                    }
                }
            }
        }
    }
}
