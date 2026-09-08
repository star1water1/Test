package com.novelcharacter.app.speech

/** Small, ranked vocabulary. No memo, prose or entire database is harvested. */
object SpeechVocabulary {
    data class Term(val text: String, val priority: Int)
    data class Selection(val terms: List<String>, val omitted: Int)
    fun select(candidates: List<Term>, byteBudget: Int, maxTerms: Int = 48): Selection {
        val ranked=candidates.sortedWith(compareBy<Term> {it.priority}.thenBy {it.text})
            .map {it.text.trim()}.filter {it.isNotBlank()}.distinct()
        val picked=mutableListOf<String>()
        var used=0
        for(term in ranked) {
            val bytes=term.toByteArray(Charsets.UTF_8).size + if(picked.isEmpty()) 0 else 2
            if(term.length>80 || term.any {it.isISOControl() || it=='<' || it=='>'} || picked.size>=maxTerms || used+bytes>byteBudget) continue
            picked.add(term); used+=bytes
        }
        return Selection(picked,ranked.size-picked.size)
    }

    data class Correction(val start: Int, val end: Int, val heard: String, val suggested: String)

    /** Fuzzy names are reviewable proposals, never automatic edits. Unique one-character differences only. */
    fun corrections(transcript: String, terms: List<String>): List<Correction> {
        val words=Regex("[\\p{L}\\p{N}]+").findAll(transcript)
        val clean=terms.filter {it.length>=3 && it.all {c->c.isLetterOrDigit()}}.distinct()
        return words.mapNotNull { match ->
            val heard=match.value
            if(heard.length<3 || heard in clean || heard.any {it.isDigit()}) return@mapNotNull null
            if(clean.any { heard.startsWith(it) && heard.removePrefix(it) in
                    setOf("은","는","이","가","을","를","의","에","와","과","도","만","에게","에서","으로") }) return@mapNotNull null
            val candidates=clean.filter {oneEditApart(heard,it)}
            candidates.singleOrNull()?.let { Correction(match.range.first,match.range.last+1,heard,it) }
        }.toList()
    }
    fun apply(text: String, correction: Correction): String? =
        if(correction.start<0 || correction.end>text.length || correction.end<correction.start ||
            text.substring(correction.start,correction.end)!=correction.heard) null
        else text.replaceRange(correction.start,correction.end,correction.suggested)

    private fun oneEditApart(a:String,b:String):Boolean {
        if(kotlin.math.abs(a.length-b.length)>1 || a==b) return false
        var i=0; var j=0; var differences=0
        while(i<a.length && j<b.length) {
            if(a[i]==b[j]) {i++;j++}
            else {
                if(++differences>1) return false
                when {a.length>b.length->i++; b.length>a.length->j++; else->{i++;j++}}
            }
        }
        return differences+(a.length-i)+(b.length-j)==1
    }
}
