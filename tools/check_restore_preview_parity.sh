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

echo "  ✓ 모든 analyze*가 가져오기와 같은 merge* 판정을 씁니다"
echo
echo "복원 미리보기 정합 검사 통과"
