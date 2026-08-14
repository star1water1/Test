// tools/usage-probe/StatsHotspotProbe.kt — S6 2차: 통계 계산 안의 다음 표적을 재서 정한다.
//   (S6 4차가 [7]을 더했다 — B-215 뒤에 남은 상위 둘(computeFieldAnalysis·computeSummary)을
//    부위로 갈라 '진짜 집계'의 몫을 재고, 접힌 집계(고유 원문 × 건수)의 상한을 함께 잰다.)
//
// ── 왜 이 프로브가 따로 있는가 ─────────────────────────────────────────────
// `ScaleProbe.kt`(S1·S6 1차)는 실사용 엑셀 파일을 args[0]로 받는다. 이 세션 환경에는 그
// 파일이 없다 — 그래서 **문서에 실측으로 남은 축 값**(scalability_performance 2장: 캐릭터 214 ·
// 필드정의 90 · 필드값 1,217(21.7%) · 채움 4,496 · 라이브러리 482 · 사건 137 · 관계 135 ·
// 상태변화 113 · 세력 5 · 이름은행 41)에 맞춰 **합성 조립**한다.
// `GraphLayoutScaleProbe`·`SearchScaleProbe`가 세운 그 방식이다.
//
// ⚠️ **여기 나오는 수는 '합성 조립 + 데스크톱 JVM' 실측이다.** 축 규모는 실측 표본을 따르지만
// **값 종수 분포·타입 구성은 모델**이다(아래 [0]이 가정을 전부 인쇄한다). 읽을 것은 절대값이
// 아니라 **비율과 몫**이다 — B-213 마무리 검토가 "모델값을 실측처럼 적은 자리"를 여섯 고쳤다.
// 이 프로브의 출력을 문서에 옮길 때는 반드시 "합성 하네스"임을 함께 적을 것.
//
// ── 무엇을 재는가 ─────────────────────────────────────────────────────────
// S6 1차(3-6)가 남긴 문장: "상위 셋의 나머지 비용은 파싱이 아니라 실제 집계다. 다음 표적은
// 재서 정할 것." 이 프로브는 그 '실제 집계' 안에서 **같은 스냅샷에 대해 겹으로 도는 헬퍼**를
// 가른다. 코드 정황(정적 호출 수):
//   augmentedCharacterValues      — 5곳 (summary·insights·analysis·crossNovel·patterns)
//   filledCharacterDefIds         — 5곳 (complexities·summary·characterStats·dataHealth·상세)
//   computeCharacterComplexities  — 2곳 (summary·crossNovel)
//   computeAllEvent/NovelCalculatedValues — 각 3곳+ (insights·patterns·crossAnalysis·드릴다운)
//   세는 법: grep -c "augmentedCharacterValues(s)" app/src/main/java/…/StatsDataProvider.kt 등
//   (개수를 코드에 박지 않는다 — 위 수가 낡았으면 grep이 정본이다)
// 전부 불변 스냅샷의 순수 함수이므로, 겹으로 도는 만큼이 그대로 걷을 수 있는 비용이다.
// 반대쪽 대조: 수치 계산(calcCache)·resolversOf는 **이미** 스냅샷 단위 캐시가 있다 — 즉
// 이 파일이 스스로 세운 규칙을 이 헬퍼들만 못 따라간 상태인지가 이 측정의 물음이다.
//
// 실행 (out-tests는 run_jvm_tests.sh가 만들어 둔 것):
//   J=$JARS_DIR; CP="...run_jvm_tests.sh의 CP와 동일..."
//   kotlinc -cp "$CP:$J/out-tests" -d $J/out-hotspot tools/usage-probe/StatsHotspotProbe.kt
//   java -Dstdout.encoding=UTF-8 -cp "$J/out-hotspot:$J/out-tests:$CP" StatsHotspotProbeKt

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
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot

// ── 측정 유틸 (ScaleProbe.kt와 같은 규약) ──────────────────────────────────

private fun timeMs(warmup: Int, runs: Int, body: () -> Unit): Long {
    repeat(warmup) { body() }
    val samples = (0 until runs).map {
        val t0 = System.nanoTime(); body(); (System.nanoTime() - t0) / 1_000_000
    }.sorted()
    return samples[samples.size / 2]
}

private val threadMx =
    java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

@Suppress("DEPRECATION")
private fun allocatedBytes(body: () -> Unit): Long {
    val id = Thread.currentThread().id
    val before = threadMx.getThreadAllocatedBytes(id)
    body()
    return threadMx.getThreadAllocatedBytes(id) - before
}

private fun mb(bytes: Long): String = "%.1fMB".format(bytes / 1024.0 / 1024.0)

// ── 결정적 난수 (GraphLayoutScaleProbe와 같은 LCG — 실행마다 같은 데이터) ──
private var seed = 20260813L
private fun rnd(n: Int): Int {
    seed = seed * 6364136223846793005L + 1442695040888963407L
    return ((seed ushr 33) % n).toInt()
}

fun main() {
    // ── [0] 모델 조립 — 축 규모는 실측 표본(scalability 2장), 분포·타입 구성은 모델 ──
    //
    // 세계관 9. 필드 정의 90을 **일부러 고르지 않게** 나눈다(u1=40 · u2=18 · u3=5 · u4~u9=27):
    // 실측 표본의 가능한 칸이 5,617 = 캐릭터당 평균 26.2정의인데, 90/9=10을 고르게 곱하면
    // 그 밀도가 안 나온다 — 캐릭터가 정의 많은 세계관에 몰려 있어야 표본과 같은 모양이다.
    val defsPerUniverse = intArrayOf(40, 18, 5, 4, 5, 4, 5, 4, 5)
    val charsPerUniverse = intArrayOf(110, 60, 25, 3, 4, 3, 3, 3, 3)
    val novelsPerUniverse = intArrayOf(5, 4, 2, 1, 1, 1, 1, 1, 1)

    val universes = (1..9).map { Universe(id = it.toLong(), name = "세계관$it", customRelationshipTypes = "") }
    val novels = mutableListOf<Novel>()
    for (u in 0 until 9) repeat(novelsPerUniverse[u]) {
        novels.add(Novel(id = novels.size + 1L, title = "작품${novels.size + 1}", universeId = (u + 1).toLong()))
    }
    val novelsByUniverse = novels.groupBy { it.universeId!! }

    // 타입 구성(모델): TEXT 위주 + 복수 텍스트·선택·수치 약간 + 수식 2 + 신체 1.
    // 실사용 표본의 타입 분포는 문서에 없다 — 3장 실기기 절차가 수식 필드(S-19)·구조화 입력을
    // 실사용 기능으로 다루므로 '있다' 쪽으로 조립하되 수는 작게 둔다.
    val defs = mutableListOf<FieldDefinition>()
    for (u in 0 until 9) {
        val uid = (u + 1).toLong()
        repeat(defsPerUniverse[u]) { i ->
            val id = defs.size + 1L
            val key = "f_u${u + 1}_$i"
            val (type, config) = when {
                u == 0 && i == 38 -> "CALCULATED" to """{"formula":"field('f_u1_0') * 2"}"""
                u == 0 && i == 39 -> "CALCULATED" to """{"formula":"field('f_u1_0') + field('f_u1_1')"}"""
                u == 0 && i == 37 -> "BODY_SIZE" to "{}"
                i % 8 == 6 -> "NUMBER" to "{}"
                i % 8 == 5 -> "MULTI_TEXT" to "{}"
                i % 8 == 4 -> "SELECT" to "{}"
                u == 0 && i == 36 -> "GRADE" to "{}"
                else -> "TEXT" to "{}"
            }
            defs.add(FieldDefinition(
                id = id, universeId = uid, key = key, name = "필드$key",
                type = type, config = config, groupName = "그룹${i % 4}",
                displayOrder = i, isRequired = false,
                entityType = FieldDefinition.ENTITY_CHARACTER
            ))
        }
    }
    // NUMBER 키 둘을 수식이 참조할 수 있게 u1의 0·1번을 NUMBER로 고정
    val fixedNumberIdx = listOf(0, 1)
    for (i in fixedNumberIdx) {
        defs[i] = defs[i].copy(type = "NUMBER", config = "{}")
    }
    val defsByUniverse = defs.groupBy { it.universeId }

    // 캐릭터 — 세계관별 작품에 순환 배정
    val characters = mutableListOf<Character>()
    val charUniverse = mutableMapOf<Long, Long>()
    for (u in 0 until 9) {
        val uid = (u + 1).toLong()
        val uNovels = novelsByUniverse[uid].orEmpty()
        repeat(charsPerUniverse[u]) { i ->
            val id = characters.size + 1L
            characters.add(Character(
                id = id, name = "인물$id", novelId = uNovels[i % uNovels.size].id,
                anotherName = if (i % 7 == 0) "이명$id, 별명$id" else "", memo = ""
            ))
            charUniverse[id] = uid
        }
    }

    // 값 풀 — 정의마다 종수 3~24(모델). NUMBER는 1~100 수치 문자열, BODY_SIZE는 "a-b-c",
    // MULTI_TEXT 값은 풀에서 1~3토큰을 ", "로 결합(콤마 분리 경로가 실제로 돌게).
    val poolByDef = HashMap<Long, List<String>>()
    for (fd in defs) {
        if (fd.type == "CALCULATED") continue
        val kinds = 3 + rnd(22)
        poolByDef[fd.id] = when (fd.type) {
            "NUMBER" -> (1..kinds).map { (1 + rnd(100)).toString() }
            "BODY_SIZE" -> (1..kinds).map { "${80 + rnd(20)}-${55 + rnd(15)}-${82 + rnd(18)}" }
            else -> (1..kinds).map { "값${fd.id}_$it" }
        }
    }
    fun sampleValue(fd: FieldDefinition): String {
        val pool = poolByDef[fd.id] ?: return ""
        return if (fd.type == "MULTI_TEXT") {
            val n = 1 + rnd(3)
            (0 until n).map { pool[rnd(pool.size)] }.distinct().joinToString(", ")
        } else pool[rnd(pool.size)]
    }

    // 희소 채움 — 목표 21.7%(실측 채움률). 칸을 훑으며 결정적으로 217/1000 확률로 채운다.
    val sparseValues = mutableListOf<CharacterFieldValue>()
    var possibleCells = 0L
    for (c in characters) {
        val here = defsByUniverse[charUniverse[c.id]].orEmpty()
        for (fd in here) {
            possibleCells++
            if (fd.type == "CALCULATED") continue
            if (rnd(1000) < 217) {
                sparseValues.add(CharacterFieldValue(
                    id = sparseValues.size + 1L, characterId = c.id,
                    fieldDefinitionId = fd.id, value = sampleValue(fd)
                ))
            }
        }
    }
    // 채움 축 — 풀이 있는 모든 칸을 채운다(ScaleProbe.filledValues와 같은 규칙)
    val have = sparseValues.mapTo(HashSet()) { it.characterId to it.fieldDefinitionId }
    val filledValues = ArrayList<CharacterFieldValue>(sparseValues)
    for (c in characters) {
        val here = defsByUniverse[charUniverse[c.id]].orEmpty()
        for (fd in here) {
            if (fd.type == "CALCULATED") continue
            if ((c.id to fd.id) in have) continue
            filledValues.add(CharacterFieldValue(
                id = filledValues.size + 1L, characterId = c.id,
                fieldDefinitionId = fd.id, value = sampleValue(fd)
            ))
        }
    }

    // 값 라이브러리 482엔트리 — u1·u2의 TEXT/SELECT 정의에 순환 배정, 값은 풀에서
    val entries = mutableListOf<FieldValueEntry>()
    run {
        val libDefs = defs.filter { (it.universeId ?: 0L) <= 2L && (it.type == "TEXT" || it.type == "SELECT") }
        var i = 0
        while (entries.size < 482 && libDefs.isNotEmpty()) {
            val fd = libDefs[i % libDefs.size]
            val pool = poolByDef[fd.id].orEmpty()
            if (pool.isNotEmpty()) {
                val v = pool[entries.size % pool.size]
                entries.add(FieldValueEntry(
                    id = entries.size + 1L, fieldDefinitionId = fd.id, value = v,
                    displayLabel = if (entries.size % 5 == 0) "$v 표시" else "",
                    aliasesJson = "[]", category = if (entries.size % 3 == 0) "분류${entries.size % 7}" else "",
                    isHidden = false, source = FieldValueEntry.SOURCE_AUTO,
                    usageCount = rnd(9)
                ))
            }
            i++
        }
    }

    // 사건 137 · 관계 135 · 상태변화 113 · 태그 9 · 세력 5(+소속 60) · 이름은행 41 — 전부 실측 축 규모
    val events = (1..137).map { i ->
        val uid = if (i <= 100) 1L else if (i <= 130) 2L else 3L
        TimelineEvent(
            id = i.toLong(), year = 900 + rnd(300), month = null, day = null,
            calendarType = "천개력", description = "사건$i", eventType = "",
            universeId = uid, displayOrder = i, isTemporary = false, code = null
        )
    }
    val crossRefs = mutableListOf<TimelineCharacterCrossRef>()
    for (e in events) {
        val pool = characters.filter { charUniverse[it.id] == e.universeId }
        repeat(1 + rnd(3)) {
            if (pool.isNotEmpty()) crossRefs.add(TimelineCharacterCrossRef(e.id, pool[rnd(pool.size)].id))
        }
    }
    val relTypes = listOf("가족", "연인", "친구", "적", "동료")
    val relationships = (1..135).map { i ->
        val relUid = if (i <= 100) 1L else 2L
        val pool = characters.filter { charUniverse[it.id] == relUid }
        val a = pool[rnd(pool.size)].id
        var b = pool[rnd(pool.size)].id
        if (b == a) b = pool[(pool.indexOfFirst { it.id == a } + 1) % pool.size].id
        CharacterRelationship(
            id = i.toLong(), characterId1 = a, characterId2 = b,
            relationshipType = relTypes[rnd(relTypes.size)], description = if (i % 3 == 0) "설명$i" else "",
            intensity = 1 + rnd(10), isBidirectional = true, displayOrder = i, code = null
        )
    }
    val stateChanges = (1..113).map { i ->
        val c = characters[rnd(120)]
        val here = defsByUniverse[charUniverse[c.id]].orEmpty()
        CharacterStateChange(
            id = i.toLong(), characterId = c.id, year = 900 + rnd(300), month = null, day = null,
            fieldKey = here[rnd(here.size)].key, newValue = "변화$i", description = "", code = null
        )
    }
    val tags = (1..9).map { CharacterTag(id = it.toLong(), characterId = characters[rnd(60)].id, tag = "태그${it % 4}") }
    val factions = (1..5).map { i ->
        Faction(id = i.toLong(), universeId = if (i <= 3) 1L else 2L, name = "세력$i", description = "",
            color = "#2196F3", autoRelationType = "", autoRelationIntensity = 5, displayOrder = i)
    }
    val factionMemberships = (1..60).map { i ->
        val f = factions[rnd(factions.size)]
        val pool = characters.filter { charUniverse[it.id] == f.universeId }
        FactionMembership(id = i.toLong(), factionId = f.id, characterId = pool[rnd(pool.size)].id,
            joinYear = null, leaveYear = null, leaveType = null)
    }
    val nameBank = (1..41).map { i ->
        NameBankEntry(id = i.toLong(), name = "은행이름$i", gender = if (i % 2 == 0) "남" else "여",
            origin = "", notes = "", isUsed = i % 3 == 0,
            usedByCharacterId = if (i % 3 == 0) characters[rnd(characters.size)].id else null)
    }

    /** ×[mult] 증폭 — ScaleProbe와 같은 규칙: 캐릭터에 매달린 것은 복제, 구조는 유지.
     *  값 문자열은 새 인스턴스(참조 공유로 힙이 가볍게 재지는 것 방지 — ScaleProbe 주석 그대로). */
    fun snapshotOf(mult: Int, src: List<CharacterFieldValue>): StatsSnapshot {
        val chars = ArrayList<Character>(characters.size * mult)
        val vals = ArrayList<CharacterFieldValue>(src.size * mult)
        val tg = ArrayList<CharacterTag>(tags.size * mult)
        val rel = ArrayList<CharacterRelationship>(relationships.size * mult)
        val stc = ArrayList<CharacterStateChange>(stateChanges.size * mult)
        val evs = ArrayList<TimelineEvent>(events.size * mult)
        val xr = ArrayList<TimelineCharacterCrossRef>(crossRefs.size * mult)
        val fm = ArrayList<FactionMembership>(factionMemberships.size * mult)
        val cBase = characters.size.toLong()
        val eBase = events.size.toLong()
        for (k in 0 until mult) {
            val cOff = cBase * k
            val eOff = eBase * k
            characters.forEach { chars.add(it.copy(id = it.id + cOff, name = it.name + "#" + k)) }
            src.forEach {
                vals.add(it.copy(
                    id = it.id + src.size.toLong() * k,
                    characterId = it.characterId + cOff,
                    value = if (k == 0) it.value else String(it.value.toCharArray())
                ))
            }
            tags.forEach { tg.add(it.copy(id = it.id + tags.size.toLong() * k, characterId = it.characterId + cOff)) }
            relationships.forEach {
                rel.add(it.copy(id = it.id + relationships.size.toLong() * k,
                    characterId1 = it.characterId1 + cOff, characterId2 = it.characterId2 + cOff))
            }
            stateChanges.forEach { stc.add(it.copy(id = it.id + stateChanges.size.toLong() * k, characterId = it.characterId + cOff)) }
            events.forEach { evs.add(it.copy(id = it.id + eOff)) }
            crossRefs.forEach { xr.add(TimelineCharacterCrossRef(it.eventId + eOff, it.characterId + cOff)) }
            factionMemberships.forEach { fm.add(it.copy(id = it.id + factionMemberships.size.toLong() * k, characterId = it.characterId + cOff)) }
        }
        return StatsSnapshot(
            characters = chars, novels = novels, universes = universes,
            events = evs, relationships = rel, relationshipChanges = emptyList(),
            tags = tg, nameBank = nameBank, stateChanges = stc,
            fieldDefinitions = defs, fieldValues = vals, crossRefs = xr,
            factions = factions, factionMemberships = fm,
            valueEntries = entries
        )
    }

    println("### STATS HOTSPOT PROBE (S6 2차 — 합성 조립)")
    println("최대 힙: ${mb(Runtime.getRuntime().maxMemory())}")
    println()
    println("[0] 조립 대조 — 목표(실측 표본, scalability 2장) vs 이 하네스")
    println("    캐릭터 214/${characters.size} · 필드정의 90/${defs.size} · 가능한 칸 5,617/$possibleCells")
    println("    희소 필드값 1,217/${sparseValues.size} (${"%.1f".format(sparseValues.size * 100.0 / possibleCells)}%) · " +
        "채움 4,496/${filledValues.size}")
    println("    라이브러리 482/${entries.size} · 사건 137/${events.size} · 관계 135/${relationships.size} · " +
        "상태변화 113/${stateChanges.size} · 세력 5/${factions.size} · 이름은행 41/${nameBank.size}")
    println("    ⚠️ 값 종수 분포(정의당 3~24종)·타입 구성(TEXT 위주 + MULTI_TEXT/SELECT/NUMBER + 수식 2)은 모델이다.")
    println("    사건·작품 커스텀 필드 0 — 실측 표본 그대로(대상=사건 정의 0). 그 축의 헬퍼는 아래 [2]가 따로 말한다.")

    val p = StatsDataProvider()
    val fill30 = snapshotOf(30, filledValues)
    val sparse30 = snapshotOf(30, sparseValues)

    data class Calc(val label: String, val body: (StatsSnapshot) -> Unit)
    val all = listOf(
        Calc("computeSummary") { p.computeSummary(it) },
        Calc("computeFieldInsights") { p.computeFieldInsights(it) },
        Calc("computeCharacterStats") { p.computeCharacterStats(it) },
        Calc("computeEventStats") { p.computeEventStats(it) },
        Calc("computeRelationshipStats") { p.computeRelationshipStats(it) },
        Calc("computeNameBankStats") { p.computeNameBankStats(it) },
        Calc("computeDataHealth") { p.computeDataHealth(it) },
        Calc("computeFieldAnalysis") { p.computeFieldAnalysis(it) },
        Calc("detectPatterns") { p.detectPatterns(it) },
        Calc("computeFactionStats") { p.computeFactionStats(it) }
    )

    // ── [1] 10계산 시간·할당 — 화면이 실제로 돌리는 열 (채움 ×30) ──
    println()
    println("[1] 10계산 시간·할당 (채움 ×30 — 값 ${fill30.fieldValues.size}개)")
    println("%-28s %9s %11s".format("계산", "시간", "할당"))
    val rows = all.map { c ->
        repeat(2) { c.body(fill30) }
        val t = timeMs(0, 5) { c.body(fill30) }
        val a = allocatedBytes { c.body(fill30) }
        Triple(c.label, t, a)
    }
    val totalT = rows.sumOf { it.second }
    val totalA = rows.sumOf { it.third }
    for ((label, t, a) in rows.sortedByDescending { it.third }) {
        println("%-28s %9s %11s".format(label, "${t}ms", mb(a)))
    }
    println("%-28s %9s %11s".format("합계(CPU 총량)", "${totalT}ms", mb(totalA)))
    println("가장 느린 하나 = ${rows.maxByOrNull { it.second }?.let { "${it.first} ${it.second}ms" }} (동시 실행 대기의 하한)")

    // 현재 채움(21.7%) ×30 — 대조용 한 줄
    val sparseTotal = run {
        all.forEach { it.body(sparse30) }
        all.sumOf { c -> timeMs(0, 3) { c.body(sparse30) } }
    }
    println("대조: 현재 채움(21.7%) ×30의 10계산 합계 ${sparseTotal}ms")

    // ── [2] 겹으로 도는 헬퍼 — 스냅샷 순수 함수의 단독 비용 (리플렉션) ──
    println()
    println("[2] 헬퍼 단독 비용 (채움 ×30 · 리플렉션 직접 호출)")
    println("    cold = 스냅샷 첫 호출(내부 수식 캐시 미적재) · warm = 두 번째부터(지금 코드가 2~5번째 호출에서 실제로 내는 비용)")
    val cls = p.javaClass
    fun helper(name: String): java.lang.reflect.Method =
        cls.getDeclaredMethod(name, StatsSnapshot::class.java).apply { isAccessible = true }

    val helpers = listOf("augmentedCharacterValues", "filledCharacterDefIds", "computeCharacterComplexities")
    // cold를 정확히 재려고 새 스냅샷(내용 동일)을 만든다 — calcCache가 IdentityKey라 새 조립이 cold다.
    for (h in helpers) {
        val m = helper(h)
        val freshSnap = snapshotOf(30, filledValues)
        val t0 = System.nanoTime(); m.invoke(p, freshSnap); val cold = (System.nanoTime() - t0) / 1_000_000
        val warmT = timeMs(1, 5) { m.invoke(p, freshSnap) }
        val warmA = allocatedBytes { m.invoke(p, freshSnap) }
        println("%-30s cold %5dms · warm %5dms · warm 할당 %10s".format(h, cold, warmT, mb(warmA)))
    }

    // ── [3] 겹의 산술 — "지금 코드가 한 번의 화면 적재에서 헛도는 몫" ──
    // 호출 수는 코드 정황(위 헤더의 grep)이고, 여기 곱은 산술이지 실측이 아니다 —
    // 실측은 캐시를 넣은 뒤 [1]을 다시 돌려 before/after로 잰다.
    println()
    println("[3] 겹의 산술 (warm 비용 × (정적 호출 수 − 1))")
    val counts = mapOf(
        "augmentedCharacterValues" to 5,
        "filledCharacterDefIds" to 5,
        "computeCharacterComplexities" to 2
    )
    var redundantT = 0L; var redundantA = 0L
    for ((h, n) in counts) {
        val m = helper(h)
        val t = timeMs(1, 5) { m.invoke(p, fill30) }
        val a = allocatedBytes { m.invoke(p, fill30) }
        redundantT += t * (n - 1); redundantA += a * (n - 1)
        println("  $h: ${t}ms × ${n - 1} = ${t * (n - 1)}ms · ${mb(a * (n - 1))}")
    }
    println("  합계 ${redundantT}ms · ${mb(redundantA)} — [1] 합계 대비 시간 ${"%.0f".format(redundantT * 100.0 / totalT)}% · " +
        "할당 ${"%.0f".format(redundantA * 100.0 / totalA)}%")

    // ── [4] 동시 실행 근사 — StatsViewModel은 이 열을 async로 동시에 돌린다 ──
    // 스레드 4개(모델 가정: 폰 big 코어 수) 고정 풀에서 10계산을 던지고 wall-clock을 잰다.
    // 데스크톱 JVM 근사다 — 절대값이 아니라 before/after 비율을 읽을 것.
    println()
    println("[4] 동시 실행 wall-clock (고정 풀 4스레드 — 모델 가정)")
    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    fun concurrentOnce(snap: StatsSnapshot): Long {
        val t0 = System.nanoTime()
        val futures = all.map { c -> pool.submit { c.body(snap) } }
        futures.forEach { it.get() }
        return (System.nanoTime() - t0) / 1_000_000
    }
    repeat(2) { concurrentOnce(fill30) }
    val wall = (0 until 5).map { concurrentOnce(fill30) }.sorted()[2]
    println("  10계산 wall-clock 중앙값: ${wall}ms")
    pool.shutdown()

    // ── [5] perSnapshot 캐시의 보관 힙 — 7장 3단계("스냅샷을 키우는가")의 실측 ──
    // 캐시가 없던 때는 겹 비용이 **일시 할당**(적재마다 만들고 버림)이었고, 캐시는 그중 한 벌을
    // 스냅샷 수명만큼 **보관**으로 바꾼다. 걷는 CPU·할당의 대가로 무는 것이 이 수다.
    //
    // ⚠️ 측정법 주의 — 활성성 함정: 지역 참조는 스코프 안이어도 **마지막 사용 뒤에는 수거될 수
    // 있다**(이 측정의 1차 시도에서 스냅샷이 측정 전에 사라져 힙이 역전됐다). 그래서 절대값
    // 빼기(h1−h2)가 아니라 **생존을 마지막 출력으로 보장한 차이 측정**(h0: 스냅샷 생존 →
    // 웜업 → h1: 스냅샷+캐시 생존)으로 잰다. 계산 결과는 버리므로 델타에 들지 않는다.
    println()
    println("[5] perSnapshot 캐시 보관 힙 (채움 ×30 · 스냅샷 1개)")
    fun quietHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(5) { System.gc(); Thread.sleep(80) }
        return rt.totalMemory() - rt.freeMemory()
    }
    val freshSnap = snapshotOf(30, filledValues)
    val freshProvider = StatsDataProvider()
    val h0 = quietHeap()
    val allFresh = listOf(
        { freshProvider.computeSummary(freshSnap) }, { freshProvider.computeFieldInsights(freshSnap) },
        { freshProvider.computeCharacterStats(freshSnap) }, { freshProvider.computeEventStats(freshSnap) },
        { freshProvider.computeRelationshipStats(freshSnap) }, { freshProvider.computeNameBankStats(freshSnap) },
        { freshProvider.computeDataHealth(freshSnap) }, { freshProvider.computeFieldAnalysis(freshSnap) },
        { freshProvider.detectPatterns(freshSnap) }, { freshProvider.computeFactionStats(freshSnap) }
    )
    allFresh.forEach { it() }
    val h1 = quietHeap()
    println("  스냅샷 생존: ${mb(h0)} → 스냅샷+캐시 찬 프로바이더: ${mb(h1)}")
    println("  → 보관분(스냅샷 1개): ${mb((h1 - h0).coerceAtLeast(0))} · 캐시 상한 4스냅샷이면 그 ×4가 최악")
    println("  keep: 값 ${freshSnap.fieldValues.size} · 캐시 주인 ${freshProvider.javaClass.simpleName}")

    // ── [6] B-215 — getFieldValues 통과(토큰화+라벨/카테고리+구간)의 공유 가능 몫 ──
    // 다섯 소비처(요약 TOP5·인사이트·레거시·교차·패턴)가 같은 값 표를 각자 이 함수로 지난다.
    // 여기서 재는 것 셋: ⓐ 값 표의 모양(고유 (def, 원문) 쌍 수 — 메모 엔트리 상한과 중복 접힘)
    // ⓑ 통과 1회의 단독 비용(1회차 vs 2회차 — 기준선에서는 같고, 메모가 서면 2회차가 조회만 남는다)
    // ⓒ 메모 보관 힙(변경 후에만 — [5]의 생존 프로토콜, 비정본 config 우회로 다른 캐시를 먼저 채워 가른다)
    println()
    println("[6] B-215 — getFieldValues 통과의 공유 가능 몫 (채움 ×30)")
    val gfv = cls.getDeclaredMethod(
        "getFieldValues", StatsSnapshot::class.java,
        FieldDefinition::class.java, String::class.java,
        com.novelcharacter.app.data.model.FieldStatsConfig::class.java
    ).apply { isAccessible = true }
    val augM = helper("augmentedCharacterValues")

    // 소비처가 쓰는 그 config — 변경 후에는 스냅샷 정본(statsConfigsOf), 기준선에서는 fromConfig 조립
    @Suppress("UNCHECKED_CAST")
    fun configsFor(snap: StatsSnapshot, provider: StatsDataProvider): Map<Long, com.novelcharacter.app.data.model.FieldStatsConfig> =
        runCatching {
            val m = cls.getDeclaredMethod("statsConfigsOf", StatsSnapshot::class.java).apply { isAccessible = true }
            m.invoke(provider, snap) as Map<Long, com.novelcharacter.app.data.model.FieldStatsConfig>
        }.getOrElse {
            snap.fieldDefinitions.associate { fd ->
                fd.id to com.novelcharacter.app.data.model.FieldStatsConfig.fromConfig(fd.config)
            }
        }

    @Suppress("UNCHECKED_CAST")
    fun passOnce(snap: StatsSnapshot, provider: StatsDataProvider,
                 cfgs: Map<Long, com.novelcharacter.app.data.model.FieldStatsConfig>): Long {
        val defById = snap.fieldDefinitions.associateBy { it.id }
        var tokens = 0L
        val aug = augM.invoke(provider, snap) as Map<Long, List<CharacterFieldValue>>
        for ((defId, values) in aug) {
            val fd = defById[defId] ?: continue
            val cfg = cfgs[defId] ?: continue
            if (!cfg.enabled) continue
            for (fv in values) {
                if (fv.value.isBlank()) continue
                tokens += (gfv.invoke(provider, snap, fd, fv.value, cfg) as List<String>).size
            }
        }
        return tokens
    }

    // ⓐ 값 표의 모양
    run {
        @Suppress("UNCHECKED_CAST")
        val aug = augM.invoke(p, fill30) as Map<Long, List<CharacterFieldValue>>
        var rows = 0L
        val distinct = HashSet<Pair<Long, String>>()
        for ((defId, values) in aug) for (fv in values) {
            if (fv.value.isBlank()) continue
            rows++; distinct.add(defId to fv.value)
        }
        println("  ⓐ 값 표: 행 ${rows} · 고유 (def, 원문) 쌍 ${distinct.size} " +
            "(중복 접힘 ${"%.1f".format(rows.toDouble() / distinct.size)}배 — 메모 엔트리 상한)")
    }

    // ⓑ 통과 단독 비용 — 새 프로바이더(콜드)에서 1~4회차를 각각 잰다
    run {
        val freshS = snapshotOf(30, filledValues)
        val freshP = StatsDataProvider()
        val cfgs = configsFor(freshS, freshP)
        for (i in 1..4) {
            var tokens = 0L
            val t0 = System.nanoTime()
            val alloc = allocatedBytes { tokens = passOnce(freshS, freshP, cfgs) }
            val t = (System.nanoTime() - t0) / 1_000_000
            println("  ⓑ 통과 ${i}회차: ${t}ms · ${mb(alloc)} (토큰 $tokens)")
        }
    }

    // ⓒ 메모 보관 힙 — 변경 후에만. 비정본 config 사본(메모 우회)으로 다른 캐시를 먼저 채워
    //    h1을 뜨고, 정본 통과(메모 적재) 뒤 h2 — 델타가 메모의 몫이다. [5]의 생존 프로토콜.
    runCatching {
        val f = cls.getDeclaredField("statsParseCaches").apply { isAccessible = true }
        val freshS = snapshotOf(30, filledValues)
        val freshP = StatsDataProvider()
        val cfgs = configsFor(freshS, freshP)
        val bypass = cfgs.mapValues { (_, c) -> c.copy() }   // 값 동일·정체 다름 → 메모 우회
        passOnce(freshS, freshP, bypass)
        val h1 = quietHeap()
        passOnce(freshS, freshP, cfgs)
        val h2 = quietHeap()
        val store = f.get(freshP) as java.util.concurrent.ConcurrentHashMap<*, *>
        val entryCount = store.values.sumOf { bundle ->
            val keysByDef = bundle.javaClass.getDeclaredField("keysByDef")
                .apply { isAccessible = true }.get(bundle) as java.util.concurrent.ConcurrentHashMap<*, *>
            keysByDef.values.sumOf { (it as java.util.concurrent.ConcurrentHashMap<*, *>).size }
        }
        println("  ⓒ 메모 보관: ${mb((h2 - h1).coerceAtLeast(0))} (엔트리 ${entryCount} · " +
            "스냅샷 생존 확인 ${freshS.fieldValues.size}행 · 캐시 상한 4스냅샷이면 그 ×4가 최악)")
    }.onFailure { println("  ⓒ 메모 보관: 없음(기준선 — statsParseCaches 미존재)") }

    // ── [7] S6 4차 — '진짜 집계'의 부위별 분해 (computeFieldAnalysis · computeSummary) ──
    // [1]의 상위 둘을 부위로 가른다. 재현 형태는 **접기 도입 전 본문**의 모양이다(S6 4차 뒤의
    // 본문은 접힘이므로, 이 재현은 걷어낸 모양을 하네스가 드는 쪽이다 — 동작 대조는
    // StatsFoldParityTest가 한다). 토큰은 미리 뽑아 두어
    // (tokensByDef — 메모 적중 상태) 리플렉션이 측정 루프 밖에 있게 했다 — 여기서 재는 것은
    // 파싱이 아니라 **집계 그 자체**(건별 재료화 · 해싱 · 정렬 · def×캐릭터 필터)다.
    // 말미의 '접힘/' 셋은 처방 후보의 산술이다: 같은 답을 (고유 원문 × 건수)로 세면 얼마인가.
    println()
    println("[7] S6 4차 — 집계 부위별 분해 (채움 ×30 · 파싱 메모 적중 상태)")
    run {
        val snap = fill30
        val cfgs = configsFor(snap, p)
        val defById = snap.fieldDefinitions.associateBy { it.id }
        @Suppress("UNCHECKED_CAST")
        val aug = augM.invoke(p, snap) as Map<Long, List<CharacterFieldValue>>
        // 토큰 표 선조립 — 리플렉션은 여기까지. 측정 루프는 맵 조회만 한다.
        val tokensByDef = HashMap<Long, HashMap<String, List<String>>>()
        for ((defId, values) in aug) {
            val fd = defById[defId] ?: continue
            val cfg = cfgs[defId] ?: continue
            if (!cfg.enabled) continue
            val m = tokensByDef.getOrPut(defId) { HashMap() }
            for (fv in values) {
                if (fv.value.isBlank() || fv.value in m) continue
                @Suppress("UNCHECKED_CAST")
                m[fv.value] = gfv.invoke(p, snap, fd, fv.value, cfg) as List<String>
            }
        }
        fun measure(label: String, body: () -> Any?) {
            val t = timeMs(1, 5) { body() }
            val a = allocatedBytes { body() }
            println("  %-44s %5dms %10s".format(label, t, mb(a)))
        }
        val discreteTypes = setOf("TEXT", "SELECT", "MULTI_TEXT", "GRADE")
        val binnableTypes = setOf("NUMBER", "CALCULATED")

        // computeSummary — 접기 도입 전 TOP5 파이프라인의 모양
        measure("summary/TOP5 (건별 flatMap+계수+정렬)") {
            aug.entries.flatMap { (defId, values) ->
                val fd = defById[defId] ?: return@flatMap emptyList<Pair<String, String>>()
                val cfg = cfgs[defId] ?: return@flatMap emptyList<Pair<String, String>>()
                if (!cfg.enabled) return@flatMap emptyList<Pair<String, String>>()
                values.filter { it.value.isNotBlank() }
                    .flatMap { fv -> tokensByDef[defId]!![fv.value]!!.map { Pair(fd.name, it) } }
            }.groupingBy { it }.eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                    .thenBy { it.key.first }.thenBy { it.key.second })
                .take(5)
        }
        // computeSummary — 완성도 평균 (percentOf × 캐릭터. filled는 메모 적중)
        val filledM = helper("filledCharacterDefIds")
        @Suppress("UNCHECKED_CAST")
        val filled = filledM.invoke(p, snap) as Map<Long, Set<Long>>
        val novelMap = snap.novels.associateBy { it.id }
        val defsByUni = snap.fieldDefinitions.groupBy { it.universeId }
        measure("summary/완성도 평균 (percentOf×캐릭터)") {
            snap.characters.mapNotNull { ch ->
                val novel = ch.novelId?.let { novelMap[it] } ?: return@mapNotNull null
                com.novelcharacter.app.util.CompletionRate.percentOf(
                    defsByUni[novel.universeId].orEmpty(), filled[ch.id].orEmpty(), snap.completionWeights)
            }.average()
        }

        // computeFieldAnalysis — 접기 도입 전 부위 다섯의 모양
        measure("fa/이산 분포 (건별 재료화+계수+정렬)") {
            for (fd in snap.fieldDefinitions) {
                if (fd.type !in discreteTypes) continue
                val cfg = cfgs[fd.id] ?: continue
                if (!cfg.enabled) continue
                val values = aug[fd.id] ?: continue
                val toks = tokensByDef[fd.id] ?: continue
                com.novelcharacter.app.util.ValueDistributions.of(
                    values.filter { it.value.isNotBlank() }.flatMap { toks[it.value]!! })
            }
        }
        measure("fa/수치 자동구간 (재료화+파싱+구간)") {
            for (fd in snap.fieldDefinitions) {
                if (fd.type !in binnableTypes) continue
                val cfg = cfgs[fd.id] ?: continue
                if (!cfg.enabled) continue
                val values = aug[fd.id] ?: continue
                val raw = values.filter { it.value.isNotBlank() }.map { it.value }
                val nums = com.novelcharacter.app.util.NumericBinning.numericValuesOf(raw, "", 0)
                com.novelcharacter.app.util.NumericBinning.autoDistribution(nums)
            }
        }
        measure("fa/BODY 파트 분포 (파싱+bin별 count)") {
            for (fd in snap.fieldDefinitions) {
                if (fd.type != "BODY_SIZE") continue
                val values = aug[fd.id] ?: continue
                for (part in 0 until 3) {
                    val nums = values.mapNotNull {
                        com.novelcharacter.app.util.NumericBinning.partValue(it.value, "-", part) }
                    val bins = com.novelcharacter.app.util.NumericBinning.autoBins(nums)
                    for (b in bins) nums.count { b.contains(it) }
                }
            }
        }
        measure("fa/수치 요약 재파싱 (numericValuesOf 두 벌째)") {
            for (fd in snap.fieldDefinitions) {
                if (fd.type !in binnableTypes) continue
                val cfg = cfgs[fd.id] ?: continue
                if (!cfg.enabled) continue
                val values = aug[fd.id] ?: continue
                com.novelcharacter.app.util.NumericBinning.numericValuesOf(values.map { it.value }, "", 0)
            }
        }
        measure("fa/필드별 완성도 (def×캐릭터 필터)") {
            snap.fieldDefinitions.filter { it.type != "CALCULATED" }.map { fd ->
                val uniNovels = snap.novels.filter { it.universeId == fd.universeId }.map { it.id }.toSet()
                val rel = snap.characters.filter { it.novelId in uniNovels }
                val f = aug[fd.id]?.count { it.value.isNotBlank() } ?: 0
                Triple(fd.name, f, rel.size)
            }.sortedBy { it.third }
        }

        // ── 접힌 집계 프로토타입 — 값 표가 148.5배로 접히므로([6ⓐ]) 집계도 고유 쌍 위에서 돌 수 있다 ──
        val countsByDef = HashMap<Long, HashMap<String, Int>>()
        measure("접힘/건수 표 조립 (원문→건수, 전체 def)") {
            countsByDef.clear()
            for ((defId, values) in aug) {
                val m = countsByDef.getOrPut(defId) { HashMap() }
                for (fv in values) {
                    if (fv.value.isBlank()) continue
                    m.merge(fv.value, 1, Int::plus)
                }
            }
        }
        measure("접힘/TOP5 (고유쌍×건수)") {
            val counts = HashMap<Pair<String, String>, Int>()
            for ((defId, byRaw) in countsByDef) {
                val fd = defById[defId] ?: continue
                val cfg = cfgs[defId] ?: continue
                if (!cfg.enabled) continue
                val toks = tokensByDef[defId] ?: continue
                for ((raw, n) in byRaw) for (t in toks[raw]!!) counts.merge(Pair(fd.name, t), n, Int::plus)
            }
            counts.entries
                .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
                    .thenBy { it.key.first }.thenBy { it.key.second })
                .take(5)
        }
        measure("접힘/이산 분포 (고유쌍×건수)") {
            for (fd in snap.fieldDefinitions) {
                if (fd.type !in discreteTypes) continue
                val cfg = cfgs[fd.id] ?: continue
                if (!cfg.enabled) continue
                val byRaw = countsByDef[fd.id] ?: continue
                val toks = tokensByDef[fd.id] ?: continue
                val counts = HashMap<String, Int>()
                for ((raw, n) in byRaw) for (t in toks[raw]!!) counts.merge(t, n, Int::plus)
                com.novelcharacter.app.util.ValueDistributions.sorted(counts)
            }
        }
    }

    println()
    println("### STATS HOTSPOT PROBE 끝")
}
