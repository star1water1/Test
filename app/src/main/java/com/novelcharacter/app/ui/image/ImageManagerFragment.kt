package com.novelcharacter.app.ui.image

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
import com.novelcharacter.app.util.notifyResult
import com.novelcharacter.app.util.notifyWithAction
import com.novelcharacter.app.util.notifySuccess
import com.novelcharacter.app.util.notifyError
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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

    // 묶어 보기의 좌표계 — 접기 전(필터·정렬 적용) 목록과, 접힌 칸 경로 → 식구들.
    // 선택·일괄 작업은 화면의 칸이 아니라 **이 펼친 목록**에 작용한다(칸 하나 = 식구 전체).
    private var expandedItems: List<ImageManagerViewModel.ManagedImage> = emptyList()
    private var stackMembers: Map<String, List<ImageManagerViewModel.ManagedImage>> = emptyMap()

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
        // 갤러리 소비 목록은 어댑터가 든 것(펼친 목록)이다 — currentList(접힌 표시 목록)로
        // 집으면 묶어 보기에서 페이지와 다른 이미지의 상세·태그가 열린다.
        binding.galleryDetailButton.setOnClickListener {
            galleryAdapter.currentList.getOrNull(binding.galleryPager.currentItem)?.let { showDetail(it) }
        }
        binding.galleryTagButton.setOnClickListener {
            galleryAdapter.currentList.getOrNull(binding.galleryPager.currentItem)?.let { openTagEdit(it) }
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

        viewModel.pruneState.observe(viewLifecycleOwner) { state ->
            applyView()
            // 후보 수는 활성 칩에 적힌다 — 눌러 보기 전에 규모가 보인다(원칙 04).
            renderActiveFilterChips()
            // **제안까지가 끝이다**(백로그 원문) — 후보를 골랐다는 사실과 함께 그 말을 한 번 한다.
            // **한 번인지 세는 것은 ViewModel이다** — 관측은 뷰 재생성마다 되돌아오는데
            // 조각 필드로 세면 회전 한 번에 도로 0이 되어 **같은 말이 회전마다 반복된다.**
            if (state is ImageManagerViewModel.PruneState.Ready &&
                state.hasBasis && state.paths.isNotEmpty() && viewModel.consumePruneNotice()
            ) {
                notifySuccess(
                    getString(R.string.image_manager_prune_found, state.scannedCharacters, state.paths.size)
                )
            }
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

        binding.controlsButton.setOnClickListener { openControlsSheet() }
        // 정렬 칩 탭 = 방향 반전 — 시트를 열지 않고 한 탭으로 뒤집는 지름길(캐릭터 목록의 관행)
        binding.sortChip.setOnClickListener {
            viewModel.sortAscending = !viewModel.sortAscending
            updateSortChip()
            applyView()
        }
        binding.importButton.setOnClickListener { imagePickerLauncher.launch("image/*") }
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

    /** 상태 복원 — 정렬 칩·활성 필터 칩·검색어를 VM criteria(SavedStateHandle)에 맞춘다. */
    private fun restoreFilterUi() {
        val c = viewModel.criteria
        // 걸러낼 후보는 SavedStateHandle에 남으므로 프로세스가 죽었다 살아나도 켠 채로 돌아온다.
        // 그때 계산 결과는 함께 살아나지 않으므로 **계산을 되살린다** —
        // 켜져 있는데 후보가 0인 화면은 *"정말 없다"*와 구별되지 않는다.
        if (c.prune == com.novelcharacter.app.util.ImageFilterHelper.PruneFilter.CANDIDATE &&
            viewModel.pruneState.value is ImageManagerViewModel.PruneState.Off
        ) {
            viewModel.setPruneFilter(true)
        }
        if (c.query.isNotBlank()) binding.searchEdit.setText(c.query)
        updateSortChip()
        renderActiveFilterChips()
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
            // 접힌 칸에서 들어와도 대표의 경로는 펼친 목록에 있다 — path 우선 동기화가 잡는다.
            viewModel.galleryPath = currentList.getOrNull(gridPos)?.path
        }
        attachGalleryAdapter()
        applyViewMode()
        galleryAdapter.submitList(expandedItems) { syncGalleryPager() }
    }

    private fun switchToGrid() {
        val path = galleryAdapter.currentList.getOrNull(binding.galleryPager.currentItem)?.path
        // 어댑터 분리로 페이지 홀더를 즉시 재활용 — 디코드 Job 취소·비트맵 해제
        // (onDestroyView와 동일 관용구. GONE 전환만으로는 layout이 없어 회수가 안 된다)
        binding.galleryPager.adapter = null
        viewModel.viewMode = ImageManagerViewModel.ViewMode.GRID
        applyViewMode()
        // 갤러리는 펼친 목록, 그리드는 접힌 목록이라 좌표가 다르다 — 보던 장이 든 칸으로 간다.
        if (path != null) {
            val target = currentList.indexOfFirst { cell ->
                cell.path == path || stackMembers[cell.path]?.any { it.path == path } == true
            }
            if (target >= 0) binding.recyclerView.scrollToPosition(target)
        }
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
        // 페이저와 같은 좌표계(어댑터 목록 = 펼친 목록)에서 집는다 — 분모도 실제 장수다.
        val list = galleryAdapter.currentList
        val item = list.getOrNull(binding.galleryPager.currentItem)
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
            binding.galleryPager.currentItem + 1, list.size, item.path.substringAfterLast('/')
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

    /** 정렬 상태 칩 — "크기순 ↓". 탭하면 방향이 반전된다(리스너는 onViewCreated에서 1회). */
    private fun updateSortChip() {
        val label = getString(when (viewModel.sort) {
            ImageManagerViewModel.Sort.SIZE -> R.string.image_manager_sort_size
            ImageManagerViewModel.Sort.NAME -> R.string.image_manager_sort_name
            ImageManagerViewModel.Sort.DATE -> R.string.image_manager_sort_date
        })
        val arrow = if (viewModel.sortAscending) "↑" else "↓"
        binding.sortChip.text = "$label $arrow"
    }

    /**
     * 활성 필터 칩 — 걸려 있는 축마다 칩 하나, ×로 그 자리에서 푼다(캐릭터 목록의
     * `renderFilterChips` 관행). 시트를 열어 봐야 아는 상태가 없게 한다(원칙 04) —
     * 종전에는 칩 두 그룹이 항상 펼쳐져 있어 상태는 보였지만 **줄 하나가 통째로 컨트롤**이었다.
     */
    private fun renderActiveFilterChips() {
        if (_binding == null) return
        val ctx = context ?: return
        val group = binding.activeFilterChips
        group.removeAllViews()
        updateControlsButtonLabel()
        val c = viewModel.criteria

        fun addChip(label: String, onClear: () -> Unit) {
            group.addView(com.google.android.material.chip.Chip(ctx).apply {
                text = label
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    onClear()
                    renderActiveFilterChips()
                    applyView()
                }
            })
        }

        if (c.base != com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ALL) {
            addChip(getString(baseFilterLabelRes(c.base))) {
                viewModel.criteria = viewModel.criteria
                    .copy(base = com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ALL)
            }
        }
        if (c.link != com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.ANY) {
            addChip(getString(when (c.link) {
                com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.LINKED -> R.string.image_manager_link_linked
                com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.UNLINKED -> R.string.image_manager_link_unlinked
                else -> R.string.image_manager_link_auto
            })) {
                viewModel.criteria = viewModel.criteria
                    .copy(link = com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.ANY)
            }
        }
        if (c.prune == com.novelcharacter.app.util.ImageFilterHelper.PruneFilter.CANDIDATE) {
            // 후보 수를 칩에 적는다 — 눌러 보기 전에 규모가 보인다(원칙 04). 계산 전이면
            // 이름만 남긴다(0을 적으면 "후보 없음"으로 읽혀 계산 중과 구별되지 않는다).
            val state = viewModel.pruneState.value
            val base = getString(R.string.image_manager_prune_candidate)
            val label = if (state is ImageManagerViewModel.PruneState.Ready && state.hasBasis) {
                "$base ${state.paths.size}"
            } else {
                base
            }
            addChip(label) { viewModel.setPruneFilter(false) }
        }
        if (c.tagPresence == com.novelcharacter.app.util.ImageFilterHelper.TagFilter.UNTAGGED) {
            addChip(getString(R.string.image_manager_tag_filter_untagged)) {
                viewModel.criteria = viewModel.criteria
                    .copy(tagPresence = com.novelcharacter.app.util.ImageFilterHelper.TagFilter.ANY)
            }
        }
        for (tag in c.tags) {
            addChip("#$tag") {
                viewModel.criteria = viewModel.criteria.copy(tags = viewModel.criteria.tags - tag)
            }
        }
        if (viewModel.groupView) {
            addChip(getString(R.string.image_manager_group_view_chip)) { viewModel.groupView = false }
        }
    }

    private fun baseFilterLabelRes(base: com.novelcharacter.app.util.ImageFilterHelper.BaseFilter): Int = when (base) {
        com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.CHARACTER -> R.string.image_manager_filter_character
        com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.NOVEL -> R.string.image_manager_filter_novel
        com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.UNIVERSE -> R.string.image_manager_filter_universe
        com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.UNASSIGNED -> R.string.image_manager_filter_unassigned
        com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.DETACHED -> R.string.image_manager_filter_detached
        com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ORPHAN -> R.string.image_manager_filter_orphan
        com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.TRASH -> R.string.image_manager_filter_trash
        com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ALL -> R.string.image_manager_filter_all
    }

    /** 컨트롤 버튼 라벨 — 활성 필터·묶어 보기 수를 적는다("정렬·필터 · N"). 정렬은 늘 있어 세지 않는다. */
    private fun updateControlsButtonLabel() {
        if (_binding == null) return
        val c = viewModel.criteria
        var n = c.tags.size
        if (c.base != com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ALL) n++
        if (c.link != com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.ANY) n++
        if (c.prune == com.novelcharacter.app.util.ImageFilterHelper.PruneFilter.CANDIDATE) n++
        if (c.tagPresence == com.novelcharacter.app.util.ImageFilterHelper.TagFilter.UNTAGGED) n++
        if (viewModel.groupView) n++
        binding.controlsButton.text =
            if (n > 0) getString(R.string.controls_button_count, n) else getString(R.string.controls_button)
    }

    /** 통합 컨트롤 시트 — 정렬(기준+방향)·묶어 보기·필터를 한 표면에서 받는다. */
    private fun openControlsSheet() {
        val sheet = ImageManagerControlsBottomSheet()
        sheet.currentCriteria = viewModel.criteria
        sheet.currentSort = viewModel.sort
        sheet.currentAscending = viewModel.sortAscending
        sheet.currentGroupView = viewModel.groupView
        sheet.loadAllTags = { viewModel.getAllImageTags() }
        sheet.onApply = { criteria, sort, ascending, groupView ->
            val wasPrune = viewModel.criteria.prune ==
                com.novelcharacter.app.util.ImageFilterHelper.PruneFilter.CANDIDATE
            val wantPrune = criteria.prune ==
                com.novelcharacter.app.util.ImageFilterHelper.PruneFilter.CANDIDATE
            // 검색어는 시트가 손대지 않는 축이라 **지금 값**을 쓴다 — 시트가 열릴 때의
            // 스냅샷을 그대로 실으면, 시트가 떠 있는 사이 디바운스로 확정된 검색어가
            // 적용 한 번에 옛값으로 되돌아간다(검색칸 글자는 그대로인 채 목록만 넓어진다).
            viewModel.criteria = criteria.copy(query = viewModel.criteria.query)
            viewModel.sort = sort
            viewModel.sortAscending = ascending
            viewModel.groupView = groupView
            // 켬/끔이 갈릴 때만 계산을 걸거나 끊는다 — 이미 켜져 있던 후보 계산은 그대로 산다.
            if (wasPrune != wantPrune) viewModel.setPruneFilter(wantPrune)
            if (_binding != null) {
                updateSortChip()
                renderActiveFilterChips()
                applyView()
            }
        }
        sheet.onClearFilters = {
            val wasPrune = viewModel.criteria.prune ==
                com.novelcharacter.app.util.ImageFilterHelper.PruneFilter.CANDIDATE
            viewModel.criteria = viewModel.criteria.copy(
                base = com.novelcharacter.app.util.ImageFilterHelper.BaseFilter.ALL,
                link = com.novelcharacter.app.util.ImageFilterHelper.LinkFilter.ANY,
                tags = emptySet(),
                tagPresence = com.novelcharacter.app.util.ImageFilterHelper.TagFilter.ANY,
                prune = com.novelcharacter.app.util.ImageFilterHelper.PruneFilter.ANY
            )
            if (wasPrune) viewModel.setPruneFilter(false)
            if (_binding != null) {
                renderActiveFilterChips()
                applyView()
            }
        }
        sheet.show(childFragmentManager, ImageManagerControlsBottomSheet.TAG)
    }

    /** 걸러낼 후보의 정규 경로 — 계산 전·꺼짐이면 빈 집합이다(아무도 후보가 아니다). */
    private val pruneCandidatePaths: Set<String>
        get() = (viewModel.pruneState.value as? ImageManagerViewModel.PruneState.Ready)?.paths.orEmpty()

    /** 현재 필터·검색·정렬을 적용해 어댑터에 반영(매칭은 ImageFilterHelper 단일 소스). */
    private fun applyView() {
        val all = viewModel.images.value ?: emptyList()
        // 람다 밖에서 한 번 집는다 — 항목마다 LiveData를 되짚지 않는다.
        val pruneCandidates = pruneCandidatePaths
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
                detachedAt = item.meta?.detachedAt,
                // 계산은 VM이 이미 해 뒀다 — 여기서는 명단 조회 하나다. **정규 경로도 VM이
                // 들고 온 것을 쓴다**(`ManagedImage.canonicalPath`) — 여기서 다시 정규화하면
                // 파일 시스템 호출이 **항목 전부에** 붙는다: `ImageFilterHelper.apply`는
                // 필터가 하나라도 걸려 있으면 `Facts`를 전량 조립하므로 단축 평가가 없고,
                // 그러면 검색어 한 글자마다 이미지 수만큼의 호출이 메인 스레드에 선다.
                pruneCandidate = pruneCandidates.contains(item.canonicalPath)
            )
        }
        // 방향은 사용자가 정한다 — 기준마다의 기본 방향은 컨트롤 시트가 제안한다.
        val comparator = when (viewModel.sort) {
            ImageManagerViewModel.Sort.SIZE -> compareBy<ImageManagerViewModel.ManagedImage> { it.sizeBytes }
            ImageManagerViewModel.Sort.NAME -> compareBy { it.path.substringAfterLast('/') }
            ImageManagerViewModel.Sort.DATE -> compareBy { it.lastModified }
        }
        val sorted = filtered.sortedWith(
            if (viewModel.sortAscending) comparator else comparator.reversed()
        )
        expandedItems = sorted
        // 묶어 보기 — 링크 묶음을 대표 한 칸으로 접는다. 접기 규칙은 LinkGroupFold가
        // 단일 소스다(라이브러리 피커와 같은 규칙 — 화면마다 다르게 접으면 같은 묶음이
        // 다른 대표로 보인다). 대표는 현재 정렬의 첫 장이고, 개수는 화면에 보이는 식구 수다.
        val display: List<ImageManagerViewModel.ManagedImage>
        if (viewModel.groupView) {
            val stacks = com.novelcharacter.app.util.LinkGroupFold.fold(sorted) { it.meta?.linkGroupId }
            stackMembers = stacks.filter { it.size > 1 }
                .associate { st -> st.representative.path to st.members }
            display = stacks.map { st ->
                if (st.size > 1) st.representative.copy(stackCount = st.size) else st.representative
            }
        } else {
            stackMembers = emptyMap()
            display = sorted
        }
        currentList = display
        // 선택은 현재 뷰(필터·정렬 적용) 기준으로 유지 — 필터 전환 시 화면 밖(안 보이는) 선택은 자동 해제한다.
        // 일괄 삭제/재압축이 사용자가 보지 않는 항목에 작용하지 않도록(변수 제어). 삭제로 사라진 항목도 함께 정리됨.
        // 묶어 보기에서는 **펼친 식구 전체가 '보이는 것'이다** — 접힌 칸이 곧 그 식구들이다.
        val visiblePaths = expandedItems.mapTo(HashSet()) { it.path }
        val selectionChanged = selectedPaths.retainAll(visiblePaths)
        // 묶어 보기 전환은 선택 집합이 그대로여도 칸의 표시 좌표(대표 경로)를 바꾼다 — 다시 그린다.
        if (selectionChanged || selectionMode) updateSelectionUi()
        adapter.submitList(display)
        // 갤러리 페이저는 **펼친 목록**을 소비한다(갤러리 모드에서만 공급 — 그리드 모드의
        // 이중 diff 비용 제거 + 분리된 어댑터에 헛공급 방지. 커밋 후 위치는 path 우선 동기화).
        // 접힌 목록을 먹이면 ⓐ 대표 밖 식구가 어느 페이지에도 없는데 화면 어디에도 그 사실이
        // 없고(원칙 04) ⓑ 정렬이 바뀌어 대표가 갈리면 보던 장의 path가 목록에서 사라져
        // 자리 추적이 엉뚱한 칸을 새 추적 대상으로 덮는다. 갤러리는 '한 장씩 보기'라
        // 접지 않는 것이 그 이름값이다 — 접는 것은 그리드·피커의 몫이다.
        if (viewModel.viewMode == ImageManagerViewModel.ViewMode.GALLERY) {
            attachGalleryAdapter()
            galleryAdapter.submitList(expandedItems) {
                syncGalleryPager()
            }
        }
        val empty = display.isEmpty() && viewModel.loading.value != true
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) binding.emptyText.text = emptyMessage()
    }

    /**
     * 빈 화면이 **왜 비었는지** 말한다 (원칙 02 — 빈 화면은 고장과 구별되지 않는다).
     *
     * 걸러낼 후보 칩이 켜져 있을 때 할 말이 셋으로 갈린다: 계산 중 · 기준 축이 없다 ·
     * 기준은 있는데 조건에 맞는 그림이 없다. 앞의 둘은 **사용자가 할 일이 다르다**
     * (기다리기 vs 대결 축에서 기준 지정하기)라 한 문구로 뭉칠 수 없다.
     */
    private fun emptyMessage(): CharSequence {
        val prune = viewModel.pruneState.value
        if (viewModel.criteria.prune != com.novelcharacter.app.util.ImageFilterHelper.PruneFilter.CANDIDATE) {
            return getString(R.string.image_manager_empty)
        }
        return when (prune) {
            is ImageManagerViewModel.PruneState.Loading -> getString(R.string.image_manager_prune_loading)
            is ImageManagerViewModel.PruneState.Ready -> when {
                !prune.hasBasis -> getString(R.string.image_manager_prune_no_basis)
                // **후보가 있는데도 화면이 비었으면 원인은 이 칩이 아니다** — 검색어나 다른
                // 칩이 좁힌 것이다. 그때 기준값을 탓하면 사용자가 엉뚱한 설정을 고치러 간다.
                prune.paths.isNotEmpty() -> getString(R.string.image_manager_empty)
                else -> {
                    val options = com.novelcharacter.app.util.DuelImageBasisPrefs.pruneOptions(requireContext())
                    getString(R.string.image_manager_prune_none, options.percent, options.played)
                }
            }
            else -> getString(R.string.image_manager_empty)
        }
    }

    // ---------- 선택 모드 ----------

    /**
     * 이 항목이 접힌 칸이면 그 식구들, 아니면 null.
     *
     * **갤러리에서는 언제나 null이다** — 갤러리는 접지 않고 전 장을 넘기므로(`applyView`)
     * 거기서 연 상세는 *지금 보는 그 장*의 것이다. 접기 지도는 **그리드의 좌표계**라
     * 갤러리에서 그대로 읽으면 대표 페이지에서만 범위가 묶음으로 튀고, 똑같이 생긴 옆
     * 페이지와 동작이 갈리는데 **그 사실이 화면 어디에도 없다**(콜드 검토가 잡은 자리).
     */
    private fun stackMembersOf(
        item: ImageManagerViewModel.ManagedImage
    ): List<ImageManagerViewModel.ManagedImage>? =
        if (viewModel.viewMode == ImageManagerViewModel.ViewMode.GALLERY) null
        else stackMembers[item.path]

    /** 이 칸이 대표하는 경로들 — 접힌 칸이면 식구 전체, 아니면 그 한 장. */
    private fun pathsOf(item: ImageManagerViewModel.ManagedImage): List<String> =
        stackMembersOf(item)?.map { it.path } ?: listOf(item.path)

    private fun enterSelection(initial: ImageManagerViewModel.ManagedImage?) {
        // 선택 모드는 그리드 전용 — 갤러리에서 진입하면 그리드로 복귀 후 시작
        if (viewModel.viewMode == ImageManagerViewModel.ViewMode.GALLERY) switchToGrid()
        selectionMode = true
        if (initial != null) selectedPaths.addAll(pathsOf(initial))
        updateSelectionUi()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedPaths.clear()
        updateSelectionUi()
    }

    private fun toggleSelect(item: ImageManagerViewModel.ManagedImage) {
        // 접힌 칸의 탭은 묶음 전체를 토글한다 — 화면의 한 칸이 곧 그 식구들이다.
        // 개수 표시는 경로 수를 세므로 사용자는 실제로 몇 장이 걸렸는지 그대로 본다.
        val paths = pathsOf(item)
        if (selectedPaths.containsAll(paths)) {
            selectedPaths.removeAll(paths)
        } else {
            selectedPaths.addAll(paths)
        }
        updateSelectionUi()
    }

    private fun selectAll() {
        // 전체선택의 '전체'는 펼친 목록이다 — 접힌 칸만 세면 식구가 조용히 빠진다.
        val allSelected = expandedItems.isNotEmpty() &&
            selectedPaths.containsAll(expandedItems.map { it.path })
        if (allSelected) {
            expandedItems.forEach { selectedPaths.remove(it.path) }
        } else {
            expandedItems.forEach { selectedPaths.add(it.path) }
        }
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        binding.selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.selectButton.text = getString(
            if (selectionMode) R.string.image_manager_select_cancel else R.string.image_manager_select
        )
        binding.selectionCountText.text = getString(R.string.image_manager_selected_count, selectedPaths.size)
        adapter.setSelectionState(selectionMode, displaySelectedPaths())
    }

    /**
     * 셀 표시용 선택 집합 — 접힌 칸은 **식구 중 하나라도** 선택돼 있으면 표시가 선다.
     *
     * 일반 모드에서 일부 식구만 고른 채 묶어 보기를 켜는 경우가 있다(선택은 뷰 전환을
     * 살아남는다). 그때 대표 경로만 보고 그리면 **표시 없는 칸의 식구가 일괄 작업에
     * 걸리는데 화면 어디에도 그 사실이 없다** — 개수 줄만으로는 어느 칸인지 모른다.
     */
    private fun displaySelectedPaths(): Set<String> {
        if (stackMembers.isEmpty()) return selectedPaths.toSet()
        val out = HashSet(selectedPaths)
        for ((rep, members) in stackMembers) {
            if (members.any { it.path in selectedPaths }) out.add(rep)
        }
        return out
    }

    /**
     * 선택된 경로에 해당하는 **현재 뷰**의 이미지 항목들 — 일괄 작업은 화면에 보이는 대상에만
     * 작용. 묶어 보기에서는 펼친 목록이 그 좌표계다(접힌 칸 하나 = 식구 전체가 선택돼 있다).
     */
    private fun selectedItems(): List<ImageManagerViewModel.ManagedImage> {
        return expandedItems.filter { selectedPaths.contains(it.path) }
    }

    // ---------- 옵션 ----------

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

        // 걸러낼 후보라면 **왜 후보인지** 말한다 (B-104 소비처 ⓒ · 원칙 02).
        // 이 창의 다음 동작이 삭제라, 근거 없이 목록에만 올려 두면 앱이 지어낸 서열이 된다.
        val candidate = (viewModel.pruneState.value as? ImageManagerViewModel.PruneState.Ready)
            ?.byPath?.get(item.canonicalPath)
        if (candidate != null) {
            sheetBinding.detailPruneReasonText.text = getString(
                R.string.image_manager_prune_reason,
                candidate.scored, candidate.rank, candidate.played
            )
            sheetBinding.detailPruneReasonText.visibility = View.VISIBLE
        } else {
            sheetBinding.detailPruneReasonText.visibility = View.GONE
        }

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

        // AI 태그 추천 — 긴 누름·선택 모드 없이 한 장에서 바로 연다. 쓸 수 있는 프로바이더가
        // 있을 때만 보인다(R-24 — 일괄 작업 시트의 AI_TAG와 같은 판정). 접힌 칸이면 묶음
        // 전체가 대상이라, 흐름 안에서 묶음 단위 전송 옵션이 함께 선다.
        val aiUsable = runCatching {
            com.novelcharacter.app.ai.AiService(ctx).hasUsableProvider()
        }.getOrDefault(false)
        sheetBinding.detailAiTagButton.visibility = if (aiUsable) View.VISIBLE else View.GONE
        sheetBinding.detailAiTagButton.setOnClickListener {
            dialog.dismiss()
            openAiTagFlow(pathsOf(item))
        }

        // 접힌 칸의 상세는 범위가 둘로 갈린다 — AI 태그·전체화면은 **묶음 전체**, 나머지
        // (태그 편집·재압축·삭제·배정)는 **이 한 장**이다. 묶음 범위인 둘만 라벨에 장수를
        // 적어 그 대비로 범위를 드러낸다(적지 않으면 사용자는 태그 편집도 묶음 전체라 믿는다).
        val stackSize = pathsOf(item).size
        if (stackSize > 1) {
            sheetBinding.detailAiTagButton.text =
                getString(R.string.image_ai_tag_action_stack, stackSize)
            sheetBinding.detailFullScreenButton.text =
                getString(R.string.image_manager_view_full_stack, stackSize)
        }

        // 링크 묶음 정보 + 해제 — 링크된 이미지에만 노출. N = 현재 목록에서 같은 묶음 수.
        // 캐릭터 자동 링크 묶음은 수동 링크와 구별해 표기한다(자동 관리 상태의 가시화 — 원칙 04).
        val groupId = item.meta?.linkGroupId
        if (groupId != null) {
            val groupSize = (viewModel.images.value ?: emptyList()).count { it.meta?.linkGroupId == groupId }
            sheetBinding.detailLinkInfoText.visibility = View.VISIBLE
            // **두 수가 갈리면 그 자리에서 말한다.** 이 줄은 필터를 무시한 묶음 전체를 세고,
            // 바로 위 단추들의 장수는 화면에 보이는 식구를 센다(그 둘에만 작용하므로) —
            // 필터가 식구를 가리면 한 창에 다른 두 수가 서고, 정박이 없으면 사용자는
            // AI 태그가 어느 쪽에 붙는지 판단할 수 없다(문구 가이드 4-4).
            sheetBinding.detailLinkInfoText.text = buildString {
                append(getString(
                    if (com.novelcharacter.app.util.AutoLinkPlanner.isAutoToken(groupId)) {
                        R.string.image_link_group_info_auto
                    } else {
                        R.string.image_link_group_info
                    },
                    groupSize
                ))
                val visible = stackMembersOf(item)?.size
                if (visible != null && visible < groupSize) {
                    append(getString(R.string.image_link_group_info_visible, visible))
                }
            }
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
        // 접힌 칸이면 묶음 전체를 한 뷰어로 — 스와이프로 식구들을 넘겨 본다(묶어 보기의 '보기' 경로).
        val json = gson.toJson(pathsOf(item))
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
                val cancelled = AtomicBoolean(false)   // 메인이 쓰고 IO가 읽는다 — 평범한 var는 happens-before가 없어 취소가 안 보일 수 있다
                val progress = showTaskProgress(
                    R.string.image_manager_delete_title, items.size, R.string.image_manager_stage_delete
                ) { cancelled.set(true) }
                viewModel.deleteImages(
                    items,
                    onProgress = { done, total -> postProgress(progress, done, total) },
                    isCancelled = { cancelled.get() }
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
                    } else if (cancelled.get()) {
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
        val cancelled = AtomicBoolean(false)   // 메인이 쓰고 IO가 읽는다 — 평범한 var는 happens-before가 없어 취소가 안 보일 수 있다
        val progress = showTaskProgress(
            R.string.image_manager_recompress_confirm_title,
            items.size,
            R.string.image_manager_stage_recompress_prepare
        ) { cancelled.set(true) }
        viewModel.prepareRecompress(
            items,
            onProgress = { done, total -> postProgress(progress, done, total) },
            isCancelled = { cancelled.get() }
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
     *
     * **모델이 그림을 안 받는다고 이미 학습했으면 그것도 여기서 말한다** (B-157) —
     * 실행기는 그 경우 요청조차 만들지 않으므로, 말하지 않으면 사용자는 눌러 놓고
     * *"아무것도 안 나왔다"*만 본다. 짧은 값·서술형 경로가 첨부 자리에서 미리 고지하는
     * 그 규칙과 같은 자리다(A-7 — `AiImageAttachRow`가 같은 가드를 같은 모양으로 읽는다).
     * **막지는 않는다** — 학습값은 모델·주소를 바꾸면 함께 버려지므로(R-23) 사용자가 그
     * 사이에 설정을 고쳤을 수 있고, 그때 눌러 보는 것이 유일한 재확인 경로다.
     */
    private fun openAiTagFlow(paths: List<String>) {
        if (paths.isEmpty()) return
        val ctx = requireContext()
        val settings = com.novelcharacter.app.ai.AiPromptSettings(ctx)
        var perRequest = settings.imageTagBatchSize
        var perGroup = settings.imageTagGroupSampleSize
        var groupUnit = settings.imageTagGroupUnit

        // 링크 묶음 단위 전송 — 대상에 2장 이상 보이는 묶음이 있을 때만 선다
        // (R-24 — 성립하지 않는 조합의 설정은 보이지 않는다). 표본·전개 규칙은
        // LinkGroupFold가 단일 소스이고, 여기서는 장수 계산과 고지에만 쓴다.
        val groupIds = viewModel.linkGroupIds(paths)
        fun samplePlan(per: Int) = com.novelcharacter.app.util.LinkGroupFold
            .sampleForAi(paths, { groupIds[it] }, per)
        val hasGroups = samplePlan(1).sampledGroups > 0
        fun sendCount(): Int =
            if (hasGroups && groupUnit) samplePlan(perGroup).sendPaths.size else paths.size

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
        val groupSwitch = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
            text = getString(R.string.image_ai_tag_group_unit)
            isChecked = groupUnit
        }
        val groupDesc = android.widget.TextView(ctx).apply {
            text = getString(R.string.image_ai_tag_group_unit_desc)
            textSize = 12f
        }
        val groupSampleLabel = android.widget.TextView(ctx)
        val groupSlider = com.google.android.material.slider.Slider(ctx).apply {
            valueFrom = com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TAG_GROUP_SAMPLE_MIN.toFloat()
            valueTo = com.novelcharacter.app.ai.AiPromptPolicy.IMAGE_TAG_GROUP_SAMPLE_MAX.toFloat()
            stepSize = 1f
            value = perGroup.toFloat()
        }
        val groupNote = android.widget.TextView(ctx).apply { textSize = 12f }
        val costLabel = android.widget.TextView(ctx).apply { textSize = 12f }

        fun refresh() {
            val send = sendCount()
            val requests = com.novelcharacter.app.ai.AiPromptPolicy
                .imageTagBatchRequestCount(send, perRequest)
            countLabel.text = getString(R.string.image_ai_tag_batch_size, perRequest)
            // **나눌 것이 없으면 장수 슬라이더도 없다** (R-24). 문지기는 고른 장수가 아니라
            // **실제로 보낼 장수**다 — 묶음 단위를 켜면 표본이 1장으로 줄 수 있고, 그때
            // 이 슬라이더는 요청 수·비용·동작 무엇도 바꾸지 못한다(요청은 언제나 하나다).
            // 판정이 살아 움직여야 하는 것도 그래서다: 스위치 한 번에 send가 갈리므로
            // 창을 만들 때 한 번 재면 아무 일도 안 하는 슬라이더가 그대로 남는다.
            val batchVis = if (send > 1) android.view.View.VISIBLE else android.view.View.GONE
            countLabel.visibility = batchVis
            slider.visibility = batchVis
            // 비용 고지는 **실제로 보낼 장수**로 센다 — 묶음 단위가 켜지면 표본 수가 곧 비용이다.
            costLabel.text = if (send == 1) {
                getString(R.string.image_ai_tag_cost_single)
            } else {
                getString(R.string.image_ai_tag_cost, send, perRequest, requests)
            }
            if (hasGroups) {
                val plan = samplePlan(perGroup)
                groupSampleLabel.text = getString(R.string.image_ai_tag_group_sample, perGroup)
                groupNote.text = getString(
                    R.string.image_ai_tag_group_note,
                    plan.sampledGroups, plan.expandedTotal
                )
                val vis = if (groupUnit) android.view.View.VISIBLE else android.view.View.GONE
                groupSampleLabel.visibility = vis
                groupSlider.visibility = vis
                groupNote.visibility = vis
            }
        }
        slider.addOnChangeListener { _, value, _ ->
            perRequest = value.toInt()
            refresh()
        }
        groupSwitch.setOnCheckedChangeListener { _, checked ->
            groupUnit = checked
            refresh()
        }
        groupSlider.addOnChangeListener { _, value, _ ->
            perGroup = value.toInt()
            refresh()
        }
        refresh()

        // 장수 슬라이더는 늘 담고 **보임만 refresh가 정한다** — 묶음 스위치가 보낼 장수를
        // 바꾸므로 담을지 말지를 여기서 정하면 그 전환을 따라가지 못한다.
        container.addView(countLabel)
        container.addView(slider)
        if (hasGroups) {
            container.addView(groupSwitch)
            container.addView(groupDesc)
            container.addView(groupSampleLabel)
            container.addView(groupSlider)
            container.addView(groupNote)
        }
        container.addView(costLabel)
        container.addView(android.widget.TextView(ctx).apply {
            text = getString(R.string.image_ai_tag_privacy)
            textSize = 12f
        })
        // 이미 배운 거부는 **누르기 전에** 말한다 (B-157). 붙이는 자리를 갈라 두지 않는 것은
        // 비용 고지와 같은 창에서 함께 읽혀야 판단이 서기 때문이다("얼마 드는데 헛돈이다").
        if (com.novelcharacter.app.ai.AiService(ctx).isImagesUnsupported()) {
            container.addView(android.widget.TextView(ctx).apply {
                text = getString(R.string.image_ai_tag_no_vision_upfront)
                textSize = 12f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorError))
            })
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.image_ai_tag_action)
            .setView(com.novelcharacter.app.util.cappedScrollView(ctx).apply { addView(container) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.image_ai_tag_run) { _, _ ->
                settings.imageTagBatchSize = perRequest
                if (hasGroups) {
                    settings.imageTagGroupUnit = groupUnit
                    settings.imageTagGroupSampleSize = perGroup
                }
                if (hasGroups && groupUnit) {
                    // 묶음 단위 — 표본만 보내고, 전개 표(표본 → 묶음 전원)를 실행과 함께 든다.
                    // 태그는 적용 시점에 그 표로 전원에 붙는다(ViewModel.applyImageTags).
                    val plan = samplePlan(perGroup)
                    runAiTagSuggest(plan.sendPaths, perRequest, groupExpand = plan.membersBySentPath)
                } else {
                    runAiTagSuggest(paths, perRequest)
                }
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
        carryOver: com.novelcharacter.app.ai.ImageBatchTagSuggester.Result? = null,
        groupExpand: Map<String, List<String>> = emptyMap()
    ) {
        if (!viewModel.runImageTagSuggest(paths, perRequest, carryOver, groupExpand)) {
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
        // 묶음 단위 실행의 고지 — 체크한 태그가 **화면에 없는 장에도 붙는다**는 사실을
        // 적용 전에 말한다(변수 제어). 행마다의 장수는 시트가 파일명 옆에 함께 적는다.
        // **제안이 있어야만 싣는다** — 제안 0건이면 붙일 것이 없어 이 고지도 뜻이 없고,
        // 실었다가는 아래 '검토할 것 없음' 갈래(B-144)를 이 줄이 막아 빈 시트가 선다.
        // **묶음 수는 표의 줄 수가 아니다** — 표본을 2장으로 두면 같은 묶음이 두 줄로 사는데,
        // 그대로 세면 묶음도 장수도 배로 부풀어 고지가 거짓말을 한다(셈은 ViewModel이 접는다).
        val groupSizes = viewModel.aiTagGroupSizes()
        if (groupSizes.isNotEmpty() && result.suggestions.isNotEmpty()) {
            val (groups, total) = viewModel.aiTagGroupNoticeStats()
            notices.add(getString(R.string.image_ai_tag_group_notice, groups, total))
        }
        // 프로바이더 자동 전환 고지 (B-108 확정 ⓑ) — 실패가 아니므로 실패 요약보다 앞에 둔다.
        notices.addAll(result.notes)
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
            sheet.rebind(result.suggestions, notices, retryPaths, groupSizes)
        } else {
            sheet.suggestions = result.suggestions
            sheet.notices = notices
            sheet.retryPaths = retryPaths
            sheet.groupSizeByPath = groupSizes
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
        // **여기서 이력을 남기지 않는다** — 화면이 이미 떨어져 나갔을 수 있고, 그때 이 자리가
        // 문지기면 유료 응답이 걸린 실패가 어디에도 안 남는다(B-164). 문장·이력은 ViewModel이
        // 들고, 이 람다는 *"화면이 알렸는가"*만 돌려준다(못 알렸으면 그쪽이 토스트로 대신한다).
        sheet.onApply = { picked ->
            viewModel.applyImageTags(picked) { result -> notifyResult(result) }
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

/*
 * 태그 적용 결과의 문장 조립은 **`ImageManagerViewModel.finishTagApply`로 내려갔다** (B-164).
 *
 * 여기(뷰 확장함수)에 있는 동안에는 **이력을 남길지 말지가 화면의 부착 상태에 매여** 있었다 —
 * 적용 직후 탭을 떠나면 `if (!isAdded) return`이 고지와 함께 기록까지 막아, 하필 유료 응답이
 * 걸린 실패가 어디에도 남지 않았다. 한 벌로 두는 이유(이미지판·폴더판이 같은 문장을 쓴다)는
 * 그대로이고, 그 한 벌의 자리만 뷰 밖으로 옮겼다.
 */
