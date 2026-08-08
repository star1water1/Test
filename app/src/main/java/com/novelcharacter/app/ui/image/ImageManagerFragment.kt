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

        /**
         * '1장씩 다시 보내기'로 되받을 수 있는 실패 (B-121).
         *
         * **번호가 어긋나 접힌 배치뿐이다** — 장수 1이면 번호 사고가 원리적으로 없으므로
         * 다시 보내는 것에 근거가 있다. 키 오류·할당량 소진·이미지 미지원은 1장으로 보내도
         * 같은 결과라, 그 길을 열면 사용자는 돈만 더 쓰고 같은 실패를 다시 본다.
         */
        private val AI_TAG_RETRYABLE = setOf(
            com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.INDEX_OUT_OF_RANGE,
            com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.INDEX_DUPLICATED,
            com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.NO_JSON,
            com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.RESPONSE_TRUNCATED
        )
    }

    private var _binding: FragmentImageManagerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ImageManagerViewModel by viewModels()
    private val gson = Gson()

    // 일괄 AI 태깅 진행 다이얼로그 — 실행 상태는 VM(aiTagRunning)이 들고 **창만** 뷰 수명에 묶는다.
    // 회전하면 이 창은 사라지고, 재생성된 뷰의 관측이 다시 세운다(B-136).
    private var aiTagProgressDialog: com.novelcharacter.app.ui.common.TaskProgressDialog.Handle? = null

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
                R.id.chipDetached -> com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.DETACHED
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
                s.totalCount, StorageAnalyzer.formatBytes(s.totalBytes), s.referencedCount,
                s.unassignedCount, s.detachedCount, s.orphanCount
            )
        }
        viewModel.images.observe(viewLifecycleOwner) { applyView() }
        observeAiTagRun()
        organizeFolder.observeFolderTagRun()

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
            com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.DETACHED -> R.id.chipDetached
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
        val c = viewModel.criteria
        val n = c.tags.size
        val untagged = c.tagPresence == com.novelcharacter.app.util.ImageFilterHelper.TagFilter.UNTAGGED
        val active = n > 0 || untagged
        val button = binding.tagFilterButton
        // 무태그는 개수로 셀 수 없으므로 라벨을 따로 든다 — 켜 두고 버튼이 '태그'라고만
        // 말하면, 목록이 왜 좁아졌는지 화면 어디에도 없다(원칙 04의 "존재를 알 수 없는 것").
        button.text = when {
            untagged -> getString(R.string.image_manager_tag_filter_untagged)
            n > 0 -> getString(R.string.image_manager_tag_filter_count, n)
            else -> getString(R.string.image_manager_tag_filter)
        }
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
        sheet.currentPresence = viewModel.criteria.tagPresence
        sheet.loadAllTags = { viewModel.getAllImageTags() }
        sheet.onApply = { tags, presence ->
            viewModel.criteria = viewModel.criteria.copy(tags = tags, tagPresence = presence)
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
                linkGroupId = item.meta?.linkGroupId,
                detachedAt = item.meta?.detachedAt
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
        val base = if (item.owners.isNotEmpty()) {
            getString(R.string.image_manager_delete_referenced_confirm, item.owners.size)
        } else {
            getString(R.string.image_manager_delete_orphan_confirm)
        }
        // 사전 고지(B-103 D6ⓐ) — 대표를 지우는 것이면 결과를 실행 전에 말하고 취소 경로를 남긴다(R-4).
        // 여기서 말하지 않으면 대표가 조용히 풀리고 사용자는 다음 화면에서야 알게 된다.
        val msg = when {
            item.representativeOf.size == 1 ->
                base + "\n\n" + getString(
                    R.string.representative_image_delete_warning_one, item.representativeOf.first()
                )
            item.representativeOf.size > 1 ->
                base + "\n\n" + getString(
                    R.string.representative_image_delete_warning_many, item.representativeOf.size
                )
            else -> base
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
        val aiUsable = runCatching {
            com.novelcharacter.app.ai.AiService(requireContext()).hasUsableProvider()
        }.getOrDefault(false)
        val sheet = ImageBatchOperationBottomSheet.newInstance(items.size, aiUsable)
        sheet.onAction = { action ->
            when (action) {
                ImageBatchOperationBottomSheet.Action.ASSIGN -> startAssignFlow(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.TAG_ADD -> openBatchTagSheet(items.map { it.path }, remove = false)
                ImageBatchOperationBottomSheet.Action.TAG_REMOVE -> openBatchTagSheet(items.map { it.path }, remove = true)
                ImageBatchOperationBottomSheet.Action.AI_TAG -> openAiTagFlow(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.LINK -> startLinkFlow(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.UNLINK -> runUnlink(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.UNASSIGN -> confirmBatchUnassign(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.CLEAR_DETACHED -> clearDetachedMarks(items.map { it.path })
                ImageBatchOperationBottomSheet.Action.RECOMPRESS -> recompressSelected()
                ImageBatchOperationBottomSheet.Action.DELETE -> deleteSelected()
            }
        }
        sheet.show(childFragmentManager, ImageBatchOperationBottomSheet.TAG)
    }

    // ---------- 이미지 내용 일괄 AI 태깅 (B-121 · 설계 feature_roadmap 2-3) ----------

    /**
     * 설정 시트 → 실행 → 검토의 세 단계 중 **첫 단계**: 장수를 정하고 비용을 고지한다.
     *
     * 고지가 실제보다 적게 말하면 사용자는 예상보다 많이 내고, **그 어긋남은 화면이 아니라
     * 청구서에서만 드러난다**(R-4). 그래서 요청 수는 `AiPromptPolicy`가 실제 청킹과 같은
     * 함수로 계산하고, 슬라이더를 움직이면 그 자리에서 다시 센다.
     *
     * **이미지가 기기 밖으로 나간다는 사실도 여기서 말한다** — B-120이 첨부 고지에 세운 그
     * 규칙이 이 경로에도 그대로 붙는다(기존 약속을 뒤집는 지점이므로 침묵 금지).
     */
    private fun openAiTagFlow(paths: List<String>) {
        if (paths.isEmpty()) return
        val ctx = requireContext()
        val settings = com.novelcharacter.app.ai.AiPromptSettings(ctx)
        var perRequest = settings.imageTagBatchSize

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 16, 64, 0)
        }
        val countLabel = android.widget.TextView(ctx)
        val slider = com.google.android.material.slider.Slider(ctx).apply {
            valueFrom = com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TAG_BATCH_MIN.toFloat()
            valueTo = com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TAG_BATCH_MAX.toFloat()
            stepSize = 1f
            value = perRequest.toFloat()
        }
        val costLabel = android.widget.TextView(ctx).apply { textSize = 12f }

        fun refresh() {
            val requests = com.novelcharacter.app.ai.AiPromptPolicy
                .imageTagBatchRequestCount(paths.size, perRequest)
            countLabel.text = getString(R.string.image_ai_tag_batch_size, perRequest)
            costLabel.text = getString(R.string.image_ai_tag_cost, paths.size, perRequest, requests)
        }
        slider.addOnChangeListener { _, value, _ ->
            perRequest = value.toInt()
            refresh()
        }
        refresh()

        container.addView(countLabel)
        container.addView(slider)
        container.addView(costLabel)
        container.addView(android.widget.TextView(ctx).apply {
            text = getString(R.string.image_ai_tag_privacy)
            textSize = 12f
        })

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.image_ai_tag_action)
            .setView(com.novelcharacter.app.util.cappedScrollView(ctx).apply { addView(container) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.image_ai_tag_run) { _, _ ->
                settings.imageTagBatchSize = perRequest
                runAiTagSuggest(paths, perRequest)
            }
            .show()
    }

    /**
     * 실행 — 결정형 진행도(R-26)와 취소를 붙인다.
     *
     * 취소는 즉시 중단이 아니라 **더 시작하지 않음**이고, **끝난 배치의 제안은 살린다**
     * (B-108 ⓕ의 관행 — 완결된 몫은 버리지 않는다). 그래서 취소해도 검토 시트가 뜬다.
     *
     * **실행 자체는 ViewModel이 한다**(B-136) — 회전으로 이 뷰가 사라져도 요청은 계속 돌고,
     * 진행도·결과는 아래 관측이 재생성된 화면에 다시 붙인다. 종전에는 실행이
     * `viewLifecycleOwner.lifecycleScope`에 있어 **회전 한 번이 결제 중인 요청을 끊었다.**
     */
    private fun runAiTagSuggest(
        paths: List<String>,
        perRequest: Int,
        carryOver: com.novelcharacter.app.ai.ImageBatchTagSuggester.Result? = null
    ) {
        if (!viewModel.runImageTagSuggest(paths, perRequest, carryOver)) {
            // 무통보 무시 금지 — 눌렀는데 아무 일도 안 일어나면 고장과 구분되지 않는다.
            notifyError(getString(R.string.image_ai_tag_already_running))
        }
    }

    /**
     * 일괄 AI 태깅의 진행·결과 관측 — **회전을 넘기는 배선이 전부 여기 있다** (B-136).
     *
     * 진행 다이얼로그는 뷰 수명에 묶고(창은 뷰의 것이다) 실행 상태는 ViewModel이 든다.
     * 그래서 회전 뒤 재생성된 뷰가 `aiTagRunning`을 다시 보고 창을 **다시 세운다.**
     */
    private fun observeAiTagRun() {
        viewModel.aiTagRunning.observe(viewLifecycleOwner) { running ->
            if (running == true) {
                if (aiTagProgressDialog == null) {
                    aiTagProgressDialog = com.novelcharacter.app.ui.common.TaskProgressDialog.show(
                        requireContext(),
                        titleRes = R.string.image_ai_tag_action,
                        total = viewModel.aiTagProgress.value?.totalRequests ?: 0,
                        stageRes = R.string.image_ai_tag_stage,
                        // 취소 깃발은 ViewModel이 든다 — 이 창은 회전으로 사라졌다 다시 서는데,
                        // 깃발이 창에 붙어 있으면 그때 취소가 실행에 닿지 못한다.
                        onCancel = { viewModel.cancelAiTagRun() }
                    )
                    // 다시 세운 창은 눈금이 0에서 시작한다 — 마지막 값으로 곧바로 맞춘다.
                    viewModel.aiTagProgress.value?.let { applyAiTagProgress(it) }
                }
            } else {
                aiTagProgressDialog?.dismiss()
                aiTagProgressDialog = null
            }
        }
        viewModel.aiTagProgress.observe(viewLifecycleOwner) { p ->
            if (p != null) applyAiTagProgress(p)
        }
        viewModel.aiTagResult.observe(viewLifecycleOwner) { result ->
            if (result != null) showAiTagReview(result)
        }
    }

    private fun applyAiTagProgress(p: ImageManagerViewModel.AiTagProgress) {
        aiTagProgressDialog?.update(
            p.doneRequests, p.totalRequests,
            stage = getString(R.string.image_ai_tag_progress, p.doneImages, p.totalImages)
        )
    }

    /**
     * 검토 — 드롭·실패를 사유별로 고지하고(R-14·R-17) 고른 것만 적용한다.
     *
     * **이미 떠 있는 시트가 있으면 다시 띄우지 않고 다시 먹인다.** 두 경로가 여기로 온다:
     * 회전으로 되살아난 빈 시트, 그리고 되받기가 합쳐 온 결과. 둘 다 시트를 새로 만들면
     * 사용자의 체크가 날아가고 창이 겹친다.
     */
    private fun showAiTagReview(result: com.novelcharacter.app.ai.ImageBatchTagSuggester.Result) {
        val notices = ArrayList<String>()
        val d = result.drops
        val dropped = d.blankOrTooLong + d.overPerImageCap
        if (dropped > 0) notices.add(getString(R.string.image_tag_review_notice_dropped, dropped))
        if (d.unreadable > 0) notices.add(getString(R.string.image_ai_tag_notice_unreadable, d.unreadable))
        if (d.blocked > 0) notices.add(getString(R.string.image_ai_tag_notice_blocked, d.blocked))
        if (d.vocabTruncated > 0) notices.add(getString(R.string.image_tag_review_notice_vocab, d.vocabTruncated))
        if (d.policyTruncated > 0) notices.add(getString(R.string.image_tag_review_notice_policy, d.policyTruncated))
        if (result.cancelled) notices.add(getString(R.string.image_ai_tag_notice_cancelled))
        notices.addAll(aiTagFailureNotices(result))

        if (result.suggestions.isEmpty() && notices.isEmpty()) {
            // **말없이 빠지지 않는다** (B-144 · R-17). 이 조합은 실패가 아니라 정상 경로다 —
            // 프롬프트가 *"근거를 찾을 수 없는 이미지는 빈 배열로 둔다"*고 시키고, `suggest`가
            // 빈 배열을 어디에도 세지 않고 뺀다(그래서 드롭 집계도 실패도 비어 notices가 없다).
            // 그런데 그 정상 경로에서 **화면이 아무 말도 하지 않으면 고장과 구분되지 않는다.**
            //
            // 폴더판([OrganizeFolderController])은 같은 자리에서 침묵하고 **그쪽은 그것이 옳다**
            // (빈 창은 그 자체가 소음이다 — 폴더 받아오기에 딸린 곁가지라 사용자가 그것만을
            // 기다리고 있지 않다). 이쪽은 다르다: **비용을 고지하고 확인받아 단독으로 실행한
            // 유료 동작**이고, 진행 창까지 닫히고 나면 사용자가 보는 것은 아무 변화도 없는
            // 화면뿐이다. 침묵의 무게가 갈리므로 형제를 따라가지 않는다.
            //
            // 시트를 여는 대신 한 줄로 말하는 것도 그래서다 — 고를 것이 없는 창을 세우면
            // 폴더판이 피한 소음을 이쪽에서 만든다.
            //
            // **`notifyError`가 곧 "실패했다"는 뜻은 아니다.** 이 저장소에서 두 고지 함수의
            // 차이는 스낵바 길이뿐이고(`notifySuccess`는 SHORT), 이 문구는 두 문장이라 짧게
            // 띄우면 다 읽기 전에 사라진다. 같은 파일의 `image_ai_tag_already_running`도
            // 같은 이유로 이쪽을 쓴다.
            notifyError(getString(R.string.image_ai_tag_nothing))
            viewModel.clearAiTagResult()
            return
        }

        // 되받을 수 있는 것은 **번호 사고로 접힌, 여러 장이 실렸던 배치**뿐이다 — 키가 없거나
        // 할당량이 끝난 실패는 1장씩 보내도 같은 결과라, 그 길을 열면 돈만 더 쓴다.
        val retryPaths = com.novelcharacter.app.ai.ImageBatchTagSuggester
            .retryablePaths(result, AI_TAG_RETRYABLE)

        // `isAdded`로 거르는 이유: 적용 실패로 결과가 **되살아나는** 경로에서는 시트가 이미
        // 닫히는 중이라 태그로는 아직 찾히지만 다시 먹여도 화면에 닿지 않는다(B-163).
        // 회전으로 되살아난 시트는 붙어 있으므로 그쪽은 종전대로 다시 먹인다.
        val existing = (childFragmentManager
            .findFragmentByTag(ImageAiTagReviewSheet.TAG) as? ImageAiTagReviewSheet)
            ?.takeIf { it.isAdded }
        val sheet = existing ?: ImageAiTagReviewSheet()
        bindAiTagReviewCallbacks(sheet, result)
        if (existing != null) {
            sheet.rebind(result.suggestions, notices, retryPaths)
        } else {
            sheet.suggestions = result.suggestions
            sheet.notices = notices
            sheet.retryPaths = retryPaths
            sheet.show(childFragmentManager, ImageAiTagReviewSheet.TAG)
        }
    }

    /**
     * 시트의 콜백을 붙인다 — 재생성된 시트의 람다는 null이라 **다시 붙이지 않으면 눌러도
     * 아무 일이 없다**(고장과 구분되지 않는다 — 통계 드릴다운 시트가 세운 선례).
     */
    private fun bindAiTagReviewCallbacks(
        sheet: ImageAiTagReviewSheet,
        result: com.novelcharacter.app.ai.ImageBatchTagSuggester.Result
    ) {
        // 되받기는 **앞 실행의 결과를 들고 간다** — 그러지 않으면 이미 결제한 제안이
        // 통째로 사라지고, 되받으려면 성공한 요청까지 다시 결제해야 한다(B-140).
        sheet.onRetryOneByOne = { retry ->
            runAiTagSuggest(retry, perRequest = 1, carryOver = result)
        }
        // **비우는 일은 여기서 하지 않는다** — 적용이 실패하면 이미 결제한 제안을 되살려야
        // 하는데, 누른 시점에 비우면 되살릴 것이 남지 않는다. 소비는 결과를 아는 곳
        // (ViewModel)이 성공했을 때만 한다(R-38 · B-163).
        sheet.onApply = { picked ->
            viewModel.applyImageTags(picked) { outcome ->
                if (!isAdded) return@applyImageTags
                reportAndNotify(tagApplyResult(outcome))
            }
        }
        // 사용자가 검토를 접었으면 보관 중인 결과도 버린다 — 남기면 다음 회전에 되살아난다.
        sheet.onDismissed = { viewModel.clearAiTagResult() }
    }

    /** 실패 고지 — 사유마다 한 줄. 같은 사유가 여러 배치에 나면 묶어서 센다. */
    private fun aiTagFailureNotices(
        result: com.novelcharacter.app.ai.ImageBatchTagSuggester.Result
    ): List<String> {
        val out = ArrayList<String>()
        val byKind = result.failures.groupBy { it.kind }
        val truncated = byKind[com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.RESPONSE_TRUNCATED]
            .orEmpty()
        val sealed = byKind.filterKeys {
            it in AI_TAG_RETRYABLE &&
                it != com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.RESPONSE_TRUNCATED
        }.values.flatten()
        if (sealed.isNotEmpty()) {
            // 번호가 어긋난 배치는 통째로 접혔다 — 무엇이 빠졌는지 말하지 않으면
            // 사용자는 그 이미지들이 '태그가 없는 이미지'라고 잘못 배운다.
            out.add(getString(R.string.image_ai_tag_notice_sealed, sealed.sumOf { it.paths.size }))
        }
        if (truncated.isNotEmpty()) {
            // 처방이 다르다 — 이쪽은 장수를 줄이거나 출력 상한을 올리는 것이다.
            out.add(getString(R.string.image_ai_tag_notice_truncated, truncated.sumOf { it.paths.size }))
        }
        byKind[com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.IMAGES_UNSUPPORTED]?.let {
            out.add(getString(R.string.image_ai_tag_notice_no_vision))
        }
        byKind[com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.IMAGES_UNREADABLE]?.let {
            out.add(getString(R.string.image_ai_tag_notice_unreadable, it.sumOf { f -> f.paths.size }))
        }
        byKind[com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.IMAGES_BLOCKED]?.let {
            // 처방이 다르다 — 파일은 멀쩡히 있고, 할 일은 그 그림을 앱에 들이는 것이다.
            out.add(getString(R.string.image_ai_tag_notice_blocked, it.sumOf { f -> f.paths.size }))
        }
        byKind[com.novelcharacter.app.ai.ImageBatchTagSuggester.BatchFailKind.REQUEST_FAILED]
            ?.firstOrNull()?.failure?.let {
            out.add(
                getString(
                    R.string.image_tag_review_notice_failed,
                    com.novelcharacter.app.ai.AiErrorMessages.of(requireContext(), it)
                )
            )
        }
        return out
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

    /**
     * 뗀 표식 지우기 — 서랍에서 뺀다(B-107 D2).
     *
     * **확인창을 두지 않는다.** 파괴적이지 않고(파일도 배정도 그대로다) 되돌리기가 같은
     * 자리에 있다 — 다시 떼면 된다. 파괴 경로에만 확인을 두는 것이 R-4의 취지다.
     */
    private fun clearDetachedMarks(paths: List<String>) {
        viewModel.clearDetachedMark(paths) { cleared ->
            if (!isAdded || _binding == null) return@clearDetachedMark
            exitSelection()
            reportAndNotify(OpResult.success(
                OpResult.CAT_MAINTENANCE,
                getString(R.string.image_manager_clear_detached_done, cleared)
            ))
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
        // 회전 시 액티비티 파괴 전에 진행 다이얼로그를 닫는다 — 파괴된 윈도우 dismiss 크래시 방지.
        // 실행은 VM에서 계속 돌고, 재생성 뷰의 aiTagRunning 관측이 창을 다시 세운다.
        aiTagProgressDialog?.dismiss()
        aiTagProgressDialog = null
        galleryPageCallback?.let { binding.galleryPager.unregisterOnPageChangeCallback(it) }
        galleryPageCallback = null
        binding.galleryPager.adapter = null
        binding.recyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}

/**
 * 태그 적용 결과를 사용자 문장으로 옮긴다 — **이미지판과 폴더판이 한 벌을 쓴다.**
 *
 * 두 화면이 각자 적으면 한쪽만 고쳐진다. B-143이 정확히 그 모양이었다(같은 결함이
 * `applyImageTags`·`applyFolderTags` 두 함수에 나란히 있었다). 문장이 한 자리에 있으면
 * 다음에 고칠 사람도 한 자리만 본다.
 *
 * 실패를 **이력에도 남긴다** — 종전에는 성공만 스낵바로 흘려 보내 실패가 어디에도 안 남았고,
 * 그래서 *"적용했다는데 태그가 없다"*를 나중에 되짚을 근거가 없었다.
 */
internal fun Fragment.tagApplyResult(outcome: ImageManagerViewModel.TagApplyOutcome): OpResult =
    when (outcome) {
        is ImageManagerViewModel.TagApplyOutcome.Done -> OpResult.success(
            OpResult.CAT_MAINTENANCE,
            getString(R.string.image_tag_review_applied, outcome.tags, outcome.images)
        )
        ImageManagerViewModel.TagApplyOutcome.Failed -> OpResult.failure(
            OpResult.CAT_MAINTENANCE,
            getString(R.string.image_tag_review_apply_failed)
        )
    }
