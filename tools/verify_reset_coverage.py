#!/usr/bin/env python3
"""
앱 초기화가 모든 테이블을 비우는지 검증 (S-13 / 색출 개혁 로드맵 7).

**왜 소스를 파싱하는가:** 순수 JVM 테스트(`ResetPlanTest`)는 Room을 실행할 수 없어
엔티티 목록을 손으로 들고 있어야 한다. 그러면 "엔티티를 늘렸는데 아무도 모르는" 바로 그
결함이 그대로 재발한다. 이 하네스가 **실제 소스 셋을 대조**해 그 구멍을 막는다:

  ① `AppDatabase.kt`의 `entities = [...]`  → 실제 테이블 집합
  ② `ResetPlan.kt`의 처분 표               → 비운다고 선언한 집합
  ③ `SettingsFragment.executeReset`의 DAO 호출 → 실제로 지우는 호출

셋이 어긋나면 초기화가 '모든 데이터 삭제' 약속을 어긴다. 특히 ①에만 있고 ②에 없는 것은
**초기화 뒤에도 살아남는 테이블**이며, 그것이 이 로드맵이 고친 결함 그 자체다.
"""
import os
import re
import sys

# 저장소 루트 — **스크립트 위치에서 유도한다**(`tools/`의 부모).
# 종전에는 `/home/user/Test`가 박혀 있어 **다른 경로로 체크아웃하면 어디서도 못 돌았다**;
# 개발 컨테이너의 경로와 우연히 같아 로컬에서만 통과했고, 2026.08.04에 이 하네스를
# CI에 걸면서 처음 드러났다(세션 로그 1-bw). 셸 검사들이 쓰는 규약과 같다.
REPO = os.environ.get("REPO") or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPDB = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
MODEL_DIR = f"{REPO}/app/src/main/java/com/novelcharacter/app/data/model"
PLAN = f"{REPO}/app/src/main/java/com/novelcharacter/app/util/ResetPlan.kt"
SETTINGS = f"{REPO}/app/src/main/java/com/novelcharacter/app/ui/settings/SettingsFragment.kt"

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


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def entity_classes():
    """@Database(entities = [...])의 클래스 이름들."""
    src = read(APPDB)
    block = re.search(r"entities\s*=\s*\[(.*?)\]", src, re.S).group(1)
    return sorted(set(re.findall(r"(\w+)::class", block)))


def table_of(cls):
    """엔티티 클래스의 Room tableName."""
    try:
        src = read(f"{MODEL_DIR}/{cls}.kt")
    except FileNotFoundError:
        return None
    m = re.search(r'tableName\s*=\s*"([a-z_]+)"', src)
    return m.group(1) if m else None


def planned_tables():
    """ResetPlan.plan의 (table, how, via)."""
    src = read(PLAN)
    block = src[src.index("val plan: List<TableClear>"):src.index("/** `executeReset`이 직접")]
    out = {}
    for t in re.findall(r'explicit\("([a-z_]+)"\)', block):
        out[t] = ("EXPLICIT", None)
    for t, via in re.findall(r'cascade\("([a-z_]+)",\s*via\s*=\s*"([a-z_]+)"\)', block):
        out[t] = ("CASCADE", via)
    return out


def reset_dao_calls():
    """executeReset의 withTransaction 블록에서 부른 DAO 메서드들."""
    src = read(SETTINGS)
    start = src.index("private fun executeReset(")
    block = src[start:src.index("// SharedPreferences 초기화", start)]
    return set(re.findall(r"db\.(\w+)\(\)\.(\w+)\(\)", block))


def main():
    print("[1] AppDatabase 엔티티 → 테이블 해소")
    classes = entity_classes()
    tables = {}
    for c in classes:
        t = table_of(c)
        if t is None:
            FAILS.append(f"{c}의 tableName을 찾지 못함")
            print(f"  ✗ {c}: tableName 미해소")
        else:
            tables[t] = c
    check(len(tables) == len(classes), f"엔티티 {len(classes)}종의 테이블명을 모두 해소 ({len(tables)}개)")

    print("\n[2] ResetPlan이 모든 테이블의 처분을 밝히는가")
    plan = planned_tables()
    missing = sorted(set(tables) - set(plan))
    check(not missing, f"초기화 후 살아남을 테이블 없음 (누락: {missing or '없음'})")
    stale = sorted(set(plan) - set(tables))
    check(not stale, f"ResetPlan에 실재하지 않는 테이블 없음 (잉여: {stale or '없음'})")

    print("\n[3] CASCADE 부모가 실제로 비워지는가")
    broken = [(t, via) for t, (how, via) in plan.items() if how == "CASCADE" and via not in plan]
    check(not broken, f"부모가 비워지지 않는 CASCADE 없음 ({broken or '없음'})")

    print("\n[4] CASCADE 선언이 엔티티의 실제 FK와 맞는가")
    for t, (how, via) in sorted(plan.items()):
        if how != "CASCADE":
            continue
        cls = tables.get(t)
        src = read(f"{MODEL_DIR}/{cls}.kt")
        parent_cls = tables.get(via)
        check(
            f"{parent_cls}::class" in src and "ForeignKey.CASCADE" in src,
            f"{t}: 부모 {via}({parent_cls})로의 CASCADE FK가 엔티티에 실재",
        )

    print("\n[5] executeReset이 EXPLICIT 테이블을 실제로 지우는가")
    calls = reset_dao_calls()
    called_daos = {dao for dao, _ in calls}
    # 테이블 → 그 테이블을 지우는 DAO 이름(관례: 엔티티명 기반). 관례에서 벗어나는 것만 표기.
    special = {
        "timeline_character_cross_ref": "timelineDao",
        "timeline_event_novel_cross_ref": "timelineDao",
        "timeline_events": "timelineDao",
        "name_bank": "nameBankDao",
        "operation_logs": "operationLogDao",
        "trash_snapshots": "trashSnapshotDao",
        "character_list_presets": "characterListPresetDao",
        "image_meta": "imageMetaDao",
        "character_relationship_changes": "characterRelationshipChangeDao",
        "character_relationships": "characterRelationshipDao",
        "character_state_changes": "characterStateChangeDao",
        "faction_memberships": "factionMembershipDao",
        "factions": "factionDao",
        "characters": "characterDao",
        "field_definitions": "fieldDefinitionDao",
        "novels": "novelDao",
        "universes": "universeDao",
        "user_preset_templates": "userPresetTemplateDao",
        "search_presets": "searchPresetDao",
        "recent_activities": "recentActivityDao",
    }
    for t, (how, _) in sorted(plan.items()):
        if how != "EXPLICIT":
            continue
        dao = special.get(t)
        check(dao is not None and dao in called_daos,
              f"{t}: executeReset이 {dao}를 부른다")

    print("\n" + "=" * 70)
    if FAILS:
        print(f"실패 {len(FAILS)}건 / 검사 {CHECKS}건")
        for f in FAILS:
            print(f" - {f}")
        return 1
    print(f"전체 통과 — 검사 {CHECKS}건")
    return 0


if __name__ == "__main__":
    sys.exit(main())
