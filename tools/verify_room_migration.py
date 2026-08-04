#!/usr/bin/env python3
"""
Room 마이그레이션 41→42 검증 하네스 (Android 없이 실제 SQLite로 실행).

핵심 원칙:
 1. 테스트가 SQL을 복사해 갖고 있으면 코드와 어긋난다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. v41 테이블은 실제 배포된 마이그레이션(v27→28 재생성 + 이후 변경)과 동일한 정의로 만든다.
 3. 마이그레이션 후 스키마가 **Room이 엔티티에서 기대하는 형태**와 일치하는지 PRAGMA로 대조한다.
    (Room은 시작 시 이 비교에서 어긋나면 IllegalStateException으로 앱을 중단시킨다)
 4. 대조군: 이미 배포되어 정상 동작 중인 MIGRATION_34_35를 같은 하네스로 돌려 하네스 자체를 검증한다.
"""
import os
import re
import sqlite3
import sys
import uuid

# 저장소 루트 — **스크립트 위치에서 유도한다**(`tools/`의 부모).
# 종전에는 `/home/user/Test`가 박혀 있어 **다른 경로로 체크아웃하면 어디서도 못 돌았다**;
# 개발 컨테이너의 경로와 우연히 같아 로컬에서만 통과했고, 2026.08.04에 이 하네스를
# CI에 걸면서 처음 드러났다(세션 로그 1-bw). 셸 검사들이 쓰는 규약과 같다.
REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
ENTITY = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model/CharacterRelationship.kt"

FAILS = []
CHECKS = 0


def check(cond, msg):
    global CHECKS
    CHECKS += 1
    if not cond:
        FAILS.append(msg)
        print(f"  ✗ {msg}")
    else:
        print(f"  ✓ {msg}")


def gen_code():
    return uuid.uuid4().hex[:16]


def extract_migration_block(src, name):
    """MIGRATION_x_y 객체의 migrate 본문 텍스트를 뽑는다."""
    start = src.index(f"private val {name} = object : Migration(")
    # 다음 'private val MIGRATION' 또는 'fun getDatabase' 까지
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase", start)
    return src[start:end]


def extract_sql(block):
    """execSQL("...") / execSQL(\"\"\"...\"\"\") 문장을 순서대로 추출."""
    stmts = []
    for m in re.finditer(r'execSQL\(\s*"""(.*?)"""', block, re.S):
        stmts.append(("exec", m.group(1).strip(), m.start()))
    for m in re.finditer(r'execSQL\(\s*"((?:[^"\\]|\\.)*)"', block):
        s = m.group(1)
        if s.strip():
            stmts.append(("exec", s.replace('\\"', '"'), m.start()))
    stmts.sort(key=lambda t: t[2])
    return [(k, s) for k, s, _ in stmts]


# v41 시점의 character_relationships 정의 — 배포된 MIGRATION_27_28의 재생성 SQL 그대로
V41_TABLE = """
CREATE TABLE `character_relationships` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `characterId1` INTEGER NOT NULL,
    `characterId2` INTEGER NOT NULL,
    `relationshipType` TEXT NOT NULL,
    `description` TEXT NOT NULL DEFAULT '',
    `intensity` INTEGER NOT NULL DEFAULT 5,
    `isBidirectional` INTEGER NOT NULL DEFAULT 1,
    `displayOrder` INTEGER NOT NULL DEFAULT 0,
    `createdAt` INTEGER NOT NULL DEFAULT 0,
    `factionId` INTEGER DEFAULT NULL,
    FOREIGN KEY(`characterId1`) REFERENCES `characters`(`id`) ON DELETE CASCADE,
    FOREIGN KEY(`characterId2`) REFERENCES `characters`(`id`) ON DELETE CASCADE,
    FOREIGN KEY(`factionId`) REFERENCES `factions`(`id`) ON DELETE SET NULL
)
"""
V41_INDICES = [
    "CREATE INDEX `index_character_relationships_characterId1` ON `character_relationships` (`characterId1`)",
    "CREATE INDEX `index_character_relationships_characterId2` ON `character_relationships` (`characterId2`)",
    "CREATE INDEX `index_character_relationships_createdAt` ON `character_relationships` (`createdAt`)",
    "CREATE UNIQUE INDEX `index_character_relationships_characterId1_characterId2_relationshipType` ON `character_relationships` (`characterId1`, `characterId2`, `relationshipType`)",
    "CREATE INDEX `index_character_relationships_displayOrder_createdAt` ON `character_relationships` (`displayOrder`, `createdAt`)",
    "CREATE INDEX `index_character_relationships_factionId` ON `character_relationships` (`factionId`)",
]


def build_v41(con, rows):
    con.execute("CREATE TABLE `characters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
    con.execute("CREATE TABLE `factions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
    con.execute(V41_TABLE)
    for s in V41_INDICES:
        con.execute(s)
    for i in range(1, 6):
        con.execute("INSERT INTO characters (id, name) VALUES (?, ?)", (i, f"char{i}"))
    con.execute("INSERT INTO factions (id, name) VALUES (1, 'f1')")
    for r in rows:
        con.execute(
            "INSERT INTO character_relationships "
            "(id, characterId1, characterId2, relationshipType, description, intensity, isBidirectional, displayOrder, createdAt, factionId) "
            "VALUES (?,?,?,?,?,?,?,?,?,?)", r)
    con.commit()


def run_migration(con, stmts):
    """Kotlin의 execSQL/커서 백필을 파이썬으로 그대로 재현 (컬럼 존재 가드 포함)."""
    have = {r[1] for r in con.execute("PRAGMA table_info(`character_relationships`)")}
    for kind, sql in stmts:
        if "UPDATE `character_relationships` SET code = ?" in sql:
            continue  # 백필은 아래 커서 루프에서
        if "ADD COLUMN" in sql and "code" in have:
            continue  # Kotlin의 hasCode 가드 재현
        con.execute(sql)
    # 커서 백필 재현 (Kotlin: SELECT id ... WHERE code IS NULL → UPDATE SET code=?)
    ids = [r[0] for r in con.execute("SELECT id FROM `character_relationships` WHERE code IS NULL")]
    for rid in ids:
        con.execute("UPDATE `character_relationships` SET code = ? WHERE id = ?", (gen_code(), rid))
    con.commit()
    return len(ids)


def schema_of(con, table):
    cols = {r[1]: {"type": r[2], "notnull": r[3], "default": r[4], "pk": r[5]}
            for r in con.execute(f"PRAGMA table_info(`{table}`)")}
    idx = {}
    for r in con.execute(f"PRAGMA index_list(`{table}`)"):
        name, unique = r[1], r[2]
        cols_in = [c[2] for c in con.execute(f"PRAGMA index_info(`{name}`)")]
        idx[name] = {"unique": bool(unique), "columns": cols_in}
    fks = [(r[2], r[3], r[4], r[6]) for r in con.execute(f"PRAGMA foreign_key_list(`{table}`)")]
    return cols, idx, fks


def main():
    src = open(APPDB, encoding="utf-8").read()

    print("[1] 마이그레이션 SQL을 AppDatabase.kt에서 추출")
    block = extract_migration_block(src, "MIGRATION_41_42")
    stmts = extract_sql(block)
    for _, s in stmts:
        print(f"    · {s[:95]}")
    check(any("ADD COLUMN `code` TEXT" in s for _, s in stmts), "ADD COLUMN `code` TEXT 문장 존재")
    check(not any("NOT NULL" in s and "ADD COLUMN" in s for _, s in stmts),
          "ADD COLUMN에 NOT NULL 없음 (엔티티 code: String? 와 일치 — Room 스키마 검증 통과 조건)")
    check(not any("DEFAULT" in s and "ADD COLUMN" in s for _, s in stmts),
          "ADD COLUMN에 DEFAULT 절 없음 (@ColumnInfo(defaultValue) 미사용과 일치)")
    check(any("CREATE UNIQUE INDEX IF NOT EXISTS `index_character_relationships_code`" in s for _, s in stmts),
          "유니크 인덱스명이 Room 규약(index_<table>_<col>)과 일치")
    check(not any(("DROP TABLE" in s or "DELETE FROM" in s) for _, s in stmts),
          "테이블 삭제·행 삭제 문장 없음 (비파괴 마이그레이션)")

    print("\n[2] 엔티티 선언 대조")
    ent = open(ENTITY, encoding="utf-8").read()
    check("val code: String? = generateEntityCode()" in ent, "엔티티가 nullable code 선언 (사건/상태변화와 동일)")
    check('Index(value = ["code"], unique = true)' in ent, "엔티티에 유니크 인덱스 선언")

    print("\n[3] 실제 SQLite에서 v41 → 마이그레이션 실행")
    rows = [
        (1, 1, 2, "친구", "설명A", 7, 1, 0, 1000, None),
        (2, 1, 2, "라이벌", "설명B", 3, 0, 1, 2000, 1),
        (3, 2, 3, "가족", "", 5, 1, 2, 3000, None),
        (4, 3, 4, "적대", "설명D", 9, 1, 3, 4000, None),
    ]
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    build_v41(con, rows)
    before = list(con.execute("SELECT id, characterId1, characterId2, relationshipType, description, intensity, isBidirectional, displayOrder, createdAt, factionId FROM character_relationships ORDER BY id"))
    backfilled = run_migration(con, stmts)
    after = list(con.execute("SELECT id, characterId1, characterId2, relationshipType, description, intensity, isBidirectional, displayOrder, createdAt, factionId FROM character_relationships ORDER BY id"))
    codes = [r[0] for r in con.execute("SELECT code FROM character_relationships ORDER BY id")]

    check(backfilled == len(rows), f"기존 {len(rows)}행 전부 백필 (실제 {backfilled})")
    check(before == after, "기존 열 데이터가 한 글자도 변하지 않음")
    check(all(c is not None for c in codes), "code가 NULL인 행 없음")
    check(len(set(codes)) == len(codes), "생성된 code가 전부 고유")
    check(all(len(c) == 16 for c in codes), "code 길이 16자 (기존 엔티티 규약과 동일)")

    print("\n[4] 마이그레이션 후 스키마가 Room 기대와 일치하는가")
    cols, idx, fks = schema_of(con, "character_relationships")
    check("code" in cols, "code 열 존재")
    check(cols["code"]["type"] == "TEXT", f"code 타입 affinity=TEXT (실제 {cols['code']['type']})")
    check(cols["code"]["notnull"] == 0, "code notNull=false (엔티티 String? 와 일치)")
    check(cols["code"]["default"] is None, "code DEFAULT 없음 (엔티티와 일치)")
    check("index_character_relationships_code" in idx, "code 유니크 인덱스 생성됨")
    check(idx.get("index_character_relationships_code", {}).get("unique") is True, "해당 인덱스가 UNIQUE")
    check(idx.get("index_character_relationships_code", {}).get("columns") == ["code"], "인덱스 대상 열이 code 단일")
    for keep in ["index_character_relationships_characterId1",
                 "index_character_relationships_characterId2",
                 "index_character_relationships_createdAt",
                 "index_character_relationships_characterId1_characterId2_relationshipType",
                 "index_character_relationships_displayOrder_createdAt",
                 "index_character_relationships_factionId"]:
        check(keep in idx, f"기존 인덱스 유지: {keep}")
    check(len(fks) == 3, f"외래키 3개 유지 (실제 {len(fks)})")
    fk_actions = sorted((f[0], f[3]) for f in fks)
    check(("characters", "CASCADE") in fk_actions and ("factions", "SET NULL") in fk_actions,
          "외래키 삭제 동작(CASCADE/SET NULL) 유지")

    print("\n[5] 유니크 제약이 실제로 작동하는가")
    try:
        dup = codes[0]
        con.execute("INSERT INTO character_relationships (characterId1, characterId2, relationshipType, description, intensity, isBidirectional, displayOrder, createdAt, factionId, code) VALUES (4,5,'테스트','',5,1,0,0,NULL,?)", (dup,))
        check(False, "중복 code 삽입이 거부되어야 함")
    except sqlite3.IntegrityError:
        check(True, "중복 code 삽입이 UNIQUE 제약으로 거부됨")

    print("\n[6] 빈 테이블·재실행 안전성")
    con2 = sqlite3.connect(":memory:")
    build_v41(con2, [])
    n = run_migration(con2, stmts)
    check(n == 0, "행이 없는 DB에서도 오류 없이 완료")
    try:
        for _, s in stmts:
            if "CREATE UNIQUE INDEX" in s:
                con2.execute(s)  # IF NOT EXISTS 재실행
        check(True, "인덱스 생성문 재실행이 IF NOT EXISTS로 안전")
    except sqlite3.OperationalError as e:
        check(False, f"인덱스 재실행 실패: {e}")

    print("\n[6-b] 컬럼이 이미 있는 DB에서 재실행 (중간 빌드를 거친 기기 — 크래시 루프 방지)")
    check("PRAGMA table_info(`character_relationships`)" in block and "hasCode" in block,
          "마이그레이션에 컬럼 존재 가드가 있음")
    con2b = sqlite3.connect(":memory:")
    build_v41(con2b, [(1, 1, 2, "친구", "", 5, 1, 0, 1, None)])
    run_migration(con2b, stmts)          # 1차
    first = [r[0] for r in con2b.execute("SELECT code FROM character_relationships ORDER BY id")]
    try:
        run_migration(con2b, stmts)      # 2차 (컬럼 이미 존재)
        second = [r[0] for r in con2b.execute("SELECT code FROM character_relationships ORDER BY id")]
        check(True, "컬럼이 있는 상태에서 재실행해도 오류 없음")
        check(first == second, "재실행이 기존 code를 바꾸지 않음(멱등)")
    except sqlite3.OperationalError as e:
        check(False, f"재실행 실패(크래시 루프 위험): {e}")

    print("\n[7] 대조군 — 이미 배포된 MIGRATION_34_35를 같은 하네스로 검증 (하네스 신뢰성)")
    block35 = extract_migration_block(src, "MIGRATION_34_35")
    stmts35 = extract_sql(block35)
    con3 = sqlite3.connect(":memory:")
    con3.execute("""CREATE TABLE `timeline_events` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `year` INTEGER NOT NULL, `description` TEXT NOT NULL)""")
    con3.execute("""CREATE TABLE `character_state_changes` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `year` INTEGER NOT NULL)""")
    con3.execute("""CREATE TABLE `character_relationship_changes` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `year` INTEGER NOT NULL)""")
    con3.execute("INSERT INTO timeline_events (id, year, description) VALUES (1, 100, 'e')")
    # 34→35는 Kotlin for 루프 + $table/$indexName 보간을 쓴다 — 루프를 그대로 재현한다
    tables35 = [
        ("timeline_events", "index_timeline_events_code"),
        ("character_state_changes", "index_character_state_changes_code"),
        ("character_relationship_changes", "index_character_relationship_changes_code"),
    ]
    for tbl, idxname in tables35:
        for _, s in stmts35:
            if "SET code = ?" in s:
                continue
            con3.execute(s.replace("$table", tbl).replace("$indexName", idxname))
        ids = [r[0] for r in con3.execute(f"SELECT id FROM `{tbl}` WHERE code IS NULL")]
        for rid in ids:
            con3.execute(f"UPDATE `{tbl}` SET code = ? WHERE id = ?", (gen_code(), rid))
    c35, i35, _ = schema_of(con3, "timeline_events")
    check(c35["code"]["type"] == "TEXT" and c35["code"]["notnull"] == 0,
          "배포된 34→35도 동일한 형태(TEXT nullable) — 41→42가 검증된 선례와 일치")
    check("index_timeline_events_code" in i35, "배포된 34→35의 인덱스 규약도 동일")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}/{CHECKS}건:")
        for f in FAILS:
            print("  - " + f)
        return 1
    print(f"전체 통과 — 검사 {CHECKS}건")
    return 0


if __name__ == "__main__":
    sys.exit(main())
