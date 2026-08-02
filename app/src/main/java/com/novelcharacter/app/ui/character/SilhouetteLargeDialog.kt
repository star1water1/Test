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

    /** 그릴 수치(호스트가 해석한 것). */
    var measured: BodyMeasurements = BodyMeasurements(
        values = emptyMap(), mode = BodyMeasurements.MappingMode.NONE,
        partSlots = emptyList(), partValues = emptyList(), unmappedParts = emptyList()
    )

    var config: BodyAnalysisConfig = BodyAnalysisConfig.DEFAULT

    /** 같은 작품 캐릭터의 수치 — 작품 평균 오버레이의 재료다. 비어 있으면 토글이 없다. */
    var peers: List<BodyMeasurements> = emptyList()

    private var silhouette: SilhouetteView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
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
            measures = BodySilhouetteSpec.measuresFrom(measured, config.ribOffset)
            // 크게 보기에서는 라벨이 기본 켬이다(5-4-1) — 크기가 있으니 읽힌다.
            showLabels = true
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
        return root
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
            toggle(getString(R.string.silhouette_view_side), false) { on ->
                silhouette?.facing =
                    if (on) SilhouetteView.Facing.SIDE else SilhouetteView.Facing.FRONT
            }
        )
        bar.addView(
            toggle(getString(R.string.silhouette_large_labels), true) { on ->
                silhouette?.showLabels = on
            }
        )

        // 작품 평균 — 기본 끔(P3). 재료가 없으면 토글 자체를 내놓지 않는다(구색 금지).
        val average = BodyEditorModel.peerAverage(peers, config.ribOffset)
        if (average != null) {
            bar.addView(
                toggle(getString(R.string.silhouette_overlay_average), false) { on ->
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

        fun newInstance(characterName: String): SilhouetteLargeDialog =
            SilhouetteLargeDialog().apply {
                arguments = Bundle().apply { putString(ARG_NAME, characterName) }
            }
    }
}
