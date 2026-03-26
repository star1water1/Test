package com.novelcharacter.app.ui.character

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.util.navigateSafe
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.content.res.ColorStateList
import android.graphics.Color
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.repository.FactionRepository
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.share.CharacterCardRenderer
import com.novelcharacter.app.share.PdfExporter
import com.novelcharacter.app.ui.adapter.TimelineAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CharacterDetailFragment : Fragment() {

    private var _binding: FragmentCharacterDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    private var characterId: Long = -1L

    private var cachedCharacter: Character? = null

    // Helpers
    private lateinit var fieldRenderer: DynamicFieldRenderer
    private lateinit var timeSliderHelper: TimeSliderHelper
    private lateinit var stateChangeHelper: StateChangeHelper
    private lateinit var relationshipHelper: RelationshipHelper

    private val factionRepository: FactionRepository by lazy {
        (requireActivity().application as NovelCharacterApp).factionRepository
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        characterId = arguments?.getLong("characterId", -1L) ?: -1L
        appDir = requireContext().filesDir

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.toolbar.inflateMenu(R.menu.character_detail_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_growth_chart -> { navigateToGrowthChart(); true }
                R.id.action_share_card -> { shareCharacterCard(); true }
                R.id.action_share_pdf -> { sharePdf(); true }
                else -> false
            }
        }

        binding.fabEdit.setOnClickListener {
            val bundle = Bundle().apply { putLong("characterId", characterId) }
            findNavController().navigateSafe(R.id.characterDetailFragment, R.id.characterEditFragment, bundle)
        }

        initHelpers()

        timeSliderHelper.setup()
        stateChangeHelper.setup()
        relationshipHelper.setup()
        observeCharacter()
        observeEvents()
        stateChangeHelper.observe()
        relationshipHelper.observe()
        observeFactions()
        loadCharacterStats()
    }

    private fun initHelpers() {
        fieldRenderer = DynamicFieldRenderer(
            containerGetter = { binding.dynamicFieldsContainer },
            contextGetter = { requireContext() },
            resourcesGetter = { resources },
            getString = { id -> getString(id) },
            getStringWithArg = { id, arg -> getString(id, arg) }
        )

        timeSliderHelper = TimeSliderHelper(
            binding = binding,
            viewModel = viewModel,
            viewLifecycleOwner = viewLifecycleOwner,
            characterId = characterId,
            fieldRenderer = fieldRenderer,
            getString = { id, arg -> getString(id, arg) },
            isBindingAlive = { _binding != null }
        )

        stateChangeHelper = StateChangeHelper(
            binding = binding,
            viewModel = viewModel,
            viewLifecycleOwner = viewLifecycleOwner,
            characterId = characterId,
            contextGetter = { requireContext() },
            getString = { id -> getString(id) },
            cachedFieldsGetter = { timeSliderHelper.cachedFields },
            onSliderUpdate = { timeSliderHelper.updateSliderRange() }
        )

        relationshipHelper = RelationshipHelper(
            binding = binding,
            viewModel = viewModel,
            viewLifecycleOwner = viewLifecycleOwner,
            characterId = characterId,
            contextGetter = { requireContext() },
            getString = { id -> getString(id) },
            getFormattedString = { id, args -> getString(id, *args) },
            navController = { findNavController() },
            isBindingAlive = { _binding != null }
        )
    }

    // ===== Character display =====

    private fun observeCharacter() {
        viewModel.getCharacterById(characterId).observe(viewLifecycleOwner) { character ->
            character?.let {
                cachedCharacter = it
                displayCharacter(it)
            }
        }

        viewModel.getTagsByCharacter(characterId).observe(viewLifecycleOwner) { tags ->
            if (tags.isNotEmpty()) {
                binding.detailTags.visibility = View.VISIBLE
                binding.detailTags.text = tags.joinToString("  ") { "#${it.tag}" }
            } else {
                binding.detailTags.visibility = View.GONE
            }
        }
    }

    private fun observeEvents() {
        val timelineAdapter = TimelineAdapter(
            onClick = { /* 연표 클릭 시 */ },
            onLongClick = { /* 연표 롱클릭 시 */ },
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            loadCharactersForEvent = { eventId -> viewModel.getCharactersForEvent(eventId) }
        )
        binding.eventsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.eventsRecyclerView.adapter = timelineAdapter

        viewModel.getEventsForCharacter(characterId).observe(viewLifecycleOwner) { events ->
            timelineAdapter.submitEventList(events)
        }

        // 사건 추가 버튼
        binding.btnAddEvent.setOnClickListener {
            val eventHelper = com.novelcharacter.app.util.EventEditDialogHelper(
                context = requireContext(),
                lifecycleScope = viewLifecycleOwner.lifecycleScope,
                layoutInflater = layoutInflater
            )
            val dataProvider = object : com.novelcharacter.app.util.EventEditDialogHelper.DataProvider {
                override suspend fun getAllNovelsList() = viewModel.getAllNovelsList()
                override suspend fun getAllCharactersList() = viewModel.getAllCharactersList()
                override suspend fun getCharacterIdsForEvent(eventId: Long) = viewModel.getCharacterIdsForEvent(eventId)
                override suspend fun getNovelIdsForEvent(eventId: Long) = viewModel.getNovelIdsForEvent(eventId)
                override fun insertEvent(event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>) { viewModel.insertEvent(event, characterIds, novelIds) }
                override fun updateEvent(event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>) { viewModel.updateEvent(event, characterIds, novelIds) }
                override suspend fun getEventsInScope(novelIds: List<Long>, universeId: Long?): List<com.novelcharacter.app.data.model.TimelineEvent> {
                    return when {
                        novelIds.isNotEmpty() ->
                            novelIds.flatMap { viewModel.getEventsByNovelList(it) }.distinctBy { it.id }
                        universeId != null -> viewModel.getEventsByUniverseList(universeId)
                        else -> viewModel.getAllEventsList()
                    }
                }
                override fun updateEventAndShiftOthers(
                    event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>,
                    shiftDirection: com.novelcharacter.app.util.EventEditDialogHelper.ShiftDirection,
                    delta: Int, originalNovelIds: List<Long>, originalUniverseId: Long?
                ) {
                    viewModel.updateEventAndShiftOthers(event, characterIds, novelIds, shiftDirection, delta, originalNovelIds, originalUniverseId)
                }
            }
            eventHelper.showEventDialog(
                dataProvider = dataProvider,
                preSelectedCharacterIds = setOf(characterId),
                preSelectedNovelIds = listOfNotNull(cachedCharacter?.novelId)
            )
        }
    }

    private fun observeFactions() {
        factionRepository.getMembershipsByCharacter(characterId).observe(viewLifecycleOwner) { memberships ->
            viewLifecycleOwner.lifecycleScope.launch {
                if (_binding == null) return@launch
                binding.factionChipGroup.removeAllViews()

                if (memberships.isEmpty()) {
                    binding.factionChipGroup.visibility = View.GONE
                    binding.factionEmptyText.visibility = View.VISIBLE
                    return@launch
                }

                binding.factionChipGroup.visibility = View.VISIBLE
                binding.factionEmptyText.visibility = View.GONE

                for (membership in memberships) {
                    val faction = factionRepository.getFactionById(membership.factionId) ?: continue
                    if (_binding == null) return@launch
                    val ctx = context ?: return@launch

                    val isDeparted = membership.leaveType == FactionMembership.LEAVE_DEPARTED
                    val chipText = if (isDeparted) "${faction.name} (탈퇴)" else faction.name

                    val chip = Chip(ctx).apply {
                        text = chipText
                        isClickable = true
                        isCheckable = false

                        val factionColor = try {
                            Color.parseColor(faction.color)
                        } catch (_: Exception) {
                            Color.parseColor("#2196F3")
                        }

                        if (isDeparted) {
                            // Outlined style for departed members
                            chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
                            chipStrokeColor = ColorStateList.valueOf(factionColor)
                            chipStrokeWidth = 2f * resources.displayMetrics.density
                            setTextColor(factionColor)
                        } else {
                            chipBackgroundColor = ColorStateList.valueOf(factionColor)
                            // Determine text color based on background luminance
                            val luminance = (0.299 * Color.red(factionColor) +
                                    0.587 * Color.green(factionColor) +
                                    0.114 * Color.blue(factionColor)) / 255.0
                            setTextColor(if (luminance > 0.5) Color.BLACK else Color.WHITE)
                        }

                        setOnClickListener {
                            val bundle = Bundle().apply {
                                putLong("universeId", faction.universeId)
                            }
                            findNavController().navigateSafe(
                                R.id.characterDetailFragment,
                                R.id.factionManageFragment,
                                bundle
                            )
                        }
                    }
                    binding.factionChipGroup.addView(chip)
                }
            }
        }
    }

    private fun displayCharacter(character: Character) {
        binding.toolbar.title = character.name

        binding.detailName.text = character.name

        // firstName/lastName 표시
        if (character.firstName.isNotBlank() || character.lastName.isNotBlank()) {
            binding.detailFullName.visibility = View.VISIBLE
            val parts = listOf(character.lastName, character.firstName).filter { it.isNotBlank() }
            binding.detailFullName.text = parts.joinToString(" ")
        } else {
            binding.detailFullName.visibility = View.GONE
        }

        // 별칭 칩 표시
        val aliases = character.aliases
        if (aliases.isNotEmpty()) {
            binding.aliasChipGroup.visibility = View.VISIBLE
            binding.aliasChipGroup.removeAllViews()
            for (alias in aliases) {
                val chip = Chip(requireContext()).apply {
                    text = alias
                    isClickable = false
                    isCheckable = false
                }
                binding.aliasChipGroup.addView(chip)
            }
        } else {
            binding.aliasChipGroup.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val novel = character.novelId?.let { viewModel.getNovelById(it) }
            if (_binding == null) return@launch
            binding.detailNovel.text = getString(R.string.novel_label_format, novel?.title ?: getString(R.string.novel_unassigned))

            if (character.memo.isNotBlank()) {
                binding.memoCard.visibility = View.VISIBLE
                binding.detailMemo.text = character.memo
            } else {
                binding.memoCard.visibility = View.GONE
            }

            if (_binding == null) return@launch
            // 표준 년도 연동 토글
            if (novel?.standardYear != null) {
                binding.stdYearLinkSwitch.visibility = View.VISIBLE
                val stdHelper = com.novelcharacter.app.util.StandardYearSyncHelper(
                    (requireActivity().application as com.novelcharacter.app.NovelCharacterApp).characterRepository,
                    (requireActivity().application as com.novelcharacter.app.NovelCharacterApp).universeRepository
                )
                val isLinked = stdHelper.isLinked(character.id)
                if (_binding == null) return@launch
                binding.stdYearLinkSwitch.setOnCheckedChangeListener(null)
                binding.stdYearLinkSwitch.isChecked = isLinked
                binding.stdYearLinkSwitch.setOnCheckedChangeListener { _, checked ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        stdHelper.setLinked(character.id, checked)
                    }
                }
            } else {
                binding.stdYearLinkSwitch.visibility = View.GONE
            }

            val universeId = novel?.universeId
            if (universeId != null) {
                val fields = viewModel.getFieldsByUniverseList(universeId)
                if (_binding == null) return@launch
                val values = viewModel.getValuesByCharacterList(character.id)
                if (_binding == null) return@launch
                timeSliderHelper.cachedFields = fields
                timeSliderHelper.cachedValues = values

                // CALCULATED 필드 사전 계산 (단일 계산, 결과 공유)
                val valueMap = values.associateBy { it.fieldDefinitionId }
                val calculatedResults = evaluateCalculatedFields(fields, valueMap)

                // 백분위 계산 (사전 계산된 CALCULATED 결과 사용)
                val percentileData = computePercentileData(fields, values, character, novel, universeId, calculatedResults)

                // 캐싱 (TimeSlider 리셋 시 백분위 보존)
                timeSliderHelper.cachedPercentileData = percentileData
                timeSliderHelper.cachedCalculatedResults = calculatedResults

                // 체형 분석 순위 데이터 계산
                fieldRenderer.bodyRankingInfo = computeBodyRanking(fields, values, character, novel)

                val sliderYear = timeSliderHelper.currentSliderYear
                if (timeSliderHelper.isTimeViewActive && sliderYear != null) {
                    timeSliderHelper.applyTimeView(sliderYear)
                } else {
                    fieldRenderer.displayDynamicFields(fields, values, percentileData, calculatedResults)
                }
            } else {
                timeSliderHelper.cachedFields = emptyList()
                timeSliderHelper.cachedValues = emptyList()
                binding.dynamicFieldsContainer.removeAllViews()
            }
        }

        setupImages(character.imagePaths)
    }

    /**
     * CALCULATED 필드의 수식을 FormulaEvaluator로 평가하여 결과를 반환.
     * displayCharacter에서 한 번만 계산하여 백분위 계산과 표시 양쪽에 공유한다.
     * @return fieldDefinitionId → 계산된 값 문자열
     */
    private fun evaluateCalculatedFields(
        fields: List<com.novelcharacter.app.data.model.FieldDefinition>,
        valueMap: Map<Long, com.novelcharacter.app.data.model.CharacterFieldValue>
    ): Map<Long, String> {
        val calculatedFields = fields.filter { it.type == "CALCULATED" }
        if (calculatedFields.isEmpty()) return emptyMap()
        val fieldKeyValues = mutableMapOf<String, String>()
        for (field in fields) {
            val v = valueMap[field.id]?.value ?: ""
            if (v.isNotBlank()) fieldKeyValues[field.key] = v
        }
        val evaluator = com.novelcharacter.app.util.FormulaEvaluator(fieldKeyValues, fields)
        val results = mutableMapOf<Long, String>()
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
                    } else "%.2f".format(value)
                }
            } catch (_: Exception) { }
        }
        return results
    }

    /**
     * 백분위 데이터 계산.
     * 각 숫자형 필드에 대해 config의 percentile 설정을 확인하고,
     * 활성화된 스코프(작품/세계관)에 대해 상위 %를 계산한다.
     * CALCULATED 필드는 사전 계산된 결과(calculatedResults)를 사용하여
     * 표시 값과 백분위 값의 일관성을 보장한다.
     */
    private suspend fun computePercentileData(
        fields: List<com.novelcharacter.app.data.model.FieldDefinition>,
        values: List<com.novelcharacter.app.data.model.CharacterFieldValue>,
        character: Character,
        novel: com.novelcharacter.app.data.model.Novel?,
        universeId: Long,
        calculatedResults: Map<Long, String> = emptyMap()
    ): Map<Long, DynamicFieldRenderer.PercentileInfo> {
        val result = mutableMapOf<Long, DynamicFieldRenderer.PercentileInfo>()
        val valueMap = values.associateBy { it.fieldDefinitionId }
        val numericTypes = setOf("NUMBER", "CALCULATED", "BODY_SIZE", "GRADE")

        for (field in fields) {
            if (field.type !in numericTypes) continue

            // Parse percentile config
            val percentileConfig = try {
                org.json.JSONObject(field.config).optJSONObject("percentile")
            } catch (_: Exception) { null }
            if (percentileConfig == null || !percentileConfig.optBoolean("enabled", false)) continue

            val scopes = try {
                val arr = percentileConfig.optJSONArray("scopes")
                if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
            } catch (_: Exception) { emptyList() }
            if (scopes.isEmpty()) continue

            // GRADE 필드: 등급 문자 → 수치 매핑 추출
            val gradeMap: Map<String, Double>? = if (field.type == "GRADE") {
                try {
                    val cfg = org.json.JSONObject(field.config)
                    val gradesObj = cfg.optJSONObject("grades")
                    if (gradesObj != null) {
                        gradesObj.keys().asSequence().associateWith { gradesObj.getDouble(it) }
                    } else null
                } catch (_: Exception) { null }
            } else null

            // Get current character's numeric value
            // CALCULATED: 사전 계산된 결과 사용 (display와 동일한 값 보장)
            val myValue: Double? = when (field.type) {
                "CALCULATED" -> calculatedResults[field.id]?.toDoubleOrNull()
                "GRADE" -> {
                    val rawVal = valueMap[field.id]?.value ?: ""
                    gradeMap?.get(rawVal)
                }
                else -> valueMap[field.id]?.value?.toDoubleOrNull()
            }

            if (myValue == null || myValue.isNaN() || myValue.isInfinite()) continue

            var novelPercentile: Float? = null
            var universePercentile: Float? = null

            val isCalculated = field.type == "CALCULATED"
            val isGrade = field.type == "GRADE"

            // 문자열 값을 수치로 변환하는 함수 (GRADE는 등급 매핑 사용)
            val toNumeric: (String) -> Double? = if (isGrade && gradeMap != null) {
                { s -> gradeMap[s] }
            } else {
                { s -> s.toDoubleOrNull() }
            }

            if ("novel" in scopes && novel != null) {
                val allValues: List<Double> = if (isCalculated) {
                    try {
                        computeCalculatedValuesForScope(field, novel.id, universeId)
                    } catch (_: Exception) { emptyList() }
                } else {
                    viewModel.getFieldValuesForNovel(novel.id, field.id)
                        .mapNotNull(toNumeric)
                }
                if (allValues.isNotEmpty()) {
                    val higher = allValues.count { it > myValue }
                    novelPercentile = ((higher.toFloat() / allValues.size) * 100f)
                    novelPercentile = novelPercentile.coerceIn(0f, 100f)
                    if (novelPercentile < 1f) novelPercentile = 1f
                }
            }

            if ("universe" in scopes) {
                val allValues: List<Double> = if (isCalculated) {
                    try {
                        computeCalculatedValuesForScope(field, null, universeId)
                    } catch (_: Exception) { emptyList() }
                } else {
                    viewModel.getFieldValuesForUniverse(universeId, field.id)
                        .mapNotNull(toNumeric)
                }
                if (allValues.isNotEmpty()) {
                    val higher = allValues.count { it > myValue }
                    universePercentile = ((higher.toFloat() / allValues.size) * 100f)
                    universePercentile = universePercentile.coerceIn(0f, 100f)
                    if (universePercentile < 1f) universePercentile = 1f
                }
            }

            if (novelPercentile != null || universePercentile != null) {
                result[field.id] = DynamicFieldRenderer.PercentileInfo(novelPercentile, universePercentile)
            }
        }
        return result
    }

    /**
     * CALCULATED 필드의 백분위 계산을 위해 범위 내 모든 캐릭터의 수식 결과를 계산한다.
     * DB에 저장되지 않는 CALCULATED 값을 각 캐릭터별로 FormulaEvaluator로 평가한다.
     */
    private suspend fun computeCalculatedValuesForScope(
        field: com.novelcharacter.app.data.model.FieldDefinition,
        novelId: Long?,
        universeId: Long?
    ): List<Double> {
        val formula = try {
            org.json.JSONObject(field.config).optString("formula", "")
        } catch (_: Exception) { "" }
        if (formula.isBlank()) return emptyList()

        val characters = if (novelId != null) {
            viewModel.getCharactersByNovelList(novelId)
        } else if (universeId != null) {
            viewModel.getCharactersByUniverseList(universeId)
        } else return emptyList()

        val universeIdForFields = universeId
            ?: characters.firstOrNull()?.let { c ->
                c.novelId?.let { viewModel.getNovelById(it)?.universeId }
            }
        if (universeIdForFields == null) return emptyList()

        val allFields = viewModel.getFieldsByUniverseList(universeIdForFields)

        return characters.mapNotNull { char ->
            val charValues = viewModel.getValuesByCharacterList(char.id)
            val charKeyValues = mutableMapOf<String, String>()
            for (f in allFields) {
                val v = charValues.firstOrNull { it.fieldDefinitionId == f.id }?.value ?: ""
                if (v.isNotBlank()) charKeyValues[f.key] = v
            }
            val eval = com.novelcharacter.app.util.FormulaEvaluator(charKeyValues, allFields)
            try { eval.evaluate(formula).takeIf { it.isFinite() } } catch (_: Exception) { null }
        }
    }

    // ===== Images =====

    private fun setupImages(imagePathsJson: String) {
        val imagePaths: List<String> = try {
            GSON.fromJson(imagePathsJson, IMAGE_PATHS_TYPE) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (imagePaths.isEmpty()) {
            binding.imageViewPager.visibility = View.GONE
            return
        }

        binding.imageViewPager.visibility = View.VISIBLE
        binding.imageViewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val imageView = ImageView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                return object : RecyclerView.ViewHolder(imageView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val imageView = holder.itemView as ImageView
                // Cancel previous image loading job for this ViewHolder
                (imageView.getTag(R.id.image_load_job) as? kotlinx.coroutines.Job)?.cancel()
                imageView.setImageResource(R.drawable.ic_character_placeholder)
                val path = imagePaths[position]
                imageView.setOnClickListener {
                    val bundle = Bundle().apply {
                        putString("imagePaths", imagePathsJson)
                        putInt("startPosition", position)
                    }
                    findNavController().navigateSafe(R.id.characterDetailFragment, R.id.imageViewerFragment, bundle)
                }
                val boundPosition = position
                val job = viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeSampledBitmap(path, 1024, 1024)
                    }
                    if (bitmap != null && holder.bindingAdapterPosition == boundPosition && isAdded) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
                imageView.setTag(R.id.image_load_job, job)
            }

            override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
                val imageView = holder.itemView as ImageView
                (imageView.getTag(R.id.image_load_job) as? kotlinx.coroutines.Job)?.cancel()
                imageView.setImageDrawable(null)
            }

            override fun getItemCount() = imagePaths.size
        }

        // 이미지가 2장 이상이면 랜덤 위치에서 시작
        if (imagePaths.size > 1) {
            binding.imageViewPager.setCurrentItem(
                kotlin.random.Random.nextInt(imagePaths.size), false
            )
        }
    }

    private var appDir: java.io.File? = null
    private fun getAppDir(): java.io.File {
        return appDir ?: requireContext().filesDir.also { appDir = it }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val file = java.io.File(path)
            val dir = appDir ?: return null
            if (!file.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator)) {
                return null
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (inSampleSize < 1024 && halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 같은 작품 내 다른 캐릭터들의 BODY_SIZE 데이터를 조회하여
     * 현재 캐릭터의 가슴/허리/엉덩이 순위를 계산한다.
     */
    private suspend fun computeBodyRanking(
        fields: List<com.novelcharacter.app.data.model.FieldDefinition>,
        values: List<com.novelcharacter.app.data.model.CharacterFieldValue>,
        character: Character,
        novel: com.novelcharacter.app.data.model.Novel?
    ): com.novelcharacter.app.util.RankingInfo? {
        val novelId = novel?.id ?: return null

        // BODY_SIZE 필드 찾기
        val bodySizeField = fields.find {
            com.novelcharacter.app.data.model.SemanticRole.fromConfig(it.config) ==
                com.novelcharacter.app.data.model.SemanticRole.BODY_SIZE
        } ?: fields.find { it.type == "BODY_SIZE" } ?: return null

        // 현재 캐릭터의 BWH 파싱
        val valueMap = values.associateBy { it.fieldDefinitionId }
        val myBwh = parseBwh(valueMap[bodySizeField.id]?.value) ?: return null

        // 같은 작품의 모든 캐릭터 BWH 수집
        val allCharacters = viewModel.getCharactersByNovelList(novelId)
        if (allCharacters.size <= 1) return null

        val allBusts = mutableListOf<Double>()
        val allWaists = mutableListOf<Double>()
        val allHips = mutableListOf<Double>()

        for (char in allCharacters) {
            val charValues = viewModel.getValuesByCharacterList(char.id)
            val bwhValue = charValues.firstOrNull { it.fieldDefinitionId == bodySizeField.id }?.value
            val bwh = parseBwh(bwhValue)
            if (bwh != null) {
                allBusts.add(bwh.first)
                allWaists.add(bwh.second)
                allHips.add(bwh.third)
            }
        }

        if (allBusts.size <= 1) return null

        return com.novelcharacter.app.util.RankingInfo(
            bustRank = com.novelcharacter.app.util.BodyAnalysisHelper.computeRank(myBwh.first, allBusts),
            waistRank = com.novelcharacter.app.util.BodyAnalysisHelper.computeRank(myBwh.second, allWaists),
            hipRank = com.novelcharacter.app.util.BodyAnalysisHelper.computeRank(myBwh.third, allHips),
            totalCharacters = allBusts.size
        )
    }

    private fun parseBwh(value: String?): Triple<Double, Double, Double>? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(Regex("[-/\\s]+")).mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size < 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun loadCharacterStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val character = viewModel.getCharacterByIdSuspend(characterId) ?: return@launch
            val relationships = viewModel.getRelationshipsForCharacterList(characterId)
            val events = viewModel.getEventsForCharacterSuspend(characterId)
            val stateChanges = viewModel.getChangesByCharacterList(characterId)

            // 필드 완성도 계산
            val novel = character.novelId?.let { viewModel.getNovelById(it) }
            val universeId = novel?.universeId
            val fieldCompletion = if (universeId != null) {
                val fields = viewModel.getFieldsByUniverseList(universeId)
                val values = viewModel.getValuesByCharacterList(characterId)
                if (fields.isNotEmpty()) {
                    values.count { it.value.isNotBlank() }.toFloat() / fields.size * 100f
                } else 0f
            } else 0f

            // 복잡도 점수 (StatsDataProvider와 동일한 가중치)
            val relWeight = relationships.size * 2f
            val evtWeight = events.size * 1.5f
            val fieldWeight = (fieldCompletion / 100f) * 5f
            val stateWeight = stateChanges.size * 1f
            val complexity = relWeight + evtWeight + fieldWeight + stateWeight

            // 잠재력 등급 및 특화 유형 계산
            val grade = com.novelcharacter.app.ui.stats.CharacterComplexity.PotentialGrade.fromScore(complexity)
            val specialization = com.novelcharacter.app.ui.stats.CharacterComplexity.Specialization.determine(
                relWeight, evtWeight, fieldWeight, stateWeight
            )

            if (_binding == null) return@launch

            val container = binding.characterStatsContainer
            container.removeAllViews()
            val ctx = context ?: return@launch
            val density = resources.displayMetrics.density

            // 잠재력 등급 + 특화 유형 헤더
            val gradeText = buildString {
                append("${grade.label}")
                if (specialization != com.novelcharacter.app.ui.stats.CharacterComplexity.Specialization.NONE) {
                    append("  ${specialization.icon} ${specialization.label}")
                }
            }
            container.addView(android.widget.TextView(ctx).apply {
                text = gradeText
                textSize = 20f
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.primary))
                setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
            })

            val stats = listOf(
                getString(R.string.char_stats_relationship_count, relationships.size),
                getString(R.string.char_stats_event_count, events.size),
                getString(R.string.char_stats_field_completion, fieldCompletion),
                getString(R.string.char_stats_state_changes, stateChanges.size),
                getString(R.string.char_stats_alias_count, character.aliases.size),
                getString(R.string.char_stats_complexity, complexity)
            )

            stats.forEach { text ->
                val tv = android.widget.TextView(ctx).apply {
                    this.text = text
                    textSize = 14f
                    setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface))
                    setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                }
                container.addView(tv)
            }
        }
    }

    // ===== Menu actions =====

    private fun navigateToGrowthChart() {
        val bundle = Bundle().apply { putLong("characterId", characterId) }
        findNavController().navigateSafe(
            R.id.characterDetailFragment, R.id.characterGrowthFragment, bundle
        )
    }

    private fun shareCharacterCard() {
        val character = cachedCharacter ?: return
        val app = requireActivity().application as NovelCharacterApp

        val themes = arrayOf(
            getString(R.string.share_card_theme_light),
            getString(R.string.share_card_theme_dark),
            getString(R.string.share_card_theme_fantasy),
            getString(R.string.share_card_theme_modern)
        )
        val themeValues = CharacterCardRenderer.CardTheme.entries.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.share_card_theme)
            .setItems(themes) { _, which ->
                val selectedTheme = themeValues[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val fieldValues = withContext(Dispatchers.IO) {
                            app.characterRepository.getValuesByCharacterList(characterId)
                        }
                        val novelId = character.novelId
                        val universeId = if (novelId != null) {
                            withContext(Dispatchers.IO) { app.novelRepository.getNovelById(novelId) }?.universeId
                        } else null
                        val fieldDefs = if (universeId != null) {
                            withContext(Dispatchers.IO) { app.universeRepository.getFieldsByUniverseList(universeId) }
                        } else emptyList()
                        val relationships = withContext(Dispatchers.IO) {
                            app.characterRepository.getRelationshipsForCharacterList(characterId).map { rel ->
                                val otherId = if (rel.characterId1 == characterId) rel.characterId2 else rel.characterId1
                                val otherName = app.characterRepository.getCharacterById(otherId)?.name ?: "?"
                                otherName to rel.relationshipType
                            }
                        }

                        val charBitmap = withContext(Dispatchers.IO) {
                            try {
                                val paths: List<String> = GSON.fromJson(character.imagePaths, IMAGE_PATHS_TYPE) ?: emptyList()
                                if (paths.isNotEmpty()) BitmapFactory.decodeFile(paths[0]) else null
                            } catch (_: Exception) { null }
                        }

                        val config = CharacterCardRenderer.CardConfig(theme = selectedTheme)
                        val renderer = CharacterCardRenderer(requireContext())
                        val cardBitmap = withContext(Dispatchers.Default) {
                            renderer.render(character, fieldValues, fieldDefs, relationships, charBitmap, config)
                        }

                        // Save to cache/exports and share
                        val exportsDir = java.io.File(requireContext().cacheDir, "exports")
                        exportsDir.mkdirs()
                        val file = java.io.File(exportsDir, "character_card_${character.id}.png")
                        withContext(Dispatchers.IO) {
                            file.outputStream().use { cardBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_character_card)))
                    } catch (e: Exception) {
                        if (isAdded) Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun sharePdf() {
        val character = cachedCharacter ?: return
        val app = requireActivity().application as NovelCharacterApp

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val novelId = character.novelId ?: run {
                    Toast.makeText(requireContext(), "작품이 지정되지 않은 캐릭터입니다", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val novel = withContext(Dispatchers.IO) { app.novelRepository.getNovelById(novelId) }
                val universeId = novel?.universeId ?: run {
                    Toast.makeText(requireContext(), "세계관을 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val pdfExporter = PdfExporter(requireContext())
                val config = PdfExporter.PdfConfig(
                    universeId = universeId,
                    novelIds = listOf(novelId),
                    characterIds = listOf(characterId)
                )
                val html = withContext(Dispatchers.IO) { pdfExporter.generateHtml(config) }

                // Use WebView to print as PDF
                val webView = WebView(requireContext())
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val printManager = requireContext().getSystemService(android.content.Context.PRINT_SERVICE) as PrintManager
                        val jobName = "${character.name}_${getString(R.string.share_pdf_export)}"
                        view.createPrintDocumentAdapter(jobName).let { adapter ->
                            printManager.print(jobName, adapter, PrintAttributes.Builder().build())
                        }
                    }
                }
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        relationshipHelper.cancelJob()
        timeSliderHelper.cancelJob()
        binding.imageViewPager.adapter = null
        binding.eventsRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val GSON = Gson()
        private val IMAGE_PATHS_TYPE = object : TypeToken<List<String>>() {}.type
    }
}
