#!/bin/bash
# 내보내기 데이터 행이 전부 마무리를 거치는가 (B-72 · R-49).
#
# **왜 기계가 봐야 하는가:** 읽기 전용 칸의 회색은 종전에 시트를 다 쓴 뒤 되돌아가 입혔다
# (`applyReadOnlyColumn`). 스트리밍 워크북은 창을 넘긴 행을 디스크로 흘려보내고 메모리에서
# 버리므로 그 되돌아가기가 **조용히 아무 일도 하지 않는다** — 실측으로 500행 중 100행만
# 입혀졌다(`ExportWorkbookParityTest`가 그 모양을 고정한다). 그래서 지금은 **행을 쓰는
# 자리에서** `finishDataRow(row, spec)`가 입힌다.
#
# 문제는 **새 시트를 더할 때**다. 빠뜨려도 오류가 나지 않고, 나오는 파일에서 그 열의 회색만
# 없다 — 즉 **빠뜨렸다는 사실 자체를 아무도 모른다**(세션 착수 규칙 4번: 자동 검증은 보이는
# 것을 못 본다). 규약으로만 적어 두면 반드시 빠지는 부류라(R-29·B-147이 같은 모양을 겪었다)
# 기계가 센다.
#
# ── 무엇을 보는가 ──
# 데이터 행을 만드는 자리(`createRow(0)`이 아닌 `sheet.createRow(`)를 담은 함수는
# `finishDataRow(`를 함께 불러야 한다. 예외는 아래 목록뿐이고 사유를 함께 적는다.
#
# 사용법: tools/check_export_row_finish.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

TARGET="app/src/main/java/com/novelcharacter/app/excel/ExcelExporter.kt"

# 명세(SheetSpec)가 없는 시트 — 읽기 전용 열이라는 개념 자체가 없다.
#   exportInstructions : '사용 안내' 시트. 열 명세가 아니라 문단을 쓰는 자리이고
#                        가져오기가 읽지 않는다(사람이 읽는 시트다).
EXEMPT_FUNCS="exportInstructions"

echo "── 내보내기 행 마무리 검사 (B-72) ──"

[ -f "$TARGET" ] || { echo "  ✗ 대상 파일이 없습니다: $TARGET"; exit 1; }

fail=0

# 함수 단위로 갈라 본다. 함수 머리는 4칸 들여쓰기 `private fun`/`private suspend fun`이다.
report=$(awk '
  /^    private (suspend )?fun [A-Za-z]/ {
    if (fn != "" ) print fn "\t" rows "\t" finish
    match($0, /fun [A-Za-z]+/)
    fn = substr($0, RSTART+4, RLENGTH-4)
    rows = 0; finish = 0
    next
  }
  /sheet\.createRow\(/ && !/createRow\(0\)/ { rows++ }
  /finishDataRow\(/ { finish++ }
  END { if (fn != "") print fn "\t" rows "\t" finish }
' "$TARGET")

while IFS=$'\t' read -r fn rows finish; do
  [ -z "${fn:-}" ] && continue
  [ "${rows:-0}" -eq 0 ] && continue
  exempt=0
  for e in $EXEMPT_FUNCS; do [ "$fn" = "$e" ] && exempt=1; done
  if [ "$exempt" -eq 1 ]; then
    # 예외가 낡지 않았는가 — 그 함수가 사라졌으면 목록을 고쳐야 한다(아래에서 함께 본다).
    continue
  fi
  if [ "${finish:-0}" -eq 0 ]; then
    echo "  ✗ 데이터 행을 마무리하지 않습니다: $fn (행 생성 ${rows}곳)"
    echo "      → 행을 쓴 직후, 같은 반복 안에서 finishDataRow(row, spec)를 부를 것."
    echo "      → 명세가 없는 시트라면 이 스크립트의 EXEMPT_FUNCS에 사유와 함께 올릴 것."
    fail=1
  fi
done <<< "$report"

# 예외 목록이 낡지 않았는가
for e in $EXEMPT_FUNCS; do
  if ! grep -q "fun $e(" "$TARGET"; then
    echo "  ✗ 예외 목록의 함수가 없습니다: $e (이름이 바뀌었으면 목록을 고칠 것)"
    fail=1
  fi
done

# 되돌아가 입히는 방식이 되살아나지 않았는가 — 그것이 이 검사가 막는 결함의 원형이다.
if grep -nE 'sheet\.getRow\(' "$TARGET" >/dev/null 2>&1; then
  echo "  ✗ 다 쓴 뒤 행을 되돌아 읽는 자리가 있습니다(sheet.getRow) — 스트리밍에서 null입니다."
  grep -nE 'sheet\.getRow\(' "$TARGET" | sed 's/^/      /'
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  count=$(printf '%s\n' "$report" | awk -F'\t' '$2>0 {n++} END {print n+0}')
  echo "  ✓ 데이터 행을 만드는 함수 ${count}개가 전부 마무리를 거친다(예외 목록 포함)"
  echo "  ✓ 되돌아가 스타일을 입히는 자리가 없다"
  echo ""
  echo "내보내기 행 마무리 검사 통과"
  exit 0
fi
echo ""
echo "내보내기 행 마무리 검사 실패" >&2
exit 1
