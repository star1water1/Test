package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * 필드별 **AI 추천 대상** 설정 (P-A A-1, 3단 복원은 B-80).
 * `FieldDefinition.config` JSON의 `"aiSuggest"` 키에 저장된다.
 *
 * ## 왜 3단인가 (B-80 — 2단에서 되돌렸다)
 * 종전 2단(on/off)의 기각 근거는 *"3번째 상태('수동만')는 서술형/짧은 값 경로 분리로 이미
 * 필드 성격에서 자동 성립한다"*였는데, **그 근거는 서술형 필드에만 성립했다.**
 * SELECT·NUMBER·짧은 TEXT는 OFF가 **일괄 제외와 ✨ 숨김을 동시에** 하므로
 * "비용이 아까워 일괄에서만 빼고 개별 ✨은 남기고 싶다"에 대응하는 상태가 없었다(이중 경로 공백).
 *
 * 별도 토글(일괄 대상 스위치를 하나 더)이 아니라 3단을 고른 이유는 **설명이 쉽다는 것**이다 —
 * 상태가 둘로 흩어지지 않고 한 줄에서 읽힌다(사용자 확정 2026.08.02, 선택지 ①).
 *
 * ## 저장 형태 — 값 셋이 표현이 서로 다르다(일부러다)
 * | 상태 | 저장 |
 * |---|---|
 * | [SuggestMode.ALL](기본) | **키 없음** — 기본값 미저장은 [NarrativeMode]·[FieldValueLibraryConfig]와 같은 관행 |
 * | [SuggestMode.MANUAL_ONLY] | `"aiSuggest": "manual"`(문자열) |
 * | [SuggestMode.OFF] | `"aiSuggest": false`(**불리언 — 2단 시절과 같다**) |
 *
 * OFF를 문자열 `"off"`로 바꾸지 않은 것은 **왕복 무결성** 때문이다(개발 의도 4번).
 * 그렇게 하면 이미 꺼 둔 필드가 다음 저장에서 표현만 바뀌고, 그전에 내보낸 엑셀·월드패키지·
 * 백업이 전부 옛 표현을 담고 있어 **읽는 쪽만 관대하고 쓰는 쪽은 갈라진다.**
 * 지금 형태는 기존 두 상태의 표현이 한 글자도 바뀌지 않고 **새 상태만 새 값을 쓴다.**
 *
 * 읽기는 그보다 넓게 관대하다([SuggestMode.parse]) — 외부에서 편집된 값이 들어오는 자리다.
 */
object FieldAiPolicy {
    const val CONFIG_KEY = "aiSuggest"

    /**
     * AI 추천 대상 3단. **두 경로(일괄 / 개별 ✨)에 각각 답한다** —
     * 종전 2단이 그 둘을 한 값으로 묶어 버린 것이 B-80의 결함이었다.
     *
     * @property inBulk 일괄 추천(`bulkTargetsOf`)의 대상인가.
     * @property inlineSparkle 폼의 인라인 ✨(서술형 작성 진입 포함)을 보여 주는가.
     */
    enum class SuggestMode(
        val key: String,
        val label: String,
        val inBulk: Boolean,
        val inlineSparkle: Boolean
    ) {
        /** 기본 — 일괄에도 들어가고 ✨도 보인다. */
        ALL("all", "전부 (일괄 추천 + 개별 ✨)", inBulk = true, inlineSparkle = true),

        /** 일괄에서만 뺀다. ✨는 그대로 — "필요할 때 내가 눌러서 받겠다"(비용 통제). */
        MANUAL_ONLY("manual", "개별만 (✨ 버튼으로 직접 요청)", inBulk = false, inlineSparkle = true),

        /** 이 필드는 AI가 건드리지 않는다 — 일괄 제외 + ✨ 숨김. */
        OFF("off", "끄기 (AI가 건드리지 않음)", inBulk = false, inlineSparkle = false);

        companion object {
            val DEFAULT = ALL

            /**
             * 관대한 해석 — 앱이 쓴 값뿐 아니라 **엑셀·월드패키지에서 손으로 고쳐 온 값**도 받는다.
             * 모르는 값은 기본([ALL])으로 떨어뜨린다: 정착하지 못한 값 하나 때문에 필드가
             * AI에서 통째로 빠지면 사용자는 이유를 볼 수 없다.
             *
             * `false`/`true`는 2단 시절의 표현이다 — 그 시절 파일이 지금도 들어온다.
             */
            fun parse(raw: Any?): SuggestMode = when (raw) {
                null, JSONObject.NULL -> DEFAULT
                is Boolean -> if (raw) ALL else OFF
                else -> when (raw.toString().trim().lowercase()) {
                    "", "all", "true", "y", "yes" -> ALL
                    "manual", "manual_only", "개별만", "개별" -> MANUAL_ONLY
                    "off", "false", "n", "no", "끄기" -> OFF
                    else -> DEFAULT
                }
            }
        }
    }

    /** 이 필드의 AI 추천 대상 상태. 키 없음·손상 JSON은 기본([SuggestMode.ALL]). */
    fun suggestMode(configJson: String): SuggestMode = try {
        SuggestMode.parse(JSONObject(configJson).opt(CONFIG_KEY))
    } catch (_: Exception) {
        SuggestMode.DEFAULT
    }

    /** 일괄 추천 대상인가. */
    fun isBulkTarget(configJson: String): Boolean = suggestMode(configJson).inBulk

    /** 폼의 인라인 ✨을 보여 주는가(서술형 작성 진입 포함). */
    fun isInlineSparkleEnabled(configJson: String): Boolean = suggestMode(configJson).inlineSparkle

    /**
     * 상태를 config에 기록한다. 기본([SuggestMode.ALL])은 키를 지우고,
     * [SuggestMode.OFF]는 **불리언 `false`**로 쓴다(위 저장 형태 표 — 왕복 무결성).
     * 손상 JSON은 원문 유지(다른 키를 파괴하지 않는다).
     */
    fun applyMode(existing: String, mode: SuggestMode): String = try {
        val json = if (existing.isBlank()) JSONObject() else JSONObject(existing)
        when (mode) {
            SuggestMode.ALL -> json.remove(CONFIG_KEY)
            SuggestMode.OFF -> json.put(CONFIG_KEY, false)
            SuggestMode.MANUAL_ONLY -> json.put(CONFIG_KEY, mode.key)
        }
        json.toString()
    } catch (_: Exception) {
        existing
    }

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

}
