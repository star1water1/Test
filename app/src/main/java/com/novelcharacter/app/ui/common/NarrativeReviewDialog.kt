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
        val drafts: List<NarrativeFieldAiWriter.Draft>, val continueWriting: Boolean = false)
    private val active = java.util.WeakHashMap<Fragment, AlertDialog>()

    fun show(fragment: Fragment, items: List<Item>, state: NarrativeReviewState, notices: String,
        onApply: (Map<Long, String>) -> Boolean, onClose: () -> Unit,
        onRefine: (Long, Int, String) -> Boolean) {
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
        state.seedDefaults(items.filter { it.drafts.isNotEmpty() }.map { it.id })
        lateinit var dialog: AlertDialog
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
                        if(isVisible) { state.edits[key]=it.toString(); text.text=it.toString() }
                    }
                }
                // RadioGroup contains only radio buttons. Full drafts stay beside each choice in this panel.
                content.addView(label("후보 ${index+1}"))
                content.addView(text); content.addView(editor)
                content.addView(button("후보 ${index+1} 직접 수정 · AI 호출 없음") {
                    editor.isVisible=!editor.isVisible
                    if(editor.isVisible) { state.editing.add(key); editor.requestFocus() }
                    else state.editing.remove(key)
                })
                content.addView(button("후보 ${index+1} 원문으로 되돌리기") {
                    state.edits.remove(key); editor.setText(draft.text); text.text=draft.text
                })
            }
            candidates.setOnCheckedChangeListener { group, checkedId ->
                (group.findViewById<RadioButton>(checkedId)?.tag as? Int)?.let { state.choose(item.id,it) }
            }
            content.addView(candidates)
            if(item.drafts.isEmpty()) content.addView(label(context.getString(R.string.ai_field_nothing)))
            val instruction=EditText(context).apply {
                hint="선택한 후보를 어떻게 보완할지 알려 주세요"
                setText(state.instructions[item.id].orEmpty()); setSingleLine(false)
                doAfterTextChanged { state.instructions[item.id]=it.toString() }
            }
            content.addView(instruction)
            content.addView(button("AI 보완 · 요청 1건 · 이전 후보 유지") {
                if(onRefine(item.id,state.chosen(item.id),instruction.text.toString())) dialog.dismiss()
            }.apply { isEnabled=item.drafts.isNotEmpty() })
            panel.addView(content)
        }
        dialog=MaterialAlertDialogBuilder(context).setTitle("서술형 결과 검토")
            .setView(cappedScrollView(context).apply { addView(panel) })
            .setPositiveButton("선택 적용",null)
            .setNegativeButton("검토 마치기") { _,_->onClose() }.create()
        dialog.setOnCancelListener { onClose() }
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
        val observer=object: DefaultLifecycleObserver { override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() } }
        owner.lifecycle.addObserver(observer)
        dialog.setOnDismissListener {
            owner.lifecycle.removeObserver(observer)
            if(active[fragment]===dialog) active.remove(fragment)
        }
        active[fragment]=dialog
        dialog.show()
    }
}
