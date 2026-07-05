package com.novelcharacter.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentHomeBinding
import com.novelcharacter.app.ui.assistant.AssistantFragment
import com.novelcharacter.app.ui.assistant.AssistantViewModel
import com.novelcharacter.app.ui.namebank.NameBankFragment
import com.novelcharacter.app.ui.timeline.TimelineFragment
import com.novelcharacter.app.ui.universe.UniverseListFragment

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // AssistantFragment(마지막 탭)와 공유 — 탭 배지가 어시스턴트 상태를 관찰한다.
    private val assistantViewModel: AssistantViewModel by viewModels()

    private val tabTitles by lazy {
        arrayOf(
            getString(R.string.tab_universes),
            getString(R.string.tab_timeline),
            getString(R.string.tab_name_bank),
            getString(R.string.assistant_tab)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.viewPager.adapter = HomePagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 2

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        // 어시스턴트 탭 배지 — 현재 표시 중인 정합성 오류 수를 능동 신호로 띄운다.
        assistantViewModel.errorCount.observe(viewLifecycleOwner) { count ->
            val tab = binding.tabLayout.getTabAt(ASSISTANT_TAB_INDEX) ?: return@observe
            if (count > 0) {
                tab.orCreateBadge.apply {
                    number = count
                    isVisible = true
                }
            } else {
                tab.removeBadge()
            }
        }
        // 탭을 열기 전에도 배지가 뜨도록 초기 1회 로드.
        assistantViewModel.refresh()

        // 글로벌 검색에서 사건 클릭 시 연표 탭으로 자동 전환
        val prefs = requireContext().getSharedPreferences("timeline_ui_state", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("pending_navigate", false)) {
            binding.viewPager.setCurrentItem(1, false) // 연표 탭 (인덱스 1)
            // pending_navigate 플래그 소비는 TimelineFragment.onResume()에서 처리
        }

        // 첫 실행 구조 안내 (B-8) — 1회만 표시
        val ctx = requireContext()
        if (savedInstanceState == null &&
            !com.novelcharacter.app.util.OnboardingPrefs.isShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_WELCOME_SHOWN)
        ) {
            com.novelcharacter.app.util.OnboardingPrefs.markShown(ctx, com.novelcharacter.app.util.OnboardingPrefs.KEY_WELCOME_SHOWN)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.onboarding_welcome_title)
                .setMessage(R.string.onboarding_welcome_message)
                .setPositiveButton(R.string.onboarding_welcome_start, null)
                .show()
        }
    }

    override fun onDestroyView() {
        binding.viewPager.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private inner class HomePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> UniverseListFragment()
                1 -> TimelineFragment()
                2 -> NameBankFragment()
                3 -> AssistantFragment()
                else -> throw IllegalStateException("Invalid position $position")
            }
        }
    }

    companion object {
        private const val ASSISTANT_TAB_INDEX = 3
    }
}
