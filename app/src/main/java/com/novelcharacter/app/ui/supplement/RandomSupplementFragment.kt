package com.novelcharacter.app.ui.supplement

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/**
 * 랜덤 보충 탭 — 랜덤으로 캐릭터 하나를 뽑아 미리보기/인라인 편집을 제공한다.
 * (구현 예정: 뽑기 엔진·미리보기 카드·편집 ON/OFF 상태 머신)
 */
class RandomSupplementFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext())
    }
}
