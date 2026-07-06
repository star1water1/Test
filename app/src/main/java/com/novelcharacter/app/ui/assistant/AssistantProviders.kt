package com.novelcharacter.app.ui.assistant

import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.ui.stats.PatternInsight
import com.novelcharacter.app.ui.stats.PatternSeverity
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.util.BirthdayHelper
import com.novelcharacter.app.util.CharacterImageLoader

/** 이름 목록 미리보기 — 앞의 [max]개만 콤마로 잇는다. */
private fun namesPreview(names: List<String>, max: Int = 3): String =
    names.take(max).joinToString(", ")

/** 캐릭터 상세로 이동하는 액션(공통). */
private fun openCharacter(res: android.content.Context, characterId: Long): InsightAction.Navigate =
    InsightAction.Navigate(
        destId = R.id.characterDetailFragment,
        characterId = characterId,
        label = res.getString(R.string.assistant_action_open_character)
    )

/**
 * 집계 카드 조립 — **핵심 규칙**: 영향 캐릭터가 1명이면 기존 인라인 기본버튼, **2명 이상이면 기본버튼이
 * '전체 보기(N명)'**가 되어 [AssistantInsight.affected] 전체를 시트로 펼친다(대량 데이터 대응).
 * many일 때는 "첫 캐릭터 열기" 같은 단건 부가액션은 시트가 대신하므로 제거하고, 카테고리 공통 부가액션만 남긴다.
 */
private fun aggregateCard(
    res: android.content.Context,
    id: String,
    category: InsightCategory,
    severity: Int,
    title: String,
    detail: String,
    affected: List<AffectedRow>,
    singlePrimary: InsightAction,
    singleExtraSecondary: List<InsightAction> = emptyList(),
    alwaysSecondary: List<InsightAction> = emptyList(),
    resurfaceValue: Int = affected.size
): AssistantInsight {
    val many = affected.size > 1
    val primary = if (many) {
        InsightAction.ShowAffected(res.getString(R.string.assistant_action_view_all, affected.size))
    } else singlePrimary
    val secondaries = (if (many) emptyList() else singleExtraSecondary) + alwaysSecondary
    return AssistantInsight(
        id = id, category = category, severity = severity, title = title, detail = detail,
        count = affected.size, resurfaceValue = resurfaceValue,
        primaryAction = primary, secondaryActions = secondaries, affected = affected
    )
}

/**
 * 정합성 문제(오류) provider — 진짜 모순을 진지한 톤으로, 명료한 설명과 교정 경로와 함께 제시한다.
 * 여러 명이면 '전체 보기'로 전원을 펼쳐 각각 그 자리에서 교정(통계탭이 못 하는 차별점).
 */
class ConsistencyProvider : InsightProvider {
    override val category = InsightCategory.CONSISTENCY

    override fun provide(ctx: InsightContext): List<AssistantInsight> {
        val res = ctx.context
        val out = mutableListOf<AssistantInsight>()
        // ConsistencyChecker 결과는 id/name만 담으므로 썸네일 imagePaths·정렬용 updatedAt은 스냅샷에서 조회.
        val charById = ctx.snapshot.characters.associateBy { it.id }

        val ages = ctx.consistency.ageMismatches
        if (ages.isNotEmpty()) {
            val first = ages.first()
            val detail = if (ages.size == 1) {
                res.getString(
                    R.string.assistant_age_detail_one,
                    first.characterName, first.standardYear, first.inputAge,
                    first.inputBirthYear, first.suggestedBirthYear, first.expectedAge
                )
            } else {
                res.getString(
                    R.string.assistant_age_detail_many,
                    first.characterName, ages.size - 1, first.standardYear
                )
            }
            val affected = ages.sortedBy { it.characterName }.map {
                AffectedRow(
                    characterId = it.characterId,
                    name = it.characterName,
                    // 기준연도·예상 나이를 함께 보여 무엇이 왜 어긋났는지 행에서도 즉시 이해되게(피드백).
                    subtitle = res.getString(
                        R.string.assistant_affected_age_subtitle,
                        it.standardYear, it.inputAge, it.inputBirthYear, it.expectedAge
                    ),
                    fixType = AffectedFixType.AGE_LINKAGE,
                    imagePath = CharacterImageLoader.firstImagePath(charById[it.characterId]?.imagePaths),
                    updatedAt = charById[it.characterId]?.updatedAt
                )
            }
            out.add(
                aggregateCard(
                    res, "consistency_age", category, InsightSeverity.CONSISTENCY,
                    res.getString(R.string.assistant_age_title, ages.size), detail, affected,
                    singlePrimary = InsightAction.Fix(
                        InsightAction.FixKind.AgeLinkage(first.characterId),
                        res.getString(R.string.assistant_action_fix)
                    ),
                    singleExtraSecondary = listOf(openCharacter(res, first.characterId))
                )
            )
        }

        val deaths = ctx.consistency.deathBeforeBirth
        if (deaths.isNotEmpty()) {
            val first = deaths.first()
            val detail = if (deaths.size == 1) {
                res.getString(R.string.assistant_death_detail_one, first.characterName, first.birthYear, first.deathYear)
            } else {
                res.getString(R.string.assistant_death_detail_many, first.characterName, deaths.size - 1)
            }
            // 사망<출생은 무엇이 오타인지 알 수 없어 안전한 자동교정이 없다 → 열기 전용.
            val affected = deaths.sortedBy { it.characterName }.map {
                AffectedRow(
                    characterId = it.characterId,
                    name = it.characterName,
                    subtitle = res.getString(R.string.assistant_affected_death_subtitle, it.birthYear, it.deathYear),
                    imagePath = CharacterImageLoader.firstImagePath(charById[it.characterId]?.imagePaths),
                    updatedAt = charById[it.characterId]?.updatedAt
                )
            }
            out.add(
                aggregateCard(
                    res, "consistency_death", category, InsightSeverity.CONSISTENCY,
                    res.getString(R.string.assistant_death_title, deaths.size), detail, affected,
                    singlePrimary = InsightAction.Navigate(
                        R.id.characterDetailFragment, first.characterId,
                        res.getString(R.string.assistant_action_check)
                    )
                )
            )
        }

        return out
    }
}

/**
 * 데이터 건강 제안 provider — 부드럽고 끌 수 있음. 캐릭터 특정 카드는 여러 명이면 '전체 보기'로,
 * 아니면 그 캐릭터를 바로 연다. id는 스냅샷에서 직접 산출(`DataHealthStats`는 이름만 담음).
 */
class HealthProvider : InsightProvider {
    override val category = InsightCategory.HEALTH

    override fun provide(ctx: InsightContext): List<AssistantInsight> {
        val res = ctx.context
        val s = ctx.snapshot
        val out = mutableListOf<AssistantInsight>()

        val relCharIds = s.relationships.flatMap { listOf(it.characterId1, it.characterId2) }.toSet()
        val eventCharIds = s.crossRefs.map { it.characterId }.toSet()

        // 작품 미배정 — 그 자리 '작품 지정'(단건) / 전체 보기(다건, 시트에서 개별 배정)
        val noNovel = s.characters.filter { it.novelId == null }.sortedBy { it.name }
        if (noNovel.isNotEmpty()) {
            val first = noNovel.first()
            val affected = noNovel.map {
                AffectedRow(
                    it.id, it.name, fixType = AffectedFixType.ASSIGN_NOVEL,
                    imagePath = CharacterImageLoader.firstImagePath(it.imagePaths),
                    updatedAt = it.updatedAt
                )
            }
            out.add(
                aggregateCard(
                    res, "health_no_novel", category, InsightSeverity.HEALTH_NO_NOVEL,
                    res.getString(R.string.assistant_health_no_novel_title, noNovel.size),
                    res.getString(R.string.assistant_health_no_novel_detail, namesPreview(noNovel.map { it.name })),
                    affected,
                    singlePrimary = InsightAction.Fix(
                        InsightAction.FixKind.AssignNovel(first.id, first.name),
                        res.getString(R.string.assistant_action_assign_novel)
                    ),
                    singleExtraSecondary = listOf(openCharacter(res, first.id))
                )
            )
        }

        val healthDetailAction = InsightAction.Navigate(
            destId = R.id.statsDataHealthDetailFragment,
            label = res.getString(R.string.assistant_health_action)
        )
        // 관계 없음 / 사건 없음 / 이미지 없음 — 열기 전용, 다건이면 전체 보기 + 데이터 건강
        characterHealthCard(
            res, "health_isolated", InsightSeverity.HEALTH_ISOLATED,
            s.characters.filter { it.id !in relCharIds }.sortedBy { it.name },
            { n -> res.getString(R.string.assistant_health_isolated_title, n) },
            { p -> res.getString(R.string.assistant_health_isolated_detail, p) },
            healthDetailAction
        )?.let(out::add)
        characterHealthCard(
            res, "health_unlinked", InsightSeverity.HEALTH_UNLINKED,
            s.characters.filter { it.id !in eventCharIds }.sortedBy { it.name },
            { n -> res.getString(R.string.assistant_health_unlinked_title, n) },
            { p -> res.getString(R.string.assistant_health_unlinked_detail, p) },
            healthDetailAction
        )?.let(out::add)
        characterHealthCard(
            res, "health_no_image", InsightSeverity.HEALTH_NO_IMAGE,
            s.characters.filter { it.imagePaths.isBlank() || it.imagePaths == "[]" }.sortedBy { it.name },
            { n -> res.getString(R.string.assistant_health_no_image_title, n) },
            { p -> res.getString(R.string.assistant_health_no_image_detail, p) },
            healthDetailAction
        )?.let(out::add)

        // 캐릭터 특정이 아닌 카드 — 데이터 건강 화면으로만 이동(affected 없음)
        val h = ctx.dataHealth
        if (h.duplicateTags.isNotEmpty()) out.add(
            AssistantInsight(
                id = "health_dup_tags", category = category, severity = InsightSeverity.HEALTH_DUP_TAGS,
                title = res.getString(R.string.assistant_health_dup_tags_title, h.duplicateTags.size),
                detail = res.getString(R.string.assistant_health_dup_tags_detail, namesPreview(h.duplicateTags)),
                count = h.duplicateTags.size, primaryAction = healthDetailAction
            )
        )
        if (h.emptyDescRelationships > 0) out.add(
            AssistantInsight(
                id = "health_empty_rel", category = category, severity = InsightSeverity.HEALTH_EMPTY_REL,
                title = res.getString(R.string.assistant_health_empty_rel_title, h.emptyDescRelationships),
                detail = res.getString(R.string.assistant_health_empty_rel_detail),
                count = h.emptyDescRelationships, primaryAction = healthDetailAction
            )
        )
        return out
    }

    /** 캐릭터 특정 건강 카드(열기 전용): 단건=첫 캐릭터 열기, 다건=전체 보기. 데이터 건강은 항상 부가. */
    private fun characterHealthCard(
        res: android.content.Context, id: String, severity: Int,
        chars: List<com.novelcharacter.app.data.model.Character>,
        title: (Int) -> String, detail: (String) -> String,
        healthDetailAction: InsightAction
    ): AssistantInsight? {
        if (chars.isEmpty()) return null
        val first = chars.first()
        val affected = chars.map {
            AffectedRow(
                it.id, it.name,
                imagePath = CharacterImageLoader.firstImagePath(it.imagePaths),
                updatedAt = it.updatedAt
            )
        }
        return aggregateCard(
            res, id, category, severity,
            title(chars.size), detail(namesPreview(chars.map { it.name })), affected,
            singlePrimary = openCharacter(res, first.id),
            alwaysSecondary = listOf(healthDetailAction)
        )
    }
}

/**
 * 편향·패턴 인사이트 provider — "어쩌라고" 해결(실질화):
 * ① 편중/이상치 카드는 **해당 값 캐릭터를 '전체 보기'로 드릴다운**(통계탭이 못 하는 차별점).
 *    다세계관 병합 id 전체를 순회해 과소집계 없이 뽑는다.
 * ② **규모 제어**: 최소 모집단 미만 필드 카드 제외(소표본 노이즈), 심각도순 카드 상한 — 데이터가
 *    많아질수록 카드가 쏟아지던 문제를 막는다. 임계값은 사용자 설정([AssistantPrefs]).
 */
class BiasProvider : InsightProvider {
    override val category = InsightCategory.BIAS

    override fun provide(ctx: InsightContext): List<AssistantInsight> {
        val res = ctx.context
        val minPop = AssistantPrefs(res).biasMinPopulation()
        // 필드 편향/이상치/균형 카드는 스코프 모집단이 최소치 미만이면 제외(소표본 노이즈).
        // 카드 수 상한은 '숨김 반영 후'에 적용해야 숨긴 카드가 슬롯을 먹지 않으므로 ViewModel에서 처리한다.
        return ctx.patterns
            .filterNot { it.mergedFieldDefIds.isNotEmpty() && it.population in 1 until minPop }
            .map { p -> buildCard(res, ctx, p, severityOf(p)) }
    }

    private fun severityOf(p: PatternInsight): Int = when {
        p.type == PatternType.BALANCE -> InsightSeverity.BIAS_INFO
        p.severity == PatternSeverity.HIGH -> InsightSeverity.BIAS_HIGH
        p.severity == PatternSeverity.MEDIUM -> InsightSeverity.BIAS_MEDIUM
        else -> InsightSeverity.BIAS_LOW
    }

    private fun buildCard(res: android.content.Context, ctx: InsightContext, p: PatternInsight, severity: Int): AssistantInsight {
        val id = "bias_${p.type.name}_${p.fieldDefId ?: p.description.hashCode()}"
        val detail = if (p.suggestion.isBlank()) p.description else "${p.description}\n\n${p.suggestion}"

        // 드릴다운(편중/이상치): 해당 값 캐릭터를 펼쳐 각자 열어볼 수 있게 → 카드가 실제로 쓸모 있어진다.
        if (p.drilldownValues.isNotEmpty() && p.mergedFieldDefIds.isNotEmpty()) {
            val chars = ctx.statsProvider.getCharactersByFieldKeyValues(
                ctx.snapshot, p.mergedFieldDefIds, p.drilldownValues.toSet(), p.drilldownExclude
            )
            if (chars.isNotEmpty()) {
                val updatedById = ctx.snapshot.characters.associate { it.id to it.updatedAt }
                val affected = chars.map {
                    AffectedRow(
                        characterId = it.characterId,
                        name = it.characterName,
                        // 편중(단일 값 포함)은 모든 행이 같은 값이라 서브타이틀 생략(중복 노이즈). 이상치는 값별로 달라 표시.
                        subtitle = if (p.drilldownValues.size == 1 && !p.drilldownExclude) null
                        else it.fieldValue.ifBlank { null },
                        imagePath = it.imageUri,
                        updatedAt = updatedById[it.characterId]
                    )
                }
                return aggregateCard(
                    res, id, category, severity, p.title, detail, affected,
                    singlePrimary = openCharacter(res, affected.first().characterId),
                    resurfaceValue = severity
                )
            }
        }

        // 폴백: 값별 캐릭터가 없거나(예: 100% 단일값) 필드 패턴이 아니면 필드 인사이트로 이동.
        val primary = p.fieldDefId?.let {
            InsightAction.Navigate(
                destId = R.id.statsFieldInsightFragment,
                label = res.getString(R.string.assistant_bias_action)
            )
        }
        return AssistantInsight(
            id = id, category = category, severity = severity,
            title = p.title, detail = detail, count = 0,
            resurfaceValue = severity, primaryAction = primary
        )
    }
}

/**
 * 넛지 provider — 임박한 생일, 미사용 이름은행, 오래 방치된 캐릭터 등 상황 알림.
 */
class NudgeProvider : InsightProvider {
    override val category = InsightCategory.NUDGE

    override fun provide(ctx: InsightContext): List<AssistantInsight> {
        val res = ctx.context
        val s = ctx.snapshot
        val out = mutableListOf<AssistantInsight>()

        // 1) 임박한 생일 (실제 달력 기준). daysUntil 오름차순.
        val birthChanges = s.stateChanges.filter {
            it.fieldKey == CharacterStateChange.KEY_BIRTH && it.month != null && it.day != null
        }
        val upcoming = BirthdayHelper.filterUpcoming(birthChanges, daysAhead = 7)
        if (upcoming.isNotEmpty()) {
            val charById = s.characters.associateBy { it.id }
            val nearest = upcoming.first()
            val nearestName = charById[nearest.characterId]?.name ?: ""
            val whenText = whenText(res, nearest.daysUntil)
            val detail = if (upcoming.size == 1) {
                res.getString(R.string.assistant_nudge_birthday_detail_one, nearestName, whenText)
            } else {
                res.getString(R.string.assistant_nudge_birthday_detail_many, nearestName, upcoming.size - 1, whenText)
            }
            val affected = upcoming.mapNotNull { u ->
                val c = charById[u.characterId] ?: return@mapNotNull null
                AffectedRow(
                    c.id, c.name, subtitle = whenText(res, u.daysUntil),
                    imagePath = CharacterImageLoader.firstImagePath(c.imagePaths),
                    updatedAt = c.updatedAt
                )
            }
            out.add(
                aggregateCard(
                    res, "nudge_birthday", category, InsightSeverity.NUDGE_BIRTHDAY,
                    res.getString(R.string.assistant_nudge_birthday_title, upcoming.size), detail, affected,
                    singlePrimary = InsightAction.Navigate(
                        R.id.characterDetailFragment, nearest.characterId,
                        res.getString(R.string.assistant_nudge_birthday_action)
                    ),
                    // 개수가 같아도 날짜가 가까워지면 다시 뜨도록.
                    resurfaceValue = upcoming.size + (7 - nearest.daysUntil)
                )
            )
        }

        // 2) 미사용 이름은행 (충분히 쌓였을 때만) — affected 없음(캐릭터 키 아님)
        val unusedNames = s.nameBank.filter { it.usedByCharacterId == null }
        if (unusedNames.size >= UNUSED_NAMES_THRESHOLD) {
            out.add(
                AssistantInsight(
                    id = "nudge_unused_names", category = category, severity = InsightSeverity.NUDGE_UNUSED_NAMES,
                    title = res.getString(R.string.assistant_nudge_unused_names_title, unusedNames.size),
                    detail = res.getString(R.string.assistant_nudge_unused_names_detail),
                    count = unusedNames.size,
                    primaryAction = InsightAction.Navigate(
                        R.id.nameBankFragment, label = res.getString(R.string.assistant_nudge_unused_names_action)
                    )
                )
            )
        }

        // 3) 오래 손대지 않은 캐릭터 (3개월+, 3명 이상). updatedAt 오름차순(오래된 순).
        val now = System.currentTimeMillis()
        val stale = s.characters.filter { now - it.updatedAt > STALE_THRESHOLD_MS }.sortedBy { it.updatedAt }
        if (stale.size >= STALE_MIN_COUNT) {
            val oldest = stale.first()
            val affected = stale.map {
                AffectedRow(
                    it.id, it.name,
                    imagePath = CharacterImageLoader.firstImagePath(it.imagePaths),
                    updatedAt = it.updatedAt
                )
            }
            out.add(
                aggregateCard(
                    res, "nudge_stale", category, InsightSeverity.NUDGE_STALE,
                    res.getString(R.string.assistant_nudge_stale_title, stale.size),
                    res.getString(R.string.assistant_nudge_stale_detail, namesPreview(stale.map { it.name })),
                    affected,
                    singlePrimary = InsightAction.Navigate(
                        R.id.characterDetailFragment, oldest.id,
                        res.getString(R.string.assistant_nudge_stale_action)
                    )
                )
            )
        }

        return out
    }

    private fun whenText(res: android.content.Context, daysUntil: Int): String = when (daysUntil) {
        0 -> res.getString(R.string.assistant_nudge_birthday_today)
        1 -> res.getString(R.string.assistant_nudge_birthday_tomorrow)
        else -> res.getString(R.string.assistant_nudge_birthday_days, daysUntil)
    }

    companion object {
        private const val UNUSED_NAMES_THRESHOLD = 20
        private const val STALE_MIN_COUNT = 3
        private const val STALE_THRESHOLD_MS = 90L * 24 * 60 * 60 * 1000 // 90일
    }
}
