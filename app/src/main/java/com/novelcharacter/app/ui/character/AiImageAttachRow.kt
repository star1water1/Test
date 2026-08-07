package com.novelcharacter.app.ui.character

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.util.AiImageAttach
import com.novelcharacter.app.util.loadCharacterThumbnail

/**
 * **이미지 첨부 줄** — 고지 다이얼로그에 붙는 썸네일 줄 + 장수 + 🎲 (A-7 · 설계 2-2 표).
 *
 * 짧은 값 추천과 서술형 작성이 **같은 하나를 쓴다.** 두 벌이 되면 같은 앱의 두 AI 경로가
 * 다른 규칙으로 그림을 고르게 되고, 그 차이는 화면에 드러나지 않는다(이 저장소가 반복해서
 * 겪은 실패 형태 — 조립기를 복사하면 근거가 갈린다).
 *
 * 조작은 셋이다(자율성 우선 — 랜덤만 있으면 "하필 그 장"을 뺄 길이 없다):
 * - **탭** — 그 장을 넣고 뺀다(직접 고르기). 한 번이라도 탭하면 그 뒤로는 사용자 선택이 전부다.
 * - **🎲** — 시드를 갈아 다시 뽑는다. 직접 고른 것은 버려지고 규칙대로 돌아간다.
 * - **장수** — 설정의 기본값에서 시작하고, 이 자리에서 바꾼 것은 이번 요청에만 적용된다.
 *
 * 고르기 **규칙 자체는 [AiImageAttach]가 단일 소스**이고 이 파일은 그리기만 한다.
 */
object AiImageAttachRow {

    /** 다이얼로그가 들고 있는 첨부 상태. [selected]가 실행 시점에 실제로 보낼 경로다. */
    class Handle internal constructor(
        val view: View,
        private val state: State
    ) {
        /** 이번 요청에 실제로 붙일 경로. 순서가 곧 프롬프트의 이미지 번호다. */
        val selected: List<String> get() = state.plan.paths

        /** 붙일 것이 하나도 없는가 — 비용 고지가 이미지 줄을 뺄지 판단한다. */
        val isEmpty: Boolean get() = state.plan.isEmpty

        /**
         * 첨부가 바뀌면 호출측 비용 문구도 다시 그려야 한다 — **장수가 곧 비용**이라
         * 한쪽만 갱신되면 고지가 거짓이 된다(R-4 사전 고지 정확성).
         *
         * 줄 자신의 다시 그리기와 **갈라 둔다**: 한 슬롯을 나눠 쓰면 호출측이 리스너를
         * 걸었을 때 썸네일 갱신이 조용히 사라진다.
         */
        fun onChanged(listener: () -> Unit) { state.external = listener }
    }

    internal class State(
        val paths: List<String>,
        val representativePath: String?,
        var count: Int,
        val includeRepresentative: Boolean,
        var seed: Long,
        var manual: MutableList<String>
    ) {
        /** 줄 자신의 다시 그리기 — [create]가 세운다. */
        var redraw: (() -> Unit)? = null

        /** 호출측(비용 문구)의 다시 그리기. */
        var external: (() -> Unit)? = null

        var plan: AiImageAttach.Plan = AiImageAttach.plan(
            paths, representativePath, count, includeRepresentative, seed, manual
        )

        fun replan() {
            plan = AiImageAttach.plan(paths, representativePath, count, includeRepresentative, seed, manual)
            redraw?.invoke()
            external?.invoke()
        }
    }

    /**
     * 첨부 줄을 만든다. **캐릭터에 이미지가 없을 때만** 아무것도 그리지 않는다(`null`) —
     * 붙일 것이 없는 자리에 조작을 보여 주는 것은 R-24가 없앤 부류다.
     *
     * **설정이 0장이어도 줄은 그린다.** 0은 성립하지 않는 조합이 아니라 "이번에는 안 보낸다"는
     * 유효한 상태이고, 줄을 감추면 한 번만 붙여 보려는 사용자가 **설정 화면을 다녀와야 한다**
     * (원칙 04 — 이중 경로: 기본값은 설정에서, 이번 요청은 여기서).
     */
    fun create(
        fragment: Fragment,
        imagePaths: List<String>,
        representativePath: String?
    ): Handle? {
        val context = fragment.requireContext()
        val available = imagePaths.filter { it.isNotBlank() }.distinct()
        if (available.isEmpty()) return null

        val settings = AiPromptSettings(context)
        val initialCount = settings.attachImageCount

        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val state = State(
            paths = available,
            representativePath = representativePath,
            count = initialCount,
            includeRepresentative = settings.attachRepresentativeFirst,
            seed = AiImageAttach.newSeed(),
            manual = mutableListOf()
        )

        val title = TextView(context).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        }
        // 대표를 켰는데 지정이 없다 — 조용히 다른 그림을 '대표'라 부르지 않고 그 사실을 말한다
        val representativeNote = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
            text = fragment.getString(R.string.ai_image_representative_missing)
        }
        // 이 모델이 이미지를 안 받는다고 이미 배웠다면 붙이기 **전에** 말한다 (A-7)
        val unsupportedNote = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
            isVisible = AiService(context).isImagesUnsupported()
            text = fragment.getString(R.string.ai_image_unsupported_notice)
        }

        val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        // scroll-cap-exempt: 가로 스크롤이고 높이는 썸네일 56dp로 이미 묶여 있다.
        // R-31이 막는 것은 **세로**로 자라 다이얼로그 본문을 넘기는 자리인데, 이 줄은
        // 장수가 늘어도 옆으로만 길어진다.
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }

        val thumbs = available.map { path ->
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(6) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                loadCharacterThumbnail(path, fragment.viewLifecycleOwner.lifecycleScope, reqPx = 128)
                setOnClickListener {
                    // 첫 탭은 지금 뽑혀 있는 것을 그대로 물려받는다 — 그러지 않으면 한 장만 빼려던
                    // 사용자가 나머지를 처음부터 다시 골라야 한다(원칙 04).
                    if (state.manual.isEmpty()) state.manual = state.plan.paths.toMutableList()
                    val hit = state.manual.firstOrNull { m -> m == path }
                    when {
                        hit != null -> {
                            state.manual.remove(hit)
                            // 마지막 한 장을 뺐으면 그것이 곧 "안 보낸다"다. 장수를 0으로
                            // 내려 두지 않으면 규칙이 도로 무작위 한 장을 채워 넣어,
                            // **뺀 그림이 다른 그림으로 바뀔 뿐** 빠지지 않는다.
                            if (state.manual.isEmpty()) state.count = 0
                        }
                        // 안 보내기로 되어 있는데 한 장을 집었다 — 그 탭 자체가 "이건 보내라"다.
                        // 장수를 올리지 않으면 상한 0에 걸려 **탭이 아무 일도 하지 않는다**
                        // (그리고 아래 밀어내기가 빈 목록에서 터진다).
                        state.count == 0 -> {
                            state.count = 1
                            state.manual.add(path)
                        }
                        state.manual.size < state.count -> state.manual.add(path)
                        else -> {
                            // 상한이 찼으면 가장 먼저 뽑힌 것을 밀어낸다 — 탭이 아무 일도 하지
                            // 않는 것보다 낫다(무반응은 고장으로 읽힌다).
                            state.manual.removeAt(0)
                            state.manual.add(path)
                        }
                    }
                    state.replan()
                }
            }
        }
        thumbs.forEach { strip.addView(it) }

        val diceButton = TextView(context).apply {
            text = fragment.getString(R.string.ai_image_reshuffle)
            textSize = 13f
            setTextColor(context.getColor(R.color.primary))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), dp(16), 0)
            setOnClickListener {
                state.seed = AiImageAttach.newSeed()
                state.manual = mutableListOf()   // 다시 뽑기는 직접 고른 것을 버린다
                state.replan()
            }
        }
        val fewerButton = stepper(context, "−", dp(16)) {
            state.count = AiPromptPolicy.clampAttachImages(state.count - 1)
            state.manual = trimTo(state.manual, state.count)
            state.replan()
        }
        val moreButton = stepper(context, "＋", dp(16)) {
            state.count = AiPromptPolicy.clampAttachImages(state.count + 1)
            state.replan()
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(diceButton)
            addView(fewerButton)
            addView(moreButton)
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(scroller)
            addView(controls)
            addView(representativeNote)
            addView(unsupportedNote)
        }

        fun refresh() {
            val chosen = state.plan.paths
            title.text = if (chosen.isEmpty()) {
                fragment.getString(R.string.ai_image_attach_none)
            } else {
                fragment.getString(R.string.ai_image_attach_count, chosen.size, available.size)
            }
            representativeNote.isVisible = state.plan.representativeMissing
            // 줄은 두 모드다 — **규칙대로 뽑기**(장수 스테퍼 + 🎲)와 **직접 고르기**(탭).
            // 직접 고르는 중에는 장수가 곧 고른 개수라 스테퍼가 할 일이 없다: 눌러도
            // 아무 일이 없는 단추를 남겨 두면 고장으로 읽히므로 흐리게 잠근다.
            // 규칙으로 돌아가는 길은 🎲 하나뿐이고, 그래서 그것만 늘 살아 있다.
            val handPicked = state.manual.isNotEmpty()
            listOf(fewerButton, moreButton).forEach {
                it.isEnabled = !handPicked
                it.alpha = if (handPicked) 0.35f else 1f
            }
            // 붙일 것이 0장이면 다시 뽑을 것도 없다 — ＋로 올리거나 썸네일을 탭하면 살아난다.
            diceButton.isEnabled = state.count > 0
            diceButton.alpha = if (state.count > 0) 1f else 0.35f
            // 뽑힌 장은 또렷하게, 아닌 장은 흐리게 — 무엇이 나가는지 한눈에 보여야 한다
            thumbs.forEachIndexed { index, view ->
                val picked = chosen.any { it == available[index] }
                view.alpha = if (picked) 1f else 0.35f
                view.setBackgroundColor(
                    if (picked) context.getColor(R.color.primary) else Color.TRANSPARENT
                )
                val inset = if (picked) dp(2) else 0
                view.setPadding(inset, inset, inset, inset)
            }
        }
        refresh()
        state.redraw = { refresh() }
        return Handle(panel, state)
    }

    private fun trimTo(manual: MutableList<String>, max: Int): MutableList<String> =
        if (manual.size <= max) manual else manual.take(max).toMutableList()

    private fun stepper(
        context: android.content.Context,
        label: String,
        pad: Int,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 16f
        setTextColor(context.getColor(R.color.primary))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, pad, 0)
        setOnClickListener { onClick() }
    }
}
