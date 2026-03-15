package com.novelcharacter.app.ui.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.databinding.DialogStateChangeBinding
import com.novelcharacter.app.databinding.FragmentCharacterDetailBinding
import com.novelcharacter.app.ui.adapter.RelationshipAdapter
import com.novelcharacter.app.ui.adapter.RelationshipDisplayItem
import com.novelcharacter.app.ui.adapter.StateChangeAdapter
import com.novelcharacter.app.ui.adapter.TimelineAdapter
import com.novelcharacter.app.util.TimeStateResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CharacterDetailFragment : Fragment() {

    private var _binding: FragmentCharacterDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    private var characterId: Long = -1L

    // Time slider state
    private var isTimeSliderExpanded = false
    private var isTimeViewActive = false
    private var currentSliderYear: Int? = null

    // Cached data for time resolution
    private var cachedFields: List<FieldDefinition> = emptyList()
    private var cachedValues: List<CharacterFieldValue> = emptyList()
    private var cachedCharacter: Character? = null
    private var cachedUniverseId: Long? = null

    // State change adapter
    private lateinit var stateChangeAdapter: StateChangeAdapter

    // Relationship adapter
    private lateinit var relationshipAdapter: RelationshipAdapter

    private val timeStateResolver = TimeStateResolver()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        characterId = arguments?.getLong("characterId", -1L) ?: -1L

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.fabEdit.setOnClickListener {
            val bundle = Bundle().apply { putLong("characterId", characterId) }
            findNavController().navigate(R.id.characterEditFragment, bundle)
        }

        setupTimeSlider()
        setupStateChanges()
        setupRelationships()
        observeCharacter()
        observeEvents()
        observeStateChanges()
        observeRelationships()
    }

    // ===== Time Slider =====

    private fun setupTimeSlider() {
        // Show the slider container
        binding.timeSliderContainer.visibility = View.VISIBLE

        // Toggle expand/collapse
        binding.timeSliderHeader.setOnClickListener {
            isTimeSliderExpanded = !isTimeSliderExpanded
            if (isTimeSliderExpanded) {
                binding.timeSliderContent.visibility = View.VISIBLE
                binding.timeSliderToggleIcon.rotation = 180f
                // Show reset button only when time view is active
                binding.btnResetTimeView.visibility = if (isTimeViewActive) View.VISIBLE else View.GONE
            } else {
                binding.timeSliderContent.visibility = View.GONE
                binding.timeSliderToggleIcon.rotation = 0f
                binding.btnResetTimeView.visibility = View.GONE
            }
        }

        // Slider change listener
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

        // Slider touch stop listener - apply the final value
        binding.yearSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val year = slider.value.toInt()
                applyTimeView(year)
            }
        })

        // Reset button
        binding.btnResetTimeView.setOnClickListener {
            resetTimeView()
        }
    }

    private fun resetTimeView() {
        isTimeViewActive = false
        currentSliderYear = null
        binding.btnResetTimeView.visibility = View.GONE
        binding.timeViewIndicator.visibility = View.GONE

        // Restore original dynamic fields
        if (cachedFields.isNotEmpty()) {
            displayDynamicFields(cachedFields, cachedValues)
        }
    }

    private fun applyTimeView(year: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (cachedFields.isEmpty()) return@launch

            // Build base values map from cached field values
            val baseValues = mutableMapOf<String, String>()
            for (value in cachedValues) {
                // Find the field definition for this value
                val fieldDef = cachedFields.find { it.id == value.fieldDefinitionId }
                if (fieldDef != null) {
                    baseValues[fieldDef.key] = value.value
                }
            }

            // Get all state changes for this character
            val allChanges = viewModel.getChangesByCharacterList(characterId)

            // Resolve state at the target year
            val resolvedState = timeStateResolver.resolveStateAtYear(
                baseValues, allChanges, year, cachedFields
            )

            // Display the resolved fields
            displayResolvedFields(cachedFields, resolvedState)
        }
    }

    private fun updateSliderRange() {
        viewLifecycleOwner.lifecycleScope.launch {
            val changes = viewModel.getChangesByCharacterList(characterId)
            if (changes.isEmpty()) {
                // No state changes, hide slider content but keep toggle
                binding.yearSlider.isEnabled = false
                return@launch
            }

            val years = changes.map { it.year }
            val minYear = years.min()
            val maxYear = years.max()

            // Ensure range is at least 1 apart
            val adjustedMin = minYear.toFloat()
            val adjustedMax = if (maxYear <= minYear) (minYear + 1).toFloat() else maxYear.toFloat()

            binding.yearSlider.isEnabled = true
            binding.yearSlider.valueFrom = adjustedMin
            binding.yearSlider.valueTo = adjustedMax

            // Show min/max year labels
            binding.minYearLabel.text = getString(R.string.slider_min_year, minYear)
            binding.maxYearLabel.text = getString(R.string.slider_max_year, adjustedMax.toInt())
            val totalRange = adjustedMax - adjustedMin
            binding.yearSlider.stepSize = when {
                totalRange > 10000 -> 100f
                totalRange > 1000 -> 10f
                else -> 1f
            }

            // Set initial value
            if (currentSliderYear == null) {
                binding.yearSlider.value = adjustedMin
                binding.yearLabel.text = "${minYear}년"
            } else {
                // Clamp to range
                val clampedYear = currentSliderYear!!.coerceIn(minYear, adjustedMax.toInt())
                binding.yearSlider.value = clampedYear.toFloat()
                binding.yearLabel.text = "${clampedYear}년"
            }
        }
    }

    // ===== State Changes =====

    private fun setupStateChanges() {
        stateChangeAdapter = StateChangeAdapter(
            onClick = { change -> showEditDeleteDialog(change) },
            onLongClick = { change -> showEditDeleteDialog(change) }
        )
        binding.stateChangesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.stateChangesRecyclerView.adapter = stateChangeAdapter

        binding.btnAddStateChange.setOnClickListener {
            showStateChangeDialog(null)
        }
    }

    private fun observeStateChanges() {
        viewModel.getChangesByCharacter(characterId).observe(viewLifecycleOwner) { changes ->
            val sortedChanges = changes.sortedWith(
                compareBy({ it.year }, { it.month ?: 0 }, { it.day ?: 0 })
            )
            stateChangeAdapter.submitList(sortedChanges)

            if (sortedChanges.isEmpty()) {
                binding.textNoStateChanges.visibility = View.VISIBLE
                binding.stateChangesRecyclerView.visibility = View.GONE
            } else {
                binding.textNoStateChanges.visibility = View.GONE
                binding.stateChangesRecyclerView.visibility = View.VISIBLE
            }

            // Update slider range when state changes are updated
            updateSliderRange()
        }
    }

    private fun showStateChangeDialog(existingChange: CharacterStateChange?) {
        val dialogBinding = DialogStateChangeBinding.inflate(LayoutInflater.from(requireContext()))

        // Build spinner items: special keys + field keys from cached field definitions
        val fieldOptions = mutableListOf<Pair<String, String>>() // key to displayName
        fieldOptions.add(CharacterStateChange.KEY_BIRTH to getString(R.string.birth))
        fieldOptions.add(CharacterStateChange.KEY_DEATH to getString(R.string.death))
        fieldOptions.add(CharacterStateChange.KEY_ALIVE to getString(R.string.alive_status))

        for (field in cachedFields) {
            fieldOptions.add(field.key to field.name)
        }

        val displayNames = fieldOptions.map { it.second }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerFieldKey.adapter = adapter

        // If editing, pre-fill
        if (existingChange != null) {
            dialogBinding.editYear.setText(existingChange.year.toString())
            existingChange.month?.let { dialogBinding.editMonth.setText(it.toString()) }
            existingChange.day?.let { dialogBinding.editDay.setText(it.toString()) }
            dialogBinding.editNewValue.setText(existingChange.newValue)
            dialogBinding.editDescription.setText(existingChange.description)

            // Set spinner to match existing field key
            val index = fieldOptions.indexOfFirst { it.first == existingChange.fieldKey }
            if (index >= 0) {
                dialogBinding.spinnerFieldKey.setSelection(index)
            }
        }

        val title = if (existingChange != null) getString(R.string.edit_state_change)
            else getString(R.string.add_state_change)

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val yearStr = dialogBinding.editYear.text.toString().trim()
                if (yearStr.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.year_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val year = yearStr.toIntOrNull()
                if (year == null) {
                    Toast.makeText(requireContext(), getString(R.string.year_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val month = dialogBinding.editMonth.text.toString().trim().toIntOrNull()
                val day = dialogBinding.editDay.text.toString().trim().toIntOrNull()
                val selectedIndex = dialogBinding.spinnerFieldKey.selectedItemPosition
                if (selectedIndex < 0 || selectedIndex >= fieldOptions.size) {
                    Toast.makeText(requireContext(), getString(R.string.field_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val fieldKey = fieldOptions[selectedIndex].first
                val newValue = dialogBinding.editNewValue.text.toString().trim()
                if (newValue.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.value_required), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val description = dialogBinding.editDescription.text.toString().trim()

                val change = CharacterStateChange(
                    id = existingChange?.id ?: 0,
                    characterId = characterId,
                    year = year,
                    month = month,
                    day = day,
                    fieldKey = fieldKey,
                    newValue = newValue,
                    description = description,
                    createdAt = existingChange?.createdAt ?: System.currentTimeMillis()
                )

                if (existingChange != null) {
                    viewModel.updateStateChange(change)
                } else {
                    viewModel.insertStateChange(change)
                }

                Toast.makeText(requireContext(), getString(R.string.state_change_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditDeleteDialog(change: CharacterStateChange) {
        val options = arrayOf(getString(R.string.edit), getString(R.string.delete))
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.edit_or_delete))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showStateChangeDialog(change)
                    1 -> confirmDeleteStateChange(change)
                }
            }
            .show()
    }

    private fun confirmDeleteStateChange(change: CharacterStateChange) {
        AlertDialog.Builder(requireContext())
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(getString(R.string.delete_warning_title))
            .setMessage(getString(R.string.confirm_delete))
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                viewModel.deleteStateChange(change)
                Toast.makeText(requireContext(), getString(R.string.state_change_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.no), null)
            .show()
    }

    // ===== Character display =====

    private fun observeCharacter() {
        viewModel.getCharacterById(characterId).observe(viewLifecycleOwner) { character ->
            character?.let {
                cachedCharacter = it
                displayCharacter(it)
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
    }

    private fun displayCharacter(character: Character) {
        binding.toolbar.title = character.name

        // 기본 정보
        binding.detailName.text = character.name

        // 작품명 + 동적 필드 로드
        viewLifecycleOwner.lifecycleScope.launch {
            val novel = character.novelId?.let { viewModel.getNovelById(it) }
            binding.detailNovel.text = "작품: ${novel?.title ?: "미지정"}"

            // 태그
            viewModel.getTagsByCharacter(characterId).observe(viewLifecycleOwner) { tags ->
                if (tags.isNotEmpty()) {
                    binding.detailTags.visibility = View.VISIBLE
                    binding.detailTags.text = tags.joinToString("  ") { "#${it.tag}" }
                } else {
                    binding.detailTags.visibility = View.GONE
                }
            }

            // 메모
            if (character.memo.isNotBlank()) {
                binding.memoCard.visibility = View.VISIBLE
                binding.detailMemo.text = character.memo
            } else {
                binding.memoCard.visibility = View.GONE
            }

            // 동적 필드 로드: novel -> universeId -> FieldDefinitions -> CharacterFieldValues
            val universeId = novel?.universeId
            cachedUniverseId = universeId
            if (universeId != null) {
                val fields = viewModel.getFieldsByUniverseList(universeId)
                val values = viewModel.getValuesByCharacterList(character.id)
                cachedFields = fields
                cachedValues = values

                if (isTimeViewActive && currentSliderYear != null) {
                    applyTimeView(currentSliderYear!!)
                } else {
                    displayDynamicFields(fields, values)
                }
            } else {
                cachedFields = emptyList()
                cachedValues = emptyList()
                binding.dynamicFieldsContainer.removeAllViews()
            }
        }

        // 이미지
        setupImages(character.imagePaths)
    }

    private fun displayDynamicFields(
        fields: List<FieldDefinition>,
        values: List<CharacterFieldValue>
    ) {
        binding.dynamicFieldsContainer.removeAllViews()

        val valueMap = values.associateBy { it.fieldDefinitionId }

        // 그룹별로 정렬하여 표시
        val grouped = fields
            .sortedBy { it.displayOrder }
            .groupBy { it.groupName }

        val context = requireContext()
        val density = resources.displayMetrics.density

        for ((groupName, groupFields) in grouped) {
            // 카드 생성
            val card = CardView(context).apply {
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

            val cardContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 그룹 제목
            val titleView = TextView(context).apply {
                text = groupName
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
            cardContent.addView(titleView)

            // 필드 행들
            for (field in groupFields) {
                val fieldValue = valueMap[field.id]?.value ?: ""
                val isCalculated = field.type == "CALCULATED"
                val displayValue = if (isCalculated && fieldValue.isEmpty()) {
                    getString(R.string.auto_calculated_label, field.name)
                } else {
                    "${field.name}: ${fieldValue.ifEmpty { "-" }}"
                }

                val rowView = TextView(context).apply {
                    text = displayValue
                    textSize = 14f
                    if (isCalculated) {
                        setTextColor(context.getColor(R.color.text_secondary))
                        setTypeface(null, android.graphics.Typeface.ITALIC)
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
            binding.dynamicFieldsContainer.addView(card)
        }
    }

    /**
     * Display dynamic fields using resolved state (from TimeStateResolver).
     * Similar to displayDynamicFields but uses a Map<String, String> instead of CharacterFieldValue list.
     */
    private fun displayResolvedFields(
        fields: List<FieldDefinition>,
        resolvedState: Map<String, String>
    ) {
        binding.dynamicFieldsContainer.removeAllViews()

        val context = requireContext()
        val density = resources.displayMetrics.density

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
            val specialCard = CardView(context).apply {
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

            val specialContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
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
            binding.dynamicFieldsContainer.addView(specialCard)
        }

        // Group fields and display with resolved values
        val grouped = fields
            .sortedBy { it.displayOrder }
            .groupBy { it.groupName }

        for ((groupName, groupFields) in grouped) {
            val card = CardView(context).apply {
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

            val cardContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = TextView(context).apply {
                text = groupName
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
            cardContent.addView(titleView)

            for (field in groupFields) {
                // Use resolved value from state map, fall back to empty
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
            binding.dynamicFieldsContainer.addView(card)
        }
    }

    private fun setupImages(imagePathsJson: String) {
        val gson = Gson()
        val type = object : TypeToken<List<String>>() {}.type
        val imagePaths: List<String> = try {
            gson.fromJson(imagePathsJson, type) ?: emptyList()
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
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                return object : RecyclerView.ViewHolder(imageView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val imageView = holder.itemView as ImageView
                imageView.setImageResource(R.drawable.ic_character_placeholder)
                viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeSampledBitmap(imagePaths[position], 1024, 1024)
                    }
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }

            override fun getItemCount() = imagePaths.size
        }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
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
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ===== Relationships =====

    private fun setupRelationships() {
        relationshipAdapter = RelationshipAdapter(
            onClick = { item ->
                // Navigate to the other character's detail
                val bundle = Bundle().apply { putLong("characterId", item.otherCharacterId) }
                findNavController().navigate(R.id.characterDetailFragment, bundle)
            },
            onLongClick = { item ->
                showRelationshipOptionsDialog(item)
            }
        )
        binding.relationshipsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.relationshipsRecyclerView.adapter = relationshipAdapter

        binding.btnAddRelationship.setOnClickListener {
            showAddRelationshipDialog()
        }
    }

    private fun observeRelationships() {
        viewModel.getRelationshipsForCharacter(characterId).observe(viewLifecycleOwner) { relationships ->
            viewLifecycleOwner.lifecycleScope.launch {
                val displayItems = relationships.mapNotNull { rel ->
                    val otherId = if (rel.characterId1 == characterId) rel.characterId2 else rel.characterId1
                    val otherChar = viewModel.getCharacterByIdSuspend(otherId)
                    if (otherChar != null) {
                        RelationshipDisplayItem(
                            relationshipId = rel.id,
                            otherCharacterName = otherChar.name,
                            otherCharacterId = otherId,
                            relationshipType = rel.relationshipType,
                            description = rel.description
                        )
                    } else null
                }
                relationshipAdapter.submitList(displayItems)
                binding.textNoRelationships.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE
                binding.relationshipsRecyclerView.visibility = if (displayItems.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun showAddRelationshipDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val allCharacters = viewModel.getAllCharactersList()
            val otherCharacters = allCharacters.filter { it.id != characterId }
            if (otherCharacters.isEmpty()) {
                Toast.makeText(requireContext(), "다른 캐릭터가 없습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val charNames = otherCharacters.map { it.name }.toTypedArray()
            val typeNames = com.novelcharacter.app.data.model.CharacterRelationship.TYPES.toTypedArray()

            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_relationship_edit, null)
            val spinnerCharacter = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerRelCharacter)
            val spinnerType = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerRelType)
            val editDesc = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editRelDescription)

            spinnerCharacter.adapter = android.widget.ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item, charNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            spinnerType.adapter = android.widget.ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item, typeNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            AlertDialog.Builder(requireContext())
                .setTitle("관계 추가")
                .setView(dialogView)
                .setPositiveButton("저장") { _, _ ->
                    val selectedCharIndex = spinnerCharacter.selectedItemPosition
                    val selectedType = typeNames[spinnerType.selectedItemPosition]
                    val desc = editDesc.text.toString().trim()

                    if (selectedCharIndex >= 0) {
                        val otherChar = otherCharacters[selectedCharIndex]
                        viewModel.insertRelationship(
                            com.novelcharacter.app.data.model.CharacterRelationship(
                                characterId1 = characterId,
                                characterId2 = otherChar.id,
                                relationshipType = selectedType,
                                description = desc
                            )
                        )
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun showRelationshipOptionsDialog(item: RelationshipDisplayItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("${item.otherCharacterName} (${item.relationshipType})")
            .setItems(arrayOf("삭제")) { _, which ->
                when (which) {
                    0 -> {
                        AlertDialog.Builder(requireContext())
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setTitle(getString(R.string.delete_warning_title))
                            .setMessage("이 관계를 삭제하시겠습니까?")
                            .setPositiveButton("예") { _, _ ->
                                viewModel.deleteRelationshipById(item.relationshipId)
                            }
                            .setNegativeButton("아니오", null)
                            .show()
                    }
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
