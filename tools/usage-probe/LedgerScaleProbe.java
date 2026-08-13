import java.sql.*;
import java.util.*;

/**
 * 가져오기 값 조회의 단위 실측 — B-72 ②. 조회 단위를 *값*에서 *캐릭터*로 내리면 얼마나 줄어드는가.
 *
 * <p>캐릭터 시트는 필드가 <b>열</b>이라 종전 조회 수가 {@code (행 × 필드 열)}이었고,
 * '캐릭터 필드값' 시트는 값 한 건이 한 행이라 <b>행당 한 번</b>이었다. 둘 다 조회 단위가
 * <b>값</b>이었는데, 값의 정체는 {@code (캐릭터, 필드)}이고 한 캐릭터의 값은 많아야 필드
 * 수만큼이라 <b>캐릭터</b>가 이 조회의 자연스러운 단위다.
 *
 * <p><b>시간과 함께 '찾은 값 수'를 낸다</b> — 빨라진 것만 보고 <b>같은 답을 내는지</b>를 안 보면
 * 이 프로브가 증명하는 것이 없다.
 *
 * <h2>돌리는 법</h2>
 *
 * <pre>
 *   curl -sSfLo /tmp/jars/sqlite-jdbc.jar https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.3.0/sqlite-jdbc-3.45.3.0.jar
 *   curl -sSfLo /tmp/jars/slf4j-api.jar    https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
 *   curl -sSfLo /tmp/jars/slf4j-nop.jar    https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar
 *   javac -encoding UTF-8 tools/usage-probe/LedgerScaleProbe.java -d /tmp/probe-out
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
 *        -cp /tmp/probe-out:/tmp/jars/sqlite-jdbc.jar:/tmp/jars/slf4j-api.jar:/tmp/jars/slf4j-nop.jar \
 *        -Dscale=30 LedgerScaleProbe
 * </pre>
 */
public class LedgerScaleProbe {
    static final int SCALE = Integer.parseInt(System.getProperty("scale", "30"));
    static final int CHARS_1 = 214, VALUES_1 = 1217;
    static final int FIELDS = 90;                       // 실사용 표본의 커스텀 필드 정의 수(고정)
    static long seed = 20260813L;
    static int rnd(int n) { seed = seed * 6364136223846793005L + 1442695040888963407L; return (int)((seed >>> 33) % n); }

    public static void main(String[] args) throws Exception {
        int nChars = CHARS_1 * SCALE, nValues = VALUES_1 * SCALE;
        Class.forName("org.sqlite.JDBC");
        new java.io.File("/tmp/ledger-scale-probe.db").delete();
        new java.io.File("/tmp/ledger-scale-probe.db-wal").delete();
        Connection c = DriverManager.getConnection("jdbc:sqlite:/tmp/ledger-scale-probe.db");
        c.setAutoCommit(false);
        Statement st = c.createStatement();
        st.executeUpdate("CREATE TABLE character_field_values (id INTEGER PRIMARY KEY, characterId INTEGER NOT NULL, fieldDefinitionId INTEGER NOT NULL, value TEXT NOT NULL)");
        st.executeUpdate("CREATE INDEX idx_cfv_char ON character_field_values(characterId)");
        st.executeUpdate("CREATE INDEX idx_cfv_fd ON character_field_values(fieldDefinitionId)");
        st.executeUpdate("CREATE UNIQUE INDEX idx_cfv_pair ON character_field_values(characterId, fieldDefinitionId)");
        PreparedStatement pv = c.prepareStatement("INSERT OR IGNORE INTO character_field_values VALUES (?,?,?,?)");
        for (int i = 1; i <= nValues; i++) {
            pv.setLong(1, i); pv.setLong(2, 1 + rnd(nChars)); pv.setLong(3, 1 + rnd(FIELDS)); pv.setString(4, "값" + rnd(500));
            pv.addBatch(); if (i % 2000 == 0) pv.executeBatch();
        }
        pv.executeBatch(); pv.close(); c.commit();
        st.execute("ANALYZE"); c.commit();

        System.out.println("=== 규모 ×" + SCALE + " : 캐릭터 " + nChars + " · 필드값 " + nValues + " · 필드 정의 " + FIELDS + " ===");

        // ── 캐릭터 시트: 종전 = 셀마다 getValue ──
        PreparedStatement one = c.prepareStatement("SELECT * FROM character_field_values WHERE characterId = ? AND fieldDefinitionId = ?");
        long t0 = System.nanoTime(); int n1 = 0;
        for (long ch = 1; ch <= nChars; ch++) {
            for (long f = 1; f <= FIELDS; f++) {
                one.setLong(1, ch); one.setLong(2, f);
                ResultSet rs = one.executeQuery(); while (rs.next()) n1++; rs.close();
            }
        }
        long msOld = (System.nanoTime() - t0) / 1_000_000;
        one.close();
        System.out.printf("  캐릭터 시트 — 종전(셀당 조회): %,d회 · %,d ms%n", (long) nChars * FIELDS, msOld);

        // ── 캐릭터 시트: 이후 = 캐릭터마다 한 번 ──
        PreparedStatement all = c.prepareStatement("SELECT * FROM character_field_values WHERE characterId = ?");
        t0 = System.nanoTime(); int n2 = 0;
        for (long ch = 1; ch <= nChars; ch++) {
            all.setLong(1, ch);
            ResultSet rs = all.executeQuery();
            HashMap<Long, String> map = new HashMap<>();
            while (rs.next()) { map.put(rs.getLong("fieldDefinitionId"), rs.getString("value")); }
            rs.close();
            for (long f = 1; f <= FIELDS; f++) if (map.get(f) != null) n2++;
        }
        long msNew = (System.nanoTime() - t0) / 1_000_000;
        all.close();
        System.out.printf("  캐릭터 시트 — 이후(캐릭터당 조회): %,d회 · %,d ms   →  %.1f배 빠르다%n",
            (long) nChars, msNew, msNew == 0 ? 0 : (double) msOld / msNew);
        System.out.println("  (찾은 값 수가 같은가: " + n1 + " vs " + n2 + " — " + (n1 == n2 ? "같다" : "★다르다★") + ")");

        // ── '캐릭터 필드값' 시트: 행당 조회 → 캐릭터당 조회 ──
        // 그 시트는 값 한 건이 한 행이라 조회 수가 곧 값 수다.
        PreparedStatement one2 = c.prepareStatement("SELECT * FROM character_field_values WHERE characterId = ? AND fieldDefinitionId = ?");
        ResultSet rows = st.executeQuery("SELECT characterId, fieldDefinitionId FROM character_field_values ORDER BY characterId");
        List<long[]> pairs = new ArrayList<>();
        while (rows.next()) pairs.add(new long[]{rows.getLong(1), rows.getLong(2)});
        rows.close();
        t0 = System.nanoTime();
        for (long[] p : pairs) { one2.setLong(1, p[0]); one2.setLong(2, p[1]); ResultSet rs = one2.executeQuery(); while (rs.next()) { } rs.close(); }
        long msOld2 = (System.nanoTime() - t0) / 1_000_000;
        one2.close();
        PreparedStatement all2 = c.prepareStatement("SELECT * FROM character_field_values WHERE characterId = ?");
        t0 = System.nanoTime();
        HashSet<Long> loaded = new HashSet<>();
        for (long[] p : pairs) {
            if (loaded.add(p[0])) { all2.setLong(1, p[0]); ResultSet rs = all2.executeQuery(); while (rs.next()) { } rs.close(); }
        }
        long msNew2 = (System.nanoTime() - t0) / 1_000_000;
        all2.close();
        System.out.printf("  '캐릭터 필드값' 시트 — 종전 %,d회 %,d ms  →  이후 %,d회 %,d ms  (%.1f배)%n",
            pairs.size(), msOld2, loaded.size(), msNew2, msNew2 == 0 ? 0 : (double) msOld2 / msNew2);
        st.close(); c.close();
    }
}
