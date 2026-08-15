import java.sql.*;
import java.util.*;

/**
 * 가져오기 시트 루프의 <b>행별 조회</b>가 색인 하나로 얼마나 줄어드는가 — B-210.
 *
 * <p>B-72 ②는 <b>값</b> 조회의 단위를 캐릭터로 내려 지배적인 둘을 없앴고(3-9),
 * 같은 모양이 <b>행 수가 한 자리 작은 시트들</b>에 그대로 남았다. 그 시트들은 행마다
 * <i>이 행이 가리키는 엔티티가 이미 있는가</i>를 물었다 — {@code getEventByCode} ·
 * {@code getChangeByNaturalKey} · {@code getCharacterByCode} 꼴이다.
 *
 * <p><b>왜 자바인가:</b> 형제 프로브와 같은 이유로 <b>진짜 SQLite</b>가 있어야 하기 때문이다.
 * 안드로이드는 이 환경에서 못 돌지만 SQLite 엔진은 기기의 것과 같고, 재는 것이 <i>비율</i>이라
 * 데스크톱·기기의 절대 속도 차이가 답을 바꾸지 않는다.
 *
 * <p><b>시간과 함께 '찾은 수'를 낸다</b> — 빨라진 것만 보고 <b>같은 답을 내는지</b>를 안 보면
 * 이 프로브가 증명하는 것이 없다({@code LedgerScaleProbe}가 세운 규약).
 *
 * <p><b>덥히기를 넣는다</b> — B-209 프로브가 {@code WARMUP} 없이 같은 훑기를 3.5 ms와 1.1 ms로
 * 냈고, 그 값으로는 판정을 할 수 없었다. <b>재는 것이 판정을 뒤집는 자리에서는 흔들리는 측정이
 * 측정이 아니다.</b>
 *
 * <h2>돌리는 법</h2>
 *
 * <pre>
 *   curl -sSfLo /tmp/jars/sqlite-jdbc.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.3.0/sqlite-jdbc-3.45.3.0.jar
 *   curl -sSfLo /tmp/jars/slf4j-api.jar    https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
 *   curl -sSfLo /tmp/jars/slf4j-nop.jar    https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar
 *   javac -encoding UTF-8 tools/usage-probe/ImportLookupScaleProbe.java -d /tmp/probe-out
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
 *        -cp /tmp/probe-out:/tmp/jars/sqlite-jdbc.jar:/tmp/jars/slf4j-api.jar:/tmp/jars/slf4j-nop.jar \
 *        -Dscale=30 ImportLookupScaleProbe
 * </pre>
 */
public class ImportLookupScaleProbe {
    static final int SCALE = Integer.parseInt(System.getProperty("scale", "30"));
    // 실사용 표본 ×1 (`scalability` 2장). 사건·상태변화는 그 표의 ⬜(미측정) 행이라
    // **행 수 자체가 추정이다** — 이 프로브가 재는 것은 그 수에서의 *비율*이다.
    static final int CHARS_1 = 214, EVENTS_1 = 137, STATE_1 = 113;
    // 작품은 2장 표의 측정된 축이다(17 → 510). ④가 쓴다.
    static final int NOVELS_1 = 17;
    static final int REPS = 5, WARMUP = 2;

    static long seed = 20260813L;
    static int rnd(int n) { seed = seed * 6364136223846793005L + 1442695040888963407L; return (int)((seed >>> 33) % n); }
    static long median(long[] xs) { long[] c = xs.clone(); Arrays.sort(c); return c[c.length / 2]; }

    public static void main(String[] args) throws Exception {
        int nChars = CHARS_1 * SCALE, nEvents = EVENTS_1 * SCALE, nState = STATE_1 * SCALE;
        Class.forName("org.sqlite.JDBC");
        new java.io.File("/tmp/import-lookup-probe.db").delete();
        new java.io.File("/tmp/import-lookup-probe.db-wal").delete();
        Connection c = DriverManager.getConnection("jdbc:sqlite:/tmp/import-lookup-probe.db");
        c.setAutoCommit(false);
        Statement st = c.createStatement();
        // 앱의 표와 같은 모양 — **코드 열에 인덱스가 없다**(현행 스키마가 그렇다).
        // 그것이 이 자리의 요점이다: 행마다 치는 조회 하나하나가 풀스캔이다.
        st.executeUpdate("CREATE TABLE characters (id INTEGER PRIMARY KEY, name TEXT NOT NULL, code TEXT NOT NULL)");
        st.executeUpdate("CREATE TABLE timeline_events (id INTEGER PRIMARY KEY, year INTEGER NOT NULL, description TEXT NOT NULL, code TEXT)");
        st.executeUpdate("CREATE TABLE character_state_changes (id INTEGER PRIMARY KEY, characterId INTEGER NOT NULL, year INTEGER NOT NULL, fieldKey TEXT NOT NULL, newValue TEXT NOT NULL, code TEXT)");

        PreparedStatement pc = c.prepareStatement("INSERT INTO characters VALUES (?,?,?)");
        for (int i = 1; i <= nChars; i++) {
            pc.setLong(1, i); pc.setString(2, "인물" + i); pc.setString(3, "C-" + i);
            pc.addBatch(); if (i % 2000 == 0) pc.executeBatch();
        }
        pc.executeBatch(); pc.close();
        PreparedStatement pe = c.prepareStatement("INSERT INTO timeline_events VALUES (?,?,?,?)");
        for (int i = 1; i <= nEvents; i++) {
            pe.setLong(1, i); pe.setInt(2, 1000 + rnd(500)); pe.setString(3, "사건 설명 " + i); pe.setString(4, "E-" + i);
            pe.addBatch(); if (i % 2000 == 0) pe.executeBatch();
        }
        pe.executeBatch(); pe.close();
        PreparedStatement ps = c.prepareStatement("INSERT INTO character_state_changes VALUES (?,?,?,?,?,?)");
        for (int i = 1; i <= nState; i++) {
            ps.setLong(1, i); ps.setLong(2, 1 + rnd(nChars)); ps.setInt(3, 1000 + rnd(500));
            ps.setString(4, "필드" + rnd(20)); ps.setString(5, "값" + rnd(50)); ps.setString(6, "S-" + i);
            ps.addBatch(); if (i % 2000 == 0) ps.executeBatch();
        }
        ps.executeBatch(); ps.close();
        c.commit();
        st.execute("ANALYZE"); c.commit();

        System.out.println("=== 규모 ×" + SCALE + " : 캐릭터 " + nChars + " · 사건 " + nEvents + " · 상태변화 " + nState + " ===");
        System.out.println("(WARMUP=" + WARMUP + " · REPS=" + REPS + " 중앙값)");
        System.out.println();

        // ── ① 캐릭터 시트: 행마다 코드로 캐릭터를 찾는다 ──
        PreparedStatement byCode = c.prepareStatement("SELECT * FROM characters WHERE code = ? LIMIT 1");
        long[] a1 = new long[REPS]; int found1 = 0;
        for (int r = -WARMUP; r < REPS; r++) {
            long t = System.nanoTime(); int n = 0;
            for (int i = 1; i <= nChars; i++) {
                byCode.setString(1, "C-" + i);
                try (ResultSet rs = byCode.executeQuery()) { if (rs.next()) n++; }
            }
            if (r >= 0) { a1[r] = System.nanoTime() - t; found1 = n; }
        }
        long[] b1 = new long[REPS]; int found2 = 0;
        for (int r = -WARMUP; r < REPS; r++) {
            long t = System.nanoTime(); int n = 0;
            HashMap<String, Long> index = new HashMap<>();
            try (ResultSet rs = c.createStatement().executeQuery("SELECT id, name, code FROM characters ORDER BY id")) {
                while (rs.next()) index.putIfAbsent(rs.getString(3), rs.getLong(1));  // 먼저 실은 것이 이긴다
            }
            for (int i = 1; i <= nChars; i++) if (index.get("C-" + i) != null) n++;
            if (r >= 0) { b1[r] = System.nanoTime() - t; found2 = n; }
        }
        report("캐릭터 시트 — 코드로 캐릭터 찾기", nChars, 1, median(a1), median(b1), found1, found2);

        // ── ② 연표 시트: 행마다 코드 + 자연키(연도·설명) 두 번 ──
        PreparedStatement evByCode = c.prepareStatement("SELECT * FROM timeline_events WHERE code = ? LIMIT 1");
        PreparedStatement evByNat = c.prepareStatement("SELECT * FROM timeline_events WHERE year = ? AND description = ? LIMIT 1");
        long[] a2 = new long[REPS]; int f1 = 0;
        for (int r = -WARMUP; r < REPS; r++) {
            long t = System.nanoTime(); int n = 0;
            for (int i = 1; i <= nEvents; i++) {
                boolean hit = false;
                evByCode.setString(1, "E-" + i);
                try (ResultSet rs = evByCode.executeQuery()) { if (rs.next()) hit = true; }
                if (!hit) {                       // 코드가 없는 구버전 파일의 폴백 경로
                    evByNat.setInt(1, 1000); evByNat.setString(2, "사건 설명 " + i);
                    try (ResultSet rs = evByNat.executeQuery()) { if (rs.next()) hit = true; }
                }
                if (hit) n++;
            }
            if (r >= 0) { a2[r] = System.nanoTime() - t; f1 = n; }
        }
        long[] b2 = new long[REPS]; int f2 = 0;
        for (int r = -WARMUP; r < REPS; r++) {
            long t = System.nanoTime(); int n = 0;
            HashMap<String, Long> codes = new HashMap<>();
            HashMap<String, Long> nat = new HashMap<>();
            try (ResultSet rs = c.createStatement().executeQuery("SELECT id, year, description, code FROM timeline_events ORDER BY id")) {
                while (rs.next()) {
                    codes.putIfAbsent(rs.getString(4), rs.getLong(1));
                    nat.putIfAbsent(rs.getInt(2) + "\u0000" + rs.getString(3), rs.getLong(1));
                }
            }
            for (int i = 1; i <= nEvents; i++) {
                Long hit = codes.get("E-" + i);
                if (hit == null) hit = nat.get(1000 + "\u0000사건 설명 " + i);
                if (hit != null) n++;
            }
            if (r >= 0) { b2[r] = System.nanoTime() - t; f2 = n; }
        }
        report("연표 시트 — 코드 + 자연키 폴백", nEvents, 1, median(a2), median(b2), f1, f2);

        // ── ③ 상태변화 시트: 행마다 캐릭터 + 코드 + 자연키 ──
        PreparedStatement scByCode = c.prepareStatement("SELECT * FROM character_state_changes WHERE code = ? LIMIT 1");
        long[] a3 = new long[REPS]; int g1 = 0;
        for (int r = -WARMUP; r < REPS; r++) {
            long t = System.nanoTime(); int n = 0;
            for (int i = 1; i <= nState; i++) {
                byCode.setString(1, "C-" + (1 + (i % nChars)));
                try (ResultSet rs = byCode.executeQuery()) { if (rs.next()) n++; }
                scByCode.setString(1, "S-" + i);
                try (ResultSet rs = scByCode.executeQuery()) { if (rs.next()) n++; }
            }
            if (r >= 0) { a3[r] = System.nanoTime() - t; g1 = n; }
        }
        long[] b3 = new long[REPS]; int g2 = 0;
        for (int r = -WARMUP; r < REPS; r++) {
            long t = System.nanoTime(); int n = 0;
            HashMap<String, Long> chars = new HashMap<>(), scs = new HashMap<>();
            try (ResultSet rs = c.createStatement().executeQuery("SELECT id, code FROM characters ORDER BY id")) {
                while (rs.next()) chars.putIfAbsent(rs.getString(2), rs.getLong(1));
            }
            try (ResultSet rs = c.createStatement().executeQuery("SELECT id, code FROM character_state_changes ORDER BY id")) {
                while (rs.next()) scs.putIfAbsent(rs.getString(2), rs.getLong(1));
            }
            for (int i = 1; i <= nState; i++) {
                if (chars.get("C-" + (1 + (i % nChars))) != null) n++;
                if (scs.get("S-" + i) != null) n++;
            }
            if (r >= 0) { b3[r] = System.nanoTime() - t; g2 = n; }
        }
        report("상태변화 시트 — 캐릭터 + 자기 코드", nState, 2, median(a3), median(b3), g1, g2);

        // ── ④ 복원 미리보기의 캐릭터 시트: 행마다 작품을 **제목으로** 찾는다 (B-236) ──
        // 위 셋은 가져오기 쪽 축이고 이것은 **미리보기에만 남아 있던 축**이다.
        // 가져오기는 같은 조회를 `resolveNovelId`의 메모(제목,세계관 단위)로 이미 눌러 두었는데,
        // 미리보기의 `analysisNovelIdByTitle`에는 그 메모가 없어 **행마다** 쳤다.
        // 갈래가 둘인 것이 요점이다 — 세계관 안에서 못 찾으면 세계관을 가리지 않고 **한 번 더** 친다.
        int nNovels = NOVELS_1 * SCALE;
        st.executeUpdate("CREATE TABLE novels (id INTEGER PRIMARY KEY, title TEXT NOT NULL, universeId INTEGER)");
        PreparedStatement pn = c.prepareStatement("INSERT INTO novels VALUES (?,?,?)");
        for (int i = 1; i <= nNovels; i++) {
            pn.setLong(1, i); pn.setString(2, "작품" + i); pn.setLong(3, 1 + (i % 9));
            pn.addBatch(); if (i % 2000 == 0) pn.executeBatch();
        }
        pn.executeBatch(); pn.close();
        c.commit(); st.execute("ANALYZE"); c.commit();

        PreparedStatement nvInScope = c.prepareStatement(
            "SELECT * FROM novels WHERE title = ? AND universeId = ? LIMIT 1");
        PreparedStatement nvAnywhere = c.prepareStatement("SELECT * FROM novels WHERE title = ?");
        long[] a4 = new long[REPS]; int h1 = 0;
        for (int r = -WARMUP; r < REPS; r++) {
            long t = System.nanoTime(); int n = 0;
            for (int i = 1; i <= nChars; i++) {
                String title = "작품" + (1 + (i % nNovels));
                boolean hit = false;
                nvInScope.setString(1, title); nvInScope.setLong(2, 1 + (i % 9));
                try (ResultSet rs = nvInScope.executeQuery()) { if (rs.next()) hit = true; }
                if (!hit) {                       // 세계관을 가리지 않는 둘째 갈래
                    nvAnywhere.setString(1, title);
                    try (ResultSet rs = nvAnywhere.executeQuery()) { if (rs.next()) hit = true; }
                }
                if (hit) n++;
            }
            if (r >= 0) { a4[r] = System.nanoTime() - t; h1 = n; }
        }
        long[] b4 = new long[REPS]; int h2 = 0;
        for (int r = -WARMUP; r < REPS; r++) {
            long t = System.nanoTime(); int n = 0;
            HashMap<String, Long> scoped = new HashMap<>(), anywhere = new HashMap<>();
            try (ResultSet rs = c.createStatement().executeQuery("SELECT id, title, universeId FROM novels ORDER BY id")) {
                while (rs.next()) {
                    scoped.putIfAbsent(rs.getString(2) + "\u0000" + rs.getLong(3), rs.getLong(1));
                    anywhere.putIfAbsent(rs.getString(2), rs.getLong(1));
                }
            }
            for (int i = 1; i <= nChars; i++) {
                String title = "작품" + (1 + (i % nNovels));
                Long hit = scoped.get(title + "\u0000" + (1 + (i % 9)));
                if (hit == null) hit = anywhere.get(title);
                if (hit != null) n++;
            }
            if (r >= 0) { b4[r] = System.nanoTime() - t; h2 = n; }
        }
        report("미리보기 캐릭터 시트 — 제목으로 작품 찾기 (B-236)", nChars, 2, median(a4), median(b4), h1, h2);

        c.close();
    }

    static void report(String label, int rows, int perRow, long before, long after, int foundBefore, int foundAfter) {
        System.out.println("── " + label + " ──");
        System.out.printf("  행 %,d · 행마다 조회 %d회 → 종전 질의 %,d회%n", rows, perRow, rows * perRow);
        System.out.printf("  종전(행마다 조회) : %8.1f ms   찾은 수 %,d%n", before / 1e6, foundBefore);
        System.out.printf("  색인(표 한 번 읽기): %8.1f ms   찾은 수 %,d%n", after / 1e6, foundAfter);
        System.out.printf("  %s — %.0f배 빠르다%n",
            foundBefore == foundAfter ? "답이 같다 ✓" : "⚠ 답이 다르다 — 이 측정은 무효다",
            (double) before / after);
        System.out.println();
    }
}
