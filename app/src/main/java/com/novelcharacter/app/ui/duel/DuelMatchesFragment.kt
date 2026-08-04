package com.novelcharacter.app.ui.duel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.databinding.FragmentDuelMatchesBinding
import com.novelcharacter.app.ui.adapter.DuelMatchAdapter
import com.novelcharacter.app.util.DuelMatchLog
import com.novelcharacter.app.util.notifyResult
import kotlinx.coroutines.launch

/**
 * **대결 기록** (B-104 — 사용자 요청: *"대결 기록이 남아서 그걸 … 인앱에서도 편집할 수 있게"*).
 *
 * 판은 처음부터 남아 있었지만 **볼 길이 없었다.** 되돌리기는 *방금 그거* 하나만 집고, 상성
 * 상세는 *그 관계를 만든 판들*만 집는다 — 어제 잘못 누른 판 하나를 찾아 고치는 길이 어디에도
 * 없었다(원칙 04: *"일일이 확인하지 않으면 존재를 알 수 없는 데이터가 있어서는 안 된다"*).
 *
 * ## 고칠 수 있는 것은 승자뿐이다
 * 참가자를 바꾸면 그 판은 *다른 판*이 되고, 시각을 바꾸면 *언제 정했는가*라는 사실이 거짓이
 * 된다. 둘 다 고치고 싶으면 **지우고 다시 붙이는 것**이 정직한 경로이고, 이 화면이 그 삭제도
 * 함께 준다. 엑셀에서는 행을 통째로 다시 쓸 수 있다 — 두 경로의 자유도가 다른 것은 일부러다.
 *
 * ## 상한을 두고 그 사실을 말한다
 * 이 표는 수만 행이 될 수 있다(`scalability_performance` 4장). 한 번에 다 그리면 화면이 멎으므로
 * [PAGE]씩 받되, **잘렸다는 사실과 전체 수를 함께 보이고 더 볼 길을 준다** — 조용히 자르면
 * 사용자는 자기 기록이 사라진 줄 안다.
 */
class DuelMatchesFragment : Fragment() {

    private var _binding: FragmentDuelMatchesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DuelViewModel by viewModels()

    private lateinit var adapter: DuelMatchAdapter
    private var axisId: Long = -1L
    private var axis: DuelAxis? = null
    private var characters: List<Character> = emptyList()

    private var limit = PAGE
    private var rows: List<DuelMatchLog.Row> = emptyList()
    private var onlyDisagreements = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDuelMatchesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        axisId = arguments?.getLong("axisId", -1L) ?: -1L
        if (axisId == -1L) {
            findNavController().popBackStack()
            return
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        adapter = DuelMatchAdapter { row -> showEditDialog(row) }
        binding.matchRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.matchRecyclerView.adapter = adapter

        binding.switchOnlyDisagreements.setOnCheckedChangeListener { _, checked ->
            onlyDisagreements = checked
            renderRows()
        }
        binding.btnLoadMore.setOnClickListener {
            limit += PAGE
            reload()
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let { notifyResult(it); viewModel.clearResult() }
        }

        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = viewModel.axis(axisId) ?: run { findNavController().popBackStack(); return@launch }
            axis = loaded
            binding.toolbar.title = getString(R.string.duel_matches_title_of, loaded.name)
            characters = viewModel.participants(loaded)

            val links = loaded.fieldLinks
            val labels = viewModel.characterFields(loaded.universeId).associate { it.key to it.name }
            val values = viewModel.fieldValuesOf(
                loaded.universeId, characters, links.influences.map { it.key }
            )
            val matches = viewModel.recentMatches(axisId, limit)
            val total = viewModel.matchCount(axisId)
            if (!isAdded) return@launch

            rows = DuelMatchLog.rows(
                matches = matches,
                namesByCode = characters.associate { it.code to it.displayName },
                influences = links.influences,
                labels = labels,
                valuesByCode = values
            )
            val summary = DuelMatchLog.summarize(rows, total)
            binding.summaryText.text = getString(
                R.string.duel_matches_summary,
                summary.shown, summary.total, summary.disagreements, summary.broken
            )
            binding.btnLoadMore.visibility = if (summary.truncated) View.VISIBLE else View.GONE
            binding.btnLoadMore.text = getString(R.string.duel_matches_load_more, summary.total - summary.shown)
            renderRows()
        }
    }

    private fun renderRows() {
        val shown = if (onlyDisagreements) DuelMatchLog.onlyDisagreements(rows) else rows
        adapter.submitList(shown)
        val empty = shown.isEmpty()
        binding.matchRecyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.emptyText.setText(
            if (onlyDisagreements && rows.isNotEmpty()) {
                R.string.duel_matches_no_disagreements
            } else {
                R.string.duel_matches_empty
            }
        )
    }

    /**
     * 한 판을 고친다 — 승자 셋 중 하나, 또는 삭제.
     *
     * **판을 코드로 다시 집는다**([DuelMatchLog.Row.code]). 목록 위치로 집으면 그 사이에
     * 판이 늘거나(다른 화면에서 더 눌렀거나) 줄었을 때 엉뚱한 판을 고친다.
     */
    private fun showEditDialog(row: DuelMatchLog.Row) {
        val context = requireContext()
        val aName = row.aName ?: getString(R.string.duel_unknown_participant)
        val bName = row.bName ?: getString(R.string.duel_unknown_participant)
        val options = arrayOf<CharSequence>(
            getString(R.string.duel_match_pick_winner, aName),
            getString(R.string.duel_match_pick_winner, bName),
            getString(R.string.duel_match_pick_draw)
        )
        val checked = when (row.outcome) {
            DuelMatchLog.Outcome.A_WON -> 0
            DuelMatchLog.Outcome.B_WON -> 1
            DuelMatchLog.Outcome.DRAW -> 2
            // 깨진 판은 아무것도 고르지 않은 채로 연다 — 지금 값이 셋 중 하나가 아니기 때문이다.
            DuelMatchLog.Outcome.BROKEN -> -1
        }
        var picked = checked

        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.duel_match_edit_title))
            .setSingleChoiceItems(options, checked) { _, which -> picked = which }
            .setPositiveButton(R.string.save) { _, _ ->
                if (picked != checked) applyWinner(row, picked)
            }
            .setNeutralButton(R.string.delete) { _, _ -> confirmDelete(row) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyWinner(row: DuelMatchLog.Row, picked: Int) {
        val winnerCode = when (picked) {
            0 -> row.aCode
            1 -> row.bCode
            2 -> null
            else -> return
        }
        // 이력에는 **코드가 아니라 이름**을 남긴다 — 나중에 이력을 읽는 사람에게 `CHR-7`은
        // 아무 뜻이 없다.
        val label = when (picked) {
            0 -> row.aName ?: row.aCode
            1 -> row.bName ?: row.bCode
            else -> getString(R.string.duel_match_result_draw)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.editWinner(row.code, winnerCode, describe(row), label)
            if (!isAdded) return@launch
            reload()
        }
    }

    /**
     * 삭제는 **되돌릴 수 없다** — 판 하나하나는 휴지통 스냅샷을 갖지 않는다(축 삭제와 다르다).
     * 그래서 묻고, 이력에 남긴다(그것이 유일한 자취다).
     */
    private fun confirmDelete(row: DuelMatchLog.Row) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.duel_match_delete_title)
            .setMessage(getString(R.string.duel_match_delete_message, describe(row)))
            .setPositiveButton(R.string.yes) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.deleteMatch(row.code, describe(row))
                    if (!isAdded) return@launch
                    reload()
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun describe(row: DuelMatchLog.Row): String = getString(
        R.string.duel_match_pair,
        row.aName ?: getString(R.string.duel_unknown_participant),
        row.bName ?: getString(R.string.duel_unknown_participant)
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 한 번에 받는 판 수. 넘치면 *더 보기*가 뜬다(자르되 말한다). */
        private const val PAGE = 200
    }
}
