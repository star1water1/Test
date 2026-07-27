// tools/usage-probe/ScaleProbe.kt — 런북 7장(규모 실측)의 하네스.
//
// Probe.kt 와 같은 규약: 경로는 args[0], 러너 목록에 넣지 않는다, 출력은 집계만.
//
// 7-1 최소 스냅샷: universes · novels · characters · fieldDefinitions · fieldValues · valueEntries
//     나머지 목록은 emptyList() 이므로 산출물에는 `0건`이 아니라 **미조립**이라고 적는다.
// 7-3 스케일 스윕: 구조를 유지한 채 캐릭터·필드값만 ×3/×10/×30 으로 복제 증폭한다
//     (필드 정의와 값 종수 분포는 그대로 — 곡선의 형태를 보는 것이 목적이다).
//
// 실행:
//   java -Dstdout.encoding=UTF-8 -cp "$J/out-probe:$J/out-tests:$CP" ScaleProbeKt "<파일경로>"

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.excel.CHARACTER_SHEET_FINGERPRINT
import com.novelcharacter.app.excel.ExcelCellValue
import com.novelcharacter.app.excel.UNCLASSIFIED_SHEET_NAME
import com.novelcharacter.app.excel.isSuffixedVariantOf
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File

private fun text(cell: Cell?): String {
    if (cell == null) return ""
    return try { ExcelCellValue.normalize(ExcelCellValue.fromCell(cell), dateHint = false) } catch (_: Exception) { "" }
}

private fun headers(sheet: Sheet): List<String> {
    val row = sheet.getRow(sheet.firstRowNum) ?: return emptyList()
    return (0 until row.lastCellNum.toInt().coerceAtLeast(0)).map { text(row.getCell(it)) }
}

private fun rows(sheet: Sheet, block: ((String) -> String) -> Unit) {
    val hs = headers(sheet)
    val idx = hs.withIndex().associate { (i, h) -> h to i }
    for (r in (sheet.firstRowNum + 1)..sheet.lastRowNum) {
        val row = sheet.getRow(r) ?: continue
        block { name -> idx[name]?.let { text(row.getCell(it)) } ?: "" }
    }
}

/** 같은 계산을 warmup 후 반복 측정해 중앙값(ms)을 낸다 — 1회 측정은 JIT 노이즈에 묻힌다. */
private fun timeMs(warmup: Int, runs: Int, body: () -> Unit): Long {
    repeat(warmup) { body() }
    val samples = (0 until runs).map {
        val t0 = System.nanoTime(); body(); (System.nanoTime() - t0) / 1_000_000
    }.sorted()
    return samples[samples.size / 2]
}

fun main(args: Array<String>) {
    XSSFWorkbook(File(args[0])).use { wb ->
        val byName = (0 until wb.numberOfSheets).associate { wb.getSheetName(it) to wb.getSheetAt(it) }

        // ── 세계관 · 작품 ──
        val universes = mutableListOf<Universe>()
        val uniIdByName = mutableMapOf<String, Long>()
        byName["세계관"]?.let { sh ->
            rows(sh) { get ->
                val n = get("이름"); if (n.isBlank()) return@rows
                val id = universes.size + 1L
                universes.add(Universe(id = id, name = n, customRelationshipTypes = get("커스텀관계유형")))
                uniIdByName[n] = id
            }
        }
        val novels = mutableListOf<Novel>()
        val novelIdByTitle = mutableMapOf<String, Long>()
        byName["작품"]?.let { sh ->
            rows(sh) { get ->
                val t = get("제목"); if (t.isBlank()) return@rows
                val id = novels.size + 1L
                novels.add(Novel(id = id, title = t, universeId = uniIdByName[get("세계관")]))
                novelIdByTitle[t] = id
            }
        }

        // ── 필드 정의 ──
        val defs = mutableListOf<FieldDefinition>()
        val defIdByUniKey = mutableMapOf<Pair<Long, String>, Long>()
        byName["필드 정의"]?.let { sh ->
            rows(sh) { get ->
                val key = get("필드키"); if (key.isBlank()) return@rows
                val uid = uniIdByName[get("세계관")] ?: return@rows
                val id = defs.size + 1L
                defs.add(FieldDefinition(
                    id = id, universeId = uid, key = key, name = get("필드명"),
                    type = get("타입"), config = get("설정(JSON)").ifBlank { "{}" },
                    groupName = get("그룹"), displayOrder = get("순서").toIntOrNull() ?: 0,
                    isRequired = get("필수여부") == "Y",
                    entityType = if (get("대상") == "사건") FieldDefinition.ENTITY_EVENT else FieldDefinition.ENTITY_CHARACTER
                ))
                defIdByUniKey[uid to key] = id
            }
        }

        // ── 캐릭터 + 필드값 ──
        val characters = mutableListOf<Character>()
        val fieldValues = mutableListOf<CharacterFieldValue>()
        val fixed = setOf("이름", "성", "이름(First)", "이명", "이미지경로", "작품", "메모", "태그",
            "코드", "작품코드", "정렬순서", "고정", "생성일")
        for ((sname, sh) in byName) {
            val hs = headers(sh)
            if (hs.firstOrNull() != "이름") continue
            if (CHARACTER_SHEET_FINGERPRINT.any { it !in hs }) continue
            val uniName = uniIdByName.keys.firstOrNull { it == sname }
                ?: uniIdByName.keys.firstOrNull { isSuffixedVariantOf(sname, it) }
            val uid = uniName?.let { uniIdByName[it] }
            val here = defs.filter { it.universeId == uid && it.entityType == FieldDefinition.ENTITY_CHARACTER }
            val colDef = mutableMapOf<Int, Long>()
            hs.forEachIndexed { ci, h ->
                if (h.isBlank() || h in fixed) return@forEachIndexed
                val core = h.removeSuffix(" (쉼표 구분)")
                val keyed = Regex("^(.*)\\((.+)\\)$").find(core)
                val fd = if (keyed != null) here.firstOrNull { it.key == keyed.groupValues[2] } ?: here.firstOrNull { it.name == core }
                    else here.firstOrNull { it.name == core }
                if (fd != null) colDef[ci] = fd.id
            }
            val nameCol = hs.indexOf("이름")
            for (r in (sh.firstRowNum + 1)..sh.lastRowNum) {
                val row = sh.getRow(r) ?: continue
                val nm = text(row.getCell(nameCol)); if (nm.isBlank()) continue
                val cid = characters.size + 1L
                val novelTitle = hs.indexOf("작품").takeIf { it >= 0 }?.let { text(row.getCell(it)) } ?: ""
                characters.add(Character(
                    id = cid, name = nm, novelId = novelIdByTitle[novelTitle],
                    anotherName = hs.indexOf("이명").takeIf { it >= 0 }?.let { text(row.getCell(it)) } ?: "",
                    memo = hs.indexOf("메모").takeIf { it >= 0 }?.let { text(row.getCell(it)) } ?: ""
                ))
                for ((ci, did) in colDef) {
                    val v = text(row.getCell(ci)); if (v.isBlank()) continue
                    fieldValues.add(CharacterFieldValue(id = fieldValues.size + 1L, characterId = cid, fieldDefinitionId = did, value = v))
                }
            }
        }

        // ── 값 라이브러리 ──
        val entries = mutableListOf<FieldValueEntry>()
        byName["필드 데이터"]?.let { sh ->
            rows(sh) { get ->
                val v = get("값"); if (v.isBlank()) return@rows
                val uid = uniIdByName[get("세계관")] ?: return@rows
                val did = defIdByUniKey[uid to get("필드키")] ?: return@rows
                val aliases = get("별칭(콤마구분)").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                entries.add(FieldValueEntry(
                    id = entries.size + 1L, fieldDefinitionId = did, value = v,
                    displayLabel = get("표시라벨"),
                    aliasesJson = if (aliases.isEmpty()) "[]" else aliases.joinToString(",", "[\"", "\"]", transform = { it }),
                    category = get("카테고리"), isHidden = get("숨김") == "Y",
                    source = get("출처").ifBlank { FieldValueEntry.SOURCE_AUTO },
                    usageCount = get("사용횟수").toIntOrNull() ?: 0
                ))
            }
        }

        fun snapshotOf(mult: Int): StatsSnapshot {
            if (mult == 1) return StatsSnapshot(
                characters = characters, novels = novels, universes = universes,
                events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
                tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
                fieldDefinitions = defs, fieldValues = fieldValues, crossRefs = emptyList(),
                valueEntries = entries
            )
            // 구조 그대로 복제 증폭 — 캐릭터 id 만 치환하고 필드 정의·값 종수 분포는 유지한다.
            val chars = ArrayList<Character>(characters.size * mult)
            val vals = ArrayList<CharacterFieldValue>(fieldValues.size * mult)
            val base = characters.size.toLong()
            for (k in 0 until mult) {
                val off = base * k
                characters.forEach { chars.add(it.copy(id = it.id + off, name = it.name + "#" + k, code = it.code + k)) }
                fieldValues.forEach { vals.add(it.copy(id = it.id + fieldValues.size.toLong() * k, characterId = it.characterId + off)) }
            }
            return StatsSnapshot(
                characters = chars, novels = novels, universes = universes,
                events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
                tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
                fieldDefinitions = defs, fieldValues = vals, crossRefs = emptyList(),
                valueEntries = entries
            )
        }

        println("### SCALE PROBE")
        println("조립: 세계관 ${universes.size} · 작품 ${novels.size} · 캐릭터 ${characters.size} · " +
            "필드정의 ${defs.size} · 필드값 ${fieldValues.size} · 라이브러리 ${entries.size}")
        println("미조립(런북 7-1): events · crossRefs · relationships · relationshipChanges · stateChanges · " +
            "factions · factionMemberships · nameBank · tags · eventField*")

        val p = StatsDataProvider()
        println("\n%-26s %8s %8s %8s %8s   %s".format("계산", "×1", "×3", "×10", "×30", "증가 형태"))
        val mults = listOf(1, 3, 10, 30)
        val snaps = mults.associateWith { snapshotOf(it) }

        data class Case(val label: String, val body: (StatsSnapshot) -> Unit)
        val cases = listOf(
            Case("computeSummary") { p.computeSummary(it) },
            Case("computeFieldInsights") { p.computeFieldInsights(it) },
            Case("computeFieldAnalysis") { p.computeFieldAnalysis(it) },
            Case("detectPatterns") { p.detectPatterns(it) },
            Case("computeCrossNovelComparison") { p.computeCrossNovelComparison(it) }
        )
        for (c in cases) {
            val ts = mults.map { m -> timeMs(2, 5) { c.body(snaps[m]!!) } }
            val shape = if (ts[0] <= 0) "기준 0ms — 판정보류" else {
                val ratio = ts[3].toDouble() / ts[0].toDouble()
                when {
                    ratio <= 45 -> "선형 (×30에서 ${"%.1f".format(ratio)}배)"
                    ratio <= 120 -> "초선형 주의 (×30에서 ${"%.1f".format(ratio)}배)"
                    else -> "초선형 (×30에서 ${"%.1f".format(ratio)}배)"
                }
            }
            println("%-26s %8s %8s %8s %8s   %s".format(c.label, "${ts[0]}ms", "${ts[1]}ms", "${ts[2]}ms", "${ts[3]}ms", shape))
        }

        // 부수 관측 (런북 7-2)
        val insights = p.computeFieldInsights(snaps[1]!!)
        println("\n인사이트 카드 ${insights.size}장")
        println("### SCALE PROBE 끝")
    }
}
