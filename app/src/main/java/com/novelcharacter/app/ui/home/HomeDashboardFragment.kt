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
import com.novelcharacter.app.ui.adapter.BirthdayBannerAdapter
import com.novelcharacter.app.ui.assistant.AssistantViewModel
import com.novelcharacter.app.util.BirthdayHelper
import com.novelcharacter.app.util.OnboardingPrefs
import com.novelcharacter.app.util.navigateSafe
import kotlinx.coroutines.Dispatchers

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
}

class HomeDashboardFragment : Fragment() {

    private var _binding: FragmentHomeDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeDashboardViewModel by viewModels()

    // 알림 카드·하단 탭 배지용 — 분석 탭의 인스턴스와 별개 스코프(각자 스냅샷 로드)
    private val assistantViewModel: AssistantViewModel by viewModels()

    private var birthdayAdapter: BirthdayBannerAdapter? = null
    private var recentAdapter: RecentActivityAdapter? = null

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
        binding.cardTimeline.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.timelineFragment)
        }
        binding.cardNameBank.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.nameBankFragment)
        }
        binding.cardImages.setOnClickListener {
            findNavController().navigateSafe(R.id.homeFragment, R.id.imageManagerFragment)
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
