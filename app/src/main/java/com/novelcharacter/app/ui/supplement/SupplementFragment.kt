package com.novelcharacter.app.ui.supplement

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.databinding.FragmentSupplementBinding

/**
 * 편집 중 이탈 가드 — 랜덤 보충 탭이 인라인 편집 중일 때 탭 전환·필터 변경 등
 * 화면 상태를 바꾸는 조작을 가로채 미저장 변경 확인을 거치게 한다 (변수 제어: 무음 유실 방지).
 */
interface RandomEditGuard {
    /** 현재 이탈을 막아야 하는 상태인가 (편집 모드 + 미저장 변경) */
    fun isBlocking(): Boolean

    /** 이탈 확인 절차(저장/버리기/계속 편집)를 거친 뒤, 진행이 허용되면 [onProceed]를 호출한다 */
    fun requestLeave(onProceed: () -> Unit)
}

/**
 * 보충 화면 호스트 — 상단 공유 필터(세계관/작품 스피너) + 내부 탭 2개(랜덤 보충 / 완성도 검사).
 * 기존 완성도 검사 본문은 AuditSupplementFragment로, 신규 랜덤 카드는 RandomSupplementFragment로 분리.
 */
class SupplementFragment : Fragment() {

    private var _binding: FragmentSupplementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SupplementViewModel by viewModels()

    private var criteria: SupplementCriteria = SupplementCriteria()

    // 스피너 상태 관리
    private var universes: List<Universe> = emptyList()
    private var filteredNovels: List<Novel> = emptyList()
    private var suppressSpinnerEvents = false
    private var lastUniversePos = 0
    private var lastNovelPos = 0

    /** 랜덤 탭이 인라인 편집 중일 때 등록하는 이탈 가드 (onDestroyView에서 해제) */
    var editGuard: RandomEditGuard? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSupplementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        criteria = SupplementCriteria.load(requireContext())

        setupPager()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // 최초 로드 + 보충 편집 후 돌아왔을 때 데이터 갱신
        criteria = SupplementCriteria.load(requireContext())
        viewModel.loadData(criteria)
    }

    /** 랜덤 탭 편집 모드 진입/이탈 시 좌우 스와이프 잠금 (실수로 탭이 넘어가는 것 방지) */
    fun setSwipeLocked(locked: Boolean) {
        _binding?.viewPager?.isUserInputEnabled = !locked
    }

    /** 세계관·작품 필터 해제 — 랜덤 탭의 빈 상태 교정 경로(변수 제어)에서 호출 */
    fun clearFilters() {
        viewModel.setNovelFilter(null)
        applyUniverseSelection(0, universes)
    }

    private fun setupPager() {
        binding.viewPager.adapter = SupplementPagerAdapter(this)
        // 두 탭을 모두 살려둔다 — 랜덤 탭의 편집 상태가 탭 전환으로 파괴되지 않도록
        binding.viewPager.offscreenPageLimit = 1

        val titles = listOf(
            getString(R.string.supplement_tab_random),
            getString(R.string.supplement_tab_audit)
        )
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()

        interceptTabClicks()
    }

    /** 탭 클릭을 가로채 편집 중이면 이탈 가드를 거친다 */
    private fun interceptTabClicks() {
        for (i in 0 until binding.tabLayout.tabCount) {
            val tabView = binding.tabLayout.getTabAt(i)?.view ?: continue
            tabView.setOnClickListener {
                val b = _binding ?: return@setOnClickListener
                if (b.viewPager.currentItem == i) return@setOnClickListener
                val guard = editGuard
                if (guard != null && guard.isBlocking()) {
                    guard.requestLeave { _binding?.viewPager?.setCurrentItem(i, true) }
                } else {
                    b.viewPager.setCurrentItem(i, true)
                }
            }
        }
    }

    private fun observeViewModel() {
        viewModel.universeList.observe(viewLifecycleOwner) { univs ->
            universes = univs
            setupUniverseSpinner(univs)
        }

        viewModel.novelList.observe(viewLifecycleOwner) { novels ->
            // 초기 로드 시 작품 스피너도 설정
            val uId = viewModel.selectedUniverseId
            filteredNovels = if (uId != null) novels.filter { it.universeId == uId } else novels
            setupNovelSpinner(filteredNovels)
        }
    }

    private fun setupUniverseSpinner(univs: List<Universe>) {
        val items = mutableListOf(getString(R.string.supplement_filter_all_universes))
        items.addAll(univs.map { it.name })

        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        suppressSpinnerEvents = true
        binding.spinnerUniverse.adapter = spinnerAdapter

        // 저장된 세계관 필터 복원
        val savedUniverseId = viewModel.selectedUniverseId
        val restoredPos = if (savedUniverseId != null) {
            val idx = univs.indexOfFirst { it.id == savedUniverseId }
            if (idx >= 0) idx + 1 else 0
        } else 0
        binding.spinnerUniverse.setSelection(restoredPos)
        lastUniversePos = restoredPos
        suppressSpinnerEvents = false

        // 작품 스피너는 novelList 옵저버에서 복원됨 (이 시점에 novelList 미로드)

        binding.spinnerUniverse.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerEvents) return
                val guard = editGuard
                if (guard != null && guard.isBlocking() && position != lastUniversePos) {
                    // 편집 중 — 선택을 되돌리고 가드 통과 시 다시 적용
                    suppressSpinnerEvents = true
                    binding.spinnerUniverse.setSelection(lastUniversePos)
                    suppressSpinnerEvents = false
                    guard.requestLeave { applyUniverseSelection(position, univs) }
                    return
                }
                applyUniverseSelection(position, univs)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applyUniverseSelection(position: Int, univs: List<Universe>) {
        val b = _binding ?: return
        lastUniversePos = position
        if (b.spinnerUniverse.selectedItemPosition != position) {
            suppressSpinnerEvents = true
            b.spinnerUniverse.setSelection(position)
            suppressSpinnerEvents = false
        }
        val selectedUniverse = if (position == 0) null else univs[position - 1]
        viewModel.setUniverseFilter(selectedUniverse?.id)

        // 작품 스피너 갱신
        filteredNovels = if (selectedUniverse != null) {
            viewModel.getNovelsForUniverse(selectedUniverse.id)
        } else {
            viewModel.novelList.value ?: emptyList()
        }
        setupNovelSpinner(filteredNovels)
    }

    private fun setupNovelSpinner(novels: List<Novel>) {
        val items = mutableListOf(getString(R.string.supplement_filter_all_novels))
        items.addAll(novels.map { it.title })

        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        suppressSpinnerEvents = true
        binding.spinnerNovel.adapter = spinnerAdapter

        // 저장된 작품 필터 복원
        val savedNovelId = viewModel.selectedNovelId
        val restoredNovelPos = if (savedNovelId != null) {
            val idx = novels.indexOfFirst { it.id == savedNovelId }
            if (idx >= 0) idx + 1 else 0
        } else 0
        binding.spinnerNovel.setSelection(restoredNovelPos)
        lastNovelPos = restoredNovelPos
        suppressSpinnerEvents = false

        binding.spinnerNovel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerEvents) return
                val guard = editGuard
                if (guard != null && guard.isBlocking() && position != lastNovelPos) {
                    suppressSpinnerEvents = true
                    binding.spinnerNovel.setSelection(lastNovelPos)
                    suppressSpinnerEvents = false
                    guard.requestLeave { applyNovelSelection(position, novels) }
                    return
                }
                applyNovelSelection(position, novels)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun applyNovelSelection(position: Int, novels: List<Novel>) {
        val b = _binding ?: return
        lastNovelPos = position
        if (b.spinnerNovel.selectedItemPosition != position) {
            suppressSpinnerEvents = true
            b.spinnerNovel.setSelection(position)
            suppressSpinnerEvents = false
        }
        val selectedNovel = if (position == 0) null else novels[position - 1]
        viewModel.setNovelFilter(selectedNovel?.id)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class SupplementPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> RandomSupplementFragment()
            else -> AuditSupplementFragment()
        }
    }
}
