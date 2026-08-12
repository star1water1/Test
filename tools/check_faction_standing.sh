#!/bin/bash
# '지금 소속' 판정의 단일 소스 검사 (B-171 · util/FactionStanding).
#
# 배경: *"이 캐릭터는 지금 어느 세력인가"*는 표에 적혀 있지 않고 **파생된다** — `leaveType`이
# 비었는가로 정해지는데, 그 규칙이 착수 시점에 통계·세력 관리·캐릭터 AI 문맥·엑셀 가져오기와
# DAO의 SQL에 각자 적혀 있었다. 대결 카드(B-171)가 그것을 하나 더 적으면 **두 화면이 다른
# 세력 수를 말하는 것이 시간 문제**다(원칙 05 · B-117이 배운 그 부류).
#
# ## 축이 둘이다 — 코틀린과 SQL
#
#   [A] **코틀린**: `leaveType == null` / `leaveType != null`을 단일 소스 밖에서 적는가.
#       Room 질의가 아닌 자리는 전부 `FactionStanding`을 지나야 한다.
#
#   [B] **SQL 쌍둥이**: Room `@Query`는 코틀린 함수를 부를 수 없어 같은 술어가 DAO에 글자로
#       한 벌 더 산다. 그 글자가 `FactionStanding.SQL_CURRENT`와 **다르면 실패한다.**
#       [A]만으로는 이 축이 원리적으로 안 잡힌다 — SQL은 코틀린 문법이 아니라서다.
#       실제로 이 자리에 `leaveYear IS NULL`을 보태고 싶은 유혹이 있고(엑셀로 들어온
#       `탈퇴연도만 적힌 행`을 보면 그렇게 하고 싶어진다), 그러면 코틀린 쪽과 조용히 갈린다.
#
# 면제: 값을 견주는 것이 목적인 자리(가져오기 매칭의 `it.leaveType == row.leaveType`)는
# 애초에 이 패턴에 걸리지 않는다 — `null`과 견주는 것만 본다.
#
# 사용법: tools/check_faction_standing.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC=app/src/main/java/com/novelcharacter/app
SINGLE_SOURCE=$SRC/util/FactionStanding.kt
DAO=$SRC/data/dao/FactionMembershipDao.kt

echo "── '지금 소속' 단일 소스 검사 (B-171) ──"

fail=0

[ -f "$SINGLE_SOURCE" ] || { echo "  ✗ 단일 소스가 없습니다: $SINGLE_SOURCE"; fail=1; }
[ -f "$DAO" ] || { echo "  ✗ DAO가 없습니다: $DAO"; fail=1; }
[ "$fail" -eq 0 ] || exit 1

# ── [A] 코틀린 ──
hits=$(grep -rn 'leaveType *[=!]= *null' "$SRC" --include=*.kt 2>/dev/null \
  | grep -v "^$SINGLE_SOURCE:" || true)
if [ -n "$hits" ]; then
  echo "  ✗ 단일 소스 밖에서 '지금 소속'을 직접 판정합니다"
  echo "$hits" | sed 's/^/      /'
  echo ""
  echo "  고치는 법: util/FactionStanding 의 isCurrent/current/hasCurrent/countCurrent/"
  echo "             currentFactionIds 를 쓸 것 — 판정이 둘이 되면 화면마다 다른 수가 나옵니다."
  fail=1
else
  echo "  ✓ 코틀린 판정은 전부 FactionStanding 을 지납니다"
fi

# ── [B] SQL 쌍둥이 ──
# 단일 소스가 선언한 술어를 그대로 뽑는다 — 여기 글자로 적지 않는 것이 요점이다(적으면 갈린다).
expected=$(sed -n 's/.*const val SQL_CURRENT *= *"\(.*\)".*/\1/p' "$SINGLE_SOURCE" | head -1)
if [ -z "$expected" ]; then
  echo "  ✗ 단일 소스에서 SQL_CURRENT 를 읽지 못했습니다 ($SINGLE_SOURCE)"
  fail=1
else
  # DAO에서 leave* 열을 쓰는 술어를 전부 뽑아 기대값과 견준다.
  # `IS NULL`/`IS NOT NULL`/`= ...` 어느 모양이든 걸리게 두고, 통과는 기대값 하나뿐이다.
  bad=$(grep -oE 'leave(Type|Year) +IS +(NOT +)?NULL|leave(Type|Year) *= *[^ )]+' "$DAO" \
    | grep -vxF "$expected" || true)
  if [ -n "$bad" ]; then
    echo "  ✗ DAO의 소속 술어가 SQL_CURRENT(\"$expected\")와 다릅니다"
    echo "$bad" | sort -u | sed 's/^/      /'
    echo ""
    echo "  고치는 법: '지금 소속'은 한 술어여야 합니다. 다른 뜻의 질의가 정말 필요하면"
    echo "             FactionStanding 의 KDoc에 그 물음을 먼저 적고 이 검사를 함께 넓힐 것."
    fail=1
  else
    used=$(grep -cF "$expected" "$DAO")
    if [ "$used" -eq 0 ]; then
      echo "  ✗ DAO에 SQL 쌍둥이가 없습니다 — 단일 소스가 가리키는 자리가 사라졌습니다"
      fail=1
    else
      echo "  ✓ DAO의 소속 술어가 전부 SQL_CURRENT 와 같습니다 (${used}자리)"
    fi
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "'지금 소속' 단일 소스 검사 실패"
  exit 1
fi

echo ""
echo "'지금 소속' 단일 소스 검사 통과"
