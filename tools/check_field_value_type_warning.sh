#!/bin/bash
# 필드값 쓰기 경로의 타입 경고 완전성 검사 (규약 R-71 · 2026.11.21)
#
# ## 무엇을 보는가
#
# **필드값을 쓰는 모든 경로가 `warnTypeMismatch`를 지나는가.**
#
# 쓰기 경로의 표식은 `FieldValueCellPlan.effectOf(` 다 — 처분(신규·갱신·동일·비움·없음)을
# 가르는 단일 소스이므로(R-33), 그 호출이 곧 *"이 자리가 필드값을 쓴다"*는 뜻이다.
# 그 앞 [WINDOW]줄 안에 경고 호출이 없으면 **그 시트만 조용히 저장하는 경로**다.
#
# ## 왜 검사가 필요한가 — 실패의 모양이 침묵이다
#
# 2026.11.07 워크스루 S4가 관찰로 잡았다: *"가져오기의 숫자 아닌 값 경고는 캐릭터 시트에만
# 뜨고 사건·작품 시트에는 없다."* 실제로 빠진 자리는 **다섯**이었고(세 오버플로 시트 +
# 작품·사건 시트의 필드 열), 넷은 아무도 세지 않았다. 사건 숫자 필드에 `many`를 적어
# 들이면 값은 저장되는데 경고가 없고, 그 값은 수식에서 `0.0`으로 섞여 들어 **화면 어디에도
# 이상이 보이지 않는다**(개발 의도 2번의 끊긴 고리).
#
#   - 컴파일: 못 본다. 경고를 안 부르는 것은 문법이 아니다.
#   - 순수 JVM 시험: 못 본다. `ExcelImportService`는 Room을 쓰므로 하네스가 닿지 않는다
#     (판정과 문구만 `FieldValueTypeWarningTest`가 덮는다 — 그것이 *불리는가*는 못 본다).
#   - 사람: 못 봤다. 다섯 자리가 석 달을 그대로 있었다.
#
# `check_jvm_test_targets.sh`·`check_pending_key.sh`와 같은 계열이고 근거도 같다:
# **"관행이라고 적어도 지켜지지 않는다. 지켜지게 하려면 적는 것 말고 기계가 있어야 한다."**
#
# 사용법: tools/check_field_value_type_warning.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt"
# **정규식이 아니라 고정 문자열로 찾는다.** 종전에는 `\(`를 escape한 정규식을 `awk -v`로
# 넘겼는데, **`-v`는 값의 이스케이프를 먼저 처리한다** — `\(`가 `(`가 되어 정규식이 짝 없는
# 괄호로 깨지고, awk 구현에 따라 치명적 오류로 죽는다. 로컬(mawk)은 견디고 **CI에서 죽었다**:
# 출력이 통째로 비어 자기 시험이 *"위반 건(1 기대)"*이라는 빈 수를 찍었다(2026.11.21 실측).
# 찾는 것이 애초에 고정 문자열이므로 `index()`로 찾아 그 부류를 없앤다.
WARN_FIXED='warnTypeMismatch('
WRITE_FIXED='FieldValueCellPlan.effectOf('
# 경고 호출과 처분 사이에 끼는 주석의 최대 길이. 캐릭터 시트가 14줄이라 넉넉히 둔다 —
# 좁히면 주석 한 줄에 검사가 빨간불이 되고, 그러면 다음 사람이 검사를 끈다.
WINDOW=25

echo "── 필드값 쓰기 경로의 타입 경고 검사 (R-71) ──"

# ── 탐지기 자기 시험 — 안 맞는 규칙은 "위반 없음"과 구별되지 않는다 ──
selftest() {
  local f; f=$(mktemp)
  cat > "$f" <<'EOF'
                warnTypeMismatch(rowLabel, "좋은 시트", fd, value)
                when (FieldValueCellPlan.effectOf(value, existing?.value)) {
                when (FieldValueCellPlan.effectOf(value, existing?.value)) {
EOF
  # 첫 줄이 건수다. 자리 목록은 자기 시험에서 잡음이라 버린다.
  local got; got=$(scan "$f" | head -1)
  rm -f "$f"
  # 첫 쓰기는 경고를 지나고 둘째는 아니다 → 위반 정확히 1건
  if [ "$got" != "1" ]; then
    echo "❌ 탐지기 자기 시험 실패: 위반 $got건(1 기대)"
    echo "   규칙이 어긋났다 — 이 상태의 '위반 0건'은 아무것도 증명하지 않는다."
    exit 1
  fi
}

# 파일 하나를 훑어 **경고를 지나지 않는 쓰기 자리**를 찾는다.
#
# 산출은 **첫 줄이 건수, 그다음이 그 자리들**이다. 종전에는 자리를 `/dev/stderr`로 냈는데,
# 그것도 구현·환경에 기대는 자리라(리다이렉션된 stderr, 열 수 없는 `/dev/stderr`) 한 통로로
# 모았다 — 검사 자신이 환경 차이로 죽으면 그 '통과'는 아무것도 증명하지 않는다.
#
# 판정 둘을 함께 쓴다 — 어느 하나만으로는 샌다:
#   ① 경고가 **이 쓰기 자리 전용**인가(`lastWarn > lastWrite`). 없으면 쓰기 둘이
#      앞의 경고 하나를 나눠 쓰는 모양이 통과한다.
#   ② 그 경고가 **이 자리 근처**인가(`NR - lastWarn <= window`). 없으면 한참 위
#      다른 함수의 경고가 이 자리를 덮는다.
scan() {
  awk -v warn="$WARN_FIXED" -v write="$WRITE_FIXED" -v window="$WINDOW" '
    index($0, warn) { lastWarn = NR }
    index($0, write) {
      if (lastWarn <= lastWrite || lastWarn == 0 || NR - lastWarn > window) {
        hits = hits sprintf("     %d: %s\n", NR, $0)
        bad++
      }
      lastWrite = NR
    }
    END { printf "%d\n%s", bad + 0, hits }
  ' "$1"
}

selftest

if [ ! -f "$SRC" ]; then
  echo "❌ $SRC 가 없다 — 경로가 바뀌었으면 이 검사를 함께 옮길 것."
  exit 1
fi

TOTAL=$(grep -cF "$WRITE_FIXED" "$SRC")
REPORT=$(scan "$SRC")
VIOLATIONS=$(printf '%s\n' "$REPORT" | head -1)
SITES=$(printf '%s\n' "$REPORT" | tail -n +2)

if [ "$TOTAL" -eq 0 ]; then
  echo "❌ 쓰기 표식($WRITE_FIXED)을 한 건도 못 찾았다 — 처분의 단일 소스가 바뀌었다."
  echo "   그 경우 이 검사는 늘 초록이면서 아무것도 보지 않는다. 표식을 함께 고칠 것."
  exit 1
fi

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "$SITES"
  echo "❌ 타입 경고를 지나지 않는 필드값 쓰기 $VIOLATIONS건 (전체 $TOTAL건)"
  echo "   그 자리 앞에 다음 한 줄을 넣을 것 — 값은 그대로 저장하고 사실만 말한다:"
  echo "     warnTypeMismatch(<행 접두>, <시트 이름>, <필드 정의>, <셀 값>)"
  exit 1
fi

echo "✅ 필드값 쓰기 $TOTAL곳이 전부 타입 경고를 지난다"
