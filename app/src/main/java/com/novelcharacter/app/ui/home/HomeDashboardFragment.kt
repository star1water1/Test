package com.novelcharacter.app.ui.home

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BirthdayCharacterItem
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.RecentActivity
import com.novelcharacter.app.databinding.FragmentHomeDashboardBinding
import com.novelcharacter.app.excel.ExcelTransferController
import com.novelcharacter.app.ui.adapter.BirthdayBannerAdapter
import com.novelcharacter.app.ui.assistant.AssistantViewModel
import com.novelcharacter.app.ui.duel.DuelEntryPrefs
import com.novelcharacter.app.util.BirthdayCelebration
import com.novelcharacter.app.util.BirthdayHelper
import com.novelcharacter.app.util.DuelEntry
import com.novelcharacter.app.util.BirthdayCelebrationPrefs
import com.novelcharacter.app.util.OnboardingPrefs
import com.novelcharacter.app.util.QuotePicker
import com.novelcharacter.app.util.SqlInChunks
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 홈 대시보드 — 시작 화면. 최근 활동·다가오는 생일·바로가기·도구 진입점을 모은다.
 * 데이터는 전부 기존 배관 재사용: RecentActivityDao, BirthdayHelper(상태변화 DAO),
 * AssistantViewModel(정합성 오류 수). 새 비즈니스 로직 없음.
 */
class HomeDashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp

    val recentActivities: LiveData<List<RecentActivity>> =
        app.recentActivityDao.getRecentActivities(RecentActivity.MAX_ENTRIES)

    // CharacterViewModel.upcomingBirthdays와 동일 소스 — 전역(작품 필터 없음) 변형
    val upcomingBirthdays: LiveData<List<BirthdayCharacterItem>> =
        app.database.characterStateChangeDao()
            .observeChangesWithDate(CharacterStateChange.KEY_BIRTH)
            .switchMap { changes ->
                liveData(Dispatchers.IO) {
                    val upcoming = BirthdayHelper.filterUpcoming(changes)
                    if (upcoming.isEmpty()) {
                        emit(emptyList())
                        return@liveData
                    }
                    val charMap = app.characterRepository
                        .getCharactersByIds(upcoming.map { it.characterId })
                        .associateBy { it.id }
                    emit(upcoming.mapNotNull { b ->
                        charMap[b.characterId]?.let {
                            BirthdayCharacterItem(
                                character = it,
                                birthMonth = b.birthMonth,
                                birthDay = b.birthDay,
                                daysUntil = b.daysUntil
                            )
                        }
                    })
                }
            }

    /**
     * 오늘 축하 창에 띄울 쪽 (사용자 요청 2026.08.20).
     *
     * **판정은 [BirthdayCelebration]이 하고 여기서는 읽어다 넘긴다** — 그래야 *"어느 대사를
     * 집는가"*·*"날짜 없는 줄은 어떻게 하는가"*를 순수 JVM 시험이 실제로 돌려 본다.
     *
     * 오늘치 판정을 [BirthdayHelper]에 맡기는 것도 같은 근거다: 윤년 2/29 규칙이 두 벌이
     * 되면 축하 창과 위젯·알림이 **다른 날 다른 사람을 말한다**(원칙 05).
     *
     * 읽는 것은 **오늘 생일인 사람 몫뿐이다** — 대사도 작품 제목도 그 id 목록으로만 묻는다.
     */
    suspend fun todayBirthdayPages(dayStamp: Long): List<BirthdayCelebration.Page> =
        withContext(Dispatchers.IO) {
            val birthChanges = app.database.characterStateChangeDao()
                .getChangesWithDate(CharacterStateChange.KEY_BIRTH)
            val todayIds = BirthdayHelper.getTodayBirthdayCharacterIds(birthChanges)
            if (todayIds.isEmpty()) return@withContext emptyList()

            val characters = app.characterRepository.getCharactersByIds(todayIds)
            if (characters.isEmpty()) return@withContext emptyList()

            val quotes = app.characterRepository.getQuotesForCharacters(characters.map { it.id })
                .groupBy { it.characterId }
            val novelTitles = SqlInChunks.flat(characters.mapNotNull { it.novelId }.distinct()) {
                app.database.novelDao().getNovelsByIds(it)
            }.associate { it.id to it.title }

            BirthdayCelebration.pagesOf(
                todayIds = todayIds,
                characters = characters,
                birthChanges = birthChanges,
                quotesByCharacter = quotes,
                novelTitles = novelTitles,
                dayStamp = dayStamp,
                // 그림 추첨의 씨앗도 **날짜**다 — 창을 닫았다 다시 열어도 같은 장이 뜨고,
                // 대사와 같은 주기로 하루마다 바뀐다.
                imageSeed = dayStamp
            )
        }

    /**
     * 대결 타일이 열 곳과 그 부제 — 판단은 [DuelEntry]가 하고 여기서는 **살아 있는지**만 확인해
     * 넘긴다(기억해 둔 축이 지워졌을 수 있다 — 휴지통·세계관 삭제).
     */
    suspend fun duelEntry(lastAxisId: Long): DuelEntryInfo {
        val axis = if (lastAxisId > 0L) app.duelRepository.axis(lastAxisId) else null
        val universes = app.database.universeDao().getAllUniversesList()
        val target = DuelEntry.targetOf(lastAxisId, axis?.universeId, universes.map { it.id })
        val universeName = axis?.let { a -> universes.firstOrNull { it.id == a.universeId }?.name }
        return DuelEntryInfo(target, universeName, axis?.name)
    }

    /** @property universeName·[axisName] 이어할 축이 있을 때만 채워진다 — 부제에 그대로 쓴다. */
    data class DuelEntryInfo(
        val target: DuelEntry.Target,
        val universeName: String?,
        val axisName: String?
    )

    suspend fun universeNames(ids: List<Long>): List<Pair<Long, String>> =
        app.database.universeDao().getAllUniversesList()
            .filter { it.id in ids }
            .map { it.id to it.name }
}

class HomeDashboardFragment : Fragment() {

    private var _binding: FragmentHomeDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeDashboardViewModel by viewModels()

    // 알림 카드·하단 탭 배지용 — 분석 탭의 인스턴스와 별개 스코프(각자 스냅샷 로드)
    private val assistantViewModel: AssistantViewModel by viewModels()

    private var birthdayAdapter: BirthdayBannerAdapter? = null
    private var recentAdapter: RecentActivityAdapter? = null

    private lateinit var excel: ExcelTransferController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 런처 등록 순서 보존을 위해 onCreate에서 생성 (컨트롤러 KDoc 참조)
        excel = ExcelTransferController(this)
        excel.restoreState(savedInstanceState)

        // 축하 창의 '캐릭터 열기'를 듣는다 (R-65). **여기서 거는 것이 요점이다** —
        // `viewLifecycleOwner`에 걸면 회전 도중 온 결과를 조용히 놓치고, 그 놓침은
        // *단추가 눌리는데 아무 일도 안 일어나는* 모양이라 사용자에게는 고장으로 보인다.
        childFragmentManager.setFragmentResultListener(
            BirthdayCelebrationDialog.REQUEST_KEY, this
        ) { _, bundle ->
            val characterId = bundle.getLong(BirthdayCelebrationDialog.RESULT_CHARACTER_ID, 0L)
            if (characterId <= 0L) return@setFragmentResultListener
            findNavController().navigateSafe(
                R.id.homeFragment, R.id.characterDetailFragment,
                bundleOf("characterId" to characterId)
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        excel.saveState(outState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupShortcuts()
        setupBirthdaySection()
        setupRecentSection()
        setupToolCards()
        setupAssistantAlert()
        showWelcomeIfFirstRun(savedInstanceState)
        showBirthdayCelebrationIfDue(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // 대결을 하고 돌아오면 이어할 자리가 달라져 있다 — 부제가 그것을 말한다.
        refreshDuelTile()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 대결 진입점 (B-104)
    // ──────────────────────────────────────────────────────────────────────

    private fun refreshDuelTile() {
        val lastAxisId = DuelEntryPrefs.lastAxisId(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val info = viewModel.duelEntry(lastAxisId)
            if (!isAdded) return@launch
            binding.duelSubtitle.text = if (info.axisName != null) {
                getString(R.string.duel_resume_subtitle, info.universeName.orEmpty(), info.axisName)
            } else {
                getString(R.string.duel_tile_subtitle)
            }
        }
    }

    /**
     * 이어할 축이 있으면 **바로 그 대결 화면으로**(사용자 확정) — 반복 활동이라 재개가 기본이다.
     * 없으면 세계관을 정한 뒤 축 목록으로 간다.
     */
    private fun openDuel() {
        val lastAxisId = DuelEntryPrefs.lastAxisId(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val info = viewModel.duelEntry(lastAxisId)
            if (!isAdded) return@launch
            when (val target = info.target) {
                is DuelEntry.Target.Resume ->
                    findNavController().navigateSafe(
                        R.id.homeFragment, R.id.duelPlayFragment,
                        bundleOf("axisId" to target.axisId)
                    )
                is DuelEntry.Target.AxisList ->
                    findNavController().navigateSafe(
                        R.id.homeFragment, R.id.duelAxisListFragment,
                        bundleOf("universeId" to target.universeId)
                    )
                is DuelEntry.Target.PickUniverse -> showUniversePicker(target.universeIds)
                DuelEntry.Target.NeedUniverse ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.duel_need_universe)
                        .setPositiveButton(R.string.confirm, null)
                        .show()
            }
        }
    }

    private fun showUniversePicker(universeIds: List<Long>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val universes = viewModel.universeNames(universeIds)
            if (!isAdded || universes.isEmpty()) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.duel_pick_universe)
                .setItems(universes.map { it.second }.toTypedArray()) { _, which ->
                    findNavController().navigateSafe(
                        R.id.homeFragment, R.id.duelAxisListFragment,
                        bundleOf("universeId" to universes[which].first)
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.menu_home_dashboard)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_global_search -> {
                    findNavController().navigateSafe(R.id.homeFragment, R.id.globalSearchFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupShortcuts() {
        binding.btnQuickAddCharacter.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.characterEditFragment)
        }
        binding.btnQuickAddEvent.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.timelineFragment)
        }
        binding.btnQuickSearch.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.globalSearchFragment)
        }
    }

    private fun setupBirthdaySection() {
        birthdayAdapter = BirthdayBannerAdapter(viewLifecycleOwner.lifecycleScope) { item ->
            findNavController().navigateSafe(
                R.id.homeFragment, R.id.characterDetailFragment,
                bundleOf("characterId" to item.character.id)
            )
        }
        binding.birthdayRecyclerView.adapter = birthdayAdapter
        viewModel.upcomingBirthdays.observe(viewLifecycleOwner) { items ->
            binding.birthdaySection.isVisible = items.isNotEmpty()
            binding.birthdayTitle.text =
                if (items.isEmpty()) getString(R.string.birthday_banner_title)
                else getString(R.string.birthday_banner_title_count, items.size)
            birthdayAdapter?.submitList(items)
        }
    }

    private fun setupRecentSection() {
        recentAdapter = RecentActivityAdapter(
            getTypeLabel = { entityType ->
                when (entityType) {
                    RecentActivity.TYPE_CHARACTER -> getString(R.string.recent_type_character)
                    RecentActivity.TYPE_NOVEL -> getString(R.string.recent_type_novel)
                    RecentActivity.TYPE_UNIVERSE -> getString(R.string.recent_type_universe)
                    else -> entityType
                }
            },
            onClick = { navigateToRecentItem(it) }
        )
        binding.recentRecyclerView.adapter = recentAdapter
        viewModel.recentActivities.observe(viewLifecycleOwner) { recents ->
            binding.recentSection.isVisible = recents.isNotEmpty()
            recentAdapter?.submitList(recents)
        }
    }

    private fun navigateToRecentItem(item: RecentActivity) {
        when (item.entityType) {
            RecentActivity.TYPE_UNIVERSE -> findNavController().navigateSafe(
                R.id.homeFragment, R.id.novelListFragment, bundleOf("universeId" to item.entityId)
            )
            RecentActivity.TYPE_NOVEL -> findNavController().navigateSafe(
                R.id.homeFragment, R.id.characterListFragment, bundleOf("novelId" to item.entityId)
            )
            RecentActivity.TYPE_CHARACTER -> findNavController().navigateSafe(
                R.id.homeFragment, R.id.characterDetailFragment, bundleOf("characterId" to item.entityId)
            )
        }
    }

    private fun setupToolCards() {
        // 보충 (N-2) — 전역 목적지라 인자 없이 id로 이동한다(imageManagerFragment와 동일).
        // 세계관·작품 필터는 자기 prefs(supplement_ui_state)에서 복원되므로 넘길 인자가 없다.
        binding.cardSupplement.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.supplementFragment)
        }
        binding.cardTimeline.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.timelineFragment)
        }
        binding.cardNameBank.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.nameBankFragment)
        }
        binding.cardImages.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.imageManagerFragment)
        }
        // 대결(B-104)은 도구 중 유일한 **반복 활동**이라 열 곳이 상황마다 다르다 — 판단은
        // `DuelEntry.targetOf`가 하고 여기서는 그 답대로 옮긴다.
        binding.cardDuel.setOnClickListener { openDuel() }
        binding.cardExcel.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dashboard_tool_excel)
                // 첫 항목이 '전체 백업'이다(설계 D1) — 백업의 직업과 데이터 추출의 직업은
                // 원하는 기본값이 정반대라 진입부터 갈라 둔다
                .setItems(
                    arrayOf(
                        getString(R.string.export_full_backup),
                        getString(R.string.export_choose_items),
                        getString(R.string.settings_import)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> excel.startFullBackup()
                        1 -> excel.showExportDialog()
                        else -> excel.showImportDialog()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupAssistantAlert() {
        binding.assistantAlertCard.setOnClickListener {
            findNavController().navigateSafe(
                R.id.homeFragment, R.id.analysisFragment, bundleOf("startTab" to 2)
            )
        }
        assistantViewModel.errorCount.observe(viewLifecycleOwner) { count ->
            binding.assistantAlertCard.isVisible = count > 0
            binding.assistantAlertTitle.text =
                getString(R.string.dashboard_assistant_alert, count)
            // 하단 탭 배지 — 분석 탭에 들어가기 전에도 오류 존재를 알 수 있게 한다.
            val bottomNav = activity?.findViewById<BottomNavigationView>(R.id.bottom_nav)
            if (bottomNav != null) {
                if (count > 0) {
                    bottomNav.getOrCreateBadge(R.id.analysisFragment).apply {
                        number = count
                        isVisible = true
                    }
                } else {
                    bottomNav.removeBadge(R.id.analysisFragment)
                }
            }
        }
        assistantViewModel.refresh()
    }

    // 첫 실행 구조 안내 (B-8) — 1회만 표시
    private fun showWelcomeIfFirstRun(savedInstanceState: Bundle?) {
        val ctx = requireContext()
        if (savedInstanceState == null &&
            !OnboardingPrefs.isShown(ctx, OnboardingPrefs.KEY_WELCOME_SHOWN)
        ) {
            OnboardingPrefs.markShown(ctx, OnboardingPrefs.KEY_WELCOME_SHOWN)
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.onboarding_welcome_title)
                .setMessage(R.string.onboarding_welcome_message)
                .setPositiveButton(R.string.onboarding_welcome_start, null)
                .show()
        }
    }

    /**
     * **그날 처음 앱을 열 때 한 번** 뜨는 생일 축하 창 (사용자 요청 2026.08.20).
     *
     * ## 세 관문을 순서대로 지난다 — 싼 것부터
     * 1. `savedInstanceState == null` — 회전·복구로 다시 뜨지 않는다(환영 창과 같은 관문).
     * 2. [BirthdayCelebrationPrefs.shouldShow] — 꺼 뒀거나 오늘 이미 띄웠으면 **여기서 끝난다.**
     *    DB를 읽기 전에 걸러내는 것이 요점이다: 꺼 둔 사용자에게는 질의가 아예 안 돈다.
     * 3. 오늘 생일인 캐릭터가 실제로 있는가 — 없으면 조용히 아무 일도 안 일어난다.
     *
     * ## 표시한 뒤에 표시를 남긴다
     * 관문 3을 지나 **쪽이 실제로 만들어졌을 때만** 오늘을 찍는다. 생일이 없는 날 찍어 두면
     * 아무 일도 없었는데 '오늘 봤음'이 되어, 그날 늦게 캐릭터의 생일을 적어도 창이 안 뜬다.
     *
     * ## 환영 창과 다투지 않는다
     * 첫 실행에는 환영 창이 먼저 뜨는데(호출 순서), 둘 다 창이라 겹쳐 뜬다. 그래도 축하
     * 창을 미루지 않는 것은 **오늘을 넘기면 그 축하가 사라지기** 때문이다 — 환영은 다음에
     * 이어서 볼 수 있지만 생일은 내년이다.
     */
    private fun showBirthdayCelebrationIfDue(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        val ctx = requireContext()
        val today = QuotePicker.todayStamp()
        if (!BirthdayCelebrationPrefs.shouldShow(ctx, today)) return

        viewLifecycleOwner.lifecycleScope.launch {
            val pages = viewModel.todayBirthdayPages(today)
            // 화면이 사라진 뒤 돌아온 결과로 창을 띄우지 않는다.
            if (!isAdded || _binding == null || pages.isEmpty()) return@launch
            BirthdayCelebrationPrefs.markShown(ctx, today)
            BirthdayCelebrationDialog.newInstance(pages)
                .show(childFragmentManager, BirthdayCelebrationDialog.TAG)
        }
    }

    override fun onDestroyView() {
        binding.birthdayRecyclerView.adapter = null
        binding.recentRecyclerView.adapter = null
        birthdayAdapter = null
        recentAdapter = null
        super.onDestroyView()
        _binding = null
    }

    /** 최근 활동 카드 어댑터 — item_recent_activity 재사용 */
    private class RecentActivityAdapter(
        private val getTypeLabel: (String) -> String,
        private val onClick: (RecentActivity) -> Unit
    ) : RecyclerView.Adapter<RecentActivityAdapter.ViewHolder>() {

        private var items: List<RecentActivity> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submitList(newItems: List<RecentActivity>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val typeLabel: TextView = view.findViewById(R.id.recentTypeLabel)
            val titleView: TextView = view.findViewById(R.id.recentTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recent_activity, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.typeLabel.text = getTypeLabel(item.entityType)
            holder.titleView.text = item.title
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
