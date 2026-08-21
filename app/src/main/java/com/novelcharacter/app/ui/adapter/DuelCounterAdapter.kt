package com.novelcharacter.app.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.databinding.ItemDuelCategoryBinding
import com.novelcharacter.app.databinding.ItemDuelCounterBinding
import com.novelcharacter.app.databinding.ItemDuelCounterHeaderBinding
import com.novelcharacter.app.util.DuelCategoryStats
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

        /** 머리말에 **필드 이름**이 들어가는 자리 — 범주 집계는 필드마다 한 구역이다(B-112). */
        data class LabeledHeader(val title: String, val hint: String) : Row()

        /** 수를 담은 안내 — 값별 전적·자른 사실·못 센 판. */
        data class LabeledNote(val text: String) : Row()

        /** 값 둘이 맞붙은 칸. */
        data class Category(val matchup: DuelCategoryStats.Matchup) : Row()
    }

    private var rows: List<Row> = emptyList()

    fun submit(values: List<Row>) {
        rows = values
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Entry -> TYPE_ENTRY
        is Row.Category -> TYPE_CATEGORY
        else -> TYPE_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ENTRY -> EntryViewHolder(ItemDuelCounterBinding.inflate(inflater, parent, false))
            TYPE_CATEGORY -> CategoryViewHolder(ItemDuelCategoryBinding.inflate(inflater, parent, false))
            else -> HeaderViewHolder(ItemDuelCounterHeaderBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row)
            is Row.Note -> (holder as HeaderViewHolder).bindNote(row)
            is Row.Entry -> (holder as EntryViewHolder).bind(row.item)
            is Row.LabeledHeader -> (holder as HeaderViewHolder).bindLabeled(row)
            is Row.LabeledNote -> (holder as HeaderViewHolder).bindLabeledNote(row)
            is Row.Category -> (holder as CategoryViewHolder).bind(row.matchup)
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

        fun bindLabeled(row: Row.LabeledHeader) {
            binding.headerTitle.visibility = View.VISIBLE
            binding.headerTitle.text = row.title
            binding.headerHint.text = row.hint
        }

        fun bindLabeledNote(row: Row.LabeledNote) {
            binding.headerTitle.visibility = View.GONE
            binding.headerHint.text = row.text
        }
    }

    /**
     * 값 둘이 맞붙은 칸 (B-112).
     *
     * **기울지 않은 칸도 감추지 않는다** — *"예상과 비슷하다"*는 것 자체가 답이다
     * (그 자리는 값이 아니라 개체의 강함이 갈랐다는 뜻이다).
     */
    inner class CategoryViewHolder(
        private val binding: ItemDuelCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(matchup: DuelCategoryStats.Matchup) {
            val context = binding.root.context
            binding.pairText.text =
                context.getString(R.string.duel_category_pair, matchup.left, matchup.right)
            binding.recordText.text = context.getString(
                R.string.duel_category_record,
                matchup.matches,
                matchup.left,
                countText(matchup.leftWins),
                countText(matchup.rightWins)
            )
            val favored = matchup.favored()
            binding.leanText.text = if (favored == null) {
                context.getString(R.string.duel_category_even)
            } else {
                val expected =
                    if (favored == matchup.left) matchup.expectedLeftWins
                    else matchup.matches - matchup.expectedLeftWins
                context.getString(R.string.duel_category_lean, favored, countText(expected))
            }
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
        private const val TYPE_CATEGORY = 2

        /**
         * 승수를 사람이 읽는 글로. **무승부가 0.5로 들어오므로** 정수로 반올림하면
         * *"3승"*과 *"2.5승"*이 같은 글이 된다 — 반쪽이 있으면 그대로 보인다.
         */
        fun countText(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString()
            else String.format(java.util.Locale.US, "%.1f", value)

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

        /**
         * **범주 집계 구역** (B-112) — 필드마다 머리말 하나, 칸 여럿, 그리고 값별 전적과
         * **못 센 것의 고지**.
         *
         * 위 상성 목록 **뒤에** 붙는다: 저 위가 *"무엇이 어긋났는가"*이고 이것이 *"왜 그런가"*라,
         * 어긋남을 먼저 보고 이유를 읽는 것이 순서다.
         *
         * @param reports 필드 이름과 그 필드의 집계. 범주로 읽히지 않는 필드는 호출부가 이미 뺐다.
         */
        fun categoryRows(
            context: Context,
            reports: List<Pair<String, DuelCategoryStats.Report>>
        ): List<Row> {
            val rows = ArrayList<Row>()
            rows.add(
                Row.Header(R.string.duel_category_section, R.string.duel_category_section_hint)
            )
            if (reports.isEmpty()) {
                rows.add(Row.Note(R.string.duel_category_empty))
                return rows
            }
            for ((label, report) in reports) {
                rows.add(
                    Row.LabeledHeader(
                        label,
                        context.getString(R.string.duel_category_field_hint, label)
                    )
                )
                report.matchups.forEach { rows.add(Row.Category(it)) }
                if (report.matchups.isEmpty()) {
                    rows.add(Row.LabeledNote(context.getString(R.string.duel_category_no_cell)))
                }
                if (report.truncated) {
                    rows.add(
                        Row.LabeledNote(
                            context.getString(
                                R.string.duel_category_truncated,
                                report.matchups.size,
                                report.totalMatchups
                            )
                        )
                    )
                }
                if (report.values.isNotEmpty()) {
                    val summary = report.values.joinToString(" · ") {
                        context.getString(
                            R.string.duel_category_value_item,
                            it.value,
                            countText(it.wins),
                            countText(it.losses)
                        )
                    }
                    rows.add(
                        Row.LabeledNote(context.getString(R.string.duel_category_values, summary))
                    )
                }
                // **깨진 판도 말한다**(콜드 검토 2026.08.21). 앞선 커밋이 목록에 실을지의
                // 게이트를 `hasSomethingToSay`로 넓히면서 이 갈래를 함께 열었는데, 여기
                // 그리는 줄이 없어서 **깨진 판만 있는 필드는 머리말과 "아직 셋 이상 붙은 값
                // 짝이 없습니다" 한 줄만** 떴다 — 사용자는 판이 모자란 줄 알고 더 붙이지만
                // 깨진 판은 영원히 세어지지 않는다. 게이트만 넓히고 렌더러를 안 넓힌 자리다.
                // 문구는 순위표가 이미 쓰는 것과 같다(`duel_caveat_malformed`).
                if (report.malformedMatches > 0) {
                    rows.add(
                        Row.LabeledNote(
                            context.getString(R.string.duel_caveat_malformed, report.malformedMatches)
                        )
                    )
                }
                // 못 센 판은 **표에 드러나지 않는다** — 값을 안 적은 캐릭터가 많으면 이 표가
                // 전체를 대표하지 못하는데, 그 사실을 말하지 않으면 사용자는 모른다.
                if (report.skippedNoValue > 0 || report.skippedSameValue > 0) {
                    rows.add(
                        Row.LabeledNote(
                            context.getString(
                                R.string.duel_category_skipped,
                                report.skippedNoValue,
                                report.skippedSameValue
                            )
                        )
                    )
                }
            }
            return rows
        }
    }
}
