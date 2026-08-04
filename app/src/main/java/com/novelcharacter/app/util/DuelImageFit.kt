package com.novelcharacter.app.util

/**
 * 대결 화면이 **참가자 그림을 어떻게 놓는가** (B-104 — 사용자 지적 2026.08.04).
 *
 * 지적 원문: *"지금 매칭창이 이미지가 가로로 길고 세로로 짧잖아? 근데 내가 쓰는 사진 규격이
 * 이래서 얼굴이 잘릴 때가 좀 있어"* — 카드는 **가로로 넓고 세로로 짧은데** 창작 캐릭터 그림은
 * 대개 **세로로 긴** 규격이다. 가운데 기준으로 잘라 채우면(`centerCrop`) 위아래가 함께 잘리고,
 * 인물화에서 얼굴은 **위쪽에 있으므로** 잘려 나가는 쪽이 하필 얼굴이다.
 *
 * ## 처방 둘 — 하나는 기본값, 하나는 사용자 선택
 * 1. **채울 때는 위를 살린다**([topCrop]). 가운데가 아니라 **위쪽 끝**을 기준으로 잘라
 *    세로로 긴 그림에서 머리가 남는다. 기본값이라 아무것도 설정하지 않아도 나아진다.
 * 2. **카드 모양과 잘림 여부를 사용자가 고른다**([Layout]·[Fit]). 그림 규격은 사람마다
 *    다르므로 앱이 하나로 정하지 않는다(자율성 우선) — 세로 그림이면 나란히, 가로 그림이면
 *    위아래, 무엇도 자르기 싫으면 전체 보이기다.
 *
 * **이 계산이 pure인 것은 화면이 이것을 판단하지 않게 하려는 것이다**(「세션 착수 규칙」 4번).
 * 행렬 값이 틀려도 앱은 죽지 않고 **그림만 조용히 어긋나게 놓이며**, 컴파일도 정적 검사도
 * 전부 통과한다 — 실기기 회신 말고는 잡을 길이 없는 부류라 실행으로 재는 자리에 둔다.
 */
object DuelImageFit {

    /** 두 카드를 어느 방향으로 놓는가. */
    enum class Layout {
        /** 위아래 — 가로로 넓은 그림에 맞는다(종전 동작). */
        STACKED,

        /** 나란히 — **세로로 긴 그림에 맞는다.** 카드가 세로로 길어져 인물화가 덜 잘린다. */
        SIDE_BY_SIDE;

        companion object {
            /** 저장된 값을 읽는다. 모르는 값은 기본값으로 — 외부에서 편집된 설정이 앱을 막지 않는다. */
            fun of(name: String?): Layout =
                entries.firstOrNull { it.name == name } ?: SIDE_BY_SIDE
        }
    }

    /** 카드 안에서 그림을 어떻게 맞추는가. */
    enum class Fit {
        /** 카드를 가득 채우되 **위를 살려서** 자른다. 얼굴이 남는 쪽이다. */
        FILL_TOP,

        /** 통째로 보인다 — 아무것도 잘리지 않는 대신 카드에 빈 자리가 생긴다. */
        WHOLE;

        companion object {
            fun of(name: String?): Fit = entries.firstOrNull { it.name == name } ?: FILL_TOP
        }
    }

    /**
     * [FILL_TOP]이 쓸 행렬 값 — 확대 배율과 옮길 거리.
     *
     * @property scale 원본에 곱할 배율.
     * @property dx 가로 이동. 가로가 넘치면 음수가 되어 **가운데**를 남긴다(인물은 가로로 가운데에 있다).
     * @property dy 세로 이동. 세로가 넘치면 **0** — 즉 위쪽 끝을 남긴다(얼굴이 있는 쪽).
     */
    data class Crop(val scale: Float, val dx: Float, val dy: Float)

    /**
     * 카드를 가득 채우는 배율로 확대하되 **위쪽을 기준으로** 놓는다.
     *
     * 배율은 두 축 중 **큰 쪽**이라 카드에 빈 자리가 남지 않는다. 넘치는 축이 세로면 아래를
     * 잘라 내고(위가 남는다), 가로면 좌우를 고르게 잘라 낸다.
     *
     * @return 넘겨받은 크기 중 하나라도 0 이하면 null — 아직 재지 않은 화면이라 계산할 것이 없다.
     */
    fun topCrop(viewWidth: Int, viewHeight: Int, bitmapWidth: Int, bitmapHeight: Int): Crop? {
        if (viewWidth <= 0 || viewHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val bw = bitmapWidth.toFloat()
        val bh = bitmapHeight.toFloat()

        val scale = maxOf(vw / bw, vh / bh)
        val dx = (vw - bw * scale) / 2f
        // 세로가 넘치면 0(위쪽 끝). 넘치지 않는 경우에만 가운데로 내린다 — 배율이 두 축의
        // 최댓값이라 실제로는 딱 맞거나 넘치므로 거의 언제나 0이지만, 반올림으로 아주 조금
        // 모자란 경우에 음수가 되어 위가 잘리는 것을 막는다.
        val dy = ((vh - bh * scale) / 2f).coerceAtLeast(0f)
        return Crop(scale, dx, dy)
    }
}
