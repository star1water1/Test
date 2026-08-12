package com.novelcharacter.app.ui.character

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.util.BodyAnalysisHelper
import com.novelcharacter.app.util.BodyAnalysisResult
import com.novelcharacter.app.util.FormulaEvaluator
import com.novelcharacter.app.util.RankingInfo

class DynamicFieldRenderer(
    private val containerGetter: () -> LinearLayout,
    private val contextGetter: () -> Context,
    private val resourcesGetter: () -> Resources,
    private val getString: (Int) -> String,
    private val getStringWithArg: (Int, Any) -> String
) {

    /**
     * 백분위 정보. 필드별로 작품/세계관 스코프의 상위 퍼센트를 보관.
     */
    data class PercentileInfo(
        val novelPercentile: Float? = null,
        val universePercentile: Float? = null
    )

    /** 외부에서 주입하는 순위 데이터 (체형 분석 인사이트용) */
    var bodyRankingInfo: RankingInfo? = null

    /**
     * 체형 분석 카드의 ⚙ — 그 필드의 편집 다이얼로그(체형 분석 설정)를 연다.
     *
     * 주입하지 않으면 아이콘 자체가 나오지 않는다. 눌러도 아무 데도 안 가는 버튼은 구색이다.
     */
    var onOpenBodySettings: ((FieldDefinition) -> Unit)? = null

    /**
     * 실루엣 탭 — 크게 보기(설계 5-4-3). 호스트가 다이얼로그를 띄우며 작품 평균 재료도
     * 그쪽이 붙인다(렌더러는 조회를 하지 않는다).
     */
    var onOpenSilhouette: ((FieldDefinition, com.novelcharacter.app.util.BodyMeasurements, BodyAnalysisConfig) -> Unit)? = null

    /**
     * 섹션 표현 방식.
     * - CARD: 디자인 시스템 카드(Widget.App.Card — 외곽선·flat)로 독립 표시. 상세 화면 등 페이지 배경 위.
     * - FLAT: 테두리 없는 섹션 + 상단 hairline 구분선. 이미 외곽선 있는 패널 안(보충 캐릭터 패널)에서
     *   이중 테두리를 피하고 한 패널의 섹션으로 읽히게 한다.
     */
    enum class SectionStyle { CARD, FLAT }
    var sectionStyle: SectionStyle = SectionStyle.CARD

    /**
     * 값 라이브러리 해석기 (fieldDefinitionId → resolver) — 호스트가 렌더 전에 주입.
     * 표시 라벨이 통계·칩·자동완성과 카드에서 동일하게 읽히도록 한다 (검토 A12).
     * 미주입/엔트리 없음이면 원시값 그대로 (폴백).
     */
    var valueResolvers: Map<Long, com.novelcharacter.app.util.FieldValueResolver> = emptyMap()

    private fun displayToken(field: FieldDefinition, token: String): String =
        valueResolvers[field.id]?.display(token) ?: token

    fun displayDynamicFields(
        fields: List<FieldDefinition>,
        values: List<CharacterFieldValue>,
        percentileData: Map<Long, PercentileInfo> = emptyMap(),
        preComputedCalculated: Map<Long, String>? = null
    ) {
        val container = containerGetter()
        container.removeAllViews()

        val valueMap = values.associateBy { it.fieldDefinitionId }

        // CALCULATED 필드 수식 평가 (사전 계산 결과가 있으면 재사용)
        val calculatedResults = preComputedCalculated
            ?: evaluateCalculatedFields(fields, valueMap)

        val grouped = fields
            .sortedBy { it.displayOrder }
            .groupBy { it.groupName }

        val context = contextGetter()
        val density = resourcesGetter().displayMetrics.density

        for ((groupName, groupFields) in grouped) {
            val card = createCard(context, density)

            val cardContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = createGroupTitle(context, density, groupName)
            cardContent.addView(titleView)

            for (field in groupFields) {
                val fieldValue = valueMap[field.id]?.value ?: ""
                val isCalculated = field.type == "CALCULATED"
                val format = DisplayFormat.fromConfig(field.config)

                if (!isCalculated && fieldValue.isNotEmpty() &&
                    (format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST)) {
                    // 필드 이름 라벨
                    val labelView = TextView(context).apply {
                        text = field.name
                        textSize = 13f
                        setTextColor(context.getColor(R.color.text_secondary))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (2 * density).toInt()
                        }
                    }
                    cardContent.addView(labelView)

                    // 쉼표 규칙은 앱 단일 소스가 든다(B-178) — 여기서 따로 쪼개면 읽기 화면만
                    // 따옴표를 모르게 되어, 한 값으로 저장·집계된 것이 두 조각으로 그려진다.
                    val items = com.novelcharacter.app.util.FieldValueTokenizer.splitMulti(fieldValue)
                    if (format == DisplayFormat.COMMA_LIST) {
                        val chipGroup = ChipGroup(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (6 * density).toInt()
                            }
                        }
                        for (item in items) {
                            chipGroup.addView(Chip(context).apply {
                                text = displayToken(field, item)
                                isClickable = false
                                isCheckable = false
                            })
                        }
                        cardContent.addView(chipGroup)
                    } else {
                        // BULLET_LIST
                        val bulletText = items.joinToString("\n") { "• ${displayToken(field, it)}" }
                        val bulletView = TextView(context).apply {
                            text = bulletText
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (6 * density).toInt()
                            }
                        }
                        cardContent.addView(bulletView)
                    }
                } else if (!isCalculated && format == DisplayFormat.MULTILINE && fieldValue.isNotEmpty()) {
                    val labelView = TextView(context).apply {
                        text = field.name
                        textSize = 13f
                        setTextColor(context.getColor(R.color.text_secondary))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (2 * density).toInt()
                        }
                    }
                    cardContent.addView(labelView)
                    val multiView = TextView(context).apply {
                        text = fieldValue
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (6 * density).toInt()
                        }
                    }
                    cardContent.addView(multiView)
                } else {
                    // PLAIN (default) / CALCULATED
                    val displayValue: String
                    val percentileSuffix: String

                    if (isCalculated) {
                        val computedValue = calculatedResults[field.id]
                        val baseValue = computedValue ?: fieldValue.ifEmpty { null }
                        if (baseValue != null) {
                            // "(자동 계산)"과 백분위를 단일 괄호로 통합
                            val infoList = mutableListOf("자동 계산")
                            percentileData[field.id]?.let { info ->
                                info.novelPercentile?.let { infoList.add("작품 상위 ${"%.0f".format(it)}%") }
                                info.universePercentile?.let { infoList.add("세계관 상위 ${"%.0f".format(it)}%") }
                            }
                            displayValue = "${field.name}: $baseValue (${infoList.joinToString(", ")})"
                        } else {
                            displayValue = getStringWithArg(R.string.auto_calculated_label, field.name)
                        }
                        percentileSuffix = "" // CALCULATED은 위에서 통합 완료
                    } else {
                        displayValue = "${field.name}: ${
                            if (fieldValue.isEmpty()) "-" else displayToken(field, fieldValue.trim())
                        }"
                        // 백분위 표기 추가 (NUMBER, GRADE 등)
                        percentileSuffix = percentileData[field.id]?.let { info ->
                            val parts = mutableListOf<String>()
                            info.novelPercentile?.let { parts.add("작품 상위 ${"%.0f".format(it)}%") }
                            info.universePercentile?.let { parts.add("세계관 상위 ${"%.0f".format(it)}%") }
                            if (parts.isNotEmpty()) " (${parts.joinToString(" / ")})" else null
                        } ?: ""
                    }

                    val rowView = TextView(context).apply {
                        text = displayValue + percentileSuffix
                        textSize = 14f
                        if (isCalculated) {
                            setTextColor(context.getColor(R.color.text_secondary))
                            setTypeface(null, Typeface.ITALIC)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (4 * density).toInt()
                        }
                    }
                    cardContent.addView(rowView)
                }
            }

            card.addView(cardContent)
            container.addView(card)
        }

        // 체형 분석 카드 (설계 5-4-2 — 위계 + 실루엣 + 바로가기)
        bodyCardData(fields, valueMap)?.let { data ->
            container.addView(buildBodyAnalysisCard(context, density, data))
        }
    }

    fun displayResolvedFields(
        fields: List<FieldDefinition>,
        resolvedState: Map<String, String>
    ) {
        val container = containerGetter()
        container.removeAllViews()

        val context = contextGetter()
        val density = resourcesGetter().displayMetrics.density

        // Show special keys (birth, death, alive, age) if present
        val specialFields = mutableListOf<Pair<String, String>>()
        resolvedState[CharacterStateChange.KEY_BIRTH]?.let {
            if (it.isNotEmpty()) specialFields.add(getString(R.string.birth) to it)
        }
        resolvedState[CharacterStateChange.KEY_DEATH]?.let {
            if (it.isNotEmpty()) specialFields.add(getString(R.string.death) to it)
        }
        resolvedState[CharacterStateChange.KEY_ALIVE]?.let {
            if (it.isNotEmpty()) {
                val displayVal = when (it) {
                    "true" -> getString(R.string.alive)
                    "false" -> getString(R.string.dead)
                    else -> it
                }
                specialFields.add(getString(R.string.alive_status) to displayVal)
            }
        }
        resolvedState[CharacterStateChange.KEY_AGE]?.let {
            if (it.isNotEmpty()) specialFields.add(getString(R.string.age) to it)
        }

        if (specialFields.isNotEmpty()) {
            val specialCard = createCard(context, density)

            val specialContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val specialTitle = TextView(context).apply {
                text = getString(R.string.time_view)
                setTypeface(null, Typeface.BOLD)
                textSize = 16f
                setTextColor(context.getColor(R.color.primary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }
            specialContent.addView(specialTitle)

            for ((name, value) in specialFields) {
                val rowView = TextView(context).apply {
                    text = "$name: $value"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (4 * density).toInt()
                    }
                }
                specialContent.addView(rowView)
            }

            specialCard.addView(specialContent)
            container.addView(specialCard)
        }

        // Group fields and display with resolved values
        val grouped = fields
            .sortedBy { it.displayOrder }
            .groupBy { it.groupName }

        for ((groupName, groupFields) in grouped) {
            val card = createCard(context, density)

            val cardContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = createGroupTitle(context, density, groupName)
            cardContent.addView(titleView)

            for (field in groupFields) {
                val resolvedValue = resolvedState[field.key] ?: ""
                val displayValue = resolvedValue.ifEmpty { "-" }

                val rowView = TextView(context).apply {
                    text = "${field.name}: $displayValue"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (4 * density).toInt()
                    }
                }
                cardContent.addView(rowView)
            }

            card.addView(cardContent)
            container.addView(card)
        }
    }

    /**
     * 섹션 루트를 생성한다(반환된 ViewGroup에 호출부가 cardContent를 addView).
     * CARD: MaterialCardView — 앱 테마 materialCardViewStyle(Widget.App.Card)를 상속해
     *       디자인 시스템 카드(외곽선·flat·radius 12)로 표시.
     * FLAT: 테두리 없는 LinearLayout + 상단 hairline 구분선(패널 내부 섹션용).
     */
    private fun createCard(context: Context, density: Float): ViewGroup {
        return when (sectionStyle) {
            SectionStyle.CARD -> MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                setContentPadding(
                    (16 * density).toInt(),
                    (12 * density).toInt(),
                    (16 * density).toInt(),
                    (12 * density).toInt()
                )
            }
            SectionStyle.FLAT -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // 상단 구분 hairline — 한 패널 안에서 섹션을 나눈다(이중 테두리 방지)
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        maxOf(1, (1 * density).toInt())
                    ).apply {
                        topMargin = (12 * density).toInt()
                        bottomMargin = (12 * density).toInt()
                    }
                    setBackgroundColor(context.getColor(R.color.outline_variant))
                })
            }
        }
    }

    /**
     * CALCULATED 필드의 수식을 FormulaEvaluator로 평가하여 결과를 반환.
     * @return fieldDefinitionId → 계산된 값 문자열
     */
    private fun evaluateCalculatedFields(
        fields: List<FieldDefinition>,
        valueMap: Map<Long, CharacterFieldValue>
    ): Map<Long, String> {
        val calculatedFields = fields.filter { it.type == "CALCULATED" }
        if (calculatedFields.isEmpty()) return emptyMap()

        // fieldKey → value 맵 구성
        val fieldKeyValues = mutableMapOf<String, String>()
        for (field in fields) {
            val v = valueMap[field.id]?.value ?: ""
            if (v.isNotBlank()) fieldKeyValues[field.key] = v
        }

        val results = mutableMapOf<Long, String>()
        val evaluator = FormulaEvaluator(fieldKeyValues, fields)
        for (field in calculatedFields) {
            val formula = try {
                org.json.JSONObject(field.config).optString("formula", "")
            } catch (_: Exception) { "" }
            if (formula.isBlank()) continue
            // 평가 실패도 값으로 남긴다 — 빈 칸은 '값 없음'과 구분되지 않아 고장이 묻힌다(U-9).
            results[field.id] = com.novelcharacter.app.util.FormulaDisplay
                .evaluateForDisplay(formula, evaluator::evaluate)
        }
        return results
    }

    /**
     * 체형 분석 카드가 쓰는 재료 한 벌.
     *
     * [result]가 null이면 분석 엔진의 계약(B/W/H 3값)을 못 채운 것이다 — 그래도 카드는
     * 뜬다. 종전에는 이 경우 카드가 통째로 사라져 **왜 없는지 알 길이 없었다**(원칙 04:
     * 일일이 확인해야 존재를 아는 데이터 금지). 이제 사유와 파트 값을 보이고, 부위가
     * 하나라도 잡히면 실루엣은 그린다(설계 3-3).
     */
    private data class BodyCardData(
        val field: FieldDefinition,
        val config: BodyAnalysisConfig,
        val measured: com.novelcharacter.app.util.BodyMeasurements,
        val result: BodyAnalysisResult?
    )

    private fun bodyCardData(
        fields: List<FieldDefinition>,
        valueMap: Map<Long, CharacterFieldValue>
    ): BodyCardData? {
        // BODY_SIZE 필드 찾기 (SemanticRole 또는 type/key 기반)
        val bodySizeField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.BODY_SIZE }
            ?: fields.find { it.type == "BODY_SIZE" }
            ?: fields.find { it.key in listOf("body_size", "body_type", "three_sizes") }
            ?: return null

        val bodySizeValue = valueMap[bodySizeField.id]?.value ?: return null
        if (bodySizeValue.isBlank()) return null

        // config에서 BodyAnalysisConfig 파싱 (없으면 DEFAULT)
        val config = BodyAnalysisConfig.fromConfig(bodySizeField.config)

        // HEIGHT 필드 찾기
        val heightField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.HEIGHT }
            ?: fields.find { it.key in listOf("height", "키") }

        // WEIGHT 필드 찾기
        val weightField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.WEIGHT }
            ?: fields.find { it.key in listOf("weight", "체중") }

        // 부위 해석은 BodyMeasurements가 단일 소스다 — 여기서 다시 쪼개지 않는다.
        // 종전의 인라인 파서는 파트 라벨을 못 보고 늘 앞 세 값을 B/W/H로 봤다.
        val measured = com.novelcharacter.app.util.BodyMeasurements.resolve(
            field = bodySizeField,
            rawValue = bodySizeValue,
            heightText = heightField?.let { valueMap[it.id]?.value },
            weightText = weightField?.let { valueMap[it.id]?.value },
            config = config
        )
        // 분석 엔진의 계약(B/W/H 3값 필수)은 v1에서 그대로 둔다 — 못 채우면 분석은 없고
        // 카드는 사유·파트 값·(가능하면) 실루엣만 든다.
        val result = if (measured.hasCoreThree) {
            BodyAnalysisHelper().analyze(
                measured.bust!!, measured.waist!!, measured.hip!!,
                measured.heightCm, measured.weightKg, config
            )
        } else null

        return BodyCardData(bodySizeField, config, measured, result)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 체형 분석 카드 (설계 5-4-2 · 판정 P8)
    // ══════════════════════════════════════════════════════════════════════

    /** 2열 배치를 접는 화면 폭(dp). 이보다 좁으면 실루엣과 요약을 위아래로 놓는다. */
    private val narrowScreenDp = 360

    private fun buildBodyAnalysisCard(context: Context, density: Float, data: BodyCardData): View {
        val result = data.result?.let { r -> bodyRankingInfo?.let { r.copy(rankingInNovel = it) } ?: r }

        val card = createCard(context, density)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        content.addView(buildBodyHeader(context, density, data.field))

        // 본문 2열 — 좌 실루엣 / 우 요약. 좁은 화면은 세로로 접는다(5-4-2).
        val narrow = resourcesGetter().configuration.screenWidthDp < narrowScreenDp
        val silhouette = buildCardSilhouette(context, density, data)
        val body = LinearLayout(context).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        if (silhouette != null) body.addView(silhouette)
        body.addView(buildBodySummary(context, density, data, result, narrow || silhouette == null))
        content.addView(body)

        // 실루엣이 없으면 사유와 파트 값을 보인다 (D6) — 카드가 통째로 사라지지 않게.
        if (silhouette == null) buildPartFallback(context, density, data)?.let { content.addView(it) }

        // 고지 — 그림의 기준 몸(P9-②)과 표시 항목을 어디서 고르는가.
        content.addView(bodySubText(context, density, getString(R.string.body_analysis_female_notice)))

        // 자세히 ▾ — 나머지 전부(현행 행 형식 유지). 접힘 상태는 영속하지 않는다(5-1).
        val detail = buildBodyDetail(context, density, data.config, result)
        if (detail.childCount > 0) {
            detail.visibility = View.GONE
            content.addView(TextView(context).apply {
                text = getString(R.string.body_analysis_expand)
                textSize = 13f
                setTextColor(context.getColor(R.color.primary))
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                setOnClickListener {
                    val opening = detail.visibility != View.VISIBLE
                    detail.visibility = if (opening) View.VISIBLE else View.GONE
                    text = getString(
                        if (opening) R.string.body_analysis_collapse else R.string.body_analysis_expand
                    )
                }
            })
            content.addView(detail)
        }

        card.addView(content)
        return card
    }

    /** 머리 행 — 제목 + ⚙(체형 분석 설정 바로가기, 터치 48dp). */
    private fun buildBodyHeader(context: Context, density: Float, field: FieldDefinition): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        }
        row.addView(TextView(context).apply {
            text = getString(R.string.body_analysis_title)
            setTypeface(null, Typeface.BOLD)
            textSize = 16f
            setTextColor(context.getColor(R.color.primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        onOpenBodySettings?.let { open ->
            row.addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_settings)
                setBackgroundResource(borderlessBackground(context))
                setColorFilter(context.getColor(R.color.text_secondary))
                contentDescription = getString(R.string.body_analysis_settings)
                layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                setOnClickListener { open(field) }
            })
        }
        return row
    }

    /** 테두리 없는 물결 배경 — 카드 안 아이콘 버튼이 네모 상자로 보이지 않게. */
    private fun borderlessBackground(context: Context): Int {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val id = attrs.getResourceId(0, 0)
        attrs.recycle()
        return id
    }

    /**
     * 카드의 실루엣 — 탭하면 크게 보기로 간다. 그릴 부위가 하나도 없으면 null이며
     * 그때는 파트 값 폴백이 대신 선다(D6).
     */
    private fun buildCardSilhouette(context: Context, density: Float, data: BodyCardData): View? {
        if (!data.config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_SILHOUETTE)) return null
        val m = com.novelcharacter.app.util.BodySilhouetteSpec
            .measuresFrom(data.measured, data.config.ribOffset) ?: return null
        return SilhouetteView(context).apply {
            measures = m
            showLabels = false      // 카드에서는 기본 끔 — 라벨은 크게 보기·편집기의 몫(5-4-1)
            interactive = false
            layoutParams = LinearLayout.LayoutParams(
                (96 * density).toInt(), (140 * density).toInt()
            ).apply { rightMargin = (12 * density).toInt() }
            onOpenSilhouette?.let { open ->
                isClickable = true
                contentDescription = getString(R.string.body_silhouette_open_large)
                setOnClickListener { open(data.field, data.measured, data.config) }
            }
        }
    }

    /** 우측 요약 — 3축 한 줄 · 체형 태그 칩 · 핵심 지표 3행(5-4-2). */
    private fun buildBodySummary(
        context: Context,
        density: Float,
        data: BodyCardData,
        result: BodyAnalysisResult?,
        fullWidth: Boolean
    ): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (fullWidth) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }

        // 3축 요약 — 편집기와 **같은 설정 한 벌**을 쓴다(두 화면이 같은 말을 하게).
        // 축 이름·경계가 세계관 설정이 된 뒤로(B-93) config를 통째로 넘긴다.
        com.novelcharacter.app.util.BodySilhouetteSpec
            .measuresFrom(data.measured, data.config.ribOffset)?.let { m ->
                val axis = com.novelcharacter.app.util.BodySilhouetteSpec
                    .axisSummary(m, data.config)
                col.addView(TextView(context).apply {
                    // 인자가 둘 이상인 서식은 주입된 getString(1인자)이 못 받는다 — 컨텍스트로 짠다.
                    text = context.getString(
                        R.string.silhouette_axis_summary, axis.torso, axis.cup, axis.hip, axis.line
                    )
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * density).toInt() }
                })
            }

        // 체형 태그 칩
        if (data.config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_BODY_TAGS) &&
            result != null && result.bodyTags.isNotEmpty()
        ) {
            col.addView(ChipGroup(context).apply {
                isSingleLine = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                for (tag in result.bodyTags) {
                    addView(Chip(context).apply {
                        text = tag
                        isClickable = false
                        isCheckable = false
                    })
                }
            })
        }

        // 핵심 지표 3행 — 컵 · 몸통(BMI) · 잘록함(WHR). 장르어가 라벨, 수치는 부제(P8).
        if (data.config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_CUP_SIZE)) {
            col.addView(
                bodyCoreMetric(
                    context, density,
                    getString(R.string.body_core_cup),
                    result?.cupSize ?: getString(R.string.body_core_missing),
                    getString(R.string.body_core_cup_hint)
                )
            )
        }
        if (data.config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_BMI)) {
            val bmi = result?.bmi
            col.addView(
                bodyCoreMetric(
                    context, density,
                    getString(R.string.body_core_bmi),
                    bmi?.let { BodyAnalysisHelper.bmiToneLabel(it) } ?: getString(R.string.body_core_missing),
                    bmi?.let { getStringWithArg(R.string.body_core_bmi_hint, "%.1f".format(it)) }
                        ?: getString(R.string.body_core_missing_hint)
                )
            )
        }
        if (data.config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_WHR)) {
            val whr = result?.whr
            col.addView(
                bodyCoreMetric(
                    context, density,
                    getString(R.string.body_core_whr),
                    whr?.let { BodyAnalysisHelper.waistlineLabel(it) } ?: getString(R.string.body_core_missing),
                    whr?.let { getStringWithArg(R.string.body_core_whr_hint, "%.2f".format(it)) }
                        ?: getString(R.string.body_core_missing_hint)
                )
            )
        }
        return col
    }

    /** 핵심 지표 한 행 — 장르어 라벨 + 값, 그 아래 쉬운 설명 한 줄(5-3). */
    private fun bodyCoreMetric(
        context: Context,
        density: Float,
        label: String,
        value: String,
        hint: String
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
        addView(TextView(context).apply {
            text = "$label: $value"
            textSize = 14f
        })
        addView(TextView(context).apply {
            text = hint
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
        })
    }

    /**
     * 실루엣을 못 그릴 때의 폴백 (D6) — 파트 이름·값과 상대 길이 바, 그리고 사유 한 줄.
     *
     * 값이 아예 없으면 바도 사유도 없다(빈 카드를 만들지 않는다).
     */
    private fun buildPartFallback(context: Context, density: Float, data: BodyCardData): View? {
        val rows = data.measured.partValues.withIndex()
            .filter { (_, text) -> text.isNotBlank() }
        if (rows.isEmpty()) return null

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        }
        val numbers = rows.mapNotNull { (_, t) -> com.novelcharacter.app.util.BodyMeasurements.parseNumber(t) }
        val maxValue = numbers.maxOrNull() ?: 0.0
        for ((index, text) in rows) {
            val slot = data.measured.partSlots.getOrNull(index)
            val name = bodySlotName(slot) ?: getStringWithArg(R.string.body_part_index, index + 1)
            col.addView(TextView(context).apply {
                this.text = context.getString(R.string.body_part_bar_label, name, text)
                textSize = 13f
            })
            val value = com.novelcharacter.app.util.BodyMeasurements.parseNumber(text)
            if (value != null && maxValue > 0) {
                col.addView(View(context).apply {
                    setBackgroundColor(context.getColor(R.color.primary))
                    layoutParams = LinearLayout.LayoutParams(
                        ((value / maxValue) * 120 * density).toInt().coerceAtLeast(1),
                        (4 * density).toInt()
                    ).apply { bottomMargin = (4 * density).toInt() }
                })
            }
        }
        col.addView(bodySubText(context, density, getString(R.string.body_parts_fallback_notice)))
        return col
    }

    private fun bodySlotName(slot: com.novelcharacter.app.data.model.BodySlot?): String? = when (slot) {
        com.novelcharacter.app.data.model.BodySlot.SHOULDER -> getString(R.string.silhouette_slot_shoulder)
        com.novelcharacter.app.data.model.BodySlot.BUST -> getString(R.string.silhouette_slot_bust)
        com.novelcharacter.app.data.model.BodySlot.UNDERBUST -> getString(R.string.silhouette_slot_underbust)
        com.novelcharacter.app.data.model.BodySlot.WAIST -> getString(R.string.silhouette_slot_waist)
        com.novelcharacter.app.data.model.BodySlot.HIP -> getString(R.string.silhouette_slot_hip)
        else -> null
    }

    private fun bodySubText(context: Context, density: Float, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        }

    /**
     * 자세히 영역 — 종전 카드의 나머지 전부(원칙 03: 삭제 없음, 위계만 바뀐다).
     *
     * 핵심층으로 올라간 셋(컵·BMI·WHR)도 **여기서 원 수치로 다시 보인다** — 장르어는
     * 요약이고, 정확한 값을 찾는 사람의 경로를 없애지 않는다.
     */
    private fun buildBodyDetail(
        context: Context,
        density: Float,
        config: BodyAnalysisConfig,
        result: BodyAnalysisResult?
    ): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        if (result == null) return box

        fun addRow(label: String, value: String) {
            box.addView(TextView(context).apply {
                text = "$label: $value"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() }
            })
        }

        fun addSectionTitle(title: String) {
            box.addView(TextView(context).apply {
                text = title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(context.getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (6 * density).toInt()
                    bottomMargin = (2 * density).toInt()
                }
            })
        }

        fun addSubRow(text: String) {
            box.addView(TextView(context).apply {
                this.text = text
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (2 * density).toInt() }
            })
        }

        // 체형 분류 + 실루엣 설명
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_BODY_TYPE)) {
            result.bodyType?.let { addRow(getString(R.string.body_type_label), it) }
        }
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_SILHOUETTE)) {
            result.silhouetteDescription?.let { addSubRow(it) }
        }

        // 컵 사이즈 (보정 표시)
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_CUP_SIZE)) {
            result.cupSize?.let { cup ->
                val diffStr = result.bustDiff?.let { d -> " (차이 ${"%.1f".format(d)}cm)" } ?: ""
                val ubStr = if (result.adjustedUnderbust != null && result.adjustedUnderbust != result.waist) {
                    " [보정 UB ${"%.0f".format(result.adjustedUnderbust)}]"
                } else ""
                addRow(getString(R.string.cup_size_label), "$cup$diffStr$ubStr")
            }
        }

        // 프레임 + 프로포션
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_FRAME_SIZE) && result.frameSize != null) {
            val heightStr = result.height?.let { " (${"%.0f".format(it)}cm)" } ?: ""
            addRow(getString(R.string.body_frame_label), "${result.frameSize}$heightStr")
        }
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_PROPORTION)) {
            result.volumeIndex?.let { vi ->
                addRow(getString(R.string.body_volume_label), "${"%.2f".format(vi)} (${BodyAnalysisHelper.volumeLabel(vi)})")
            }
            result.curvesIndex?.let { ci ->
                addRow(getString(R.string.body_curves_label), "${"%.2f".format(ci)} (${BodyAnalysisHelper.curvesLabel(ci)})")
            }
        }

        // 측정값 분석
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_BWH_DIFF)) {
            addSectionTitle(getString(R.string.body_bwh_diff_label))
            addRow("B-W", "%+.0fcm".format(result.bustWaistDiff))
            addRow("W-H", "%+.0fcm".format(-result.waistHipDiff))
            addRow("B-H", "%+.0fcm".format(result.bustHipDiff))
        }
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_NORMALIZED_RATIO)) {
            addRow(getString(R.string.body_normalized_ratio_label), result.normalizedRatio)
        }

        // 신체 지표 — 핵심층의 장르어에 대응하는 원 수치
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_BMI)) {
            result.bmi?.let { bmi ->
                val categoryStr = result.bmiCategory?.let { " ($it)" } ?: ""
                addRow(getString(R.string.bmi_label), "${"%.1f".format(bmi)}$categoryStr")
            }
        }
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_WHR)) {
            result.whr?.let { addRow(getString(R.string.whr_label), "%.2f".format(it)) }
        }

        // 키 대비 비율
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_HEIGHT_RELATIVE) &&
            result.bustHeightRatio != null
        ) {
            addSectionTitle(getString(R.string.body_height_relative_label))
            result.bustHeightRatio.let {
                addSubRow("가슴/키: ${"%.1f".format(it * 100)}% (참고: 51~53%)")
            }
            result.waistHeightRatio?.let {
                addSubRow("허리/키: ${"%.1f".format(it * 100)}% (참고: 38~42%)")
            }
            result.hipHeightRatio?.let {
                addSubRow("엉덩이/키: ${"%.1f".format(it * 100)}% (참고: 52~56%)")
            }
        }

        // 목표 비율 (P8 — '골든 비율'의 개명. 공식은 그대로이고 이상값은 설정이 든다)
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_GOLDEN_RATIO) &&
            result.goldenRatioScore != null
        ) {
            addSectionTitle(getString(R.string.body_target_ratio_label))
            // 무엇과의 거리인지 말한다(5-3) — 이상 몸 > 비율 고정 > 장르 기준 순으로 밝힌다.
            val idealBody = config.idealBody
            addSubRow(
                when {
                    idealBody != null && idealBody.isComplete -> {
                        val fmt = { v: Double -> com.novelcharacter.app.util.BodyEditorModel.formatValue(v) }
                        context.getString(
                            R.string.body_target_ratio_hint_body,
                            fmt(idealBody.bust!!), fmt(idealBody.waist!!), fmt(idealBody.hip!!),
                            fmt(idealBody.heightCm ?: com.novelcharacter.app.util.BodySilhouetteSpec.BASE.height)
                        )
                    }
                    config.goldenRatioIdeals.isNotEmpty() -> getString(R.string.body_target_ratio_hint_custom)
                    else -> getString(R.string.body_target_ratio_hint_auto)
                }
            )
            addSubRow(getStringWithArg(R.string.body_target_ratio_score, "%.0f".format(result.goldenRatioScore)))
            result.goldenRatioDetails?.forEach { item ->
                addSubRow("${item.label}: ${"%.2f".format(item.actual)} (이상: ${"%.2f".format(item.ideal)}, %+.1f%%)".format(item.deviationPercent))
            }
        }

        // 작품 내 순위 — 축별로(P8: 가슴·힙·키). 허리는 뒤에 남긴다.
        val ranking = result.rankingInNovel
        if (config.isInsightEnabled(BodyAnalysisConfig.INSIGHT_RANKING) &&
            ranking != null && ranking.totalCharacters > 1
        ) {
            addSectionTitle(getString(R.string.body_ranking_label))
            val parts = mutableListOf<String>()
            ranking.bustRank?.let { parts.add(getStringWithArg(R.string.body_rank_bust, it)) }
            ranking.hipRank?.let { parts.add(getStringWithArg(R.string.body_rank_hip, it)) }
            ranking.heightRank?.let { parts.add(getStringWithArg(R.string.body_rank_height, it)) }
            ranking.waistRank?.let { parts.add(getStringWithArg(R.string.body_rank_waist, it)) }
            if (parts.isNotEmpty()) {
                addSubRow(parts.joinToString(" / "))
                addSubRow(getStringWithArg(R.string.body_rank_total, ranking.totalCharacters))
            }
        }
        return box
    }

    private fun createGroupTitle(context: Context, density: Float, groupName: String): TextView {
        return TextView(context).apply {
            text = groupName.ifEmpty { getString(R.string.other) }
            setTypeface(null, Typeface.BOLD)
            textSize = 16f
            setTextColor(context.getColor(R.color.primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
    }
}
