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
# ① `app/src/main/java`에서 `DuelRecords.resolve(`를 부르는 **모든 자리**가 인자에 `matching`을
#    이름으로 적는가. 값이 무엇인지는 보지 않는다 — 축을 아는 것은 부르는 쪽이고, 이 검사가
#    할 일은 **그 판단을 건너뛰지 못하게 하는 것**이다(기본값에 기대면 그 판단이 없다).
# ② **캐릭터를 넘는 판의 판정이 `DuelImageRoster` 밖에 없는가** (B-208).
#    ①이 *적합과 나누기가 같은 잣대를 쓰는가*를 보는 것과 축이 같다 — 이쪽은 **세는 쪽과
#    고르는 쪽**이다. 순위표는 그 판을 *수*로 말하고 기록 화면은 *줄*로 보이는데, 한쪽이
#    직접 `Claim.Cross`를 대조하면 **N개라더니 M개가 보인다**(확정 15-8 착수 조건).
#    이 갈래도 ①과 같은 이유로 시험이 아니라 검사다: 손으로 짜도 컴파일되고 시험도 돈다.
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

# ── ② 캐릭터를 넘는 판의 판정은 한 자리뿐이다 (B-208) ──
#
# `Claim`은 `DuelImageRoster`가 든 몫 가르기의 잣대다. 그 밖에서 `Claim.Cross`를 직접
# 대조하면 **세는 쪽과 고르는 쪽이 갈릴 길**이 생긴다 — 화면이 자기 자리에서 조건을 하나
# 빠뜨려도(범위·지워진 그림) 아무 오류가 없고, 갈린 수만 사용자가 본다.
ROSTER=$SRC/util/DuelImageRoster.kt
[ -f "$ROSTER" ] || { echo "  ✗ 선언이 없습니다: $ROSTER"; exit 1; }

# 주석은 뺀다 — 이 저장소는 *왜 그렇게 갈랐는가*를 KDoc에 적어 두는 관행이 있다.
stray=$(grep -rn 'Claim\.Cross' "$SRC" --include=*.kt \
  | grep -v "^$ROSTER:" \
  | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)' || true)
if [ -n "$stray" ]; then
  echo "  ✗ 캐릭터를 넘는 판을 DuelImageRoster 밖에서 직접 판정합니다:"
  printf '%s\n' "$stray" | sed 's/^/      /'
  echo "  고치는 법: DuelImageRoster.crossCharacterMatchesOf(matches, owners, characterId) 를 쓸 것."
  echo "             범위(characterId)를 함께 넘겨야 순위표가 센 수와 같아집니다."
  echo ""
  echo "대결 코드 대조 방식 검사 실패"
  exit 1
fi

# 세는 쪽과 고르는 쪽이 **둘 다 살아 있는가.** 한쪽이 사라지면 이 규칙은 지킬 것이 없어지고,
# 그때 검사는 *위반 없음*으로 조용히 통과한다(이 저장소가 반복해 겪은 그 모양).
for fn in crossCharacterMatchesOf 'crossCharacterMatches ='; do
  grep -q "$fn" "$ROSTER" || {
    echo "  ✗ 등재된 자리가 없습니다: $ROSTER ($fn)"
    echo "      → 이름이 바뀌었으면 이 검사를 함께 고칠 것. 못 찾은 채 통과하면"
    echo "        *위반 없음*과 구별되지 않는다."
    echo ""
    echo "대결 코드 대조 방식 검사 실패"
    exit 1
  }
done
echo "  ✓ 캐릭터를 넘는 판은 DuelImageRoster 하나가 판정한다 (B-208)"

# ── ③ 거르개 요청의 인자 이름이 한 자리에만 산다 (B-208 · R-61) ──
#
# 화면에서 화면으로 넘기는 `Bundle` 키는 **타입이 없는 문자열**이고 짝이 다른 파일에 있다.
# 이름이 갈리면 예외가 아니라 **침묵**이다 — 목록은 그냥 뜨고, 누른 사람은 자기가 부탁한
# 거르개가 없던 일이 된 줄도 모른다. `nav_graph.xml`은 상수를 쓸 수 없어 예외이고,
# **대신 그 선언이 있는지를 본다**(그래프에 없으면 기본값이 없어 갈래가 코드에서 달라진다).
MATCHES=$SRC/ui/duel/DuelMatchesFragment.kt
GRAPH=app/src/main/res/navigation/nav_graph.xml
for f in "$MATCHES" "$GRAPH"; do
  [ -f "$f" ] || { echo "  ✗ 등재된 파일이 없습니다: $f"; echo ""; echo "대결 코드 대조 방식 검사 실패"; exit 1; }
done
for key in onlyCross focusCharacterId; do
  # 선언 자신(상수 정의)과 자원은 뺀다. 주석도 뺀다 — *왜 이 이름인가*를 적어 두는 관행이 있다.
  raw=$(grep -rn "\"$key\"" "$SRC" --include=*.kt \
    | grep -vE "^$MATCHES:[0-9]+:[[:space:]]*const val" \
    | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)' || true)
  if [ -n "$raw" ]; then
    echo "  ✗ 거르개 인자 이름을 생문자열로 적은 자리가 있습니다 ($key):"
    printf '%s\n' "$raw" | sed 's/^/      /'
    echo "  고치는 법: DuelMatchesFragment.ARG_ONLY_CROSS / ARG_FOCUS_CHARACTER_ID 를 쓸 것."
    echo ""
    echo "대결 코드 대조 방식 검사 실패"
    exit 1
  fi
  if ! grep -q "android:name=\"$key\"" "$GRAPH"; then
    echo "  ✗ nav_graph.xml에 인자 선언이 없습니다 ($key)"
    echo "      → 선언이 없으면 기본값이 없어 *인자 없이 들어온 화면*과 *값을 싣고 들어온"
    echo "        화면*이 코드에서 다르게 갈린다(R-61)."
    echo ""
    echo "대결 코드 대조 방식 검사 실패"
    exit 1
  fi
done
# **읽은 자리가 지우는가** (R-61) — 남겨 두면 화면이 다시 설 때마다 같은 요청이 다시 걸려
# 사용자가 끈 거르개를 재생성이 도로 켠다.
for key in ARG_ONLY_CROSS ARG_FOCUS_CHARACTER_ID; do
  grep -q "arguments?.remove($key)" "$MATCHES" || {
    echo "  ✗ 읽은 요청을 인자에서 지우지 않습니다: $MATCHES ($key)"
    echo "      → arguments?.remove($key). 남기면 회전마다 같은 요청이 다시 걸린다(R-61)."
    echo ""
    echo "대결 코드 대조 방식 검사 실패"
    exit 1
  }
done
echo "  ✓ 거르개 요청의 이름이 한 자리에 살고, 읽은 자리가 지운다 (B-208 · R-61)"

echo ""
echo "대결 코드 대조 방식 검사 통과"
