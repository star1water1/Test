#!/usr/bin/env python3
"""
Room 마이그레이션 54→55 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: 대결 축의 **후보 필터**(B-168) — `duel_axes.candidateFiltersJson`.
설계 정본은 `docs/duel_system_design_2026-08.md` 12장이다.

**이 마이그레이션에서 값어치가 가장 큰 검사는 "NULL을 허용하고 기본값이 없는가"다.**
형제 칸 `sortDuelAxisCode`(50→51)와 같은 부류다 — `'{}'` 기본값을 박으면 휴지통 payload
(Gson — 구버전 스냅샷은 이 칸이 null이다)와 저장 형식이 갈려 *필터 없음*의 표기가 둘이 되고,
NOT NULL로 잘못 걸면 Room의 기대 스키마(nullable)와 어긋나 **앱이 시작 시 거부한다** —
사용자 쪽에서는 업데이트 직후 앱이 안 뜨는 모양이고 되돌릴 길이 없다.

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
    v54 `duel_axes`도 **그 표를 만든 블록(48→49)과 이후 ALTER(49→50 · 51→52)**에서
    추출한다(두 벌로 적으면 한쪽이 낡는다).
 2. 마이그레이션 후 컬럼 집합이 **엔티티가 선언한 것**과 일치하는가.
 3. 기존 축·판이 살아남고 새 칸이 NULL이 되는가 — *"필터 없음 = 전원 후보"*가 기본이다.
 4. 새 칸이 **nullable**이고 기본값이 없는가(Room이 시작 시 대조하는 항목 — 이 판의 요점).
 5. 유니크 인덱스 셋이 그대로인가 — ADD COLUMN은 표를 재작성하지 않으므로 살아 있어야 한다.
 6. 필터를 담은 축의 FK CASCADE가 그대로 도는가 — 세계관을 지우면 축·판이 함께 죽는
    기존 계약이 새 칸과 무관하게 성립해야 한다.
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
    "influenceFieldKeys", "outcomeFieldKeys", "profileFieldKeys",
    "candidateFiltersJson", "code",
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

    경계는 **다음 마이그레이션 블록**이다 — 뒤에 56이 들어와도 그것까지 추출하지 않는다.
    """
    start = src.index(f"private val {name} = object : Migration(")
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase(", start)
    block = src[start:end]
    triple = re.findall(r'execSQL\(\s*"""(.*?)"""\s*\)', block, re.S)
    single = re.findall(r'execSQL\(\s*"([^"\n]+)"\s*\)', block)
    return triple + single


def axis_statements(src):
    """v54 시점의 `duel_axes`를 세우는 문장들 — 만든 블록 + 이후 ALTER 둘."""
    created = [s for s in extract_migration_sql(src, "MIGRATION_48_49")
               if "duel_axes" in s and "duel_matches" not in s and "duel_counter" not in s]
    altered = [s for s in extract_migration_sql(src, "MIGRATION_49_50")
               if "duel_axes" in s]
    altered += [s for s in extract_migration_sql(src, "MIGRATION_51_52")
                if "duel_axes" in s]
    return created + altered


def match_statements(src):
    """판 표 — FK CASCADE 검사(6번)를 위해 함께 세운다."""
    return [s for s in extract_migration_sql(src, "MIGRATION_48_49")
            if "duel_matches" in s]


def indices_of(con, table):
    return {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", (table,))}


def main():
    src = read(APPDB)
    axis_src = read(f"{MODEL}/DuelAxis.kt")

    print("=" * 70)
    print("Room 마이그레이션 54→55 검증 — 대결 축의 후보 필터 (B-168)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 55), "@Database(version)이 55 이상이다")
    check(re.search(r"ALL_MIGRATIONS\b[^=]*=\s*arrayOf\([^)]*?MIGRATION_54_55\b", src, re.S) is not None
                and "addMigrations(*ALL_MIGRATIONS)" in src,
          "MIGRATION_54_55가 등록 목록(ALL_MIGRATIONS)에 있고 그 목록이 그대로 Room에 넘어간다 (빠뜨리면 실행 시 IllegalStateException)")
    check("val candidateFiltersJson: String? = null" in axis_src,
          "엔티티가 candidateFiltersJson을 **nullable**로 선언했다 (구버전 휴지통 payload의 Gson null과 한 표기)")

    stmts = extract_migration_sql(src, "MIGRATION_54_55")
    joined = " ".join(stmts)
    check(len(stmts) == 1, f"칸 하나를 더하는 1문이다 (추출 {len(stmts)}문)")
    check(joined.upper().count("ALTER TABLE") == 1, "ALTER TABLE ADD COLUMN 하나다")
    check("DROP TABLE" not in joined.upper() and "CREATE TABLE" not in joined.upper(),
          "표를 재작성하지 않는 순수 추가다 (재작성하면 인덱스·FK를 다시 세워야 한다)")
    check("NOT NULL" not in joined.upper(),
          "NOT NULL을 걸지 않는다 — 엔티티가 nullable이라 걸면 Room이 시작 시 거부한다")
    check("DEFAULT" not in joined.upper(),
          "DEFAULT를 주지 않는다 — '{}'를 박으면 필터 없음의 표기가 null과 둘로 갈린다")

    print("\n[2] v54 스키마를 실제로 세우고 마이그레이션을 돌린다")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    # FK 대상 최소 스키마 — 세계관 표는 마이그레이션 밖(초기 스키마)이라 여기서만 세운다.
    con.execute("CREATE TABLE universes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
    setup = axis_statements(src)
    check(len(setup) >= 4, f"v54 duel_axes를 만드는 문장을 찾았다 (추출 {len(setup)}문 — 표 1 + 인덱스 + ALTER 3)")
    for s in setup:
        con.execute(s)
    for s in match_statements(src):
        con.execute(s)

    con.execute("INSERT INTO universes (id, name) VALUES (1, '테스트')")
    con.execute(
        "INSERT INTO duel_axes"
        "(id, universeId, name, targetType, displayOrder, createdAt,"
        " influenceFieldKeys, outcomeFieldKeys, profileFieldKeys, code)"
        " VALUES (1, 1, '강함', 'character', 0, 100, '[]', '[]', '[]', 'DAX-1')")
    con.execute(
        "INSERT INTO duel_matches (id, axisId, aCode, bCode, winnerCode, groupId, decidedAt, code)"
        " VALUES (1, 1, 'C-A', 'C-B', 'C-A', NULL, 100, 'DM-1')")

    before = indices_of(con, "duel_axes")
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
    check(set(cols) == AXIS_COLUMNS,
          f"duel_axes 컬럼이 엔티티와 같다 (실제: {sorted(cols)})")
    check(cols["candidateFiltersJson"][3] == 0,
          "candidateFiltersJson이 **nullable**이다 (이 판에서 가장 값어치가 큰 검사)")
    check(cols["candidateFiltersJson"][2].upper() == "TEXT", "candidateFiltersJson이 TEXT다")
    check(cols["candidateFiltersJson"][4] is None,
          "기본값이 없다 — 필터 없음의 표기는 null 한 가지다")

    print("\n[4] 기존 축·판이 그대로인가")
    row = con.execute(
        "SELECT name, targetType, influenceFieldKeys, candidateFiltersJson"
        " FROM duel_axes WHERE id = 1").fetchone()
    check(row is not None and row[0] == "강함" and row[1] == "character" and row[2] == "[]",
          "마이그레이션 전에 있던 축이 그대로다")
    check(row is not None and row[3] is None,
          "기존 축의 새 칸이 NULL이다 — '필터 없음 = 전원 후보'가 기본이다")
    match = con.execute("SELECT aCode, bCode FROM duel_matches WHERE id = 1").fetchone()
    check(match == ("C-A", "C-B"), "쌓인 판이 그대로다 — 필터 칸은 판을 건드리지 않는다")

    print("\n[5] 인덱스와 새 칸의 쓰기")
    check(indices_of(con, "duel_axes") == before,
          "유니크 인덱스 셋(이름·코드)이 그대로다 (ADD COLUMN은 표를 재작성하지 않는다)")
    filters_json = '[{"fieldId":-1,"fieldName":"성별","values":["여성"],"matchMode":"exact","fieldKey":"gender"}]'
    con.execute(
        "INSERT INTO duel_axes"
        "(id, universeId, name, targetType, displayOrder, createdAt,"
        " influenceFieldKeys, outcomeFieldKeys, profileFieldKeys, candidateFiltersJson, code)"
        " VALUES (2, 1, '아름다움', 'character', 1, 200, '[]', '[]', '[]', ?, 'DAX-2')",
        (filters_json,))
    row = con.execute("SELECT candidateFiltersJson FROM duel_axes WHERE id = 2").fetchone()
    check(row == (filters_json,), "필터를 담은 축이 JSON 그대로 저장·재독된다")

    print("\n[6] FK CASCADE가 새 칸과 무관하게 그대로 도는가")
    con.execute("DELETE FROM universes WHERE id = 1")
    axes_left = con.execute("SELECT COUNT(*) FROM duel_axes").fetchone()[0]
    matches_left = con.execute("SELECT COUNT(*) FROM duel_matches").fetchone()[0]
    check(axes_left == 0, "세계관을 지우면 필터를 담은 축까지 CASCADE로 함께 죽는다")
    check(matches_left == 0, "판도 함께 죽는다 (축 경유 CASCADE — 휴지통이 지우기 전에 담는 근거)")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f"  - {f}")
        raise SystemExit(1)
    print(f"통과 — 검사 {CHECKS}건")


if __name__ == "__main__":
    main()
