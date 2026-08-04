#!/usr/bin/env python3
"""
Room 마이그레이션 49→50 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: 대결 축의 **필드 연결**(B-104 층 C) — `duel_axes`에 `influenceFieldKeys`·`outcomeFieldKeys`.
설계 정본은 `docs/duel_system_design_2026-08.md`이고 해석 규칙은 `util/DuelFieldLinks`다.

**이 마이그레이션에서 값어치가 가장 큰 검사는 "기존 축이 그대로인가"다.** 칸을 더하는 순수
추가지만, 기본값을 빠뜨리면 기존 행의 새 칸이 NULL이 되고 Room은 NOT NULL을 기대하므로
**앱이 시작 시 스키마 불일치로 거부한다** — 사용자 쪽에서는 업데이트하자마자 앱이 안 뜨는
모양으로 나타나고, 되돌릴 길이 없다. 그것은 스키마를 실제로 돌려 보면 바로 드러나는 사실이다.

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
    v49 `duel_axes`도 마찬가지로 **48→49 블록에서 추출**한다(두 벌로 적으면 한쪽이 낡는다).
 2. 마이그레이션 후 컬럼 집합이 **엔티티가 선언한 것**과 일치하는가.
 3. 기존 행이 살아남고 새 칸이 `[]`로 채워지는가 — *"연결 없음"*이 기본이다.
 4. 새 칸이 NOT NULL인가(Room이 시작 시 대조하는 항목).
 5. 인덱스·FK가 그대로인가 — ALTER TABLE ADD COLUMN은 표를 재작성하지 않으므로 살아 있어야 한다.
 6. 판·처분 표는 손대지 않았는가.
"""
import os
import re
import sqlite3

REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"

FAILS = []
CHECKS = 0

# v49 시점의 `universes` 표 — 축이 매달리는 부모(49판 하네스와 같은 정의).
V49_UNIVERSES = """CREATE TABLE `universes` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `name` TEXT NOT NULL,
    `code` TEXT NOT NULL DEFAULT ''
)"""

AXES_COLUMNS = {
    "id", "universeId", "name", "targetType", "displayOrder", "createdAt",
    "influenceFieldKeys", "outcomeFieldKeys", "code",
}


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

    경계는 **다음 마이그레이션 블록**이다 — 뒤에 51이 들어와도 그것까지 추출하지 않는다.
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
    print("Room 마이그레이션 49→50 검증 — 대결 축↔필드 연결 (B-104 층 C)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 50), "@Database(version)이 50 이상이다")
    check(re.search(r"addMigrations\([^)]*MIGRATION_49_50", src, re.S) is not None,
          "MIGRATION_49_50이 addMigrations에 등록됐다 (빠뜨리면 실행 시 IllegalStateException)")
    check("val influenceFieldKeys: String = \"[]\"" in axis_src,
          "엔티티가 influenceFieldKeys를 기본값 '[]'로 선언했다")
    check("val outcomeFieldKeys: String = \"[]\"" in axis_src,
          "엔티티가 outcomeFieldKeys를 기본값 '[]'로 선언했다")
    check("DuelFieldLinks" in axis_src,
          "엔티티가 해석을 DuelFieldLinks에 맡긴다 (두 곳에서 파싱하면 규칙이 갈린다)")

    stmts = extract_migration_sql(src, "MIGRATION_49_50")
    joined = " ".join(stmts).upper()
    check(len(stmts) == 2, f"칸 둘을 더하는 2문이다 (추출 {len(stmts)}문)")
    check(joined.count("ALTER TABLE") == 2, "ALTER TABLE ADD COLUMN 둘이다")
    check("DROP TABLE" not in joined and "CREATE TABLE" not in joined,
          "표를 재작성하지 않는 순수 추가다 (재작성하면 인덱스·FK를 다시 세워야 한다)")
    check("DEFAULT '[]'" in " ".join(stmts),
          "기본값 '[]'를 준다 — 없으면 기존 행이 NULL이 되어 Room이 시작 시 거부한다")

    print("\n[2] v49 스키마를 실제로 세우고 마이그레이션을 돌린다")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys = ON")
    con.execute(V49_UNIVERSES)
    con.execute("INSERT INTO universes(id, name, code) VALUES (1, '아르카나', 'UNI-1')")
    # v49의 대결 표 셋은 **48→49 블록에서 그대로 가져온다**(SQL 이중화 금지).
    for s in extract_migration_sql(src, "MIGRATION_48_49"):
        con.execute(s)
    con.execute(
        "INSERT INTO duel_axes(id, universeId, name, targetType, displayOrder, createdAt, code)"
        " VALUES (1, 1, '강함', 'character', 0, 100, 'DAX-1')")
    con.execute(
        "INSERT INTO duel_matches(id, axisId, aCode, bCode, winnerCode, groupId, decidedAt, code)"
        " VALUES (1, 1, 'CHR-1', 'CHR-2', 'CHR-1', NULL, 200, 'DMT-1')")
    con.execute(
        "INSERT INTO duel_counter_verdicts(id, axisId, kind, shape, memberCodes, memberKey, decidedAt, code)"
        " VALUES (1, 1, 'counter', 'direct', '[\"CHR-1\",\"CHR-2\"]', '[\"CHR-1\",\"CHR-2\"]', 300, 'DCV-1')")

    axes_before = indices_of(con, "duel_axes")
    try:
        for s in stmts:
            con.execute(s)
        ran = True
    except Exception as e:  # noqa: BLE001
        ran = False
        print(f"    실행 실패: {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[3] 스키마가 Room 기대와 일치하는가")
    axes = {r[1]: r for r in con.execute("PRAGMA table_info(`duel_axes`)")}
    check(set(axes) == AXES_COLUMNS, f"duel_axes 컬럼이 엔티티와 같다 (실제: {sorted(axes)})")
    for col in ("influenceFieldKeys", "outcomeFieldKeys"):
        check(axes[col][3] == 1, f"duel_axes.{col}이 NOT NULL이다")
        check(axes[col][2].upper() == "TEXT", f"duel_axes.{col}이 TEXT다")

    print("\n[4] 기존 축이 그대로인가")
    row = con.execute(
        "SELECT name, code, influenceFieldKeys, outcomeFieldKeys FROM duel_axes WHERE id = 1").fetchone()
    check(row is not None and row[0] == "강함" and row[1] == "DAX-1",
          "마이그레이션 전에 있던 축이 그대로 있다")
    check(row is not None and row[2] == "[]" and row[3] == "[]",
          "기존 축의 새 칸이 '[]'(연결 없음)로 채워진다 — 소급해서 필드를 이어 주지 않는다")

    print("\n[5] 인덱스·FK·다른 표")
    check(indices_of(con, "duel_axes") == axes_before,
          "축의 인덱스가 그대로다 (ADD COLUMN은 표를 재작성하지 않는다)")
    check(con.execute("SELECT COUNT(*) FROM duel_matches").fetchone()[0] == 1, "판이 그대로다")
    check(con.execute("SELECT COUNT(*) FROM duel_counter_verdicts").fetchone()[0] == 1, "처분이 그대로다")
    match_cols = {r[1] for r in con.execute("PRAGMA table_info(`duel_matches`)")}
    check("influenceFieldKeys" not in match_cols, "판 표에는 칸이 붙지 않았다 (연결은 축의 것이다)")

    # 세계관을 지우면 축이 함께 죽는 CASCADE가 살아 있는가 — ADD COLUMN이 FK를 건드리지 않았다는 증명.
    con.execute("DELETE FROM universes WHERE id = 1")
    check(con.execute("SELECT COUNT(*) FROM duel_axes").fetchone()[0] == 0,
          "세계관 삭제 CASCADE가 그대로다 (축이 함께 사라진다)")
    check(con.execute("SELECT COUNT(*) FROM duel_matches").fetchone()[0] == 0,
          "축과 함께 판도 사라진다 (2단 CASCADE 유지)")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f"  - {f}")
        raise SystemExit(1)
    print(f"통과 — 검사 {CHECKS}건")


if __name__ == "__main__":
    main()
