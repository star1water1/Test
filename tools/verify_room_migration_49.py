#!/usr/bin/env python3
"""
Room 마이그레이션 48→49 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: 대결 시스템(B-104)의 표 셋 — `duel_axes` · `duel_matches` · `duel_counter_verdicts`.
설계 정본은 `docs/duel_system_design_2026-08.md`이고 데이터 모델은 그 문서 4장이다.

**이 마이그레이션에서 값어치가 가장 큰 검사는 "무엇이 FK로 매달려 있고 무엇이 아닌가"다.**
축은 세계관에 매달려 함께 죽어야 하고(그래서 휴지통이 담는다), 판은 **캐릭터에 매달리면 안 된다** —
캐릭터를 지울 때 판이 CASCADE로 함께 죽으면 설계가 정한 처분(*"지우지 않고 적합에서만 뺀다.
복원하면 되살아난다"*)이 원리적으로 불가능해진다. 그것은 스키마를 보면 바로 알 수 있는 사실이고,
여기서 잡지 못하면 실기기에서 캐릭터를 지워 본 사용자만이 알게 된다.

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. v48 `universes` 표는 실제 배포된 정의와 같게 만든다(FK 부모가 있어야 CASCADE를 잴 수 있다).
 3. 마이그레이션 후 스키마가 **Room이 엔티티에서 기대하는 형태**와 일치하는지 PRAGMA로 대조한다.
 4. **CASCADE가 실제로 도는가** — 세계관을 지우면 축·판·처분이 함께 사라진다(초기화 계획의 전제).
 5. **캐릭터 삭제는 판을 건드리지 않는다** — 참가자가 코드라 FK 자체가 없다.
 6. 유니크 제약 셋(축 코드 · 세계관 안 이름 · 판 코드 · 축 안 처분 키)이 실제로 거부하는가.
 7. 기존 표·행이 하나도 변하지 않았는가(순수 추가다).
"""
import os
import re
import sqlite3

# 저장소 루트 — **스크립트 위치에서 유도한다**(`tools/`의 부모). 경로를 박으면 다른 곳에
# 체크아웃했을 때 어디서도 못 돈다(2026.08.04에 CI에서 실제로 드러난 함정이다).
REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"

FAILS = []
CHECKS = 0

# v48 시점의 `universes` 표 — 이 마이그레이션이 붙는 부모다.
V48_UNIVERSES = """CREATE TABLE `universes` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `name` TEXT NOT NULL,
    `description` TEXT NOT NULL DEFAULT '',
    `createdAt` INTEGER NOT NULL DEFAULT 0,
    `code` TEXT NOT NULL DEFAULT '',
    `displayOrder` INTEGER NOT NULL DEFAULT 0,
    `borderColor` TEXT NOT NULL DEFAULT '',
    `borderWidthDp` REAL NOT NULL DEFAULT 1.5,
    `imagePaths` TEXT NOT NULL DEFAULT '[]',
    `imageMode` TEXT NOT NULL DEFAULT 'none',
    `imageCharacterId` INTEGER,
    `imageNovelId` INTEGER,
    `customRelationshipTypes` TEXT NOT NULL DEFAULT '',
    `customRelationshipColors` TEXT NOT NULL DEFAULT ''
)"""

# 판이 **매달리지 않아야** 하는 표. 캐릭터 삭제가 판을 지우면 안 된다는 것을 실제로 잰다.
V48_CHARACTERS = """CREATE TABLE `characters` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `name` TEXT NOT NULL,
    `code` TEXT NOT NULL DEFAULT ''
)"""

AXES_COLUMNS = {"id", "universeId", "name", "targetType", "displayOrder", "createdAt", "code"}
MATCH_COLUMNS = {"id", "axisId", "aCode", "bCode", "winnerCode", "groupId", "decidedAt", "code"}
VERDICT_COLUMNS = {"id", "axisId", "kind", "shape", "memberCodes", "memberKey", "decidedAt", "code"}


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


def extract_migration_sql(src):
    """MIGRATION_48_49 블록의 execSQL 인자를 순서대로 뽑는다 (SQL을 복사하지 않기 위해).

    경계는 **다음 마이그레이션 블록**이다 — 뒤에 50이 들어와도 그것까지 추출하지 않는다.
    지금은 49가 마지막이라 fallback(`fun getDatabase(`)을 타지만, 다음이 들어오면 자동으로
    앞쪽 경계로 옮겨 간다(45·46판이 이 처리를 빠뜨려 실제로 깨진 적이 있다).
    """
    start = src.index("private val MIGRATION_48_49 = object : Migration(")
    nxt = src.find("private val MIGRATION", start + 10)
    end = nxt if nxt != -1 else src.index("fun getDatabase(", start)
    block = src[start:end]
    triple = re.findall(r'execSQL\(\s*"""(.*?)"""\s*\)', block, re.S)
    single = re.findall(r'execSQL\(\s*"([^"\n]+)"\s*\)', block)
    return triple + single, block


def indices_of(con, table):
    return {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", (table,))}


def rejects(con, sql, params=()):
    """유니크·FK 위반으로 거부되는가."""
    try:
        con.execute(sql, params)
        return False
    except sqlite3.IntegrityError:
        return True


def main():
    src = read(APPDB)
    axis_src = read(f"{MODEL}/DuelAxis.kt")
    match_src = read(f"{MODEL}/DuelMatch.kt")
    verdict_src = read(f"{MODEL}/DuelCounterVerdict.kt")

    print("=" * 70)
    print("Room 마이그레이션 48→49 검증 — 대결 표 셋 (B-104)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 49), "@Database(version)이 49 이상이다")
    check(re.search(r"addMigrations\([^)]*MIGRATION_48_49", src, re.S) is not None,
          "MIGRATION_48_49가 addMigrations에 등록됐다 (빠뜨리면 실행 시 IllegalStateException)")
    for cls in ("DuelAxis", "DuelMatch", "DuelCounterVerdict"):
        check(re.search(rf"{cls}::class", src) is not None,
              f"{cls}이 @Database(entities)에 등재됐다")
    check("val winnerCode: String? = null" in match_src,
          "엔티티가 winnerCode를 nullable로 선언했다 (null = 무승부 — 마이그레이션의 NULL 허용과 짝)")
    check("val groupId: String? = null" in match_src, "엔티티가 groupId를 nullable로 선언했다")
    check("entity = DuelAxis::class" in match_src and "CASCADE" in match_src,
          "판이 축에 CASCADE로 매달린다 (축을 지우면 판도 사라진다)")
    check("Character::class" not in match_src,
          "판에 캐릭터 FK가 **없다** — 있으면 캐릭터 삭제가 판을 지워 설계된 처분(고아로 남겼다가 복원)이 불가능해진다")
    check("entity = Universe::class" in axis_src,
          "축이 세계관에 매달린다 (ㄷ1 — 세계관 단위)")
    check("entity = DuelAxis::class" in verdict_src, "처분이 축에 매달린다")

    stmts, _block = extract_migration_sql(src)
    joined = " ".join(stmts).upper()
    check(len(stmts) == 14, f"표 3 + 인덱스 11을 만드는 14문이다 (추출 {len(stmts)}문)")
    check(joined.count("CREATE INDEX") + joined.count("CREATE UNIQUE INDEX") == 11,
          "인덱스 11개를 만든다 (축 3 · 판 5 · 처분 3)")
    check(joined.count("CREATE TABLE") == 3, "표 셋을 만든다")
    check("DROP TABLE" not in joined and "ALTER TABLE" not in joined,
          "기존 표를 건드리지 않는 순수 추가다 (재작성·변경 문장이 없다)")

    print("\n[2] v48 스키마에서 실제로 실행")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys = ON")
    con.execute(V48_UNIVERSES)
    con.execute(V48_CHARACTERS)
    con.execute("CREATE UNIQUE INDEX `index_universes_code` ON `universes`(`code`)")
    con.execute("INSERT INTO universes(id, name, code) VALUES (1, '아르카나', 'UNI-1')")
    con.execute("INSERT INTO universes(id, name, code) VALUES (2, '다른 세계', 'UNI-2')")
    con.execute("INSERT INTO characters(id, name, code) VALUES (10, '리안', 'CHR-1')")
    con.execute("INSERT INTO characters(id, name, code) VALUES (11, '카이', 'CHR-2')")

    try:
        for s in stmts:
            con.execute(s)
        ran = True
    except Exception as e:  # noqa: BLE001
        ran = False
        print(f"    실행 실패: {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[3] 스키마가 Room 기대와 일치하는가")
    axes = {r[1]: r for r in con.execute("PRAGMA table_info(`duel_axes`)")}
    matches = {r[1]: r for r in con.execute("PRAGMA table_info(`duel_matches`)")}
    verdicts = {r[1]: r for r in con.execute("PRAGMA table_info(`duel_counter_verdicts`)")}
    check(set(axes) == AXES_COLUMNS, f"duel_axes 컬럼이 엔티티와 같다 (실제: {sorted(axes)})")
    check(set(matches) == MATCH_COLUMNS, f"duel_matches 컬럼이 엔티티와 같다 (실제: {sorted(matches)})")
    check(set(verdicts) == VERDICT_COLUMNS,
          f"duel_counter_verdicts 컬럼이 엔티티와 같다 (실제: {sorted(verdicts)})")

    # nullable 여부는 Room이 시작 시 대조하는 항목이다 — 어긋나면 앱이 아예 안 뜬다.
    check(matches["winnerCode"][3] == 0, "winnerCode가 nullable이다 (null = 무승부)")
    check(matches["groupId"][3] == 0, "groupId가 nullable이다 (null = 단독 판)")
    for col in ("aCode", "bCode", "code"):
        check(matches[col][3] == 1, f"duel_matches.{col}이 NOT NULL이다")
    for col in ("universeId", "name", "targetType", "code"):
        check(axes[col][3] == 1, f"duel_axes.{col}이 NOT NULL이다")
    for col in ("kind", "shape", "memberCodes", "memberKey", "code"):
        check(verdicts[col][3] == 1, f"duel_counter_verdicts.{col}이 NOT NULL이다")
    check(matches["decidedAt"][2].upper() == "INTEGER", "decidedAt이 INTEGER다 (Long)")
    check(all(c[4] is None for c in list(axes.values()) + list(matches.values())),
          "새 표의 칸에 SQL 기본값이 없다 — 값은 언제나 엔티티가 정한다(두 곳에 기본값이 갈리지 않는다)")

    print("\n[4] 인덱스")
    axis_idx = indices_of(con, "duel_axes")
    match_idx = indices_of(con, "duel_matches")
    verdict_idx = indices_of(con, "duel_counter_verdicts")
    for name in ("index_duel_axes_universeId", "index_duel_axes_universeId_targetType_name",
                 "index_duel_axes_code"):
        check(name in axis_idx, f"{name}이 있다")
    for name in ("index_duel_matches_axisId", "index_duel_matches_axisId_aCode",
                 "index_duel_matches_axisId_bCode", "index_duel_matches_groupId",
                 "index_duel_matches_code"):
        check(name in match_idx, f"{name}이 있다")
    for name in ("index_duel_counter_verdicts_axisId",
                 "index_duel_counter_verdicts_axisId_memberKey",
                 "index_duel_counter_verdicts_code"):
        check(name in verdict_idx, f"{name}이 있다")

    print("\n[5] 유니크 제약이 실제로 거부한다")
    con.execute("""INSERT INTO duel_axes(id, universeId, name, targetType, displayOrder, createdAt, code)
                   VALUES (1, 1, '강함', 'character', 0, 100, 'AX-1')""")
    check(rejects(con, """INSERT INTO duel_axes(universeId, name, targetType, displayOrder, createdAt, code)
                          VALUES (1, '강함', 'character', 0, 100, 'AX-9')"""),
          "같은 세계관·같은 대상에 같은 이름의 축을 둘 만들 수 없다")
    con.execute("""INSERT INTO duel_axes(id, universeId, name, targetType, displayOrder, createdAt, code)
                   VALUES (2, 1, '강함', 'image', 0, 100, 'AX-2')""")
    check(True, "같은 이름이라도 대상 종류가 다르면 다른 축이다 (캐릭터 '강함' ≠ 이미지 '강함')")
    check(rejects(con, """INSERT INTO duel_axes(universeId, name, targetType, displayOrder, createdAt, code)
                          VALUES (2, '강함', 'character', 0, 100, 'AX-1')"""),
          "축 코드는 세계관을 넘어 유일하다 (R-1 — 코드가 대상을 정한다)")

    con.execute("""INSERT INTO duel_matches(id, axisId, aCode, bCode, winnerCode, groupId, decidedAt, code)
                   VALUES (1, 1, 'CHR-1', 'CHR-2', 'CHR-1', NULL, 1000, 'MT-1')""")
    con.execute("""INSERT INTO duel_matches(id, axisId, aCode, bCode, winnerCode, groupId, decidedAt, code)
                   VALUES (2, 1, 'CHR-1', 'CHR-2', NULL, 'grp-1', 1001, 'MT-2')""")
    check(con.execute("SELECT winnerCode FROM duel_matches WHERE id = 2").fetchone()[0] is None,
          "무승부(winnerCode = NULL)가 저장된다 — 화면이 아직 안 내줘도 형식은 받는다")
    check(rejects(con, """INSERT INTO duel_matches(axisId, aCode, bCode, decidedAt, code)
                          VALUES (1, 'CHR-1', 'CHR-3', 1002, 'MT-1')"""),
          "판 코드가 중복될 수 없다 (엑셀 왕복이 같은 판을 두 번 들이지 않는 근거)")
    check(rejects(con, """INSERT INTO duel_matches(axisId, aCode, bCode, decidedAt, code)
                          VALUES (999, 'CHR-1', 'CHR-3', 1003, 'MT-3')"""),
          "없는 축에는 판을 넣을 수 없다 (FK)")

    con.execute("""INSERT INTO duel_counter_verdicts(id, axisId, kind, shape, memberCodes, memberKey, decidedAt, code)
                   VALUES (1, 1, 'counter', 'direct', '["CHR-1","CHR-2"]', '["CHR-1","CHR-2"]', 2000, 'VD-1')""")
    check(rejects(con, """INSERT INTO duel_counter_verdicts(axisId, kind, shape, memberCodes, memberKey, decidedAt, code)
                          VALUES (1, 'undecided', 'direct', '["CHR-2","CHR-1"]', '["CHR-1","CHR-2"]', 2001, 'VD-2')"""),
          "같은 축의 같은 관계에 처분이 둘일 수 없다 (②를 ③으로 굳힐 때 덮어쓰기가 성립하는 근거)")

    print("\n[6] CASCADE — 세계관을 지우면 축·판·처분이 함께 사라진다")
    con.execute("DELETE FROM universes WHERE id = 1")
    check(con.execute("SELECT COUNT(*) FROM duel_axes").fetchone()[0] == 0,
          "축이 세계관과 함께 사라진다 (ResetPlan의 cascade 선언과 같은 사실)")
    check(con.execute("SELECT COUNT(*) FROM duel_matches").fetchone()[0] == 0,
          "판이 축과 함께 사라진다 — 그래서 세계관 삭제 경로가 휴지통에 담아야 한다")
    check(con.execute("SELECT COUNT(*) FROM duel_counter_verdicts").fetchone()[0] == 0,
          "처분도 함께 사라진다")

    print("\n[7] 캐릭터를 지워도 판은 남는다 (설계된 처분 — 고아로 남겼다가 복원)")
    con.execute("""INSERT INTO duel_axes(id, universeId, name, targetType, displayOrder, createdAt, code)
                   VALUES (3, 2, '아름다움', 'character', 0, 100, 'AX-3')""")
    con.execute("""INSERT INTO duel_matches(axisId, aCode, bCode, winnerCode, decidedAt, code)
                   VALUES (3, 'CHR-1', 'CHR-2', 'CHR-2', 3000, 'MT-10')""")
    con.execute("DELETE FROM characters WHERE code = 'CHR-1'")
    check(con.execute("SELECT COUNT(*) FROM duel_matches WHERE axisId = 3").fetchone()[0] == 1,
          "캐릭터 삭제가 판을 지우지 않는다 — 지웠다면 휴지통 복원으로도 되살릴 것이 없다")
    check(con.execute(
        "SELECT aCode FROM duel_matches WHERE code = 'MT-10'").fetchone()[0] == 'CHR-1',
        "판이 지워진 참가자의 **코드를 그대로 들고 있다** — 복원되면 그 코드로 다시 이어진다")

    print("\n[8] 기존 표는 그대로다")
    check(con.execute("SELECT COUNT(*) FROM universes").fetchone()[0] == 1,
          "남은 세계관 행이 온전하다")
    ucols = {r[1] for r in con.execute("PRAGMA table_info(`universes`)")}
    check(ucols == {"id", "name", "description", "createdAt", "code", "displayOrder",
                    "borderColor", "borderWidthDp", "imagePaths", "imageMode",
                    "imageCharacterId", "imageNovelId", "customRelationshipTypes",
                    "customRelationshipColors"},
          "universes 컬럼이 하나도 바뀌지 않았다")
    check("index_universes_code" in indices_of(con, "universes"),
          "기존 인덱스가 그대로다")

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
