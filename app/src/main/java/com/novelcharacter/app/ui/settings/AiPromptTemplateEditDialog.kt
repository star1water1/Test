package com.novelcharacter.app.ui.settings

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.PromptTemplateValidator
import com.novelcharacter.app.ai.PromptTemplates
import com.novelcharacter.app.databinding.DialogAiPromptTemplateEditBinding
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError

/**
 * 양식 하나를 고치는 창 (사용자 요청 2026.08.20).
 *
 * ## 왜 `DialogFragment`인가 — 회전
 *
 * 조각 안에서 `MaterialAlertDialogBuilder(...).show()`로 띄우면 **회전 한 번에 창이 사라지고
 * 쓰던 글이 함께 죽는다.** 여기서 쓰는 글은 수천 자짜리라 그 유실이 크고, 조용하다.
 * `DialogFragment`는 안드로이드가 다시 세워 주고, 쓰던 글은 [onSaveInstanceState]가 싣는다
 * (B-260이 체형 편집 창에서 같은 사고를 겪고 세운 길이다).
 *
 * ## 결과는 콜백이 아니라 [setFragmentResult]로 돌려준다
 *
 * 회전 뒤에는 **인스턴스에 꽂아 둔 콜백 속성이 null이다**(R-65 — 명대사 판이 '캐릭터 열기'에서
 * 정확히 이 사고를 냈다). 저장이 끝났다는 사실만 알리면 되므로 빈 결과를 보내고, 목록은
 * 자기가 다시 읽는다.
 */
class AiPromptTemplateEditDialog : DialogFragment() {

    private var _binding: DialogAiPromptTemplateEditBinding? = null

    private val templateId: PromptTemplates.Id
        get() = PromptTemplates.Id.ofKey(requireArguments().getString(ARG_KEY).orEmpty())
            ?: PromptTemplates.Id.entries.first()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val id = templateId
        val settings = AiPromptSettings(ctx)
        val binding = DialogAiPromptTemplateEditBinding.inflate(layoutInflater)
        _binding = binding
        val tokens = PromptTemplates.tokensOf(id)

        binding.templatePurpose.text = getString(
            if (id.kind == PromptTemplates.Kind.SYSTEM) R.string.ai_prompt_template_purpose_system
            else R.string.ai_prompt_template_purpose_user
        )
        // **글자 수를 세어 보이되 막지는 않는다.** 형제 자리(필드 설명)는 `InputFilter`로 아예
        // 못 넘게 하지만, 여기서 잘라 버리면 필수 자리표가 잘려나간 **계약이 깨진 양식**이
        // 조용히 만들어진다. 넘으면 저장에서 거절하고 몇 자인지 말해 주는 편이 낫다.
        binding.templateInputLayout.counterMaxLength = AiPromptPolicy.PROMPT_TEMPLATE_MAX_CHARS
        binding.templateInput.setText(
            savedInstanceState?.getString(STATE_TEXT) ?: settings.templateOf(id)
        )
        // **커서를 맨 뒤로 보낸다.** `setText` 직후 커서는 0이라, 본문을 한 번도 안 누른 채
        // 자리표 칩을 누르면 그 자리표가 **글 맨 앞에 박힌다** — 검증은 어휘·개수만 보므로
        // 그대로 저장돼 다음 요청부터 망가진 글이 나간다.
        binding.templateInput.setSelection(binding.templateInput.text?.length ?: 0)

        tokens.forEach { token ->
            binding.tokenChips.addView(
                Chip(ctx).apply {
                    text = PromptTemplates.wrap(token.name)
                    isCheckable = false
                    setOnClickListener { insertAtCursor(PromptTemplates.wrap(token.name)) }
                }
            )
        }
        binding.tokenMeanings.text = tokens.joinToString("\n") {
            PromptTemplates.wrap(it.name) + " — " + it.meaning + when {
                // '한 번만'은 그렇게 선언된 자리에만 붙인다 — 전부에 붙이면 기본 양식이
                // 두 번 쓰는 자리에서 안내가 곧바로 거짓이 된다.
                it.single -> getString(R.string.ai_prompt_template_token_single)
                it.required -> getString(R.string.ai_prompt_template_token_required)
                else -> ""
            }
        }

        // 바깥을 눌러 닫히지 않는다 — 수천 자를 쓰는 창이라 손이 미끄러지면 적던 것이
        // 말없이 사라진다. 닫는 길은 저장·취소 둘뿐이다(R-27 결).
        isCancelable = false
        return MaterialAlertDialogBuilder(ctx)
            .setTitle(id.label)
            .setView(binding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            // **닫지 않고 칸만 되돌린다** — 눌러서 확인한 뒤 저장하는 것이 이 단추의 쓰임이고,
            // 취소로 빠져나갈 길도 남는다(형제 창들이 쓰는 꼴 그대로).
            .setNeutralButton(R.string.ai_prompt_template_restore_default, null)
            .create()
            .also { dialog ->
                dialog.setValidatedPositiveButton(
                    onShow = { shown ->
                        shown.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            _binding?.templateInput?.let { edit ->
                                edit.setText(PromptTemplates.default(id))
                                edit.setSelection(edit.text?.length ?: 0)
                            }
                            _binding?.templateInputLayout?.error = null
                        }
                    }
                ) {
                    val edit = _binding ?: return@setValidatedPositiveButton true
                    val problems =
                        settings.setTemplate(id, edit.templateInput.text?.toString().orEmpty())
                    if (problems.isEmpty()) {
                        setFragmentResult(RESULT_KEY, bundleOf())
                        true
                    } else {
                        // 실패 문구는 **고칠 칸에** 붙는다 — 토스트는 화면을 떠난다(R-27).
                        edit.templateInputLayout.showInlineError(
                            problems.joinToString("\n") { problemText(it) }
                        )
                        false
                    }
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // **저장하지 않은 글도 싣는다** — 회전은 사용자가 편집을 끝냈다는 뜻이 아니다.
        _binding?.templateInput?.text?.toString()?.let { outState.putString(STATE_TEXT, it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun insertAtCursor(text: String) {
        val edit = _binding?.templateInput ?: return
        val editable = edit.text ?: return
        val from = minOf(edit.selectionStart, edit.selectionEnd).coerceIn(0, editable.length)
        val end = maxOf(edit.selectionStart, edit.selectionEnd).coerceIn(from, editable.length)
        editable.replace(from, end, text)
        // 커서를 끼워 넣은 글 뒤로 옮긴다 — 안 옮기면 자리표를 둘 넣을 때 둘째가
        // 첫째 앞에 붙어 `{{나}}{{가}}`가 된다.
        edit.setSelection((from + text.length).coerceAtMost(editable.length))
    }

    /**
     * 거절 사유의 **화면판 문구**. 순수 계층은 `Problem`만 내고 리소스를 모른다 —
     * 엑셀 가져오기는 자기 문구를 따로 입힌다(`FormulaValidator`와 같은 관행).
     */
    private fun problemText(problem: PromptTemplateValidator.Problem): String = when (problem) {
        is PromptTemplateValidator.Problem.UnknownTokens ->
            getString(
                R.string.ai_prompt_template_error_unknown,
                problem.names.joinToString(", ") { PromptTemplates.wrap(it) }
            )
        is PromptTemplateValidator.Problem.MissingRequired ->
            getString(
                R.string.ai_prompt_template_error_missing,
                problem.names.joinToString(", ") { PromptTemplates.wrap(it) }
            )
        is PromptTemplateValidator.Problem.Duplicated ->
            getString(
                R.string.ai_prompt_template_error_duplicated,
                problem.names.joinToString(", ") { PromptTemplates.wrap(it) }
            )
        is PromptTemplateValidator.Problem.PaddedTokens ->
            getString(
                R.string.ai_prompt_template_error_padded,
                problem.samples.joinToString(", ")
            )
        is PromptTemplateValidator.Problem.MalformedTokens ->
            getString(
                R.string.ai_prompt_template_error_malformed,
                problem.samples.joinToString(", ")
            )
        is PromptTemplateValidator.Problem.TooLong ->
            getString(R.string.ai_prompt_template_error_too_long, problem.limit, problem.chars)
    }

    companion object {
        const val RESULT_KEY = "ai_prompt_template_saved"
        private const val ARG_KEY = "templateKey"
        private const val STATE_TEXT = "text"

        fun newInstance(id: PromptTemplates.Id) = AiPromptTemplateEditDialog().apply {
            arguments = bundleOf(ARG_KEY to id.key)
        }
    }
}
