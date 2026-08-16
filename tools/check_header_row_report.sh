#!/usr/bin/env bash
# 헤더 행 실패는 조용할 수 없다 (B-231)
#
# **무엇을 막는가:** `import*`가 `sheet.getRow(0) ?: return` 꼴로 **아무 말 없이** 시트를
# 버리는 것. 첫 행이 통째로 빈 시트(표 위에 빈 줄을 넣었거나 편집기가 행을 지운 파일)는
# 그렇게 경고 한 줄 없이 사라진다 — 짝인 '인식되지 않아 무시되었습니다' 경고도 뜨지 않는다.
# `findSheet`가 그 시트를 이미 `consumedSheetNames`에 넣어 그 경고를 **억제하기 때문**이다.
#
# 헤더가 *틀린* 파일은 소리 내어 거부하면서 헤더가 *없는* 파일만 조용히 버린 셈이라,
# 사용자가 알아챌 길이 어디에도 없었다(개발 의도 2번 — 말없이 버리지 않는다).
# 실측: 수리 전 **26자리**가 이 모양이었다.
#
# **왜 시험이 아니라 검사인가:** `ExcelImportService`는 Room에 묶여 순수 하네스가 원리적으로
# 닿지 않고, 진짜 위험은 **새 시트가 같은 두 줄을 복사해 오는 것**이라 시험이 못 막는다
# (시험도 함께 낡는다). R-33·R-53 계열과 같은 근거다.
#
# **고치는 법:** `headerRowOrReport(sheet, 기대헤더, result) ?: return` 하나로 집는다.
# 그 함수가 *없음*과 *틀림*을 갈라 각각 말한다.
#
# 기준선은 **0건**이다.

set -uo pipefail
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
TARGET="$REPO/app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt"

if [ ! -f "$TARGET" ]; then
  echo "대상 파일을 찾을 수 없습니다: $TARGET" >&2
  exit 2
fi

echo "── 헤더 행 무통보 스킵 검사 (B-231) ──"

SCAN=$(python3 - "$TARGET" <<'PY'
import re, sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')

# `import*` 본문만 본다. `analyze*`(미리보기)는 `result`가 없어 말할 채널 자체가 없고,
# 그쪽의 침묵은 숫자 0으로 드러나므로 이 검사의 축이 아니다.
DECL = re.compile(r'^    (?:private )?suspend fun (import[A-Za-z0-9_]*)\s*\(')
# 실패 갈래가 곧장 빠져나가는 모양 — 그 줄에 보고가 없다.
BAD = re.compile(r'getRow\(0\)\s*\?:\s*(return|continue|break)\b')

found, i = [], 0
while i < len(lines):
    m = DECL.match(lines[i])
    if not m:
        i += 1
        continue
    fn, j = m.group(1), i + 1
    while j < len(lines) and lines[j] != '    }':
        code = lines[j].split('//')[0]
        if BAD.search(code):
            found.append((j + 1, fn, lines[j].strip()))
        j += 1
    i = j + 1

for ln, fn, text in found:
    print(f"{ln}\t{fn}\t{text}")
print(f"__HCOUNT__{len(found)}")
PY
)

hcount=$(printf '%s\n' "$SCAN" | sed -n 's/^__HCOUNT__//p')
hbody=$(printf '%s\n' "$SCAN" | grep -v '^__HCOUNT__' || true)

# 자기 출력 증명 — 스캐너가 죽어 표식을 못 내면 아래 `${x:-0}`이 **0 = 위반 없음**으로 떨어져
# 조용히 통과한다(짝 검사들이 같은 함정을 겪고 세운 가드다).
if [ -z "$hcount" ]; then
  echo "  ✗ 검사가 자기 출력을 내지 못했습니다 (__HCOUNT__ 없음) — 스캐너 실패입니다" >&2
  printf '%s\n' "$SCAN" | tail -5 >&2
  exit 2
fi

if [ "${hcount:-0}" -gt 0 ]; then
  echo "  ✗ 헤더 행이 없을 때 아무 말 없이 시트를 버립니다 (${hcount}건)"
  echo
  printf '%s\n' "$hbody" | while IFS=$'\t' read -r ln fn text; do
    [ -z "${ln:-}" ] && continue
    echo "    ExcelImportService.kt:$ln  ($fn)"
    echo "      $text"
  done
  echo
  echo "  헤더가 **틀린** 파일은 소리 내어 거부하면서 헤더가 **없는** 파일만 조용히 사라집니다."
  echo "  '인식되지 않아 무시되었습니다' 경고도 뜨지 않습니다 — findSheet가 이미 소비 처리했기 때문입니다."
  echo "  고치는 법:  val headerRow = headerRowOrReport(sheet, 기대헤더, result) ?: return"
  exit 1
fi

echo "  ✓ import*의 헤더 행 실패가 전부 사유와 함께 보고됩니다 (${hcount}건 위반)"
echo
echo "헤더 행 무통보 스킵 검사 통과"
