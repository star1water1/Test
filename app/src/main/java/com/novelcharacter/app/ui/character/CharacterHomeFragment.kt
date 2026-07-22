package com.novelcharacter.app.ui.character

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentCharacterHomeBinding
import com.novelcharacter.app.ui.supplement.RandomEditGuard
import com.novelcharacter.app.ui.supplement.SupplementFragment

/**
 * 캐릭터 탭 호스트 — 홈 탭과 같은 구조(TabLayout + ViewPager2)로 내부 탭 2개를 담는다:
 * 목록([CharacterListFragment]) / 보충([SupplementFragment]).
 * 보충은 설정에서 버튼으로 진입하던 화면을 캐릭터 탭의 내부 탭으로 옮긴 것.
 */
class CharacterHomeFragment : Fragment() {

    private var _binding: FragmentCharacterHomeBinding? = null
    private val binding get() = _binding!!

    // 가드를 통과해 확정된 탭 위치 — 가드 우회 선택(접근성 클릭 등)의 사후 복귀 기준
    private var committedTabPos = 0

    private val tabTitles by lazy {
        arrayOf(
            getString(R.string.character_tab_list),
            getString(R.string.tab_supplement)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCharacterHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.viewPager.adapter = CharacterPagerAdapter(this)
        // 두 탭을 모두 살려둔다 — 보충(랜덤) 탭의 인라인 편집 상태가 탭 전환으로 파괴되지 않도록
        binding.viewPager.offscreenPageLimit = 1

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
        }.attach()

        committedTabPos = binding.viewPager.currentItem
        setupTabGuard()
    }

    /** 보충(랜덤) 탭 인라인 편집 중 좌우 스와이프 잠금 — [SupplementFragment]가 내부 페이저와 함께 잠근다 */
    fun setSwipeLocked(locked: Boolean) {
        _binding?.viewPager?.isUserInputEnabled = !locked
    }

    /** 보충 탭이 등록해 둔 편집 이탈 가드 — 미생성/미등록이면 null */
    private fun supplementGuard(): RandomEditGuard? =
        childFragmentManager.fragments.filterIsInstance<SupplementFragment>().firstOrNull()?.editGuard

    /**
     * 탭 전환을 보충 탭의 편집 이탈 가드에 태운다 ([SupplementFragment.setupTabGuard]와 같은 패턴).
     * TabView.performClick()은 커스텀 리스너 실행 후에도 무조건 tab.select()를 호출하므로,
     * 터치 단계에서 제스처를 소비해 선택 자체를 차단하고 가드 통과 시에만 전환한다.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTabGuard() {
        for (i in 0 until binding.tabLayout.tabCount) {
            val tabView = binding.tabLayout.getTabAt(i)?.view ?: continue
            tabView.setOnTouchListener { _, event ->
                val b = _binding ?: return@setOnTouchListener false
                val guard = supplementGuard()
                if (guard == null || !guard.isBlocking() || b.viewPager.currentItem == i) {
                    return@setOnTouchListener false
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    guard.requestLeave { _binding?.viewPager?.setCurrentItem(i, true) }
                }
                true
            }
        }
        // 터치를 거치지 않는 선택(접근성 클릭 등)은 사후 복귀로 방어 —
        // 확정 탭으로 되돌린 뒤 가드 통과 시에만 목표 탭으로 이동한다
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val target = tab.position
                val guard = supplementGuard()
                if (guard != null && guard.isBlocking() && target != committedTabPos) {
                    val b = _binding ?: return
                    b.tabLayout.getTabAt(committedTabPos)?.select()
                    guard.requestLeave { _binding?.viewPager?.setCurrentItem(target, true) }
                } else {
                    committedTabPos = target
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    override fun onDestroyView() {
        binding.viewPager.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private inner class CharacterPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> CharacterListFragment()
            else -> SupplementFragment()
        }
    }
}
