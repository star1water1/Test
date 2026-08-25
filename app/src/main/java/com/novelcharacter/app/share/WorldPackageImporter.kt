package com.novelcharacter.app.share

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.DuelGradeRef
import com.novelcharacter.app.data.model.GradeSystemRef
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.util.ImagePathMatch
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.SqlInChunks
import com.novelcharacter.app.util.copyWithLimit
import com.novelcharacter.app.util.withImagePaths
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
/**
 * 복원된 이미지 목록과 **옛 경로 → 새 경로** 대응.
 * 대표 이미지 포인터(B-103)가 재매핑을 따라가려면 후자가 필요하다.
 */
private data class RestoredPaths(val json: String, val renames: Map<String, String>)

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
                            // zip-slip 방어 — 판정은 [ImagePathMatch.isInside] (B-106 ⓐ · R-39).
                            // 종전 한 줄은 `canonicalPath`가 던지면 **가져오기 전체가 죽었다**;
                            // 이제 그 항목만 막고 나머지는 이어 간다.
                            if (!ImagePathMatch.isInside(target.path, extractDir)) {
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
        val gradeSystems: Int,
        val fieldValues: Int,
        val eventFieldValues: Int,
        val novelFieldValues: Int,
        val tags: Int,
        val stateChanges: Int,
        val quotes: Int,
        val relationships: Int,
        val relationshipChanges: Int,
        val events: Int,
        val factions: Int,
        val factionMemberships: Int,
        val factionRelationships: Int,
        val duelAxes: Int,
        val duelMatches: Int,
        val duelVerdicts: Int,
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
        // B-118의 ⓐ(고지)가 여기서 값을 한다 — 축을 담게 된 뒤에도 **옛 패키지를 읽을 때**
        // 그 패키지에 대결이 없다는 것을 말해 줄 자리가 필요하다(확정 15번: *"ⓐ는 ⓑ의 폴백
        // 경로에 편입된다 — 같은 코드가 두 번 값을 한다"*).
        if (version < 6) {
            warnings.add(appContext.getString(com.novelcharacter.app.R.string.world_package_legacy_v5, version))
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
        // 명대사 (사용자 요청 2026.08.20) — 코드가 유니크라 겹치면 새로 발급해야 한다.
        val quoteReg = WorldPackageCodes.Registry(db.characterQuoteDao().getAllQuotesList().map { it.code })
        val relReg = WorldPackageCodes.Registry(db.characterRelationshipDao().getAllRelationships().map { it.code })
        val relChangeReg = WorldPackageCodes.Registry(db.characterRelationshipChangeDao().getAllChanges().map { it.code })
        val factionReg = WorldPackageCodes.Registry(db.factionDao().getAllFactionsList().map { it.code })
        // 세력 간 관계도 코드를 갖는다(v58) — 유니크 열이라 겹치면 새로 발급해야 한다.
        val factionRelReg = WorldPackageCodes.Registry(
            db.factionRelationshipDao().getAllRelationshipsList().map { it.code }
        )
        // 은행 전량을 한 번만 읽어 코드 사전을 만든다 — v1·v2 패키지는 원 기기 은행 전체가
        // 실려 있어, 행마다 점조회하면 트랜잭션 안에서 수천 쿼리가 된다(규모).
        val existingNameBank = db.nameBankDao().getAllNamesList()
        // 마이그레이션 잔재의 빈 code("")끼리 사전 키가 충돌하지 않도록 걸러낸다
        val existingNameBankByCode = existingNameBank
            .filter { it.code.isNotBlank() }
            .associateBy { it.code }
        val nameBankReg = WorldPackageCodes.Registry(existingNameBank.map { it.code })
        val entryReg = WorldPackageCodes.Registry(db.fieldValueEntryDao().getAllList().map { it.code })
        val gradeSystemReg = WorldPackageCodes.Registry(db.gradeSystemDao().getAllList().map { it.code })
        // 대결 표 셋(v6 — B-118)은 **전량이 아니라 겹치는 것만** 읽는다. 판 표는 이 앱에서
        // 가장 커질 수 있는 표라(수만 행) 전량 조회를 붙이면 가져오기 비용이 *기기에 쌓인 양*에
        // 비례해 늘고, 알아야 하는 것은 *이 패키지가 원하는 코드가 겹치는가* 하나다.
        // 세 벌로 적은 것은 일부러다 — `share/`는 로컬 컴파일 증명이 없는 계층이라
        // (검증은 CI뿐) 여기서는 짧은 것보다 **읽으면 아는 것**을 고른다.
        // IN 목록은 저장소 공통 통로([SqlInChunks] · R-54)를 지난다.
        val wantedAxisCodes = codesToCheck(contents.duelAxes.map { it.code })
        val duelAxisReg = WorldPackageCodes.Registry(
            SqlInChunks.flat(wantedAxisCodes) { db.duelAxisDao().getExistingCodes(it) }
        )
        val wantedMatchCodes = codesToCheck(contents.duelMatches.map { it.code })
        val duelMatchReg = WorldPackageCodes.Registry(
            SqlInChunks.flat(wantedMatchCodes) { db.duelMatchDao().getExistingCodes(it) }
        )
        val wantedVerdictCodes = codesToCheck(contents.duelVerdicts.map { it.code })
        val duelVerdictReg = WorldPackageCodes.Registry(
            SqlInChunks.flat(wantedVerdictCodes) { db.duelCounterVerdictDao().getExistingCodes(it) }
        )
        // **`claim`하는 등록부는 전부 여기 있어야 한다** — `reissuedCodes`가 이 목록만 더하므로
        // 빠진 등록부의 재발급은 *"코드를 재발급한 항목 N건"* 고지에서 조용히 사라진다.
        // `quoteReg`가 그 자리였다(만들고 `claim`까지 하는데 집계만 안 왔다 — 명대사는
        // 뒤늦게 더해진 축이고, 세 자리 중 둘만 따라온 모양이다).
        val registries = listOf(
            uniReg, novelReg, charReg, eventReg, scReg, quoteReg, relReg, relChangeReg,
            factionReg, factionRelReg, nameBankReg, entryReg,
            gradeSystemReg, duelAxisReg, duelMatchReg, duelVerdictReg
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
        var truncatedImagesDropped = 0
        var strippedImagePaths = 0

        // 새 경로 목록과 함께 **옛 경로 → 새 경로**를 돌려준다. 대표 이미지(B-103)가 그것을
        // 따라가야 하기 때문이다 — 패키지 안의 대표 경로는 내보낸 기기의 것이라, 재매핑을
        // 반영하지 않으면 받아온 쪽에서 100% 어긋난다(D5의 "경로가 바뀐다" 갈래).
        fun restoreImagePaths(imagePathsJson: String?, entryPrefix: String): RestoredPaths {
            if (imagePathsJson.isNullOrBlank() || imagePathsJson == "[]") return RestoredPaths("[]", emptyMap())
            val paths: List<String?> = try {
                gson.fromJson(imagePathsJson, Array<String>::class.java)?.toList() ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val result = mutableListOf<String>()
            val renames = mutableMapOf<String, String>()
            paths.forEachIndexed { index, original ->
                if (original == null) return@forEachIndexed
                // 이름을 짜는 자리는 단일 소스다 — 내보내기와 규약이 갈리면 장이 통째로
                // 안 잡힌다(B-234).
                val entryName = WorldPackageImages.entryName(entryPrefix, index)
                val entryFile = File(extractDir, entryName)
                // `exists()`는 syscall이라 한 번만 묻는다 — 장마다 도는 자리다.
                // **잘린 장은 엔트리가 있어도 없는 것으로 본다**(B-234 · 확정 19장 5번).
                // 그래야 아래 갈래가 그대로 산다: 같은 기기 재가져오기면 원본을 재사용하고,
                // 그것도 없으면 사유와 함께 세어 고지된다. 바이트를 열어 보지 않는 것은
                // 성능이다 — 그 비용은 **정상 경로의 장마다** 붙는다(사용자 제1원칙).
                val entryExists = entryFile.exists()
                val restorable = WorldPackageImages.isRestorable(
                    entryName, entryExists, contents.truncatedImages
                )
                val truncated = entryExists && !restorable
                when {
                    restorable -> {
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
                            renames[original] = dest.absolutePath
                            restoredImages++
                        } catch (e: Exception) {
                            Log.w(TAG, "Image restore failed for $entryName", e)
                            missingImages++
                        }
                    }
                    // 같은 기기 재가져오기: 원 경로 파일이 그대로 있으면 재사용한다
                    // (공유 경로의 무음 파괴는 ImageOwnershipGuard가 막는다 — 엑셀 경로와 동일).
                    // **잘린 장도 이 갈래를 지난다** — 보내는 기기에서만 실패한 것이라
                    // 이 기기에 원본이 있으면 그것이 온전한 장이다.
                    runCatching { File(original).exists() }.getOrDefault(false) -> result.add(original)
                    // 사유를 갈라 센다(R-39) — *패키지에 없던 장*과 *잘려서 못 쓰는 장*은
                    // 사용자가 할 일이 다르다(전자는 보낸 사람이 이미지를 빼고 내보낸 것,
                    // 후자는 보낸 기기에서 다시 내보내면 살아난다).
                    truncated -> truncatedImagesDropped++
                    includesImages -> missingImages++
                    else -> strippedImagePaths++
                }
            }
            return RestoredPaths(gson.toJson(result), renames)
        }

        val oldUniverse = contents.universe
        val universeImagePaths = restoreImagePaths(oldUniverse.imagePaths, "universe_").json
        val novelImagePaths = contents.novels.associate { it.id to restoreImagePaths(it.imagePaths, "novel_${it.id}_").json }
        val charImagePaths = contents.characters.associate { it.id to restoreImagePaths(it.imagePaths, "${it.id}_") }

        // 이미지 축의 참가자는 **경로**다(B-118) — 판·상성이 그 경로를 따라가려면 옛 경로 →
        // 이 기기의 경로 표가 있어야 한다. 두 갈래를 함께 담는다:
        //   · 복사된 장 → `renames`가 그대로 답이다.
        //   · 같은 기기 재가져오기로 **원 경로를 그대로 재사용한 장** → `renames`에 없다
        //     (바뀐 것이 없으니 그 표의 뜻대로 비어 있는 것이 맞다). 그 자리는 새 목록에
        //     옛 경로가 그대로 들어 있으므로 **제자리 대응**으로 담는다.
        // 담기지 않은 경로(장을 못 찾았거나 이미지 미포함 패키지)는 그래서 미해석이 되고,
        // 매퍼가 그 판을 세어 알린다 — 조용히 남의 그림에 붙는 것보다 낫다.
        val imagePathRemap = HashMap<String, String>()
        for (restored in charImagePaths.values) {
            imagePathRemap.putAll(restored.renames)
            for (path in com.novelcharacter.app.util.CharacterRepresentativeImage.paths(restored.json)) {
                imagePathRemap.putIfAbsent(path, path)
            }
        }

        var importedUniverseId = 0L
        var danglingRefs = 0
        var crossUniverseRelsDropped = 0
        var detachedEvents = 0
        var duplicateDefsSkipped = 0
        var demotedGradeRefs = 0
        var droppedDuelGradeRefs = 0
        var duplicateAxesSkipped = 0
        var demotedBasisAxes = 0
        var unresolvedDuelMatches = 0
        var unresolvedDuelVerdicts = 0
        var duplicateDuelVerdicts = 0
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

                // 1.5. 등급 체계 (v5 — U-1). 필드 정의 config가 code로 참조하므로 **정의보다
                //      먼저** 넣는다. code가 재발급되면 config의 참조를 새 code로 다시 잇는다(R-1).
                val systemCodeRemap = HashMap<String, String>()
                val packageSystemCodes = HashSet<String>()
                val seenSystemNames = HashSet<String>()
                for (gs in contents.gradeSystems) {
                    if (!seenSystemNames.add(gs.name)) continue   // 유니크 (universeId, name) 방어
                    val claimed = gradeSystemReg.claim(gs.code)
                    if (claimed != gs.code) systemCodeRemap[gs.code] = claimed
                    packageSystemCodes.add(gs.code)
                    db.gradeSystemDao().insert(gs.copy(id = 0, universeId = newUniverseId, code = claimed))
                }

                // 1.6. 대결 축 (v6 — B-118). **필드 정의보다 먼저** 넣는 이유가 등급 체계와 같다:
                //      필드 config의 `duelGrade.axisCode`가 축을 code로 가리키므로, 정의를 쓸 때
                //      새 code를 이미 알고 있어야 한다.
                //      기준 축 유일성은 삽입 전에 순수 계층이 세운다([WorldPackageDuels]) —
                //      저장소를 지나지 않는 삽입이라 그 불변식을 여기서 지켜 줄 것이 없다.
                val axesToInsert = WorldPackageDuels.normalizeImportedAxes(contents.duelAxes)
                demotedBasisAxes = axesToInsert.demotedBasisAxes
                val axisIdByOldId = HashMap<Long, Long>()
                val axisTargetTypeByOldId = HashMap<Long, String>()
                val axisCodeRemap = HashMap<String, String>()
                val packageAxisCodes = HashSet<String>()
                val seenAxisKeys = HashSet<Pair<String, String>>()
                for (axis in axesToInsert.axes) {
                    // 유니크 (universeId, targetType, name) 방어 — 손편집 패키지가 같은 축을 두 번
                    // 담으면 삽입이 예외로 죽고, 트랜잭션이 하나라 세계관 전체가 들어오지 못한다.
                    if (!seenAxisKeys.add(axis.targetType to axis.name)) {
                        duplicateAxesSkipped++
                        continue
                    }
                    val claimed = duelAxisReg.claim(axis.code)
                    if (claimed != axis.code) axisCodeRemap[axis.code] = claimed
                    packageAxisCodes.add(axis.code)
                    val newId = db.duelAxisDao().insert(
                        axis.copy(id = 0, universeId = newUniverseId, code = claimed)
                    )
                    axisIdByOldId[axis.id] = newId
                    axisTargetTypeByOldId[axis.id] = axis.targetType
                }

                // 캐릭터 code는 **삽입보다 먼저 확정한다**(4번에서 그대로 쓴다). 필드 정의가
                // `duelGrade.lastApplied`의 배정 키(캐릭터 code)를 함께 옮겨야 하는데, 그 표가
                // 정의를 쓰는 시점에 있어야 하기 때문이다. claim 순서는 종전과 같아
                // 어느 코드가 재발급되는지도 그대로다.
                // **자리(순서)로 들고 id로 들지 않는다.** `Character.code`는 유니크 인덱스라,
                // 손편집 패키지가 같은 `id`를 둘에 적어 두면 id로 담은 표는 둘에게 **같은 code를
                // 주고** 삽입이 예외로 죽는다 — 트랜잭션이 하나라 세계관 전체가 들어오지 못한다.
                // (종전에는 각자 claim했으므로 그 입력에서도 죽지 않았다.)
                val claimedCharCodes = contents.characters.map { charReg.claim(it.code) }
                val charCodeRemap = HashMap<String, String>()
                val ambiguousCharCodes = HashSet<String>()
                contents.characters.forEachIndexed { index, character ->
                    val oldCode: String? = character.code
                    if (oldCode.isNullOrBlank()) return@forEachIndexed
                    if (charCodeRemap.put(oldCode, claimedCharCodes[index]) != null) {
                        ambiguousCharCodes.add(oldCode)
                    }
                }
                // 같은 code를 둘이 들고 온 패키지에서는 그 code가 **누구인지 알 수 없다.**
                // 표에서 지우면 그 코드를 가리키는 판·상성이 제외 + 계수된다 — 아무나 골라
                // 붙이는 것보다 낫다(R-1: 오배정은 생략보다 나쁘다).
                for (code in ambiguousCharCodes) charCodeRemap.remove(code)

                // 2. 필드 정의 (전 entityType — v3). 유니크 (universeId, entityType, key) 방어적 중복 제거.
                //    등급 체계 참조는 재발급 표로 다시 잇고, 패키지에 없는 체계를 가리키면
                //    독자 표로 내려앉힌다(실효 표가 config에 있어 필드는 그대로 동작한다 — 관대 수용).
                val defIdMap = HashMap<Long, Long>()
                val seenDefKeys = HashSet<Pair<String, String>>()
                for (fd in contents.fieldDefinitions) {
                    if (!seenDefKeys.add(fd.entityType to fd.key)) {
                        duplicateDefsSkipped++
                        continue
                    }
                    val refCode = GradeSystemRef.codeFromConfig(fd.config)
                    val gradeResolved = when {
                        refCode == null -> fd.config
                        refCode in packageSystemCodes -> GradeSystemRef.remapCode(fd.config, systemCodeRemap)
                        else -> {
                            demotedGradeRefs++
                            GradeSystemRef.demote(fd.config)
                        }
                    }
                    // 대결 등급 산정(B-113)은 이제 **재발급할 표가 있다**(v6 — B-118: 패키지가
                    // 축을 담는다). 그래서 등급 체계와 **같은 3분기**가 된다:
                    //   · 가리키는 축이 이 패키지에 함께 왔다 → 새 code로 다시 잇는다.
                    //     배정 흔적의 캐릭터 code도 같은 자리에서 옮긴다(둘은 한 몸이다 —
                    //     [DuelGradeRef.remapCodes]가 그 이유를 적었다).
                    //   · 안 왔다(v5 이하 패키지 · 다른 세계관 축을 가리키던 config) → 걷어낸다.
                    //     축 code는 전역 유니크라 남겨 두면 이 기기의 **다른 세계관 축**을
                    //     정확히 집어낸다 — 못 찾는 것이 아니라 오배정이다(R-1 · R-35).
                    //   · 애초에 대결과 무관한 필드 → 손대지 않는다.
                    // **걷어낸 수를 센다.** 형제인 등급 체계 강등은 세어 경고하는데 이쪽은 세지
                    // 않고 있었다(B-118 등재가 지적한 자리) — 사용자가 세계관을 옮긴 뒤 등급이
                    // 왜 안 나오는지 알 길이 없었다.
                    val duelRefCode = DuelGradeRef.axisCodeFromConfig(gradeResolved)
                    val config = when {
                        duelRefCode == null -> gradeResolved
                        duelRefCode in packageAxisCodes ->
                            DuelGradeRef.remapCodes(gradeResolved, axisCodeRemap, charCodeRemap)
                        else -> {
                            droppedDuelGradeRefs++
                            DuelGradeRef.remove(gradeResolved)
                        }
                    }
                    // 전역키 보증(universeId = newUniverseId 비-null — 갓 만든 세계관이라
                    // 그 안에서만 충돌할 수 있고 그것은 유니크 색인이 잡는다)
                    defIdMap[fd.id] =
                        db.fieldDefinitionDao().insert(fd.copy(id = 0, universeId = newUniverseId, config = config))
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
                contents.characters.forEachIndexed { index, character ->
                    val mappedNovel = character.novelId?.let { old ->
                        novelIdMap[old].also { if (it == null) danglingRefs++ }
                    }
                    val restored = charImagePaths[character.id]
                    charIdMap[character.id] = db.characterDao().insert(
                        character.copy(
                            id = 0,
                            novelId = mappedNovel,
                            // code는 위(1.6 뒤)에서 이미 claim했다 — 필드 정의가 그 표를 먼저
                            // 봐야 했기 때문이다. 여기서 다시 claim하면 **같은 캐릭터에 코드가
                            // 두 번 발급되어** 판·상성이 가리키는 코드와 갈린다.
                            // **자리로 집는다** — id는 손편집 패키지에서 겹칠 수 있다.
                            code = claimedCharCodes[index],
                            // v47 이전 패키지에는 이 키가 없어 Gson이 null을 주입한다(R-2).
                            // **명시로 넘겨야 한다** — 넘기지 않으면 기본값으로 채워지면서
                            // Kotlin이 거는 copy 인자 null 검사에 그대로 걸려 죽는다.
                            representativeImagePath = character.representativeImagePath.orEmpty()
                        ).withImagePaths(restored?.json ?: "[]", restored?.renames ?: emptyMap())
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

                // 7-b. 명대사 — 상태변화와 같은 규약(임자를 못 찾으면 버리고 세어 고지한다).
                val quoteRows = contents.quotes.mapNotNull { q ->
                    val cid = charIdMap[q.characterId] ?: run { danglingRefs++; return@mapNotNull null }
                    q.copy(id = 0, characterId = cid, code = claimNullable(quoteReg, q.code))
                }
                quoteRows.chunked(CHUNK).forEach { db.characterQuoteDao().insertAll(it) }

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
                factionRelResult.relationships
                    .map { it.copy(code = claimNullable(factionRelReg, it.code)) }
                    .chunked(CHUNK).forEach { db.factionRelationshipDao().insertAll(it) }

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

                // 13-b. 작품 필드값 (v4, 확-3) — 사건판과 같은 규칙: 작품·정의 둘 다 새 id로
                //       재배선되지 않으면 넣을 수 없다(FK가 거부한다). 버린 것은 개수로 고지한다.
                val novelValueRows = contents.novelFieldValues.mapNotNull { v ->
                    val nid = novelIdMap[v.novelId]
                    val fid = defIdMap[v.fieldDefinitionId]
                    if (nid == null || fid == null) {
                        danglingRefs++
                        null
                    } else v.copy(id = 0, novelId = nid, fieldDefinitionId = fid)
                }
                novelValueRows.chunked(CHUNK).forEach { db.novelFieldValueDao().insertAll(it) }

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

                // 17-b. 대결 판·상성 (v6 — B-118). 축은 1.6에서 이미 섰고, 여기 오는 이유는
                //       **참가자**다: 캐릭터 code는 위에서 확정됐고 이미지 경로는 복사가 끝나
                //       새 경로가 정해진 뒤여야 한다. 재배선과 계수는 [WorldPackageDuels]가 한다.
                val duelResult = WorldPackageDuels.fromPortable(
                    matches = contents.duelMatches,
                    verdicts = contents.duelVerdicts,
                    axisIdByOldId = axisIdByOldId,
                    axisTargetTypeByOldId = axisTargetTypeByOldId,
                    characterCodeRemap = charCodeRemap,
                    imagePathRemap = imagePathRemap
                )
                unresolvedDuelMatches = duelResult.unresolvedMatches
                unresolvedDuelVerdicts = duelResult.unresolvedVerdicts
                duplicateDuelVerdicts = duelResult.duplicateVerdicts
                // code는 삽입 직전에 claim한다 — 매퍼는 순수 계층이라 이 기기의 사용 중 코드를
                // 모른다(세력 간 관계도 같은 모양이다: 매퍼가 행을 만들고 배선이 코드를 준다).
                val duelMatchRows = duelResult.matches.map { it.copy(code = duelMatchReg.claim(it.code)) }
                duelMatchRows.chunked(CHUNK).forEach { db.duelMatchDao().insertAll(it) }
                val duelVerdictRows = duelResult.verdicts.map { it.copy(code = duelVerdictReg.claim(it.code)) }
                duelVerdictRows.chunked(CHUNK).forEach { db.duelCounterVerdictDao().insertAll(it) }

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
                    gradeSystems = packageSystemCodes.size,
                    fieldValues = fieldValueRows.size,
                    eventFieldValues = eventValueRows.size,
                    novelFieldValues = novelValueRows.size,
                    tags = tagRows.size,
                    stateChanges = stateChangeRows.size,
                    quotes = quoteRows.size,
                    relationships = relIdMap.size,
                    relationshipChanges = relChangeRows.size,
                    events = eventIdMap.size,
                    factions = factionIdMap.size,
                    factionMemberships = membershipRows.size,
                    factionRelationships = factionRelResult.relationships.size,
                    duelAxes = axisIdByOldId.size,
                    duelMatches = duelMatchRows.size,
                    duelVerdicts = duelVerdictRows.size,
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
        if (truncatedImagesDropped > 0) {
            warnings.add("내보내는 중 잘린 이미지 ${truncatedImagesDropped}개를 제외했습니다 (보낸 기기에서 다시 내보내 주세요)")
        }
        if (strippedImagePaths > 0) warnings.add("이미지 미포함 패키지 — 이미지 연결 ${strippedImagePaths}개 제외")
        if (danglingRefs > 0) warnings.add("패키지 내부 참조 불일치로 제외된 항목 ${danglingRefs}건")
        if (crossUniverseRelsDropped > 0) warnings.add("패키지 밖 캐릭터와 얽힌 관계·관계변화 ${crossUniverseRelsDropped}건 제외")
        if (detachedEvents > 0) warnings.add("다른 세계관 소속이던 사건 ${detachedEvents}건은 세계관 연결 없이 들어왔습니다")
        if (duplicateDefsSkipped > 0) warnings.add("중복 필드 정의 ${duplicateDefsSkipped}건 제외")
        if (demotedGradeRefs > 0) {
            warnings.add("패키지에 없는 등급 체계를 가리키던 필드 ${demotedGradeRefs}개를 독자 등급 표로 전환했습니다 (표 내용은 그대로입니다)")
        }
        // 대결(v6 — B-118). 다섯 다 **뜻이 다른 사실**이라 한 줄로 합치지 않는다:
        // 참가자를 잇지 못한 것 / 같은 관계가 두 번인 것 / 축이 겹친 것 / 기준 표식을 내린 것 /
        // 등급 산정 약속을 걷어낸 것. 합치면 사용자가 무엇을 고쳐야 하는지 알 수 없다.
        if (unresolvedDuelMatches > 0) {
            warnings.add("참가자를 잇지 못한 대결 기록 ${unresolvedDuelMatches}건 제외")
        }
        if (unresolvedDuelVerdicts > 0) {
            warnings.add("참가자를 잇지 못한 대결 상성 ${unresolvedDuelVerdicts}건 제외")
        }
        if (duplicateDuelVerdicts > 0) {
            warnings.add("같은 관계가 두 번 담긴 대결 상성 ${duplicateDuelVerdicts}건 제외")
        }
        if (duplicateAxesSkipped > 0) warnings.add("이름이 겹치는 대결 축 ${duplicateAxesSkipped}건 제외")
        if (demotedBasisAxes > 0) {
            warnings.add("기준 축이 둘 이상이라 ${demotedBasisAxes}개의 기준 표식을 내렸습니다 (같은 대상끼리는 하나만 켤 수 있습니다)")
        }
        if (droppedDuelGradeRefs > 0) {
            warnings.add("패키지에 없는 대결 축을 가리키던 필드 ${droppedDuelGradeRefs}개의 등급 산정 설정을 걷어냈습니다 (등급 표는 그대로입니다)")
        }
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

    /**
     * 충돌을 물어볼 코드만 남긴다 — null·공란은 어차피 신규 발급이라 물을 것이 없고,
     * 중복은 한 번만 물으면 된다(IN 절이 짧아진다).
     */
    private fun codesToCheck(wanted: List<String?>): List<String> =
        wanted.filterNotNull().filter { it.isNotBlank() }.distinct()

    companion object {
        private const val TAG = "WorldPackageImporter"
        private const val CHUNK = 500
        private const val MAX_JSON_ENTRY_SIZE = 128L * 1024 * 1024
        private const val MAX_IMAGE_ENTRY_COUNT = 50000
        private const val MAX_IMAGE_TOTAL_SIZE = 8L * 1024 * 1024 * 1024
    }
}
