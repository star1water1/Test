package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodyAnalysisConfig.GenerationPreset
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 🎲 축·프리셋 편집 창이 **회전을 넘겨 지키는 것** (B-260 · 확정 15장 5번 · R-41-a).
 *
 * ## 왜 순수 계층에 있는가
 *
 * [BodyEditorState]와 같은 근거다. 이 저장소의 자동 검증은 화면을 못 보므로, 창 안에서
 * `Bundle`에 칸을 넣고 빼면 **왕복이 맞는지 증명할 수단이 없다.** 담는 것과 푸는 것을 여기로
 * 내리면 `data class` 동등성 하나로 증명되고, 창에는 [encode] 한 줄과 [decode] 한 줄만 남는다.
 *
 * ## 왜 값이 아니라 **날 문자열**인가
 *
 * 이 창이 지키려는 것은 *저장 가능한 한 벌*이 아니라 **적던 중인 것**이다. 적는 도중의 칸은
 * `"12."`처럼 아직 수가 아니거나 비어 있을 수 있고, 그 상태를 [GenerationPreset]으로 담으려면
 * 어딘가에서 **눌러 담아야 한다** — 그러면 회전 한 번이 사용자가 적던 값을 조용히 바꾼다.
 * 그래서 칸은 보이는 그대로 담고, 수로 바꾸는 것은 종전대로 [저장]이 든다(R-27 — 검증은
 * 막되 버리지 않는다).
 *
 * ## [current]는 왜 함께 담는가
 *
 * 창이 그리는 **행 수**와 프리셋 스피너의 **항목 글자**가 거기서 나온다. 항목은 일부러
 * *저장된* 이름이라(이름을 고치는 중에 항목이 흔들리면 고르던 자리를 잃는다) 적던 이름으로
 * 대신할 수 없다. 이것 하나만 위젯 밖에서 오므로 함께 담는다 —
 * 코덱은 [BodyAnalysisConfig.generationToJsonString] **하나**를 빌려 쓴다(두 벌로 적으면 갈린다).
 *
 * ## 칸을 더할 때
 *
 * R-41이 요구하는 '한 벌'이 이 클래스다. 창에 새 입력을 더하면 여기 프로퍼티와
 * [encode]·[decode] 양쪽에 함께 등재한다 — 한쪽만 넣으면 왕복이 조용히 절반만 돈다.
 * [BodyGenerationEditStateTest]의 '전 칸 왕복' 시험이 그 누락을 잡는다.
 */
data class BodyGenerationEditState(
    /** 창을 연 시점의 한 벌 — 행 수와 스피너 항목이 여기서 나온다. */
    val current: GenerationPreset,
    /** 키 축 — 이름 + [중심, 흔들림]의 **날 문자열**. */
    val heightRows: List<RowText>,
    /** 몸통 축 — 이름 + [허리비, BMI, 끝]. */
    val torsoRows: List<RowText>,
    /** 가슴 축 — 이름 + [컵차]. */
    val bustRows: List<RowText>,
    /** 힙 축 — 이름 + [보너스, 끝]. */
    val hipRows: List<RowText>,
    /** 프리셋 — 이름 + 축 셋의 **고른 자리**(스피너는 자리로 저장한다). */
    val presetRows: List<PresetText>,
    /**
     * 프리셋 스피너의 **항목 글자**.
     *
     * **자리가 아니라 글자도 상태인 것이 여기 있는 이유다.** [기본값으로]는 창을 닫지 않고
     * 칸을 되돌리는데 그때 **스피너 항목 글자도 함께** 되돌린다(이름 칸만 바꾸면 축 이름은
     * 기본값인데 프리셋의 항목은 사용자가 지은 옛 이름으로 남아 **한 창이 두 말을 한다**).
     * 담지 않으면 *되돌린 뒤 회전*에서 정확히 그 두 말이 돌아온다 — 항목은 옛 이름,
     * 이름 칸은 기본 이름.
     */
    val presetOptionLabels: OptionLabels
) {

    /** 축 한 줄이 든 글자 — 이름과 수치 칸을 보이는 그대로. */
    data class RowText(val name: String, val numbers: List<String>)

    /** 프리셋 한 줄 — 이름과 고른 축의 자리. */
    data class PresetText(val name: String, val torso: Int, val bust: Int, val hip: Int)

    /** 프리셋이 고르는 축 셋의 항목 글자. */
    data class OptionLabels(
        val torso: List<String>,
        val bust: List<String>,
        val hip: List<String>
    ) {
        companion object {
            fun of(gen: GenerationPreset) = OptionLabels(
                torso = gen.torsoOptions.map { it.label },
                bust = gen.bustOptions.map { it.label },
                hip = gen.hipOptions.map { it.label }
            )
        }
    }

    fun encode(): String = JSONObject().apply {
        put(KEY_CURRENT, BodyAnalysisConfig.generationToJsonString(current))
        put(KEY_HEIGHTS, rowsToJson(heightRows))
        put(KEY_TORSOS, rowsToJson(torsoRows))
        put(KEY_BUSTS, rowsToJson(bustRows))
        put(KEY_HIPS, rowsToJson(hipRows))
        put(KEY_PRESETS, JSONArray().apply {
            for (p in presetRows) put(JSONObject().apply {
                put(KEY_NAME, p.name)
                put(KEY_TORSO, p.torso); put(KEY_BUST, p.bust); put(KEY_HIP, p.hip)
            })
        })
        put(KEY_LABELS, JSONObject().apply {
            put(KEY_TORSO, JSONArray().apply { presetOptionLabels.torso.forEach { put(it) } })
            put(KEY_BUST, JSONArray().apply { presetOptionLabels.bust.forEach { put(it) } })
            put(KEY_HIP, JSONArray().apply { presetOptionLabels.hip.forEach { put(it) } })
        })
    }.toString()

    /**
     * 담긴 칸을 [current]의 **모양에 맞춘다** — 어긋난 축은 [current]의 값으로 되돌린다.
     *
     * 어긋날 수 있는 이유는 둘이다: 옛 판이 담은 것을 새 판이 읽거나(축 수가 바뀐 판올림),
     * 밖에서 상한 것을 읽거나. **그 축만 되돌리고 나머지는 지킨다** — 통째로 버리면 멀쩡한
     * 열아홉 칸이 한 칸 때문에 사라지고, 그대로 그리면 칸 수가 안 맞아 창이 죽는다.
     * 프리셋이 가리키는 자리도 함께 눌러 담는다(없는 축을 가리키면 스피너가 첫 항목으로 튄다).
     */
    fun aligned(): BodyGenerationEditState {
        val base = initial(current)
        fun fit(saved: List<RowText>, fallback: List<RowText>): List<RowText> =
            if (saved.size == fallback.size && saved.indices.all { saved[it].numbers.size == fallback[it].numbers.size }) {
                saved
            } else {
                fallback
            }
        val presets = if (presetRows.size == base.presetRows.size) {
            presetRows.map {
                PresetText(
                    it.name,
                    it.torso.coerceIn(0, (current.torsoOptions.size - 1).coerceAtLeast(0)),
                    it.bust.coerceIn(0, (current.bustOptions.size - 1).coerceAtLeast(0)),
                    it.hip.coerceIn(0, (current.hipOptions.size - 1).coerceAtLeast(0))
                )
            }
        } else {
            base.presetRows
        }
        // 항목 글자도 축 수와 맞아야 한다 — 어긋나면 스피너가 없는 자리를 가리킨다.
        val labels = if (presetOptionLabels.torso.size == current.torsoOptions.size &&
            presetOptionLabels.bust.size == current.bustOptions.size &&
            presetOptionLabels.hip.size == current.hipOptions.size
        ) presetOptionLabels else base.presetOptionLabels
        return copy(
            heightRows = fit(heightRows, base.heightRows),
            torsoRows = fit(torsoRows, base.torsoRows),
            bustRows = fit(bustRows, base.bustRows),
            hipRows = fit(hipRows, base.hipRows),
            presetRows = presets,
            presetOptionLabels = labels
        )
    }

    companion object {
        private const val KEY_CURRENT = "current"
        private const val KEY_HEIGHTS = "heights"
        private const val KEY_TORSOS = "torsos"
        private const val KEY_BUSTS = "busts"
        private const val KEY_HIPS = "hips"
        private const val KEY_PRESETS = "presets"
        private const val KEY_NAME = "name"
        private const val KEY_NUMBERS = "numbers"
        private const val KEY_TORSO = "torso"
        private const val KEY_BUST = "bust"
        private const val KEY_HIP = "hip"
        private const val KEY_LABELS = "labels"

        /**
         * 담긴 것을 되돌린다 — **못 읽으면 `null`이고, 부르는 쪽은 안전 종료로 돌아간다**(R-41-a).
         *
         * 옛 판이 담은 것을 새 판이 읽는 자리라 모양이 어긋날 수 있는데, 그때 껍데기를 띄우면
         * [저장]이 **사용자가 손대지도 않은 값**을 진짜 설정 위에 덮어쓴다. 담긴 것이 없으면
         * 잃은 것도 없으므로 다시 여는 것이 옳다.
         */
        fun decode(json: String?): BodyGenerationEditState? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val current = BodyAnalysisConfig.generationFromJsonString(obj.optString(KEY_CURRENT))
                    ?: return null
                BodyGenerationEditState(
                    current = current,
                    heightRows = rowsFromJson(obj.optJSONArray(KEY_HEIGHTS)),
                    torsoRows = rowsFromJson(obj.optJSONArray(KEY_TORSOS)),
                    bustRows = rowsFromJson(obj.optJSONArray(KEY_BUSTS)),
                    hipRows = rowsFromJson(obj.optJSONArray(KEY_HIPS)),
                    presetRows = (obj.optJSONArray(KEY_PRESETS) ?: JSONArray()).let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.let {
                                PresetText(
                                    it.optString(KEY_NAME), it.optInt(KEY_TORSO),
                                    it.optInt(KEY_BUST), it.optInt(KEY_HIP)
                                )
                            }
                        }
                    },
                    presetOptionLabels = (obj.optJSONObject(KEY_LABELS)).let { lab ->
                        if (lab == null) OptionLabels.of(current) else OptionLabels(
                            torso = strings(lab.optJSONArray(KEY_TORSO)),
                            bust = strings(lab.optJSONArray(KEY_BUST)),
                            hip = strings(lab.optJSONArray(KEY_HIP))
                        )
                    }
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun strings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }

        /**
         * 아직 아무것도 안 고친 상태 — 창을 **처음 열 때**의 한 벌이다.
         *
         * 회전 뒤의 것과 **같은 타입**이라 창이 두 길을 가르지 않아도 된다(가르면 그중
         * 한쪽만 낡는다 — 이 저장소가 반복해 물린 모양이다).
         */
        fun initial(current: GenerationPreset): BodyGenerationEditState = BodyGenerationEditState(
            current = current,
            heightRows = current.heightOptions.map {
                RowText(it.label, listOf(format(it.center), format(it.variance)))
            },
            torsoRows = current.torsoOptions.map {
                RowText(it.label, listOf(format(it.waistRatio), format(it.bmiTarget), format(it.maxRatio)))
            },
            bustRows = current.bustOptions.map { RowText(it.label, listOf(format(it.cupDiff))) },
            hipRows = current.hipOptions.map {
                RowText(it.label, listOf(format(it.hipBonus), format(it.maxDiff)))
            },
            presetRows = current.bodyPresets.map { PresetText(it.label, it.torso, it.bust, it.hip) },
            presetOptionLabels = OptionLabels.of(current)
        )

        /**
         * 소수점 뒤 0을 달지 않는다 — `18.5`는 그대로, `24.0`은 `24`로 보인다.
         *
         * 자릿수가 **교정이 미는 폭([GenerationPreset.Limits.TORSO_END_STEP] = .001)보다
         * 잘아야 한다** — 굵으면 창을 열었다 저장만 해도 값이 반올림되어, 손대지 않은 설정이
         * 조용히 바뀐다. **창과 여기가 같은 함수를 쓰는 것이 그 계약이다**(창이 따로 적으면
         * 회전 전후로 같은 값이 다른 글자로 보인다).
         */
        fun format(value: Double): String =
            if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString()
            else String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

        private fun rowsToJson(rows: List<RowText>): JSONArray = JSONArray().apply {
            for (r in rows) put(JSONObject().apply {
                put(KEY_NAME, r.name)
                put(KEY_NUMBERS, JSONArray().apply { r.numbers.forEach { put(it) } })
            })
        }

        private fun rowsFromJson(arr: JSONArray?): List<RowText> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { obj ->
                    val nums = obj.optJSONArray(KEY_NUMBERS) ?: JSONArray()
                    RowText(
                        obj.optString(KEY_NAME),
                        (0 until nums.length()).map { nums.optString(it) }
                    )
                }
            }
        }
    }
}
