package com.novelcharacter.app.ui.character

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.FormulaEvaluator

class DynamicFieldRenderer(
    private val containerGetter: () -> LinearLayout,
    private val contextGetter: () -> Context,
    private val resourcesGetter: () -> Resources,
    private val getString: (Int) -> String,
    private val getStringWithArg: (Int, Any) -> String
) {

    /**
     * 백분위 정보. 필드별로 작품/세계관 스코프의 상위 퍼센트를 보관.
     */
    data class PercentileInfo(
        val novelPercentile: Float? = null,
        val universePercentile: Float? = null
    )

    fun displayDynamicFields(
        fields: List<FieldDefinition>,
        values: List<CharacterFieldValue>,
        percentileData: Map<Long, PercentileInfo> = emptyMap(),
        preComputedCalculated: Map<Long, String>? = null
    ) {
        val container = containerGetter()
        container.removeAllViews()

        val valueMap = values.associateBy { it.fieldDefinitionId }

        // CALCULATED 필드 수식 평가 (사전 계산 결과가 있으면 재사용)
        val calculatedResults = preComputedCalculated
            ?: evaluateCalculatedFields(fields, valueMap)

        val grouped = fields
            .sortedBy { it.displayOrder }
            .groupBy { it.groupName }

        val context = contextGetter()
        val density = resourcesGetter().displayMetrics.density

        for ((groupName, groupFields) in grouped) {
            val card = createCard(context, density)

            val cardContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = createGroupTitle(context, density, groupName)
            cardContent.addView(titleView)

            for (field in groupFields) {
                val fieldValue = valueMap[field.id]?.value ?: ""
                val isCalculated = field.type == "CALCULATED"
                val format = DisplayFormat.fromConfig(field.config)

                if (!isCalculated && fieldValue.isNotEmpty() &&
                    (format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST)) {
                    // 필드 이름 라벨
                    val labelView = TextView(context).apply {
                        text = field.name
                        textSize = 13f
                        setTextColor(context.getColor(R.color.text_secondary))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (2 * density).toInt()
                        }
                    }
                    cardContent.addView(labelView)

                    val items = fieldValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (format == DisplayFormat.COMMA_LIST) {
                        val chipGroup = ChipGroup(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (6 * density).toInt()
                            }
                        }
                        for (item in items) {
                            chipGroup.addView(Chip(context).apply {
                                text = item
                                isClickable = false
                                isCheckable = false
                            })
                        }
                        cardContent.addView(chipGroup)
                    } else {
                        // BULLET_LIST
                        val bulletText = items.joinToString("\n") { "• $it" }
                        val bulletView = TextView(context).apply {
                            text = bulletText
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (6 * density).toInt()
                            }
                        }
                        cardContent.addView(bulletView)
                    }
                } else if (!isCalculated && format == DisplayFormat.MULTILINE && fieldValue.isNotEmpty()) {
                    val labelView = TextView(context).apply {
                        text = field.name
                        textSize = 13f
                        setTextColor(context.getColor(R.color.text_secondary))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (2 * density).toInt()
                        }
                    }
                    cardContent.addView(labelView)
                    val multiView = TextView(context).apply {
                        text = fieldValue
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (6 * density).toInt()
                        }
                    }
                    cardContent.addView(multiView)
                } else {
                    // PLAIN (default) / CALCULATED
                    val displayValue: String
                    val percentileSuffix: String

                    if (isCalculated) {
                        val computedValue = calculatedResults[field.id]
                        val baseValue = computedValue ?: fieldValue.ifEmpty { null }
                        if (baseValue != null) {
                            // "(자동 계산)"과 백분위를 단일 괄호로 통합
                            val infoList = mutableListOf("자동 계산")
                            percentileData[field.id]?.let { info ->
                                info.novelPercentile?.let { infoList.add("작품 상위 ${"%.0f".format(it)}%") }
                                info.universePercentile?.let { infoList.add("세계관 상위 ${"%.0f".format(it)}%") }
                            }
                            displayValue = "${field.name}: $baseValue (${infoList.joinToString(", ")})"
                        } else {
                            displayValue = getStringWithArg(R.string.auto_calculated_label, field.name)
                        }
                        percentileSuffix = "" // CALCULATED은 위에서 통합 완료
                    } else {
                        displayValue = "${field.name}: ${fieldValue.ifEmpty { "-" }}"
                        // 백분위 표기 추가 (NUMBER, GRADE 등)
                        percentileSuffix = percentileData[field.id]?.let { info ->
                            val parts = mutableListOf<String>()
                            info.novelPercentile?.let { parts.add("작품 상위 ${"%.0f".format(it)}%") }
                            info.universePercentile?.let { parts.add("세계관 상위 ${"%.0f".format(it)}%") }
                            if (parts.isNotEmpty()) " (${parts.joinToString(" / ")})" else null
                        } ?: ""
                    }

                    val rowView = TextView(context).apply {
                        text = displayValue + percentileSuffix
                        textSize = 14f
                        if (isCalculated) {
                            setTextColor(context.getColor(R.color.text_secondary))
                            setTypeface(null, Typeface.ITALIC)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (4 * density).toInt()
                        }
                    }
                    cardContent.addView(rowView)
                }
            }

            card.addView(cardContent)
            container.addView(card)
        }
    }

    fun displayResolvedFields(
        fields: List<FieldDefinition>,
        resolvedState: Map<String, String>
    ) {
        val container = containerGetter()
        container.removeAllViews()

        val context = contextGetter()
        val density = resourcesGetter().displayMetrics.density

        // Show special keys (birth, death, alive, age) if present
        val specialFields = mutableListOf<Pair<String, String>>()
        resolvedState[CharacterStateChange.KEY_BIRTH]?.let {
            if (it.isNotEmpty()) specialFields.add(getString(R.string.birth) to it)
        }
        resolvedState[CharacterStateChange.KEY_DEATH]?.let {
            if (it.isNotEmpty()) specialFields.add(getString(R.string.death) to it)
        }
        resolvedState[CharacterStateChange.KEY_ALIVE]?.let {
            if (it.isNotEmpty()) {
                val displayVal = when (it) {
                    "true" -> getString(R.string.alive)
                    "false" -> getString(R.string.dead)
                    else -> it
                }
                specialFields.add(getString(R.string.alive_status) to displayVal)
            }
        }
        resolvedState[CharacterStateChange.KEY_AGE]?.let {
            if (it.isNotEmpty()) specialFields.add(getString(R.string.age) to it)
        }

        if (specialFields.isNotEmpty()) {
            val specialCard = createCard(context, density)

            val specialContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val specialTitle = TextView(context).apply {
                text = getString(R.string.time_view)
                setTypeface(null, Typeface.BOLD)
                textSize = 16f
                setTextColor(context.getColor(R.color.primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }
            specialContent.addView(specialTitle)

            for ((name, value) in specialFields) {
                val rowView = TextView(context).apply {
                    text = "$name: $value"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (4 * density).toInt()
                    }
                }
                specialContent.addView(rowView)
            }

            specialCard.addView(specialContent)
            container.addView(specialCard)
        }

        // Group fields and display with resolved values
        val grouped = fields
            .sortedBy { it.displayOrder }
            .groupBy { it.groupName }

        for ((groupName, groupFields) in grouped) {
            val card = createCard(context, density)

            val cardContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = createGroupTitle(context, density, groupName)
            cardContent.addView(titleView)

            for (field in groupFields) {
                val resolvedValue = resolvedState[field.key] ?: ""
                val displayValue = resolvedValue.ifEmpty { "-" }

                val rowView = TextView(context).apply {
                    text = "${field.name}: $displayValue"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (4 * density).toInt()
                    }
                }
                cardContent.addView(rowView)
            }

            card.addView(cardContent)
            container.addView(card)
        }
    }

    private fun createCard(context: Context, density: Float): CardView {
        return CardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            radius = 8 * density
            cardElevation = 2 * density
            setContentPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
        }
    }

    /**
     * CALCULATED 필드의 수식을 FormulaEvaluator로 평가하여 결과를 반환.
     * @return fieldDefinitionId → 계산된 값 문자열
     */
    private fun evaluateCalculatedFields(
        fields: List<FieldDefinition>,
        valueMap: Map<Long, CharacterFieldValue>
    ): Map<Long, String> {
        val calculatedFields = fields.filter { it.type == "CALCULATED" }
        if (calculatedFields.isEmpty()) return emptyMap()

        // fieldKey → value 맵 구성
        val fieldKeyValues = mutableMapOf<String, String>()
        for (field in fields) {
            val v = valueMap[field.id]?.value ?: ""
            if (v.isNotBlank()) fieldKeyValues[field.key] = v
        }

        val results = mutableMapOf<Long, String>()
        val evaluator = FormulaEvaluator(fieldKeyValues, fields)
        for (field in calculatedFields) {
            val formula = try {
                org.json.JSONObject(field.config).optString("formula", "")
            } catch (_: Exception) { "" }
            if (formula.isBlank()) continue
            try {
                val value = evaluator.evaluate(formula)
                if (!value.isNaN() && !value.isInfinite()) {
                    results[field.id] = if (value == value.toLong().toDouble()) {
                        value.toLong().toString()
                    } else {
                        "%.2f".format(value)
                    }
                }
            } catch (_: Exception) { /* 평가 실패 시 기존 동작 유지 */ }
        }
        return results
    }

    private fun createGroupTitle(context: Context, density: Float, groupName: String): TextView {
        return TextView(context).apply {
            text = groupName.ifEmpty { getString(R.string.other) }
            setTypeface(null, Typeface.BOLD)
            textSize = 16f
            setTextColor(context.getColor(R.color.primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
    }
}
