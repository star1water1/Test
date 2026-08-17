#!/bin/bash
# 가져오기가 필드값 한 칸의 **처분을 손으로 정하는가** (B-187 · 규약 R-33의 여섯째 짝).
#
# **무엇이 문제였는가.** 한 (소유자, 필드, 셀)의 처분 규칙 — *값 있음+기존 없음 → 신규 /
# 값 있음+다름 → 변경 / 값 있음+같음 → 동일 / 빈칸+열 있음+기존 있음 → 비움* — 은 **여섯
# 자리에 손으로** 적혀 있었고, **미리보기에는 한 자리도 없었다.** 복원 미리보기의 범주
# 스물하나 중 필드값을 세는 것이 하나도 없어서, `mergeCharacter`가 `Character` 엔티티 열만
# 견주는 동안 **필드값이 통째로 덮여도 '동일'이라 말했다.**
#
# **왜 짝 검사(`check_restore_preview_parity.sh`)가 못 보는가.** 그 검사가 보는 축은
# *`analyze*`가 손으로 짠 비교식을 쓰는가*이지 ***범주가 아예 빠졌는가***가 아니다.
# 없는 코드는 어떤 스캐너도 못 본다 — 그래서 축을 **쓰기 쪽에서** 잡는다: 값을 쓰는 자리가
# 전부 한 판정을 지나면, 미리보기는 그 판정을 부르는 것 말고는 셀 방법이 없다.
#
# **무엇을 보는가.** `ExcelImportService.kt`에서 세 값 표에 **쓰는** 호출을 전부 찾아,
# 그 호출을 감싼 함수가 [FieldValueCellPlan]을 부르는지 본다. 안 부르면 그 함수는 처분을
# 자기 손으로 정하고 있는 것이다.
#
# 일부러 지나지 않는 자리는 **그 자리에** 사유를 적어 면제한다(명단이 아니라 자리에 —
# `check_import_row_queries.sh`가 같은 결론에 닿아 있다):
#   `// 처분 무관(사유)` 를 그 호출 위 6줄 안에.

set -u
REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SRC="$REPO/app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt"
PLAN="$REPO/app/src/main/java/com/novelcharacter/app/util/FieldValueCellPlan.kt"

echo "── 필드값 처분 단일 판정 검사 (B-187 · R-33) ──"

if [ ! -f "$SRC" ] || [ ! -f "$PLAN" ]; then
  echo "  ✗ 대상 파일을 찾지 못했다: $SRC / $PLAN"
  exit 1
fi

python3 - "$SRC" <<'PY'
import re, sys

path = sys.argv[1]
lines = open(path, encoding='utf-8').read().split('\n')

# 세 값 표에 **쓰는** 호출. 읽기(getValue·getAllValuesList)는 대상이 아니다 —
# 처분을 정하는 것은 쓰는 자리이고, 읽기는 그 재료다.
WRITE = re.compile(
    r'\b(?:characterFieldValueDao|novelFieldValueDao|eventFieldValueDao)\(\)\.'
    r'(insert|insertAll|update|delete|deleteValue|deleteValuesNotInUniverse|replaceForFields)\b'
)
# 함수 경계 — 이 파일의 최상위 멤버는 4칸 들여쓰기다.
DECL = re.compile(r'^    (?:private |internal )?(?:suspend )?fun ([A-Za-z0-9_]+)\s*[(<]')
EXEMPT = re.compile(r'//.*처분 무관')
PLAN_CALL = re.compile(r'\bFieldValueCellPlan\.effectOf\b')

# 함수 범위를 모은다.
spans = []          # (name, start, end)
i = 0
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

def owner(ln):
    for name, a, b in spans:
        if a <= ln <= b:
            return name, a, b
    return None, None, None

probe = 0
bad = []
for ln, line in enumerate(lines):
    code = line.split('//')[0]
    if not WRITE.search(code):
        continue
    probe += 1
    # 자리 면제 — 호출 줄이나 그 위 6줄.
    if any(EXEMPT.search(lines[k]) for k in range(max(0, ln - 6), ln + 1)):
        continue
    name, a, b = owner(ln)
    if name is None:
        bad.append((ln + 1, '(최상위 밖)', line.strip()))
        continue
    if not any(PLAN_CALL.search(lines[k]) for k in range(a, b + 1)):
        bad.append((ln + 1, name, line.strip()))

# 탐지기 자기 시험 — 아무것도 못 찾고 '통과'하는 것이 가장 나쁜 결말이다(저장소의 fail-closed 관행).
if probe == 0:
    print("  ✗ 자기 시험 실패 — 값 표 쓰기 호출을 하나도 못 찾았다. DAO 이름이 바뀌었으면 이 스크립트를 고칠 것")
    sys.exit(1)

if bad:
    print(f"  ✗ 필드값 처분을 손으로 정하는 자리가 있습니다 ({len(bad)}건)")
    print()
    for ln, fn, text in bad:
        print(f"    ExcelImportService.kt:{ln}  ({fn})")
        print(f"      {text}")
    print()
    print("  고치는 법: 그 자리의 처분을 FieldValueCellPlan.effectOf(...)로 가르고 갈래마다 쓰세요.")
    print("             미리보기가 같은 판정을 불러 세므로, 손으로 적으면 예고와 실제가 갈립니다.")
    print("             일부러 지나지 않는 자리는 그 호출 위 6줄 안에 `// 처분 무관(사유)`.")
    sys.exit(1)

print(f"  ✓ 값 표 쓰기 {probe}자리가 전부 한 판정을 지납니다")
PY
