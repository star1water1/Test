#!/usr/bin/env python3
"""
Room 마이그레이션 56→57 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: **캐릭터 명대사** — `character_quotes` 표 신설 (사용자 요청 2026.08.20).

**이 마이그레이션에서 값어치가 가장 큰 검사는 "표를 새로 세우는가"다.** 앞의 여럿과 달리
`ALTER TABLE ADD COLUMN`이 아니라 `CREATE TABLE`이라, 어긋날 수 있는 자리가 컬럼 하나가
아니라 **표 전체**다. Room은 시작할 때 기대 스키마와 실제를 통째로 대조하므로 컬럼 하나의
타입 affinity나 인덱스 이름 하나만 달라도 **앱이 안 뜬다** — 되돌릴 길이 사용자 쪽에 없다.

특히 셋을 본다:
 - **인덱스 이름이 Room 규약(`index_<table>_<col>`)인가.** 손으로 지은 이름은 컴파일도
   실행도 통과하다가 **갱신한 기기에서만** 죽는다.
 - **`code`가 nullable UNIQUE인가.** 유니크는 엑셀 왕복이 같은 행을 잇는 기준이고,
   nullable인 것은 코드 없는 옛 스냅샷 행이 서로를 막지 않게 하려는 것이다
   (SQLite는 NULL끼리를 다르게 본다 — R-57의 그 성질을 여기서는 **의도해서** 쓴다).
 - **FK CASCADE가 실제로 도는가.** 캐릭터를 지웠는데 대사가 남으면 다음 캐릭터가
   그 id를 받았을 때 남의 대사를 물고 태어난다.

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. 마이그레이션 후 컬럼 집합이 **엔티티가 선언한 것**과 일치하는가.
 3. 기존 캐릭터가 살아남고, 표가 **비어 있는 채로** 생기는가 — 소급해서 대사를 지어내지 않는다.
 4. NOT NULL·기본값 규약 — 엔티티가 non-null인 칸은 전부 NOT NULL이고 `code`만 nullable이다.
 5. 인덱스 셋과 그 이름이 Room 기대와 같은가.
 6. FK CASCADE가 돈다 — 캐릭터를 지우면 대사도 사라진다.
 7. 두 번 돌려도 안전한가(IF NOT EXISTS) — 복구 경로가 같은 문장을 다시 밟을 수 있다.
"""
import os
import re
import sqlite3

REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"

FAILS = []
CHECKS = 0

# 엔티티가 선언한 칸 전부 — `CharacterQuote.kt`와 어긋나면 Room이 시작 시 거부한다.
QUOTE_COLUMNS = {
    "id", "characterId", "text", "occasionKey", "note", "sortOrder", "createdAt", "code",
}

# Room이 기대하는 인덱스 이름 — 규약은 `index_<table>_<col1>_<col2>`다.
QUOTE_INDICES = {
    "index_character_quotes_characterId",
    "index_character_quotes_occasionKey",
    "index_character_quotes_code",
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

    경계는 **다음 마이그레이션 블록**이다 — 뒤에 58이 들어와도 그것까지 추출하지 않는다.
    이 판의 문장은 여러 줄 이어붙이기(`"..." + "..."`)라 그 꼴도 함께 읽는다.
    """
    start = src.index(f"private val {name} = object : Migration(")
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase(", start)
    block = src[start:end]
    out = []
    # execSQL( ... ) 한 호출을 통째로 집어 그 안의 문자열 조각을 잇는다.
    for m in re.finditer(r"execSQL\(\s*(.*?)\n\s*\)", block, re.S):
        parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
        if parts:
            out.append("".join(parts))
    # 한 줄짜리도 수용한다.
    for m in re.finditer(r'execSQL\(\s*"([^"\n]+)"\s*\)', block):
        if m.group(1) not in out:
            out.append(m.group(1))
    return out


def characters_statements(src):
    """FK 부모 — `characters` 표의 최소 스키마.

    초기 스키마(v1)는 마이그레이션에 없으므로 여기서 세운다. **명대사가 보는 것은 `id`
    하나뿐**이라 그 이상을 흉내 내면 이 하네스가 진짜 스키마를 두 번째로 적는 자리가 된다.
    """
    return [
        "CREATE TABLE characters ("
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
        "name TEXT NOT NULL)"
    ]


def columns_of(con, table):
    return {r[1]: r for r in con.execute(f"PRAGMA table_info({table})")}


def indices_of(con, table):
    return {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", (table,))}


def main():
    src = read(APPDB)
    quote_src = read(f"{MODEL}/CharacterQuote.kt")

    print("=" * 70)
    print("Room 마이그레이션 56→57 검증 — 캐릭터 명대사 (사용자 요청 2026.08.20)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 57), "@Database(version)이 57 이상이다")
    check("CharacterQuote::class" in src, "엔티티가 @Database(entities)에 실렸다")
    check("abstract fun characterQuoteDao()" in src, "DAO 접근자가 선언됐다")
    check(re.search(r"ALL_MIGRATIONS\b[^=]*=\s*arrayOf\([^)]*?MIGRATION_56_57\b", src, re.S) is not None
          and "addMigrations(*ALL_MIGRATIONS)" in src,
          "MIGRATION_56_57이 등록 목록(ALL_MIGRATIONS)에 있고 그 목록이 그대로 Room에 넘어간다 "
          "(빠뜨리면 실행 시 IllegalStateException)")

    print("\n[2] 엔티티 선언과 마이그레이션이 같은 것을 말하는가")
    check("val code: String? = generateEntityCode()" in quote_src,
          "엔티티의 code가 **nullable**이다 (옛 Gson 스냅샷 역직렬화 수용 — R-2)")
    check(re.search(r"val\s+text:\s*String\b(?!\?)", quote_src) is not None,
          "엔티티의 text가 non-null이다")
    check(re.search(r'Index\(value\s*=\s*\["code"\],\s*unique\s*=\s*true\)', quote_src) is not None,
          "엔티티가 code에 유니크 색인을 선언했다 (엑셀 왕복이 같은 행을 잇는 기준)")
    check("onDelete = ForeignKey.CASCADE" in quote_src,
          "엔티티가 캐릭터 CASCADE를 선언했다")

    stmts = extract_migration_sql(src, "MIGRATION_56_57")
    joined = " ".join(stmts)
    check(len(stmts) == 4, f"표 1문 + 인덱스 3문 = 4문이다 (추출 {len(stmts)}문)")
    check(joined.upper().count("CREATE TABLE") == 1, "CREATE TABLE 하나다")
    check("DROP TABLE" not in joined.upper() and "ALTER TABLE" not in joined.upper(),
          "기존 표를 건드리지 않는 순수 신설이다")
    check(joined.upper().count("IF NOT EXISTS") == 4,
          "네 문장 모두 IF NOT EXISTS다 — 복구 경로가 같은 문장을 다시 밟아도 안전하다")
    check("ON DELETE CASCADE" in joined.upper(), "FK가 CASCADE로 선언됐다")

    print("\n[3] v56 스키마를 세우고 마이그레이션을 실제로 돌린다")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    for s in characters_statements(src):
        con.execute(s)
    con.execute("INSERT INTO characters (id, name) VALUES (1, '주인공')")
    con.execute("INSERT INTO characters (id, name) VALUES (2, '조연')")

    for s in stmts:
        con.execute(s)
    con.commit()
    check(True, "마이그레이션 4문이 예외 없이 실행됐다")

    cols = columns_of(con, "character_quotes")
    check(set(cols) == QUOTE_COLUMNS,
          f"컬럼 집합이 엔티티 선언과 같다 (실제 {sorted(cols)})")

    print("\n[4] NULL 허용 규약 — code만 nullable이다")
    for name, row in sorted(cols.items()):
        notnull = row[3] == 1
        if name == "code":
            check(not notnull, "code는 NULL을 허용한다 (코드 없는 옛 행이 서로를 막지 않는다)")
        elif name == "id":
            # INTEGER PRIMARY KEY는 rowid 별칭이라 PRAGMA가 notnull=0으로 낸다 — 정상이다.
            check(row[5] == 1, "id가 기본키다")
        else:
            check(notnull, f"{name}이 NOT NULL이다 (엔티티가 non-null이라 안 걸면 Room이 거부한다)")

    print("\n[5] 인덱스 — 이름이 Room 규약과 같은가")
    idx = indices_of(con, "character_quotes")
    named = {i for i in idx if not i.startswith("sqlite_autoindex")}
    check(QUOTE_INDICES <= named,
          f"세 인덱스가 Room 규약 이름으로 서 있다 (실제 {sorted(named)})")
    unique_idx = {r[1] for r in con.execute("PRAGMA index_list(character_quotes)") if r[2] == 1}
    check("index_character_quotes_code" in unique_idx, "code 인덱스가 UNIQUE다")
    check("index_character_quotes_characterId" not in unique_idx,
          "characterId 인덱스는 UNIQUE가 아니다 (한 캐릭터가 대사를 여럿 든다)")

    print("\n[6] 소급해서 아무것도 지어내지 않는다")
    n = con.execute("SELECT COUNT(*) FROM character_quotes").fetchone()[0]
    check(n == 0, "표가 비어 있는 채로 생긴다 — 옛 데이터에서 대사를 만들어 내지 않는다")
    survivors = con.execute("SELECT COUNT(*) FROM characters").fetchone()[0]
    check(survivors == 2, "기존 캐릭터가 그대로 살아남는다")

    print("\n[7] 실제로 쓰고 지워 본다")
    con.execute(
        "INSERT INTO character_quotes (characterId, text, occasionKey, note, sortOrder, createdAt, code)"
        " VALUES (1, '나는 포기하지 않아', '', '1화', 0, 100, 'CQ-1')")
    con.execute(
        "INSERT INTO character_quotes (characterId, text, occasionKey, note, sortOrder, createdAt, code)"
        " VALUES (1, '생일 축하해 줘서 고마워', '__birthday', '', 1, 101, 'CQ-2')")
    con.execute(
        "INSERT INTO character_quotes (characterId, text, occasionKey, note, sortOrder, createdAt, code)"
        " VALUES (2, '조연의 한마디', '', '', 0, 102, 'CQ-3')")
    con.commit()
    check(con.execute("SELECT COUNT(*) FROM character_quotes").fetchone()[0] == 3,
          "대사 셋이 들어간다")

    # 코드 유니크 — 같은 파일을 두 번 들여도 대사가 두 벌이 되지 않는 근거다.
    dup = False
    try:
        con.execute(
            "INSERT INTO character_quotes (characterId, text, occasionKey, note, sortOrder, createdAt, code)"
            " VALUES (2, '같은 코드', '', '', 1, 103, 'CQ-3')")
        con.commit()
    except sqlite3.IntegrityError:
        dup = True
    check(dup, "같은 code는 두 번 들어가지 않는다 (엑셀을 두 번 들여도 대사가 두 벌이 안 된다)")
    con.rollback()

    # 코드 없는 행은 여럿이어도 막히지 않는다 — nullable UNIQUE의 그 성질을 의도해서 쓴다.
    con.execute(
        "INSERT INTO character_quotes (characterId, text, occasionKey, note, sortOrder, createdAt, code)"
        " VALUES (2, '코드 없는 옛 행 A', '', '', 2, 104, NULL)")
    con.execute(
        "INSERT INTO character_quotes (characterId, text, occasionKey, note, sortOrder, createdAt, code)"
        " VALUES (2, '코드 없는 옛 행 B', '', '', 3, 105, NULL)")
    con.commit()
    check(con.execute("SELECT COUNT(*) FROM character_quotes WHERE code IS NULL").fetchone()[0] == 2,
          "code가 빈 옛 행은 여럿이어도 서로를 막지 않는다 (SQLite는 NULL끼리를 다르게 본다)")

    print("\n[8] FK CASCADE — 캐릭터를 지우면 대사도 사라진다")
    con.execute("DELETE FROM characters WHERE id = 2")
    con.commit()
    left = con.execute("SELECT COUNT(*) FROM character_quotes WHERE characterId = 2").fetchone()[0]
    check(left == 0,
          "지운 캐릭터의 대사가 남지 않는다 (남으면 다음 캐릭터가 그 id를 받아 남의 대사를 문다)")
    check(con.execute("SELECT COUNT(*) FROM character_quotes").fetchone()[0] == 2,
          "다른 캐릭터의 대사는 그대로다")

    print("\n[9] 생일 대사 조회가 색인을 탄다")
    plan = con.execute(
        "EXPLAIN QUERY PLAN SELECT * FROM character_quotes WHERE characterId = 1"
    ).fetchall()
    check(any("index_character_quotes_characterId" in str(r) for r in plan),
          "characterId 조회가 색인을 쓴다 (캐릭터마다 전수 훑기가 되지 않는다)")

    print("\n[10] 두 번 돌려도 안전한가")
    try:
        for s in stmts:
            con.execute(s)
        con.commit()
        check(True, "같은 마이그레이션을 다시 밟아도 예외가 없다 (IF NOT EXISTS)")
    except sqlite3.Error as e:
        check(False, f"재실행이 죽는다: {e}")
    check(con.execute("SELECT COUNT(*) FROM character_quotes").fetchone()[0] == 2,
          "재실행이 기존 대사를 지우지 않는다")

    con.close()

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f" - {f}")
        return 1
    print(f"통과 — 검사 {CHECKS}건")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
