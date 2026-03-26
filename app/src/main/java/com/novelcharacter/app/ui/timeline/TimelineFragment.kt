package com.novelcharacter.app.ui.timeline

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.databinding.FragmentTimelineBinding
import com.novelcharacter.app.ui.adapter.TimelineAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TimelineViewModel by viewModels()

    private lateinit var adapter: TimelineAdapter
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var itemTouchHelper: ItemTouchHelper? = null
    private var isReorderMode = false
    private var pendingScrollToYear = false

    // Cached data for spinner filters
    private var cachedNovels: List<Novel> = emptyList()
    private var cachedCharacters: List<Character> = emptyList()

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
        setupFilters()
        setupFab()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = TimelineAdapter(
            onClick = { event ->
                showEditEventDialog(event)
            },
            onLongClick = { event ->
                val items = mutableListOf(
                    getString(R.string.edit),
                    getString(R.string.delete),
                    getString(R.string.set_as_standard_year)
                )
                val title = "${getString(R.string.event_year_format, event.year)} — ${event.description.take(50)}"
                AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setItems(items.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> showEditEventDialog(event)
                            1 -> viewModel.deleteEvent(event)
                            2 -> showSetStandardYearDialog(event)
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

    private var searchJob: Job? = null

    private fun setupSearch() {
        binding.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    viewModel.setSearchQuery(query)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFilters() {
        // Novel filter - setup listener once
        binding.spinnerFilterNovel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val novelId = if (position > 0) cachedNovels.getOrNull(position - 1)?.id else null
                viewModel.setFilterNovel(novelId)
                updateClearFilterButton()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Character filter - setup listener once
        binding.spinnerFilterCharacter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val charId = if (position > 0) cachedCharacters.getOrNull(position - 1)?.id else null
                viewModel.setFilterCharacter(charId)
                updateClearFilterButton()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Observe novel data and update spinner adapter only
        viewModel.allNovels.observe(viewLifecycleOwner) { novels ->
            cachedNovels = novels
            val ctx = context ?: return@observe
            val novelNames = mutableListOf(getString(R.string.all_novels_filter))
            novelNames.addAll(novels.map { it.title })
            val spinnerAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, novelNames)
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerFilterNovel.adapter = spinnerAdapter
            // 어댑터 교체 후 ViewModel의 현재 필터 선택을 복원 (어댑터 세팅이 리스너를 트리거하여 position 0으로 리셋되는 것 방지)
            val currentNovelId = viewModel.filterNovelId.value
            if (currentNovelId != null) {
                val idx = novels.indexOfFirst { it.id == currentNovelId }
                if (idx >= 0) binding.spinnerFilterNovel.setSelection(idx + 1)
            }
        }

        // Observe filtered characters (연동: 소설 선택 시 해당 소설 캐릭터만 표시)
        viewModel.filteredCharacters.observe(viewLifecycleOwner) { characters ->
            cachedCharacters = characters
            val ctx = context ?: return@observe
            val charNames = mutableListOf(getString(R.string.all_characters_filter))
            charNames.addAll(characters.map { it.name })
            val spinnerAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, charNames)
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerFilterCharacter.adapter = spinnerAdapter
            // 어댑터 교체 후 ViewModel의 현재 필터 선택을 복원
            val currentCharId = viewModel.filterCharacterId.value
            if (currentCharId != null) {
                val idx = characters.indexOfFirst { it.id == currentCharId }
                if (idx >= 0) binding.spinnerFilterCharacter.setSelection(idx + 1)
            }
        }

        // Clear filter button
        binding.btnClearFilter.setOnClickListener {
            viewModel.clearFilters()
            binding.spinnerFilterNovel.setSelection(0)
            binding.spinnerFilterCharacter.setSelection(0)
            binding.btnClearFilter.visibility = View.GONE
        }
    }

    private fun updateClearFilterButton() {
        val hasFilter = viewModel.filterNovelId.value != null || viewModel.filterCharacterId.value != null
        binding.btnClearFilter.visibility = if (hasFilter) View.VISIBLE else View.GONE
    }

    private fun setupFab() {
        binding.fabAddEvent.setOnClickListener {
            showEditEventDialog(null)
        }
        binding.fabAddEvent.setOnLongClickListener {
            toggleReorderMode()
            true
        }
    }

    private fun toggleReorderMode() {
        isReorderMode = !isReorderMode
        adapter.isReorderMode = isReorderMode

        if (isReorderMode) {
            Toast.makeText(requireContext(), R.string.reorder_mode, Toast.LENGTH_SHORT).show()
            val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun isLongPressDragEnabled(): Boolean = false

                override fun canDropOver(
                    recyclerView: RecyclerView,
                    current: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val currentItem = adapter.currentList.getOrNull(current.bindingAdapterPosition)
                    val targetItem = adapter.currentList.getOrNull(target.bindingAdapterPosition)
                    return currentItem is com.novelcharacter.app.ui.adapter.TimelineDisplayItem.EventItem &&
                           targetItem is com.novelcharacter.app.ui.adapter.TimelineDisplayItem.EventItem
                }
            }
            itemTouchHelper = ItemTouchHelper(callback).also {
                it.attachToRecyclerView(binding.timelineRecyclerView)
            }
            adapter.onStartDrag = { holder -> itemTouchHelper?.startDrag(holder) }
        } else {
            // 재정렬 모드 종료 — displayOrder 저장
            val reorderedEvents = adapter.getReorderedEvents()
            if (reorderedEvents.isNotEmpty()) {
                viewModel.updateDisplayOrders(reorderedEvents)
            }
            itemTouchHelper?.attachToRecyclerView(null)
            itemTouchHelper = null
            adapter.onStartDrag = null
            Toast.makeText(requireContext(), R.string.reorder_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private var cachedNovelNamesMap: Map<Long, List<String>> = emptyMap()

    private fun loadNovelNamesMap() {
        viewLifecycleOwner.lifecycleScope.launch {
            val eventNovelNames = viewModel.getAllEventNovelNames()
            cachedNovelNamesMap = eventNovelNames.groupBy({ it.eventId }, { it.title })
            adapter.novelNamesMap = cachedNovelNamesMap
        }
    }

    /** 값을 valueFrom 기준 stepSize 배수로 정렬 (Slider 제약 충족) */
    private fun alignToStep(value: Float, valueFrom: Float, stepSize: Float): Float {
        if (stepSize <= 0f) return value
        val steps = kotlin.math.round((value - valueFrom) / stepSize)
        return valueFrom + steps * stepSize
    }

    /** 현재 center year에 해당하는 사건으로 RecyclerView 스크롤 */
    private fun scrollToCurrentYear() {
        val targetYear = viewModel.centerYear.value ?: return
        val position = adapter.currentList.indexOfFirst { item ->
            when (item) {
                is com.novelcharacter.app.ui.adapter.TimelineDisplayItem.EventItem ->
                    item.event.year == targetYear
                is com.novelcharacter.app.ui.adapter.TimelineDisplayItem.GroupHeader ->
                    item.events.any { it.year == targetYear }
            }
        }
        if (position >= 0) {
            (binding.timelineRecyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, 0)
        }
    }

    private fun observeData() {
        // 연결 작품명 최초 로드
        loadNovelNamesMap()

        // Observe search results (falls back to filteredEvents when query is empty)
        viewModel.searchResults.observe(viewLifecycleOwner) { events ->
            adapter.submitEventList(events)
            val isEmpty = events.isEmpty()
            binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.timelineRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            // 캐시된 작품명 적용 (DB 재조회 없음)
            adapter.novelNamesMap = cachedNovelNamesMap
            // 이동 후 해당 연도의 사건으로 스크롤 (네비게이션으로 인한 변경일 때만)
            if (pendingScrollToYear) {
                pendingScrollToYear = false
                scrollToCurrentYear()
            }
        }

        // 사건 목록 변경 시 작품명 캐시 갱신 (삽입/수정/삭제 시에만 갱신)
        viewModel.allEvents.observe(viewLifecycleOwner) { _ ->
            loadNovelNamesMap()
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

        // Observe visible range to update density bar range
        viewModel.visibleRange.observe(viewLifecycleOwner) { (start, end) ->
            binding.eventDensityBar.setRange(start, end)
        }

        // ===== Event navigation (이전/다음 사건) =====
        binding.btnPrevEvent.setOnClickListener {
            pendingScrollToYear = true
            viewModel.navigateToPreviousEvent()
        }
        binding.btnNextEvent.setOnClickListener {
            pendingScrollToYear = true
            viewModel.navigateToNextEvent()
        }

        viewModel.navState.observe(viewLifecycleOwner) { state ->
            binding.btnPrevEvent.isEnabled = state.hasPrevious
            binding.btnNextEvent.isEnabled = state.hasNext
            binding.navStatusLabel.text = when {
                state.totalCount > 0 && state.currentIndex >= 0 ->
                    "${state.currentIndex + 1} / ${state.totalCount}"
                state.totalCount > 0 ->
                    getString(R.string.event_nav_count, state.totalCount)
                else -> ""
            }
        }

        // Update slider range and density bar based on all events data
        viewModel.allEvents.observe(viewLifecycleOwner) { events ->
            updateSliderRange(events)
        }

        // Observe event density for density bar
        viewModel.eventDensity.observe(viewLifecycleOwner) { density ->
            val events = viewModel.allEvents.value ?: return@observe
            if (events.isEmpty()) return@observe
            val minYear = events.minOf { it.year }
            val maxYear = events.maxOf { it.year }
            binding.eventDensityBar.setDensityData(density, minYear - 10, maxYear + 10)
        }

        // Density bar tap → jump to year
        binding.eventDensityBar.setOnYearTapListener { year ->
            viewModel.setSelectedYear(year)
        }

        // Density bar long-press → quick event creation
        binding.eventDensityBar.setOnYearLongPressListener { year ->
            showQuickAddDialog(year)
        }

        // Observe selected year to update slider position
        viewModel.selectedYear.observe(viewLifecycleOwner) { year ->
            if (year != null) {
                val slider = binding.yearSlider
                if (slider.valueFrom < slider.valueTo) {
                    val aligned = alignToStep(year.toFloat(), slider.valueFrom, slider.stepSize)
                    val clampedValue = aligned.coerceIn(slider.valueFrom, slider.valueTo)
                    if (slider.value != clampedValue) {
                        slider.value = clampedValue
                    }
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
            // stepSize를 먼저 0으로 리셋해야 기존 범위/값 제약과 충돌하지 않음
            binding.yearSlider.stepSize = 0f
            binding.yearSlider.valueFrom = -100f
            binding.yearSlider.valueTo = 100f
            binding.yearSlider.value = 0f
            binding.yearSlider.stepSize = 1f
            binding.minYearLabel.text = ""
            binding.maxYearLabel.text = ""
            return
        }

        val minYear = events.minOf { it.year }
        val maxYear = events.maxOf { it.year }

        // Determine step size based on range
        val rawRange = (maxYear - minYear + 20).toFloat() // +20 for padding
        val stepSize = when {
            rawRange > 10000 -> 100f
            rawRange > 1000 -> 10f
            else -> 1f
        }

        // Align range boundaries to step size so range is evenly divisible
        val step = stepSize.toInt()
        val alignedFrom = ((minYear - 10).floorDiv(step) * step).toFloat()
        val alignedTo = (((maxYear + 10) + step - 1).floorDiv(step) * step).toFloat()

        // Ensure valueFrom < valueTo
        val finalFrom = if (alignedFrom >= alignedTo) alignedFrom - stepSize else alignedFrom
        val finalTo = if (alignedFrom >= alignedTo) alignedTo + stepSize else alignedTo

        // Reset stepSize to 0 (continuous) first to avoid constraint violations during update
        binding.yearSlider.stepSize = 0f

        // Set range wide enough to cover both old and new values, then narrow
        binding.yearSlider.valueFrom = minOf(binding.yearSlider.valueFrom, finalFrom)
        binding.yearSlider.valueTo = maxOf(binding.yearSlider.valueTo, finalTo)

        // Set value within new range, aligned to step grid
        val currentCenter = viewModel.centerYear.value ?: 0
        val aligned = alignToStep(currentCenter.toFloat(), finalFrom, stepSize)
        val clampedValue = aligned.coerceIn(finalFrom, finalTo)
        binding.yearSlider.value = clampedValue

        // Now safely narrow the range
        binding.yearSlider.valueFrom = finalFrom
        binding.yearSlider.valueTo = finalTo
        binding.yearSlider.stepSize = stepSize

        // Show min/max year labels (actual min/max, not padded range)
        binding.minYearLabel.text = getString(R.string.slider_min_year, minYear)
        binding.maxYearLabel.text = getString(R.string.slider_max_year, maxYear)
    }

    private fun showSetStandardYearDialog(event: TimelineEvent) {
        viewLifecycleOwner.lifecycleScope.launch {
            val novelIds = viewModel.getNovelIdsForEvent(event.id)
            if (novelIds.isEmpty()) {
                Toast.makeText(requireContext(), R.string.standard_year_no_novel, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val allNovels = viewModel.getAllNovelsList()
            val novelIdSet = novelIds.toSet()
            val novels = allNovels.filter { it.id in novelIdSet }
            if (novels.isEmpty()) return@launch

            if (novels.size == 1) {
                confirmSetStandardYear(novels[0], event.year)
            } else {
                // 복수 작품 → 선택 다이얼로그
                val names = novels.map { it.title }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.standard_year_select_novel)
                    .setItems(names) { _, which ->
                        confirmSetStandardYear(novels[which], event.year)
                    }
                    .show()
            }
        }
    }

    private fun confirmSetStandardYear(novel: com.novelcharacter.app.data.model.Novel, year: Int) {
        if (novel.standardYear == year) {
            Toast.makeText(requireContext(), getString(R.string.standard_year_already_set, year), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.set_as_standard_year)
            .setMessage(getString(R.string.standard_year_confirm, novel.title, year))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.setNovelStandardYear(novel.id, year)
                Toast.makeText(requireContext(), getString(R.string.standard_year_set_done, novel.title, year), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showEditEventDialog(event: TimelineEvent?) {
        val eventHelper = com.novelcharacter.app.util.EventEditDialogHelper(
            requireContext(), viewLifecycleOwner.lifecycleScope, layoutInflater
        )
        val dataProvider = object : com.novelcharacter.app.util.EventEditDialogHelper.DataProvider {
            override suspend fun getAllNovelsList(): List<Novel> = viewModel.getAllNovelsList()
            override suspend fun getAllCharactersList(): List<Character> = viewModel.getAllCharactersList()
            override suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> = viewModel.getCharacterIdsForEvent(eventId)
            override suspend fun getNovelIdsForEvent(eventId: Long): List<Long> = viewModel.getNovelIdsForEvent(eventId)
            override fun insertEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>) {
                viewModel.insertEvent(event, characterIds, novelIds)
            }
            override fun updateEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>) {
                viewModel.updateEvent(event, characterIds, novelIds)
            }
            override suspend fun getEventsInScope(novelIds: List<Long>, universeId: Long?): List<TimelineEvent> {
                return when {
                    novelIds.isNotEmpty() ->
                        novelIds.flatMap { viewModel.getEventsByNovelList(it) }.distinctBy { it.id }
                    universeId != null -> viewModel.getEventsByUniverseList(universeId)
                    else -> viewModel.getAllEventsList()
                }
            }
            override fun updateEventAndShiftOthers(
                event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>,
                shiftDirection: com.novelcharacter.app.util.EventEditDialogHelper.ShiftDirection,
                delta: Int, originalNovelIds: List<Long>, originalUniverseId: Long?
            ) {
                viewModel.updateEventAndShiftOthers(event, characterIds, novelIds, shiftDirection, delta, originalNovelIds, originalUniverseId)
            }
        }
        eventHelper.showEventDialog(dataProvider = dataProvider, event = event)
    }

    /** 간편 사건 추가: 연도 + 한줄 설명만으로 임시 사건 생성 */
    private fun showQuickAddDialog(year: Int) {
        val ctx = context ?: return
        val density = resources.displayMetrics.density

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
        }

        val editDescription = EditText(ctx).apply {
            hint = getString(R.string.hint_quick_event_desc)
            isSingleLine = true
        }
        container.addView(editDescription)

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.quick_add_event_title, year))
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val desc = editDescription.text.toString().trim()
                if (desc.isEmpty()) return@setPositiveButton
                val currentNovel = viewModel.filterNovelId.value?.let { nid ->
                    cachedNovels.find { it.id == nid }
                }
                val quickEvent = TimelineEvent(
                    year = year,
                    description = desc,
                    isTemporary = true,
                    universeId = currentNovel?.universeId
                )
                viewModel.insertEvent(quickEvent, emptyList(), listOfNotNull(currentNovel?.id))
                Toast.makeText(ctx, R.string.quick_event_added, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        binding.timelineRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
