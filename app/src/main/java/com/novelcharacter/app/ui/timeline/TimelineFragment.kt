package com.novelcharacter.app.ui.timeline

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
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
import com.novelcharacter.app.ui.common.applyRange
import com.novelcharacter.app.util.SliderRange
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.databinding.FragmentTimelineBinding
import com.novelcharacter.app.ui.adapter.TimelineAdapter
import com.novelcharacter.app.util.FieldValueFixRoute
import com.novelcharacter.app.util.notifyResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimelineFragment : Fragment(), EventEditDialogFragment.Host {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TimelineViewModel by viewModels()

    private lateinit var adapter: TimelineAdapter
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var itemTouchHelper: ItemTouchHelper? = null
    private var isReorderMode = false

    private companion object {
        /**
         * 사건을 **하나씩** 그리기 시작하는 줌 — 이보다 낮으면 연대 묶음이라 끌 대상이 없다
         * ([TimelineAdapter.reprocessEvents]의 갈림과 같은 수다).
         */
        const val ZOOM_INDIVIDUAL_EVENTS = 4
        const val DEFAULT_ZOOM = 4
    }
    private var pendingScrollToYear = false

    /**
     * 고치러 온 요청을 처리하는 중인가 (B-198).
     *
     * [consumeFixRequest]는 **두 자리에서 불린다**(`onViewCreated` · `onResume`). 사건 조회가
     * 끝나기 전에 둘째 호출이 오면 같은 요청으로 시트가 **둘** 뜬다 — `show()`는 태그가 같아도
     * 겹쳐 넣는다. 표식 하나로 막고, 끝나면(취소돼도) 되돌린다.
     */
    private var fixRequestInFlight = false

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

        // 푸시 목적지(대시보드·검색·딥링크 진입) — 업 버튼은 디스패처 경유로 뒤로가기와 동일 동작
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        // 순서 편집을 가시적 메뉴로 통일 (FAB 길게 누르기는 액셀러레이터로 존치)
        binding.toolbar.inflateMenu(R.menu.menu_timeline)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reorder -> {
                    toggleReorderMode()
                    true
                }
                R.id.action_sort_order -> {
                    viewModel.toggleSortDescending()
                    true
                }
                else -> false
            }
        }

        setupRecyclerView()
        setupPinchZoom()
        setupZoomControls()
        setupYearSlider()
        setupSearch()
        setupFilters()
        setupFab()
        observeData()

        // 간편 사건 추가 결과 수신 (DialogFragment — 회전/재생성에도 안전)
        childFragmentManager.setFragmentResultListener(
            QuickAddEventDialogFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val year = bundle.getInt(QuickAddEventDialogFragment.RESULT_YEAR)
            val desc = bundle.getString(QuickAddEventDialogFragment.RESULT_DESCRIPTION) ?: return@setFragmentResultListener
            val currentNovel = viewModel.filterNovelId.value?.let { nid ->
                cachedNovels.find { it.id == nid }
            }
            // 시드 계산+insert는 viewModelScope에서 durable하게(뷰 스코프 코루틴이 회전/이탈로 취소돼
            // 사건이 유실되던 문제 수정). 역법은 스코프 세계관 최빈값 시드(없으면 공란).
            viewModel.quickAddEvent(year, desc, currentNovel?.id, currentNovel?.universeId)
            Toast.makeText(requireContext(), R.string.quick_event_added, Toast.LENGTH_SHORT).show()
        }

        consumeFixRequest()
    }

    /**
     * 타입이 안 맞는 값을 고치러 온 요청을 소비한다 (B-198).
     *
     * **인자는 시트를 실제로 연 그 자리에서 지운다 — 읽는 자리가 아니다.** 사건 조회는
     * 중단점이라, 먼저 지우면 그사이 화면이 내려갈 때 **요청만 사라진다**(누른 사람은
     * 아무 일도 안 일어난 것을 보고 다시 누를 방법이 없다). 남겨 두면 다시 선 화면이
     * 이어 연다. 열고 나서 지우므로 회전에 두 번 열리지도 않는다.
     *
     * **상태 저장 뒤에는 물러나고, 다시 설 때 잇는다.** `DialogFragment.show()`는 그 시점에
     * 예외로 죽는다(`navigateSafe`가 이동에 대해 막는 것과 같은 부류다). 그래서 인자를 남기고
     * 물러나는데, **그것만으로는 요청이 영영 붙들린다** — 화면이 잠깐 내려갔다 돌아오는 길
     * (홈 키 → 복귀)에는 `onViewCreated`가 다시 돌지 않기 때문이다. 그 갈래를 잇는 것이
     * `onResume`의 둘째 호출이고, 겹쳐 열리는 것은 [fixRequestInFlight]가 막는다.
     *
     * **사건을 못 찾으면 말하고 지운다.** 통계 스냅샷은 뜬 시점의 사진이라, 그사이 지워진
     * 사건의 줄이 아직 목록에 서 있을 수 있다. 그때 아무 일도 안 일어나면 누른 사람은 앱이
     * 먹통인 줄 안다(개발 의도 2번 — 조용히 버리지 않는다). 다시 시도해도 없을 것이므로
     * 그 갈래는 그 자리에서 끝낸다.
     */
    private fun consumeFixRequest() {
        if (fixRequestInFlight) return
        val args = arguments ?: return
        val eventId = args.getLong(FieldValueFixRoute.ARG_FOCUS_EVENT_ID, 0L)
        if (eventId <= 0L) return
        val fieldId = args.getLong(FieldValueFixRoute.ARG_FOCUS_FIELD_ID, 0L)
        val fieldName = args.getString(FieldValueFixRoute.ARG_FOCUS_FIELD_NAME).orEmpty()

        fixRequestInFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            // 취소(뷰 소멸)로 끝나도 되돌린다 — 안 되돌리면 다시 선 화면이 영영 못 잇는다.
            try {
                val event = viewModel.getEventById(eventId)
                if (!isAdded || _binding == null) return@launch
                if (event == null) {
                    clearFixRequest()
                    Toast.makeText(requireContext(), R.string.fix_target_event_missing, Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (childFragmentManager.isStateSaved) return@launch   // onResume이 잇는다
                clearFixRequest()
                // 시트를 닫은 뒤 그 사건이 있는 자리에 서 있게 한다 — 전역 검색·인사이트가
                // 연표로 보낼 때 쓰는 규약과 같다(그쪽은 연도만 알아 연도로 보낸다).
                viewModel.setSelectedYear(event.year)
                EventEditDialogFragment.show(
                    childFragmentManager, event = event,
                    focusFieldId = fieldId, focusFieldName = fieldName
                )
            } finally {
                fixRequestInFlight = false
            }
        }
    }

    private fun clearFixRequest() {
        val args = arguments ?: return
        args.remove(FieldValueFixRoute.ARG_FOCUS_EVENT_ID)
        args.remove(FieldValueFixRoute.ARG_FOCUS_FIELD_ID)
        args.remove(FieldValueFixRoute.ARG_FOCUS_FIELD_NAME)
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
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(title)
                    .setItems(items.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> showEditEventDialog(event)
                            1 -> confirmDeleteEvent(event)
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

    /**
     * 삭제는 묻고 지운다 — 형제 화면(캐릭터 상세)과 같은 처분이다(R-4: 파괴적 동작은 실행
     * 전에 결과를 알리고 취소 경로를 남긴다). 종전에는 롱프레스 메뉴의 '삭제'가 확인 없이
     * 즉시 지웠다. 연결된 캐릭터 수를 먼저 세는 것은 **파급을 모르는 동의는 동의가 아니기
     * 때문**이고, 휴지통 보관(되돌릴 길)을 함께 말한다.
     */
    private fun confirmDeleteEvent(event: TimelineEvent) {
        viewLifecycleOwner.lifecycleScope.launch {
            val linked = try {
                viewModel.getCharacterIdsForEvent(event.id).size
            } catch (_: Exception) {
                0
            }
            if (!isAdded) return@launch
            val message = if (linked > 0) {
                getString(R.string.event_delete_linked_confirm, event.description, linked)
            } else {
                getString(R.string.event_delete_confirm, event.description)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete)
                .setMessage(message)
                .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteEvent(event) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
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

    /**
     * 두 자리가 **같은 날짜 묶음**인가 — 끌어 옮기기의 유일한 문이다.
     *
     * `displayOrder`는 같은 `(year, month, day)` 안의 타이브레이크라 **날짜를 넘는 이동은
     * 저장할 표현이 애초에 없다.** 종전에는 `EventItem`인지만 보아 저장할 수 없는 배치를
     * 손으로 만들 수 있었고, 모드를 끄면 목록이 재방출되며 그 배치가 말없이 사라졌다 —
     * 화면은 "순서가 저장되었습니다"라고 말한 뒤였다.
     */
    private fun sameDateGroup(currentPos: Int, targetPos: Int): Boolean {
        val a = adapter.currentList.getOrNull(currentPos)
            as? com.novelcharacter.app.ui.adapter.TimelineDisplayItem.EventItem ?: return false
        val b = adapter.currentList.getOrNull(targetPos)
            as? com.novelcharacter.app.ui.adapter.TimelineDisplayItem.EventItem ?: return false
        return com.novelcharacter.app.util.TimelineDisplayOrder.dateKeyOf(a.event) ==
            com.novelcharacter.app.util.TimelineDisplayOrder.dateKeyOf(b.event)
    }

    private fun toggleReorderMode() {
        // **묶음 보기에서는 끌 것이 없다**(콜드 검토 2026.08.21). 줌 1~3은 사건을 연대
        // 묶음으로 접어 그리므로 드래그 핸들이 하나도 서지 않는다 — 종전에는 모드가 켜지고
        // *"같은 날짜 안에서만 옮깁니다"*라는 안내까지 뜬 뒤 아무것도 할 수 없었다.
        // 고를 수 있는데 아무 일도 일어나지 않는 자리다(R-24). 사유와 함께 막는다.
        if (!isReorderMode && (viewModel.zoomLevel.value ?: DEFAULT_ZOOM) < ZOOM_INDIVIDUAL_EVENTS) {
            Toast.makeText(requireContext(), R.string.reorder_needs_event_zoom, Toast.LENGTH_LONG).show()
            return
        }
        isReorderMode = !isReorderMode
        adapter.isReorderMode = isReorderMode
        // 순서 편집 중에는 표시 순서를 못 바꾸게 한다 (B-47) — 뒤집으면 목록이 통째로 다시
        // 방출돼 **끌어 옮기던 배치가 말없이 사라진다.** 막지 않으면 사용자는 저장 전에
        // 그것을 알 방법이 없다(비활성 버튼은 적어도 보인다).
        binding.toolbar.menu.findItem(R.id.action_sort_order)?.isEnabled = !isReorderMode

        if (isReorderMode) {
            // 잠근 사실을 말한다 — 알려 주지 않으면 "왜 안 되지"가 남는다(원칙 04).
            Toast.makeText(requireContext(), R.string.reorder_mode_same_date, Toast.LENGTH_LONG).show()
            val callback = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    // 같은 날짜 안에서만 움직인다 — [canDropOver]와 **같은 판정**을 지난다.
                    if (!sameDateGroup(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)) {
                        return false
                    }
                    return adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun isLongPressDragEnabled(): Boolean = false

                override fun canDropOver(
                    recyclerView: RecyclerView,
                    current: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean = sameDateGroup(current.bindingAdapterPosition, target.bindingAdapterPosition)
            }
            itemTouchHelper = ItemTouchHelper(callback).also {
                it.attachToRecyclerView(binding.timelineRecyclerView)
            }
            adapter.onStartDrag = { holder -> itemTouchHelper?.startDrag(holder) }
        } else {
            // 재정렬 모드 종료 — displayOrder 저장
            val reorderedEvents = adapter.getVisualOrderEvents()
            itemTouchHelper?.attachToRecyclerView(null)
            itemTouchHelper = null
            adapter.onStartDrag = null
            if (reorderedEvents.isNotEmpty()) {
                // **고지는 결과를 받은 뒤다.** 종전에는 저장 코루틴을 기다리지 않고 무조건
                // "순서가 저장되었습니다"를 띄워, 실패해도 성공이라 말했다.
                viewLifecycleOwner.lifecycleScope.launch {
                    val saved = viewModel.updateDisplayOrders(reorderedEvents)
                    if (_binding != null && saved) {
                        Toast.makeText(requireContext(), R.string.reorder_saved, Toast.LENGTH_SHORT).show()
                    }
                }
            }
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

    /** 목록에 그려지는 사건의 필드값 요약만 조회한다 (B-5). */
    private var fieldSummaryJob: Job? = null

    private fun loadEventFieldSummaries(events: List<TimelineEvent>) {
        // 앞선 조회는 이미 낡았다 — 취소하지 않으면 늦게 끝난 쪽이 최신 결과를 덮는다.
        fieldSummaryJob?.cancel()
        if (events.isEmpty()) {
            adapter.fieldSummaries = emptyMap()
            return
        }
        fieldSummaryJob = viewLifecycleOwner.lifecycleScope.launch {
            val summaries = viewModel.getEventFieldSummaries(events.map { it.id })
            adapter.fieldSummaries = summaries
        }
    }

    /** 목록에 그려지는 사건의 등장 캐릭터를 **한 번에** 조회한다(카드가 바인딩마다 묻지 않게). */
    private var eventCharactersJob: Job? = null

    private fun loadEventCharacters(events: List<TimelineEvent>) {
        // 필드값 요약과 같은 규약 — 앞선 조회는 이미 낡았다.
        eventCharactersJob?.cancel()
        if (events.isEmpty()) {
            adapter.charactersMap = emptyMap()
            return
        }
        eventCharactersJob = viewLifecycleOwner.lifecycleScope.launch {
            adapter.charactersMap = viewModel.getCharactersForEvents(events.map { it.id })
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

        // 표시 순서 — 메뉴 문구는 **누르면 무엇이 되는가**를 말한다 (B-47).
        // 밀도 바·연도 슬라이더는 함께 뒤집지 않는다(연도 축이라 그렇다 — 근거는
        // [com.novelcharacter.app.util.TimelineDisplayOrder] KDoc).
        viewModel.sortDescending.observe(viewLifecycleOwner) { descending ->
            binding.toolbar.menu.findItem(R.id.action_sort_order)?.setTitle(
                if (descending == true) R.string.timeline_sort_ascending else R.string.timeline_sort_descending
            )
        }

        // Observe display list (검색 결과에 표시 순서를 입힌 것 — B-47)
        viewModel.displayEvents.observe(viewLifecycleOwner) { events ->
            adapter.submitEventList(events)
            val isEmpty = events.isEmpty()
            binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.timelineRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            // 캐시된 작품명 적용 (DB 재조회 없음)
            adapter.novelNamesMap = cachedNovelNamesMap
            // 카드에 얹을 사건 필드값 — 화면에 실린 사건만 조회
            loadEventFieldSummaries(events)
            // 등장 캐릭터도 같은 규약으로 한 번에 — 카드가 바인딩마다 묻던 자리다
            loadEventCharacters(events)
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

        // 데이터 처리 결과 알림 (사건 저장/수정/삭제 성공·실패 즉시 통보 + 작업 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
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

        // 목록 창은 **축이 아니라 강조 구간**이다 — 축으로 쓰면 같은 x가 바와 슬라이더에서
        // 서로 다른 해를 가리키고, 줌을 올려 창 폭이 0이 되면 바가 통째로 죽는다.
        viewModel.visibleRange.observe(viewLifecycleOwner) { (start, end) ->
            binding.eventDensityBar.setWindow(start, end)
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
            binding.eventDensityBar.setDensityData(density)
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
            // 바의 축은 **슬라이더와 같은 수**다(단일 소스).
            binding.eventDensityBar.setRange(-100, 100)
            binding.minYearLabel.text = ""
            binding.maxYearLabel.text = ""
            return
        }

        val minYear = events.minOf { it.year }
        val maxYear = events.maxOf { it.year }

        // **범위·눈금·값은 한 벌로 나온다**(`SliderRange` — 캐릭터 상세의 시점 슬라이더와
        // 같은 함수다. 종전에는 같은 판단이 두 벌로 적혀 있었고 **한쪽만 옳았다**).
        val spec = SliderRange.of(minYear, maxYear, viewModel.centerYear.value, pad = 10)

        // 바의 축은 **슬라이더와 같은 수**다 — 둘이 한 벌의 눈금으로 읽히도록 레이아웃이
        // 같은 좌우 여백으로 얹어 둔 것이 그 근거다(TimelineDisplayOrder KDoc).
        binding.eventDensityBar.setRange(spec.from.toInt(), spec.to.toInt())
        binding.yearSlider.applyRange(spec)

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
                MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_as_standard_year)
            .setMessage(getString(R.string.standard_year_confirm, novel.title, year))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.setNovelStandardYear(novel.id, year)
                Toast.makeText(requireContext(), getString(R.string.standard_year_set_done, novel.title, year), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun eventDialogDataProvider(): EventEditDialogFragment.DataProvider =
        object : EventEditDialogFragment.DataProvider {
            override suspend fun getAllNovelsList(): List<Novel> = viewModel.getAllNovelsList()
            override suspend fun getAllCharactersList(): List<Character> = viewModel.getAllCharactersList()
            override suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> = viewModel.getCharacterIdsForEvent(eventId)
            override suspend fun getNovelIdsForEvent(eventId: Long): List<Long> = viewModel.getNovelIdsForEvent(eventId)
            override suspend fun getEventFieldsForUniverse(universeId: Long?) = viewModel.getEventFieldsForUniverse(universeId)
            override suspend fun getEventFieldValuesForEvent(eventId: Long) = viewModel.getEventFieldValuesForEvent(eventId)
            override suspend fun insertEventField(field: com.novelcharacter.app.data.model.FieldDefinition) =
                viewModel.insertEventField(field)
            override fun insertEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, fieldSubmission: com.novelcharacter.app.data.repository.EventFieldValueMerge.Submission) {
                viewModel.insertEvent(event, characterIds, novelIds, fieldSubmission)
            }
            override fun updateEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, fieldSubmission: com.novelcharacter.app.data.repository.EventFieldValueMerge.Submission) {
                viewModel.updateEvent(event, characterIds, novelIds, fieldSubmission)
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
                shiftDirection: EventEditDialogFragment.ShiftDirection,
                delta: Int, originalNovelIds: List<Long>, originalUniverseId: Long?,
                fieldSubmission: com.novelcharacter.app.data.repository.EventFieldValueMerge.Submission
            ) {
                viewModel.updateEventAndShiftOthers(event, characterIds, novelIds, shiftDirection, delta, originalNovelIds, originalUniverseId, fieldSubmission)
            }
        }

    private fun showEditEventDialog(event: TimelineEvent?) {
        // 신규 사건이면 현재 필터 작품을 미리 선택 → 세계관 즉시 확정으로 역법 시드가 곧바로 동작(+작품 자동 연결).
        val preNovels = if (event == null) listOfNotNull(viewModel.filterNovelId.value) else emptyList()
        EventEditDialogFragment.show(childFragmentManager, event = event, preSelectedNovelIds = preNovels)
    }

    /** 간편 사건 추가: 연도 + 한줄 설명만으로 임시 사건 생성 */
    private fun showQuickAddDialog(year: Int) {
        QuickAddEventDialogFragment.show(childFragmentManager, year)
    }

    override fun onResume() {
        super.onResume()
        // 상태 저장에 막혀 물러난 요청을 여기서 잇는다 (B-198) — 홈 키로 내려갔다 돌아오는
        // 길에는 `onViewCreated`가 다시 돌지 않아, 이 호출이 없으면 요청이 영영 붙들린다.
        consumeFixRequest()
        // 글로벌 검색에서 사건 클릭 시 전달된 연도로 이동
        val prefs = requireContext().getSharedPreferences("timeline_ui_state", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("pending_navigate", false)) {
            prefs.edit().remove("pending_navigate").apply()
            val year = prefs.getInt("center_year", 0)
            viewModel.setSelectedYear(year)
        }
        maybeShowLongPressHint()
    }

    /**
     * 롱프레스 전용 기능(재정렬/간편 추가) 1회성 힌트 (B-8).
     *
     * 반드시 onResume에서 호출한다 — onViewCreated 시점에는 홈 ViewPager2가 이 탭을
     * 화면 밖에서 미리 생성하는 중이라 뷰가 윈도우에 부착되지 않아 Snackbar.make가
     * "No suitable parent found"로 크래시한다(v35 업데이트 직후 전 사용자 크래시 루프의 원인).
     * ViewPager2는 현재 보이는 페이지만 RESUMED로 올리므로, onResume 시점에는
     * (a) 뷰 부착이 보장되고 (b) 사용자가 실제로 이 탭을 볼 때만 힌트가 소비된다.
     */
    private fun maybeShowLongPressHint() {
        val ctx = context ?: return
        if (com.novelcharacter.app.util.OnboardingPrefs.isShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_TIMELINE_HINT_SHOWN)) return
        val root = _binding?.root ?: return
        // 이중 방어: 부착 전이면 이번에는 건너뛴다 — 플래그를 소비하지 않았으므로 다음 onResume에 다시 시도된다
        if (!root.isAttachedToWindow) return
        com.novelcharacter.app.util.OnboardingPrefs.markShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_TIMELINE_HINT_SHOWN)
        com.google.android.material.snackbar.Snackbar
            .make(root, R.string.hint_timeline_longpress, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.hint_dismiss) { }
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
