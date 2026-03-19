package com.novelcharacter.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 필드별 통계 분석 설정.
 * FieldDefinition.config JSON의 "stats" 객체에 저장됨.
 */
data class FieldStatsConfig(
    val enabled: Boolean = true,
    val analyses: List<AnalysisEntry> = listOf(AnalysisEntry()),
    val binning: BinningConfig? = null,
    val valueLabels: Map<String, String> = emptyMap()
) {
    data class AnalysisEntry(
        val type: StatsType = StatsType.DISTRIBUTION,
        val chart: ChartType = ChartType.PIE,
        val limit: Int = 10
    )

    data class BinningConfig(
        val mode: String = "auto",
        val ranges: List<String> = emptyList()
    ) {
        fun parseRanges(): List<BinRange> {
            return ranges.mapNotNull { rangeStr ->
                val parts = rangeStr.split(":")
                if (parts.size != 2) return@mapNotNull null
                val rangePart = parts[0].trim()
                val label = parts[1].trim()
                when {
                    rangePart.startsWith("~") -> {
                        val max = rangePart.removePrefix("~").toFloatOrNull() ?: return@mapNotNull null
                        BinRange(null, max, label)
                    }
                    rangePart.endsWith("~") -> {
                        val min = rangePart.removeSuffix("~").toFloatOrNull() ?: return@mapNotNull null
                        BinRange(min, null, label)
                    }
                    rangePart.contains("~") -> {
                        val (minStr, maxStr) = rangePart.split("~", limit = 2)
                        val min = minStr.toFloatOrNull() ?: return@mapNotNull null
                        val max = maxStr.toFloatOrNull() ?: return@mapNotNull null
                        BinRange(min, max, label)
                    }
                    else -> null
                }
            }
        }
    }

    data class BinRange(val min: Float?, val max: Float?, val label: String) {
        fun contains(value: Float): Boolean {
            val aboveMin = min == null || value >= min
            val belowMax = max == null || value <= max
            return aboveMin && belowMax
        }
    }

    enum class StatsType(val key: String, val label: String) {
        DISTRIBUTION("distribution", "값 분포 (비율)"),
        NUMERIC("numeric", "수치 요약 (최소/최대/평균)"),
        RANKING("ranking", "값 순위 (TOP N)");

        companion object {
            fun fromKey(key: String?): StatsType =
                entries.find { it.key == key } ?: DISTRIBUTION

            fun labels(): List<String> = entries.map { it.label }

            fun forFieldType(fieldType: String): List<StatsType> = when (fieldType) {
                "NUMBER" -> listOf(DISTRIBUTION, NUMERIC, RANKING)
                "TEXT", "SELECT", "MULTI_TEXT", "GRADE" -> listOf(DISTRIBUTION, RANKING)
                else -> listOf(DISTRIBUTION)
            }
        }
    }

    enum class ChartType(val key: String, val label: String) {
        PIE("pie", "원형 차트"),
        BAR("bar", "막대 차트"),
        HORIZONTAL_BAR("horizontal_bar", "가로 막대"),
        DONUT("donut", "도넛 차트");

        companion object {
            fun fromKey(key: String?): ChartType =
                entries.find { it.key == key } ?: PIE

            fun labels(): List<String> = entries.map { it.label }
        }
    }

    /** 저장값 → 표시 라벨 변환 */
    fun applyLabel(rawValue: String): String = valueLabels[rawValue] ?: rawValue

    /** 숫자값 → 구간 라벨 변환 */
    fun applyBinning(numericValue: Float): String? {
        val config = binning ?: return null
        if (config.mode == "auto") return null
        val ranges = config.parseRanges()
        return ranges.firstOrNull { it.contains(numericValue) }?.label
    }

    companion object {
        private const val KEY_STATS = "stats"

        fun fromConfig(configJson: String): FieldStatsConfig {
            return try {
                val root = JSONObject(configJson)
                val stats = root.optJSONObject(KEY_STATS) ?: return FieldStatsConfig()

                val enabled = stats.optBoolean("enabled", true)

                val analyses = mutableListOf<AnalysisEntry>()
                val analysesArr = stats.optJSONArray("analyses")
                if (analysesArr != null) {
                    for (i in 0 until analysesArr.length()) {
                        val obj = analysesArr.getJSONObject(i)
                        analyses.add(
                            AnalysisEntry(
                                type = StatsType.fromKey(obj.optString("type")),
                                chart = ChartType.fromKey(obj.optString("chart")),
                                limit = obj.optInt("limit", 10)
                            )
                        )
                    }
                }
                if (analyses.isEmpty()) analyses.add(AnalysisEntry())

                val binningObj = stats.optJSONObject("binning")
                val binning = if (binningObj != null) {
                    val ranges = mutableListOf<String>()
                    val rangesArr = binningObj.optJSONArray("ranges")
                    if (rangesArr != null) {
                        for (i in 0 until rangesArr.length()) {
                            ranges.add(rangesArr.getString(i))
                        }
                    }
                    BinningConfig(
                        mode = binningObj.optString("mode", "auto"),
                        ranges = ranges
                    )
                } else null

                val valueLabels = mutableMapOf<String, String>()
                val labelsObj = stats.optJSONObject("valueLabels")
                if (labelsObj != null) {
                    val keys = labelsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        valueLabels[k] = labelsObj.getString(k)
                    }
                }

                FieldStatsConfig(enabled, analyses, binning, valueLabels)
            } catch (_: Exception) {
                FieldStatsConfig()
            }
        }

        fun applyToConfig(existingConfig: String, statsConfig: FieldStatsConfig): String {
            val root = try {
                JSONObject(existingConfig)
            } catch (_: Exception) {
                JSONObject()
            }

            val stats = JSONObject().apply {
                put("enabled", statsConfig.enabled)

                val analysesArr = JSONArray()
                for (entry in statsConfig.analyses) {
                    analysesArr.put(JSONObject().apply {
                        put("type", entry.type.key)
                        put("chart", entry.chart.key)
                        put("limit", entry.limit)
                    })
                }
                put("analyses", analysesArr)

                if (statsConfig.binning != null) {
                    put("binning", JSONObject().apply {
                        put("mode", statsConfig.binning.mode)
                        val rangesArr = JSONArray()
                        statsConfig.binning.ranges.forEach { rangesArr.put(it) }
                        put("ranges", rangesArr)
                    })
                }

                if (statsConfig.valueLabels.isNotEmpty()) {
                    put("valueLabels", JSONObject().apply {
                        statsConfig.valueLabels.forEach { (k, v) -> put(k, v) }
                    })
                }
            }

            root.put(KEY_STATS, stats)
            return root.toString()
        }
    }
}
