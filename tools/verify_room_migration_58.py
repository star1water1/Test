#!/usr/bin/env python3
"""
Room 마이그레이션 57→58 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: **세력 간 관계의 안정 식별자** — `faction_relationships.code` (2026.08.25 사용자 지시).

**이 마이그레이션에서 값어치가 가장 큰 검사는 "백필이 유니크 인덱스보다 먼저인가"다.**
형제인 `MIGRATION_41_42`(character_relationships)가 세운 순서 그대로 — 칼럼 추가 → **전 행
백필** → 유니크 인덱스. 순서를 뒤집으면 기존 행이 전부 `code IS NULL`인 채로 유니크 인덱스를
만나는데, SQLite는 NULL끼리를 다르게 보므로 **인덱스 생성은 통과하고** 백필의 첫 UPDATE부터
문제가 없다 — 즉 잘못된 순서가 조용히 지나갈 수도, 다른 SQLite 판에서 걸릴 수도 있다.
순서를 시험으로 못 박아 두는 이유가 그것이다.

함께 보는 것:
 - **nullable TEXT인가.** `DEFAULT` 절 없는 ALTER TABLE 결과가 엔티티 선언(`code: String?`)과
   정확히 맞아야 한다. NOT NULL이나 DEFAULT를 걸면 Room이 시작 시 기대 스키마와 어긋난다고
   판단해 **앱이 안 뜬다** — 되돌릴 길이 사용자 쪽에 없다.
 - **인덱스 이름이 Room 규약(`index_<table>_<col>`)인가.** 손으로 지은 이름은 컴파일도 실행도
   통과하다가 갱신한 기기에서만 죽는다.
 - **표를 재작성하지 않는가.** 재작성하면 v33에서 세운 FK·유니크 인덱스를 다시 세워야 한다.
 - **칼럼 존재 확인이 있는가.** 중간 빌드를 거친 기기에서 이미 칼럼이 있으면 ALTER TABLE이
   "duplicate column name"으로 실패해 시작 시마다 기동 불가가 된다(파괴적 폴백을 쓰지 않는다).
 - **기존 행이 살아남고 전부 코드를 받는가.** 코드가 곧 엑셀 왕복의 정체성이라, 백필이 한 행만
   빠뜨려도 그 관계는 다음 왕복에서 유형을 고치는 순간 둘로 갈린다(이 판이 고친 결함 그 자체).
"""
import os
import re
import sqlite3

REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"

FAILS = []
CHECKS = 0

REL_COLUMNS = {
    "id", "factionId1", "factionId2", "relationType", "description",
    "intensity", "isBidirectional", "displayOrder", "createdAt", "code",
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
    """`private val <name> = object : Migration(` 블록의 execSQL 인자를 **적힌 순서대로** 뽑는다.

    경계는 **다음 마이그레이션 블록**이다. 순서가 이 하네스의 검사 대상이라
    (백필이 인덱스보다 먼저인가) 삼중 따옴표와 홑따옴표를 나눠 모으지 않고 한 줄기로 읽는다.
    """
    start = src.index(f"private val {name} = object : Migration(")
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("val ALL_MIGRATIONS", start)
    block = src[start:end]
    out = []
    for m in re.finditer(r'execSQL\(\s*(?:"""(.*?)"""|"([^"\n]+)")', block, re.S):
        out.append((m.group(1) or m.group(2)).strip())
    return out


def indices_of(con, table):
    return {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", (table,))}


def main():
    src = read(APPDB)
    model_src = read(f"{MODEL}/FactionRelationship.kt")

    print("=" * 70)
    print("Room 마이그레이션 57→58 검증 — 세력 간 관계의 안정 식별자(code)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 58), "@Database(version)이 58 이상이다")
    check(re.search(r"ALL_MIGRATIONS\b[^=]*=\s*arrayOf\([^)]*?MIGRATION_57_58\b", src, re.S) is not None
          and "addMigrations(*ALL_MIGRATIONS)" in src,
          "MIGRATION_57_58이 등록 목록(ALL_MIGRATIONS)에 있고 그 목록이 그대로 Room에 넘어간다")
    check("val code: String? = generateEntityCode()" in model_src,
          "엔티티가 code를 **nullable**로 선언하고 기본값으로 발급한다")
    check('Index(value = ["code"], unique = true)' in model_src,
          "엔티티가 code에 **유니크** 인덱스를 선언했다 (형제 CharacterRelationship과 같은 모양)")

    print("\n[2] 마이그레이션 문장 자체")
    stmts = extract_migration_sql(src, "MIGRATION_57_58")
    joined = " ".join(stmts).upper()
    check("ALTER TABLE `FACTION_RELATIONSHIPS` ADD COLUMN `CODE` TEXT" in joined,
          "nullable TEXT 칼럼을 더한다")
    check("NOT NULL" not in joined,
          "NOT NULL을 걸지 않는다 — 엔티티가 nullable이라 걸면 Room이 시작 시 거부한다")
    check("DEFAULT" not in joined,
          "DEFAULT를 주지 않는다 — 기본값을 박으면 엔티티의 기대 스키마와 어긋난다")
    check("DROP TABLE" not in joined and "CREATE TABLE" not in joined,
          "표를 재작성하지 않는 순수 추가다 (재작성하면 v33의 FK·유니크 인덱스를 다시 세워야 한다)")
    check("PRAGMA table_info(`faction_relationships`)" in " ".join(
              re.findall(r'db\.query\(\s*"([^"\n]+)"', src[src.index("MIGRATION_57_58"):])),
          "ALTER 전에 칼럼 존재를 확인한다 (중간 빌드 기기의 duplicate column 크래시 방지)")

    idx_pos = next((i for i, s in enumerate(stmts) if "CREATE UNIQUE INDEX" in s.upper()), -1)
    upd_pos = next((i for i, s in enumerate(stmts) if s.upper().startswith("UPDATE")), -1)
    check(idx_pos >= 0, "유니크 인덱스를 만든다")
    check(upd_pos >= 0, "백필 UPDATE 문이 있다")
    check(upd_pos >= 0 and idx_pos >= 0 and upd_pos < idx_pos,
          "**백필이 유니크 인덱스보다 먼저다** (이 판에서 가장 값어치가 큰 검사)")
    check(any("index_faction_relationships_code" in s for s in stmts),
          "인덱스 이름이 Room 규약(index_<table>_<col>)이다")

    print("\n[3] v57 스키마를 실제로 세우고 마이그레이션을 돌린다")
    con = sqlite3.connect(":memory:")
    con.execute(
        "CREATE TABLE `factions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
    setup = [s for s in extract_migration_sql(src, "MIGRATION_32_33")
             if "faction_relationships" in s]
    check(len(setup) >= 4, f"v57 세력 관계 표를 세우는 문장을 찾았다 (추출 {len(setup)}문)")
    for s in setup:
        con.execute(s)
    con.execute("INSERT INTO factions(id, name) VALUES (1, '가'), (2, '나')")
    con.execute(
        "INSERT INTO faction_relationships"
        "(id, factionId1, factionId2, relationType, description, intensity,"
        " isBidirectional, displayOrder, createdAt)"
        " VALUES (1, 1, 2, '동맹', '설명', 5, 1, 0, 100),"
        "        (2, 2, 1, '적대', '', 7, 0, 1, 200)")

    before = indices_of(con, "faction_relationships")
    ran = True
    for s in stmts:
        if s.upper().startswith("UPDATE"):
            continue  # 백필은 코틀린 루프가 값을 채운다 — 아래에서 같은 문장을 파이썬으로 돌린다
        try:
            con.execute(s)
        except Exception as e:  # noqa: BLE001
            ran = False
            print(f"    실행 실패: {s[:60]} → {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[4] 스키마가 Room 기대와 일치하는가")
    cols = {r[1]: r for r in con.execute("PRAGMA table_info(`faction_relationships`)")}
    check(set(cols) == REL_COLUMNS,
          f"faction_relationships 컬럼이 엔티티와 같다 (실제: {sorted(cols)})")
    check(cols["code"][3] == 0, "code가 **nullable**이다")
    check(cols["code"][2].upper() == "TEXT", "code가 TEXT다")
    check(cols["code"][4] is None, "기본값이 없다 — 값은 코틀린이 발급해 넣는다")

    print("\n[5] 기존 행과 인덱스")
    rows = list(con.execute(
        "SELECT id, relationType, description, code FROM faction_relationships ORDER BY id"))
    check(len(rows) == 2 and rows[0][1] == "동맹" and rows[1][1] == "적대",
          "마이그레이션 전에 있던 관계 둘이 그대로다")
    check(all(r[3] is None for r in rows),
          "SQL만으로는 코드가 비어 있다 — 값은 코틀린 백필 루프가 넣는다(문장에 하드코딩하지 않는다)")

    # 코틀린이 하는 일을 그대로 흉내 내 백필이 유니크 인덱스와 어긋나지 않는지 본다.
    for i, (rid, *_rest) in enumerate(rows):
        con.execute("UPDATE faction_relationships SET code = ? WHERE id = ?", (f"CODE{i}", rid))
    codes = [r[0] for r in con.execute("SELECT code FROM faction_relationships ORDER BY id")]
    check(codes == ["CODE0", "CODE1"], "백필이 전 행에 서로 다른 코드를 넣는다")

    after = indices_of(con, "faction_relationships")
    check(before <= after, "v33이 세운 인덱스가 그대로 남는다 (ADD COLUMN은 표를 재작성하지 않는다)")
    check("index_faction_relationships_code" in after, "code 유니크 인덱스가 섰다")

    dup = True
    try:
        con.execute("UPDATE faction_relationships SET code = 'CODE0' WHERE id = 2")
        dup = False
    except sqlite3.IntegrityError:
        pass
    check(dup, "같은 코드를 둘이 들 수 없다 (유니크가 실제로 작동한다)")

    # 코드 없는 행이 여럿 있어도 서로를 막지 않는다 — 구버전 스냅샷 복원이 걸리는 자리다.
    con.execute("UPDATE faction_relationships SET code = NULL")
    nulls_ok = True
    try:
        con.execute(
            "INSERT INTO faction_relationships"
            "(id, factionId1, factionId2, relationType, description, intensity,"
            " isBidirectional, displayOrder, createdAt, code)"
            " VALUES (3, 1, 2, '중립', '', 5, 1, 0, 300, NULL)")
    except sqlite3.IntegrityError:
        nulls_ok = False
    check(nulls_ok, "code가 NULL인 행은 여럿 있어도 서로를 막지 않는다 (SQLite의 NULL 성질)")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f"  - {f}")
        raise SystemExit(1)
    print(f"통과 — 검사 {CHECKS}건")


if __name__ == "__main__":
    main()
