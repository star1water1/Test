#!/usr/bin/env python3
"""
Room 마이그레이션 45→46 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: `grade_systems` — 세계관 등급 체계(U-1)의 정의 테이블.

`verify_room_migration_45.py`와 같은 원칙을 따른다:
 1. 테스트가 SQL을 복사해 갖고 있으면 코드와 어긋난다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. v45 테이블은 실제 배포된 정의와 동일하게 만든다 (이 마이그레이션이 참조하는 것만).
 3. 마이그레이션 후 스키마가 **Room이 엔티티에서 기대하는 형태**와 일치하는지 PRAGMA로 대조한다
    (컬럼명·NOT NULL·인덱스명·유니크까지 — 하나라도 어긋나면 Room이 시작 시 거부한다).
 4. FK CASCADE와 두 유니크 제약이 실제로 동작하는지 실행으로 확인한다 — 세계관 삭제 시 체계가
    함께 사라지는 것(휴지통 스냅샷의 전제), 이름 해석의 유일성(엑셀 '등급체계' 열의 전제),
    코드 참조의 유일성(config 참조의 전제)이 전부 여기 걸려 있다.
"""
import re
import sqlite3

REPO = "/home/user/Test"
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
ENTITY = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model/GradeSystem.kt"

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
    """버전은 '이상'으로 본다 — 못 박으면 다음 스키마 상향이 이 하네스를 상시 실패로 만든다."""
    m = re.search(r"version\s*=\s*(\d+)", src)
    return m is not None and int(m.group(1)) >= minimum


def extract_migration_sql(src):
    """MIGRATION_45_46 블록의 execSQL 인자를 순서대로 뽑는다 (SQL을 복사하지 않기 위해)."""
    start = src.index("private val MIGRATION_45_46 = object : Migration(")
    # 경계는 **다음 마이그레이션 블록**이다. `fun getDatabase(`로 잡으면 뒤에 새 마이그레이션이
    # 들어오는 순간 그것까지 함께 추출되어, 이 하네스가 만들지도 않은 표에 대고 SQL을 돌리다
    # 죽는다(2026.08.03 MIGRATION_46_47 추가 때 실제로 그렇게 됐다). 42~44판은 이미 이 형태다.
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
    print("Room 마이그레이션 45→46 검증 — grade_systems (U-1)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 46), "@Database(version)이 46 이상이다")
    check("GradeSystem::class" in src, "엔티티가 @Database entities에 등재됐다")
    check("gradeSystemDao()" in src, "DAO 접근자가 선언됐다")
    check(re.search(r"addMigrations\([^)]*MIGRATION_45_46", src, re.S) is not None,
          "MIGRATION_45_46이 addMigrations에 등록됐다 (빠뜨리면 실행 시 IllegalStateException)")

    stmts, block = extract_migration_sql(src)
    check(len(stmts) >= 4, f"마이그레이션이 표 1 + 인덱스 3을 만든다 (추출 {len(stmts)}문)")

    print("\n[2] v45 스키마에서 실제로 실행")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    # v45 시점에 존재하는 부모 표 — 이 마이그레이션이 참조하는 것만 만든다
    con.execute("""CREATE TABLE `universes` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)""")
    try:
        for s in stmts:
            con.execute(s)
        ran = True
    except Exception as e:  # noqa: BLE001
        ran = False
        print(f"    실행 실패: {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[3] 스키마가 Room 기대와 일치하는가")
    cols = {r[1]: r for r in con.execute("PRAGMA table_info(`grade_systems`)")}
    check(set(cols) == {"id", "universeId", "name", "gradesJson", "displayOrder", "createdAt", "code"},
          f"컬럼 집합이 엔티티와 같다 (실제: {sorted(cols)})")
    for c in ("universeId", "name", "gradesJson", "displayOrder", "createdAt", "code"):
        check(cols[c][3] == 1, f"`{c}`가 NOT NULL이다 (엔티티가 non-null 선언)")
    check(cols["id"][5] == 1, "`id`가 기본키다")
    check('val gradesJson: String = "{}"' in entity_src,
          "엔티티의 gradesJson이 non-null이다 (마이그레이션의 NOT NULL과 짝)")

    idx = {r[1]: r for r in con.execute("PRAGMA index_list(`grade_systems`)")}
    check("index_grade_systems_universeId" in idx, "universeId 인덱스가 있다")
    uniq_name = "index_grade_systems_universeId_name"
    check(uniq_name in idx and idx[uniq_name][2] == 1,
          "(universeId, name) 유니크 인덱스가 있다 — 이름 해석(엑셀 '등급체계' 열)이 유일하다")
    uniq_code = "index_grade_systems_code"
    check(uniq_code in idx and idx[uniq_code][2] == 1,
          "code 유니크 인덱스가 있다 — config 참조가 정확히 하나를 가리킨다")

    print("\n[4] FK CASCADE가 실제로 동작하는가 (세계관 스냅샷이 체계를 담는 근거)")
    con.execute("INSERT INTO universes(id, name) VALUES (1, 'U')")
    con.execute("""INSERT INTO grade_systems(universeId, name, gradesJson, displayOrder, createdAt, code)
                   VALUES (1, '전투 등급', '{"C":0.5,"B":1,"A":2,"S":3}', 0, 0, 'gs01')""")
    check(con.execute("SELECT COUNT(*) FROM grade_systems").fetchone()[0] == 1, "체계가 들어간다")

    con.execute("DELETE FROM universes WHERE id = 1")
    check(con.execute("SELECT COUNT(*) FROM grade_systems").fetchone()[0] == 0,
          "세계관을 지우면 체계가 함께 지워진다 (universeId CASCADE)")

    print("\n[5] 유니크 제약이 실제로 막는가")
    con.execute("INSERT INTO universes(id, name) VALUES (2, 'U2')")
    con.execute("INSERT INTO universes(id, name) VALUES (3, 'U3')")
    con.execute("""INSERT INTO grade_systems(universeId, name, gradesJson, displayOrder, createdAt, code)
                   VALUES (2, '전투 등급', '{}', 0, 0, 'gs02')""")
    dup_name = True
    try:
        con.execute("""INSERT INTO grade_systems(universeId, name, gradesJson, displayOrder, createdAt, code)
                       VALUES (2, '전투 등급', '{}', 0, 0, 'gs03')""")
        dup_name = False
    except sqlite3.IntegrityError:
        pass
    check(dup_name, "같은 세계관에 같은 이름의 체계는 거부된다")
    ok_cross = True
    try:
        con.execute("""INSERT INTO grade_systems(universeId, name, gradesJson, displayOrder, createdAt, code)
                       VALUES (3, '전투 등급', '{}', 0, 0, 'gs04')""")
    except sqlite3.IntegrityError:
        ok_cross = False
    check(ok_cross, "다른 세계관에서는 같은 이름을 쓸 수 있다 (유니크는 세계관 안에서만)")
    dup_code = True
    try:
        con.execute("""INSERT INTO grade_systems(universeId, name, gradesJson, displayOrder, createdAt, code)
                       VALUES (3, '다른 이름', '{}', 0, 0, 'gs02')""")
        dup_code = False
    except sqlite3.IntegrityError:
        pass
    check(dup_code, "code 중복은 세계관이 달라도 거부된다 (전역 유일)")

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
