package com.novelcharacter.app.ui.namebank

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.databinding.BottomSheetNameBankBulkRegisterBinding

/**
 * 이름은행 일괄 캐릭터 등록 옵션 시트.
 * 충돌·사용됨 건수와 작품 미지정의 의미(세계관 필드 미기록)를 **실행 전** 전부 고지한다 (R-4).
 * 데이터·콜백은 프래그먼트가 프로퍼티로 주입한다 (ImageTagFilterBottomSheet 방식).
 */
class BulkRegisterBottomSheet : BottomSheetDialogFragment() {

    data class Setup(
        val count: Int,
        val novels: List<Novel>,
        val collisionsVsExisting: Int,
        val collisionsWithinSelection: Int,
        val usedCount: Int
    )

    var setup: Setup? = null
    var onConfirm: ((novelId: Long?, mapGender: Boolean, includeOriginNotes: Boolean, policy: BulkRegisterPlanner.DuplicatePolicy) -> Unit)? = null

    private var _binding: BottomSheetNameBankBulkRegisterBinding? = null
    private val binding get() = _binding!!
    private var selectedNovelId: Long? = null

    // 매핑 불가 상태(작품 없음·세계관 미연결)가 스위치를 자동 OFF했다는 표시 —
    // 매핑 가능 작품으로 복귀 시 기본 ON을 복원한다. 사용자가 활성 상태에서 수동 OFF한
    // 경우에는 세워지지 않으므로 사용자의 명시적 선택은 덮어쓰지 않는다.
    private var genderAutoDisabled = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetNameBankBulkRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val setup = this.setup
        if (setup == null || onConfirm == null) {
            // 프로세스 재생성으로 주입이 소실됨 — 재진입 안내 대신 조용한 닫기(주입식 시트 공통 계약)
            dismissAllowingStateLoss()
            return
        }

        binding.titleText.text = getString(R.string.name_bank_bulk_title, setup.count)
        binding.btnConfirm.text = getString(R.string.name_bank_bulk_confirm, setup.count)

        val names = mutableListOf(getString(R.string.batch_novel_none))
        names.addAll(setup.novels.map { it.title })
        binding.novelSpinner.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        binding.novelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedNovelId = if (position > 0) setup.novels[position - 1].id else null
                // 성별 필드는 세계관 스코프 — 작품 미지정이거나 작품이 세계관에 연결되어
                // 있지 않으면 기록할 곳이 없다 (둘 다 사전 고지 + 스위치 자동 OFF, R-4)
                val mappable = position > 0 && setup.novels[position - 1].universeId != null
                binding.noUniverseNotice.visibility = if (mappable) View.GONE else View.VISIBLE
                if (!mappable) {
                    if (binding.switchMapGender.isChecked) {
                        genderAutoDisabled = true
                        binding.switchMapGender.isChecked = false
                    }
                    binding.switchMapGender.isEnabled = false
                } else {
                    binding.switchMapGender.isEnabled = true
                    if (genderAutoDisabled) {
                        binding.switchMapGender.isChecked = true
                        genderAutoDisabled = false
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (setup.collisionsVsExisting > 0 || setup.collisionsWithinSelection > 0) {
            binding.collisionNotice.visibility = View.VISIBLE
            binding.collisionNotice.text = getString(
                R.string.name_bank_bulk_collision_notice,
                setup.collisionsVsExisting, setup.collisionsWithinSelection
            )
        }
        if (setup.usedCount > 0) {
            binding.usedNotice.visibility = View.VISIBLE
            binding.usedNotice.text = getString(R.string.name_bank_bulk_used_notice, setup.usedCount)
        }

        binding.btnConfirm.setOnClickListener {
            // 연타 가드 — 이중 onConfirm은 이중 등록으로 직결된다 (bulkRegister는 NonCancellable)
            binding.btnConfirm.isEnabled = false
            val policy = if (binding.policySkipDuplicates.isChecked) {
                BulkRegisterPlanner.DuplicatePolicy.SKIP_DUPLICATES
            } else {
                BulkRegisterPlanner.DuplicatePolicy.REGISTER_ALL
            }
            onConfirm?.invoke(
                selectedNovelId,
                binding.switchMapGender.isChecked,
                binding.switchIncludeNotes.isChecked,
                policy
            )
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BulkRegisterBottomSheet"
    }
}
