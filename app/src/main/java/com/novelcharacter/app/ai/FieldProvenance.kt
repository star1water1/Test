package com.novelcharacter.app.ai

import com.novelcharacter.app.ai.CharacterFieldAiSuggester.*
import org.json.JSONArray
import org.json.JSONObject

/** Model metadata is a claim. Labels and selection come from app-owned dispatch evidence. */
object FieldProvenance {
    data class Claim(val origins: List<String>?, val source: String?, val quote: String?, val malformed: Boolean)
    data class Evidence(val source: String, val quote: String, val context: String)
    data class Assessment(val label: String, val defaultOn: Boolean, val explanation: String,
        val evidence: List<Evidence>, val dispatch: String)

    fun readClaim(obj: JSONObject): Claim? {
        if (listOf("origin", "sourceType", "sourceEvidence").none { obj.has(it) && !obj.isNull(it) }) return null
        var malformed = false
        fun string(key: String): String? {
            val value = obj.opt(key)
            if (value == null || value == JSONObject.NULL) return null
            if (value !is String) { malformed = true; return null }
            return value.takeIf { it.isNotBlank() }
        }
        val raw = obj.opt("origin")
        val origins = when (raw) {
            null, JSONObject.NULL -> null
            is String -> listOf(raw)
            is JSONArray -> (0 until raw.length()).mapNotNull {
                (raw.opt(it) as? String).also { value -> if (value == null) malformed = true }
            }
            else -> { malformed = true; null }
        }
        val source = string("sourceType")
        val quote = string("sourceEvidence")
        return Claim(origins, source, quote, malformed)
    }

    /** Match complete supplied material in actual wire text, including JSON-escaped data blocks. */
    private fun wasSent(text: String, receipt: AiInputReceipt): Boolean = text.isNotBlank() &&
        receipt.sentText.any { it.contains(text) || it.contains(JSONObject.quote(text).removeSurrounding("\"")) }

    fun assess(value: Suggestion, spec: FieldSpec?, receipt: AiInputReceipt?, confirmedByUser: Boolean = false): Assessment {
        val actual = receipt?.takeIf { it.id == value.inputReceiptId }
        val claim = value.provenance
        val origins = claim?.origins.orEmpty().map { it.uppercase(java.util.Locale.ROOT) }.toSet()
        val known = claim != null && !claim.malformed && origins.isNotEmpty() &&
            origins.all { it in setOf("DIRECT", "INTERPRETED", "CREATIVE", "MIXED") }
        val creative = "CREATIVE" in origins || "MIXED" in origins
        val source = claim?.source?.uppercase(java.util.Locale.ROOT)
        val quote = claim?.quote ?: value.sourceEvidence
        val evidence = mutableListOf<Evidence>()
        if (actual != null && !quote.isNullOrBlank()) {
            fun add(kind: String, text: String) {
                if (wasSent(text, actual) && text.contains(quote)) evidence.add(Evidence(kind, quote, text))
            }
            add("BRIEF", actual.source.briefing)
            add("INSTRUCTION", actual.source.instructions[value.fieldKey].orEmpty())
            actual.source.contextText.orEmpty().forEach { add("CONTEXT", it) }
        }
        val matched = if (source == null) evidence else evidence.filter { it.source == source }
        val sourceConsistent = source == null || source in setOf("BRIEF", "INSTRUCTION", "CONTEXT", "IMAGE")
        val imageClaim = source == "IMAGE"
        val directConfirmed = confirmedByUser && known && !creative && sourceConsistent && matched.isNotEmpty()
        val label = when {
            directConfirmed -> "직접 언급 · 사용자 확인"
            "CREATIVE" in origins -> if (origins.size > 1) "AI 창작 포함 · 혼합 (모델 표시)" else "AI 창작 (모델 표시)"
            "MIXED" in origins -> "혼합 · 창작 여부 미확인"
            !known || !sourceConsistent -> "출처 상세 미확인"
            imageClaim -> if ((actual?.imageCount ?: 0) > 0) "이미지 전송됨 · 활용 여부 미확인" else "이미지 출처 확인 불가"
            matched.isEmpty() -> "원문 근거 확인 불가"
            origins.size > 1 -> "원문 인용 있음 · 해석 포함 혼합"
            else -> "원문 인용 있음 · AI 해석"
        }
        val valid = spec != null && CharacterFieldAiSuggester.normalizeChecked(value.value, spec) is Normalized.Ok
        val defaultOn = valid && spec!!.currentValue.isBlank() && value.confidence != Confidence.LOW &&
            known && !creative && sourceConsistent && !imageClaim && matched.isNotEmpty() &&
            ("INTERPRETED" in origins || directConfirmed)
        val explanation = when {
            !valid -> "현재 항목의 형식과 선택지를 확인하세요."
            !spec!!.currentValue.isBlank() -> "기존 값을 바꾸므로 직접 선택해야 합니다."
            creative -> "창작이 포함되거나 포함 여부가 불명확한 혼합 항목은 기본으로 선택하지 않습니다. 모델이 표시한 분류이며 사실 검증이 아닙니다."
            actual == null -> "당시 전송 기록이 없어 근거를 대조할 수 없습니다. 값은 보관했습니다."
            !known || !sourceConsistent -> "출처 정보가 없거나 형식을 확인할 수 없습니다. 값은 보관했습니다."
            imageClaim -> "이미지 전송 여부만 확인할 수 있습니다. 이 값을 이미지에서 얻었는지는 확인할 수 없습니다."
            matched.isEmpty() -> "인용을 당시 전송한 해당 자료에서 찾지 못했습니다."
            directConfirmed -> "원문과 이 값의 대응을 사용자가 확인했습니다. 의미를 자동 검증한 결과는 아닙니다."
            "INTERPRETED" !in origins -> "직접 추출이라는 모델 표시만으로 필드와 값의 대응을 확정하지 않습니다. 원문 맥락을 확인해 선택하세요."
            value.confidence == Confidence.LOW -> "근거 강도가 낮아 기본 선택을 해제했습니다."
            else -> "원문 인용 위치만 확인했습니다. 부정·정정·범위와 값의 의미 일치는 직접 확인하세요."
        }
        val dispatch = if (actual == null) "당시 전송 기록 없음" else buildString {
            append("당시 모델: ${actual.model} · 실제 이미지 전송 ${actual.imageCount}장")
            append("\n참고자료 기록 ${actual.source.contextText?.size ?: 0}개")
            if (actual.source.contextText == null) append(" · 구버전은 참고자료 구분 기록 없음")
            actual.source.contextNotes.forEach { append("\n").append(it) }
        }
        return Assessment(label, defaultOn, explanation, matched.distinct(), dispatch)
    }
}
