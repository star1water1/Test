#!/usr/bin/env python3
"""
Room 마이그레이션 50→51 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: 목록 프리셋의 **대결 정렬 축**(B-117) — `character_list_presets.sortDuelAxisCode`.
설계 정본은 `docs/duel_system_design_2026-08.md` 9장이다.

**이 마이그레이션에서 값어치가 가장 큰 검사는 "NULL을 허용하는가"다.** 형제 칸
`sortFieldKey`와 같은 부류이고, 뜻이 *"이 프리셋은 대결로 정렬하지 않는다"*라 빈 문자열로
채우면 **코드가 빈 축을 가리키는 상태**와 구별되지 않는다. NOT NULL DEFAULT ''로 잘못 넣으면
Room의 기대 스키마(nullable)와 어긋나 **앱이 시작 시 거부한다** — 사용자 쪽에서는 업데이트
직후 앱이 안 뜨는 모양이고 되돌릴 길이 없다. 스키마를 실제로 돌려 보면 바로 드러나는 사실이다.

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
    v50 `character_list_presets`도 마찬가지로 **그 표를 만든 블록(36→37)과 이후 ALTER(38→39)**
    에서 추출한다(두 벌로 적으면 한쪽이 낡는다).
 2. 마이그레이션 후 컬럼 집합이 **엔티티가 선언한 것**과 일치하는가.
 3. 기존 프리셋이 살아남고 새 칸이 NULL이 되는가 — *"대결 정렬이 아니다"*가 기본이다.
 4. 새 칸이 **nullable**인가(Room이 시작 시 대조하는 항목 — 이 판의 요점).
 5. 이름 유니크 인덱스가 그대로인가 — ADD COLUMN은 표를 재작성하지 않으므로 살아 있어야 한다.
 6. 정렬 종류 목록이 **한 자리**에 있는가 — 엑셀 가져오기가 같은 판정을 해야 한다.
"""
import os
import re
import sqlite3

REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"

FAILS = []
CHECKS = 0

PRESET_COLUMNS = {
    "id", "name", "tagsJson", "fieldFiltersJson", "sortKind", "sortFieldKey",
    "sortAscending", "bodySizePartIndex", "novelIdsJson", "isDefault",
    "createdAt", "updatedAt", "sortDuelAxisCode",
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

    경계는 **다음 마이그레이션 블록**이다 — 뒤에 52가 들어와도 그것까지 추출하지 않는다.
    """
    start = src.index(f"private val {name} = object : Migration(")
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase(", start)
    block = src[start:end]
    triple = re.findall(r'execSQL\(\s*"""(.*?)"""\s*\)', block, re.S)
    single = re.findall(r'execSQL\(\s*"([^"\n]+)"\s*\)', block)
    return triple + single


def preset_statements(src):
    """v50 시점의 `character_list_presets`를 세우는 문장들 — 만든 블록 + 이후 ALTER."""
    created = [s for s in extract_migration_sql(src, "MIGRATION_36_37")
               if "character_list_presets" in s]
    altered = [s for s in extract_migration_sql(src, "MIGRATION_38_39")
               if "character_list_presets" in s]
    return created + altered


def indices_of(con, table):
    return {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", (table,))}


def main():
    src = read(APPDB)
    preset_src = read(f"{MODEL}/CharacterListPreset.kt")

    print("=" * 70)
    print("Room 마이그레이션 50→51 검증 — 목록 프리셋의 대결 정렬 축 (B-117)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 51), "@Database(version)이 51 이상이다")
    check(re.search(r"addMigrations\([^)]*MIGRATION_50_51", src, re.S) is not None,
          "MIGRATION_50_51이 addMigrations에 등록됐다 (빠뜨리면 실행 시 IllegalStateException)")
    check("val sortDuelAxisCode: String? = null" in preset_src,
          "엔티티가 sortDuelAxisCode를 **nullable**로 선언했다")
    check('const val SORT_DUEL = "duel"' in preset_src,
          "정렬 종류 SORT_DUEL이 상수로 있다")
    check("val SORT_KINDS: Set<String>" in preset_src,
          "받아들이는 정렬 종류가 한 자리에 모여 있다 (엑셀 가져오기가 같은 판정을 탄다)")

    stmts = extract_migration_sql(src, "MIGRATION_50_51")
    joined = " ".join(stmts)
    check(len(stmts) == 1, f"칸 하나를 더하는 1문이다 (추출 {len(stmts)}문)")
    check(joined.upper().count("ALTER TABLE") == 1, "ALTER TABLE ADD COLUMN 하나다")
    check("DROP TABLE" not in joined.upper() and "CREATE TABLE" not in joined.upper(),
          "표를 재작성하지 않는 순수 추가다 (재작성하면 인덱스를 다시 세워야 한다)")
    check("NOT NULL" not in joined.upper(),
          "NOT NULL을 걸지 않는다 — 엔티티가 nullable이라 걸면 Room이 시작 시 거부한다")
    check("DEFAULT" not in joined.upper(),
          "DEFAULT를 주지 않는다 — 빈 문자열로 채우면 '대결 정렬 아님'과 '축을 못 찾음'이 섞인다")

    print("\n[2] v50 스키마를 실제로 세우고 마이그레이션을 돌린다")
    con = sqlite3.connect(":memory:")
    setup = preset_statements(src)
    check(len(setup) >= 2, f"v50 프리셋 표를 만드는 문장을 찾았다 (추출 {len(setup)}문)")
    for s in setup:
        con.execute(s)
    con.execute(
        "INSERT INTO character_list_presets"
        "(id, name, tagsJson, fieldFiltersJson, sortKind, sortFieldKey, sortAscending,"
        " bodySizePartIndex, novelIdsJson, isDefault, createdAt, updatedAt)"
        " VALUES (1, '주연만', '[]', '{}', 'field', 'power', 0, NULL, '[]', 0, 100, 100)")

    before = indices_of(con, "character_list_presets")
    try:
        for s in stmts:
            con.execute(s)
        ran = True
    except Exception as e:  # noqa: BLE001
        ran = False
        print(f"    실행 실패: {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[3] 스키마가 Room 기대와 일치하는가")
    cols = {r[1]: r for r in con.execute("PRAGMA table_info(`character_list_presets`)")}
    check(set(cols) == PRESET_COLUMNS,
          f"character_list_presets 컬럼이 엔티티와 같다 (실제: {sorted(cols)})")
    check(cols["sortDuelAxisCode"][3] == 0,
          "sortDuelAxisCode가 **nullable**이다 (이 판에서 가장 값어치가 큰 검사)")
    check(cols["sortDuelAxisCode"][2].upper() == "TEXT", "sortDuelAxisCode가 TEXT다")
    check(cols["sortDuelAxisCode"][4] is None,
          "기본값이 없다 — 프리셋을 만들 때 앱이 명시적으로 넣는다")

    print("\n[4] 기존 프리셋이 그대로인가")
    row = con.execute(
        "SELECT name, sortKind, sortFieldKey, sortDuelAxisCode"
        " FROM character_list_presets WHERE id = 1").fetchone()
    check(row is not None and row[0] == "주연만" and row[1] == "field" and row[2] == "power",
          "마이그레이션 전에 있던 프리셋이 그대로다")
    check(row is not None and row[3] is None,
          "기존 프리셋의 새 칸이 NULL이다 — '대결로 정렬하지 않는다'가 기본이다")

    print("\n[5] 인덱스와 새 칸의 쓰기")
    check(indices_of(con, "character_list_presets") == before,
          "이름 유니크 인덱스가 그대로다 (ADD COLUMN은 표를 재작성하지 않는다)")
    con.execute(
        "INSERT INTO character_list_presets"
        "(id, name, tagsJson, fieldFiltersJson, sortKind, sortFieldKey, sortAscending,"
        " bodySizePartIndex, novelIdsJson, isDefault, createdAt, updatedAt, sortDuelAxisCode)"
        " VALUES (2, '강한 순', '[]', '{}', 'duel', NULL, 0, NULL, '[]', 0, 200, 200, 'DAX-1')")
    row = con.execute(
        "SELECT sortKind, sortDuelAxisCode FROM character_list_presets WHERE id = 2").fetchone()
    check(row == ("duel", "DAX-1"), "대결 정렬 프리셋이 축을 **코드로** 담는다 (R-1)")

    # 지워진 축을 가리키는 프리셋이 남아도 저장은 막지 않는다 — FK를 걸지 않은 것이 일부러다.
    # 축이 사라졌다고 프리셋까지 죽이면 사용자가 만든 필터·태그 조합이 함께 사라진다.
    fks = list(con.execute("PRAGMA foreign_key_list(`character_list_presets`)"))
    check(not any(f[2] == "duel_axes" for f in fks),
          "축으로 나가는 FK가 없다 — 축을 지워도 프리셋의 나머지 설정이 살아남는다")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f"  - {f}")
        raise SystemExit(1)
    print(f"통과 — 검사 {CHECKS}건")


if __name__ == "__main__":
    main()
