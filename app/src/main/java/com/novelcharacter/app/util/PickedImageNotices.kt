package com.novelcharacter.app.util

/**
 * **고른 이미지를 들이다 생긴 몫을 무엇이라 말할 것인가** — 고지 판정의 단일 소스.
 *
 * ## 왜 판정을 따로 빼는가
 *
 * 이 고지는 **같은 자리에서 두 번 틀렸다.**
 *
 * 1. 처음에는 폼이 사라지면 그 자리에서 `return`이라, 쓴 파일은 고아가 되고 남은 몫은
 *    말없이 버려졌다.
 * 2. 고친 뒤에도 `adopted > 0`만 말했다 — 담는 데 실패한 몫(`adoptOrphans`가 예외로 0을
 *    돌려준 경우)이 *"담을 것이 없었다"*와 **같은 침묵**으로 나갔다.
 *
 * 둘 다 *"어느 몫을 말하는가"*의 잘못이고, 그 판정이 화면 코드 안에 있으면 **어떤 순수
 * 시험도 보지 못한다**(Android 토스트·DB가 걸려 있다). 그래서 수만 받아 **무엇을 말할지**
 * 만 정하고, 글자는 부르는 쪽이 붙인다.
 *
 * ## 규약
 *
 * - **0건은 말하지 않는다.** 일어나지 않은 일을 말하면 다음 줄의 신뢰가 깎인다.
 * - **몫끼리 겹치지 않는다.** `담았다`와 `담지 못했다`는 같은 `stranded`를 둘로 가른 것이고,
 *   `시도 못 했다`·`저장 실패`는 아예 다른 모수다. 합이 사용자가 고른 장수를 넘지 않는다.
 * - **순서는 사용자가 할 일이 있는 것부터**가 아니라 **사실의 무게 순**이다: 어디에 있는지
 *   (담았다) → 어디에도 없다(담지 못했다) → 아예 안 들어왔다(시도 못 했다 · 저장 실패).
 */
object PickedImageNotices {

    /** 무엇을 말할 것인가. 글자는 부르는 쪽이 고른다(화면마다 다른 자원을 쓴다). */
    enum class Notice {
        /** 폼에 붙이지 못해 **라이브러리에 미배정으로 담았다.** */
        STRANDED_ADOPTED,

        /** 디스크에 썼는데 **담지도 못했다** — 주인도 meta도 없는 파일이다. */
        STRANDED_FAILED,

        /** **시도조차 못 한** 몫(프로세스가 끊긴 자리). */
        NOT_ATTEMPTED,

        /** 읽거나 쓰다 **실패한** 몫. */
        SAVE_FAILED
    }

    /**
     * @param adopted `stranded` 중 라이브러리에 담긴 장수
     * @param strandedTotal 폼이 받지 못한 장수(담긴 것 + 담지 못한 것)
     * @param notAttempted 시도조차 못 한 장수
     * @param failed 읽기·쓰기가 실패한 장수
     */
    fun of(adopted: Int, strandedTotal: Int, notAttempted: Int, failed: Int): List<Pair<Notice, Int>> =
        buildList {
            if (adopted > 0) add(Notice.STRANDED_ADOPTED to adopted)
            // 음수가 되지 않게 좁힌다 — `adoptOrphans`가 넘긴 것보다 많이 셀 수는 없지만,
            // 수를 믿고 빼기만 하면 **그 가정이 깨지는 날 고지가 "-2장"이라 말한다.**
            val notAdopted = (strandedTotal - adopted).coerceAtLeast(0)
            if (notAdopted > 0) add(Notice.STRANDED_FAILED to notAdopted)
            if (notAttempted > 0) add(Notice.NOT_ATTEMPTED to notAttempted)
            if (failed > 0) add(Notice.SAVE_FAILED to failed)
        }
}
