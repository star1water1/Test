package com.novelcharacter.app.ui.character

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodyAnalysisConfig.GenerationPreset
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import java.util.Locale

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
 */
object BodyGenerationEditDialog {

    /**
     * @param current 지금 쓰이는 한 벌(빈 축이 없는 것으로 정규화된 값을 받는다)
     * @param onSave 저장 시 새 한 벌. **부르는 쪽이 필드 config에 담는다** — 이 창은
     *   화면과 검증만 든다(계산·저장을 창 안에 적으면 시험이 닿지 않는다).
     */
    fun show(
        context: Context,
        current: GenerationPreset,
        onSave: (GenerationPreset) -> Unit
    ) {
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

        /** 축 한 줄 — 이름 + 수치 칸 몇. 칸 수는 축마다 다르고 행 수는 [current]가 정한다. */
        fun axisRow(label: String, fields: List<NumField>): AxisRow {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) }
            }
            val nameEdit = textBox(context, label, weight = 1.4f, hintRes = R.string.body_gen_edit_name)
            row.addView(nameEdit.first)
            val numEdits = fields.map { f ->
                val box = textBox(context, format(f.value), weight = 1f, hintRes = f.hintRes, numeric = true)
                row.addView(box.first)
                box.second
            }
            root.addView(row)
            return AxisRow(nameEdit.second, numEdits)
        }

        // ── 키 ──
        caption(R.string.body_gen_edit_height, top = 8)
        val heightRows = current.heightOptions.map {
            axisRow(it.label, listOf(
                NumField(it.center, R.string.body_gen_edit_center),
                NumField(it.variance, R.string.body_gen_edit_variance)
            ))
        }
        // ── 몸통 ──
        caption(R.string.body_gen_edit_torso)
        val torsoRows = current.torsoOptions.map {
            axisRow(it.label, listOf(
                NumField(it.waistRatio, R.string.body_gen_edit_waist_ratio),
                NumField(it.bmiTarget, R.string.body_gen_edit_bmi),
                NumField(it.maxRatio, R.string.body_gen_edit_max)
            ))
        }
        // ── 가슴 ──
        caption(R.string.body_gen_edit_bust)
        val bustRows = current.bustOptions.map {
            axisRow(it.label, listOf(NumField(it.cupDiff, R.string.body_gen_edit_cup_diff)))
        }
        // ── 힙 ──
        caption(R.string.body_gen_edit_hip)
        val hipRows = current.hipOptions.map {
            axisRow(it.label, listOf(
                NumField(it.hipBonus, R.string.body_gen_edit_hip_bonus),
                NumField(it.maxDiff, R.string.body_gen_edit_max)
            ))
        }
        // ── 프리셋 ── 축을 스피너로 고른다: 이름을 적게 하면 축 이름을 바꿀 때마다
        // 프리셋이 조용히 끊긴다(저장은 자리로 한다 — 그 사실을 화면도 그대로 따른다).
        caption(R.string.body_gen_edit_preset)
        val presetRows = current.bodyPresets.map { preset ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) }
            }
            val name = textBox(context, preset.label, weight = 1.4f, hintRes = R.string.body_gen_edit_name)
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
            val t = spinner(current.torsoOptions.map { it.label }, preset.torso)
            val b = spinner(current.bustOptions.map { it.label }, preset.bust)
            val h = spinner(current.hipOptions.map { it.label }, preset.hip)
            row.addView(t); row.addView(b); row.addView(h)
            root.addView(row)
            PresetRow(name.second, t, b, h)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.body_gen_edit_title)
            // 바깥을 눌러 닫히지 않는다 — 칸 스물이 넘는 창이라 손이 미끄러지면
            // 적던 것이 말없이 사라진다. 닫는 길은 저장·취소 둘뿐이다(R-27 결).
            .setCancelable(false)
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
                        }
                    }
                ) {
                    val built = build(context, heightRows, torsoRows, bustRows, hipRows, presetRows)
                    if (built != null) onSave(built)
                    built != null
                }
                dialog.show()
            }
    }

    // ── 화면 조각 ──────────────────────────────────────────────────────────

    private class NumField(val value: Double, val hintRes: Int)

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
     * 소수점 뒤 0을 달지 않는다 — `18.5`는 그대로, `24.0`은 `24`로 보인다.
     *
     * 자릿수가 **교정이 미는 폭(`Limits.TORSO_END_STEP` = .001)보다 잘아야 한다** —
     * 굵으면 창을 열었다 저장만 해도 값이 반올림되어, 손대지 않은 설정이 조용히 바뀐다.
     */
    private fun format(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString()
        else String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

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
        ok = ascendingOrError(context, torsoRows, torsos.map { it.maxRatio }, 2) && ok
        ok = ascendingOrError(context, hipRows, hips.map { it.maxDiff }, 1) && ok

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
