// tools/usage-probe/Probe.kt — 실사용 대조 런북(docs/usage_reality_check_runbook.md)의 단일 하네스.
//
// **이 파일은 커밋한다. 데이터는 커밋하지 않는다.**
// 규약(런북 2-3 · 10장):
//   1. 파일 경로는 args[0]. 코드에 박지 않는다.
//   2. run_jvm_tests.sh 의 SOURCES/TESTS 에 넣지 않는다 — tools/ 아래이므로 러너와 무관하다.
//   3. 출력은 집계만. 행 단위 덤프 금지, 예시는 필드당 최대 3건.
//
// 4장(우회 흔적)·5장(기능 도달률)·6장(왕복 무결성)의 집계를 **워크북 한 번의 순회**에서 함께 뽑는다.
//
// 실행:
//   java -Dstdout.encoding=UTF-8 -cp "$J/out-probe:$J/out-tests:$CP" ProbeKt "<파일경로>"

import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.excel.CHARACTER_SHEET_FINGERPRINT
import com.novelcharacter.app.excel.ExcelCellValue
import com.novelcharacter.app.excel.GUIDE_SHEET_NAME
import com.novelcharacter.app.excel.UNCLASSIFIED_SHEET_NAME
import com.novelcharacter.app.excel.characterFieldValueSpec
import com.novelcharacter.app.excel.factionMembershipSpec
import com.novelcharacter.app.excel.factionRelationshipSpec
import com.novelcharacter.app.excel.factionSpec
import com.novelcharacter.app.excel.fieldDefinitionSpec
import com.novelcharacter.app.excel.fieldValueLibrarySpec
import com.novelcharacter.app.excel.headersMatchSpec
import com.novelcharacter.app.excel.isSuffixedVariantOf
import com.novelcharacter.app.excel.appSettingsSpec
import com.novelcharacter.app.excel.characterListPresetSpec
import com.novelcharacter.app.excel.imageMetaSpec
import com.novelcharacter.app.excel.nameBankSpec
import com.novelcharacter.app.excel.novelSpec
import com.novelcharacter.app.excel.relationshipChangeSpec
import com.novelcharacter.app.excel.searchPresetSpec
import com.novelcharacter.app.excel.userPresetTemplateSpec
import com.novelcharacter.app.excel.relationshipSpec
import com.novelcharacter.app.excel.stateChangeSpec
import com.novelcharacter.app.excel.timelineSpec
import com.novelcharacter.app.excel.universeSpec
import com.novelcharacter.app.util.FieldValueTokenizer
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.json.JSONObject
import java.io.File

// ── 공용 ──

private fun cellText(cell: Cell?): String {
    if (cell == null) return ""
    return try { ExcelCellValue.normalize(ExcelCellValue.fromCell(cell), dateHint = false) } catch (_: Exception) { "" }
}

private fun headersOf(sheet: Sheet): List<String> {
    val row = sheet.getRow(sheet.firstRowNum) ?: return emptyList()
    return (0 until row.lastCellNum.toInt().coerceAtLeast(0)).map { cellText(row.getCell(it)) }
}

/** 데이터 행(헤더 제외)을 헤더명→값 맵으로 훑는다. */
private fun forEachDataRow(sheet: Sheet, block: (Int, (String) -> String) -> Unit) {
    val headers = headersOf(sheet)
    val idx = headers.withIndex().associate { (i, h) -> h to i }
    for (r in (sheet.firstRowNum + 1)..sheet.lastRowNum) {
        val row = sheet.getRow(r) ?: continue
        val get = { name: String -> idx[name]?.let { cellText(row.getCell(it)) } ?: "" }
        block(r, get)
    }
}

private fun pct(n: Int, d: Int): String = if (d == 0) "n/a" else "%.1f%%".format(n * 100.0 / d)

/** B층 치환 — 터미널에는 원문을 보여도 되지만, 예시는 골격만 남겨 산출물로 그대로 옮길 수 있게 한다. */
private fun shape(v: String): String {
    val t = v.replace("\n", "\\n")
    return if (t.length <= 40) t else "<${t.length}자 · 줄바꿈 ${v.count { it == '\n' }} · 콤마 ${v.count { it == ',' }}>"
}

private fun <K> MutableMap<K, Int>.inc(k: K) { this[k] = (this[k] ?: 0) + 1 }

// ── 수집 구조 ──

private class FieldValueRec(val fd: FieldDefinition, val universe: String, val value: String, val origin: String)

fun main(args: Array<String>) {
    val file = File(args[0])
    println("### PROBE  size=${file.length()}B")

    XSSFWorkbook(file).use { wb ->
        // ───────── [A] 시트 실체 ─────────
        println("\n=== [A] 시트 목록 ===")
        val sheetsByName = LinkedHashMap<String, Sheet>()
        var holeSheets = 0
        for (i in 0 until wb.numberOfSheets) {
            val sh = wb.getSheetAt(i)
            sheetsByName[sh.sheetName] = sh
            val last = sh.lastRowNum
            val phys = sh.physicalNumberOfRows
            val cols = headersOf(sh).size
            val dataRows = if (phys > 0) phys - 1 else 0
            val hole = phys > 0 && (last + 1) != phys
            if (hole) holeSheets++
            println("[${sh.sheetName}] last=$last physical=$phys 데이터행=$dataRows 열=$cols" +
                (if (hole) "  ⚠구멍=${last + 1 - phys}" else "") +
                (if (phys == 1) "  ⚠헤더만" else "") +
                (if (sh.mergedRegions.isNotEmpty()) "  병합=${sh.mergedRegions.size}" else ""))
        }
        println("시트 총 ${wb.numberOfSheets}개 · 구멍/헤더만 의심 ${holeSheets}개")

        // ───────── [B] spec 시트 정체 판별 (6장) ─────────
        println("\n=== [B] spec 시트 판별 (정확일치 → 헤더검증 → 접미사변형) ===")
        val specs = listOf(
            universeSpec(), novelSpec(emptyList()), fieldDefinitionSpec(emptyList()),
            fieldValueLibrarySpec(), characterFieldValueSpec(), timelineSpec(emptyList()),
            stateChangeSpec(), relationshipSpec(), relationshipChangeSpec(), nameBankSpec(),
            userPresetTemplateSpec(), characterListPresetSpec(), searchPresetSpec(),
            factionSpec(), factionMembershipSpec(), factionRelationshipSpec(),
            appSettingsSpec(), imageMetaSpec()
        )
        for (spec in specs) {
            val exact = sheetsByName[spec.sheetName]
            val variants = sheetsByName.keys.filter { isSuffixedVariantOf(it, spec.sheetName) }
            val hdrOk = exact?.let { headersMatchSpec(headersOf(it), spec) }
            val rows = exact?.let { if (it.physicalNumberOfRows > 0) it.physicalNumberOfRows - 1 else 0 }
            val variantVerdict = variants.map { name ->
                val sh = sheetsByName[name]!!
                "$name(헤더일치=${headersMatchSpec(headersOf(sh), spec)})"
            }
            println("%-14s 정확일치=%-5s 헤더일치=%-5s 행=%-6s 접미사변형=%s".format(
                spec.sheetName, exact != null, hdrOk?.toString() ?: "-", rows?.toString() ?: "-",
                if (variantVerdict.isEmpty()) "없음" else variantVerdict))
        }

        // ───────── [C] 필드 정의 (5-1) ─────────
        println("\n=== [C] 필드 정의 (5-1 필드 설계) ===")
        val fieldDefs = mutableListOf<Pair<String, FieldDefinition>>()   // universeName to fd
        val typeCount = mutableMapOf<String, Int>()
        val entityCount = mutableMapOf<String, Int>()
        val groupCount = mutableMapOf<String, Int>()
        val configKeyCount = mutableMapOf<String, Int>()
        var nonZeroOrder = 0; var requiredY = 0; var badConfig = 0
        var fieldsWithConfigKeys = 0
        sheetsByName["필드 정의"]?.let { sh ->
            forEachDataRow(sh) { _, get ->
                val uni = get("세계관"); val key = get("필드키"); val name = get("필드명")
                val type = get("타입"); val cfg = get("설정(JSON)").ifBlank { "{}" }
                val group = get("그룹"); val order = get("순서")
                val req = get("필수여부"); val target = get("대상").ifBlank { "캐릭터" }
                if (key.isBlank() && name.isBlank()) return@forEachDataRow
                typeCount.inc(type)
                entityCount.inc(target)
                groupCount.inc(group.ifBlank { "(빈칸)" })
                if ((order.toIntOrNull() ?: 0) != 0) nonZeroOrder++
                if (req == "Y") requiredY++
                var keys = emptyList<String>()
                try {
                    val j = JSONObject(cfg)
                    keys = j.keys().asSequence().toList()
                } catch (_: Exception) { badConfig++ }
                if (keys.isNotEmpty()) fieldsWithConfigKeys++
                keys.forEach { configKeyCount.inc(it) }
                fieldDefs.add(uni to FieldDefinition(
                    universeId = 0, key = key, name = name, type = type, config = cfg,
                    groupName = group, displayOrder = order.toIntOrNull() ?: 0,
                    isRequired = req == "Y",
                    entityType = if (target == "사건") FieldDefinition.ENTITY_EVENT else FieldDefinition.ENTITY_CHARACTER
                ))
            }
        }
        val nDefs = fieldDefs.size
        println("필드 정의 $nDefs 개")
        println("  타입 분포: " + typeCount.entries.sortedByDescending { it.value }
            .joinToString(" · ") { "${it.key}=${it.value}(${pct(it.value, nDefs)})" })
        println("  대상 분포: " + entityCount.entries.joinToString(" · ") { "${it.key}=${it.value}" })
        println("  그룹 ${groupCount.size}종 · 기본값('기본 정보')=${groupCount["기본 정보"] ?: 0} · 그 외=${nDefs - (groupCount["기본 정보"] ?: 0)}")
        println("  순서≠0: $nonZeroOrder/$nDefs · 필수여부=Y: $requiredY/$nDefs · config 파싱실패: $badConfig")
        println("  설정(JSON) 키를 1개라도 가진 필드: $fieldsWithConfigKeys/$nDefs (${pct(fieldsWithConfigKeys, nDefs)})")
        println("  이탈 키 종류별: " + configKeyCount.entries.sortedByDescending { it.value }
            .joinToString(" · ") { "${it.key}=${it.value}" })

        // 세계관 간 같은 뜻 다른 필드키 / 같은 키 재사용 (원칙 05)
        val byUniverse = fieldDefs.groupBy({ it.first }, { it.second })
        val nameToKeys = mutableMapOf<String, MutableSet<String>>()
        val keyToUniverses = mutableMapOf<String, MutableSet<String>>()
        for ((uni, fd) in fieldDefs) {
            nameToKeys.getOrPut(fd.name) { mutableSetOf() }.add(fd.key)
            keyToUniverses.getOrPut(fd.key) { mutableSetOf() }.add(uni)
        }
        val sameNameDiffKey = nameToKeys.filter { it.value.size > 1 }
        val sharedKeys = keyToUniverses.filter { it.value.size > 1 }
        println("  세계관 ${byUniverse.size}개 · 세계관별 필드수: " +
            byUniverse.entries.sortedByDescending { it.value.size }.joinToString(" · ") { "${it.value.size}" })
        println("  같은 필드명·다른 필드키: ${sameNameDiffKey.size}건" +
            (if (sameNameDiffKey.isEmpty()) "" else " (예: " + sameNameDiffKey.entries.take(3).joinToString(", ") { "${it.key}→키${it.value.size}종" } + ")"))
        println("  세계관 2개 이상이 공유하는 필드키: ${sharedKeys.size}건 / 전체 키 ${keyToUniverses.size}종")

        // ───────── [D] 값 라이브러리 (5-2) ─────────
        println("\n=== [D] 필드 데이터 = 값 라이브러리 (5-2) ===")
        sheetsByName["필드 데이터"]?.let { sh ->
            var n = 0
            val origin = mutableMapOf<String, Int>()
            var label = 0; var alias = 0; var category = 0; var desc = 0; var hidden = 0
            var useZero = 0; var curatedUnused = 0
            forEachDataRow(sh) { _, get ->
                n++
                origin.inc(get("출처").ifBlank { "(빈칸)" })
                val hasLabel = get("표시라벨").isNotBlank()
                val hasAlias = get("별칭(콤마구분)").isNotBlank()
                val hasCat = get("카테고리").isNotBlank()
                if (hasLabel) label++
                if (hasAlias) alias++
                if (hasCat) category++
                if (get("설명").isNotBlank()) desc++
                if (get("숨김") == "Y") hidden++
                val uses = get("사용횟수").toIntOrNull() ?: 0
                if (uses == 0) useZero++
                if (uses == 0 && (hasLabel || hasAlias || hasCat)) curatedUnused++
            }
            println("엔트리 $n 개")
            println("  출처: " + origin.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key}=${it.value}(${pct(it.value, n)})" })
            println("  표시라벨=$label(${pct(label, n)}) · 별칭=$alias(${pct(alias, n)}) · 카테고리=$category(${pct(category, n)}) · 설명=$desc(${pct(desc, n)})")
            println("  숨김=Y: $hidden(${pct(hidden, n)}) · 사용횟수 0: $useZero(${pct(useZero, n)}) · 큐레이션됐는데 미사용: $curatedUnused")
        } ?: println("시트 없음")

        // ───────── [E] 캐릭터 시트 + 필드값 수집 ─────────
        println("\n=== [E] 캐릭터 시트 · 필드값 수집 ===")
        val universeNames = sheetsByName["세계관"]?.let { sh ->
            val out = mutableListOf<String>()
            forEachDataRow(sh) { _, get -> get("이름").takeIf { it.isNotBlank() }?.let { out.add(it) } }
            out
        } ?: emptyList()
        val novelTitles = sheetsByName["작품"]?.let { sh ->
            val out = mutableListOf<String>()
            forEachDataRow(sh) { _, get -> get("제목").takeIf { it.isNotBlank() }?.let { out.add(it) } }
            out
        } ?: emptyList()
        println("세계관 ${universeNames.size}개 · 작품 ${novelTitles.size}개")

        val values = mutableListOf<FieldValueRec>()
        var characterCount = 0
        val fixedCols = setOf("이름", "성", "이름(First)", "이명", "이미지경로", "작품", "메모", "태그",
            "코드", "작품코드", "정렬순서", "고정", "생성일")
        val memoValues = mutableListOf<String>()
        val aliasValues = mutableListOf<String>()
        val characterNames = mutableSetOf<String>()
        var unmappedCols = 0
        val unmappedSamples = mutableListOf<String>()

        for ((name, sh) in sheetsByName) {
            val headers = headersOf(sh)
            if (headers.firstOrNull() != "이름") continue
            if (CHARACTER_SHEET_FINGERPRINT.any { it !in headers }) continue   // 캐릭터 시트 지문
            // 이 시트의 세계관 판정: 시트명 정확일치 → 접미사 변형 → 미분류
            val uni = universeNames.firstOrNull { it == name }
                ?: universeNames.firstOrNull { isSuffixedVariantOf(name, it) }
                ?: if (name == UNCLASSIFIED_SHEET_NAME || isSuffixedVariantOf(name, UNCLASSIFIED_SHEET_NAME)) "(미분류)" else "(불명)"
            val defsHere = byUniverse[uni].orEmpty().filter { it.entityType == FieldDefinition.ENTITY_CHARACTER }
            // 헤더 → 필드정의 매핑 (내보내기 규약: "필드명" 또는 "필드명(필드키)" 또는 "… (쉼표 구분)")
            val colToDef = mutableMapOf<Int, FieldDefinition>()
            headers.forEachIndexed { ci, h ->
                if (h.isBlank() || h in fixedCols) return@forEachIndexed
                val core = h.removeSuffix(" (쉼표 구분)")
                val keyed = Regex("^(.*)\\((.+)\\)$").find(core)
                val fd = when {
                    keyed != null -> defsHere.firstOrNull { it.key == keyed.groupValues[2] }
                        ?: defsHere.firstOrNull { it.name == core }
                    else -> defsHere.firstOrNull { it.name == core }
                }
                if (fd != null) colToDef[ci] = fd
                else { unmappedCols++; if (unmappedSamples.size < 5) unmappedSamples.add("[$name] $h") }
            }
            var rows = 0
            forEachDataRow(sh) { r, _ -> rows++ }
            for (r in (sh.firstRowNum + 1)..sh.lastRowNum) {
                val row = sh.getRow(r) ?: continue
                val nm = cellText(row.getCell(headers.indexOf("이름")))
                if (nm.isBlank()) continue
                characterCount++
                characterNames.add(nm)
                headers.indexOf("이명").takeIf { it >= 0 }?.let { ci ->
                    cellText(row.getCell(ci)).takeIf { it.isNotBlank() }?.let { aliasValues.add(it) } }
                headers.indexOf("메모").takeIf { it >= 0 }?.let { ci ->
                    cellText(row.getCell(ci)).takeIf { it.isNotBlank() }?.let { memoValues.add(it) } }
                for ((ci, fd) in colToDef) {
                    val v = cellText(row.getCell(ci))
                    if (v.isNotBlank()) values.add(FieldValueRec(fd, uni, v, "캐릭터시트"))
                }
            }
            println("  [$name] 세계관='$uni' 필드열=${colToDef.size}/${headers.size - fixedCols.count { it in headers }} 행=$rows")
        }
        // 오버플로 시트
        sheetsByName["캐릭터 필드값"]?.let { sh ->
            var n = 0
            forEachDataRow(sh) { _, get ->
                val uni = get("세계관"); val key = get("필드키"); val v = get("값")
                if (v.isBlank()) return@forEachDataRow
                val fd = byUniverse[uni].orEmpty().firstOrNull { it.key == key }
                    ?: FieldDefinition(universeId = 0, key = key, name = get("필드명"), type = "TEXT")
                values.add(FieldValueRec(fd, uni, v, "오버플로")); n++
            }
            println("  [캐릭터 필드값] $n 행")
        } ?: println("  [캐릭터 필드값] 시트 없음")
        println("캐릭터 $characterCount 명 · 필드값 ${values.size} 건 · 매핑실패 열 $unmappedCols" +
            (if (unmappedSamples.isEmpty()) "" else " (예: ${unmappedSamples.joinToString(", ")})"))

        // ───────── [F] 왕복 무결성 셀 스캔 (6장) ─────────
        println("\n=== [F] 셀 해석 · 왕복 무결성 (6장) ===")
        var totalCells = 0; var numericCells = 0; var stringCells = 0; var formulaCells = 0; var boolCells = 0
        var dateLike = 0; var zeroPad = 0; var sciNotation = 0; var mergedTotal = 0
        var leadingZeroText = 0
        val dateSamples = mutableListOf<String>(); val zeroPadSamples = mutableListOf<String>()
        val sciSamples = mutableListOf<String>(); val lzSamples = mutableListOf<String>()
        for ((sname, sh) in sheetsByName) {
            mergedTotal += sh.mergedRegions.size
            for (r in sh.firstRowNum..sh.lastRowNum) {
                val row = sh.getRow(r) ?: continue
                for (c in 0 until row.lastCellNum.toInt().coerceAtLeast(0)) {
                    val cell = row.getCell(c) ?: continue
                    totalCells++
                    val p = try { ExcelCellValue.fromCell(cell) } catch (_: Exception) { continue }
                    when (p.type) {
                        CellType.NUMERIC -> {
                            numericCells++
                            if (ExcelCellValue.isLikelyDate(p, false)) {
                                dateLike++
                                if (dateSamples.size < 3) dateSamples.add("[$sname] 서식='${p.dataFormatString}' → '${ExcelCellValue.formatDate(p)}'")
                            } else {
                                val fmt = p.dataFormatString
                                if (fmt != null && Regex("0{2,}").matches(fmt)) {
                                    zeroPad++
                                    if (zeroPadSamples.size < 3) zeroPadSamples.add("[$sname] 서식='$fmt' → '${cellText(cell)}'")
                                }
                                if (p.numericValue.toString().contains("E")) {
                                    sciNotation++
                                    if (sciSamples.size < 3) sciSamples.add("[$sname] raw=${p.numericValue} → '${cellText(cell)}'")
                                }
                            }
                        }
                        CellType.STRING -> {
                            stringCells++
                            val s = p.stringValue ?: ""
                            if (Regex("^0\\d+$").matches(s.trim())) {
                                leadingZeroText++
                                if (lzSamples.size < 3) lzSamples.add("[$sname] '$s'")
                            }
                        }
                        CellType.FORMULA -> formulaCells++
                        CellType.BOOLEAN -> boolCells++
                        else -> {}
                    }
                }
            }
        }
        println("셀 $totalCells (문자열 $stringCells · 숫자 $numericCells · 수식 $formulaCells · 불리언 $boolCells)")
        println("  병합 영역: $mergedTotal 건  ← B-7")
        println("  날짜 서식 판정 셀: $dateLike 건  ← B-27①" + (if (dateSamples.isEmpty()) "" else "\n    " + dateSamples.joinToString("\n    ")))
        println("  0패딩 서식 셀: $zeroPad 건" + (if (zeroPadSamples.isEmpty()) "" else "\n    " + zeroPadSamples.joinToString("\n    ")))
        println("  과학표기 위험 셀: $sciNotation 건" + (if (sciSamples.isEmpty()) "" else "\n    " + sciSamples.joinToString("\n    ")))
        println("  선행0 문자열 셀: $leadingZeroText 건" + (if (lzSamples.isEmpty()) "" else "\n    " + lzSamples.joinToString("\n    ")))

        // ───────── [G] 다중값 구분 (6장 · B-27②) ─────────
        println("\n=== [G] 다중값 구분 — 값 안의 콤마 (B-27②) ===")
        var commaTotal = 0; var commaMulti = 0; var commaPlain = 0
        val commaPlainByField = mutableMapOf<String, Int>()
        val commaPlainSamples = mutableMapOf<String, MutableList<String>>()
        var thousandSep = 0; var sentenceComma = 0; var listLike = 0
        for (rec in values) {
            if (!rec.value.contains(",")) continue
            commaTotal++
            val multi = try { FieldValueTokenizer.isMultiToken(rec.fd) } catch (_: Exception) { false }
            if (multi) commaMulti++ else {
                commaPlain++
                commaPlainByField.inc("${rec.fd.name}(${rec.fd.type})")
                commaPlainSamples.getOrPut("${rec.fd.name}(${rec.fd.type})") { mutableListOf() }
                    .let { if (it.size < 3) it.add(shape(rec.value)) }
            }
            when {
                Regex("\\d,\\d{3}(\\D|$)").containsMatchIn(rec.value) -> thousandSep++
                Regex("[.!?]|다,|고,|서,|며,").containsMatchIn(rec.value) || rec.value.length > 40 -> sentenceComma++
                else -> listLike++
            }
        }
        println("값 안 콤마 총 $commaTotal 건 / 필드값 ${values.size} 건")
        println("  다중값 필드(MULTI_TEXT·콤마목록·글머리) = $commaMulti · 단일값 필드(쪼개면 파손) = $commaPlain")
        println("  유형: 목록형 $listLike / 문장부호형 $sentenceComma / 천단위형 $thousandSep")
        commaPlainByField.entries.sortedByDescending { it.value }.take(8).forEach { (f, n) ->
            println("    $f: ${n}건 · 예: ${commaPlainSamples[f]?.joinToString(" | ")}")
        }

        // ───────── [H] 타입 선언 vs 값 불일치 (4-1③ · §5 변수 제어) ─────────
        println("\n=== [H] 타입 선언과 값의 불일치 (4-1③) ===")
        var numberBad = 0; var selectBad = 0; var gradeBad = 0; var bodyBad = 0
        val numberBadSamples = mutableListOf<String>(); val selectBadSamples = mutableListOf<String>()
        val bodyBadSamples = mutableListOf<String>()
        val numberBadByField = mutableMapOf<String, Int>()
        val selectBadByField = mutableMapOf<String, Int>()
        for (rec in values) {
            val v = rec.value.trim()
            when (rec.fd.type) {
                "NUMBER" -> if (v.toDoubleOrNull() == null) {
                    numberBad++; numberBadByField.inc(rec.fd.name)
                    if (numberBadSamples.size < 3) numberBadSamples.add("${rec.fd.name}='${shape(v)}'")
                }
                "SELECT" -> {
                    val opts = try {
                        val arr = JSONObject(rec.fd.config).optJSONArray("options")
                        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
                    } catch (_: Exception) { emptyList() }
                    if (opts.isNotEmpty()) {
                        val toks = try { FieldValueTokenizer.tokenize(rec.fd, v) } catch (_: Exception) { listOf(v) }
                        if (toks.any { it !in opts }) {
                            selectBad++; selectBadByField.inc(rec.fd.name)
                            if (selectBadSamples.size < 3) selectBadSamples.add("${rec.fd.name}='${shape(v)}' (옵션 ${opts.size}종)")
                        }
                    }
                }
                "GRADE" -> if (v.toDoubleOrNull() == null && v.length > 6) gradeBad++
                "BODY_SIZE" -> {
                    val parts = try { FieldValueTokenizer.splitForStats(rec.fd, v) } catch (_: Exception) { listOf(v) }
                    if (parts.size < 2) {
                        bodyBad++
                        if (bodyBadSamples.size < 3) bodyBadSamples.add("${rec.fd.name}='${shape(v)}'")
                    }
                }
            }
        }
        println("NUMBER인데 수치 아님: $numberBad 건" + (if (numberBadSamples.isEmpty()) "" else " · 예: ${numberBadSamples.joinToString(" | ")}"))
        if (numberBadByField.isNotEmpty()) println("    필드별: " + numberBadByField.entries.sortedByDescending { it.value }.take(6).joinToString(" · ") { "${it.key}=${it.value}" })
        println("SELECT인데 옵션 밖: $selectBad 건" + (if (selectBadSamples.isEmpty()) "" else " · 예: ${selectBadSamples.joinToString(" | ")}"))
        if (selectBadByField.isNotEmpty()) println("    필드별: " + selectBadByField.entries.sortedByDescending { it.value }.take(6).joinToString(" · ") { "${it.key}=${it.value}" })
        println("GRADE 이상: $gradeBad 건 · BODY_SIZE 파트분리 실패: $bodyBad 건" +
            (if (bodyBadSamples.isEmpty()) "" else " · 예: ${bodyBadSamples.joinToString(" | ")}"))

        // ───────── [I] 값 길이 분포 (4-1①②) ─────────
        println("\n=== [I] 값 길이 분포 (4-1①②) ===")
        val sorted = values.sortedByDescending { it.value.length }
        println("상위 20개 값 (필드 · 길이 · 골격):")
        sorted.take(20).forEach { println("  ${it.fd.name}(${it.fd.type}) ${it.value.length}자 · ${shape(it.value)}") }
        val medianByField = values.groupBy { "${it.fd.name}(${it.fd.type})" }
            .mapValues { (_, vs) -> vs.map { it.value.length }.sorted().let { it[it.size / 2] } to vs.size }
        println("필드별 값 길이 중앙값 상위 5:")
        medianByField.entries.sortedByDescending { it.value.first }.take(5)
            .forEach { println("  ${it.key} 중앙값=${it.value.first}자 (n=${it.value.second})") }

        // ───────── [J] 여러 필드에 반복 출현하는 동일 문자열 (4-1④) ─────────
        println("\n=== [J] 여러 필드에 반복 출현하는 동일 값 (4-1④) ===")
        val valueToFields = mutableMapOf<String, MutableMap<String, Int>>()
        for (rec in values) {
            if (rec.value.length > 30) continue
            valueToFields.getOrPut(rec.value) { mutableMapOf() }.inc(rec.fd.name)
        }
        val cross = valueToFields.filter { it.value.size >= 2 }
        println("2개 이상 필드에 걸친 값: ${cross.size}종")
        cross.entries.sortedByDescending { it.value.values.sum() }.take(8).forEach { (v, m) ->
            println("  '${shape(v)}': ${m.size}개 필드에 ${m.values.sum()}회")
        }

        // ───────── [K] 알려진 6패턴 (4-2) ─────────
        println("\n=== [K] 우회 흔적 6패턴 (4-2) ===")
        val pUnit = Regex("^\\s*-?\\d+(\\.\\d+)?\\s*(cm|kg|mm|m|살|세|년|월|일|%)\\s*$")
        val pSeries = Regex("\\d+\\s*(장|화|권|막|부|편|년|월|일)\\s*[~\\-–]|→")
        val pParen = Regex("^[^()]+\\([^()]+\\)$")
        val counts = mutableMapOf<String, Int>()
        val samples = mutableMapOf<String, MutableList<String>>()
        val fieldsOf = mutableMapOf<String, MutableSet<String>>()
        fun hit(p: String, rec: FieldValueRec) {
            counts.inc(p)
            fieldsOf.getOrPut(p) { mutableSetOf() }.add(rec.fd.name)
            samples.getOrPut(p) { mutableListOf() }.let { if (it.size < 3) it.add("${rec.fd.name}:'${shape(rec.value)}'") }
        }
        var parenSuffixFp = 0
        for (rec in values) {
            val v = rec.value
            val multi = try { FieldValueTokenizer.isMultiToken(rec.fd) } catch (_: Exception) { false }
            if (v.contains(",") && !multi && rec.fd.type == "TEXT") hit("1.값안콤마(TEXT·단일값)", rec)
            if (pUnit.containsMatchIn(v) && (rec.fd.type == "NUMBER" || rec.fd.type == "TEXT")) hit("2.단위붙은수치", rec)
            if (pSeries.containsMatchIn(v)) hit("3.시계열표기", rec)
            if (pParen.matches(v)) {
                hit("4.괄호별칭", rec)
                if (Regex("^.+\\(\\d+\\)$").matches(v)) parenSuffixFp++
            }
            if (v.count { it == '\n' } >= 3) hit("6.줄바꿈3이상", rec)
        }
        // 5. 다른 캐릭터 이름 포함 — 이름 2자 이상만, 자기 자신 제외
        val nameNeedles = characterNames.filter { it.length >= 2 }
        var otherNameHits = 0
        val otherNameFields = mutableMapOf<String, Int>()
        for (rec in values) {
            if (rec.value.length < 2) continue
            val found = nameNeedles.firstOrNull { rec.value.contains(it) }
            if (found != null) { otherNameHits++; otherNameFields.inc(rec.fd.name) }
        }
        for ((p, n) in counts.entries.sortedBy { it.key }) {
            println("  $p: ${n}건 · ${fieldsOf[p]?.size}개 필드 · 예: ${samples[p]?.joinToString(" | ")}")
        }
        println("  4.괄호별칭 중 '(숫자)' 접미사·수량 의심 오탐: ${parenSuffixFp}건")
        println("  5.다른캐릭터이름포함: ${otherNameHits}건 · 필드별 상위: " +
            otherNameFields.entries.sortedByDescending { it.value }.take(5).joinToString(" · ") { "${it.key}=${it.value}" })
        values.asSequence().filter { it.value.length >= 2 }
            .mapNotNull { rec -> nameNeedles.firstOrNull { rec.value.contains(it) }?.let { rec to it } }
            .take(6).forEach { (rec, needle) ->
                println("      오탐확인용: ${rec.fd.name}='${shape(rec.value)}' ← 이름 '$needle' 포함")
            }
        // 메모·이명 별도 (고정 열이라 필드값 집합에 없다)
        val memoSeries = memoValues.count { pSeries.containsMatchIn(it) }
        val memoNl = memoValues.count { it.count { c -> c == '\n' } >= 3 }
        println("  [고정열] 메모 ${memoValues.size}건: 시계열표기 $memoSeries · 줄바꿈3이상 $memoNl · 최장 ${memoValues.maxOfOrNull { it.length } ?: 0}자")
        println("  [고정열] 이명 ${aliasValues.size}건: 콤마포함 ${aliasValues.count { it.contains(",") }} · 괄호 ${aliasValues.count { pParen.matches(it) }}")

        // ───────── [M] 값 라이브러리 정합성 — 수확 축 vs 집계 축 (R-16) ─────────
        // harvestUniversesOrThrow 는 캐릭터값·사건값·**상태변화 새 값** 3축에서 토큰을 수확하는데,
        // recountUsageOrThrow 는 캐릭터값·사건값 2축만 센다. 상태변화에만 등장하는 값은
        // 라이브러리에 실재하면서 영구히 usageCount=0 이 된다 — 실파일로 그 수를 잰다.
        println("\n=== [M] 값 라이브러리 정합성 (수확 3축 vs 집계 2축) ===")
        run {
            val lib = sheetsByName["필드 데이터"] ?: return@run
            // (세계관, 필드키) → 그 필드의 캐릭터값 토큰 / 상태변화 토큰
            val charTokens = mutableMapOf<Pair<String, String>, MutableSet<String>>()
            for (rec in values) {
                val fd = rec.fd
                val toks = try { FieldValueTokenizer.tokenize(fd, rec.value) } catch (_: Exception) { listOf(rec.value) }
                charTokens.getOrPut(rec.universe to fd.key) { mutableSetOf() }.addAll(toks)
            }
            // 상태변화: 캐릭터 이름 → 세계관 (캐릭터 시트에서 이미 수집한 매핑이 없으므로 시트에서 다시)
            val charUniverse = mutableMapOf<String, String>()
            for ((name, sh) in sheetsByName) {
                val headers = headersOf(sh)
                if (headers.firstOrNull() != "이름") continue
                if (CHARACTER_SHEET_FINGERPRINT.any { it !in headers }) continue
                val uni = universeNames.firstOrNull { it == name }
                    ?: universeNames.firstOrNull { isSuffixedVariantOf(name, it) }
                    ?: if (name == UNCLASSIFIED_SHEET_NAME || isSuffixedVariantOf(name, UNCLASSIFIED_SHEET_NAME)) "(미분류)" else "(불명)"
                forEachDataRow(sh) { _, get -> get("이름").takeIf { it.isNotBlank() }?.let { charUniverse[it] = uni } }
            }
            val stateTokens = mutableMapOf<Pair<String, String>, MutableSet<String>>()
            var stateRows = 0; var stateUnmapped = 0
            sheetsByName["캐릭터 상태변화"]?.let { sh ->
                forEachDataRow(sh) { _, get ->
                    stateRows++
                    val ch = get("캐릭터"); val key = get("필드키"); val nv = get("새 값")
                    if (nv.isBlank() || key.startsWith("__")) return@forEachDataRow
                    val uni = charUniverse[ch]
                    if (uni == null) { stateUnmapped++; return@forEachDataRow }
                    val fd = byUniverse[uni].orEmpty().firstOrNull { it.key == key }
                    val toks = if (fd == null) listOf(nv.trim())
                        else try { FieldValueTokenizer.tokenize(fd, nv) } catch (_: Exception) { listOf(nv.trim()) }
                    stateTokens.getOrPut(uni to key) { mutableSetOf() }.addAll(toks)
                }
            }
            var n = 0; var zero = 0
            var zeroInChar = 0; var zeroOnlyInState = 0; var zeroNowhere = 0
            var nonZeroNotInChar = 0
            val onlyStateSamples = mutableListOf<String>()
            val fieldsHit = mutableSetOf<String>()
            forEachDataRow(lib) { _, get ->
                val uni = get("세계관"); val key = get("필드키"); val v = get("값")
                if (v.isBlank()) return@forEachDataRow
                n++
                val uses = get("사용횟수").toIntOrNull() ?: 0
                val inChar = charTokens[uni to key]?.contains(v) == true
                val inState = stateTokens[uni to key]?.contains(v) == true
                if (uses == 0) {
                    zero++
                    when {
                        inChar -> zeroInChar++
                        inState -> {
                            zeroOnlyInState++
                            fieldsHit.add("${get("필드명")}")
                            if (onlyStateSamples.size < 3) onlyStateSamples.add("${get("필드명")}='$v'")
                        }
                        else -> zeroNowhere++
                    }
                } else if (!inChar) nonZeroNotInChar++
            }
            println("라이브러리 $n 엔트리 · 상태변화 $stateRows 행(캐릭터 매핑 실패 $stateUnmapped)")
            println("  사용횟수=0: $zero (${pct(zero, n)})")
            println("    ├ 현재 캐릭터값에 실재(집계 누락 의심): $zeroInChar")
            println("    ├ 상태변화에만 실재 → **수확은 되고 집계는 안 되는 축**: $zeroOnlyInState" +
                (if (onlyStateSamples.isEmpty()) "" else " · ${fieldsHit.size}개 필드 · 예: ${onlyStateSamples.joinToString(" | ")}"))
            println("    └ 어디에도 없음(과거 값의 잔재 — 정상): $zeroNowhere")
            println("  사용횟수>0 인데 현재 캐릭터값에 없음: $nonZeroNotInChar")
            // 집계가 필드 단위로 도는가(=필드별 화면 진입 시점에만 갱신) 확인 — 필드별 0/비0 분포
            val perField = mutableMapOf<String, IntArray>()   // [비0, 0]
            forEachDataRow(lib) { _, get ->
                val v = get("값"); if (v.isBlank()) return@forEachDataRow
                val uses = get("사용횟수").toIntOrNull() ?: 0
                val arr = perField.getOrPut(get("필드명")) { IntArray(2) }
                if (uses > 0) arr[0]++ else arr[1]++
            }
            val allZero = perField.filter { it.value[0] == 0 }
            val allNonZero = perField.filter { it.value[1] == 0 }
            val mixed = perField.filter { it.value[0] > 0 && it.value[1] > 0 }
            println("  필드 ${perField.size}개 중 — 전부 0: ${allZero.size} · 전부 비0: ${allNonZero.size} · 섞임: ${mixed.size}")
            println("    (전부 0 또는 전부 비0으로 갈리면 집계가 **필드 단위로 한 번씩만** 돈다는 뜻 = 화면 진입 갱신)")
        }

        // ───────── [N] 사건 · 관계 도달률 세부 (5-3 보조) ─────────
        println("\n=== [N] 사건 · 관계 세부 ===")
        sheetsByName["사건 연표"]?.let { sh ->
            var n = 0; var withNovel = 0; var withChar = 0; var provisional = 0; var withCalendar = 0
            val types = mutableMapOf<String, Int>()
            forEachDataRow(sh) { _, get ->
                n++
                if (get("관련 작품").isNotBlank()) withNovel++
                if (get("관련 캐릭터").isNotBlank()) withChar++
                if (get("임시배치") == "Y") provisional++
                if (get("역법").isNotBlank()) withCalendar++
                types.inc(get("사건 유형").ifBlank { "(빈칸)" })
            }
            println("  사건 $n 건 · 작품연결 $withNovel(${pct(withNovel, n)}) · 캐릭터연결 $withChar(${pct(withChar, n)})" +
                " · 임시배치 $provisional · 역법기입 $withCalendar")
            println("    사건 유형: " + types.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key}=${it.value}" })
        }
        sheetsByName["캐릭터 관계"]?.let { sh ->
            var n = 0; var withDesc = 0; var bidir = 0; var fromFaction = 0
            val relTypes = mutableMapOf<String, Int>()
            forEachDataRow(sh) { _, get ->
                n++
                if (get("설명").isNotBlank()) withDesc++
                if (get("양방향") == "Y") bidir++
                if (get("세력").isNotBlank()) fromFaction++
                relTypes.inc(get("관계 유형").ifBlank { "(빈칸)" })
            }
            println("  관계 $n 건 · 설명채움 $withDesc(${pct(withDesc, n)}) · 양방향 $bidir · 세력자동 $fromFaction · 유형 ${relTypes.size}종")
        }
        sheetsByName["캐릭터 상태변화"]?.let { sh ->
            val keys = mutableMapOf<String, Int>()
            var n = 0; var withDesc = 0
            forEachDataRow(sh) { _, get ->
                n++
                keys.inc(get("필드키").ifBlank { "(빈칸)" })
                if (get("설명").isNotBlank()) withDesc++
            }
            println("  상태변화 $n 건 · ${keys.size}종 필드키 · 설명채움 $withDesc" +
                " · 상위: " + keys.entries.sortedByDescending { it.value }.take(5).joinToString(" · ") { "${it.key}=${it.value}" })
        }

        // ───────── [O] 참조 열의 콤마 위험 (B-27② 실측) ─────────
        // B-27②의 실제 파손 지점은 '값 안의 콤마'가 아니라 **참조 열**이다 —
        // 관련 캐릭터·소속 세력처럼 콤마로 여러 이름을 나열하는 열에서, 이름 자체에 콤마가 있으면
        // split(",")이 한 이름을 둘로 쪼갠다. 그런 이름이 실제로 있는지 센다.
        println("\n=== [O] 참조 열 콤마 위험 (B-27②) ===")
        run {
            fun commaNames(sheetName: String, col: String): Pair<Int, List<String>> {
                val sh = sheetsByName[sheetName] ?: return 0 to emptyList()
                val hits = mutableListOf<String>()
                var total = 0
                forEachDataRow(sh) { _, get ->
                    val v = get(col)
                    if (v.isBlank()) return@forEachDataRow
                    total++
                    if (v.contains(",")) hits.add(v)
                }
                return total to hits
            }
            val checks = listOf(
                Triple("캐릭터 이름", "세계관", ""),   // 자리표시 — 아래에서 캐릭터명은 따로 센다
                Triple("작품 제목", "작품", "제목"),
                Triple("세계관 이름", "세계관", "이름"),
                Triple("세력 이름", "세력", "이름")
            )
            val nameComma = characterNames.count { it.contains(",") }
            println("  캐릭터 이름 ${characterNames.size}종 중 콤마 포함: $nameComma" +
                (if (nameComma > 0) " ⚠ 관련 캐릭터 열 split(\",\")이 쪼갠다" else ""))
            for ((label, sheet, col) in checks.drop(1)) {
                val (total, hits) = commaNames(sheet, col)
                println("  $label $total 종 중 콤마 포함: ${hits.size}" + (if (hits.isEmpty()) "" else " ⚠ 예: ${hits.take(2).joinToString(" | ") { shape(it) }}"))
            }
            // 사건의 '관련 캐릭터' 열 — 토큰이 실제 캐릭터명으로 해석되는가
            sheetsByName["사건 연표"]?.let { sh ->
                var cells = 0; var tokens = 0; var unresolved = 0
                val unresolvedSamples = mutableListOf<String>()
                forEachDataRow(sh) { _, get ->
                    val v = get("관련 캐릭터")
                    if (v.isBlank()) return@forEachDataRow
                    cells++
                    for (t in v.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                        tokens++
                        if (t !in characterNames) {
                            unresolved++
                            if (unresolvedSamples.size < 3) unresolvedSamples.add(t)
                        }
                    }
                }
                println("  사건 '관련 캐릭터': $cells 셀 · $tokens 토큰 · 이름 미해석 $unresolved" +
                    (if (unresolvedSamples.isEmpty()) "" else " · 예: ${unresolvedSamples.joinToString(" | ")}"))
            }
            // 세력 소속의 '세력'/'캐릭터' 열도 같은 규약
            sheetsByName["세력 소속"]?.let { sh ->
                var n = 0; var unresolved = 0
                forEachDataRow(sh) { _, get ->
                    val c = get("캐릭터")
                    if (c.isBlank()) return@forEachDataRow
                    n++
                    if (c !in characterNames) unresolved++
                }
                println("  세력 소속 '캐릭터': $n 행 · 이름 미해석 $unresolved")
            }
        }

        // ───────── [P] 안정 식별자(코드) 정합성 — R-1의 전제 검사 ─────────
        // R-1은 "코드가 대상을 정한다". 코드가 비었거나 겹치면 가져오기는 이름 매칭으로 떨어지고
        // 동명이인에서 오배정이 난다. B-25(code 없는 사건)의 실제 규모도 여기서 나온다.
        println("\n=== [P] 안정 식별자(코드) 정합성 ===")
        run {
            fun codeCheck(sheetName: String, col: String) {
                val sh = sheetsByName[sheetName] ?: run { println("  %-14s 시트 없음".format(sheetName)); return }
                var n = 0; var blank = 0
                val seen = mutableMapOf<String, Int>()
                forEachDataRow(sh) { _, get ->
                    n++
                    val c = get(col)
                    if (c.isBlank()) blank++ else seen.inc(c)
                }
                val dup = seen.filterValues { it > 1 }
                println("  %-14s %4d행 · 코드 빈칸 %d · 중복 %d%s".format(
                    sheetName, n, blank, dup.size,
                    if (dup.isEmpty()) "" else " ⚠ " + dup.entries.take(2).joinToString { "${it.key}×${it.value}" }))
            }
            listOf("세계관" to "코드", "작품" to "코드", "사건 연표" to "코드",
                "캐릭터 상태변화" to "코드", "캐릭터 관계" to "코드", "이름 은행" to "코드",
                "세력" to "코드", "필드 데이터" to "코드").forEach { (s, c) -> codeCheck(s, c) }
            // 캐릭터 코드는 시트가 여럿이라 전체를 합쳐 본다
            var chN = 0; var chBlank = 0
            val chSeen = mutableMapOf<String, Int>()
            val nameSeen = mutableMapOf<String, Int>()
            for ((name, sh) in sheetsByName) {
                val hs = headersOf(sh)
                if (hs.firstOrNull() != "이름") continue
                if (CHARACTER_SHEET_FINGERPRINT.any { it !in hs }) continue
                forEachDataRow(sh) { _, get ->
                    if (get("이름").isBlank()) return@forEachDataRow
                    chN++
                    val c = get("코드")
                    if (c.isBlank()) chBlank++ else chSeen.inc(c)
                    nameSeen.inc(get("이름"))
                }
            }
            val chDup = chSeen.filterValues { it > 1 }
            val nameDup = nameSeen.filterValues { it > 1 }
            println("  %-14s %4d행 · 코드 빈칸 %d · 중복 %d".format("캐릭터(전 시트)", chN, chBlank, chDup.size))
            println("  동명이인(같은 이름 2명 이상): ${nameDup.size}종 · 해당 캐릭터 ${nameDup.values.sum()}명" +
                (if (nameDup.isEmpty()) "" else " ⚠ 코드가 없으면 가져오기가 오배정한다"))
        }

        // ───────── [Q] CALCULATED 수식 실태 (로드맵 5 / S-11 입력) ─────────
        // 내보내기는 NaN·Inf를 mapNotNull로 떨어뜨려 **빈 셀**로 쓴다(ExcelExporter:906~911).
        // 그래서 '수식이 깨졌다'와 '아직 입력 안 했다'가 파일에서 구별되지 않는다.
        println("\n=== [Q] CALCULATED 수식 실태 ===")
        run {
            val calcDefs = fieldDefs.filter { it.second.type == "CALCULATED" }
            if (calcDefs.isEmpty()) { println("  CALCULATED 필드 없음"); return@run }
            val allKeysByUni = byUniverse.mapValues { (_, v) -> v.map { it.key }.toSet() }
            for ((uni, fd) in calcDefs) {
                val formula = try { JSONObject(fd.config).optString("formula", "") } catch (_: Exception) { "" }
                // 수식이 참조하는 필드키 추출 — `field(키)` 가 유일한 참조 문법이다
                // (FormulaEvaluator:127 `formula.startsWith("field(", i)`).
                // 맨 식별자를 필드키로 읽으면 함수명·문법 토큰을 '없는 참조'로 오탐한다.
                val refs = Regex("field\\(\\s*([^)]*?)\\s*\\)").findAll(formula)
                    .map { it.groupValues[1].trim().trim('"', '\'') }.filter { it.isNotEmpty() }.toSet()
                val known = allKeysByUni[uni].orEmpty()
                val missing = refs.filter { it !in known }
                val filled = values.count { it.fd.key == fd.key && it.universe == uni }
                val population = values.map { it.universe }.count { it == uni }
                    .let { _ -> characterNames.size }   // 대략적 모수 — 아래 시트별 수치로 보정
                println("  필드 '${fd.name}'(${uni.let { "세계관" }}) · 수식 토큰 ${refs.size}개 · " +
                    "정의에 없는 참조 ${missing.size}개${if (missing.isEmpty()) "" else " ⚠ $missing"}")
                println("    값이 채워진 행: $filled  (빈칸 = '수식 오류'와 '입력 없음'이 구별되지 않는다)")
            }
        }

        // ───────── [L] 사건 · 관계 · 상태변화 (5-3 보조) ─────────
        println("\n=== [L] 사건 · 상태변화 · 관계 규모 ===")
        listOf("사건 연표", "캐릭터 상태변화", "캐릭터 관계", "관계 변화", "이름 은행",
            "세력", "세력 소속", "세력 관계", "필드 템플릿", "목록 프리셋", "검색 프리셋", "이미지", "앱 설정")
            .forEach { n ->
                val sh = sheetsByName[n]
                val rows = sh?.let { if (it.physicalNumberOfRows > 0) it.physicalNumberOfRows - 1 else 0 }
                println("  %-14s %s".format(n, if (sh == null) "시트 없음" else "$rows 행"))
            }
    }
    println("\n### PROBE 끝")
}
