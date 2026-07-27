// tools/usage-probe/RecountProbe.kt — P1(U-0) 재집계 비용 실측 하네스.
//
// Probe.kt / ScaleProbe.kt 와 같은 규약: 러너 목록에 넣지 않는다, 출력은 집계만.
// ScaleProbe와 달리 **실파일이 필요 없다** — 재집계 비용은 값의 내용이 아니라 규모
// (캐릭터 수 × 필드 수 × 필드당 값 종수)로 결정되므로 합성 데이터로 곡선을 본다.
//
// **무엇을 재는가 / 재지 않는가:**
//  - 잰다: 재집계의 CPU 몫 — 토큰화 + 별칭 정규화(FieldValueResolver) + 집계 + diff.
//    이것이 `FieldValueRules.recountedEntries`이고, 필드 수만큼 반복된다.
//  - 재지 않는다: SQLite 행 조회(getValuesByFieldDefs)의 I/O 몫. Room 런타임이 필요해
//    이 순수 JVM 하네스로는 잴 수 없다. 그래서 P1은 **실측 결과와 무관하게 안전한 쪽**
//    (수확 훅에서는 예약 → 백그라운드, 읽기 경로에서는 await)으로 구현했다.
//
// 실행:
//   java -Dstdout.encoding=UTF-8 -cp "$J/out-probe:$J/out-tests:$CP" RecountProbeKt

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.repository.FieldValueRules

/** 실사용 표본의 형상: 세계관 1개에 캐릭터 필드 36개, 필드당 값 종수 중앙값 ~13. */
private const val FIELDS = 36
private const val DISTINCT_VALUES = 13
private const val BASE_CHARACTERS = 214

private fun fields(): List<FieldDefinition> = (1..FIELDS).map { i ->
    FieldDefinition(id = i.toLong(), universeId = 1L, key = "f$i", name = "필드$i", type = "TEXT")
}

private fun entriesFor(fieldId: Long): List<FieldValueEntry> = (0 until DISTINCT_VALUES).map { v ->
    FieldValueEntry(
        id = fieldId * 100 + v,
        fieldDefinitionId = fieldId,
        value = "값$v",
        // 절반은 별칭을 달아 정규화 경로도 타게 한다(별칭 없는 데이터는 낙관적으로 빠르다)
        aliasesJson = if (v % 2 == 0) """["값${v}_변형"]""" else "[]",
        usageCount = 0
    )
}

/** 캐릭터 수만큼의 값 목록 — 값 종수 분포는 고정하고 개수만 늘린다(곡선의 형태를 본다). */
private fun valuesFor(characterCount: Int): List<String> = (0 until characterCount).map { c ->
    val v = c % DISTINCT_VALUES
    if (c % 4 == 0) "값${v}_변형" else "값$v"
}

private fun measure(characterCount: Int): Long {
    val defs = fields()
    val entriesByField = defs.associate { it.id to entriesFor(it.id) }
    val values = valuesFor(characterCount)

    // 워밍업 — JIT가 안정된 뒤 잰다
    repeat(3) {
        for (fd in defs) FieldValueRules.recountedEntries(fd, entriesByField[fd.id]!!, values)
    }

    val runs = 5
    var best = Long.MAX_VALUE
    repeat(runs) {
        val t0 = System.nanoTime()
        // 캐릭터 저장 1회가 유발하는 작업량 = 그 세계관의 필드 수만큼 재집계
        for (fd in defs) FieldValueRules.recountedEntries(fd, entriesByField[fd.id]!!, values)
        val elapsed = System.nanoTime() - t0
        if (elapsed < best) best = elapsed
    }
    return best / 1_000_000
}

fun main() {
    println("── 재집계 CPU 몫 실측 (필드 ${FIELDS}개 × 값 종수 ${DISTINCT_VALUES}) ──")
    println("배율\t캐릭터\t값 읽기\t소요(ms)")
    for (mult in listOf(1, 3, 10, 30)) {
        val chars = BASE_CHARACTERS * mult
        val ms = measure(chars)
        println("×$mult\t$chars\t${chars * FIELDS}\t$ms")
    }
    println()
    println("주의: SQLite 조회 I/O는 포함되지 않는다(Room 런타임 필요).")
    println("      수확 훅은 이 비용을 저장 경로에서 지고 있지 않다 — 예약 후 백그라운드에서 돈다.")
}
