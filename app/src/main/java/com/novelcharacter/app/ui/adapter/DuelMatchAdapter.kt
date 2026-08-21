package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.ItemDuelMatchBinding
import com.novelcharacter.app.util.DuelFieldLinks
import com.novelcharacter.app.util.DuelMatchLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 대결 기록의 줄들 (B-104 — 인앱 편집).
 *
 * **줄 하나가 답해야 하는 것 셋:** 누가 이겼는가 · 언제인가 · **필드는 뭐라고 하는가.**
 * 세 번째가 층 C의 소비처이고, 그것이 있어야 이 목록이 *"고칠 자리를 짚어 주는 목록"*이 된다
 * (그냥 나열하면 수만 줄 가운데 무엇을 봐야 할지 알 수 없다 — 원칙 02가 말하는 '실질').
 */
class DuelMatchAdapter(
    private val onClick: (DuelMatchLog.Row) -> Unit
) : ListAdapter<DuelMatchLog.Row, DuelMatchAdapter.RowViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder =
        RowViewHolder(ItemDuelMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) = holder.bind(getItem(position))

    inner class RowViewHolder(
        private val binding: ItemDuelMatchBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: DuelMatchLog.Row) {
            val context = binding.root.context
            val aName = row.aName ?: context.getString(R.string.duel_unknown_participant)
            val bName = row.bName ?: context.getString(R.string.duel_unknown_participant)

            // 이긴 쪽에 표식을 붙인다 — 이름 둘만 늘어놓으면 어느 쪽이 이겼는지 매번 곱씹어야 한다.
            binding.matchTitle.text = when (row.outcome) {
                DuelMatchLog.Outcome.A_WON -> context.getString(R.string.duel_match_a_won, aName, bName)
                DuelMatchLog.Outcome.B_WON -> context.getString(R.string.duel_match_b_won, aName, bName)
                DuelMatchLog.Outcome.DRAW -> context.getString(R.string.duel_match_draw, aName, bName)
                DuelMatchLog.Outcome.BROKEN -> context.getString(R.string.duel_match_broken, aName, bName)
            }
            binding.matchMeta.text = TIME_FORMAT.format(Date(row.decidedAt))

            if (row.cells.isEmpty()) {
                binding.matchFields.visibility = View.GONE
            } else {
                binding.matchFields.visibility = View.VISIBLE
                binding.matchFields.text = row.cells.joinToString("\n") { cell ->
                    context.getString(
                        R.string.duel_match_field_line,
                        cell.rank,
                        cell.label,
                        cell.aValue.ifBlank { context.getString(R.string.duel_field_value_empty) },
                        cell.bValue.ifBlank { context.getString(R.string.duel_field_value_empty) }
                    )
                }
            }

            // **짚어 주는 자리.** 깨진 판이 어긋남보다 앞이다 — 그쪽은 고치지 않으면 점수에서
            // 통째로 빠지므로 더 급하다.
            val flag = when {
                row.outcome == DuelMatchLog.Outcome.BROKEN ->
                    context.getString(R.string.duel_match_flag_broken)
                row.disagrees -> {
                    val label = row.cells.firstOrNull { it.key == row.prediction.decidedBy }?.label
                        ?: row.prediction.decidedBy
                    val expected = if (row.prediction.side == DuelFieldLinks.Side.A) aName else bName
                    context.getString(R.string.duel_match_flag_disagree, label, expected)
                }
                else -> null
            }
            binding.matchFlag.visibility = if (flag == null) View.GONE else View.VISIBLE
            binding.matchFlag.text = flag.orEmpty()

            binding.root.setOnClickListener { onClick(row) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DuelMatchLog.Row>() {
        override fun areItemsTheSame(oldItem: DuelMatchLog.Row, newItem: DuelMatchLog.Row) =
            oldItem.code == newItem.code

        override fun areContentsTheSame(oldItem: DuelMatchLog.Row, newItem: DuelMatchLog.Row) =
            oldItem == newItem
    }

    companion object {
        // platform-parity-ok: 화면에 보이는 시각이다 — 사용자 로케일을 따르는 것이 옳다(R-22)
        private val TIME_FORMAT = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
    }
}
