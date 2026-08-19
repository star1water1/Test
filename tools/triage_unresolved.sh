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
# ⚠️ **아래 `grep -vE`의 `-E`가 이 선별기의 생명이다 (2026.08.19 · B-251 ⓓ 실측).**
# 종전에는 `-E` 없이 `grep -v '^import +com\.novelcharacter\.'`였는데, **BRE에서 `+`는
# 수량자가 아니라 글자**라 그 패턴은 `import` 뒤에 진짜 `+`를 요구했다 — 즉 **아무것도
# 거르지 못했다.** 실측: 저장소 자기 import **1,627줄이 그대로 통과**했다.
# 결과는 조용하고 컸다 — **저장소가 어디선가 import 하는 타입은 전부 EXTERNAL에 실려
# DECLS에서 빠졌고**, 그래서 이 선별기는 *아무 데서도 import 되지 않는 타입*만 볼 수
# 있었다(하려던 것의 정반대에 가깝다). 2026.08.07 신설 이래 그 상태였고,
# **그동안의 초록은 대부분 아무것도 증명하지 않았다.**
# `StatsSnapshot` 누락(B-251 ⓓ)을 CI가 먼저 잡은 것이 이 결함의 실증이다.
EXTERNAL=$(grep -rhoE '^import +[a-z][A-Za-z0-9_.]*\.[A-Z][A-Za-z0-9_]*' "$SRC" --include=*.kt 2>/dev/null \
           | grep -vE '^import +com\.novelcharacter\.' \
           | sed -E 's/.*\.([A-Z][A-Za-z0-9_]*)$/\1/' | sort -u)

DECLS=$(comm -23 <(echo "$ALL_DECLS") <(echo "$EXTERNAL"))

# 미해석 심벌 이름 전부.
#
# ⚠️ **문구가 하나가 아니다 (2026.08.19 · B-251 ⓓ에서 실측으로 물렸다).**
# 종전에는 `unresolved reference 'X'` 하나만 봤는데, 코틀린은 **같은 결함을 자리에 따라
# 다른 문구로** 낸다 — 미해석 타입이 **시그니처의 반환 타입**이면
# `<ERROR TYPE REF: Symbol not found for X>`로 나온다. 그 꼴만 나오면 이 선별기는
# **아무것도 못 보고 초록**이다.
#
# 실제로 그렇게 놓쳤다: `StatsSnapshot`을 `util/`로 옮기며 `StatsViewModel.kt`의 import를
# 빠뜨렸는데(같은 패키지에 있던 타입이라 원래 import가 없었다 — **개명이 아니라 이사라서
# 고칠 import 자체가 없던 자리다**), 차분 컴파일은 그것을
# `suspend fun ensureSnapshot(): <ERROR TYPE REF: Symbol not found for StatsSnapshot>`로
# **정확히 보고했는데** 이 선별기가 통과시켰고 **CI가 잡았다** — 이 도구가 막으라고
# 세워진 바로 그 실패다(B-113의 재발이며, 그때와 다른 것은 *문구*뿐이다).
#
# **교훈은 이 저장소가 최근 두 번 적은 것과 같다: 글자 꼴로 찾는 그물은 꼴이 바뀌는 날
# 조용히 죽는다.** 그래서 여기서는 **꼴을 늘리고**, 늘린 것이 실제로 잡는지를
# 아래 자기 시험이 확인한다.
SYMS=$( { grep -oE "unresolved reference '[A-Za-z0-9_]+'" "$IN" 2>/dev/null \
            | sed -E "s/unresolved reference '(.*)'/\1/"
          grep -oE "Symbol not found for [A-Za-z0-9_]+" "$IN" 2>/dev/null \
            | sed -E "s/Symbol not found for //"
        } | sort -u)

# **자기 시험 — 그물이 두 꼴을 다 뜨는가.** 하나라도 못 뜨면 이 선별기는 *자기가 죽은 것*을
# 초록으로 보고한다(B-263 ⓐ가 겪은 그 모양). 값을 세지 않고 **뜨는가만** 본다.
_probe="dummy: error: unresolved reference 'ZzTriageProbeA'.
dummy: error: fun f(): <ERROR TYPE REF: Symbol not found for ZzTriageProbeB>"
for _want in ZzTriageProbeA ZzTriageProbeB; do
  echo "$_probe" \
    | { grep -oE "unresolved reference '[A-Za-z0-9_]+'" | sed -E "s/unresolved reference '(.*)'/\1/"
        echo "$_probe" | grep -oE "Symbol not found for [A-Za-z0-9_]+" | sed -E "s/Symbol not found for //"; } \
    | grep -qx "$_want" || {
      echo "⚠️  선별기의 그물이 '$_want' 꼴을 못 뜬다 — 이 산출을 믿지 말 것." >&2; exit 1; }
done

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
  grep -nE "unresolved reference '$s'|Symbol not found for $s" "$IN" | head -3 | sed 's/^/          /'
done
echo ""
echo "      → import가 빠졌거나 이름이 틀렸다. 다른 패키지의 타입은 import 없이 쓰이지 않는다."
echo ""
echo "미해석 심벌 선별 실패" >&2
exit 1
