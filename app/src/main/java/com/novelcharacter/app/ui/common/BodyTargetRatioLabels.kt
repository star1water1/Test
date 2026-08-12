package com.novelcharacter.app.ui.common

import androidx.annotation.StringRes
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BodyAnalysisConfig.TargetRatioSource

/**
 * 목표 비율 기준의 **화면 이름 한 자리** (B-94).
 *
 * 고르는 자리(필드 설정의 기본 기준)와 보이는 자리(체형 카드의 즉석 전환 칩·기준 문장)가
 * 서로 다른 파일이라, 이름을 각자 지으면 **같은 기준이 두 이름으로 불린다.** 15판이
 * 축 라벨에서 겪은 것과 같은 부류이므로 같은 처방을 쓴다 — 이름은 여기 하나다.
 *
 * **순서도 계약이다** — 스피너의 자리 번호가 곧 이 목록의 인덱스다.
 */
object BodyTargetRatioLabels {

    /** 고를 수 있는 기준 전수. 스피너·칩이 이 순서로 선다. */
    val SOURCES: List<TargetRatioSource> = TargetRatioSource.entries.toList()

    @StringRes
    fun labelOf(source: TargetRatioSource): Int = when (source) {
        TargetRatioSource.AUTO -> R.string.body_target_ratio_source_auto
        TargetRatioSource.GENRE -> R.string.body_target_ratio_source_genre
        TargetRatioSource.IDEAL_BODY -> R.string.body_target_ratio_source_ideal_body
        TargetRatioSource.NOVEL_AVERAGE -> R.string.body_target_ratio_source_novel_average
    }

    /**
     * 칩에 쓰는 짧은 이름 — [TargetRatioSource.AUTO]의 긴 설명문은 칩에 안 들어간다.
     * 스피너는 처음 고르는 자리라 설명이 필요하고, 칩은 이미 아는 것을 되짚는 자리다.
     */
    @StringRes
    fun chipLabelOf(source: TargetRatioSource): Int =
        if (source == TargetRatioSource.AUTO) R.string.body_target_ratio_source_auto_short
        else labelOf(source)
}
