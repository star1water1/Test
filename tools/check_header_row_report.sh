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

# ── ② 데이터 루프가 헤더 다음 행에서 시작하는가 (B-231 ⓑ) ──
#
# **왜 새 축인가:** ①은 *헤더를 못 찾았을 때 말하는가*를 본다. 헤더가 0행이 아닐 수 있게 되면서
# (B-231 ⓑ) **찾은 뒤**에 새 실패 모양이 생겼다 — 헤더를 3행에서 찾아 놓고 데이터 루프가
# 1행부터 돌면 **제목·메모 행이 데이터로 읽힌다.** 이름 칸이 '내 캐릭터 목록'인 유령 캐릭터가
# 생기는 식이고, **오류도 경고도 없다.**
#
# 종전에는 마흔일곱 자리가 `for (i in 1..sheet.lastRowNum)`이었다. 시작을 헤더 행에서
# 파생시키는 통로(`dataRows(sheet, headerRow)`)를 두고, **생 리터럴을 금지한다** —
# 자리마다 정하게 두면 새 시트가 하나만 빠뜨려도 그 시트에서만 되살아난다(①과 같은 근거).
#
# **주석 줄은 일부러 뺀다** — 규칙을 설명하는 주석이 위반으로 잡히면 다음 사람이 설명을 지워
# 통과시킨다(`check_export_font.sh`가 세운 관행).
SCAN2=$(python3 - "$TARGET" <<'PY2'
import re, sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
BAD = re.compile(r'\bfor \(\s*\w+ in 1\.\.\s*\w+\.lastRowNum\s*\)')
found = []
for n, l in enumerate(lines):
    st = l.lstrip()
    if st.startswith('*') or st.startswith('//') or st.startswith('/*'):
        continue
    code = l.split('//')[0]
    if BAD.search(code):
        found.append((n + 1, l.strip()))
for ln, text in found:
    print(f"{ln}\t{text}")
print(f"__DCOUNT__{len(found)}")
PY2
)
dcount=$(printf '%s\n' "$SCAN2" | sed -n 's/^__DCOUNT__//p')
dbody=$(printf '%s\n' "$SCAN2" | grep -v '^__DCOUNT__' || true)

# ── ③ 헤더 행을 집는 자리가 통로를 지나는가 (B-231 ⓑ) ──
#
# `getRow(0)`을 직접 쓰면 **헤더가 0행이라고 다시 가정하는 것**이다. 통로는 셋이고 뜻이 다르다:
# `headerRowOrReport`(가져오기 — 못 찾으면 말한다) · `locateHeaderRow`(검증하는 미리보기) ·
# `headerRowOrFirst`(검증하지 않고 관대하게 읽는 자리). 통로 자신과 주석은 대상이 아니다.
SCAN3=$(python3 - "$TARGET" <<'PY3'
import re, sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
DECL = re.compile(r'^    (?:private )?(?:suspend )?fun (\w+)')
GATES = {'headerRowOrReport', 'locateHeaderRow', 'headerRowOrFirst'}
BAD = re.compile(r'\.getRow\(0\)')
found, fn = [], '?'
for n, l in enumerate(lines):
    m = DECL.match(l)
    if m:
        fn = m.group(1)
    st = l.lstrip()
    if st.startswith('*') or st.startswith('//') or st.startswith('/*'):
        continue
    if fn in GATES:
        continue
    code = l.split('//')[0]
    if BAD.search(code):
        found.append((n + 1, fn, l.strip()))
for ln, f, text in found:
    print(f"{ln}\t{f}\t{text}")
print(f"__GCOUNT__{len(found)}")
PY3
)
gcount=$(printf '%s\n' "$SCAN3" | sed -n 's/^__GCOUNT__//p')
gbody=$(printf '%s\n' "$SCAN3" | grep -v '^__GCOUNT__' || true)

hcount=$(printf '%s\n' "$SCAN" | sed -n 's/^__HCOUNT__//p')
hbody=$(printf '%s\n' "$SCAN" | grep -v '^__HCOUNT__' || true)

# 자기 출력 증명 — 스캐너가 죽어 표식을 못 내면 아래 `${x:-0}`이 **0 = 위반 없음**으로 떨어져
# 조용히 통과한다(짝 검사들이 같은 함정을 겪고 세운 가드다).
require_count() {  # $1=뽑은 값  $2=표식 이름  $3=스캐너 산출
  if [ -z "$1" ]; then
    echo "  ✗ 검사가 자기 출력을 내지 못했습니다 ($2 없음) — 스캐너 실패입니다" >&2
    printf '%s\n' "$3" | tail -5 >&2
    exit 2
  fi
}
require_count "$hcount" "__HCOUNT__" "$SCAN"
require_count "$dcount" "__DCOUNT__" "$SCAN2"
require_count "$gcount" "__GCOUNT__" "$SCAN3"

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

if [ "${dcount:-0}" -gt 0 ]; then
  echo "  ✗ 데이터 루프가 1행에서 시작합니다 (${dcount}건) — 헤더가 그 아래면 제목·메모가 데이터가 됩니다"
  echo
  printf '%s\n' "$dbody" | while IFS=$'\t' read -r ln text; do
    [ -z "${ln:-}" ] && continue
    echo "    ExcelImportService.kt:$ln"
    echo "      $text"
  done
  echo
  echo "  헤더 행은 0행이 아닐 수 있습니다(B-231 ⓑ). 시작을 헤더에서 파생시키세요:"
  echo "      for (i in dataRows(sheet, headerRow)) { … }"
  echo "  오류도 경고도 없이 유령 행이 생기는 부류라, 자리마다 정하게 두지 않습니다."
  exit 1
fi

if [ "${gcount:-0}" -gt 0 ]; then
  echo "  ✗ 헤더 행을 통로 없이 0행에서 집습니다 (${gcount}건)"
  echo
  printf '%s\n' "$gbody" | while IFS=$'\t' read -r ln fn text; do
    [ -z "${ln:-}" ] && continue
    echo "    ExcelImportService.kt:$ln  ($fn)"
    echo "      $text"
  done
  echo
  echo "  통로 셋 중 뜻이 맞는 것을 쓰세요(B-231 ⓑ):"
  echo "      headerRowOrReport(sheet, 기대헤더, result)  — 가져오기: 못 찾으면 사유를 말한다"
  echo "      locateHeaderRow(sheet, 기대헤더)            — 첫 열을 검증하는 미리보기"
  echo "      headerRowOrFirst(sheet, 기대헤더)           — 검증하지 않고 관대하게 읽는 자리"
  exit 1
fi

echo "  ✓ import*의 헤더 행 실패가 전부 사유와 함께 보고됩니다 (${hcount}건 위반)"
echo "  ✓ 데이터 루프가 전부 헤더 다음 행에서 시작합니다 (B-231 ⓑ)"
echo "  ✓ 헤더 행을 집는 자리가 전부 통로를 지납니다 (B-231 ⓑ)"
echo
echo "헤더 행 무통보 스킵 검사 통과"
