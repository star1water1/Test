package com.novelcharacter.app.ui.character

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition

class DynamicFieldRenderer(
    private val containerGetter: () -> LinearLayout,
    private val contextGetter: () -> Context,
    private val resourcesGetter: () -> Resources,
    private val getString: (Int) -> String,
    private val getStringWithArg: (Int, Any) -> String
) {

    fun displayDynamicFields(
        fields: List<FieldDefinition>,
        values: List<CharacterFieldValue>
    ) {
        val container = containerGetter()
        container.removeAllViews()

        val valueMap = values.associateBy { it.fieldDefinitionId }

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
                val displayValue = if (isCalculated) {
                    if (fieldValue.isEmpty()) {
                        getStringWithArg(R.string.auto_calculated_label, field.name)
                    } else {
                        contextGetter().getString(R.string.auto_calculated_value, field.name, fieldValue)
                    }
                } else {
                    "${field.name}: ${fieldValue.ifEmpty { "-" }}"
                }

                val rowView = TextView(context).apply {
                    text = displayValue
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
