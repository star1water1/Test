package com.novelcharacter.app.ui.duel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.databinding.FragmentDuelAxisListBinding
import com.novelcharacter.app.ui.adapter.DuelAxisAdapter
import com.novelcharacter.app.ui.adapter.DuelFieldLinkAdapter
import com.novelcharacter.app.util.DuelFieldLinks
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import kotlinx.coroutines.launch

/**
 * 대결의 **축 목록** (B-104 화면 계층) — "무엇을 겨루는가"를 사용자가 만드는 자리.
 *
 * 축은 세계관 단위다(사용자 확정 ㄷ1). 강함·아름다움 같은 이름은 예시일 뿐이고 **추가·편집·
 * 삭제가 전부 열려 있다**(원칙 01) — 고정 프리셋을 두지 않는 것이 이 앱의 뼈대다.
 *
 * **삭제는 규모를 먼저 알린다**(R-4). 축을 지우면 그 아래 수만 판이 FK CASCADE로 함께 죽는데,
 * 저장소가 지우기 전에 휴지통에 담으므로 되돌릴 수는 있다. 그래도 *"판 1,234개가 함께
 * 들어갑니다"*를 묻기 전에 말한다 — 규모를 모르고 누른 삭제는 동의가 아니다.
 */
class DuelAxisListFragment : Fragment() {

    private var _binding: FragmentDuelAxisListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DuelViewModel by viewModels()

    private lateinit var adapter: DuelAxisAdapter
    private var universeId: Long = -1L

    // 드래그 중의 순서 — ListAdapter의 비동기 diff가 끝나기 전에는 currentList가 낡아 있다.
    private var pendingOrderList: List<DuelAxis>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDuelAxisListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        universeId = arguments?.getLong("universeId", -1L) ?: -1L
        if (universeId == -1L) {
            findNavController().popBackStack()
            return
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupRecyclerView()
        binding.fabAddAxis.setOnClickListener { showAxisEditDialog(null) }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let { notifyResult(it); viewModel.clearResult() }
        }
    }

    override fun onResume() {
        super.onResume()
        // 대결 화면에서 판을 쌓고 돌아오면 요약(판 수·상성)이 달라져 있다.
        reload()
    }

    private fun setupRecyclerView() {
        adapter = DuelAxisAdapter(
            onClick = { axis -> openAxis(axis, R.id.duelPlayFragment) },
            onEdit = { axis -> showAxisEditDialog(axis) },
            onDelete = { axis -> confirmDelete(axis) },
            onStandings = { axis -> openAxis(axis, R.id.duelStandingsFragment) },
            onMatches = { axis -> openAxis(axis, R.id.duelMatchesFragment) }
        )
        binding.axisRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.axisRecyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val list = (pendingOrderList ?: adapter.currentList).toMutableList()
                if (from >= list.size || to >= list.size) return false
                list.add(to, list.removeAt(from))
                pendingOrderList = list
                adapter.submitList(list)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val ordered = pendingOrderList ?: return
                pendingOrderList = null
                viewLifecycleOwner.lifecycleScope.launch {
                    ordered.forEachIndexed { index, axis ->
                        if (axis.displayOrder != index) viewModel.saveAxis(axis.copy(displayOrder = index))
                    }
                }
            }
        })
        touchHelper.attachToRecyclerView(binding.axisRecyclerView)
    }

    /**
     * 축을 연다. **이미지 축은 아직 열지 않는다.**
     *
     * 이미지 축의 참가자 코드는 이미지 경로인데, 폴더 왕복이 파일을 개명하면 그 코드도 함께
     * 옮겨야 한다 — 그 경로가 아직 없다(설계 4장 ①의 ⚠️). 지금 열면 개명 한 번에 쌓아 둔
     * 판이 통째로 남의 이미지에 붙거나 고아가 된다(R-1이 막는 오배정). 그래서 **막고 이유를
     * 말한다** — 조용히 캐릭터를 참가자로 세우면 그것이 바로 그 오배정이다.
     *
     * 이 화면은 이미지 축을 **만들지 않으므로** 지금 이 자리에 걸릴 축은 없다. 엑셀·백업으로
     * 들어올 수 있어 미리 세워 둔다.
     */
    private fun openAxis(axis: DuelAxis, destination: Int) {
        if (axis.isImageAxis) {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.duel_image_axis_not_ready)
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }
        val bundle = Bundle().apply { putLong("axisId", axis.id) }
        findNavController().navigateSafe(R.id.duelAxisListFragment, destination, bundle)
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 필드 이름을 먼저 받아 둔다 — 축 편집 창의 연결 요약이 **키가 아니라 이름**을
            // 말해야 하는데, 그 창은 목록에서 곧바로 열리므로 그때 읽으면 늦는다.
            fieldNames = viewModel.characterFields(universeId).associate { it.key to it.name }
            val axes = viewModel.axes(universeId)
            if (!isAdded) return@launch
            adapter.submitList(axes)
            binding.emptyText.visibility = if (axes.isEmpty()) View.VISIBLE else View.GONE
            binding.axisRecyclerView.visibility = if (axes.isEmpty()) View.GONE else View.VISIBLE
            loadSummaries(axes)
        }
    }

    /**
     * 목록의 요약 줄 — 축마다 **판 수와 상성 건수**를 낸다.
     *
     * 상성 건수는 점수 적합을 한 번 돌려야 나오는 값이라 축 하나당 비용이 붙는다. 그래도
     * 여기서 내는 이유는 P-10이 확정한 배지가 *"눌러 보기 전에 볼 것이 있는지 안다"*는
     * 약속이기 때문이다 — 목록이 판 수만 말하면 상성은 다시 일일이 열어야 알 수 있다
     * (원칙 04가 금지하는 부류).
     */
    private fun loadSummaries(axes: List<DuelAxis>) {
        if (axes.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val characters = viewModel.participantsOf(universeId)
            val summaries = HashMap<Long, String>(axes.size)
            for (axis in axes) {
                val loaded = viewModel.load(axis, characters)
                summaries[axis.id] = getString(
                    R.string.duel_axis_summary,
                    // **쌓인 판 전부**를 센다(`fit.usedMatches`가 아니다). 저쪽은 점수 적합에
                    // 들어간 수라 고아·상성 제외·깨진 판이 빠지므로, 삭제 고지가 말하는
                    // *"쌓인 판 N개"*(전량)와 **같은 것을 두 숫자로 말하게 된다.**
                    loaded.state.records.matches.size,
                    characters.size,
                    loaded.state.report.count
                )
                if (!isAdded) return@launch
                adapter.updateSummaries(summaries.toMap())
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 축 추가 · 편집
    // ──────────────────────────────────────────────────────────────────────

    private fun showAxisEditDialog(existing: DuelAxis?) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_duel_axis_edit, null)
        val editName = view.findViewById<EditText>(R.id.editAxisName)
        editName.setText(existing?.name.orEmpty())

        // 필드 연결 — 창을 여는 동안 편집 중인 값이고, 저장을 눌러야 축에 실린다.
        val links = existing?.fieldLinks ?: DuelFieldLinks.Axis()
        var influences = links.influences
        var outcomes = links.outcomes
        val influenceSummary = view.findViewById<TextView>(R.id.influenceSummary)
        val outcomeSummary = view.findViewById<TextView>(R.id.outcomeSummary)
        val conflictWarning = view.findViewById<TextView>(R.id.linkConflictWarning)

        fun renderLinks() {
            influenceSummary.text = summarize(influences)
            outcomeSummary.text = summarize(outcomes)
            // 같은 필드가 재료이면서 결과일 수는 없다 — 막지는 않고 **말한다**(자율성 우선).
            val conflicts = DuelFieldLinks.Axis(influences, outcomes).conflicts
            conflictWarning.visibility = if (conflicts.isEmpty()) View.GONE else View.VISIBLE
            if (conflicts.isNotEmpty()) {
                conflictWarning.text = getString(
                    R.string.duel_links_conflict,
                    conflicts.joinToString(", ") { fieldNames[it] ?: it }
                )
            }
        }
        renderLinks()

        view.findViewById<View>(R.id.btnEditInfluence).setOnClickListener {
            showFieldLinkDialog(influences, rankable = true) { picked ->
                influences = picked
                renderLinks()
            }
        }
        view.findViewById<View>(R.id.btnEditOutcome).setOnClickListener {
            showFieldLinkDialog(outcomes, rankable = false) { picked ->
                outcomes = picked
                renderLinks()
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.duel_axis_add else R.string.duel_axis_edit)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        // 검증에 걸려도 창을 닫지 않는다 — 닫으면 사용자가 적은 것이 사라진다(R-27).
        var saving = false
        dialog.setValidatedPositiveButton {
            if (saving) return@setValidatedPositiveButton false
            val name = editName.text.toString().trim()
            if (name.isEmpty()) {
                editName.showInlineError(getString(R.string.duel_axis_name_required))
                return@setValidatedPositiveButton false
            }
            val axis = (
                existing?.copy(name = name) ?: DuelAxis(
                    universeId = universeId,
                    name = name,
                    targetType = DuelAxis.TARGET_CHARACTER,
                    displayOrder = adapter.itemCount
                )
                ).copy(
                influenceFieldKeys = DuelFieldLinks.encode(influences),
                outcomeFieldKeys = DuelFieldLinks.encode(outcomes)
            )
            saving = true
            // 이름 유니크는 인덱스가 지킨다. 인덱스에 걸리면 예외로 죽으므로 **먼저 묻는다** —
            // 물어보지 않으면 저장이 조용히 실패한 것처럼 보인다.
            viewLifecycleOwner.lifecycleScope.launch {
                val taken = viewModel.nameTaken(axis)
                if (!isAdded) return@launch
                if (taken) {
                    saving = false
                    editName.showInlineError(getString(R.string.duel_axis_name_taken))
                    return@launch
                }
                val saved = viewModel.saveAxis(axis)
                viewModel.reportAxisSaved(saved, isNew = existing == null)
                if (!isAdded) return@launch
                dialog.dismiss()
                reload()
            }
            false
        }
        dialog.show()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 필드 연결 (층 C)
    // ──────────────────────────────────────────────────────────────────────

    /** 이 세계관의 캐릭터 필드 — 키 → 이름. 요약 줄이 키가 아니라 **사람이 읽는 이름**을 말하게 한다. */
    private var fieldNames: Map<String, String> = emptyMap()

    private fun summarize(links: List<DuelFieldLinks.Link>): String {
        if (links.isEmpty()) return getString(R.string.duel_links_none)
        return links.joinToString(" · ") { link ->
            val label = fieldNames[link.key] ?: link.key
            if (link.higherWins) label else getString(R.string.duel_links_lower_marker, label)
        }
    }

    /**
     * 필드를 고르는 창.
     *
     * **필드를 여기서 만들 수는 없다** — 이 창은 이미 있는 필드를 잇는 자리이고, 필드가 하나도
     * 없으면 그 사실과 어디서 만드는지를 말한다(빈 화면에 아무 설명이 없으면 사용자는 기능이
     * 고장 난 것으로 읽는다).
     */
    private fun showFieldLinkDialog(
        current: List<DuelFieldLinks.Link>,
        rankable: Boolean,
        onPicked: (List<DuelFieldLinks.Link>) -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val fields = viewModel.characterFields(universeId)
            if (!isAdded) return@launch
            fieldNames = fields.associate { it.key to it.name }

            val context = requireContext()
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_duel_field_links, null)
            view.findViewById<TextView>(R.id.linksPurpose).setText(
                if (rankable) R.string.duel_links_influence_purpose else R.string.duel_links_outcome_purpose
            )
            view.findViewById<View>(R.id.linksEmpty).visibility =
                if (fields.isEmpty()) View.VISIBLE else View.GONE

            val recycler = view.findViewById<RecyclerView>(R.id.linksRecyclerView)
            val linkAdapter = DuelFieldLinkAdapter(fields, current, rankable) {}
            recycler.layoutManager = LinearLayoutManager(context)
            recycler.adapter = linkAdapter

            MaterialAlertDialogBuilder(context)
                .setTitle(if (rankable) R.string.duel_links_influence_label else R.string.duel_links_outcome_label)
                .setView(view)
                .setPositiveButton(R.string.confirm) { _, _ -> onPicked(linkAdapter.currentLinks()) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun confirmDelete(axis: DuelAxis) {
        viewLifecycleOwner.lifecycleScope.launch {
            val matches = viewModel.matchCount(axis.id)
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.duel_axis_delete_title)
                .setMessage(getString(R.string.duel_axis_delete_message, axis.name, matches))
                .setPositiveButton(R.string.yes) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        viewModel.deleteAxis(axis)
                        if (!isAdded) return@launch
                        reload()
                    }
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
