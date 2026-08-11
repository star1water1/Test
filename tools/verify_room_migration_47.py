#!/usr/bin/env python3
"""
Room 마이그레이션 46→47 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: `characters.representativeImagePath` — 캐릭터 대표 이미지(B-103 결정 D1).

`verify_room_migration_46.py`와 같은 원칙을 따른다:
 1. 테스트가 SQL을 복사해 갖고 있으면 코드와 어긋난다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. v46 `characters` 표는 실제 배포된 정의와 동일하게 만든다.
 3. 마이그레이션 후 스키마가 **Room이 엔티티에서 기대하는 형태**와 일치하는지 PRAGMA로 대조한다
    (NOT NULL·기본값까지 — 어긋나면 Room이 시작 시 스키마 불일치로 거부한다).
 4. **기존 행이 실제로 빈 문자열을 받는가**를 실행으로 확인한다. 이 마이그레이션의 값어치가
    거기 있다 — NOT NULL 칸을 기본값 없이 더하면 기존 행에 null이 남아 Room이 읽는 순간
    죽고, 기본값을 `'0'` 같은 것으로 두면 **아무도 고르지 않은 이미지가 대표로 심긴다**.
 5. 표 하나만 바뀌는 순수 추가이므로 **다른 표가 건드려지지 않았는지**도 함께 본다.
"""
import os
import re
import sqlite3

# 저장소 루트 — **스크립트 위치에서 유도한다**(`tools/`의 부모).
# 종전에는 `/home/user/Test`가 박혀 있어 **다른 경로로 체크아웃하면 어디서도 못 돌았다**;
# 개발 컨테이너의 경로와 우연히 같아 로컬에서만 통과했고, 2026.08.04에 이 하네스를
# CI에 걸면서 처음 드러났다(세션 로그 1-bw). 셸 검사들이 쓰는 규약과 같다.
REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
ENTITY = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model/Character.kt"

FAILS = []
CHECKS = 0

# v46 시점의 `characters` 정의 — 이 마이그레이션이 손대는 표.
V46_CHARACTERS = """CREATE TABLE `characters` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `name` TEXT NOT NULL,
    `firstName` TEXT NOT NULL,
    `lastName` TEXT NOT NULL,
    `anotherName` TEXT NOT NULL,
    `novelId` INTEGER,
    `imagePaths` TEXT NOT NULL,
    `createdAt` INTEGER NOT NULL,
    `updatedAt` INTEGER NOT NULL,
    `memo` TEXT NOT NULL,
    `code` TEXT NOT NULL,
    `displayOrder` INTEGER NOT NULL,
    `isPinned` INTEGER NOT NULL,
    FOREIGN KEY(`novelId`) REFERENCES `novels`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
)"""

V46_COLUMNS = {
    "id", "name", "firstName", "lastName", "anotherName", "novelId", "imagePaths",
    "createdAt", "updatedAt", "memo", "code", "displayOrder", "isPinned",
}


def check(cond, msg):
    global CHECKS
    CHECKS += 1
    if not cond:
        FAILS.append(msg)
        print(f"  ✗ {msg}")
    else:
        print(f"  ✓ {msg}")


def db_version_at_least(src, minimum):
    """버전은 '이상'으로 본다 — 못 박으면 다음 스키마 상향이 이 하네스를 상시 실패로 만든다."""
    m = re.search(r"version\s*=\s*(\d+)", src)
    return m is not None and int(m.group(1)) >= minimum


def extract_migration_sql(src):
    """MIGRATION_46_47 블록의 execSQL 인자를 순서대로 뽑는다 (SQL을 복사하지 않기 위해)."""
    start = src.index("private val MIGRATION_46_47 = object : Migration(")
    # 경계는 **다음 마이그레이션 블록**이다 — 뒤에 48이 들어와도 이 하네스가 그것까지
    # 추출하지 않는다. 45·46판이 `fun getDatabase(`로 잡고 있다가 이 47이 들어오는 순간
    # 둘 다 깨졌고, 그래서 셋을 함께 이 형태로 맞췄다(42~44판은 원래 이 형태다).
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase(", start)
    block = src[start:end]
    triple = re.findall(r'execSQL\(\s*"""(.*?)"""\s*\)', block, re.S)
    single = re.findall(r'execSQL\(\s*"([^"\n]+)"\s*\)', block)
    return triple + single, block


def main():
    src = open(APPDB, encoding="utf-8").read()
    entity_src = open(ENTITY, encoding="utf-8").read()

    print("=" * 70)
    print("Room 마이그레이션 46→47 검증 — characters.representativeImagePath (B-103)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 47), "@Database(version)이 47 이상이다")
    check(re.search(r"ALL_MIGRATIONS\b[^=]*=\s*arrayOf\([^)]*?MIGRATION_46_47\b", src, re.S) is not None
                and "addMigrations(*ALL_MIGRATIONS)" in src,
          "MIGRATION_46_47이 등록 목록(ALL_MIGRATIONS)에 있고 그 목록이 그대로 Room에 넘어간다 (빠뜨리면 실행 시 IllegalStateException)")
    check("val representativeImagePath: String = \"\"" in entity_src,
          "엔티티가 non-null + 기본값 \"\"로 선언했다 (마이그레이션의 NOT NULL DEFAULT ''와 짝)")

    stmts, block = extract_migration_sql(src)
    check(len(stmts) == 1, f"칸 하나를 더하는 한 문장이다 (추출 {len(stmts)}문)")
    check("ALTER TABLE" in " ".join(stmts).upper(),
          "표를 다시 만들지 않고 ALTER TABLE로 더한다 (재작성은 기존 행을 위험에 놓는다)")

    print("\n[2] v46 스키마에서 실제로 실행")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("""CREATE TABLE `novels` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL)""")
    con.execute(V46_CHARACTERS)
    # 마이그레이션 전에 들어 있던 행 — 업그레이드하는 사용자의 데이터다.
    con.execute("INSERT INTO novels(id, title) VALUES (1, 'N')")
    con.execute("""INSERT INTO characters(
        id, name, firstName, lastName, anotherName, novelId, imagePaths,
        createdAt, updatedAt, memo, code, displayOrder, isPinned)
        VALUES (1, '기존 캐릭터', '', '', '', 1,
                '["/files/char_a.jpg","/files/char_b.jpg"]', 0, 0, '', 'c001', 0, 0)""")
    con.execute("""INSERT INTO characters(
        id, name, firstName, lastName, anotherName, novelId, imagePaths,
        createdAt, updatedAt, memo, code, displayOrder, isPinned)
        VALUES (2, '이미지 없는 캐릭터', '', '', '', NULL, '[]', 0, 0, '', 'c002', 0, 0)""")

    try:
        for s in stmts:
            con.execute(s)
        ran = True
    except Exception as e:  # noqa: BLE001
        ran = False
        print(f"    실행 실패: {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[3] 스키마가 Room 기대와 일치하는가")
    cols = {r[1]: r for r in con.execute("PRAGMA table_info(`characters`)")}
    check(set(cols) == V46_COLUMNS | {"representativeImagePath"},
          f"컬럼 집합이 엔티티와 같다 (실제: {sorted(cols)})")
    rep = cols.get("representativeImagePath")
    check(rep is not None and rep[2].upper() == "TEXT", "타입이 TEXT다")
    check(rep is not None and rep[3] == 1,
          "NOT NULL이다 — 엔티티가 non-null 선언이므로 어긋나면 Room이 시작 시 거부한다")
    check(rep is not None and rep[4] in ("''", '""'),
          f"기본값이 빈 문자열이다 (실제: {rep[4] if rep else None})")

    print("\n[4] 기존 행이 '지정 없음'으로 온다 (소급 지정이 없어야 한다)")
    rows = dict(con.execute("SELECT id, representativeImagePath FROM characters"))
    check(rows.get(1) == "", "이미지가 있던 기존 캐릭터도 대표는 빈 문자열이다")
    check(rows.get(2) == "", "이미지가 없던 캐릭터도 빈 문자열이다")
    check(con.execute(
        "SELECT COUNT(*) FROM characters WHERE representativeImagePath IS NULL").fetchone()[0] == 0,
        "null인 행이 하나도 없다 — 있으면 Room이 읽는 순간 죽는다")
    # 0번을 심지 않는 것이 확정 사항이다: 지금까지 '첫 번째'는 암묵적 기본값이었을 뿐
    # 사용자가 고른 적이 없다. 심으면 정하지 않은 것을 정해 주는 것이 된다.
    check(all("/files/" not in (v or "") for v in rows.values()),
          "어떤 행에도 이미지 경로가 심기지 않았다 (0번을 대표로 소급하지 않는다)")

    print("\n[5] 다른 표는 건드려지지 않았다")
    check([r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='novels'")] == ["novels"],
        "novels 표가 그대로다")
    check(con.execute("SELECT COUNT(*) FROM characters").fetchone()[0] == 2,
          "행이 사라지거나 늘지 않았다")

    print("\n[6] 마이그레이션 후 쓰기가 동작한다")
    con.execute("UPDATE characters SET representativeImagePath = '/files/char_b.jpg' WHERE id = 1")
    check(con.execute(
        "SELECT representativeImagePath FROM characters WHERE id = 1").fetchone()[0]
        == "/files/char_b.jpg", "대표 지정이 저장된다")
    con.execute("UPDATE characters SET representativeImagePath = '' WHERE id = 1")
    check(con.execute(
        "SELECT representativeImagePath FROM characters WHERE id = 1").fetchone()[0] == "",
        "해제(빈 문자열)가 저장된다")

    con.close()
    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f"  - {f}")
        raise SystemExit(1)
    print(f"전체 통과 — 검사 {CHECKS}건")


if __name__ == "__main__":
    main()
