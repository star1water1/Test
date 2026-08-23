package com.novelcharacter.app.ui.namebank

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.CharacterNameAiSuggester
import com.novelcharacter.app.ai.CharacterNameAiSuggester.Mark
import com.novelcharacter.app.ai.CharacterNameAiSuggester.Mode
import com.novelcharacter.app.databinding.BottomSheetNameSuggestBinding
import com.novelcharacter.app.ui.character.CreativityChipRow
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.notifyResult

/**
 * **AI 이름 추천 시트** (B-123 · 설계 정본 `feature_roadmap_2026-08.md` 7장,
 * UI 정본 `docs/mockups/name_suggest_sheet_2026-08.html`).
 *
 * 진입은 둘이다 — 편집 폼 이름 줄의 ✨(캐릭터 컨텍스트 포함)과 이름은행의
 * [AI로 이름 만들기](세계관만 골라. 설계 8-2 ⓒ가 B-124에서 열 시트가 없어 미룬 그 항목이다).
 *
 * ## 회전을 넘긴다 (R-38)
 *
 * 라운드 후보는 **이미 결제한 응답**이라 닫아 버리는 관행(*주입 유실 시 안전 종료*)을 쓸 수 없다.
 * - **후보·표식·실행**은 [NameSuggestViewModel]이 든다(`by viewModels()` — 시트와 함께 산다).
 * - **검토 상태**(어느 라운드를 펼쳤는가·최신 라운드를 다 펼쳤는가)는 이 시트가
 *   [onSaveInstanceState]로 지킨다. 펼침만 되돌아가도 30개를 훑던 자리를 잃는다.
 * - **콜백만 호스트가 다시 붙인다** — 재생성된 시트의 람다는 null이라 [적용]이 죽은 버튼이 된다.
 *   호스트가 `findFragmentByTag`로 찾아 [onApply]를 다시 건다.
 *
 * ## 적용은 폼에만 쓴다
 *
 * AI 출력을 DB에 직접 기록하지 않는다는 기존 불변식 그대로다 — [적용]은 모드에 맞는 폼 칸을
 * 채우고, 캐릭터에 남는 것은 사용자가 저장을 눌렀을 때다. 은행 진입에는 채울 폼이 없으므로
 * **[적용]과 ♥ 칩 탭이 아예 서지 않는다**(눌리는데 아무 일도 안 나는 자리를 만들지 않는다 —
 * 설계 8-7 ⓒ가 잡은 그 부류).
 */
class NameSuggestSheet : BottomSheetDialogFragment() {

    /** 고른 이름을 폼에 적용한다 — 모드가 어느 칸인지 정한다. 은행 진입은 null이다. */
    var onApply: ((Mode, String) -> Unit)? = null

    private var _binding: BottomSheetNameSuggestBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NameSuggestViewModel by viewModels()

    /**
     * 호스트가 [show] 전에 건네는 재료. **여기서 ViewModel을 건드리지 않는다** —
     * 붙기 전(`show` 전)의 조각은 ViewModelStore가 없어 접근하는 순간 예외다.
     * 붙은 뒤([onViewCreated])에 한 번 먹인다.
     */
    private var pendingSetup: NameSuggestViewModel.Setup? = null

    /** 펼친 지난 라운드 번호 — 검토 상태라 시트가 지킨다(R-38). */
    private val expandedRounds = LinkedHashSet<Int>()

    /** 최신 라운드를 다 펼쳤는가(기본은 [PREVIEW_ROWS]까지 접힘). */
    private var latestExpanded = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNameSuggestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingSetup?.let { viewModel.setupOnce(it) }
        if (!viewModel.initialized) {
            // 주입 없이 재생성됐다(프로세스 재생성) — ViewModel도 함께 죽었으므로 풀도 없다.
            // 유료 응답을 **버리는 것이 아니라** 이미 없는 것을 확인하고 닫는다(회전은 이 길로 오지 않는다).
            dismissAllowingStateLoss()
            return
        }
        savedInstanceState?.let { state ->
            latestExpanded = state.getBoolean(STATE_LATEST_EXPANDED, false)
            state.getIntArray(STATE_EXPANDED_ROUNDS)?.let { expandedRounds.addAll(it.toList()) }
        }

        binding.titleText.text = if (viewModel.title.isBlank()) {
            getString(R.string.ai_name_title_plain)
        } else {
            getString(R.string.ai_name_title, viewModel.title)
        }
        binding.purposeText.setText(
            if (viewModel.hasCharacter) R.string.ai_name_purpose else R.string.ai_name_purpose_bank
        )
        binding.btnOptions.setOnClickListener { showOptions() }
        binding.btnMore.setOnClickListener { runRound() }
        binding.btnDeposit.setOnClickListener { confirmDeposit() }

        buildModeChips()

        viewModel.pool.observe(viewLifecycleOwner) { render() }
        viewModel.running.observe(viewLifecycleOwner) { render() }
        viewModel.depositing.observe(viewLifecycleOwner) { render() }
        viewModel.depositPlan.observe(viewLifecycleOwner) { render() }
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }
    }

    /** 호스트가 시트를 처음 열 때 한 번 먹인다. 회전 뒤에는 ViewModel이 이미 들고 있다. */
    fun setup(setup: NameSuggestViewModel.Setup) {
        pendingSetup = setup
    }

    // ===== 모드 =====

    /**
     * 고를 수 있는 모드만 세운다 (R-24 — 성립하지 않는 조합은 보이지 않는다).
     *
     * 성 고정은 폼에 성이 적혀 있어야, 이름 고정은 이름이 적혀 있어야 성립한다. 은행 진입에는
     * 고정할 폼 자체가 없고 이명도 붙일 캐릭터가 없으므로 **전체 이름 하나만 선다.**
     */
    private fun availableModes(): List<Mode> = buildList {
        add(Mode.FULL)
        if (!viewModel.hasCharacter) return@buildList
        if (viewModel.fixedSurname.isNotBlank()) add(Mode.GIVEN)
        if (viewModel.fixedGiven.isNotBlank()) add(Mode.SURNAME)
        add(Mode.ALIAS)
    }

    private fun buildModeChips() {
        val modes = availableModes()
        binding.modeChips.removeAllViews()
        binding.modeChips.isVisible = modes.size > 1
        val current = viewModel.pool.value?.mode ?: Mode.FULL
        for (mode in modes) {
            val chip = Chip(requireContext()).apply {
                text = getString(modeLabel(mode))
                isCheckable = true
                isChecked = mode == current
                setOnClickListener { if (isChecked) askSwitchMode(mode) else isChecked = true }
            }
            binding.modeChips.addView(chip)
        }
    }

    /**
     * 모드 전환은 **풀을 비운다** — 전체 이름과 이명이 한 목록에 섞이면 적용 칸도 담기의
     * 성별·출처도 갈린다. 이미 결제한 후보가 사라지는 자리라 **먼저 확인받는다**.
     */
    private fun askSwitchMode(mode: Mode) {
        val pool = viewModel.pool.value
        // 이미 그 모드다 — 선택 필수 칩은 같은 칩을 다시 눌러도 리스너가 울린다.
        if (pool?.mode == mode) return
        if (pool == null || pool.isEmpty) {
            viewModel.switchMode(mode)
            buildModeChips()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_name_mode_switch_title)
            .setMessage(getString(R.string.ai_name_mode_switch_message, pool.candidates.size))
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.switchMode(mode)
                latestExpanded = false
                expandedRounds.clear()
                buildModeChips()
            }
            // 되돌린다 — 취소했는데 칩만 옮겨 가 있으면 화면이 거짓을 말한다.
            .setNegativeButton(R.string.cancel) { _, _ -> buildModeChips() }
            .setOnCancelListener { buildModeChips() }
            .show()
    }

    private fun modeLabel(mode: Mode): Int = when (mode) {
        Mode.FULL -> R.string.ai_name_mode_full
        Mode.GIVEN -> R.string.ai_name_mode_given
        Mode.SURNAME -> R.string.ai_name_mode_surname
        Mode.ALIAS -> R.string.ai_name_mode_alias
    }

    // ===== 실행 =====

    private fun runRound() {
        val instruction = binding.instructionEdit.text?.toString().orEmpty()
        if (!viewModel.runRound(instruction)) {
            // 이미 실행 중 — 무통보로 삼키지 않는다.
            android.widget.Toast.makeText(
                requireContext(), R.string.ai_name_running, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 다발 크기·창작도 — 매 라운드 고치는 값이 아니라 ⋮ 뒤에 둔다. */
    private fun showOptions() {
        val context = requireContext()
        val settings = AiPromptSettings(context)
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        var picked = settings.nameSuggestBatchSize
        val valueLabel = TextView(context).apply {
            textSize = 14f
            text = getString(R.string.ai_name_batch_value, picked)
        }
        val slider = Slider(context).apply {
            valueFrom = AiPromptPolicy.NAME_SUGGEST_BATCH_MIN.toFloat()
            valueTo = AiPromptPolicy.NAME_SUGGEST_BATCH_MAX.toFloat()
            stepSize = 1f
            value = picked.toFloat()
            // 저장은 [확인]에서 한다 — 끌다가 취소한 값이 다음 라운드에 남으면 안 된다.
            addOnChangeListener { _, v, _ ->
                picked = v.toInt()
                valueLabel.text = getString(R.string.ai_name_batch_value, picked)
            }
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply {
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
                setText(R.string.ai_name_batch_purpose)
            })
            addView(valueLabel)
            addView(slider)
            addView(CreativityChipRow.create(this@NameSuggestSheet))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_name_options)
            .setView(panel)
            .setPositiveButton(R.string.confirm) { _, _ ->
                settings.nameSuggestBatchSize = picked
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ===== 그리기 =====

    private fun render() {
        if (_binding == null) return
        val pool = viewModel.pool.value ?: CharacterNameAiSuggester.Pool()
        val running = viewModel.running.value == true
        val batch = AiPromptSettings(requireContext()).nameSuggestBatchSize

        binding.emptyText.isVisible = pool.isEmpty && !running
        binding.btnMore.isEnabled = !running
        // 요청 중에는 모드를 못 바꾼다 — 바꾸면 **옛 모드로 만들어진 이름**이 돌아온다.
        // ViewModel도 그 경우를 막지만(도착 시점에 모드를 대조한다) 막힌 뒤 말하는 것보다
        // 애초에 못 누르는 편이 낫다: 사용자가 잃는 것이 요청 1건의 값이다.
        for (i in 0 until binding.modeChips.childCount) {
            binding.modeChips.getChildAt(i).isEnabled = !running
        }
        binding.btnMore.text = when {
            running -> getString(R.string.ai_name_running_short)
            // 첫 라운드에 "10개 더"라고 적으면 무엇에 더한다는 것인지가 없다.
            pool.isEmpty -> getString(R.string.ai_name_first_run, batch)
            else -> getString(R.string.ai_name_more, batch)
        }

        renderShelf(pool)
        renderLatest(pool)
        renderPast(pool)

        // 비용 고지는 늘 서고, **무엇을 싣는지**는 표식이 있을 때만 덧붙는다 —
        // 표식이 없는데 "♥ 0개를 따른다"고 적으면 고지가 소음이 된다.
        val hearts = pool.namesMarked(Mark.HEART).size
        val avoids = pool.namesMarked(Mark.REJECT).size
        binding.costText.text = getString(R.string.ai_name_cost_notice) +
            if (hearts > 0 || avoids > 0) getString(R.string.ai_name_cost_marks, hearts, avoids) else ""

        val plan = viewModel.depositPlan.value
        val count = plan?.defaultChecked?.size ?: 0
        binding.btnDeposit.text = getString(R.string.ai_name_deposit, count)
        // 담기 중에는 버튼을 내린다 — 빗장은 뷰모델이 들지만(회전을 넘긴다) 누를 수 있는
        // 채로 두면 사용자가 같은 조작을 두 번 한 줄로 안다.
        binding.btnDeposit.isEnabled = plan != null && plan.items.isNotEmpty() &&
            viewModel.depositing.value != true
        // 걸러진 것을 개수로 말한다 (R-14) — 27개를 만들었는데 14개만 담기면 그 차이가 보여야 한다.
        binding.depositNote.text = when {
            plan == null || pool.isEmpty -> ""
            plan.filteredInBank > 0 || plan.filteredDuplicate > 0 -> getString(
                R.string.ai_name_deposit_note_filtered,
                pool.candidates.size, plan.filteredInBank + plan.filteredDuplicate
            )
            else -> getString(R.string.ai_name_deposit_note, pool.candidates.size)
        }
    }

    private fun renderShelf(pool: CharacterNameAiSuggester.Pool) {
        val hearts = pool.namesMarked(Mark.HEART)
        binding.shelfCard.isVisible = hearts.isNotEmpty()
        binding.shelfTitle.text = getString(R.string.ai_name_shelf_title, hearts.size)
        binding.shelfChips.removeAllViews()
        for (name in hearts) {
            val chip = Chip(requireContext()).apply {
                text = getString(R.string.ai_name_shelf_chip, name)
                isCloseIconVisible = true
                setOnCloseIconClickListener { viewModel.setMark(name, Mark.NONE) }
                // 폼이 있을 때만 누를 수 있다 — 은행 진입에서는 적용할 칸이 없으므로
                // 리스너를 아예 달지 않는다(눌리는 것처럼 보이는데 아무 일도 안 나는 자리 금지).
                if (viewModel.hasCharacter) {
                    isClickable = true
                    setOnClickListener { applyName(pool.mode, name) }
                } else {
                    isClickable = false
                }
            }
            binding.shelfChips.addView(chip)
        }
    }

    private fun renderLatest(pool: CharacterNameAiSuggester.Pool) {
        val latest = pool.rounds.lastOrNull()
        binding.latestCard.isVisible = latest != null
        if (latest == null) return
        binding.latestTitle.text = getString(
            R.string.ai_name_round_title, latest.ordinal, latest.candidates.size
        )
        val shown = if (latestExpanded || latest.candidates.size <= PREVIEW_ROWS) {
            latest.candidates
        } else {
            latest.candidates.take(PREVIEW_ROWS)
        }
        fillRows(binding.latestContainer, shown, pool)
        val hidden = latest.candidates.size - shown.size
        binding.btnExpandLatest.isVisible = hidden > 0 || latestExpanded
        binding.btnExpandLatest.text = if (hidden > 0) {
            getString(R.string.ai_name_expand_more, hidden)
        } else {
            getString(R.string.ai_name_collapse)
        }
        binding.btnExpandLatest.setOnClickListener {
            latestExpanded = !latestExpanded
            render()
        }
        val notices = latest.notices
        binding.latestNotices.isVisible = notices.isNotEmpty()
        binding.latestNotices.text = notices.joinToString("\n")
    }

    private fun renderPast(pool: CharacterNameAiSuggester.Pool) {
        val past = pool.rounds.dropLast(1)
        binding.pastCard.isVisible = past.isNotEmpty()
        binding.pastContainer.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (round in past) {
            val view = inflater.inflate(R.layout.item_name_suggest_round, binding.pastContainer, false)
            val title = view.findViewById<TextView>(R.id.roundTitle)
            val chevron = view.findViewById<TextView>(R.id.roundChevron)
            val container = view.findViewById<LinearLayout>(R.id.roundContainer)
            val expanded = round.ordinal in expandedRounds
            title.text = getString(
                R.string.ai_name_past_round_title,
                round.ordinal, round.candidates.size,
                pool.countMarked(round, Mark.HEART), pool.countMarked(round, Mark.REJECT)
            )
            chevron.text = getString(if (expanded) R.string.ai_name_collapse else R.string.ai_name_expand)
            container.isVisible = expanded
            if (expanded) {
                fillRows(container, round.candidates, pool)
                // 그 라운드의 고지도 함께 편다 — 토큰·드롭·되풀이는 **그 라운드의 사실**이라
                // 최신 라운드에만 두면 지난 판에서 무엇이 빠졌는지 볼 길이 사라진다(R-14).
                if (round.notices.isNotEmpty()) {
                    container.addView(TextView(requireContext()).apply {
                        text = round.notices.joinToString("\n")
                        setTextAppearance(R.style.TextAppearance_App_Caption)
                        setTextColor(requireContext().getColor(R.color.text_secondary))
                    })
                }
            }
            view.findViewById<View>(R.id.roundHeader).setOnClickListener {
                if (expanded) expandedRounds.remove(round.ordinal) else expandedRounds.add(round.ordinal)
                render()
            }
            binding.pastContainer.addView(view)
        }
    }

    private fun fillRows(
        container: LinearLayout,
        candidates: List<CharacterNameAiSuggester.Candidate>,
        pool: CharacterNameAiSuggester.Pool
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (candidate in candidates) {
            val row = inflater.inflate(R.layout.item_name_suggest_candidate, container, false)
            val mark = pool.markOf(candidate.name)
            val nameText = row.findViewById<TextView>(R.id.nameText)
            val reasonText = row.findViewById<TextView>(R.id.reasonText)
            val heart = row.findViewById<MaterialButton>(R.id.btnHeart)
            val reject = row.findViewById<MaterialButton>(R.id.btnReject)
            val applyButton = row.findViewById<MaterialButton>(R.id.btnApply)

            nameText.text = candidate.name
            nameText.paintFlags = if (mark == Mark.REJECT) {
                nameText.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                nameText.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            row.alpha = if (mark == Mark.REJECT) 0.5f else 1f
            reasonText.isVisible = candidate.reason.isNotBlank()
            reasonText.text = candidate.reason

            // ✕한 줄은 **되살리기 하나만** 남긴다 — 피한다고 정한 이름에 ♥·적용을 함께
            // 세우면 무엇이 지금 상태인지 줄만 봐서는 알 수 없다.
            val rejected = mark == Mark.REJECT
            heart.isVisible = !rejected
            applyButton.isVisible = !rejected && viewModel.hasCharacter
            heart.text = getString(
                if (mark == Mark.HEART) R.string.ai_name_mark_heart_on else R.string.ai_name_mark_heart
            )
            reject.text = getString(
                if (rejected) R.string.ai_name_mark_restore else R.string.ai_name_mark_reject
            )
            heart.setOnClickListener {
                viewModel.setMark(candidate.name, if (mark == Mark.HEART) Mark.NONE else Mark.HEART)
            }
            reject.setOnClickListener {
                viewModel.setMark(candidate.name, if (rejected) Mark.NONE else Mark.REJECT)
            }
            applyButton.setOnClickListener { applyName(pool.mode, candidate.name) }
            container.addView(row)
        }
    }

    private fun applyName(mode: Mode, name: String) {
        val handler = onApply
        if (handler == null) {
            // 재생성 뒤 호스트가 콜백을 다시 붙이지 못한 자리 — 조용히 넘기면 고장과 같다.
            android.widget.Toast.makeText(
                requireContext(), R.string.ai_name_apply_unavailable, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        handler(mode, name)
        android.widget.Toast.makeText(
            requireContext(), getString(R.string.ai_name_applied, name), android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // ===== 은행에 담기 =====

    /**
     * 무엇을 담는지 **먼저 보이고** 담는다 — ✕한 이름은 기본 해제로 목록에 서고, 이미
     * 캐릭터가 쓰는 이름은 표식이 붙는다(설계 7-4). 창고에 들어가는 것은 되돌리기가 없는
     * 쓰기라 목록을 확인할 자리를 준다.
     */
    private fun confirmDeposit() {
        val plan = viewModel.depositPlan.value ?: return
        if (plan.items.isEmpty()) return
        val labels = plan.items.map { item ->
            buildString {
                append(item.name)
                if (item.usedByCharacter) append(getString(R.string.ai_name_deposit_used_mark))
                if (item.rejected) append(getString(R.string.ai_name_deposit_rejected_mark))
            }
        }.toTypedArray()
        val checked = plan.items.map { !it.rejected }.toBooleanArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_name_deposit_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.confirm) { _, _ ->
                viewModel.deposit(
                    plan.items.filterIndexed { index, _ -> checked[index] }.map { it.name }
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 검토 상태는 시트밖에 모른다 (R-38) — 펼침이 되돌아가면 훑던 자리를 잃는다.
        outState.putBoolean(STATE_LATEST_EXPANDED, latestExpanded)
        outState.putIntArray(STATE_EXPANDED_ROUNDS, expandedRounds.toIntArray())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "NameSuggestSheet"

        /**
         * 쓸 수 있는 프로바이더가 없으면 **조용히 아무 일도 하지 않는 대신 설정 경로를 안내한다**
         * (필드 ✨·서술형 시트와 같은 규약).
         *
         * **진입 둘이 이 함수 하나를 쓴다** — 편집 폼의 ✨과 은행의 [AI로 이름 만들기]가 각자
         * 창을 세우면 같은 기능이 들어온 문에 따라 다르게 말하게 된다. 감추지 않고 안내하는
         * 것은 설계 7-1이 *"필드 ✨과 같은 문법"*이라 적었기 때문이다(R-24로 줄을 감추는
         * 일괄 태깅과 갈리는 자리이며, 그쪽은 목록의 한 줄이고 이쪽은 상시 버튼이다).
         */
        fun guardProvider(fragment: Fragment): Boolean {
            val context = fragment.requireContext()
            if (AiService(context).hasUsableProvider()) return true
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ai_name_title_plain)
                .setMessage(R.string.ai_name_not_configured)
                // 두 진입의 현재 목적지가 다르므로 `navigateSafe`를 쓴다 — 목적지가 안 맞는
                // 상황에서 `navigate`는 예외를 던진다(이 저장소가 그 래퍼를 둔 이유다).
                .setPositiveButton(R.string.ai_settings_title) { _, _ ->
                    fragment.findNavController().navigateSafe(R.id.aiSettingsFragment)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return false
        }

        /** 최신 라운드에서 접지 않고 보일 줄 수 — 다발 기본값(10)보다 작아야 접기가 실제로 돈다. */
        const val PREVIEW_ROWS = 5

        private const val STATE_LATEST_EXPANDED = "latestExpanded"
        private const val STATE_EXPANDED_ROUNDS = "expandedRounds"
    }
}
