#!/usr/bin/env python3
"""
Room 마이그레이션 51→52 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: 대결 축의 **프로필 필드**(B-122) — `duel_axes.profileFieldKeys`.
설계 정본은 `docs/feature_roadmap_2026-08.md` 3장이다.

**이 판에서 값어치가 가장 큰 검사는 "기존 축이 `[]`가 되는가"다.** 엔티티가 non-null이므로
DEFAULT 없이 칸을 더하면 기존 행이 NULL이 되고, Room은 시작 시 그 어긋남을 거부한다 —
사용자 쪽에서는 **업데이트 직후 앱이 안 뜨는 모양**이고 되돌릴 길이 없다. 형제 칸
`influenceFieldKeys`(v50)가 같은 이유로 `DEFAULT '[]'`를 달고 있으며, 그 판단이 이번에도
같은지를 실제 스키마로 잰다.

**함께 재는 것 하나 더 — 쌓인 판이 살아남는가.** 이 표는 축이 죽으면 판이 FK CASCADE로
함께 죽는 구조라(설계 1장), 칸을 더하다가 표를 재작성하면 **수만 판이 조용히 사라진다.**
ADD COLUMN이 표를 재작성하지 않는다는 사실을 문장 검사로만 두지 않고 **판을 실제로 넣어 두고
마이그레이션을 돌려** 확인한다.

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
    v51 대결 표 셋도 그 표를 만든 블록(48→49)과 이후 ALTER(49→50)에서 추출한다.
 2. 마이그레이션 후 컬럼 집합이 **엔티티가 선언한 것**과 일치하는가.
 3. 기존 축이 살아남고 새 칸이 `[]`가 되는가 — *"프로필 없음"*이 옛 축의 사실이다.
 4. 인덱스(이름 유니크·코드 유니크)가 그대로인가.
 5. 쌓인 판·처분이 그대로인가 — 표를 재작성하지 않았다는 실증.
"""
import os
import re
import sqlite3

REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"

FAILS = []
CHECKS = 0

AXIS_COLUMNS = {
    "id", "universeId", "name", "targetType", "displayOrder", "createdAt",
    "influenceFieldKeys", "outcomeFieldKeys", "profileFieldKeys", "code",
}

V51_UNIVERSES = """
CREATE TABLE IF NOT EXISTS universes (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL,
    code TEXT NOT NULL
)
"""


def check(cond, msg):
    global CHECKS
    CHECKS += 1
    if not cond:
        FAILS.append(msg)
        print(f"  ✗ {msg}")
    else:
        print(f"  ✓ {msg}")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def db_version_at_least(src, minimum):
    """버전은 '이상'으로 본다 — 못 박으면 다음 스키마 상향이 이 하네스를 상시 실패로 만든다."""
    m = re.search(r"version\s*=\s*(\d+)", src)
    return m is not None and int(m.group(1)) >= minimum


def extract_migration_sql(src, name):
    """`private val <name> = object : Migration(` 블록의 execSQL 인자를 순서대로 뽑는다.

    경계는 **다음 마이그레이션 블록**이다 — 뒤에 53이 들어와도 그것까지 추출하지 않는다.
    """
    start = src.index(f"private val {name} = object : Migration(")
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase(", start)
    block = src[start:end]
    triple = re.findall(r'execSQL\(\s*"""(.*?)"""\s*\)', block, re.S)
    single = re.findall(r'execSQL\(\s*"([^"\n]+)"\s*\)', block)
    return triple + single


def indices_of(con, table):
    return {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", (table,))}


def main():
    src = read(APPDB)
    axis_src = read(f"{MODEL}/DuelAxis.kt")

    print("=" * 70)
    print("Room 마이그레이션 51→52 검증 — 대결 축의 프로필 필드 (B-122)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 52), "@Database(version)이 52 이상이다")
    check(re.search(r"addMigrations\([^)]*MIGRATION_51_52", src, re.S) is not None,
          "MIGRATION_51_52가 addMigrations에 등록됐다 (빠뜨리면 실행 시 IllegalStateException)")
    check('val profileFieldKeys: String = "[]"' in axis_src,
          "엔티티가 profileFieldKeys를 기본값 '[]'로 선언했다")
    check("profiles = DuelFieldLinks.decode(profileFieldKeys)" in axis_src,
          "엔티티가 해석을 DuelFieldLinks에 맡긴다 (두 곳에서 파싱하면 규칙이 갈린다)")

    stmts = extract_migration_sql(src, "MIGRATION_51_52")
    joined = " ".join(stmts)
    check(len(stmts) == 1, f"칸 하나를 더하는 1문이다 (추출 {len(stmts)}문)")
    check(joined.upper().count("ALTER TABLE") == 1, "ALTER TABLE ADD COLUMN 하나다")
    check("DROP TABLE" not in joined.upper() and "CREATE TABLE" not in joined.upper(),
          "표를 재작성하지 않는 순수 추가다 (재작성하면 인덱스·FK를 다시 세워야 한다)")
    check("DEFAULT '[]'" in joined,
          "기본값 '[]'를 준다 — 없으면 기존 행이 NULL이 되어 Room이 시작 시 거부한다")
    check("NOT NULL" in joined.upper(),
          "NOT NULL이다 — 엔티티가 non-null이라 걸지 않으면 Room이 시작 시 거부한다")

    print("\n[2] v51 스키마를 실제로 세우고 마이그레이션을 돌린다")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys = ON")
    con.execute(V51_UNIVERSES)
    con.execute("INSERT INTO universes(id, name, code) VALUES (1, '아르카나', 'UNI-1')")
    # v51의 대결 표 셋은 **48→49 블록에서 그대로 가져온다**(SQL 이중화 금지).
    for s in extract_migration_sql(src, "MIGRATION_48_49"):
        con.execute(s)
    # 층 C의 연결 칸 둘(49→50)도 그 블록에서 가져온다 — v51은 그것까지 든 상태다.
    for s in extract_migration_sql(src, "MIGRATION_49_50"):
        con.execute(s)

    con.execute(
        "INSERT INTO duel_axes(id, universeId, name, targetType, displayOrder, createdAt, code,"
        " influenceFieldKeys, outcomeFieldKeys)"
        " VALUES (1, 1, '강함', 'character', 0, 100, 'DAX-1', '[\"mana\"]', '[\"power\"]')")
    con.execute(
        "INSERT INTO duel_matches(id, axisId, aCode, bCode, winnerCode, groupId, decidedAt, code)"
        " VALUES (1, 1, 'CHR-1', 'CHR-2', 'CHR-1', NULL, 200, 'DMT-1')")
    # 무승부 판 — B-114가 화면을 여는 그 형식이 v51에서도 이미 담긴다는 실증이기도 하다.
    con.execute(
        "INSERT INTO duel_matches(id, axisId, aCode, bCode, winnerCode, groupId, decidedAt, code)"
        " VALUES (2, 1, 'CHR-1', 'CHR-3', NULL, NULL, 210, 'DMT-2')")
    con.execute(
        "INSERT INTO duel_counter_verdicts(id, axisId, kind, shape, memberCodes, memberKey,"
        " decidedAt, code)"
        " VALUES (1, 1, 'undecided', 'direct', '[\"CHR-1\",\"CHR-2\"]', '[\"CHR-1\",\"CHR-2\"]',"
        " 300, 'DCV-1')")

    before_axis = indices_of(con, "duel_axes")
    before_match = indices_of(con, "duel_matches")
    try:
        for s in stmts:
            con.execute(s)
        ran = True
    except Exception as e:  # noqa: BLE001
        ran = False
        print(f"    실행 실패: {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[3] 스키마가 Room 기대와 일치하는가")
    cols = {r[1]: r for r in con.execute("PRAGMA table_info(`duel_axes`)")}
    check(set(cols) == AXIS_COLUMNS, f"duel_axes 컬럼이 엔티티와 같다 (실제: {sorted(cols)})")
    check(cols["profileFieldKeys"][3] == 1,
          "profileFieldKeys가 NOT NULL이다 (엔티티가 non-null — 어긋나면 앱이 시작을 거부한다)")
    check(cols["profileFieldKeys"][2].upper() == "TEXT", "profileFieldKeys가 TEXT다")

    print("\n[4] 기존 축이 그대로인가 — 이 판에서 가장 값어치가 큰 검사")
    row = con.execute(
        "SELECT name, influenceFieldKeys, outcomeFieldKeys, profileFieldKeys"
        " FROM duel_axes WHERE id = 1").fetchone()
    check(row is not None and row[0] == "강함", "마이그레이션 전에 있던 축이 그대로다")
    check(row is not None and row[1] == '["mana"]' and row[2] == '["power"]',
          "층 C의 연결 둘이 손상되지 않았다")
    check(row is not None and row[3] == "[]",
          "기존 축의 새 칸이 '[]'다 — NULL이면 Room이 시작 시 거부한다")

    print("\n[5] 인덱스와 쌓인 것들")
    check(indices_of(con, "duel_axes") == before_axis,
          "축의 인덱스(이름·코드 유니크)가 그대로다 (ADD COLUMN은 표를 재작성하지 않는다)")
    check(indices_of(con, "duel_matches") == before_match, "판의 인덱스가 그대로다")
    matches = con.execute("SELECT COUNT(*) FROM duel_matches WHERE axisId = 1").fetchone()[0]
    check(matches == 2, f"쌓인 판이 그대로다 (실제 {matches}건) — 표를 재작성했다면 함께 사라졌다")
    draw = con.execute(
        "SELECT winnerCode FROM duel_matches WHERE code = 'DMT-2'").fetchone()
    check(draw is not None and draw[0] is None, "무승부 판의 승자 없음(NULL)이 보존된다")
    verdicts = con.execute("SELECT COUNT(*) FROM duel_counter_verdicts WHERE axisId = 1").fetchone()[0]
    check(verdicts == 1, "층 B의 처분이 그대로다")

    print("\n[6] 새 칸에 실제로 쓰고 읽는다")
    con.execute(
        "UPDATE duel_axes SET profileFieldKeys = '[\"faction\",\"origin\"]' WHERE id = 1")
    row = con.execute("SELECT profileFieldKeys FROM duel_axes WHERE id = 1").fetchone()
    check(row is not None and row[0] == '["faction","origin"]',
          "프로필 연결이 **차례 그대로** 저장된다 (차례가 표시 차례다)")

    # 축이 죽으면 판도 함께 죽는다 — 새 칸이 그 CASCADE를 건드리지 않았는지 확인한다.
    con.execute("DELETE FROM duel_axes WHERE id = 1")
    left = con.execute("SELECT COUNT(*) FROM duel_matches").fetchone()[0]
    check(left == 0, "축을 지우면 판도 함께 죽는다 (FK CASCADE가 살아 있다)")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f"  - {f}")
        return 1
    print(f"검사 {CHECKS}건 모두 통과")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
