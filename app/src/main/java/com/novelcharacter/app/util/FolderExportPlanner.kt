package com.novelcharacter.app.util

/**
 * 정리 폴더 **내보내기 계획**의 순수 로직 (Android 무의존 — JVM 테스트로 검증).
 *
 * 현재 라이브러리 상태를 [FolderRoundtripPlanner]가 읽을 수 있는 폴더 배치로 옮겨 적는다.
 * 실제 복사·SAF는 전부 호출부([OrganizeFolderService]) 몫이다.
 *
 * ## 버킷 (설계 3장 표의 역방향)
 *
 * | 현재 상태 | 내보낼 자리 |
 * |---|---|
 * | 캐릭터 1명 소유 | `<캐릭터명>/` |
 * | 캐릭터 2명 이상 소유 | `_공유/` (받아오기가 위치 반영을 제외하는 자리) |
 * | 미배정 + 수동 링크 그룹 | `_미배정/세트-n/` |
 * | 미배정 + 링크 없음 | `_미배정/` |
 *
 * 자동 링크(`char:`) 그룹은 세트로 내보내지 않는다 — 그 묶음은 **캐릭터 배정에서 파생된
 * 결과**라 캐릭터 폴더가 이미 같은 사실을 말하고, 세트로도 적으면 받아오기가 같은 뜻을 수동
 * 그룹으로 한 번 더 고정해 자동/수동이 겹친다.
 *
 * ## 되돌아올 수 없는 이름은 내보내지 않는다 (설계 9장 C-14)
 *
 * 받아오기는 폴더명을 캐릭터 이름과 **정확 일치**로만 되돌린다. 그래서 이름을 고쳐야만
 * 폴더로 쓸 수 있는 캐릭터, 예약 폴더명과 겹치는 캐릭터, **동명이 둘 이상인 캐릭터**는
 * 내보내기에서 빼고 이름·개수를 고지한다([BlockReason]).
 *
 * 특히 동명은 조용히 내보내면 손해가 크다 — 받아오기가 그 폴더를 "기타 이름"으로 읽어
 * 서로 다른 캐릭터의 이미지를 **하나의 수동 링크 묶음**으로 붙여 버린다. 수동 묶음은 자동
 * 링크와 달리 재동기화가 풀어 주지도 않는다. 오배정·오묶음은 생략보다 나쁘다(R-1과 같은 취지).
 */
object FolderExportPlanner {

    /** 세트 폴더 이름 접두 — `_미배정/세트-1/`. 번호는 정렬용일 뿐 의미 없다. */
    const val SET_FOLDER_PREFIX = "세트-"

    /** 내보낼 대상 범위 — 사용자가 고른다(전부 다 내보내는 것만이 답은 아니다). */
    enum class Scope {
        /** 캐릭터 배정 이미지 + 미배정 라이브러리 전부. */
        ALL,
        /** 캐릭터에 배정된 것만. */
        ASSIGNED,
        /** 미배정 라이브러리만. */
        UNASSIGNED
    }

    /** 캐릭터 1명. [name]은 원문(트림은 여기서 한다). */
    data class CharacterInput(val id: Long, val name: String)

    /**
     * 내보내기 후보 이미지 1장.
     *
     * @param path 앱 내부 경로(호출부가 canonical로 맞춰 넘긴다 — 비교의 축).
     * @param ownerCharacterIds 이 이미지를 등록한 캐릭터 id. 비어 있으면 캐릭터 미배정.
     * @param linkGroupId 링크 그룹 토큰. 수동(UUID)만 세트가 되고 자동(`char:`)은 무시한다.
     * @param inLibrary 라이브러리(`image_meta`) 행이 있는가. 캐릭터 소유도 라이브러리 행도
     *        없는 이미지는 **작품·세계관 전용**이라 v1 범위 밖이다(개수만 고지).
     */
    data class ImageInput(
        val path: String,
        val sizeBytes: Long = 0,
        val ownerCharacterIds: List<Long> = emptyList(),
        val linkGroupId: String? = null,
        val inLibrary: Boolean = true
    )

    /** 내보낼 파일 1건 — 어디에 무슨 이름으로 쓸 것인가. */
    data class ExportFile(
        val sourcePath: String,
        val folders: List<String>,
        val fileName: String,
        val token: String,
        val sizeBytes: Long
    ) {
        val relativePath: String get() = (folders + fileName).joinToString("/")
    }

    /** 내보낼 수 없는 캐릭터의 사유 — 전부 사용자가 **고칠 수 있는** 것들이라 이름째 고지한다. */
    enum class BlockReason {
        /** 같은 이름의 캐릭터가 둘 이상 — 폴더 하나가 누구를 가리키는지 정할 수 없다. */
        DUPLICATE_NAME,
        /** 예약 폴더명(`_미배정`·`_공유`·`_처리됨`)과 이름이 같다. */
        RESERVED_NAME,
        /** 폴더명으로 그대로 쓸 수 없는 이름(금지 문자·제어문자·앞뒤 마침표·길이 초과). */
        UNSAFE_NAME
    }

    data class BlockedCharacter(val name: String, val reason: BlockReason, val imageCount: Int)

    /**
     * 내보내기 계획.
     *
     * @param legacySkipped 파일명이 UUID 형태가 아니라 토큰을 심을 수 없는 이미지 수
     *        (내보내도 돌아올 때 위치를 반영할 수 없다 — 설계 2장).
     * @param entityOnlySkipped 작품·세계관 전용 이미지 수(v1 범위 밖 — 설계 8장 PR-2 3번).
     * @param missingSkipped 경로에 파일이 없어 건너뛴 이미지 수.
     */
    data class Plan(
        val files: List<ExportFile> = emptyList(),
        val characterFolders: Int = 0,
        val unassignedCount: Int = 0,
        val setFolders: Int = 0,
        val sharedCount: Int = 0,
        val legacySkipped: Int = 0,
        val entityOnlySkipped: Int = 0,
        val missingSkipped: Int = 0,
        val blockedCharacters: List<BlockedCharacter> = emptyList()
    ) {
        val totalBytes: Long get() = files.sumOf { it.sizeBytes }
        val isEmpty: Boolean get() = files.isEmpty()

        /** 내보내지 못한 이미지 총수 — 하나라도 있으면 고지 대상이다(조용한 생략 금지). */
        val skippedTotal: Int
            get() = legacySkipped + entityOnlySkipped + missingSkipped +
                blockedCharacters.sumOf { it.imageCount }
    }

    /** 이미 정리 폴더에 있는 파일 1건(재내보내기 정리 판정용). */
    data class ExistingCopy(val id: String, val folders: List<String>, val fileName: String)

    /**
     * 재내보내기 정리 계획.
     *
     * @param staleIds 지울 이전 사본 — **이번에 새로 쓰는 이미지의 사본만** 고른다.
     * @param rearrangedIds 그중 지금 위치가 새로 쓸 위치와 달라, 아직 받아오지 않은
     *        **사용자의 배치 의도**를 담고 있는 것. 지우기 전에 반드시 고지한다.
     */
    data class Cleanup(
        val staleIds: List<String> = emptyList(),
        val rearrangedIds: List<String> = emptyList(),
        /**
         * 이전 사본 → 그것이 가리키는 앱 이미지 경로.
         *
         * 지우기에 **실패한** 사본의 이미지는 새로 쓰지 않기 위해 필요하다. 남은 사본 옆에
         * 같은 이름으로 쓰면 provider가 이름을 바꿔 다는데(`…(1).jpg`), 그러면 파일명 끝의
         * 토큰 자리가 깨져 다음 받아오기가 그 사본을 **새 이미지로 편입**한다 — 조용한 중복이다.
         */
        val sourcePathById: Map<String, String> = emptyMap()
    ) {
        val isEmpty: Boolean get() = staleIds.isEmpty()
    }

    private val RESERVED_FOLDER_NAMES = setOf(
        FolderRoundtripPlanner.FOLDER_PROCESSED,
        FolderRoundtripPlanner.FOLDER_UNASSIGNED,
        FolderRoundtripPlanner.FOLDER_SHARED
    )

    /**
     * 계획을 세운다.
     *
     * @param images 후보 전체. 같은 경로가 두 번 실려도 한 번만 내보낸다.
     * @param tokenByPath 경로 → 심을 토큰([FolderNameToken.buildDictionary]의 `tokenByPath`).
     *        여기 없는 경로는 레거시 파일명이라 내보낼 수 없다.
     * @param existingPaths 파일이 실제로 있는 경로. null이면 존재 확인을 하지 않는다
     *        (순수 테스트·미리보기용 — 실제 내보내기는 호출부가 확인한 집합을 넘긴다).
     */
    fun plan(
        images: List<ImageInput>,
        characters: List<CharacterInput>,
        tokenByPath: Map<String, String>,
        scope: Scope = Scope.ALL,
        existingPaths: Set<String>? = null
    ): Plan {
        // ── 캐릭터 이름 색인 + 내보낼 수 없는 이름 판정.
        val idsByName = LinkedHashMap<String, MutableList<Long>>()
        for (c in characters) idsByName.getOrPut(c.name.trim()) { mutableListOf() }.add(c.id)

        val nameById = HashMap<Long, String>(characters.size)
        val blockReasonById = HashMap<Long, BlockReason>()
        for ((name, ids) in idsByName) {
            val reason = when {
                ids.size >= 2 -> BlockReason.DUPLICATE_NAME
                name in RESERVED_FOLDER_NAMES -> BlockReason.RESERVED_NAME
                !FolderNameToken.isFolderNameSafe(name) -> BlockReason.UNSAFE_NAME
                else -> null
            }
            for (id in ids) {
                nameById[id] = name
                if (reason != null) blockReasonById[id] = reason
            }
        }

        // ── 버킷 배분. 정렬은 경로 기준 — 같은 상태면 같은 결과가 나와야 한다(번호는 정렬용).
        //    라벨은 **파일마다** 다르다: `_공유/`는 한 폴더 안에서도 이미지마다 주인이 달라,
        //    폴더 대표 라벨을 쓰면 남의 이름을 붙이게 된다.
        data class Entry(val image: ImageInput, val token: String, val label: String)
        val bucketed = LinkedHashMap<List<String>, MutableList<Entry>>()
        val setIndexByGroup = LinkedHashMap<String, Int>()
        val blockedCounts = LinkedHashMap<Long, Int>()
        var legacy = 0
        var entityOnly = 0
        var missing = 0

        val seen = HashSet<String>()
        for (image in images.sortedBy { it.path }) {
            if (!seen.add(image.path)) continue
            val owners = image.ownerCharacterIds.distinct().sorted()
            if (!scope.accepts(owners.isNotEmpty())) continue
            // 캐릭터도 라이브러리도 쥐지 않은 이미지 = 작품·세계관 전용(v1 범위 밖).
            if (owners.isEmpty() && !image.inLibrary) { entityOnly++; continue }
            if (existingPaths != null && image.path !in existingPaths) { missing++; continue }
            val token = tokenByPath[image.path]
            if (token == null) { legacy++; continue }

            val folders: List<String>
            val label: String
            when {
                owners.size >= 2 -> {
                    folders = listOf(FolderRoundtripPlanner.FOLDER_SHARED)
                    // 라벨은 사람용 힌트일 뿐이라 이름을 고쳐 써도 왕복에 영향이 없다.
                    label = owners.firstNotNullOfOrNull { nameById[it] } ?: FolderNameToken.FALLBACK_LABEL
                }

                owners.size == 1 -> {
                    val owner = owners[0]
                    val blocked = blockReasonById[owner]
                    if (blocked != null) {
                        blockedCounts[owner] = (blockedCounts[owner] ?: 0) + 1
                        continue
                    }
                    val name = nameById[owner]
                    if (name == null) {
                        // 사라진 캐릭터를 가리키는 배정 — 미배정처럼 다룬다(유실보다 낫다).
                        folders = listOf(FolderRoundtripPlanner.FOLDER_UNASSIGNED)
                        label = FolderNameToken.FALLBACK_LABEL
                    } else {
                        folders = listOf(name)
                        label = name
                    }
                }

                else -> {
                    val group = image.linkGroupId?.takeIf { !AutoLinkPlanner.isAutoToken(it) }
                    folders = if (group == null) {
                        listOf(FolderRoundtripPlanner.FOLDER_UNASSIGNED)
                    } else {
                        val number = setIndexByGroup.getOrPut(group) { setIndexByGroup.size + 1 }
                        listOf(FolderRoundtripPlanner.FOLDER_UNASSIGNED, "$SET_FOLDER_PREFIX$number")
                    }
                    label = FolderNameToken.FALLBACK_LABEL
                }
            }
            bucketed.getOrPut(folders) { mutableListOf() }.add(Entry(image, token, label))
        }

        // ── 파일명 조립 — 번호는 폴더 안에서 1부터.
        val files = ArrayList<ExportFile>()
        var characterFolders = 0
        var unassigned = 0
        var setFolders = 0
        var shared = 0
        for ((folders, entries) in bucketed) {
            val isShared = folders == listOf(FolderRoundtripPlanner.FOLDER_SHARED)
            val isUnassignedRoot = folders == listOf(FolderRoundtripPlanner.FOLDER_UNASSIGNED)
            val isSet = folders.size == 2 && folders[0] == FolderRoundtripPlanner.FOLDER_UNASSIGNED
            when {
                isShared -> shared += entries.size
                isUnassignedRoot -> unassigned += entries.size
                isSet -> setFolders++
                else -> characterFolders++
            }
            entries.forEachIndexed { index, entry ->
                files.add(
                    ExportFile(
                        sourcePath = entry.image.path,
                        folders = folders,
                        fileName = FolderNameToken.exportFileName(
                            entry.label, index + 1, entry.token,
                            entry.image.path.substringAfterLast('.', "")
                        ),
                        token = entry.token,
                        sizeBytes = entry.image.sizeBytes
                    )
                )
            }
        }

        val blocked = blockedCounts.entries.map { (id, count) ->
            BlockedCharacter(
                name = nameById[id].orEmpty(),
                reason = blockReasonById.getValue(id),
                imageCount = count
            )
        }.groupBy { it.name to it.reason }
            // 동명은 캐릭터마다 한 줄이 되므로 이름 기준으로 합친다(같은 이름을 두 번 보이면 소음).
            .map { (key, group) -> BlockedCharacter(key.first, key.second, group.sumOf { it.imageCount }) }

        return Plan(
            files = files,
            characterFolders = characterFolders,
            unassignedCount = unassigned,
            setFolders = setFolders,
            sharedCount = shared,
            legacySkipped = legacy,
            entityOnlySkipped = entityOnly,
            missingSkipped = missing,
            blockedCharacters = blocked
        )
    }

    private fun Scope.accepts(assigned: Boolean): Boolean = when (this) {
        Scope.ALL -> true
        Scope.ASSIGNED -> assigned
        Scope.UNASSIGNED -> !assigned
    }

    /**
     * 재내보내기 정리 계획 — 이전 사본 중 **이번 배치와 같은 이미지를 가리키는 것**만 고른다.
     *
     * 남겨 두면 같은 이미지를 가리키는 파일이 폴더에 둘이 되어 받아오기가 그 이미지를 통째로
     * 보류한다(설계 7장 · `HoldReason.DUPLICATE_TOKEN`) — 내보내기가 스스로 왕복을 막는 꼴이다.
     *
     * 그렇다고 폴더의 토큰 파일을 싹 지우면 안 된다. **이번 배치에 없는 토큰 파일은 사용자가
     * 옮겨 두고 아직 받아오지 않은 작업**일 수 있다 — 그것까지 지우는 것은 지시받지 않은 파괴다.
     * 그래서 판정은 "이번에 새로 쓸 이미지의 사본인가" 하나뿐이고, [Cleanup.rearrangedIds]로
     * "지금 위치가 새 위치와 다르다 = 아직 받아오지 않은 배치"를 따로 세어 지우기 전에 고지한다.
     *
     * 별칭 때문에 토큰 문자열이 달라도 같은 이미지일 수 있으므로(재압축 개명), 비교는 토큰
     * 문자열이 아니라 [pathByToken]으로 **경로까지 풀어서** 한다.
     */
    fun planCleanup(
        existing: List<ExistingCopy>,
        files: List<ExportFile>,
        pathByToken: Map<String, String>
    ): Cleanup {
        if (files.isEmpty()) return Cleanup()
        val targetFolders = files.associate { it.sourcePath to it.folders }
        val stale = ArrayList<String>()
        val rearranged = ArrayList<String>()
        val sourceById = LinkedHashMap<String, String>()
        for (copy in existing) {
            // `_처리됨/`은 사용자의 처리 이력이고 받아오기가 스캔에서 통째로 빼므로 중복을
            // 만들지 않는다 — 손대지 않는다.
            if (copy.folders.firstOrNull() == FolderRoundtripPlanner.FOLDER_PROCESSED) continue
            val token = FolderNameToken.tokenCandidateOf(copy.fileName) ?: continue
            val sourcePath = pathByToken[token] ?: continue
            val target = targetFolders[sourcePath] ?: continue
            stale.add(copy.id)
            sourceById[copy.id] = sourcePath
            if (copy.folders != target) rearranged.add(copy.id)
        }
        return Cleanup(stale, rearranged, sourceById)
    }
}
