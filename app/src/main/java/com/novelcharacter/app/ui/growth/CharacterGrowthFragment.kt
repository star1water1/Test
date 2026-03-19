package com.novelcharacter.app.ui.growth

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.databinding.FragmentCharacterGrowthBinding
import kotlinx.coroutines.launch

class CharacterGrowthViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val app = application as NovelCharacterApp
    private val characterRepository = app.characterRepository
    private val universeRepository = app.universeRepository

    suspend fun getCharacter(id: Long): Character? = characterRepository.getCharacterById(id)
    suspend fun getStateChanges(characterId: Long): List<CharacterStateChange> =
        characterRepository.getChangesByCharacterList(characterId)
    suspend fun getFieldDefinitions(universeId: Long): List<FieldDefinition> =
        universeRepository.getFieldsByUniverseList(universeId)
    suspend fun getNovelById(id: Long) = app.novelRepository.getNovelById(id)
    suspend fun getAllCharacters() = characterRepository.getAllCharactersList()
}

class CharacterGrowthFragment : Fragment() {

    private var _binding: FragmentCharacterGrowthBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterGrowthViewModel by viewModels()

    private var characterId: Long = -1
    private var compareCharacterIds = mutableListOf<Long>()
    private var selectedFieldKeys = mutableSetOf<String>()

    // 라인 색상 팔레트
    private val lineColors = listOf(
        Color.parseColor("#5C6BC0"), // primary
        Color.parseColor("#E91E63"),
        Color.parseColor("#4CAF50"),
        Color.parseColor("#FF9800"),
        Color.parseColor("#00BCD4"),
        Color.parseColor("#9C27B0")
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterGrowthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        characterId = arguments?.getLong("characterId", -1L) ?: -1L
        if (characterId == -1L) {
            findNavController().popBackStack()
            return
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        setupChart()
        loadData()
    }

    private fun setupChart() {
        binding.lineChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            legend.isEnabled = true
            legend.textSize = 11f

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val year = value.toInt()
                    return if (year < 0) "BC ${-year}" else "$year"
                }
            }

            axisRight.isEnabled = false
            axisLeft.granularity = 1f

            setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
                override fun onValueSelected(e: Entry?, h: Highlight?) {
                    e?.let {
                        val year = it.x.toInt()
                        val value = it.y
                        binding.tooltipText.visibility = View.VISIBLE
                        binding.tooltipText.text = getString(R.string.year_label_format, year) + ": " + String.format("%.1f", value)
                    }
                }
                override fun onNothingSelected() {
                    binding.tooltipText.visibility = View.GONE
                }
            })
        }
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val character = viewModel.getCharacter(characterId) ?: return@launch
            binding.toolbar.title = getString(R.string.growth_chart_title, character.name)

            // 세계관 필드 정의 가져오기
            val novel = character.novelId?.let { viewModel.getNovelById(it) }
            val universeId = novel?.universeId
            val fields = if (universeId != null) viewModel.getFieldDefinitions(universeId) else emptyList()

            // NUMBER, GRADE 타입 필드만 필터
            val numericFields = fields.filter { it.type == "NUMBER" || it.type == "GRADE" }

            if (numericFields.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.lineChart.visibility = View.GONE
                return@launch
            }

            setupFieldChips(numericFields)
            setupCompareButton()

            // 기본적으로 첫 번째 필드 선택
            if (selectedFieldKeys.isEmpty() && numericFields.isNotEmpty()) {
                selectedFieldKeys.add(numericFields.first().key)
            }

            updateChart()
        }
    }

    private fun setupFieldChips(fields: List<FieldDefinition>) {
        binding.fieldChipGroup.removeAllViews()
        fields.forEach { field ->
            val chip = Chip(requireContext()).apply {
                text = field.name
                isCheckable = true
                isChecked = selectedFieldKeys.contains(field.key)
                tag = field.key
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedFieldKeys.add(field.key)
                    else selectedFieldKeys.remove(field.key)
                    viewLifecycleOwner.lifecycleScope.launch { updateChart() }
                }
            }
            binding.fieldChipGroup.addView(chip)
        }
    }

    private fun setupCompareButton() {
        binding.btnAddCompare.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val allChars = viewModel.getAllCharacters()
                    .filter { it.id != characterId && it.id !in compareCharacterIds }

                if (allChars.isEmpty()) return@launch
                if (!isAdded) return@launch

                val names = allChars.map { it.name }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.growth_add_compare)
                    .setItems(names) { _, which ->
                        compareCharacterIds.add(allChars[which].id)
                        viewLifecycleOwner.lifecycleScope.launch { updateChart() }
                    }
                    .show()
            }
        }
    }

    private suspend fun updateChart() {
        val allCharIds = mutableListOf(characterId)
        allCharIds.addAll(compareCharacterIds)

        val dataSets = mutableListOf<LineDataSet>()
        var colorIndex = 0

        for (charId in allCharIds) {
            val char = viewModel.getCharacter(charId) ?: continue
            val changes = viewModel.getStateChanges(charId)

            for (fieldKey in selectedFieldKeys) {
                val fieldChanges = changes.filter { it.fieldKey == fieldKey }
                if (fieldChanges.isEmpty()) continue

                val entries = fieldChanges.map { change ->
                    val value = parseNumericValue(change.newValue, fieldKey)
                    Entry(change.year.toFloat(), value)
                }.sortedBy { it.x }

                if (entries.isEmpty()) continue

                val label = "${char.name} - $fieldKey"
                val dataSet = LineDataSet(entries, label).apply {
                    color = lineColors[colorIndex % lineColors.size]
                    setCircleColor(color)
                    lineWidth = 2.5f
                    circleRadius = 4f
                    setDrawValues(false)
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                    cubicIntensity = 0.15f
                }
                dataSets.add(dataSet)
                colorIndex++
            }
        }

        if (dataSets.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.lineChart.visibility = View.GONE
            return
        }

        binding.emptyState.visibility = View.GONE
        binding.lineChart.visibility = View.VISIBLE
        binding.lineChart.data = LineData(dataSets.toList())
        binding.lineChart.invalidate()
        binding.lineChart.animateX(500)
    }

    private fun parseNumericValue(value: String, fieldKey: String): Float {
        // 숫자인 경우 직접 파싱
        value.toFloatOrNull()?.let { return it }

        // GRADE 타입: 기본 매핑 사용 (커스텀 매핑은 FieldDefinition config에서)
        val defaultGradeMap = mapOf(
            "SSS" to 15f, "SS" to 13f, "S+" to 11f, "S" to 10f, "S-" to 9f,
            "A+" to 8.5f, "A" to 8f, "A-" to 7.5f,
            "B+" to 7f, "B" to 6f, "B-" to 5.5f,
            "C+" to 5f, "C" to 4f, "C-" to 3.5f,
            "D+" to 3f, "D" to 2f, "D-" to 1.5f,
            "E" to 1f, "F" to 0f
        )

        return defaultGradeMap[value.uppercase()] ?: 0f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
