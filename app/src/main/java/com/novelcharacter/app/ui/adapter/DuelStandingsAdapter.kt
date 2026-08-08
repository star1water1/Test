package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.ItemDuelStandingBinding
import com.novelcharacter.app.util.DuelStandings

/**
 * 순위표의 줄들 (B-104 화면 계층).
 *
 * **점수만 보이면 안 된다.** P-10이 확정한 것은 배지까지이고, 조사가 거기 둘을 더했다 —
 * ① 행마다 근거(`±오차`와 판 수)를 함께 보인다. 순위표를 볼 때 사용자의 실제 물음은
 * *"이 줄을 믿어도 되나"*인데 점수 하나로는 답이 안 된다. ② 한 판도 안 한 참가자는 그
 * 사실을 말한다 — 그 1500은 **계산된 값이 아니라 앵커 그대로**라 다른 1500과 뜻이 다르다.
 */
class DuelStandingsAdapter(
    private val nameOf: (String) -> String
) : ListAdapter<DuelStandings.Row, DuelStandingsAdapter.RowViewHolder>(DiffCallback()) {

    /**
     * 후보 필터가 걸린 축의 후보 코드 (B-168). null이면 필터가 없는 것이고 표식이 안 뜬다.
     *
     * 전적 있는 비후보는 표에서 빼지 않는다 — 빼면 그 판이 고아가 되어 남의 점수까지 움직인다.
     * 대신 이 표식이 *"이 줄은 더 이상 새 짝에 들어가지 않는다"*를 말한다(조용히 두면
     * 사용자는 왜 이 캐릭터의 짝이 더 안 나오는지 알 길이 없다 — 개발 의도 2번).
     */
    var candidateCodes: Set<String>? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder =
        RowViewHolder(ItemDuelStandingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) = holder.bind(getItem(position))

    inner class RowViewHolder(
        private val binding: ItemDuelStandingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: DuelStandings.Row) {
            val context = binding.root.context
            binding.rankText.text = row.rank.toString()
            binding.nameText.text = nameOf(row.code)
            binding.scoreText.text = context.getString(R.string.duel_score_value, row.score, row.scoreError)

            val evidence = if (row.provisional) {
                context.getString(R.string.duel_row_provisional)
            } else {
                context.getString(R.string.duel_row_evidence, row.played, winsLabel(row.wins))
            }
            val candidates = candidateCodes
            binding.evidenceText.text = if (candidates != null && row.code !in candidates) {
                evidence + " · " + context.getString(R.string.duel_row_not_candidate)
            } else {
                evidence
            }

            if (row.counters > 0) {
                binding.counterBadge.visibility = View.VISIBLE
                binding.counterBadge.text = context.getString(R.string.duel_row_counters, row.counters)
            } else {
                binding.counterBadge.visibility = View.GONE
            }
        }
    }

    /**
     * 승수는 무승부가 0.5로 들어오므로 정수로 잘라 쓰지 않는다 — 자르면 12.5승이 12승으로
     * 조용히 줄어든다. 화면에 무승부 버튼이 아직 없어도 **저장 형식은 이미 받고 있고**
     * 엑셀로 들어올 수도 있다.
     */
    private fun winsLabel(wins: Double): String =
        if (wins % 1.0 == 0.0) {
            wins.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", wins)
        }

    class DiffCallback : DiffUtil.ItemCallback<DuelStandings.Row>() {
        override fun areItemsTheSame(oldItem: DuelStandings.Row, newItem: DuelStandings.Row) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: DuelStandings.Row, newItem: DuelStandings.Row) =
            oldItem == newItem
    }
}
