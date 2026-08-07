package com.novelcharacter.app.util

import com.novelcharacter.app.ai.AiPromptPolicy

/**
 * **"이 요청에 어느 그림을 붙이는가"의 단일 소스** (A-7 · 설계 `feature_roadmap_2026-08` 2-2).
 *
 * 순수 판정이다 — 파일을 읽지도, 비트맵을 만들지도 않는다. 그 몫은 [AiImagePreparer]이고,
 * 여기서 정하는 것은 **목록에서 무엇을 몇 장 고르는가**뿐이다. 갈라 둔 이유는 고르기 규칙이
 * 세 화면(짧은 값 추천 · 서술형 작성 · 보완 재요청)에 걸쳐 같아야 하는데, 비트맵이 섞이면
 * 어느 것도 실행으로 검증할 수 없기 때문이다.
 *
 * 규칙(설계 2-2 표):
 * 1. **대표 반드시 포함**을 켜면 지정 대표가 항상 첫 장이다. 지정이 없거나 목록에 없으면
 *    [Plan.representativeMissing]로 **그 사실을 말하고** 전부 랜덤으로 채운다 —
 *    조용히 다른 그림을 '대표'라 부르지 않는다.
 * 2. 나머지는 시드 랜덤(중복 없이). 같은 시드면 같은 결과라 다이얼로그가 다시 그려져도
 *    고른 그림이 튀지 않는다([CharacterRepresentativeImage]의 시드 규약과 같은 태도).
 * 3. 사용자가 직접 고른 것([manual])이 있으면 그것이 전부다 — 랜덤도 대표 강제도 끼어들지
 *    않는다. 자율성 우선: 사람이 집은 것을 앱이 덮으면 고르는 경로가 있으나 마나다.
 */
object AiImageAttach {

    /**
     * @property paths 실제로 붙일 경로. 순서가 곧 프롬프트의 `이미지 1`, `이미지 2`… 번호다.
     * @property representativeMissing '대표 반드시 포함'을 켰는데 지정 대표를 목록에서
     *   찾지 못했다. 화면은 이 신호를 **반드시 고지**한다(조용한 대체 금지).
     */
    data class Plan(
        val paths: List<String>,
        val representativeMissing: Boolean
    ) {
        val count: Int get() = paths.size
        val isEmpty: Boolean get() = paths.isEmpty()
    }

    private val NONE = Plan(emptyList(), representativeMissing = false)

    /** 화면 진입·재추첨마다 새로 뽑는 시드. 같은 시드 = 같은 고름. */
    fun newSeed(): Long = CharacterRepresentativeImage.newSeed()

    /**
     * @param paths 캐릭터의 이미지 경로 목록(폼의 라이브 값).
     * @param representativePath 사용자가 지정한 대표. 없으면 null·빈 문자열.
     * @param count 붙일 장수(사용자 설정). 0이면 아무것도 붙이지 않는다.
     * @param includeRepresentative '대표 반드시 포함' 스위치.
     * @param seed 이 화면이 들고 있는 시드. [newSeed]로 만들고 🎲가 갈아 끼운다.
     * @param manual 사용자가 썸네일 줄에서 **직접 고른** 경로. 비어 있지 않으면 이것이 전부다.
     */
    fun plan(
        paths: List<String>,
        representativePath: String?,
        count: Int,
        includeRepresentative: Boolean,
        seed: Long,
        manual: List<String> = emptyList()
    ): Plan {
        val wanted = AiPromptPolicy.clampAttachImages(count)
        val available = paths.filter { it.isNotBlank() }.distinct()
        if (wanted == 0 || available.isEmpty()) return NONE

        // 직접 고르기가 이긴다 — 다만 상한과 목록 소속은 지킨다(사라진 파일을 붙일 수는 없다).
        if (manual.isNotEmpty()) {
            val picked = manual.filter { m -> available.any { ImagePathMatch.same(it, m) } }
                .distinct()
                .take(wanted)
            if (picked.isNotEmpty()) return Plan(picked, representativeMissing = false)
            // 고른 것이 전부 사라졌으면 직접 고르기가 없던 것으로 보고 아래 규칙으로 채운다.
        }

        // 대표는 **사다리 1칸(PINNED)만** 인정한다 — 랜덤 폴백까지 대표로 부르면
        // 1번 규칙이 무의미해진다. 판정은 대표 단일 소스에 맡긴다.
        val pinned = if (includeRepresentative) {
            CharacterRepresentativeImage
                .pickFrom(available, representativePath, seed, characterId = 0L)
                .takeIf { it.source == CharacterRepresentativeImage.Source.PINNED }
                ?.path
        } else null

        val head = listOfNotNull(pinned)
        val rest = shuffled(available.filterNot { it in head }, seed)
        return Plan(
            paths = (head + rest).take(wanted),
            // 켰는데 못 찾았다 = 고지 대상. 끈 경우는 고지할 것이 없다.
            representativeMissing = includeRepresentative && pinned == null
        )
    }

    /**
     * 시드 기반 섞기 — `Random(seed)`를 쓰지 않는 이유는 대표 고르기와 같다:
     * 이 저장소의 시드 규약은 **splitmix64 혼합**이고, 두 벌의 난수원을 두면
     * "같은 시드인데 화면마다 다른 그림"이 난다.
     */
    private fun shuffled(paths: List<String>, seed: Long): List<String> =
        paths.withIndex()
            .sortedBy { (index, _) -> CharacterRepresentativeImage.randomIndex(seed, index.toLong(), KEY_SPACE) }
            .map { it.value }

    /**
     * 섞기 키의 공간. 목록 크기와 무관하게 넓게 잡아야 키가 겹쳐 원래 순서로 되돌아가는
     * 일이 줄어든다 — 좁게 잡으면(예: size) 충돌이 잦아 "랜덤인데 늘 앞쪽"이 된다.
     */
    private const val KEY_SPACE = 1 shl 20
}
