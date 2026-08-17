#!/bin/bash
# 내보내기 워크북의 글꼴이 한 자리에서만 정해지는가 (P-10).
#
# **왜 기계가 봐야 하는가:** 글꼴은 두 자리에 걸린다 — 기본 폰트(`applyExportBaseFont`)와
# 새 폰트(`createExportFont`). 한쪽만 걸면 워크북의 **일부만** 덮이는데, 빠뜨린 쪽은
# 오류가 아니라 **그 셀만 Calibri로 그려지는 것**이라 아무도 모른다(세션 착수 규칙 4번:
# 자동 검증은 보이는 것을 못 본다). `check_export_row_finish.sh`가 회색에 대해 세운 것과
# 같은 그물이고, 실제로 이 저장소는 규약으로만 적어 둔 것이 반드시 빠지는 것을 겪었다(R-29·B-147).
#
# ── 무엇을 보는가 ──
#  ① ExcelStyles가 `workbook.createFont()`를 직접 부르지 않는가 — POI는 매번 Calibri로 세워
#     돌려주므로 기본 폰트를 고쳐 두어도 그 폰트에는 미치지 않는다.
#  ② ExcelStyles가 기본 폰트를 못박는가 (`applyExportBaseFont`) — 없으면 폰트를 걸지 않은
#     스타일의 셀(지금의 데이터 셀 대부분)이 통째로 Calibri다.
#  ③ 글꼴 이름이 단일 소스(EXPORT_FONT_NAME)인가 — 생문자열로 적으면 두 벌이 되어 갈린다.
#
# 사용법: tools/check_export_font.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

EXPORTER="app/src/main/java/com/novelcharacter/app/excel/ExcelExporter.kt"
SPEC="app/src/main/java/com/novelcharacter/app/excel/SheetSpec.kt"

echo "── 내보내기 글꼴 검사 (P-10) ──"

fail=0
for f in "$EXPORTER" "$SPEC"; do
  [ -f "$f" ] || { echo "  ✗ 대상 파일이 없습니다: $f"; exit 1; }
done

# ① 직접 createFont — 단일 소스인 SheetSpec의 createExportFont 안에서만 허용된다.
#    **주석 줄은 뺀다** — 이 규칙을 설명하는 주석이 곧 그 규칙의 위반으로 잡히면,
#    다음 사람은 검사를 통과시키려고 설명을 지운다(설명이 사라지는 방향의 그물은 나쁜 그물이다).
direct=$(grep -nE '\.createFont\(\)' "$EXPORTER" | grep -vE '^[0-9]+: *(//|\*|/\*)' || true)
if [ -n "$direct" ]; then
  echo "  ✗ $EXPORTER 가 createFont()를 직접 부릅니다 — 그 폰트는 Calibri입니다."
  printf '%s\n' "$direct" | sed 's/^/      /'
  echo "      → createExportFont(workbook)을 쓸 것(굵기·크기·색은 그 위에 얹는다)."
  fail=1
fi

# ② 기본 폰트를 못박는가
if ! grep -q 'applyExportBaseFont(' "$EXPORTER"; then
  echo "  ✗ $EXPORTER 가 기본 폰트를 못박지 않습니다(applyExportBaseFont)."
  echo "      → 폰트를 걸지 않은 스타일의 셀이 전부 Calibri가 됩니다(ExcelStyles.init)."
  fail=1
fi

# ③ 글꼴 이름의 단일 소스 — 상수 선언 한 줄 말고는 생문자열이 없어야 한다.
name=$(grep -oE 'const val EXPORT_FONT_NAME = "[^"]+"' "$SPEC" | head -1 | sed 's/.*"\(.*\)"/\1/')
if [ -z "${name:-}" ]; then
  echo "  ✗ $SPEC 에 EXPORT_FONT_NAME 선언이 없습니다."
  fail=1
else
  stray=$(grep -rn "\"$name\"" app/src/main/java/com/novelcharacter/app/ \
            | grep -v 'const val EXPORT_FONT_NAME' || true)
  if [ -n "$stray" ]; then
    echo "  ✗ 글꼴 이름을 생문자열로 적은 자리가 있습니다 — 두 벌이 되면 갈립니다."
    printf '%s\n' "$stray" | sed 's/^/      /'
    echo "      → EXPORT_FONT_NAME을 참조할 것."
    fail=1
  fi
fi

if [ "$fail" -eq 0 ]; then
  echo "  ✓ 글꼴은 EXPORT_FONT_NAME('$name') 한 자리가 정한다"
  echo "  ✓ 기본 폰트(applyExportBaseFont)와 새 폰트(createExportFont) 두 자리가 모두 걸려 있다"
  echo ""
  echo "내보내기 글꼴 검사 통과"
  exit 0
fi
echo ""
echo "내보내기 글꼴 검사 실패" >&2
exit 1
