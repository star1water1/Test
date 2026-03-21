package com.novelcharacter.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 체형 분석 인사이트 설정.
 * FieldDefinition.config JSON의 "bodyAnalysis" 객체에 저장됨.
 *
 * - cupMapping: 컵 사이즈 산출 매핑 (bust-underbust 차이 → 라벨)
 * - bodyTypeRules: 체형 분류 규칙 (조건 기반, priority 순 평가)
 * - enabledInsights: 인사이트 항목별 표시/숨김 토글
 *
 * config에 "bodyAnalysis" 키가 없으면 DEFAULT를 사용하므로
 * 기존 데이터의 마이그레이션이 불필요하다.
 */
data class BodyAnalysisConfig(
    val cupMapping: List<CupMappingEntry> = DEFAULT_CUP_MAPPING,
    val underbustEstimation: String = "waist",
    val bodyTypeRules: List<BodyTypeRule> = DEFAULT_BODY_TYPE_RULES,
    val defaultBodyType: String = "보통체형",
    val enabledInsights: Map<String, Boolean> = DEFAULT_ENABLED_INSIGHTS
) {
    data class CupMappingEntry(val maxDiff: Double, val label: String)

    data class BodyTypeRule(
        val label: String,
        val conditions: Map<String, RangeCondition>,
        val priority: Int
    )

    data class RangeCondition(val min: Double? = null, val max: Double? = null)

    fun isInsightEnabled(key: String): Boolean =
        enabledInsights[key] ?: DEFAULT_ENABLED_INSIGHTS[key] ?: true

    companion object {
        private const val KEY = "bodyAnalysis"

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
            INSIGHT_RANKING to true
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

                val underbustEstimation = obj.optString("underbustEstimation", "waist")

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
                    underbustEstimation = underbustEstimation,
                    bodyTypeRules = bodyTypeRules.ifEmpty { DEFAULT_BODY_TYPE_RULES },
                    defaultBodyType = defaultBodyType,
                    enabledInsights = if (enabledInsights.isEmpty()) DEFAULT_ENABLED_INSIGHTS else enabledInsights
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

                if (config.underbustEstimation != "waist") {
                    put("underbustEstimation", config.underbustEstimation)
                }

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
