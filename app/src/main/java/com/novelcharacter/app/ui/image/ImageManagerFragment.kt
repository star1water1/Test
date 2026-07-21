package com.novelcharacter.app.ui.image

import android.os.Bundle
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ImageManagerAdapter(
            viewLifecycleOwner.lifecycleScope,
            onClick = { showDetail(it) },
            onToggleSelect = { toggleSelect(it) },
            onLongPress = { enterSelection(it) }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter

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
        if (c.query.isNotBlank()) binding.searchEdit.setText(c.query)
        updateTagFilterLabel()
    }

    private fun updateTagFilterLabel() {
        val n = viewModel.criteria.tags.size
        binding.tagFilterButton.text =
            if (n == 0) getString(R.string.image_manager_tag_filter)
            else getString(R.string.image_manager_tag_filter_count, n)
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
                }
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
        val empty = sorted.isEmpty() && viewModel.loading.value != true
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ---------- 선택 모드 ----------

    private fun enterSelection(initial: ImageManagerViewModel.ManagedImage?) {
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
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                0 -> ImageSettingsDialog.show(this) { viewModel.load() }
                1 -> confirmCleanOrphans()
                else -> viewModel.load()
            }
            true
        }
        popup.show()
    }

    // ---------- 상세(바텀시트) ----------

    private fun showDetail(item: ImageManagerViewModel.ManagedImage) {
        val ctx = context ?: return
        val sheetBinding = BottomSheetImageDetailBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(ctx)
        dialog.setContentView(sheetBinding.root)

        sheetBinding.detailSizeText.text = StorageAnalyzer.formatBytes(item.sizeBytes)
        sheetBinding.detailOwnerText.text = if (item.owners.isEmpty()) {
            when (item.status) {
                ImageManagerViewModel.Status.TRASH_HELD -> getString(R.string.image_manager_owner_trash)
                ImageManagerViewModel.Status.UNASSIGNED -> getString(R.string.image_manager_owner_unassigned)
                else -> getString(R.string.image_manager_owner_orphan)
            }
        } else {
            item.owners.joinToString("\n") { "${typeLabel(it.type)} · ${it.name}" }
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
            val sheet = ImageTagEditBottomSheet()
            sheet.currentTags = tags
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

        // 링크 그룹 정보 + 해제 — 링크된 이미지에만 노출. N = 현재 목록에서 같은 그룹 수.
        val groupId = item.meta?.linkGroupId
        if (groupId != null) {
            val groupSize = (viewModel.images.value ?: emptyList()).count { it.meta?.linkGroupId == groupId }
            sheetBinding.detailLinkInfoText.visibility = View.VISIBLE
            sheetBinding.detailLinkInfoText.text = getString(R.string.image_link_group_info, groupSize)
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
        AlertDialog.Builder(ctx)
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
        AlertDialog.Builder(ctx)
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
        AlertDialog.Builder(ctx)
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

    private fun deleteSelected() {
        val ctx = context ?: return
        val items = selectedItems()
        if (items.isEmpty()) {
            reportAndNotify(OpResult.failure(OpResult.CAT_MAINTENANCE, getString(R.string.image_manager_select_none)))
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.image_manager_delete_title)
            .setMessage(getString(R.string.image_manager_delete_selected_confirm, items.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteImages(items) { result ->
                    // isAdded는 onDestroyView 후에도 true라 회전/백스택 중 콜백이 파괴된 뷰에 닿는다 →
                    // _binding까지 확인해야 exitSelection()의 binding!! NPE를 막는다(ViewModel이 프래그먼트 스코프라 코루틴 생존).
                    if (!isAdded || _binding == null) return@deleteImages
                    exitSelection()
                    if (result.failed > 0) {
                        reportAndNotify(OpResult.failure(
                            OpResult.CAT_MAINTENANCE,
                            getString(R.string.image_manager_bulk_delete_failed, result.failed)
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
        viewModel.prepareRecompress(items) { preview ->
            if (!isAdded) { viewModel.discardRecompress(); return@prepareRecompress }
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
            AlertDialog.Builder(ctx)
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
        AlertDialog.Builder(ctx)
            .setTitle(R.string.image_manager_recompress_confirm_title)
            .setMessage(msg)
            .setPositiveButton(R.string.image_manager_recompress) { _, _ ->
                viewModel.commitRecompress { result ->
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
                            viewModel.undoLastRecompress { ok ->
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
        picker.onPicked = { type, row ->
            val expansion = viewModel.expandWithLinkedGroups(paths)
            if (expansion.addedByLink.isNotEmpty()) {
                val ctx = context
                if (ctx != null) {
                    AlertDialog.Builder(ctx)
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

    private fun doAssign(paths: List<String>, type: ImageManagerViewModel.OwnerType, row: ImageManagerViewModel.PickRow) {
        viewModel.assignToTarget(paths, type, row.id) { result ->
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
        AlertDialog.Builder(ctx)
            .setTitle(R.string.image_unassign_action)
            .setMessage(getString(R.string.image_unassign_confirm, paths.size))
            .setPositiveButton(R.string.confirm) { _, _ -> runUnassign(paths, null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runUnassign(paths: List<String>, owners: List<ImageManagerViewModel.Owner>?) {
        viewModel.unassign(paths, owners) { result ->
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
                AlertDialog.Builder(ctx)
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
        viewModel.unlinkImages(paths) { count ->
            if (!isAdded || _binding == null) return@unlinkImages
            exitSelection()
            reportAndNotify(OpResult.success(OpResult.CAT_MAINTENANCE, getString(R.string.image_unlink_done, count)))
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
            if (remove) {
                viewModel.removeTagsFromImages(paths, tags) { count ->
                    if (!isAdded || _binding == null) return@removeTagsFromImages
                    exitSelection()
                    reportAndNotify(OpResult.success(OpResult.CAT_MAINTENANCE, getString(R.string.image_batch_tag_done, count)))
                }
            } else {
                viewModel.addTagsToImages(paths, tags) { count ->
                    if (!isAdded || _binding == null) return@addTagsToImages
                    exitSelection()
                    reportAndNotify(OpResult.success(OpResult.CAT_MAINTENANCE, getString(R.string.image_batch_tag_done, count)))
                }
            }
        }
        sheet.show(childFragmentManager, ImageBatchTagBottomSheet.TAG)
    }

    // ---------- 고아 정리 ----------

    private fun confirmCleanOrphans() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
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

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
