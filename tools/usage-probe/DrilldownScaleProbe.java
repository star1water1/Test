import java.util.*;

/**
 * 통계 드릴다운의 필드값 훑기 실측 — B-209. 스냅샷의 <b>모든</b> 필드값을 매번 훑는 것과
 * <b>필드정의로 색인해</b> 자기 몫만 보는 것의 차이는 얼마인가.
 *
 * <p>모양은 이렇다(`StatsDataProvider`의 여섯 자리가 글자까지 같다):
 * <pre>
 *   for (fv in s.fieldValues) {
 *       if (fv.fieldDefinitionId !in idSet) continue
 *       if (defById[fv.fieldDefinitionId]?.fieldType == CALCULATED) continue
 *       … 매칭 …
 *   }
 * </pre>
 * 세 겹이 겹쳐 있다 — ① 드릴다운이 보는 것은 <b>필드 하나</b>인데 컬렉션은 <b>모든 필드의 모든 값</b>이다
 * ② 같은 id로 Map을 <b>두 번</b> 친다(뒤엣것이 앞엣것의 답을 품는다) ③ 키가 {@code Long}이라 두 조회 모두 <b>박싱</b>한다.
 *
 * <p><b>시간과 함께 '찾은 수'를 낸다</b> — 빨라진 것만 보고 <b>같은 답을 내는지</b>를 안 보면
 * 이 프로브가 증명하는 것이 없다(`LedgerScaleProbe`와 같은 규약).
 *
 * <p><b>왜 순수 자바인가:</b> 재는 것이 JVM 안의 컬렉션 순회·박싱이라 안드로이드가 필요 없다.
 * 상수는 기기에서 더 크게 나오지만(ART는 데스크톱 JIT보다 느리다) 재는 것이 <b>비율</b>이라
 * 답이 바뀌지 않는다 — `SearchScaleProbe`가 SQLite에 대해 같은 논리를 쓴다.
 *
 * <h2>돌리는 법</h2>
 *
 * <pre>
 *   javac -encoding UTF-8 tools/usage-probe/DrilldownScaleProbe.java -d /tmp/probe-out
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp /tmp/probe-out DrilldownScaleProbe
 * </pre>
 */
public class DrilldownScaleProbe {

    /** 실사용 표본(2026-07 사용자 엑셀 1건) — `scalability_performance_2026-07.md` 2장. */
    static final int CHARS_1 = 214, VALUES_1 = 1217, SLOTS_1 = 5617;
    /** 커스텀 필드 정의 수 — ×30에서도 90 고정이다(2장 주: 캐릭터를 30배 늘려도 스키마는 안 늘린다). */
    static final int FIELDS = 90;

    /** 재기 전에 덥히는 횟수 — 이것이 0이면 첫 측정이 JIT 이전 값이라 정본이 흔들린다. */
    static final int WARMUP = 200, REPS = 21;

    static long seed = 20260813L;
    static int rnd(int n) { seed = seed * 6364136223846793005L + 1442695040888963407L; return (int) ((seed >>> 33) % n); }

    /** `CharacterFieldValue`의 이 경로에 쓰이는 부분. */
    static final class FV {
        final long characterId, fieldDefinitionId; final String value;
        FV(long c, long f, String v) { characterId = c; fieldDefinitionId = f; value = v; }
    }

    /** `FieldDefinition`의 이 경로에 쓰이는 부분. */
    static final class FD {
        final long id; final String fieldType;
        FD(long id, String t) { this.id = id; fieldType = t; }
    }

    public static void main(String[] args) {
        System.out.println("=== 통계 드릴다운 필드값 훑기 — 색인 전/후 (B-209) ===\n");
        System.out.println("표본 ×1 = 캐릭터 " + CHARS_1 + " · 필드값 " + VALUES_1 + " · 필드정의 " + FIELDS
                + " (정의된 칸 " + SLOTS_1 + " 중 21.7% 채움)\n");

        // 세 자리를 잰다. ×30은 목표 규모, '×30 만실'은 2장 ⚠️이 말한 그 하한 위의 자리다
        // (사용자가 계속 채우는 중이고, 채움만으로 필드값이 3.7배가 된다).
        run("×1        ", CHARS_1, VALUES_1);
        run("×30       ", CHARS_1 * 30, VALUES_1 * 30);
        run("×30 만실  ", CHARS_1 * 30, SLOTS_1 * 30);
    }

    static void run(String label, int nChars, int nValues) {
        List<FV> fieldValues = new ArrayList<>(nValues);
        for (int i = 0; i < nValues; i++) {
            fieldValues.add(new FV(1 + rnd(nChars), 1 + rnd(FIELDS), "값" + rnd(500)));
        }
        Map<Long, FD> defById = new HashMap<>();
        for (long id = 1; id <= FIELDS; id++) {
            // 표본의 계산 필드는 소수다 — 90 중 3을 CALCULATED로 둔다.
            defById.put(id, new FD(id, id <= 3 ? "CALCULATED" : "TEXT"));
        }

        // 드릴다운이 실제로 넘겨받는 것: 인사이트 카드가 합산한 **머지 id 전체**(S-7).
        // 흔한 경우는 하나이고, 세계관이 여럿이면 형제 def가 몇 개 붙는다 — 둘 다 잰다.
        for (int targets : new int[]{1, 3}) {
            Set<Long> idSet = new LinkedHashSet<>();
            for (int k = 0; k < targets; k++) idSet.add((long) (10 + k)); // 전부 저장형(비 CALCULATED)

            // **먼저 덥힌다.** 이것을 빼면 JIT이 아직 안 돈 첫 측정이 그대로 정본이 된다 —
            // 초판 프로브가 같은 36,510건 훑기를 3.5 ms와 1.1 ms로 재어 그 사실을 보여 줬다.
            // 끌어올리기판이 미리 가르는 것 — 대상 def 중 **저장형만**. 루프 밖에서 한 번 짓는다.
            Set<Long> storedIds = new LinkedHashSet<>();
            for (Long id : idSet) {
                FD fd = defById.get(id);
                if (fd != null && !fd.fieldType.equals("CALCULATED")) storedIds.add(id);
            }

            int sink = 0;
            for (int w = 0; w < WARMUP; w++) {
                sink += scanAll(fieldValues, idSet, defById);
                sink += scanHoisted(fieldValues, storedIds);
                sink += scanIndexed(buildIndex(fieldValues), idSet, defById);
            }
            if (sink == Integer.MIN_VALUE) System.out.print(""); // 죽은 코드 제거 방지

            long[] cur = new long[REPS];
            long[] hoi = new long[REPS];
            long[] idx = new long[REPS];
            long[] bld = new long[REPS];
            int curFound = 0, hoiFound = 0, idxFound = 0;
            Map<Long, List<FV>> index = null;

            for (int rep = 0; rep < REPS; rep++) {
                long t0 = System.nanoTime();
                curFound = scanAll(fieldValues, idSet, defById);
                cur[rep] = System.nanoTime() - t0;

                long th = System.nanoTime();
                hoiFound = scanHoisted(fieldValues, storedIds);
                hoi[rep] = System.nanoTime() - th;

                long t1 = System.nanoTime();
                index = buildIndex(fieldValues);
                bld[rep] = System.nanoTime() - t1;

                long t2 = System.nanoTime();
                idxFound = scanIndexed(index, idSet, defById);
                idx[rep] = System.nanoTime() - t2;
            }
            Arrays.sort(cur); Arrays.sort(hoi); Arrays.sort(idx); Arrays.sort(bld);
            long buildNanos = bld[REPS / 2];

            int touchedCur = nValues;                      // 훑는 건수 = 컬렉션 전체
            int touchedIdx = 0;
            for (Long id : idSet) touchedIdx += index.getOrDefault(id, List.of()).size();

            int m = REPS / 2;
            System.out.printf("%s def %d개 | 훑는 건수 %,7d → 색인 %,6d (%2.0f배 적다) |"
                            + " 지금 %6.3f ms · 끌어올리기 %6.3f ms (%.1f배) · 색인 %6.3f ms (+짓기 %6.3f ms) |"
                            + " 찾은 수 %d %s%n",
                    label, targets, touchedCur, touchedIdx,
                    touchedIdx == 0 ? 0.0 : (double) touchedCur / touchedIdx,
                    cur[m] / 1e6, hoi[m] / 1e6, (double) cur[m] / Math.max(1, hoi[m]),
                    idx[m] / 1e6, buildNanos / 1e6,
                    curFound,
                    (curFound == idxFound && curFound == hoiFound) ? "= 셋이 같다" : "≠ 다르다 ★결함★");
        }
        System.out.println();
    }

    /** 지금 코드 — 컬렉션 전체를 훑고, 같은 id로 Map을 두 번 친다(둘 다 박싱). */
    static int scanAll(List<FV> fieldValues, Set<Long> idSet, Map<Long, FD> defById) {
        int found = 0;
        for (FV fv : fieldValues) {
            if (!idSet.contains(fv.fieldDefinitionId)) continue;
            FD fd = defById.get(fv.fieldDefinitionId);
            if (fd != null && fd.fieldType.equals("CALCULATED")) continue;
            found += match(fv.value);
        }
        return found;
    }

    /**
     * <b>끌어올리기판</b> — 색인 없이, 컬렉션은 그대로 전부 훑되 <b>def 판정을 루프 밖으로</b> 뺀다.
     *
     * <p>지금 코드가 값마다 두 번 치는 것({@code idSet} 다음 {@code defById})은 <b>같은 id</b>이고,
     * 뒤엣것이 앞엣것의 답을 품는다. 게다가 묻는 것({@code fieldType == CALCULATED})은
     * <b>def의 성질</b>이라 값마다 달라지지 않는다 — 대상 def를 미리 <b>저장형/계산형으로 가르면</b>
     * 안쪽은 집합 한 번만 친다. 박싱도 두 번에서 한 번으로 준다.
     *
     * <p>이 판이 중요한 이유: <b>구조를 하나도 안 바꾼다.</b> 새 캐시도, 스냅샷 확대도,
     * 무효화 축도 없다 — 그런데도 얼마를 가져가는지가 판정의 갈림길이다.
     */
    static int scanHoisted(List<FV> fieldValues, Set<Long> storedIds) {
        int found = 0;
        for (FV fv : fieldValues) {
            if (!storedIds.contains(fv.fieldDefinitionId)) continue;
            found += match(fv.value);
        }
        return found;
    }

    /** 색인판 — 자기 몫만 받고, def는 <b>바깥에서 한 번</b> 판정한다(값마다가 아니다). */
    static int scanIndexed(Map<Long, List<FV>> index, Set<Long> idSet, Map<Long, FD> defById) {
        int found = 0;
        for (Long defId : idSet) {
            FD fd = defById.get(defId);
            if (fd == null || fd.fieldType.equals("CALCULATED")) continue;
            for (FV fv : index.getOrDefault(defId, List.of())) {
                found += match(fv.value);
            }
        }
        return found;
    }

    static Map<Long, List<FV>> buildIndex(List<FV> fieldValues) {
        Map<Long, List<FV>> m = new HashMap<>();
        for (FV fv : fieldValues) m.computeIfAbsent(fv.fieldDefinitionId, k -> new ArrayList<>()).add(fv);
        return m;
    }

    /** 매칭 한 건의 몫 — 양쪽에 <b>똑같이</b> 든다. 여기서 갈리면 재는 것이 스캔이 아니게 된다. */
    static int match(String value) { return value.endsWith("7") ? 1 : 0; }
}
