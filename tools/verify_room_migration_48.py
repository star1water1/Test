#!/usr/bin/env python3
"""
Room 마이그레이션 47→48 검증 하네스 (Android 없이 실제 SQLite로 실행).

대상: `image_meta.detachedAt` · `image_meta.detachedFromCode` — 뗀 이미지 표식(B-107 결정 D1).

`verify_room_migration_47.py`와 같은 원칙을 따르되, **칸의 성격이 반대라 검사도 반대다.**
47은 NOT NULL 칸이라 "기존 행이 기본값을 받는가"가 값어치였는데, 이쪽은 nullable이라
**"기존 행이 null로 남는가"**가 값어치다 — 마이그레이션이 값을 채워 넣으면 아직 뗀 적 없는
이미지가 서랍에 나타나고, 화면이 지어낸 시각을 사실처럼 말하게 된다.

 1. SQL을 복사해 갖지 않는다 → **AppDatabase.kt에서 실제 문장을 추출**해 실행한다.
 2. v47 `image_meta` 표는 실제 배포된 정의(인덱스 포함)와 동일하게 만든다.
 3. 마이그레이션 후 스키마가 **Room이 엔티티에서 기대하는 형태**와 일치하는지 PRAGMA로 대조한다.
    nullable 칸을 NOT NULL로 만들어 버리면 Room이 시작 시 스키마 불일치로 거부한다.
 4. **기존 행이 null로 온다**(소급 없음)를 실행으로 확인한다.
 5. **path의 unique 인덱스가 살아남는가** — ALTER TABLE ADD COLUMN은 표를 재작성하지 않으므로
    남아야 정상이고, 누가 나중에 재작성 방식으로 바꾸면 여기서 잡힌다.
 6. 뗀 표식을 붙이고/지우는 쓰기가 실제로 동작하는가(둘 다 — 푸는 길이 계약이다, D2).
"""
import re
import sqlite3

REPO = "/home/user/Test"
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
ENTITY = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model/ImageMeta.kt"

FAILS = []
CHECKS = 0

# v47 시점의 `image_meta` 정의 — 이 마이그레이션이 손대는 표.
V47_IMAGE_META = """CREATE TABLE `image_meta` (
    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    `path` TEXT NOT NULL,
    `linkGroupId` TEXT,
    `importedAt` INTEGER NOT NULL,
    `adoptSource` TEXT
)"""

V47_COLUMNS = {"id", "path", "linkGroupId", "importedAt", "adoptSource"}
NEW_COLUMNS = {"detachedAt", "detachedFromCode"}


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
    """MIGRATION_47_48 블록의 execSQL 인자를 순서대로 뽑는다 (SQL을 복사하지 않기 위해).

    경계는 **다음 마이그레이션 블록**이다 — 뒤에 49가 들어와도 이 하네스가 그것까지 추출하지
    않는다. `fun getDatabase(`로 잡으면 새 블록이 들어오는 순간 깨진다(45·46판이 실제로
    그렇게 깨져 2026.08.03에 셋을 함께 고쳤다). 지금은 48이 마지막이라 fallback을 타지만,
    다음 마이그레이션이 들어오면 자동으로 앞쪽 경계로 옮겨 간다.
    """
    start = src.index("private val MIGRATION_47_48 = object : Migration(")
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
    print("Room 마이그레이션 47→48 검증 — image_meta 뗀 표식 두 칸 (B-107)")
    print("=" * 70)

    print("\n[1] 등록 확인")
    check(db_version_at_least(src, 48), "@Database(version)이 48 이상이다")
    check(re.search(r"addMigrations\([^)]*MIGRATION_47_48", src, re.S) is not None,
          "MIGRATION_47_48이 addMigrations에 등록됐다 (빠뜨리면 실행 시 IllegalStateException)")
    check("val detachedAt: Long? = null" in entity_src,
          "엔티티가 detachedAt을 nullable로 선언했다 (마이그레이션의 기본값 없는 칸과 짝)")
    check("val detachedFromCode: String? = null" in entity_src,
          "엔티티가 detachedFromCode를 nullable로 선언했다")

    stmts, _block = extract_migration_sql(src)
    check(len(stmts) == 2, f"칸 둘을 더하는 두 문장이다 (추출 {len(stmts)}문)")
    joined = " ".join(stmts).upper()
    check(joined.count("ALTER TABLE") == 2,
          "표를 다시 만들지 않고 ALTER TABLE로 더한다 (재작성은 기존 행과 인덱스를 위험에 놓는다)")
    check("NOT NULL" not in joined,
          "새 칸에 NOT NULL이 없다 — null이 곧 '뗀 적 없음'이므로 채울 기본값이 존재하지 않는다")

    print("\n[2] v47 스키마에서 실제로 실행")
    con = sqlite3.connect(":memory:")
    con.execute(V47_IMAGE_META)
    con.execute("CREATE UNIQUE INDEX `index_image_meta_path` ON `image_meta` (`path`)")
    con.execute("CREATE INDEX `index_image_meta_linkGroupId` ON `image_meta` (`linkGroupId`)")
    # 마이그레이션 전에 들어 있던 행 — 업그레이드하는 사용자의 데이터다.
    con.execute("""INSERT INTO image_meta(id, path, linkGroupId, importedAt, adoptSource)
                   VALUES (1, '/files/a.jpg', NULL, 1000, NULL)""")
    con.execute("""INSERT INTO image_meta(id, path, linkGroupId, importedAt, adoptSource)
                   VALUES (2, '/files/b.jpg', 'grp-1', 2000, 'auto')""")

    try:
        for s in stmts:
            con.execute(s)
        ran = True
    except Exception as e:  # noqa: BLE001
        ran = False
        print(f"    실행 실패: {e}")
    check(ran, "추출한 마이그레이션 문장이 실제 SQLite에서 실행된다")

    print("\n[3] 스키마가 Room 기대와 일치하는가")
    cols = {r[1]: r for r in con.execute("PRAGMA table_info(`image_meta`)")}
    check(set(cols) == V47_COLUMNS | NEW_COLUMNS,
          f"컬럼 집합이 엔티티와 같다 (실제: {sorted(cols)})")
    at = cols.get("detachedAt")
    check(at is not None and at[2].upper() == "INTEGER", "detachedAt 타입이 INTEGER다 (Long?)")
    check(at is not None and at[3] == 0,
          "detachedAt이 nullable이다 — NOT NULL이면 Room이 시작 시 거부한다")
    check(at is not None and at[4] is None, f"detachedAt에 기본값이 없다 (실제: {at[4] if at else None})")
    frm = cols.get("detachedFromCode")
    check(frm is not None and frm[2].upper() == "TEXT", "detachedFromCode 타입이 TEXT다 (String?)")
    check(frm is not None and frm[3] == 0, "detachedFromCode가 nullable이다")
    check(frm is not None and frm[4] is None,
          f"detachedFromCode에 기본값이 없다 (실제: {frm[4] if frm else None})")

    print("\n[4] 기존 행은 '뗀 적 없음'으로 온다 (소급하지 않는다)")
    rows = {r[0]: (r[1], r[2]) for r in
            con.execute("SELECT id, detachedAt, detachedFromCode FROM image_meta")}
    check(rows.get(1) == (None, None), "미링크 기존 행이 둘 다 null이다")
    check(rows.get(2) == (None, None), "자동 입양 기존 행도 둘 다 null이다")
    check(con.execute(
        "SELECT COUNT(*) FROM image_meta WHERE detachedAt IS NOT NULL").fetchone()[0] == 0,
        "뗀 것으로 표시된 행이 하나도 없다 — 있으면 안 뗀 이미지가 서랍에 나타난다")

    print("\n[5] 인덱스와 다른 값이 살아남았다")
    idx = {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='image_meta'")}
    check("index_image_meta_path" in idx,
          "path의 unique 인덱스가 그대로다 (재작성 방식으로 바뀌면 여기서 잡힌다)")
    check("index_image_meta_linkGroupId" in idx, "linkGroupId 인덱스가 그대로다")
    unique_ok = False
    try:
        con.execute("""INSERT INTO image_meta(path, importedAt) VALUES ('/files/a.jpg', 3000)""")
    except sqlite3.IntegrityError:
        unique_ok = True
    check(unique_ok, "path 중복이 여전히 거부된다 (unique 제약이 살아 있다)")
    check(con.execute("SELECT COUNT(*) FROM image_meta").fetchone()[0] == 2,
          "행이 사라지거나 늘지 않았다")
    check(con.execute("SELECT linkGroupId FROM image_meta WHERE id = 2").fetchone()[0] == 'grp-1',
          "기존 칸 값이 보존됐다")

    print("\n[6] 붙이고 푸는 쓰기가 둘 다 동작한다 (푸는 길이 계약이다 — D2)")
    con.execute("""UPDATE image_meta SET detachedAt = 1700000000000, detachedFromCode = 'c001'
                   WHERE id = 1""")
    check(tuple(con.execute(
        "SELECT detachedAt, detachedFromCode FROM image_meta WHERE id = 1").fetchone())
        == (1700000000000, 'c001'), "뗀 표식이 저장된다 (시각 + 출처 코드)")
    con.execute("UPDATE image_meta SET detachedAt = NULL, detachedFromCode = NULL WHERE id = 1")
    check(tuple(con.execute(
        "SELECT detachedAt, detachedFromCode FROM image_meta WHERE id = 1").fetchone())
        == (None, None), "표식 지우기(다시 붙이거나 사용자가 직접)가 저장된다")

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
