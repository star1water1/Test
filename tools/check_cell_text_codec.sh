#!/bin/bash
# 셀 글자 쓰기·읽기 규약 검사 (규약 R-72, 2026.11.21)
#
# 배경: 엑셀 파일 형식은 셀 글자를 `_xHHHH_` 표기로 적는다. 종전 내보내기는 그 규약 없이 썼다 —
# XML에 실을 수 없는 글자(`\u0001`·Word의 수동 줄바꿈 `\u000B` 등)는 POI가 `?`로 바꿨고,
# 사용자가 적은 `_x0041_` 꼴은 다른 글자로 읽혔다. 읽기도 갈렸다 — DOM은 해석하고 스트리밍은
# 인라인 문자열·수식 결과를 해석하지 않아 **같은 파일이 크기에 따라 다른 값**이 됐다.
# 처분은 `excel/ExcelCellText`의 한 쌍(encode/decode)이다.
#
# ── 왜 시험이 아니라 검사인가 ──
# 규칙 자체는 `ExcelCellTextTest`가 실제 POI 파일로 잠근다. 그러나 이 결함의 모양은
# **아무도 그 쌍을 부르지 않는 것**이다 — 새 시트가 `setCellValue(name)`을 한 줄 적으면 시험은
# 전부 초록인 채 그 칸에서만 조용히 `?`가 된다(R-47의 `check_list_cell_csv.sh`와 같은 계열).
#
# ── 무엇을 보는가 ──
# ① **쓰는 쪽**: 앱 소스 전체의 `setCellValue(` — 글자를 싣는 호출은 규약을 지나야 한다.
#    통과하는 모양: `ExcelCellText.`을 부르는 줄 · 규약을 지난 값(`encoded.text`) · 숫자
#    (`.toDouble()` · 15자리 판정 `fitsSpreadsheetPrecision(`을 지난 줄 · 숫자 지역 `d`·`number`).
#    숫자 지역 이름 둘은 손 명단이다 — 그 이름으로 글자를 싣는 날 이 검사는 못 본다(아래 한계).
# ② **읽는 쪽**: `StreamingXlsxReader`가 글자 셀을 만드는 자리(`CellType.STRING, stringValue =`)는
#    공유 문자열(POI가 이미 해석한다 — `= s)`)이 아니면 `ExcelCellText.decode(`를 지나야 한다.
#
# ── 이 검사가 못 보는 자리 ──
# 변수를 거쳐 가는 글자: 숫자 지역 이름(`d`·`number`)에 글자를 담아 싣는 코드는 통과한다 —
# 타입을 보려면 컴파일러가 필요하다. 2026.11.21 현재 두 이름은 전부 `Double?`이다.
#
# 사용법: tools/check_cell_text_codec.sh            # 위반 시 exit 1
#         tools/check_cell_text_codec.sh --self-test # 판정기가 위반을 실제로 잡는지 본다
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

MAIN="app/src/main/java/com/novelcharacter/app"
READER="$MAIN/excel/StreamingXlsxReader.kt"

# 주석 줄을 빼고(블록 주석 본문 `*`·한 줄 주석 `//`) 남은 코드의 `setCellValue(` 중 통과 모양이 아닌 것.
write_violations() {
  grep -HnE 'setCellValue\(' "$@" 2>/dev/null \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//)' \
    | grep -vE 'ExcelCellText\.|setCellValue\(encoded\.text\)|\.toDouble\(\)\)|fitsSpreadsheetPrecision\(|setCellValue\((d|number)\)' \
    || true
}

read_violations() {
  grep -nE 'CellType\.STRING, stringValue =' "$1" 2>/dev/null \
    | grep -vE 'ExcelCellText\.decode\(|stringValue = s\)' \
    || true
}

if [ "${1:-}" = "--self-test" ]; then
  T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
  printf '%s\n' \
    '        cell.setCellValue(col.header)' \
    '        // cell.setCellValue(commented)' \
    '        cell.setCellValue(ExcelCellText.encode(x))' \
    '        cell.setCellValue(encoded.text)' \
    '        row.createCell(1).setCellValue(n.toDouble())' \
    '        if (d != null) { setCellValue(d); return }' > "$T/W.kt"
  printf '%s\n' \
    '"inlineStr" -> ExcelCellValue.Primitives(CellType.STRING, stringValue = inlineBuf.toString())' \
    '"s" -> ExcelCellValue.Primitives(CellType.STRING, stringValue = s)' \
    '"str" -> ExcelCellValue.Primitives(CellType.STRING, stringValue = ExcelCellText.decode(buf.toString()))' > "$T/R.kt"
  w=$(write_violations "$T/W.kt" | wc -l); r=$(read_violations "$T/R.kt" | wc -l)
  if [ "$w" -eq 1 ] && [ "$r" -eq 1 ]; then echo "자기 시험 통과 (쓰기 위반 1 · 읽기 위반 1을 잡았다)"; exit 0; fi
  echo "자기 시험 실패 — 쓰기 위반 $w (기대 1) · 읽기 위반 $r (기대 1)"; exit 1
fi

echo "── 셀 글자 쓰기·읽기 규약 검사 (R-72) ──"
fail=0
mapfile -t FILES < <(grep -rlE 'setCellValue\(' "$MAIN" --include='*.kt' | sort)
W=$(write_violations "${FILES[@]}")
if [ -n "$W" ]; then
  echo "✗ 규약을 지나지 않고 셀에 글자를 싣는 자리:"; echo "$W" | sed 's/^/    /'
  echo "  → 데이터 칸은 setTextSafe, 머리글·안내는 setLabelText, 그 밖은 ExcelCellText.encodeWithin을 지난다."
  fail=1
fi
R=$(read_violations "$READER")
if [ -n "$R" ]; then
  echo "✗ 스트리밍 읽기가 해석하지 않고 글자 셀을 만드는 자리:"; echo "$R" | sed 's/^/    /'
  echo "  → ExcelCellText.decode를 지난다(DOM 경로는 POI가 해석한다 — 두 경로가 같은 값을 내야 한다)."
  fail=1
fi
if [ "$fail" -eq 0 ]; then
  echo "✓ 셀에 글자를 싣는 ${#FILES[@]}개 파일 · 스트리밍 글자 셀 전부 규약을 지난다"
fi
exit "$fail"
