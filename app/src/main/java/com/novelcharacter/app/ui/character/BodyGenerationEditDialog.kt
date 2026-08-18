package com.novelcharacter.app.ui.character

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodyAnalysisConfig.GenerationPreset
import com.novelcharacter.app.util.BodyGenerationEditState
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError

/**
 * 🎲 생성 축·프리셋 편집 창 (B-93 · 확정 7번 ㄱ1·ㄴ1).
 *
 * **자리가 실루엣 편집기 안인 것이 이 창의 설계다.** 축을 손보고 싶어지는 순간은 굴려 본
 * 직후이지 필드 설정을 열러 갈 때가 아니다(원칙 04) — 그래서 🎲 패널에서 바로 열고,
 * 저장하면 그 자리에서 축이 다시 서서 곧바로 굴려 볼 수 있다.
 *
 * **여는 것은 이름과 수치뿐이다**(확정 ㄴ1 — 단계 개수는 열지 않는다). 그래서 이 창은
 * 행을 더하거나 지우지 않고, 언제나 같은 수의 칸을 그린다. 개수를 열면 프리셋이 가리키는
 * 축이 사라질 수 있고, 화면이 그리는 칸 수도 세계관마다 달라진다.
 *
 * **검증은 막되 버리지 않는다**(R-27) — 잘못 적은 칸은 그 자리에 붉은 글씨로 말하고 창은
 * 열린 채 남는다. 저장을 눌러 닫혔다면 적은 것이 전부 담긴 것이다.
 *
 * ## 왜 [DialogFragment]인가 (B-260 · R-41-a)
 *
 * 종전에는 `object`가 맨 `AlertDialog`를 세워 띄웠다 — **액티비티가 다시 서면 창과 함께
 * 적던 것이 전부 사라졌다.** 이 창은 스스로 *"칸 스물이 넘는 창이라 손이 미끄러지면 적던
 * 것이 말없이 사라진다"*며 바깥 누름을 막아 두었는데, **회전은 그 방어를 지나갔다.**
 * 확정 15장 5번이 *그 자리에서 만들어진 작업물*을 안전 종료의 예외로 세운 그 부류다.
 *
 * 담는 벌은 순수 계층이 든다([BodyGenerationEditState]) — 창 안에서 `Bundle`에 칸을 넣고
 * 빼면 왕복이 맞는지 증명할 수단이 없다. **여는 쪽은 [newInstance]로 넘기고 받는 쪽은
 * [RESULT_KEY]로 받는다** — 콜백을 인스턴스에 꽂지 않으므로 *"회전 뒤 호스트가 다시 꽂지
 * 않아 [저장]이 아무 데도 닿지 않는" 갈래가 원리적으로 없다*(B-201이 겪은 그 모양).
 */
class BodyGenerationEditDialog : DialogFragment() {

    /** 창이 그리는 한 벌 — 행 수와 스피너 항목이 여기서 나온다. */
    private lateinit var current: GenerationPreset
    private var heightRows: List<AxisRow> = emptyList()
    private var torsoRows: List<AxisRow> = emptyList()
    private var bustRows: List<AxisRow> = emptyList()
    private var hipRows: List<AxisRow> = emptyList()
    private var presetRows: List<PresetRow> = emptyList()

    /**
     * 프리셋 스피너에 지금 실려 있는 항목 글자 — **[기본값으로]가 이것도 되돌린다.**
     * 위젯에서 되읽지 않고 필드로 드는 것은 `Spinner.adapter`를 훑어 글자를 되뽑는 것보다
     * *무엇을 실었는가*가 정확하기 때문이다(같은 근거로 [BodyGenerationEditState]가 이것을 담는다).
     */
    private var presetOptionLabels: BodyGenerationEditState.OptionLabels =
        BodyGenerationEditState.OptionLabels(emptyList(), emptyList(), emptyList())

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // **회전 뒤가 먼저다** — 담긴 것이 있으면 그것이 사용자가 적던 값이고,
        // 없으면 연 쪽이 넘긴 처음 상태다. 둘의 타입이 같아 길을 가르지 않는다.
        val state = BodyGenerationEditState.decode(
            savedInstanceState?.getString(STATE_KEY) ?: arguments?.getString(ARG_STATE)
        )
        if (state == null) {
            // 못 읽으면 **안전 종료**다(R-41-a) — 껍데기를 띄우면 [저장]이 사용자가 손대지도
            // 않은 기본값을 진짜 설정 위에 덮어쓴다. 담긴 것이 없으면 잃은 것도 없다.
            dismissAllowingStateLoss()
            return MaterialAlertDialogBuilder(requireContext()).create()
        }
        return buildDialog(requireContext(), state)
    }

    /**
     * 적던 것을 담는다 — **칸을 더하면 [BodyGenerationEditState]에도 함께 등재할 것**(R-41).
     * 위젯을 읽는 자리는 여기 하나이고, 읽는 법은 [snapshot]이 든다.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        snapshot()?.let { outState.putString(STATE_KEY, it.encode()) }
    }

    /** 지금 칸에 적힌 것을 그대로 — **수로 바꾸지 않는다**(적던 중인 값이 눌리면 안 된다). */
    private fun snapshot(): BodyGenerationEditState? {
        if (!::current.isInitialized) return null
        fun rows(list: List<AxisRow>) = list.map { row ->
            BodyGenerationEditState.RowText(
                row.name.text.toString(),
                row.numbers.map { it.text.toString() }
            )
        }
        return BodyGenerationEditState(
            current = current,
            heightRows = rows(heightRows),
            torsoRows = rows(torsoRows),
            bustRows = rows(bustRows),
            hipRows = rows(hipRows),
            presetRows = presetRows.map {
                BodyGenerationEditState.PresetText(
                    it.name.text.toString(), it.torso.selectedItemPosition,
                    it.bust.selectedItemPosition, it.hip.selectedItemPosition
                )
            },
            presetOptionLabels = presetOptionLabels
        )
    }

    companion object {
        /**
         * 수치 칸의 자리 — **세 곳이 같은 수를 각자 들고 있던 자리다**(감시자 배선 · 경고 계산 ·
         * 오름차순 검증). 칸이 하나 늘면 셋을 함께 옮겨야 하는데 **하나를 놓쳐도 조용하다** —
         * 경고가 엉뚱한 칸을 읽고 아무 말도 하지 않을 뿐이라 컴파일도 시험도 못 본다.
         */
        private const val HEIGHT_CENTER_COL = 0
        private const val HEIGHT_VARIANCE_COL = 1
        private const val TORSO_END_COL = 2
        private const val HIP_END_COL = 1

        const val TAG = "body_generation_edit"

        /** 저장된 한 벌이 이 열쇠로 돌아온다 — 값은 [RESULT_GENERATION_JSON]의 JSON 문자열이다. */
        const val RESULT_KEY = "body_generation_edit_result"
        const val RESULT_GENERATION_JSON = "generation_json"

        private const val ARG_STATE = "arg_state"
        private const val STATE_KEY = "state"

        /**
         * @param current 지금 쓰이는 한 벌(빈 축이 없는 것으로 정규화된 값을 받는다)
         *
         * **저장 결과는 [RESULT_KEY]로 간다** — 부르는 쪽이 그것을 필드 config에 담는다.
         * 이 창은 화면과 검증만 든다(계산·저장을 창 안에 적으면 시험이 닿지 않는다).
         */
        fun newInstance(current: GenerationPreset): BodyGenerationEditDialog =
            BodyGenerationEditDialog().apply {
                arguments = bundleOf(ARG_STATE to BodyGenerationEditState.initial(current).encode())
            }
    }

    private fun buildDialog(context: Context, saved: BodyGenerationEditState): Dialog {
        // 담긴 칸을 한 벌의 모양에 맞춘다 — 어긋난 축만 되돌린다(순수 계층이 든다).
        val state = saved.aligned()
        current = state.current
        presetOptionLabels = state.presetOptionLabels
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        fun caption(textRes: Int, top: Int = 12) {
            root.addView(TextView(context).apply {
                setText(textRes)
                setTextAppearance(R.style.TextAppearance_App_Caption)
                setPadding(0, dp(top), 0, dp(2))
            })
        }

        // 목적문 — 이 창이 무엇을 어디에 어떻게 하는가(R-25).
        root.addView(TextView(context).apply {
            setText(R.string.body_gen_edit_purpose)
            setTextAppearance(R.style.TextAppearance_App_Caption)
            setPadding(0, 0, 0, dp(4))
        })

        /**
         * 축 한 줄 — 이름 + 수치 칸 몇. 칸 수는 축마다 다르고 행 수는 [current]가 정한다.
         *
         * **값은 담긴 글자에서 온다**(B-260) — 처음 열 때는 한 벌을 옮긴 것이고 회전 뒤에는
         * 사용자가 적던 것이다. 둘을 가르지 않으므로 *회전 뒤에만 다르게 서는* 갈래가 없다.
         */
        fun axisRow(text: BodyGenerationEditState.RowText, hints: List<Int>): AxisRow {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) }
            }
            val nameEdit = textBox(context, text.name, weight = 1.4f, hintRes = R.string.body_gen_edit_name)
            row.addView(nameEdit.first)
            val numEdits = hints.mapIndexed { at, hintRes ->
                val box = textBox(
                    context, text.numbers.getOrElse(at) { "" },
                    weight = 1f, hintRes = hintRes, numeric = true
                )
                row.addView(box.first)
                box.second
            }
            root.addView(row)
            return AxisRow(nameEdit.second, numEdits)
        }

        // ── 키 ──
        caption(R.string.body_gen_edit_height, top = 8)
        heightRows = state.heightRows.map {
            axisRow(it, listOf(R.string.body_gen_edit_center, R.string.body_gen_edit_variance))
        }
        // ── 몸통 ──
        caption(R.string.body_gen_edit_torso)
        torsoRows = state.torsoRows.map {
            axisRow(it, listOf(
                R.string.body_gen_edit_waist_ratio,
                R.string.body_gen_edit_bmi,
                R.string.body_gen_edit_max
            ))
        }
        val torsoWarning = warningLine(context, dp(2))
        root.addView(torsoWarning)
        // ── 가슴 ──
        caption(R.string.body_gen_edit_bust)
        bustRows = state.bustRows.map { axisRow(it, listOf(R.string.body_gen_edit_cup_diff)) }
        // ── 힙 ──
        caption(R.string.body_gen_edit_hip)
        hipRows = state.hipRows.map {
            axisRow(it, listOf(R.string.body_gen_edit_hip_bonus, R.string.body_gen_edit_max))
        }
        val hipWarning = warningLine(context, dp(2))
        root.addView(hipWarning)
        // ── 프리셋 ── 축을 스피너로 고른다: 이름을 적게 하면 축 이름을 바꿀 때마다
        // 프리셋이 조용히 끊긴다(저장은 자리로 한다 — 그 사실을 화면도 그대로 따른다).
        caption(R.string.body_gen_edit_preset)
        presetRows = state.presetRows.map { preset ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) }
            }
            val name = textBox(context, preset.name, weight = 1.4f, hintRes = R.string.body_gen_edit_name)
            row.addView(name.first)
            fun spinner(labels: List<String>, selected: Int): Spinner =
                Spinner(context).apply {
                    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels)
                        .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    setSelection(selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            // 스피너 항목은 **지금 창에 적힌 이름**이 아니라 저장된 이름이다 — 이름을 고치는
            // 중에 항목이 흔들리면 고르던 자리를 잃는다. 저장은 자리로 하므로 뜻은 같다.
            // **담긴 것에서 오는 이유는 [기본값으로]가 이 글자도 되돌리기 때문이다**(B-260) —
            // `current`에서 다시 뽑으면 *되돌린 뒤 회전*에서 항목만 옛 이름으로 되살아난다.
            val t = spinner(state.presetOptionLabels.torso, preset.torso)
            val b = spinner(state.presetOptionLabels.bust, preset.bust)
            val h = spinner(state.presetOptionLabels.hip, preset.hip)
            row.addView(t); row.addView(b); row.addView(h)
            root.addView(row)
            PresetRow(name.second, t, b, h)
        }

        /**
         * **구간에 들어갈 정수 cm가 없는 축을 그 자리에서 말한다**(B-202 · 확정 15장 6번).
         *
         * 저장을 막지 않으므로 **저장을 누른 뒤에 붙이면 창이 닫혀 아무도 못 본다** —
         * 그래서 적는 동안 계속 다시 잰다. 값 하나라도 아직 수가 아니면 잴 수 없으므로
         * 그때는 **아무 말도 하지 않는다**(틀린 경고보다 침묵이 낫다. 저장 때의 범위 검증이
         * 그 칸을 따로 잡는다).
         */
        fun refreshBandWarnings() {
            val gen = probeOf(current, heightRows, torsoRows, hipRows)
            val empty = gen?.emptyBands()
            // 이름 칸이 비어 있으면 **저장된 이름**으로 부른다 — 비었다고 이름을 빼면
            // 부를 이름이 없어져 경고가 통째로 사라진다(그때가 침묵이면 안 되는 자리다).
            fun show(view: TextView, rows: List<AxisRow>, saved: List<String>, bad: List<Int>?) {
                val names = bad.orEmpty().map { at ->
                    rows.getOrNull(at)?.name?.text?.toString()?.trim()?.ifEmpty { null }
                        ?: saved.getOrNull(at).orEmpty()
                }
                view.visibility = if (bad.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (!bad.isNullOrEmpty()) {
                    view.text = context.getString(
                        R.string.body_gen_edit_warn_empty_band, names.joinToString(", ")
                    )
                }
            }
            show(torsoWarning, torsoRows, current.torsoOptions.map { it.label }, empty?.torso)
            show(hipWarning, hipRows, current.hipOptions.map { it.label }, empty?.hip)
        }
        // 경고를 낳는 칸 전부에 단다 — 키(중심·흔들림)가 몸통 구간의 cm 폭을 정하고,
        // 이름은 경고 문구 안에 그대로 들어간다.
        val watched = heightRows.flatMap { it.numbers } +
            torsoRows.map { it.numbers[TORSO_END_COL] } + hipRows.map { it.numbers[HIP_END_COL] } +
            (torsoRows + hipRows).map { it.name }
        for (edit in watched) {
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = refreshBandWarnings()
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        refreshBandWarnings()

        // 바깥을 눌러 닫히지 않는다 — 칸 스물이 넘는 창이라 손이 미끄러지면
        // 적던 것이 말없이 사라진다. 닫는 길은 저장·취소 둘뿐이다(R-27 결).
        // **회전은 종전에 이 방어를 지나갔다**(B-260) — 이제 적던 것이 함께 넘어간다.
        isCancelable = false
        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.body_gen_edit_title)
            // 축 열넷 + 프리셋 넷이라 본문이 화면을 넘는다 — 높이 상한을 건다(R-31).
            .setView(cappedScrollView(context).apply { addView(root) })
            .setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.body_gen_edit_reset, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setValidatedPositiveButton(
                    onShow = {
                        // '기본값으로'는 **닫지 않고 칸을 되돌린다** — 눌러서 확인한 뒤
                        // 저장하는 것이 이 단추의 쓰임이고, 취소로 빠져나갈 길도 남는다.
                        it.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            resetTo(context, BodyAnalysisConfig.DEFAULT_GENERATION, heightRows,
                                torsoRows, bustRows, hipRows, presetRows)
                            // 스피너에 실은 글자가 바뀌었으므로 담을 것도 함께 바꾼다 —
                            // 안 바꾸면 되돌린 뒤 회전에서 항목만 옛 이름으로 되살아난다.
                            presetOptionLabels = BodyGenerationEditState.OptionLabels
                                .of(BodyAnalysisConfig.DEFAULT_GENERATION)
                            refreshBandWarnings()
                        }
                    }
                ) {
                    val built = build(context, heightRows, torsoRows, bustRows, hipRows, presetRows)
                    // **콜백이 아니라 결과로 건넨다**(B-260) — 인스턴스에 꽂힌 람다는 회전이
                    // 지우지만 결과는 프래그먼트 매니저가 들고 있다가 되살아난 호스트에 준다.
                    if (built != null) {
                        setFragmentResult(
                            RESULT_KEY,
                            bundleOf(RESULT_GENERATION_JSON to BodyAnalysisConfig.generationToJsonString(built))
                        )
                    }
                    built != null
                }
            }
    }

    // ── 화면 조각 ──────────────────────────────────────────────────────────

    /**
     * 경고 한 줄 — 평소에는 자리도 차지하지 않는다(`GONE`).
     * 붉은 글씨를 쓰되 **저장을 막지 않는다**는 것이 이 줄과 [showInlineError]의 차이다.
     */
    private fun warningLine(context: Context, pad: Int): TextView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_App_Caption)
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorError))
        setPadding(0, pad, 0, pad)
        visibility = View.GONE
    }

    /**
     * 지금 창에 적힌 값으로 세운 한 벌 — **잴 수 없으면 null이다.**
     *
     * 수가 아니거나 범위 밖인 칸이 하나라도 있으면 돌려주지 않는다. 그런 값은 저장이
     * 어차피 거부하므로(범위 검증), 그 위에서 센 경고는 **일어나지 않을 일에 대한 경고**다.
     * 오름차순이 깨진 것도 같은 이유로 뺀다 — 그 자리는 저장 때 제 문구가 따로 붙고,
     * 두 말을 겹쳐 하면 고칠 곳이 흐려진다.
     */
    private fun probeOf(
        current: GenerationPreset,
        heightRows: List<AxisRow>, torsoRows: List<AxisRow>, hipRows: List<AxisRow>
    ): GenerationPreset? {
        val limits = GenerationPreset.Limits
        fun col(rows: List<AxisRow>, at: Int, range: ClosedFloatingPointRange<Double>): List<Double>? {
            val values = rows.mapNotNull {
                it.numbers.getOrNull(at)?.text?.toString()?.trim()?.toDoubleOrNull()?.takeIf { v -> v in range }
            }
            return if (values.size == rows.size) values else null
        }
        val centers = col(heightRows, HEIGHT_CENTER_COL, limits.HEIGHT_CENTER) ?: return null
        val variances = col(heightRows, HEIGHT_VARIANCE_COL, limits.HEIGHT_VARIANCE) ?: return null
        val torsoEnds = col(torsoRows, TORSO_END_COL, limits.TORSO_END) ?: return null
        val hipEnds = col(hipRows, HIP_END_COL, limits.HIP_END) ?: return null
        if (limits.ascendingViolations(torsoEnds).isNotEmpty()) return null
        if (limits.ascendingViolations(hipEnds).isNotEmpty()) return null
        return current.copy(
            heightOptions = current.heightOptions.mapIndexed { i, o ->
                o.copy(center = centers[i], variance = variances[i])
            },
            torsoOptions = current.torsoOptions.mapIndexed { i, o -> o.copy(maxRatio = torsoEnds[i]) },
            hipOptions = current.hipOptions.mapIndexed { i, o -> o.copy(maxDiff = hipEnds[i]) }
        )
    }

    private class AxisRow(val name: EditText, val numbers: List<EditText>)

    private class PresetRow(val name: EditText, val torso: Spinner, val bust: Spinner, val hip: Spinner)

    private fun textBox(
        context: Context, value: String, weight: Float, hintRes: Int, numeric: Boolean = false
    ): Pair<TextInputLayout, EditText> {
        val edit = TextInputEditText(context).apply {
            setText(value)
            maxLines = 1
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val layout = TextInputLayout(context).apply {
            hint = context.getString(hintRes)
            addView(edit)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                .apply { marginEnd = (4 * context.resources.displayMetrics.density).toInt() }
        }
        return layout to edit
    }

    /**
     * 수를 칸의 글자로 — **순수 계층이 단일 소스다**([BodyGenerationEditState.format]).
     *
     * 창이 따로 적으면 회전 전후로 같은 값이 다른 글자로 보인다(담는 쪽은 그쪽 함수를
     * 쓰기 때문이다). 자릿수가 왜 그 굵기여야 하는지는 그 함수의 주석이 든다.
     */
    private fun format(value: Double): String = BodyGenerationEditState.format(value)

    private fun resetTo(
        context: Context,
        gen: GenerationPreset,
        heightRows: List<AxisRow>, torsoRows: List<AxisRow>, bustRows: List<AxisRow>,
        hipRows: List<AxisRow>, presetRows: List<PresetRow>
    ) {
        fun fill(rows: List<AxisRow>, labels: List<String>, numbers: List<List<Double>>) {
            for ((i, row) in rows.withIndex()) {
                row.name.setText(labels.getOrNull(i) ?: continue)
                numbers.getOrNull(i)?.forEachIndexed { j, v -> row.numbers.getOrNull(j)?.setText(format(v)) }
            }
        }
        fill(heightRows, gen.heightOptions.map { it.label }, gen.heightOptions.map { listOf(it.center, it.variance) })
        fill(torsoRows, gen.torsoOptions.map { it.label }, gen.torsoOptions.map { listOf(it.waistRatio, it.bmiTarget, it.maxRatio) })
        fill(bustRows, gen.bustOptions.map { it.label }, gen.bustOptions.map { listOf(it.cupDiff) })
        fill(hipRows, gen.hipOptions.map { it.label }, gen.hipOptions.map { listOf(it.hipBonus, it.maxDiff) })
        // 스피너 **항목 글자도** 되돌린다 — 이름 칸만 바꾸면 축 이름은 기본값인데
        // 프리셋의 항목은 사용자가 지은 옛 이름으로 남아 한 창이 두 말을 한다.
        fun relabel(spinner: Spinner, labels: List<String>, selected: Int) {
            spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinner.setSelection(selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)))
        }
        for ((i, row) in presetRows.withIndex()) {
            val p = gen.bodyPresets.getOrNull(i) ?: continue
            row.name.setText(p.label)
            relabel(row.torso, gen.torsoOptions.map { it.label }, p.torso)
            relabel(row.bust, gen.bustOptions.map { it.label }, p.bust)
            relabel(row.hip, gen.hipOptions.map { it.label }, p.hip)
        }
    }

    // ── 읽기·검증 ─────────────────────────────────────────────────────────

    /**
     * 칸을 읽어 한 벌을 짓는다. 못 지으면 **그 칸에 붉은 글씨를 달고 null**이다 —
     * 창은 열린 채 남아 적은 것이 유실되지 않는다(R-27).
     *
     * 여기서 막는 것은 *사람이 고칠 수 있는 잘못*이다(빈 칸·숫자 아님·경계가 거꾸로).
     * 파일에서 들어온 값의 교정은 [GenerationPreset.sanitized]가 따로 든다 — 그쪽은
     * 사람이 없는 자리라 되묻지 못하고, 이쪽은 사람이 있으므로 되묻는 것이 옳다.
     */
    private fun build(
        context: Context,
        heightRows: List<AxisRow>, torsoRows: List<AxisRow>, bustRows: List<AxisRow>,
        hipRows: List<AxisRow>, presetRows: List<PresetRow>
    ): GenerationPreset? {
        var ok = true
        fun name(edit: EditText): String {
            val text = edit.text.toString().trim()
            if (text.isEmpty()) {
                edit.showInlineError(context.getString(R.string.body_gen_edit_error_name))
                ok = false
            }
            return text
        }
        // 범위는 **파일 교정과 같은 잣대**를 쓴다(GenerationPreset.Limits) — 두 벌로 두면
        // 창은 거부하는데 엑셀로는 들어오는 값이 생기고, 그 값은 고칠 자리가 화면에 없다.
        fun num(edit: EditText, range: ClosedFloatingPointRange<Double>): Double {
            val value = edit.text.toString().trim().toDoubleOrNull()
            if (value == null || value !in range) {
                edit.showInlineError(
                    context.getString(
                        R.string.body_gen_edit_error_range,
                        format(range.start), format(range.endInclusive)
                    )
                )
                ok = false
                return range.start
            }
            return value
        }

        val limits = GenerationPreset.Limits
        val heights = heightRows.map {
            BodyAnalysisConfig.HeightOption(
                name(it.name), num(it.numbers[0], limits.HEIGHT_CENTER), num(it.numbers[1], limits.HEIGHT_VARIANCE)
            )
        }
        val torsos = torsoRows.map {
            BodyAnalysisConfig.TorsoOption(
                name(it.name), num(it.numbers[0], limits.WAIST_RATIO), num(it.numbers[1], limits.BMI),
                maxRatio = num(it.numbers[2], limits.TORSO_END)
            )
        }
        val busts = bustRows.map {
            BodyAnalysisConfig.BustOption(name(it.name), num(it.numbers[0], limits.CUP_DIFF))
        }
        val hips = hipRows.map {
            BodyAnalysisConfig.HipOption(
                name(it.name), num(it.numbers[0], limits.HIP_BONUS), maxDiff = num(it.numbers[1], limits.HIP_END)
            )
        }
        val presets = presetRows.map {
            BodyAnalysisConfig.BodyPreset(
                name(it.name), it.torso.selectedItemPosition,
                it.bust.selectedItemPosition, it.hip.selectedItemPosition
            )
        }

        // 축의 끝은 오름차순이어야 한다 — 거꾸로면 그 축이 겨눌 폭이 없어지고,
        // 요약도 그 이름을 영영 돌려주지 못한다(고른 축과 돌아오는 말이 갈린다).
        ok = ascendingOrError(context, torsoRows, torsos.map { it.maxRatio }, TORSO_END_COL) && ok
        ok = ascendingOrError(context, hipRows, hips.map { it.maxDiff }, HIP_END_COL) && ok

        return if (ok) GenerationPreset(heights, torsos, busts, hips, presets) else null
    }

    /** 어긋난 자리를 세는 것은 순수 계층이 든다 — 창은 그 자리에 붉은 글씨만 단다. */
    private fun ascendingOrError(
        context: Context, rows: List<AxisRow>, ends: List<Double>, column: Int
    ): Boolean {
        val bad = GenerationPreset.Limits.ascendingViolations(ends)
        for (i in bad) {
            rows[i].numbers[column].showInlineError(context.getString(R.string.body_gen_edit_error_order))
        }
        return bad.isEmpty()
    }
}
