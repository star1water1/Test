package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.databinding.BottomSheetBodyGeneratorBinding
import com.novelcharacter.app.util.BodyGenerator
import com.novelcharacter.app.util.BodyGenerator.BodyCategory

/**
 * 신체 수치 자동 생성 BottomSheet.
 * - 기본 생성: 키/체형 옵션 선택 → 랜덤 생성
 * - 상대 생성: 기준 캐릭터 대비 조절
 * - 분포 인식: 작품 내 캐릭터 분포 표시
 * - 즉시 미리보기: 생성 결과 분석
 */
class BodyGeneratorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBodyGeneratorBinding? = null
    private val binding get() = _binding!!

    /** 생성된 수치를 필드에 적용하는 콜백 */
    var onApply: ((BodyGenerator.GeneratedBody) -> Unit)? = null

    /** 같은 작품의 캐릭터 목록 (상대 생성 + 분포 분석용) */
    var novelCharacters: List<Character> = emptyList()

    /** BWH 파싱된 캐릭터 데이터 (characterId → Triple<bust,waist,hip>) */
    var characterBwh: Map<Long, Triple<Double, Double, Double>> = emptyMap()

    /** 캐릭터별 키 (characterId → height) */
    var characterHeights: Map<Long, Double> = emptyMap()

    /** 캐릭터별 몸무게 (characterId → weight) */
    var characterWeights: Map<Long, Double> = emptyMap()

    /** 분석 설정 */
    var analysisConfig: BodyAnalysisConfig = BodyAnalysisConfig.DEFAULT

    private var currentGenerated: BodyGenerator.GeneratedBody? = null
    private val preset = BodyGenerator.GenerationPreset()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetBodyGeneratorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeightOptions()
        setupBodyTypeOptions()
        if (novelCharacters.isNotEmpty()) {
            setupBaseCharacterSpinner()
            setupRelativeOptions()
        } else {
            // 캐릭터 없으면 상대 생성 섹션 숨김
            binding.spinnerBaseCharacter.visibility = View.GONE
            binding.relativeOptionsContainer.visibility = View.GONE
            // 라벨도 숨김
            (binding.spinnerBaseCharacter.parent as? ViewGroup)?.let { parent ->
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child is android.widget.TextView && child.text == getString(R.string.body_gen_relative)) {
                        child.visibility = View.GONE
                        break
                    }
                }
            }
        }
        showDistribution()

        binding.btnGenerate.setOnClickListener { generateAndPreview() }
        binding.btnRegenerate.setOnClickListener { generateAndPreview() }
        binding.btnApply.setOnClickListener {
            currentGenerated?.let { body ->
                onApply?.invoke(body)
                dismiss()
            }
        }
    }

    private fun setupHeightOptions() {
        val density = resources.displayMetrics.density
        for ((i, option) in preset.heightOptions.withIndex()) {
            val rb = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = option.label
                textSize = 13f
                tag = i
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            binding.rgHeight.addView(rb)
            if (i == 1) rb.isChecked = true // "보통" 기본 선택
        }
    }

    private fun setupBodyTypeOptions() {
        val density = resources.displayMetrics.density
        for ((i, option) in preset.bodyTypeOptions.withIndex()) {
            val rb = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = option.label
                textSize = 13f
                tag = i
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            binding.rgBodyType.addView(rb)
            if (i == 2) rb.isChecked = true // "보통" 기본 선택
        }
    }

    private fun setupBaseCharacterSpinner() {
        val names = mutableListOf(getString(R.string.body_gen_no_base))
        names.addAll(novelCharacters.map { it.name })
        binding.spinnerBaseCharacter.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, names
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerBaseCharacter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                binding.relativeOptionsContainer.visibility = if (pos > 0) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupRelativeOptions() {
        val density = resources.displayMetrics.density
        for ((i, entry) in BodyGenerator.RELATIVE_MULTIPLIERS.withIndex()) {
            val rbH = RadioButton(requireContext()).apply {
                id = View.generateViewId(); text = entry.first; textSize = 11f; tag = i
                setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)
            }
            binding.rgRelativeHeight.addView(rbH)
            if (i == 2) rbH.isChecked = true

            val rbV = RadioButton(requireContext()).apply {
                id = View.generateViewId(); text = entry.first; textSize = 11f; tag = i
                setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)
            }
            binding.rgRelativeVolume.addView(rbV)
            if (i == 2) rbV.isChecked = true
        }
    }

    private fun showDistribution() {
        if (characterBwh.isEmpty()) {
            binding.distributionText.visibility = View.GONE
            return
        }

        var slim = 0; var normal = 0; var voluptuous = 0
        for ((charId, bwh) in characterBwh) {
            val height = characterHeights[charId]
            when (BodyGenerator.categorize(bwh.first, bwh.second, bwh.third, height)) {
                BodyCategory.SLIM -> slim++
                BodyCategory.NORMAL -> normal++
                BodyCategory.VOLUPTUOUS -> voluptuous++
            }
        }
        val total = slim + normal + voluptuous
        val summary = BodyGenerator.DistributionSummary(slim, normal, voluptuous, total)
        val recLabel = when (summary.recommendation) {
            BodyCategory.SLIM -> getString(R.string.body_gen_cat_slim)
            BodyCategory.NORMAL -> getString(R.string.body_gen_cat_normal)
            BodyCategory.VOLUPTUOUS -> getString(R.string.body_gen_cat_voluptuous)
            null -> ""
        }

        binding.distributionText.text = getString(
            R.string.body_gen_distribution,
            voluptuous, normal, slim, if (recLabel.isNotEmpty()) " ← $recLabel ${getString(R.string.body_gen_recommend)}" else ""
        )
        binding.distributionText.visibility = View.VISIBLE
    }

    private fun generateAndPreview() {
        val baseCharPos = binding.spinnerBaseCharacter.selectedItemPosition

        val body = if (baseCharPos > 0 && baseCharPos - 1 < novelCharacters.size) {
            // 상대 생성
            val baseChar = novelCharacters[baseCharPos - 1]
            val baseBwh = characterBwh[baseChar.id] ?: return
            val baseH = characterHeights[baseChar.id] ?: 163.0
            val baseW = characterWeights[baseChar.id] ?: 55.0

            val hIdx = getSelectedIndex(binding.rgRelativeHeight)
            val vIdx = getSelectedIndex(binding.rgRelativeVolume)
            val hMul = BodyGenerator.RELATIVE_MULTIPLIERS.getOrNull(hIdx)?.second ?: 1.0
            val vMul = BodyGenerator.RELATIVE_MULTIPLIERS.getOrNull(vIdx)?.second ?: 1.0

            BodyGenerator.generateRelative(baseH, baseBwh.second, baseBwh.first, baseBwh.third, baseW, hMul, vMul)
        } else {
            // 기본 생성
            val hIdx = getSelectedIndex(binding.rgHeight)
            val bIdx = getSelectedIndex(binding.rgBodyType)
            val hOpt = preset.heightOptions.getOrNull(hIdx) ?: preset.heightOptions[1]
            val bOpt = preset.bodyTypeOptions.getOrNull(bIdx) ?: preset.bodyTypeOptions[2]
            BodyGenerator.generate(hOpt, bOpt)
        }

        currentGenerated = body
        showPreview(body)
    }

    private fun showPreview(body: BodyGenerator.GeneratedBody) {
        val result = BodyGenerator.analyzeGenerated(body, analysisConfig)

        binding.previewMeasurements.text = getString(
            R.string.body_gen_preview_measurements,
            body.height.toInt(), body.weight.toInt(), body.bwhString
        )
        binding.previewTags.text = result.bodyTags.joinToString(" · ").ifEmpty { result.bodyType ?: "" }

        val details = buildString {
            result.cupSize?.let { append("${getString(R.string.cup_size_label)}: $it") }
            result.goldenRatioScore?.let { append("  ${getString(R.string.body_golden_ratio_label)}: ${"%.0f".format(it)}/100") }
        }
        binding.previewDetails.text = details
        binding.previewSilhouette.text = result.silhouetteDescription ?: ""

        binding.previewContainer.visibility = View.VISIBLE
        binding.actionButtons.visibility = View.VISIBLE
    }

    private fun getSelectedIndex(rg: RadioGroup): Int {
        val checkedId = rg.checkedRadioButtonId
        if (checkedId == -1) return 0
        val rb = rg.findViewById<RadioButton>(checkedId) ?: return 0
        return (rb.tag as? Int) ?: 0
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
