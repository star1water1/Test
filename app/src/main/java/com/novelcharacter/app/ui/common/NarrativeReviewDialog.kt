package com.novelcharacter.app.ui.common

import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
import com.novelcharacter.app.ai.NarrativeFieldAiWriter
import com.novelcharacter.app.ai.NarrativeReviewState
import com.novelcharacter.app.util.cappedScrollView

object NarrativeReviewDialog {
    data class Item(val id: Long, val name: String, val original: String,
        val drafts: List<NarrativeFieldAiWriter.Draft>, val continueWriting: Boolean = false,
        val imageCount: Int = 0)
    data class PendingItem(val id: Long, val name: String, val imageCount: Int)
    private val active = java.util.WeakHashMap<Fragment, AlertDialog>()

    fun show(fragment: Fragment, items: List<Item>, state: NarrativeReviewState, notices: String,
        onApply: (Map<Long, String>) -> Boolean, onClose: () -> Unit,
        onRefine: (Long, Int, String) -> Boolean,
        running: androidx.lifecycle.LiveData<Boolean>? = null,
        pending: List<PendingItem> = emptyList(),
        onResume: (Set<Long>) -> Boolean = { false }) {
        active.remove(fragment)?.dismiss()
        val context = fragment.requireContext()
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        fun label(value: String) = TextView(context).apply {
            text=value; textSize=15f; setTextIsSelectable(true)
            setPadding(0,pad/2,0,pad/2)
        }
        fun button(title: String, action: () -> Unit) = MaterialButton(context, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text=title; setOnClickListener { action() }
        }
        panel.addView(label(notices))
        panel.addView(label("받은 결과와 수정·선택 내용은 이 기기에 보관합니다. 편집 화면을 다시 연 뒤 같은 AI 버튼을 누르면 검토를 이어갈 수 있습니다. 앱 삭제·데이터 삭제 시에는 지워집니다."))
        state.seedDefaults(items.filter { it.drafts.isNotEmpty() }.map { it.id })
        lateinit var dialog: AlertDialog
        if (pending.isNotEmpty()) {
            panel.addView(label("아직 요청하지 않은 항목 · 필요한 것만 선택해 이어 요청하세요."))
            val resume=button("") {
                val ids=pending.filter { it.id in state.resumeSelection }.map { it.id }.toSet()
                if (ids.isEmpty()) Toast.makeText(context,R.string.ai_review_pick_none,Toast.LENGTH_SHORT).show()
                else if (onResume(ids)) dialog.dismiss()
            }
            fun refreshCost() {
                val selected=pending.filter { it.id in state.resumeSelection }
                resume.text="선택 ${selected.size}개 이어 요청 · 요청 ${selected.size}건"
                val imageCount=selected.sumOf { it.imageCount }
                if (imageCount>0) resume.append(" · 이미지 총 ${imageCount}장 전송")
                resume.isEnabled=selected.isNotEmpty()
            }
            pending.forEach { item -> panel.addView(CheckBox(context).apply {
                text=item.name; isChecked=item.id in state.resumeSelection
                setOnCheckedChangeListener { _,on ->
                    if (on) state.resumeSelection.add(item.id) else state.resumeSelection.remove(item.id)
                    state.changed(); refreshCost()
                }
            }) }
            if (pending.any { it.imageCount>0 }) panel.addView(label(
                "첫 요청의 이미지를 다시 사용합니다. 이미지마다 약 ${com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MIN}~${com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MAX} 토큰이 추가됩니다."))
            refreshCost(); panel.addView(resume)
        }
        for (item in items) {
            panel.addView(CheckBox(context).apply {
                text=item.name
                isEnabled=item.drafts.isNotEmpty()
                isChecked=state.isChecked(item.id)
                setOnCheckedChangeListener { _, on -> state.setChecked(item.id,on) }
            })
            val content = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
            content.isVisible=items.size==1 || item.id in state.expanded
            if (items.size > 1) panel.addView(button("전체 내용 · 후보 비교 · 수정") {
                content.isVisible=!content.isVisible
                if(content.isVisible) state.expanded.add(item.id) else state.expanded.remove(item.id)
                state.changed()
            })
            if (item.original.isNotBlank()) content.addView(label("기존 원문\n${item.original}"))
            if (item.continueWriting) content.addView(label("선택한 글은 현재 입력된 원문 뒤에 이어 붙입니다."))
            val candidates=RadioGroup(context).apply { orientation=RadioGroup.VERTICAL }
            item.drafts.forEachIndexed { index, draft ->
                val key=state.key(item.id,index)
                candidates.addView(RadioButton(context).apply {
                    id=android.view.View.generateViewId()
                    text="후보 ${index+1} 선택"
                    tag=index
                    isChecked=state.chosen(item.id)==index
                })
                val text=label(state.current(item.id,index,draft.text))
                val editor=EditText(context).apply {
                    setText(state.current(item.id,index,draft.text)); setSingleLine(false); minLines=4
                    isVisible=key in state.editing
                    doAfterTextChanged {
                        if(isVisible) { state.edits[key]=it.toString(); text.text=it.toString(); state.changed() }
                    }
                }
                // Each radio button stays immediately above its full draft and editor.
                candidates.addView(label("후보 ${index+1}"))
                candidates.addView(text); candidates.addView(editor)
                candidates.addView(button("후보 ${index+1} 직접 수정 · AI 호출 없음") {
                    editor.isVisible=!editor.isVisible
                    if(editor.isVisible) { state.editing.add(key); editor.requestFocus() }
                    else state.editing.remove(key)
                    state.changed()
                })
                candidates.addView(button("후보 ${index+1} 원문으로 되돌리기") {
                    state.edits.remove(key); editor.setText(draft.text); text.text=draft.text; state.changed()
                })
            }
            candidates.setOnCheckedChangeListener { group, checkedId ->
                (group.findViewById<RadioButton>(checkedId)?.tag as? Int)?.let { state.choose(item.id,it) }
            }
            content.addView(candidates)
            if(item.drafts.isEmpty()) content.addView(label(context.getString(R.string.ai_field_nothing)))
            content.addView(NaturalLanguageInput.create(fragment,"narrative-refine:${state.sessionId}:${item.id}",
                "선택한 후보를 어떻게 보완할지 알려 주세요",state.instructions[item.id].orEmpty(),
                onChanged={state.instructions[item.id]=it; state.changed()}))
            if(item.imageCount>0) content.addView(label("AI 보완·재요청에는 첫 요청의 이미지 ${item.imageCount}장이 다시 전송됩니다. 이미지마다 약 ${com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MIN}~${com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TOKENS_MAX} 토큰이 추가됩니다."))
            content.addView(button(if(item.drafts.isEmpty()) "이 항목 다시 요청 · 요청 1건" else "AI 보완 · 요청 1건 · 이전 후보 유지") {
                if(onRefine(item.id,state.chosen(item.id),state.instructions[item.id].orEmpty())) dialog.dismiss()
            })
            panel.addView(content)
        }
        dialog=MaterialAlertDialogBuilder(context).setTitle("서술형 결과 검토")
            .setView(cappedScrollView(context).apply { addView(panel) })
            .setPositiveButton("선택 적용",null)
            .setNegativeButton("닫기 · 내용 보관",null)
            .setNeutralButton("검토 내용 버리기") { _,_->onClose() }.create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected=items.filter { state.isChecked(it.id) && it.drafts.isNotEmpty() }.associate { item ->
                    val index=state.chosen(item.id).coerceIn(item.drafts.indices)
                    item.id to state.current(item.id,index,item.drafts[index].text)
                }
                when {
                    selected.isEmpty()->Toast.makeText(context,R.string.ai_review_pick_none,Toast.LENGTH_SHORT).show()
                    selected.values.any { it.isBlank() }->Toast.makeText(context,"빈 글은 적용할 수 없습니다. 내용을 입력하세요.",Toast.LENGTH_LONG).show()
                    onApply(selected)->dialog.dismiss()
                }
            }
        }
        val owner=fragment.viewLifecycleOwnerLiveData.value ?: fragment
        val runningObserver=androidx.lifecycle.Observer<Boolean> { busy ->
            if(active[fragment]===dialog) {if(busy==true) dialog.hide() else dialog.show()}
        }
        val observer=object: DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() } }
        owner.lifecycle.addObserver(observer)
        dialog.setOnDismissListener {
            running?.removeObserver(runningObserver)
            owner.lifecycle.removeObserver(observer)
            if(active[fragment]===dialog) active.remove(fragment)
        }
        active[fragment]=dialog
        dialog.show()
        running?.observe(owner,runningObserver)
    }
}
