package com.novelcharacter.app.ui.analysis

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentAnalysisHomeBinding
import com.novelcharacter.app.ui.assistant.AssistantFragment
import com.novelcharacter.app.ui.assistant.AssistantViewModel
import com.novelcharacter.app.ui.graph.RelationshipGraphFragment
import com.novelcharacter.app.ui.stats.StatsMainFragment

/**
 * 분석 허브 — 같은 데이터를 보는 인사이트 화면 3개(통계/관계도/어시스턴트)를 내부 탭으로 담는다.
 * 페이저 스와이프는 비활성(탭 클릭 전환만): 관계도의 팬/줌, 통계의 가로 범례 스크롤,
 * 차트 회전 드래그가 모두 가로 제스처라 스와이프와 충돌한다.
 * 마지막으로 본 탭을 기억해 재방문 시 바로 복원한다(예: 관계도 사용자는 항상 탭 1번에 도달).
 */
class AnalysisHomeFragment : Fragment() {

    private var _binding: FragmentAnalysisHomeBinding? = null
    private val binding get() = _binding!!

    // AssistantFragment(내부 탭)와 부모 프래그먼트 스코프로 공유 — 탭 배지가 상태를 관찰한다.
    private val assistantViewModel: AssistantViewModel by viewModels()

    private val tabTitles by lazy {
        arrayOf(
            getString(R.string.tab_statistics),
            getString(R.string.tab_relationship_graph),
            getString(R.string.assistant_tab)
        )
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            prefs().edit().putInt(KEY_LAST_TAB, position).apply()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalysisHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.viewPager.adapter = AnalysisPagerAdapter(this)
        binding.viewPager.isUserInputEnabled = false

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        // startTab 인자: 0..2 = 명시 진입(대시보드 알림 카드 등), -1 = 마지막 탭 복원
        if (savedInstanceState == null) {
            val startTab = arguments?.getInt("startTab", -1) ?: -1
            val initial = if (startTab in 0 until TAB_COUNT) startTab
                          else prefs().getInt(KEY_LAST_TAB, 0).coerceIn(0, TAB_COUNT - 1)
            binding.viewPager.setCurrentItem(initial, false)
        }
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback)

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
    }

    private fun prefs() =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onDestroyView() {
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        binding.viewPager.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private inner class AnalysisPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = TAB_COUNT

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> StatsMainFragment()
            1 -> RelationshipGraphFragment()
            2 -> AssistantFragment()
            else -> throw IllegalStateException("Invalid position $position")
        }
    }

    companion object {
        private const val TAB_COUNT = 3
        private const val ASSISTANT_TAB_INDEX = 2
        private const val PREFS_NAME = "analysis_ui_state"
        private const val KEY_LAST_TAB = "last_tab"
    }
}
