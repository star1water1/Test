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
# ── B-137이 여기 얹힌 이유 (2026.08.08) ──
# 위 결함을 고치자 경고가 **처음으로 실제로 떴고**, 그때 그 문구가 거짓이라는 것이 드러났다:
# *"값 N개가 초기화됩니다"*라고 약속하면서 `applyPropagate`는 값 행을 손대지 않는다.
# 사용자 판정은 **값을 지우지 않는 쪽**이었다(설계 1-11) — 문구를 사실대로 고쳤다.
#
# 그래서 이 검사가 지키는 것이 둘이 됐다: *세는가*(B-135)와 **지우지 않는가**(B-137).
# 뒤엣것을 기계로 잠그는 이유는 **더하고 싶어지는 자리**이기 때문이다 — 미리보기가 수를 세어
# 보이므로 세었으면 지워야 할 것 같고, 단일 필드 편집(`FieldEditDialog`)이 같은 판정으로
# 실제로 값을 `""`로 만든다. 그러나 전파에는 되돌리기가 붙어 있고 그 스냅샷
# (`FieldDefinitionSnapshot`)은 정의만 담아, 값을 지우면 **되돌려도 값은 돌아오지 않는다.**
#
# ── 무엇을 보는가 ──
# ① 필수: 저장소의 전파 미리보기가 `DefaultFieldPlan.typeChanges`를 실제로 부르는가.
# ② 금지: 저장소가 값 채우기를 **직전 템플릿의 타입**으로 정하는가(옛 조건의 부활).
# ③ 금지: 저장소가 값 표에 **쓰는가** — 값 DAO에서 부를 수 있는 것은 `get*`뿐이다(B-137).
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

# ── ③용 — 값 DAO를 부르는 자리를 **메서드까지** 뽑는다 (B-137) ──
# 줄바꿈을 지워 한 줄로 만드는 것은 `db.characterFieldValueDao()\n    .update(...)`처럼
# 끊어 적은 호출을 놓치지 않기 위해서다(코틀린에서 흔한 모양이고, 줄 단위로 보면 조용히 샌다).
value_dao_calls() {
  sed -E 's://.*$::' "$1" \
    | grep -vE '^[[:space:]]*(\*|/\*)' \
    | tr '\n' ' ' \
    | grep -oE '[A-Za-z]*FieldValueDao\(\)[[:space:]]*\.[[:space:]]*[A-Za-z_][A-Za-z0-9_]*' \
    || true
}
# 읽기만 허용한다 — 이름이 `get`으로 시작하지 않으면 쓰기로 본다.
value_dao_writes() {
  local calls; calls=$(value_dao_calls "$1")
  [ -n "$calls" ] || return 0
  printf '%s\n' "$calls" | sed -E 's/.*\.[[:space:]]*//' | grep -vE '^get' || true
}

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

# ── 탐지기 자기 시험 ② — 값 DAO 쓰기 (B-137) ──
# 끊어 적은 호출과 주석 안의 호출을 각각 세워, **잡는지**와 **잘못 잡지 않는지**를 함께 잰다.
SELFTEST2=$(mktemp)
cat > "$SELFTEST2" <<'EOF'
db.characterFieldValueDao().update(fv.copy(value = ""))
db.eventFieldValueDao()
    .deleteByFieldDef(field.id)
val ok = db.novelFieldValueDao().getValuesByFieldDef(field.id).map { it.value }
// db.characterFieldValueDao().update(x) 는 주석이라 세지 않는다
EOF
w=$(value_dao_writes "$SELFTEST2" | grep -c . || true)
rm -f "$SELFTEST2"
if [ "$w" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 ② 실패 — 지어낸 쓰기 2건 중 ${w}건만 잡았습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 ② 통과 (끊어 적은 값 DAO 쓰기를 잡고, 주석·읽기는 세지 않는다)"

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

  # ③ 값 표에 쓰지 않는가 (B-137)
  writes=$(value_dao_writes "$STORE")
  if [ -n "$writes" ]; then
    echo "  ✗ 전파 저장소가 값 표에 쓰고 있습니다(B-137 판정 위반):"
    printf '%s\n' "$writes" | sort -u | sed 's/^/      값 DAO 호출: /'
    echo "      → 전파는 정의만 덮는다. 타입이 안 맞게 된 값도 지우지 않고 그대로 둔다."
    echo "        미리보기가 세는 수는 고지이지 처분이 아니다(설계 1-11, 사용자 확정)."
    echo "        지우려면 되돌리기 스냅샷(FieldDefinitionSnapshot)이 값을 담도록 형식부터"
    echo "        바꿔야 하고, 그것은 사용자 판정이 필요한 일이다."
    fail=1
  fi
fi

if [ "$fail" -eq 0 ]; then
  echo "  ✓ 값을 채우는 조건과 세는 조건이 같은 함수를 지난다"
  echo "  ✓ 전파가 값 표를 읽기만 한다 (B-137 — 세는 것은 고지이지 처분이 아니다)"
  echo ""
  echo "전파 값 채우기 정합 검사 통과"
  exit 0
fi
echo ""
echo "전파 값 채우기 정합 검사 실패" >&2
exit 1
