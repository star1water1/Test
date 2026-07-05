package com.novelcharacter.app.ui.assistant

import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.ui.stats.PatternSeverity
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.util.BirthdayHelper

/** 이름 목록 미리보기 — 앞의 [max]개만 콤마로 잇는다. */
private fun namesPreview(names: List<String>, max: Int = 3): String =
    names.take(max).joinToString(", ")

/** 액션 대상 캐릭터 참조(집계 카드가 "첫 대상"을 지목하기 위해 id를 보존). */
private data class CharRef(val id: Long, val name: String)

/** 캐릭터 상세로 이동하는 부가 액션(공통). */
private fun openCharacter(res: android.content.Context, characterId: Long): InsightAction.Navigate =
    InsightAction.Navigate(
        destId = R.id.characterDetailFragment,
        characterId = characterId,
        label = res.getString(R.string.assistant_action_open_character)
    )

/**
 * 정합성 문제(오류) provider — 진짜 모순을 진지한 톤으로, 명료한 설명과 교정 경로와 함께 제시한다.
 * 나이 카드의 기본 액션은 '고치기'(탭 안에서 라이브 재검출 후 교정 다이얼로그) — 통계탭이 못 하는 차별점.
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
                    first.inputBirthYear, first.suggestedBirthYear, first.expectedAge
                )
            } else {
                res.getString(
                    R.string.assistant_age_detail_many,
                    first.characterName, ages.size - 1, first.standardYear
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
                    primaryAction = InsightAction.Fix(
                        kind = InsightAction.FixKind.AgeLinkage(first.characterId),
                        label = res.getString(R.string.assistant_action_fix)
                    ),
                    secondaryActions = listOf(openCharacter(res, first.characterId))
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
            // 사망<출생은 둘 중 무엇이 오타인지 알 수 없어 안전한 자동교정이 없다 → 확인(이동)만.
            out.add(
                AssistantInsight(
                    id = "consistency_death",
                    category = category,
                    severity = InsightSeverity.CONSISTENCY,
                    title = res.getString(R.string.assistant_death_title, deaths.size),
                    detail = detail,
                    count = deaths.size,
                    primaryAction = InsightAction.Navigate(
                        destId = R.id.characterDetailFragment,
                        characterId = first.characterId,
                        label = res.getString(R.string.assistant_action_check)
                    )
                )
            )
        }

        return out
    }
}

/**
 * 데이터 건강 제안 provider — 부드럽고 끌 수 있음. 캐릭터 특정 카드는 **그 캐릭터를 바로 열 수 있게**
 * id를 스냅샷에서 직접 산출한다(`DataHealthStats`는 이름만 담고 id가 없으므로). 완성도(fill-rate %)는
 * 헤드라인으로 쓰지 않는다(원칙 02).
 */
class HealthProvider : InsightProvider {
    override val category = InsightCategory.HEALTH

    override fun provide(ctx: InsightContext): List<AssistantInsight> {
        val res = ctx.context
        val s = ctx.snapshot
        val out = mutableListOf<AssistantInsight>()

        val relCharIds = s.relationships.flatMap { listOf(it.characterId1, it.characterId2) }.toSet()
        val eventCharIds = s.crossRefs.map { it.characterId }.toSet()
        fun refs(list: List<com.novelcharacter.app.data.model.Character>) = list.map { CharRef(it.id, it.name) }

        // 작품 미배정 — '작품 지정'(그 자리에서) + 캐릭터 열기
        val noNovel = refs(s.characters.filter { it.novelId == null })
        if (noNovel.isNotEmpty()) {
            val first = noNovel.first()
            out.add(
                AssistantInsight(
                    id = "health_no_novel",
                    category = category,
                    severity = InsightSeverity.HEALTH_NO_NOVEL,
                    title = res.getString(R.string.assistant_health_no_novel_title, noNovel.size),
                    detail = res.getString(R.string.assistant_health_no_novel_detail, namesPreview(noNovel.map { it.name })),
                    count = noNovel.size,
                    primaryAction = InsightAction.Fix(
                        kind = InsightAction.FixKind.AssignNovel(first.id, first.name),
                        label = res.getString(R.string.assistant_action_assign_novel)
                    ),
                    secondaryActions = listOf(openCharacter(res, first.id))
                )
            )
        }

        // 관계 없음 / 사건 없음 / 이미지 없음 — 기본 '캐릭터 열기' + 데이터 건강 전체
        val isolated = refs(s.characters.filter { it.id !in relCharIds })
        if (isolated.isNotEmpty()) out.add(
            characterCard(
                res, "health_isolated", InsightSeverity.HEALTH_ISOLATED,
                res.getString(R.string.assistant_health_isolated_title, isolated.size),
                res.getString(R.string.assistant_health_isolated_detail, namesPreview(isolated.map { it.name })),
                isolated
            )
        )
        val unlinked = refs(s.characters.filter { it.id !in eventCharIds })
        if (unlinked.isNotEmpty()) out.add(
            characterCard(
                res, "health_unlinked", InsightSeverity.HEALTH_UNLINKED,
                res.getString(R.string.assistant_health_unlinked_title, unlinked.size),
                res.getString(R.string.assistant_health_unlinked_detail, namesPreview(unlinked.map { it.name })),
                unlinked
            )
        )
        val noImage = refs(s.characters.filter { it.imagePaths.isBlank() || it.imagePaths == "[]" })
        if (noImage.isNotEmpty()) out.add(
            characterCard(
                res, "health_no_image", InsightSeverity.HEALTH_NO_IMAGE,
                res.getString(R.string.assistant_health_no_image_title, noImage.size),
                res.getString(R.string.assistant_health_no_image_detail, namesPreview(noImage.map { it.name })),
                noImage
            )
        )

        // 캐릭터 특정이 아닌 카드 — 데이터 건강 화면으로만 이동
        val h = ctx.dataHealth
        val healthAction = InsightAction.Navigate(
            destId = R.id.statsDataHealthDetailFragment,
            label = res.getString(R.string.assistant_health_action)
        )
        if (h.duplicateTags.isNotEmpty()) out.add(
            AssistantInsight(
                id = "health_dup_tags", category = category, severity = InsightSeverity.HEALTH_DUP_TAGS,
                title = res.getString(R.string.assistant_health_dup_tags_title, h.duplicateTags.size),
                detail = res.getString(R.string.assistant_health_dup_tags_detail, namesPreview(h.duplicateTags)),
                count = h.duplicateTags.size, primaryAction = healthAction
            )
        )
        if (h.emptyDescRelationships > 0) out.add(
            AssistantInsight(
                id = "health_empty_rel", category = category, severity = InsightSeverity.HEALTH_EMPTY_REL,
                title = res.getString(R.string.assistant_health_empty_rel_title, h.emptyDescRelationships),
                detail = res.getString(R.string.assistant_health_empty_rel_detail),
                count = h.emptyDescRelationships, primaryAction = healthAction
            )
        )
        return out
    }

    /** 캐릭터 특정 건강 카드: 기본=첫 캐릭터 열기, 부가=데이터 건강 전체. */
    private fun characterCard(
        res: android.content.Context, id: String, severity: Int,
        title: String, detail: String, refs: List<CharRef>
    ): AssistantInsight {
        val first = refs.first()
        return AssistantInsight(
            id = id, category = category, severity = severity,
            title = title, detail = detail, count = refs.size,
            primaryAction = openCharacter(res, first.id),
            secondaryActions = listOf(
                InsightAction.Navigate(
                    destId = R.id.statsDataHealthDetailFragment,
                    label = res.getString(R.string.assistant_health_action)
                )
            )
        )
    }
}

/**
 * 편향·패턴 인사이트 provider — 기존 패턴 감지 엔진([StatsDataProvider.detectPatterns]) 결과를 카드로.
 * 필드 값 쏠림·사건 밀도·서사 공백 등 실질적 인사이트의 핵심이며, 커스텀 필드도 자동 포함(적응형).
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
            val primary = p.fieldDefId?.let {
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
                primaryAction = primary
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
                    primaryAction = InsightAction.Navigate(
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
                    primaryAction = InsightAction.Navigate(
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
                    primaryAction = InsightAction.Navigate(
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
