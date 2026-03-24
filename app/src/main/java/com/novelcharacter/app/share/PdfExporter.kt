package com.novelcharacter.app.share

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class PdfConfig(
    val universeId: Long,
    val novelIds: List<Long>? = null,
    val characterIds: List<Long>? = null,
    val includeProfiles: Boolean = true,
    val includeGraph: Boolean = false,
    val includeTimeline: Boolean = true,
    val includeNameBank: Boolean = false,
    val includeFieldDefs: Boolean = true
)

class PdfExporter(private val context: Context) {

    suspend fun generateHtml(config: PdfConfig): String {
        val app = context.applicationContext as NovelCharacterApp
        val db = app.database

        val universe = app.universeRepository.getUniverseById(config.universeId)
            ?: return "<html><body>Universe not found</body></html>"

        val novels = app.novelRepository.getNovelsByUniverseList(config.universeId)
        val selectedNovels = if (config.novelIds != null) {
            novels.filter { it.id in config.novelIds }
        } else novels

        val novelIds = selectedNovels.map { it.id }.toSet()
        val allChars = app.characterRepository.getAllCharactersList()
        val characters = if (config.characterIds != null) {
            allChars.filter { it.id in config.characterIds }
        } else {
            allChars.filter { it.novelId in novelIds }
        }

        val fieldDefs = app.universeRepository.getFieldsByUniverseList(config.universeId)
        val fieldValues = db.characterFieldValueDao().getAllValuesList()
        val relationships = app.characterRepository.getAllRelationships()
        val events = app.timelineRepository.getAllEventsList()
            .filter { it.novelId in novelIds || it.universeId == config.universeId }
            .sortedBy { it.year }
        val nameBank = if (config.includeNameBank) db.nameBankDao().getAllNamesList() else emptyList()

        return buildString {
            append("""
<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<style>
  body { font-family: sans-serif; color: #212121; max-width: 800px; margin: 0 auto; padding: 20px; }
  h1 { color: #5C6BC0; border-bottom: 3px solid #5C6BC0; padding-bottom: 8px; }
  h2 { color: #3949AB; margin-top: 30px; border-bottom: 1px solid #E0E0E0; padding-bottom: 4px; }
  h3 { color: #5C6BC0; }
  table { width: 100%; border-collapse: collapse; margin: 10px 0; }
  th, td { border: 1px solid #E0E0E0; padding: 8px; text-align: left; font-size: 13px; }
  th { background: #F5F5F5; font-weight: bold; }
  .profile-card { background: #FAFAFA; border: 1px solid #E0E0E0; border-radius: 8px; padding: 16px; margin: 12px 0; page-break-inside: avoid; }
  .profile-name { font-size: 18px; font-weight: bold; color: #5C6BC0; }
  .profile-sub { color: #757575; font-size: 13px; }
  .tag { display: inline-block; background: #E8EAF6; color: #3949AB; padding: 2px 8px; border-radius: 12px; font-size: 11px; margin: 2px; }
  .event-row td:first-child { white-space: nowrap; font-weight: bold; color: #5C6BC0; }
  @media print { .page-break { page-break-before: always; } }
  .toc a { color: #5C6BC0; text-decoration: none; }
  .toc li { margin: 4px 0; }
  .footer { text-align: center; color: #9E9E9E; font-size: 11px; margin-top: 40px; padding-top: 12px; border-top: 1px solid #E0E0E0; }
</style>
</head><body>
""")

            // Cover
            append("<h1>${escHtml(universe.name)}</h1>")
            if (universe.description.isNotBlank()) {
                append("<p>${escHtml(universe.description)}</p>")
            }

            // TOC
            append("<h2>목차</h2><ul class='toc'>")
            if (config.includeFieldDefs) append("<li><a href='#fields'>필드 정의</a></li>")
            append("<li><a href='#novels'>소설 목록</a></li>")
            if (config.includeProfiles) append("<li><a href='#characters'>캐릭터 프로필</a></li>")
            if (config.includeTimeline) append("<li><a href='#timeline'>타임라인</a></li>")
            if (config.includeNameBank) append("<li><a href='#namebank'>이름뱅크</a></li>")
            append("</ul>")

            // Field Definitions
            if (config.includeFieldDefs && fieldDefs.isNotEmpty()) {
                append("<h2 id='fields'>필드 정의</h2>")
                append("<table><tr><th>이름</th><th>키</th><th>타입</th><th>그룹</th></tr>")
                for (fd in fieldDefs) {
                    append("<tr><td>${escHtml(fd.name)}</td><td>${escHtml(fd.key)}</td>")
                    append("<td>${escHtml(fd.type)}</td><td>${escHtml(fd.groupName)}</td></tr>")
                }
                append("</table>")
            }

            // Novels
            append("<h2 id='novels'>소설 목록</h2>")
            for (novel in selectedNovels) {
                append("<h3>${escHtml(novel.title)}</h3>")
                if (novel.description.isNotBlank()) {
                    append("<p>${escHtml(novel.description)}</p>")
                }
                val charCount = characters.count { it.novelId == novel.id }
                append("<p>캐릭터 ${charCount}명</p>")
            }

            // Character Profiles
            if (config.includeProfiles) {
                append("<div class='page-break'></div>")
                append("<h2 id='characters'>캐릭터 프로필</h2>")

                // Pre-build lookup maps to avoid O(n²) searches
                val fieldValuesByChar = fieldValues.groupBy { it.characterId }
                val charMap = characters.associateBy { it.id }
                val relsByChar = mutableMapOf<Long, MutableList<com.novelcharacter.app.data.model.CharacterRelationship>>()
                for (rel in relationships) {
                    relsByChar.getOrPut(rel.characterId1) { mutableListOf() }.add(rel)
                    relsByChar.getOrPut(rel.characterId2) { mutableListOf() }.add(rel)
                }

                for (char in characters) {
                    append("<div class='profile-card'>")
                    append("<div class='profile-name'>${escHtml(char.name)}</div>")
                    if (char.firstName.isNotBlank() || char.lastName.isNotBlank()) {
                        val fullName = listOf(char.lastName, char.firstName).filter { it.isNotBlank() }.joinToString(" ")
                        append("<div class='profile-sub'>${escHtml(fullName)}</div>")
                    }
                    if (char.anotherName.isNotBlank()) {
                        val aliasHtml = char.aliases.joinToString(" ") { "<span class='alias-tag'>${escHtml(it)}</span>" }
                        append("<div class='profile-sub'>$aliasHtml</div>")
                    }

                    // Field values (ordered by field displayOrder)
                    // CALCULATED 필드는 DB에 저장되지 않으므로 FormulaEvaluator로 실시간 계산
                    val charValueMap = (fieldValuesByChar[char.id] ?: emptyList()).associateBy { it.fieldDefinitionId }
                    val calculatedResults = run {
                        val calcFields = fieldDefs.filter { it.type == "CALCULATED" }
                        if (calcFields.isEmpty()) emptyMap()
                        else {
                            val keyValues = mutableMapOf<String, String>()
                            for (fd in fieldDefs) {
                                val v = charValueMap[fd.id]?.value ?: ""
                                if (v.isNotBlank()) keyValues[fd.key] = v
                            }
                            val evaluator = com.novelcharacter.app.util.FormulaEvaluator(keyValues, fieldDefs)
                            calcFields.mapNotNull { fd ->
                                val formula = try {
                                    org.json.JSONObject(fd.config).optString("formula", "")
                                } catch (_: Exception) { "" }
                                if (formula.isBlank()) return@mapNotNull null
                                try {
                                    val value = evaluator.evaluate(formula)
                                    if (value.isNaN() || value.isInfinite()) return@mapNotNull null
                                    val formatted = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)
                                    fd.id to formatted
                                } catch (_: Exception) { null }
                            }.toMap()
                        }
                    }
                    val orderedFieldValues = fieldDefs.mapNotNull { fd ->
                        if (fd.type == "CALCULATED") {
                            calculatedResults[fd.id]?.let { v -> fd to CharacterFieldValue(characterId = char.id, fieldDefinitionId = fd.id, value = v) }
                        } else {
                            charValueMap[fd.id]?.let { fv -> fd to fv }
                        }
                    }.filter { it.second.value.isNotBlank() }
                    if (orderedFieldValues.isNotEmpty()) {
                        append("<table>")
                        for ((fd, fv) in orderedFieldValues) {
                            append("<tr><td>${escHtml(fd.name)}</td><td>${escHtml(fv.value)}</td></tr>")
                        }
                        append("</table>")
                    }

                    // Relationships
                    val charRels = relsByChar[char.id] ?: emptyList()
                    if (charRels.isNotEmpty()) {
                        append("<p><b>관계:</b> ")
                        val relTexts = charRels.map { rel ->
                            val otherId = if (rel.characterId1 == char.id) rel.characterId2 else rel.characterId1
                            val otherName = charMap[otherId]?.name ?: "?"
                            "$otherName(${rel.relationshipType})"
                        }
                        append(relTexts.joinToString(", ") { escHtml(it) })
                        append("</p>")
                    }

                    if (char.memo.isNotBlank()) {
                        append("<p><i>${escHtml(char.memo)}</i></p>")
                    }
                    append("</div>")
                }
            }

            // Timeline
            if (config.includeTimeline && events.isNotEmpty()) {
                append("<div class='page-break'></div>")
                append("<h2 id='timeline'>타임라인</h2>")
                append("<table class='event-row'><tr><th>연도</th><th>설명</th></tr>")
                for (event in events) {
                    append("<tr><td>${escHtml(event.getFormattedDate())}</td>")
                    append("<td>${escHtml(event.description)}</td></tr>")
                }
                append("</table>")
            }

            // Name Bank
            if (config.includeNameBank && nameBank.isNotEmpty()) {
                append("<h2 id='namebank'>이름뱅크</h2>")
                append("<table><tr><th>이름</th><th>성별</th><th>출처</th><th>사용</th></tr>")
                for (name in nameBank) {
                    val usedText = if (name.isUsed) "사용됨" else "미사용"
                    append("<tr><td>${escHtml(name.name)}</td><td>${escHtml(name.gender)}</td>")
                    append("<td>${escHtml(name.origin)}</td><td>$usedText</td></tr>")
                }
                append("</table>")
            }

            append("<div class='footer'>Generated by NovelCharacter</div>")
            append("</body></html>")
        }
    }

    /**
     * WebView를 사용하여 HTML을 PDF로 인쇄한다.
     * 메인 스레드에서 호출해야 한다.
     */
    fun printAsPdf(webView: WebView, html: String, jobName: String) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = webView.createPrintDocumentAdapter(jobName)
                val attrs = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()
                printManager.print(jobName, adapter, attrs)
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun escHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
