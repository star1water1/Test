package com.novelcharacter.app.ui.character

import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.novelcharacter.app.R
import com.novelcharacter.app.ui.common.applyRange
import com.novelcharacter.app.util.SliderRange
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
    private val getString: (Int, Any?) -> String,
    private val isBindingAlive: () -> Boolean = { true }
) {
    var isTimeSliderExpanded = false
    var isTimeViewActive = false
    var currentSliderYear: Int? = null
    var cachedFields: List<FieldDefinition> = emptyList()
    var cachedValues: List<CharacterFieldValue> = emptyList()
    var cachedPercentileData: Map<Long, DynamicFieldRenderer.PercentileInfo> = emptyMap()
    var cachedCalculatedResults: Map<Long, String> = emptyMap()

    private val timeStateResolver = TimeStateResolver()
    private var applyTimeViewJob: Job? = null
    private var updateSliderJob: Job? = null

    fun cancelJob() {
        applyTimeViewJob?.cancel()
        applyTimeViewJob = null
        updateSliderJob?.cancel()
        updateSliderJob = null
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
                binding.yearLabel.text = getString(R.string.year_label_format, year)
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
            fieldRenderer.displayDynamicFields(cachedFields, cachedValues, cachedPercentileData, cachedCalculatedResults)
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
            if (!isBindingAlive()) return@launch

            val resolvedState = timeStateResolver.resolveStateAtYear(
                baseValues, allChanges, year, cachedFields
            )

            if (!isBindingAlive()) return@launch
            fieldRenderer.displayResolvedFields(cachedFields, resolvedState)
        }
    }

    fun updateSliderRange() {
        // 동시 호출 시 이전 갱신을 취소해 슬라이더 범위가 뒤섞이지 않도록 함
        updateSliderJob?.cancel()
        updateSliderJob = viewLifecycleOwner.lifecycleScope.launch {
            val changes = viewModel.getChangesByCharacterList(characterId)
            if (!isBindingAlive()) return@launch
            try {
                // 시간축과 무관한 시스템 상태변경(year=0) 제외: __alive, __std_year_link 등
                val timeRelevantChanges = changes.filter { it.year != 0 }
                if (timeRelevantChanges.isEmpty()) {
                    binding.yearSlider.isEnabled = false
                    binding.minYearLabel.text = ""
                    binding.maxYearLabel.text = ""
                    return@launch
                }

                val years = timeRelevantChanges.map { it.year }
                val minYear = years.minOrNull() ?: return@launch
                val maxYear = years.maxOrNull() ?: return@launch

                binding.yearSlider.isEnabled = true

                // **범위·눈금·값은 한 벌로 나온다**(`SliderRange`) — 눈금만 폭에 따라 바꾸고
                // 경계를 실측값 그대로 넣으면 `(끝 - 시작)`이 눈금의 배수가 아니게 되고,
                // 그 어김은 **그리기 패스**에서 터져 이 `try/catch`가 원리적으로 못 잡는다
                // (연도 폭 1,000년이 넘고 10의 배수가 아니면 화면이 열리자마자 죽었다).
                val spec = SliderRange.of(minYear, maxYear, currentSliderYear)
                binding.yearSlider.applyRange(spec)

                // 라벨은 **실측 최소·최대**를 말한다 — 격자에 맞춘 경계가 아니라(사용자가 아는 수).
                binding.minYearLabel.text = getString(R.string.slider_min_year, minYear)
                binding.maxYearLabel.text = getString(R.string.slider_max_year, maxYear)
                binding.yearLabel.text = getString(R.string.year_label_format, spec.value.toInt())
            } catch (_: Exception) {
                // Fragment view may have been destroyed during suspend
            }
        }
    }
}
