#!/usr/bin/env bash
# 복원 미리보기 ↔ 가져오기 정합 검사 (규약 R-33 · B-101/B-102)
#
# **무엇을 막는가:** `analyze*`(복원 미리보기)가 '변경/동일'을 **손으로 짠 필드 비교식**으로
# 판정하는 것. 그렇게 적는 순간 그 목록은 짝이 되는 `import*`가 쓰는 열 집합과 갈리기 시작하고,
# 갈림의 방향은 늘 **'바뀌는데 안 바뀐다'는 거짓 안심**이었다 — 미리보기가 '동일'이라 말한 행을
# 가져오기가 덮어써서, 사용자가 되돌릴 기회를 갖지 못한다.
#
# **왜 테스트가 아니라 검사인가:** 진짜 위험은 "새 열이 import에만 추가되는 것"이고,
# 그것은 단위 테스트가 못 막는다(테스트도 함께 낡는다). 기계가 막아야 한다.
# R-32(`check_image_pointer.sh`)가 같은 이유로 만들어졌다.
#
# **고치는 법:** 그 범주의 `read*Row` + `merge*` 쌍을 만들고 양쪽이 함께 부르게 한 뒤,
# 판정은 `merge*(existing, r, ...) != existing` 한 줄로 적는다.
# 설계와 전수 대조: docs/restore_preview_parity_2026-08.md
#
# 기준선은 **0건**이다 — 새 위반이 들어오면 즉시 실패한다.

set -uo pipefail
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
TARGET="$REPO/app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt"

if [ ! -f "$TARGET" ]; then
  echo "대상 파일을 찾을 수 없습니다: $TARGET" >&2
  exit 2
fi

echo "── 복원 미리보기 정합 검사 (규약 R-33) ──"

violations=$(python3 - "$TARGET" <<'PY'
import re, sys

path = sys.argv[1]
lines = open(path, encoding='utf-8').read().split('\n')

# `analyze*` 함수의 본문 범위를 모은다. 선언 줄의 들여쓰기(4칸)와 같은 깊이의 닫는 중괄호가 끝이다.
DECL = re.compile(r'^    (?:private )?suspend fun (analyze[A-Za-z0-9_]*)\s*\(')
# 손으로 짠 필드 비교 — `existing.name != name`, `byCode.year != year` 같은 것.
# 엔티티 변수로 볼 이름만 대상으로 한다(로컬 스칼라 비교까지 잡으면 소음이 된다).
ENTITY = r'(?:existing[A-Za-z0-9_]*|byCode|byName|match|candidate)'
COMPARE = re.compile(r'\b' + ENTITY + r'\.[A-Za-z][A-Za-z0-9_]*\s*[!=]=')
# 허용: merge* 결과와 통째로 비교하는 것이 이 규약이 요구하는 모양이다.
ALLOWED = re.compile(r'\bmerged\s*!=|!=\s*existing[A-Za-z0-9_]*\b|==\s*existing[A-Za-z0-9_]*\b')

found = []
i = 0
while i < len(lines):
    m = DECL.match(lines[i])
    if not m:
        i += 1
        continue
    fn = m.group(1)
    j = i + 1
    while j < len(lines) and lines[j] != '    }':
        line = lines[j]
        code = line.split('//')[0]
        if COMPARE.search(code) and not ALLOWED.search(code):
            found.append((j + 1, fn, line.strip()))
        j += 1
    i = j + 1

for ln, fn, text in found:
    print(f"{ln}\t{fn}\t{text}")
print(f"__COUNT__{len(found)}")
PY
)

PAIRING=$(python3 - "$TARGET" <<'PY2'
import re, sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')

# `analyze*` / `import*` 본문 범위
DECL = re.compile(r'^    (?:private )?suspend fun (analyze[A-Za-z0-9_]*|import[A-Za-z0-9_]*)\s*\(')
spans, i = [], 0
while i < len(lines):
    m = DECL.match(lines[i])
    if not m:
        i += 1
        continue
    j = i + 1
    while j < len(lines) and lines[j] != '    }':
        j += 1
    spans.append((m.group(1), i, j))
    i = j + 1

# 규약이 세운 이름꼴: 한 행을 읽는 자리는 `read*Row`다.
# **양쪽이 같은 리더를 부르지 않으면** 비교식을 아무리 맞춰도 한 겹 아래에서 갈린다(설계 1-1).
readers = sorted(set(re.findall(r'\bfun (read[A-Z][A-Za-z0-9_]*Row)\s*\(', '\n'.join(lines))))
bad = []
for r in readers:
    called = {'analyze': False, 'import': False}
    for name, s, e in spans:
        side = 'analyze' if name.startswith('analyze') else 'import'
        if any(r + '(' in l for l in lines[s:e]):
            called[side] = True
    if not (called['analyze'] and called['import']):
        only = 'analyze' if called['analyze'] else ('import' if called['import'] else '아무데서도')
        bad.append(f"{r}\t{only}")

for b in bad:
    print(b)
print(f"__PCOUNT__{len(bad)}\t__TOTAL__{len(readers)}")
PY2
)

pcount=$(printf '%s\n' "$PAIRING" | sed -n 's/^__PCOUNT__\([0-9]*\).*/\1/p')
ptotal=$(printf '%s\n' "$PAIRING" | sed -n 's/.*__TOTAL__\([0-9]*\)$/\1/p')
pbody=$(printf '%s\n' "$PAIRING" | grep -v '^__PCOUNT__' || true)

# ── ③ '갱신 N'이 세는 것이 미리보기와 같은가 (B-111 · 확정 7-2) ──
# 미리보기는 `merged != existing`으로 **실제로 바뀌는 행**만 '변경'으로 센다. 가져오기의
# `result.updated*++`가 그 판정 없이 서 있으면 **매칭된 행 전부**를 세고, 그래서 같은 파일에서
# 미리보기가 "변경 3 · 동일 7"이라 하고 결과가 "갱신 10"이라 하던 것이 B-111이다.
# 이것도 시험이 못 막는다 — `ExcelImportService`는 DB에 묶여 순수 하네스가 원리적으로 못 닿고,
# 새 범주가 늘 때 게이트를 빠뜨리는 것이 정확히 ①과 같은 부류의 침묵이다.
TALLY=$(python3 - "$TARGET" <<'PY3'
import re, sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
INC = re.compile(r'result\.updated[A-Za-z0-9_]*\+\+')
# 게이트로 인정하는 모양: 같은 줄이나 위쪽 가까이에 `X != Y` 비교가 있는 것.
# `*CountedUnchanged.remove(`도 게이트다(B-217) — 이미지 연동처럼 지연 해석되는 축은 병합
# 시점에 변경 여부를 알 수 없어, 병합이 '동일'로 센 행을 집합에 두고 **순효과가 실제로
# 바뀌었을 때만** remove 성공 갈래에서 '갱신'으로 승격한다. remove가 곧 그 변경 판정이다.
GATE = re.compile(r'!=\s*(existing[A-Za-z0-9_]*|current)\b|\b(merged|kept|saved|updated|candidate|target)\s*!=|\b\w*CountedUnchanged\.remove\(')
bad = []
for i, line in enumerate(lines):
    code = line.split('//')[0]
    if not INC.search(code):
        continue
    # 같은 줄 + 바로 위 6줄까지를 창으로 본다(블록형 `if (merged != existing) { … }` 수용).
    window = '\n'.join(l.split('//')[0] for l in lines[max(0, i - 6):i + 1])
    if not GATE.search(window):
        bad.append((i + 1, line.strip()))
for ln, text in bad:
    print(f"{ln}\t{text}")
print(f"__TCOUNT__{len(bad)}")
PY3
)
tcount=$(printf '%s\n' "$TALLY" | sed -n 's/^__TCOUNT__//p')
tbody=$(printf '%s\n' "$TALLY" | grep -v '^__TCOUNT__' || true)

# ── ④ 미리보기의 시트 조회가 가져오기와 같은 판정을 지나는가 (B-217) ──
# analyze*가 `workbook.getSheet(정확명)`으로 시트를 직접 찾으면 findSheet의 판정(캐릭터 시트
# 지문 배제 · 접미사 복구 · 헤더 불일치 폴백 — SheetResolver)을 통째로 우회한다. 예약명을
# 빼앗긴 레거시 백업에서 가져오기는 밀린 시트를 되찾아 읽는데 미리보기는 "시트 없음"이라
# 말하고, 이름을 차지한 캐릭터 시트를 데이터 시트로 읽어 엉뚱한 건수를 예고하는 것이 그
# 모양이다. 실제로 열아홉 자리가 이렇게 서 있었고 이 그물의 ①~③은 전부 놓쳤다(감사 실측).
# 미리보기의 시트 조회는 sheetForAnalysis(= SheetResolver.sheetForRead)를 지나야 한다.
SHEETS=$(python3 - "$TARGET" <<'PY4'
import re, sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
DECL = re.compile(r'^    (?:private )?suspend fun (analyze[A-Za-z0-9_]*)\s*\(')
BAD = re.compile(r'\bworkbook\.getSheet\(')
found = []
i = 0
while i < len(lines):
    m = DECL.match(lines[i])
    if not m:
        i += 1
        continue
    fn = m.group(1)
    j = i + 1
    while j < len(lines) and lines[j] != '    }':
        code = lines[j].split('//')[0]
        if BAD.search(code):
            found.append((j + 1, fn, lines[j].strip()))
        j += 1
    i = j + 1
for ln, fn, text in found:
    print(f"{ln}\t{fn}\t{text}")
print(f"__SCOUNT__{len(found)}")
PY4
)
scount=$(printf '%s\n' "$SHEETS" | sed -n 's/^__SCOUNT__//p')
sbody=$(printf '%s\n' "$SHEETS" | grep -v '^__SCOUNT__' || true)

count=$(printf '%s\n' "$violations" | sed -n 's/^__COUNT__//p')
body=$(printf '%s\n' "$violations" | grep -v '^__COUNT__' || true)

if [ "${count:-0}" -gt 0 ]; then
  echo "  ✗ analyze*가 손으로 짠 필드 비교로 '변경/동일'을 판정합니다 (${count}건)"
  echo
  printf '%s\n' "$body" | while IFS=$'\t' read -r ln fn text; do
    [ -z "${ln:-}" ] && continue
    echo "    ExcelImportService.kt:$ln  ($fn)"
    echo "      $text"
  done
  echo
  echo "  고치는 법: 그 범주의 read*Row + merge* 쌍을 만들고 import*와 함께 부르게 한 뒤,"
  echo "             판정은 merge*(existing, r, ...) != existing 으로 적으세요."
  echo "             설계: docs/restore_preview_parity_2026-08.md"
  exit 1
fi

if [ "${pcount:-0}" -gt 0 ]; then
  echo "  ✗ 읽기(read*Row)를 한쪽에서만 부릅니다 (${pcount}건)"
  echo
  printf '%s\n' "$pbody" | while IFS=$'\t' read -r fn only; do
    [ -z "${fn:-}" ] && continue
    echo "    $fn — ${only}에서만 부름"
  done
  echo
  echo "  비교식만 맞추고 리더를 각자 두면 같은 결함이 한 겹 아래에서 되살아납니다."
  echo "  가져오기와 미리보기가 **같은 read*Row**를 부르게 하세요."
  echo "             설계: docs/restore_preview_parity_2026-08.md 1-1"
  exit 1
fi

if [ "${tcount:-0}" -gt 0 ]; then
  echo "  ✗ '갱신' 집계가 변경 판정 없이 올라갑니다 (${tcount}건) — 매칭된 행 전부를 셉니다"
  echo
  printf '%s\n' "$tbody" | while IFS=$'\t' read -r ln text; do
    [ -z "${ln:-}" ] && continue
    echo "    ExcelImportService.kt:$ln"
    echo "      $text"
  done
  echo
  echo "  '갱신'은 **실제로 바뀐 행**입니다(확정 7-2). 미리보기와 같은 판정을 쓰세요:"
  echo "      val merged = mergeX(existing, …)"
  echo "      db.xDao().update(merged)"
  echo "      if (merged != existing) result.updatedX++ else result.unchangedRows++"
  echo "  else 쪽을 빠뜨리면 아무것도 안 바뀐 파일에서 결과창이 '데이터 없음'이라 말합니다."
  exit 1
fi

if [ "${scount:-0}" -gt 0 ]; then
  echo "  ✗ analyze*가 workbook.getSheet(정확명)로 시트 판정을 우회합니다 (${scount}건)"
  echo
  printf '%s\n' "$sbody" | while IFS=$'\t' read -r ln fn text; do
    [ -z "${ln:-}" ] && continue
    echo "    ExcelImportService.kt:$ln  ($fn)"
    echo "      $text"
  done
  echo
  echo "  미리보기의 시트 조회는 sheetForAnalysis(= SheetResolver.sheetForRead)를 지나야"
  echo "  가져오기(findSheet)와 같은 시트를 봅니다 — 캐릭터 시트 지문 배제·접미사 복구 포함."
  exit 1
fi

echo "  ✓ 모든 analyze*가 가져오기와 같은 merge* 판정을 씁니다"
echo "  ✓ read*Row ${ptotal}종을 가져오기와 미리보기가 함께 부릅니다"
echo "  ✓ '갱신' 집계가 전부 변경 판정 뒤에 있습니다 (B-111)"
echo "  ✓ analyze*의 시트 조회가 전부 가져오기와 같은 판정(SheetResolver)을 지납니다 (B-217)"
echo
echo "복원 미리보기 정합 검사 통과"
