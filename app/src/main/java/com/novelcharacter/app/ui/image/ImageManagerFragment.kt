package com.novelcharacter.app.ui.image

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetImageDetailBinding
import com.novelcharacter.app.databinding.FragmentImageManagerBinding
import com.novelcharacter.app.util.ImageImportHelper
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.StorageAnalyzer
import com.novelcharacter.app.util.navigateSafe
import com.novelcharacter.app.util.reportAndNotify
import com.novelcharacter.app.util.logOperation
import com.novelcharacter.app.util.notifyWithAction
import com.novelcharacter.app.util.notifySuccess
import com.novelcharacter.app.util.notifyError
import kotlinx.coroutines.launch

/**
 * 이미지 관리 탭 — 앱 내 모든 이미지(캐릭터·작품·세계관)를 그리드로 조회/필터/정렬하고,
 * 상세·전체화면 보기·삭제(고아 + 참조본 안전삭제)·고아 일괄 정리·용량 요약을 제공한다.
 *
 * PR-3a: 다중선택 일괄(삭제/재압축) + 기존 이미지 재압축(정확한 전/후 크기 미리보기 + 스킵 사유 고지 + 확인).
 */
class ImageManagerFragment : Fragment() {

    companion object {
        /**
         * 진입 인자 — 이 탭을 특정 링크 상태로 걸어 연다([ImageFilterHelper.LinkFilter] 이름).
         * 어시스턴트의 '흩어진 묶음' 카드가 쓴다. 값이 이상하면 무시한다(필터는 사용자가
         * 언제든 바꿀 수 있으므로 잘못된 인자로 실패할 이유가 없다).
         */
        const val ARG_LINK_FILTER = "linkFilter"
    }

    private var _binding: FragmentImageManagerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImageManagerViewModel by viewModels()
    private val gson = Gson()

    // 탭 직접 임포트 — 시스템 픽커 다중 선택 → img_ 라이브러리(미배정) 편입
    private val imagePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        viewModel.importImages(uris) { result ->
            if (!isAdded || _binding == null) return@importImages
            if (result.failed > 0) {
                reportAndNotify(OpResult.failure(
                    OpResult.CAT_MAINTENANCE,
                    getString(R.string.image_manager_import_failed, result.imported, result.failed)
                ))
            } else {
                reportAndNotify(OpResult.success(
                    OpResult.CAT_MAINTENANCE,
                    getString(R.string.image_manager_imported, result.imported)
                ))
            }
        }
    }

    private var selectionMode = false
    private val selectedPaths = LinkedHashSet<String>()
    private var currentList: List<ImageManagerViewModel.ManagedImage> = emptyList()
    private var searchJob: kotlinx.coroutines.Job? = null

    private lateinit var adapter: ImageManagerAdapter
    // 갤러리뷰 모드 — 그리드와 같은 목록을 페이저로 소비
    private lateinit var galleryAdapter: GalleryPagerAdapter
    private var galleryBackCallback: androidx.activity.OnBackPressedCallback? = null
    private var galleryPageCallback: androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 푸시 목적지(대시보드 진입) — 선택 모드 종료 등 기존 뒤로가기 콜백을 존중하도록 디스패처 경유
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        adapter = ImageManagerAdapter(
            viewLifecycleOwner.lifecycleScope,
            onClick = { showDetail(it) },
            onToggleSelect = { toggleSelect(it) },
            onLongPress = { enterSelection(it) }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter

        galleryAdapter = GalleryPagerAdapter(viewLifecycleOwner.lifecycleScope)
        // 어댑터는 갤러리 모드에서만 장착 — 그리드 모드에서 페이저가 고해상 비트맵을 붙들지 않게
        // (GONE 뷰는 layout되지 않아 submitList(empty)로는 회수가 실행되지 않는다)
        if (viewModel.viewMode == ImageManagerViewModel.ViewMode.GALLERY) {
            binding.galleryPager.adapter = galleryAdapter
        }
        galleryPageCallback = object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.galleryPosition = position
                // path 기록은 콜백 position의 좌표계인 어댑터 리스트 기준 —
                // 프래그먼트 currentList는 diff 커밋 전일 수 있어 어긋난다
                galleryAdapter.currentList.getOrNull(position)?.let { viewModel.galleryPath = it.path }
                updateGalleryOverlay()
            }
        }
        binding.galleryPager.registerOnPageChangeCallback(galleryPageCallback!!)
        binding.viewModeButton.setOnClickListener { toggleViewMode() }
        binding.galleryDetailButton.setOnClickListener {
            currentList.getOrNull(binding.galleryPager.currentItem)?.let { showDetail(it) }
        }
        binding.galleryTagButton.setOnClickListener {
            currentList.getOrNull(binding.galleryPager.currentItem)?.let { openTagEdit(it) }
        }
        // 갤러리 모드의 뒤로가기는 화면 이탈이 아니라 그리드 복귀
        galleryBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                switchToGrid()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, galleryBackCallback!!)

        // 진입 인자로 들어온 링크 필터를 **복원보다 먼저** 반영한다 — 저장된 필터를 덮어야
        // 카드가 약속한 화면이 나온다. 인자는 한 번 쓰고 지운다(회전 때 다시 걸리면, 사용자가
        // 그사이 바꾼 필터를 화면 재생성이 되돌려 놓는 꼴이 된다).
        arguments?.getString(ARG_LINK_FILTER)?.let { raw ->
            runCatching { com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.valueOf(raw) }
                .getOrNull()?.let { viewModel.criteria = viewModel.criteria.copy(link = it) }
            arguments?.remove(ARG_LINK_FILTER)
        }

        // 상태 복원(D10: SavedStateHandle 영속) — 리스너 등록 전에 UI를 현재 criteria로 맞춘다.
        restoreFilterUi()

        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val base = when (checkedIds.firstOrNull()) {
                R.id.chipCharacter -> com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.CHARACTER
                R.id.chipNovel -> com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.NOVEL
                R.id.chipUniverse -> com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.UNIVERSE
                R.id.chipUnassigned -> com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.UNASSIGNED
                R.id.chipOrphan -> com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ORPHAN
                R.id.chipTrash -> com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.TRASH
                else -> com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ALL
            }
            viewModel.criteria = viewModel.criteria.copy(base = base)
            applyView()
        }

        // 링크 상태는 소유·상태와 직교하는 별도 축이다 — 두 칩 그룹이 AND로 조합된다.
        binding.linkFilterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val link = when (checkedIds.firstOrNull()) {
                R.id.chipLinked -> com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.LINKED
                R.id.chipUnlinked -> com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.UNLINKED
                R.id.chipLinkAuto -> com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.AUTO
                else -> com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.ANY
            }
            viewModel.criteria = viewModel.criteria.copy(link = link)
            applyView()
        }

        // 검색 — 300ms 디바운스(캐릭터 목록 검색 패턴)
        binding.searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(300)
                    viewModel.criteria = viewModel.criteria.copy(query = s?.toString() ?: "")
                    applyView()
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.tagFilterButton.setOnClickListener { openTagFilterSheet() }
        binding.importButton.setOnClickListener { imagePickerLauncher.launch("image/*") }
        binding.sortButton.setOnClickListener { showSortMenu() }
        binding.optionsButton.setOnClickListener { showOptionsMenu() }
        binding.selectButton.setOnClickListener { if (selectionMode) exitSelection() else enterSelection(null) }
        binding.selectAllButton.setOnClickListener { selectAll() }
        binding.actionsButton.setOnClickListener { openBatchOperations() }
        binding.organizeFolderBanner.setOnClickListener { startOrganizeFolderImport() }

        viewModel.loading.observe(viewLifecycleOwner) {
            binding.progressBar.visibility = if (it) View.VISIBLE else View.GONE
        }
        viewModel.summary.observe(viewLifecycleOwner) { s ->
            binding.summaryText.text = getString(
                R.string.image_manager_summary,
                s.totalCount, StorageAnalyzer.formatBytes(s.totalBytes), s.referencedCount, s.unassignedCount, s.orphanCount
            )
        }
        viewModel.images.observe(viewLifecycleOwner) { applyView() }

        updateSelectionUi()
        viewModel.load()
    }

    override fun onResume() {
        super.onResume()
        // 편집화면에서 이미지를 추가/삭제하고 돌아왔을 수 있으니 갱신.
        viewModel.load()
        refreshOrganizeFolderBanner()
    }

    /** 상태 복원 — 칩·검색어·태그필터 버튼 라벨을 VM criteria(SavedStateHandle)에 맞춘다. */
    private fun restoreFilterUi() {
        val c = viewModel.criteria
        val chipId = when (c.base) {
            com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.CHARACTER -> R.id.chipCharacter
            com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.NOVEL -> R.id.chipNovel
            com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.UNIVERSE -> R.id.chipUniverse
            com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.UNASSIGNED -> R.id.chipUnassigned
            com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ORPHAN -> R.id.chipOrphan
            com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.TRASH -> R.id.chipTrash
            com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ALL -> R.id.chipAll
        }
        binding.filterChips.check(chipId)
        binding.linkFilterChips.check(when (c.link) {
            com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.LINKED -> R.id.chipLinked
            com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.UNLINKED -> R.id.chipUnlinked
            com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.AUTO -> R.id.chipLinkAuto
            com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.ANY -> R.id.chipLinkAny
        })
        if (c.query.isNotBlank()) binding.searchEdit.setText(c.query)
        updateTagFilterLabel()
        applyViewMode()
    }

    // ---------- 갤러리뷰 모드 ----------

    private fun applyViewMode() {
        val gallery = viewModel.viewMode == ImageManagerViewModel.ViewMode.GALLERY
        binding.recyclerView.visibility = if (gallery) View.GONE else View.VISIBLE
        binding.galleryPager.visibility = if (gallery) View.VISIBLE else View.GONE
        binding.galleryOverlay.visibility = if (gallery) View.VISIBLE else View.GONE
        binding.viewModeButton.text = getString(
            if (gallery) R.string.image_manager_view_grid else R.string.image_manager_view_gallery
        )
        galleryBackCallback?.isEnabled = gallery
        if (gallery) updateGalleryOverlay()
    }

    private fun toggleViewMode() {
        if (viewModel.viewMode == ImageManagerViewModel.ViewMode.GALLERY) switchToGrid() else switchToGallery()
    }

    /** 갤러리 어댑터 장착 — 그리드 모드에서 분리해 둔 것을 재부착 (setAdapter의 위치 리셋은 sync가 복원) */
    private fun attachGalleryAdapter() {
        if (binding.galleryPager.adapter == null) binding.galleryPager.adapter = galleryAdapter
    }

    private fun switchToGallery() {
        // 선택 모드는 그리드 전용 — 집합 조작과 한 장 보기는 목적이 상충 (오조작 방지)
        if (selectionMode) exitSelection()
        val gridPos = (binding.recyclerView.layoutManager as? GridLayoutManager)
            ?.findFirstVisibleItemPosition()
            ?.takeIf { it != androidx.recyclerview.widget.RecyclerView.NO_POSITION }
        viewModel.viewMode = ImageManagerViewModel.ViewMode.GALLERY
        if (gridPos != null) {
            viewModel.galleryPosition = gridPos
            viewModel.galleryPath = currentList.getOrNull(gridPos)?.path
        }
        attachGalleryAdapter()
        applyViewMode()
        galleryAdapter.submitList(currentList) { syncGalleryPager() }
    }

    private fun switchToGrid() {
        val pos = binding.galleryPager.currentItem
        // 어댑터 분리로 페이지 홀더를 즉시 재활용 — 디코드 Job 취소·비트맵 해제
        // (onDestroyView와 동일 관용구. GONE 전환만으로는 layout이 없어 회수가 안 된다)
        binding.galleryPager.adapter = null
        viewModel.viewMode = ImageManagerViewModel.ViewMode.GRID
        applyViewMode()
        if (pos in currentList.indices) binding.recyclerView.scrollToPosition(pos)
    }

    /**
     * 페이저 위치를 목록 상태와 동기화 — 보던 항목의 path를 우선 추적하고(필터·정렬·재압축에도
     * 같은 이미지 유지), path가 목록에 없을 때만 인덱스 클램프로 폴백한다.
     */
    private fun syncGalleryPager() {
        if (_binding == null || viewModel.viewMode != ImageManagerViewModel.ViewMode.GALLERY) return
        val list = galleryAdapter.currentList
        if (list.isEmpty()) {
            // 빈 목록에서는 galleryPath를 지우지 않는다 — 프로세스 재생성 직후 sticky 빈
            // 리스트가 먼저 도착해도 실 목록이 오면 path로 복원돼야 한다
            updateGalleryOverlay()
            return
        }
        val byPath = viewModel.galleryPath?.let { p -> list.indexOfFirst { it.path == p } } ?: -1
        val target = if (byPath >= 0) byPath else viewModel.galleryPosition.coerceIn(0, list.size - 1)
        if (byPath < 0) {
            // 항목 소실(삭제 등) — 폴백 위치의 항목을 새 추적 대상으로
            viewModel.galleryPath = list.getOrNull(target)?.path
        }
        if (binding.galleryPager.currentItem != target) {
            binding.galleryPager.setCurrentItem(target, false)
        }
        updateGalleryOverlay()
    }

    /** 하단 오버레이 갱신 — 현재 페이지의 인덱스·파일명·소유자·태그 (편집 진입 버튼 포함) */
    private fun updateGalleryOverlay() {
        if (_binding == null || viewModel.viewMode != ImageManagerViewModel.ViewMode.GALLERY) return
        val item = currentList.getOrNull(binding.galleryPager.currentItem)
        if (item == null) {
            binding.galleryIndexText.text = ""
            binding.galleryOwnerText.text = ""
            binding.galleryTagText.visibility = View.GONE
            binding.galleryDetailButton.isEnabled = false
            binding.galleryTagButton.isEnabled = false
            return
        }
        binding.galleryDetailButton.isEnabled = true
        binding.galleryTagButton.isEnabled = true
        binding.galleryIndexText.text = getString(
            R.string.image_manager_gallery_index,
            binding.galleryPager.currentItem + 1, currentList.size, item.path.substringAfterLast('/')
        )
        binding.galleryOwnerText.text = ownerLabel(item)
        val tags = item.meta?.tags.orEmpty()
        if (tags.isEmpty()) {
            binding.galleryTagText.visibility = View.GONE
        } else {
            binding.galleryTagText.visibility = View.VISIBLE
            binding.galleryTagText.text =
                getString(R.string.image_manager_gallery_tags, tags.joinToString(" · "))
        }
    }

    /**
     * 태그 필터 버튼 상태 갱신 — 라벨 개수뿐 아니라 체크 아이콘·강조색·배경까지 함께 바꿔
     * 필터가 적용 중인지 버튼만 보고도 판단할 수 있게 한다(토글 시각화).
     */
    private fun updateTagFilterLabel() {
        val n = viewModel.criteria.tags.size
        val active = n > 0
        val button = binding.tagFilterButton
        button.text =
            if (!active) getString(R.string.image_manager_tag_filter)
            else getString(R.string.image_manager_tag_filter_count, n)
        if (active) {
            button.setIconResource(R.drawable.ic_check)
            val primary = MaterialColors.getColor(button, com.google.android.material.R.attr.colorPrimary)
            button.setTextColor(primary)
            button.iconTint = ColorStateList.valueOf(primary)
            button.strokeColor = ColorStateList.valueOf(primary)
            button.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(button, com.google.android.material.R.attr.colorSecondaryContainer)
            )
        } else {
            button.icon = null
            val ctx = requireContext()
            button.setTextColor(androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.button_outlined_text))
            button.iconTint = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.button_outlined_text)
            button.strokeColor = androidx.core.content.ContextCompat.getColorStateList(ctx, R.color.button_outlined_stroke)
            button.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
    }

    private fun openTagFilterSheet() {
        val sheet = ImageTagFilterBottomSheet()
        sheet.currentTags = viewModel.criteria.tags
        sheet.loadAllTags = { viewModel.getAllImageTags() }
        sheet.onApply = { tags ->
            viewModel.criteria = viewModel.criteria.copy(tags = tags)
            if (_binding != null) { updateTagFilterLabel(); applyView() }
        }
        sheet.show(childFragmentManager, ImageTagFilterBottomSheet.TAG)
    }

    /** 현재 필터·검색·정렬을 적용해 어댑터에 반영(매칭은 ImageFilterHelper 단일 소스). */
    private fun applyView() {
        val all = viewModel.images.value ?: emptyList()
        val filtered = com.novelcharacter.app.util.ImageFilterHelper.apply(all, viewModel.criteria) { item ->
            com.novelcharacter.app.util.ImageFilterHelper.Facts(
                fileName = item.path.substringAfterLast('/'),
                ownerNames = item.owners.map { it.name },
                tags = item.meta?.tags ?: emptyList(),
                ownerKinds = item.owners.mapTo(HashSet()) {
                    when (it.type) {
                        ImageManagerViewModel.OwnerType.CHARACTER -> com.novelcharacter.app.util.ImageFilterHelper.OwnerKind.CHARACTER
                        ImageManagerViewModel.OwnerType.NOVEL -> com.novelcharacter.app.util.ImageFilterHelper.OwnerKind.NOVEL
                        ImageManagerViewModel.OwnerType.UNIVERSE -> com.novelcharacter.app.util.ImageFilterHelper.OwnerKind.UNIVERSE
                    }
                },
                status = when (item.status) {
                    ImageManagerViewModel.Status.REFERENCED -> com.novelcharacter.app.util.ImageFilterHelper.StatusKind.REFERENCED
                    ImageManagerViewModel.Status.ORPHAN -> com.novelcharacter.app.util.ImageFilterHelper.StatusKind.ORPHAN
                    ImageManagerViewModel.Status.TRASH_HELD -> com.novelcharacter.app.util.ImageFilterHelper.StatusKind.TRASH
                    ImageManagerViewModel.Status.UNASSIGNED -> com.novelcharacter.app.util.ImageFilterHelper.StatusKind.UNASSIGNED
                },
                linkGroupId = item.meta?.linkGroupId
            )
        }
        val sorted = when (viewModel.sort) {
            ImageManagerViewModel.Sort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
            ImageManagerViewModel.Sort.NAME -> filtered.sortedBy { it.path.substringAfterLast('/') }
            ImageManagerViewModel.Sort.DATE -> filtered.sortedByDescending { it.lastModified }
        }
        currentList = sorted
        // 선택은 현재 뷰(필터·정렬 적용) 기준으로 유지 — 필터 전환 시 화면 밖(안 보이는) 선택은 자동 해제한다.
        // 일괄 삭제/재압축이 사용자가 보지 않는 항목에 작용하지 않도록(변수 제어). 삭제로 사라진 항목도 함께 정리됨.
        val visiblePaths = sorted.mapTo(HashSet()) { it.path }
        if (selectedPaths.retainAll(visiblePaths)) updateSelectionUi()
        adapter.submitList(sorted)
        // 갤러리 페이저는 같은 목록을 소비하되 갤러리 모드에서만 공급 — 그리드 모드의
        // 이중 diff 비용 제거 + 분리된 어댑터에 헛공급 방지. 커밋 후 위치는 path 우선 동기화.
        if (viewModel.viewMode == ImageManagerViewModel.ViewMode.GALLERY) {
            attachGalleryAdapter()
            galleryAdapter.submitList(sorted) {
                syncGalleryPager()
            }
        }
        val empty = sorted.isEmpty() && viewModel.loading.value != true
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ---------- 선택 모드 ----------

    private fun enterSelection(initial: ImageManagerViewModel.ManagedImage?) {
        // 선택 모드는 그리드 전용 — 갤러리에서 진입하면 그리드로 복귀 후 시작
        if (viewModel.viewMode == ImageManagerViewModel.ViewMode.GALLERY) switchToGrid()
        selectionMode = true
        if (initial != null) selectedPaths.add(initial.path)
        updateSelectionUi()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedPaths.clear()
        updateSelectionUi()
    }

    private fun toggleSelect(item: ImageManagerViewModel.ManagedImage) {
        if (!selectedPaths.add(item.path)) selectedPaths.remove(item.path)
        updateSelectionUi()
    }

    private fun selectAll() {
        val allSelected = currentList.isNotEmpty() && selectedPaths.containsAll(currentList.map { it.path })
        if (allSelected) {
            currentList.forEach { selectedPaths.remove(it.path) }
        } else {
            currentList.forEach { selectedPaths.add(it.path) }
        }
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        binding.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.selectButton.text = getString(
            if (selectionMode) R.string.image_manager_select_cancel else R.string.image_manager_select
        )
        binding.selectionCountText.text = getString(R.string.image_manager_selected_count, selectedPaths.size)
        adapter.setSelectionState(selectionMode, selectedPaths.toSet())
    }

    /** 선택된 경로에 해당하는 **현재 뷰(currentList)**의 이미지 항목들 — 일괄 작업은 화면에 보이는 대상에만 작용. */
    private fun selectedItems(): List<ImageManagerViewModel.ManagedImage> {
        return currentList.filter { selectedPaths.contains(it.path) }
    }

    // ---------- 정렬/옵션 ----------

    private fun showSortMenu() {
        val popup = PopupMenu(requireContext(), binding.sortButton)
        popup.menu.add(0, 0, 0, R.string.image_manager_sort_size)
        popup.menu.add(0, 1, 1, R.string.image_manager_sort_name)
        popup.menu.add(0, 2, 2, R.string.image_manager_sort_date)
        popup.setOnMenuItemClickListener { mi ->
            viewModel.sort = when (mi.itemId) {
                0 -> ImageManagerViewModel.Sort.SIZE
                1 -> ImageManagerViewModel.Sort.NAME
                else -> ImageManagerViewModel.Sort.DATE
            }
            applyView()
            true
        }
        popup.show()
    }

    private fun showOptionsMenu() {
        val popup = PopupMenu(requireContext(), binding.optionsButton)
        popup.menu.add(0, 0, 0, R.string.settings_image_compress_title)
        popup.menu.add(0, 1, 1, R.string.image_manager_clean_orphans)
        popup.menu.add(0, 2, 2, R.string.image_manager_refresh)
        popup.menu.add(0, 3, 3, R.string.organize_folder_import)
        popup.menu.add(0, 4, 4, R.string.organize_folder_export)
        popup.menu.add(0, 5, 5, R.string.organize_folder_settings)
        popup.menu.add(0, 6, 6, R.string.organize_folder_help)
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                0 -> ImageSettingsDialog.show(this) { viewModel.load() }
                1 -> confirmCleanOrphans()
                2 -> viewModel.load()
                3 -> startOrganizeFolderImport()
                4 -> startOrganizeFolderExport()
                5 -> showOrganizeFolderSettings()
                else -> com.novelcharacter.app.ui.common.HelpDialog.showHelp(
                    requireContext(), com.novelcharacter.app.ui.common.HelpDialog.Topic.ORGANIZE_FOLDER
                )
            }
            true
        }
        popup.show()
    }

    // ---------- 정리 폴더 왕복 ----------

    /**
     * 흐름 본체는 [OrganizeFolderController]에 있다 — 설정 화면도 같은 것을 쓴다(복제 금지).
     *
     * **필드 초기화 시점에 만들어야 한다** — 컨트롤러가 생성자에서 SAF 선택기를
     * `registerForActivityResult`로 등록하는데, 그 등록은 STARTED 이후엔 예외가 난다.
     */
    private val organizeFolder = OrganizeFolderController(this, { viewModel }) { count ->
        if (_binding == null) return@OrganizeFolderController
        binding.organizeFolderBanner.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            binding.organizeFolderBanner.text = getString(R.string.organize_folder_banner, count)
        }
    }

    private fun refreshOrganizeFolderBanner() = organizeFolder.refreshOrganizeFolderBanner()
    private fun startOrganizeFolderImport() = organizeFolder.startOrganizeFolderImport()
    private fun startOrganizeFolderExport() = organizeFolder.startOrganizeFolderExport()
    private fun showOrganizeFolderSettings() = organizeFolder.showOrganizeFolderSettings()


    // ---------- 상세(바텀시트) ----------

    private fun showDetail(item: ImageManagerViewModel.ManagedImage) {
        val ctx = context ?: return
        val sheetBinding = BottomSheetImageDetailBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(ctx)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.detailSizeText.text = StorageAnalyzer.formatBytes(item.sizeBytes)
        sheetBinding.detailOwnerText.text = ownerLabel(item)

        // 태그 칩(라이브러리 이미지) — 태그 편집은 어떤 이미지든 가능(편집 시 라이브러리로 입양).
        val tags = item.meta?.tags.orEmpty()
        sheetBinding.detailTagChipGroup.removeAllViews()
        if (tags.isNotEmpty()) {
            sheetBinding.detailTagChipGroup.visibility = View.VISIBLE
            for (tag in tags) {
                sheetBinding.detailTagChipGroup.addView(
                    com.google.android.material.chip.Chip(ctx).apply {
                        text = tag
                        isClickable = false
                        isCheckable = false
                        textSize = 12f
                    }
                )
            }
        } else {
            sheetBinding.detailTagChipGroup.visibility = View.GONE
        }
        sheetBinding.detailTagEditButton.setOnClickListener {
            dialog.dismiss()
            openTagEdit(item)
        }

        // 링크 그룹 정보 + 해제 — 링크된 이미지에만 노출. N = 현재 목록에서 같은 그룹 수.
        // 캐릭터 자동 링크 그룹은 수동 링크와 구별해 표기한다(자동 관리 상태의 가시화 — 원칙 04).
        val groupId = item.meta?.linkGroupId
        if (groupId != null) {
            val groupSize = (viewModel.images.value ?: emptyList()).count { it.meta?.linkGroupId == groupId }
            sheetBinding.detailLinkInfoText.visibility = View.VISIBLE
            sheetBinding.detailLinkInfoText.text = getString(
                if (com.novelcharacter.app.util.AutoLinkPlanner.isAutoToken(groupId)) {
                    R.string.image_link_group_info_auto
                } else {
                    R.string.image_link_group_info
                },
                groupSize
            )
            sheetBinding.detailUnlinkButton.visibility = View.VISIBLE
            sheetBinding.detailUnlinkButton.setOnClickListener { dialog.dismiss(); runUnlink(listOf(item.path)) }
        } else {
            sheetBinding.detailLinkInfoText.visibility = View.GONE
            sheetBinding.detailUnlinkButton.visibility = View.GONE
        }

        // 배정(항상) / 배정 해제(소유자 있을 때: 1명=확인, 복수=소유자 multi-choice)
        sheetBinding.detailAssignButton.setOnClickListener { dialog.dismiss(); startAssignFlow(listOf(item.path)) }
        sheetBinding.detailUnassignButton.visibility = if (item.owners.isEmpty()) View.GONE else View.VISIBLE
        sheetBinding.detailUnassignButton.setOnClickListener {
            dialog.dismiss()
            if (item.owners.size == 1) {
                confirmSingleUnassign(item)
            } else {
                pickOwnersAndUnassign(item)
            }
        }

        // 재압축: 참조본 + 라이브러리 미배정 모두 사용자 자산 → 노출. 고아/휴지통만 숨김.
        sheetBinding.detailRecompressButton.visibility =
            if (item.status == ImageManagerViewModel.Status.REFERENCED ||
                item.status == ImageManagerViewModel.Status.UNASSIGNED) View.VISIBLE else View.GONE

        sheetBinding.detailFullScreenButton.setOnClickListener { dialog.dismiss(); openFullScreen(item) }
        sheetBinding.detailRecompressButton.setOnClickListener { dialog.dismiss(); startRecompress(listOf(item)) }
        sheetBinding.detailDeleteButton.setOnClickListener { dialog.dismiss(); confirmDelete(item) }
        sheetBinding.detailCloseButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun confirmSingleUnassign(item: ImageManagerViewModel.ManagedImage) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.image_unassign_action)
            .setMessage(getString(R.string.image_unassign_confirm, 1))
            .setPositiveButton(R.string.confirm) { _, _ -> runUnassign(listOf(item.path), null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickOwnersAndUnassign(item: ImageManagerViewModel.ManagedImage) {
        val ctx = context ?: return
        val labels = item.owners.map { "${typeLabel(it.type)} · ${it.name}" }.toTypedArray()
        val checked = BooleanArray(item.owners.size) { true }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.image_unassign_pick_owners)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.confirm) { _, _ ->
                val chosen = item.owners.filterIndexed { i, _ -> checked[i] }
                if (chosen.isNotEmpty()) runUnassign(listOf(item.path), chosen)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openFullScreen(item: ImageManagerViewModel.ManagedImage) {
        val json = gson.toJson(listOf(item.path))
        findNavController().navigateSafe(
            R.id.imageViewerFragment,
            bundleOf("imagePaths" to json, "startPosition" to 0)
        )
    }

    // ---------- 삭제(개별/일괄) ----------

    private fun confirmDelete(item: ImageManagerViewModel.ManagedImage) {
        val ctx = context ?: return
        val msg = if (item.owners.isNotEmpty()) {
            getString(R.string.image_manager_delete_referenced_confirm, item.owners.size)
        } else {
            getString(R.string.image_manager_delete_orphan_confirm)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.image_manager_delete_title)
            .setMessage(msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteImage(item) { freed ->
                    if (!isAdded) return@deleteImage
                    if (freed != null) {
                        reportAndNotify(OpResult.success(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.image_manager_deleted, StorageAnalyzer.formatBytes(freed))
                        ))
                    } else {
                        reportAndNotify(OpResult.failure(
                            OpResult.CAT_MAINTENANCE, getString(R.string.image_manager_delete_failed)
                        ))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 작업형 진행도 (규약 R-26 · B-51) ----------
    //
    // 이미지 대량 작업은 전부 항목 순회형인데 **표시가 0건이었다** — 수백 장을 고르면 화면이
    // 죽은 채 있다가 결과창만 튀어나왔다(B-51의 이미지 몫).
    //
    // **뷰모델의 진행 콜백은 작업 스레드(IO)에서 온다.** 갱신은 메인이어야 하므로 여기서
    // 한 번에 감싼다 — 뷰모델이 경로마다 스레드를 갈아타면 갱신이 작업보다 비싸지고,
    // Room 트랜잭션 안에서 도는 갈래(배정·해제·태그)는 그 전환 자체가 위험하다.

    /** 진행 창을 띄운다. 화면이 없으면 null이고 작업은 그대로 진행된다. */
    private fun showTaskProgress(
        @androidx.annotation.StringRes titleRes: Int,
        total: Int,
        @androidx.annotation.StringRes stageRes: Int,
        onCancel: (() -> Unit)? = null
    ): com.novelcharacter.app.ui.common.TaskProgressDialog.Handle? {
        val ctx = context ?: return null
        return runCatching {
            com.novelcharacter.app.ui.common.TaskProgressDialog.show(
                ctx, titleRes = titleRes, total = total, stageRes = stageRes, onCancel = onCancel
            )
        }.getOrNull()
    }

    /** 진행도 갱신 — 화면이 사라진 뒤의 갱신은 조용히 버린다(작업은 계속 돈다). */
    private fun postProgress(
        handle: com.novelcharacter.app.ui.common.TaskProgressDialog.Handle?,
        current: Int,
        total: Int,
        stage: String? = null
    ) {
        handle ?: return
        activity?.runOnUiThread { if (isAdded) handle.update(current, total, stage) }
    }

    private fun deleteSelected() {
        val ctx = context ?: return
        val items = selectedItems()
        if (items.isEmpty()) {
            reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_manager_select_none)))
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.image_manager_delete_title)
            .setMessage(getString(R.string.image_manager_delete_selected_confirm, items.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                // 삭제는 한 건 한 건이 완결되므로 취소를 받는다 —
                // 취소 = 중단 시점까지 반영 + 요약(R-26 원문).
                var cancelled = false
                val progress = showTaskProgress(
                    R.string.image_manager_delete_title, items.size, R.string.image_manager_stage_delete
                ) { cancelled = true }
                viewModel.deleteImages(
                    items,
                    onProgress = { done, total -> postProgress(progress, done, total) },
                    isCancelled = { cancelled }
                ) { result ->
                    progress?.dismiss()
                    // isAdded는 onDestroyView 후에도 true라 회전/백스택 중 콜백이 파괴된 뷰에 닿는다 →
                    // _binding까지 확인해야 exitSelection()의 binding!! NPE를 막는다(ViewModel이 프래그먼트 스코프라 코루틴 생존).
                    if (!isAdded || _binding == null) return@deleteImages
                    exitSelection()
                    if (result.failed > 0) {
                        reportAndNotify(OpResult.failure(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.image_manager_bulk_delete_failed, result.failed)
                        ))
                    } else if (cancelled) {
                        // 취소해도 여기까지는 실제로 지워졌다 — 조용히 넘기면 목록이 줄어든 이유를 알 수 없다.
                        reportAndNotify(OpResult.success(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.image_manager_bulk_delete_cancelled, result.deleted)
                        ))
                    } else {
                        reportAndNotify(OpResult.success(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.image_manager_bulk_deleted, result.deleted, StorageAnalyzer.formatBytes(result.freed))
                        ))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 재압축(개별/일괄) ----------

    private fun recompressSelected() {
        val items = selectedItems()
        if (items.isEmpty()) {
            reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_manager_select_none)))
            return
        }
        startRecompress(items)
    }

    /** 대상들을 준비(임시 재인코드)한 뒤, 정확한 전/후 크기·스킵 사유를 담은 확인 다이얼로그를 띄운다. */
    private fun startRecompress(items: List<ImageManagerViewModel.ManagedImage>) {
        // 준비는 대상 전부를 디코드·재인코드하는 가장 느린 구간이다 — 재압축에서 취소가
        // 값을 하는 자리도 여기뿐이다. 취소 = 산출물 없음(임시 파일까지 지운다).
        var cancelled = false
        val progress = showTaskProgress(
            R.string.image_manager_recompress_confirm_title,
            items.size,
            R.string.image_manager_stage_recompress_prepare
        ) { cancelled = true }
        viewModel.prepareRecompress(
            items,
            onProgress = { done, total -> postProgress(progress, done, total) },
            isCancelled = { cancelled }
        ) { preview ->
            progress?.dismiss()
            if (!isAdded) { viewModel.discardRecompress(); return@prepareRecompress }
            if (preview.cancelled) {
                notifySuccess(getString(R.string.image_manager_recompress_prepare_cancelled))
                return@prepareRecompress
            }
            showRecompressConfirm(preview)
        }
    }

    private fun showRecompressConfirm(preview: ImageManagerViewModel.RecompressPreview) {
        val ctx = context ?: run { viewModel.discardRecompress(); return }
        val skipSummary = skipSummary(preview.skips)

        if (preview.plans.isEmpty()) {
            val msg = buildString {
                append(getString(R.string.image_manager_recompress_none))
                if (skipSummary != null) append("\n").append(skipSummary)
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.image_manager_recompress_confirm_title)
                .setMessage(msg)
                .setPositiveButton(R.string.confirm) { _, _ -> viewModel.discardRecompress() }
                .setOnCancelListener { viewModel.discardRecompress() }
                .show()
            return
        }

        val msg = buildString {
            append(getString(
                R.string.image_manager_recompress_confirm,
                preview.plans.size,
                StorageAnalyzer.formatBytes(preview.totalBefore),
                StorageAnalyzer.formatBytes(preview.totalAfter),
                StorageAnalyzer.formatBytes(preview.savings)
            ))
            if (skipSummary != null) append("\n\n").append(skipSummary)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.image_manager_recompress_confirm_title)
            .setMessage(msg)
            .setPositiveButton(R.string.image_manager_recompress) { _, _ ->
                // 커밋은 취소를 받지 않는다(2단계 DB 교체가 한 트랜잭션 — 뷰모델 주석 참조).
                // 대신 두 구간(바꾸기·보관)을 각자 총량으로 보고한다.
                val commitProgress = showTaskProgress(
                    R.string.image_manager_recompress_confirm_title,
                    preview.plans.size,
                    R.string.image_manager_stage_recompress_replace
                )
                val replaceStage = getString(R.string.image_manager_stage_recompress_replace)
                val backupStage = getString(R.string.image_manager_stage_recompress_backup)
                viewModel.commitRecompress(
                    onProgress = { done, total, stage ->
                        val text = when (stage) {
                            ImageManagerViewModel.RecompressStage.REPLACE -> replaceStage
                            ImageManagerViewModel.RecompressStage.BACKUP -> backupStage
                        }
                        postProgress(commitProgress, done, total, text)
                    }
                ) { result ->
                    commitProgress?.dismiss()
                    // onDestroyView 후에도 isAdded==true인 창(회전/백스택)에서 파괴된 뷰 접근 방지 — _binding까지 확인.
                    if (!isAdded || _binding == null) return@commitRecompress
                    exitSelection()
                    val base = if (result.skipped > 0) {
                        getString(
                            R.string.image_manager_recompress_done_skipped,
                            result.recompressed, StorageAnalyzer.formatBytes(result.freed), result.skipped
                        )
                    } else {
                        getString(
                            R.string.image_manager_recompress_done,
                            result.recompressed, StorageAnalyzer.formatBytes(result.freed)
                        )
                    }
                    // 커밋 실패분이 있으면 조용히 넘기지 않고 함께 고지(변수 제어). 전량 실패면 실패로 통보.
                    val text = if (result.failed > 0) {
                        base + " " + getString(R.string.image_manager_recompress_failed_suffix, result.failed)
                    } else base
                    val op = if (result.failed > 0 && result.recompressed == 0) {
                        OpResult.failure(OpResult.CAT_MAINTENANCE, text)
                    } else {
                        OpResult.success(OpResult.CAT_MAINTENANCE, text)
                    }
                    // 재압축 성공분이 있으면 "실행취소"(원본 복원) 액션 스낵바 제공 — 되돌리기 안전장치(변수 제어).
                    if (result.recompressed > 0 && viewModel.hasRecompressUndo()) {
                        logOperation(op)  // 이력엔 남기고 알림은 액션 스낵바로 대체
                        notifyWithAction(text, getString(R.string.image_manager_recompress_undo)) {
                            // 되돌리기는 취소를 받지 않는다 — 복구 행위를 다시 끊으면 어느 이미지가
                            // 어느 상태인지 알 길이 없다(뷰모델 주석 참조). 표시는 한다.
                            val undoProgress = showTaskProgress(
                                R.string.image_manager_recompress_undo_title,
                                result.recompressed,
                                R.string.image_manager_stage_recompress_undo
                            )
                            viewModel.undoLastRecompress(
                                onProgress = { done, total -> postProgress(undoProgress, done, total) }
                            ) { ok ->
                                undoProgress?.dismiss()
                                if (!isAdded || _binding == null) return@undoLastRecompress
                                if (ok) notifySuccess(getString(R.string.image_manager_recompress_undo_done))
                                else notifyError(getString(R.string.image_manager_recompress_undo_failed))
                            }
                        }
                    } else {
                        reportAndNotify(op)
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.discardRecompress() }
            .setOnCancelListener { viewModel.discardRecompress() }
            .show()
    }

    /** 스킵 내역을 사유별 개수로 요약. 없으면 null. */
    private fun skipSummary(skips: List<ImageManagerViewModel.RecompressSkip>): String? {
        if (skips.isEmpty()) return null
        val parts = skips.groupingBy { it.reason }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { "${skipReasonLabel(it.key)} ${it.value}" }
        return getString(R.string.image_manager_recompress_skip_summary, skips.size, parts.joinToString(" · "))
    }

    private fun skipReasonLabel(reason: ImageImportHelper.SkipReason): String = getString(
        when (reason) {
            ImageImportHelper.SkipReason.NOT_REFERENCED -> R.string.image_manager_skip_not_referenced
            ImageImportHelper.SkipReason.TOO_SMALL -> R.string.image_manager_skip_too_small
            ImageImportHelper.SkipReason.TOO_LARGE -> R.string.image_manager_skip_too_large
            ImageImportHelper.SkipReason.CORRUPT -> R.string.image_manager_skip_corrupt
            ImageImportHelper.SkipReason.NO_BENEFIT -> R.string.image_manager_skip_no_benefit
            ImageImportHelper.SkipReason.ERROR -> R.string.image_manager_skip_error
        }
    )

    // ---------- 일괄 작업(작업 시트) + 배정/해제/링크 플로우 ----------

    private fun openBatchOperations() {
        val items = selectedItems()
        if (items.isEmpty()) {
            reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_manager_select_none)))
            return
        }
        val sheet = ImageBatchOperationBottomSheet.newInstance(items.size)
        sheet.onAction = { action ->
            when (action) {
                ImageBatchOperationBottomSheet.Action.ASSIGN -> startAssignFlow(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.TAG_ADD -> openBatchTagSheet(items.map { it.path }, remove = false)
                ImageBatchOperationBottomSheet.Action.TAG_REMOVE -> openBatchTagSheet(items.map { it.path }, remove = true)
                ImageBatchOperationBottomSheet.Action.LINK -> startLinkFlow(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.UNLINK -> runUnlink(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.UNASSIGN -> confirmBatchUnassign(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.RECOMPRESS -> recompressSelected()
                ImageBatchOperationBottomSheet.Action.DELETE -> deleteSelected()
            }
        }
        sheet.show(childFragmentManager, ImageBatchOperationBottomSheet.TAG)
    }

    /** 배정 플로우: 대상 피커 → 링크 그룹 확장 확인(선택 밖 추가분 있으면) → 배정 → 종합 고지. */
    private fun startAssignFlow(paths: List<String>) {
        val picker = EntityPickerBottomSheet()
        picker.loadTargets = { type -> viewModel.getAssignTargets(type) }
        picker.onCreateNewCharacter = { promptCreateCharacter(paths) }
        picker.onPicked = { type, row ->
            val expansion = viewModel.expandWithLinkedGroups(paths)
            if (expansion.addedByLink.isNotEmpty()) {
                val ctx = context
                if (ctx != null) {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.image_assign_action)
                        .setMessage(getString(
                            R.string.image_assign_link_confirm,
                            paths.size, expansion.addedByLink.size, expansion.allPaths.size, row.title
                        ))
                        .setPositiveButton(R.string.confirm) { _, _ -> doAssign(paths, type, row) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } else {
                doAssign(paths, type, row)
            }
        }
        picker.show(childFragmentManager, EntityPickerBottomSheet.TAG)
    }

    /**
     * '새 캐릭터 만들기' — 이름(필수) + 작품(선택)을 받아 즉시 만들고 그 이미지를 배정한다.
     *
     * **러프 입력 경로다**(원칙 04의 이중 경로). 여기서 받는 것은 이름뿐이고 나머지는 캐릭터
     * 편집 화면이 정한다. 작품을 **강제하지 않는 것도 일부러다** — 강제하면 이미지 한 장
     * 붙이려고 작품부터 만들어야 해서, 막혀 있던 자리가 그대로 남는다(미분류 캐릭터는
     * 이 앱이 이미 지원하는 상태다).
     *
     * 입력을 유실하지 않는다(R-27) — 이름이 비면 만들지 않고 그 자리에서 알린다.
     */
    private fun promptCreateCharacter(paths: List<String>) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val novels = viewModel.getNovelChoices()
            if (!isAdded) return@launch

            val density = ctx.resources.displayMetrics.density
            val pad = (20 * density).toInt()
            val container = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, (8 * density).toInt(), pad, 0)
            }
            val nameEdit = android.widget.EditText(ctx).apply {
                hint = getString(R.string.image_assign_create_name_hint)
                setSingleLine()
            }
            container.addView(nameEdit)

            // 작품 선택 — 첫 항목은 '작품 없음'(미분류). 작품이 하나도 없으면 스피너를 감춘다.
            val novelSpinner = android.widget.Spinner(ctx)
            if (novels.isNotEmpty()) {
                val labels = mutableListOf(getString(R.string.image_assign_create_no_novel))
                labels.addAll(novels.map { it.title })
                novelSpinner.adapter = android.widget.ArrayAdapter(
                    ctx, android.R.layout.simple_spinner_dropdown_item, labels
                )
                container.addView(novelSpinner)
            }

            // R-27: 리스너 없이 만들고 setValidatedPositiveButton으로 검증한다 —
            // setPositiveButton은 조기 return을 해도 창이 닫혀 입력이 유실된다.
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.image_assign_create_character)
                .setView(container)
                .setPositiveButton(R.string.confirm, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
            dialog.setValidatedPositiveButton {
                val name = nameEdit.text.toString().trim()
                if (name.isEmpty()) {
                    // 실패 문구는 고칠 자리에 붙인다(토스트는 화면을 떠난다)
                    nameEdit.showInlineError(getString(R.string.image_assign_create_name_required))
                    return@setValidatedPositiveButton false
                }
                val novelId = if (novels.isEmpty()) null else {
                    val idx = novelSpinner.selectedItemPosition
                    if (idx <= 0) null else novels[idx - 1].id
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val row = viewModel.createCharacterForAssign(name, novelId)
                    if (!isAdded) return@launch
                    doAssign(paths, ImageManagerViewModel.OwnerType.CHARACTER, row)
                }
                true
            }
            dialog.show()
        }
    }

    private fun doAssign(paths: List<String>, type: ImageManagerViewModel.OwnerType, row: ImageManagerViewModel.PickRow) {
        // 총량은 링크 그룹으로 넓힌 뒤의 수다 — 고른 수로 띄우면 막대가 총량을 넘는다.
        val total = viewModel.expandWithLinkedGroups(paths).allPaths.size
        val progress = showTaskProgress(
            R.string.image_assign_action, total, R.string.image_manager_stage_assign
        )
        viewModel.assignToTarget(
            paths, type, row.id,
            onProgress = { done, t -> postProgress(progress, done, t) }
        ) { result ->
            progress?.dismiss()
            if (!isAdded || _binding == null) return@assignToTarget
            if (result.failed) {
                reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_assign_failed)))
                return@assignToTarget
            }
            exitSelection()
            val parts = mutableListOf(getString(R.string.image_assign_done, row.title, result.assigned))
            if (result.viaLink > 0) parts.add(getString(R.string.image_assign_note_link, result.viaLink))
            if (result.alreadyOwned > 0) parts.add(getString(R.string.image_assign_note_already, result.alreadyOwned))
            if (result.modeChanged) parts.add(getString(R.string.image_assign_note_mode))
            reportAndNotify(OpResult.success(OpResult.CAT_MAINTENANCE, parts.joinToString(" · ")))
        }
    }

    private fun confirmBatchUnassign(paths: List<String>) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.image_unassign_action)
            .setMessage(getString(R.string.image_unassign_confirm, paths.size))
            .setPositiveButton(R.string.confirm) { _, _ -> runUnassign(paths, null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runUnassign(paths: List<String>, owners: List<ImageManagerViewModel.Owner>?) {
        val progress = showTaskProgress(
            R.string.image_unassign_action, paths.size, R.string.image_manager_stage_unassign
        )
        viewModel.unassign(
            paths, owners,
            onProgress = { done, total -> postProgress(progress, done, total) }
        ) { result ->
            progress?.dismiss()
            if (!isAdded || _binding == null) return@unassign
            if (result.failed) {
                reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_unassign_failed)))
                return@unassign
            }
            exitSelection()
            val parts = mutableListOf(getString(R.string.image_unassign_done, result.cleared))
            if (result.adopted > 0) parts.add(getString(R.string.image_unassign_note_adopted, result.adopted))
            reportAndNotify(OpResult.success(OpResult.CAT_MAINTENANCE, parts.joinToString(" · ")))
        }
    }

    private fun startLinkFlow(paths: List<String>) {
        if (paths.size < 2) {
            reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_link_need_two)))
            return
        }
        viewModel.linkImages(paths, confirmMerge = false) { outcome -> handleLinkOutcome(paths, outcome) }
    }

    private fun handleLinkOutcome(paths: List<String>, outcome: ImageManagerViewModel.LinkOutcome) {
        if (!isAdded || _binding == null) return
        when (outcome) {
            is ImageManagerViewModel.LinkOutcome.NeedsMerge -> {
                val ctx = context ?: return
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.image_link_action)
                    .setMessage(getString(R.string.image_link_merge_confirm, outcome.groups))
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        viewModel.linkImages(paths, confirmMerge = true) { o -> handleLinkOutcome(paths, o) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            is ImageManagerViewModel.LinkOutcome.Done -> {
                exitSelection()
                val msg = if (outcome.merged) getString(R.string.image_link_merged_done, outcome.linked)
                else getString(R.string.image_link_done, outcome.linked)
                reportAndNotify(OpResult.success(OpResult.CAT_MAINTENANCE, msg))
            }
            ImageManagerViewModel.LinkOutcome.Failed ->
                reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_link_failed)))
        }
    }

    private fun runUnlink(paths: List<String>) {
        viewModel.unlinkImages(paths) { result ->
            if (!isAdded || _binding == null) return@unlinkImages
            exitSelection()
            // 자동 링크 대상이면 해제가 다음 재동기화에 되돌아간다 — 조용한 원복 금지, 교정 경로 고지
            val message = buildString {
                append(getString(R.string.image_unlink_done, result.cleared))
                if (result.autoRelinkable > 0) {
                    append("\n")
                    append(getString(R.string.image_unlink_auto_notice, result.autoRelinkable))
                }
            }
            reportAndNotify(OpResult.success(OpResult.CAT_MAINTENANCE, message))
        }
    }

    private fun openBatchTagSheet(paths: List<String>, remove: Boolean) {
        val sheet = ImageBatchTagBottomSheet()
        sheet.isRemoveMode = remove
        sheet.loadChips = if (remove) {
            { viewModel.getDistinctTagsForPaths(paths) }
        } else {
            { viewModel.getTagSuggestions() }
        }
        sheet.onConfirm = { tags ->
            // 둘 다 취소를 받지 않는다 — 한 트랜잭션(추가) · 끊어 보내는 삭제 질의(제거)라
            // 중간에 멈출 안전한 경계가 없다(뷰모델 주석 참조).
            val progress = showTaskProgress(
                R.string.image_manager_tag_progress_title,
                paths.size,
                if (remove) R.string.image_manager_stage_tag_remove else R.string.image_manager_stage_tag_add
            )
            val onCount: (Int) -> Unit = { count ->
                progress?.dismiss()
                if (isAdded && _binding != null) {
                    exitSelection()
                    reportAndNotify(OpResult.success(OpResult.CAT_MAINTENANCE, getString(R.string.image_batch_tag_done, count)))
                }
            }
            if (remove) {
                viewModel.removeTagsFromImages(
                    paths, tags,
                    onProgress = { done, total -> postProgress(progress, done, total) },
                    onDone = onCount
                )
            } else {
                viewModel.addTagsToImages(
                    paths, tags,
                    onProgress = { done, total -> postProgress(progress, done, total) },
                    onDone = onCount
                )
            }
        }
        sheet.show(childFragmentManager, ImageBatchTagBottomSheet.TAG)
    }

    // ---------- 고아 정리 ----------

    private fun confirmCleanOrphans() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.image_manager_clean_orphans)
            .setMessage(R.string.storage_clean_orphan_confirm)
            .setPositiveButton(R.string.storage_clean_run) { _, _ ->
                viewModel.cleanOrphans { result ->
                    if (!isAdded) return@cleanOrphans
                    if (result.aborted) {
                        reportAndNotify(OpResult.failure(
                            OpResult.CAT_MAINTENANCE, getString(R.string.storage_clean_orphan_aborted)
                        ))
                    } else {
                        reportAndNotify(OpResult.success(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.storage_clean_orphan_done, result.deleted, StorageAnalyzer.formatBytes(result.freed))
                        ))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun typeLabel(type: ImageManagerViewModel.OwnerType): String = when (type) {
        ImageManagerViewModel.OwnerType.CHARACTER -> getString(R.string.image_manager_type_character)
        ImageManagerViewModel.OwnerType.NOVEL -> getString(R.string.image_manager_type_novel)
        ImageManagerViewModel.OwnerType.UNIVERSE -> getString(R.string.image_manager_type_universe)
    }

    /** 소유자/상태 문구 — 상세 시트와 갤러리 오버레이가 공용 */
    private fun ownerLabel(item: ImageManagerViewModel.ManagedImage): String =
        if (item.owners.isEmpty()) {
            when (item.status) {
                ImageManagerViewModel.Status.TRASH_HELD -> getString(R.string.image_manager_owner_trash)
                ImageManagerViewModel.Status.UNASSIGNED -> getString(R.string.image_manager_owner_unassigned)
                else -> getString(R.string.image_manager_owner_orphan)
            }
        } else {
            item.owners.joinToString("\n") { "${typeLabel(it.type)} · ${it.name}" }
        }

    /** 태그 편집 시트 — 상세 시트와 갤러리 오버레이('태그 편집' 1탭 단축)가 공용 */
    private fun openTagEdit(item: ImageManagerViewModel.ManagedImage) {
        val sheet = ImageTagEditBottomSheet()
        sheet.currentTags = item.meta?.tags.orEmpty()
        sheet.loadSuggestions = { viewModel.getTagSuggestions() }
        sheet.onSave = { newTags ->
            viewModel.replaceTags(item.path, newTags) {
                if (!isAdded || _binding == null) return@replaceTags
                reportAndNotify(OpResult.success(
                    OpResult.CAT_MAINTENANCE,
                    getString(R.string.image_tag_edit_done, newTags.size)
                ))
            }
        }
        sheet.show(childFragmentManager, ImageTagEditBottomSheet.TAG)
    }

    override fun onDestroyView() {
        galleryPageCallback?.let { binding.galleryPager.unregisterOnPageChangeCallback(it) }
        galleryPageCallback = null
        binding.galleryPager.adapter = null
        binding.recyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
