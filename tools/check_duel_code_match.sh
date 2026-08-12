#!/bin/bash
# 대결 참가자 코드의 **대조 방식**을 부르는 쪽이 밝히는가 (B-175 · R-51).
#
# 배경: 대결의 참가자 코드는 축마다 성격이 다르다 — 캐릭터 축은 `Character.code`(개명되지
# 않는다)이고 이미지 축은 **경로**다. 경로는 같은 파일이 여러 표기를 가질 수 있어 글자로
# 견주면 멀쩡한 판이 고아가 되고, 그 자리에서 **나누기(`DuelImageRoster`)와 적합(`DuelRecords`)이
# 서로 다른 판 수를 갖는다.** 2026.08.12까지 실제로 그랬고, 순위표는 남의 캐릭터 판을
# *"지워진 참가자의 판"*이라 고지했다.
#
# ## 왜 시험이 아니라 검사인가
# 빠뜨려도 **오류가 나지 않는다.** `matching`은 기본값이 있어(EXACT — 뜻을 바꾸지 않는 쪽)
# 컴파일도 되고 시험도 돈다. 틀린 것은 **화면에 뜨는 수**뿐이고, 그것은 이미지 축에
# 손편집된 자료가 들어왔을 때만 갈린다. 새 소비처가 생길 때 그 자리를 사람이 기억해야
# 하는 구조라면 언젠가 반드시 빠진다(R-45·R-46이 배운 그 모양).
#
# ## 무엇을 보는가
# `app/src/main/java`에서 `DuelRecords.resolve(`를 부르는 **모든 자리**가 인자에 `matching`을
# 이름으로 적는가. 값이 무엇인지는 보지 않는다 — 축을 아는 것은 부르는 쪽이고, 이 검사가
# 할 일은 **그 판단을 건너뛰지 못하게 하는 것**이다(기본값에 기대면 그 판단이 없다).
#
# 사용법: tools/check_duel_code_match.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC=app/src/main/java/com/novelcharacter/app
DECL=$SRC/util/DuelRecords.kt

echo "── 대결 코드 대조 방식 검사 (B-175 · R-51) ──"

[ -f "$DECL" ] || { echo "  ✗ 선언이 없습니다: $DECL"; exit 1; }

python3 - "$SRC" "$DECL" <<'PY'
import os, re, sys

src, decl = sys.argv[1], sys.argv[2]
CALL = re.compile(r'DuelRecords\.resolve\s*\(')

def calls(text):
    """호출 하나의 인자 목록을 괄호 균형으로 잘라 낸다 — 여러 줄에 끊어 적은 호출도 본다."""
    out = []
    for m in CALL.finditer(text):
        i = m.end()
        depth = 1
        while i < len(text) and depth:
            if text[i] == '(':
                depth += 1
            elif text[i] == ')':
                depth -= 1
            i += 1
        out.append((text[:m.start()].count('\n') + 1, text[m.end():i - 1]))
    return out

def scan(path):
    text = open(path, encoding='utf-8', errors='ignore').read()
    # 주석 줄은 지운다 — KDoc의 예시 코드가 위반으로 잡히면 안 된다.
    text = '\n'.join('' if l.lstrip().startswith(('*', '//', '/*')) else l
                     for l in text.split('\n'))
    return [(line, args) for line, args in calls(text) if 'matching' not in args]

violations = []
for root, _, files in os.walk(src):
    for name in files:
        if not name.endswith('.kt'):
            continue
        path = os.path.join(root, name)
        if os.path.abspath(path) == os.path.abspath(decl):
            continue                      # 선언 자신 — 기본값을 여기서 정한다
        for line, _ in scan(path):
            violations.append('%s:%d' % (path, line))

# ── 탐지기 자기 시험 — 안 맞는 자르기는 "위반 없음"과 구별되지 않는다 ──
GOOD = 'val r = DuelRecords.resolve(\n  codes,\n  matches,\n  verdicts,\n  matching = m\n)\n'
BAD = 'val r = DuelRecords.resolve(codes, matches(a, b), verdicts)\n'
if [a for _, a in calls(GOOD) if 'matching' not in a]:
    print('  ✗ 탐지기 자기 시험 실패 — 여러 줄 호출을 위반으로 읽습니다')
    sys.exit(1)
if not [a for _, a in calls(BAD) if 'matching' not in a]:
    print('  ✗ 탐지기 자기 시험 실패 — 중첩 괄호가 든 호출을 못 봅니다')
    sys.exit(1)

if violations:
    print('  ✗ 대조 방식을 밝히지 않고 DuelRecords.resolve 를 부르는 자리 %d건' % len(violations))
    print()
    for v in violations:
        print('      %s' % v)
    print()
    print('  고치는 법: matching = DuelRecords.CodeMatch.EXACT / IMAGE_PATH 를 이름으로 적을 것.')
    print('             축을 들고 있으면 DuelRepository.matchingOf(axis) 가 그 판정의 단일 소스입니다.')
    sys.exit(1)

total = 0
for root, _, files in os.walk(src):
    for name in files:
        if name.endswith('.kt') and os.path.abspath(os.path.join(root, name)) != os.path.abspath(decl):
            total += len(calls(open(os.path.join(root, name), encoding='utf-8', errors='ignore').read()))
print('  ✓ 부르는 자리 %d곳이 전부 대조 방식을 밝힙니다' % total)
PY
rc=$?

if [ "$rc" -ne 0 ]; then
  echo ""
  echo "대결 코드 대조 방식 검사 실패"
  exit 1
fi

echo ""
echo "대결 코드 대조 방식 검사 통과"
