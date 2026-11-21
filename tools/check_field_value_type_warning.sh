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
# awk의 ERE용 — 괄호를 escape한다(그대로 넘기면 "missing )"로 죽는다).
WRITE_ERE='FieldValueCellPlan\.effectOf\('
WARN_ERE='warnTypeMismatch\('
# 세는 쪽은 고정 문자열로 — 같은 글자를 두 문법으로 적지 않게 한자리에 둔다.
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
  local got; got=$(scan "$f" 2>/dev/null)   # 줄 번호 출력은 자기 시험에서 잡음이다
  rm -f "$f"
  # 첫 쓰기는 경고를 지나고 둘째는 아니다 → 위반 정확히 1건
  if [ "$got" != "1" ]; then
    echo "❌ 탐지기 자기 시험 실패: 위반 $got건(1 기대)"
    echo "   규칙이 어긋났다 — 이 상태의 '위반 0건'은 아무것도 증명하지 않는다."
    exit 1
  fi
}

# 파일 하나를 훑어 **경고를 지나지 않는 쓰기 자리의 수**를 센다(줄 번호는 stderr로).
#
# 판정 둘을 함께 쓴다 — 어느 하나만으로는 샌다:
#   ① 경고가 **이 쓰기 자리 전용**인가(`lastWarn > lastWrite`). 없으면 쓰기 둘이
#      앞의 경고 하나를 나눠 쓰는 모양이 통과한다.
#   ② 그 경고가 **이 자리 근처**인가(`NR - lastWarn <= window`). 없으면 한참 위
#      다른 함수의 경고가 이 자리를 덮는다.
scan() {
  awk -v warn="$WARN_ERE" -v write="$WRITE_ERE" -v window="$WINDOW" '
    $0 ~ warn { lastWarn = NR }
    $0 ~ write {
      if (lastWarn <= lastWrite || lastWarn == 0 || NR - lastWarn > window) {
        printf "     %d: %s\n", NR, $0 > "/dev/stderr"
        bad++
      }
      lastWrite = NR
    }
    END { print bad + 0 }
  ' "$1"
}

selftest

if [ ! -f "$SRC" ]; then
  echo "❌ $SRC 가 없다 — 경로가 바뀌었으면 이 검사를 함께 옮길 것."
  exit 1
fi

TOTAL=$(grep -cF "$WRITE_FIXED" "$SRC")
VIOLATIONS=$(scan "$SRC")

if [ "$TOTAL" -eq 0 ]; then
  echo "❌ 쓰기 표식($WRITE_FIXED)을 한 건도 못 찾았다 — 처분의 단일 소스가 바뀌었다."
  echo "   그 경우 이 검사는 늘 초록이면서 아무것도 보지 않는다. 표식을 함께 고칠 것."
  exit 1
fi

if [ "$VIOLATIONS" -gt 0 ]; then
  echo
  echo "❌ 타입 경고를 지나지 않는 필드값 쓰기 $VIOLATIONS건 (전체 $TOTAL건)"
  echo "   그 자리 앞에 다음 한 줄을 넣을 것 — 값은 그대로 저장하고 사실만 말한다:"
  echo "     warnTypeMismatch(<행 접두>, <시트 이름>, <필드 정의>, <셀 값>)"
  exit 1
fi

echo "✅ 필드값 쓰기 $TOTAL곳이 전부 타입 경고를 지난다"
