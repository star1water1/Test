// tools/usage-probe/ScaleProbe.kt — 런북 7장(규모 실측)의 하네스.
//
// Probe.kt 와 같은 규약: 경로는 args[0], 러너 목록에 넣지 않는다, 출력은 집계만.
//
// 7-1 최소 스냅샷: universes · novels · characters · fieldDefinitions · fieldValues · valueEntries
//     나머지 목록은 emptyList() 이므로 산출물에는 `0건`이 아니라 **미조립**이라고 적는다.
// 7-3 스케일 스윕: 구조를 유지한 채 캐릭터·필드값만 ×3/×10/×30 으로 복제 증폭한다
//     (필드 정의와 값 종수 분포는 그대로 — 곡선의 형태를 보는 것이 목적이다).
//
// ── S1(측정 축 확대, 2026.07.30)에서 넓힌 것 ─────────────────────────────────
// 위 7-1의 "6개 목록만"은 이제 **옛 범위**다. S1이 요구한 대로 셋을 더한다:
//   (a) 조립 범위를 events·crossRefs·eventNovelCrossRefs·relationships·stateChanges·
//       tags·factions·factionMemberships·nameBank 까지 넓힌다. 넓힌 뒤에도 남는 미조립은
//       **사유와 함께** 출력한다(0건과 미조립은 다른 말이다 — 런북 7-1).
//   (b) **힙을 시간과 함께 잰다.** 5-3이 "메모리는 측정된 적이 없다"고 남긴 자리이며
//       S4(부분 적재)는 이 수 없이 착수하지 않는다.
//   (c) **채움률 축.** 표본은 아직 덜 채워졌고 앞으로 더 채워진다(사용자 확인 2026.07.30).
//       캐릭터 수만 늘리는 축은 그 사실을 담지 못해 미래를 과소평가한다 —
//       "같은 캐릭터 수, 빈 칸을 채운" 축을 따로 잰다.
//
// 증폭 규칙(넓히면서 정한 것): **캐릭터에 매달린 것은 함께 복제하고, 세계관·작품에 매달린
// 것은 유지한다.** 캐릭터만 30배로 늘리고 관계·상태변화·태그를 그대로 두면 캐릭터당 밀도가
// 30분의 1로 묽어져 실제보다 가벼운 스냅샷을 재게 된다. 다만 이 규칙은 **캐릭터당 밀도를
// 유지**할 뿐이므로, 밀도 자체가 오르는 축(캐릭터가 늘면 1인당 관계도 는다)은 여전히 미측정이다.
//
// 실행:
//   java -Dstdout.encoding=UTF-8 -cp "$J/out-probe:$J/out-tests:$CP" ScaleProbeKt "<파일경로>"

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineEventNovelCrossRef
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

// ── S1-b 힙 측정 ──────────────────────────────────────────────────────────
// 정확한 retained size는 JVM에서 공짜로 얻을 수 없다. 여기서는 **GC로 잠재운 뒤의 사용량 차이**를
// 쓴다 — 근사지만 "×30에서 스냅샷이 몇 MB인가"라는 질문에는 답이 된다. 절대값을 소수점까지
// 믿지 말고 **증가 형태와 자릿수**를 볼 것(ART와 JVM은 객체 헤더·정렬이 다르다).

private fun quietHeapBytes(): Long {
    val rt = Runtime.getRuntime()
    repeat(4) { System.gc(); Thread.sleep(60) }
    return rt.totalMemory() - rt.freeMemory()
}

private fun mb(bytes: Long): String = "%.1fMB".format(bytes / 1024.0 / 1024.0)

/**
 * [body] 실행 중 힙 사용량의 최대치를 폴링으로 잡는다. GC가 도는 사이에 찍히면 낮게 나오므로
 * **하한**으로 읽을 것 — "적어도 이만큼은 썼다"는 뜻이지 정확한 피크가 아니다.
 */
private fun peakHeapBytes(body: () -> Unit): Long {
    val rt = Runtime.getRuntime()
    val running = java.util.concurrent.atomic.AtomicBoolean(true)
    val peak = java.util.concurrent.atomic.AtomicLong(0)
    val poller = Thread {
        while (running.get()) {
            val used = rt.totalMemory() - rt.freeMemory()
            peak.updateAndGet { if (used > it) used else it }
            Thread.sleep(3)
        }
    }
    poller.isDaemon = true
    poller.start()
    try { body() } finally { running.set(false); poller.join(500) }
    return peak.get()
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

        // ── 캐릭터 + 필드값 (+ S1: 태그 · 코드/이름 역인덱스) ──
        val characters = mutableListOf<Character>()
        val fieldValues = mutableListOf<CharacterFieldValue>()
        val tags = mutableListOf<CharacterTag>()
        val charIdByCode = mutableMapOf<String, Long>()
        val charIdByName = mutableMapOf<String, Long>()   // 동명이인은 첫 행이 이긴다(근사 — 아래 주)
        val charUniverseId = mutableMapOf<Long, Long?>()  // 캐릭터 → 세계관 (채움률 계산용)
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
                charUniverseId[cid] = uid
                charIdByName.putIfAbsent(nm, cid)
                hs.indexOf("코드").takeIf { it >= 0 }?.let { ci ->
                    text(row.getCell(ci)).takeIf { it.isNotBlank() }?.let { charIdByCode[it] = cid }
                }
                // 태그는 전용 시트가 없다 — 캐릭터 시트의 `태그` 열(쉼표 구분)이 원본이다.
                hs.indexOf("태그").takeIf { it >= 0 }?.let { ci ->
                    text(row.getCell(ci)).split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { tags.add(CharacterTag(id = tags.size + 1L, characterId = cid, tag = it)) }
                }
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

        // ── S1-a: 종전에 미조립이던 목록들 ──────────────────────────────────
        // 캐릭터 참조는 코드 우선, 없으면 이름으로 푼다. 코드가 왕복 안정 식별자이고
        // 이름은 동명이인에서 갈리기 때문이다(폴백은 근사이며, 못 푼 건수를 아래에서 보고한다).
        var unresolvedCharRefs = 0
        fun resolveChar(code: String, name: String): Long? {
            charIdByCode[code.trim()]?.let { return it }
            charIdByName[name.trim()]?.let { return it }
            if (code.isNotBlank() || name.isNotBlank()) unresolvedCharRefs++
            return null
        }

        val events = mutableListOf<TimelineEvent>()
        val crossRefs = mutableListOf<TimelineCharacterCrossRef>()
        val eventNovelCrossRefs = mutableListOf<TimelineEventNovelCrossRef>()
        byName["사건 연표"]?.let { sh ->
            rows(sh) { get ->
                val desc = get("사건 설명")
                val year = get("연도").toIntOrNull() ?: return@rows
                val eid = events.size + 1L
                events.add(TimelineEvent(
                    id = eid, year = year,
                    month = get("월").toIntOrNull(), day = get("일").toIntOrNull(),
                    calendarType = get("역법").ifBlank { "천개력" },
                    description = desc,
                    eventType = when (get("사건 유형")) { "출생" -> "birth"; "사망" -> "death"; else -> "" },
                    universeId = uniIdByName[get("세계관")],
                    displayOrder = get("정렬순서").toIntOrNull() ?: 0,
                    isTemporary = get("임시배치") == "Y",
                    code = get("코드").ifBlank { null }
                ))
                val codes = get("관련캐릭터코드").split(",").map { it.trim() }
                val names = get("관련 캐릭터").split(",").map { it.trim() }
                val n = maxOf(codes.size, names.size)
                for (i in 0 until n) {
                    val c = codes.getOrElse(i) { "" }; val nm = names.getOrElse(i) { "" }
                    if (c.isBlank() && nm.isBlank()) continue
                    resolveChar(c, nm)?.let { crossRefs.add(TimelineCharacterCrossRef(eid, it)) }
                }
                get("관련 작품").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { t -> novelIdByTitle[t]?.let { eventNovelCrossRefs.add(TimelineEventNovelCrossRef(eid, it)) } }
            }
        }

        val relationships = mutableListOf<CharacterRelationship>()
        byName["캐릭터 관계"]?.let { sh ->
            rows(sh) { get ->
                val a = resolveChar(get("캐릭터1코드"), get("캐릭터1")) ?: return@rows
                val b = resolveChar(get("캐릭터2코드"), get("캐릭터2")) ?: return@rows
                relationships.add(CharacterRelationship(
                    id = relationships.size + 1L, characterId1 = a, characterId2 = b,
                    relationshipType = get("관계 유형"), description = get("설명"),
                    intensity = get("강도").toIntOrNull() ?: 5,
                    isBidirectional = get("양방향") != "N",
                    displayOrder = get("표시순서").toIntOrNull() ?: 0,
                    code = get("코드").ifBlank { null }
                ))
            }
        }

        val stateChanges = mutableListOf<CharacterStateChange>()
        byName["캐릭터 상태변화"]?.let { sh ->
            rows(sh) { get ->
                val cid = resolveChar(get("캐릭터코드"), get("캐릭터")) ?: return@rows
                val year = get("연도").toIntOrNull() ?: return@rows
                stateChanges.add(CharacterStateChange(
                    id = stateChanges.size + 1L, characterId = cid, year = year,
                    month = get("월").toIntOrNull(), day = get("일").toIntOrNull(),
                    fieldKey = get("필드키"), newValue = get("새 값"),
                    description = get("설명"), code = get("코드").ifBlank { null }
                ))
            }
        }

        val factions = mutableListOf<Faction>()
        val factionIdByCode = mutableMapOf<String, Long>()
        val factionIdByName = mutableMapOf<String, Long>()
        byName["세력"]?.let { sh ->
            rows(sh) { get ->
                val nm = get("이름"); if (nm.isBlank()) return@rows
                val uid = uniIdByName[get("세계관")] ?: return@rows
                val id = factions.size + 1L
                factions.add(Faction(
                    id = id, universeId = uid, name = nm, description = get("설명"),
                    color = get("색상").ifBlank { "#2196F3" },
                    autoRelationType = get("자동관계유형"),
                    autoRelationIntensity = get("자동관계강도").toIntOrNull() ?: 5,
                    displayOrder = get("정렬순서").toIntOrNull() ?: 0
                ))
                get("코드").takeIf { it.isNotBlank() }?.let { factionIdByCode[it] = id }
                factionIdByName.putIfAbsent(nm, id)
            }
        }
        val factionMemberships = mutableListOf<FactionMembership>()
        byName["세력 소속"]?.let { sh ->
            rows(sh) { get ->
                val fid = factionIdByCode[get("세력코드").trim()] ?: factionIdByName[get("세력").trim()] ?: return@rows
                val cid = resolveChar(get("캐릭터코드"), get("캐릭터")) ?: return@rows
                factionMemberships.add(FactionMembership(
                    id = factionMemberships.size + 1L, factionId = fid, characterId = cid,
                    joinYear = get("가입연도").toIntOrNull(), leaveYear = get("탈퇴연도").toIntOrNull(),
                    leaveType = get("탈퇴유형").ifBlank { null }
                ))
            }
        }

        val nameBank = mutableListOf<NameBankEntry>()
        byName["이름 은행"]?.let { sh ->
            rows(sh) { get ->
                val nm = get("이름"); if (nm.isBlank()) return@rows
                nameBank.add(NameBankEntry(
                    id = nameBank.size + 1L, name = nm, gender = get("성별"),
                    origin = get("출처"), notes = get("메모"), isUsed = get("사용여부") == "Y",
                    usedByCharacterId = charIdByCode[get("사용캐릭터코드").trim()]
                ))
            }
        }

        // ── S1-c: 채움률 ──────────────────────────────────────────────────
        // 분모는 "캐릭터 × 그 캐릭터가 속한 세계관의 캐릭터 필드 정의 수"다. 필드 정의는
        // 세계관마다 다르므로 전역 90을 곱하면 분모가 부풀어 채움률이 실제보다 낮게 나온다.
        val charDefsByUniverse = defs.filter { it.entityType == FieldDefinition.ENTITY_CHARACTER }
            .groupBy { it.universeId }
        val fillDenominator = characters.sumOf { c -> (charDefsByUniverse[charUniverseId[c.id]]?.size ?: 0).toLong() }
        val fillRatio = if (fillDenominator == 0L) 0.0 else fieldValues.size.toDouble() / fillDenominator

        /** 빈 칸을 그 필드의 기존 값으로 메운 필드값 목록 — 값 종수 분포는 유지한다. */
        fun filledValues(): List<CharacterFieldValue> {
            val samplesByDef = fieldValues.groupBy { it.fieldDefinitionId }
                .mapValues { (_, v) -> v.map { it.value }.distinct() }
            val have = fieldValues.mapTo(HashSet()) { it.characterId to it.fieldDefinitionId }
            val out = ArrayList<CharacterFieldValue>(fieldValues)
            var next = fieldValues.size.toLong() + 1
            for (c in characters) {
                val here = charDefsByUniverse[charUniverseId[c.id]] ?: continue
                for ((i, fd) in here.withIndex()) {
                    if ((c.id to fd.id) in have) continue
                    val pool = samplesByDef[fd.id] ?: continue   // 표본에 값이 하나도 없는 필드는 못 채운다
                    out.add(CharacterFieldValue(
                        id = next++, characterId = c.id, fieldDefinitionId = fd.id,
                        value = pool[(c.id.toInt() + i) % pool.size]
                    ))
                }
            }
            return out
        }
        val filled = filledValues()

        /**
         * @param mult   캐릭터와 그에 매달린 목록의 복제 배수
         * @param full   true면 S1이 넓힌 전량 조립, false면 **옛 6목록 범위**(비교 기준)
         * @param fillUp true면 빈 필드값을 메운 목록을 쓴다(S1-c 채움률 축)
         */
        fun snapshotOf(mult: Int, full: Boolean = true, fillUp: Boolean = false): StatsSnapshot {
            val srcValues = if (fillUp) filled else fieldValues
            // 캐릭터에 매달린 것만 복제한다(헤더의 증폭 규칙). 세계관·작품·필드 정의·값 라이브러리·
            // 세력·이름 은행은 구조라 유지한다 — 유지하는 축은 아래 출력에 명시한다.
            val chars = ArrayList<Character>(characters.size * mult)
            val vals = ArrayList<CharacterFieldValue>(srcValues.size * mult)
            val tg = ArrayList<CharacterTag>(tags.size * mult)
            val rel = ArrayList<CharacterRelationship>(relationships.size * mult)
            val stc = ArrayList<CharacterStateChange>(stateChanges.size * mult)
            val evs = ArrayList<TimelineEvent>(events.size * mult)
            val xr = ArrayList<TimelineCharacterCrossRef>(crossRefs.size * mult)
            val exr = ArrayList<TimelineEventNovelCrossRef>(eventNovelCrossRefs.size * mult)
            val fm = ArrayList<FactionMembership>(factionMemberships.size * mult)
            val cBase = characters.size.toLong()
            val eBase = events.size.toLong()
            for (k in 0 until mult) {
                val cOff = cBase * k
                val eOff = eBase * k
                characters.forEach { chars.add(it.copy(id = it.id + cOff, name = it.name + "#" + k, code = it.code + k)) }
                // 값 문자열을 **새 인스턴스로** 만든다. copy()는 String 참조를 공유하므로 그대로 두면
                // 30벌이 문자열 하나를 나눠 써서 힙이 실제보다 훨씬 가볍게 측정된다(DB는 행마다 새
                // String을 준다). 내용은 같으므로 값 종수 분포와 집계 결과는 바뀌지 않는다 —
                // 바뀌는 것은 힙뿐이고, 그것이 이 축에서 재려는 것이다.
                srcValues.forEach {
                    vals.add(it.copy(
                        id = it.id + srcValues.size.toLong() * k,
                        characterId = it.characterId + cOff,
                        value = if (k == 0) it.value else String(it.value.toCharArray())
                    ))
                }
                if (!full) continue
                tags.forEach { tg.add(it.copy(id = it.id + tags.size.toLong() * k, characterId = it.characterId + cOff)) }
                relationships.forEach {
                    rel.add(it.copy(
                        id = it.id + relationships.size.toLong() * k,
                        characterId1 = it.characterId1 + cOff, characterId2 = it.characterId2 + cOff
                    ))
                }
                stateChanges.forEach { stc.add(it.copy(id = it.id + stateChanges.size.toLong() * k, characterId = it.characterId + cOff)) }
                events.forEach { evs.add(it.copy(id = it.id + eOff)) }
                crossRefs.forEach { xr.add(TimelineCharacterCrossRef(it.eventId + eOff, it.characterId + cOff)) }
                eventNovelCrossRefs.forEach { exr.add(TimelineEventNovelCrossRef(it.eventId + eOff, it.novelId)) }
                factionMemberships.forEach { fm.add(it.copy(id = it.id + factionMemberships.size.toLong() * k, characterId = it.characterId + cOff)) }
            }
            return StatsSnapshot(
                characters = chars, novels = novels, universes = universes,
                events = evs, relationships = rel,
                // 관계변화는 표본에 시트 자체가 없다 — 0건이 아니라 **미조립**이다(출력 참조).
                relationshipChanges = emptyList(),
                tags = tg, nameBank = if (full) nameBank else emptyList(), stateChanges = stc,
                fieldDefinitions = defs, fieldValues = vals, crossRefs = xr,
                factions = if (full) factions else emptyList(), factionMemberships = fm,
                eventNovelCrossRefs = exr,
                valueEntries = entries
            )
        }

        println("### SCALE PROBE (S1 — 측정 축 확대)")
        println("최대 힙: ${mb(Runtime.getRuntime().maxMemory())}")
        println()
        println("[조립] 세계관 ${universes.size} · 작품 ${novels.size} · 캐릭터 ${characters.size} · " +
            "필드정의 ${defs.size} · 필드값 ${fieldValues.size} · 라이브러리 ${entries.size}")
        println("[S1이 더한 것] 사건 ${events.size} · 사건-캐릭터 ${crossRefs.size} · 사건-작품 ${eventNovelCrossRefs.size} · " +
            "관계 ${relationships.size} · 상태변화 ${stateChanges.size} · 태그 ${tags.size} · " +
            "세력 ${factions.size} · 세력소속 ${factionMemberships.size} · 이름은행 ${nameBank.size}")
        println("[여전히 미조립] relationshipChanges(표본에 `캐릭터 관계 변화` 시트 없음) · " +
            "eventField*(대상=사건 필드 정의가 ${defs.count { it.entityType == FieldDefinition.ENTITY_EVENT }}개라 값도 없음)")
        println("[증폭에서 고정] 세계관 · 작품 · 필드정의 · 값 라이브러리 · 세력 · 이름은행 (구조 축)")
        if (unresolvedCharRefs > 0) println("[주의] 캐릭터 참조 미해결 ${unresolvedCharRefs}건 — 그만큼 관계·상태변화·crossRef가 덜 조립됐다")

        // ── S1-c 채움률 ──
        println()
        println("[채움률] 필드값 ${fieldValues.size} / 가능한 칸 $fillDenominator = ${"%.1f".format(fillRatio * 100)}%")
        println("         빈 칸을 메우면 필드값 ${fieldValues.size} → ${filled.size} (${"%.1f".format(filled.size.toDouble() / fieldValues.size)}배)")
        println("         표본은 아직 채워지는 중이다 — 캐릭터 수만 늘리는 축은 이 배수를 담지 못한다.")

        val p = StatsDataProvider()
        val mults = listOf(1, 3, 10, 30)

        // ── 1. 조립 범위가 수치를 얼마나 바꾸는가 (옛 6목록 vs S1 전량) ──
        println()
        println("[1] 조립 범위 비교 — 옛 범위에서 잰 수는 실제보다 가볍다")
        val minimal30 = snapshotOf(30, full = false)
        val full30 = snapshotOf(30, full = true)
        println("%-26s %10s %10s %8s".format("계산", "옛범위×30", "전량×30", "차이"))
        data class Case(val label: String, val body: (StatsSnapshot) -> Unit)
        val cases = listOf(
            Case("computeSummary") { p.computeSummary(it) },
            Case("computeFieldInsights") { p.computeFieldInsights(it) },
            Case("computeFieldAnalysis") { p.computeFieldAnalysis(it) },
            Case("detectPatterns") { p.detectPatterns(it) },
            Case("computeCrossNovelComparison") { p.computeCrossNovelComparison(it) }
        )
        // 이 표는 차이가 작을 것으로 예상되는 자리라 표본을 늘린다 — 5회로는 ±10%가 흔들려
        // '옛 범위가 더 느리다'는 물리적으로 불가능한 부호가 나온다(1회차에서 실제로 나왔다).
        for (c in cases) {
            val a = timeMs(3, 9) { c.body(minimal30) }
            val b = timeMs(3, 9) { c.body(full30) }
            val d = if (a <= 0) "—" else "%+.0f%%".format((b - a) * 100.0 / a)
            println("%-26s %10s %10s %8s".format(c.label, "${a}ms", "${b}ms", d))
        }
        println("  (부호가 음수면 노이즈다 — 목록을 더하고 계산이 빨라질 수는 없다)")

        // ── 2. 시간 스윕 (전량 조립) ──
        println()
        println("[2] 시간 — 전량 조립 기준")
        println("%-26s %8s %8s %8s %8s   %s".format("계산", "×1", "×3", "×10", "×30", "증가 형태"))
        val snaps = mults.associateWith { snapshotOf(it) }
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
        val insights = p.computeFieldInsights(snaps[1]!!)
        println("인사이트 카드 ${insights.size}장")

        // ── 3. 힙 (S1-b) — 5-3이 "측정된 적이 없다"고 남긴 자리 ──
        // 스냅샷을 하나씩 만들어 재고 즉시 버린다. 넷을 동시에 들고 있으면 서로의 측정을 오염시킨다
        // (그리고 앱의 계산 캐시는 스냅샷 4개까지 보관하므로, 그 경우는 아래 마지막 줄로 환산한다).
        snaps.keys.toList()  // 위 스윕이 잡고 있던 참조를 여기서 놓는다
        println()
        println("[3] 힙 — 스냅샷 적재량과 계산 피크")
        // '계산 중 추가'는 피크에서 **스냅샷을 든 채 잠재운 힙**을 뺀 값이다. 피크 절대값을
        // 스냅샷 크기로 나누면 JVM 기본 점유까지 분자에 들어가 ×1에서 수백 배 같은 무의미한
        // 비율이 나온다(1회차에서 실제로 그랬다). 여기서 알고 싶은 것은 **적재 위에 얼마나 더
        // 쌓이는가**이므로 차이를 적는다.
        println("%-8s %12s %12s %14s".format("배수", "스냅샷", "계산중 추가", "적재+계산"))
        val retained = LinkedHashMap<Int, Long>()
        for (m in mults) {
            val before = quietHeapBytes()
            var snap: StatsSnapshot? = snapshotOf(m)
            val loaded = quietHeapBytes()
            val size = (loaded - before).coerceAtLeast(0)
            retained[m] = size
            val peak = peakHeapBytes { cases.forEach { it.body(snap!!) } }
            val extra = (peak - loaded).coerceAtLeast(0)
            println("%-8s %12s %12s %14s".format("×$m", mb(size), mb(extra), mb(size + extra)))
            snap = null
        }
        val r30 = retained[30] ?: 0L
        println("스냅샷 4개 캐시(현행 정책) 환산 — ×30에서 ${mb(r30 * 4)}")
        println("  적재는 싼데 계산이 비싸면, 줄일 것은 캐시가 아니라 계산의 중간 할당이다.")

        // ── 4. 채움률을 올린 축 (S1-c) ──
        println()
        println("[4] 채움률 100% — 캐릭터 수는 그대로, 빈 칸만 메운 경우")
        println("%-26s %10s %10s %8s".format("계산", "현재×30", "채움×30", "차이"))
        val filled30 = snapshotOf(30, fillUp = true)
        for (c in cases) {
            val a = timeMs(2, 5) { c.body(full30) }
            val b = timeMs(2, 5) { c.body(filled30) }
            val d = if (a <= 0) "—" else "%+.0f%%".format((b - a) * 100.0 / a)
            println("%-26s %10s %10s %8s".format(c.label, "${a}ms", "${b}ms", d))
        }
        run {
            val before = quietHeapBytes()
            @Suppress("UNUSED_VARIABLE") var s: StatsSnapshot? = snapshotOf(30, fillUp = true)
            val after = quietHeapBytes()
            println("채움 ×30 스냅샷 힙 ${mb((after - before).coerceAtLeast(0))} (현재 ×30은 ${mb(r30)})")
            s = null
        }
        println("### SCALE PROBE 끝")
    }
}
