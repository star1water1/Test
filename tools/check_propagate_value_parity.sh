#!/bin/bash
# 전파 미리보기의 값 채우기 정합 검사 (B-135) — **채우는 조건과 세는 조건은 한 벌이어야 한다.**
#
# 배경: 전역 기본 필드의 전파 미리보기는 *"밀면 값 N개가 초기화됩니다"*를 세계관마다 말한다.
# 세는 쪽(`DefaultFieldPlan.planPropagate`)은 **세계관마다** `field.type != template.type`으로
# 정확히 판정했는데, 값을 **채우는** 쪽(`DefaultFieldTemplateRepository.planPropagate`)이
# `previous != null && previous.type != template.type`이라 **템플릿 단위**로 물었다.
# 채우는 조건이 좁으면 세는 쪽은 빈 목록을 받아 **항상 0**을 낸다 — 경고 줄 조건이
# `incompatibleValues > 0`이라 참이 될 수 없고, 관리 화면의 '전파'는 `previous = null`로
# 열리므로 **정식 경로에서 분석이 통째로 죽어 있었다.**
#
# 고장이 조용한 것이 이 검사를 다는 이유다: 아무것도 실패하지 않고, 경고가 안 뜬 화면은
# *"영향이 없다"*와 구별되지 않는다. 게다가 저장소는 Room에 매달려 있어 **순수 JVM 시험이
# 원리적으로 못 본다** — 순수 시험은 두 조건이 *같은 함수를 지나는지*까지만 잴 수 있고
# (`DefaultFieldPlanTest.값을_채우는_조건과_세는_조건이_같다`), *저장소가 그 함수를 부르는지*는
# 이 검사만 본다. B-133이 `naturalKeyOfRow`에서 마주쳤던 것과 같은 사각이다.
#
# ── 무엇을 보는가 ──
# ① 필수: 저장소의 전파 미리보기가 `DefaultFieldPlan.typeChanges`를 실제로 부르는가.
# ② 금지: 저장소가 값 채우기를 **직전 템플릿의 타입**으로 정하는가(옛 조건의 부활).
#
# 사용법: tools/check_propagate_value_parity.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app"
STORE="$SRC/data/repository/DefaultFieldTemplateRepository.kt"
PLAN="$SRC/util/DefaultFieldPlan.kt"

echo "── 전파 값 채우기 정합 검사 (B-135) ──"

# 옛 조건을 되살린 코드를 잡는 정규식 — 이름이 바뀌어도 *직전 템플릿의 타입으로 가른다*는
# 모양은 남는다. 공백·널 안전 호출·비교 방향을 전부 흡수한다.
BANNED='previous *\??\.type *[!=]= *[A-Za-z_][A-Za-z0-9_.]*\.type|[A-Za-z_][A-Za-z0-9_.]*\.type *[!=]= *previous *\??\.type'

# 주석은 대상 밖이다 — **옛 조건을 이름으로 적어 두는 것이 이 저장소의 관행**이라
# (무엇이 왜 틀렸는지를 고친 자리에 남긴다) 주석을 세면 바르게 고친 코드가 위반이 된다.
# 실제로 이 검사를 처음 돌렸을 때 그렇게 걸렸다.
strip_comments() { grep -nE "$BANNED" "$1" | grep -vE '^[0-9]+: *(//|\*|/\*)' || true; }

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 정규식이 안 맞으면 위반이 있어도 "위반 없음"과 구별되지 않는다(B-146이 겪은 그 부류).
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
val typeChanging = previous != null && previous.type != template.type
val other = template.type == previous?.type
val fine = DefaultFieldPlan.typeChanges(field, template)
// 주석 안의 previous.type != template.type 는 세지 않는다
EOF
hits=$(strip_comments "$SELFTEST" | grep -c . || true)
rm -f "$SELFTEST"
if [ "$hits" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 위반 2줄 중 ${hits}줄만 잡았습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (직전 템플릿 타입으로 가르는 식을 잡는다)"

fail=0

[ -f "$PLAN" ] || { echo "  ✗ 단일 소스가 없습니다: $PLAN"; fail=1; }
if [ -f "$PLAN" ] && ! grep -q 'fun typeChanges' "$PLAN"; then
  echo "  ✗ 단일 소스에 typeChanges가 없습니다: $PLAN"
  echo "      → 타입이 바뀌는가의 판정은 순수 계층 하나가 낸다. 저장소가 정하면 두 벌이 된다."
  fail=1
fi

if [ ! -f "$STORE" ]; then
  echo "  ✗ 등재된 소비처가 없습니다: $STORE (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
  fail=1
else
  if ! grep -q 'DefaultFieldPlan\.typeChanges' "$STORE"; then
    echo "  ✗ 전파 미리보기가 값 채우기를 스스로 정하고 있습니다: $STORE"
    echo "      → DefaultFieldPlan.typeChanges 에 물어서 읽을 행을 가릴 것."
    fail=1
  fi
  offenders=$(strip_comments "$STORE")
  if [ -n "$offenders" ]; then
    echo "  ✗ 직전 템플릿의 타입으로 값 채우기를 가르고 있습니다(B-135의 옛 조건):"
    echo "$offenders" | sed 's/^/      /'
    echo "      → 타입 갈라짐은 세계관마다 생긴다. DefaultFieldPlan.typeChanges 로 물을 것."
    fail=1
  fi
fi

if [ "$fail" -eq 0 ]; then
  echo "  ✓ 값을 채우는 조건과 세는 조건이 같은 함수를 지난다"
  echo ""
  echo "전파 값 채우기 정합 검사 통과"
  exit 0
fi
echo ""
echo "전파 값 채우기 정합 검사 실패" >&2
exit 1
