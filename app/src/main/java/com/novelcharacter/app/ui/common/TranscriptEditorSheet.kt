package com.novelcharacter.app.ui.common

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton

/** A full-height editing surface for the same journal-backed draft; closing never submits text. */
class TranscriptEditorSheet : DialogFragment() {
    private val model by lazy { ViewModelProvider(requireParentFragment())
        .get("voice:${requireArguments().getString("key")}", VoiceInputViewModel::class.java) }
    private var editor: EditText? = null
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        model.bind(requireArguments().getString("key")!!,requireArguments().getString("audioId"))
        val context = requireContext()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad,pad,pad,pad)
        }
        panel.addView(TextView(context).apply { text = "전사 편집 · 수정 내용은 보관됩니다. 입력에 추가는 이전 화면에서 실행하세요." })
        val field = EditText(context).apply {
            gravity = Gravity.TOP; setSingleLine(false); setText(model.session.draft)
            setSelection((savedInstanceState?.getInt("start") ?: 0).coerceIn(0,length()),
                (savedInstanceState?.getInt("end") ?: 0).coerceIn(0,length()))
            doAfterTextChanged { model.editDraft(it.toString()) }
        }
        field.post { field.scrollTo(0,savedInstanceState?.getInt("scroll") ?: 0) }
        editor = field
        // Full-window weighted editor: its own scroll is bounded by the resized keyboard viewport.
        panel.addView(field, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(MaterialButton(context).apply { text = "편집 마치고 검토로"; setOnClickListener { dismiss() } })
        model.updates.observe(this) {
            if (field.text.toString() != model.session.draft) {
                val cursor = field.selectionStart.coerceAtLeast(0)
                field.setText(model.session.draft); field.setSelection(cursor.coerceAtMost(field.length()))
            }
        }
        return Dialog(context, theme).apply { setContentView(panel) }
    }
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        editor?.let { outState.putInt("start",it.selectionStart); outState.putInt("end",it.selectionEnd); outState.putInt("scroll",it.scrollY) }
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        // The parent review receives this same draft when it is exposed again.
        editor?.requestFocus()
    }
    override fun onDestroyView() { model.updates.removeObservers(this); editor=null; super.onDestroyView() }
    override fun onDismiss(dialog: android.content.DialogInterface) { model.refresh(); super.onDismiss(dialog) }
    companion object {
        fun open(host: Fragment, key: String, audioId: String? = null) {
            val tag = "transcript-editor:$key"
            if (host.childFragmentManager.isStateSaved || host.childFragmentManager.findFragmentByTag(tag) != null) return
            TranscriptEditorSheet().apply { arguments=Bundle().apply { putString("key",key); putString("audioId",audioId) } }
                .show(host.childFragmentManager,tag)
        }
    }
}
