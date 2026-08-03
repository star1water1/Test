package com.novelcharacter.app.ui.character

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.ai.AiCreativity
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.CharacterFieldAiSuggester

/**
 * 창작도 칩 한 줄 (A-4 §6-8) — 비용 고지·보완 재요청·서술형 시트가 같은 줄을 쓴다.
 *
 * **값은 하나다**: 어디서 바꾸든 [AiPromptSettings.creativity]를 바꾼다. "이번 요청만"을
 * 나누면 화면마다 다른 값이 살아 있어 원칙 04가 금지한 상태가 된다. 칩 높이 한 줄이라
 * 기존 다이얼로그 레이아웃을 밀지 않는다 — 별도 진입점·별도 화면을 만들지 않는 것이
 * "다른 기능 사용에 방해되지 않는 위치"에 대한 답이다.
 *
 * **이 줄을 쓰는 자리가 곧 "설정 카드 밖"이다**(B-82) — 설정 화면의 창작도 카드는 이 줄이
 * 아니라 자기 라디오를 쓰고 [applyWithConflictGuard]만 공유한다. 그래서 전역 변경 고지는
 * [create]에만 붙는다. 새 자리가 이 줄을 쓰면 고지도 따라온다(감춤을 하드코딩하지 않는다).
 */
object CreativityChipRow {

    /**
     * 라벨 + 4칩 한 줄 생성. 선택 즉시 반영(저장 버튼 없음), 자유·실험은 충돌 가드를 거친다.
     *
     * 바꾼 값이 전역 기본값이라는 사실은 칩 아래 한 줄로 고지한다
     * ([AiCreativity.globalChangeNotice] — 되돌리면 그 줄도 사라진다).
     */
    fun create(fragment: Fragment): View {
        val context = fragment.requireContext()
        val settings = AiPromptSettings(context)
        val density = context.resources.displayMetrics.density
        // 이 자리를 연 시점의 값 — 고지 판정의 기준이다(되돌림을 알아보려면 시작점이 필요하다).
        val initial = settings.creativity

        val group = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = true
            chipSpacingHorizontal = (4 * density).toInt()
        }

        // B-82 고지 — 평소에는 자리를 차지하지 않는다(GONE). 칩 한 줄이 레이아웃을 밀지
        // 않는다는 §6-8의 전제를 바꾼 뒤에만, 바꾼 그 자리에서 깨뜨린다.
        val globalNote = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.accent))
            visibility = View.GONE
            setPadding(0, (2 * density).toInt(), 0, 0)
        }

        fun syncChips() {
            val current = settings.creativity
            for (i in 0 until group.childCount) {
                val chip = group.getChildAt(i) as? Chip ?: continue
                chip.isChecked = (chip.tag as? AiCreativity) == current
            }
            val changed = AiCreativity.globalChangeNotice(initial, current)
            globalNote.visibility = if (changed == null) View.GONE else View.VISIBLE
            if (changed != null) {
                globalNote.text =
                    context.getString(R.string.ai_creativity_global_change_note, changed.label)
            }
        }

        AiCreativity.entries.forEach { tier ->
            group.addView(Chip(context).apply {
                text = tier.label
                tag = tier
                isCheckable = true
                setEnsureMinTouchTargetSize(false)
                setOnClickListener {
                    if (settings.creativity == tier) {
                        syncChips() // 같은 칩 재탭으로 해제되는 것 방지
                        return@setOnClickListener
                    }
                    applyWithConflictGuard(fragment, tier) { syncChips() }
                }
            })
        }
        syncChips()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.ai_creativity_label)
                textSize = 13f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, 0, (8 * density).toInt(), 0)
            })
            addView(group)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(globalNote)
        }
    }

    /**
     * 선택 적용 + 충돌 가드 (§6-6) — 자유·실험 × 근거 강도 하한은 서로를 무력화한다
     * (창작도를 올리면 근거 얕은 값이 늘고, 하한이 그것을 전부 제외하면 돈만 나가고 결과가 없다).
     * 경고는 선택한 **그 자리**에서 뜨고, 교정 링크는 설정 화면 이동이 아니라 그 값을 바로
     * 바꾸는 다이얼로그다(원칙 04). [onDone]은 적용/취소 후 UI 동기화용 — 반드시 호출된다.
     */
    fun applyWithConflictGuard(fragment: Fragment, tier: AiCreativity, onDone: () -> Unit) {
        val context = fragment.requireContext()
        val settings = AiPromptSettings(context)
        val floor = settings.minConfidence
        val conflicts = (tier == AiCreativity.FREE || tier == AiCreativity.BOLD) && floor != null
        if (!conflicts) {
            settings.creativity = tier
            onDone()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_creativity_label)
            .setMessage(context.getString(R.string.ai_creativity_confidence_conflict, floor!!.label))
            .setPositiveButton(R.string.ai_creativity_lower_floor) { _, _ ->
                showFloorPicker(fragment, tier, onDone)
            }
            .setNegativeButton(R.string.ai_creativity_apply_anyway) { _, _ ->
                settings.creativity = tier
                onDone()
            }
            .setNeutralButton(R.string.cancel) { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }

    /** 근거 강도 하한을 그 자리에서 바꾸는 다이얼로그 — 고른 값으로 하한을 낮추고 창작도를 적용한다. */
    private fun showFloorPicker(fragment: Fragment, tier: AiCreativity, onDone: () -> Unit) {
        val context = fragment.requireContext()
        val settings = AiPromptSettings(context)
        val options = listOf<Pair<Int, CharacterFieldAiSuggester.Confidence?>>(
            R.string.ai_settings_confidence_all to null,
            R.string.ai_settings_confidence_medium to CharacterFieldAiSuggester.Confidence.MEDIUM
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_settings_confidence)
            .setItems(options.map { context.getString(it.first) }.toTypedArray()) { _, which ->
                settings.minConfidence = options[which].second
                settings.creativity = tier
                onDone()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }
}
