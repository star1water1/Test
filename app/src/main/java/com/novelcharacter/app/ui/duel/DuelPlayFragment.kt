package com.novelcharacter.app.ui.duel

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
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
import com.novelcharacter.app.util.DuelCardGrid
import com.novelcharacter.app.util.DuelCardInfo
import com.novelcharacter.app.util.DuelSystemFields
import com.novelcharacter.app.util.DuelFieldLinks
import com.novelcharacter.app.util.DuelImageFit
import com.novelcharacter.app.util.DuelImageParticipants
import com.novelcharacter.app.util.DuelPairing
import com.novelcharacter.app.util.DuelRound
import com.novelcharacter.app.util.DuelSession
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **대결 화면** (B-104) — 올라온 것들을 보고 하나를 고른다.
 *
 * 화면이 하는 일은 셋뿐이다: 후보를 띄우고 · 누름을 판으로 기록하고 · 다음을 띄운다.
 * *무엇을 물을 것인가*는 [DuelPairing]이, *언제 무엇으로 갈아 끼울 것인가*는 [DuelSession]이,
 * *한 화면에 누구를 올리고 그 누름이 어떤 판이 되는가*는 [DuelRound]가, *그 카드를 어느 자리에
 * 놓는가*는 [DuelCardGrid]가 정한다 — **화면에 판단을 두지 않는 것은 이 저장소의 자동 검증이
 * 화면을 못 보기 때문이다**(「세션 착수 규칙」 4번).
 *
 * ## 1:1은 `k=2`다 (B-115)
 * 한 화면에 둘·셋·넷을 올릴 수 있고(보기 설정), **둘일 때가 특수한 경우가 아니다.** 고르면
 * `k−1`판이, '비슷함'이면 전 조합이 남는다는 한 규칙이 셋 다 낸다 — `k=2`를 넣으면 종전과
 * 글자 그대로 같은 것이 나오므로 이 조각에 *"둘일 때는 이렇게"*라는 갈래가 없다.
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

    /**
     * 이 화면 진입의 대표 이미지 추첨 시드 — 형제 열넷과 같은 주기다(B-103·B-106).
     * 같은 화면 안에서는 재바인드·판 전환에 그림이 튀지 않고, 나갔다 들어오면 바뀐다.
     */
    private val imageSeed: Long = CharacterRepresentativeImage.newSeed()
    private var characters: List<Character> = emptyList()
    private var charactersByCode: Map<String, Character> = emptyMap()

    /**
     * 이미지 축일 때 **누구의 이미지를 겨루는가** (설계 13장). 캐릭터 축이면 -1이다.
     *
     * 이미지 축은 캐릭터마다 따로 논다(사용자 확정) — 그래서 이 화면은 축 하나가 아니라
     * **(축, 캐릭터) 하나**를 연다. 인자를 받지 못하면 고르는 화면으로 돌려보낸다.
     */
    private var characterId: Long = -1L

    /**
     * 이미지 축의 참가자 현황 — 참가자 코드(=경로)와 진행률의 출처.
     *
     * **소유 표까지 함께 들고 있는 것이 요점이다**(B-175). 다시 계산은 한 판 누를 때마다 도는데,
     * 몫 가르기가 그 표를 필요로 한다 — 그때마다 새로 만들면 세계관 그림 수만큼의 파일 시스템
     * 호출이 **판마다** 붙는다.
     */
    private var imageTarget: DuelViewModel.ImageTarget? = null

    private val isImageAxis: Boolean get() = axis?.isImageAxis == true

    /** 후보 필터의 결과 (B-168) — 대기열·빈 화면 문구·필터 줄이 이것을 본다. */
    private var roster: DuelViewModel.Roster? = null

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
     * 한 번의 누름이 남긴 것 — 되돌리기가 집는 단위 (B-115).
     *
     * @property groupId 판이 둘 이상이라 묶인 경우의 묶음 값. 판이 하나면 null이고 그때는
     *   [matches]의 그 하나를 지운다([DuelRepository.recordGroup]의 계약).
     */
    private data class Recorded(val groupId: String?, val matches: List<DuelMatch>)

    /**
     * 되돌리기가 집을 판 — **몇 번째 답이었는가**를 열쇠로 쓴다.
     *
     * 목록의 마지막을 집으면 안 된다. 기록은 중단 함수라 누름보다 늦게 끝나므로, 사용자가
     * 빠르게 누르면 *"방금 답한 판"*이 아직 목록에 없다 — 그때 마지막을 집으면 **엉뚱한 판을
     * 지운다.** 차례를 열쇠로 두면 집는 대상이 언제나 그 답의 판이다.
     */
    private val recordedBySeq = HashMap<Long, Recorded>()

    /** 아직 저장이 끝나지 않은 누름 수. 0이 아닌 동안은 되돌리기를 잠근다. */
    private var pendingRecords = 0

    private var refreshJob: Job? = null

    /** 슬롯마다 도는 그림 읽기 — 슬롯 번호가 곧 자리다([DuelCardGrid]). */
    private val imageJobs = arrayOfNulls<Job>(SLOT_COUNT)

    /** 화면에 떠 있는 한 판 — 누구를 올렸고 머리 짝이 무엇인가. */
    private var round: DuelRound.Round? = null

    /** [round]의 참가자와 **같은 차례**의 카드. 펼침 시트가 다시 계산하지 않고 이것을 연다. */
    private var cards: List<DuelCardInfo.Card> = emptyList()

    /** 지금 쓰는 슬롯 배치. `슬롯 번호 → 참가자 차례` — 카드 누름이 누구를 가리키는지의 출처다. */
    private var slotToMember: Map<Int, Int> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDuelPlayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        axisId = arguments?.getLong("axisId", -1L) ?: -1L
        characterId = arguments?.getLong("characterId", -1L) ?: -1L
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
        // 슬롯은 넷이고 자리가 고정이다 — 누름이 **누구**를 가리키는지는 [slotToMember]가 든다
        // (배치가 바뀔 때마다 리스너를 다시 걸면 그 사이의 누름이 옛 참가자로 간다).
        cardSlots.forEachIndexed { slot, views ->
            views.root.setOnClickListener { pickSlot(slot) }
            views.btnExpand.setOnClickListener { expandSlot(slot) }
        }
        // **무승부**(B-114) — 저장 형식은 처음부터 받고 있었고 화면만 없었다.
        // 셋 이상에서는 *"다 비슷함"*이 되어 전 조합이 무승부로 남는다(B-115 · [DuelRound]).
        binding.btnDraw.setOnClickListener { answer(null) }
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
            if (loaded.isImageAxis) { loadImageFirst(loaded); return@launch }
            val currentRoster = viewModel.roster(loaded)
            roster = currentRoster
            characters = currentRoster.participants
            links = loaded.fieldLinks
            val fields = viewModel.characterFields(loaded.universeId)
            // 시스템 열의 이름도 함께 든다(B-167) — 없으면 이명 줄의 라벨이 `sys:another_name`이 된다
            // (`DuelCardInfo.build`가 *"없는 키는 키를 그대로 쓴다"*로 정해 둔 자리).
            // 방금 읽은 목록을 넘긴다 — 아래 역할 필드도 그것을 보므로 두 번 훑을 까닭이 없다.
            fieldLabels = viewModel.linkLabels(fields)
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
            val state = viewModel.load(loaded, characters, currentRoster.candidateCodes)
            if (!isAdded) return@launch
            charactersByCode = state.charactersByCode
            codeById = state.state.records.codeById
            progress = state.state.plan.progress
            session = DuelSession.begin(state.state.plan)
            render()
        }
    }

    /**
     * 이미지 축의 짐 싣기 — **(축, 캐릭터) 하나**를 연다 (설계 13장).
     *
     * 캐릭터 축이 하는 일 중 여기서 **하지 않는 것 넷**이 있고 전부 같은 이유다 —
     * 두 참가자가 **같은 캐릭터의 그림**이라 그 값이 양쪽에 똑같이 뜬다:
     * 필드 연결(영향·프로필)·역할 필드(성별·나이)·필드값 읽기·후보 필터.
     * 붙여 봐야 두 카드에 같은 글이 두 번 뜰 뿐이고, 그것은 고르는 데 아무 재료도 되지 않는다
     * (원칙 02가 금지하는 겉핥기). **읽지도 않으므로 질의도 늘지 않는다.**
     */
    private suspend fun loadImageFirst(loaded: DuelAxis) {
        val target = if (characterId > 0) viewModel.imageTarget(loaded, characterId) else null
        val entry = target?.entry
        if (target == null || entry == null) {
            // 캐릭터를 정하지 못하면 고르는 화면이 먼저다 — 빈 대결 화면을 띄우면
            // 사용자는 "고장 났다"로 읽는다. 어디로 가야 하는지를 화면이 말한다.
            if (isAdded) openImageCharacters()
            return
        }
        imageTarget = target
        roster = null
        characters = emptyList()
        links = DuelFieldLinks.Axis()
        fieldLabels = emptyMap()
        fieldValues = emptyMap()
        genderKey = null
        ageKey = null
        // 제목이 축 이름만이면 **누구의 이미지를 보고 있는지 알 수 없다** — 캐릭터마다 따로
        // 도는 대결이라 그 이름이 제목의 절반이다.
        binding.toolbar.title = getString(R.string.duel_image_play_title, loaded.name, entry.name)

        val state = viewModel.loadImages(loaded, target)
        if (!isAdded) return
        if (state == null) { render(); return }
        charactersByCode = emptyMap()
        codeById = state.state.records.codeById
        progress = state.state.plan.progress
        session = DuelSession.begin(state.state.plan)
        render()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 한 판
    // ──────────────────────────────────────────────────────────────────────

    /** 카드를 눌렀다 — 그 슬롯에 누가 있는지는 [slotToMember]가 든다. */
    private fun pickSlot(slot: Int) {
        val index = slotToMember[slot] ?: return
        val member = round?.members?.getOrNull(index) ?: return
        answer(member)
    }

    /**
     * 한 화면의 답 — 고른 참가자, **null이면 '비슷함'**.
     *
     * 무엇이 판으로 남는가는 [DuelRound.outcomes]가 정한다: 고르면 `k−1`판, 비슷함이면
     * 전 조합. **1:1도 같은 길을 탄다** — `k=2`를 넣으면 종전과 글자 그대로 같은 것이 나오고,
     * 그래서 이 화면에 *"둘일 때는 이렇게, 셋일 때는 저렇게"*라는 갈래가 없다.
     */
    private fun answer(winnerId: Long?) {
        val axis = this.axis ?: return
        val shown = round ?: return
        val outcomes = DuelRound.outcomes(shown, winnerId)
        if (outcomes.isEmpty()) return

        val rows = ArrayList<Triple<String, String, String?>>(outcomes.size)
        for (outcome in outcomes) {
            val a = codeById[outcome.aId]
            val b = codeById[outcome.bId]
            // 코드를 못 찾으면 **아무것도 적지 않는다.** 그 한 판만 빼면 사용자가 한 번 누른
            // 일이 절반만 남고, 되돌리기가 지울 대상도 절반이 된다(반쪽 묶음).
            if (a == null || b == null) return
            val winnerCode = if (outcome.winnerId == null) null else codeById[outcome.winnerId] ?: return
            rows += Triple(a, b, winnerCode)
        }

        // 화면은 기다리지 않는다 — 다음 짝을 먼저 올리고 기록·재계산은 뒤에서 돈다.
        // 이 화면이 함께 답한 짝을 알려 주지 않으면 방금 고른 얼굴이 곧바로 다시 뜬다.
        session = DuelSession.answer(session, DuelRound.handledPairs(shown, winnerId))
        val seq = session.seq
        pendingRecords++
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            val saved = viewModel.recordGroup(axis.id, rows)
            pendingRecords--
            if (!isAdded) return@launch
            if (saved.isEmpty()) {
                // 저장소가 거절한 값(승자가 두 참가자 중 어느 쪽도 아님)은 조용히 넘기지 않는다.
                android.widget.Toast.makeText(
                    requireContext(), R.string.duel_record_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
                updateUndoButton()
                return@launch
            }
            recordedBySeq[seq] = Recorded(saved.first().groupId, saved)
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

        // **몇 판이 사라지는지 버튼이 말한다**(B-115). 묶음 되돌리기는 판 하나가 아니라
        // 그 화면이 낸 것 전부를 지우므로, 문구가 '방금 그거'에 머물면 사용자는 판 하나만
        // 지워질 줄 알고 누른다. 수는 **실제로 적힌 판**에서 든다 — 화면에 뜬 카드 수로
        // 세면 저장소가 거절한 경우에 틀린 수를 말한다.
        val last = session.lastAnswered
        val recorded = last?.let { recordedBySeq[it.seq] }
        when {
            last == null -> binding.btnUndo.setText(R.string.duel_undo)
            recorded != null && recorded.matches.size > 1 ->
                binding.btnUndo.text = getString(R.string.duel_undo_group, recorded.matches.size)
            recorded != null -> binding.btnUndo.setText(R.string.duel_undo)
            // 아직 저장 중 — 잠긴 버튼의 글자가 깜빡이지 않게 그대로 둔다.
            pendingRecords > 0 -> Unit
            // 저장이 거절됐다(위 [answer]의 고지). **지울 판이 없으므로 묶음 문구를 남기면
            // 거짓말이 된다** — 되돌리기 자체는 여전히 돌아야 하고(그 짝을 다시 묻는다),
            // 다만 사라지는 판은 0이다.
            else -> binding.btnUndo.setText(R.string.duel_undo)
        }
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
        val recorded = recordedBySeq.remove(last.seq)
        session = DuelSession.undo(session)
        updateUndoButton()
        render()

        // **저장이 거절된 답에도 되돌리기는 돈다 — 지울 것이 없을 뿐이다.**
        // 종전에는 여기서 그냥 나갔는데(`?: return`), 그러면 버튼은 켜져 있는데 눌러도
        // 아무 일이 없는 상태가 남는다 — 사용자는 자기 누름이 먹었는지 알 길이 없고,
        // 되돌아오지 않은 그 짝을 다시 물을 방법도 없다. 다시 계산은 띄우지 않는다:
        // DB가 그대로라 새 계획이 옛 계획과 같고, 짝은 [DuelSession.undo]가 이미 되돌렸다.
        if (recorded == null) return

        viewLifecycleOwner.lifecycleScope.launch {
            // **묶음이면 묶음 값으로 지운다** — 들고 있는 목록으로 지우면 그 사이 기록
            // 화면에서 한 판이 손편집·삭제됐을 때 남은 것을 못 지워, 취소한 화면의 절반이
            // 살아남는다. 판이 하나면 묶음 값이 없고(단독 판) 그것 하나를 지운다.
            val groupId = recorded.groupId
            if (groupId != null) {
                viewModel.undoGroup(groupId)
            } else {
                recorded.matches.forEach { viewModel.undo(it) }
            }
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
            // **참가자 집합을 어디서 얻는가가 축마다 다르다.** 이미지 축에서 [characters]는
            // 언제나 비어 있으므로(그쪽 참가자는 캐릭터가 아니라 경로다) 캐릭터 축의 경로를
            // 그대로 타면 **참가자 0으로 다시 적합해 대기열이 통째로 마른다** — 화면에서는
            // *한 판 누르면 그대로 빈 화면이 되는* 것으로 나타난다.
            val loaded = if (axis.isImageAxis) {
                val target = imageTarget ?: return@launch
                viewModel.loadImages(axis, target) ?: return@launch
            } else {
                viewModel.load(axis, characters, roster?.candidateCodes)
            }
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

    /** 머리 짝의 두 참가자가 코드로 풀리는가 — 안 풀리면 낼 판이 없는 것과 같다. */
    private fun leadResolved(candidate: DuelPairing.Candidate): Boolean =
        codeById.containsKey(candidate.pair.lowId) && codeById.containsKey(candidate.pair.highId)

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
            round = null
            cards = emptyList()
            binding.duelArea.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.reasonText.visibility = View.GONE
            binding.btnDraw.isEnabled = false
            // 왜 빈 화면인가를 가른다(B-168) — 필터가 후보를 좁혀 짝이 없는 것과 캐릭터 자체가
            // 모자란 것은 사용자가 할 일이 다르다(필터를 고치러 vs 캐릭터를 만들러).
            val unresolved = roster?.unresolvedNames.orEmpty()
            val candidateShort = roster?.filtered == true && (roster?.candidateCount ?: 0) < 2
            when {
                unresolved.isNotEmpty() -> {
                    binding.emptyTitle.text = getString(R.string.duel_filter_unresolved_title)
                    binding.emptyHint.text =
                        getString(R.string.duel_filter_unresolved_hint, unresolved.joinToString(", "))
                }
                candidateShort -> {
                    binding.emptyTitle.text = getString(R.string.duel_filter_need_two)
                    binding.emptyHint.text =
                        getString(R.string.duel_filter_need_two_hint, roster?.candidateCount ?: 0)
                }
                // 이미지 축은 모자란 것이 캐릭터가 아니라 **그 캐릭터의 그림**이다 —
                // 캐릭터를 더 만들라고 하면 엉뚱한 곳으로 보내는 셈이다.
                isImageAxis && (imageTarget?.entry?.imageCount ?: 0) < 2 -> {
                    binding.emptyTitle.text = getString(R.string.duel_image_need_two)
                    binding.emptyHint.text = getString(R.string.duel_image_need_two_hint)
                }
                characters.size < 2 -> {
                    binding.emptyTitle.text = getString(R.string.duel_need_two)
                    binding.emptyHint.text = getString(R.string.duel_need_two_hint)
                }
                else -> {
                    binding.emptyTitle.text = getString(R.string.duel_queue_empty)
                    binding.emptyHint.text = getString(R.string.duel_queue_empty_hint)
                }
            }
            return
        }

        // **화면 하나를 짠다**(B-115) — 머리 짝에 대기열에서 뽑은 거들 참가자를 더한다.
        //
        // **떠 있는 판은 답하기 전에 다시 짜지 않는다**([DuelRound.canReuse]). 매번 짜면
        // 배경에서 새 계획이 도착할 때마다 머리 짝은 그대로인데 셋째·넷째 카드만 갈려,
        // 사용자가 고르려던 얼굴이 손가락 아래에서 바뀐다(DuelSession 규칙 1을 화면 전체로
        // 넓힌 것이다). 이 화면은 판마다 뒤에서 다시 계산하므로 실제로 자주 벌어질 자리다.
        val reusable = DuelRound.canReuse(round, current, groupSize) { codeById.containsKey(it) }
        val built = if (reusable) round!! else DuelRound.build(current, session.queue, groupSize)

        // 코드로 안 풀리는 참가자는 카드에 못 올리므로 걷어 내되, 머리 짝이 그렇다면
        // 낼 판이 없는 것과 같다(종전 동작 그대로).
        val members = built.members.filter { codeById.containsKey(it) }
        if (!leadResolved(current) || members.size < 2) {
            round = null
            cards = emptyList()
            binding.duelArea.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
            binding.btnDraw.isEnabled = false
            binding.emptyTitle.text = getString(R.string.duel_queue_empty)
            binding.emptyHint.text = getString(R.string.duel_queue_empty_hint)
            return
        }
        val shown = if (members.size == built.members.size) built else built.copy(members = members)
        round = shown

        binding.duelArea.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.reasonText.visibility = View.VISIBLE
        binding.reasonText.text = getString(reasonTextOf(shown.reason))

        val plan = DuelCardGrid.plan(shown.size, cardLayout == DuelImageFit.Layout.STACKED)
        applyGrid(plan)

        cards = shown.members.map { id ->
            val code = codeById[id].orEmpty()
            cardOf(charactersByCode[code], code, plan.compact)
        }
        shown.members.forEachIndexed { index, id ->
            val slot = plan.slots[index]
            val views = cardSlots[slot]
            paintCard(views, cards[index])
            val code = codeById[id].orEmpty()
            imageJobs[slot] = if (isImageAxis) {
                // 참가자 코드가 곧 경로다 — 대표 고르기를 거치지 않고 그 파일을 그대로 띄운다.
                loadImageAt(code, views.cardImage, imageJobs[slot])
            } else {
                loadPortrait(charactersByCode[code], views.cardImage, imageJobs[slot])
            }
        }

        // 비슷함은 짝이 떠 있을 때만 뜻이 있다 — 빈 화면에서 누르면 아무 일도 안 일어난다.
        binding.btnDraw.isEnabled = true
        binding.btnDraw.setText(
            if (shown.size > 2) R.string.duel_draw_group else R.string.duel_draw
        )
    }

    /**
     * 계획대로 슬롯을 켜고 끈다 — **판단은 [DuelCardGrid]가 이미 했다.**
     *
     * `INVISIBLE`과 `GONE`을 가르는 것이 이 함수의 유일한 요점이다: 셋일 때 넷째 슬롯은
     * 사라지지 않고 **자리를 지킨다**(안 그러면 셋째 카드가 혼자 두 배로 커져 눈을 끈다).
     */
    private fun applyGrid(plan: DuelCardGrid.Plan) {
        slotToMember = plan.slots.withIndex().associate { (index, slot) -> slot to index }
        val slots = cardSlots
        for (slot in 0 until SLOT_COUNT) {
            val views = slots[slot]
            views.root.visibility = when {
                slot in plan.slots -> View.VISIBLE
                slot in plan.spacers -> View.INVISIBLE
                else -> View.GONE
            }
            // 자리만 지키는 슬롯은 눌러도 아무 일이 없어야 한다 — 보이지 않는 카드를 골라
            // 판이 기록되면 사용자는 무엇을 눌렀는지도 모른다.
            views.root.isClickable = slot in plan.slots
            applyCardInner(views, plan.horizontalCard)
        }
        binding.rowBottom.visibility = if (plan.bottomRow) View.VISIBLE else View.GONE
        binding.versusInline.visibility = if (plan.inlineVersus) View.VISIBLE else View.GONE
        binding.versusStack.visibility = if (plan.stackVersus) View.VISIBLE else View.GONE
    }

    /** 이 참가자의 카드 — **무엇이 뜨는가는 [DuelCardInfo]가 정한다.** */
    private fun cardOf(character: Character?, code: String, compact: Boolean): DuelCardInfo.Card =
        DuelCardInfo.build(
            // 이미지 축의 이름은 **파일 이름**이다. 경로를 통째로 적으면 카드 폭을 다 먹으면서
            // 두 카드가 앞부분이 똑같아 오히려 못 가른다. 파일 이름은 폴더를 열었을 때
            // 사용자가 실제로 보는 이름이라 정리할 때 그대로 이어진다.
            name = if (isImageAxis) DuelImageParticipants.displayName(code) else {
                character?.displayName ?: getString(R.string.duel_unknown_participant)
            },
            values = fieldValues[code].orEmpty(),
            labels = fieldLabels,
            links = links,
            genderKey = genderKey,
            ageKey = ageKey,
            showProfiles = showProfile,
            showInfluences = showInfluence,
            compact = compact
        )

    /** 정해진 것을 붙인다 — 여기서 정하는 것은 **말글뿐**이다(단위·빈 값 표시). */
    private fun paintCard(views: ViewDuelCardBinding, card: DuelCardInfo.Card) {
        views.cardName.text = card.name

        views.cardTrio.text = trioTextOf(card)
        views.cardTrio.visibility = if (card.hasTrio) View.VISIBLE else View.GONE

        views.profileContainer.removeAllViews()
        val inflater = LayoutInflater.from(views.root.context)
        for (line in card.profiles) {
            views.profileContainer.addView(profileRow(inflater, views.profileContainer, line))
        }
        views.profileMore.visibility = if (card.showProfileMore) View.VISIBLE else View.GONE
        if (card.showProfileMore) {
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

    /**
     * 프로필 한 줄을 그린다 — **명대사만 모양이 다르다** (사용자 요청 2026.08.20).
     *
     * 카드와 펼침 시트가 같은 함수를 지나는 것이 요점이다. 두 자리에 따로 적으면 한쪽만
     * 대사 모양을 갖고, 그 어긋남은 **펼쳐 봐야** 보인다.
     *
     * @param expanded 펼침 시트인가. 그때는 **값이 빈 줄도 남고** *"안 적음"*을 적는다 —
     *   좁은 카드와 달리 여기서는 *"그 필드가 걸려 있는데 비었다"*가 알 값어치가 있다.
     */
    private fun profileRow(
        inflater: LayoutInflater,
        parent: ViewGroup,
        line: DuelCardInfo.Line,
        expanded: Boolean = false
    ): View {
        // 대사인데 값이 비어 있으면 **보통 줄로 그린다** — 인용 부호만 뜬 빈 상자를 만들지
        // 않으려는 것이고, 펼침 시트에서 "안 적음"이 이름표와 함께 읽혀야 하기 때문이다.
        if (DuelSystemFields.isQuoteKey(line.key) && line.value.isNotEmpty()) {
            val row = inflater.inflate(R.layout.item_duel_card_quote, parent, false)
            row.findViewById<android.widget.TextView>(R.id.quoteValue).text =
                getString(R.string.quote_wrapped, line.value)
            return row
        }
        val row = inflater.inflate(R.layout.item_duel_card_profile, parent, false)
        row.findViewById<android.widget.TextView>(R.id.profileLabel).text = line.label
        row.findViewById<android.widget.TextView>(R.id.profileValue).text =
            if (expanded) line.value.ifEmpty { getString(R.string.duel_field_value_empty) }
            else line.value
        return row
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
    private fun expandSlot(slot: Int) {
        val index = slotToMember[slot] ?: return
        showProfileSheet(cards.getOrNull(index) ?: return)
    }

    private fun showProfileSheet(card: DuelCardInfo.Card) {
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
        val sheetInflater = LayoutInflater.from(context)
        for (line in card.allProfiles) {
            body.addView(profileRow(sheetInflater, body, line, expanded = true))
        }
        // **접힌 카드에서는 영향 줄도 여기서만 볼 수 있다**(B-115) — 카드가 이름+트리오로
        // 접혔으면 판단 재료가 통째로 이 시트에 있다. 접히지 않은 카드에서는 이미 카드에
        // 떠 있으므로 두 번 적지 않는다.
        if (card.influences.isEmpty() && card.allInfluences.isNotEmpty()) {
            for (line in card.allInfluences) {
                val row = LayoutInflater.from(context)
                    .inflate(R.layout.item_duel_card_influence, body, false)
                row.findViewById<android.widget.TextView>(R.id.influenceRank).text =
                    line.rank.toString()
                row.findViewById<android.widget.TextView>(R.id.influenceLabel).text = line.label
                row.findViewById<android.widget.TextView>(R.id.influenceValue).text =
                    line.value.ifEmpty { getString(R.string.duel_field_value_empty) }
                body.addView(row)
            }
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
        renderFilterLine()
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

    /**
     * 후보 필터 줄 (B-168) — 걸려 있으면 *"후보 12/45 · 성별: 여성"*을 상시 보인다.
     * 일일이 편집 창을 열어야 필터의 존재를 아는 구조는 원칙 04가 금지하는 그것이다.
     */
    private fun renderFilterLine() {
        val value = roster
        if (value == null || !value.filtered) {
            binding.filterText.visibility = View.GONE
            return
        }
        binding.filterText.visibility = View.VISIBLE
        binding.filterText.text = getString(
            R.string.duel_filter_status,
            value.candidateCount,
            value.all.size,
            value.filters.joinToString(" · ") { viewModel.filterSummary(it) }
        )
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

    /** 한 화면에 몇을 올리는가 (B-115). 2면 종전 1:1이다. */
    private var groupSize = DuelRound.MIN_SIZE

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
     * ## k지선다(B-115)가 열린 자리도 여기다
     * 카드는 한 벌(`view_duel_card.xml`)을 **네 번** include한 것이고, *어느 슬롯을 쓰는가*는
     * [DuelCardGrid]가 정한다. **폭·높이를 여기서 계산하지 않는 것이 종전과 달라진 점**이다 —
     * 두 줄과 줄 안의 무게가 레이아웃에 박혀 있어 슬롯을 켜고 끄는 것만으로 종전 두 배치의
     * 크기가 그대로 나온다(나란히는 아랫줄이 사라져 윗줄이 높이를 다 갖는다).
     * 그래서 이 함수에 남은 일은 **설정을 읽어 다시 그리는 것**뿐이다.
     */
    private fun applyViewOptions() {
        val context = requireContext()
        cardLayout = DuelViewPrefs.layout(context)
        imageFit = DuelViewPrefs.fit(context)
        showProfile = DuelViewPrefs.showProfile(context)
        showInfluence = DuelViewPrefs.showInfluence(context)
        groupSize = DuelViewPrefs.groupSize(context)

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
     *
     * **격자(셋 이상)에서는 언제나 세로다** — 카드가 화면의 4분의 1이라 가로로 가르면 그림도
     * 패널도 둘 다 못 쓸 만큼 좁아진다. 그쪽은 패널이 이름+트리오로 접히므로(compact) 세로로
     * 두어도 그림이 자리를 거의 다 갖는다.
     */
    private fun applyCardInner(card: ViewDuelCardBinding, horizontal: Boolean) {
        card.cardInner.orientation =
            if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        val imageParams = card.cardImage.layoutParams as LinearLayout.LayoutParams
        val panelParams = card.cardPanel.layoutParams as LinearLayout.LayoutParams
        if (horizontal) {
            imageParams.width = 0
            imageParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            imageParams.weight = 4f
            panelParams.width = 0
            panelParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            panelParams.weight = 6f
        } else {
            imageParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            imageParams.height = 0
            imageParams.weight = 1f
            panelParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            panelParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            panelParams.weight = 0f
        }
        card.cardImage.layoutParams = imageParams
        card.cardPanel.layoutParams = panelParams
    }

    private fun showViewOptions() {
        val context = requireContext()
        var pickedLayout = cardLayout
        var pickedFit = imageFit

        var pickedGroupSize = groupSize

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_duel_view_options, null)
        val layoutGroup = view.findViewById<RadioGroup>(R.id.groupLayout)
        val fitGroup = view.findViewById<RadioGroup>(R.id.groupFit)
        val sizeGroup = view.findViewById<RadioGroup>(R.id.groupSize)
        val profileSwitch = view.findViewById<MaterialSwitch>(R.id.switchShowProfile)
        val influenceSwitch = view.findViewById<MaterialSwitch>(R.id.switchShowInfluence)
        sizeGroup.check(
            when (pickedGroupSize) {
                3 -> R.id.optionGroupThree
                4 -> R.id.optionGroupFour
                else -> R.id.optionGroupTwo
            }
        )
        sizeGroup.setOnCheckedChangeListener { _, id ->
            pickedGroupSize = when (id) {
                R.id.optionGroupThree -> 3
                R.id.optionGroupFour -> 4
                else -> DuelRound.MIN_SIZE
            }
        }
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
                    influenceSwitch.isChecked,
                    pickedGroupSize
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

        // **시드는 화면이 소유한다** — 종전에는 시드 자리에 `character.id`를 넘겨, 이 캐릭터의
        // 대표 그림이 앱을 다시 켜도 **영원히 같은 한 장으로 고정**됐다(형제 열넷은 전부
        // 화면 진입마다 새로 뽑는다). 넷째 인자의 `character.id`는 그대로다 — 그것은 시드가
        // 아니라 *같은 시드 아래 캐릭터마다 다른 답을 내게 하는* 독립 키다.
        //
        // **가중치는 일부러 걸지 않는다**(`duel_system_design_2026-08.md` 13-8-3) —
        // 카드 그림은 판단 재료라, 무게를 얹으면 이 축이 재려는 것을 이 축이 오염시킨다.
        val pick = CharacterRepresentativeImage.pickFrom(
            paths, character.representativeImagePath, imageSeed, character.id
        )
        // 사다리가 낸 답을 그대로 쓴다 — 호출부가 다시 계산하면 단일 소스가 둘이 된다.
        val path = pick.path ?: return null
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

    /**
     * 이미지 축의 카드 그림 — **참가자 코드가 곧 경로**라 대표 고르기를 거치지 않는다.
     *
     * 뒤늦게 도착한 비트맵이 이미 넘어간 짝에 붙지 않게 막는 것은 캐릭터 축과 같다
     * ([loadPortrait]와 같은 가드) — 대결은 빠르게 연타하는 화면이라 그 사이가 실제로 벌어진다.
     */
    private fun loadImageAt(path: String, target: ImageView, previous: Job?): Job? {
        previous?.cancel()
        target.scaleType = ImageView.ScaleType.FIT_CENTER
        target.setImageResource(R.drawable.ic_character_placeholder)
        if (path.isBlank()) return null
        val filesDir = requireContext().filesDir
        return viewLifecycleOwner.lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                CharacterImageLoader.decodeThumbnail(path, filesDir, 512)
            }
            if (isAdded && bitmap != null && path in currentCodes()) placeBitmap(target, bitmap)
        }
    }

    /**
     * 지금 화면에 떠 있는 참가자 코드 — 뒤늦게 도착한 그림이 **넘어간 판에 붙지 않게** 막는다.
     *
     * 카드가 넷이 될 수 있으므로 짝 둘이 아니라 [round]의 참가자 전부를 본다. 여기가 좁으면
     * 셋째·넷째 카드의 그림이 도착하는 족족 버려져 **그 자리가 영영 자리표시자로 남는다.**
     */
    private fun currentCodes(): Set<String> {
        val members = round?.members ?: return emptySet()
        return members.mapNotNullTo(HashSet()) { codeById[it] }
    }

    private fun openStandings() {
        // 이미지 축의 순위는 **그 캐릭터 안의 순위**다 — 캐릭터를 안 넘기면 순위표가
        // 누구의 그림을 줄 세우는지 알 수 없다(설계 13장).
        val bundle = Bundle().apply {
            putLong("axisId", axisId)
            putLong("characterId", characterId)
        }
        findNavController().navigateSafe(R.id.duelPlayFragment, R.id.duelStandingsFragment, bundle)
    }

    /** 쌓은 판을 보고 고치는 자리 — 되돌리기가 집지 못하는 *어제 그 판*이 여기 있다. */
    private fun openMatches() {
        val bundle = Bundle().apply { putLong("axisId", axisId) }
        findNavController().navigateSafe(R.id.duelPlayFragment, R.id.duelMatchesFragment, bundle)
    }

    /**
     * 이미지 축에서 **누구의 그림을 겨룰지** 고르는 자리.
     *
     * **이 화면을 백스택에서 빼고 간다.** 캐릭터를 못 정해 여기로 튕겨 온 것이므로 남겨 두면
     * 뒤로가기가 이 조각으로 돌아오고, 돌아오면 다시 튕겨 나가 **뒤로가기가 영영 안 먹는다**
     * (홈 '이어하기'가 이미지 축을 기억하고 있으면 실제로 그 길로 들어온다).
     */
    private fun openImageCharacters() {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.duelPlayFragment) return
        val bundle = Bundle().apply { putLong("axisId", axisId) }
        val options = androidx.navigation.NavOptions.Builder()
            .setPopUpTo(R.id.duelPlayFragment, true)
            .build()
        controller.navigateSafe(R.id.duelImageCharacterFragment, bundle, options)
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
        imageJobs.fill(null)
        round = null
        cards = emptyList()
        _binding = null
    }

    /** 슬롯 넷 — 차례가 곧 [DuelCardGrid]의 슬롯 번호다. */
    private val cardSlots: List<ViewDuelCardBinding>
        get() = listOf(binding.cardA, binding.cardB, binding.cardC, binding.cardD)

    companion object {
        /** [DuelCardGrid]의 슬롯 수. 레이아웃의 include 수와 같아야 한다. */
        private const val SLOT_COUNT = 4

        private val gson = Gson()
        private val imagePathsType = object : TypeToken<List<String>>() {}.type
    }
}
