package com.novelcharacter.app.ai

import org.json.JSONArray
import org.json.JSONObject

data class NaturalBatchRefs(
    val characters: Set<String>,
    val fields: Set<String>,
    val relationships: Set<String>,
    val factions: Set<String>
)

class NaturalBatchFormatException(message: String) : IllegalArgumentException(message)

/** The AI can describe candidates, but only app-issued request references survive parsing. */
object NaturalBatchPlanParser {
    private const val MAX_RESPONSE_CHARS = 1_000_000
    private const val MAX_ITEMS = 500

    fun parse(
        response: String,
        input: NaturalBatchInput,
        requestedSegments: Set<String>,
        refs: NaturalBatchRefs
    ): NaturalBatchPlan {
        val validSegments = input.segments().map { it.id }.toSet()
        val segmentsById = input.segments().associateBy { it.id }
        ensure(requestedSegments.isNotEmpty() && requestedSegments.all { it in validSegments }, "Invalid request segments")
        ensure(response.length <= MAX_RESPONSE_CHARS, "Response is too large")
        val trimmed = response.trim()
        ensure(trimmed.startsWith('{') && trimmed.endsWith('}'), "Expected one JSON object")
        ensure(singleObject(trimmed), "Expected one JSON object")
        try {
            val root = JSONObject(trimmed)
            root.keysOnly(setOf("schemaVersion", "sessionId", "scopeRevision", "inputRevision",
                "operations", "constraints", "unresolved", "suggestionNotes", "segmentStatus"))
            ensure(root.integer("schemaVersion") == 1L, "Unsupported schema version")
            ensure(root.string("sessionId") == input.sessionId, "Wrong input session")
            ensure(root.integer("scopeRevision") == input.scopeRevision, "Stale scope")
            ensure(root.integer("inputRevision") == input.inputRevision, "Stale input")

            val rejected = mutableListOf<NaturalBatchPlan.Unresolved>()
            val operations = root.array("operations").objects().mapNotNull { item ->
                item.keysOnly(setOf("id", "kind", "targetRef", "fieldRef", "relatedRef",
                    "factionRef", "relationshipRef", "value", "relationshipType",
                    "relationshipDescription", "intensity", "bidirectional", "joinYear",
                    "leaveYear", "leaveMode", "origin", "segmentIds", "quote"))
                val kind = item.enum<NaturalBatchPlan.Kind>("kind")
                val target = item.string("targetRef")
                val field = item.optionalString("fieldRef")
                val related = item.optionalString("relatedRef")
                val faction = item.optionalString("factionRef")
                val relationship = item.optionalString("relationshipRef")
                val value = item.optionalString("value", allowEmpty = true, maxLength = MAX_RESPONSE_CHARS)
                val type = item.optionalString("relationshipType")
                val description = item.optionalString("relationshipDescription", allowEmpty = true,
                    maxLength = MAX_RESPONSE_CHARS)
                val intensity = item.optionalInt("intensity")
                val bidirectional = item.optionalBoolean("bidirectional")
                val joinYear = item.optionalInt("joinYear")
                val leaveYear = item.optionalInt("leaveYear")
                val leaveMode = item.optionalEnum<NaturalBatchPlan.LeaveMode>("leaveMode")
                when (kind) {
                    NaturalBatchPlan.Kind.SET_FIELD_VALUE,
                    NaturalBatchPlan.Kind.ADD_FIELD_VALUE,
                    NaturalBatchPlan.Kind.REMOVE_FIELD_VALUE ->
                        ensure(field != null && !value.isNullOrBlank() && related == null && faction == null &&
                            relationship == null && type == null && description == null &&
                            intensity == null && bidirectional == null && joinYear == null &&
                            leaveYear == null && leaveMode == null, "Invalid field operation")
                    NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE ->
                        ensure(field != null && value == null && related == null && faction == null &&
                            relationship == null && type == null && description == null &&
                            intensity == null && bidirectional == null && joinYear == null &&
                            leaveYear == null && leaveMode == null, "Invalid clear operation")
                    NaturalBatchPlan.Kind.ADD_RELATIONSHIP ->
                        ensure(related != null && related != target && type != null && relationship == null &&
                            field == null && faction == null && value == null && joinYear == null &&
                            leaveYear == null && leaveMode == null,
                            "Add relationship needs another character and type")
                    NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP ->
                        ensure(relationship != null && related == null &&
                            field == null && faction == null && value == null && joinYear == null &&
                            leaveYear == null && leaveMode == null &&
                            (type != null || description != null || intensity != null || bidirectional != null),
                            "Invalid relationship update")
                    NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP ->
                        ensure(relationship != null && related == null && type == null &&
                            field == null && faction == null && value == null && description == null &&
                            intensity == null && bidirectional == null && joinYear == null &&
                            leaveYear == null && leaveMode == null, "Invalid relationship removal")
                    NaturalBatchPlan.Kind.JOIN_FACTION ->
                        ensure(faction != null && leaveMode == null && leaveYear == null && field == null &&
                            related == null && relationship == null && value == null && type == null &&
                            description == null && intensity == null && bidirectional == null,
                            "Invalid faction join")
                    NaturalBatchPlan.Kind.LEAVE_FACTION ->
                        ensure(faction != null && leaveMode != null &&
                            (leaveMode != NaturalBatchPlan.LeaveMode.DEPART || leaveYear != null) &&
                            (leaveMode != NaturalBatchPlan.LeaveMode.REMOVE || leaveYear == null) &&
                            field == null && related == null && relationship == null && value == null &&
                            type == null && description == null && intensity == null &&
                            bidirectional == null && joinYear == null, "Invalid faction departure")
                }
                ensure(intensity == null || intensity in 1..10, "Invalid relationship intensity")
                val ids = item.segments(requestedSegments)
                val quote = item.string("quote", maxLength = 2_000)
                val matched = ids.any { id -> segmentsById.getValue(id).text.contains(quote) }
                val unknownRefs = target !in refs.characters ||
                    (field != null && field !in refs.fields) ||
                    (related != null && related !in refs.characters) ||
                    (faction != null && faction !in refs.factions) ||
                    (relationship != null && relationship !in refs.relationships)
                if (unknownRefs) {
                    rejected += NaturalBatchPlan.Unresolved(item.string("id"), ids, quote,
                        "분석 요청에 없는 대상 또는 필드 참조입니다")
                    return@mapNotNull null
                }
                NaturalBatchPlan.Operation(item.string("id"), kind, target, field, related, faction,
                    relationship, value, type, description, intensity, bidirectional, joinYear,
                    leaveYear, leaveMode, item.enum("origin"),
                    NaturalBatchPlan.Evidence(ids, quote, matched))
            }
            val constraints = root.array("constraints").objects().mapNotNull { item ->
                item.keysOnly(setOf("id", "segmentIds", "leftTargetRef", "rightTargetRef",
                    "fieldRef", "comparison", "description"))
                val id = item.string("id")
                val ids = item.segments(requestedSegments)
                val left = item.string("leftTargetRef")
                val right = item.string("rightTargetRef")
                val field = item.string("fieldRef")
                val comparison = item.enum<NaturalBatchPlan.Comparison>("comparison")
                val description = item.string("description", maxLength = 4_000)
                if (left !in refs.characters || right !in refs.characters || field !in refs.fields) {
                    rejected += NaturalBatchPlan.Unresolved(id, ids, description,
                        "분석 요청에 없는 비교 대상 또는 필드 참조입니다")
                    null
                } else NaturalBatchPlan.Constraint(id, ids, left, right, field,
                    comparison, description)
            }
            val unresolved = rejected + root.array("unresolved").objects().map { item ->
                item.keysOnly(setOf("id", "segmentIds", "text", "reason"))
                NaturalBatchPlan.Unresolved(item.string("id"), item.segments(requestedSegments),
                    item.string("text", maxLength = 4_000), item.string("reason", maxLength = 4_000))
            }
            val notes = root.array("suggestionNotes").objects().map { item ->
                item.keysOnly(setOf("id", "segmentIds", "text"))
                NaturalBatchPlan.Note(item.string("id"), item.segments(requestedSegments),
                    item.string("text", maxLength = 4_000))
            }
            val status = root.array("segmentStatus").objects().map { item ->
                item.keysOnly(setOf("segmentId", "status", "reason"))
                val id = item.string("segmentId", requestedSegments)
                val coverage = item.enum<NaturalBatchPlan.Coverage>("status")
                val reason = item.optionalString("reason")
                ensure(coverage != NaturalBatchPlan.Coverage.IGNORED || reason != null,
                    "Ignored segment needs a reason")
                NaturalBatchPlan.SegmentStatus(id, coverage, reason)
            }
            ensure(status.map { it.segmentId }.distinct().size == status.size, "Duplicate segment status")
            val ids = operations.map { it.id } + constraints.map { it.id } +
                unresolved.map { it.id } + notes.map { it.id }
            ensure(ids.distinct().size == ids.size, "Duplicate item ID")
            return NaturalBatchPlan(input.sessionId, input.scopeRevision, input.inputRevision,
                operations, constraints, unresolved, notes, status)
        } catch (e: NaturalBatchFormatException) {
            throw e
        } catch (e: Exception) {
            throw NaturalBatchFormatException("Invalid batch response: ${e.message}")
        }
    }

    private fun ensure(value: Boolean, message: String) {
        if (!value) throw NaturalBatchFormatException(message)
    }

    private fun singleObject(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0 && index != text.lastIndex) return false
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0 && !inString
    }

    private fun JSONObject.keysOnly(allowed: Set<String>) {
        val keys = keys().asSequence().toSet()
        ensure(keys.all { it in allowed }, "Unknown key: ${(keys - allowed).firstOrNull()}")
    }

    private fun JSONObject.string(
        key: String, allowed: Set<String>? = null, maxLength: Int = 256
    ): String {
        val value = get(key)
        ensure(value is String && value.isNotBlank() && value.length <= maxLength, "Invalid $key")
        value as String
        ensure(allowed == null || value in allowed, "Unknown $key reference")
        return value
    }

    private fun JSONObject.optionalString(
        key: String, allowed: Set<String>? = null, allowEmpty: Boolean = false,
        maxLength: Int = 256
    ): String? {
        if (!has(key) || isNull(key)) return null
        val value = get(key)
        ensure(value is String && value.length <= maxLength && (allowEmpty || value.isNotBlank()),
            "Invalid $key")
        value as String
        ensure(allowed == null || value in allowed, "Unknown $key reference")
        return value
    }

    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        ensure(value is Number && value !is Float && value !is Double, "Invalid $key")
        return value.toString().toLongOrNull()
            ?: throw NaturalBatchFormatException("Invalid $key")
    }

    private fun JSONObject.optionalInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = integer(key)
        ensure(value in Int.MIN_VALUE..Int.MAX_VALUE, "Invalid $key")
        return value.toInt()
    }

    private fun JSONObject.optionalBoolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        val value = get(key)
        ensure(value is Boolean, "Invalid $key")
        return value as Boolean
    }

    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String): T {
        val value = string(key)
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw NaturalBatchFormatException("Unknown $key")
    }

    private inline fun <reified T : Enum<T>> JSONObject.optionalEnum(key: String): T? =
        if (!has(key) || isNull(key)) null else enum<T>(key)

    private fun JSONObject.array(key: String): JSONArray {
        val value = get(key)
        ensure(value is JSONArray && value.length() <= MAX_ITEMS, "Invalid $key")
        return value as JSONArray
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { index ->
        val value = get(index)
        ensure(value is JSONObject, "Expected object at $index")
        value as JSONObject
    }

    private fun JSONObject.segments(allowed: Set<String>): List<String> {
        val array = array("segmentIds")
        ensure(array.length() in 1..64, "Missing segment citation")
        return (0 until array.length()).map { index ->
            val value = array.get(index)
            ensure(value is String && value in allowed, "Unknown segment reference")
            value as String
        }.also { ensure(it.distinct().size == it.size, "Duplicate segment citation") }
    }
}
