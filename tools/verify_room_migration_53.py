#!/usr/bin/env python3
"""
Room 마이그레이션 52→53 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: **전역 기본 필드 템플릿**(B-119) — `default_field_templates` 표 신설.
설계 정본은 `docs/feature_roadmap_2026-08.md` 1장이다.

**이 판에서 값어치가 가장 큰 검사는 둘이다.**

① **유니크 `(entityType, key)`가 실제로 거절하는가.** 이 제약이 없으면 같은 자리를 노리는
   템플릿 둘을 만들 수 있고, 그 둘은 **어느 세계관에서든 반드시 하나가 삽입에 실패한다**
   (심는 자리의 제약이 `(universeId, entityType, key)`이므로). 실패는 심는 순간에 나는데
   원인은 만드는 순간에 있어, 사용자에게는 *"기본 필드가 어떤 세계관에서만 안 생긴다"*로
   보인다 — 설계 1-2가 참조형을 기각한 바로 그 증상이다. 선언만 읽지 않고 **실제로 두 번째
   INSERT를 던져** 거절을 확인한다.

② **기존 필드가 손대지 않은 채인가.** 연결 표식은 `field_definitions.config` 안의 키라
   (`DefaultFieldRef`) 필드 쪽 스키마 변경이 없어야 한다. 표 하나만 더하는 순수 추가라는
   사실을 문장 검사로만 두지 않고 **필드를 실제로 넣어 두고 마이그레이션을 돌려** 잰다.

함께 재는 것: 마이그레이션이 **템플릿을 하나도 지어내지 않는가**. 어느 필드가 "모든 세계관이
가져야 할 것"인지 앱이 알 길이 없고, 지어내면 사용자가 정하지 않은 것을 전 세계관에 심는
셈이 된다(v49→50·v51→52가 소급 연결을 거부한 것과 같은 근거).

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. 마이그레이션 후 컬럼 집합이 **엔티티가 선언한 것**과 일치하는가.
 3. 유니크 둘이 실재하고 **실제로 거절하는가**.
 4. 기존 필드 정의가 그대로인가 — 표를 건드리지 않았다는 실증.
 5. 새 표에 실제로 쓰고 읽는가.
"""
import os
import re
import sqlite3

REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"

FAILS = []
CHECKS = 0

TEMPLATE_COLUMNS = {
    "id", "key", "name", "type", "config", "groupName", "displayOrder",
    "isRequired", "entityType", "createdAt", "code",
}

# v52의 필드 정의 표 — **이 하네스가 재는 대상이 아니라 배경**이다(마이그레이션 52→53이
# 건드리지 않는다는 것을 보이기 위한 최소 형태. v51→52 하네스의 `V51_UNIVERSES`와 같은 몫).
V52_FIELD_DEFINITIONS = """
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
    entityType TEXT NOT NULL
)
"""


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

    경계는 **다음 마이그레이션 블록**이다 — 뒤에 54가 들어와도 그것까지 추출하지 않는다.
    """
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


def rejects(con, sql, params):
    """제약이 실제로 거절하는가 — 선언을 읽는 것과 던져 보는 것은 다르다."""
    try:
        con.execute(sql, params)
        con.commit()
        return False
    except sqlite3.IntegrityError:
        return True


def main():
    src = read(APPDB)
    tpl_src = read(f"{MODEL}/DefaultFieldTemplate.kt")
    ref_src = read(f"{MODEL}/DefaultFieldRef.kt")

    print("=" * 70)
    print("Room 마이그레이션 52→53 검증 — 전역 기본 필드 템플릿 (B-119)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 53), "@Database(version)이 53 이상이다")
    check(re.search(r"ALL_MIGRATIONS\b[^=]*=\s*arrayOf\([^)]*?MIGRATION_52_53\b", src, re.S) is not None
                and "addMigrations(*ALL_MIGRATIONS)" in src,
          "MIGRATION_52_53이 등록 목록(ALL_MIGRATIONS)에 있고 그 목록이 그대로 Room에 넘어간다 (빠뜨리면 실행 시 IllegalStateException)")
    check("DefaultFieldTemplate::class" in src,
          "@Database(entities)에 DefaultFieldTemplate이 등재됐다")
    check("abstract fun defaultFieldTemplateDao()" in src, "DAO 접근자가 선언됐다")
    check('tableName = "default_field_templates"' in tpl_src,
          "엔티티의 tableName이 default_field_templates다")
    check('const val CONFIG_KEY = "defaultField"' in ref_src,
          "연결 표식의 config 키를 DefaultFieldRef가 단일 소스로 든다")

    stmts = extract_migration_sql(src, "MIGRATION_52_53")
    joined = " ".join(stmts)
    check(len(stmts) == 3, f"표 하나 + 인덱스 둘 = 3문이다 (추출 {len(stmts)}문)")
    check("DROP TABLE" not in joined.upper() and "ALTER TABLE" not in joined.upper(),
          "기존 표를 재작성하지도 고치지도 않는 순수 추가다")
    check("INSERT" not in joined.upper(),
          "템플릿을 하나도 지어내지 않는다 — 무엇이 '모든 세계관의 기본'인지는 사용자가 정한다")
    check(joined.upper().count("CREATE UNIQUE INDEX") == 2,
          "유니크 인덱스 둘(code · (entityType,key))을 세운다")
    check("IF NOT EXISTS" in joined.upper(),
          "IF NOT EXISTS다 — 같은 마이그레이션이 두 번 닿아도 죽지 않는다")

    print("\n[2] v52 스키마를 실제로 세우고 마이그레이션을 돌린다")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys = ON")
    con.execute(V52_FIELD_DEFINITIONS)
    con.execute(
        "INSERT INTO field_definitions(id, universeId, `key`, name, type, config, groupName,"
        " displayOrder, isRequired, entityType)"
        " VALUES (1, 1, 'gender', '성별', 'SELECT', '{\"options\":[\"남\",\"여\"]}', '기본 정보',"
        " 0, 0, 'character')")
    before_fields = indices_of(con, "field_definitions")

    try:
        for s in stmts:
            con.execute(s)
        ran = True
    except Exception as e:  # noqa: BLE001
        ran = False
        print(f"    실행 실패: {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[3] 스키마가 Room 기대와 일치하는가")
    cols = {r[1]: r for r in con.execute("PRAGMA table_info(`default_field_templates`)")}
    check(set(cols) == TEMPLATE_COLUMNS,
          f"default_field_templates 컬럼이 엔티티와 같다 (실제: {sorted(cols)})")
    check(all(cols[c][3] == 1 for c in TEMPLATE_COLUMNS - {"id"} if c in cols),
          "id를 뺀 모든 칸이 NOT NULL이다 (엔티티가 전부 non-null — 어긋나면 앱이 시작을 거부한다)")
    check(cols["isRequired"][2].upper() == "INTEGER", "isRequired가 INTEGER다 (Boolean)")
    check(cols["displayOrder"][2].upper() == "INTEGER", "displayOrder가 INTEGER다")
    check(cols["createdAt"][2].upper() == "INTEGER", "createdAt이 INTEGER다 (Long)")

    print("\n[4] 유니크 둘이 실제로 거절하는가 — 이 판에서 가장 값어치가 큰 검사")
    idx = indices_of(con, "default_field_templates")
    check("index_default_field_templates_code" in idx, "code 유니크 인덱스가 있다")
    check("index_default_field_templates_entityType_key" in idx,
          "(entityType, key) 유니크 인덱스가 있다")

    ins = ("INSERT INTO default_field_templates(id, `key`, name, type, config, groupName,"
           " displayOrder, isRequired, entityType, createdAt, code) VALUES (?,?,?,?,?,?,?,?,?,?,?)")
    con.execute(ins, (1, "gender", "성별", "SELECT", "{}", "기본 정보", 0, 0, "character", 100, "DFT-1"))
    con.commit()

    check(rejects(con, ins,
                  (2, "gender", "다른 이름", "TEXT", "{}", "기본 정보", 1, 0, "character", 110, "DFT-2")),
          "같은 (entityType, key)의 둘째 템플릿을 거절한다 — 두면 어느 세계관에서든 삽입이 깨진다")
    check(rejects(con, ins,
                  (3, "other", "다른 키", "TEXT", "{}", "기본 정보", 2, 0, "character", 120, "DFT-1")),
          "같은 code의 둘째 템플릿을 거절한다 (R-1 — 심긴 필드가 이 값으로 원본을 가리킨다)")

    # 종류가 다르면 같은 key가 공존한다 — 캐릭터의 `place`와 사건의 `place`는 다른 자리다(R-29).
    ok_cross_type = True
    try:
        con.execute(ins, (4, "gender", "성별", "SELECT", "{}", "기본 정보", 0, 0, "event", 130, "DFT-4"))
        con.commit()
    except sqlite3.IntegrityError:
        ok_cross_type = False
    check(ok_cross_type,
          "종류가 다르면 같은 key가 공존한다 (캐릭터의 place와 사건의 place는 다른 자리 — R-29)")

    print("\n[5] 기존 필드 정의가 그대로인가 — 순수 추가의 실증")
    row = con.execute(
        "SELECT `key`, name, config, entityType FROM field_definitions WHERE id = 1").fetchone()
    check(row is not None and row[0] == "gender" and row[1] == "성별",
          "마이그레이션 전에 있던 필드가 그대로다")
    check(row is not None and row[2] == '{"options":["남","여"]}',
          "필드 config가 손상되지 않았다 — 연결 표식은 config 안의 키라 스키마 변경이 없다")
    check(indices_of(con, "field_definitions") == before_fields,
          "필드 정의 표의 인덱스가 그대로다 (이 마이그레이션은 그 표를 건드리지 않는다)")
    check(len({r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")} & {"default_field_templates"}) == 1,
        "새 표가 실재한다")

    print("\n[6] 새 표에 실제로 쓰고 읽는다")
    con.execute("UPDATE default_field_templates SET config = ? WHERE code = 'DFT-1'",
                ('{"options":["남","여","기타"]}',))
    got = con.execute(
        "SELECT config FROM default_field_templates WHERE code = 'DFT-1'").fetchone()
    check(got is not None and got[0] == '{"options":["남","여","기타"]}',
          "템플릿 config가 그대로 저장된다")

    # 템플릿을 지워도 심긴 필드는 남는다 — FK가 **없는 것이 설계다**(설계 1-3).
    # CASCADE였다면 템플릿 삭제가 전 세계관의 캐릭터 값을 함께 지웠을 것이다.
    con.execute("DELETE FROM default_field_templates WHERE code = 'DFT-1'")
    left = con.execute("SELECT COUNT(*) FROM field_definitions").fetchone()[0]
    check(left == 1,
          "템플릿을 지워도 심긴 필드는 남는다 (FK가 없는 것이 설계 — 강등만 하고 값은 건드리지 않는다)")

    fks = list(con.execute("PRAGMA foreign_key_list(`default_field_templates`)"))
    check(not fks,
          "템플릿 표에 FK가 없다 — 전역이라 매달릴 부모가 없고, 그래서 초기화가 직접 지운다(ResetPlan)")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f"  - {f}")
        return 1
    print(f"검사 {CHECKS}건 모두 통과")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
