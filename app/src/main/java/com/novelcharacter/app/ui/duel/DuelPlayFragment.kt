package com.novelcharacter.app.ui.duel

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.material.materialswitch.MaterialSwitch
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.databinding.FragmentDuelPlayBinding
import com.novelcharacter.app.databinding.ViewDuelCardBinding
import com.novelcharacter.app.util.CharacterImageLoader
import com.novelcharacter.app.util.CharacterRepresentativeImage
import com.novelcharacter.app.util.DuelCardInfo
import com.novelcharacter.app.util.DuelFieldLinks
import com.novelcharacter.app.util.DuelImageFit
import com.novelcharacter.app.util.DuelPairing
import com.novelcharacter.app.util.DuelSession
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **대결 화면** (B-104) — 둘을 보고 하나를 고른다.
 *
 * 화면이 하는 일은 셋뿐이다: 짝을 띄우고 · 누름을 판 하나로 기록하고 · 다음 짝을 띄운다.
 * *무엇을 물을 것인가*는 [DuelPairing]이, *언제 무엇으로 갈아 끼울 것인가*는 [DuelSession]이
 * 정한다 — **화면에 판단을 두지 않는 것은 이 저장소의 자동 검증이 화면을 못 보기 때문이다**
 * (「세션 착수 규칙」 4번).
 *
 * ## 누르면 곧바로 다음 짝이 뜬다
 * 한 판을 기록하면 점수가 달라지고 대기열도 달라지지만, 그 다시 계산(실측 900명에서 300ms대)이
 * 끝날 때까지 손을 멈추게 하면 원칙 04를 정면으로 어긴다. 그래서 **누름은 손에 든 대기열로
 * 즉시 나아가고**, 새 계획은 준비되는 대로 뒤에 갈아 끼운다. 갈아 끼울 때 **떠 있는 짝은
 * 바뀌지 않는다** — 고르려던 둘이 손가락 아래에서 갈리면 그 누름은 사용자가 뜻한 것이 아니다.
 *
 * ## 점수를 여기 띄우지 않는다
 * 순위표는 한 번 눌러 열리는 자리에 있고, 이 화면에는 없다. 고르는 순간에 모델의 현재 의견을
 * 보여 주면 **사용자의 판단이 그 숫자에 끌려간다** — 그러면 대결이 재는 것은 창작자의 감각이
 * 아니라 앱이 앞서 낸 점수가 된다. 대신 *왜 이 짝을 냈는가*는 보인다([DuelPairing.Reason]) —
 * 그것은 답을 암시하지 않으면서 이 판의 값어치를 설명한다(원칙 02).
 *
 * ## 카드에 무엇이 뜨는가는 [DuelCardInfo]가 정한다 (B-122)
 * 카드가 *'그림 위 스크림'*에서 **'그림 + 정보 패널'**로 갈리면서 정할 것이 늘었다 — 어느
 * 줄이 어느 구역에 가고 무엇이 접히는가. 그 판단을 여기 두지 않는 것은 위와 같은 근거다:
 * 자동 검증이 화면을 못 본다. 이 조각이 하는 일은 **정해진 것을 붙이는 것뿐**이다.
 */
class DuelPlayFragment : Fragment() {

    private var _binding: FragmentDuelPlayBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DuelViewModel by viewModels()

    private var axisId: Long = -1L
    private var axis: DuelAxis? = null
    private var characters: List<Character> = emptyList()
    private var charactersByCode: Map<String, Character> = emptyMap()

    private var session = DuelSession.State()
    private var progress: DuelPairing.Progress? = null

    /**
     * 이 축의 필드 연결 — **영향과 프로필**. 산출 필드는 [DuelCardInfo]가 표시에서 걷어낸다
     * (담고는 있어야 걷어낼 수 있다 — 엑셀로 들어온 파일이 산출 필드를 프로필에 적어 둘 수 있다).
     */
    private var links: DuelFieldLinks.Axis = DuelFieldLinks.Axis()
    private var fieldLabels: Map<String, String> = emptyMap()
    private var fieldValues: Map<String, Map<String, String>> = emptyMap()

    /**
     * 필수 트리오가 집는 **시맨틱 역할 필드의 키** — 없으면 null이고 그 조각이 빠진다.
     *
     * 라벨 문자열로 *"성별"*을 찾지 않는 것은 R-20이다. 사용자가 역할을 지정한 필드만이
     * 어긋나지 않는 출처이며, 지정된 것이 없다는 사실은 프로필 고르기 창이 말한다(R-17).
     */
    private var genderKey: String? = null
    private var ageKey: String? = null

    /**
     * 되돌리기가 집을 판 — **몇 번째 답이었는가**를 열쇠로 쓴다.
     *
     * 목록의 마지막을 집으면 안 된다. 기록은 중단 함수라 누름보다 늦게 끝나므로, 사용자가
     * 빠르게 누르면 *"방금 답한 판"*이 아직 목록에 없다 — 그때 마지막을 집으면 **엉뚱한 판을
     * 지운다.** 차례를 열쇠로 두면 집는 대상이 언제나 그 답의 판이다.
     */
    private val recordedBySeq = HashMap<Long, DuelMatch>()

    /** 아직 저장이 끝나지 않은 누름 수. 0이 아닌 동안은 되돌리기를 잠근다. */
    private var pendingRecords = 0

    private var refreshJob: Job? = null
    private var imageJobA: Job? = null
    private var imageJobB: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDuelPlayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        axisId = arguments?.getLong("axisId", -1L) ?: -1L
        if (axisId == -1L) {
            findNavController().popBackStack()
            return
        }

        // 홈의 '이어하기'가 집는 값 — **화면이 열릴 때** 적는다(끝날 때가 아니다). 사용자가
        // 앱을 강제로 끄거나 화면이 죽어도 다음에 여기로 돌아오게 하려는 것이라, 끝까지
        // 정상 종료된 경우에만 적으면 정작 필요한 경우에 비어 있다.
        DuelEntryPrefs.rememberAxis(requireContext(), axisId)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.toolbar.inflateMenu(R.menu.menu_duel_play)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_duel_axes -> { openAxisList(); true }
                R.id.action_duel_matches -> { openMatches(); true }
                R.id.action_duel_view_options -> { showViewOptions(); true }
                else -> false
            }
        }
        applyViewOptions()
        binding.cardA.root.setOnClickListener { answer(Winner.A) }
        binding.cardB.root.setOnClickListener { answer(Winner.B) }
        binding.cardA.btnExpand.setOnClickListener { showProfileSheet(left = true) }
        binding.cardB.btnExpand.setOnClickListener { showProfileSheet(left = false) }
        // **무승부**(B-114) — 저장 형식은 처음부터 받고 있었고 화면만 없었다.
        binding.btnDraw.setOnClickListener { answer(Winner.DRAW) }
        binding.btnUndo.setOnClickListener { undoLast() }
        binding.btnStandings.setOnClickListener { openStandings() }

        loadFirst()
    }

    private fun loadFirst() {
        // 순위표·상성 상세에 다녀오면 **화면만 다시 만들어지고 이 조각은 살아 있다**(뒤로가기
        // 스택). 그때 차례는 0부터 다시 세는데 옛 기록이 남아 있으면, 되돌리기가 **이미 지운
        // 판이나 남의 판을 집는다.** 대기열을 새로 받는 자리에서 함께 비운다.
        recordedBySeq.clear()
        pendingRecords = 0

        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = viewModel.axis(axisId) ?: run { findNavController().popBackStack(); return@launch }
            axis = loaded
            binding.toolbar.title = loaded.name
            characters = viewModel.participants(loaded)
            links = loaded.fieldLinks
            val fields = viewModel.characterFields(loaded.universeId)
            fieldLabels = fields.associate { it.key to it.name }
            // 역할 필드는 세계관에 **하나여야 하지만** 사용자가 둘에 같은 역할을 줄 수도 있다.
            // 그때 첫 번째를 쓰는 것은 필드 관리의 차례가 사용자가 정한 차례이기 때문이다.
            genderKey = fields.firstOrNull { SemanticRole.fromConfig(it.config) == SemanticRole.GENDER }?.key
            ageKey = fields.firstOrNull { SemanticRole.fromConfig(it.config) == SemanticRole.AGE }?.key
            // **읽을 키를 화면이 세지 않는다** — 카드가 보는 것과 갈리면 값이 있는데도 빈 줄이
            // 뜨고, 그것은 "값을 안 적었다"와 구별되지 않는다(DuelCardInfo.keysToLoad의 계약).
            fieldValues = viewModel.fieldValuesOf(
                loaded.universeId,
                characters,
                DuelCardInfo.keysToLoad(links, genderKey, ageKey)
            )
            val state = viewModel.load(loaded, characters)
            if (!isAdded) return@launch
            charactersByCode = state.charactersByCode
            codeById = state.state.records.codeById
            progress = state.state.plan.progress
            session = DuelSession.begin(state.state.plan)
            render()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 한 판
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 한 판의 답 — 왼쪽 · 오른쪽 · **비슷함**(B-114).
     *
     * 무승부를 `null` 코드 하나로 다루지 않고 종류로 두는 것은, 코드가 null인 상태가
     * *"비겼다"*와 *"참가자를 못 찾았다"* 둘 다일 수 있기 때문이다 — 뒤엣것은 답이 아니라
     * 오류이고 판으로 남아서는 안 된다.
     */
    private enum class Winner { A, B, DRAW }

    private fun answer(winner: Winner) {
        val current = session.current ?: return
        val axis = this.axis ?: return
        val (aCode, bCode) = codesOf(current) ?: return
        val winnerCode = when (winner) {
            Winner.A -> aCode
            Winner.B -> bCode
            // 순수 계층이 0.5승으로 처리한다(DuelMatch.winnerCode의 계약).
            Winner.DRAW -> null
        }

        // 화면은 기다리지 않는다 — 다음 짝을 먼저 올리고 기록·재계산은 뒤에서 돈다.
        session = DuelSession.answer(session)
        val seq = session.seq
        pendingRecords++
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            val row = viewModel.record(axis.id, aCode, bCode, winnerCode)
            pendingRecords--
            if (!isAdded) return@launch
            if (row == null) {
                // 저장소가 거절한 값(승자가 두 참가자 중 어느 쪽도 아님)은 조용히 넘기지 않는다.
                android.widget.Toast.makeText(
                    requireContext(), R.string.duel_record_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
                updateUndoButton()
                return@launch
            }
            recordedBySeq[seq] = row
            updateUndoButton()
            scheduleRefresh()
        }
    }

    /**
     * 되돌리기는 **저장이 끝난 뒤에만** 열린다.
     *
     * 저장이 도는 사이에 되돌리면 지울 판이 아직 없어, 뒤늦게 들어온 행이 되돌린 뒤에도
     * 남는다 — 화면에는 취소했는데 기록에는 남는 어긋남이다.
     */
    private fun updateUndoButton() {
        binding.btnUndo.isEnabled = session.canUndo && pendingRecords == 0
    }

    /**
     * 층 B ①(*"잘못 눌렀다"*) — **판을 지우고 그 짝을 다시 묻는다.**
     *
     * 지우기만 하면 그 짝은 답이 없는 채로 대기열 뒤편에 남아, 사용자는 자기가 무엇을
     * 고치려 했는지 잃는다. 점수는 다시 적합하면 그것이 정확한 답이다(BT는 결과 집합의 함수라
     * 한 판을 빼는 데 뒤의 판을 다시 계산할 필요가 없다 — 이 모델을 고른 근거 셋 중 하나).
     */
    private fun undoLast() {
        if (pendingRecords > 0) return
        val last = session.lastAnswered ?: return
        val match = recordedBySeq.remove(last.seq) ?: return
        session = DuelSession.undo(session)
        updateUndoButton()
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.undo(match)
            if (!isAdded) return@launch
            scheduleRefresh()
        }
    }

    /**
     * 새 대기열을 뒤에서 계산해 갈아 끼운다.
     *
     * 계산을 띄운 시점의 차례를 함께 넘기는 것이 요점이다 — 계산이 도는 사이에 사용자가 더
     * 누르면 그 답을 이 계획은 모르므로, [DuelSession.refresh]가 그만큼만 걸러 낸다.
     */
    private fun scheduleRefresh() {
        val axis = this.axis ?: return
        refreshJob?.cancel()
        val planSeq = session.seq
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val loaded = viewModel.load(axis, characters)
            if (!isAdded) return@launch
            charactersByCode = loaded.charactersByCode
            codeById = loaded.state.records.codeById
            progress = loaded.state.plan.progress
            session = DuelSession.refresh(session, loaded.state.plan, planSeq)
            render()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 그리기
    // ──────────────────────────────────────────────────────────────────────

    private fun codesOf(candidate: DuelPairing.Candidate): Pair<String, String>? {
        val a = codeById[candidate.pair.lowId] ?: return null
        val b = codeById[candidate.pair.highId] ?: return null
        return a to b
    }

    /**
     * 순수 계층의 id → 참가자 코드. **다시 계산할 때마다 갈아 끼워도 안전하다.**
     *
     * `DuelRecords.resolve`는 넘겨받은 참가자 목록 순서대로 1번부터 id를 매기고, 그 뒤에
     * 나타나는 사라진 참가자에게 뒷번호를 준다. 이 화면은 [characters]를 **처음 한 번만**
     * 읽어 다시 계산에도 같은 목록을 넘기므로 살아 있는 참가자의 id가 움직이지 않는다.
     * 뒷번호(사라진 참가자)는 애초에 대기열에 오르지 않는다 — 참가자가 아니라 점수가 없고,
     * 짝은 점수가 있는 것들 사이에서만 만들어진다. 그래서 **여기 담기는 id는 언제나
     * 같은 참가자를 가리킨다**(R-1이 막으려는 오배정이 생길 자리가 없다).
     */
    private var codeById: Map<Long, String> = emptyMap()

    private fun render() {
        val current = session.current
        renderProgress()
        updateUndoButton()

        if (current == null) {
            binding.duelArea.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.reasonText.visibility = View.GONE
            binding.btnDraw.isEnabled = false
            binding.emptyTitle.text = getString(
                if (characters.size < 2) R.string.duel_need_two else R.string.duel_queue_empty
            )
            binding.emptyHint.text = getString(
                if (characters.size < 2) R.string.duel_need_two_hint else R.string.duel_queue_empty_hint
            )
            return
        }

        val codes = codesOf(current)
        if (codes == null) {
            binding.duelArea.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.btnDraw.isEnabled = false
            binding.emptyTitle.text = getString(R.string.duel_queue_empty)
            binding.emptyHint.text = getString(R.string.duel_queue_empty_hint)
            return
        }

        binding.duelArea.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.reasonText.visibility = View.VISIBLE
        binding.reasonText.text = getString(reasonTextOf(current.reason))

        val a = charactersByCode[codes.first]
        val b = charactersByCode[codes.second]
        cardA = cardOf(a, codes.first)
        cardB = cardOf(b, codes.second)
        paintCard(binding.cardA, cardA)
        paintCard(binding.cardB, cardB)
        // 비슷함은 짝이 떠 있을 때만 뜻이 있다 — 빈 화면에서 누르면 아무 일도 안 일어난다.
        binding.btnDraw.isEnabled = true
        imageJobA = loadPortrait(a, binding.cardA.cardImage, imageJobA)
        imageJobB = loadPortrait(b, binding.cardB.cardImage, imageJobB)
    }

    /** 지금 떠 있는 카드 — 펼침 시트가 다시 계산하지 않고 이것을 연다(두 자리가 갈릴 수 없다). */
    private var cardA = DuelCardInfo.Card(name = "")
    private var cardB = DuelCardInfo.Card(name = "")

    /** 이 참가자의 카드 — **무엇이 뜨는가는 [DuelCardInfo]가 정한다.** */
    private fun cardOf(character: Character?, code: String): DuelCardInfo.Card =
        DuelCardInfo.build(
            name = character?.displayName ?: getString(R.string.duel_unknown_participant),
            values = fieldValues[code].orEmpty(),
            labels = fieldLabels,
            links = links,
            genderKey = genderKey,
            ageKey = ageKey,
            showProfiles = showProfile,
            showInfluences = showInfluence
        )

    /** 정해진 것을 붙인다 — 여기서 정하는 것은 **말글뿐**이다(단위·빈 값 표시). */
    private fun paintCard(views: ViewDuelCardBinding, card: DuelCardInfo.Card) {
        views.cardName.text = card.name

        views.cardTrio.text = trioTextOf(card)
        views.cardTrio.visibility = if (card.hasTrio) View.VISIBLE else View.GONE

        views.profileContainer.removeAllViews()
        val inflater = LayoutInflater.from(views.root.context)
        for (line in card.profiles) {
            val row = inflater.inflate(R.layout.item_duel_card_profile, views.profileContainer, false)
            row.findViewById<android.widget.TextView>(R.id.profileLabel).text = line.label
            row.findViewById<android.widget.TextView>(R.id.profileValue).text = line.value
            views.profileContainer.addView(row)
        }
        views.profileMore.visibility = if (card.profileOverflow > 0) View.VISIBLE else View.GONE
        if (card.profileOverflow > 0) {
            views.profileMore.text = getString(R.string.duel_profile_more, card.profileOverflow)
        }
        // 눌러도 같은 것만 나오면 마찰이다 — 접힌 것이 있을 때만 띄운다.
        views.btnExpand.visibility = if (card.canExpand) View.VISIBLE else View.GONE

        views.influenceContainer.removeAllViews()
        views.influenceDivider.visibility =
            if (card.influences.isEmpty()) View.GONE else View.VISIBLE
        for (line in card.influences) {
            val row = inflater.inflate(R.layout.item_duel_card_influence, views.influenceContainer, false)
            row.findViewById<android.widget.TextView>(R.id.influenceRank).text = line.rank.toString()
            row.findViewById<android.widget.TextView>(R.id.influenceLabel).text = line.label
            // 값을 안 적은 필드도 **줄이 남는다** — 두 카드의 자리가 맞아야 견줄 수 있다.
            row.findViewById<android.widget.TextView>(R.id.influenceValue).text =
                line.value.ifEmpty { getString(R.string.duel_field_value_empty) }
            views.influenceContainer.addView(row)
        }
    }

    /** `여 · 17세` — 조각이 하나뿐이면 그것만, 둘 다 없으면 빈 글이다(줄 자체가 사라진다). */
    private fun trioTextOf(card: DuelCardInfo.Card): String = listOfNotNull(
        card.gender,
        card.age?.let { getString(R.string.duel_trio_age, it) }
    ).joinToString(getString(R.string.duel_trio_separator))

    /**
     * 전체 프로필 — 카드에서 접힌 것까지 (원칙 04의 이중 경로: 러프하게 보고 필요하면 정밀히).
     *
     * **값이 빈 줄도 보인다.** 좁은 카드에서는 그것이 자리 낭비지만 여기서는 *"그 필드가
     * 걸려 있는데 이 캐릭터는 비었다"*가 알 값어치가 있는 사실이다(개발 의도 2번).
     */
    private fun showProfileSheet(left: Boolean) {
        val card = if (left) cardA else cardB
        val context = requireContext()
        val scroll = cappedScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        if (card.hasTrio) {
            val trio = LayoutInflater.from(context)
                .inflate(R.layout.item_duel_card_profile, body, false)
            trio.findViewById<android.widget.TextView>(R.id.profileLabel)
                .text = getString(R.string.duel_profile_trio_label)
            trio.findViewById<android.widget.TextView>(R.id.profileValue).text = trioTextOf(card)
            body.addView(trio)
        }
        for (line in card.allProfiles) {
            val row = LayoutInflater.from(context)
                .inflate(R.layout.item_duel_card_profile, body, false)
            row.findViewById<android.widget.TextView>(R.id.profileLabel).text = line.label
            row.findViewById<android.widget.TextView>(R.id.profileValue).text =
                line.value.ifEmpty { getString(R.string.duel_field_value_empty) }
            body.addView(row)
        }
        scroll.addView(body)

        MaterialAlertDialogBuilder(context)
            .setTitle(card.name)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun renderProgress() {
        val value = progress ?: return
        binding.progressBar.progress = (value.settledPercent * 10).toInt().coerceIn(0, 1000)
        val base = getString(
            R.string.duel_progress,
            value.played, value.folded, value.remaining, value.total
        )
        // 상한에 걸려 덜 훑었으면 그 사실을 함께 말한다 — 조용히 자르지 않는다.
        binding.progressText.text = if (value.exact) {
            base
        } else {
            base + " " + getString(R.string.duel_progress_capped)
        }
    }

    private fun reasonTextOf(reason: DuelPairing.Reason): Int = when (reason) {
        DuelPairing.Reason.NEW -> R.string.duel_reason_new
        DuelPairing.Reason.CLOSE -> R.string.duel_reason_close
        DuelPairing.Reason.UNCERTAIN -> R.string.duel_reason_uncertain
        DuelPairing.Reason.RECHECK -> R.string.duel_reason_recheck
    }

    // ──────────────────────────────────────────────────────────────────────
    // 보기 설정 — 카드 배치와 그림 맞춤
    // ──────────────────────────────────────────────────────────────────────

    private var cardLayout = DuelImageFit.Layout.SIDE_BY_SIDE
    private var imageFit = DuelImageFit.Fit.FILL_TOP
    private var showProfile = true
    private var showInfluence = true

    /**
     * 저장된 보기 설정을 화면에 얹는다 — **배치를 가르는 단 하나의 자리**.
     *
     * **기본이 '나란히'인 것은 그림 규격 때문이다** — 창작 캐릭터 그림은 대개 세로로 길고,
     * 위아래로 놓으면 카드가 가로로 넓어져 그 그림의 위아래가 잘린다(얼굴이 위에 있다).
     * 나란히 놓으면 카드가 세로로 길어져 같은 그림이 훨씬 덜 잘린다.
     *
     * ## 카드 **안**도 여기서 갈린다 (B-122)
     * 위아래(STACKED)로 두 카드를 쌓으면 카드 하나가 가로로 넓고 낮아져, 그림 아래로 정보
     * 패널까지 세로로 이어 붙일 높이가 없다. 그래서 그 배치에서는 **카드 안을 가로로**
     * 돌린다 — 그림(좌 40%) + 정보 패널(우). 접기 규칙(프로필 넷 + *"외 N개"*)은 그대로다.
     *
     * ## k지선다(B-115)가 열리는 자리도 여기다
     * 카드는 한 벌(`view_duel_card.xml`)을 두 번 include한 것이라 셋·넷으로 늘어도 카드
     * 자체는 그대로다. 그 슬라이스가 바꿀 것은 **담는 그릇**([FragmentDuelPlayBinding.duelArea]를
     * 2×2 격자로)과 접기 규칙(3장 이상이면 이름+트리오까지)뿐이며, 그 둘이 다 이 함수 안에 있다.
     */
    private fun applyViewOptions() {
        val context = requireContext()
        cardLayout = DuelViewPrefs.layout(context)
        imageFit = DuelViewPrefs.fit(context)
        showProfile = DuelViewPrefs.showProfile(context)
        showInfluence = DuelViewPrefs.showInfluence(context)

        val sideBySide = cardLayout == DuelImageFit.Layout.SIDE_BY_SIDE
        binding.duelArea.orientation =
            if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        fun stretch(view: View) {
            val lp = view.layoutParams as LinearLayout.LayoutParams
            if (sideBySide) {
                lp.width = 0
                lp.height = LinearLayout.LayoutParams.MATCH_PARENT
            } else {
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.height = 0
            }
            lp.weight = 1f
            view.layoutParams = lp
        }

        for (card in listOf(binding.cardA, binding.cardB)) {
            stretch(card.root)
            applyCardInner(card, sideBySide)
        }

        val vsParams = binding.versusLabel.layoutParams as LinearLayout.LayoutParams
        vsParams.gravity = if (sideBySide) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        binding.versusLabel.layoutParams = vsParams

        // 이미 붙어 있는 그림도 새 규칙으로 다시 놓는다 — 설정을 바꾼 뒤 다음 판까지 기다리게
        // 하면 사용자는 그 설정이 먹었는지 알 수 없다.
        // **아직 축을 읽기 전이면 그리지 않는다** — 그때 그리면 "캐릭터가 둘 이상이어야 합니다"가
        // 한 번 번쩍이고 곧 짝으로 바뀐다.
        if (axis != null) render()
    }

    /**
     * 카드 안의 그림·패널 배치.
     *
     * 세로(카드가 세로로 길 때)에서는 **패널이 필요한 만큼 갖고 그림이 나머지를 전부** 갖는다 —
     * 비율을 숫자로 박으면 프로필을 안 건 축에서 그림이 공연히 작아진다. 가로(카드가 낮을 때)
     * 에서는 폭을 4:6으로 나눈다: 그림은 세로로 길어야 얼굴이 살고, 패널은 값 줄이 잘리지
     * 않을 만큼 넓어야 한다.
     */
    private fun applyCardInner(card: ViewDuelCardBinding, sideBySide: Boolean) {
        card.cardInner.orientation =
            if (sideBySide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val imageParams = card.cardImage.layoutParams as LinearLayout.LayoutParams
        val panelParams = card.cardPanel.layoutParams as LinearLayout.LayoutParams
        if (sideBySide) {
            imageParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            imageParams.height = 0
            imageParams.weight = 1f
            panelParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            panelParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            panelParams.weight = 0f
        } else {
            imageParams.width = 0
            imageParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            imageParams.weight = 4f
            panelParams.width = 0
            panelParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            panelParams.weight = 6f
        }
        card.cardImage.layoutParams = imageParams
        card.cardPanel.layoutParams = panelParams
    }

    private fun showViewOptions() {
        val context = requireContext()
        var pickedLayout = cardLayout
        var pickedFit = imageFit

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_duel_view_options, null)
        val layoutGroup = view.findViewById<RadioGroup>(R.id.groupLayout)
        val fitGroup = view.findViewById<RadioGroup>(R.id.groupFit)
        val profileSwitch = view.findViewById<MaterialSwitch>(R.id.switchShowProfile)
        val influenceSwitch = view.findViewById<MaterialSwitch>(R.id.switchShowInfluence)
        layoutGroup.check(
            if (pickedLayout == DuelImageFit.Layout.SIDE_BY_SIDE) R.id.optionSideBySide else R.id.optionStacked
        )
        fitGroup.check(
            if (pickedFit == DuelImageFit.Fit.WHOLE) R.id.optionWhole else R.id.optionFillTop
        )
        profileSwitch.isChecked = showProfile
        influenceSwitch.isChecked = showInfluence
        layoutGroup.setOnCheckedChangeListener { _, id ->
            pickedLayout = if (id == R.id.optionSideBySide) {
                DuelImageFit.Layout.SIDE_BY_SIDE
            } else {
                DuelImageFit.Layout.STACKED
            }
        }
        fitGroup.setOnCheckedChangeListener { _, id ->
            pickedFit = if (id == R.id.optionWhole) DuelImageFit.Fit.WHOLE else DuelImageFit.Fit.FILL_TOP
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.duel_view_options)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                DuelViewPrefs.save(
                    context,
                    pickedLayout,
                    pickedFit,
                    profileSwitch.isChecked,
                    influenceSwitch.isChecked
                )
                if (isAdded) applyViewOptions()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 그림 한 장을 카드에 놓는다 — [DuelImageFit.Fit]이 정한 대로.
     *
     * `centerCrop`을 쓰지 않는 것이 요점이다. 그것은 넘치는 축의 **양끝을 고르게** 잘라 내는데,
     * 세로로 긴 인물화에서 잘려 나가는 위쪽이 곧 얼굴이다. [DuelImageFit.topCrop]은 같은 배율로
     * 채우되 **위쪽 끝을 기준**으로 놓는다.
     */
    private fun placeBitmap(target: ImageView, bitmap: Bitmap) {
        target.setImageBitmap(bitmap)
        if (imageFit == DuelImageFit.Fit.WHOLE) {
            target.scaleType = ImageView.ScaleType.FIT_CENTER
            return
        }
        target.scaleType = ImageView.ScaleType.MATRIX
        val place = {
            val crop = DuelImageFit.topCrop(target.width, target.height, bitmap.width, bitmap.height)
            if (crop != null) {
                target.imageMatrix = Matrix().apply {
                    setScale(crop.scale, crop.scale)
                    postTranslate(crop.dx, crop.dy)
                }
            }
        }
        // 아직 재지 않은 화면에서는 폭·높이가 0이라 계산할 것이 없다 — 그때는 배치가 끝난 뒤에 놓는다.
        if (target.width > 0 && target.height > 0) place() else target.post { if (isAdded) place() }
    }

    /** 대표 이미지 한 장 — 판정은 `CharacterRepresentativeImage`가 단일 소스다(B-103 D2·D4). */
    private fun loadPortrait(character: Character?, target: ImageView, previous: Job?): Job? {
        previous?.cancel()
        target.scaleType = ImageView.ScaleType.FIT_CENTER
        target.setImageResource(R.drawable.ic_character_placeholder)
        if (character == null) return null

        val paths: List<String> = try {
            gson.fromJson(character.imagePaths, imagePathsType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (paths.isEmpty()) return null

        val pick = CharacterRepresentativeImage.pickFrom(
            paths, character.representativeImagePath, character.id, character.id
        )
        val path = paths[pick.index.coerceAtLeast(0) % paths.size]
        val filesDir = requireContext().filesDir
        val boundCode = character.code
        return viewLifecycleOwner.lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                CharacterImageLoader.decodeThumbnail(path, filesDir, 512)
            }
            // 뒤늦게 도착한 이미지가 이미 넘어간 짝에 붙지 않게 한다.
            if (isAdded && bitmap != null && boundCode in currentCodes()) placeBitmap(target, bitmap)
        }
    }

    private fun currentCodes(): Set<String> {
        val current = session.current ?: return emptySet()
        val codes = codesOf(current) ?: return emptySet()
        return setOf(codes.first, codes.second)
    }

    private fun openStandings() {
        val bundle = Bundle().apply { putLong("axisId", axisId) }
        findNavController().navigateSafe(R.id.duelPlayFragment, R.id.duelStandingsFragment, bundle)
    }

    /** 쌓은 판을 보고 고치는 자리 — 되돌리기가 집지 못하는 *어제 그 판*이 여기 있다. */
    private fun openMatches() {
        val bundle = Bundle().apply { putLong("axisId", axisId) }
        findNavController().navigateSafe(R.id.duelPlayFragment, R.id.duelMatchesFragment, bundle)
    }

    /** 홈에서 바로 들어온 경우 축 목록에 닿을 길이 여기뿐이다(뒤로가기는 홈으로 간다). */
    private fun openAxisList() {
        val universeId = axis?.universeId ?: return
        val bundle = Bundle().apply { putLong("universeId", universeId) }
        findNavController().navigateSafe(R.id.duelPlayFragment, R.id.duelAxisListFragment, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob = null
        imageJobA = null
        imageJobB = null
        _binding = null
    }

    companion object {
        private val gson = Gson()
        private val imagePathsType = object : TypeToken<List<String>>() {}.type
    }
}
