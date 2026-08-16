#!/usr/bin/env python3
"""
Room 마이그레이션 44→45 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: `novel_field_values` — 작품 커스텀 필드(확-3)의 값 테이블.

`verify_room_migration_44.py`와 같은 원칙을 따른다:
 1. 테스트가 SQL을 복사해 갖고 있으면 코드와 어긋난다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. v44 테이블은 실제 배포된 정의와 동일하게 만든다.
 3. 마이그레이션 후 스키마가 **Room이 엔티티에서 기대하는 형태**와 일치하는지 PRAGMA로 대조한다
    (컬럼명·NOT NULL·인덱스명·유니크까지 — 하나라도 어긋나면 Room이 시작 시 거부한다).
 4. FK CASCADE가 실제로 동작하는지 실행으로 확인한다. 이 표를 통합 테이블이 아니라 세 번째
    테이블로 만든 **유일한 이유가 CASCADE**이므로, 그것이 안 걸리면 설계 근거가 사라진다.
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
ENTITY = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model/NovelFieldValue.kt"
DAO = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/dao/NovelFieldValueDao.kt"

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


def db_version_at_least(src, minimum):
    """
    버전을 '이상'으로 보는 이유는 44 하네스의 주석 그대로다 — 못 박으면 다음 스키마 상향이
    이 하네스를 상시 실패로 만든다(43 하네스가 실제로 그렇게 됐다).
    """
    m = re.search(r"version\s*=\s*(\d+)", src)
    return m is not None and int(m.group(1)) >= minimum


def extract_migration_sql(src):
    """MIGRATION_44_45 블록의 execSQL 인자를 순서대로 뽑는다 (SQL을 복사하지 않기 위해)."""
    start = src.index("private val MIGRATION_44_45 = object : Migration(")
    # 경계는 **다음 마이그레이션 블록**이다. `fun getDatabase(`로 잡으면 뒤에 새 마이그레이션이
    # 들어오는 순간 그것까지 함께 추출되어, 이 하네스가 만들지도 않은 표에 대고 SQL을 돌리다
    # 죽는다(2026.08.03 MIGRATION_46_47 추가 때 실제로 그렇게 됐다). 42~44판은 이미 이 형태다.
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase(", start)
    block = src[start:end]
    # execSQL(""" ... """)  와  execSQL(" ... ")  둘 다 받는다
    triple = re.findall(r'execSQL\(\s*"""(.*?)"""\s*\)', block, re.S)
    single = re.findall(r'execSQL\(\s*"([^"\n]+)"\s*\)', block)
    return triple + single, block


def main():
    src = open(APPDB, encoding="utf-8").read()
    entity_src = open(ENTITY, encoding="utf-8").read()
    dao_src = open(DAO, encoding="utf-8").read()

    print("=" * 70)
    print("Room 마이그레이션 44→45 검증 — novel_field_values (확-3)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 45), "@Database(version)이 45 이상이다")
    check("NovelFieldValue::class" in src, "엔티티가 @Database entities에 등재됐다")
    check("novelFieldValueDao()" in src, "DAO 접근자가 선언됐다")
    check(re.search(r"ALL_MIGRATIONS\b[^=]*=\s*arrayOf\([^)]*?MIGRATION_44_45\b", src, re.S) is not None
                and "addMigrations(*ALL_MIGRATIONS)" in src,
          "MIGRATION_44_45가 등록 목록(ALL_MIGRATIONS)에 있고 그 목록이 그대로 Room에 넘어간다 (빠뜨리면 실행 시 IllegalStateException)")

    stmts, block = extract_migration_sql(src)
    check(len(stmts) >= 4, f"마이그레이션이 표 1 + 인덱스 3을 만든다 (추출 {len(stmts)}문)")

    print("\n[2] v44 스키마에서 실제로 실행")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    # v44 시점에 존재하는 부모 표들 — 이 마이그레이션이 참조하는 것만 만든다
    con.execute("""CREATE TABLE `novels` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL)""")
    con.execute("""CREATE TABLE `field_definitions` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `universeId` INTEGER NOT NULL,
        `key` TEXT NOT NULL, `entityType` TEXT NOT NULL DEFAULT 'character')""")
    try:
        for s in stmts:
            con.execute(s)
        ran = True
    except Exception as e:  # noqa: BLE001
        ran = False
        print(f"    실행 실패: {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[3] 스키마가 Room 기대와 일치하는가")
    cols = {r[1]: r for r in con.execute("PRAGMA table_info(`novel_field_values`)")}
    check(set(cols) == {"id", "novelId", "fieldDefinitionId", "value"},
          f"컬럼 집합이 엔티티와 같다 (실제: {sorted(cols)})")
    # notnull 플래그는 PRAGMA의 4번째 값. 엔티티가 non-null 선언이면 NOT NULL이어야 한다.
    for c in ("novelId", "fieldDefinitionId", "value"):
        check(cols[c][3] == 1, f"`{c}`가 NOT NULL이다 (엔티티가 non-null 선언)")
    check(cols["id"][5] == 1, "`id`가 기본키다")
    check("val value: String = \"\"" in entity_src,
          "엔티티의 value가 non-null이다 (마이그레이션의 NOT NULL과 짝)")

    idx = {r[1]: r for r in con.execute("PRAGMA index_list(`novel_field_values`)")}
    check("index_novel_field_values_novelId" in idx, "novelId 인덱스가 있다")
    check("index_novel_field_values_fieldDefinitionId" in idx, "fieldDefinitionId 인덱스가 있다")
    uniq = "index_novel_field_values_novelId_fieldDefinitionId"
    check(uniq in idx and idx[uniq][2] == 1,
          "(novelId, fieldDefinitionId) 유니크 인덱스가 있다 — 같은 작품·필드에 값이 둘 생기지 않는다")

    print("\n[4] FK CASCADE가 실제로 동작하는가 (이 표를 따로 둔 유일한 근거)")
    con.execute("INSERT INTO novels(id, title) VALUES (1, 'N')")
    con.execute("INSERT INTO field_definitions(id, universeId, `key`, entityType) VALUES (10, 1, 'genre', 'novel')")
    con.execute("INSERT INTO novel_field_values(novelId, fieldDefinitionId, value) VALUES (1, 10, '판타지')")
    check(con.execute("SELECT COUNT(*) FROM novel_field_values").fetchone()[0] == 1, "값이 들어간다")

    con.execute("DELETE FROM novels WHERE id = 1")
    check(con.execute("SELECT COUNT(*) FROM novel_field_values").fetchone()[0] == 0,
          "작품을 지우면 값이 함께 지워진다 (novelId CASCADE)")

    con.execute("INSERT INTO novels(id, title) VALUES (2, 'N2')")
    con.execute("INSERT INTO novel_field_values(novelId, fieldDefinitionId, value) VALUES (2, 10, 'SF')")
    con.execute("DELETE FROM field_definitions WHERE id = 10")
    check(con.execute("SELECT COUNT(*) FROM novel_field_values").fetchone()[0] == 0,
          "필드 정의를 지우면 값이 함께 지워진다 (fieldDefinitionId CASCADE)")

    print("\n[5] 유니크 제약이 REPLACE로 동작하는가 (DAO의 OnConflictStrategy와 짝)")
    con.execute("INSERT INTO field_definitions(id, universeId, `key`, entityType) VALUES (11, 1, 'genre', 'novel')")
    con.execute("INSERT OR REPLACE INTO novel_field_values(novelId, fieldDefinitionId, value) VALUES (2, 11, 'a')")
    con.execute("INSERT OR REPLACE INTO novel_field_values(novelId, fieldDefinitionId, value) VALUES (2, 11, 'b')")
    rows = con.execute("SELECT value FROM novel_field_values WHERE novelId=2 AND fieldDefinitionId=11").fetchall()
    check(len(rows) == 1 and rows[0][0] == "b",
          "같은 (작품, 필드)에 두 번 넣으면 덮어쓴다 — 행이 둘로 늘지 않는다")
    check("OnConflictStrategy.REPLACE" in dao_src, "DAO의 insertAll이 REPLACE 전략이다 (위 동작과 짝)")

    print("\n[6] 커버 집합 규약 (R-5) — 폼이 렌더한 것만 갈아끼운다")
    check("replaceForFields" in dao_src, "DAO에 커버 집합 교체 경로가 있다")
    # 값이 아니라 **통로**를 본다 (B-245 · R-54). 종전에는 `chunked(900)` 리터럴을 찾았는데,
    # 그러면 이 하네스가 *값이 여기 적혀 있는가*를 요구하는 셈이라 **단일 소스로 모으는 수리를
    # 스스로 막는다** — 실제로 B-245가 그 리터럴을 걷자 이 줄이 빨간불이 됐다.
    # 지켜야 할 것은 900이라는 글자가 아니라 *상한 아래로 나뉘는가*이고, 그 답은 통로가 든다.
    check("SqlInChunks.each" in dao_src, "삭제가 SqlInChunks 통로를 지난다 (SQLite 변수 상한)")
    check("deleteByNovelAndFields" in dao_src, "필드 한정 삭제가 있다 (전량 삭제가 아니다)")

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
