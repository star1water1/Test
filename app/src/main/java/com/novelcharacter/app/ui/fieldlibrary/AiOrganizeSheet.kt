package com.novelcharacter.app.ui.fieldlibrary

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiService
import com.novelcharacter.app.ai.FieldLibraryAiOrganizer
import com.novelcharacter.app.data.model.FieldDefinition
import kotlinx.coroutines.launch

/**
 * AI 정리 플로우의 **배선** (온디맨드, docs/ai_integration.md 계약):
 * 프로바이더 가드 → 요청 수·비용 고지 → 실행 → 제안 체크리스트(전체 선택/해제, 근거 표시)
 * → 선택 항목 단일 통합 확인(전파 드라이런 합계) → 적용.
 * AI 출력은 어떤 경우에도 자동 적용되지 않는다.
 *
 * ## 유료 응답은 뷰가 들지 않는다 (R-38 · B-155)
 *
 * 종전에는 이 `object`가 **실행과 결과를 통째로 들고 있었다** — 실행이
 * `viewLifecycleOwner.lifecycleScope`에 있어 회전 한 번이 결제 중인 요청을 끊었고, 결과는
 * 코루틴 지역 변수에만, 체크리스트는 맨 `AlertDialog`에만 살았다. 회전하면 **되살릴 자리
 * 자체가 없었다.**
 *
 * 지금 이 파일에 남은 것은 **배선뿐이다:**
 * - **실행·결과 보관**은 [FieldValueLibraryViewModel.runAiOrganize]·`aiOrganizeResult`
 * - **검토 체크 상태**는 [AiOrganizeReviewSheet]의 `onSaveInstanceState`
 * - **적용과 실패 시 되살리기**는 [FieldValueLibraryViewModel.applyAiOrganize]
 *
 * 그래서 [observe]를 호스트 Fragment가 `onViewCreated`에서 한 번 부르면, 회전으로 재생성된
 * 뒤에도 보관된 결과가 그대로 흘러와 창이 다시 선다.
 */
object AiOrganizeSheet {

    /**
     * 보관 중인 결과를 화면에 잇는다 — **호스트가 `onViewCreated`에서 부른다.**
     *
     * 회전 뒤에도 같은 결과가 다시 흘러오므로, 아래 [showReview]는 **이미 떠 있는 창을
     * 찾아 다시 먹이는 길**을 먼저 본다(새로 만들면 체크가 날아가고 창이 겹친다).
     */
    fun observe(fragment: Fragment, viewModel: FieldValueLibraryViewModel) {
        viewModel.aiOrganizeResult.observe(fragment.viewLifecycleOwner) { outcome ->
            if (outcome != null) showReview(fragment, viewModel, outcome)
        }
        viewModel.aiOrganizeRunning.observe(fragment.viewLifecycleOwner) { running ->
            if (running == true) showProgress(fragment) else dismissProgress()
        }
        // 화면이 죽을 때 진행 창을 반드시 놓는다 — 이 `object`는 앱과 수명이 같아서,
        // 창을 든 채로 두면 **없어진 Activity를 정적 필드가 붙잡는다**(누수).
        // 회전이면 재생성된 화면이 위 관측으로 다시 세우므로 표시가 끊기지도 않는다.
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = dismissProgress()
        })
    }

    fun show(fragment: Fragment, fd: FieldDefinition, viewModel: FieldValueLibraryViewModel) {
        val context = fragment.requireContext()

        if (!AiService(context).hasUsableProvider()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.field_library_menu_ai)
                .setMessage(R.string.field_library_ai_not_configured)
                .setPositiveButton(R.string.ai_settings_title) { _, _ ->
                    fragment.findNavController().navigate(R.id.aiSettingsFragment)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        // 엔트리 읽기와 비용 고지는 화면의 일이라 뷰 스코프로 족하다 — 여기까지는
        // 아직 아무것도 결제하지 않았고, 회전하면 사용자가 메뉴를 다시 누르면 된다.
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val entries = viewModel.repo.entriesForField(fd.id)
            if (entries.size < 2) {
                MaterialAlertDialogBuilder(context)
                    .setMessage(R.string.field_library_ai_too_few)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
                return@launch
            }
            val chunkCount = FieldLibraryAiOrganizer.chunkEntries(entries).size
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.field_library_menu_ai)
                .setMessage(fragment.getString(R.string.field_library_ai_cost_notice, entries.size, chunkCount))
                .setPositiveButton(R.string.field_library_ai_run) { _, _ ->
                    // 이 순간부터가 유료다 — 실행은 ViewModel이 진다.
                    if (!viewModel.runAiOrganize(fd, entries)) {
                        // 이미 돌고 있다. **말없이 무시하지 않는다** — 두 번 눌렀는데 아무 일도
                        // 안 일어나면 사용자는 첫 실행이 죽은 줄 안다.
                        MaterialAlertDialogBuilder(context)
                            .setMessage(R.string.field_library_ai_already_running)
                            .setPositiveButton(R.string.confirm, null)
                            .show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ── 진행 표시 ──────────────────────────────────────────────────────────────
    // 회전하면 창은 사라지지만 **실행은 ViewModel에서 계속 돈다.** 재생성된 화면이
    // `aiOrganizeRunning`을 다시 관측해 이 창을 세우므로 진행 표시도 함께 되살아난다.

    private var progress: AlertDialog? = null

    private fun showProgress(fragment: Fragment) {
        if (progress?.isShowing == true) return
        progress = MaterialAlertDialogBuilder(fragment.requireContext())
            .setMessage(R.string.field_library_ai_running)
            .setCancelable(false)
            .show()
    }

    private fun dismissProgress() {
        progress?.dismiss()
        progress = null
    }

    private fun showReview(
        fragment: Fragment,
        viewModel: FieldValueLibraryViewModel,
        outcome: FieldValueLibraryViewModel.AiOrganizeOutcome
    ) {
        val result = outcome.result
        val notices = buildList {
            add(fragment.getString(R.string.field_library_ai_token_usage,
                result.totalInputTokens, result.totalOutputTokens))
            if (result.droppedCount > 0) {
                add(fragment.getString(R.string.field_library_ai_dropped, result.droppedCount))
            }
            addAll(result.failures)
        }

        // 고를 것이 하나도 없으면 체크리스트 대신 한 줄로 말하고 끝낸다 — **말없이 빠지지는
        // 않는다**(R-38 ⑥): 비용을 확인받고 돌린 실행이 아무 흔적도 안 남기면, 사용자는
        // 눌렀는데 아무 일도 안 일어난 것으로 본다.
        if (result.merges.isEmpty() && result.categories.isEmpty()) {
            viewModel.clearAiOrganizeResult()
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.field_library_menu_ai)
                .setMessage(
                    (listOf(fragment.getString(R.string.field_library_ai_nothing)) + notices)
                        .joinToString("\n")
                )
                .setPositiveButton(R.string.confirm, null)
                .show()
            return
        }

        // 이미 떠 있으면(회전으로 되살아난 창) 새로 만들지 않고 다시 먹인다 — 새로 만들면
        // 사용자의 체크가 날아가고 창이 겹친다. `isAdded`는 이미지판과 같은 이유다(B-163):
        // 적용 실패로 되살아나는 경로에서는 창이 닫히는 중이라 태그로는 찾히지만 다시
        // 먹여도 화면에 닿지 않는다.
        val existing = (fragment.childFragmentManager
            .findFragmentByTag(AiOrganizeReviewSheet.TAG) as? AiOrganizeReviewSheet)
            ?.takeIf { it.isAdded }
        val sheet = existing ?: AiOrganizeReviewSheet()
        // 비우는 일은 여기서 하지 않는다 — 적용이 깨지면 되살릴 것이 남아야 한다(R-38 ⑤).
        sheet.onApply = { merges, categories ->
            confirmAndApply(fragment, viewModel, outcome, merges, categories)
        }
        sheet.onDismissed = { viewModel.clearAiOrganizeResult() }
        if (existing != null) {
            sheet.rebind(result.merges, result.categories, notices)
        } else {
            sheet.merges = result.merges
            sheet.categories = result.categories
            sheet.notices = notices
            sheet.show(fragment.childFragmentManager, AiOrganizeReviewSheet.TAG)
        }
    }

    /** 선택 항목을 단일 통합 확인으로 적용 — 병합별 개별 확인 없음 (원칙 04) */
    private fun confirmAndApply(
        fragment: Fragment,
        viewModel: FieldValueLibraryViewModel,
        outcome: FieldValueLibraryViewModel.AiOrganizeOutcome,
        merges: List<FieldLibraryAiOrganizer.MergeSuggestion>,
        categories: List<FieldLibraryAiOrganizer.CategorySuggestion>
    ) {
        val context = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            // 드라이런: 선택된 병합 전체의 전파 규모 합산
            var totalChars = 0
            var totalEvents = 0
            var totalStates = 0
            var totalNovels = 0
            // 병합마다 따로 물으므로 **캐릭터는 id로 모은다** — 한 캐릭터가 두 값을 들고 있으면
            // 수를 더하는 순간 같은 사람을 두 번 센다.
            val untouchedIds = LinkedHashSet<Long>()
            for (m in merges) {
                val report = viewModel.previewPropagation(outcome.field, m.variants.toSet())
                totalChars += report.characterValues
                totalEvents += report.eventValues
                totalStates += report.stateChanges
                totalNovels += report.novelValues
                untouchedIds.addAll(report.unattributedHistoryCharacterIds)
            }
            val message = buildString {
                if (merges.isNotEmpty()) {
                    append(fragment.getString(R.string.field_library_ai_apply_merges, merges.size))
                    append("\n")
                    append(fragment.getString(R.string.field_library_propagation_message,
                        totalChars, totalEvents, totalStates, totalNovels))
                    if (untouchedIds.isNotEmpty()) {
                        append("\n")
                        append(context.getString(
                            R.string.field_library_propagation_unattributed, untouchedIds.size))
                    }
                }
                if (categories.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(fragment.getString(R.string.field_library_ai_apply_categories, categories.size))
                }
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.field_library_ai_apply)
                .setMessage(message)
                .setPositiveButton(R.string.field_library_ai_apply) { _, _ ->
                    viewModel.applyAiOrganize(outcome, merges, categories)
                }
                // 확인에서 물러선 것은 **검토를 접은 것이 아니다** — 보관 중인 응답을 그대로
                // 두어야 체크리스트로 돌아갈 수 있다(다시 결제하지 않는다).
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
