#!/bin/bash
# 복원 보류 키 검사 (B-25 · R-11)
#
# **무엇을 막는가:** 복원 미리보기가 *"같은 작업이 곧 되살릴 대상인가"*를 판정할 때
# **한쪽만** 보류 키를 알고 있는 상태. 키는 두 자리에서 만나야 한다 —
# 심는 쪽(`collectOperationCodes`)과 묻는 쪽(`tally.note`의 둘째 인자)이다.
# 한쪽이 빠지면 조회가 그냥 빗나가고, **결과는 조용하다**: 예고가 "찾을 수 없음"이라
# 말하는데 실제 복원은 멀쩡히 이어진다(거짓 경고). 반대로 심지 말아야 할 자리에 심으면
# "유실 없음"이라 예고해 놓고 실제로는 잃는다(거짓 안심 — 이쪽이 더 나쁘다).
#
# ── 왜 테스트가 아니라 검사인가 ──
#
# `collectOperationCodes`는 **private이고 Room에 매달려 있다.** 순수 JVM 하네스가
# 원리적으로 못 닿는다 — 2026.08.11에 실제로 확인했다: 심는 줄을 통째로 지워도
# `run_jvm_tests` 2337건이 **전부 초록**이었다. 키의 *모양*은 `RestoreTallyTest`가 재지만
# *배선*은 아무것도 재지 못한다. 그 자리를 기계가 본다.
#
# ── 축이 셋이다 ──
#
# ① **묻는 쪽** — 모든 `tally.note(`가 보류 키를 `RestoreTally.pendingKeyOf(...)`로 만드는가.
#    날 `code`를 그대로 넘기면 **그 자리만** 코드 없는 참조의 보류를 못 본다. 참조 해석은
#    타입마다 자리가 따로라(캐릭터·사건·세력·작품·세계관) 새 자리가 계속 는다 — 이 축이
#    없으면 다음에 추가되는 자리가 조용히 옛 모양으로 들어온다.
# ② **심는 쪽** — `collectOperationCodes`가 코드 없는 행에 `pendingKeyOf(…, null)`을 심는가.
#    이것이 B-25 수정의 본체이고, 지워도 아무 시험도 빨개지지 않는 그 줄이다.
# ③ **심지 말아야 할 자리** — 등급 체계·대결 축 갈래가 옛 id를 보류 키로 쓰지 않는가.
#    `applyGradeSystem`·`applyDuelAxis`는 **언제나 `id = 0`으로 넣어** 새 id를 받는다.
#    id 폴백이 성립할 수 없는 타입에 보류를 주면 거짓 안심이 된다.
#
# 추출은 python으로 한다 — `awk -v`로 정규식을 넘기면 `\(`의 처리가 구현마다 갈려
# 로컬은 통과하고 CI가 죽는다(2026.08.11에 겪었다. check_event_crud_notify.sh 머리 주석).
#
# 사용법: tools/check_pending_key.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

TARGET="app/src/main/java/com/novelcharacter/app/data/repository/TrashRepository.kt"

if [ ! -f "$TARGET" ]; then
  echo "대상 파일을 찾을 수 없습니다: $TARGET" >&2
  exit 2
fi

echo "── 복원 보류 키 검사 (B-25) ──"

# 인자 하나: 검사할 파일. 출력은 탭 구분 레코드와 마지막 요약 줄.
scan() {
  python3 - "$1" <<'PY'
import re, sys

path = sys.argv[1]
raw = open(path, encoding='utf-8').read().split('\n')

# 줄 주석만 지운 사본으로 판정한다 — 주석에 적힌 본보기가 위반으로 잡히지 않게.
# (블록 주석은 지우지 않는다: `*`부터 지우는 규칙은 곱셈이 든 줄을 망가뜨린다.)
code = [l.split('//')[0] for l in raw]
text = '\n'.join(code)

def offset_to_line(off):
    return text.count('\n', 0, off) + 1

def arg_list(src, open_idx):
    """`(` 위치에서 시작해 괄호 균형으로 최상위 인자들을 뜬다.

    여러 줄 시그니처를 그대로 받는다 — 줄 단위로 자르는 추출기가 대상 셋 중 하나만 보면서
    초록을 냈던 것이 2026.08.11의 교훈이다."""
    depth, i, start, args = 0, open_idx, open_idx + 1, []
    while i < len(src):
        c = src[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                args.append(src[start:i])
                return args, i
        elif c == ',' and depth == 1:
            args.append(src[start:i])
            start = i + 1
        i += 1
    return None, -1

# ── ① 묻는 쪽 ──
notes = 0
for m in re.finditer(r'\btally\.note\s*\(', text):
    notes += 1
    args, _ = arg_list(text, m.end() - 1)
    ln = offset_to_line(m.start())
    if args is None or len(args) < 2:
        print(f"A\t{ln}\t인자를 뜨지 못했습니다(괄호 불균형?)")
        continue
    if 'pendingKeyOf' not in args[1]:
        print(f"A\t{ln}\t{' '.join(args[1].split())[:70]}")

# ── ②③ 심는 쪽 ──
decl = re.search(r'\bfun\s+collectOperationCodes\s*\(', text)
if not decl:
    print("X\t0\tcollectOperationCodes를 찾지 못했습니다")
else:
    # 선언 줄부터 같은 깊이의 닫는 중괄호까지가 본문이다.
    start = text.index('{', decl.end())
    depth, i = 0, start
    while i < len(text):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                break
        i += 1
    body = text[start:i]
    base = offset_to_line(start)

    planted = False
    for m in re.finditer(r'pendingKeyOf\s*\(', body):
        args, _ = arg_list(body, m.end() - 1)
        if args and len(args) >= 3 and args[2].strip() == 'null':
            planted = True
    if not planted:
        print(f"B\t{base}\t코드 없는 행에 pendingKeyOf(…, null)을 심지 않습니다")

    # id를 보존하지 않는 타입의 갈래 — 다음 `TrashSnapshot.TYPE_`/`else ->`까지가 한 갈래다.
    #
    # **범위를 `when (item.entityType)` 블록으로 먼저 좁힌다.** 함수 본문 전체에서 자르면
    # 마지막 갈래가 `when` 밖의 코드까지 삼켜, 아래에서 보류 키를 심는 줄을 그 갈래의 것으로
    # 오인한다 — 이 검사를 처음 돌렸을 때 실제로 그렇게 오검출했다.
    NO_ID_TYPES = ['TYPE_GRADE_SYSTEM', 'TYPE_DUEL_AXIS']
    wm = re.search(r'\bwhen\s*\(\s*item\.entityType\s*\)\s*\{', body)
    if not wm:
        print(f"X\t{base}\twhen (item.entityType) 블록을 찾지 못했습니다")
    else:
        wstart = body.index('{', wm.end() - 1)
        depth, j = 0, wstart
        while j < len(body):
            if body[j] == '{':
                depth += 1
            elif body[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        when_body = body[wstart:j]
        marks = [(m.start(), m.group(1)) for m in
                 re.finditer(r'TrashSnapshot\.(TYPE_[A-Z_]+)\s*->', when_body)]
        marks.append((len(when_body), 'else'))
        for idx, (pos, name) in enumerate(marks[:-1]):
            if name not in NO_ID_TYPES:
                continue
            branch = when_body[pos:marks[idx + 1][0]]
            if 'idPreserved' in branch or 'pendingKeyOf' in branch:
                ln = base + body.count(chr(10), 0, wstart + pos)
                print(f"C\t{ln}\t{name}")

print(f"__NOTES__{notes}")
PY
}

FOUND=$(scan "$TARGET")
notes=$(printf '%s\n' "$FOUND" | sed -n 's/^__NOTES__//p')
body=$(printf '%s\n' "$FOUND" | grep -v '^__NOTES__' || true)

# 추출기가 죽으면 출력이 비고 **"위반 0건"으로 초록이 뜬다** — 그 자리를 먼저 막는다.
if [ "${notes:-0}" -lt 5 ]; then
  echo "  ✗ 추출기가 tally.note 호출을 ${notes:-0}건밖에 못 찾았습니다 — 검사가 성립하지 않습니다" >&2
  exit 2
fi

fail=0
a=$(printf '%s\n' "$body" | grep -c '^A' || true)
b=$(printf '%s\n' "$body" | grep -c '^B' || true)
c=$(printf '%s\n' "$body" | grep -c '^C' || true)
x=$(printf '%s\n' "$body" | grep -c '^X' || true)

if [ "$x" -gt 0 ]; then
  echo "  ✗ 대상 함수를 찾지 못했습니다 — 이름이 바뀌었다면 이 검사도 함께 옮기세요"
  exit 2
fi

if [ "$a" -gt 0 ]; then
  fail=1
  echo "  ✗ 보류 키를 pendingKeyOf로 만들지 않는 tally.note가 있습니다 (${a}건)"
  printf '%s\n' "$body" | grep '^A' | while IFS=$'\t' read -r _ ln txt; do
    echo "    TrashRepository.kt:$ln"
    echo "      둘째 인자: $txt"
  done
  echo
  echo "  코드가 없는 참조(v35 이전 사건 등)는 코드가 아니라 (타입, 옛 id)가 보류 키입니다."
  echo "      RestoreTally.pendingKeyOf(TrashSnapshot.TYPE_EVENT, oldId, code)"
fi

if [ "$b" -gt 0 ]; then
  fail=1
  echo "  ✗ collectOperationCodes가 코드 없는 행에 보류 키를 심지 않습니다"
  echo
  echo "  심지 않으면 묻는 쪽이 아무리 옳아도 조회가 빗나갑니다 — 그리고 그 실패는"
  echo "  **어떤 순수 시험도 잡지 못합니다**(private + Room)."
fi

if [ "$c" -gt 0 ]; then
  fail=1
  echo "  ✗ id를 보존하지 않는 타입에 보류 키를 심었습니다 (${c}건)"
  printf '%s\n' "$body" | grep '^C' | while IFS=$'\t' read -r _ ln name; do
    echo "    TrashRepository.kt:$ln  ($name)"
  done
  echo
  echo "  applyGradeSystem·applyDuelAxis는 언제나 id = 0으로 넣습니다 — 옛 id로 다시 찾을 수"
  echo "  없으므로, 보류를 주면 '유실 없음'이라 예고하고 실제로는 잃습니다."
fi

[ "$fail" -eq 0 ] || exit 1

# ── 자기 시험 — 진짜를 잡고, 멀쩡한 것은 안 잡는가 ──
# 본보기는 **실제 코드의 모양을 닮아야 한다**(여러 줄 인자 · 중첩 괄호). 닮지 않은 본보기로만
# 시험하면 추출기가 실제 코드에서 죽어도 자기 시험은 초록이다.
SELFDIR=$(mktemp -d)
cat > "$SELFDIR/Self.kt" <<'EOF'
class Sample {
    fun collectOperationCodes(items: List<TrashSnapshot>): OperationCodes {
        for (item in items) {
            var idPreserved: Long? = null
            val code = when (item.entityType) {
                TrashSnapshot.TYPE_EVENT ->
                    parse(item, EventSnapshot::class.java)?.event
                        ?.also { idPreserved = it.id }?.code
                TrashSnapshot.TYPE_GRADE_SYSTEM ->
                    parse(item, GradeSystemSnapshot::class.java)?.gradeSystem?.code
                else -> null
            }
            val legacyId = idPreserved
            when {
                code != null -> pending.add(code)
                legacyId != null ->
                    pending.add(RestoreTally.pendingKeyOf(item.entityType, legacyId, null))
            }
        }
    }

    fun good(oldId: Long) = tally.note(
        SnapshotRefResolver.resolveByCode(
            oldId, refs?.events?.get(oldId.toString()),
            eventIndex.codeById, eventIndex.idByCode, eventIndex.liveIds
        ),
        RestoreTally.pendingKeyOf(
            TrashSnapshot.TYPE_EVENT, oldId, refs?.events?.get(oldId.toString())
        )
    )

    fun bad(oldId: Long) = tally.note(
        SnapshotRefResolver.resolveByCode(
            oldId, refs?.characters?.get(oldId.toString()),
            charIndex.codeById, charIndex.idByCode, charIndex.liveIds
        ),
        refs?.characters?.get(oldId.toString())
    )

    // tally.note(res, code) — 주석 속 본보기는 잡지 않는다
    fun more(oldId: Long) = tally.note(resolve(oldId), RestoreTally.pendingKeyOf(T, oldId, null))
    fun more2(oldId: Long) = tally.note(resolve(oldId), RestoreTally.pendingKeyOf(T, oldId, null))
    fun more3(oldId: Long) = tally.note(resolve(oldId), RestoreTally.pendingKeyOf(T, oldId, null))
}
EOF
SELF=$(scan "$SELFDIR/Self.kt")
self_a=$(printf '%s\n' "$SELF" | grep -c '^A' || true)
self_bc=$(printf '%s\n' "$SELF" | grep -cE '^[BCX]' || true)
self_notes=$(printf '%s\n' "$SELF" | sed -n 's/^__NOTES__//p')

# ③의 반대 방향도 함께 시험한다 — 멀쩡한 갈래에 idPreserved를 얹으면 잡아야 한다.
sed 's|parse(item, GradeSystemSnapshot::class.java)?.gradeSystem?.code|parse(item, GradeSystemSnapshot::class.java)?.gradeSystem?.also { idPreserved = it.id }?.code|' \
  "$SELFDIR/Self.kt" > "$SELFDIR/SelfC.kt"
self_c=$(scan "$SELFDIR/SelfC.kt" | grep -c '^C' || true)
rm -rf "$SELFDIR"

if [ "${self_notes:-0}" -ne 5 ] || [ "$self_a" -ne 1 ] || [ "$self_bc" -ne 0 ] || [ "$self_c" -ne 1 ]; then
  echo "  ✗ 추출기 자기 시험 실패 — note ${self_notes:-0}건(5) · 위반 ${self_a}건(1) ·" >&2
  echo "    오검출 ${self_bc}건(0) · 보존 없는 타입 검출 ${self_c}건(1)" >&2
  exit 2
fi

echo "  ✓ tally.note ${notes}자리가 전부 pendingKeyOf로 보류 키를 만듭니다"
echo "  ✓ collectOperationCodes가 코드 없는 행에 (타입, 옛 id) 키를 심습니다"
echo "  ✓ id를 보존하지 않는 타입(등급 체계·대결 축)에는 심지 않습니다"
echo
echo "복원 보류 키 검사 통과"
