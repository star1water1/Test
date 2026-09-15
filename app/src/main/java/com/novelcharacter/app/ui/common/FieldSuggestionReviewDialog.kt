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
    private data class Update(val targets: List<CharacterFieldAiSuggester.FieldSpec>,
        val outcome: CharacterFieldAiSuggester.SuggestOutcome, val notices: String,
        val onApply: (List<CharacterFieldAiSuggester.Suggestion>) -> Boolean,
        val onClose: () -> Unit, val onRefine: (List<String>, String) -> Boolean,
        val retryKeys: List<String>, val canApply: Boolean,
        val liveSpecs: () -> List<CharacterFieldAiSuggester.FieldSpec>)
    private data class Active(val session: String, val dialog: AlertDialog, val update: (Update) -> Unit)
    private val active = java.util.WeakHashMap<Fragment, Active>()
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
        running: androidx.lifecycle.LiveData<Boolean>? = null,
        liveSpecs: () -> List<CharacterFieldAiSuggester.FieldSpec>,
        progress: androidx.lifecycle.LiveData<Pair<Int, Int>>? = null,
        onCancel: () -> Unit = {}
    ): AlertDialog {
        val incoming = Update(targets, outcome, notices, onApply, onClose, onRefine, retryKeys, canApply, liveSpecs)
        active[fragment]?.takeIf { it.session == state.sessionId && it.dialog.isShowing }?.let {
            it.update(incoming)
            return it.dialog
        }
        active.remove(fragment)?.dialog?.dismiss()
        var outcome = outcome
        var onApply = onApply
        var onClose = onClose
        var onRefine = onRefine
        var retryKeys = retryKeys
        var canApply = canApply
        var liveSpecs = liveSpecs
        val context = fragment.requireContext()
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        var specs = liveSpecs().associateBy { it.key }
        val requestSpecs = targets.associateBy { it.key }.toMutableMap()
        val originals = outcome.suggestions.associateBy { it.fieldKey }.toMutableMap()
        var syncingChecks = false
        fun provenance(value: CharacterFieldAiSuggester.Suggestion) = com.novelcharacter.app.ai.FieldProvenance.assess(
            value, specs[value.fieldKey], state.receipt(value, outcome.inputReceipts.orEmpty()), state.isDirectConfirmed(value))
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
        val scroll = cappedScrollView(context).apply { addView(panel) }
        val viewContext = ReviewViewContext(scroll).apply { expanded.addAll(state.viewport?.expanded.orEmpty()) }
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                event.actionMasked == android.view.MotionEvent.ACTION_MOVE) viewContext.cancelRestore()
            false
        }
        fun disclosure(key: String, title: String, parent: LinearLayout, body: LinearLayout) {
            body.isVisible = key in viewContext.expanded
            val toggle = button("") {}
            fun caption() { toggle.text = title + if (body.isVisible) " 접기" else " 펼치기" }
            caption()
            toggle.setOnClickListener {
                viewContext.cancelRestore()
                body.isVisible = !body.isVisible
                if (body.isVisible) viewContext.expanded.add(key) else viewContext.expanded.remove(key)
                caption(); state.viewport = viewContext.capture(); state.changed()
            }
            parent.addView(toggle); parent.addView(body)
        }
        val busyButtons = mutableListOf<View>()
        fun idleButton(title: String, action: () -> Unit) = button(title) {
            if (running?.value != true) action()
        }.also { busyButtons.add(it) }
        val status = label("")
        val errors = label("")
        val cancel = button("남은 요청 중단") { onCancel() }
        panel.addView(status); panel.addView(cancel); panel.addView(errors)
        val noticeView = label(notices); panel.addView(noticeView)
        panel.addView(label(com.novelcharacter.app.ai.AiInputPreflight.SEND_NOTICE))
        panel.addView(label("받은 결과와 수정·선택 내용은 이 기기에 보관합니다. 편집 화면을 다시 연 뒤 같은 AI 버튼을 누르면 검토를 이어갈 수 있습니다. 앱 삭제·데이터 삭제 시에는 지워집니다."))
        if(imageCount>0) panel.addView(label("AI 보완·재요청은 첫 요청의 이미지 ${imageCount}장을 요청마다 다시 보냅니다. 이미지마다 약 ${com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MIN}~${com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MAX} 토큰이 추가됩니다."))
        val boxes = linkedMapOf<String, CheckBox>()
        val commitEdits = linkedMapOf<String, () -> Boolean>()
        val renders = linkedMapOf<String, () -> Unit>()
        lateinit var dialog: AlertDialog
        fun refine(keys: List<String>, instruction: String) {
            if (running?.value == true) return
            if (keys.isEmpty()) {
                Toast.makeText(context, R.string.ai_review_pick_none, Toast.LENGTH_SHORT).show()
                return
            }
            if (keys.any { commitEdits[it]?.invoke() == false }) return
            onRefine(keys, instruction)
        }
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(rows)
        val rowKeys = mutableSetOf<String>()
        fun addRow(key: String, original: CharacterFieldAiSuggester.Suggestion) {
            if (!rowKeys.add(key)) return
            val spec = specs[key] ?: requestSpecs[key] ?: return
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            rows.addView(row); viewContext.anchor(key, row)
            val initial = state.current(original)
            state.syncSelection(initial, provenance(initial).defaultOn)
            val box = CheckBox(context).apply {
                text = spec.name
                textSize = 16f
                isChecked = state.isChecked(key)
                setOnCheckedChangeListener { _, on -> if (!syncingChecks) state.setChecked(key, on) }
            }
            boxes[key] = box
            row.addView(box)
            val value = label("")
            val source = label("")
            val badge = label("")
            val why = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val reason = label("")
            val note = label("")
            var renderActions: () -> Unit = {}
            fun render() {
                val current = state.current(original)
                val assessment = provenance(current)
                state.syncSelection(current, assessment.defaultOn)
                syncingChecks = true
                box.isChecked = state.isChecked(key)
                syncingChecks = false
                value.text = buildString {
                    val live = specs[key]
                    if (live == null) append("현재 폼에 없는 항목입니다. 제안은 보관했습니다.\n")
                    if (!live?.currentValue.isNullOrBlank()) append(context.getString(
                        R.string.ai_field_overwrite_format, live?.currentValue, current.value))
                    else append(current.value)
                    if (current.editedByUser) append("  [직접 수정]")
                    if (current.outsideLibrary) append("  [목록 밖]")
                }
                badge.text = assessment.label + if (state.needsSelectionReview(key)) " · 선택 재검토 필요" else ""
                source.text = buildString {
                    if (current.editedByUser) append("수정 전 AI 제안의 출처\n")
                    append(assessment.label).append("\n").append(assessment.explanation)
                    current.confidence?.let { append("\n모델 근거 강도: ").append(when(it) {
                        CharacterFieldAiSuggester.Confidence.HIGH -> "높음"
                        CharacterFieldAiSuggester.Confidence.MEDIUM -> "중간"
                        CharacterFieldAiSuggester.Confidence.LOW -> "낮음"
                    }) }
                    if (state.needsSelectionReview(key)) append("\n현재 AI 제안은 출처를 다시 확인해야 하므로 이전 체크를 해제했습니다.")
                    assessment.evidence.firstOrNull()?.let { append("\n원문 인용: “${it.quote}”") }
                }
                reason.text=if(current.reason.isBlank()) "" else "추천 이유\n${current.reason}"
                reason.isVisible=current.reason.isNotBlank()
                val memo = current.suggestionNote.orEmpty()
                note.text = if (memo.isBlank()) "" else "AI 제안 메모\n$memo"
                note.isVisible = memo.isNotBlank()
                renderActions()
            }
            renders[key] = { render() }
            render()
            row.addView(value); row.addView(badge)
            why.addView(source); why.addView(reason); why.addView(note)
            disclosure("why:$key", "근거·메모", row, why)
            why.addView(button("당시 자료·인용 맥락 보기") {
                val current = state.current(original)
                val session = state.sessionId
                val assessment = provenance(current)
                val detail = buildString {
                    append("확인할 제안값\n").append(current.value).append("\n\n")
                    append(assessment.dispatch).append("\n\n").append(assessment.explanation)
                    current.provenance?.let {
                        append("\n모델의 분류 주장: ").append(it.origins.orEmpty().joinToString(", ").ifBlank { "미제공" })
                        append("\n모델의 자료 주장: ").append(it.source ?: "미제공")
                    }
                    current.provenance?.quote?.let { append("\n\n모델이 제시한 인용\n").append(it) }
                    assessment.evidence.forEach {
                        val name = when (it.source) { "BRIEF" -> "브리핑"; "INSTRUCTION" -> "보완 지시"; else -> "참고자료" }
                        append("\n\n당시 ").append(name).append("의 전체 맥락\n").append(it.context)
                    }
                }
                MaterialAlertDialogBuilder(context).setTitle("전송 자료와 인용 확인")
                    .setView(cappedScrollView(context).apply { addView(label(detail)) })
                    .setNegativeButton("닫기", null).apply {
                        if (assessment.evidence.isNotEmpty() && current.provenance?.origins
                                ?.map { it.uppercase(java.util.Locale.ROOT) } == listOf("DIRECT") &&
                            current.provenance.malformed == false) {
                            setPositiveButton("원문과 값의 대응 확인 · 항목 선택") { _, _ ->
                                // The confirmation belongs to exactly the value and request the user saw.
                                if (state.sessionId == session && state.current(original) == current) {
                                    state.confirmDirect(current); state.setChecked(key, true); render()
                                } else Toast.makeText(context, "제안이 바뀌었습니다. 새 값과 원문을 다시 확인하세요.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.show()
            })
            val editor = EditText(context).apply {
                hint = context.getString(R.string.ai_field_refine_value_hint)
                setText(state.editDrafts[key] ?: state.current(original).value)
                setSingleLine(false)
                minLines = 2
                isVisible = key in state.editDrafts
                doAfterTextChanged { if (isVisible) state.setDraft(key, it.toString()) }
            }
            viewContext.editor("edit:$key", editor)
            fun focusEditor() {
                editor.requestFocus()
                viewContext.revealEditor("edit:$key")
            }
            val format = label(listOfNotNull(spec.formatHint,
                spec.options.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString("\n"))
            format.isVisible = editor.isVisible && format.text.isNotBlank()
            lateinit var done: View
            fun commit(): Boolean {
                if (!editor.isVisible) return true
                val raw = editor.text.toString().trim()
                if (raw.isBlank()) {
                    editor.error = context.getString(R.string.ai_field_refine_value_required)
                    return false
                }
                val live = specs[key]
                if (live == null) { editor.error = "현재 폼에 없는 항목입니다."; return false }
                return when (val valid = CharacterFieldAiSuggester.normalizeChecked(raw, live)) {
                    is CharacterFieldAiSuggester.Normalized.Rejected -> {
                        editor.error = valid.cause.label
                        false
                    }
                    is CharacterFieldAiSuggester.Normalized.Ok -> {
                        state.remember(state.current(original).copy(value=valid.value,
                            editedByUser=true, outsideLibrary=valid.outsideLibrary))
                        state.removeDraft(key)
                        editor.isVisible = false; done.isVisible = false; format.isVisible = false
                        render()
                        true
                    }
                }
            }
            commitEdits[key] = { commit() }
            done = button("수정 반영 · AI 호출 없음") { commit() }.apply { isVisible=editor.isVisible }
            row.addView(editor); row.addView(format); row.addView(done)
            row.addView(button("직접 수정") {
                editor.setText(state.editDrafts[key] ?: state.current(original).value)
                editor.isVisible = true; done.isVisible = true
                state.setDraft(key, editor.text.toString())
                format.isVisible = format.text.isNotBlank()
                focusEditor()
            })
            val reset = button("최근 AI 제안으로 되돌리기") {
                state.reset(original)
                editor.isVisible = false; done.isVisible = false; format.isVisible = false
                render()
            }
            row.addView(reset)
            val candidates = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            row.addView(candidates)
            val candidateIds = mutableSetOf<String>()
            renderActions = {
                reset.isVisible = com.novelcharacter.app.ai.ReviewPresentation.canReset(
                    state.current(original).value, state.latest(original).value, state.editDrafts[key])
                for (candidate in state.candidates(key).filter { candidateIds.add(it.id) }) {
                candidates.addView(label((if (candidate.stale) "이전 요청에서 늦게 받은 후보" else
                    "수정 중 받아 따로 보관한 AI 후보") + "\n" + candidate.suggestion.value))
                candidates.addView(button("이 후보 채택 · 편집 중인 값 바꾸기") {
                    state.adopt(candidate.id)
                    editor.isVisible = false; done.isVisible = false; format.isVisible = false
                    render()
                })
            }
            }
            renderActions()
            editor.doAfterTextChanged { renderActions() }
            val mergeMemo = button("") {
                val current = state.current(original)
                val memo = current.suggestionNote?.takeIf { it.isNotBlank() } ?: current.reason
                editor.isVisible = true; done.isVisible = true
                editor.setText(current.value + "\n" + memo)
                state.setDraft(key, editor.text.toString()); focusEditor()
            }
            why.addView(mergeMemo)
            val previousActions = renderActions
            renderActions = {
                previousActions()
                val current = state.current(original)
                val live = specs[key]
                mergeMemo.isVisible = live?.type == FieldType.TEXT && !live.isBirthDate && live.structuredSeparator == null &&
                    (!current.suggestionNote.isNullOrBlank() || current.reason.isNotBlank())
                mergeMemo.text = if (current.suggestionNote.isNullOrBlank()) "추천 이유를 현재 제안에 합치기" else "제안 메모를 현재 제안에 합치기"
            }
            renderActions()
            val refinement = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            disclosure("refine:$key", "AI 보완", row, refinement)
            val instructionInput = NaturalLanguageInput.create(fragment,"field-refine:${state.sessionId}:$key",
                "AI에게 보완할 방향을 알려 주세요",state.instructions[key].orEmpty(),
                onChanged={state.instructions[key]=it; state.changed()},
                terms={ (spec.options+spec.usageExamples+spec.canonicalByVariant.keys).map {
                    com.novelcharacter.app.speech.SpeechVocabulary.Term(it,0) } })
            refinement.addView(instructionInput)
            (instructionInput.getChildAt(0) as? EditText)?.let { viewContext.editor("refine:$key", it) }
            refinement.addView(label("보완 실행 시 외부 AI 요청 1건이 발생합니다." +
                if (imageCount > 0) " 이미지 ${imageCount}장을 다시 보냅니다." else ""))
            refinement.addView(idleButton("이 항목 AI 보완 · 요청 1건") { refine(listOf(key), state.instructions[key].orEmpty()) })
            row.addView(View(context).apply {
                setBackgroundColor(context.getColor(R.color.outline_variant))
                layoutParams=LinearLayout.LayoutParams(-1, 1)
            })
        }
        originals.forEach { (key, value) -> addRow(key, value) }
        val empty = label(context.getString(R.string.ai_field_nothing)); panel.addView(empty)
        val retryButton = idleButton("") { refine(retryKeys, "") }; panel.addView(retryButton)
        val bulk = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        disclosure("bulk", "선택 항목 함께 AI 보완", panel, bulk)
        val bulkInput = NaturalLanguageInput.create(fragment,"field-refine:${state.sessionId}:__bulk",
            "선택한 항목에 함께 적용할 방향",state.instructions["__bulk"].orEmpty(),
            onChanged={state.instructions["__bulk"]=it; state.changed()})
        bulk.addView(bulkInput)
        (bulkInput.getChildAt(0) as? EditText)?.let { viewContext.editor("bulk", it) }
        val bulkButton = idleButton("") {
            refine(originals.keys.filter { state.isChecked(it) }, state.instructions["__bulk"].orEmpty())
        }
        bulk.addView(bulkButton)
        fun updateCost() {
            val maxTokens = com.novelcharacter.app.ai.AiService(context).effectiveMaxTokens()
            val count = boxes.keys.count { state.isChecked(it) }
            val requests = CharacterFieldAiSuggester.requestCountFor(count, maxTokens)
            bulkButton.text = "선택 ${count}개 AI 보완 · 요청 ${requests}건"
            if(imageCount>0) bulkButton.append(" · 이미지 총 ${imageCount*requests}장 전송")
            retryButton.isVisible = retryKeys.isNotEmpty()
            retryButton.text = "못 받은 ${retryKeys.size}개 다시 요청 · 요청 ${CharacterFieldAiSuggester.requestCountFor(retryKeys.size,maxTokens)}건"
            empty.isVisible = originals.isEmpty()
            boxes.forEach { (key, box) -> box.setOnCheckedChangeListener { _, on ->
                if (!syncingChecks) { state.setChecked(key, on); updateCost() }
            } }
        }
        updateCost()
        panel.addView(idleButton("검토 내용 버리기") { onClose(); dialog.dismiss() })
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_field_review_title)
            .setView(scroll)
            .setPositiveButton(R.string.ai_field_apply, null)
            .setNegativeButton("닫기 · 내용 보관", null)
            .setNeutralButton(R.string.field_library_ai_select_all, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = canApply && originals.isNotEmpty()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (running?.value == true) return@setOnClickListener
                val keys = originals.keys.filter { state.isChecked(it) }
                val live = liveSpecs().associateBy { it.key }
                val changed = com.novelcharacter.app.ai.FieldReviewApply.changedKeys(keys, specs, live)
                specs = live
                renders.values.forEach { it() }
                if (changed.isNotEmpty()) {
                    errors.text = "폼의 값이나 항목 설정이 바뀌었습니다. 현재 값과 제안을 확인한 뒤 다시 적용하세요."
                    return@setOnClickListener
                }
                if (keys.isEmpty()) Toast.makeText(context, R.string.ai_review_pick_none, Toast.LENGTH_SHORT).show()
                else if (keys.all { commitEdits[it]?.invoke() != false }) {
                    val prepared = com.novelcharacter.app.ai.FieldReviewApply.prepare(
                        keys.map { state.current(originals.getValue(it)) }, live.values.toList())
                    errors.text = prepared.errors.joinToString("\n")
                    if (prepared.errors.isEmpty() && onApply(prepared.values)) dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                state.viewport = viewContext.capture(); state.changed(); dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val select = boxes.values.any { !it.isChecked }
                boxes.values.forEach { it.isChecked = select }
            }
        }
        val sessionId = state.sessionId
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { if (state.sessionId == sessionId && scroll.isAttachedToWindow) { state.viewport = viewContext.capture(); state.changed() } }
            override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
        }
        val owner = fragment.viewLifecycleOwnerLiveData.value ?: fragment
        fun renderRunning() {
            val position = if (dialog.isShowing && scroll.isLaidOut) viewContext.capture() else null
            val busy = running?.value == true
            val count = progress?.value
            status.isVisible = busy
            status.text = "AI 보완 중 · ${com.novelcharacter.app.ai.ReviewPresentation.progress(count?.first ?: 0, count?.second ?: 0).text}\n직접 수정할 수 있습니다. 남은 요청을 중단해도 이미 보낸 요청은 끝까지 받으며 과금될 수 있습니다."
            cancel.isVisible = busy
            busyButtons.forEach { it.isEnabled = !busy }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !busy && canApply && originals.isNotEmpty()
            viewContext.restore(position, focus = false)
        }
        val runningObserver=androidx.lifecycle.Observer<Boolean> { renderRunning() }
        val progressObserver=androidx.lifecycle.Observer<Pair<Int, Int>> { renderRunning() }
        owner.lifecycle.addObserver(observer)
        dialog.setOnDismissListener {
            if (state.sessionId == sessionId && scroll.isAttachedToWindow) { state.viewport = viewContext.capture(); state.changed() }
            running?.removeObserver(runningObserver)
            progress?.removeObserver(progressObserver)
            owner.lifecycle.removeObserver(observer)
            if (active[fragment]?.dialog === dialog) active.remove(fragment)
        }
        active[fragment] = Active(sessionId, dialog) { update ->
            val position = viewContext.capture()
            outcome = update.outcome; onApply = update.onApply; onClose = update.onClose
            onRefine = update.onRefine; retryKeys = update.retryKeys; canApply = update.canApply
            liveSpecs = update.liveSpecs; specs = liveSpecs().associateBy { it.key }
            requestSpecs.putAll(update.targets.associateBy { it.key })
            originals.putAll(outcome.suggestions.associateBy { it.fieldKey })
            noticeView.text = update.notices
            originals.forEach { (key, value) -> addRow(key, value) }
            renders.values.forEach { it() }; updateCost(); renderRunning()
            viewContext.restore(position, focus = false)
        }
        dialog.show()
        viewContext.restore(state.viewport)
        renderRunning()
        running?.observe(owner,runningObserver)
        progress?.observe(owner,progressObserver)
        return dialog
    }
}
