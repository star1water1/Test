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
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.util.CharacterRepresentativeImage
import com.novelcharacter.app.util.DuelImageRoster
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

    /** '캐릭터를 넘는 판만' 거르개 (B-208) — 순위표 고지가 켠 채로 열 수 있다. */
    private var onlyCross = false

    /**
     * 그 거르개의 **범위** — 0 이하면 축 전체, 크면 그 캐릭터가 낀 것만.
     *
     * 순위표의 고지는 *그 캐릭터 몫*을 세므로(설계 13-5), 범위 없이 걸면 축 전체가 걸려
     * **N개라던 것이 M개로 보인다**(확정 15-8 착수 조건).
     */
    private var focusCharacterId = -1L
    private var focusCharacterName: String? = null

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
        // 회전 뒤에는 사용자가 만든 상태가 이긴다 — 인자를 다시 읽으면 껐던 거르개가 되살아난다.
        if (savedInstanceState == null) {
            onlyCross = arguments?.getBoolean(ARG_ONLY_CROSS, false) ?: false
            focusCharacterId = arguments?.getLong(ARG_FOCUS_CHARACTER_ID, -1L) ?: -1L
        } else {
            onlyCross = savedInstanceState.getBoolean(ARG_ONLY_CROSS, false)
            focusCharacterId = savedInstanceState.getLong(ARG_FOCUS_CHARACTER_ID, -1L)
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        adapter = DuelMatchAdapter { row -> showEditDialog(row) }
        binding.matchRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.matchRecyclerView.adapter = adapter

        binding.switchOnlyDisagreements.setOnCheckedChangeListener { _, checked ->
            onlyDisagreements = checked
            renderRows()
        }
        // 거르개를 켜고 끄면 **받아 오는 범위가 달라진다** — 켜면 축 전수를 훑어 그중에서
        // 넘기므로(아래 reload) 목록만 다시 그려서는 안 된다.
        binding.switchOnlyCross.isChecked = onlyCross
        binding.switchOnlyCross.setOnCheckedChangeListener { _, checked ->
            if (onlyCross == checked) return@setOnCheckedChangeListener
            onlyCross = checked
            limit = PAGE
            reload()
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
            // 캐릭터를 넘는 판은 **이미지 축에만 있다**(참가자가 경로라야 주인이 갈린다).
            // 다른 축에서 켜진 채로 들어오면 스위치는 감춰지는데 목록만 비어 **끌 길이 없다** —
            // 그 자리에서 끄고 평소 목록을 보인다.
            if (!loaded.isImageAxis) onlyCross = false
            binding.toolbar.title = getString(R.string.duel_matches_title_of, loaded.name)
            // 기록 화면은 **참가자 전부**를 본다(후보 필터와 무관하다 — 이미 기록된 판의
            // 참가자 이름이 필터 때문에 '알 수 없음'이 되면 안 된다).
            //
            // 이미지 축은 로스터를 아예 거치지 않는다. 그쪽 후보 필터는 화면이 감춰 두었지만
            // **엑셀로는 들어올 수 있고**(감추되 저장값은 이어받는다 — 설계 13-3), 그때
            // 걸러진 캐릭터의 그림이 이름표에서 빠져 멀쩡한 판이 '알 수 없음'으로 뜬다.
            characters = if (loaded.isImageAxis) {
                viewModel.participantsOf(loaded.universeId)
            } else {
                viewModel.roster(loaded).participants
            }

            val links = loaded.fieldLinks
            // 시스템 열까지 든다(B-167) — 기록 줄이 카드와 같은 이름을 말해야 한다.
            val labels = viewModel.linkLabels(loaded.universeId)
            // 이미지 축에는 영향 필드가 없다(축 편집 창이 아예 감춘다) — 값을 읽을 것이
            // 없으므로 질의도 열지 않는다.
            val values = if (loaded.isImageAxis) {
                emptyMap()
            } else {
                viewModel.fieldValuesOf(loaded.universeId, characters, links.influences.map { it.key })
            }
            // ── 캐릭터를 넘는 판만 (B-208) ──
            //
            // **거르개를 켜면 최근 N판이 아니라 축 전수에서 고른다.** 최근 판만 걸러 내면
            // 순위표가 *"N개 있습니다"*라고 말한 판이 오래된 것일 때 **0개가 보인다** —
            // 확정 15-8이 막으려던 그 증상이 술어가 아니라 **범위**에서 되살아나는 자리다.
            //
            // 새 상한이 아니다: 순위표로 오는 길목(`imageTarget`)이 이미 같은 축의 판을
            // 전수로 읽어 소유 표를 만든다(`scalability_performance` 7장 2단계 — 축 하나의
            // 판 수에 붙는 비용이고, 켠 회차에만 든다).
            val crossOwners = if (onlyCross) DuelImageRoster.owners(characters) else null
            val matches: List<DuelMatch>
            val total: Int
            if (crossOwners != null) {
                val cross = DuelImageRoster.crossCharacterMatchesOf(
                    viewModel.allMatches(axisId), crossOwners, focusCharacterId
                )
                total = cross.size
                matches = cross.take(limit)
            } else {
                matches = viewModel.recentMatches(axisId, limit)
                total = viewModel.matchCount(axisId)
            }
            if (!isAdded) return@launch

            rows = DuelMatchLog.rows(
                matches = matches,
                // 이미지 축의 참가자 코드는 경로다 — 캐릭터 이름표로 찾으면 전부 '알 수 없음'이
                // 된다. **어느 캐릭터의 그림인지까지 붙인다**: 이 화면은 축 전체의 판을 한 줄로
                // 늘어놓으므로 파일 이름만으로는 누구의 것인지 알 수 없다(대결·순위표와 달리
                // 여기는 캐릭터가 섞여 있다).
                namesByCode = if (loaded.isImageAxis) imageNames() else {
                    characters.associate { it.code to it.displayName }
                },
                influences = if (loaded.isImageAxis) emptyList() else links.influences,
                labels = labels,
                valuesByCode = values
            )
            renderCrossFilter(loaded.isImageAxis)
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

    /**
     * 이미지 축의 참가자 이름표 — `경로 → "파일 이름 (캐릭터)"`.
     *
     * 캐릭터 이름을 함께 붙이는 것은 이 화면이 **축 전체의 판**을 한 줄로 늘어놓기 때문이다.
     * 대결·순위표는 한 캐릭터 안에 있어 파일 이름만으로 충분하지만, 여기서는 여러 캐릭터의
     * 판이 섞여 있어 파일 이름만 적으면 *"이게 누구 그림이었지"*를 알 길이 없다.
     */
    private fun imageNames(): Map<String, String> {
        val out = HashMap<String, String>()
        for (character in characters) {
            for (path in CharacterRepresentativeImage.paths(character.imagePaths)) {
                out[path] = getString(
                    R.string.duel_image_match_participant,
                    path.substringAfterLast('/').ifBlank { path },
                    character.displayName
                )
            }
        }
        return out
    }

    /**
     * 거르개를 세울지 정한다 (B-208) — **이미지 축에만 선다.**
     *
     * 다른 축에는 캐릭터를 넘는 판이 원리적으로 없다(참가자가 캐릭터 자신이다). 세워 두면
     * 눌러 봐야 언제나 빈 목록이고, 그것은 *기능이 고장 났다*와 구별되지 않는다(R-17).
     *
     * 범위를 갖고 왔으면 **그 사실을 말한다** — 순위표에서 넘어온 사람은 축 전체가 아니라
     * *그 캐릭터의 판*을 보고 있는데, 아무 말도 없으면 축에 그것뿐인 줄 안다.
     */
    private fun renderCrossFilter(isImageAxis: Boolean) {
        val b = _binding ?: return
        if (!isImageAxis) {
            b.switchOnlyCross.visibility = View.GONE
            b.onlyCrossPurpose.visibility = View.GONE
            return
        }
        b.switchOnlyCross.visibility = View.VISIBLE
        b.onlyCrossPurpose.visibility = View.VISIBLE
        // 인자로 켜진 채 들어온 회차에는 스위치가 아직 꺼져 있다 — 같은 값이면 리스너가
        // 되돌아 나가므로(위 가드) 다시 부르는 비용은 없다.
        b.switchOnlyCross.isChecked = onlyCross
        focusCharacterName = characters.firstOrNull { it.id == focusCharacterId }?.displayName
        val name = focusCharacterName
        b.onlyCrossPurpose.text = if (onlyCross && name != null) {
            getString(R.string.duel_matches_only_cross_of, name)
        } else {
            getString(R.string.duel_matches_only_cross_purpose)
        }
    }

    private fun renderRows() {
        val shown = if (onlyDisagreements) DuelMatchLog.onlyDisagreements(rows) else rows
        adapter.submitList(shown)
        val empty = shown.isEmpty()
        binding.matchRecyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        // **왜 비었는지를 갈라 말한다** — 거르개가 켜져 있으면 *판이 없는 것*이 아니라
        // *그 조건의 판이 없는 것*이고, 처방이 다르다(거르개를 끄면 된다).
        binding.emptyText.setText(
            when {
                onlyDisagreements && rows.isNotEmpty() -> R.string.duel_matches_no_disagreements
                onlyCross -> R.string.duel_matches_no_cross
                else -> R.string.duel_matches_empty
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 거르개는 **사용자가 정한 상태**다 — 회전에 잃으면 인자가 다시 읽혀 껐던 것이 켜진다.
        outState.putBoolean(ARG_ONLY_CROSS, onlyCross)
        outState.putLong(ARG_FOCUS_CHARACTER_ID, focusCharacterId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 한 번에 받는 판 수. 넘치면 *더 보기*가 뜬다(자르되 말한다). */
        private const val PAGE = 200

        /**
         * 거르개를 켠 채로 연다 (B-208) — 순위표의 '캐릭터를 넘는 판' 고지가 넘긴다.
         *
         * 이름을 여기 한 벌만 두는 것은 **보내는 쪽과 받는 쪽이 다르게 적으면 오류도 고지도
         * 없이 아무 일이 안 일어나기 때문**이다(R-61이 같은 이유로 세워졌다 —
         * `FieldValueFixRoute`).
         */
        const val ARG_ONLY_CROSS = "onlyCross"

        /** 거르개의 범위 — 순위표 고지가 센 **그 캐릭터**. */
        const val ARG_FOCUS_CHARACTER_ID = "focusCharacterId"
    }
}
