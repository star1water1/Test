package com.novelcharacter.app.ui.fieldlibrary

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.FieldLibraryAiOrganizer
import com.novelcharacter.app.util.cappedScrollView

/**
 * AI 정리의 **제안 체크리스트** — 사용자가 고른 것만 적용한다.
 *
 * **AI 출력은 어떤 경우에도 자동 적용되지 않는다**(`docs/ai_integration.md` 계약).
 * 기본값은 **전부 해제**다 — 병합은 값을 합치는 파괴적 조작이라, 훑어 지우는 쪽이 아니라
 * 골라 켜는 쪽이 맞다(이미지 태그 시트가 전부 체크로 시작하는 것과 반대인 것은 의도다:
 * 그쪽은 태그를 더하는 일이라 되돌리기가 싸다).
 *
 * ## 회전을 넘긴다 (R-38 · B-155)
 *
 * 이 화면이 보여 주는 것은 **이미 결제한 응답**이다. 종전에는 맨 `AlertDialog`라
 * **회전에 그냥 사라졌고 되살릴 자리조차 없었다** — 형제인 이미지 검토 시트 둘보다 나빴다.
 * 갈래는 그 둘과 같게 맞춘다:
 * **제안은 [FieldValueLibraryViewModel.aiOrganizeResult]가 들고 호출부가 [rebind]로 다시
 * 먹이며, 사용자의 체크는 이 시트가 [onSaveInstanceState]로 지킨다.**
 *
 * 체크를 **키 문자열로** 지키는 것은 제안 객체가 `Parcelable`이 아니어서만이 아니다 —
 * 되살아난 제안 목록과 저장된 체크를 **값으로** 맞춰야 순서가 달라져도 옳게 붙는다.
 */
class AiOrganizeReviewSheet : DialogFragment() {

    var merges: List<FieldLibraryAiOrganizer.MergeSuggestion> = emptyList()
    var categories: List<FieldLibraryAiOrganizer.CategorySuggestion> = emptyList()

    /** 사용 토큰·드롭·부분 실패 고지 — 조용히 버린 것이 없음을 보이는 자리(R-17). */
    var notices: List<String> = emptyList()

    /** 사용자가 고른 것. 둘 다 비어 있으면 부르지 않는다. */
    var onApply: (
        List<FieldLibraryAiOrganizer.MergeSuggestion>,
        List<FieldLibraryAiOrganizer.CategorySuggestion>
    ) -> Unit = { _, _ -> }

    /**
     * 사용자가 검토를 **접었다**(취소·뒤로가기·바깥 탭). 호출부가 보관 중인 응답을 버린다 —
     * 남겨 두면 다음 회전에 이 창이 혼자 되살아난다.
     */
    var onDismissed: () -> Unit = {}

    private val checkedMerges = LinkedHashSet<String>()
    private val checkedCategories = LinkedHashSet<String>()

    /** 체크 상태의 열쇠 — 목록 위치가 아니라 **내용**으로 잡는다(위 KDoc). */
    private fun keyOf(m: FieldLibraryAiOrganizer.MergeSuggestion): String =
        m.canonical + SEP + m.variants.joinToString(SEP)

    private fun keyOf(c: FieldLibraryAiOrganizer.CategorySuggestion): String =
        c.value + SEP + c.category

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        restoreReviewState(savedInstanceState)
        val context = requireContext()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.field_library_ai_review_title)
            .setView(cappedScrollView(context).apply { addView(buildReviewList(context)) })
            .setPositiveButton(R.string.field_library_ai_apply, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.field_library_ai_select_all, null)
            .create()

        dialog.setOnShowListener {
            // 전체 선택·적용 둘 다 기본 동작(닫기)을 쓰지 않는다 — 전자는 닫히면 안 되고,
            // 후자는 고른 것이 없을 때 닫히면 안 된다.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val turnOn = checkedMerges.size + checkedCategories.size < merges.size + categories.size
                setAllChecked(turnOn)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val picked = merges.filter { keyOf(it) in checkedMerges }
                val pickedCategories = categories.filter { keyOf(it) in checkedCategories }
                if (picked.isEmpty() && pickedCategories.isEmpty()) return@setOnClickListener
                onApply(picked, pickedCategories)
                dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                onDismissed()
                dismiss()
            }
        }
        return dialog
    }

    /**
     * 재생성 뒤 호출부가 제안을 다시 먹인다 — **체크 상태는 건드리지 않는다.**
     *
     * 창을 다시 짓지 않고 목록만 갈아 끼운다(다시 지으면 겹친다).
     */
    fun rebind(
        merges: List<FieldLibraryAiOrganizer.MergeSuggestion>,
        categories: List<FieldLibraryAiOrganizer.CategorySuggestion>,
        notices: List<String>
    ) {
        this.merges = merges
        this.categories = categories
        this.notices = notices
        val scroll = (dialog as? AlertDialog)?.findViewById<LinearLayout>(R.id.aiOrganizeReviewList)
        scroll?.let { rebuildInto(it, requireContext()) }
    }

    private fun buildReviewList(context: android.content.Context): LinearLayout {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val list = LinearLayout(context).apply {
            id = R.id.aiOrganizeReviewList
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        rebuildInto(list, context)
        return list
    }

    private fun rebuildInto(list: LinearLayout, context: android.content.Context) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        list.removeAllViews()

        fun header(text: String) {
            list.addView(TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, pad / 2, 0, pad / 4)
            })
        }

        if (notices.isNotEmpty()) header(notices.joinToString("\n"))

        if (merges.isNotEmpty()) {
            header(getString(R.string.field_library_ai_merges_section))
            for (m in merges) {
                val key = keyOf(m)
                list.addView(CheckBox(context).apply {
                    text = "${m.variants.joinToString(", ")} → ${m.canonical}" +
                        if (m.reason.isNotBlank()) "\n  (${m.reason})" else ""
                    isChecked = key in checkedMerges
                    setOnCheckedChangeListener { _, on ->
                        if (on) checkedMerges.add(key) else checkedMerges.remove(key)
                    }
                })
            }
        }

        if (categories.isNotEmpty()) {
            header(getString(R.string.field_library_ai_categories_section))
            for (c in categories) {
                val key = keyOf(c)
                list.addView(CheckBox(context).apply {
                    text = "${c.value} → ${c.category}"
                    isChecked = key in checkedCategories
                    setOnCheckedChangeListener { _, on ->
                        if (on) checkedCategories.add(key) else checkedCategories.remove(key)
                    }
                })
            }
        }
    }

    private fun setAllChecked(on: Boolean) {
        checkedMerges.clear()
        checkedCategories.clear()
        if (on) {
            merges.mapTo(checkedMerges) { keyOf(it) }
            categories.mapTo(checkedCategories) { keyOf(it) }
        }
        val list = (dialog as? AlertDialog)?.findViewById<LinearLayout>(R.id.aiOrganizeReviewList)
        list?.let { rebuildInto(it, requireContext()) }
    }

    /**
     * 사용자가 접었다 — 보관 중인 응답을 버리라고 알린다.
     * **회전은 여기 오지 않는다**(`onCancel`은 뒤로가기·바깥 탭에서만 불린다).
     */
    override fun onCancel(dialog: DialogInterface) {
        onDismissed()
        super.onCancel(dialog)
    }

    /** 검토 상태를 지킨다 — 제안은 ViewModel이 들지만 **어느 칸을 켰는지는 여기밖에 모른다.** */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_MERGES, ArrayList(checkedMerges))
        outState.putStringArrayList(STATE_CATEGORIES, ArrayList(checkedCategories))
    }

    private fun restoreReviewState(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        checkedMerges.clear()
        checkedMerges.addAll(state.getStringArrayList(STATE_MERGES).orEmpty())
        checkedCategories.clear()
        checkedCategories.addAll(state.getStringArrayList(STATE_CATEGORIES).orEmpty())
    }

    companion object {
        const val TAG = "AiOrganizeReviewSheet"

        /**
         * 열쇠의 구분자 — 사용자 값에 절대 못 들어가는 글자라야 두 제안의 열쇠가 겹치지 않는다.
         * **날바이트로 적지 않는다**(R-37): 소스에 날 NUL이 들어가면 git이 그 파일을 binary로
         * 보고 grep 기반 검사들이 그 파일을 통째로 건너뛴다 — 이 파일이 실제로 한 번 그렇게
         * 걸렸고, `tools/check_no_nul_bytes.sh`가 그 자리에서 잡았다.
         */
        private const val SEP = "\u0000"
        private const val STATE_MERGES = "checkedMerges"
        private const val STATE_CATEGORIES = "checkedCategories"
    }
}
