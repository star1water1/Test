package com.novelcharacter.app.ui.character

import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.graphics.Bitmap
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.novelcharacter.app.util.DetailListSort
import com.novelcharacter.app.util.factionSpanLabel
import com.novelcharacter.app.util.dismissSafely
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.logOperation
import com.novelcharacter.app.util.OpResult
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
import com.novelcharacter.app.share.CardTheme
import com.novelcharacter.app.share.CharacterCardRenderer
import com.novelcharacter.app.share.PdfConfig
import com.novelcharacter.app.share.PdfExporter
import com.novelcharacter.app.ui.adapter.TimelineAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.novelcharacter.app.data.model.FieldType

class CharacterDetailFragment : Fragment(), com.novelcharacter.app.ui.timeline.EventEditDialogFragment.Host {

    /**
     * 이 화면 진입의 랜덤 시드(B-103 D3) — 대표가 없는 캐릭터의 그림을 고른다.
     * 같은 시드가 유지되는 동안에는 재바인드에도 그림이 튀지 않는다.
     */
    private val imageSeed: Long = com.novelcharacter.app.util.CharacterRepresentativeImage.newSeed()

    /**
     * ☆ 상태를 현재 장에 맞추는 콜백(B-103 D7). 재등록 전에 반드시 해제한다 —
     * `setupImages`는 캐릭터가 갱신될 때마다 다시 불리므로, 쌓이면 한 번 넘길 때
     * 같은 일이 여러 번 돈다.
     */
    private var representativePageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback =
        object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {}

    private var _binding: FragmentCharacterDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CharacterViewModel by viewModels()

    /**
     * 체형 분석 설정(⚙)에서 고친 필드를 저장하는 자리 — 필드 관리 화면과 **같은 뷰모델**이다.
     * 키 변경 시의 수식·상태변화 이력 이관이 거기 들어 있으므로 별도 저장 경로를 만들지 않는다.
     */
    private val fieldViewModel: com.novelcharacter.app.ui.field.FieldViewModel by viewModels()

    private var characterId: Long = -1L

    private var cachedCharacter: Character? = null

    /**
     * 같은 작품 캐릭터의 체형 수치 — 순위 계산이 모아 둔 것을 실루엣 크게 보기의
     * 작품 평균 오버레이가 재사용한다(같은 조회를 두 번 하지 않는다).
     */
    private var bodyPeerMeasurements: List<com.novelcharacter.app.util.BodyMeasurements> = emptyList()

    /** 관련 사건의 보기 정렬(B-85)과 그 원본. 정렬만 바꿀 때 DB를 다시 읽지 않기 위해 들고 있는다. */
    private var eventSortMode = DetailListSort.EventMode.CHRONO
    private var currentEvents: List<com.novelcharacter.app.data.model.TimelineEvent> = emptyList()

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
                R.id.action_representative_image_help -> {
                    com.novelcharacter.app.ui.common.HelpDialog.showHelp(
                        requireContext(),
                        com.novelcharacter.app.ui.common.HelpDialog.Topic.REPRESENTATIVE_IMAGE
                    )
                    true
                }
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
        // ⚙ — 체형 분석 설정(대상 필드의 편집 다이얼로그). 저장은 필드 관리 화면과 **같은
        // 경로**를 탄다(키 변경 시의 수식·이력 이관까지) — 여기만 다른 저장을 쓰면 갈린다.
        fieldRenderer.onOpenBodySettings = { field ->
            com.novelcharacter.app.ui.field.FieldEditDialog
                // 전역 구역 필드(universeId null — B-119 확장)는 0을 넘긴다 — 이 다이얼로그의
                // 기존 관례(0 = 세계관 문맥 없음)이고, 편집 저장은 existingField.copy라
                // 필드의 실제 구역(null)이 그대로 보존된다. 세계관 전용 섹션(대결 등급 산정)이
                // 0에서 꺼지는 것도 옳다 — 전역 필드는 그것을 가질 수 없다(설계 1-2).
                .newInstance(field.universeId ?: 0L, field)
                .show(childFragmentManager, "body_analysis_settings")
        }
        // 실루엣 탭 — 크게 보기. 작품 평균은 순위 계산이 이미 모아 둔 이웃 수치를 재사용한다.
        fieldRenderer.onOpenSilhouette = { _, measured, config ->
            SilhouetteLargeDialog.newInstance(cachedCharacter?.name.orEmpty()).apply {
                this.measured = measured
                this.config = config
                this.peers = bodyPeerMeasurements
            }.show(childFragmentManager, SilhouetteLargeDialog.TAG)
        }
        setupBodyFieldEditResultListener()

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

    /**
     * ⚙로 연 체형 분석 설정의 저장 결과를 받는다.
     *
     * 저장은 [fieldViewModel]에 맡기고(필드 관리 화면과 같은 경로), 화면은 다시 그린다 —
     * 토글·파트 연결을 바꾼 사용자가 카드가 그대로인 것을 보면 안 먹은 줄 안다(원칙 04).
     */
    private fun setupBodyFieldEditResultListener() {
        childFragmentManager.setFragmentResultListener(
            com.novelcharacter.app.ui.field.FieldEditDialog.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val json = bundle.getString(
                com.novelcharacter.app.ui.field.FieldEditDialog.RESULT_FIELD_JSON
            ) ?: return@setFragmentResultListener
            val savedField = com.google.gson.Gson()
                .fromJson(json, com.novelcharacter.app.data.model.FieldDefinition::class.java)
            if (savedField.id == 0L) return@setFragmentResultListener   // 이 화면은 생성 경로가 없다
            fieldViewModel.updateField(savedField)
            cachedCharacter?.let { displayCharacter(it) }
        }
    }

    // ===== Character display =====

    private fun observeCharacter() {
        // 데이터 처리 결과 알림 (관계·관계변화·상태변화 등 즉시 통보 + 작업 이력 기록)
        viewModel.result.observe(viewLifecycleOwner) { result ->
            result?.let {
                notifyResult(it)
                viewModel.clearResult()
            }
        }

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
            onClick = { event -> showEventEditDialog(event) },
            onLongClick = { event -> showEventActions(event) },
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            loadCharactersForEvent = { eventId -> viewModel.getCharactersForEvent(eventId) }
        )
        binding.eventsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.eventsRecyclerView.adapter = timelineAdapter

        viewModel.getEventsForCharacter(characterId).observe(viewLifecycleOwner) { events ->
            currentEvents = events
            renderEvents(timelineAdapter)
        }

        // 사건 추가 버튼
        binding.btnAddEvent.setOnClickListener {
            com.novelcharacter.app.ui.timeline.EventEditDialogFragment.show(
                childFragmentManager,
                preSelectedCharacterIds = setOf(characterId),
                preSelectedNovelIds = listOfNotNull(cachedCharacter?.novelId)
            )
        }

        // 보기 정렬(B-85) — 사건에는 저장 순서가 없어 순서 편집과 충돌하지 않는다.
        eventSortMode = CharacterDetailSortPrefs.eventMode(requireContext())
        updateEventSortButton()
        binding.btnSortEvents.setOnClickListener { anchor ->
            val modes = DetailListSort.EventMode.values()
            PopupMenu(requireContext(), anchor).apply {
                modes.forEachIndexed { i, m -> menu.add(0, i, i, eventSortLabelRes(m)) }
                setOnMenuItemClickListener { item ->
                    eventSortMode = modes[item.itemId]
                    CharacterDetailSortPrefs.setEventMode(requireContext(), eventSortMode)
                    updateEventSortButton()
                    renderEvents(timelineAdapter)
                    true
                }
                show()
            }
        }
    }

    /** 보이는 순서만 만든다 — 정렬을 바꿔도 DB를 다시 읽지 않는다(원칙 04). */
    private fun renderEvents(adapter: TimelineAdapter) {
        adapter.submitEventList(currentEvents.sortedWith(DetailListSort.events(eventSortMode)))
    }

    private fun updateEventSortButton() {
        binding.btnSortEvents.setText(eventSortLabelRes(eventSortMode))
    }

    private fun eventSortLabelRes(mode: DetailListSort.EventMode): Int = when (mode) {
        DetailListSort.EventMode.CHRONO -> R.string.sort_year_asc
        DetailListSort.EventMode.CHRONO_DESC -> R.string.sort_year_desc
        DetailListSort.EventMode.RECENT_ADDED -> R.string.sort_recent_added
    }

    private fun showEventEditDialog(event: com.novelcharacter.app.data.model.TimelineEvent) {
        com.novelcharacter.app.ui.timeline.EventEditDialogFragment.show(childFragmentManager, event)
    }

    /**
     * 사건 롱프레스 동작 — 편집 / 이 캐릭터에서 연결 해제 / 삭제.
     *
     * 종전에는 캐릭터 화면의 사건 목록이 아무 반응도 하지 않아 사건을 고치거나 지우려면
     * 반드시 연표 탭으로 가야 했다(원칙 04). '연결 해제'를 '삭제'와 따로 두는 이유는
     * 사건이 여러 캐릭터의 공유물이기 때문이다 — 여기서 지우면 다른 캐릭터의 연표에서도
     * 사라지므로, 그 사실을 확인 다이얼로그로 먼저 말한다(원칙: 무통보 파괴 금지).
     */
    private fun showEventActions(event: com.novelcharacter.app.data.model.TimelineEvent) {
        val items = arrayOf(
            getString(R.string.edit),
            getString(R.string.event_unlink_from_character),
            getString(R.string.delete)
        )
        val title = "${getString(R.string.event_year_format, event.year)} — ${event.description.take(50)}"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEventEditDialog(event)
                    1 -> viewModel.unlinkEventFromCharacter(event.id, characterId)
                    2 -> confirmDeleteEvent(event)
                }
            }
            .show()
    }

    private fun confirmDeleteEvent(event: com.novelcharacter.app.data.model.TimelineEvent) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 이 사건에 몇 명이 걸려 있는지 먼저 센다 — "나만의 사건"인지 "공유 사건"인지에 따라
            // 삭제의 파급이 다르고, 그 차이를 모르면 동의라고 할 수 없다.
            val others = try {
                viewModel.getCharacterIdsForEvent(event.id).count { it != characterId }
            } catch (_: Exception) {
                0
            }
            if (!isAdded) return@launch
            val message = if (others > 0) {
                getString(R.string.event_delete_shared_confirm, event.description, others)
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

    override fun eventDialogDataProvider(): com.novelcharacter.app.ui.timeline.EventEditDialogFragment.DataProvider =
        object : com.novelcharacter.app.ui.timeline.EventEditDialogFragment.DataProvider {
            override suspend fun getAllNovelsList() = viewModel.getAllNovelsList()
            override suspend fun getAllCharactersList() = viewModel.getAllCharactersList()
            override suspend fun getCharacterIdsForEvent(eventId: Long) = viewModel.getCharacterIdsForEvent(eventId)
            override suspend fun getNovelIdsForEvent(eventId: Long) = viewModel.getNovelIdsForEvent(eventId)
            override suspend fun getEventFieldsForUniverse(universeId: Long?) = viewModel.getEventFieldsForUniverse(universeId)
            override suspend fun getEventFieldValuesForEvent(eventId: Long) = viewModel.getEventFieldValuesForEvent(eventId)
            override suspend fun insertEventField(field: com.novelcharacter.app.data.model.FieldDefinition) =
                viewModel.insertEventField(field)
            override fun insertEvent(event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, fieldSubmission: com.novelcharacter.app.data.repository.EventFieldValueMerge.Submission) { viewModel.insertEvent(event, characterIds, novelIds, fieldSubmission) }
            override fun updateEvent(event: com.novelcharacter.app.data.model.TimelineEvent, characterIds: List<Long>, novelIds: List<Long>, fieldSubmission: com.novelcharacter.app.data.repository.EventFieldValueMerge.Submission) { viewModel.updateEvent(event, characterIds, novelIds, fieldSubmission) }
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
                shiftDirection: com.novelcharacter.app.ui.timeline.EventEditDialogFragment.ShiftDirection,
                delta: Int, originalNovelIds: List<Long>, originalUniverseId: Long?,
                fieldSubmission: com.novelcharacter.app.data.repository.EventFieldValueMerge.Submission
            ) {
                viewModel.updateEventAndShiftOthers(event, characterIds, novelIds, shiftDirection, delta, originalNovelIds, originalUniverseId, fieldSubmission)
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
                    // 세력 멤버 목록은 '1000년~' · '탈퇴 (1500년)'을 보여 주는데 이쪽은 이름뿐이라,
                    // 같은 소속이 어디서 보느냐에 따라 아는 것이 달랐다. 표기는 세력 화면과 같은
                    // 자리(factionSpanLabel)에서 가져온다.
                    val span = ctx.factionSpanLabel(membership.joinYear, membership.leaveYear)
                    val chipText = when {
                        isDeparted && span != null ->
                            getString(R.string.faction_chip_departed_span, faction.name, span)
                        isDeparted -> getString(R.string.faction_chip_departed, faction.name)
                        span != null -> getString(R.string.faction_chip_span, faction.name, span)
                        else -> faction.name
                    }

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
                // 목표 비율 '작품 평균' 기준의 재료 (B-94) — 위 계산이 모아 둔 이웃 수치를
                // 그대로 쓴다(같은 조회를 두 번 하지 않는다. 크게 보기의 오버레이와 같은 자리).
                fieldRenderer.bodyPeerAverage =
                    com.novelcharacter.app.util.BodyEditorModel.peerAverageBody(bodyPeerMeasurements)

                // 값 라이브러리 표시 라벨 — 통계·칩과 카드 표기 일치 (검토 A12)
                fieldRenderer.valueResolvers = runCatching {
                    (requireActivity().application as com.novelcharacter.app.NovelCharacterApp)
                        .fieldValueLibraryRepository.resolversForFields(fields.map { it.id })
                }.getOrDefault(emptyMap())

                if (_binding == null) return@launch
                val sliderYear = timeSliderHelper.currentSliderYear
                if (timeSliderHelper.isTimeViewActive && sliderYear != null) {
                    timeSliderHelper.applyTimeView(sliderYear)
                } else {
                    fieldRenderer.displayDynamicFields(fields, values, percentileData, calculatedResults)
                }
            } else {
                // 작품 미배정(미분류) 캐릭터도 필드값을 **보관**한다 — 저장 경로가 더 이상 지우지
                // 않기 때문이다(N2). 여기서 아무것도 그리지 않으면 그 값은 '일일이 확인하지 않으면
                // 존재를 알 수 없는 데이터'가 된다(원칙 04 위반). 그래서 값이 가리키는 정의를
                // 세계관을 가리지 않고 직접 찾아 그대로 보여 준다.
                val values = viewModel.getValuesByCharacterList(character.id)
                if (_binding == null) return@launch
                // CALCULATED는 저장값이 없고 여기서는 참조 필드가 갖춰지지 않아 수식을 평가할 수
                // 없다 — 부분 집합으로 평가하면 사실이 아닌 숫자를 보여주므로 아예 제외한다.
                val orphanFields = viewModel
                    .getFieldsByIds(values.map { it.fieldDefinitionId }.distinct())
                    .filter { it.fieldType != FieldType.CALCULATED }
                if (_binding == null) return@launch
                // cachedFields는 상태변화 추가 다이얼로그의 '필드' 선택지 소스이기도 하다.
                // 여기에 타 세계관 정의를 넣으면 소속되지도 않은 세계관의 필드가 선택 가능해진다.
                timeSliderHelper.cachedFields = emptyList()
                timeSliderHelper.cachedValues = emptyList()
                timeSliderHelper.cachedPercentileData = emptyMap()
                timeSliderHelper.cachedCalculatedResults = emptyMap()
                binding.dynamicFieldsContainer.removeAllViews()
                if (orphanFields.isNotEmpty()) {
                    // 백분위·체형 순위는 세계관 스코프가 있어야 성립하므로 계산하지 않는다.
                    fieldRenderer.valueResolvers = emptyMap()
                    fieldRenderer.bodyRankingInfo = null
                    // 작품이 없으면 '작품 평균'도 없다 — 앞 캐릭터의 재료가 남아 있으면
                    // 미분류 캐릭터가 남의 작품 평균으로 채점된다.
                    fieldRenderer.bodyPeerAverage = null
                    fieldRenderer.displayDynamicFields(orphanFields, values, emptyMap(), emptyMap())
                }
            }
        }

        setupImages(character)
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
        val calculatedFields = fields.filter { it.fieldType == FieldType.CALCULATED }
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
            // 평가 실패도 값으로 남긴다 — 종전에는 결과에서 빠져 칸이 비어 보였고,
            // 사용자는 수식이 고장 났다는 것 자체를 알 수 없었다(U-9).
            results[field.id] = com.novelcharacter.app.util.FormulaDisplay
                .evaluateForDisplay(formula, evaluator::evaluate)
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

        for (field in fields) {
            // "이 타입이 수를 내는가"는 앱에 한 벌뿐이다 (B-55) — 종전에는 여기 · 목록 정렬 ·
            // 통계 순위가 같은 집합을 따로 적고 있었고, 갈리면 같은 필드가 화면마다 다른 축으로 읽힌다.
            if (!com.novelcharacter.app.util.FieldValueSorter.isNumericSortType(field.fieldType)) continue

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
            val gradeMap: Map<String, Double>? = if (field.fieldType == FieldType.GRADE) {
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
            val myValue: Double? = when (field.fieldType) {
                FieldType.CALCULATED -> calculatedResults[field.id]?.toDoubleOrNull()
                FieldType.GRADE -> {
                    val rawVal = valueMap[field.id]?.value ?: ""
                    gradeMap?.get(rawVal)
                }
                // NUMBER·BODY_SIZE는 저장 원문이 곧 수다. 나머지 타입은 위 `isNumericSortType`이
                // 이미 걸러 여기 못 온다 — 그래도 적는 것은 `when`을 전부 덮게 하기 위해서다.
                FieldType.NUMBER, FieldType.BODY_SIZE,
                FieldType.TEXT, FieldType.SELECT, FieldType.MULTI_TEXT, null ->
                    valueMap[field.id]?.value?.toDoubleOrNull()
            }

            if (myValue == null || myValue.isNaN() || myValue.isInfinite()) continue

            var novelPercentile: Float? = null
            var universePercentile: Float? = null

            val isCalculated = field.fieldType == FieldType.CALCULATED
            val isGrade = field.fieldType == FieldType.GRADE

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

        // 캐릭터당 개별 쿼리(N+1) 대신 범위 전체 값을 1회 배치 조회 후 인메모리 그룹핑
        val fieldIdToKey = allFields.associateBy({ it.id }, { it.key })
        val valuesByChar = viewModel.getValuesForCharacters(characters.map { it.id })
            .groupBy { it.characterId }

        return characters.mapNotNull { char ->
            val charKeyValues = mutableMapOf<String, String>()
            for (v in valuesByChar[char.id].orEmpty()) {
                val key = fieldIdToKey[v.fieldDefinitionId] ?: continue
                if (v.value.isNotBlank()) charKeyValues[key] = v.value
            }
            val eval = com.novelcharacter.app.util.FormulaEvaluator(charKeyValues, allFields)
            try { eval.evaluate(formula).takeIf { it.isFinite() } } catch (_: Exception) { null }
        }
    }

    // ===== Images =====

    private fun setupImages(character: com.novelcharacter.app.data.model.Character) {
        // 캐릭터를 통째로 받는다 — 시작 위치를 대표로 잡으려면 포인터와 id가 함께 필요하다(B-103 D4).
        val imagePaths: List<String> = try {
            GSON.fromJson(character.imagePaths, IMAGE_PATHS_TYPE) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (imagePaths.isEmpty()) {
            binding.imageCarouselContainer.visibility = View.GONE
            binding.representativeMissingNotice.visibility = View.GONE
            return
        }

        binding.imageCarouselContainer.visibility = View.VISIBLE
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
                        putString("imagePaths", character.imagePaths)
                        putInt("startPosition", position)
                    }
                    findNavController().navigateSafe(R.id.characterDetailFragment, R.id.imageViewerFragment, bundle)
                }
                val boundPosition = position
                val job = viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(path, getAppDir(), 1024)
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

        val pick = com.novelcharacter.app.util.CharacterRepresentativeImage.pickFrom(
            imagePaths, character.representativeImagePath, imageSeed, character.id
        )

        // 시작 위치: **대표가 있으면 그 장**, 없으면 시드 랜덤 (B-103 D4).
        // 종전에는 무조건 랜덤이라 대표를 지정해도 상세만 다른 장에서 열렸다.
        if (imagePaths.size > 1) {
            binding.imageViewPager.setCurrentItem(pick.index.coerceAtLeast(0), false)
        }

        setupRepresentativeStar(character, imagePaths, pick)
    }

    /**
     * 대표 지정 ☆ (B-103 D7) + 사후 고지 (D6ⓑ).
     *
     * 탭 = 지금 보고 있는 그 장을 대표로, 다시 탭 = 해제. **1탭이다**(원칙 04).
     * 이미지 탭(=뷰어 열기)과 스와이프는 그대로 둔다 — 기존 제스처를 뺏지 않는다.
     */
    private fun setupRepresentativeStar(
        character: com.novelcharacter.app.data.model.Character,
        imagePaths: List<String>,
        pick: com.novelcharacter.app.util.CharacterRepresentativeImage.Pick
    ) {
        // 지정한 대표를 목록에서 못 찾았다 — 앱 밖 삭제·폴더 왕복처럼 사전 고지가 불가능한
        // 자리다. **포인터를 조용히 지우지 않는다**(D6ⓑ) — 폴더 왕복은 파일을 되돌려 놓을 수
        // 있어서, 잠깐 안 보인다고 지우면 돌아왔을 때 지정이 사라져 있다.
        binding.representativeMissingNotice.visibility =
            if (pick.pinnedMissing) View.VISIBLE else View.GONE

        fun currentPathIsRepresentative(): Boolean {
            val position = binding.imageViewPager.currentItem
            val path = imagePaths.getOrNull(position) ?: return false
            return com.novelcharacter.app.util.ImagePathMatch.same(path, character.representativeImagePath)
        }

        fun renderStar() {
            val pinned = currentPathIsRepresentative()
            binding.btnRepresentativeImage.setImageResource(
                if (pinned) R.drawable.ic_star else R.drawable.ic_star_outline
            )
            binding.btnRepresentativeImage.contentDescription = getString(
                if (pinned) R.string.representative_image_clear else R.string.representative_image_set
            )
        }

        renderStar()

        // 스와이프로 장을 넘기면 ☆도 그 장의 상태를 말해야 한다 — 아니면 지정 여부를
        // 알려면 일일이 눌러 봐야 한다(원칙 04가 금지하는 상태다).
        binding.imageViewPager.unregisterOnPageChangeCallback(representativePageCallback)
        representativePageCallback =
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = renderStar()
            }
        binding.imageViewPager.registerOnPageChangeCallback(representativePageCallback)

        binding.btnRepresentativeImage.setOnClickListener {
            val position = binding.imageViewPager.currentItem
            val path = imagePaths.getOrNull(position) ?: return@setOnClickListener
            val clearing = currentPathIsRepresentative()
            // 저장은 이 자리에서 곧바로 한다(원칙 04 — 저장 버튼을 거치지 않는다).
            // 상세는 편집 폼이 아니므로 취소 계약에 걸리지 않는다.
            viewModel.updateCharacter(
                character.copy(
                    representativeImagePath = if (clearing) "" else path,
                    updatedAt = System.currentTimeMillis()
                )
            )
            Toast.makeText(
                requireContext(),
                if (clearing) R.string.representative_image_cleared else R.string.representative_image_set_done,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private var appDir: java.io.File? = null
    private fun getAppDir(): java.io.File {
        return appDir ?: requireContext().filesDir.also { appDir = it }
    }

    /**
     * 같은 작품 내 다른 캐릭터들의 BODY_SIZE 데이터를 조회하여
     * 현재 캐릭터의 가슴/허리/엉덩이/키 순위를 계산한다.
     *
     * **부위 해석은 [com.novelcharacter.app.util.BodyMeasurements]가 든다**(설계 3-3).
     * 종전에는 여기 인라인 파서(`parseBwh`)가 따로 있어 **파트 라벨도 파트 연결 설정도
     * 보지 않고 늘 앞 세 값을 B/W/H로 봤다** — 같은 화면의 분석 카드는 이미 해석 사다리를
     * 쓰고 있었으므로, 순위만 다른 부위를 세는 일이 실제로 가능했다.
     *
     * 모아 둔 이웃 수치는 [bodyPeerMeasurements]에 남겨 실루엣 크게 보기의 작품 평균
     * 오버레이가 재사용한다(같은 조회를 두 번 하지 않는다).
     */
    private suspend fun computeBodyRanking(
        fields: List<com.novelcharacter.app.data.model.FieldDefinition>,
        values: List<com.novelcharacter.app.data.model.CharacterFieldValue>,
        character: Character,
        novel: com.novelcharacter.app.data.model.Novel?
    ): com.novelcharacter.app.util.RankingInfo? {
        bodyPeerMeasurements = emptyList()
        val novelId = novel?.id ?: return null

        // BODY_SIZE 필드 찾기
        val bodySizeField = fields.find {
            com.novelcharacter.app.data.model.SemanticRole.fromConfig(it.config) ==
                com.novelcharacter.app.data.model.SemanticRole.BODY_SIZE
        } ?: fields.find { it.fieldType == FieldType.BODY_SIZE } ?: return null

        val config = com.novelcharacter.app.data.model.BodyAnalysisConfig.fromConfig(bodySizeField.config)
        val heightField = fields.find {
            com.novelcharacter.app.data.model.SemanticRole.fromConfig(it.config) ==
                com.novelcharacter.app.data.model.SemanticRole.HEIGHT
        }
        val weightField = fields.find {
            com.novelcharacter.app.data.model.SemanticRole.fromConfig(it.config) ==
                com.novelcharacter.app.data.model.SemanticRole.WEIGHT
        }

        fun resolve(
            valuesById: Map<Long, com.novelcharacter.app.data.model.CharacterFieldValue>
        ): com.novelcharacter.app.util.BodyMeasurements? {
            val raw = valuesById[bodySizeField.id]?.value ?: return null
            if (raw.isBlank()) return null
            return com.novelcharacter.app.util.BodyMeasurements.resolve(
                field = bodySizeField,
                rawValue = raw,
                heightText = heightField?.let { valuesById[it.id]?.value },
                weightText = weightField?.let { valuesById[it.id]?.value },
                config = config
            )
        }

        val mine = resolve(values.associateBy { it.fieldDefinitionId }) ?: return null

        // 같은 작품의 모든 캐릭터 수치 수집
        val allCharacters = viewModel.getCharactersByNovelList(novelId)
        if (allCharacters.size <= 1) return null

        val peers = mutableListOf<com.novelcharacter.app.util.BodyMeasurements>()
        for (char in allCharacters) {
            val charValues = viewModel.getValuesByCharacterList(char.id).associateBy { it.fieldDefinitionId }
            resolve(charValues)?.let { peers.add(it) }
        }
        bodyPeerMeasurements = peers
        if (peers.size <= 1) return null

        // 부위별로 **값이 있는 캐릭터만** 센다 — 빈 칸을 0으로 세면 순위가 무너진다.
        fun rankOf(pick: (com.novelcharacter.app.util.BodyMeasurements) -> Double?): Int? {
            val my = pick(mine) ?: return null
            val all = peers.mapNotNull(pick)
            if (all.size <= 1) return null
            return com.novelcharacter.app.util.BodyAnalysisHelper.computeRank(my, all)
        }

        val total = peers.count { it.bust != null || it.waist != null || it.hip != null }
        if (total <= 1) return null

        return com.novelcharacter.app.util.RankingInfo(
            bustRank = rankOf { it.bust },
            waistRank = rankOf { it.waist },
            hipRank = rankOf { it.hip },
            heightRank = rankOf { it.heightCm },
            totalCharacters = total
        )
    }

    private fun loadCharacterStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val character = viewModel.getCharacterByIdSuspend(characterId) ?: return@launch
            val relationships = viewModel.getRelationshipsForCharacterList(characterId)
            val events = viewModel.getEventsForCharacterSuspend(characterId)
            val stateChanges = viewModel.getChangesByCharacterList(characterId)

            // 필드 완성도 — 판정은 [CompletionRate] 하나다(B-100). 종전에는 이 화면만
            // 분모에서 CALCULATED를 빼지 않고 분자로 값을 통째로 세어, **같은 캐릭터가
            // 통계 화면과 다른 %로 보였다.**
            val statsCtx = context ?: return@launch
            val novel = character.novelId?.let { viewModel.getNovelById(it) }
            val universeId = novel?.universeId
            val fieldCompletion = if (universeId != null) {
                val fields = viewModel.getFieldsByUniverseList(universeId)
                val filledDefIds = viewModel.getValuesByCharacterList(characterId)
                    .filter { it.value.isNotBlank() }
                    .map { it.fieldDefinitionId }
                    .toSet()
                com.novelcharacter.app.util.CompletionRate.percentOf(
                    fields, filledDefIds,
                    com.novelcharacter.app.ui.stats.CompletionWeightPrefs.weights(statsCtx)
                ) ?: 0f
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
        val themeValues = CardTheme.entries.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
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
                            // 풀사이즈·무가드 디코드는 대용량 이미지에서 OOM 위험 → 공용 유틸로 다운샘플+경로가드.
                            // 카드에 박히는 그림도 대표를 따른다 (B-103 D4) — 종전에는 0번 고정이라
                            // 대표를 지정해도 내보낸 카드만 다른 사람처럼 보였다.
                            com.novelcharacter.app.util.CharacterRepresentativeImage.path(
                                character.imagePaths, character.representativeImagePath,
                                imageSeed, character.id
                            )?.let { com.novelcharacter.app.util.CharacterImageLoader.decodeThumbnail(it, getAppDir(), 1024) }
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

        // HTML 생성이 오래 걸릴 수 있어 진행 표시 — 조용한 실패와 구분(변수 제어)
        val progress = com.novelcharacter.app.util.createProgressDialog(
            requireContext(), R.string.pdf_generating
        )
        progress.show()
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
                val config = PdfConfig(
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
                // PDF는 시스템 인쇄 대화상자로 넘어가므로 성공 통보는 그쪽이 담당 — 이력만 기록
                logOperation(OpResult.success(OpResult.CAT_SHARE,
                    getString(R.string.result_pdf_shared, character.name)))
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
                logOperation(OpResult.failure(OpResult.CAT_SHARE,
                    getString(R.string.result_pdf_share_failed), e.message))
            } finally {
                progress.dismissSafely()
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
