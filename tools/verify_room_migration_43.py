#!/usr/bin/env python3
"""
Room 마이그레이션 42→43 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: `trash_snapshots.operationId` — 휴지통이 캐릭터 전용에서 세계관·작품·세력·사건까지
확대되면서(B-1) 한 번의 삭제가 만드는 스냅샷이 하나가 아니게 됐다. 묶음을 중간에서 자르면
반쪽 백업이 되므로 정리·복원 단위를 '삭제 작업'으로 올린다(B-14).

`verify_room_migration.py`(41→42)와 같은 원칙을 따른다:
 1. 테스트가 SQL을 복사해 갖고 있으면 코드와 어긋난다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. v42 테이블은 실제 배포된 마이그레이션(v32→33 생성 + 이후 변경)과 동일한 정의로 만든다.
 3. 마이그레이션 후 스키마가 **Room이 엔티티에서 기대하는 형태**와 일치하는지 PRAGMA로 대조한다.
 4. 대조군: 이미 배포되어 정상 동작 중인 MIGRATION_40_41을 같은 하네스로 돌려 하네스 자체를 검증한다.

추가로 이 하네스는 **작업 키의 SQL 표현**(`COALESCE(operationId, 'row:' || id)`)이
Kotlin 쪽 `TrashSnapshot.operationKey`와 같은 문자열을 만드는지 실제 SQLite로 확인한다 —
둘이 어긋나면 정리의 보호 목록이 빗나가 방금 만든 백업을 태운다(R-3가 막으려는 결함).
"""
import re
import sqlite3
import sys

REPO = "/home/user/Test"
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
ENTITY = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model/TrashSnapshot.kt"
DAO = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/dao/TrashSnapshotDao.kt"

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


def extract_migration_block(src, name):
    start = src.index(f"private val {name} = object : Migration(")
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase", start)
    return src[start:end]


def extract_sql(block):
    """execSQL("...") / execSQL(\"\"\"...\"\"\") 문장을 순서대로 추출."""
    stmts = []
    for m in re.finditer(r'execSQL\(\s*"""(.*?)"""', block, re.S):
        stmts.append((m.group(1).strip(), m.start()))
    for m in re.finditer(r'execSQL\(\s*"((?:[^"\\]|\\.)*)"', block):
        s = m.group(1)
        if s.strip():
            stmts.append((s.replace('\\"', '"'), m.start()))
    stmts.sort(key=lambda t: t[1])
    return [s for s, _ in stmts]


# v42 시점의 trash_snapshots 정의 — 배포된 MIGRATION_32_33의 생성 SQL 그대로.
# FK는 설계상 없다(원본 삭제 CASCADE와 무관하게 살아남아야 하므로).
V42_TABLE = """
CREATE TABLE `trash_snapshots` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `entityType` TEXT NOT NULL,
    `entityName` TEXT NOT NULL,
    `payload` TEXT NOT NULL,
    `imagePaths` TEXT NOT NULL,
    `deletedAt` INTEGER NOT NULL
)
"""
V42_INDICES = [
    "CREATE INDEX `index_trash_snapshots_deletedAt` ON `trash_snapshots` (`deletedAt`)",
]


def build_v42(con, rows):
    con.execute(V42_TABLE)
    for s in V42_INDICES:
        con.execute(s)
    for r in rows:
        con.execute(
            "INSERT INTO trash_snapshots (id, entityType, entityName, payload, imagePaths, deletedAt) "
            "VALUES (?,?,?,?,?,?)", r)
    con.commit()


def run_migration(con, stmts, table="trash_snapshots", guarded_col="operationId"):
    """Kotlin의 execSQL + 컬럼 존재 가드를 파이썬으로 그대로 재현."""
    have = {r[1] for r in con.execute(f"PRAGMA table_info(`{table}`)")}
    for sql in stmts:
        if "ADD COLUMN" in sql and guarded_col in sql and guarded_col in have:
            continue  # Kotlin의 hasOperationId 가드 재현
        con.execute(sql)
    con.commit()


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


ROWS = [
    (1, "character", "가온", "{}", "[]", 1000),
    (2, "character", "나린", "{}", '["/a/b.jpg"]', 2000),
    (3, "character", "다솜", "{}", "[]", 3000),
]


def main():
    src = open(APPDB, encoding="utf-8").read()
    ent = open(ENTITY, encoding="utf-8").read()
    dao = open(DAO, encoding="utf-8").read()

    print("[1] 마이그레이션 SQL을 AppDatabase.kt에서 추출")
    block = extract_migration_block(src, "MIGRATION_42_43")
    stmts = extract_sql(block)
    check(len(stmts) >= 2, f"execSQL 문장 {len(stmts)}개 추출 (ADD COLUMN + CREATE INDEX)")
    check(any("ADD COLUMN" in s and "operationId" in s for s in stmts), "operationId 컬럼 추가문 존재")
    check(any("index_trash_snapshots_operationId" in s for s in stmts), "Room 명명 규약대로 인덱스 생성문 존재")
    check(all("DROP TABLE" not in s and "DELETE FROM" not in s for s in stmts),
          "파괴적 문장(DROP/DELETE) 없음")
    add_col = next(s for s in stmts if "ADD COLUMN" in s and "operationId" in s)
    check("NOT NULL" not in add_col, "ADD COLUMN에 NOT NULL 없음 (엔티티가 String?)")
    check("DEFAULT" not in add_col, "ADD COLUMN에 DEFAULT 없음 (@ColumnInfo(defaultValue) 미사용)")
    check("hasOperationId" in block and "PRAGMA table_info" in block,
          "컬럼 존재 가드가 있음 (중간 빌드를 거친 기기의 크래시 루프 방지)")
    check("version = 43" in src, "@Database(version = 43)로 상향됨")
    check("MIGRATION_42_43)" in src or "MIGRATION_42_43," in src, "addMigrations에 등록됨")

    print("\n[2] 엔티티 선언이 마이그레이션 결과와 같은 형태인가")
    check("val operationId: String? = null" in ent, "엔티티가 nullable operationId 선언")
    check('Index("operationId")' in ent, "엔티티에 operationId 인덱스 선언")
    check("val operationKey: String get() = operationId ?: legacyOperationKey(id)" in ent,
          "Kotlin 쪽 작업 키가 operationId 폴백 형태")
    check('fun legacyOperationKey(id: Long): String = "row:$id"' in ent,
          "구버전 행의 작업 키가 'row:<id>' 형식")
    check("COALESCE(operationId, 'row:' || id)" in dao,
          "DAO의 SQL 표현이 Kotlin 폴백과 같은 형식")

    print("\n[3] 실제 SQLite에서 v42 → 마이그레이션 실행")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    build_v42(con, ROWS)
    before = list(con.execute(
        "SELECT id, entityType, entityName, payload, imagePaths, deletedAt FROM trash_snapshots ORDER BY id"))
    run_migration(con, stmts)
    after = list(con.execute(
        "SELECT id, entityType, entityName, payload, imagePaths, deletedAt FROM trash_snapshots ORDER BY id"))
    ops = [r[0] for r in con.execute("SELECT operationId FROM trash_snapshots ORDER BY id")]

    check(before == after, "기존 열 데이터가 한 글자도 변하지 않음")
    check(all(o is None for o in ops),
          "기존 행의 operationId는 NULL로 남는다 (백필하지 않는다 — NULL이 이미 '독립 작업'이라는 올바른 의미다)")

    print("\n[4] 마이그레이션 후 스키마가 Room 기대와 일치하는가")
    cols, idx, fks = schema_of(con, "trash_snapshots")
    check("operationId" in cols, "operationId 열 존재")
    check(cols["operationId"]["type"] == "TEXT", f"타입 affinity=TEXT (실제 {cols['operationId']['type']})")
    check(cols["operationId"]["notnull"] == 0, "notNull=false (엔티티 String? 와 일치)")
    check(cols["operationId"]["default"] is None, "DEFAULT 없음 (엔티티와 일치)")
    check("index_trash_snapshots_operationId" in idx, "operationId 인덱스 생성됨")
    check(idx.get("index_trash_snapshots_operationId", {}).get("unique") is False,
          "그 인덱스는 UNIQUE가 아니다 (한 작업에 여러 행이 들어간다)")
    check(idx.get("index_trash_snapshots_operationId", {}).get("columns") == ["operationId"],
          "인덱스 대상 열이 operationId 단일")
    check("index_trash_snapshots_deletedAt" in idx, "기존 deletedAt 인덱스 유지")
    check(len(fks) == 0, f"외래키 없음 유지 (실제 {len(fks)}) — 원본 삭제와 무관히 살아남아야 한다")

    print("\n[5] 작업 키의 SQL 표현이 Kotlin과 같은 문자열을 만드는가")
    # 어긋나면 정리의 보호 목록이 빗나가 방금 만든 백업을 태운다 (R-3).
    con.execute("INSERT INTO trash_snapshots (id, entityType, entityName, payload, imagePaths, deletedAt, operationId) "
                "VALUES (10,'universe','아스테리아','{}','[]',5000,'OP-1')")
    con.execute("INSERT INTO trash_snapshots (id, entityType, entityName, payload, imagePaths, deletedAt, operationId) "
                "VALUES (11,'character','가온','{}','[]',5001,'OP-1')")
    keys = dict(con.execute("SELECT id, COALESCE(operationId, 'row:' || id) FROM trash_snapshots ORDER BY id"))
    check(keys[1] == "row:1", f"구버전 행 → 'row:1' (실제 {keys[1]})")
    check(keys[10] == "OP-1" and keys[11] == "OP-1", "같은 작업의 두 행이 같은 키")

    print("\n[6] 작업 단위 집계 쿼리가 DAO 선언대로 동작하는가")
    groups = list(con.execute(
        "SELECT COALESCE(operationId, 'row:' || id) AS opKey, MAX(deletedAt) AS newestAt, COUNT(*) AS itemCount "
        "FROM trash_snapshots GROUP BY opKey ORDER BY newestAt ASC, opKey ASC"))
    check(len(groups) == 4, f"작업 4건으로 접힘 (구버전 3 + OP-1) — 실제 {len(groups)}")
    op1 = [g for g in groups if g[0] == "OP-1"][0]
    check(op1[2] == 2, f"OP-1의 항목 수 2 (실제 {op1[2]})")
    check(op1[1] == 5001, f"OP-1의 시각은 가장 최근 스냅샷 (실제 {op1[1]})")
    check([g[0] for g in groups][-1] == "OP-1", "정렬이 오래된 작업부터")
    by_op = list(con.execute(
        "SELECT id FROM trash_snapshots WHERE COALESCE(operationId, 'row:' || id) = 'OP-1' ORDER BY deletedAt ASC, id ASC"))
    check([r[0] for r in by_op] == [10, 11], "작업으로 행 조회가 정확")

    print("\n[7] 빈 테이블·재실행 안전성")
    con2 = sqlite3.connect(":memory:")
    build_v42(con2, [])
    run_migration(con2, stmts)
    check(True, "행이 없는 DB에서도 오류 없이 완료")
    try:
        for s in stmts:
            if "CREATE INDEX" in s:
                con2.execute(s)
        check(True, "인덱스 생성문 재실행이 IF NOT EXISTS로 안전")
    except sqlite3.OperationalError as e:
        check(False, f"인덱스 재실행 실패: {e}")

    print("\n[7-b] 컬럼이 이미 있는 DB에서 재실행 (중간 빌드를 거친 기기 — 크래시 루프 방지)")
    con3 = sqlite3.connect(":memory:")
    build_v42(con3, ROWS)
    run_migration(con3, stmts)
    con3.execute("UPDATE trash_snapshots SET operationId = 'KEEP' WHERE id = 1")
    con3.commit()
    try:
        run_migration(con3, stmts)
        check(True, "컬럼이 있는 상태에서 재실행해도 오류 없음")
    except sqlite3.OperationalError as e:
        check(False, f"재실행 실패: {e}")
    kept = list(con3.execute("SELECT operationId FROM trash_snapshots WHERE id = 1"))[0][0]
    check(kept == "KEEP", "재실행이 기존 operationId를 바꾸지 않음(멱등)")

    print("\n[8] 대조군 — 이미 배포된 MIGRATION_40_41을 같은 하네스로 검증 (하네스 신뢰성)")
    block41 = extract_migration_block(src, "MIGRATION_40_41")
    stmts41 = extract_sql(block41)
    check(len(stmts41) >= 3, f"배포된 40→41도 같은 방식으로 추출됨 ({len(stmts41)}문)")
    con4 = sqlite3.connect(":memory:")
    con4.execute("CREATE TABLE `field_definitions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
    for s in stmts41:
        con4.execute(s)
    cols41, idx41, _ = schema_of(con4, "field_value_entries")
    check("code" in cols41 and cols41["code"]["notnull"] == 1,
          "배포된 40→41은 NOT NULL code — 42→43의 nullable과 대비되는 정상 사례")
    check("index_field_value_entries_code" in idx41, "배포된 40→41의 인덱스 규약도 동일")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f" - {f}")
        sys.exit(1)
    print(f"전체 통과 — 검사 {CHECKS}건")


if __name__ == "__main__":
    main()
