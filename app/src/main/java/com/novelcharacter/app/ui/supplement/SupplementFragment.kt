package com.novelcharacter.app.ui.supplement

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.FragmentSupplementBinding
import com.novelcharacter.app.databinding.ItemSupplementCharacterBinding
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SupplementFragment : Fragment() {

    private var _binding: FragmentSupplementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SupplementViewModel by viewModels()

    private var adapter: SupplementAdapter? = null
    private var criteria: SupplementCriteria = SupplementCriteria()

    // 스피너 상태 관리
    private var universes: List<Universe> = emptyList()
    private var filteredNovels: List<Novel> = emptyList()
    private var suppressSpinnerEvents = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSupplementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        criteria = SupplementCriteria.load(requireContext())

        setupRecyclerView()
        setupFilterChips()
        setupSortChips()
        setupButtons()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // 최초 로드 + 보충 편집 후 돌아왔을 때 데이터 갱신
        criteria = SupplementCriteria.load(requireContext())
        viewModel.loadData(criteria)
    }

    private fun setupRecyclerView() {
        adapter = SupplementAdapter { target ->
            // 개별 캐릭터 클릭 → 편집 화면으로 이동
            val bundle = Bundle().apply {
                putLong("characterId", target.character.id)
            }
            findNavController().navigateSafe(R.id.supplementFragment, R.id.characterEditFragment, bundle)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupFilterChips() {
        val chipGroup = binding.issueFilterChipGroup
        chipGroup.removeAllViews()

        // "전체" 칩
        val allChip = createFilterChip(getString(R.string.supplement_filter_all), null)
        allChip.isChecked = true
        chipGroup.addView(allChip)

        // 각 이슈 타입 칩 (활성화된 기준만)
        val issueChips = mutableListOf<Pair<SupplementIssue, String>>()
        if (criteria.checkImages) issueChips.add(SupplementIssue.NO_IMAGE to getString(R.string.supplement_check_image))
        if (criteria.checkMemo) issueChips.add(SupplementIssue.NO_MEMO to getString(R.string.supplement_check_memo))
        if (criteria.checkAliases) issueChips.add(SupplementIssue.NO_ALIASES to getString(R.string.supplement_check_aliases))
        if (criteria.checkNovel) issueChips.add(SupplementIssue.NO_NOVEL to getString(R.string.supplement_check_novel))
        if (criteria.checkTags) issueChips.add(SupplementIssue.NO_TAGS to getString(R.string.supplement_check_tags))
        if (criteria.checkCustomFields) issueChips.add(SupplementIssue.INCOMPLETE_FIELDS to getString(R.string.supplement_check_fields))
        if (criteria.checkRelationships) issueChips.add(SupplementIssue.NO_RELATIONSHIPS to getString(R.string.supplement_check_relationships))
        if (criteria.checkEvents) issueChips.add(SupplementIssue.NO_EVENTS to getString(R.string.supplement_check_events))
        if (criteria.checkFactions) issueChips.add(SupplementIssue.NO_FACTIONS to getString(R.string.supplement_check_factions))

        for ((issue, label) in issueChips) {
            chipGroup.addView(createFilterChip(label, issue))
        }
    }

    private fun createFilterChip(text: String, issue: SupplementIssue?): Chip {
        return Chip(requireContext()).apply {
            this.text = text
            isCheckable = true
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    viewModel.setIssueFilter(issue)
                }
            }
        }
    }

    private fun setupSortChips() {
        val sortGroup = binding.sortChipGroup
        sortGroup.removeAllViews()

        val sortOptions = listOf(
            SupplementViewModel.SortMode.ISSUES_DESC to getString(R.string.supplement_sort_issues),
            SupplementViewModel.SortMode.NAME_ASC to getString(R.string.supplement_sort_name),
            SupplementViewModel.SortMode.COMPLETION_ASC to getString(R.string.supplement_sort_completion)
        )

        for ((mode, label) in sortOptions) {
            val chip = Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isChecked = mode == viewModel.sortMode
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        viewModel.setSortMode(mode)
                    }
                }
            }
            sortGroup.addView(chip)
        }
    }

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnStartSupplement.setOnClickListener { startSupplement() }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.loadingProgress.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.targets.observe(viewLifecycleOwner) { targets ->
            adapter?.submitList(targets)
            updateUI(targets)
        }

        viewModel.totalCount.observe(viewLifecycleOwner) { total ->
            updateUI(viewModel.targets.value ?: emptyList())
        }

        viewModel.universeList.observe(viewLifecycleOwner) { univs ->
            universes = univs
            setupUniverseSpinner(univs)
        }

        viewModel.novelList.observe(viewLifecycleOwner) { novels ->
            // 초기 로드 시 작품 스피너도 설정
            val uId = viewModel.selectedUniverseId
            filteredNovels = if (uId != null) novels.filter { it.universeId == uId } else novels
            setupNovelSpinner(filteredNovels)
        }
    }

    private fun setupUniverseSpinner(univs: List<Universe>) {
        val items = mutableListOf("전체 세계관")
        items.addAll(univs.map { it.name })

        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        suppressSpinnerEvents = true
        binding.spinnerUniverse.adapter = spinnerAdapter

        // 저장된 세계관 필터 복원
        val savedUniverseId = viewModel.selectedUniverseId
        val restoredPos = if (savedUniverseId != null) {
            val idx = univs.indexOfFirst { it.id == savedUniverseId }
            if (idx >= 0) idx + 1 else 0
        } else 0
        binding.spinnerUniverse.setSelection(restoredPos)
        suppressSpinnerEvents = false

        // 작품 스피너는 novelList 옵저버에서 복원됨 (이 시점에 novelList 미로드)

        binding.spinnerUniverse.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerEvents) return
                val selectedUniverse = if (position == 0) null else univs[position - 1]
                viewModel.setUniverseFilter(selectedUniverse?.id)

                // 작품 스피너 갱신
                filteredNovels = if (selectedUniverse != null) {
                    viewModel.getNovelsForUniverse(selectedUniverse.id)
                } else {
                    viewModel.novelList.value ?: emptyList()
                }
                setupNovelSpinner(filteredNovels)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupNovelSpinner(novels: List<Novel>) {
        val items = mutableListOf("전체 작품")
        items.addAll(novels.map { it.title })

        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        suppressSpinnerEvents = true
        binding.spinnerNovel.adapter = spinnerAdapter

        // 저장된 작품 필터 복원
        val savedNovelId = viewModel.selectedNovelId
        val restoredNovelPos = if (savedNovelId != null) {
            val idx = novels.indexOfFirst { it.id == savedNovelId }
            if (idx >= 0) idx + 1 else 0
        } else 0
        binding.spinnerNovel.setSelection(restoredNovelPos)
        suppressSpinnerEvents = false

        binding.spinnerNovel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerEvents) return
                val selectedNovel = if (position == 0) null else novels[position - 1]
                viewModel.setNovelFilter(selectedNovel?.id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateUI(targets: List<SupplementTarget>) {
        val total = viewModel.totalCount.value ?: 0
        val targetCount = targets.size
        val completedCount = total - targetCount
        val completionRate = if (total > 0) completedCount * 100 / total else 100

        if (total == 0) {
            binding.contentLayout.visibility = View.GONE
            binding.emptyText.visibility = View.VISIBLE
            binding.emptyText.text = getString(R.string.supplement_no_characters)
            return
        }

        binding.contentLayout.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE

        binding.targetCountText.text = targetCount.toString()
        binding.totalCountText.text = total.toString()
        binding.completionRateText.text = getString(R.string.supplement_completion_format, completionRate, completedCount, total)
        binding.completionProgressBar.progress = completionRate

        if (targetCount == 0) {
            binding.startDescText.text = getString(R.string.supplement_all_complete)
            binding.btnStartSupplement.isEnabled = false
            binding.listHeaderText.text = getString(R.string.supplement_no_targets)
        } else {
            binding.startDescText.text = getString(R.string.supplement_start_desc, targetCount)
            binding.btnStartSupplement.isEnabled = true
            binding.listHeaderText.text = getString(R.string.supplement_target_count) + " ($targetCount)"
        }
    }

    private fun startSupplement() {
        val targets = viewModel.targets.value ?: return
        if (targets.isEmpty()) return

        val ids = targets.map { it.character.id }.toLongArray()
        // 각 캐릭터별 이슈 라벨을 직렬화하여 전달
        val issueLabelsArray = targets.map { t ->
            t.issues.joinToString(", ") { it.label }
        }.toTypedArray()
        val bundle = Bundle().apply {
            putLong("characterId", ids[0])
            putBoolean("supplementMode", true)
            putInt("supplementIndex", 0)
            putLongArray("supplementIds", ids)
            putStringArray("supplementIssueLabelsArray", issueLabelsArray)
            putString("supplementIssueLabels", issueLabelsArray.getOrNull(0) ?: "")
        }

        findNavController().navigateSafe(R.id.supplementFragment, R.id.characterEditFragment, bundle)
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_supplement_settings, null)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.supplement_settings_title))
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // 스위치 바인딩
        val switchImage = dialogView.findViewById<MaterialSwitch>(R.id.switchImage)
        val switchMemo = dialogView.findViewById<MaterialSwitch>(R.id.switchMemo)
        val switchAliases = dialogView.findViewById<MaterialSwitch>(R.id.switchAliases)
        val switchNovel = dialogView.findViewById<MaterialSwitch>(R.id.switchNovel)
        val switchTags = dialogView.findViewById<MaterialSwitch>(R.id.switchTags)
        val switchFields = dialogView.findViewById<MaterialSwitch>(R.id.switchFields)
        val switchRelationships = dialogView.findViewById<MaterialSwitch>(R.id.switchRelationships)
        val switchEvents = dialogView.findViewById<MaterialSwitch>(R.id.switchEvents)
        val switchFactions = dialogView.findViewById<MaterialSwitch>(R.id.switchFactions)
        val thresholdSlider = dialogView.findViewById<Slider>(R.id.thresholdSlider)
        val thresholdLayout = dialogView.findViewById<View>(R.id.thresholdLayout)
        val thresholdLabel = dialogView.findViewById<android.widget.TextView>(R.id.thresholdLabel)

        // 현재 값 설정
        switchImage.isChecked = criteria.checkImages
        switchMemo.isChecked = criteria.checkMemo
        switchAliases.isChecked = criteria.checkAliases
        switchNovel.isChecked = criteria.checkNovel
        switchTags.isChecked = criteria.checkTags
        switchFields.isChecked = criteria.checkCustomFields
        switchRelationships.isChecked = criteria.checkRelationships
        switchEvents.isChecked = criteria.checkEvents
        switchFactions.isChecked = criteria.checkFactions
        thresholdSlider.value = criteria.fieldCompletionThreshold.toFloat()
        thresholdLayout.visibility = if (criteria.checkCustomFields) View.VISIBLE else View.GONE
        thresholdLabel.text = getString(R.string.supplement_field_threshold_format, criteria.fieldCompletionThreshold)

        // 슬라이더 연동
        switchFields.setOnCheckedChangeListener { _, isChecked ->
            thresholdLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        thresholdSlider.addOnChangeListener { _, value, _ ->
            thresholdLabel.text = getString(R.string.supplement_field_threshold_format, value.toInt())
        }

        // 기본값 초기화
        dialogView.findViewById<View>(R.id.btnResetDefaults).setOnClickListener {
            val defaults = SupplementCriteria()
            switchImage.isChecked = defaults.checkImages
            switchMemo.isChecked = defaults.checkMemo
            switchAliases.isChecked = defaults.checkAliases
            switchNovel.isChecked = defaults.checkNovel
            switchTags.isChecked = defaults.checkTags
            switchFields.isChecked = defaults.checkCustomFields
            switchRelationships.isChecked = defaults.checkRelationships
            switchEvents.isChecked = defaults.checkEvents
            switchFactions.isChecked = defaults.checkFactions
            thresholdSlider.value = defaults.fieldCompletionThreshold.toFloat()
        }

        // 닫기
        dialogView.findViewById<View>(R.id.btnClose).setOnClickListener {
            // 저장
            criteria = SupplementCriteria(
                checkImages = switchImage.isChecked,
                checkMemo = switchMemo.isChecked,
                checkAliases = switchAliases.isChecked,
                checkNovel = switchNovel.isChecked,
                checkTags = switchTags.isChecked,
                checkCustomFields = switchFields.isChecked,
                fieldCompletionThreshold = thresholdSlider.value.toInt(),
                checkRelationships = switchRelationships.isChecked,
                checkEvents = switchEvents.isChecked,
                checkFactions = switchFactions.isChecked
            )
            SupplementCriteria.save(requireContext(), criteria)

            // 필터 칩 재생성 및 데이터 리로드
            setupFilterChips()
            viewModel.loadData(criteria)
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ========== 내부 어댑터 ==========

    private class SupplementAdapter(
        private val onClick: (SupplementTarget) -> Unit
    ) : RecyclerView.Adapter<SupplementAdapter.ViewHolder>() {

        private var items: List<SupplementTarget> = emptyList()
        private val gson = com.google.gson.Gson()
        private val imagePathsType = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
        private var scope: kotlinx.coroutines.CoroutineScope? = null

        fun submitList(list: List<SupplementTarget>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            scope?.cancel()
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
            )
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            scope?.cancel()
            scope = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSupplementCharacterBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position + 1)
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(
            private val binding: ItemSupplementCharacterBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(target: SupplementTarget, index: Int) {
                binding.indexText.text = index.toString()
                binding.characterName.text = target.character.displayName

                // 미흡 항목 요약
                val issueLabels = target.issues.joinToString(", ") { it.label }
                binding.issueText.text = itemView.context.getString(R.string.supplement_issue_summary, issueLabels)

                // 이미지 — IO 스레드에서 비동기 디코딩
                val imagePaths = try {
                    gson.fromJson<List<String>>(target.character.imagePaths, imagePathsType) ?: emptyList()
                } catch (_: Exception) { emptyList() }

                binding.characterImage.setImageResource(R.drawable.ic_character_placeholder)
                if (imagePaths.isNotEmpty()) {
                    val path = imagePaths[0]
                    val appDir = itemView.context.filesDir
                    val charId = target.character.id
                    scope?.launch {
                        val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val file = java.io.File(path)
                                if (!file.exists() || !file.canonicalPath.startsWith(appDir.canonicalPath + java.io.File.separator)) return@withContext null
                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(path, options)
                                val targetSize = (48 * itemView.context.resources.displayMetrics.density).toInt()
                                var sampleSize = 1
                                val halfH = options.outHeight / 2
                                val halfW = options.outWidth / 2
                                while (sampleSize < 1024 && halfH / sampleSize >= targetSize && halfW / sampleSize >= targetSize) {
                                    sampleSize *= 2
                                }
                                options.inSampleSize = sampleSize
                                options.inJustDecodeBounds = false
                                BitmapFactory.decodeFile(path, options)
                            } catch (_: Exception) { null }
                        }
                        // ViewHolder가 재활용되었을 수 있으므로 검증
                        if (bitmap != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            val item = items.getOrNull(bindingAdapterPosition)
                            if (item?.character?.id == charId) {
                                binding.characterImage.setImageBitmap(bitmap)
                            }
                        }
                    }
                }

                // 완성률 배지
                val ctx = itemView.context
                if (target.fieldCompletion >= 0) {
                    val pct = target.fieldCompletion.toInt()
                    binding.completionBadge.text = "${pct}%"
                    val color = when {
                        pct < 30 -> ContextCompat.getColor(ctx, R.color.supplement_badge_high)
                        pct < 70 -> ContextCompat.getColor(ctx, R.color.supplement_badge_mid)
                        else -> ContextCompat.getColor(ctx, R.color.supplement_badge_low)
                    }
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                    }
                    binding.completionBadge.background = bg
                } else {
                    binding.completionBadge.text = ctx.getString(R.string.supplement_field_na)
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    }
                    binding.completionBadge.background = bg
                }

                itemView.setOnClickListener { onClick(target) }
            }
        }
    }
}
