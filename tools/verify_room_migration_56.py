#!/usr/bin/env python3
"""
Room 마이그레이션 55→56 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: 대결 축의 **기준 축 표식**(B-104 소비처 ⓑ·ⓒ) — `duel_axes.isBasisAxis`.
설계 정본은 `docs/duel_system_design_2026-08.md` 13-5다.

**이 마이그레이션에서 값어치가 가장 큰 검사는 "NOT NULL + DEFAULT 0인가"다** —
형제 칸들과 **정반대**라 그렇다. `candidateFiltersJson`(54→55)·`sortDuelAxisCode`(50→51)는
*없는 값*과 *빈 값*을 갈라야 해서 nullable에 기본값이 없는데, 이쪽은 **스위치라 중간 상태가
없다.** 여기서 NULL을 허용하면 *"기준 아님"*의 표기가 NULL과 0 둘이 되고, 엔티티는
non-null `Boolean`이라 Room이 시작 시 기대 스키마와 어긋난 것을 발견해 **앱을 거부한다** —
사용자 쪽에서는 업데이트 직후 앱이 안 뜨는 모양이고 되돌릴 길이 없다.

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
    v55 `duel_axes`도 **그 표를 만든 블록(48→49)과 이후 ALTER(49→50 · 51→52 · 54→55)**에서
    추출한다(두 벌로 적으면 한쪽이 낡는다).
 2. 마이그레이션 후 컬럼 집합이 **엔티티가 선언한 것**과 일치하는가.
 3. 기존 축·판이 살아남고 새 칸이 **0**이 되는가 — *"지정 없음이 기본"*이다.
    소급해서 아무 축이나 기준으로 세우면 사용자가 지정한 적 없는 기준으로 대표 그림이 바뀐다.
 4. 새 칸이 **NOT NULL이고 기본값이 0**인가(이 판의 요점 — 위 참조).
 5. 유니크 인덱스 셋이 그대로인가 — ADD COLUMN은 표를 재작성하지 않으므로 살아 있어야 한다.
 6. **세계관당 하나**를 DB가 아니라 쓰기가 지킨다는 사실 — 스키마에 유니크 제약이 **없어야**
    한다(있으면 *"켜진 것이 없음"*이 정상인 이 칸에서 부분 인덱스가 필요해지고, 그 문법은
    Room 스키마 대조와 어긋난다). 대신 저장소·가져오기가 형제를 내리는지는 소스로 본다.
 7. 기준 축을 담은 축의 FK CASCADE가 그대로 도는가.
"""
import os
import re
import sqlite3

REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"
REPOSITORY = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/repository"
EXCEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/excel"

FAILS = []
CHECKS = 0

AXIS_COLUMNS = {
    "id", "universeId", "name", "targetType", "displayOrder", "createdAt",
    "influenceFieldKeys", "outcomeFieldKeys", "profileFieldKeys",
    "candidateFiltersJson", "isBasisAxis", "code",
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

    경계는 **다음 마이그레이션 블록**이다 — 뒤에 57이 들어와도 그것까지 추출하지 않는다.
    """
    start = src.index(f"private val {name} = object : Migration(")
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase(", start)
    block = src[start:end]
    triple = re.findall(r'execSQL\(\s*"""(.*?)"""\s*\)', block, re.S)
    single = re.findall(r'execSQL\(\s*"([^"\n]+)"\s*\)', block)
    return triple + single


def axis_statements(src):
    """v55 시점의 `duel_axes`를 세우는 문장들 — 만든 블록 + 이후 ALTER 셋."""
    created = [s for s in extract_migration_sql(src, "MIGRATION_48_49")
               if "duel_axes" in s and "duel_matches" not in s and "duel_counter" not in s]
    altered = []
    for name in ("MIGRATION_49_50", "MIGRATION_51_52", "MIGRATION_54_55"):
        altered += [s for s in extract_migration_sql(src, name) if "duel_axes" in s]
    return created + altered


def match_statements(src):
    """판 표 — FK CASCADE 검사(7번)를 위해 함께 세운다."""
    return [s for s in extract_migration_sql(src, "MIGRATION_48_49")
            if "duel_matches" in s]


def indices_of(con, table):
    return {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", (table,))}


def main():
    src = read(APPDB)
    axis_src = read(f"{MODEL}/DuelAxis.kt")

    print("=" * 70)
    print("Room 마이그레이션 55→56 검증 — 대결 기준 축 (B-104 소비처 ⓑ·ⓒ)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 56), "@Database(version)이 56 이상이다")
    check(re.search(r"addMigrations\([^)]*MIGRATION_55_56", src, re.S) is not None,
          "MIGRATION_55_56이 addMigrations에 등록됐다 (빠뜨리면 실행 시 IllegalStateException)")
    check("val isBasisAxis: Boolean = false" in axis_src,
          "엔티티가 isBasisAxis를 **non-null Boolean**으로 선언했다 (스위치라 중간 상태가 없다)")

    stmts = extract_migration_sql(src, "MIGRATION_55_56")
    joined = " ".join(stmts)
    check(len(stmts) == 1, f"칸 하나를 더하는 1문이다 (추출 {len(stmts)}문)")
    check(joined.upper().count("ALTER TABLE") == 1, "ALTER TABLE ADD COLUMN 하나다")
    check("DROP TABLE" not in joined.upper() and "CREATE TABLE" not in joined.upper(),
          "표를 재작성하지 않는 순수 추가다 (재작성하면 인덱스·FK를 다시 세워야 한다)")
    check("NOT NULL" in joined.upper(),
          "NOT NULL을 건다 — 엔티티가 non-null이라 안 걸면 Room이 시작 시 거부한다")
    check(re.search(r"DEFAULT\s+0", joined, re.I) is not None,
          "DEFAULT 0을 준다 — 기존 축은 '기준 아님'이고, 소급해서 기준을 지어 주지 않는다")

    print("\n[2] v55 스키마를 실제로 세우고 마이그레이션을 돌린다")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    # FK 대상 최소 스키마 — 세계관 표는 마이그레이션 밖(초기 스키마)이라 여기서만 세운다.
    con.execute("CREATE TABLE universes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
    setup = axis_statements(src)
    check(len(setup) >= 5, f"v55 duel_axes를 만드는 문장을 찾았다 (추출 {len(setup)}문 — 표 1 + 인덱스 + ALTER 4)")
    for s in setup:
        con.execute(s)
    for s in match_statements(src):
        con.execute(s)

    con.execute("INSERT INTO universes (id, name) VALUES (1, '테스트')")
    con.execute(
        "INSERT INTO duel_axes"
        "(id, universeId, name, targetType, displayOrder, createdAt,"
        " influenceFieldKeys, outcomeFieldKeys, profileFieldKeys, code)"
        " VALUES (1, 1, '아름다움', 'image', 0, 100, '[]', '[]', '[]', 'DAX-1')")
    con.execute(
        "INSERT INTO duel_matches (id, axisId, aCode, bCode, winnerCode, groupId, decidedAt, code)"
        " VALUES (1, 1, '/files/a.jpg', '/files/b.jpg', '/files/a.jpg', NULL, 100, 'DM-1')")

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
    check(cols["isBasisAxis"][3] == 1,
          "isBasisAxis가 **NOT NULL**이다 (이 판에서 가장 값어치가 큰 검사)")
    check(cols["isBasisAxis"][2].upper() == "INTEGER", "isBasisAxis가 INTEGER다 (Room의 Boolean)")
    check(str(cols["isBasisAxis"][4]).strip("'\" ") == "0",
          "기본값이 0이다 — '기준 아님'의 표기가 하나뿐이다")

    print("\n[4] 기존 축·판이 그대로인가")
    row = con.execute(
        "SELECT name, targetType, influenceFieldKeys, isBasisAxis"
        " FROM duel_axes WHERE id = 1").fetchone()
    check(row is not None and row[0] == "아름다움" and row[1] == "image" and row[2] == "[]",
          "마이그레이션 전에 있던 축이 그대로다")
    check(row is not None and row[3] == 0,
          "기존 축의 새 칸이 0이다 — 소급해서 기준을 지어 주지 않는다(대표 그림이 말없이 바뀌면 안 된다)")
    match = con.execute("SELECT aCode, bCode FROM duel_matches WHERE id = 1").fetchone()
    check(match == ("/files/a.jpg", "/files/b.jpg"),
          "쌓인 판이 그대로다 — 기준 칸은 판을 건드리지 않는다")

    print("\n[5] 인덱스와 새 칸의 쓰기")
    check(indices_of(con, "duel_axes") == before,
          "유니크 인덱스 셋(이름·코드)이 그대로다 (ADD COLUMN은 표를 재작성하지 않는다)")
    con.execute(
        "INSERT INTO duel_axes"
        "(id, universeId, name, targetType, displayOrder, createdAt,"
        " influenceFieldKeys, outcomeFieldKeys, profileFieldKeys, isBasisAxis, code)"
        " VALUES (2, 1, '멋짐', 'image', 1, 200, '[]', '[]', '[]', 1, 'DAX-2')")
    row = con.execute("SELECT isBasisAxis FROM duel_axes WHERE id = 2").fetchone()
    check(row == (1,), "기준으로 켠 축이 그대로 저장·재독된다")

    print("\n[6] 세계관당 하나는 **쓰기**가 지킨다 (스키마 제약이 아니다)")
    # 스키마에 유니크가 없어야 한다 — '켜진 것이 없음'이 정상이라 부분 인덱스가 필요한데
    # 그 문법은 Room 스키마 대조와 어긋난다. 그래서 DB는 둘을 받아 준다.
    con.execute(
        "INSERT INTO duel_axes"
        "(id, universeId, name, targetType, displayOrder, createdAt,"
        " influenceFieldKeys, outcomeFieldKeys, profileFieldKeys, isBasisAxis, code)"
        " VALUES (3, 1, '분위기', 'image', 2, 300, '[]', '[]', '[]', 1, 'DAX-3')")
    both = con.execute(
        "SELECT COUNT(*) FROM duel_axes WHERE universeId = 1 AND isBasisAxis = 1").fetchone()[0]
    check(both == 2, "DB 자체는 둘을 막지 않는다 (유일성을 스키마에 걸지 않았다는 사실의 확인)")

    # 그러므로 **쓰기 경로가 지켜야** 한다 — 그것이 소스에 있는지 본다.
    dao_src = read(f"{REPO}/app/src/main/java/com/novelcharacter/app/data/dao/DuelAxisDao.kt")
    repo_src = read(f"{REPOSITORY}/DuelRepository.kt")
    import_src = read(f"{EXCEL}/ExcelImportService.kt")
    trash_src = read(f"{REPOSITORY}/TrashRepository.kt")
    check("clearBasisExcept" in dao_src, "DAO가 형제의 표식을 내리는 질의를 갖는다")
    check("clearBasisExcept" in repo_src,
          "saveAxis가 그것을 부른다 — 화면에서 켜면 형제가 같은 트랜잭션에서 내려간다")
    check("clearBasisExcept" in import_src or "enforceSingleBasisAxis" in import_src,
          "엑셀 가져오기도 유일성을 지킨다 (저장소를 지나지 않는 경로라 빠뜨리기 쉽다)")
    check("isBasisAxis = keepBasis" in trash_src,
          "휴지통 복원이 살아 있는 지정을 뒤엎지 않는다 (되살린 축이 기준을 훔치지 않는다)")

    # **대상 범위가 읽기와 쓰기에서 같아야 한다** — 콜드 검토가 잡은 실결함이다.
    # 내리는 쪽만 대상을 안 가리면 `기준축=Y`가 적힌 **캐릭터 축 행 하나가** 살아 있는
    # 이미지 축의 기준을 조용히 풀고(엑셀 왕복), 축 목록을 드래그해 순서만 바꿔도 같은 일이 난다
    # (`saveAxis`가 축마다 불린다). 사용자가 한 일과 결과 사이에 아무 연결도 없다.
    check(re.search(r"clearBasisExcept\(universeId: Long, targetType: String, exceptId: Long\)", dao_src)
          is not None,
          "clearBasisExcept가 **대상을 받는다** — 읽기(getBasisAxis)와 범위가 같아야 한다")
    check("if (saved.isImageBasis)" in repo_src,
          "saveAxis가 `isBasisAxis`가 아니라 **`isImageBasis`**로 판정한다 (캐릭터 축의 표식은 형제를 내리지 않는다)")
    check("targetType != DuelAxis.TARGET_IMAGE" in import_src,
          "가져오기도 이미지 축일 때만 내린다 (시트는 모든 축 행에 Y/N 드롭다운을 싣는다)")
    check("source.isImageAxis &&" in trash_src,
          "복원의 빗장도 이미지 축일 때만 따진다 (캐릭터 축의 표식은 뺏을 것이 없어 거짓 고지가 된다)")

    print("\n[7] FK CASCADE가 새 칸과 무관하게 그대로 도는가")
    con.execute("DELETE FROM universes WHERE id = 1")
    axes_left = con.execute("SELECT COUNT(*) FROM duel_axes").fetchone()[0]
    matches_left = con.execute("SELECT COUNT(*) FROM duel_matches").fetchone()[0]
    check(axes_left == 0, "세계관을 지우면 기준 축까지 CASCADE로 함께 죽는다")
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
