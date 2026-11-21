package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.util.BodyEditorModel
import com.novelcharacter.app.util.BodyEditorState
import com.novelcharacter.app.util.BodyMeasurements
import com.novelcharacter.app.util.BodySilhouetteSpec

/**
 * 실루엣 크게 보기 (설계 `docs/body_visual_redesign_2026-07.md` 5-4-3).
 *
 * **그림 참고용이며 아무것도 고치지 않는다** — 핸들도 없고 쓰는 자리도 없다(D5).
 * 수치를 바꾸는 길은 편집 화면의 [BodySilhouetteEditorSheet] 하나뿐이며, 그 사실을
 * 목적문이 말한다(R-25).
 *
 * 계산은 하지 않는다: 지오메트리는 [BodySilhouetteSpec], 작품 평균은 [BodyEditorModel]이
 * 들고, 이웃 수치는 호스트가 이미 모아 둔 것을 받는다(같은 조회를 두 번 하지 않는다).
 */
class SilhouetteLargeDialog : DialogFragment() {

    /**
     * 그릴 것은 **arguments가 든다** — 인스턴스 프로퍼티가 아니다.
     *
     * 종전에는 호스트가 `newInstance(...).apply { this.measured = …; this.config = …;
     * this.peers = … }`로 인스턴스에 직접 꽂았는데, 회전·다크모드 전환은 FragmentManager가
     * 이 창을 **무인자 생성자로 되살리므로** 셋이 전부 선언 기본값으로 돌아갔다.
     * 그러면 `measuresFrom`이 축을 하나도 못 찾아 `null`을 내고 `SilhouetteView.onDraw`가
     * 첫 줄에서 돌아가 **빈 화면**이 된다 — 창은 떠 있는데 아무것도 없다.
     *
     * 이 창이 실제로 쓰는 것은 그림 둘뿐이다(본인 수치 · 작품 평균 오버레이). 그래서
     * 해석된 [BodySilhouetteSpec.Measures]를 그대로 담는다 — 캐릭터 id로 다시 조회하는
     * 길보다 짧고, 해석 규칙이 두 벌이 되지 않는다(코덱은 [BodyEditorState]의 것 하나다).
     */
    private val figure: BodySilhouetteSpec.Measures? by lazy {
        BodyEditorState.measuresFromJson(readJson(ARG_MEASURES))
    }

    private val peerAverage: BodySilhouetteSpec.Measures? by lazy {
        BodyEditorState.measuresFromJson(readJson(ARG_PEER_AVERAGE))
    }

    private fun readJson(key: String): org.json.JSONObject? =
        arguments?.getString(key)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }

    private var silhouette: SilhouetteView? = null

    /**
     * 하단 토글 셋의 상태 — **회전·다크모드 전환을 넘긴다.**
     *
     * 그림은 arguments가 넘기지만 토글은 사용자가 이 창에서 고른 것이라 arguments에 없다.
     * 종전에는 담지 않아 되살아난 창이 기본값(측면 끔 · 라벨 켬 · 작품 평균 끔)으로 섰고,
     * 켜 둔 '작품 평균' 오버레이가 회전 한 번에 꺼졌다(S8 에뮬레이터 2026-11-08 · `1-ㄹ2`).
     */
    private var sideOn = false
    private var labelsOn = true
    private var overlayOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // **앱 테마로 띄운다** — 종전의 기기 기본 라이트 테마는 다크 모드에서도 상태 표시줄을 밝은
        // 바탕·어두운 아이콘으로 세워, 어두운 화면 위에 아이콘이 보이지 않았다(S8 관찰). 앱 테마는
        // 낮/밤에 따라 상태 표시줄 색과 아이콘 밝기를 함께 뒤집는다(values-night/themes.xml).
        setStyle(STYLE_NORMAL, R.style.Theme_NovelCharacter)
        savedInstanceState?.let {
            sideOn = it.getBoolean(STATE_SIDE, sideOn)
            labelsOn = it.getBoolean(STATE_LABELS, labelsOn)
            overlayOn = it.getBoolean(STATE_OVERLAY, overlayOn)
        }
    }

    /**
     * 상태·내비게이션 표시줄 아이콘 밝기를 **지금 낮/밤에 맞춘다** — MainActivity와 같은 처분이다.
     * 테마 플래그만으로는 안드로이드 15의 전면 표시에서 아이콘 밝기가 따라오지 않는다.
     */
    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SIDE, sideOn)
        outState.putBoolean(STATE_LABELS, labelsOn)
        outState.putBoolean(STATE_OVERLAY, overlayOn)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(R.color.background))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── 상단 앱바: 닫기 ✕ + 캐릭터명 ──
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                setColorFilter(ctx.getColor(R.color.text_secondary))
                contentDescription = getString(R.string.silhouette_close)
                layoutParams = LinearLayout.LayoutParams(
                    (48 * density).toInt(), (48 * density).toInt()
                )
                setOnClickListener { dismiss() }
            })
            addView(TextView(ctx).apply {
                text = arguments?.getString(ARG_NAME).orEmpty()
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (8 * density).toInt() }
            })
        })

        // ── 목적문 (R-25) — 무엇을 하는 화면이고 고치는 길은 어디인가 ──
        root.addView(TextView(ctx).apply {
            text = getString(R.string.silhouette_large_purpose)
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (8 * density).toInt())
        })

        // ── 중앙 실루엣 (화면 최대) ──
        val view = SilhouetteView(ctx).apply {
            this.measures = figure
            // 크게 보기에서는 라벨이 기본 켬이다(5-4-1) — 크기가 있으니 읽힌다.
            // 토글 셋은 담아 둔 상태로 선다(회전·다크모드 전환 뒤에도 사용자가 고른 그대로).
            showLabels = labelsOn
            facing = if (sideOn) SilhouetteView.Facing.SIDE else SilhouetteView.Facing.FRONT
            overlayMeasures = if (overlayOn) peerAverage else null
            interactive = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        silhouette = view
        root.addView(view)

        // 클램프 캡션은 **호스트가 단다** — 뷰는 걸렸는지만 내준다(1-am의 규정).
        if (view.isClamped) {
            root.addView(TextView(ctx).apply {
                text = getString(R.string.silhouette_clamp_caption)
                textSize = 12f
                setTextColor(ctx.getColor(R.color.silhouette_warn))
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (4 * density).toInt())
            })
        }

        root.addView(buildControls(ctx, density))
        if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) arrangeForLandscape(root, view)
        // 안드로이드 15의 전면 표시에서는 이 창도 시스템 표시줄 밑으로 그려진다 — 닫기 ✕와
        // 하단 토글이 가리지 않게 표시줄 몫을 안쪽 여백으로 받는다(그 밖의 판에서는 0이다).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return root
    }

    /**
     * 가로 화면에서는 그림을 **왼쪽에 화면 높이만큼** 세우고 머리·목적문·토글을 오른쪽 열로 옮긴다.
     *
     * 세로 배치 그대로면 낮은 높이에서 머리·목적문·토글이 위아래를 먹어 그림이 작아졌다(S8 관찰).
     * 같은 뷰를 옮겨 담을 뿐이라 토글 상태·그림 입력은 그대로다.
     */
    private fun arrangeForLandscape(root: LinearLayout, figure: View) {
        val ctx = root.context
        val children = (0 until root.childCount).map { root.getChildAt(it) }
        if (figure !in children) return
        root.removeAllViews()
        root.orientation = LinearLayout.HORIZONTAL
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        children.forEachIndexed { i, child ->
            if (child === figure) {
                // 그림이 있던 자리에 빈 칸을 두어, 위(머리·목적문)와 아래(캡션·토글)가 갈라 선다.
                column.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            } else {
                column.addView(child)
            }
        }
        figure.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        root.addView(figure)
        root.addView(column)
    }

    /** 하단 컨트롤 바 — [정면/측면] · [라벨] · [작품 평균](재료가 있을 때만). */
    private fun buildControls(ctx: android.content.Context, density: Float): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun toggle(label: String, initiallyOn: Boolean, onChange: (Boolean) -> Unit): MaterialButton {
            var on = initiallyOn
            return MaterialButton(ctx).apply {
                text = label
                alpha = if (on) 1f else .55f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = (4 * density).toInt() }
                setOnClickListener {
                    on = !on
                    alpha = if (on) 1f else .55f
                    onChange(on)
                    silhouette?.invalidate()
                }
            }
        }

        bar.addView(
            toggle(getString(R.string.silhouette_view_side), sideOn) { on ->
                sideOn = on
                silhouette?.facing =
                    if (on) SilhouetteView.Facing.SIDE else SilhouetteView.Facing.FRONT
            }
        )
        bar.addView(
            toggle(getString(R.string.silhouette_large_labels), labelsOn) { on ->
                labelsOn = on
                silhouette?.showLabels = on
            }
        )

        // 작품 평균 — 기본 끔(P3). 재료가 없으면 토글 자체를 내놓지 않는다(구색 금지).
        val average = peerAverage
        if (average != null) {
            bar.addView(
                toggle(getString(R.string.silhouette_overlay_average), overlayOn) { on ->
                    overlayOn = on
                    silhouette?.overlayMeasures = if (on) average else null
                }
            )
        }
        return bar
    }

    override fun onDestroyView() {
        silhouette = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "SilhouetteLargeDialog"
        private const val ARG_NAME = "characterName"

        /** 굳혀 담은 그림 — 회전·다크모드 전환을 이 둘이 넘긴다. */
        private const val ARG_MEASURES = "measures"
        private const val ARG_PEER_AVERAGE = "peerAverage"

        private const val STATE_SIDE = "large_side"
        private const val STATE_LABELS = "large_labels"
        private const val STATE_OVERLAY = "large_overlay"

        /**
         * @param measured 호스트가 해석한 수치 · @param peers 같은 작품 이웃(작품 평균의 재료)
         *
         * **그림을 여기서 굳혀 담는다** — 창이 회전·다크모드 전환을 넘어 스스로 다시 그릴 수
         * 있어야 하기 때문이다. 해석은 호스트가 이미 한 번 했으므로 여기서 다시 하지 않는다.
         */
        fun newInstance(
            characterName: String,
            measured: BodyMeasurements,
            config: BodyAnalysisConfig,
            peers: List<BodyMeasurements>
        ): SilhouetteLargeDialog = SilhouetteLargeDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_NAME, characterName)
                BodySilhouetteSpec.measuresFrom(measured, config.ribOffset)?.let {
                    putString(ARG_MEASURES, BodyEditorState.measuresToJson(it).toString())
                }
                BodyEditorModel.peerAverage(peers, config.ribOffset)?.let {
                    putString(ARG_PEER_AVERAGE, BodyEditorState.measuresToJson(it).toString())
                }
            }
        }
    }
}
