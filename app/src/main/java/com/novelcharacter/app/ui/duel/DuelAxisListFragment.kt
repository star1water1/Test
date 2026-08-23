package com.novelcharacter.app.ui.duel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.databinding.FragmentDuelAxisListBinding
import com.novelcharacter.app.ui.adapter.DuelAxisAdapter
import com.novelcharacter.app.ui.adapter.DuelFieldLinkAdapter
import com.novelcharacter.app.util.DuelCandidateFilter
import com.novelcharacter.app.util.DuelFieldLinks
import com.novelcharacter.app.util.DuelSystemFields
import com.novelcharacter.app.util.MultiValueInput
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
        // 축 불러오기(B-116) — 필드 관리와 같은 자리에 둔다(툴바 오버플로). 만드는 화면에서
        // *이미 만들어 둔 것을 데려오는* 길이라 같은 화면 안에 있어야 한다(원칙 05).
        binding.toolbar.inflateMenu(R.menu.duel_axis_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_axes -> { showAxisImportDialog(); true }
                else -> false
            }
        }
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
                // **목록만 넘긴다** — 쓰기는 뷰모델이 한 트랜잭션으로 든다(형제 셋과 같은 모양).
                // 종전에는 여기서 축마다 따로 저장해, 손을 떼자마자 나가면 앞쪽 몇 개만
                // 새 번호로 굳었다.
                viewModel.updateAxisDisplayOrders(ordered.map { it.id })
            }
        })
        touchHelper.attachToRecyclerView(binding.axisRecyclerView)
    }

    /**
     * 축을 연다.
     *
     * **이미지 축은 대결·순위표로 곧바로 가지 않는다** — 캐릭터마다 따로 도는 대결이라
     * (사용자 확정) *누구의 그림인가*가 정해지지 않으면 참가자 집합 자체가 없다. 그래서
     * 고르는 화면([DuelImageCharacterFragment])을 먼저 열고, 거기서 고른 캐릭터가
     * 대결·순위표로 함께 넘어간다(설계 13장).
     *
     * 종전에 이 자리는 이미지 축을 **막고 이유를 말했다** — 개명 추종 경로가 없어 판이
     * 남의 이미지에 붙을 수 있었기 때문이다(설계 4장 ①의 ⚠️). 그 빗장은
     * `DuelRepository.followImageRenames`(R-42)가 풀었다.
     */
    private fun openAxis(axis: DuelAxis, destination: Int) {
        val bundle = Bundle().apply { putLong("axisId", axis.id) }
        // **기록 화면만은 캐릭터를 묻지 않는다** — 그 화면은 축 전체의 판을 늘어놓는 자리라
        // (설계 13-4) 참가자 집합이 필요 없다. 대결·순위표는 *누구의 그림인가*가 정해지지
        // 않으면 참가자 집합 자체가 없으므로 고르는 화면을 먼저 연다.
        val target = if (axis.isImageAxis && destination != R.id.duelMatchesFragment) {
            R.id.duelImageCharacterFragment
        } else {
            destination
        }
        findNavController().navigateSafe(R.id.duelAxisListFragment, target, bundle)
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 필드 이름을 먼저 받아 둔다 — 축 편집 창의 연결 요약이 **키가 아니라 이름**을
            // 말해야 하는데, 그 창은 목록에서 곧바로 열리므로 그때 읽으면 늦는다.
            // 시스템 열도 함께 든다(B-167) — 그러지 않으면 이명을 건 축의 요약이 `sys:another_name`이라
            // 적힌다.
            fieldNames = viewModel.linkLabels(universeId)
            val axes = viewModel.axes(universeId)
            if (!isAdded) return@launch
            adapter.submitList(axes)
            binding.emptyText.visibility = if (axes.isEmpty()) View.VISIBLE else View.GONE
            binding.axisRecyclerView.visibility = if (axes.isEmpty()) View.GONE else View.VISIBLE
            loadSummaries(axes)
        }
    }

    /**
     * 목록의 요약 줄 — 축마다 **판 수와 상성 건수**, 그리고 쌓여 있으면 **미정 건수**를 낸다.
     *
     * 상성 건수는 점수 적합을 한 번 돌려야 나오는 값이라 축 하나당 비용이 붙는다. 그래도
     * 여기서 내는 이유는 P-10이 확정한 배지가 *"눌러 보기 전에 볼 것이 있는지 안다"*는
     * 약속이기 때문이다 — 목록이 판 수만 말하면 상성은 다시 일일이 열어야 알 수 있다
     * (원칙 04가 금지하는 부류).
     *
     * **미정('아직 안 정했다')이 뒤늦게 붙은 것도 같은 근거다**(로드맵 5-1). 그 판정은
     * *"나중에 다시 묻는다"*는 약속인데 어디에도 세어지지 않아, 쌓여도 눈에 띄지 않고
     * 결국 다시 묻히지 않았다. **0건이면 적지 않는다** — 모든 축에 `미정 0`이 붙으면
     * 그 글자가 정작 쌓인 축을 가린다.
     */
    private fun loadSummaries(axes: List<DuelAxis>) {
        if (axes.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val summaries = HashMap<Long, String>(axes.size)
            for (axis in axes) {
                // 축마다 로스터를 새로 읽는다(B-168) — 후보 필터가 축 단위라 참가자 구성이
                // 축마다 다르다. 필터 없는 축은 로스터가 캐릭터 목록 한 번으로 끝난다.
                val roster = viewModel.roster(axis)
                val loaded = viewModel.load(axis, roster.participants, roster.candidateCodes)
                val base = getString(
                    R.string.duel_axis_summary,
                    // **쌓인 판 전부**를 센다(`fit.usedMatches`가 아니다). 저쪽은 점수 적합에
                    // 들어간 수라 고아·상성 제외·깨진 판이 빠지므로, 삭제 고지가 말하는
                    // *"쌓인 판 N개"*(전량)와 **같은 것을 두 숫자로 말하게 된다.**
                    loaded.state.records.matches.size,
                    roster.all.size,
                    loaded.state.report.count
                )
                val undecided = loaded.verdicts.count { it.kind == DuelCounterVerdict.KIND_UNDECIDED }
                var summary = if (undecided > 0) {
                    base + getString(R.string.duel_axis_summary_undecided, undecided)
                } else {
                    base
                }
                // 필터가 걸린 축은 목록에서도 보인다 — 눌러 봐야 아는 상태를 만들지 않는다(원칙 04).
                if (roster.filtered) {
                    summary += getString(R.string.duel_axis_summary_filtered, roster.candidateCount)
                }
                summaries[axis.id] = summary
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

        // ── 겨루는 대상 (설계 13장) ──
        //
        // **만든 뒤에는 바꾸지 않는다**(`DuelAxis.targetType`) — 바꾸면 쌓인 판의 참가자가
        // 통째로 뜻을 잃는다(캐릭터 코드였던 것이 경로로 읽히거나 그 반대). 그래서 편집일
        // 때는 이 구역을 감춘다. **막고 이유를 말하는 대신 감추는 것**은, 여기서 고를 수
        // 있는 것이 아무것도 없어 남겨 두면 눌러 보고 안 되는 자리가 되기 때문이다.
        val targetSection = view.findViewById<View>(R.id.targetSection)
        val targetImage = view.findViewById<android.widget.RadioButton>(R.id.targetImage)
        val targetCharacter = view.findViewById<android.widget.RadioButton>(R.id.targetCharacter)
        val linkSection = view.findViewById<View>(R.id.linkSection)
        targetSection.visibility = if (existing == null) View.VISIBLE else View.GONE
        if (existing?.isImageAxis == true) targetImage.isChecked = true else targetCharacter.isChecked = true

        // ── 기준 축 (설계 13-5) ──
        //
        // **이미지 축에만 뜻이 있다** — 대표 추첨의 가중치와 걸러낼 후보가 이미지 축의 순위를
        // 쓴다. 캐릭터 축에서는 켤 자리조차 두지 않는다(눌러 보고 아무 일도 없는 자리를
        // 만들지 않는다 — 대상 구역을 편집일 때 감추는 것과 같은 근거).
        val basisSection = view.findViewById<View>(R.id.basisSection)
        val basisSwitch = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.basisSwitch)
        val basisTakenNotice = view.findViewById<TextView>(R.id.basisTakenNotice)
        basisSwitch.isChecked = existing?.isBasisAxis == true

        // 필드 연결·후보 필터는 이미지 축에 뜻이 없다 — 두 참가자가 **같은 캐릭터의 그림**이라
        // 어떤 필드값도 양쪽에 똑같이 뜨고, 산출 필드는 이미지 순위를 받을 자리가 없다
        // (`DuelViewModel.gradeApplyFieldsFor`가 이미 그렇게 정해 두었다).
        fun applyTargetVisibility() {
            linkSection.visibility = if (targetImage.isChecked) View.GONE else View.VISIBLE
            basisSection.visibility = if (targetImage.isChecked) View.VISIBLE else View.GONE
        }
        applyTargetVisibility()
        targetImage.setOnCheckedChangeListener { _, _ -> applyTargetVisibility() }

        // **지금 무엇이 기준인지 눌러 보기 전에 말한다**(원칙 04). 켜서 저장하면 그쪽이
        // 풀리는데, 그 사실을 저장 뒤에 알면 사용자는 대표 그림이 왜 바뀌었는지 모른다.
        viewLifecycleOwner.lifecycleScope.launch {
            val current = viewModel.basisImageAxis(universeId)
            if (!isAdded || current == null || current.id == existing?.id) return@launch
            basisTakenNotice.text = getString(R.string.duel_axis_basis_taken, current.name)
            basisTakenNotice.visibility = View.VISIBLE
        }

        // 필드 연결 — 창을 여는 동안 편집 중인 값이고, 저장을 눌러야 축에 실린다.
        val links = existing?.fieldLinks ?: DuelFieldLinks.Axis()
        var influences = links.influences
        var outcomes = links.outcomes
        var profiles = links.profiles
        // 후보 필터(B-168)도 같은 계약이다 — 저장을 눌러야 축에 실린다.
        var candidateFilters = DuelCandidateFilter.parse(existing?.candidateFiltersJson)
        val influenceSummary = view.findViewById<TextView>(R.id.influenceSummary)
        val outcomeSummary = view.findViewById<TextView>(R.id.outcomeSummary)
        val profileSummary = view.findViewById<TextView>(R.id.profileSummary)
        val candidateFilterSummary = view.findViewById<TextView>(R.id.candidateFilterSummary)
        val conflictWarning = view.findViewById<TextView>(R.id.linkConflictWarning)
        val profileBlockedWarning = view.findViewById<TextView>(R.id.profileBlockedWarning)
        val outcomeBlockedWarning = view.findViewById<TextView>(R.id.outcomeBlockedWarning)

        fun renderLinks() {
            influenceSummary.text = summarize(influences)
            outcomeSummary.text = summarize(outcomes)
            profileSummary.text = summarize(profiles)
            candidateFilterSummary.text = if (candidateFilters.isEmpty()) {
                getString(R.string.duel_filter_none)
            } else {
                candidateFilters.joinToString(" · ") { viewModel.filterSummary(it) }
            }
            val edited = DuelFieldLinks.Axis(influences, outcomes, profiles)
            // 같은 필드가 재료이면서 결과일 수는 없다 — 막지는 않고 **말한다**(자율성 우선).
            val conflicts = edited.conflicts
            conflictWarning.visibility = if (conflicts.isEmpty()) View.GONE else View.VISIBLE
            if (conflicts.isNotEmpty()) {
                conflictWarning.text = getString(
                    R.string.duel_links_conflict,
                    conflicts.joinToString(", ") { fieldNames[it] ?: it }
                )
            }
            // 프로필로 걸렸는데 산출 필드라 카드에서 빠지는 것 — 고르는 창은 막지만 엑셀로
            // 들어온 파일은 그 창을 지나지 않으므로 이 자리가 필요하다(조용히 빼지 않는다).
            val blocked = edited.profileBlocked
            profileBlockedWarning.visibility = if (blocked.isEmpty()) View.GONE else View.VISIBLE
            if (blocked.isNotEmpty()) {
                profileBlockedWarning.text = getString(
                    R.string.duel_links_profile_blocked_warning,
                    blocked.joinToString(", ") { fieldNames[it] ?: it }
                )
            }
            // 산출로 걸린 시스템 열 — 같은 근거로 여기 있다(B-167). 고르는 창은 애초에 내지
            // 않지만 엑셀은 그 창을 지나지 않으므로, 들어온 것을 조용히 버리지도 조용히
            // 무시하지도 않고 **말한다**(개발 의도 2번).
            val systemOutcomes = edited.outcomeBlocked
            outcomeBlockedWarning.visibility = if (systemOutcomes.isEmpty()) View.GONE else View.VISIBLE
            if (systemOutcomes.isNotEmpty()) {
                outcomeBlockedWarning.text = getString(
                    R.string.duel_links_outcome_system_warning,
                    systemOutcomes.joinToString(", ") { fieldNames[it] ?: it }
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
            // 시스템 열은 내지 않는다(B-167) — 대결 결과가 이명·메모로 흘러갈 수 없다.
            showFieldLinkDialog(outcomes, rankable = false, allowSystem = false) { picked ->
                outcomes = picked
                renderLinks()
            }
        }
        view.findViewById<View>(R.id.btnEditProfile).setOnClickListener {
            showFieldLinkDialog(
                profiles,
                rankable = false,
                directional = false,
                // 지금 편집 중인 산출 필드로 막는다 — 저장된 것이 아니라, 이 창에서 방금
                // 산출로 옮긴 필드도 곧바로 막혀야 앞뒤가 맞는다.
                // **일하는 산출만 막는다**(B-167) — 산출 자리의 시스템 열은 답을 만들지
                // 않으므로 프로필로 고르는 것을 막을 근거가 없다.
                blockedKeys = DuelFieldLinks.Axis(outcomes = outcomes)
                    .effectiveOutcomes.map { it.key }.toSet()
            ) { picked ->
                profiles = picked
                renderLinks()
            }
        }
        view.findViewById<View>(R.id.btnEditCandidateFilter).setOnClickListener {
            showCandidateFilterDialog(existing, candidateFilters) { picked ->
                candidateFilters = picked
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
            val isImage = existing?.isImageAxis ?: targetImage.isChecked
            val axis = (
                existing?.copy(name = name) ?: DuelAxis(
                    universeId = universeId,
                    name = name,
                    targetType =
                        if (targetImage.isChecked) DuelAxis.TARGET_IMAGE else DuelAxis.TARGET_CHARACTER,
                    displayOrder = adapter.itemCount
                )
                ).copy(
                // 이미지 축에는 연결을 **적지 않는다.** 창에서 감췄으니 편집 중인 값은
                // 언제나 비어 있지만, 엑셀로 연결이 실려 들어온 축을 이 창에서 저장할 때
                // 그것을 조용히 지우면 안 된다 — 그래서 기존 값을 그대로 이어받는다
                // (개발 의도 2번: 말없이 유실되지 않는다).
                influenceFieldKeys =
                    if (isImage) existing?.influenceFieldKeys ?: "[]" else DuelFieldLinks.encode(influences),
                outcomeFieldKeys =
                    if (isImage) existing?.outcomeFieldKeys ?: "[]" else DuelFieldLinks.encode(outcomes),
                profileFieldKeys =
                    if (isImage) existing?.profileFieldKeys ?: "[]" else DuelFieldLinks.encode(profiles),
                // 저장 표기는 한 가지다 — 조건이 없으면 "{}"가 아니라 null(마이그레이션 55 주석).
                candidateFiltersJson = when {
                    isImage -> existing?.candidateFiltersJson
                    candidateFilters.isEmpty() -> null
                    else -> DuelCandidateFilter.encode(candidateFilters)
                },
                // 기준 축은 이미지 축의 것이다. 캐릭터 축에서는 구역을 감췄으므로 스위치가
                // 무엇을 들고 있든 기존 값을 그대로 이어받는다 — 연결 셋과 같은 규약이다
                // (감추되 저장된 값은 지운다는 뜻이 아니다).
                isBasisAxis = if (isImage) basisSwitch.isChecked else existing?.isBasisAxis ?: false
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
    // 축 불러오기 (B-116)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * **다른 세계관의 축을 이 세계관으로** — 필드 관리의 '필드 가져오기'와 같은 짜임새다
     * (세계관 고르기 → 여럿 고르기 → 이미 있는 것은 사유와 함께 막기).
     *
     * **막되 감추지 않는다.** 이름이 겹치는 축은 고를 수 없지만 목록에는 남아 *"이미 있음"*을
     * 말한다 — 빼 버리면 사용자는 자기가 찾던 축이 왜 없는지 알 길이 없다(필드 가져오기가
     * 같은 자리에서 정해 둔 처분이다).
     *
     * **이 세계관에 없는 필드는 세어서 말한다**(4장 B-116 행 ⓐ). 연결을 떼고 가져오지 않는
     * 것은 나중에 같은 키의 필드를 만들면 그대로 이어지기 때문이고, 그래도 *지금은 몇 개가
     * 비어 있는지*를 누르기 전에 알아야 축을 열어 보고서야 아는 일이 없다(원칙 04).
     */
    private fun showAxisImportDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            // **읽기 실패를 삼키는 것이 여기서는 옳다** — 삼키지 않으면 이 코루틴이 죽어
            // **메뉴를 눌렀는데 아무 반응도 없다**(조용한 무동작 — R-27이 막는 부류). 아무것도
            // 지워지지 않는 자리이고, 못 읽었으면 *가져올 축이 없다*와 같은 처분이면 된다
            // (필드 관리의 '필드 합치기'가 같은 근거로 같은 처분을 한다).
            val sources = runCatching { viewModel.axisImportSources(universeId) }
                .getOrElse {
                    android.util.Log.e("DuelAxisList", "Failed to collect axis import sources", it)
                    emptyList()
                }
            if (!isAdded) return@launch
            if (sources.isEmpty()) {
                Toast.makeText(requireContext(), R.string.duel_axis_import_none, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }

            val context = requireContext()
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_duel_axis_import, null)
            val spinner = view.findViewById<Spinner>(R.id.importSourceSpinner)
            val holder = view.findViewById<LinearLayout>(R.id.importAxisHolder)
            spinner.adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                sources.map {
                    getString(R.string.duel_axis_import_source, it.label, it.plans.size)
                }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            // 고른 것은 **스피너를 옮겨도 풀리지 않는다** — 다른 세계관을 훑어보고 돌아왔을 때
            // 켜 둔 것이 꺼져 있으면 처음부터 다시 골라야 한다(원칙 04). 확인은 **세계관을 가리지
            // 않고 켜 둔 전부**를 한 번에 심는다(축 code가 전역 유니크라 열쇠로 그대로 쓴다).
            val picked = linkedMapOf<String, DuelAxis>()

            fun renderSource(index: Int) {
                holder.removeAllViews()
                val source = sources.getOrNull(index) ?: return
                for (plan in source.plans) {
                    val row = android.widget.CheckBox(context).apply {
                        // **이미지 축은 이름 뒤에 표식을 단다** — 같은 이름의 캐릭터 축과
                        // 이미지 축은 서로 다른 축이라 둘 다 목록에 설 수 있고(유니크 인덱스가
                        // 대상을 함께 묶는다), 표식이 없으면 **글자가 같은 줄 둘 중 하나만
                        // [이미 있음]인 상태**가 되어 어느 쪽을 고르는지 알 수 없다.
                        text = if (plan.source.isImageAxis) {
                            getString(R.string.duel_axis_import_image_badge, plan.source.name)
                        } else {
                            plan.source.name
                        }
                        isEnabled = plan.importable
                        isChecked = plan.source.code in picked
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) picked[plan.source.code] = plan.source
                            else picked.remove(plan.source.code)
                        }
                    }
                    holder.addView(row)
                    val notes = ArrayList<String>(2)
                    if (plan.duplicateName) notes.add(getString(R.string.duel_axis_import_duplicate))
                    if (plan.missingKeys.isNotEmpty()) {
                        notes.add(
                            getString(R.string.duel_axis_import_missing, plan.missingKeys.size) +
                                " — " + plan.missingKeys.joinToString(", ")
                        )
                    }
                    if (notes.isEmpty()) continue
                    holder.addView(TextView(context).apply {
                        text = notes.joinToString(" · ")
                        textSize = 12f
                        setTextColor(
                            androidx.core.content.ContextCompat
                                .getColor(context, R.color.text_secondary)
                        )
                        setPadding(
                            (32 * resources.displayMetrics.density).toInt(), 0, 0,
                            (6 * resources.displayMetrics.density).toInt()
                        )
                    })
                }
            }
            renderSource(0)
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long
                ) = renderSource(position)

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.duel_axis_import)
                .setView(view)
                .setPositiveButton(R.string.confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            // 아무것도 안 골랐으면 창을 닫지 않는다 — 닫으면 스피너를 옮겨 가며 훑어본
            // 것이 통째로 사라진다(R-27).
            var importing = false
            dialog.setValidatedPositiveButton {
                if (importing) return@setValidatedPositiveButton false
                if (picked.isEmpty()) {
                    Toast.makeText(context, R.string.duel_axis_import_pick_none, Toast.LENGTH_SHORT)
                        .show()
                    return@setValidatedPositiveButton false
                }
                importing = true
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.importAxes(universeId, picked.values.toList())
                    if (!isAdded) return@launch
                    dialog.dismiss()
                    reload()
                }
                false
            }
            dialog.show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 필드 연결 (층 C)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 걸 수 있는 것 전부 — 키 → 이름. 요약 줄이 키가 아니라 **사람이 읽는 이름**을 말하게 한다.
     * 커스텀 필드와 시스템 열이 함께 든다([DuelViewModel.linkLabels]가 단일 소스다 — B-167).
     */
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
        directional: Boolean = true,
        blockedKeys: Set<String> = emptySet(),
        /**
         * 시스템 열을 목록에 내는가 (B-167). **산출 자리에서만 거짓이다** — 대결 결과가
         * 이명·메모로 흘러갈 수는 없어, 고를 수 있는데 아무 일도 안 일어나는 자리가 된다.
         */
        allowSystem: Boolean = true,
        onPicked: (List<DuelFieldLinks.Link>) -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val fields = viewModel.characterFields(universeId)
            if (!isAdded) return@launch
            // 방금 읽은 목록으로 이름표를 짠다 — `linkLabels(universeId)`를 부르면 같은
            // 세계관의 필드를 한 번 더 훑는다.
            fieldNames = viewModel.linkLabels(fields)

            // 커스텀 필드가 먼저다 — 사용자가 만든 것이 목록 앞에 서야 하고, 시스템 열은
            // 늘 같은 자리에 있는 붙박이라 뒤가 자연스럽다.
            //
            // **산출 자리에서는 `sys:` 키를 든 진짜 필드도 뺀다.** 접두가 **예약**이라
            // (R-40) `outcomeBlocked`가 소유자를 묻지 않고 접두만 보는데, 여기서 그 필드를
            // 내면 **고른 뒤 조용히 일하지 않는다** — 이 슬라이스가 막으려던 바로 그 모양이
            // 접두를 빌린 필드에서 되살아난다.
            val pickable = if (allowSystem) fields else fields.filterNot {
                DuelSystemFields.isSystemKey(it.key)
            }
            val systemColumns = if (!allowSystem) {
                emptyList()
            } else {
                // **가려진 열은 아예 내지 않는다**(DuelSystemFields.shadowed) — 같은 키의 진짜
                // 필드가 이미 위에 서 있고, 두 줄이 한 키를 다투면 고르기가 어느 쪽을 집었는지
                // 말할 수 없게 된다. 사용자가 잃는 것은 없다: 그 키의 줄은 **자기 필드**로 떠 있다.
                DuelSystemFields.available(fields.map { it.key })
            }
            val systemTypeLabel = getString(R.string.duel_links_system_type)
            val choices = pickable.map { DuelFieldLinkAdapter.choiceOf(it) } +
                systemColumns.map {
                    DuelFieldLinkAdapter.systemChoice(
                        it.key, viewModel.systemFieldLabel(it), systemTypeLabel
                    )
                }

            val context = requireContext()
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_duel_field_links, null)
            // 프로필은 순위도 방향도 없다 — 그 셋을 한 창으로 가르는 축이 rankable·directional이다.
            val isProfile = !rankable && !directional
            view.findViewById<TextView>(R.id.linksPurpose).setText(
                when {
                    rankable -> R.string.duel_links_influence_purpose
                    isProfile -> R.string.duel_links_profile_purpose
                    else -> R.string.duel_links_outcome_purpose
                }
            )
            // **고를 것이 하나도 없을 때만** 안내를 띄운다 — 커스텀 필드가 없어도 시스템 열은
            // 늘 있으므로(B-167), `fields`만 보면 고를 것이 뻔히 있는 창에 *"필드가 없다"*가 뜬다.
            view.findViewById<View>(R.id.linksEmpty).visibility =
                if (choices.isEmpty()) View.VISIBLE else View.GONE

            // 트리오(성별·나이)가 어디서 오는지 — **이 세계관에 그 역할 필드가 없으면 그 사실을
            // 말한다**(R-17). 없다는 것을 조용히 두면 사용자는 프로필을 아무리 골라도 트리오
            // 줄이 안 뜨는 이유를 알 길이 없다.
            val roleNote = view.findViewById<TextView>(R.id.linksRoleNote)
            if (isProfile) {
                val missing = missingTrioRoles(fields)
                roleNote.visibility = View.VISIBLE
                roleNote.text = if (missing.isEmpty()) {
                    getString(R.string.duel_links_profile_trio_ok)
                } else {
                    getString(R.string.duel_links_profile_trio_missing, missing.joinToString(", "))
                }
            } else {
                roleNote.visibility = View.GONE
            }

            val recycler = view.findViewById<RecyclerView>(R.id.linksRecyclerView)
            val blockedReason = getString(R.string.duel_links_profile_blocked_note)
            val linkAdapter = DuelFieldLinkAdapter(
                choices,
                current,
                rankable,
                {},
                directional,
                blockedKeys.associateWith { blockedReason }
            )
            recycler.layoutManager = LinearLayoutManager(context)
            recycler.adapter = linkAdapter

            MaterialAlertDialogBuilder(context)
                .setTitle(
                    when {
                        rankable -> R.string.duel_links_influence_label
                        isProfile -> R.string.duel_links_profile_label
                        else -> R.string.duel_links_outcome_label
                    }
                )
                .setView(view)
                .setPositiveButton(R.string.confirm) { _, _ -> onPicked(linkAdapter.currentLinks()) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 후보 필터 (B-168)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 후보 필터 편집 창.
     *
     * 값 고르기는 이 세계관에 **실재하는 값**의 칩이 기본이고, 없는 값은 직접 입력한다
     * (러프/정밀 이중 경로 — 원칙 04). 조건을 넣고 뺄 때마다 **후보 수 미리보기**가 곧바로
     * 갱신된다 — 저장하고 대결 화면에 가서야 후보가 0인 것을 아는 구조를 만들지 않는다.
     *
     * @param existing 편집 중인 축. 새 축이면 null이고, 미리보기는 임시 축(id 0 — 판이 없어
     *   전적 합집합이 후보와 같다)으로 돈다.
     */
    private fun showCandidateFilterDialog(
        existing: DuelAxis?,
        current: List<com.novelcharacter.app.data.model.FieldFilter>,
        onPicked: (List<com.novelcharacter.app.data.model.FieldFilter>) -> Unit
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val fields = viewModel.characterFields(universeId)
            if (!isAdded) return@launch
            fieldNames = viewModel.linkLabels(fields)

            // 고를 수 있는 항목 — 커스텀 필드가 먼저, 시스템 열이 뒤(연결 고르기와 같은 차례).
            // 가려진 시스템 열은 내지 않는다(같은 키의 진짜 필드가 이미 위에 있다 — B-167).
            val options = ArrayList<Pair<String, String>>()
            fields.forEach { options.add(it.key to it.name) }
            DuelSystemFields.available(fields.map { it.key }).forEach { column ->
                options.add(
                    column.key to getString(
                        R.string.duel_filter_system_option, viewModel.systemFieldLabel(column)
                    )
                )
            }

            val context = requireContext()
            val view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_duel_candidate_filter, null)
            val chipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filterChipGroup)
            val emptyText = view.findViewById<TextView>(R.id.filterEmpty)
            val preview = view.findViewById<TextView>(R.id.filterPreview)
            val fieldSpinner = view.findViewById<android.widget.Spinner>(R.id.filterFieldSpinner)
            val valuesEmpty = view.findViewById<TextView>(R.id.filterValuesEmpty)
            val valueChips = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filterValueChipGroup)
            val valueInput = view.findViewById<EditText>(R.id.filterValueInput)
            val matchContains = view.findViewById<android.widget.RadioButton>(R.id.filterMatchContains)

            var edited = current
            var previewJob: kotlinx.coroutines.Job? = null

            fun renderPreview() {
                previewJob?.cancel()
                previewJob = viewLifecycleOwner.lifecycleScope.launch {
                    // 미리보기는 실제 적용과 **같은 길**(roster)을 지난다 — 따로 세면 갈린다.
                    val probe = (existing ?: DuelAxis(universeId = universeId, name = ""))
                        .copy(
                            candidateFiltersJson = if (edited.isEmpty()) {
                                null
                            } else {
                                DuelCandidateFilter.encode(edited)
                            }
                        )
                    val roster = viewModel.roster(probe)
                    if (!isAdded) return@launch
                    val count = roster.candidateCount
                    preview.text = getString(
                        if (count < 2) R.string.duel_filter_preview_short else R.string.duel_filter_preview,
                        count
                    )
                }
            }

            fun renderConditions() {
                chipGroup.removeAllViews()
                emptyText.visibility = if (edited.isEmpty()) View.VISIBLE else View.GONE
                edited.forEach { filter ->
                    val chip = com.google.android.material.chip.Chip(context).apply {
                        text = viewModel.filterSummary(filter)
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            edited = edited - filter
                            renderConditions()
                        }
                    }
                    chipGroup.addView(chip)
                }
                renderPreview()
            }

            var valueLoadJob: kotlinx.coroutines.Job? = null
            fun loadValues(key: String) {
                valueLoadJob?.cancel()
                valueLoadJob = viewLifecycleOwner.lifecycleScope.launch {
                    val values = viewModel.filterValueSuggestions(universeId, key)
                    if (!isAdded) return@launch
                    valueChips.removeAllViews()
                    valuesEmpty.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
                    values.forEach { value ->
                        valueChips.addView(
                            com.google.android.material.chip.Chip(context).apply {
                                text = value
                                isCheckable = true
                            }
                        )
                    }
                }
            }

            fieldSpinner.adapter = android.widget.ArrayAdapter(
                context, android.R.layout.simple_spinner_dropdown_item, options.map { it.second }
            )
            fieldSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long
                ) {
                    options.getOrNull(position)?.let { loadValues(it.first) }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            if (options.isNotEmpty()) loadValues(options[0].first)

            view.findViewById<View>(R.id.btnAddCondition).setOnClickListener {
                val picked = options.getOrNull(fieldSpinner.selectedItemPosition) ?: return@setOnClickListener
                val values = ArrayList<String>()
                for (i in 0 until valueChips.childCount) {
                    (valueChips.getChildAt(i) as? com.google.android.material.chip.Chip)
                        ?.takeIf { it.isChecked }
                        ?.let { values.add(it.text.toString()) }
                }
                MultiValueInput.parse(valueInput.text.toString())
                    .filter { it !in values }
                    .forEach { values.add(it) }
                if (values.isEmpty()) {
                    valueInput.showInlineError(getString(R.string.duel_filter_add_needs_value))
                    return@setOnClickListener
                }
                edited = edited + com.novelcharacter.app.data.model.FieldFilter(
                    fieldId = -1L,
                    // 시스템 열 접두를 이름표에 남기지 않는다 — 요약 줄은 사람이 읽는 말이다.
                    fieldName = fieldNames[picked.first] ?: picked.second,
                    values = values,
                    matchMode = if (matchContains.isChecked) "contains" else "exact",
                    fieldKey = picked.first
                )
                valueInput.setText("")
                for (i in 0 until valueChips.childCount) {
                    (valueChips.getChildAt(i) as? com.google.android.material.chip.Chip)?.isChecked = false
                }
                renderConditions()
            }

            renderConditions()

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.duel_filter_label)
                .setView(view)
                .setPositiveButton(R.string.confirm) { _, _ -> onPicked(edited) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * 트리오 중 **이 세계관에 역할 필드가 없는 것**의 이름 — 없으면 빈 목록이다.
     *
     * 라벨 문자열로 성별·나이 필드를 찾지 않는 것은 R-20이다. 역할이 지정돼 있지 않으면
     * 그 줄은 뜨지 않으며, 그 사실을 말하는 것이 이 함수의 몫이다.
     */
    private fun missingTrioRoles(fields: List<FieldDefinition>): List<String> {
        val present = fields.mapNotNull { SemanticRole.fromConfig(it.config) }.toSet()
        return listOf(SemanticRole.GENDER, SemanticRole.AGE)
            .filter { it !in present }
            .map { it.label }
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
