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
    private val onLongClick: (FactionMemberItem) -> Unit
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

            binding.root.setOnLongClickListener {
                onLongClick(item)
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
