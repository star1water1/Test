package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldAiPolicy
import com.novelcharacter.app.data.model.FieldDescription
import org.json.JSONObject

/**
 * '필드 정의' 시트의 **config 파생 전용 열**(AI추천 · 필드설명) 왕복 규칙 (P-A A-1·A-2).
 *
 * 왜 전용 열인가: 이 사용자는 엑셀 우선 작업자다(docs/usage_reality_check_2026-07.md).
 * 설명은 사람이 쓰는 산문인데 JSON 문자열 안에서만 편집 가능하면(따옴표 이스케이프,
 * 한 셀에 뭉친 설정 전체) 사실상 앱에서만 쓸 수 있다. 전용 열이면 수백 행을 채워 한 번에 들인다.
 *
 * **파일 안에 같은 사실을 두 벌 두지 않는다** — 내보낼 때 `설정(JSON)` 셀에서 두 키를 제거하고
 * 전용 열에만 싣는다([stripPortableKeys]). 두 벌이면 어느 쪽이 진짜인지 물어야 하고,
 * 그 질문에 좋은 답이 없다.
 *
 * 가져오기 병합은 열/키/기존값의 3분기([merge]) — 여기를 틀리면 설명이 통째로 날아간다.
 */
object FieldConfigColumns {

    const val COLUMN_AI_SUGGEST = "AI추천"
    const val COLUMN_DESCRIPTION = "필드설명"

    /**
     * 내보내기용: JSON 셀에 싣기 전에 전용 열로 나가는 키들을 **문자열 사본에서** 제거한다
     * (AI추천 · 필드설명 · 등급체계 참조 — 재정의 `gradeOverrides`와 실효 표 `grades`는
     * 전용 열이 없으므로 JSON에 남는다).
     *
     * DB의 config 원본은 절대 건드리지 않는다 — 월드패키지(`.ncworld`)는 FieldDefinition 객체를
     * 통째 직렬화하므로 원본을 고치면 그쪽 왕복이 함께 빈다(설계 4-5의 조건).
     * 손상 JSON은 원문 그대로 내보낸다(여기서 고치려 들면 다른 키까지 파괴한다).
     */
    fun stripPortableKeys(configJson: String): String = try {
        val json = JSONObject(configJson)
        json.remove(FieldAiPolicy.CONFIG_KEY)
        json.remove(FieldDescription.CONFIG_KEY)
        json.remove(com.novelcharacter.app.data.model.GradeSystemRef.CONFIG_KEY)
        json.toString()
    } catch (_: Exception) {
        configJson
    }

    /**
     * 가져오기 병합 — 두 열 각각 3분기 (`필수여부`의 `sheetBooleanOrKeep`과 같은 문법):
     *
     * ① 전용 열이 있으면 → 그 셀이 값이다. AI추천의 빈칸은 기본값(켜짐)으로 해석한다 —
     *    끄기는 드롭다운의 "N"이 말하고, 빈칸은 config 키 없음(=기본값)에 대응한다.
     *    필드설명의 빈칸은 설명 제거다(내보내기가 설명 없는 필드를 빈칸으로 쓰므로 왕복 일치).
     * ② 열이 없고 JSON 셀에 키가 있으면 → 그대로 둔다 (전용 열 도입 전 구버전 파일).
     * ③ 열이 없고 JSON에도 키가 없으면 → **기존 DB 값을 다시 얹는다** — 이 분기를 빠뜨리면
     *    전용 열을 지운 파일을 들일 때 설명·토글이 무통보로 유실된다.
     *    신규 필드(existingConfig == null)는 기본값이 된다.
     */
    fun merge(
        sheetConfig: String,
        aiColumnPresent: Boolean,
        aiCellText: String,
        descriptionColumnPresent: Boolean,
        descriptionCellText: String,
        existingConfig: String?
    ): String {
        var merged = sheetConfig
        merged = when {
            aiColumnPresent -> {
                val enabled = if (aiCellText.isBlank()) true else parseSheetBoolean(aiCellText)
                FieldAiPolicy.applyToConfig(merged, enabled)
            }
            hasKey(merged, FieldAiPolicy.CONFIG_KEY) -> merged
            existingConfig != null ->
                FieldAiPolicy.applyToConfig(merged, FieldAiPolicy.isSuggestEnabled(existingConfig))
            else -> merged
        }
        merged = when {
            descriptionColumnPresent -> FieldDescription.applyToConfig(merged, descriptionCellText)
            hasKey(merged, FieldDescription.CONFIG_KEY) -> merged
            existingConfig != null ->
                FieldDescription.applyToConfig(merged, FieldDescription.fromConfig(existingConfig))
            else -> merged
        }
        return merged
    }

    private fun hasKey(configJson: String, key: String): Boolean = try {
        JSONObject(configJson).has(key)
    } catch (_: Exception) {
        false
    }
}
