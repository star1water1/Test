package com.novelcharacter.app.util

import com.google.gson.Gson
import com.novelcharacter.app.data.model.Character

/**
 * **"어느 이미지가 이 캐릭터를 대표하는가"의 단일 소스** (B-103 결정 D2·D3 — 순수 로직).
 *
 * 이 판단은 종전에 **아홉 종류·열아홉 지점**에 흩어져 있었고 **규칙이 넷으로 갈려 있었다**:
 * 언제나 0번(배너·순위·통계·어시스턴트) · 영속 랜덤 인덱스(캐릭터 목록) · 호출마다 랜덤
 * (세계관·작품 카드 연동) · 진입마다 랜덤 시작(상세). 그래서 대표를 고정해도 손댄 화면에서만
 * 듣고 나머지는 계속 0번을 보여 줄 참이었다 — **먼저 한 곳으로 모으는 것이 이 파일이다.**
 *
 * 사다리(D2):
 * 1. 지정한 대표가 **그 캐릭터의 목록 안에 있으면** 그것 ([Source.PINNED])
 * 2. 시드 랜덤 ([Source.RANDOM]) — **대결 점수가 있으면 균등이 아니라 가중이다**(B-104 ⓑ)
 * 3. 목록이 비었으면 없음 ([Source.NONE])
 *
 * **D9의 '비어 있는 칸'은 칸으로 채워지지 않았다.** 종전 이 자리는 *"대결이 이미지 축 승자를
 * 내면 2번 칸에 들어간다"*고 적혀 있었는데, 물었을 때 사용자가 되물어 갈음했다 —
 * *"대결 1위 그림이 대표가 되면 이미지 다양성이 아쉽잖아. 확률이면 모를까."* 그래서 승자는
 * **사다리의 칸이 아니라 3번 추첨의 무게**가 됐다([RepresentativeWeighting], 설계 13-5).
 * 사다리가 셋으로 줄어든 것이 그 갈음의 자취다.
 *
 * 랜덤 주기(D3 · 사용자 확정 ㄴ3·P-3): **시드 하나 = 화면 진입 한 번.**
 * 화면이 [newSeed]로 시드를 하나 뽑아 들고 있으면, 같은 화면 안에서는 스크롤 재바인드·검색·
 * 필터·정렬·저장에 **그림이 튀지 않고**(같은 시드 → 같은 결과) 나갔다 들어오면 바뀐다.
 * **상태를 저장하지 않으므로 쓰기가 0이다** — 종전 `ImageIndexPrefs`는 목록 진입마다 전량
 * 읽기(`loadAll`) + bind마다 쓰기가 있었고, 그 자리는 성능 수리 P2-1이 한 번 손댔다가
 * 재추첨 경로를 통째로 없애 **"영구 고정" 회귀**를 낸 곳이다(세션 로그 1-12, 실기기 보고).
 */
object CharacterRepresentativeImage {

    /** 무엇이 골랐는가. */
    enum class Source {
        /** 사용자가 지정한 대표. */
        PINNED,

        /** 지정이 없어(또는 지정이 목록에 없어) 시드 랜덤으로 골랐다. */
        RANDOM,

        /** 보여 줄 이미지가 없다. */
        NONE
    }

    /**
     * @property path 표시할 경로. 없으면 null.
     * @property index [path]가 목록에서 몇 번째인가. 없으면 -1 (상세 ViewPager의 시작 위치가 이것을 쓴다).
     * @property pinnedMissing **지정은 있는데 목록에 없다.** ㄷ1의 **사후 고지** 신호다 —
     *   앱 밖에서 파일이 사라졌거나 폴더 왕복이 경로를 바꾼 자리이며, 사전 고지가 원리적으로
     *   불가능하다. **이때도 포인터를 조용히 지우지 않는다**(D6ⓑ) — 폴더 왕복은 파일을 되돌려
     *   놓을 수 있어서, 잠깐 안 보인다고 지우면 돌아왔을 때 지정이 사라져 있다.
     */
    data class Pick(
        val path: String?,
        val source: Source,
        val pinnedMissing: Boolean,
        val index: Int
    )

    private val NONE = Pick(path = null, source = Source.NONE, pinnedMissing = false, index = -1)

    // ── 고르기 ──

    /**
     * 화면이 진입할 때 한 번 뽑는 시드. `onViewCreated`에서 만들어 어댑터에 넘긴다.
     *
     * **전역 싱글턴을 두지 않는다** — 두 화면이 동시에 살아 있을 때 한쪽의 진입이
     * 다른 쪽 그림을 바꾼다.
     */
    fun newSeed(): Long = java.util.concurrent.ThreadLocalRandom.current().nextLong()

    /** `imagePaths` JSON 배열 → 공백을 걸러낸 경로 목록. 파싱 실패면 빈 목록. */
    fun paths(imagePathsJson: String?): List<String> {
        if (imagePathsJson.isNullOrBlank()) return emptyList()
        val list: List<String?>? = runCatching {
            Gson().fromJson<List<String?>>(imagePathsJson, GsonTypes.STRING_LIST)
        }.getOrNull()
        return list.orEmpty().filterNotNull().filter { it.isNotBlank() }
    }

    /**
     * [paths] + [pickFrom]. 호출부 대부분이 이것을 쓴다.
     *
     * @param weights 추첨 무게(B-104 ⓑ). 기본값 null이 **종전 그대로의 균등 추첨**이므로,
     *   가중치를 들지 않는 호출부는 한 글자도 바뀌지 않는다.
     */
    fun pick(
        imagePathsJson: String?,
        representativePath: String?,
        seed: Long,
        characterId: Long,
        weights: RepresentativeWeighting.Weights? = null
    ): Pick = pickFrom(paths(imagePathsJson), representativePath, seed, characterId, weights)

    /**
     * 이미 경로 목록을 손에 들고 있는 호출부용(통계 드릴다운 등).
     *
     * @param weights 경로별 추첨 무게 — [RepresentativeWeighting]이 만든다. null이면 균등이다.
     *   목록에 있으나 표에 없는 경로는 [RepresentativeWeighting.Weights.unknown](= 앵커에 선
     *   그림의 무게)로 친다. **시드와 함께 다녀야 한다** — 무게가 화면 도중에 바뀌면 같은
     *   시드가 다른 그림을 골라, B-103이 없앤 *"화면마다 다른 그림"*이 되살아난다.
     */
    fun pickFrom(
        paths: List<String>,
        representativePath: String?,
        seed: Long,
        characterId: Long,
        weights: RepresentativeWeighting.Weights? = null
    ): Pick {
        val pinned = ImagePathMatch.canonical(representativePath)
        val pinnedIndex = if (pinned.isEmpty()) -1 else ImagePathMatch.indexIn(paths, pinned)

        if (pinnedIndex >= 0) {
            return Pick(paths[pinnedIndex], Source.PINNED, pinnedMissing = false, index = pinnedIndex)
        }

        // 지정이 있었는데 못 찾았다 — 목록이 비었든 파일이 사라졌든 사용자가 정한 것이 안 보인다.
        // 고지 신호는 이 사실 그대로 낸다. 무엇을 보여 줄지(랜덤/없음)는 아래가 따로 정한다.
        val missing = pinned.isNotEmpty()

        if (paths.isEmpty()) return NONE.copy(pinnedMissing = missing)

        val index = weightedIndex(seed, characterId, paths, weights)
        return Pick(paths[index], Source.RANDOM, pinnedMissing = missing, index = index)
    }

    /** 표시할 경로만 필요한 자리를 위한 지름길. */
    fun path(
        imagePathsJson: String?,
        representativePath: String?,
        seed: Long,
        characterId: Long,
        weights: RepresentativeWeighting.Weights? = null
    ): String? = pick(imagePathsJson, representativePath, seed, characterId, weights).path

    /**
     * `index = floorMod(mix(seed, characterId), size)` (D3).
     *
     * 같은 시드 안에서 캐릭터마다 **독립적**이어야 한다 — 단순히 `seed + id`를 나머지 연산에
     * 넣으면 목록이 통째로 한 칸씩 밀리는 꼴이 되어, 이웃한 id들이 같은 방향으로 움직인다.
     * splitmix64 마무리 함수를 두 번 걸어 비트를 흩는다.
     */
    fun randomIndex(seed: Long, characterId: Long, size: Int): Int {
        if (size <= 1) return 0
        val hash = splitMix64(seed xor splitMix64(characterId))
        val n = size.toLong()
        return (((hash % n) + n) % n).toInt()
    }

    /**
     * 무게가 있으면 가중 추첨, 없으면 [randomIndex] (B-104 ⓑ).
     *
     * **무게가 없는 길이 종전 코드 그대로인 것이 요점이다.** 무게가 전부 같을 때도 여기로
     * 오지 않게 [RepresentativeWeighting.weights]가 null을 내므로, 대결을 안 한 캐릭터는
     * **한 비트도 다르지 않은 그림**을 본다.
     *
     * 뽑는 법: 같은 splitMix64 해시를 `[0,1)` 실수로 펴서 누적 무게를 걷는다. 시드가 같으면
     * 결과도 같다([randomIndex]와 같은 성질) — 스크롤 재바인드에 그림이 튀지 않는다.
     */
    fun weightedIndex(
        seed: Long,
        characterId: Long,
        paths: List<String>,
        weights: RepresentativeWeighting.Weights?
    ): Int {
        if (paths.size <= 1) return 0
        if (weights == null || weights.byPath.isEmpty()) return randomIndex(seed, characterId, paths.size)

        var total = 0.0
        val each = DoubleArray(paths.size) { index ->
            // **경로를 `String?`로 받는다** — 선언은 `List<String>`이지만 이 목록은 Gson이
            // 파싱한 `imagePaths`라 **런타임에 null·빈 문자열이 섞여 들어온다**(어댑터가 날
            // Gson으로 읽는다). 여기서 `path.trim()`을 바로 부르면 그 자리에서 죽는다.
            //
            // 표에 없는 경로는 [RepresentativeWeighting.Weights.unknown] — *"아직 안 겨룬 그림"*의
            // 무게이고 **앵커에 선다.** 1.0으로 치면 그것이 곧 1위의 무게라(모든 무게가 `(0,1]`)
            // 방금 넣은 그림이 가장 자주 뜬다. 0으로 치면 반대로 영영 안 뜬다.
            val path: String? = paths.getOrNull(index)
            val w = weights.of(path)
            val safe = if (w.isFinite() && w > 0.0) w else weights.unknown.takeIf { it > 0.0 } ?: 1.0
            total += safe
            safe
        }
        if (total <= 0.0 || !total.isFinite()) return randomIndex(seed, characterId, paths.size)

        val hash = splitMix64(seed xor splitMix64(characterId))
        // 위 53비트를 [0,1)로 편다 — 나머지 연산과 달리 무게 비율을 그대로 쓸 수 있다.
        val unit = (hash ushr 11).toDouble() / (1L shl 53).toDouble()
        var cursor = unit * total
        for (index in each.indices) {
            cursor -= each[index]
            if (cursor < 0.0) return index
        }
        // 부동소수 반올림으로 끝을 넘겼다 — 마지막 자리가 답이다(빈손으로 돌아가지 않는다).
        return paths.size - 1
    }

    private fun splitMix64(input: Long): Long {
        var z = input + -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    // ── 쓰기 정합 (D5) ──
    //
    // 포인터는 `imagePaths` 목록을 가리킨다. **목록을 고치는 모든 경로가 포인터도 함께 고친다** —
    // 빠뜨려도 오류는 나지 않고 포인터만 조용히 어긋나므로, 아래 둘을 거치지 않는 쓰기가 있으면
    // 그것이 곧 결함이다. 처분은 셋뿐이다:
    //   경로가 바뀐다  → [follow]로 따라 고친다 (이동·개명·되돌리기 · 폴더 왕복 편입 · 엑셀 재매핑 · 월드패키지 복원)
    //   경로가 사라진다 → [retain]이 비운다 + 호출부가 고지한다 (삭제 · 정리 · 폴더 왕복 제외)
    //   목록이 통째로 바뀐다 → [retain]이 새 목록에 없으면 비운다 (폼 저장)

    /**
     * 경로가 바뀐 자리를 따라간다. [renames]는 `옛 경로 → 새 경로`.
     * 해당 없으면 [representativePath]를 그대로 돌려준다.
     */
    fun follow(representativePath: String?, renames: Map<String, String>): String {
        val current = representativePath?.trim().orEmpty()
        if (current.isEmpty() || renames.isEmpty()) return current
        renames[current]?.let { return it }
        // 원문 키로 못 찾으면 정규화해서 한 번 더 본다(호출부가 어느 형태를 넣었는지에 기대지 않는다).
        val canon = ImagePathMatch.canonical(current)
        renames.entries.firstOrNull { ImagePathMatch.canonical(it.key) == canon }?.let { return it.value }
        return current
    }

    /**
     * 새 목록에 남아 있으면 유지, 아니면 **빈 문자열**(= 지정 없음).
     *
     * 반환이 비었는데 [representativePath]가 비어 있지 않았다면 **대표가 풀린 것**이므로
     * 호출부는 고지해야 한다(ㄷ1). 그 판정은 [wasDropped]가 대신 세어 준다.
     */
    fun retain(representativePath: String?, newPaths: List<String>): String {
        val current = representativePath?.trim().orEmpty()
        if (current.isEmpty()) return ""
        val index = ImagePathMatch.indexIn(newPaths, current)
        // 목록에 실린 형태를 그대로 유지한다 — 저장된 문자열과 목록의 문자열이 어긋나면
        // 다음 대조가 정규화에 기대게 되고, 정규화가 실패하는 날 포인터가 죽는다.
        return if (index >= 0) newPaths[index] else ""
    }

    /** 쓰기 한 번으로 대표 지정이 풀렸는가 — 고지 여부의 판정. */
    fun wasDropped(before: String?, after: String?): Boolean =
        !before.isNullOrBlank() && after.isNullOrBlank()

    /**
     * [follow] 뒤 [retain] — 쓰기 지점 대부분이 이 한 줄이면 된다.
     * 경로가 바뀌었으면 따라가고, 그러고도 새 목록에 없으면 비운다.
     */
    fun resolveAfterWrite(
        representativePath: String?,
        newPaths: List<String>,
        renames: Map<String, String> = emptyMap()
    ): String = retain(follow(representativePath, renames), newPaths)
}

/**
 * **`imagePaths`를 바꾸는 유일하게 옳은 방법** — 포인터를 함께 정합시킨다(B-103 D5).
 *
 * `character.copy(imagePaths = ...)`를 직접 쓰면 대표 포인터가 그 자리에서만 조용히 어긋난다.
 * 오류는 나지 않고 다음 화면이 엉뚱한 그림을 보여 줄 뿐이라, **빠뜨렸다는 사실 자체를
 * 아무도 모른다.** `tools/check_image_pointer.sh`가 새 직접 호출을 막는다.
 *
 * @param newImagePathsJson 새 `imagePaths` JSON.
 * @param renames 경로가 바뀐 자리의 `옛 경로 → 새 경로`. 삭제·제외처럼 경로가 사라지기만
 *   하는 자리는 비워 두면 된다 — 그때는 새 목록에 없으므로 포인터가 저절로 풀린다.
 */
fun Character.withImagePaths(
    newImagePathsJson: String,
    renames: Map<String, String> = emptyMap()
): Character = copy(
    imagePaths = newImagePathsJson,
    representativeImagePath = CharacterRepresentativeImage.resolveAfterWrite(
        representativeImagePath,
        CharacterRepresentativeImage.paths(newImagePathsJson),
        renames
    )
)
