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
⑤ **부모 표를 DROP하는 동안 자식 행이 살아남는가** (B-145, 2026.08.07 보강).
   `field_definitions`를 부모로 삼는 `ON DELETE CASCADE` 자식이 **넷**이다. 재건은 그 부모를
   실제로 DROP하므로, FK 강제가 켜져 있으면 **자식이 오류 없이 전량 사라진다.**
   종전 하네스는 `PRAGMA foreign_keys=ON`을 켜 놓고도 **자식 표를 하나도 싣지 않아**
   자기가 켠 그 설정이 뜻하는 검사를 한 번도 하지 못했다 — 검증 사각이었다.

   **실사용이 안전한 이유는 하나뿐이다:** Room이 `PRAGMA foreign_keys = ON`을 생성 코드의
   `onOpen`에서 걸고 그것이 `onUpgrade` **뒤**라, 마이그레이션은 FK가 꺼진 채 돈다.
   즉 이 마이그레이션의 안전은 **자기가 지키는 것이 아니라 Room의 실행 순서에 얹혀 있다.**
   그래서 하네스는 **두 설정에서 각각** 잰다 — 프로덕션과 같은 OFF에서 보존을 확인하고,
   ON에서는 유실을 **수치로 남긴다**(다음 재건 마이그레이션이 그 사실을 알아야 한다).

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. 마이그레이션 후 칼럼 집합·NOT NULL 여부가 엔티티 선언과 일치하는가.
 3. 색인 둘(universeId · 유니크 3중)이 재건 뒤에도 실재하는가.
 4. 자식 표 목록을 **손으로 적지 않는다** → `data/model`에서 `entity = FieldDefinition::class`를
    세어 하네스가 모델링한 집합과 대조한다. B-145의 원인이 바로 *"목록이 불완전한데 아무도
    눈치채지 못한 것"*이라, 다섯째 자식이 생기면 **이 하네스가 실패해야** 한다.
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

# `field_definitions`를 부모로 삼는 자식 표 넷 (B-145). **이것이 빠져 있던 것이 그 사각이다.**
# 재건이 부모를 DROP하므로 이 표들이 실재해야 CASCADE가 뜻을 갖는다.
#
# 조부모(characters·timeline_events·novels)는 **최소 형태로만** 짓는다 — 이 하네스가 재는 것은
# `field_definitions`와의 FK이지 저쪽 표의 칼럼 충실도가 아니다(V53_SCHEMA의 `universes`가
# 이미 같은 방식이다). 자식 표도 FK 검사에 필요한 칼럼만 싣는다.
V53_CHILDREN = [
    "CREATE TABLE IF NOT EXISTS characters (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS timeline_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS novels (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL)",
    """
    CREATE TABLE IF NOT EXISTS character_field_values (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        characterId INTEGER NOT NULL,
        fieldDefinitionId INTEGER NOT NULL,
        value TEXT NOT NULL,
        FOREIGN KEY(characterId) REFERENCES characters(id) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(fieldDefinitionId) REFERENCES field_definitions(id) ON UPDATE NO ACTION ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS event_field_values (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        eventId INTEGER NOT NULL,
        fieldDefinitionId INTEGER NOT NULL,
        value TEXT NOT NULL,
        FOREIGN KEY(eventId) REFERENCES timeline_events(id) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(fieldDefinitionId) REFERENCES field_definitions(id) ON UPDATE NO ACTION ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS novel_field_values (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        novelId INTEGER NOT NULL,
        fieldDefinitionId INTEGER NOT NULL,
        value TEXT NOT NULL,
        FOREIGN KEY(novelId) REFERENCES novels(id) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(fieldDefinitionId) REFERENCES field_definitions(id) ON UPDATE NO ACTION ON DELETE CASCADE
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS field_value_entries (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        fieldDefinitionId INTEGER NOT NULL,
        value TEXT NOT NULL,
        code TEXT NOT NULL,
        FOREIGN KEY(fieldDefinitionId) REFERENCES field_definitions(id) ON UPDATE NO ACTION ON DELETE CASCADE
    )
    """,
]

# 하네스가 모델링한 자식 표 — 아래 [7]이 이 집합을 `data/model` 선언과 대조한다.
MODELED_CHILDREN = {
    "character_field_values", "event_field_values",
    "novel_field_values", "field_value_entries",
}

# 자식 표에 심는 씨앗. (표, 열 목록, 행들) — 조부모 id는 전부 1이다.
CHILD_SEEDS = [
    ("character_field_values", "(characterId, fieldDefinitionId, value)", [(1, 10, "높음"), (1, 20, "흑발"), (2, 10, "중간")]),
    ("event_field_values", "(eventId, fieldDefinitionId, value)", [(1, 30, "기")]),
    ("novel_field_values", "(novelId, fieldDefinitionId, value)", [(1, 10, "작품값")]),
    ("field_value_entries", "(fieldDefinitionId, value, code)", [(10, "높음", "FVE-1"), (20, "흑발", "FVE-2")]),
]
CHILD_TOTAL = sum(len(rows) for _, _, rows in CHILD_SEEDS)


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


# 값이 전부 다른 행 셋 — 오배정 검사의 재료(같은 값이면 밀림이 안 보인다).
ROWS_BEFORE = [
    (10, 1, "mana", "마나친화", "NUMBER", '{"allowNegative":true}', "능력", 3, 1, "CHARACTER"),
    (20, 1, "hair", "머리색", "TEXT", "{}", "외형", 1, 0, "CHARACTER"),
    (30, 2, "arc", "사건 유형", "SELECT", '{"options":["기","승"]}', "분류", 2, 0, "EVENT"),
]


def build_v53(foreign_keys):
    """v53 스키마(부모 + 자식 넷)를 짓고 씨앗을 심은 연결을 준다.

    `foreign_keys`는 **마이그레이션이 도는 동안의** 설정이다 — 프로덕션에서 이 값은 OFF다
    (Room이 `PRAGMA foreign_keys = ON`을 `onOpen`에서 걸고, 그것은 `onUpgrade` 뒤에 온다).
    """
    con = sqlite3.connect(":memory:")
    con.isolation_level = None          # 트랜잭션을 명시로 다룬다 — PRAGMA foreign_keys는
                                        # 트랜잭션 안에서 **조용한 무동작**이라, 암묵 트랜잭션에
                                        # 걸리면 이 하네스가 재려는 설정 자체가 안 걸린다.
    con.execute(f"PRAGMA foreign_keys={foreign_keys}")
    on = con.execute("PRAGMA foreign_keys").fetchone()[0]
    assert on == (1 if foreign_keys == "ON" else 0), \
        f"PRAGMA foreign_keys={foreign_keys}가 걸리지 않았다 (실제 {on})"

    for ddl in V53_SCHEMA:
        con.execute(ddl)
    for ddl in V53_CHILDREN:
        con.execute(ddl)

    con.execute("INSERT INTO universes (id, name) VALUES (1, '세계A'), (2, '세계B')")
    con.execute("INSERT INTO characters (id, name) VALUES (1, '가'), (2, '나')")
    con.execute("INSERT INTO timeline_events (id, title) VALUES (1, '개전')")
    con.execute("INSERT INTO novels (id, title) VALUES (1, '작품')")
    con.executemany(
        "INSERT INTO field_definitions (id, universeId, `key`, name, type, config, groupName, displayOrder, isRequired, entityType)"
        " VALUES (?,?,?,?,?,?,?,?,?,?)", ROWS_BEFORE)
    for table, cols, rows in CHILD_SEEDS:
        con.executemany(
            f"INSERT INTO {table} {cols} VALUES ({','.join('?' * len(rows[0]))})", rows)
    return con


def run_migration(con, sqls):
    """추출한 실제 문장을 Room과 같이 **한 트랜잭션 안에서** 실행한다."""
    con.execute("BEGIN")
    for sql in sqls:
        con.executescript(sql) if ";" in sql.strip().rstrip(";") else con.execute(sql)
    con.execute("COMMIT")


def child_counts(con):
    return {t: con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in sorted(MODELED_CHILDREN)}


def declared_children(model_dir):
    """`data/model`에서 `entity = FieldDefinition::class` FK를 가진 표 이름을 모은다.

    **손으로 적은 목록을 믿지 않는 것이 이 함수의 존재 이유다** — B-145의 원인이 정확히
    "자식 목록이 비어 있는데 아무도 눈치채지 못한 것"이었다.
    """
    found = set()
    for fname in sorted(os.listdir(model_dir)):
        if not fname.endswith(".kt"):
            continue
        src = read(os.path.join(model_dir, fname))
        if "entity = FieldDefinition::class" not in src:
            continue
        m = re.search(r'tableName\s*=\s*"([^"]+)"', src)
        if not m:
            # **조용히 건너뛰지 않는다.** 자식인데 표 이름을 못 읽으면 이 함수가 세는 집합이
            # 실제보다 작아지고, 그러면 아래 대조가 **통과한다** — B-145의 원인과 글자 그대로
            # 같은 모양(목록이 불완전한데 아무도 눈치채지 못하는 것)이 여기서 재현된다.
            raise SystemExit(
                f"[7] 자식 판별 실패: {fname}이 FieldDefinition을 부모로 삼는데 tableName을 읽지 못했다.\n"
                f"     (Room 기본 표 이름을 쓰는 엔티티라면 이 하네스의 declared_children을 함께 고칠 것)")
        found.add(m.group(1))
    return found


def main():
    src = read(APPDB)
    entity_src = read(f"{MODEL}/FieldDefinition.kt")

    print("=" * 70)
    print("Room 마이그레이션 53→54 검증 — 전역 필드의 무소속 구역 (B-119 확장)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 54), "@Database(version)이 54 이상이다")
    check(re.search(r"ALL_MIGRATIONS\b[^=]*=\s*arrayOf\([^)]*?MIGRATION_53_54\b", src, re.S) is not None
                and "addMigrations(*ALL_MIGRATIONS)" in src,
          "MIGRATION_53_54가 등록 목록(ALL_MIGRATIONS)에 있고 그 목록이 그대로 Room에 넘어간다")
    check("val universeId: Long?," in entity_src,
          "엔티티의 universeId가 Long?다 (스키마만 풀고 타입을 안 풀면 NULL 행 읽기에서 죽는다)")

    print("\n[2] 추출한 실제 SQL로 마이그레이션 실행")
    sqls = extract_migration_sql(src, "MIGRATION_53_54")
    # 재건 5문장 + 색인 2문장 — 문장 수를 세는 이유는 `.trimIndent()`가 붙어 추출에서
    # 조용히 빠지는 사고를 v53 하네스가 실제로 잡았기 때문이다.
    check(len(sqls) >= 6, f"execSQL 문장이 6개 이상 추출됐다 (실제 {len(sqls)}개)")

    rows_before = ROWS_BEFORE
    # **프로덕션과 같은 설정에서 돌린다 — FK OFF.** Room은 `PRAGMA foreign_keys = ON`을
    # `onOpen`에서 걸고 그것은 `onUpgrade` 뒤라, 마이그레이션이 도는 동안 FK는 꺼져 있다.
    # (종전 하네스는 여기를 ON으로 두었다. 자식 표가 없어 차이가 드러나지 않았을 뿐이다 — B-145.)
    con = build_v53("OFF")
    run_migration(con, sqls)

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

    print("\n[4-a] 자식 표 — 부모를 DROP하는 동안 한 행도 잃지 않는다 (B-145)")
    counts = child_counts(con)
    for table, _, rows in CHILD_SEEDS:
        check(counts[table] == len(rows),
              f"{table}: {counts[table]}행 보존 (심은 것 {len(rows)}행)")
    # 재건은 부모를 이름으로 갈아 끼운다 — 자식의 FK가 새 표를 가리키지 못하면 그 뒤의
    # CASCADE·무결성이 전부 조용히 죽는다. DDL을 직접 읽어 잰다.
    for table in sorted(MODELED_CHILDREN):
        parents = {r[2] for r in con.execute(f"PRAGMA foreign_key_list({table})")}
        check("field_definitions" in parents,
              f"{table}의 FK가 재건 뒤에도 field_definitions를 가리킨다")
    # Room 자동 마이그레이션 생성 코드가 재건 끝에 내는 그 문장. **여기서는 결과를 읽는다** —
    # `execSQL`로 부르면 SQLite가 위반 행을 돌려줘도 아무도 보지 않아 잡히는 것이 없다.
    violations = list(con.execute("PRAGMA foreign_key_check"))
    check(not violations, f"foreign_key_check가 비었다 (댕글링 참조 없음, 실제 {len(violations)}건)")

    print("\n[4-b] 같은 마이그레이션을 FK 강제 ON에서 돌리면 — 자식이 전량 사라진다")
    # **이것은 살아 있는 버그가 아니라 경계의 실측이다.** 이 마이그레이션의 안전은 스스로
    # 지켜지는 것이 아니라 *Room이 onUpgrade 동안 FK를 꺼 둔다*는 사실에 얹혀 있다.
    # 그 전제가 깨지는 날(직접 열기·다른 마이그레이션 경로·라이브러리 교체) **오류 하나 없이
    # 사용자 값이 통째로 사라진다.** 그래서 숫자로 남긴다 — 다음 재건이 이 줄을 읽게.
    con_on = build_v53("ON")
    run_migration(con_on, sqls)
    lost = child_counts(con_on)
    check(sum(lost.values()) == 0,
          f"FK ON에서는 자식 {CHILD_TOTAL}행이 오류 없이 전부 사라진다 (실측 잔존 {sum(lost.values())}행) "
          f"— 재건 마이그레이션은 FK가 꺼진 채 돌아야 한다")
    check(con_on.execute("SELECT COUNT(*) FROM field_definitions").fetchone()[0] == len(rows_before),
          "그 사이 부모 행은 멀쩡하다 — 유실이 부모가 아니라 **자식에서만** 난다(그래서 눈에 안 띈다)")
    con_on.close()

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
    # **여기서 비로소 FK를 켠다** — 프로덕션의 `onOpen` 이후 상태다. 마이그레이션이 끝난 뒤라야
    # 순서가 실제와 같다. 트랜잭션 밖에서 걸어야 하므로(안에서는 조용한 무동작) 걸린 것을 되읽어 잰다.
    con.execute("PRAGMA foreign_keys=ON")
    check(con.execute("PRAGMA foreign_keys").fetchone()[0] == 1,
          "마이그레이션 뒤 FK 강제가 실제로 켜졌다 (트랜잭션 안이면 조용히 무시된다)")

    con.execute("DELETE FROM universes WHERE id = 1")
    left = {r[0] for r in con.execute("SELECT id FROM field_definitions WHERE universeId = 1")}
    check(len(left) == 0, "세계A 필드가 CASCADE로 사라졌다 (FK가 재건 뒤에도 산다)")
    globals_left = con.execute("SELECT COUNT(*) FROM field_definitions WHERE universeId IS NULL").fetchone()[0]
    check(globals_left == 2, "전역 구역 필드는 어느 세계관 삭제에도 살아남는다")

    # 2단 CASCADE — 세계관 → 필드 → 그 필드의 값. 자식 표를 실으면서 비로소 잴 수 있게 된 것이다.
    # 세계A의 필드는 10·20이고 그 값만 사라져야 한다. 세계B의 필드 30에 달린 값은 남는다.
    check(con.execute("SELECT COUNT(*) FROM character_field_values").fetchone()[0] == 0,
          "세계A 필드에 달린 캐릭터 값이 2단 CASCADE로 함께 사라졌다 (유령 값이 남지 않는다)")
    check(con.execute("SELECT COUNT(*) FROM field_value_entries").fetchone()[0] == 0,
          "그 필드의 값 라이브러리 항목도 함께 사라졌다")
    check(con.execute("SELECT COUNT(*) FROM event_field_values").fetchone()[0] == 1,
          "세계B 필드에 달린 사건 값은 그대로다 (남의 세계관까지 지우지 않는다)")
    check(not list(con.execute("PRAGMA foreign_key_check")),
          "삭제 뒤에도 댕글링 참조가 없다")

    print("\n[7] 자식 목록이 실재와 일치하는가 — 손으로 적은 목록을 믿지 않는다 (B-145)")
    # B-145의 원인은 결함 자체가 아니라 **목록이 조용히 불완전했던 것**이다. 다섯째 자식이
    # 생기면 이 검사가 실패해야 하고, 그때 위 V53_CHILDREN에 그 표를 실으라고 말해야 한다.
    declared = declared_children(MODEL)
    check(declared == MODELED_CHILDREN,
          f"field_definitions를 부모로 삼는 표가 하네스 모델과 같다 "
          f"(선언 {sorted(declared)} / 모델 {sorted(MODELED_CHILDREN)})")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}/{CHECKS}")
        for f in FAILS:
            print(f"  ✗ {f}")
        raise SystemExit(1)
    print(f"전체 통과 ({CHECKS}건)")


if __name__ == "__main__":
    main()
