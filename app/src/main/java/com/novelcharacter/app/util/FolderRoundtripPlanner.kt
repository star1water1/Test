package com.novelcharacter.app.util

/**
 * 정리 폴더 **받아오기 계획**의 순수 로직 (Android 무의존 — JVM 테스트로 검증).
 *
 * 스캔 결과(폴더 경로 + 파일명)를 현재 상태(캐릭터 이름 사전·토큰 사전·현재 배정)와 대조해
 * "무엇을 편입하고, 무엇을 옮기고, 무엇을 풀고, 무엇을 묶고, 무엇을 **보류**하는가"를 정한다.
 * DB·SAF 접근은 전부 호출부([OrganizeFolderService]) 몫이다.
 *
 * ## 폴더 규약 (설계 3장)
 *
 * | 위치 | 신규 파일(토큰 없음) | 토큰 파일(기존 이미지) |
 * |---|---|---|
 * | `<캐릭터명>/` 정확 일치 | 편입 + 그 캐릭터 배정(자동 링크가 묶는다) | 배정을 그 캐릭터로 **이동** |
 * | `<기타 이름>/` | 편입 + 미배정 + 그 폴더끼리 **링크 세트** | 배정 불변 + 그 폴더 세트로 링크 |
 * | 정리 폴더 직속 · `_미배정/` 직속 | 편입 + 미배정(링크 없음) | 캐릭터 배정 **전부 해제** |
 * | `_미배정/<세트명>/` | 편입 + 미배정 + 링크 세트 | 배정 해제 + 그 세트로 링크 |
 * | `_공유/` | 편입 + 미배정(고지) | **반영 제외**(고지만) |
 * | `_처리됨/` | 스캔 제외 | 스캔 제외 |
 *
 * ## 판정의 축 — 셋을 헷갈리지 말 것
 *
 * - **위치**는 사용자의 의도다. 무엇을 하려 했는지는 폴더가 말한다.
 * - **현재 상태**는 그 의도를 실행해도 되는지를 정한다. 캐릭터 소유자가 2명 이상인 이미지는
 *   폴더가 무엇이든 이동·해제를 **보류**한다(설계 9장 C-2) — 한쪽을 고르는 이동은 오배정이고
 *   전부 떼는 해제는 지시받지 않은 파괴다. 둘 다 생략보다 나쁘다.
 * - **정체성**은 토큰만 진다([FolderNameToken]). 라벨·번호·폴더명은 사람용이다.
 *
 * ## 보류는 실패가 아니다
 *
 * 판정할 수 없을 때 임의로 고르지 않고 [Hold]로 남긴다. 보류된 파일은 `_처리됨/`으로 옮기지도
 * 않으므로 폴더에 그대로 남아 **다음 기회가 살아 있다**(남아 있는 것 = 미처리의 표식).
 * 동명 캐릭터 폴더의 신규 파일은 배정만 보류하고 **링크 세트로는 들어간다** — 인앱에서 한 장만
 * 올바른 캐릭터에 배정하면 세트 전체가 따라가는 부드러운 실패(결정 D3)다.
 */
object FolderRoundtripPlanner {

    /** 처리 완료분 보관 폴더 — 스캔에서 통째로 제외한다(계수에도 넣지 않는다). */
    const val FOLDER_PROCESSED = "_처리됨"

    /** 미배정 보관 폴더. 직속은 "링크 없는 미배정", 하위 폴더는 링크 세트다. */
    const val FOLDER_UNASSIGNED = "_미배정"

    /** 캐릭터 여럿이 함께 쓰는 이미지의 보관 폴더 — 위치 이동의 의미가 모호해 반영하지 않는다. */
    const val FOLDER_SHARED = "_공유"

    /** 예약 폴더 접두. 사용자 폴더가 이 접두를 쓰면 캐릭터 이름으로 해석하지 않는다. */
    const val RESERVED_PREFIX = "_"

    /** 나열이 내려가는 최대 깊이(정리 폴더 기준). `_미배정/<세트명>/`이 2단계라 2로 잡는다. */
    const val MAX_SCAN_DEPTH = 2

    /** 스캔 항목 1건. [folders]는 정리 폴더 기준 폴더 경로(직속이면 빈 목록). */
    data class ScanItem(val id: String, val folders: List<String>, val fileName: String)

    /** 항목이 놓인 자리의 해석. */
    sealed class Location {
        /** 정리 폴더 직속. */
        object Root : Location()
        /** 예약이 아닌 1단계 폴더 — 캐릭터 이름일 수도, 아닐 수도 있다. */
        data class Named(val name: String) : Location()
        /** `_미배정/` 직속. */
        object UnassignedRoot : Location()
        /** `_미배정/<세트명>/`. */
        data class UnassignedSet(val name: String) : Location()
        /** `_공유/`. */
        object Shared : Location()
        /** `_처리됨/` 하위 — 스캔 제외(계수도 하지 않는다). */
        object Skipped : Location()
        /** 규약 밖 깊이 — 무시하고 개수만 고지한다. */
        object TooDeep : Location()
    }

    /** 신규 편입 — 폴더의 파일을 앱 라이브러리로 들여온다. */
    data class ImportAction(
        val item: ScanItem,
        /** 편입 직후 배정할 캐릭터(캐릭터 폴더 정확 일치일 때만). null이면 미배정. */
        val assignCharacterId: Long?,
        /** 소속 링크 세트 키. null이면 링크 없음. */
        val setKey: String?
    )

    /** 배정 이동 — 토큰 파일이 다른 캐릭터 폴더에서 발견됐다. */
    data class MoveAction(
        val item: ScanItem,
        val path: String,
        val fromCharacterIds: List<Long>,
        val toCharacterId: Long
    )

    /** 배정 해제 — 토큰 파일이 정리 폴더 직속·`_미배정/`에서 발견됐다. */
    data class DetachAction(
        val item: ScanItem,
        val path: String,
        val fromCharacterIds: List<Long>
    )

    /**
     * 링크 세트 — 폴더 하나가 만드는 수동 묶음(UUID 토큰). 편입 후 경로가 정해지는 신규
     * 항목과, 토큰으로 이미 아는 기존 경로가 함께 실린다. 2장 이상일 때만 만들어진다.
     */
    data class LinkSetAction(
        val key: String,
        val newItemIds: List<String>,
        val existingPaths: List<String>
    ) {
        val size: Int get() = newItemIds.size + existingPaths.size
    }

    /** 보류 사유 — 전부 사용자에게 고지한다(조용한 생략 금지). */
    enum class HoldReason {
        /** 같은 이미지를 가리키는 파일이 폴더에 둘 이상 — 어느 위치가 의도인지 알 수 없다. */
        DUPLICATE_TOKEN,
        /** 캐릭터 소유자가 2명 이상 — 이동·해제를 반영하지 않는다(C-2). */
        SHARED_OWNERS,
        /** `_공유/`의 토큰 파일 — 위치 이동의 의미가 모호하다. */
        SHARED_FOLDER
    }

    data class Hold(val item: ScanItem, val reason: HoldReason)

    /**
     * 받아오기 계획.
     *
     * @param unknownTokenFiles 토큰꼴이지만 사전에 없어 **신규로 편입되는** 파일 수.
     *        재압축·복원 개명으로 별칭이 끊긴 사본이 여기 잡힌다 — 조용한 중복 편입을 막는
     *        고지 대상이다(설계 9장 C-1).
     * @param ambiguousFolders 동명 캐릭터가 둘 이상이라 배정을 보류한 폴더명.
     * @param deeperIgnored 규약 밖 깊이라 무시한 파일 수.
     */
    data class Plan(
        val imports: List<ImportAction> = emptyList(),
        val moves: List<MoveAction> = emptyList(),
        val detaches: List<DetachAction> = emptyList(),
        val linkSets: List<LinkSetAction> = emptyList(),
        val holds: List<Hold> = emptyList(),
        val unknownTokenFiles: Int = 0,
        val ambiguousFolders: List<String> = emptyList(),
        val deeperIgnored: Int = 0
    ) {
        /** 실제로 파일을 만지는 항목 수 — 진행도 총량. */
        val actionCount: Int get() = imports.size + moves.size + detaches.size

        val isEmpty: Boolean get() = actionCount == 0 && linkSets.isEmpty()
    }

    /**
     * 나열이 이 폴더 안으로 내려가야 하는가 — 깊이·예약 규칙의 단일 소스.
     * 호출부(SAF 나열)와 [classify]가 같은 규칙을 쓰게 한다.
     *
     * @param folders 이 폴더의 정리 폴더 기준 경로.
     */
    fun shouldDescend(folders: List<String>): Boolean {
        if (folders.isEmpty()) return true
        if (folders[0] == FOLDER_PROCESSED) return false
        return folders.size < MAX_SCAN_DEPTH
    }

    /** 폴더 경로 → 자리 해석. */
    fun classify(folders: List<String>): Location = when {
        folders.isEmpty() -> Location.Root
        folders[0] == FOLDER_PROCESSED -> Location.Skipped
        folders.size > MAX_SCAN_DEPTH -> Location.TooDeep
        folders[0] == FOLDER_UNASSIGNED ->
            if (folders.size == 1) Location.UnassignedRoot else Location.UnassignedSet(folders[1])
        folders[0] == FOLDER_SHARED ->
            if (folders.size == 1) Location.Shared else Location.TooDeep
        folders.size == 1 -> Location.Named(folders[0])
        // 예약이 아닌 폴더의 하위 폴더는 규약 밖이다 — 세트는 `_미배정/` 아래에서만 만든다.
        else -> Location.TooDeep
    }

    /**
     * 계획을 세운다.
     *
     * @param items 스캔한 파일 전체(이미지 확장자 판정은 호출부가 이미 했다).
     * @param characterIdsByName 트림된 캐릭터 이름 → 그 이름을 쓰는 캐릭터 id 목록.
     *        2건 이상이면 동명이라 배정을 보류한다.
     * @param pathByToken 토큰 사전([FolderNameToken.buildDictionary]의 결과).
     * @param characterIdsByPath 이미지 경로 → 현재 그 이미지를 등록한 캐릭터 id 목록.
     *        비어 있거나 없으면 캐릭터 배정이 없는 이미지다.
     */
    fun plan(
        items: List<ScanItem>,
        characterIdsByName: Map<String, List<Long>>,
        pathByToken: Map<String, String>,
        characterIdsByPath: Map<String, List<Long>>
    ): Plan {
        val imports = ArrayList<ImportAction>()
        val moves = ArrayList<MoveAction>()
        val detaches = ArrayList<DetachAction>()
        val holds = ArrayList<Hold>()
        val ambiguous = LinkedHashSet<String>()
        var unknownTokens = 0
        var deeper = 0

        // 세트 후보를 폴더별로 모았다가 마지막에 2장 이상인 것만 세트로 만든다.
        val setNewItems = LinkedHashMap<String, MutableList<String>>()
        val setExistingPaths = LinkedHashMap<String, MutableList<String>>()
        val setKeyOfItem = HashMap<String, String>()

        // ── 1단계: 자리 해석 + 토큰 해석. 같은 이미지를 두 번 가리키는 파일은 전부 보류한다.
        data class Resolved(val item: ScanItem, val location: Location, val path: String?)
        val resolved = ArrayList<Resolved>(items.size)
        val pathUseCount = HashMap<String, Int>()

        for (item in items) {
            val location = classify(item.folders)
            if (location is Location.Skipped) continue
            if (location is Location.TooDeep) { deeper++; continue }

            val candidate = FolderNameToken.tokenCandidateOf(item.fileName)
            val path = candidate?.let { pathByToken[it] }
            if (candidate != null && path == null) unknownTokens++
            if (path != null) pathUseCount[path] = (pathUseCount[path] ?: 0) + 1
            resolved.add(Resolved(item, location, path))
        }

        // ── 2단계: 항목별 처리.
        for ((item, location, path) in resolved) {
            if (path != null && (pathUseCount[path] ?: 0) > 1) {
                holds.add(Hold(item, HoldReason.DUPLICATE_TOKEN))
                continue
            }
            val owners = path?.let { characterIdsByPath[it] }.orEmpty()
            val sharedOwners = owners.size >= 2

            when (location) {
                is Location.Root, is Location.UnassignedRoot -> {
                    if (path == null) {
                        imports.add(ImportAction(item, null, null))
                    } else if (sharedOwners) {
                        holds.add(Hold(item, HoldReason.SHARED_OWNERS))
                    } else if (owners.isNotEmpty()) {
                        detaches.add(DetachAction(item, path, owners))
                    }
                    // owners가 비어 있으면 이미 미배정이다 — 할 일 없음(계수도 하지 않는다).
                }

                is Location.Shared -> {
                    if (path == null) imports.add(ImportAction(item, null, null))
                    else holds.add(Hold(item, HoldReason.SHARED_FOLDER))
                }

                is Location.Named -> {
                    val matches = characterIdsByName[location.name.trim()].orEmpty()
                    if (matches.size >= 2) ambiguous.add(location.name)
                    val target = matches.singleOrNull()
                    if (target != null) {
                        // 캐릭터 폴더 — 수동 세트를 만들지 않는다(자동 링크가 묶는다).
                        if (path == null) {
                            imports.add(ImportAction(item, target, null))
                        } else if (sharedOwners) {
                            holds.add(Hold(item, HoldReason.SHARED_OWNERS))
                        } else if (owners != listOf(target)) {
                            moves.add(MoveAction(item, path, owners, target))
                        }
                        // 이미 그 캐릭터에만 배정돼 있으면 할 일 없음.
                    } else {
                        // 기타 이름(동명 보류 포함) — 배정은 건드리지 않고 폴더끼리 묶는다.
                        val key = location.name
                        if (path == null) {
                            setNewItems.getOrPut(key) { mutableListOf() }.add(item.id)
                            setKeyOfItem[item.id] = key
                            imports.add(ImportAction(item, null, key))
                        } else {
                            setExistingPaths.getOrPut(key) { mutableListOf() }.add(path)
                        }
                    }
                }

                is Location.UnassignedSet -> {
                    val key = "$FOLDER_UNASSIGNED/${location.name}"
                    if (path == null) {
                        setNewItems.getOrPut(key) { mutableListOf() }.add(item.id)
                        setKeyOfItem[item.id] = key
                        imports.add(ImportAction(item, null, key))
                    } else if (sharedOwners) {
                        // 해제는 보류하되 묶음에는 넣는다 — 링크는 되돌릴 수 있고 파괴적이지 않다.
                        holds.add(Hold(item, HoldReason.SHARED_OWNERS))
                        setExistingPaths.getOrPut(key) { mutableListOf() }.add(path)
                    } else {
                        if (owners.isNotEmpty()) detaches.add(DetachAction(item, path, owners))
                        setExistingPaths.getOrPut(key) { mutableListOf() }.add(path)
                    }
                }

                is Location.Skipped, is Location.TooDeep -> Unit  // 1단계에서 걸렀다
            }
        }

        // ── 3단계: 2장 이상인 폴더만 세트로 확정한다(1장짜리 링크 배지는 오해 — 인앱 규약과 동일).
        val linkSets = ArrayList<LinkSetAction>()
        val liveKeys = HashSet<String>()
        for (key in (setNewItems.keys + setExistingPaths.keys)) {
            val news = setNewItems[key].orEmpty()
            val existing = setExistingPaths[key].orEmpty()
            if (news.size + existing.size < 2) continue
            liveKeys.add(key)
            linkSets.add(LinkSetAction(key, news, existing))
        }
        // 세트가 성립하지 않은 폴더의 편입은 링크 없는 미배정으로 되돌린다.
        val finalImports = imports.map { action ->
            val key = setKeyOfItem[action.item.id]
            if (key != null && key !in liveKeys) action.copy(setKey = null) else action
        }

        return Plan(
            imports = finalImports,
            moves = moves,
            detaches = detaches,
            linkSets = linkSets,
            holds = holds,
            unknownTokenFiles = unknownTokens,
            ambiguousFolders = ambiguous.toList(),
            deeperIgnored = deeper
        )
    }
}
