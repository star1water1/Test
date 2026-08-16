#!/bin/bash
# 링크 묶음 해제 문장의 계약 검사 (B-243 · data/dao/ImageMetaDao).
#
# 배경: 삭제 루프 둘(`OrganizeFolderService`의 `_삭제승인/` · `CharacterSaveCoordinator`의
# 편집창 삭제)은 `linkGroupId`를 **루프 앞에서 일괄로 한 번** 떠서 항목마다 `delete`에 먹인다.
# 앞 항목의 삭제가 뒤 항목의 토큰을 풀 수 있으므로 그 값은 **낡을 수 있는데**, 낡아도 답이
# 같다는 것이 그 일괄 조회의 유일한 근거다(정본은 `ImageDeletionService.delete`의
# `@param linkGroupId` 문단). 그 근거는 두 문장의 **모양**에 통째로 기대고 있다:
#
#   [A] `clearGroupIfSingleton` — **토큰으로 행을 고르고**(`linkGroupId = :groupId`)
#       **식구 수로 가른다**(`COUNT(*) … <= 1`). 낡은 토큰은 식구 0인 토큰뿐이라
#       `WHERE`에 걸리는 행이 없어 0행 UPDATE다.
#   [B] `clearSingletonGroups`(일괄판, B-239) — 같은 성질. 폴더 왕복의 마지막 정리가
#       루프 내내 모아 둔 `touchedGroups`를 넘기므로 **거기 실린 토큰도 낡아 있다.**
#
# **이 문장이 *행 id*로 고르거나 식구 수 가드를 잃으면 그 근거가 조용히 무너진다** —
# 낡은 토큰이 지우면 안 될 표식을 풀고, 그것은 오류도 고지도 없다(개발 의도 2번).
# 코틀린이 아니라 SQL이라 타입 검사도 순수 시험도 원리적으로 닿지 않는 자리라 검사로 세운다
# (`check_faction_standing.sh` [B]와 같은 부류 — Room `@Query`는 코틀린을 부를 수 없다).
#
# 이 검사가 보지 않는 것: 해제를 **id로** 하는 `setGroup(ids, null)`은 대상이 아니다.
# 그쪽은 부르는 자리가 제 행만 가리키므로 낡을 토큰 자체가 없다.
#
# 사용법: tools/check_group_dissolve_contract.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

DAO=app/src/main/java/com/novelcharacter/app/data/dao/ImageMetaDao.kt

echo "── 링크 묶음 해제 문장의 계약 검사 (B-243) ──"

# 한 함수의 `@Query` 본문을 뽑는다 — 선언 줄에서 거슬러 올라가 가장 가까운 `@Query`부터
# 그 선언까지를 한 덩이로 본다(이 저장소는 세 줄짜리 `"""` 질의를 쓴다).
# 산출은 한 줄로 접은 SQL이고, 없으면 빈 문자열이다.
query_of() {
  python3 - "$1" "$2" <<'PY'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
name = sys.argv[2]
decl = re.search(r'(?:suspend\s+)?fun\s+' + re.escape(name) + r'\s*\(', src)
if not decl:
    print(''); sys.exit(0)
# **선언에서 거슬러 올라가 가장 가까운 `@Query`를 잡는다.** 앞에서부터 비탐욕으로 찾으면
# `.`이 줄바꿈을 먹어 **앞선 질의를 통째로 삼킨다**(자기 시험이 실측으로 잡았다).
head = src[:decl.start()]
at = head.rfind('@Query')
if at < 0:
    print(''); sys.exit(0)
# 괄호 균형으로 `@Query(...)` 본문을 뜬다 — 문자열 안의 괄호를 세지 않으려고
# 리터럴을 먼저 지운 사본에서 짝을 찾고, 그 구간을 원본에서 되잘라 쓴다.
tail = src[at:]
open_i = tail.find('(')
if open_i < 0:
    print(''); sys.exit(0)
masked = re.sub(r'""".*?"""', lambda m: '"' * len(m.group(0)), tail, flags=re.S)
masked = re.sub(r'(?<!")"(?:[^"\\\n]|\\.)*"', lambda m: '"' * len(m.group(0)), masked)
depth, close_i = 0, -1
for i in range(open_i, len(masked)):
    c = masked[i]
    if c == '(':
        depth += 1
    elif c == ')':
        depth -= 1
        if depth == 0:
            close_i = i
            break
if close_i < 0:
    print(''); sys.exit(0)
body = tail[open_i + 1:close_i]
# 문자열 리터럴만 남긴다(`"""…"""` 또는 `"…"`).
lits = re.findall(r'"""(.*?)"""', body, re.S) or re.findall(r'"(.*?)"', body, re.S)
print(' '.join(''.join(lits).split()))
PY
}

# 한 문장이 [A]/[B]의 성질을 지키는가 — 토큰 선택 · 식구 수 가드.
# 산출: `ok` 또는 빠진 성질 이름.
verdict_of() {
  local sql="$1" missing=""
  # 토큰으로 고르는가 — `linkGroupId = :param` 또는 `linkGroupId IN (…)`.
  printf '%s' "$sql" | grep -qiE 'WHERE +linkGroupId +(= *:|IN *\()' || missing="$missing 토큰-선택"
  # 식구 수로 가르는가 — `COUNT(*)` 와 `<= 1`이 함께 있어야 한다.
  printf '%s' "$sql" | grep -qi 'COUNT( *\* *)' || missing="$missing 식구-계수"
  printf '%s' "$sql" | grep -qE '<= *1' || missing="$missing 하한-가드"
  [ -z "$missing" ] && printf 'ok' || printf '%s' "${missing# }"
}

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 뽑기가 실패하면(정규식이 안 맞으면) 빈 SQL이 되고, 빈 SQL은 세 성질을 전부 잃으므로
# 아래 '뽑지 못함' 갈래로 떨어진다. 그 갈래가 실제로 도는지까지 여기서 확인한다.
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
    @Query(
        """UPDATE image_meta SET linkGroupId = NULL WHERE linkGroupId = :groupId
           AND (SELECT COUNT(*) FROM image_meta WHERE linkGroupId = :groupId) <= 1"""
    )
    suspend fun goodSingle(groupId: String)

    @Query(
        """UPDATE image_meta SET linkGroupId = NULL WHERE linkGroupId IN (
               SELECT linkGroupId FROM image_meta WHERE linkGroupId IN (:groupIds)
               GROUP BY linkGroupId HAVING COUNT(*) <= 1
           )"""
    )
    suspend fun goodBatch(groupIds: List<String>)

    /** 행 id로 고른다 — 낡은 토큰 논증이 통째로 무너지는 모양이다. */
    @Query("UPDATE image_meta SET linkGroupId = NULL WHERE id = :id AND (SELECT COUNT(*) FROM image_meta) <= 1")
    suspend fun byRowId(id: Long)

    /** 가드가 없다 — 토큰만 맞으면 몇 장이든 푼다. */
    @Query("UPDATE image_meta SET linkGroupId = NULL WHERE linkGroupId = :groupId")
    suspend fun noGuard(groupId: String)
EOF
st_fail=0
[ "$(verdict_of "$(query_of "$SELFTEST" goodSingle)")" = "ok" ] || st_fail=1
[ "$(verdict_of "$(query_of "$SELFTEST" goodBatch)")" = "ok" ] || st_fail=1
[ "$(verdict_of "$(query_of "$SELFTEST" byRowId)")" = "ok" ] && st_fail=1
[ "$(verdict_of "$(query_of "$SELFTEST" noGuard)")" = "ok" ] && st_fail=1
# 없는 이름은 빈 SQL이고, 빈 SQL이 'ok'로 읽히면 이 검사는 **영영 초록**이다.
[ -n "$(query_of "$SELFTEST" 없는이름)" ] && st_fail=1
[ "$(verdict_of "")" = "ok" ] && st_fail=1
rm -f "$SELFTEST"
if [ "$st_fail" -ne 0 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 위반(행 id로 고르기 · 가드 없음 · 뽑기 실패)을 못 잡거나" >&2
  echo "     멀쩡한 두 문장을 위반으로 잡았습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (행 id 선택·가드 없음·뽑기 실패를 잡고, 단수/일괄 두 문장은 통과)"

[ -f "$DAO" ] || { echo "  ✗ DAO가 없습니다: $DAO"; exit 1; }

fail=0
for fn in clearGroupIfSingleton clearSingletonGroups; do
  sql=$(query_of "$DAO" "$fn")
  if [ -z "$sql" ]; then
    echo "  ✗ $fn 의 @Query 를 뽑지 못했습니다 — 이름이 바뀌었거나 문장이 사라졌습니다"
    fail=1
    continue
  fi
  v=$(verdict_of "$sql")
  if [ "$v" != "ok" ]; then
    echo "  ✗ $fn 이 낡은 토큰 논증의 전제를 잃었습니다 (빠진 성질: $v)"
    echo "      $sql"
    fail=1
  else
    echo "  ✓ $fn — 토큰으로 고르고 식구 수로 가른다"
  fi
done

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "  고치는 법: 문장을 이 모양으로 되돌리거나, 정말 바꿔야 한다면 **먼저**"
  echo "             ImageDeletionService.delete 의 @param linkGroupId 문단을 고치고"
  echo "             삭제 루프 둘의 일괄 조회를 항목마다 읽는 형태로 되돌릴 것"
  echo "             (util/OrganizeFolderService ③-a · ui/character/CharacterSaveCoordinator)."
  echo ""
  echo "링크 묶음 해제 문장의 계약 검사 실패"
  exit 1
fi

echo ""
echo "링크 묶음 해제 문장의 계약 검사 통과"
