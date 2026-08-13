import java.util.*;

/**
 * 관계도 레이아웃의 규모 실측 — 이 앱에서 <b>캐릭터 수에 대해 2차식인 유일한 계산</b>이다.
 *
 * <p>재는 것은 둘이다.
 *
 * <h2>① 요약 모드가 노드 수를 <b>가두는가</b></h2>
 *
 * `RelationshipGraphFragment`는 관여 캐릭터가 {@code SUMMARY_MODE_THRESHOLD}(200)을 넘으면
 * "요약 모드"로 내려가는데, 그 모드가 남기는 것은 <b>연결 3개 이상인 캐릭터 전부</b>다.
 * 이것은 <b>술어이지 상한이 아니다</b> — 남는 수가 관계 수에 비례해 함께 늘어난다.
 * `scalability_performance_2026-07.md` 4장은 이 상한의 ×30 칸에 <i>"상시 요약 모드"</i>라고만
 * 적어 두었는데, 그 문장은 <b>요약 모드가 규모를 막는다</b>를 전제한다. 그 전제를 여기서 잰다.
 *
 * <h2>② 그 노드 수에서 레이아웃이 얼마나 도는가</h2>
 *
 * `RelationshipGraphView.computeLayout`은 force-directed이고 반복마다 <b>모든 노드 쌍</b>을 두 번 훑는다
 * (반발력 1회 · 세력 클러스터링 1회). 반복 수는 {@code min(300, max(100, n*8))}이라 n ≥ 38이면 300 고정이다.
 * 즉 <b>2 × 300 × n(n-1)/2</b>가 바닥 비용이고, 여기에 반복마다 다시 짓는 박싱 集合 둘이 얹힌다.
 *
 * <p>세 판을 나란히 잰다.
 * <ul>
 *   <li><b>현행</b> — 지금 코드 그대로.</li>
 *   <li><b>낭비 제거</b> — 항상 참인 정리 셋만. ⓐ 세력 루프의 중복 방지 集合은 <b>원리적으로 아무것도
 *       거르지 못한다</b>(i&lt;j 루프라 같은 쌍이 두 번 올 수 없다) ⓑ 엣지 중복 방지는 <b>반복마다</b>
 *       다시 짓는데 입력이 안 변한다 ⓒ 세력 공유 판정이 {@code List.any{it in List}}라 쌍마다 반복자를 만든다.</li>
 *   <li><b>세력 그룹화</b> — 위에 더해, 세력 공유 쌍을 <b>n²/2를 훑어 찾지 않고</b> 세력별 묶음 안에서만 짝짓는다
 *       (Σ m_f²/2). 만드는 쌍의 집합은 같다 — 다만 한 쌍이 여러 세력에서 나올 수 있어 <b>그때는 중복 방지가
 *       진짜로 필요해진다</b>(ⓐ에서 지운 그 장치가 여기서 제자리를 찾는다).</li>
 * </ul>
 *
 * <p><b>시간과 함께 '만든 쌍 수'를 낸다</b> — 빨라진 것만 보고 <b>같은 힘을 주는지</b>를 안 보면
 * 이 프로브가 증명하는 것이 없다(`LedgerScaleProbe`·`DrilldownScaleProbe`와 같은 규약).
 * 세 판의 최종 좌표가 부동소수 오차 안에서 같은지도 함께 대조한다.
 *
 * <p><b>왜 순수 자바인가:</b> 재는 것이 JVM 안의 산술·박싱·컬렉션이라 안드로이드가 필요 없다.
 * 상수는 기기에서 더 크게 나오지만(ART는 데스크톱 JIT보다 느리다) 재는 것이 <b>비율과 차수</b>라
 * 답이 바뀌지 않는다 — `SearchScaleProbe`가 SQLite에 대해 같은 논리를 쓴다.
 *
 * <h2>돌리는 법</h2>
 *
 * <pre>
 *   javac -encoding UTF-8 tools/usage-probe/GraphLayoutScaleProbe.java -d /tmp/probe-out
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp /tmp/probe-out GraphLayoutScaleProbe
 * </pre>
 */
public class GraphLayoutScaleProbe {

    /** 실사용 표본(2026-07 사용자 엑셀 1건) — `scalability_performance_2026-07.md` 2장. */
    static final int CHARS_1 = 214, RELS_1 = 135, FACTIONS_1 = 5;

    /** `RelationshipGraphFragment.SUMMARY_MODE_THRESHOLD` — 관여 캐릭터가 이보다 많으면 요약 모드. */
    static final int SUMMARY_MODE_THRESHOLD = 200;

    /** 요약 모드가 남기는 기준 — 연결 수가 이 값 이상인 캐릭터. */
    static final int SUMMARY_MIN_DEGREE = 3;

    /** 재기 전에 덥히는 횟수 — 이것이 0이면 첫 측정이 JIT 이전 값이라 정본이 흔들린다. */
    static final int WARMUP = 3, REPS = 5;

    /** 레이아웃 한 번이 비싸서 반복을 적게 잡는다. 대신 큰 n은 반복을 더 줄인다(아래 repsFor). */
    static int repsFor(int n) { return n >= 800 ? 3 : REPS; }
    static int warmupFor(int n) { return n >= 800 ? 1 : WARMUP; }

    static long seed;
    static void reseed() { seed = 20260813L; }
    static int rnd(int n) { seed = seed * 6364136223846793005L + 1442695040888963407L; return (int) ((seed >>> 33) % n); }

    // ─────────────────────────────────────────────────────────────────────
    // 모델 — `GraphNode`·`GraphEdge`의 이 경로에 쓰이는 부분만
    // ─────────────────────────────────────────────────────────────────────

    static final class Node {
        final long id;
        final List<Long> factionIds;
        Node(long id, List<Long> f) { this.id = id; factionIds = f; }
    }

    static final class Edge {
        final long fromId, toId;
        Edge(long f, long t) { fromId = f; toId = t; }
    }

    /** 한 배수의 그래프. 관계는 서로 다른 두 캐릭터를 균등 무작위로 잇는다(밀도 유지). */
    static final class Graph {
        final int characters, relationships;
        final long[] a, b;            // 관계의 두 끝
        final int[] degree;           // 캐릭터별 연결 수
        final int involved;           // 관계에 한 번이라도 나온 캐릭터 수 = 요약 모드 이전의 노드 수
        final int summaryNodes;       // 연결 ≥ 3 인 캐릭터 수 = 요약 모드가 남기는 노드 수

        Graph(int characters, int relationships) {
            this.characters = characters;
            this.relationships = relationships;
            a = new long[relationships];
            b = new long[relationships];
            degree = new int[characters];
            for (int i = 0; i < relationships; i++) {
                int x = rnd(characters);
                int y = rnd(characters);
                if (y == x) y = (x + 1) % characters;
                a[i] = x; b[i] = y;
                degree[x]++; degree[y]++;
            }
            int inv = 0, sum = 0;
            for (int d : degree) {
                if (d > 0) inv++;
                if (d >= SUMMARY_MIN_DEGREE) sum++;
            }
            involved = inv;
            summaryNodes = sum;
        }
    }

    /**
     * 실제로 화면에 서는 노드/엣지를 만든다 — 요약 모드 판정까지 프래그먼트와 같은 순서로.
     * 세력은 표본의 세력 수에 비례해 늘리고, 노드의 60%가 한 세력·10%가 두 세력에 든다고 본다
     * (표본에 소속 분포가 없어 **가정이다** — 세력 루프의 비용은 이 가정에 비례하므로 표에 함께 적는다).
     */
    static Object[] buildScreenGraph(Graph g, int factionCount) {
        boolean summaryMode = g.involved > SUMMARY_MODE_THRESHOLD;
        int[] keep = new int[g.characters];
        for (int i = 0; i < g.characters; i++) {
            if (summaryMode) keep[i] = g.degree[i] >= SUMMARY_MIN_DEGREE ? 1 : 0;
            else keep[i] = g.degree[i] > 0 ? 1 : 0;
        }
        List<Node> nodes = new ArrayList<>();
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < g.characters; i++) {
            if (keep[i] == 0) continue;
            List<Long> f = new ArrayList<>(2);
            int r = rnd(100);
            if (r < 60) f.add((long) rnd(factionCount));
            else if (r < 70) { f.add((long) rnd(factionCount)); f.add((long) rnd(factionCount)); }
            index.put((long) i, nodes.size());
            nodes.add(new Node(i, f));
        }
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < g.relationships; i++) {
            if (index.containsKey(g.a[i]) && index.containsKey(g.b[i])) edges.add(new Edge(g.a[i], g.b[i]));
        }
        return new Object[]{nodes.toArray(new Node[0]), edges.toArray(new Edge[0])};
    }

    // ─────────────────────────────────────────────────────────────────────
    // 판 1 — 현행 (`RelationshipGraphView.computeLayout`의 충실한 이식)
    // ─────────────────────────────────────────────────────────────────────

    static long pairsVisitedCurrent, pairsForcedCurrent;

    static float[] layoutCurrent(Node[] nodesCopy, Edge[] edgesCopy) {
        int count = nodesCopy.length;
        float[] xs = new float[count], ys = new float[count];
        float radius = Math.max(150f, count * 30f);
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            xs[i] = (float) (radius * Math.cos(angle));
            ys[i] = (float) (radius * Math.sin(angle));
        }
        int iterations = Math.min(300, Math.max(100, count * 8));
        float k = (float) Math.sqrt((radius * radius * 5) / (float) count);
        float temp = radius * 0.7f;
        Map<Long, Integer> nodeIndexMap = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) nodeIndexMap.put(nodesCopy[i].id, i);

        Set<Long> processedPairs = new LinkedHashSet<>();
        for (int iter = 0; iter < iterations; iter++) {
            float cooling = temp * (1f - (float) iter / iterations);
            float[] dispX = new float[count], dispY = new float[count];

            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                    float dist = Math.max((float) Math.sqrt(dx * dx + dy * dy), 0.1f);
                    float force = k * k / dist;
                    float fx = dx / dist * force, fy = dy / dist * force;
                    dispX[i] += fx; dispY[i] += fy;
                    dispX[j] -= fx; dispY[j] -= fy;
                }
            }

            processedPairs.clear();
            for (Edge edge : edgesCopy) {
                Integer iIdx = nodeIndexMap.get(edge.fromId);
                if (iIdx == null) continue;
                Integer jIdx = nodeIndexMap.get(edge.toId);
                if (jIdx == null) continue;
                long pairKey = (Math.min(edge.fromId, edge.toId) << 32) | Math.max(edge.fromId, edge.toId);
                if (!processedPairs.add(pairKey)) continue;
                float dx = xs[iIdx] - xs[jIdx], dy = ys[iIdx] - ys[jIdx];
                float dist = Math.max((float) Math.sqrt(dx * dx + dy * dy), 0.1f);
                float force = dist * dist / k;
                float fx = dx / dist * force, fy = dy / dist * force;
                dispX[iIdx] -= fx; dispY[iIdx] -= fy;
                dispX[jIdx] += fx; dispY[jIdx] += fy;
            }

            float factionWeight = 0.3f;
            processedPairs.clear();
            for (int i = 0; i < count; i++) {
                List<Long> iFactions = nodesCopy[i].factionIds;
                if (iFactions.isEmpty()) continue;
                for (int j = i + 1; j < count; j++) {
                    List<Long> jFactions = nodesCopy[j].factionIds;
                    if (jFactions.isEmpty()) continue;
                    if (iter == 0) pairsVisitedCurrent++;
                    boolean shared = false;
                    for (Long f : iFactions) { if (jFactions.contains(f)) { shared = true; break; } }
                    if (!shared) continue;
                    long pairKey = ((long) i << 32) | (long) j;
                    if (!processedPairs.add(pairKey)) continue;
                    if (iter == 0) pairsForcedCurrent++;
                    float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                    float dist = Math.max((float) Math.sqrt(dx * dx + dy * dy), 0.1f);
                    float force = dist * dist / k * factionWeight;
                    float fx = dx / dist * force, fy = dy / dist * force;
                    dispX[i] -= fx; dispY[i] -= fy;
                    dispX[j] += fx; dispY[j] += fy;
                }
            }

            for (int i = 0; i < count; i++) {
                float dist = Math.max((float) Math.sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i]), 0.1f);
                xs[i] += dispX[i] / dist * Math.min(dist, cooling);
                ys[i] += dispY[i] / dist * Math.min(dist, cooling);
            }
        }
        return interleave(xs, ys);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 판 2 — 낭비 제거 (항상 참인 정리만. 만드는 쌍의 집합은 판 1과 같다)
    // ─────────────────────────────────────────────────────────────────────

    static float[] layoutTrimmed(Node[] nodesCopy, Edge[] edgesCopy) {
        int count = nodesCopy.length;
        float[] xs = new float[count], ys = new float[count];
        float radius = Math.max(150f, count * 30f);
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            xs[i] = (float) (radius * Math.cos(angle));
            ys[i] = (float) (radius * Math.sin(angle));
        }
        int iterations = Math.min(300, Math.max(100, count * 8));
        float k = (float) Math.sqrt((radius * radius * 5) / (float) count);
        float temp = radius * 0.7f;

        // ⓑ 엣지 중복 제거를 **한 번만** 한다 — 입력이 반복 중에 변하지 않는다.
        int[] edgeI = new int[edgesCopy.length], edgeJ = new int[edgesCopy.length];
        int edgeCount = uniqueEdgePairs(nodesCopy, edgesCopy, edgeI, edgeJ);
        // ⓒ 세력 목록을 원시 배열로 — 쌍마다 반복자를 만들지 않는다.
        long[][] factions = new long[count][];
        for (int i = 0; i < count; i++) {
            List<Long> f = nodesCopy[i].factionIds;
            long[] arr = new long[f.size()];
            for (int t = 0; t < arr.length; t++) arr[t] = f.get(t);
            factions[i] = arr;
        }

        float factionWeight = 0.3f;
        float[] dispX = new float[count], dispY = new float[count];
        for (int iter = 0; iter < iterations; iter++) {
            float cooling = temp * (1f - (float) iter / iterations);
            Arrays.fill(dispX, 0f); Arrays.fill(dispY, 0f);

            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                    float dist = Math.max((float) Math.sqrt(dx * dx + dy * dy), 0.1f);
                    float force = k * k / dist;
                    float fx = dx / dist * force, fy = dy / dist * force;
                    dispX[i] += fx; dispY[i] += fy;
                    dispX[j] -= fx; dispY[j] -= fy;
                }
            }

            for (int e = 0; e < edgeCount; e++) {
                int i = edgeI[e], j = edgeJ[e];
                float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                float dist = Math.max((float) Math.sqrt(dx * dx + dy * dy), 0.1f);
                float force = dist * dist / k;
                float fx = dx / dist * force, fy = dy / dist * force;
                dispX[i] -= fx; dispY[i] -= fy;
                dispX[j] += fx; dispY[j] += fy;
            }

            // ⓐ 중복 방지 集合 없음 — i<j 루프라 같은 쌍이 두 번 올 수 없다.
            for (int i = 0; i < count; i++) {
                long[] fi = factions[i];
                if (fi.length == 0) continue;
                for (int j = i + 1; j < count; j++) {
                    long[] fj = factions[j];
                    if (fj.length == 0) continue;
                    if (!sharesFaction(fi, fj)) continue;
                    float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                    float dist = Math.max((float) Math.sqrt(dx * dx + dy * dy), 0.1f);
                    float force = dist * dist / k * factionWeight;
                    float fx = dx / dist * force, fy = dy / dist * force;
                    dispX[i] -= fx; dispY[i] -= fy;
                    dispX[j] += fx; dispY[j] += fy;
                }
            }

            for (int i = 0; i < count; i++) {
                float dist = Math.max((float) Math.sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i]), 0.1f);
                xs[i] += dispX[i] / dist * Math.min(dist, cooling);
                ys[i] += dispY[i] / dist * Math.min(dist, cooling);
            }
        }
        return interleave(xs, ys);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 판 3 — 세력 그룹화 (세력 쌍을 n²/2로 찾지 않는다)
    // ─────────────────────────────────────────────────────────────────────

    static long factionPairsGrouped;

    static float[] layoutGrouped(Node[] nodesCopy, Edge[] edgesCopy) {
        int count = nodesCopy.length;
        float[] xs = new float[count], ys = new float[count];
        float radius = Math.max(150f, count * 30f);
        for (int i = 0; i < count; i++) {
            double angle = 2.0 * Math.PI * i / count;
            xs[i] = (float) (radius * Math.cos(angle));
            ys[i] = (float) (radius * Math.sin(angle));
        }
        int iterations = Math.min(300, Math.max(100, count * 8));
        float k = (float) Math.sqrt((radius * radius * 5) / (float) count);
        float temp = radius * 0.7f;

        int[] edgeI = new int[edgesCopy.length], edgeJ = new int[edgesCopy.length];
        int edgeCount = uniqueEdgePairs(nodesCopy, edgesCopy, edgeI, edgeJ);

        // 세력별 소속 노드 묶음 → 그 안에서만 짝짓는다. 한 쌍이 여러 세력에서 나올 수 있어
        // **여기서는 중복 방지가 진짜로 필요하다**(판 2에서 지운 그 장치의 제자리).
        Map<Long, List<Integer>> byFaction = new LinkedHashMap<>();
        for (int i = 0; i < count; i++)
            for (Long f : nodesCopy[i].factionIds) byFaction.computeIfAbsent(f, x -> new ArrayList<>()).add(i);
        Set<Long> seen = new HashSet<>();
        List<int[]> fp = new ArrayList<>();
        for (List<Integer> members : byFaction.values()) {
            for (int x = 0; x < members.size(); x++)
                for (int y = x + 1; y < members.size(); y++) {
                    int i = members.get(x), j = members.get(y);
                    if (i > j) { int t = i; i = j; j = t; }
                    if (seen.add(((long) i << 32) | (long) j)) fp.add(new int[]{i, j});
                }
        }
        // **사전순으로 세운다.** 힘은 부동소수 덧셈으로 쌓이는데 force-directed는 300회를 도는
        // 되먹임이라 **1e-7의 순서 차이가 마지막에는 눈에 보이는 배치 차이가 된다**(초판에서 실측:
        // 좌표 최대 차 37,660). 중첩 루프가 만드는 순서가 정확히 (i,j) 사전순이므로 그대로 맞추면
        // 누적 순서가 한 연산씩 같아져 **비트까지 같은 결과**가 나온다.
        fp.sort((p, q) -> p[0] != q[0] ? Integer.compare(p[0], q[0]) : Integer.compare(p[1], q[1]));
        factionPairsGrouped = fp.size();
        int[] facI = new int[fp.size()], facJ = new int[fp.size()];
        for (int t = 0; t < fp.size(); t++) { facI[t] = fp.get(t)[0]; facJ[t] = fp.get(t)[1]; }

        float factionWeight = 0.3f;
        float[] dispX = new float[count], dispY = new float[count];
        for (int iter = 0; iter < iterations; iter++) {
            float cooling = temp * (1f - (float) iter / iterations);
            Arrays.fill(dispX, 0f); Arrays.fill(dispY, 0f);

            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                    float dist = Math.max((float) Math.sqrt(dx * dx + dy * dy), 0.1f);
                    float force = k * k / dist;
                    float fx = dx / dist * force, fy = dy / dist * force;
                    dispX[i] += fx; dispY[i] += fy;
                    dispX[j] -= fx; dispY[j] -= fy;
                }
            }

            for (int e = 0; e < edgeCount; e++) {
                int i = edgeI[e], j = edgeJ[e];
                float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                float dist = Math.max((float) Math.sqrt(dx * dx + dy * dy), 0.1f);
                float force = dist * dist / k;
                float fx = dx / dist * force, fy = dy / dist * force;
                dispX[i] -= fx; dispY[i] -= fy;
                dispX[j] += fx; dispY[j] += fy;
            }

            for (int t = 0; t < facI.length; t++) {
                int i = facI[t], j = facJ[t];
                float dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                float dist = Math.max((float) Math.sqrt(dx * dx + dy * dy), 0.1f);
                float force = dist * dist / k * factionWeight;
                float fx = dx / dist * force, fy = dy / dist * force;
                dispX[i] -= fx; dispY[i] -= fy;
                dispX[j] += fx; dispY[j] += fy;
            }

            for (int i = 0; i < count; i++) {
                float dist = Math.max((float) Math.sqrt(dispX[i] * dispX[i] + dispY[i] * dispY[i]), 0.1f);
                xs[i] += dispX[i] / dist * Math.min(dist, cooling);
                ys[i] += dispY[i] / dist * Math.min(dist, cooling);
            }
        }
        return interleave(xs, ys);
    }

    // ─────────────────────────────────────────────────────────────────────

    static int uniqueEdgePairs(Node[] nodes, Edge[] edges, int[] outI, int[] outJ) {
        Map<Long, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < nodes.length; i++) idx.put(nodes[i].id, i);
        Set<Long> seen = new LinkedHashSet<>();
        int n = 0;
        for (Edge e : edges) {
            Integer i = idx.get(e.fromId); if (i == null) continue;
            Integer j = idx.get(e.toId); if (j == null) continue;
            long key = (Math.min(e.fromId, e.toId) << 32) | Math.max(e.fromId, e.toId);
            if (!seen.add(key)) continue;
            outI[n] = i; outJ[n] = j; n++;
        }
        return n;
    }

    static boolean sharesFaction(long[] a, long[] b) {
        for (long x : a) for (long y : b) if (x == y) return true;
        return false;
    }

    static float[] interleave(float[] xs, float[] ys) {
        float[] out = new float[xs.length * 2];
        for (int i = 0; i < xs.length; i++) { out[2 * i] = xs[i]; out[2 * i + 1] = ys[i]; }
        return out;
    }

    static double medianMs(java.util.function.Supplier<float[]> body, int warmup, int reps) {
        for (int i = 0; i < warmup; i++) body.get();
        double[] t = new double[reps];
        for (int i = 0; i < reps; i++) {
            long s = System.nanoTime();
            body.get();
            t[i] = (System.nanoTime() - s) / 1_000_000.0;
        }
        Arrays.sort(t);
        return t[reps / 2];
    }

    static double maxDelta(float[] a, float[] b) {
        double m = 0;
        for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    public static void main(String[] args) {
        System.out.println("=== 관계도 레이아웃의 규모 — 요약 모드는 상한인가 (5-5 · 4장 SUMMARY_MODE_THRESHOLD) ===\n");
        System.out.println("표본 ×1 = 캐릭터 " + CHARS_1 + " · 관계 " + RELS_1 + " · 세력 " + FACTIONS_1
                + "   (관계는 서로 다른 두 캐릭터를 균등 무작위로 잇는다 — 밀도 유지)\n");

        // ── ① 요약 모드가 남기는 노드 수 ──
        System.out.println("── ① 요약 모드가 남기는 노드 수 (연결 " + SUMMARY_MIN_DEGREE + "개 이상) ──");
        System.out.println("배수 | 캐릭터 | 관계 | 관여 캐릭터 | 요약 모드? | **실제 노드 수**");
        int[] mults = {1, 3, 10, 30};
        Map<Integer, Integer> nodesAt = new LinkedHashMap<>();
        for (int m : mults) {
            reseed();
            Graph g = new Graph(CHARS_1 * m, RELS_1 * m);
            boolean sm = g.involved > SUMMARY_MODE_THRESHOLD;
            int shown = sm ? g.summaryNodes : g.involved;
            nodesAt.put(m, shown);
            System.out.printf("×%-3d | %6d | %5d | %10d | %-9s | %d%n",
                    m, g.characters, g.relationships, g.involved, sm ? "예" : "아니오", shown);
        }
        System.out.println("\n밀도가 오르는 축 (3-4가 '재지 않았다'고 적은 그 축 — 캐릭터는 ×30 고정, 관계만 늘린다):");
        System.out.println("관계 배수 | 관계 수 | 관여 캐릭터 | **실제 노드 수**");
        for (int d : new int[]{1, 2, 3}) {
            reseed();
            Graph g = new Graph(CHARS_1 * 30, RELS_1 * 30 * d);
            boolean sm = g.involved > SUMMARY_MODE_THRESHOLD;
            int shown = sm ? g.summaryNodes : g.involved;
            System.out.printf("×%-8d | %7d | %10d | %d%n", d, g.relationships, g.involved, shown);
        }

        // ── ② 그 노드 수에서 레이아웃 비용 ──
        System.out.println("\n── ② 레이아웃 한 번의 비용 (`computeLayout`) ──");
        System.out.println("노드 | 반복 | 반발력 쌍/반복 | 현행 ms | 낭비 제거 ms | 세력 그룹화 ms | 세력 쌍 훑기(현행→그룹화)");
        int[] ns = {nodesAt.get(1), 200, nodesAt.get(3), nodesAt.get(10), nodesAt.get(30)};
        Arrays.sort(ns);
        for (int n : ns) {
            if (n < 2) continue;
            reseed();
            // 그 노드 수를 그대로 만드는 그래프 — 관계는 노드 수에 비례해 붙인다.
            Graph g = new Graph(n, Math.max(1, (int) ((long) n * RELS_1 / CHARS_1)));
            Object[] sg = buildScreenGraphAll(g, Math.max(1, FACTIONS_1 * n / CHARS_1));
            Node[] nodes = (Node[]) sg[0];
            Edge[] edges = (Edge[]) sg[1];
            if (nodes.length < 2) continue;
            int cnt = nodes.length;
            int iterations = Math.min(300, Math.max(100, cnt * 8));

            pairsVisitedCurrent = 0; pairsForcedCurrent = 0;
            float[] cur = layoutCurrent(nodes, edges);
            float[] trm = layoutTrimmed(nodes, edges);
            float[] grp = layoutGrouped(nodes, edges);
            double d1 = maxDelta(cur, trm), d2 = maxDelta(cur, grp);

            double tCur = medianMs(() -> layoutCurrent(nodes, edges), warmupFor(cnt), repsFor(cnt));
            double tTrm = medianMs(() -> layoutTrimmed(nodes, edges), warmupFor(cnt), repsFor(cnt));
            double tGrp = medianMs(() -> layoutGrouped(nodes, edges), warmupFor(cnt), repsFor(cnt));

            System.out.printf("%4d | %4d | %,14d | %8.1f | %12.1f | %14.1f | %,d → %,d%n",
                    cnt, iterations, (long) cnt * (cnt - 1) / 2, tCur, tTrm, tGrp,
                    pairsVisitedCurrent, factionPairsGrouped);
            System.out.printf("     └ 좌표 최대 차: 낭비 제거 %.4f · 세력 그룹화 %.4f  (같은 힘을 주는가)%n", d1, d2);
        }

        System.out.println("\n(중앙값. 노드 800 이상은 WARMUP=1·REPS=3, 나머지는 " + WARMUP + "·" + REPS + ".");
        System.out.println(" 세력 소속은 **가정이다** — 노드의 60%가 한 세력, 10%가 두 세력. 표본에 소속 분포가 없다.)");
    }

    /** ②의 표는 노드 수를 고정해 재므로 요약 모드 판정 없이 관여 캐릭터 전부를 세운다. */
    static Object[] buildScreenGraphAll(Graph g, int factionCount) {
        List<Node> nodes = new ArrayList<>();
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < g.characters; i++) {
            List<Long> f = new ArrayList<>(2);
            int r = rnd(100);
            if (r < 60) f.add((long) rnd(factionCount));
            else if (r < 70) { f.add((long) rnd(factionCount)); f.add((long) rnd(factionCount)); }
            index.put((long) i, nodes.size());
            nodes.add(new Node(i, f));
        }
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < g.relationships; i++) edges.add(new Edge(g.a[i], g.b[i]));
        return new Object[]{nodes.toArray(new Node[0]), edges.toArray(new Edge[0])};
    }
}
