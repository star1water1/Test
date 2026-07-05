package com.novelcharacter.app.ui.assistant

import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.ui.stats.PatternSeverity
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.util.BirthdayHelper

/** 이름 목록 미리보기 — 앞의 [max]개만 콤마로 잇는다. */
private fun namesPreview(names: List<String>, max: Int = 3): String =
    names.take(max).joinToString(", ")

/**
 * 정합성 문제(오류) provider — 진짜 모순을 진지한 톤으로, 교정 경로와 함께 제시한다.
 * 유형별 집계 1카드(캐릭터마다 카드 X). 카드를 누르면 "다음에 고칠" 캐릭터 상세로 이동하고,
 * 거기서 기존 나이-출생연도 교정 플로우로 바로잡을 수 있다.
 */
class ConsistencyProvider : InsightProvider {
    override val category = InsightCategory.CONSISTENCY

    override fun provide(ctx: InsightContext): List<AssistantInsight> {
        val res = ctx.context
        val out = mutableListOf<AssistantInsight>()

        val ages = ctx.consistency.ageMismatches
        if (ages.isNotEmpty()) {
            val first = ages.first()
            val detail = if (ages.size == 1) {
                res.getString(
                    R.string.assistant_age_detail_one,
                    first.characterName, first.standardYear, first.inputAge,
                    first.inputBirthYear, first.suggestedBirthYear
                )
            } else {
                res.getString(
                    R.string.assistant_age_detail_many,
                    first.characterName, ages.size - 1
                )
            }
            out.add(
                AssistantInsight(
                    id = "consistency_age",
                    category = category,
                    severity = InsightSeverity.CONSISTENCY,
                    title = res.getString(R.string.assistant_age_title, ages.size),
                    detail = detail,
                    count = ages.size,
                    action = InsightAction.Navigate(
                        destId = R.id.characterDetailFragment,
                        characterId = first.characterId,
                        label = res.getString(R.string.assistant_age_action)
                    )
                )
            )
        }

        val deaths = ctx.consistency.deathBeforeBirth
        if (deaths.isNotEmpty()) {
            val first = deaths.first()
            val detail = if (deaths.size == 1) {
                res.getString(
                    R.string.assistant_death_detail_one,
                    first.characterName, first.birthYear, first.deathYear
                )
            } else {
                res.getString(
                    R.string.assistant_death_detail_many,
                    first.characterName, deaths.size - 1
                )
            }
            out.add(
                AssistantInsight(
                    id = "consistency_death",
                    category = category,
                    severity = InsightSeverity.CONSISTENCY,
                    title = res.getString(R.string.assistant_death_title, deaths.size),
                    detail = detail,
                    count = deaths.size,
                    action = InsightAction.Navigate(
                        destId = R.id.characterDetailFragment,
                        characterId = first.characterId,
                        label = res.getString(R.string.assistant_death_action)
                    )
                )
            )
        }

        return out
    }
}

/**
 * 데이터 건강 제안 provider — [DataHealthStats]를 유형별 집계 카드로 흡수하고
 * 상세는 기존 데이터 건강 화면으로 링크한다(로직 중복 없음). 부드러운 톤, 끌 수 있음.
 * 완성도(fill-rate %)는 헤드라인으로 쓰지 않는다(원칙 02 안티패턴 회피).
 */
class HealthProvider : InsightProvider {
    override val category = InsightCategory.HEALTH

    override fun provide(ctx: InsightContext): List<AssistantInsight> {
        val res = ctx.context
        val h = ctx.dataHealth
        val out = mutableListOf<AssistantInsight>()
        val action = InsightAction.Navigate(
            destId = R.id.statsDataHealthDetailFragment,
            label = res.getString(R.string.assistant_health_action)
        )

        if (h.noNovelChars.isNotEmpty()) {
            out.add(
                card(
                    "health_no_novel", InsightSeverity.HEALTH_NO_NOVEL,
                    res.getString(R.string.assistant_health_no_novel_title, h.noNovelChars.size),
                    res.getString(R.string.assistant_health_no_novel_detail, namesPreview(h.noNovelChars)),
                    h.noNovelChars.size, action
                )
            )
        }
        if (h.isolatedChars.isNotEmpty()) {
            out.add(
                card(
                    "health_isolated", InsightSeverity.HEALTH_ISOLATED,
                    res.getString(R.string.assistant_health_isolated_title, h.isolatedChars.size),
                    res.getString(R.string.assistant_health_isolated_detail, namesPreview(h.isolatedChars)),
                    h.isolatedChars.size, action
                )
            )
        }
        if (h.unlinkedChars.isNotEmpty()) {
            out.add(
                card(
                    "health_unlinked", InsightSeverity.HEALTH_UNLINKED,
                    res.getString(R.string.assistant_health_unlinked_title, h.unlinkedChars.size),
                    res.getString(R.string.assistant_health_unlinked_detail, namesPreview(h.unlinkedChars)),
                    h.unlinkedChars.size, action
                )
            )
        }
        if (h.duplicateTags.isNotEmpty()) {
            out.add(
                card(
                    "health_dup_tags", InsightSeverity.HEALTH_DUP_TAGS,
                    res.getString(R.string.assistant_health_dup_tags_title, h.duplicateTags.size),
                    res.getString(R.string.assistant_health_dup_tags_detail, namesPreview(h.duplicateTags)),
                    h.duplicateTags.size, action
                )
            )
        }
        if (h.emptyDescRelationships > 0) {
            out.add(
                card(
                    "health_empty_rel", InsightSeverity.HEALTH_EMPTY_REL,
                    res.getString(R.string.assistant_health_empty_rel_title, h.emptyDescRelationships),
                    res.getString(R.string.assistant_health_empty_rel_detail),
                    h.emptyDescRelationships, action
                )
            )
        }
        if (h.noImageChars.isNotEmpty()) {
            out.add(
                card(
                    "health_no_image", InsightSeverity.HEALTH_NO_IMAGE,
                    res.getString(R.string.assistant_health_no_image_title, h.noImageChars.size),
                    res.getString(R.string.assistant_health_no_image_detail, namesPreview(h.noImageChars)),
                    h.noImageChars.size, action
                )
            )
        }
        return out
    }

    private fun card(
        id: String, severity: Int, title: String, detail: String,
        count: Int, action: InsightAction
    ) = AssistantInsight(
        id = id, category = category, severity = severity,
        title = title, detail = detail, count = count, action = action
    )
}

/**
 * 편향·패턴 인사이트 provider — 기존 패턴 감지 엔진([StatsDataProvider.detectPatterns])의
 * 결과를 카드로 옮긴다. 필드 값 쏠림·사건 밀도·서사 공백 등 실질적 인사이트의 핵심이며,
 * 커스텀 필드도 자동으로 포함된다(적응형, 원칙 05).
 */
class BiasProvider : InsightProvider {
    override val category = InsightCategory.BIAS

    override fun provide(ctx: InsightContext): List<AssistantInsight> {
        val res = ctx.context
        return ctx.patterns.map { p ->
            val severity = when {
                p.type == PatternType.BALANCE -> InsightSeverity.BIAS_INFO
                p.severity == PatternSeverity.HIGH -> InsightSeverity.BIAS_HIGH
                p.severity == PatternSeverity.MEDIUM -> InsightSeverity.BIAS_MEDIUM
                else -> InsightSeverity.BIAS_LOW
            }
            val detail = if (p.suggestion.isBlank()) p.description
                else "${p.description}\n\n${p.suggestion}"
            val action = p.fieldDefId?.let {
                InsightAction.Navigate(
                    destId = R.id.statsFieldInsightFragment,
                    label = res.getString(R.string.assistant_bias_action)
                )
            }
            AssistantInsight(
                // 필드 패턴은 fieldDefId로, 비필드 패턴(집중·공백·작품비교)은 설명 해시로 유일하게 식별.
                id = "bias_${p.type.name}_${p.fieldDefId ?: p.description.hashCode()}",
                category = category,
                severity = severity,
                title = p.title,
                detail = detail,
                count = 0,
                // 개수 대신 심각도로 재노출 판정 — 편향이 악화되면(MEDIUM→HIGH) 다시 뜬다.
                resurfaceValue = severity,
                action = action
            )
        }
    }
}

/**
 * 넛지 provider — 임박한 생일, 미사용 이름은행, 오래 방치된 캐릭터 등 상황 알림.
 * 시의성 있는 정보를 먼저 띄우되, 사소한 것은 임계치로 걸러 넛지가 잔소리가 되지 않게 한다.
 */
class NudgeProvider : InsightProvider {
    override val category = InsightCategory.NUDGE

    override fun provide(ctx: InsightContext): List<AssistantInsight> {
        val res = ctx.context
        val s = ctx.snapshot
        val out = mutableListOf<AssistantInsight>()

        // 1) 임박한 생일 (실제 달력 기준, 기존 위젯/워커와 동일 규칙)
        val birthChanges = s.stateChanges.filter {
            it.fieldKey == CharacterStateChange.KEY_BIRTH && it.month != null && it.day != null
        }
        val upcoming = BirthdayHelper.filterUpcoming(birthChanges, daysAhead = 7)
        if (upcoming.isNotEmpty()) {
            val nearest = upcoming.first()
            val charById = s.characters.associateBy { it.id }
            val nearestName = charById[nearest.characterId]?.name ?: ""
            val whenText = whenText(res, nearest.daysUntil)
            val detail = if (upcoming.size == 1) {
                res.getString(R.string.assistant_nudge_birthday_detail_one, nearestName, whenText)
            } else {
                res.getString(
                    R.string.assistant_nudge_birthday_detail_many,
                    nearestName, upcoming.size - 1, whenText
                )
            }
            out.add(
                AssistantInsight(
                    id = "nudge_birthday",
                    category = category,
                    severity = InsightSeverity.NUDGE_BIRTHDAY,
                    title = res.getString(R.string.assistant_nudge_birthday_title, upcoming.size),
                    detail = detail,
                    count = upcoming.size,
                    // 개수가 같아도 날짜가 가까워지면 다시 뜨도록: 가까울수록 값이 커진다.
                    resurfaceValue = upcoming.size + (7 - nearest.daysUntil),
                    action = InsightAction.Navigate(
                        destId = R.id.characterDetailFragment,
                        characterId = nearest.characterId,
                        label = res.getString(R.string.assistant_nudge_birthday_action)
                    )
                )
            )
        }

        // 2) 미사용 이름은행 (충분히 쌓였을 때만)
        val unusedNames = s.nameBank.filter { it.usedByCharacterId == null }
        if (unusedNames.size >= UNUSED_NAMES_THRESHOLD) {
            out.add(
                AssistantInsight(
                    id = "nudge_unused_names",
                    category = category,
                    severity = InsightSeverity.NUDGE_UNUSED_NAMES,
                    title = res.getString(R.string.assistant_nudge_unused_names_title, unusedNames.size),
                    detail = res.getString(R.string.assistant_nudge_unused_names_detail),
                    count = unusedNames.size,
                    action = InsightAction.Navigate(
                        destId = R.id.nameBankFragment,
                        label = res.getString(R.string.assistant_nudge_unused_names_action)
                    )
                )
            )
        }

        // 3) 오래 손대지 않은 캐릭터 (3개월+, 3명 이상일 때만)
        val now = System.currentTimeMillis()
        val stale = s.characters
            .filter { now - it.updatedAt > STALE_THRESHOLD_MS }
            .sortedBy { it.updatedAt }
        if (stale.size >= STALE_MIN_COUNT) {
            val oldest = stale.first()
            out.add(
                AssistantInsight(
                    id = "nudge_stale",
                    category = category,
                    severity = InsightSeverity.NUDGE_STALE,
                    title = res.getString(R.string.assistant_nudge_stale_title, stale.size),
                    detail = res.getString(
                        R.string.assistant_nudge_stale_detail, namesPreview(stale.map { it.name })
                    ),
                    count = stale.size,
                    action = InsightAction.Navigate(
                        destId = R.id.characterDetailFragment,
                        characterId = oldest.id,
                        label = res.getString(R.string.assistant_nudge_stale_action)
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
