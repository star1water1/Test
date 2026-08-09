package com.novelcharacter.app.ui.namebank

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.databinding.BottomSheetNameBankPickerBinding
import com.novelcharacter.app.util.NameBankMatch
import com.novelcharacter.app.util.NameBankPickOrder

/**
 * 편집 폼의 이름 줄에서 여는 **이름은행 선택 시트** (B-124 ⓐ).
 *
 * 두 방향이 한 창에 있다 — 창고에서 **꺼내기**(목록에서 고르기)와 창고에 **넣기**
 * (하단 [지금 이름을 은행에 저장]). 설계 8-2 ⓐ가 둘을 한 자리에 둔 이유는 사용자가
 * *"이 이름 어디서 관리하지"*를 한 번만 묻게 하기 위해서다(원칙 04).
 *
 * **고른 것은 즉시 쓰지 않는다.** 이름 칸만 채우고 사용 표시는 **저장 시점**에 걸린다
 * (편집 취소 계약 — `CharacterSaveCoordinator.Host.requestedNameBankEntryId`). 즉시 쓰면
 * 편집을 취소한 사용자가 은행에 흔적을 남기게 된다.
 *
 * 정렬·검색 규칙은 [NameBankPickOrder]가 단일 소스다(화면에서 즉석으로 적으면 다음 화면이
 * 순서를 뒤집는다). 데이터·콜백은 호스트가 프로퍼티로 주입한다([BulkRegisterBottomSheet] 방식).
 */
class NameBankPickerSheet : BottomSheetDialogFragment() {

    data class Setup(
        val entries: List<NameBankEntry>,
        /** 사용 중 엔트리가 가리키는 캐릭터 이름 (id → 이름). 없으면 "사용 중"만 적는다. */
        val usedByNames: Map<Long, String>,
        /** 편집 중인 캐릭터의 성별 값 — GENDER 역할 필드에서 읽는다. 비면 성별 축은 무효다. */
        val preferredGender: String?,
        /** 폼의 현재 이름 칸 값 — 역방향 담기의 재료이자 "이미 은행에 있는가"의 판정 대상. */
        val currentName: String
    )

    var setup: Setup? = null

    /** 목록에서 고름 — 호스트가 이름 칸을 채우고 저장까지 id를 들고 있는다. */
    var onPick: ((NameBankEntry) -> Unit)? = null

    /** 하단 [지금 이름을 은행에 저장] — 호스트가 은행에 넣는다(즉시 쓰기). */
    var onSaveCurrent: ((String) -> Unit)? = null

    private var _binding: BottomSheetNameBankPickerBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: PickAdapter
    private var query: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNameBankPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val setup = this.setup
        if (setup == null || onPick == null) {
            // 프로세스 재생성으로 주입이 소실됨 — 조용한 닫기(주입식 시트 공통 계약).
            // 여기서 그래도 되는 이유는 **다시 열면 그만**이기 때문이다(R-38이 가른 유료 응답 부류가 아니다).
            dismissAllowingStateLoss()
            return
        }
        query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        if (query.isNotEmpty()) binding.searchEdit.setText(query)

        binding.genderHint.visibility =
            if (!setup.preferredGender.isNullOrBlank()) View.VISIBLE else View.GONE

        adapter = PickAdapter(setup.usedByNames) { entry ->
            onPick?.invoke(entry)
            // 사용 중인 것을 골라도 이름은 넣는다(사용자가 그 이름을 쓰겠다고 한 것이다).
            // 다만 사용 표시가 옮겨 가지 않는다는 사실은 그 자리에서 말한다 — 저장 뒤에야
            // 알게 되면 그때는 무엇을 골랐는지 화면에 남아 있지 않다.
            if (entry.isUsed) {
                Toast.makeText(requireContext(), R.string.name_bank_pick_used_blocked, Toast.LENGTH_LONG).show()
            }
            dismiss()
        }
        binding.pickRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.pickRecyclerView.adapter = adapter

        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s.toString()
                render()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnSaveCurrent.setOnClickListener {
            val name = setup.currentName.trim()
            when {
                name.isEmpty() ->
                    Toast.makeText(requireContext(), R.string.name_bank_save_current_empty, Toast.LENGTH_SHORT).show()
                setup.entries.any { NameBankMatch.sameName(it.name, name) } ->
                    // 이미 있는 이름을 또 넣으면 창고에 쌍둥이가 생기고 자동 대조가 둘 중 하나를
                    // 고르게 된다 — 넣지 않고 그 사실을 말한다(변수 제어).
                    Toast.makeText(requireContext(), R.string.name_bank_save_current_exists, Toast.LENGTH_SHORT).show()
                else -> {
                    onSaveCurrent?.invoke(name)
                    dismiss()
                }
            }
        }

        render()
    }

    private fun render() {
        val setup = this.setup ?: return
        val ordered = NameBankPickOrder.order(setup.entries, query, setup.preferredGender)
        adapter.submitList(ordered)
        binding.pickRecyclerView.visibility = if (ordered.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (ordered.isEmpty()) View.VISIBLE else View.GONE
        if (ordered.isEmpty()) {
            // 비어 있는 이유를 가른다 — *창고가 빈 것*과 *검색이 안 맞는 것*은 할 일이 다르다.
            binding.emptyText.setText(
                if (setup.entries.isEmpty()) R.string.name_bank_pick_empty
                else R.string.name_bank_pick_empty_search
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_QUERY, query)
    }

    override fun onDestroyView() {
        _binding?.pickRecyclerView?.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private class PickAdapter(
        private val usedByNames: Map<Long, String>,
        private val onClick: (NameBankEntry) -> Unit
    ) : ListAdapter<NameBankEntry, PickAdapter.VH>(Diff()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_name_bank_pick, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameText: TextView = itemView.findViewById(R.id.nameText)
            private val genderText: TextView = itemView.findViewById(R.id.genderText)
            private val usedIndicator: TextView = itemView.findViewById(R.id.usedIndicator)
            private val subText: TextView = itemView.findViewById(R.id.subText)

            fun bind(entry: NameBankEntry) {
                val ctx = itemView.context
                nameText.text = entry.name

                genderText.text = entry.gender
                genderText.visibility = if (entry.gender.isNotBlank()) View.VISIBLE else View.GONE

                if (entry.isUsed) {
                    val who = entry.usedByCharacterId?.let { usedByNames[it] }
                    usedIndicator.text = if (who != null) {
                        ctx.getString(R.string.name_bank_pick_used_by, who)
                    } else {
                        ctx.getString(R.string.name_bank_pick_used_unknown)
                    }
                    usedIndicator.visibility = View.VISIBLE
                    itemView.alpha = 0.6f
                } else {
                    usedIndicator.visibility = View.GONE
                    itemView.alpha = 1.0f
                }

                val sub = listOf(entry.origin, entry.notes).filter { it.isNotBlank() }.joinToString(" · ")
                subText.text = sub
                subText.visibility = if (sub.isNotBlank()) View.VISIBLE else View.GONE

                itemView.setOnClickListener { onClick(entry) }
            }
        }

        private class Diff : DiffUtil.ItemCallback<NameBankEntry>() {
            override fun areItemsTheSame(oldItem: NameBankEntry, newItem: NameBankEntry) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: NameBankEntry, newItem: NameBankEntry) = oldItem == newItem
        }
    }

    companion object {
        const val TAG = "NameBankPickerSheet"
        private const val STATE_QUERY = "query"
    }
}
