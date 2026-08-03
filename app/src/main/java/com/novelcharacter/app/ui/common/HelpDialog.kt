package com.novelcharacter.app.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldDescription

/**
 * 도움말·설명 표시 다이얼로그의 공용 껍데기 (제목 + 본문).
 *
 * 진입은 둘로 나뉜다 — **컴포넌트는 공유하되 출처가 다르므로 아이콘을 나눈다**:
 * - `ⓘ` → [showFieldNote]: **필드 설명** — 사용자가 쓴 글(`FieldDefinition.config`의
 *   `description`). P-A(A-2)가 만들었다.
 * - `?` → [showHelp]: **앱 도움말** — 개발자 작성, `strings.xml`의 `help_*`.
 *   본문은 `docs/text_style_guide_2026-07.md` 9-2 초안의 승인분만 싣는다(P3 게이트).
 */
object HelpDialog {

    /**
     * 도움말 주제(helpKey) — 문자열 키 대신 enum이라 리소스 누락이 컴파일에서 잡힌다.
     * 새 주제는 본문 승인 후에만 등재한다(가이드 9-2가 원문의 단일 소스).
     */
    enum class Topic(@StringRes val titleRes: Int, @StringRes val bodyRes: Int) {
        FIELD_KEY(R.string.help_field_key_title, R.string.help_field_key_body),
        DISPLAY_FORMAT(R.string.help_display_format_title, R.string.help_display_format_body),
        NARRATIVE_MODE(R.string.help_narrative_mode_title, R.string.help_narrative_mode_body),
        SEMANTIC_ROLE(R.string.help_semantic_role_title, R.string.help_semantic_role_body),
        STRUCTURED_INPUT(R.string.help_structured_input_title, R.string.help_structured_input_body),
        PERCENTILE(R.string.help_percentile_title, R.string.help_percentile_body),
        RANDOM_GENERATION(R.string.help_random_title, R.string.help_random_body),
        STATS_ANALYSIS(R.string.help_stats_analysis_title, R.string.help_stats_analysis_body),
        STATS_GROUPING(R.string.help_stats_grouping_title, R.string.help_stats_grouping_body),
        INPUT_MODE(R.string.help_input_mode_title, R.string.help_input_mode_body),
        INITIAL_VALUES(R.string.help_initial_values_title, R.string.help_initial_values_body),
        GRADE_VALUES(R.string.help_grade_values_title, R.string.help_grade_values_body),

        /**
         * 정리 폴더 사용법 — H1~H12와 달리 **기능 전체**를 설명하므로 본문이 길다.
         * 길이를 맞추려고 쪼개면 "폴더에 넣으면 무슨 일이 일어나는가"라는 한 질문이 여러 창으로
         * 흩어진다. 한 번 읽고 마는 종류라 한 자리에 모은다(가이드 9-2 H13).
         */
        /**
         * 사건 필드 — H13·H14와 같은 계열이다(스위치 하나가 아니라 "이게 무엇이고 어떻게
         * 쓰는가"라 절이 나뉜다). 본문은 가이드 9-2에서 승인된 것과 **한 글자도 다르지 않다**.
         * 예시 셋(회차·장소·시점 인물)은 추천 세트와 한 몸이라 세트가 바뀌면 함께 바꾼다.
         */
        EVENT_FIELD(R.string.help_event_field_title, R.string.help_event_field_body),

        ORGANIZE_FOLDER(R.string.help_organize_folder_title, R.string.help_organize_folder_body),

        /**
         * 필드 데이터 라이브러리 — H13과 같은 이유로 본문이 길다(스위치 하나가 아니라 화면 전체).
         * 이 화면은 "있는 줄 몰랐다"는 회신이 나온 자리라(P4·U-5), 도착한 뒤의 설명이
         * 상시 노출 목적문 한 줄로는 모자란다. 초안·승인 근거는 가이드 9-2 H14.
         */
        FIELD_LIBRARY(R.string.help_field_library_title, R.string.help_field_library_body),

        /**
         * 대표 이미지(B-103 D7). ☆는 설정 스위치가 아니라 조작이라 R-25 목적문 대상이 아니고,
         * 대신 "지정하면 무엇이 달라지는가"를 여기서 한 번에 말한다 — 지정·해제·표시 자리·
         * 삭제 시 처분이 한 질문이라 창을 쪼개면 답이 흩어진다.
         */
        REPRESENTATIVE_IMAGE(
            R.string.help_representative_image_title,
            R.string.help_representative_image_body
        )
    }

    /** 앱 도움말 표시 — `?` 아이콘의 진입점. */
    fun showHelp(context: Context, topic: Topic) {
        MaterialAlertDialogBuilder(context)
            .setTitle(topic.titleRes)
            .setMessage(topic.bodyRes)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    /** 필드 설명 표시 — 설명이 없으면 아무것도 띄우지 않는다(호출측이 아이콘 자체를 감춘다). */
    fun showFieldNote(context: Context, field: FieldDefinition) {
        val description = FieldDescription.fromConfig(field.config)
        if (description.isBlank()) return
        MaterialAlertDialogBuilder(context)
            .setTitle(field.name)
            .setMessage(description)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }
}
