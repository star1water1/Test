package com.novelcharacter.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 구조화 입력 파트가 몸의 어느 부위를 재는가.
 *
 * 파트 라벨은 사용자가 자유롭게 짓는다("B"·"가슴"·"윗둘레"…). 그래서 분석·실루엣이
 * 라벨을 직접 읽으면 새 표기마다 조용히 빠진다 — 부위는 라벨이 아니라 이 슬롯이 든다.
 * 해석 사다리(명시 매핑 → 라벨 추론 → 위치 폴백)는 [com.novelcharacter.app.util.BodyMeasurements]가
 * 단일 소스로 갖는다.
 *
 * [UNDERBUST]는 선택 슬롯이다 — 매핑돼 있으면 컵 계산이 실측 밑가슴을 쓰고,
 * 없으면 현행 근사(허리 + [BodyAnalysisConfig.ribOffset])로 동작한다.
 */
enum class BodySlot { BUST, UNDERBUST, WAIST, HIP, SHOULDER, NONE }

/**
 * 체형 분석 인사이트 설정.
 * FieldDefinition.config JSON의 "bodyAnalysis" 객체에 저장됨.
 *
 * - cupMapping: 컵 사이즈 산출 매핑 (bust-underbust 차이 → 라벨)
 * - bodyTypeRules: 체형 분류 규칙 (조건 기반, priority 순 평가)
 * - enabledInsights: 인사이트 항목별 표시/숨김 토글
 * - partSlots: 구조화 입력 파트 인덱스 → [BodySlot] 명시 매핑 (비어 있으면 추론)
 *
 * config에 "bodyAnalysis" 키가 없으면 DEFAULT를 사용하므로
 * 기존 데이터의 마이그레이션이 불필요하다.
 *
 * **파싱이 읽지 않는 키는 [unusedKeysIn]이 센다**(B-95 · P-5). 없앤 설정이 담긴 옛 파일을
 * 들일 때 그 사실을 조용히 삼키지 않기 위해서다 — 세는 쪽이 [READ_KEYS] 하나이므로
 * 키를 새로 읽기 시작하면 그 순간 '모르는 키'에서 빠진다(적어 두면 낡는 부류를 만들지 않는다).
 */
data class BodyAnalysisConfig(
    val cupMapping: List<CupMappingEntry> = DEFAULT_CUP_MAPPING,
    val bodyTypeRules: List<BodyTypeRule> = DEFAULT_BODY_TYPE_RULES,
    val defaultBodyType: String = "보통체형",
    val enabledInsights: Map<String, Boolean> = DEFAULT_ENABLED_INSIGHTS,
    val ribOffset: Double = DEFAULT_RIB_OFFSET,                          // 흉곽 보정 — 밑가슴 = 허리 + 이 값
    val bodyTagRules: List<BodyTagRule> = emptyList(),                   // 다층 태그 (비어있으면 bodyTypeRules에서 변환)
    // 목표 비율 이상값 — **비어 있으면 자동**(이상 몸 → 장르 기준 순 —
    // BodyGenerator.genreTargetIdeals, 키·흉곽 보정 적응). 키별로 직접 정한 값만 담기며,
    // 담긴 키가 자동을 이긴다.
    // 종전 기본(황금비 계열 .70/1.00/.40/.52)은 P8 '황금비 잔재 제거'로 소거됐다(2026.08.02).
    val goldenRatioIdeals: Map<String, Double> = emptyMap(),
    val partSlots: List<BodySlot> = emptyList(),                         // 파트 인덱스 → 부위 (비어있으면 추론)
    // 이상 몸(치수 입력 — P8 '사용자 이상형', 2026.08.02 사용자 요청). 셋(B·W·H)이 다
    // 있어야 효력이 있고, 비율 환산·키 적응은 BodyGenerator.idealsFromBody가 든다.
    // 부분 입력도 저장은 한다 — 적다 만 값을 버리면 말없는 유실이다(R-27 결).
    val idealBody: IdealBody? = null
) {
    /**
     * 사용자가 치수로 적은 이상 몸. [heightCm]가 없으면 기준 몸 키로 읽는다.
     *
     * 저장은 적은 그대로(원문 보존), 해석은 평가 시점에 한다 — 흉곽 보정을 나중에 바꿔도
     * 이 몸의 컵차가 그 시점 규약으로 다시 계산된다(늦은 해석 — `BodyMeasurements` 선례).
     */
    data class IdealBody(
        val bust: Double? = null,
        val waist: Double? = null,
        val hip: Double? = null,
        val heightCm: Double? = null
    ) {
        /** 비율을 낼 수 있는가 — 세 치수가 전부 양수여야 한다. */
        val isComplete: Boolean
            get() = (bust ?: 0.0) > 0 && (waist ?: 0.0) > 0 && (hip ?: 0.0) > 0

        val isEmpty: Boolean
            get() = bust == null && waist == null && hip == null && heightCm == null
    }

    data class CupMappingEntry(val maxDiff: Double, val label: String)

    data class BodyTypeRule(
        val label: String,
        val conditions: Map<String, RangeCondition>,
        val priority: Int
    )

    data class BodyTagRule(
        val label: String,
        val layer: String,    // "build" | "silhouette" | "special"
        val conditions: Map<String, RangeCondition>,
        val priority: Int = 0
    )

    data class RangeCondition(val min: Double? = null, val max: Double? = null)

    fun isInsightEnabled(key: String): Boolean =
        enabledInsights[key] ?: DEFAULT_ENABLED_INSIGHTS[key] ?: true

    companion object {
        private const val KEY = "bodyAnalysis"

        /**
         * [fromConfig]가 실제로 읽는 `bodyAnalysis` 키 전수 — [unusedKeysIn]의 기준이다.
         *
         * **여기 없는 키는 파싱이 통째로 버린다.** 그 사실을 세어 알리는 것이 P-5의 처분이고
         * (B-95 — 없앤 `underbustEstimation`이 담긴 옛 파일이 실재한다), 알리지 않으면
         * 사용자가 적어 둔 설정이 말없이 사라진다(개발 의도 2번).
         *
         * **키를 새로 읽기 시작하면 이 목록에 함께 넣을 것** — 빠뜨리면 멀쩡한 설정이
         * *"더 이상 쓰지 않는 것"*으로 고지된다. 그 누락은 `BodyAnalysisConfigKeysTest`가
         * 잡는다(내보내기가 쓰는 키를 그대로 되읽혀 '모르는 키' 0을 요구한다) —
         * **손으로 대조하지 않아야 낡지 않는다.**
         */
        val READ_KEYS = setOf(
            "cupMapping", "bodyTypeRules", "defaultBodyType", "ribOffset",
            "bodyTagRules", "goldenRatioIdeals", "idealBody", "partSlots", "enabledInsights"
        )

        /**
         * `bodyAnalysis`에 담겼으나 [fromConfig]가 읽지 않는 키 — 이름 그대로 돌려준다.
         *
         * 손상 JSON이나 `bodyAnalysis`가 없는 config는 **빈 목록**이다(잴 것이 없는 것과
         * 버려지는 것은 다른 사실이고, 손상은 이미 가져오기가 따로 경고한다).
         */
        fun unusedKeysIn(configJson: String): List<String> = try {
            val obj = JSONObject(configJson).optJSONObject(KEY)
            if (obj == null) emptyList() else obj.keys().asSequence()
                .filterNot { it in READ_KEYS }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }

        // 인사이트 키 상수
        const val INSIGHT_BODY_TYPE = "bodyType"
        const val INSIGHT_CUP_SIZE = "cupSize"
        const val INSIGHT_BMI = "bmi"
        const val INSIGHT_WHR = "whr"
        const val INSIGHT_BWH_DIFF = "bwhDifferences"
        const val INSIGHT_NORMALIZED_RATIO = "normalizedRatio"
        const val INSIGHT_HEIGHT_RELATIVE = "heightRelative"
        const val INSIGHT_GOLDEN_RATIO = "goldenRatio"
        const val INSIGHT_SILHOUETTE = "silhouette"
        const val INSIGHT_RANKING = "ranking"
        const val INSIGHT_BODY_TAGS = "bodyTags"
        const val INSIGHT_FRAME_SIZE = "frameSize"
        const val INSIGHT_PROPORTION = "proportion"

        /**
         * 흉곽 보정 기본값(cm) — 실측 밑가슴이 없을 때 `밑가슴 = 허리 + 이 값`으로 본다.
         *
         * **이 값 하나가 그림과 글자를 함께 정한다**(B-92 해소, 2026.08.02). 종전에는
         * 기본이 0이라 분석은 `밑가슴 = 허리`로 컵을 재고 실루엣은 전용 상수 6으로 그려,
         * 같은 캐릭터의 컵 글자가 두 계층에서 두 컵 이상 갈렸다. 읽기 카드가 실루엣과
         * 컵 글자를 **한 카드에** 싣게 되면서 그 어긋남이 화면 안으로 들어오므로,
         * 근사 규약을 판정 P4가 확정한 장르 감각(밑가슴 ≈ 허리 + 6)으로 통일했다.
         *
         * 사용자가 이 값을 바꾸면 **그림도 함께 움직인다** — 설정과 화면이 갈리지 않는다.
         */
        const val DEFAULT_RIB_OFFSET = 6.0

        val DEFAULT_CUP_MAPPING = listOf(
            CupMappingEntry(7.5, "AA"),
            CupMappingEntry(10.0, "A"),
            CupMappingEntry(12.5, "B"),
            CupMappingEntry(15.0, "C"),
            CupMappingEntry(17.5, "D"),
            CupMappingEntry(20.0, "E"),
            CupMappingEntry(22.5, "F"),
            CupMappingEntry(25.0, "G"),
            CupMappingEntry(27.5, "H"),
            CupMappingEntry(30.0, "I"),
            CupMappingEntry(999.0, "J+")
        )

        val DEFAULT_BODY_TYPE_RULES = listOf(
            BodyTypeRule(
                "글래머", mapOf(
                    "bustWaistDiff" to RangeCondition(min = 18.0),
                    "whr" to RangeCondition(max = 0.72),
                    "bust" to RangeCondition(min = 88.0)
                ), priority = 1
            ),
            BodyTypeRule(
                "풍만형", mapOf(
                    "bust" to RangeCondition(min = 95.0),
                    "hip" to RangeCondition(min = 98.0)
                ), priority = 2
            ),
            BodyTypeRule(
                "날씬형", mapOf(
                    "bustWaistDiff" to RangeCondition(max = 12.0),
                    "waistHipDiff" to RangeCondition(max = 8.0),
                    "bust" to RangeCondition(max = 82.0)
                ), priority = 3
            ),
            BodyTypeRule(
                "소녀체형", mapOf(
                    "height" to RangeCondition(max = 158.0),
                    "bust" to RangeCondition(max = 80.0),
                    "hip" to RangeCondition(max = 85.0)
                ), priority = 4
            ),
            BodyTypeRule(
                "볼륨형", mapOf(
                    "bustWaistDiff" to RangeCondition(min = 12.0, max = 18.0),
                    "hip" to RangeCondition(min = 92.0),
                    "whr" to RangeCondition(min = 0.72, max = 0.82)
                ), priority = 5
            ),
            BodyTypeRule(
                "탄탄형", mapOf(
                    "whr" to RangeCondition(min = 0.70, max = 0.80),
                    "bustHipRatio" to RangeCondition(min = 0.93, max = 1.07)
                ), priority = 6
            )
        )

        val DEFAULT_BODY_TAG_RULES = listOf(
            // Build layer (BMI 기반)
            BodyTagRule("마른 체형", "build", mapOf("bmi" to RangeCondition(max = 18.5)), 1),
            BodyTagRule("표준 체형", "build", mapOf("bmi" to RangeCondition(min = 18.5, max = 25.0)), 2),
            BodyTagRule("풍만 체형", "build", mapOf("bmi" to RangeCondition(min = 25.0)), 3),
            // Silhouette layer (곡선/비율 기반)
            BodyTagRule("모래시계", "silhouette", mapOf(
                "whr" to RangeCondition(max = 0.72), "bustWaistDiff" to RangeCondition(min = 18.0)
            ), 1),
            BodyTagRule("슬렌더", "silhouette", mapOf(
                "bustWaistDiff" to RangeCondition(max = 13.0), "waistHipDiff" to RangeCondition(max = 13.0)
            ), 2),
            BodyTagRule("탄탄형", "silhouette", mapOf(
                "whr" to RangeCondition(min = 0.70, max = 0.80),
                "bustHipRatio" to RangeCondition(min = 0.93, max = 1.07)
            ), 3),
            BodyTagRule("배형", "silhouette", mapOf("whr" to RangeCondition(min = 0.85)), 4),
            // Special layer (누적)
            BodyTagRule("모델급", "special", mapOf(
                "height" to RangeCondition(min = 170.0), "whr" to RangeCondition(max = 0.73)
            ), 1),
            BodyTagRule("아담한", "special", mapOf(
                "height" to RangeCondition(max = 158.0), "bust" to RangeCondition(max = 82.0)
            ), 2),
            BodyTagRule("볼륨 압도적", "special", mapOf("cupIndex" to RangeCondition(min = 8.0)), 3)
        )

        val DEFAULT_ENABLED_INSIGHTS = mapOf(
            INSIGHT_BODY_TYPE to true,
            INSIGHT_CUP_SIZE to true,
            INSIGHT_BMI to true,
            INSIGHT_WHR to true,
            INSIGHT_BWH_DIFF to true,
            INSIGHT_NORMALIZED_RATIO to true,
            INSIGHT_HEIGHT_RELATIVE to true,
            INSIGHT_GOLDEN_RATIO to true,
            INSIGHT_SILHOUETTE to true,
            INSIGHT_RANKING to true,
            INSIGHT_BODY_TAGS to true,
            INSIGHT_FRAME_SIZE to true,
            INSIGHT_PROPORTION to true
        )

        val DEFAULT = BodyAnalysisConfig()

        fun fromConfig(configJson: String): BodyAnalysisConfig {
            return try {
                val root = JSONObject(configJson)
                val obj = root.optJSONObject(KEY) ?: return DEFAULT

                // Cup mapping
                val cupMapping = mutableListOf<CupMappingEntry>()
                val cupArr = obj.optJSONArray("cupMapping")
                if (cupArr != null) {
                    for (i in 0 until cupArr.length()) {
                        val entry = cupArr.getJSONObject(i)
                        cupMapping.add(
                            CupMappingEntry(
                                maxDiff = entry.optDouble("maxDiff", 999.0),
                                label = entry.optString("label", "?")
                            )
                        )
                    }
                }

                // Body type rules
                val bodyTypeRules = mutableListOf<BodyTypeRule>()
                val rulesArr = obj.optJSONArray("bodyTypeRules")
                if (rulesArr != null) {
                    for (i in 0 until rulesArr.length()) {
                        val ruleObj = rulesArr.getJSONObject(i)
                        val conditions = mutableMapOf<String, RangeCondition>()
                        val condObj = ruleObj.optJSONObject("conditions")
                        if (condObj != null) {
                            val keys = condObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val rangeObj = condObj.getJSONObject(k)
                                conditions[k] = RangeCondition(
                                    min = if (rangeObj.has("min")) rangeObj.getDouble("min") else null,
                                    max = if (rangeObj.has("max")) rangeObj.getDouble("max") else null
                                )
                            }
                        }
                        bodyTypeRules.add(
                            BodyTypeRule(
                                label = ruleObj.optString("label", ""),
                                conditions = conditions,
                                priority = ruleObj.optInt("priority", i)
                            )
                        )
                    }
                }

                val defaultBodyType = obj.optString("defaultBodyType", "보통체형")

                // Rib offset — 키가 없으면 기본값. 종전 기본이 0이던 동안 0은 저장된 적이
                // 없으므로(아래 toConfig의 기본값 생략 규칙), 이 갈아타기로 잃는 저장값은 없다.
                val ribOffset = obj.optDouble("ribOffset", DEFAULT_RIB_OFFSET)

                // Body tag rules (multi-tag)
                val bodyTagRules = mutableListOf<BodyTagRule>()
                val tagRulesArr = obj.optJSONArray("bodyTagRules")
                if (tagRulesArr != null) {
                    for (i in 0 until tagRulesArr.length()) {
                        val ruleObj = tagRulesArr.getJSONObject(i)
                        val conditions = mutableMapOf<String, RangeCondition>()
                        val condObj = ruleObj.optJSONObject("conditions")
                        if (condObj != null) {
                            val keys = condObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val rangeObj = condObj.getJSONObject(k)
                                conditions[k] = RangeCondition(
                                    min = if (rangeObj.has("min")) rangeObj.getDouble("min") else null,
                                    max = if (rangeObj.has("max")) rangeObj.getDouble("max") else null
                                )
                            }
                        }
                        bodyTagRules.add(BodyTagRule(
                            label = ruleObj.optString("label", ""),
                            layer = ruleObj.optString("layer", "silhouette"),
                            conditions = conditions,
                            priority = ruleObj.optInt("priority", i)
                        ))
                    }
                }

                // Golden ratio ideals
                val goldenRatioIdeals = mutableMapOf<String, Double>()
                val idealsObj = obj.optJSONObject("goldenRatioIdeals")
                if (idealsObj != null) {
                    val keys = idealsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        goldenRatioIdeals[k] = idealsObj.optDouble(k, 0.0)
                    }
                }

                // 이상 몸 — 있는 키만 읽는다(부분 입력 보존).
                val idealBodyObj = obj.optJSONObject("idealBody")
                fun bodyNum(key: String): Double? =
                    if (idealBodyObj != null && idealBodyObj.has(key))
                        idealBodyObj.optDouble(key).takeUnless { it.isNaN() }
                    else null
                val idealBody = if (idealBodyObj != null) IdealBody(
                    bust = bodyNum("bust"),
                    waist = bodyNum("waist"),
                    hip = bodyNum("hip"),
                    heightCm = bodyNum("height")
                ).takeUnless { it.isEmpty } else null

                // Part → BodySlot 명시 매핑. 모르는 이름은 NONE으로 받아 자리를 보존한다
                // (버리면 뒤 파트의 인덱스가 밀려 매핑 전체가 어긋난다).
                val partSlots = mutableListOf<BodySlot>()
                val slotsArr = obj.optJSONArray("partSlots")
                if (slotsArr != null) {
                    for (i in 0 until slotsArr.length()) {
                        val name = slotsArr.optString(i, "")
                        partSlots.add(
                            runCatching { BodySlot.valueOf(name.trim().uppercase()) }
                                .getOrDefault(BodySlot.NONE)
                        )
                    }
                }

                // Enabled insights
                val enabledInsights = mutableMapOf<String, Boolean>()
                val insightsObj = obj.optJSONObject("enabledInsights")
                if (insightsObj != null) {
                    val keys = insightsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        enabledInsights[k] = insightsObj.optBoolean(k, true)
                    }
                }

                BodyAnalysisConfig(
                    cupMapping = cupMapping.ifEmpty { DEFAULT_CUP_MAPPING },
                    bodyTypeRules = bodyTypeRules.ifEmpty { DEFAULT_BODY_TYPE_RULES },
                    defaultBodyType = defaultBodyType,
                    enabledInsights = if (enabledInsights.isEmpty()) DEFAULT_ENABLED_INSIGHTS else enabledInsights,
                    ribOffset = ribOffset,
                    bodyTagRules = bodyTagRules,
                    // 비어 있으면 빈 채로 둔다 — '장르 기준 자동'이라는 뜻이 있는 값이다.
                    goldenRatioIdeals = goldenRatioIdeals,
                    partSlots = partSlots,
                    idealBody = idealBody
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }

        fun applyToConfig(existingConfig: String, config: BodyAnalysisConfig): String {
            val root = try {
                JSONObject(existingConfig)
            } catch (_: Exception) {
                JSONObject()
            }

            val obj = JSONObject().apply {
                // Cup mapping
                val cupArr = JSONArray()
                for (entry in config.cupMapping) {
                    cupArr.put(JSONObject().apply {
                        put("maxDiff", entry.maxDiff)
                        put("label", entry.label)
                    })
                }
                put("cupMapping", cupArr)

                // Body type rules
                val rulesArr = JSONArray()
                for (rule in config.bodyTypeRules) {
                    rulesArr.put(JSONObject().apply {
                        put("label", rule.label)
                        put("priority", rule.priority)
                        val condObj = JSONObject()
                        for ((k, range) in rule.conditions) {
                            condObj.put(k, JSONObject().apply {
                                range.min?.let { put("min", it) }
                                range.max?.let { put("max", it) }
                            })
                        }
                        put("conditions", condObj)
                    })
                }
                put("bodyTypeRules", rulesArr)

                put("defaultBodyType", config.defaultBodyType)

                // Rib offset — 기본값이면 적지 않는다(기존 config JSON이 불어나지 않게).
                // 기준이 [DEFAULT_RIB_OFFSET]으로 옮겨졌으므로 이제 **0은 명시 저장된다** —
                // 근사를 끄고 싶은 사용자의 선택이 기본값과 구분돼 왕복한다.
                if (config.ribOffset != DEFAULT_RIB_OFFSET) {
                    put("ribOffset", config.ribOffset)
                }

                // Body tag rules
                if (config.bodyTagRules.isNotEmpty()) {
                    val tagRulesArr = JSONArray()
                    for (rule in config.bodyTagRules) {
                        tagRulesArr.put(JSONObject().apply {
                            put("label", rule.label)
                            put("layer", rule.layer)
                            put("priority", rule.priority)
                            val condObj = JSONObject()
                            for ((k, range) in rule.conditions) {
                                condObj.put(k, JSONObject().apply {
                                    range.min?.let { put("min", it) }
                                    range.max?.let { put("max", it) }
                                })
                            }
                            put("conditions", condObj)
                        })
                    }
                    put("bodyTagRules", tagRulesArr)
                }

                // 목표 비율 이상값 — 직접 정한 키가 있을 때만 저장(빈 = 자동).
                if (config.goldenRatioIdeals.isNotEmpty()) {
                    val idealsObj = JSONObject()
                    for ((k, v) in config.goldenRatioIdeals) {
                        idealsObj.put(k, v)
                    }
                    put("goldenRatioIdeals", idealsObj)
                }

                // 이상 몸 — 적힌 값만 싣는다(부분 입력 보존, 빈 몸은 싣지 않는다).
                config.idealBody?.takeUnless { it.isEmpty }?.let { body ->
                    put("idealBody", JSONObject().apply {
                        body.bust?.let { put("bust", it) }
                        body.waist?.let { put("waist", it) }
                        body.hip?.let { put("hip", it) }
                        body.heightCm?.let { put("height", it) }
                    })
                }

                // Part → BodySlot 매핑은 명시했을 때만 싣는다 — 추론과 같은 기본값을 굽지 않아야
                // 기존 필드 정의의 JSON이 불어나지 않는다(ribOffset 선례).
                if (config.partSlots.isNotEmpty()) {
                    val slotsArr = JSONArray()
                    for (slot in config.partSlots) slotsArr.put(slot.name)
                    put("partSlots", slotsArr)
                }

                // Enabled insights
                val insightsObj = JSONObject()
                for ((k, v) in config.enabledInsights) {
                    insightsObj.put(k, v)
                }
                put("enabledInsights", insightsObj)
            }

            root.put(KEY, obj)
            return root.toString()
        }

        fun removeFromConfig(existingConfig: String): String {
            val root = try {
                JSONObject(existingConfig)
            } catch (_: Exception) {
                return existingConfig
            }
            root.remove(KEY)
            return root.toString()
        }
    }
}
