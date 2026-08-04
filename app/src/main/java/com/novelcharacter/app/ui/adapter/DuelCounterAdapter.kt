package com.novelcharacter.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.databinding.ItemDuelCounterBinding
import com.novelcharacter.app.databinding.ItemDuelCounterHeaderBinding
import com.novelcharacter.app.util.DuelStandings

/**
 * 상성 상세의 목록 (B-104 화면 계층).
 *
 * **천적과 순환을 갈라 보인다** — 둘은 사용자가 할 일이 다르다(천적은 *"맞다/아니다"*,
 * 순환은 층 B의 세 갈래 처분). 그리고 **후보와 이미 처분한 것을 갈라 보인다** — 처분한 것은
 * 물릴 수 있어야 하고, ③으로 확정한 짝은 점수 적합에서 빠져 후보 목록에 다시 나타나지
 * 않으므로 여기 없으면 되돌릴 길이 없다.
 */
class DuelCounterAdapter(
    private val nameOf: (String) -> String,
    private val onMistake: (DuelStandings.CounterItem) -> Unit,
    private val onUndecided: (DuelStandings.CounterItem) -> Unit,
    private val onCounter: (DuelStandings.CounterItem) -> Unit,
    private val onClear: (DuelStandings.CounterItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val titleRes: Int, val hintRes: Int) : Row()
        data class Entry(val item: DuelStandings.CounterItem) : Row()
        data class Note(val textRes: Int) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submit(values: List<Row>) {
        rows = values
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Entry -> TYPE_ENTRY
        else -> TYPE_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ENTRY) {
            EntryViewHolder(ItemDuelCounterBinding.inflate(inflater, parent, false))
        } else {
            HeaderViewHolder(ItemDuelCounterHeaderBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row)
            is Row.Note -> (holder as HeaderViewHolder).bindNote(row)
            is Row.Entry -> (holder as EntryViewHolder).bind(row.item)
        }
    }

    inner class HeaderViewHolder(
        private val binding: ItemDuelCounterHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row.Header) {
            binding.headerTitle.visibility = View.VISIBLE
            binding.headerTitle.setText(row.titleRes)
            binding.headerHint.setText(row.hintRes)
        }

        fun bindNote(row: Row.Note) {
            binding.headerTitle.visibility = View.GONE
            binding.headerHint.setText(row.textRes)
        }
    }

    inner class EntryViewHolder(
        private val binding: ItemDuelCounterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DuelStandings.CounterItem) {
            val context = binding.root.context
            binding.kindText.setText(
                if (item.isCycle) R.string.duel_kind_cycle else R.string.duel_kind_direct
            )
            binding.relationText.text = relationLabel(item)
            binding.evidenceText.text = evidenceLabel(item)

            val decided = item.verdictKind != null
            binding.verdictButtons.visibility = if (decided) View.GONE else View.VISIBLE
            binding.decidedRow.visibility = if (decided) View.VISIBLE else View.GONE

            if (decided) {
                val kindLabel = context.getString(
                    if (item.verdictKind == DuelCounterVerdict.KIND_COUNTER) {
                        R.string.duel_verdict_counter_done
                    } else {
                        R.string.duel_verdict_undecided_done
                    }
                )
                // ②는 점수에서 빼지 않으므로 어긋남이 그대로 남는다 — 그것이 *"나중에 다시
                // 묻는다"*(확정 문서 2-1 ②)의 신호이고, 여기서 그 사실을 말한다.
                binding.verdictLabel.text = if (item.stillDetected) {
                    kindLabel + " · " + context.getString(R.string.duel_verdict_still_detected)
                } else {
                    kindLabel
                }
                binding.btnClearVerdict.setOnClickListener { onClear(item) }
            } else {
                binding.btnMistake.setOnClickListener { onMistake(item) }
                binding.btnUndecided.setOnClickListener { onUndecided(item) }
                binding.btnCounter.setOnClickListener { onCounter(item) }
            }
        }

        private fun relationLabel(item: DuelStandings.CounterItem): String {
            val names = item.memberCodes.map(nameOf)
            return if (item.isCycle) {
                // 순환은 이기는 차례대로 담기고 마지막이 처음으로 돌아온다.
                (names + names.first()).joinToString(" → ")
            } else {
                // 천적은 [센 쪽, 잡는 쪽]. 방향을 뒤집으면 뜻이 정반대가 된다.
                binding.root.context.getString(
                    R.string.duel_relation_direct,
                    names.getOrElse(0) { "" },
                    names.getOrElse(1) { "" }
                )
            }
        }

        private fun evidenceLabel(item: DuelStandings.CounterItem): String {
            val context = binding.root.context
            if (item.samples <= 0) return context.getString(R.string.duel_counter_no_current_evidence)
            return if (item.isCycle) {
                context.getString(R.string.duel_counter_cycle_evidence, item.samples)
            } else {
                context.getString(
                    R.string.duel_counter_direct_evidence,
                    item.samples,
                    String.format(java.util.Locale.US, "%.1f", item.deviation)
                )
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1

        /** 화면이 목록을 짤 때 쓰는 머리말·안내 묶음 — 문구 선택이 한 자리에 모인다. */
        fun rowsOf(review: DuelStandings.CounterReview): List<Row> {
            val rows = ArrayList<Row>()
            rows.add(Row.Header(R.string.duel_counter_candidates, R.string.duel_counter_candidates_hint))
            if (review.candidates.isEmpty()) {
                rows.add(Row.Note(R.string.duel_counter_candidates_empty))
            } else {
                review.candidates.forEach { rows.add(Row.Entry(it)) }
            }
            rows.add(Row.Header(R.string.duel_counter_decided, R.string.duel_counter_decided_hint))
            if (review.decided.isEmpty()) {
                rows.add(Row.Note(R.string.duel_counter_decided_empty))
            } else {
                review.decided.forEach { rows.add(Row.Entry(it)) }
            }
            return rows
        }
    }
}
