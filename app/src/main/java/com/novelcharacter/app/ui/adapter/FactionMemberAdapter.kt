package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.databinding.ItemFactionMemberBinding

data class FactionMemberItem(
    val membership: FactionMembership,
    val characterName: String
)

class FactionMemberAdapter(
    /**
     * 짧게 눌러도, 꾹 눌러도 **같은 자리**로 간다.
     *
     * 종전에는 꾹 누르기만 있었다 — 그런데 이 행에는 짧은 터치로 할 다른 일이 **없다.**
     * 경쟁하는 동작이 없는데 숨은 제스처를 요구하면, 눌러 봐도 반응이 없어 기능이 있는 줄
     * 모르게 된다(원칙 04: 접근과 조작은 최대한 짧아야 한다).
     * 꾹 누르기는 종전 습관을 위해 그대로 둔다.
     */
    private val onSelect: (FactionMemberItem) -> Unit
) : ListAdapter<FactionMemberItem, FactionMemberAdapter.MemberViewHolder>(MemberDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val binding = ItemFactionMemberBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MemberViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MemberViewHolder(
        private val binding: ItemFactionMemberBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FactionMemberItem) {
            binding.memberName.text = item.characterName

            val m = item.membership
            when (m.leaveType) {
                FactionMembership.LEAVE_DEPARTED -> {
                    binding.memberStatus.text = "탈퇴 (${m.leaveYear ?: "?"}년)"
                    binding.memberStatus.visibility = View.VISIBLE
                    binding.memberStatus.alpha = 0.7f
                }
                FactionMembership.LEAVE_REMOVED -> {
                    binding.memberStatus.text = "제거됨"
                    binding.memberStatus.visibility = View.VISIBLE
                    binding.memberStatus.alpha = 0.5f
                }
                else -> {
                    if (m.joinYear != null) {
                        binding.memberStatus.text = "${m.joinYear}년~"
                        binding.memberStatus.visibility = View.VISIBLE
                        binding.memberStatus.alpha = 1f
                    } else {
                        binding.memberStatus.visibility = View.GONE
                    }
                }
            }

            binding.root.setOnClickListener { onSelect(item) }
            binding.root.setOnLongClickListener {
                onSelect(item)
                true
            }
        }
    }

    class MemberDiffCallback : DiffUtil.ItemCallback<FactionMemberItem>() {
        override fun areItemsTheSame(oldItem: FactionMemberItem, newItem: FactionMemberItem) =
            oldItem.membership.id == newItem.membership.id
        override fun areContentsTheSame(oldItem: FactionMemberItem, newItem: FactionMemberItem) =
            oldItem == newItem
    }
}
