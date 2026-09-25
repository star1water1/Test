package com.novelcharacter.app.ai

/** A valid request ref is still not proof that the quoted words identify that row. */
object NaturalBatchEvidenceGuard {
    fun check(plan: NaturalBatchPlan, context: NaturalBatchContext): NaturalBatchPlan {
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
            val target = uniqueMention(quote, characterTerms, operation.targetRef)
            val related = operation.relatedRef == null || uniqueMention(quote,
                characterTerms, operation.relatedRef)
            val field = operation.fieldRef == null || uniqueMention(quote,
                fieldTerms, operation.fieldRef)
            val faction = operation.factionRef == null || uniqueMention(quote,
                factionTerms, operation.factionRef)
            if (target && related && field && faction) safe += operation
            else unresolved += NaturalBatchPlan.Unresolved(operation.id,
                operation.evidence.segmentIds, quote,
                "인용한 원문만으로 대상 또는 필드를 하나로 확인할 수 없습니다")
        }
        return plan.copy(operations = safe, unresolved = unresolved)
    }

    /** Ignore a nickname occurrence nested inside a longer name at the same position. */
    private fun uniqueMention(
        quote: String, candidates: Map<String, List<String>>, expectedRef: String
    ): Boolean {
        data class Hit(val ref: String, val start: Int, val end: Int)
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
        val exposed = hits.filter { hit ->
            hits.none { other -> other.start <= hit.start && other.end >= hit.end &&
                other.end - other.start > hit.end - hit.start }
        }
        return exposed.any { candidate ->
            candidate.ref == expectedRef && exposed.none { other ->
                other.ref != expectedRef && other.start == candidate.start &&
                    other.end == candidate.end
            }
        }
    }
}
