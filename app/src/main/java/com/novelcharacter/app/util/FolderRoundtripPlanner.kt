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
 * | `기타/` · `_기타/` **서랍** | 편입 + 미배정 + **링크 없음**(낱개) | **묶음만 해제**(배정은 유지) |
 * | `<그 외 이름>/` | 편입 + 미배정 + 그 폴더끼리 **링크 세트** | 배정 불변 + 그 폴더 세트로 링크 |
 * | 정리 폴더 직속 · `_미배정/` 직속 | 편입 + 미배정(링크 없음) | 캐릭터 배정 **전부 해제 + 링크 묶음도 해제** |
 * | `_미배정/<세트명>/` | 편입 + 미배정 + 링크 세트 | 배정 해제 + 그 세트로 링크(**기존 묶음은 병합**) |
 * | `_공유/` | 편입 + 미배정(고지) | **반영 제외**(고지만) |
 * | `_처리됨/` | 스캔 제외 | 스캔 제외 |
 *
 * ### 되돌리는 자리 vs 묶는 자리 — 링크를 푸는 곳은 하나뿐이다
 *
 * 정리 폴더 직속·`_미배정/` 직속은 **되돌리는 자리**다. 신규 파일이 "미배정 + 링크 없음"으로
 * 들어오는 자리이므로, 토큰 파일도 같은 뜻이어야 한다 — 배정도 묶음도 없는 상태로 돌아간다.
 * 그래서 **배정이 없고 묶음만 있는 이미지도 여기서는 할 일이 있다**([DetachAction.unlinks]).
 * 이 갈래가 없으면 `<기타 이름>/`·`_미배정/<세트명>/`이 만든 세트를 폴더로 되돌릴 길이 없다.
 *
 * 반대로 `_미배정/<세트명>/`은 **묶는 자리**라 링크를 풀지 않는다. 여기서 먼저 풀어 버리면
 * 뒤이은 링크 세트가 기존 그룹을 흡수할 수 없어, 규약상 **병합**이어야 할 것이 조용히
 * **이동**이 된다(같이 묶여 있던 이미지가 남겨진다). 사전 확인이 "폴더에 없던 이미지 M장이
 * 함께 묶입니다"라고 이미 약속한 뒤이므로, 고지와 실제가 어긋난다.
 *
 * ### 서랍은 세 번째 자리다 — 되돌리는 자리와 헷갈리지 말 것
 *
 * `기타/`·`_기타/`는 **낱개로 두는 자리**다([Location.Misc], 결정 D-2). 되돌리는 자리와
 * 신규 파일에 대해서는 같은 뜻이지만, 토큰 파일에서 갈린다 — 서랍은 **묶음만** 풀고 캐릭터
 * 배정은 그대로 둔다([UnlinkOnlyAction]). 배정까지 떼려는 사용자에게는 `_미배정/` 직속이
 * 그대로 남아 있으므로, 두 자리는 서로를 대체하지 않고 역할이 갈린다.
 *
 * `기타/`(접두 없음)는 [classify]에서 [Location.Named]로 왔다가 **해석 단계에서** 강등된다.
 * 그 이름의 캐릭터가 실재하면 캐릭터가 이기고([Plan.miscReadAsCharacter]로 고지), 동명이인·
 * 미지 코드도 기존 해소 사다리를 그대로 탄다 — '기타'라는 이름이 질문을 우회하지 못한다.
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

    /**
     * 잡동사니 서랍 — 낱개로 들이는 자리(설계 `image_folder_tag_ai` 2장, 결정 D-1·D-2).
     *
     * `_미배정/` 직속과 헷갈리지 말 것. 그쪽은 **되돌리는 자리**라 토큰 파일의 배정까지 떼지만,
     * 여기는 **비파괴 서랍**이라 묶음만 푼다. 신규 파일에 대해서만 둘이 같은 뜻이다.
     */
    const val FOLDER_MISC = "_기타"

    /**
     * 예약 접두 없이도 서랍으로 읽는 이름. **캐릭터가 우선이다**(D-1) — 이 이름의 캐릭터가
     * 실재하면 캐릭터 폴더로 해석하고 [Plan.miscReadAsCharacter]로 고지한다.
     * 서랍임을 확정하고 싶은 사용자에게는 [FOLDER_MISC]가 남아 있다.
     */
    const val MISC_PLAIN_NAME = "기타"

    /** 예약 폴더 접두. 사용자 폴더가 이 접두를 쓰면 캐릭터 이름으로 해석하지 않는다. */
    const val RESERVED_PREFIX = "_"

    /** 나열이 내려가는 최대 깊이(정리 폴더 기준). `_미배정/<세트명>/`이 2단계라 2로 잡는다. */
    const val MAX_SCAN_DEPTH = 2

    /** 스캔 항목 1건. [folders]는 정리 폴더 기준 폴더 경로(직속이면 빈 목록). */
    data class ScanItem(val id: String, val folders: List<String>, val fileName: String)

    /**
     * 캐릭터 폴더명이 가리키는 대상을 정하는 **해소 사다리** — 위에서부터 먼저 맞는 것이 이긴다.
     *
     * | # | 폴더명 | 결과 |
     * |---|--------|------|
     * | 1 | `홍길동` (그 이름이 유일) | 그 캐릭터 |
     * | 2 | `홍길동#a1b2c3d4e5f60718` | **코드로 확정** — 앱이 내보낸 폴더 |
     * | 3 | `홍길동(은하전기)` | 작품으로 좁혀 1명이면 확정 |
     * | 4 | 그래도 둘 이상 | [ambiguousFolders]에 실어 **사용자에게 묻는다** |
     *
     * **1번이 2·3번보다 먼저인 것이 중요하다.** 사용자가 이미 `홍길동(가명)`이라는 *이름*을 쓰고
     * 있었다면 그 폴더는 그 캐릭터의 것이지, "홍길동인데 작품이 가명"이 아니다. 정확 일치를
     * 먼저 보지 않으면 이 규약이 사용자가 쓰던 이름을 빼앗는다.
     *
     * @param characterIdsByName 트림된 이름 → 그 이름을 쓰는 캐릭터 id 목록.
     * @param characterIdByCode 안정 식별자 → 캐릭터 id.
     * @param novelIdByCharacterId 캐릭터 id → 그 캐릭터의 작품 id(없으면 미등재).
     * @param novelIdsByTitle 트림된 작품 제목 → 작품 id 목록. 제목이 겹치면 좁히지 않는다.
     * @param choices 사용자가 고른 폴더명 → 캐릭터 id (4번 질문의 답).
     */
    class CharacterFolderResolver(
        private val characterIdsByName: Map<String, List<Long>>,
        private val characterIdByCode: Map<String, Long> = emptyMap(),
        private val novelIdByCharacterId: Map<Long, Long> = emptyMap(),
        private val novelIdsByTitle: Map<String, List<Long>> = emptyMap(),
        private val choices: Map<String, Long> = emptyMap()
    ) {
        /** 해소 결과. */
        sealed class Result {
            /** 대상 확정. */
            data class Found(val characterId: Long) : Result()
            /** 캐릭터 폴더가 아니다(그런 이름의 캐릭터가 없다) — '기타 이름'으로 다룬다. */
            object NotCharacter : Result()
            /** 동명이 둘 이상이고 좁힐 근거도 없다 — 물어봐야 한다. */
            object Ambiguous : Result()
            /** `#코드`가 붙어 있으나 그런 코드가 없다 — 조용히 이름으로 폴백하지 않는다. */
            object UnknownCode : Result()
        }

        fun resolve(folderName: String): Result {
            val raw = keyOf(folderName)
            val exact = characterIdsByName[raw].orEmpty()

            // 1. 폴더명 전체가 캐릭터 이름과 정확 일치하고 유일하면 그것이 답이다.
            if (exact.size == 1) return Result.Found(exact[0])

            // 2. `이름#코드` — 코드가 대상을 정한다(R-1).
            val parsed = FolderNameToken.parseCharacterFolderName(raw)
            if (parsed.code != null) {
                // 코드가 있는데 못 찾으면 이름으로 폴백하지 않는다 — 폴백은 곧 오배정 위험이고,
                // 코드를 적었다는 것은 대상을 특정하려는 의도다. 사유를 남겨 고지한다(R-17).
                return characterIdByCode[parsed.code]?.let { Result.Found(it) } ?: Result.UnknownCode
            }

            // 사용자가 이미 고른 답이 있으면 그것이 이긴다.
            choices[raw]?.let { return Result.Found(it) }

            // 폴더명 자체가 동명인 이름이면 좁힐 근거가 없다 — 괄호도 없으니 물어봐야 한다.
            if (exact.size >= 2) return Result.Ambiguous

            // 3. `이름(작품명)` — 이름만으로 못 가를 때 작품으로 좁힌다.
            val hint = parseNovelHint(raw) ?: return Result.NotCharacter
            val byName = characterIdsByName[hint.name].orEmpty()
            if (byName.isEmpty()) return Result.NotCharacter
            if (byName.size == 1) return Result.Found(byName[0])

            val novelIds = novelIdsByTitle[hint.novelTitle].orEmpty()
            // 같은 제목의 작품이 둘이면 좁히는 것 자체가 근거 없는 선택이 된다.
            if (novelIds.size != 1) return Result.Ambiguous
            val narrowed = byName.filter { novelIdByCharacterId[it] == novelIds[0] }
            return if (narrowed.size == 1) Result.Found(narrowed[0]) else Result.Ambiguous
        }

        /**
         * 폴더명의 정규화 키 — [Plan.ambiguousFolders]에 실리는 값이자 [choices]의 키다.
         * 물어본 키와 답한 키가 다르면 사용자가 고른 답이 조용히 무시된다.
         */
        fun keyOf(folderName: String): String = folderName.trim()

        /** 사람이 읽을 이름 — 고지 문구에 `#코드`를 그대로 보이면 읽기 어렵다. */
        fun displayName(folderName: String): String {
            val raw = folderName.trim()
            if (characterIdsByName.containsKey(raw)) return raw
            val parsed = FolderNameToken.parseCharacterFolderName(raw)
            if (parsed.code != null) return parsed.name
            return parseNovelHint(raw)?.name ?: raw
        }

        private data class NovelHint(val name: String, val novelTitle: String)

        /** `홍길동(은하전기)` → (홍길동, 은하전기). 꼴이 아니면 null. */
        private fun parseNovelHint(raw: String): NovelHint? {
            if (!raw.endsWith(')')) return null
            val open = raw.lastIndexOf('(')
            if (open <= 0) return null
            val name = raw.substring(0, open).trim()
            val title = raw.substring(open + 1, raw.length - 1).trim()
            if (name.isEmpty() || title.isEmpty()) return null
            return NovelHint(name, title)
        }
    }

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
        /** `_기타/` — 잡동사니 서랍. `기타/`는 [Location.Named]로 왔다가 해석 단계에서 강등된다. */
        object Misc : Location()
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

    /**
     * 배정 해제 — 토큰 파일이 정리 폴더 직속·`_미배정/`에서 발견됐다.
     *
     * @param fromCharacterIds 떼어낼 캐릭터 배정. **비어 있을 수 있다** — 이미 미배정이지만
     *        묶음만 남은 이미지를 되돌리는 자리에 둔 경우가 그렇다(그때는 [unlinks]가 참이다).
     * @param unlinks 링크 묶음까지 푸는가. 되돌리는 자리(직속)에서만 참이고,
     *        묶는 자리(`_미배정/<세트명>/`)에서는 거짓이다 — 거기서 풀면 병합이 이동이 된다.
     */
    data class DetachAction(
        val item: ScanItem,
        val path: String,
        val fromCharacterIds: List<Long>,
        val unlinks: Boolean = false
    )

    /**
     * 묶음만 해제 — 토큰 파일이 서랍([Location.Misc])에서 발견됐다. **캐릭터 배정은 건드리지
     * 않는다**(D-2).
     *
     * [DetachAction]에 플래그를 더하지 않은 이유: 그 자료형은 "배정을 뗀다"가 본체이고
     * `unlinks`는 그 안의 갈래다. 배정을 떼지 않는 동작을 거기 실으면 이름이 거짓이 되고,
     * 설계 11-1장이 정확히 그 혼선("계획이 정하는가 실행부가 판정하는가")으로 값을 치렀다.
     * R-13(집계의 셀 단위가 다르면 함수를 나눈다)의 자료형 판이다.
     *
     * 그룹 id는 싣지 않는다 — 플래너는 [plan]의 `linkedPaths`로 "묶여 있다"만 알고, 어느
     * 그룹인지는 실행부가 DB에서 읽는다. 여기 실으면 계획 시점과 실행 시점 사이에 그룹이
     * 바뀌었을 때 낡은 값으로 지우게 된다.
     */
    data class UnlinkOnlyAction(val item: ScanItem, val path: String)

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
     * @param ambiguousFolders 동명 캐릭터가 둘 이상이고 좁힐 근거도 없어 **사용자에게 물어야 하는**
     *        폴더명. 사용자가 고르면 그 답을 [CharacterFolderResolver]에 실어 계획을 다시 세운다.
     * @param unknownCodeFolders `#코드`가 붙어 있으나 그런 코드의 캐릭터가 없는 폴더명.
     *        이름으로 조용히 폴백하지 않고 고지한다 — 코드를 적었다는 것은 대상을 특정하려는
     *        의도이므로, 폴백은 그 의도를 배신하는 오배정이 될 수 있다(R-17).
     * @param deeperIgnored 규약 밖 깊이라 무시한 파일 수.
     * @param miscReadAsCharacter `기타/`라고 썼지만 그 이름의 캐릭터가 실재해 **캐릭터 폴더로
     *        읽은** 폴더명(D-1). 조용히 넘어가면 사용자는 서랍에 넣은 줄 알고 배정이 바뀐 것을
     *        모른다 — 고지하고 `_기타/`라는 빠져나갈 길을 함께 알린다(R-17).
     * @param aiTagFolders AI 태그 제안 대상 '그 외' 폴더명 → 그 폴더의 **신규 파일** 항목 id.
     *        경로는 편입 뒤에야 정해지므로 실행부가 해석한다. 캐릭터 폴더·서랍·예약 폴더는
     *        들어오지 않는다(설계 2-1·2-4).
     * @param aiTagExistingPaths 같은 폴더의 **토큰 파일** 경로. 신규와 나눈 이유는 하나는
     *        편입 후에야 경로가 생기고 다른 하나는 이미 있기 때문이다 — 한 목록에 담으면
     *        실행부가 "이 문자열이 id인가 경로인가"를 추측해야 한다. 둘 다 "이번에 그 폴더에서
     *        온" 이미지이므로 태그 적용 대상은 합집합이다(D-4).
     */
    data class Plan(
        val imports: List<ImportAction> = emptyList(),
        val moves: List<MoveAction> = emptyList(),
        val detaches: List<DetachAction> = emptyList(),
        val unlinkOnly: List<UnlinkOnlyAction> = emptyList(),
        val linkSets: List<LinkSetAction> = emptyList(),
        val holds: List<Hold> = emptyList(),
        val unknownTokenFiles: Int = 0,
        val ambiguousFolders: List<String> = emptyList(),
        val unknownCodeFolders: List<String> = emptyList(),
        val deeperIgnored: Int = 0,
        val miscReadAsCharacter: List<String> = emptyList(),
        val aiTagFolders: Map<String, List<String>> = emptyMap(),
        val aiTagExistingPaths: Map<String, List<String>> = emptyMap(),
        /**
         * 서랍으로 **낱개 편입**되는 신규 파일 수. [imports] 안에서 자리로는 구별되지 않으므로
         * (되돌리는 자리·`_공유/`의 편입도 배정·세트가 없다) 세어 둔다 — 사용자가 요청한 동작이
         * 실제로 그렇게 계획됐는지 확인할 유일한 수다.
         */
        val miscImported: Int = 0
    ) {
        /** 실제로 파일을 만지는 항목 수 — 진행도 총량. */
        val actionCount: Int get() = imports.size + moves.size + detaches.size + unlinkOnly.size

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
        // 예약이므로 반드시 여기서 갈라야 한다 — 아래 `size == 1` 분기가 미등재 `_xxx`를
        // 전부 Named로 흘려보내므로, 빠뜨리면 `_기타/`가 "이름이 `_기타`인 캐릭터"를 찾는다.
        folders[0] == FOLDER_MISC ->
            if (folders.size == 1) Location.Misc else Location.TooDeep
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
     * @param linkedPaths 현재 링크 묶음에 속한 이미지 경로. **되돌리는 자리에서만 쓴다** —
     *        배정이 없어도 묶음이 있으면 할 일이 있다는 판정의 근거다(위 KDoc "되돌리는 자리").
     *        비워 두면 묶음을 푸는 계획이 서지 않으므로, 호출부는 반드시 실어 보낸다.
     */
    fun plan(
        items: List<ScanItem>,
        characterIdsByName: Map<String, List<Long>>,
        pathByToken: Map<String, String>,
        characterIdsByPath: Map<String, List<Long>>,
        resolver: CharacterFolderResolver = CharacterFolderResolver(characterIdsByName),
        linkedPaths: Set<String> = emptySet()
    ): Plan {
        val imports = ArrayList<ImportAction>()
        val moves = ArrayList<MoveAction>()
        val detaches = ArrayList<DetachAction>()
        val unlinkOnly = ArrayList<UnlinkOnlyAction>()
        val holds = ArrayList<Hold>()
        val ambiguous = LinkedHashSet<String>()
        val unknownCode = LinkedHashSet<String>()
        val miscAsCharacter = LinkedHashSet<String>()
        val aiTagFolders = LinkedHashMap<String, MutableList<String>>()
        val aiTagExistingPaths = LinkedHashMap<String, MutableList<String>>()
        var unknownTokens = 0
        var deeper = 0
        var miscImported = 0

        /**
         * 서랍 처리 — 신규는 낱개로 들이고, 토큰 파일은 묶음만 푼다(D-2).
         * `_기타/`와 강등된 `기타/`가 **같은 함수**를 타야 둘의 뜻이 갈리지 않는다.
         *
         * 소유자 수(C-2)를 보지 않는 이유: 그 보류는 **배정을 건드릴 때**의 안전장치인데
         * 서랍은 배정을 건드리지 않는다. 링크 해제는 되돌릴 수 있고 파괴적이지 않다
         * (`_미배정/<세트명>/`이 보류 중에도 묶음에는 넣는 것과 같은 판단).
         */
        fun handleMisc(item: ScanItem, path: String?) {
            if (path == null) {
                imports.add(ImportAction(item, null, null))
                miscImported++
            } else if (path in linkedPaths) {
                unlinkOnly.add(UnlinkOnlyAction(item, path))
            }
            // 이미 낱개면 할 일 없음 — 계수도 하지 않는다(되돌리는 자리와 같은 규약).
        }

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
                    // 되돌리는 자리 — 배정과 묶음을 **모두** 없앤 상태로 돌린다.
                    // 배정이 없어도 묶음이 있으면 할 일이 있다. 종전에는 owners만 보고 넘겨서,
                    // `<기타 이름>/`·`_미배정/<세트명>/`이 만든 세트를 폴더로 되돌릴 길이 없었다.
                    val linked = path != null && path in linkedPaths
                    if (path == null) {
                        imports.add(ImportAction(item, null, null))
                    } else if (sharedOwners) {
                        // 소유자가 둘 이상이면 배정도 묶음도 건드리지 않는다(C-2) — 보류는
                        // 배정에 대한 판단이지만, 반쪽만 반영하면 "보류했다면서 뭔가 했다"가 된다.
                        holds.add(Hold(item, HoldReason.SHARED_OWNERS))
                    } else if (owners.isNotEmpty() || linked) {
                        detaches.add(DetachAction(item, path, owners, unlinks = linked))
                    }
                    // 배정도 묶음도 없으면 이미 되돌아온 상태다 — 할 일 없음(계수도 하지 않는다).
                }

                is Location.Shared -> {
                    if (path == null) imports.add(ImportAction(item, null, null))
                    else holds.add(Hold(item, HoldReason.SHARED_FOLDER))
                }

                is Location.Misc -> handleMisc(item, path)

                is Location.Named -> {
                    val resolution = resolver.resolve(location.name)
                    val target = when (resolution) {
                        is CharacterFolderResolver.Result.Found -> resolution.characterId
                        is CharacterFolderResolver.Result.Ambiguous -> { ambiguous.add(resolver.keyOf(location.name)); null }
                        is CharacterFolderResolver.Result.UnknownCode -> { unknownCode.add(resolver.keyOf(location.name)); null }
                        is CharacterFolderResolver.Result.NotCharacter -> null
                    }
                    // 서랍 강등은 **그런 캐릭터가 없을 때만**이다(D-1 캐릭터 우선).
                    // Ambiguous·UnknownCode는 캐릭터를 가리키려던 의도이므로 기존 해소 사다리에
                    // 그대로 태운다 — '기타'라는 이름이 동명이인 질문을 우회하게 두지 않는다.
                    val isDrawer = resolution is CharacterFolderResolver.Result.NotCharacter &&
                        resolver.keyOf(location.name) == MISC_PLAIN_NAME
                    if (isDrawer) {
                        handleMisc(item, path)
                    } else if (target != null) {
                        if (resolver.keyOf(location.name) == MISC_PLAIN_NAME) {
                            miscAsCharacter.add(resolver.keyOf(location.name))
                        }
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
                        // AI 태그 대상은 **진짜 '그 외' 폴더뿐**이다. 동명 보류·미지 코드는
                        // 캐릭터를 가리키려던 이름이라, 그 이름에서 태그를 뽑으면 캐릭터 이름이
                        // 태그가 된다. 신규·토큰 파일 모두 "이번에 그 폴더에서 온" 것이므로
                        // 함께 싣는다(D-4) — 경로 해석은 실행부 몫이다.
                        if (resolution is CharacterFolderResolver.Result.NotCharacter) {
                            val folderKey = resolver.keyOf(key)
                            if (path == null) {
                                aiTagFolders.getOrPut(folderKey) { mutableListOf() }.add(item.id)
                            } else {
                                aiTagExistingPaths.getOrPut(folderKey) { mutableListOf() }.add(path)
                            }
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
                        // 묶는 자리다 — 배정만 뗀다(`unlinks = false`). 여기서 묶음을 먼저 풀면
                        // 아래 링크 세트가 기존 그룹을 흡수할 수 없어 병합이 이동이 된다.
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
            unlinkOnly = unlinkOnly,
            linkSets = linkSets,
            holds = holds,
            unknownTokenFiles = unknownTokens,
            ambiguousFolders = ambiguous.toList(),
            unknownCodeFolders = unknownCode.toList(),
            deeperIgnored = deeper,
            miscReadAsCharacter = miscAsCharacter.toList(),
            aiTagFolders = aiTagFolders.mapValues { it.value.toList() },
            aiTagExistingPaths = aiTagExistingPaths.mapValues { it.value.toList() },
            miscImported = miscImported
        )
    }
}
