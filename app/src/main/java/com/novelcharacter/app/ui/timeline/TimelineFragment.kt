package com.novelcharacter.app.ui.timeline

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.databinding.DialogTimelineEditBinding
import com.novelcharacter.app.databinding.FragmentTimelineBinding
import com.novelcharacter.app.ui.adapter.TimelineAdapter
import kotlinx.coroutines.launch

class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TimelineViewModel by viewModels()

    private lateinit var adapter: TimelineAdapter
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimelineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupPinchZoom()
        setupZoomControls()
        setupYearSlider()
        setupSearch()
        setupFab()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = TimelineAdapter(
            onClick = { event ->
                showEditEventDialog(event)
            },
            onLongClick = { event ->
                AlertDialog.Builder(requireContext())
                    .setTitle("${event.year}년")
                    .setItems(arrayOf("편집", "삭제")) { _, which ->
                        when (which) {
                            0 -> showEditEventDialog(event)
                            1 -> {
                                AlertDialog.Builder(requireContext())
                                    .setMessage(R.string.confirm_delete)
                                    .setPositiveButton(R.string.yes) { _, _ ->
                                        viewModel.deleteEvent(event)
                                    }
                                    .setNegativeButton(R.string.no, null)
                                    .show()
                            }
                        }
                    }
                    .show()
            },
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            loadCharactersForEvent = { eventId -> viewModel.getCharactersForEvent(eventId) }
        )
        binding.timelineRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.timelineRecyclerView.adapter = adapter
    }

    private fun setupPinchZoom() {
        scaleGestureDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var scaleFactor = 1.0f

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    scaleFactor = 1.0f
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    if (scaleFactor > 1.3f) {
                        // Pinch out -> zoom in (more detail)
                        viewModel.zoomIn()
                    } else if (scaleFactor < 0.7f) {
                        // Pinch in -> zoom out (less detail)
                        viewModel.zoomOut()
                    }
                }
            }
        )

        binding.timelineRecyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                scaleGestureDetector.onTouchEvent(e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                scaleGestureDetector.onTouchEvent(e)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun setupZoomControls() {
        binding.btnZoomIn.setOnClickListener {
            viewModel.zoomIn()
        }

        binding.btnZoomOut.setOnClickListener {
            viewModel.zoomOut()
        }
    }

    private fun setupYearSlider() {
        binding.yearSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val year = value.toInt()
                viewModel.setSelectedYear(year)
            }
        }
    }

    private fun setupSearch() {
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFab() {
        binding.fabAddEvent.setOnClickListener {
            showEditEventDialog(null)
        }
    }

    private fun observeData() {
        // Observe search results (falls back to filteredEvents when query is empty)
        viewModel.searchResults.observe(viewLifecycleOwner) { events ->
            adapter.submitEventList(events)
            binding.emptyText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        }

        // Observe zoom level changes
        viewModel.zoomLevel.observe(viewLifecycleOwner) { level ->
            adapter.zoomLevel = level
            updateZoomLevelLabel(level)
        }

        // Observe zoom level label
        viewModel.zoomLevelLabel.observe(viewLifecycleOwner) { label ->
            binding.zoomLevelLabel.text = label
        }

        // Observe visible range to update year range label
        viewModel.visibleRange.observe(viewLifecycleOwner) { (start, end) ->
            binding.yearRangeLabel.text = getString(R.string.year_range_format, start, end)
        }

        // Update slider range based on all events data
        viewModel.allEvents.observe(viewLifecycleOwner) { events ->
            updateSliderRange(events)
        }

        // Observe selected year to update slider position
        viewModel.selectedYear.observe(viewLifecycleOwner) { year ->
            if (year != null) {
                val slider = binding.yearSlider
                val clampedValue = year.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
                if (slider.value != clampedValue) {
                    slider.value = clampedValue
                }
            }
        }
    }

    private fun updateZoomLevelLabel(level: Int) {
        val labelRes = when (level) {
            1 -> R.string.zoom_level_1000
            2 -> R.string.zoom_level_100
            3 -> R.string.zoom_level_10
            4 -> R.string.zoom_level_1
            5 -> R.string.zoom_level_month
            else -> R.string.zoom_level_1
        }
        binding.zoomLevelLabel.text = getString(labelRes)
    }

    private fun updateSliderRange(events: List<TimelineEvent>) {
        if (events.isEmpty()) {
            binding.yearSlider.valueFrom = -100f
            binding.yearSlider.valueTo = 100f
            binding.yearSlider.value = 0f
            return
        }

        val minYear = events.minOf { it.year }
        val maxYear = events.maxOf { it.year }

        // Add some padding to the range
        val rangeFrom = (minYear - 10).toFloat()
        val rangeTo = (maxYear + 10).toFloat()

        // Ensure valueFrom < valueTo
        if (rangeFrom >= rangeTo) {
            binding.yearSlider.valueFrom = rangeFrom - 10
            binding.yearSlider.valueTo = rangeTo + 10
        } else {
            binding.yearSlider.valueFrom = rangeFrom
            binding.yearSlider.valueTo = rangeTo
        }

        // Dynamically adjust step size based on range to avoid excessive discrete steps
        val totalRange = rangeTo - rangeFrom
        binding.yearSlider.stepSize = when {
            totalRange > 10000 -> 100f
            totalRange > 1000 -> 10f
            else -> 1f
        }

        // Set current value within range
        val currentCenter = viewModel.centerYear.value ?: 0
        val clampedValue = currentCenter.toFloat().coerceIn(
            binding.yearSlider.valueFrom,
            binding.yearSlider.valueTo
        )
        binding.yearSlider.value = clampedValue
    }

    private fun showEditEventDialog(event: TimelineEvent?) {
        lifecycleScope.launch {
            val novels = viewModel.getAllNovelsList()
            val characters = viewModel.getAllCharactersList()
            val selectedCharIds = if (event != null) {
                viewModel.getCharacterIdsForEvent(event.id).toMutableSet()
            } else {
                mutableSetOf()
            }

            val dialogBinding = DialogTimelineEditBinding.inflate(layoutInflater)

            // Fill existing data
            event?.let {
                dialogBinding.editYear.setText(it.year.toString())
                dialogBinding.editMonth.setText(it.month?.toString() ?: "")
                dialogBinding.editDay.setText(it.day?.toString() ?: "")
                dialogBinding.editCalendarType.setText(it.calendarType)
                dialogBinding.editDescription.setText(it.description)
            }

            // Novel spinner
            val novelNames = mutableListOf("전체")
            novelNames.addAll(novels.map { it.title })
            val novelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, novelNames)
            novelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            dialogBinding.spinnerNovel.adapter = novelAdapter

            event?.novelId?.let { novelId ->
                val index = novels.indexOfFirst { it.id == novelId }
                if (index >= 0) dialogBinding.spinnerNovel.setSelection(index + 1)
            }

            // Character checkboxes
            setupCharacterCheckboxes(dialogBinding, characters, selectedCharIds)

            AlertDialog.Builder(requireContext())
                .setTitle(if (event == null) R.string.add_event else R.string.edit_event)
                .setView(dialogBinding.root)
                .setPositiveButton(R.string.save) { _, _ ->
                    val yearStr = dialogBinding.editYear.text.toString().trim()
                    val description = dialogBinding.editDescription.text.toString().trim()

                    if (yearStr.isEmpty() || description.isEmpty()) {
                        Toast.makeText(requireContext(), "연도와 설명을 입력하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val year = yearStr.toIntOrNull()
                    if (year == null) {
                        Toast.makeText(requireContext(), "올바른 연도를 입력하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    // Parse optional month and day
                    val monthStr = dialogBinding.editMonth.text.toString().trim()
                    val dayStr = dialogBinding.editDay.text.toString().trim()
                    val month = if (monthStr.isNotEmpty()) monthStr.toIntOrNull() else null
                    val day = if (dayStr.isNotEmpty()) dayStr.toIntOrNull() else null

                    // Validate month range
                    if (month != null && (month < 1 || month > 12)) {
                        Toast.makeText(requireContext(), "월은 1~12 사이로 입력하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    // Validate day range
                    if (day != null && (day < 1 || day > 31)) {
                        Toast.makeText(requireContext(), "일은 1~31 사이로 입력하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val calendarType = dialogBinding.editCalendarType.text.toString().trim()
                    val novelPosition = dialogBinding.spinnerNovel.selectedItemPosition
                    val selectedNovel = if (novelPosition > 0) novels[novelPosition - 1] else null
                    val novelId = selectedNovel?.id
                    val universeId = selectedNovel?.universeId ?: event?.universeId

                    val newEvent = TimelineEvent(
                        id = event?.id ?: 0,
                        year = year,
                        month = month,
                        day = day,
                        calendarType = calendarType,
                        description = description,
                        universeId = universeId,
                        novelId = novelId,
                        createdAt = event?.createdAt ?: System.currentTimeMillis()
                    )

                    if (event == null) {
                        viewModel.insertEvent(newEvent, selectedCharIds.toList())
                    } else {
                        viewModel.updateEvent(newEvent, selectedCharIds.toList())
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupCharacterCheckboxes(
        dialogBinding: DialogTimelineEditBinding,
        characters: List<Character>,
        selectedIds: MutableSet<Long>
    ) {
        // Simple checkbox list implementation
        val recyclerView = dialogBinding.characterSelectRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val checkBox = CheckBox(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4 }
                    textSize = 14f
                }
                return object : RecyclerView.ViewHolder(checkBox) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val character = characters[position]
                val checkBox = holder.itemView as CheckBox
                checkBox.setOnCheckedChangeListener(null)
                checkBox.text = character.name
                checkBox.isChecked = selectedIds.contains(character.id)
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIds.add(character.id)
                    else selectedIds.remove(character.id)
                }
            }

            override fun getItemCount() = characters.size
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
