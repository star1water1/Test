package com.novelcharacter.app.ui.character

import android.app.Dialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.util.GsonTypes
import java.io.File

data class DuplicateCandidate(
    val character: Character,
    val novelTitle: String?
)

class DuplicateCharacterDialog : DialogFragment() {

    enum class Resolution {
        CANCEL,
        UPDATE_EXISTING,
        CREATE_NEW,
        SAVE_ANYWAY
    }

    data class Result(
        val resolution: Resolution,
        val selectedCharacter: Character? = null
    )

    var onResolved: ((Result) -> Unit)? = null

    private var candidates: List<DuplicateCandidate> = emptyList()
    private var isEditMode: Boolean = false
    private var selectedPosition: Int = -1

    companion object {
        private const val ARG_IS_EDIT_MODE = "is_edit_mode"
        private const val ARG_CANDIDATES_JSON = "candidates_json"
        private const val STATE_SELECTED_POSITION = "selected_position"

        private val CANDIDATE_LIST_TYPE = object : TypeToken<List<DuplicateCandidate>>() {}.type

        fun newInstance(candidates: List<DuplicateCandidate>, isEditMode: Boolean): DuplicateCharacterDialog {
            return DuplicateCharacterDialog().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_EDIT_MODE, isEditMode)
                    putString(ARG_CANDIDATES_JSON, Gson().toJson(candidates))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // arguments에서 candidates 복원 (회전 시에도 안전)
        arguments?.getString(ARG_CANDIDATES_JSON)?.let { json ->
            candidates = try {
                Gson().fromJson(json, CANDIDATE_LIST_TYPE) ?: emptyList()
            } catch (e: Exception) { emptyList() }
        }
        isEditMode = arguments?.getBoolean(ARG_IS_EDIT_MODE, false) ?: false
        // savedInstanceState에서 선택 상태 복원
        savedInstanceState?.let {
            selectedPosition = it.getInt(STATE_SELECTED_POSITION, -1)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_POSITION, selectedPosition)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_duplicate_character, null)

        val tvMessage = view.findViewById<TextView>(R.id.tvMessage)
        val rvCandidates = view.findViewById<RecyclerView>(R.id.rvCandidates)
        val tvSelectHint = view.findViewById<TextView>(R.id.tvSelectHint)

        val name = candidates.firstOrNull()?.character?.name ?: ""
        tvMessage.text = getString(R.string.duplicate_character_message, name, candidates.size)

        val adapter = CandidateAdapter(candidates) { position ->
            selectedPosition = position
            updateButtonState()
        }

        rvCandidates.layoutManager = LinearLayoutManager(requireContext())
        rvCandidates.adapter = adapter

        // 후보가 1개이거나 이전 선택이 있으면 자동 선택
        val initialSelection = when {
            selectedPosition >= 0 && selectedPosition < candidates.size -> selectedPosition
            candidates.size == 1 -> 0
            else -> -1
        }
        if (initialSelection >= 0) {
            selectedPosition = initialSelection
            adapter.setSelected(initialSelection)
        }

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.duplicate_character_title)
            .setView(view)
            .setNegativeButton(R.string.duplicate_action_cancel) { _, _ ->
                onResolved?.invoke(Result(Resolution.CANCEL))
            }

        if (isEditMode) {
            // 수정 모드: 취소 / 그대로 저장(동명이인)
            builder.setPositiveButton(R.string.duplicate_action_save_anyway) { _, _ ->
                onResolved?.invoke(Result(Resolution.SAVE_ANYWAY))
            }
            tvSelectHint.visibility = View.GONE
        } else {
            // 신규 모드: 취소 / 기존 캐릭터 업데이트 / 동명이인으로 새로 생성
            tvSelectHint.visibility = View.VISIBLE
            builder.setPositiveButton(R.string.duplicate_action_update) { _, _ ->
                val selected = candidates.getOrNull(selectedPosition)
                onResolved?.invoke(Result(Resolution.UPDATE_EXISTING, selected?.character))
            }
            builder.setNeutralButton(R.string.duplicate_action_create_new) { _, _ ->
                onResolved?.invoke(Result(Resolution.CREATE_NEW))
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            updateButtonState()
        }
        return dialog
    }

    private fun updateButtonState() {
        val alertDialog = dialog as? AlertDialog ?: return
        if (!isEditMode) {
            // "기존 캐릭터 업데이트" 버튼은 선택 시에만 활성화
            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selectedPosition >= 0
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        onResolved?.invoke(Result(Resolution.CANCEL))
    }

    private class CandidateAdapter(
        private val candidates: List<DuplicateCandidate>,
        private val onSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<CandidateAdapter.VH>() {

        private var selectedPos = -1

        fun setSelected(pos: Int) {
            val old = selectedPos
            selectedPos = pos
            if (old >= 0) notifyItemChanged(old)
            notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_duplicate_candidate, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val candidate = candidates[position]
            val character = candidate.character

            holder.tvName.text = character.name
            holder.tvNovel.text = candidate.novelTitle
                ?: holder.itemView.context.getString(R.string.duplicate_novel_none)

            val memo = character.memo
            if (memo.isNullOrBlank()) {
                holder.tvMemo.visibility = View.GONE
            } else {
                holder.tvMemo.visibility = View.VISIBLE
                holder.tvMemo.text = if (memo.length > 50) memo.substring(0, 50) + "…" else memo
            }

            // Load first image thumbnail
            val imagePaths = try {
                Gson().fromJson<List<String>>(character.imagePaths, GsonTypes.STRING_LIST) ?: emptyList()
            } catch (e: Exception) { emptyList() }

            if (imagePaths.isNotEmpty() && File(imagePaths[0]).exists()) {
                try {
                    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val bitmap = BitmapFactory.decodeFile(imagePaths[0], options)
                    holder.ivThumbnail.setImageBitmap(bitmap)
                    holder.ivThumbnail.visibility = View.VISIBLE
                } catch (e: Exception) {
                    holder.ivThumbnail.visibility = View.GONE
                }
            } else {
                holder.ivThumbnail.visibility = View.GONE
            }

            holder.radioSelect.isChecked = position == selectedPos

            holder.itemView.setOnClickListener {
                val adapterPos = holder.adapterPosition
                if (adapterPos != RecyclerView.NO_POSITION) {
                    val old = selectedPos
                    selectedPos = adapterPos
                    if (old >= 0) notifyItemChanged(old)
                    notifyItemChanged(adapterPos)
                    onSelected(adapterPos)
                }
            }
        }

        override fun getItemCount() = candidates.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val radioSelect: RadioButton = view.findViewById(R.id.radioSelect)
            val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvNovel: TextView = view.findViewById(R.id.tvNovel)
            val tvMemo: TextView = view.findViewById(R.id.tvMemo)
        }
    }
}
