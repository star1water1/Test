#!/bin/bash
# 차분 컴파일의 `unresolved reference` 중 **진짜**를 골라낸다 (B-113, 2026.08.07)
#
# 배경(값비싸게 배웠다): 차분 컴파일은 Android SDK·ViewBinding 생성 코드가 없어
# `unresolved reference`를 수백 건 뱉는다. 그래서 관행이 "기준선과 comm으로 비교하고,
# 무더기로 나오면 메시지 단위로 접는다"인데 — **접기의 뒷면이 두 번째로 물었다.**
#
#   1차(2026.08.03): `sort -u`가 같은 문구의 진짜 신규를 함께 감췄다.
#   2차(2026.08.07): B-113 구현이 `FieldEditDialog`에서 다른 패키지의
#      `DuelGradeApplySheet`를 **import 없이** 썼다. 차분 컴파일이 그것을
#      `unresolved reference 'DuelGradeApplySheet'`로 **정확히 보고했는데**,
#      같은 목록에 ViewBinding 필드(`duelGradeBody`·`btnDuelGradeApply` …)와
#      프레임워크 멤버(`isFakeBoldText`·`clipRect` …)가 함께 있어 **전부 노이즈로
#      분류했다.** CI가 잡았고, 그 전까지 master가 컴파일되지 않는 상태였다.
#
# ── 판별식 ──
# **미해석 심벌이 이 저장소 소스에 선언된 최상위 타입이면 노이즈가 아니다.**
# ViewBinding 필드도 프레임워크 멤버도 `class/object/interface`로 선언돼 있지 않다.
# 반대로 우리가 만든 클래스는 선언이 실재하므로, 그것이 "못 찾겠다"고 나오면
# **import가 없거나 이름이 틀린 것**이다 — 어느 쪽이든 실제 컴파일 오류다.
#
# 사용법:
#   JARS_DIR=... tools/differential_compile.sh /tmp/cur.txt
#   tools/triage_unresolved.sh /tmp/cur.txt      # 진짜 후보가 있으면 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

IN="${1:-}"
[ -n "$IN" ] && [ -f "$IN" ] || { echo "사용법: tools/triage_unresolved.sh <차분컴파일 출력파일>" >&2; exit 2; }

SRC="app/src/main/java"

# 저장소가 선언한 **최상위** 타입 이름 전부.
# 중첩 타입을 빼는 것이 요점이다 — 중첩은 `Outer.Result`로 참조되므로 **맨이름 미해석의
# 원인이 될 수 없다.** 넣어 두면 `Result`처럼 흔한 중첩 이름이 매번 오탐으로 걸린다.
ALL_DECLS=$(grep -rhoE '^(internal |private |open |abstract |sealed |data |enum )*(class|object|interface)[[:space:]]+[A-Z][A-Za-z0-9_]*' \
          "$SRC" --include=*.kt 2>/dev/null \
        | sed -E 's/.*(class|object|interface)[[:space:]]+//' | sort -u)

# **바깥 이름과 겹치는 것은 뺀다.** `View`·`Entry`·`Index`·`Result`·`Row`는 우리도 선언하고
# 프레임워크에도 있어서, 미해석의 원인이 어느 쪽인지 이름만으로 가릴 수 없다(SDK가 없는
# 차분 컴파일에서는 프레임워크 쪽이 원인일 때가 압도적으로 많다). 저장소가 **바깥에서
# import 하는 이름**을 모아 빼면, 남는 것은 오직 우리 것만 가리키는 이름들이다.
EXTERNAL=$(grep -rhoE '^import +[a-z][A-Za-z0-9_.]*\.[A-Z][A-Za-z0-9_]*' "$SRC" --include=*.kt 2>/dev/null \
           | grep -v '^import +com\.novelcharacter\.' \
           | sed -E 's/.*\.([A-Z][A-Za-z0-9_]*)$/\1/' | sort -u)

DECLS=$(comm -23 <(echo "$ALL_DECLS") <(echo "$EXTERNAL"))

# 미해석 심벌 이름 전부
SYMS=$(grep -oE "unresolved reference '[A-Za-z0-9_]+'" "$IN" 2>/dev/null \
       | sed -E "s/unresolved reference '(.*)'/\1/" | sort -u)

echo "── 차분 컴파일 미해석 심벌 선별 ──"

hits=$(comm -12 <(echo "$SYMS") <(echo "$DECLS"))

if [ -z "$hits" ]; then
  echo "  ✓ 저장소가 선언한 타입 중 미해석인 것 없음 (나머지는 SDK·ViewBinding 노이즈)"
  echo ""
  echo "미해석 심벌 선별 통과"
  exit 0
fi

echo "  ✗ **이 저장소가 선언한 타입인데 미해석이다 — 노이즈가 아니라 실제 오류다:**"
for s in $hits; do
  echo "      $s"
  grep -n "unresolved reference '$s'" "$IN" | head -3 | sed 's/^/          /'
done
echo ""
echo "      → import가 빠졌거나 이름이 틀렸다. 다른 패키지의 타입은 import 없이 쓰이지 않는다."
echo ""
echo "미해석 심벌 선별 실패" >&2
exit 1
