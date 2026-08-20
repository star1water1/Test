package com.novelcharacter.app.ui.image

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.novelcharacter.app.R
import com.novelcharacter.app.databinding.BottomSheetImageControlsBinding
import com.novelcharacter.app.util.ImageFilterHelper
import kotlinx.coroutines.launch

/**
 * 이미지 탭 통합 컨트롤 시트 — 정렬(기준+방향) / 묶어 보기 / 필터(소유·상태 × 링크 ×
 * 걸러낼 후보 × 태그)를 한 표면에 담는다. '적용' 한 번으로 전부 반영된다(원칙 04).
 *
 * 캐릭터 목록의 [com.novelcharacter.app.ui.character.CharacterListControlsBottomSheet]
 * 패턴을 따른다 — 종전에는 정렬이 팝업 메뉴(방향 고정), 필터가 화면 상단 칩 두 그룹,
 * 태그가 별도 시트로 **세 곳에 흩어져** 있었고 방향은 아예 고를 수 없었다.
 *
 * 검색어와 걸러낼 후보의 **계산**은 이 시트의 일이 아니다 — 검색어는 화면의 검색칸이 들고,
 * 후보 계산은 호출부가 적용 결과를 보고 켠다([ImageManagerViewModel.setPruneFilter]).
 *
 * 주입 유실 시 안전 종료(R-41-a) — 이 시트가 드는 것은 사용자의 작업물이 아니라
 * 현재 상태의 사본이라, 회전으로 콜백이 비면 닫는 쪽이 옳다(형제 시트들과 같은 처분).
 */
class ImageManagerControlsBottomSheet : BottomSheetDialogFragment() {

    var currentCriteria: ImageFilterHelper.Criteria = ImageFilterHelper.Criteria()
    var currentSort: ImageManagerViewModel.Sort = ImageManagerViewModel.Sort.SIZE
    var currentAscending: Boolean =
        ImageManagerViewModel.defaultAscending(ImageManagerViewModel.Sort.SIZE)
    var currentGroupView: Boolean = false
    var loadAllTags: (suspend () -> List<String>)? = null

    /** 적용 — (필터, 정렬 기준, 오름차순, 묶어 보기). 검색어는 [currentCriteria]의 것이 그대로 산다. */
    var onApply: ((ImageFilterHelper.Criteria, ImageManagerViewModel.Sort, Boolean, Boolean) -> Unit)? = null

    /** 필터만 초기화 — 정렬·묶어 보기는 손대지 않는다(캐릭터 목록 시트와 같은 갈래). */
    var onClearFilters: (() -> Unit)? = null

    private var _binding: BottomSheetImageControlsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImageControlsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (onApply == null) { dismissAllowingStateLoss(); return }  // 재생성으로 주입 유실 — 안전 종료

        setupSort()
        setupFilters()
        setupTags()

        binding.groupViewSwitch.isChecked = currentGroupView

        binding.btnClearFilters.setOnClickListener {
            onClearFilters?.invoke()
            dismiss()
        }
        binding.btnApply.setOnClickListener { applyAll() }
    }

    private fun setupSort() {
        binding.sortKindGroup.check(when (currentSort) {
            ImageManagerViewModel.Sort.SIZE -> binding.rbSize.id
            ImageManagerViewModel.Sort.NAME -> binding.rbName.id
            ImageManagerViewModel.Sort.DATE -> binding.rbDate.id
        })
        binding.dirToggleGroup.check(
            if (currentAscending) binding.btnDirAsc.id else binding.btnDirDesc.id
        )
        // 기준이 바뀌면 합리적 기본 방향을 제안(기준 유지 시 현재 방향 보존) — 캐릭터 목록과 동일
        binding.sortKindGroup.setOnCheckedChangeListener { _, _ ->
            val kind = selectedSort()
            val proposed =
                if (kind == currentSort) currentAscending
                else ImageManagerViewModel.defaultAscending(kind)
            binding.dirToggleGroup.check(if (proposed) binding.btnDirAsc.id else binding.btnDirDesc.id)
        }
    }

    private fun selectedSort(): ImageManagerViewModel.Sort = when (binding.sortKindGroup.checkedRadioButtonId) {
        binding.rbName.id -> ImageManagerViewModel.Sort.NAME
        binding.rbDate.id -> ImageManagerViewModel.Sort.DATE
        else -> ImageManagerViewModel.Sort.SIZE
    }

    private fun setupFilters() {
        binding.filterChips.check(when (currentCriteria.base) {
            ImageFilterHelper.BaseFilter.CHARACTER -> binding.chipCharacter.id
            ImageFilterHelper.BaseFilter.NOVEL -> binding.chipNovel.id
            ImageFilterHelper.BaseFilter.UNIVERSE -> binding.chipUniverse.id
            ImageFilterHelper.BaseFilter.UNASSIGNED -> binding.chipUnassigned.id
            ImageFilterHelper.BaseFilter.DETACHED -> binding.chipDetached.id
            ImageFilterHelper.BaseFilter.ORPHAN -> binding.chipOrphan.id
            ImageFilterHelper.BaseFilter.TRASH -> binding.chipTrash.id
            ImageFilterHelper.BaseFilter.ALL -> binding.chipAll.id
        })
        binding.linkFilterChips.check(when (currentCriteria.link) {
            ImageFilterHelper.LinkFilter.LINKED -> binding.chipLinked.id
            ImageFilterHelper.LinkFilter.UNLINKED -> binding.chipUnlinked.id
            ImageFilterHelper.LinkFilter.AUTO -> binding.chipLinkAuto.id
            ImageFilterHelper.LinkFilter.ANY -> binding.chipLinkAny.id
        })
        binding.chipPruneCandidate.isChecked =
            currentCriteria.prune == ImageFilterHelper.PruneFilter.CANDIDATE
    }

    private fun setupTags() {
        binding.untaggedCheck.isChecked =
            currentCriteria.tagPresence == ImageFilterHelper.TagFilter.UNTAGGED
        binding.untaggedCheck.setOnCheckedChangeListener { _, checked ->
            if (checked) clearTagChips()
            syncChipEnabled()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val tags = loadAllTags?.invoke() ?: emptyList()
            if (_binding == null) return@launch
            val ctx = context ?: return@launch
            if (tags.isEmpty()) {
                binding.noTagsText.visibility = View.VISIBLE
                return@launch
            }
            for (tag in tags) {
                binding.tagChipGroup.addView(Chip(ctx).apply {
                    text = tag
                    isCheckable = true
                    isChecked = tag in currentCriteria.tags
                    textSize = 13f
                    // 태그를 고르는 순간 '태그 없는 것만'은 뜻을 잃는다 — 조용히 두면
                    // 적용 결과가 0건이고 사용자는 이유를 알 수 없다.
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) binding.untaggedCheck.isChecked = false
                    }
                })
            }
            syncChipEnabled()
        }
    }

    /** 스위치가 켜져 있으면 태그 칩은 고를 수 없다 — 배타를 눈에 보이게(조용한 0건 방지). */
    private fun syncChipEnabled() {
        val locked = binding.untaggedCheck.isChecked
        for (i in 0 until binding.tagChipGroup.childCount) {
            binding.tagChipGroup.getChildAt(i).isEnabled = !locked
        }
    }

    private fun clearTagChips() {
        for (i in 0 until binding.tagChipGroup.childCount) {
            (binding.tagChipGroup.getChildAt(i) as? Chip)?.isChecked = false
        }
    }

    private fun applyAll() {
        val base = when (binding.filterChips.checkedChipId) {
            binding.chipCharacter.id -> ImageFilterHelper.BaseFilter.CHARACTER
            binding.chipNovel.id -> ImageFilterHelper.BaseFilter.NOVEL
            binding.chipUniverse.id -> ImageFilterHelper.BaseFilter.UNIVERSE
            binding.chipUnassigned.id -> ImageFilterHelper.BaseFilter.UNASSIGNED
            binding.chipDetached.id -> ImageFilterHelper.BaseFilter.DETACHED
            binding.chipOrphan.id -> ImageFilterHelper.BaseFilter.ORPHAN
            binding.chipTrash.id -> ImageFilterHelper.BaseFilter.TRASH
            else -> ImageFilterHelper.BaseFilter.ALL
        }
        val link = when (binding.linkFilterChips.checkedChipId) {
            binding.chipLinked.id -> ImageFilterHelper.LinkFilter.LINKED
            binding.chipUnlinked.id -> ImageFilterHelper.LinkFilter.UNLINKED
            binding.chipLinkAuto.id -> ImageFilterHelper.LinkFilter.AUTO
            else -> ImageFilterHelper.LinkFilter.ANY
        }
        val untagged = binding.untaggedCheck.isChecked
        val selectedTags = mutableSetOf<String>()
        for (i in 0 until binding.tagChipGroup.childCount) {
            (binding.tagChipGroup.getChildAt(i) as? Chip)?.let {
                if (it.isChecked) selectedTags.add(it.text.toString())
            }
        }
        val criteria = currentCriteria.copy(
            base = base,
            link = link,
            // 배타 규칙 — '태그 없음'이 켜져 있으면 태그 선택은 뜻이 없다(잠긴 칩 상태를 버린다).
            tags = if (untagged) emptySet() else selectedTags,
            tagPresence = if (untagged) ImageFilterHelper.TagFilter.UNTAGGED else ImageFilterHelper.TagFilter.ANY,
            prune = if (binding.chipPruneCandidate.isChecked) {
                ImageFilterHelper.PruneFilter.CANDIDATE
            } else {
                ImageFilterHelper.PruneFilter.ANY
            }
        )
        val ascending = binding.dirToggleGroup.checkedButtonId == binding.btnDirAsc.id
        onApply?.invoke(criteria, selectedSort(), ascending, binding.groupViewSwitch.isChecked)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object { const val TAG = "ImageManagerControlsBottomSheet" }
}
