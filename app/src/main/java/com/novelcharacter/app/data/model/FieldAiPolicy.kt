package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * 필드별 **AI 추천 대상 여부** 설정 (P-A A-1).
 * `FieldDefinition.config` JSON의 `"aiSuggest"` 키에 저장된다.
 *
 * OFF의 의미 — "이 필드는 AI가 건드리지 않는다":
 * 일괄 추천 대상에서 제외(사유와 개수로 고지)되고 폼 인라인 ✨·서술형 작성 진입도 숨는다.
 * 🎲 랜덤(AI 아님)·값 라이브러리 AI 정리(있는 값 정리)·통계·검색·엑셀은 영향받지 않는다.
 *
 * 2단(on/off)인 이유: 후보였던 3번째 상태("수동만")는 서술형/짧은 값 경로 분리로 이미
 * 필드 성격에서 자동 성립하므로, 선택지로 만들면 설정만 늘고 결과는 같다.
 *
 * 기본값(true)은 저장하지 않는다 — [NarrativeMode]·[FieldValueLibraryConfig]와 같은 관행.
 * 기존 필드 전부가 config를 건드리지 않고도 켜진 상태로 시작한다(회귀 없음).
 */
object FieldAiPolicy {
    const val CONFIG_KEY = "aiSuggest"

    /**
     * 필드별 **AI 이미지 태그 어휘 편입** 설정
     * (설계 `image_folder_tag_ai` 4-2, 결정 D-3).
     *
     * 켜면 이 필드에 입력된 값들이 이미지 태그 제안 때 **어휘 후보로 함께 전달**된다.
     * 기조 문구에 적히는 것이 아니고 이 필드의 값이 바뀌지도 않는다 — 사용자가 우려한 대로
     * '기조에 포함'이라는 이름은 "내가 쓴 기조에 값이 덧붙는다"로 읽히므로, 화면 문구는
     * '어휘에 포함'으로 부르고 목적문이 그 오해를 정면으로 막는다.
     *
     * [CONFIG_KEY]와 **기본값 방향이 반대다**(그쪽은 기본 켜짐, 이쪽은 기본 꺼짐).
     * 모든 필드의 값을 무조건 어휘로 보내면 프롬프트가 폭발하고, 무관한 필드(메모·나이)가
     * 태그 어휘를 오염시킨다. 그래서 **켠 것만** 싣는다.
     */
    const val IMAGE_TAG_VOCAB_KEY = "imageTagVocab"

    /** 키 없음 = false(꺼짐). 손상 JSON도 기본값으로 관대 처리. */
    fun isImageTagVocabEnabled(configJson: String): Boolean = try {
        JSONObject(configJson).optBoolean(IMAGE_TAG_VOCAB_KEY, false)
    } catch (_: Exception) {
        false
    }

    /** false면 키를 제거한다(기본값 미저장). 손상 JSON은 원문 유지. */
    fun applyImageTagVocabToConfig(existing: String, enabled: Boolean): String = try {
        val json = if (existing.isBlank()) JSONObject() else JSONObject(existing)
        if (enabled) json.put(IMAGE_TAG_VOCAB_KEY, true) else json.remove(IMAGE_TAG_VOCAB_KEY)
        json.toString()
    } catch (_: Exception) {
        existing
    }

    /** 키 없음 = true(켜짐). 손상 JSON도 기본값으로 관대 처리. */
    fun isSuggestEnabled(configJson: String): Boolean = try {
        JSONObject(configJson).optBoolean(CONFIG_KEY, true)
    } catch (_: Exception) {
        true
    }

    /** true면 키를 제거한다(기본값 미저장). 손상 JSON은 원문 유지(다른 키를 파괴하지 않는다). */
    fun applyToConfig(existing: String, enabled: Boolean): String = try {
        val json = if (existing.isBlank()) JSONObject() else JSONObject(existing)
        if (enabled) json.remove(CONFIG_KEY) else json.put(CONFIG_KEY, false)
        json.toString()
    } catch (_: Exception) {
        existing
    }
}
