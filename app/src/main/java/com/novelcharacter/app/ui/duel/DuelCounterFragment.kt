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
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.databinding.FragmentDuelCounterBinding
import com.novelcharacter.app.ui.adapter.DuelCounterAdapter
import com.novelcharacter.app.util.DuelStandings
import com.novelcharacter.app.util.notifyResult
import kotlinx.coroutines.launch

/**
 * **상성 상세** (B-104) — 층 B의 처분을 사용자가 가르는 자리.
 *
 * 앱은 어긋남을 **찾기만 하고 판정하지 않는다.** 확정 문서 2-1이 못 박은 것이 이것이다 —
 * *"순환을 오류로 처리하면, 상성을 가진 세계관에서 앱이 계속 틀렸다고 잔소리하는 기능이
 * 된다."* 그래서 셋 중 무엇인지는 창작자가 고른다:
 *
 * | 처분 | 여기서 하는 일 |
 * |---|---|
 * | ① 잘못 눌렀다 | **그 관계를 만든 판들을 지운다.** 어긋남 자체가 사라지므로 남길 판정이 없다 |
 * | ② 아직 안 정했다 | 판정을 남기고 **점수는 건드리지 않는다.** 어긋남이 그대로라 나중에 다시 묻게 된다 |
 * | ③ 상성이다 | 관계로 등재하고 **그 짝을 점수 적합에서 뺀다** — 두 층을 가르면 양쪽이 다 정확해진다 |
 *
 * ①이 지우는 판 수를 **묻기 전에 알린다**(R-4). 되돌릴 수 없는 처분이고, 몇 판이 사라지는지
 * 모르고 누른 것은 동의가 아니다.
 */
class DuelCounterFragment : Fragment() {

    private var _binding: FragmentDuelCounterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DuelViewModel by viewModels()

    private lateinit var adapter: DuelCounterAdapter
    private var axisId: Long = -1L
    private var charactersByCode: Map<String, Character> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDuelCounterBinding.inflate(inflater, container, false)
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
        adapter = DuelCounterAdapter(
            nameOf = { code ->
                charactersByCode[code]?.displayName ?: getString(R.string.duel_unknown_participant)
            },
            onMistake = { item -> confirmMistake(item) },
            onUndecided = { item -> decide(item, DuelCounterVerdict.KIND_UNDECIDED) },
            onCounter = { item -> decide(item, DuelCounterVerdict.KIND_COUNTER) },
            onClear = { item -> clearVerdict(item) }
        )
        binding.counterRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.counterRecyclerView.adapter = adapter

        // 처분은 순위표의 다른 줄까지 움직인다 — 무엇이 처리됐는지 알리고 이력에도 남긴다.
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let { notifyResult(it); viewModel.clearResult() }
        }
        reload()
    }

    /**
     * 이력·알림에 쓸 관계 이름 — **이름을 차례대로 잇는다.**
     *
     * 카드에 보이는 문장(*"A가 위인데 B에게 집니다"*)을 그대로 쓰지 않는 것은 일부러다.
     * 이력은 한 줄 요약이고 목록에서 훑어 읽는 것이라, 문장보다 **관계의 모양**이 눈에 들어와야
     * 한다. 순서(뜻이 있는 순서 — 천적은 `[센 쪽, 잡는 쪽]`)는 양쪽이 같다.
     */
    private fun relationLabel(item: DuelStandings.CounterItem): String =
        item.memberCodes.joinToString(" → ") { code ->
            charactersByCode[code]?.displayName ?: getString(R.string.duel_unknown_participant)
        }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val axis = viewModel.axis(axisId) ?: run { findNavController().popBackStack(); return@launch }
            val characters = viewModel.participants(axis)
            val loaded = viewModel.load(axis, characters)
            if (!isAdded) return@launch

            charactersByCode = loaded.charactersByCode
            binding.toolbar.title = getString(R.string.duel_counter_title, axis.name)

            val review = DuelStandings.counterReview(
                loaded.state.report, loaded.state.records, loaded.verdicts
            )
            adapter.submit(DuelCounterAdapter.rowsOf(review))

            // 코드를 되찾지 못해 못 실은 항목이 있으면 **그 사실을 말한다** — 조용히 빠지면
            // 사용자는 상성이 없다고 믿는다(개발 의도 2번).
            binding.unresolvedText.visibility =
                if (review.unresolvedMembers > 0) View.VISIBLE else View.GONE
            binding.unresolvedText.text =
                getString(R.string.duel_counter_unresolved, review.unresolvedMembers)
        }
    }

    /** 층 B ① — 그 관계를 만든 판들을 지운다. 지우는 수를 먼저 알린다(R-4). */
    private fun confirmMistake(item: DuelStandings.CounterItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val matches = viewModel.matchesBetween(axisId, item.memberCodes)
            if (!isAdded) return@launch
            if (matches.isEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.duel_verdict_mistake_none)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
                return@launch
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.duel_verdict_mistake)
                .setMessage(getString(R.string.duel_verdict_mistake_confirm, matches.size))
                .setPositiveButton(R.string.yes) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.deleteMatches(matches, relationLabel(item))
                        if (!isAdded) return@launch
                        reload()
                    }
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }
    }

    /** 층 B ②·③ — 같은 관계에 다시 판정하면 덮어쓴다(②를 ③으로 굳히는 흐름이 확정이다). */
    private fun decide(item: DuelStandings.CounterItem, kind: String) {
        val relation = relationLabel(item)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recordVerdict(axisId, item.memberCodes, kind, relation)
            if (!isAdded) return@launch
            reload()
        }
    }

    private fun clearVerdict(item: DuelStandings.CounterItem) {
        val relation = relationLabel(item)
        viewLifecycleOwner.lifecycleScope.launch {
            val verdict = viewModel.verdictById(axisId, item.verdictId)
            if (verdict != null) viewModel.clearVerdict(verdict, relation)
            if (!isAdded) return@launch
            reload()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
