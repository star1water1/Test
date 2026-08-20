package com.novelcharacter.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.PromptTemplates
import com.novelcharacter.app.databinding.FragmentAiPromptTemplatesBinding
import com.novelcharacter.app.databinding.ItemAiPromptTemplateBinding

/**
 * **AI에 보내는 메시지 양식 편집** (사용자 요청 2026.08.20).
 *
 * *"ai api에 보내지는 메세지 양식도 모두 사용자가 편집할 수 있게 하기(인앱, 엑셀 모두)."*
 *
 * 열넷을 한 목록에 놓고 본문은 창에서 고친다([AiPromptTemplateEditDialog]) — 한 화면에 펼치면
 * 어느 양식을 고치고 있는지 흐려지고, 긴 글 열넷이 세로로 이어져 무엇이 있는지조차 안 보인다.
 *
 * ## 저장은 창이 닫힐 때 한 번
 *
 * 형제 자유 문구(`이미지 태그 기조`)는 포커스가 떠날 때 확정하지만 **여기는 창이라 다르다** —
 * 검증에 걸리는 글이 있고(자리표 오타·필수 자리 누락), 그것을 포커스 이동마다 판정하면
 * *고치는 도중*에 계속 거절당한다. 창은 '저장'을 누른 순간이 확정 시점이라 그 문제가 없고,
 * 실패하면 창이 닫히지 않아 쓴 글이 유실되지 않는다(R-27).
 */
class AiPromptTemplateFragment : Fragment() {

    private var _binding: FragmentAiPromptTemplatesBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: AiPromptSettings
    private lateinit var adapter: TemplateAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiPromptTemplatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AiPromptSettings(requireContext())
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        adapter = TemplateAdapter { id ->
            AiPromptTemplateEditDialog.newInstance(id).show(childFragmentManager, id.key)
        }
        binding.templateList.layoutManager = LinearLayoutManager(requireContext())
        binding.templateList.adapter = adapter

        // 저장을 **결과로** 받는다 — 회전 뒤에는 창에 꽂아 둔 콜백이 null이다(R-65).
        //
        // **`childFragmentManager` + `viewLifecycleOwner`다**(`FieldManageFragment`와 같은 꼴).
        // Fragment 확장판 `setFragmentResultListener`는 *부모* 매니저에 프래그먼트 수명으로
        // 다는데, 이 프래그먼트는 뷰가 죽어도 살아 있을 수 있어 그 사이 결과가 오면
        // `refresh()`가 `binding get() = _binding!!`에서 터진다. 창을 여는 매니저도 함께
        // 옮긴다 — 결과를 받는 매니저와 창이 결과를 넣는 매니저가 같아야 한다.
        childFragmentManager.setFragmentResultListener(
            AiPromptTemplateEditDialog.RESULT_KEY, viewLifecycleOwner
        ) { _, _ -> refresh() }
        refresh()
    }

    private fun refresh() {
        adapter.submit(
            PromptTemplates.Id.entries.map { id ->
                Row(id, settings.storedTemplate(id) != null, settings.templateOf(id))
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class Row(
        val id: PromptTemplates.Id,
        val customized: Boolean,
        val effective: String
    )

    private class TemplateAdapter(
        private val onClick: (PromptTemplates.Id) -> Unit
    ) : RecyclerView.Adapter<TemplateAdapter.Holder>() {

        private var rows: List<Row> = emptyList()

        fun submit(next: List<Row>) {
            rows = next
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemAiPromptTemplateBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemAiPromptTemplateBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val ctx = holder.itemView.context
            holder.binding.templateLabel.text = row.id.label
            holder.binding.templateState.text = ctx.getString(
                if (row.customized) R.string.ai_prompt_template_state_custom
                else R.string.ai_prompt_template_state_default
            )
            // 첫 두 줄만 보인다 — 무엇이 들어 있는지 알아보기에 그만큼이면 충분하고,
            // 더 보이면 목록이 아니라 본문이 된다.
            holder.binding.templatePreview.text =
                row.effective.lineSequence().filter { it.isNotBlank() }.take(2).joinToString(" ")
            holder.binding.templateCard.setOnClickListener { onClick(row.id) }
        }
    }
}
