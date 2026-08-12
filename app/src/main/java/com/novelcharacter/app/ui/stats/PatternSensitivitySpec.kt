package com.novelcharacter.app.ui.stats

import com.novelcharacter.app.R

/**
 * 패턴 유형 하나와 그 **민감도 손잡이**를 잇는 표 (B-70).
 *
 * 왜 별도 파일인가: 화면이 유형별로 `when`을 적으면 손잡이가 늘 때마다 화면을 고쳐야 하고,
 * 그 `when`은 새 유형이 들어와도 **아무 말 없이 통과한다**(else 가지가 삼킨다). 표를 하나 두고
 * [of]가 반드시 답을 내게 하면, 유형이 늘었는데 손잡이를 안 붙인 경우 **컴파일이 막는다**
 * (`when`이 전수라 새 상수에서 컴파일 오류가 난다). 규약 R-45가 설정에 세운 모양과 같은 취지다.
 *
 * 읽기·쓰기·표시를 한 선언이 함께 든다 — 한쪽만 적는 것이 불가능해야 늘어난다.
 * 순수 계층이라(`Context` 없음, 문자열은 리소스 id로만 든다) 순수 JVM 시험이 그대로 잰다.
 */
data class PatternSensitivitySpec(
    /** 입력칸 옆에 붙는 단위 — "%"·"년"·"배". */
    val unit: String,
    /** 소수점을 받는가. 배수(3.5배)는 받고 백분율·연수는 안 받는다. */
    val allowsDecimal: Boolean,
    /** 받아들이는 범위 — 문구도 이 값으로 채운다(R-14: 숫자를 문구에 박지 않는다). */
    val min: String,
    val max: String,
    /** 이 손잡이가 무엇을 정하는지 한 줄. */
    val hintRes: Int,
    /** 현재 값을 칸에 적는다. */
    val format: (PatternThresholds) -> String,
    /**
     * 칸의 글자를 값으로 바꿔 [PatternThresholds]에 얹는다.
     * **못 읽거나 범위 밖이면 null** — 부르는 쪽이 그 칸에 오류를 붙이고 창을 열어 둔다(R-27).
     * 여기서 조용히 기본값으로 되돌리면 사용자가 적은 것이 말없이 사라진다.
     */
    val parse: (PatternThresholds, String) -> PatternThresholds?
) {
    companion object {
        /**
         * `when`이 전수라 **유형을 늘리면 여기서 컴파일이 막힌다** — 그것이 이 함수의 요점이다.
         * `else`를 두지 말 것: 두는 순간 새 유형이 조용히 기본값 손잡이를 갖게 된다.
         */
        fun of(type: PatternType): PatternSensitivitySpec = when (type) {
            PatternType.DOMINANCE -> percent(
                hintRes = R.string.stats_pattern_hint_dominance,
                range = PatternThresholds.DOMINANCE_RANGE,
                get = { it.dominancePercent },
                set = { t, v -> t.copy(dominancePercent = v) }
            )
            PatternType.BALANCE -> percent(
                hintRes = R.string.stats_pattern_hint_balance,
                range = PatternThresholds.BALANCE_RANGE,
                get = { it.balanceMaxPercent },
                set = { t, v -> t.copy(balanceMaxPercent = v) }
            )
            PatternType.OUTLIER -> percent(
                hintRes = R.string.stats_pattern_hint_outlier,
                range = PatternThresholds.OUTLIER_RANGE,
                get = { it.outlierSingletonPercent },
                set = { t, v -> t.copy(outlierSingletonPercent = v) }
            )
            PatternType.CLUSTER -> percent(
                hintRes = R.string.stats_pattern_hint_cluster,
                range = PatternThresholds.CLUSTER_RANGE,
                get = { it.clusterPercent },
                set = { t, v -> t.copy(clusterPercent = v) }
            )
            // 연수는 스케일이 열려 있어야 한다 — 역법이 수천 년인 작품이 실사용 표본에 있고,
            // 그 사용자에게 '100년 공백'은 아무것도 뜻하지 않는다(B-70이 든 바로 그 예다).
            PatternType.ABSENCE -> PatternThresholds.ABSENCE_YEARS_RANGE.let { range ->
                PatternSensitivitySpec(
                    unit = "년",
                    allowsDecimal = false,
                    min = range.first.toString(), max = range.last.toString(),
                    hintRes = R.string.stats_pattern_hint_absence,
                    format = { it.absenceGapYears.toString() },
                    parse = { t, raw ->
                        raw.toIntOrNull()?.takeIf { it in range }?.let { t.copy(absenceGapYears = it) }
                    }
                )
            }
            PatternType.CROSS_NOVEL -> PatternThresholds.CROSS_NOVEL_RANGE.let { range ->
                PatternSensitivitySpec(
                    unit = "배",
                    allowsDecimal = true,
                    min = trim(range.start), max = trim(range.endInclusive),
                    hintRes = R.string.stats_pattern_hint_cross_novel,
                    format = { trim(it.crossNovelRatio) },
                    parse = { t, raw ->
                        raw.toFloatOrNull()?.takeIf { it.isFinite() && it in range }
                            ?.let { t.copy(crossNovelRatio = it) }
                    }
                )
            }
        }

        private fun percent(
            hintRes: Int,
            range: ClosedFloatingPointRange<Float>,
            get: (PatternThresholds) -> Float,
            set: (PatternThresholds, Float) -> PatternThresholds
        ) = PatternSensitivitySpec(
            unit = "%",
            allowsDecimal = false,
            min = trim(range.start), max = trim(range.endInclusive),
            hintRes = hintRes,
            format = { trim(get(it)) },
            parse = { t, raw ->
                raw.toFloatOrNull()?.takeIf { it.isFinite() && it in range }?.let { set(t, it) }
            }
        )

        /** `60.0`이 아니라 `60`으로 보이게 — 칸에 뜬 글자가 곧 사용자가 고칠 글자다. */
        private fun trim(v: Float): String =
            if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    }
}
