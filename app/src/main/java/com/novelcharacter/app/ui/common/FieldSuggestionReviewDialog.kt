package com.novelcharacter.app.ui.common

import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.ai.FieldSuggestionReviewState
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.util.cappedScrollView

/** Review UI shared by character and event fields; domain validation stays in the suggester. */
object FieldSuggestionReviewDialog {
    private val active = java.util.WeakHashMap<Fragment, AlertDialog>()
    fun show(
        fragment: Fragment,
        targets: List<CharacterFieldAiSuggester.FieldSpec>,
        outcome: CharacterFieldAiSuggester.SuggestOutcome,
        state: FieldSuggestionReviewState,
        notices: String,
        onApply: (List<CharacterFieldAiSuggester.Suggestion>) -> Boolean,
        onClose: () -> Unit,
        onRefine: (List<String>, String) -> Boolean,
        retryKeys: List<String> = emptyList(),
        canApply: Boolean = true,
        imageCount: Int = 0,
        running: androidx.lifecycle.LiveData<Boolean>? = null
    ) {
        active.remove(fragment)?.dismiss()
        val context = fragment.requireContext()
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val specs = targets.associateBy { it.key }
        val originals = outcome.suggestions.associateBy { it.fieldKey }
        state.seedDefaults(outcome.suggestions.filter {
            specs[it.fieldKey]?.currentValue.isNullOrBlank() &&
                it.confidence != CharacterFieldAiSuggester.Confidence.LOW
        }.map { it.fieldKey })
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        fun label(value: String) = TextView(context).apply {
            text = value
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        fun button(title: String, action: () -> Unit) = MaterialButton(context, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = title
            setOnClickListener { action() }
        }
        panel.addView(label(notices))
        panel.addView(label("받은 결과와 수정·선택 내용은 이 기기에 보관합니다. 편집 화면을 다시 연 뒤 같은 AI 버튼을 누르면 검토를 이어갈 수 있습니다. 앱 삭제·데이터 삭제 시에는 지워집니다."))
        if(imageCount>0) panel.addView(label("AI 보완·재요청은 첫 요청의 이미지 ${imageCount}장을 요청마다 다시 보냅니다. 이미지마다 약 ${com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MIN}~${com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MAX} 토큰이 추가됩니다."))
        val boxes = linkedMapOf<String, CheckBox>()
        val commitEdits = linkedMapOf<String, () -> Boolean>()
        lateinit var dialog: AlertDialog
        fun refine(keys: List<String>, instruction: String) {
            if (keys.isEmpty()) {
                Toast.makeText(context, R.string.ai_review_pick_none, Toast.LENGTH_SHORT).show()
                return
            }
            if (keys.any { commitEdits[it]?.invoke() == false }) return
            if (onRefine(keys, instruction)) dialog.dismiss()
        }
        for ((key, original) in originals) {
            val spec = specs[key] ?: continue
            state.current(original)
            val box = CheckBox(context).apply {
                text = spec.name
                textSize = 16f
                isChecked = state.isChecked(key)
                setOnCheckedChangeListener { _, on -> state.setChecked(key, on) }
            }
            boxes[key] = box
            panel.addView(box)
            val value = label("")
            val source = label("")
            val note = label("")
            fun render() {
                val current = state.current(original)
                value.text = buildString {
                    if (spec.currentValue.isNotBlank()) append(context.getString(
                        R.string.ai_field_overwrite_format, spec.currentValue, current.value))
                    else append(current.value)
                    if (current.editedByUser) append("  [직접 수정]")
                    else current.confidence?.let { append("  [${it.label}]") }
                    if (current.outsideLibrary) append("  [목록 밖]")
                }
                source.text = current.sourceEvidence?.let { "원문 근거\n“$it”" }.orEmpty()
                source.isVisible = source.text.isNotEmpty()
                val memo = current.suggestionNote ?: current.reason
                note.text = if (memo.isBlank()) "" else "AI 제안 메모\n$memo"
                note.isVisible = memo.isNotBlank()
            }
            render()
            panel.addView(value); panel.addView(source); panel.addView(note)
            val editor = EditText(context).apply {
                hint = context.getString(R.string.ai_field_refine_value_hint)
                setText(state.editDrafts[key] ?: state.current(original).value)
                setSingleLine(false)
                minLines = 2
                isVisible = key in state.editDrafts
                doAfterTextChanged { if (isVisible) { state.editDrafts[key] = it.toString(); state.changed() } }
            }
            val format = label(listOfNotNull(spec.formatHint,
                spec.options.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString("\n"))
            format.isVisible = false
            lateinit var done: View
            fun commit(): Boolean {
                if (!editor.isVisible) return true
                val raw = editor.text.toString().trim()
                if (raw.isBlank()) {
                    editor.error = context.getString(R.string.ai_field_refine_value_required)
                    return false
                }
                return when (val valid = CharacterFieldAiSuggester.normalizeChecked(raw, spec)) {
                    is CharacterFieldAiSuggester.Normalized.Rejected -> {
                        editor.error = valid.cause.label
                        false
                    }
                    is CharacterFieldAiSuggester.Normalized.Ok -> {
                        state.remember(state.current(original).copy(value=valid.value,
                            editedByUser=true, outsideLibrary=valid.outsideLibrary))
                        state.editDrafts.remove(key); state.changed()
                        editor.isVisible = false; done.isVisible = false; format.isVisible = false
                        render()
                        true
                    }
                }
            }
            commitEdits[key] = { commit() }
            done = button("수정 반영 · AI 호출 없음") { commit() }.apply { isVisible=editor.isVisible }
            panel.addView(editor); panel.addView(format); panel.addView(done)
            panel.addView(button("직접 수정") {
                editor.setText(state.editDrafts[key] ?: state.current(original).value)
                editor.isVisible = true; done.isVisible = true
                state.editDrafts[key] = editor.text.toString(); state.changed()
                format.isVisible = format.text.isNotBlank()
                editor.requestFocus()
            })
            panel.addView(button("원래 AI 제안으로 되돌리기") {
                state.reset(original)
                editor.isVisible = false; done.isVisible = false; format.isVisible = false
                render()
            })
            if (spec.type == FieldType.TEXT && !spec.isBirthDate && spec.structuredSeparator == null &&
                (original.suggestionNote ?: original.reason).isNotBlank()) {
                panel.addView(button("메모를 현재 제안에 합치기") {
                    val current = state.current(original)
                    editor.isVisible = true; done.isVisible = true
                    editor.setText(current.value + "\n" + (current.suggestionNote ?: current.reason))
                    state.editDrafts[key] = editor.text.toString(); state.changed()
                    editor.requestFocus()
                })
            }
            panel.addView(NaturalLanguageInput.create(fragment,"field-refine:${state.sessionId}:$key",
                "AI에게 보완할 방향을 알려 주세요",state.instructions[key].orEmpty(),
                onChanged={state.instructions[key]=it; state.changed()},
                terms={ (spec.options+spec.usageExamples+spec.canonicalByVariant.keys).map {
                    com.novelcharacter.app.speech.SpeechVocabulary.Term(it,0) } }))
            panel.addView(button("이 항목 AI 보완 · 요청 1건") { refine(listOf(key), state.instructions[key].orEmpty()) })
            panel.addView(View(context).apply {
                setBackgroundColor(context.getColor(R.color.outline_variant))
                layoutParams=LinearLayout.LayoutParams(-1, 1)
            })
        }
        if (originals.isEmpty()) panel.addView(label(context.getString(R.string.ai_field_nothing)))
        if (retryKeys.isNotEmpty()) panel.addView(button("못 받은 ${retryKeys.size}개 다시 요청 · 요청 ${CharacterFieldAiSuggester.requestCountFor(retryKeys.size,com.novelcharacter.app.ai.AiService(context).effectiveMaxTokens())}건") {
            refine(retryKeys, "")
        })
        if (originals.size > 1) {
            panel.addView(NaturalLanguageInput.create(fragment,"field-refine:${state.sessionId}:__bulk",
                "선택한 항목에 함께 적용할 방향",state.instructions["__bulk"].orEmpty(),
                onChanged={state.instructions["__bulk"]=it; state.changed()}))
            val bulkButton = button("선택 항목 AI 보완") {
                refine(originals.keys.filter { state.isChecked(it) }, state.instructions["__bulk"].orEmpty())
            }
            fun updateCost() {
                val count = boxes.keys.count { state.isChecked(it) }
                val requests = CharacterFieldAiSuggester.requestCountFor(count,
                    com.novelcharacter.app.ai.AiService(context).effectiveMaxTokens())
                bulkButton.text = "선택 ${count}개 AI 보완 · 요청 ${requests}건"
                if(imageCount>0) bulkButton.append(" · 이미지 총 ${imageCount*requests}장 전송")
            }
            boxes.forEach { (key, box) -> box.setOnCheckedChangeListener { _, on ->
                state.setChecked(key, on)
                updateCost()
            } }
            updateCost()
            panel.addView(bulkButton)
        }
        panel.addView(button("검토 내용 버리기") { onClose(); dialog.dismiss() })
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_review_title)
            .setView(cappedScrollView(context).apply { addView(panel) })
            .setPositiveButton(R.string.ai_field_apply, null)
            .setNegativeButton("닫기 · 내용 보관", null)
            .setNeutralButton(R.string.field_library_ai_select_all, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = canApply && originals.isNotEmpty()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keys = originals.keys.filter { state.isChecked(it) }
                if (keys.isEmpty()) Toast.makeText(context, R.string.ai_review_pick_none, Toast.LENGTH_SHORT).show()
                else if (keys.all { commitEdits[it]?.invoke() != false } &&
                    onApply(keys.map { state.current(originals.getValue(it)) })) dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val select = boxes.values.any { !it.isChecked }
                boxes.values.forEach { it.isChecked = select }
            }
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
        }
        val owner = fragment.viewLifecycleOwnerLiveData.value ?: fragment
        val runningObserver=androidx.lifecycle.Observer<Boolean> { busy ->
            if(active[fragment]===dialog) {if(busy==true) dialog.hide() else dialog.show()}
        }
        owner.lifecycle.addObserver(observer)
        dialog.setOnDismissListener {
            running?.removeObserver(runningObserver)
            owner.lifecycle.removeObserver(observer)
            if (active[fragment] === dialog) active.remove(fragment)
        }
        active[fragment] = dialog
        dialog.show()
        running?.observe(owner,runningObserver)
    }
}
