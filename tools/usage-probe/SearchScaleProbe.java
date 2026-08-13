import java.sql.*;
import java.util.*;

/**
 * 전역 검색의 규모 실측 — B-10(검색 인프라)이 여는 갈래 넷을 **실제 SQLite**로 잰다.
 *
 * <h2>왜 자바이고 왜 이 폴더인가</h2>
 *
 * 형제 프로브(`ScaleProbe.kt`·`RecountProbe.kt`)는 순수 계산을 재므로 코틀린으로 충분하지만,
 * 이 물음은 <b>"SQLite가 이 질의를 어떻게 도는가"</b>라 **진짜 SQLite가 있어야 한다.**
 * 안드로이드 계층은 이 환경에서 못 돌지만(2장 — `dl.google.com` 차단) SQLite 자체는
 * `org.xerial:sqlite-jdbc`로 그대로 돌릴 수 있고, 그 안의 SQLite는 기기의 것과 같은 엔진이다.
 * 재는 것이 <b>비율</b>(어느 갈래가 몇 배인가)이라 데스크톱·기기의 절대 속도 차이가 답을 바꾸지 않는다.
 *
 * <h2>돌리는 법</h2>
 *
 * <pre>
 *   curl -sSfLo /tmp/jars/sqlite-jdbc.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.3.0/sqlite-jdbc-3.45.3.0.jar
 *   curl -sSfLo /tmp/jars/slf4j-api.jar    https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
 *   curl -sSfLo /tmp/jars/slf4j-nop.jar    https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar
 *   javac -encoding UTF-8 tools/usage-probe/SearchScaleProbe.java -d /tmp/probe-out
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
 *        -cp /tmp/probe-out:/tmp/jars/sqlite-jdbc.jar:/tmp/jars/slf4j-api.jar:/tmp/jars/slf4j-nop.jar \
 *        -Dscale=30 SearchScaleProbe
 * </pre>
 *
 * `-Dscale`은 실사용 표본의 배수다(scalability 2장 — 목표는 ×30, 그 표가 "상한이 아니라 하한"이라
 * 적었으므로 ×100·×300도 함께 볼 것).
 *
 * <h2>무엇을 재는가</h2>
 *
 * <ol>
 *   <li><b>ⓐ 현행</b> — `LIKE '%q%'` 풀스캔 넷(`CharacterDao:31`·`TimelineDao:40`·`NovelDao:61`·
 *       `CharacterFieldValueDao:122`)</li>
 *   <li><b>ⓑ value 인덱스</b> — B-10이 든 갈래 하나. 선행 와일드카드에서 <b>쓰이는가</b></li>
 *   <li><b>ⓒ FTS4 그림자 표</b> — 나머지 갈래. <b>낱말 토크나이저와 bigram 두 벌</b>을 재는 것이
 *       이 프로브의 요점이다: 앞은 오늘 되는 검색을 <b>조용히 잃고</b>, 뒤는 그것을 되찾는 대신
 *       쓰기 비용을 문다</li>
 *   <li><b>유지 비용</b> — 트리거가 도는 자리(대량 가져오기). 읽기만 재고 쓰기를 안 재면
 *       FTS는 언제나 이긴다</li>
 * </ol>
 */
public class SearchScaleProbe {

    static final int SCALE = Integer.parseInt(System.getProperty("scale", "30"));
    /** 실사용 표본 ×1 — scalability 2장의 표 그대로. */
    static final int CHARS_1 = 214, VALUES_1 = 1217, EVENTS_1 = 137, NOVELS_1 = 17;

    static final String[] SURNAMES = {"김","이","박","최","정","강","조","윤","장","임","한","오","서","신","권","황","안","송","전","홍"};
    static final String[] GIVEN = {"레온","서준","하윤","도현","지우","민수","예린","태오","시아","건우","라온","해린","윤슬","가온","다인","단우","여울","하람","노을","미르"};
    static final String[] WORDS = {"붉은","머리카락","푸른","눈동자","기사단","수도","북부","남부","마법사","용병","상인","귀족","평민","사제","암살자","왕국","제국","변방","항구","설원","사막","숲","폐허","성채","길드","반란","전쟁","맹세","저주","축복"};

    /** 결정적 난수 — 같은 seed면 같은 표본이라 두 실행을 견줄 수 있다. */
    static long seed = 20260813L;
    static int rnd(int n) { seed = seed * 6364136223846793005L + 1442695040888963407L; return (int) ((seed >>> 33) % n); }

    static final String DB = "/tmp/search-scale-probe.db";

    public static void main(String[] args) throws Exception {
        int nChars = CHARS_1 * SCALE, nValues = VALUES_1 * SCALE, nEvents = EVENTS_1 * SCALE, nNovels = NOVELS_1 * SCALE;
        System.out.println("=== 규모 ×" + SCALE + " : 캐릭터 " + nChars + " · 필드값 " + nValues
            + " · 사건 " + nEvents + " · 작품 " + nNovels + " ===");

        Class.forName("org.sqlite.JDBC");
        for (String suffix : new String[]{"", "-wal", "-shm"}) new java.io.File(DB + suffix).delete();
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB);
        try (Statement p = c.createStatement()) { p.execute("PRAGMA journal_mode=WAL"); }
        c.setAutoCommit(false);
        Statement st = c.createStatement();
        build(c, st, nChars, nValues, nEvents, nNovels);

        // 사용자가 실제로 치는 모양 — 낱말 전체가 아니라 조각이 섞여 있다.
        String[] qs = {"레온", "머리", "리카", "북부", "기사", "설원", "가온", "윤슬"};

        System.out.println("\n── ⓐ 현행: LIKE '%q%' 풀스캔 ──");
        String qChar = "SELECT * FROM characters WHERE name LIKE '%'||?||'%' ESCAPE '\\' OR anotherName LIKE '%'||?||'%' ESCAPE '\\' OR firstName LIKE '%'||?||'%' ESCAPE '\\' OR lastName LIKE '%'||?||'%' ESCAPE '\\'";
        String qEvent = "SELECT * FROM timeline_events WHERE description LIKE '%'||?||'%' ESCAPE '\\' ORDER BY year ASC, month ASC, day ASC, displayOrder ASC";
        String qNovel = "SELECT * FROM novels WHERE title LIKE '%'||?||'%' ESCAPE '\\' OR description LIKE '%'||?||'%' ESCAPE '\\'";
        String qFv = "SELECT DISTINCT characterId FROM character_field_values WHERE fieldDefinitionId = 7 AND value LIKE '%'||?||'%' ESCAPE '\\'";
        double tc = time(c, qChar, qs, 4), te = time(c, qEvent, qs, 1), tn = time(c, qNovel, qs, 2);
        System.out.printf("  캐릭터 %.2f · 사건 %.2f · 작품 %.2f ms  →  전역 검색 1회 합계 %.2f ms%n", tc, te, tn, tc + te + tn);
        System.out.printf("  (필터) 필드값 contains %.2f ms%n", time(c, qFv, qs, 1));

        System.out.println("\n── ⓑ character_field_values.value 인덱스 ──");
        st.execute("CREATE INDEX idx_cfv_value ON character_field_values(value)");
        c.commit();
        System.out.printf("  필드값 contains %.2f ms  ", time(c, qFv, qs, 1));
        plan(c, "EXPLAIN QUERY PLAN " + qFv, 1);
        st.execute("DROP INDEX idx_cfv_value"); c.commit();

        System.out.println("\n── ⓒ-1 FTS4 낱말 토크나이저: 무엇을 잃는가 ──");
        semantics(c);

        System.out.println("\n── ⓒ-2 FTS4 bigram 그림자 표 ──");
        fts(c, st, qs);

        System.out.println("\n── 유지 비용: 필드값 1,000행 삽입 ──");
        maintenance(c, st, nValues, nChars);

        st.close();
        c.close();
    }

    // ─────────────────────────── 표본 조립 ───────────────────────────

    static void build(Connection c, Statement st, int nChars, int nValues, int nEvents, int nNovels) throws Exception {
        st.executeUpdate("CREATE TABLE characters (id INTEGER PRIMARY KEY, name TEXT NOT NULL, firstName TEXT NOT NULL, lastName TEXT NOT NULL, anotherName TEXT NOT NULL, novelId INTEGER, imagePaths TEXT NOT NULL, representativeImagePath TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, memo TEXT NOT NULL, code TEXT NOT NULL, displayOrder INTEGER NOT NULL, isPinned INTEGER NOT NULL)");
        st.executeUpdate("CREATE INDEX idx_char_name ON characters(name)");
        st.executeUpdate("CREATE TABLE timeline_events (id INTEGER PRIMARY KEY, description TEXT NOT NULL, year INTEGER, month INTEGER, day INTEGER, displayOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)");
        st.executeUpdate("CREATE TABLE novels (id INTEGER PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL, createdAt INTEGER NOT NULL)");
        st.executeUpdate("CREATE TABLE character_field_values (id INTEGER PRIMARY KEY, characterId INTEGER NOT NULL, fieldDefinitionId INTEGER NOT NULL, value TEXT NOT NULL)");
        st.executeUpdate("CREATE INDEX idx_cfv_char ON character_field_values(characterId)");
        st.executeUpdate("CREATE INDEX idx_cfv_fd ON character_field_values(fieldDefinitionId)");

        try (PreparedStatement pc = c.prepareStatement("INSERT INTO characters VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 1; i <= nChars; i++) {
                String sur = SURNAMES[rnd(SURNAMES.length)];
                String giv = GIVEN[rnd(GIVEN.length)] + (rnd(4) == 0 ? GIVEN[rnd(GIVEN.length)] : "");
                pc.setLong(1, i); pc.setString(2, sur + giv); pc.setString(3, giv); pc.setString(4, sur);
                pc.setString(5, rnd(3) == 0 ? WORDS[rnd(WORDS.length)] + "의 " + WORDS[rnd(WORDS.length)] : "");
                pc.setLong(6, 1 + rnd(nNovels)); pc.setString(7, "[]"); pc.setString(8, "");
                pc.setLong(9, 0); pc.setLong(10, 0);
                StringBuilder memo = new StringBuilder();
                for (int k = 0; k < 60; k++) memo.append(WORDS[rnd(WORDS.length)]).append(' ');  // 메모 ~200자
                pc.setString(11, memo.toString()); pc.setString(12, "C" + i); pc.setLong(13, i); pc.setInt(14, 0);
                pc.addBatch(); if (i % 2000 == 0) pc.executeBatch();
            }
            pc.executeBatch();
        }
        try (PreparedStatement pe = c.prepareStatement("INSERT INTO timeline_events VALUES (?,?,?,?,?,?,?)")) {
            for (int i = 1; i <= nEvents; i++) {
                StringBuilder d = new StringBuilder();
                for (int k = 0; k < 12; k++) d.append(WORDS[rnd(WORDS.length)]).append(' ');
                pe.setLong(1, i); pe.setString(2, d.toString()); pe.setInt(3, rnd(500)); pe.setInt(4, 1 + rnd(12));
                pe.setInt(5, 1 + rnd(28)); pe.setLong(6, i); pe.setLong(7, 0);
                pe.addBatch(); if (i % 2000 == 0) pe.executeBatch();
            }
            pe.executeBatch();
        }
        try (PreparedStatement pn = c.prepareStatement("INSERT INTO novels VALUES (?,?,?,?)")) {
            for (int i = 1; i <= nNovels; i++) {
                pn.setLong(1, i); pn.setString(2, WORDS[rnd(WORDS.length)] + "의 " + WORDS[rnd(WORDS.length)]);
                StringBuilder d = new StringBuilder();
                for (int k = 0; k < 20; k++) d.append(WORDS[rnd(WORDS.length)]).append(' ');
                pn.setString(3, d.toString()); pn.setLong(4, 0);
                pn.addBatch();
            }
            pn.executeBatch();
        }
        try (PreparedStatement pv = c.prepareStatement("INSERT INTO character_field_values VALUES (?,?,?,?)")) {
            for (int i = 1; i <= nValues; i++) {
                pv.setLong(1, i); pv.setLong(2, 1 + rnd(nChars)); pv.setLong(3, 1 + rnd(90));
                pv.setString(4, rnd(5) == 0
                    ? WORDS[rnd(WORDS.length)] + " " + WORDS[rnd(WORDS.length)] + " " + WORDS[rnd(WORDS.length)]
                    : WORDS[rnd(WORDS.length)]);
                pv.addBatch(); if (i % 2000 == 0) pv.executeBatch();
            }
            pv.executeBatch();
        }
        c.commit();
        st.execute("ANALYZE"); c.commit();
        System.out.println("DB " + (new java.io.File(DB).length() / 1024) + " KB");
    }

    // ─────────────────────────── 갈래별 측정 ───────────────────────────

    /**
     * FTS4의 **낱말 토크나이저**가 오늘의 `LIKE '%q%'`와 같은 답을 내는가.
     * 이 표가 이 프로브에서 가장 중요하다 — 시간이 아니라 **결과가 갈리는지**를 보기 때문이다.
     */
    static void semantics(Connection c) throws Exception {
        Statement st = c.createStatement();
        st.execute("CREATE TABLE sem (id INTEGER PRIMARY KEY, s TEXT)");
        st.execute("CREATE VIRTUAL TABLE sem_fts USING fts4(s, tokenize=simple)");
        String[] rows = {"김레온", "레온하르트", "붉은 머리카락", "북부 기사단장", "Alexander", "설원의 늑대"};
        for (int i = 0; i < rows.length; i++) {
            st.executeUpdate("INSERT INTO sem VALUES (" + (i + 1) + ", '" + rows[i] + "')");
            st.executeUpdate("INSERT INTO sem_fts(docid, s) VALUES (" + (i + 1) + ", '" + rows[i] + "')");
        }
        c.commit();
        System.out.printf("  %-7s %-24s %-20s %-20s%n", "검색어", "오늘 LIKE '%q%'", "FTS4  q", "FTS4  q*");
        for (String q : new String[]{"레온", "머리", "리카", "기사", "exand", "늑대"}) {
            System.out.printf("  %-7s %-24s %-20s %-20s%n", q,
                rows(c, "SELECT s FROM sem WHERE s LIKE '%" + q + "%'"),
                rows(c, "SELECT s FROM sem_fts WHERE sem_fts MATCH '" + q + "'"),
                rows(c, "SELECT s FROM sem_fts WHERE sem_fts MATCH '" + q + "*'"));
        }
        System.out.println("  ※ 낱말 토크나이저는 '레온'으로 김레온을, '리카'로 머리카락을 못 찾는다 —");
        System.out.println("     오늘 되는 검색이 조용히 사라진다(개발 의도 2번). bigram이 그것을 되찾는다.");
        st.execute("DROP TABLE sem_fts"); st.execute("DROP TABLE sem"); c.commit();
        st.close();
    }

    static void fts(Connection c, Statement st, String[] qs) throws Exception {
        long t0 = System.nanoTime();
        st.execute("CREATE VIRTUAL TABLE search_fts USING fts4(body, tokenize=simple)");
        List<String> bodies = new ArrayList<>();
        for (String sql : new String[]{
            "SELECT name || ' ' || firstName || ' ' || lastName || ' ' || anotherName FROM characters",
            "SELECT description FROM timeline_events",
            "SELECT title || ' ' || description FROM novels",
            "SELECT value FROM character_field_values"}) {
            try (ResultSet rs = st.executeQuery(sql)) { while (rs.next()) bodies.add(grams(rs.getString(1))); }
        }
        try (PreparedStatement pf = c.prepareStatement("INSERT INTO search_fts(docid, body) VALUES (?,?)")) {
            for (int i = 0; i < bodies.size(); i++) {
                pf.setLong(1, i + 1); pf.setString(2, bodies.get(i)); pf.addBatch();
                if (i % 2000 == 0) pf.executeBatch();
            }
            pf.executeBatch();
        }
        c.commit();
        System.out.println("  전체 색인 세우기: " + ((System.nanoTime() - t0) / 1_000_000) + " ms · DB "
            + (new java.io.File(DB).length() / 1024) + " KB(증가분 포함) · 문서 " + bodies.size() + "건");
        double t = 0;
        for (String q : qs) {
            long s0 = System.nanoTime();
            try (PreparedStatement ps = c.prepareStatement("SELECT docid FROM search_fts WHERE search_fts MATCH ?")) {
                ps.setString(1, String.join(" ", gramList(q)));
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { } }
            }
            t += (System.nanoTime() - s0) / 1e6;
        }
        System.out.printf("  MATCH(bigram) 전체 문서 1회: %.2f ms%n", t / qs.length);
    }

    static void maintenance(Connection c, Statement st, int nValues, int nChars) throws Exception {
        long t0 = System.nanoTime();
        try (PreparedStatement p = c.prepareStatement("INSERT INTO character_field_values VALUES (?,?,?,?)")) {
            for (int i = 1; i <= 1000; i++) {
                p.setLong(1, nValues + i); p.setLong(2, 1 + rnd(nChars)); p.setLong(3, 1 + rnd(90));
                p.setString(4, WORDS[rnd(WORDS.length)]); p.addBatch();
            }
            p.executeBatch();
        }
        c.commit();
        long plain = (System.nanoTime() - t0) / 1_000_000;

        // 트리거가 bigram을 스스로 편다 — 재귀 CTE로 실제로 된다(SQL 내장 함수만으로 가능).
        st.execute("CREATE TRIGGER cfv_ai AFTER INSERT ON character_field_values BEGIN "
            + "INSERT INTO search_fts(docid, body) SELECT new.id + 100000000, group_concat(sub, ' ') FROM ("
            + "  WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < length(new.value)-1) "
            + "  SELECT substr(new.value, i, 2) AS sub FROM n); END");
        c.commit();
        t0 = System.nanoTime();
        try (PreparedStatement p = c.prepareStatement("INSERT INTO character_field_values VALUES (?,?,?,?)")) {
            for (int i = 1; i <= 1000; i++) {
                p.setLong(1, nValues + 1000 + i); p.setLong(2, 1 + rnd(nChars)); p.setLong(3, 1 + rnd(90));
                p.setString(4, WORDS[rnd(WORDS.length)]); p.addBatch();
            }
            p.executeBatch();
        }
        c.commit();
        long withFts = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("  색인 없이 %d ms  →  FTS bigram 트리거 달고 %d ms  (%.1f배)%n",
            plain, withFts, plain == 0 ? 0 : (double) withFts / plain);
        System.out.println("  ※ 이 벌금은 가져오기만이 아니라 **모든 쓰기**가 문다 — 캐릭터 저장·필드 편집 포함.");
    }

    // ─────────────────────────── 거들기 ───────────────────────────

    /** 문자열 → 공백 구분 bigram 열(1글자 토큰은 그대로). */
    static String grams(String s) {
        StringBuilder sb = new StringBuilder();
        for (String tok : s.split("[\\s,·/]+")) {
            if (tok.isEmpty()) continue;
            if (tok.length() == 1) { sb.append(tok).append(' '); continue; }
            for (int i = 0; i + 2 <= tok.length(); i++) sb.append(tok, i, i + 2).append(' ');
        }
        return sb.toString();
    }

    static List<String> gramList(String q) {
        List<String> out = new ArrayList<>();
        if (q.length() == 1) { out.add(q); return out; }
        for (int i = 0; i + 2 <= q.length(); i++) out.add(q.substring(i, i + 2));
        return out;
    }

    static String rows(Connection c, String sql) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) { if (sb.length() > 0) sb.append(","); sb.append(rs.getString(1)); }
            return sb.length() == 0 ? "—(없음)" : sb.toString();
        } catch (Exception e) { return "ERR:" + e.getMessage(); }
    }

    static void plan(Connection c, String sql, int binds) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 1; i <= binds; i++) ps.setString(i, "가");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) System.out.print("[" + rs.getString(rs.getMetaData().getColumnCount()) + "]");
            }
        }
        System.out.println();
    }

    /** 세 번 돌리고 마지막만 센다 — 첫 실행의 페이지 적재를 재지 않기 위해서다. */
    static double time(Connection c, String sql, String[] qs, int binds) throws Exception {
        double total = 0;
        for (int rep = 0; rep < 3; rep++) {
            for (String q : qs) {
                long s0 = System.nanoTime();
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 1; i <= binds; i++) ps.setString(i, q);
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { } }
                }
                if (rep == 2) total += (System.nanoTime() - s0) / 1e6;
            }
        }
        return total / qs.length;
    }
}
