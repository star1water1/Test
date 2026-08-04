package com.novelcharacter.app.ui.duel

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.databinding.FragmentDuelPlayBinding
import com.novelcharacter.app.util.CharacterImageLoader
import com.novelcharacter.app.util.CharacterRepresentativeImage
import com.novelcharacter.app.util.DuelPairing
import com.novelcharacter.app.util.DuelSession
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

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.cardA.setOnClickListener { pick(left = true) }
        binding.cardB.setOnClickListener { pick(left = false) }
        binding.btnUndo.setOnClickListener { undoLast() }
        binding.btnStandings.setOnClickListener { openStandings() }

        loadFirst()
    }

    private fun loadFirst() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = viewModel.axis(axisId) ?: run { findNavController().popBackStack(); return@launch }
            axis = loaded
            binding.toolbar.title = loaded.name
            characters = viewModel.participants(loaded)
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

    private fun pick(left: Boolean) {
        val current = session.current ?: return
        val axis = this.axis ?: return
        val (aCode, bCode) = codesOf(current) ?: return
        val winner = if (left) aCode else bCode

        // 화면은 기다리지 않는다 — 다음 짝을 먼저 올리고 기록·재계산은 뒤에서 돈다.
        session = DuelSession.answer(session)
        val seq = session.seq
        pendingRecords++
        render()

        viewLifecycleOwner.lifecycleScope.launch {
            val row = viewModel.record(axis.id, aCode, bCode, winner)
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
        binding.nameA.text = a?.displayName ?: getString(R.string.duel_unknown_participant)
        binding.nameB.text = b?.displayName ?: getString(R.string.duel_unknown_participant)
        imageJobA = loadPortrait(a, binding.imageA, imageJobA)
        imageJobB = loadPortrait(b, binding.imageB, imageJobB)
    }

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

    /** 대표 이미지 한 장 — 판정은 `CharacterRepresentativeImage`가 단일 소스다(B-103 D2·D4). */
    private fun loadPortrait(character: Character?, target: ImageView, previous: Job?): Job? {
        previous?.cancel()
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
            if (isAdded && bitmap != null && boundCode in currentCodes()) target.setImageBitmap(bitmap)
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
