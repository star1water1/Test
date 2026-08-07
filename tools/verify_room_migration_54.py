#!/usr/bin/env python3
"""
Room 마이그레이션 53→54 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: **전역 필드의 무소속 구역**(B-119 확장, 2026.08.07 사용자 확정 — *"전역필드라면
세계관 소속이 없더라도 가지게"*) — `field_definitions.universeId`의 NOT NULL 해제.

**이 저장소의 첫 표 재건 마이그레이션이다.** 지금까지(41→53)는 전부 표·칼럼을 더하는 순수
추가였고, 이번에는 SQLite가 제약 변경을 못 해 새 표를 짓고 데이터를 옮긴다. 그래서 값어치가
가장 큰 검사도 종전과 다르다:

① **모든 행·모든 칸이 자리 그대로 옮겨졌는가.** 재건의 유일한 진짜 위험은 유실이 아니라
   **오배정**이다 — INSERT…SELECT의 칼럼 짝이 어긋나면 값이 옆 칸으로 밀려 들어가는데,
   행 수는 그대로라 개수 검사는 통과한다. 값이 다른 여러 행을 실제로 넣고 **칸 단위로**
   되읽어 잰다.
② **id가 보존되는가.** `CharacterFieldValue.fieldDefinitionId`가 id로 가리키므로 재건이
   id를 다시 매기면 **모든 캐릭터의 값이 남의 필드로 간다**(조용한 전량 오배정).
③ **FK CASCADE가 살아 있는가.** 재건 표가 FK를 빠뜨리면 세계관 삭제가 필드를 남기고,
   그 필드는 어느 화면에도 안 보이는 유령이 된다. 세계관을 실제로 지워 잰다.
④ **NULL이 실제로 들어가고, 유니크가 NULL 구역에서 어떻게 동작하는지 그대로 적는가.**
   SQLite 유니크 색인은 NULL끼리를 다른 값으로 본다 — 전역 구역의 key 유일성은 DB가 아니라
   심기 로직이 지킨다(엔티티 KDoc이 그렇게 선언한다). 하네스는 그 사실이 **선언과 일치**하는지
   잰다(색인이 NULL 중복을 막지 **않는다**는 것을 실증 — 막는다고 잘못 믿는 것이 다음 사고다).

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. 마이그레이션 후 칼럼 집합·NOT NULL 여부가 엔티티 선언과 일치하는가.
 3. 색인 둘(universeId · 유니크 3중)이 재건 뒤에도 실재하는가.
"""
import os
import re
import sqlite3

REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"

FAILS = []
CHECKS = 0

FIELD_COLUMNS = {
    "id", "universeId", "key", "name", "type", "config", "groupName",
    "displayOrder", "isRequired", "entityType",
}

# v53의 표 — 재건 **이전** 형태(universeId NOT NULL + FK). 하네스가 이 위에서 마이그레이션을
# 실행한다. v52 하네스의 V51_UNIVERSES와 같은 몫이다.
V53_SCHEMA = [
    """
    CREATE TABLE IF NOT EXISTS universes (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        name TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS field_definitions (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        universeId INTEGER NOT NULL,
        `key` TEXT NOT NULL,
        name TEXT NOT NULL,
        type TEXT NOT NULL,
        config TEXT NOT NULL,
        groupName TEXT NOT NULL,
        displayOrder INTEGER NOT NULL,
        isRequired INTEGER NOT NULL,
        entityType TEXT NOT NULL,
        FOREIGN KEY(universeId) REFERENCES universes(id) ON UPDATE NO ACTION ON DELETE CASCADE
    )
    """,
    "CREATE INDEX IF NOT EXISTS index_field_definitions_universeId ON field_definitions (universeId)",
    "CREATE UNIQUE INDEX IF NOT EXISTS index_field_definitions_universeId_entityType_key ON field_definitions (universeId, entityType, `key`)",
]


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
    m = re.search(r"version\s*=\s*(\d+)", src)
    return m is not None and int(m.group(1)) >= minimum


def extract_migration_sql(src, name):
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
    entity_src = read(f"{MODEL}/FieldDefinition.kt")

    print("=" * 70)
    print("Room 마이그레이션 53→54 검증 — 전역 필드의 무소속 구역 (B-119 확장)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 54), "@Database(version)이 54 이상이다")
    check(re.search(r"addMigrations\([^)]*MIGRATION_53_54", src, re.S) is not None,
          "MIGRATION_53_54가 addMigrations에 등록됐다")
    check("val universeId: Long?," in entity_src,
          "엔티티의 universeId가 Long?다 (스키마만 풀고 타입을 안 풀면 NULL 행 읽기에서 죽는다)")

    print("\n[2] 추출한 실제 SQL로 마이그레이션 실행")
    sqls = extract_migration_sql(src, "MIGRATION_53_54")
    # 재건 5문장 + 색인 2문장 — 문장 수를 세는 이유는 `.trimIndent()`가 붙어 추출에서
    # 조용히 빠지는 사고를 v53 하네스가 실제로 잡았기 때문이다.
    check(len(sqls) >= 6, f"execSQL 문장이 6개 이상 추출됐다 (실제 {len(sqls)}개)")

    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    for ddl in V53_SCHEMA:
        con.execute(ddl)

    # 값이 전부 다른 행 셋 — 오배정 검사의 재료(같은 값이면 밀림이 안 보인다).
    con.execute("INSERT INTO universes (id, name) VALUES (1, '세계A'), (2, '세계B')")
    rows_before = [
        (10, 1, "mana", "마나친화", "NUMBER", '{"allowNegative":true}', "능력", 3, 1, "CHARACTER"),
        (20, 1, "hair", "머리색", "TEXT", "{}", "외형", 1, 0, "CHARACTER"),
        (30, 2, "arc", "사건 유형", "SELECT", '{"options":["기","승"]}', "분류", 2, 0, "EVENT"),
    ]
    con.executemany(
        "INSERT INTO field_definitions (id, universeId, `key`, name, type, config, groupName, displayOrder, isRequired, entityType)"
        " VALUES (?,?,?,?,?,?,?,?,?,?)", rows_before)
    con.commit()

    for sql in sqls:
        con.executescript(sql) if ";" in sql.strip().rstrip(";") else con.execute(sql)
    con.commit()

    print("\n[3] 스키마 — 엔티티 선언과 일치")
    cols = {r[1]: r for r in con.execute("PRAGMA table_info(field_definitions)")}
    check(set(cols.keys()) == FIELD_COLUMNS,
          f"칼럼 집합이 엔티티와 같다 (실제: {sorted(cols.keys())})")
    check(cols["universeId"][3] == 0, "universeId의 NOT NULL이 풀렸다 (notnull=0)")
    check(cols["key"][3] == 1, "key는 여전히 NOT NULL이다 (다른 칼럼 제약을 건드리지 않았다)")
    idx = indices_of(con, "field_definitions")
    check("index_field_definitions_universeId" in idx, "universeId 색인이 재건 뒤에도 있다")
    check("index_field_definitions_universeId_entityType_key" in idx, "유니크 3중 색인이 재건 뒤에도 있다")
    fks = list(con.execute("PRAGMA foreign_key_list(field_definitions)"))
    check(any(fk[2] == "universes" and fk[6] == "CASCADE" for fk in fks),
          "universes로의 FK CASCADE가 재건 표에 실려 있다")

    print("\n[4] 데이터 — 행·칸·id 그대로 (오배정 검사)")
    after = {r[0]: r for r in con.execute(
        "SELECT id, universeId, `key`, name, type, config, groupName, displayOrder, isRequired, entityType"
        " FROM field_definitions")}
    check(len(after) == len(rows_before), f"행 수가 같다 ({len(after)} = {len(rows_before)})")
    for row in rows_before:
        got = after.get(row[0])
        check(got == row, f"id={row[0]} 행이 칸 단위로 같다 (id 보존 + 자리 안 밀림)")

    print("\n[5] 전역 구역 — NULL이 실제로 동작한다")
    con.execute(
        "INSERT INTO field_definitions (universeId, `key`, name, type, config, groupName, displayOrder, isRequired, entityType)"
        " VALUES (NULL, 'global_note', '전역 메모', 'TEXT', '{}', '기본', 0, 0, 'CHARACTER')")
    con.commit()
    n = con.execute("SELECT COUNT(*) FROM field_definitions WHERE universeId IS NULL").fetchone()[0]
    check(n == 1, "universeId NULL 행이 실제로 들어간다 (전역 구역)")
    # 유니크가 NULL 구역을 막지 **않는다**는 사실의 실증 — 막는다고 믿는 것이 다음 사고다.
    con.execute(
        "INSERT INTO field_definitions (universeId, `key`, name, type, config, groupName, displayOrder, isRequired, entityType)"
        " VALUES (NULL, 'global_note', '전역 메모2', 'TEXT', '{}', '기본', 1, 0, 'CHARACTER')")
    con.commit()
    dup = con.execute(
        "SELECT COUNT(*) FROM field_definitions WHERE universeId IS NULL AND `key`='global_note'").fetchone()[0]
    check(dup == 2, "유니크 색인은 NULL 구역의 key 중복을 막지 않는다 — 심기 로직이 지킨다(엔티티 KDoc 선언과 일치)")
    kdoc_states_it = "전역 구역에서는 강제되지 않는다" in entity_src
    check(kdoc_states_it, "그 사실이 엔티티 KDoc에 명문으로 있다 (다음 사람이 색인을 믿지 않게)")

    print("\n[6] FK — 세계관 삭제가 그 구역 필드를 지우고 전역 구역은 남긴다")
    con.execute("DELETE FROM universes WHERE id = 1")
    con.commit()
    left = {r[0] for r in con.execute("SELECT id FROM field_definitions WHERE universeId = 1")}
    check(len(left) == 0, "세계A 필드가 CASCADE로 사라졌다 (FK가 재건 뒤에도 산다)")
    globals_left = con.execute("SELECT COUNT(*) FROM field_definitions WHERE universeId IS NULL").fetchone()[0]
    check(globals_left == 2, "전역 구역 필드는 어느 세계관 삭제에도 살아남는다")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}/{CHECKS}")
        for f in FAILS:
            print(f"  ✗ {f}")
        raise SystemExit(1)
    print(f"전체 통과 ({CHECKS}건)")


if __name__ == "__main__":
    main()
