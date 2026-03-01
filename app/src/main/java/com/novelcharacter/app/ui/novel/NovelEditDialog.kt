package com.novelcharacter.app.ui.novel

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.DialogNovelEditBinding

class NovelEditDialog : DialogFragment() {

    private var onSave: ((String, String) -> Unit)? = null
    private var initialTitle: String = ""
    private var initialDescription: String = ""

    fun setOnSaveListener(listener: (String, String) -> Unit) {
        onSave = listener
    }

    fun setInitialValues(title: String, description: String) {
        initialTitle = title
        initialDescription = description
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogNovelEditBinding.inflate(layoutInflater)
        binding.editTitle.setText(initialTitle)
        binding.editDescription.setText(initialDescription)

        return AlertDialog.Builder(requireContext())
            .setTitle(if (initialTitle.isEmpty()) R.string.add_novel else R.string.edit_novel)
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = binding.editTitle.text.toString().trim()
                val description = binding.editDescription.text.toString().trim()
                if (title.isNotEmpty()) {
                    onSave?.invoke(title, description)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }
}
