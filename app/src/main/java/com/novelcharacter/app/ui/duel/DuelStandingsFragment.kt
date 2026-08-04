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
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.databinding.FragmentDuelStandingsBinding
import com.novelcharacter.app.ui.adapter.DuelStandingsAdapter
import com.novelcharacter.app.util.DuelStandings
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.launch

/**
 * **순위표** (B-104) — 이 축에서 누가 얼마나 센가, 그리고 그것을 믿어도 되는가.
 *
 * P-10 확정이 정한 바닥을 그대로 쓴다 — **"이 축에 상성 N건" 배지 + 눌러서 상세.**
 * 배지가 필요한 이유는 설계상의 귀결이다: **상성이 있으면 단일 점수로는 원리적으로 표현할
 * 수 없다.** `A>B>C>A`는 어떤 1차원 숫자로도 만들어지지 않으므로, 점수 하나로 뭉개면
 * 사용자가 요구한 상성이 표현될 자리가 없어진다(확정 문서 2-1).
 *
 * **고지를 접지 않는다.** 지워진 캐릭터의 판·깨진 판·상한에 걸려 덜 훑은 사실은 전부
 * 화면에 뜬다([DuelStandings.Caveats]) — 조용히 버리지 않는 것이 개발 의도 2번이다.
 */
class DuelStandingsFragment : Fragment() {

    private var _binding: FragmentDuelStandingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DuelViewModel by viewModels()

    private lateinit var adapter: DuelStandingsAdapter
    private var axisId: Long = -1L
    private var namesByCode: Map<String, Character> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDuelStandingsBinding.inflate(inflater, container, false)
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
        adapter = DuelStandingsAdapter { code ->
            namesByCode[code]?.displayName ?: getString(R.string.duel_unknown_participant)
        }
        binding.standingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.standingsRecyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // 상성 상세에서 처분을 내리면 점수가 달라진다(③은 그 짝을 적합에서 뺀다).
        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            val axis = viewModel.axis(axisId) ?: run { findNavController().popBackStack(); return@launch }
            val characters = viewModel.participants(axis)
            val loaded = viewModel.load(axis, characters)
            if (!isAdded) return@launch

            namesByCode = loaded.charactersByCode
            binding.toolbar.title = getString(R.string.duel_standings_title, axis.name)

            val rows = DuelStandings.rows(loaded.state.fit, loaded.state.report, loaded.state.records)
            adapter.submitList(rows)
            binding.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

            renderCounterBadge(loaded.state.report.count)
            renderCaveats(
                DuelStandings.caveats(
                    loaded.state.fit,
                    loaded.state.report,
                    loaded.state.plan,
                    loaded.state.missingParticipants
                ),
                loaded.state.report.wobbles.size
            )
        }
    }

    /** P-10의 배지 — 0건이어도 감추지 않는다. *"아직 없다"*와 *"안 보고 있다"*는 다르다. */
    private fun renderCounterBadge(count: Int) {
        binding.btnCounters.text = getString(R.string.duel_counter_badge, count)
        binding.btnCounters.setOnClickListener {
            val bundle = Bundle().apply { putLong("axisId", axisId) }
            findNavController().navigateSafe(
                R.id.duelStandingsFragment, R.id.duelCounterFragment, bundle
            )
        }
    }

    private fun renderCaveats(caveats: DuelStandings.Caveats, wobbles: Int) {
        val lines = ArrayList<String>(6)
        if (caveats.orphanMatches > 0) {
            lines.add(
                getString(
                    R.string.duel_caveat_orphan,
                    caveats.orphanMatches,
                    caveats.missingParticipants
                )
            )
        }
        if (caveats.excludedMatches > 0) {
            lines.add(getString(R.string.duel_caveat_excluded, caveats.excludedMatches))
        }
        if (caveats.malformedMatches > 0) {
            lines.add(getString(R.string.duel_caveat_malformed, caveats.malformedMatches))
        }
        if (caveats.notConverged) lines.add(getString(R.string.duel_caveat_not_converged))
        if (caveats.pairScanCapped) lines.add(getString(R.string.duel_caveat_pair_capped))
        if (caveats.triangleScanCapped) lines.add(getString(R.string.duel_caveat_triangle_capped))
        // 흔들림은 짝의 성질이 아니라 **축 자체의 품질 신호**라 여기(축 단위)에서 읽는다.
        if (wobbles > 0) lines.add(getString(R.string.duel_caveat_wobble, wobbles))

        binding.caveatText.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.caveatText.text = lines.joinToString("\n")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
