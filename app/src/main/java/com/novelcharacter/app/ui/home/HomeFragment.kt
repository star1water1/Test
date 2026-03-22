package com.novelcharacter.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.FragmentHomeBinding
import com.novelcharacter.app.ui.namebank.NameBankFragment
import com.novelcharacter.app.ui.timeline.TimelineFragment
import com.novelcharacter.app.ui.universe.UniverseListFragment

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val tabTitles by lazy {
        arrayOf(
            getString(R.string.tab_universes),
            getString(R.string.tab_timeline),
            getString(R.string.tab_name_bank)
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
    }

    override fun onDestroyView() {
        binding.viewPager.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private inner class HomePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> UniverseListFragment()
                1 -> TimelineFragment()
                2 -> NameBankFragment()
                else -> throw IllegalStateException("Invalid position $position")
            }
        }
    }
}
