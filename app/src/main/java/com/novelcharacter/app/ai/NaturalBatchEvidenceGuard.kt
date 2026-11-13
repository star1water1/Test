package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.util.FactionStanding

/** A valid request ref is still not proof that the quoted words identify that row. */
object NaturalBatchEvidenceGuard {
    /**
     * @param segments 인용이 가리킨 문단의 원문(id → text). 한 문단에 사실이 둘이면 모델은 이름이 빠진
     *   구절만 인용하곤 한다("…이고 직업은 기사") — 실제 제공자 응답의 대부분이 그랬다. 인용에 인물이
     *   **하나도 없고** 그 문단이 말하는 인물이 **그 한 명뿐**일 때만 문단으로 대상을 확인한다.
     *   인용이 다른 인물을 가리키거나 문단에 인물이 둘 이상이면 여전히 확인 불가다. 관계의 양 끝과
     *   필드·세력은 종전대로 인용 안에서만 확인한다.
     */
    fun check(plan: NaturalBatchPlan, context: NaturalBatchContext,
        segments: Map<String, String> = emptyMap()): NaturalBatchPlan {
        val safe = mutableListOf<NaturalBatchPlan.Operation>()
        val unresolved = plan.unresolved.toMutableList()
        val characterTerms = context.characters.mapValues { (_, character) ->
            listOf(character.name, character.code) + character.aliases
        }
        val fieldTerms = context.fields.mapValues { (_, field) -> listOf(field.name, field.key) }
        val factionTerms = context.factions.mapValues { (_, faction) ->
            listOf(faction.name, faction.code)
        }
        plan.operations.forEach { operation ->
            val quote = operation.evidence.quote
            val cited = operation.evidence.segmentIds.mapNotNull(segments::get)
            val target = uniqueMention(quote, characterTerms, operation.targetRef) ||
                (operation.relatedRef == null && operation.relationshipRef == null &&
                    cited.size == operation.evidence.segmentIds.size && cited.isNotEmpty() &&
                    cited.any { it.contains(quote) } && exposedHits(quote, characterTerms).isEmpty() &&
                    cited.all { text -> exposedHits(text, characterTerms).let { hits ->
                        hits.isNotEmpty() && hits.all { it.ref == operation.targetRef } } })
            val related = operation.relatedRef == null || uniqueMention(quote,
                characterTerms, operation.relatedRef)
            val relationship = operation.relationshipRef?.let(context.relationships::get)
            val endpoints = operation.relationshipRef == null || (relationship != null &&
                listOf(relationship.firstId, relationship.secondId).all { id ->
                    val ref = context.characters.entries.firstOrNull { it.value.id == id }?.key
                    ref != null && uniqueMention(quote, characterTerms, ref)
                } && context.characters[operation.targetRef]?.id in
                    listOf(relationship.firstId, relationship.secondId))
            val field = operation.fieldRef == null || uniqueMention(quote,
                fieldTerms, operation.fieldRef)
            val faction = operation.factionRef == null || uniqueMention(quote,
                factionTerms, operation.factionRef) || (operation.kind == NaturalBatchPlan.Kind.LEAVE_FACTION &&
                operation.leaveMode == NaturalBatchPlan.LeaveMode.REMOVE && quote.contains("무소속") &&
                context.memberships.orEmpty().any { FactionStanding.isCurrent(it) &&
                    it.characterId == context.characters[operation.targetRef]?.id &&
                    it.factionId == context.factions[operation.factionRef]?.id })
            val fieldType = operation.fieldRef?.let { context.fields[it]?.type?.let(FieldType::fromName) }
            val unwritable = operation.fieldRef != null &&
                (fieldType == null || fieldType == FieldType.CALCULATED)
            if (target && related && endpoints && field && faction && !unwritable) safe += operation
            else unresolved += NaturalBatchPlan.Unresolved(operation.id,
                operation.evidence.segmentIds, quote,
                if (unwritable) "계산 필드 또는 알 수 없는 필드 타입은 직접 수정할 수 없습니다"
                else "인용한 원문만으로 대상 또는 필드를 하나로 확인할 수 없습니다")
        }
        return plan.copy(operations = safe, unresolved = unresolved)
    }

    private data class Hit(val ref: String, val start: Int, val end: Int)

    private fun uniqueMention(
        quote: String, candidates: Map<String, List<String>>, expectedRef: String
    ): Boolean {
        val exposed = exposedHits(quote, candidates)
        return exposed.any { candidate ->
            candidate.ref == expectedRef && exposed.none { other ->
                other.ref != expectedRef && other.start == candidate.start &&
                    other.end == candidate.end
            }
        }
    }

    /** Ignore a nickname occurrence nested inside a longer name at the same position. */
    private fun exposedHits(quote: String, candidates: Map<String, List<String>>): List<Hit> {
        val hits = buildList {
            candidates.forEach { (ref, terms) ->
                terms.filter { it.isNotBlank() }.distinct().forEach { term ->
                    var from = 0
                    while (from < quote.length) {
                        val index = quote.indexOf(term, from, ignoreCase = true)
                        if (index < 0) break
                        add(Hit(ref, index, index + term.length))
                        from = index + 1
                    }
                }
            }
        }
        return hits.filter { hit ->
            hits.none { other -> other.start <= hit.start && other.end >= hit.end &&
                other.end - other.start > hit.end - hit.start }
        }
    }
}
