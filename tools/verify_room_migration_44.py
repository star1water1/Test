#!/usr/bin/env python3
"""
Room 마이그레이션 43→44 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: `image_meta.adoptSource` — 캐릭터 자동 링크(G4)는 링크 토큰을 얹기 위해 meta 행이 없는
이미지를 자동 입양한다. 그 행을 사용자 입양과 구별하지 못하면 링크가 풀린 뒤에도 행이 남아
편집창 제거 정책(LIBRARY_ONLY)의 "라이브러리는 보존" 범위가 조용히 전체 이미지로 넓어진다.
'auto' 표시 행은 링크를 지니는 동안만 존재하고, 링크가 풀리면(태그 없을 때) 반납된다.

`verify_room_migration_43.py`와 같은 원칙을 따른다:
 1. 테스트가 SQL을 복사해 갖고 있으면 코드와 어긋난다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. v43 테이블은 실제 배포된 마이그레이션(v39→40 생성)과 동일한 정의로 만든다.
 3. 마이그레이션 후 스키마가 **Room이 엔티티에서 기대하는 형태**와 일치하는지 PRAGMA로 대조한다.
 4. DAO의 새 SQL(자동 입양 반납·승격)이 실제 SQLite에서 선언대로 동작하는지 확인한다 —
    반납이 사용자 행이나 태그·링크 있는 행을 지우면 라이브러리 자산의 무음 유실이다.
"""
import re
import sqlite3

REPO = "/home/user/Test"
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
ENTITY = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model/ImageMeta.kt"
DAO = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/dao/ImageMetaDao.kt"

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
    stmts = []
    for m in re.finditer(r'execSQL\(\s*"""(.*?)"""', block, re.S):
        stmts.append((m.group(1).strip(), m.start()))
    for m in re.finditer(r'execSQL\(\s*"((?:[^"\\]|\\.)*)"', block):
        s = m.group(1)
        if s.strip():
            stmts.append((s.replace('\\"', '"'), m.start()))
    stmts.sort(key=lambda t: t[1])
    return [s for s, _ in stmts]


def build_v43(con):
    """v43 시점의 image_meta/image_tags — 배포된 MIGRATION_39_40의 생성 SQL 그대로."""
    src = open(APPDB, encoding="utf-8").read()
    block = extract_migration_block(src, "MIGRATION_39_40")
    for sql in extract_sql(block):
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
    return cols, idx


def dao_query(dao_src, method):
    """@Query("...") / @Query(\"\"\"...\"\"\")를 메서드 이름으로 찾아 SQL을 돌려준다.

    메서드 선언 위치에서 **역방향**으로 가장 가까운 @Query를 찾는다 — 전방 정규식은
    비탐욕이어도 백트래킹이 메서드 경계를 넘어 다른 쿼리와 이어 붙는다.
    """
    at = dao_src.index(f"suspend fun {method}")
    q_start = dao_src.rindex("@Query(", 0, at)
    window = dao_src[q_start:at]
    m = re.match(r'@Query\(\s*(?:"""(?P<tq>.*?)"""|"(?P<q>(?:[^"\\]|\\.)*)")\s*\)', window, re.S)
    assert m, f"DAO에서 {method}의 @Query를 찾지 못함"
    sql = m.group("tq") or m.group("q")
    return re.sub(r"\s+", " ", sql).strip()


def main():
    src = open(APPDB, encoding="utf-8").read()
    ent = open(ENTITY, encoding="utf-8").read()
    dao = open(DAO, encoding="utf-8").read()

    print("[1] 마이그레이션 SQL을 AppDatabase.kt에서 추출")
    block = extract_migration_block(src, "MIGRATION_43_44")
    stmts = extract_sql(block)
    check(len(stmts) == 1, f"execSQL 문장 {len(stmts)}개 추출 (ADD COLUMN 하나)")
    add_col = stmts[0]
    check("ADD COLUMN" in add_col and "adoptSource" in add_col, "adoptSource 컬럼 추가문 존재")
    check("NOT NULL" not in add_col, "ADD COLUMN에 NOT NULL 없음 (엔티티가 String?)")
    check("DEFAULT" not in add_col, "ADD COLUMN에 DEFAULT 없음 (@ColumnInfo(defaultValue) 미사용)")
    check("hasAdoptSource" in block and "PRAGMA table_info" in block,
          "컬럼 존재 가드가 있음 (중간 빌드를 거친 기기의 크래시 루프 방지)")
    check(all("DROP TABLE" not in s and "DELETE FROM" not in s for s in stmts),
          "파괴적 문장(DROP/DELETE) 없음")
    check("version = 44" in src, "@Database(version = 44)로 상향됨")
    check("MIGRATION_43_44)" in src or "MIGRATION_43_44," in src, "addMigrations에 등록됨")

    print("\n[2] 엔티티 선언이 마이그레이션 결과와 같은 형태인가")
    check("val adoptSource: String? = null" in ent, "엔티티가 nullable adoptSource 선언 (구버전 행 null=사용자)")
    check('const val ADOPT_SOURCE_AUTO = "auto"' in ent, "'auto' 상수 선언")
    check("adoptSource = ImageMeta.ADOPT_SOURCE_AUTO" in dao, "adoptAuto가 상수로 자동 행을 만든다")

    print("\n[3] 실제 SQLite에서 v43 → 마이그레이션 실행")
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys=ON")
    build_v43(con)
    con.execute("INSERT INTO image_meta (id, path, linkGroupId, importedAt) VALUES (1, '/a/1.jpg', NULL, 100)")
    con.execute("INSERT INTO image_meta (id, path, linkGroupId, importedAt) VALUES (2, '/a/2.jpg', 'G1', 200)")
    con.execute("INSERT INTO image_tags (imageId, tag) VALUES (2, '흑발')")
    con.commit()
    before = list(con.execute("SELECT id, path, linkGroupId, importedAt FROM image_meta ORDER BY id"))

    # Kotlin의 컬럼 존재 가드 재현: 이미 있으면 건너뛴다 + 재실행 멱등
    have = {r[1] for r in con.execute("PRAGMA table_info(`image_meta`)")}
    for sql in stmts:
        if "ADD COLUMN" in sql and "adoptSource" in have:
            continue
        con.execute(sql)
    con.commit()

    after = list(con.execute("SELECT id, path, linkGroupId, importedAt FROM image_meta ORDER BY id"))
    sources = [r[0] for r in con.execute("SELECT adoptSource FROM image_meta ORDER BY id")]
    check(before == after, "기존 열 데이터가 한 글자도 변하지 않음")
    check(all(s is None for s in sources),
          "기존 행의 adoptSource는 NULL로 남는다 (백필하지 않는다 — NULL이 이미 '사용자 입양'이라는 올바른 의미다)")

    print("\n[4] 마이그레이션 후 스키마가 Room 기대와 일치하는가")
    cols, idx = schema_of(con, "image_meta")
    check("adoptSource" in cols, "adoptSource 열 존재")
    check(cols["adoptSource"]["type"] == "TEXT", f"타입 affinity=TEXT (실제 {cols['adoptSource']['type']})")
    check(cols["adoptSource"]["notnull"] == 0, "notNull=false (엔티티 String? 와 일치)")
    check(cols["adoptSource"]["default"] is None, "DEFAULT 없음 (엔티티와 일치)")
    check("index_image_meta_path" in idx and idx["index_image_meta_path"]["unique"] is True,
          "기존 path 유니크 인덱스 유지")
    check("index_image_meta_linkGroupId" in idx, "기존 linkGroupId 인덱스 유지")

    print("\n[5] 자동 입양 반납 SQL이 선언대로만 지우는가 (무음 유실 방지의 핵심)")
    sweep = dao_query(dao, "sweepBareAutoAdopted")
    promote = dao_query(dao, "promoteToUserByPaths").replace(":paths", "(?)").replace("IN ((?))", "IN (?)")
    rows = [
        # (id, path, group, adoptSource, tags) — 반납 기대: id 13만
        (11, '/u/user-bare.jpg', None, None),          # 사용자 행, 링크 없음 → 보존
        (12, '/u/auto-linked.jpg', 'char:7', 'auto'),  # 자동 행, 링크 보유 → 보존
        (13, '/u/auto-bare.jpg', None, 'auto'),        # 자동 행, 링크·태그 없음 → 반납
        (14, '/u/auto-tagged.jpg', None, 'auto'),      # 자동 행, 태그 있음 → 보존
    ]
    for r in rows:
        con.execute("INSERT INTO image_meta (id, path, linkGroupId, importedAt, adoptSource) VALUES (?,?,?,300,?)", r)
    con.execute("INSERT INTO image_tags (imageId, tag) VALUES (14, '금발')")
    con.commit()
    con.execute(sweep)
    con.commit()
    left = {r[0] for r in con.execute("SELECT id FROM image_meta WHERE id >= 11")}
    check(left == {11, 12, 14}, f"태그·링크 없는 자동 행(13)만 반납 (실제 잔존 {sorted(left)})")

    print("\n[6] 승격 SQL — 사용자 행위가 자동 행을 보호 대상으로 바꾸는가")
    src_before = con.execute("SELECT adoptSource FROM image_meta WHERE id = 12").fetchone()[0]
    check(src_before == 'auto', "승격 전: 자동 행")
    con.execute(promote, ('/u/auto-linked.jpg',))
    con.commit()
    src_after = con.execute("SELECT adoptSource FROM image_meta WHERE id = 12").fetchone()[0]
    check(src_after is None, "승격 후: 사용자 행 (NULL)")
    con.execute("UPDATE image_meta SET linkGroupId = NULL WHERE id = 12")
    con.execute(sweep)
    con.commit()
    check(con.execute("SELECT COUNT(*) FROM image_meta WHERE id = 12").fetchone()[0] == 1,
          "승격된 행은 링크가 풀려도 반납되지 않는다")

    print("\n[7] 기존 singleton 정리와 자동 토큰의 상호작용")
    clear = dao_query(dao, "clearGroupIfSingleton").replace(":groupId", "?")
    con.execute("INSERT INTO image_meta (id, path, linkGroupId, importedAt, adoptSource) VALUES (21, '/u/s1.jpg', 'char:9', 400, 'auto')")
    con.commit()
    con.execute(clear, ('char:9', 'char:9'))
    con.commit()
    g = con.execute("SELECT linkGroupId FROM image_meta WHERE id = 21").fetchone()[0]
    check(g is None, "1장 남은 자동 그룹은 기존 SQL로 해제된다")
    con.execute(sweep)
    con.commit()
    check(con.execute("SELECT COUNT(*) FROM image_meta WHERE id = 21").fetchone()[0] == 0,
          "singleton 해제로 링크를 잃은 자동 행도 반납된다 (수명 = 링크 보유 기간)")

    print(f"\n결과: {CHECKS}개 검사, 실패 {len(FAILS)}건")
    if FAILS:
        for f in FAILS:
            print(f"  실패: {f}")
        raise SystemExit(1)
    print("모든 검사 통과")


if __name__ == "__main__":
    main()
