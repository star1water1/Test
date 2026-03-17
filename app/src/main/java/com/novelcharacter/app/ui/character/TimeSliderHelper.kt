package com.novelcharacter.app.ui.character

import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.util.TimeStateResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TimeSliderHelper(
    private val binding: FragmentCharacterDetailBinding,
    private val viewModel: CharacterViewModel,
    private val viewLifecycleOwner: LifecycleOwner,
    private val characterId: Long,
    private val fieldRenderer: DynamicFieldRenderer,
    private val getString: (Int, Any?) -> String
) {
    var isTimeSliderExpanded = false
    var isTimeViewActive = false
    var currentSliderYear: Int? = null
    var cachedFields: List<FieldDefinition> = emptyList()
    var cachedValues: List<CharacterFieldValue> = emptyList()

    private val timeStateResolver = TimeStateResolver()
    private var applyTimeViewJob: Job? = null

    fun cancelJob() {
        applyTimeViewJob?.cancel()
        applyTimeViewJob = null
    }

    fun setup() {
        binding.timeSliderContainer.visibility = View.VISIBLE

        binding.timeSliderHeader.setOnClickListener {
            isTimeSliderExpanded = !isTimeSliderExpanded
            if (isTimeSliderExpanded) {
                binding.timeSliderContent.visibility = View.VISIBLE
                binding.timeSliderToggleIcon.rotation = 180f
                binding.btnResetTimeView.visibility = if (isTimeViewActive) View.VISIBLE else View.GONE
            } else {
                binding.timeSliderContent.visibility = View.GONE
                binding.timeSliderToggleIcon.rotation = 0f
                binding.btnResetTimeView.visibility = View.GONE
            }
        }

        binding.yearSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val year = value.toInt()
                currentSliderYear = year
                binding.yearLabel.text = "${year}년"
                isTimeViewActive = true
                binding.btnResetTimeView.visibility = View.VISIBLE
                binding.timeViewIndicator.visibility = View.VISIBLE
                binding.timeViewIndicator.text = getString(R.string.time_view_indicator, year)
                applyTimeView(year)
            }
        }

        binding.yearSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val year = slider.value.toInt()
                applyTimeView(year)
            }
        })

        binding.btnResetTimeView.setOnClickListener {
            resetTimeView()
        }
    }

    fun resetTimeView() {
        isTimeViewActive = false
        currentSliderYear = null
        binding.btnResetTimeView.visibility = View.GONE
        binding.timeViewIndicator.visibility = View.GONE

        if (cachedFields.isNotEmpty()) {
            fieldRenderer.displayDynamicFields(cachedFields, cachedValues)
        }
    }

    fun applyTimeView(year: Int) {
        applyTimeViewJob?.cancel()
        applyTimeViewJob = viewLifecycleOwner.lifecycleScope.launch {
            if (cachedFields.isEmpty()) return@launch

            val baseValues = mutableMapOf<String, String>()
            for (value in cachedValues) {
                val fieldDef = cachedFields.find { it.id == value.fieldDefinitionId }
                if (fieldDef != null) {
                    baseValues[fieldDef.key] = value.value
                }
            }

            val allChanges = viewModel.getChangesByCharacterList(characterId)

            val resolvedState = timeStateResolver.resolveStateAtYear(
                baseValues, allChanges, year, cachedFields
            )

            fieldRenderer.displayResolvedFields(cachedFields, resolvedState)
        }
    }

    fun updateSliderRange() {
        viewLifecycleOwner.lifecycleScope.launch {
            val changes = viewModel.getChangesByCharacterList(characterId)
            if (changes.isEmpty()) {
                binding.yearSlider.isEnabled = false
                binding.minYearLabel.text = ""
                binding.maxYearLabel.text = ""
                return@launch
            }

            val years = changes.map { it.year }
            val minYear = years.minOrNull() ?: return@launch
            val maxYear = years.maxOrNull() ?: return@launch

            val adjustedMin = minYear.toFloat()
            val adjustedMax = if (maxYear <= minYear) (minYear + 1).toFloat() else maxYear.toFloat()

            binding.yearSlider.isEnabled = true

            // Reset stepSize first to avoid validation errors when range changes
            binding.yearSlider.stepSize = 0f

            // Expand range before narrowing to avoid value-out-of-range errors
            val oldFrom = binding.yearSlider.valueFrom
            val oldTo = binding.yearSlider.valueTo
            binding.yearSlider.valueFrom = minOf(oldFrom, adjustedMin)
            binding.yearSlider.valueTo = maxOf(oldTo, adjustedMax)

            // Now narrow to actual range
            binding.yearSlider.valueFrom = adjustedMin
            binding.yearSlider.valueTo = adjustedMax

            binding.minYearLabel.text = getString(R.string.slider_min_year, minYear)
            binding.maxYearLabel.text = getString(R.string.slider_max_year, maxYear)
            val totalRange = adjustedMax - adjustedMin
            val newStepSize = when {
                totalRange > 10000 -> 100f
                totalRange > 1000 -> 10f
                else -> 1f
            }

            // Set value before stepSize to ensure alignment
            val sliderYear = currentSliderYear
            if (sliderYear == null) {
                binding.yearSlider.value = adjustedMin
                binding.yearLabel.text = "${minYear}년"
            } else {
                val clampedYear = sliderYear.coerceIn(minYear, adjustedMax.toInt())
                binding.yearSlider.value = clampedYear.toFloat()
                binding.yearLabel.text = "${clampedYear}년"
            }

            // Align value to step before setting stepSize
            if (newStepSize > 0f) {
                val currentVal = binding.yearSlider.value
                val aligned = adjustedMin + (((currentVal - adjustedMin) / newStepSize).toInt() * newStepSize)
                binding.yearSlider.value = aligned.coerceIn(adjustedMin, adjustedMax)
            }
            binding.yearSlider.stepSize = newStepSize
        }
    }
}
