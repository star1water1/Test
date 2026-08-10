#!/bin/bash
# 다이얼로그 검증 실패가 입력을 유실하지 않는지 검사한다 (백로그 B-28 · B-76의 재발 방지).
#
# 배경: `setPositiveButton(text) { ... }`의 람다는 **무조건 닫힌다.** 그 안에서 검증하고
# `return@setPositiveButton`으로 빠져나오면, 토스트는 뜨지만 창은 이미 닫히는 중이라
# 사용자가 적어 둔 나머지 입력이 통째로 사라진다. S-12가 관계변화 다이얼로그 2곳에서 고쳤는데
# 같은 형태가 여섯 곳 더 남아 있었다(B-28) — 사람 리뷰로는 반복해서 놓치는 자리다.
#
# 올바른 형태: `.setPositiveButton(text, null)` + `.create()` 뒤에
#   `dialog.setValidatedPositiveButton { ... false /* 실패: 창 유지 */ ... true /* 성공 */ }`
# 그리고 실패는 `showInlineError`로 고칠 칸에 붙인다(util/AlertDialogExt.kt가 단일 소스).
#
# ## 이 검사가 보는 것은 둘이다
#
#   [A] **조기 return** — `return@setPositiveButton` (기준선 0건 동결)
#   [B] **가드-래핑** — 저장을 `if`로 **감싸** 실패 경로가 아무것도 하지 않는 형태
#       (기준선 파일로 동결. 아래가 그 사유다)
#
# ## [B]를 더한 이유 — [A]만으로는 원리적으로 못 잡는다 (B-76)
#
# [A]는 `return@setPositiveButton`이라는 **낱말 하나**를 grep한다. 그런데 같은 유실이
# 낱말 없이도 일어난다:
#
#     setPositiveButton(R.string.save) { _, _ ->
#         val name = input.text.toString().trim()
#         if (name.isNotEmpty()) viewModel.saveAsPreset(name)   // ← 비면 아무 일도 없이 닫힌다
#     }
#
# 사용자가 이름을 비운 채 '저장'을 누르면 **창은 닫히고, 적어 둔 것은 사라지고, 아무 말도
# 없다.** [A]는 통과시킨다 — 조기 return이 없기 때문이다. 실증이 세계관 편집 다이얼로그였다:
# 형제(작품 편집·U-1 등급 체계)는 R-27로 이행됐는데 호스트만 이 형태로 남아 있었다.
#
# **실패 분기가 토스트뿐인 것도 같은 부류다.** `if (blank) { Toast } else { save }`는
# 말은 하지만 창이 닫히는 것은 매한가지라 입력은 그대로 사라진다 — [A]가 막으려던 것이
# 정확히 그 모양이고(*"토스트는 뜨지만 창은 이미 닫히는 중"*), 문법만 다르다.
#
# ## 무엇을 위반으로 세는가 — **분기와 가드를 가른다**
#
# 세 조건을 다 만족하는 `setPositiveButton` 트레일링 람다가 위반이다:
#
#   1. **사용자 입력을 읽는다** (`.text.toString()`). 읽지 않으면 유실될 입력이 없다.
#   2. **문장 수준의 `if`가 있다.** `val x = if (a) b else c`(식으로 쓰인 if)는 값을 고르는
#      것이지 저장을 막는 것이 아니라 세지 않는다.
#   3. **실패 경로가 아무것도 보존하지 않는다** — `else`가 없거나, 어느 한 분기가
#      **알림 호출뿐**(Toast·Snackbar·setError·showInlineError)이거나.
#
# 3번이 **분기를 가드와 가르는 자리**다. 두 분기가 모두 실제 동작을 하면 그것은 검증이 아니라
# 선택이므로 통과한다 — `ColorPickerHelper`가 그 본보기다(잘못된 hex면 고른 색으로 되돌린다.
# 유실이 아니라 폴백이다). **알림 낱말을 짐작으로 넓히지 않는다**: 여기 넷은 실제로 관찰된
# 표현이고, 넓히면 진짜 동작을 하는 분기를 '알림뿐'으로 오인해 검사가 거짓 위반을 낸다.
#
# ## 왜 기준선이 0이 아닌가
#
# [B]를 켠 시점에 이미 여덟 자리가 있었고, 그 이행은 화면 셋에 흩어져 있어 한 슬라이스에
# 들어가지 않는다(각각 실기기 확인이 붙는다). 그래서 **기준선 파일로 동결**한다 —
# `check_text_style.sh`가 쓰는 것과 같은 방식이고, 목적도 같다: **새로 들어오는 것만 막는다.**
# 자리를 고치면 `--rebaseline`으로 기준선을 줄인다(줄이는 갱신만 정상이다. 늘리는 갱신은
# 새 위반을 동결하는 것이라 리뷰에서 걸러야 한다).
#
# 사용법: tools/check_dialog_validation.sh                # 위반 시 exit 1
#         tools/check_dialog_validation.sh --rebaseline   # 현재 [B] 위반으로 기준선 재작성
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"
SRC=app/src/main/java
BASELINE="tools/dialog_validation_baseline.txt"

echo "── 다이얼로그 검증 검사 (백로그 B-28 · B-76) ──"

# ─────────────────────────────────────────────────────────────────────
# [A] 조기 return — 기준선 0건 동결
# ─────────────────────────────────────────────────────────────────────
HITS=$(grep -rn "return@setPositiveButton" "$SRC" --include=*.kt || true)
COUNT=$(printf '%s' "$HITS" | grep -c . || true)

if [ "$COUNT" -ne 0 ]; then
  echo "  ✗ [A] 자동 닫힘 버튼 안에서 조기 return — 검증 실패 시 입력이 유실됩니다 ($COUNT건)"
  printf '%s\n' "$HITS" | sed 's/^/      /'
  echo ""
  echo "  고치는 법: setPositiveButton(text, null) + create() 뒤"
  echo "             setValidatedPositiveButton { ... } (실패 시 false 반환 → 창 유지)"
  echo "             실패 문구는 showInlineError로 해당 입력 칸에 붙일 것"
  exit 1
fi

echo "  ✓ [A] 자동 닫힘 버튼 안의 조기 return 없음"

# ─────────────────────────────────────────────────────────────────────
# [B] 가드-래핑 — 구조로 본다(낱말 하나로는 못 본다)
# ─────────────────────────────────────────────────────────────────────
# 스캐너를 파일 밖으로 빼지 않는 것은 일부러다 — `check_*.sh` 글롭이 CI가 도는 단위이고
# (워크플로 `Static checks`), 짝이 되는 `.py`를 따로 두면 그 글롭에도 스키마 하네스
# 글롭에도 안 걸려 **조용히 안 도는 파일**이 하나 생긴다. B-158이 이름 붙인 그 부류다.
scan() {
  python3 - "$1" <<'PY'
import os, re, sys

root = sys.argv[1]

def blank_noise(t):
    """주석·문자열을 같은 길이의 공백으로 지운다 — 그 안의 중괄호가 짝맞추기를 망친다.
    (길이를 보존해야 뒤에서 센 줄 번호가 원본과 맞는다.)"""
    out = list(t)
    i, n = 0, len(t)
    while i < n:
        c = t[i]
        if c == '/' and i + 1 < n and t[i + 1] == '/':
            while i < n and t[i] != '\n':
                out[i] = ' '; i += 1
        elif c == '/' and i + 1 < n and t[i + 1] == '*':
            while i < n and not (t[i] == '*' and i + 1 < n and t[i + 1] == '/'):
                if t[i] != '\n': out[i] = ' '
                i += 1
            for _ in range(min(2, n - i)):
                out[i] = ' '; i += 1
        elif t.startswith('"""', i):
            out[i] = out[i+1] = out[i+2] = ' '; i += 3
            while i < n and not t.startswith('"""', i):
                if t[i] != '\n': out[i] = ' '
                i += 1
            for _ in range(min(3, n - i)):
                out[i] = ' '; i += 1
        elif c in '"\'':
            q = c; out[i] = ' '; i += 1
            while i < n and t[i] != q:
                if t[i] == '\\':
                    out[i] = ' '; i += 1
                if i < n:
                    if t[i] != '\n': out[i] = ' '
                    i += 1
            if i < n:
                out[i] = ' '; i += 1
        else:
            i += 1
    return ''.join(out)

def match(t, i, o, c):
    """i가 여는 괄호일 때 닫는 짝의 인덱스."""
    d = 0
    while i < len(t):
        if t[i] == o: d += 1
        elif t[i] == c:
            d -= 1
            if d == 0: return i
        i += 1
    return -1

READS_INPUT = re.compile(r"\.text\s*\??\s*\.?\s*toString\s*\(\)")
# 알림뿐인 분기 — **관찰된 표현만** 든다(짐작으로 넓히면 진짜 동작을 알림으로 오인한다).
NOTICE = re.compile(r"\b(?:Toast|Snackbar)\b|\bshowInlineError\b|\bsetError\b")
CALL = re.compile(r"\b[A-Za-z_][A-Za-z0-9_.]*\s*\(")

def branch_is_notice_only(src):
    """분기가 알림 호출만 하는가 — 보존되는 것이 아무것도 없는가."""
    calls = [m for m in CALL.finditer(src)]
    real = 0
    for m in calls:
        head = src[max(0, m.start() - 30):m.end()]
        if NOTICE.search(head):
            continue
        # `if (`·`for (` 등 제어문 머리는 호출이 아니다
        if re.search(r"\b(?:if|for|while|when|catch|return)\s*\($", src[:m.end()]):
            continue
        real += 1
    return real == 0 and len(calls) > 0

def statement_guards(body):
    """문장 수준의 if 를 (조건, if본문, else본문|None) 으로 돌려준다.
    `= if (...)`·`else if` 처럼 **값을 고르는** if 는 제외한다."""
    out = []
    for m in re.finditer(r"\bif\s*\(", body):
        before = body[:m.start()].rstrip()
        if before.endswith('=') or before.endswith('else') or before.endswith('return'):
            continue          # 식으로 쓰인 if — 저장을 막는 가드가 아니다
        po = m.end() - 1
        pc = match(body, po, '(', ')')
        if pc < 0: continue
        cond = body[po + 1:pc]
        k = pc + 1
        while k < len(body) and body[k] in ' \t\r\n': k += 1
        if k < len(body) and body[k] == '{':
            bc = match(body, k, '{', '}')
            if bc < 0: continue
            then_src, after = body[k + 1:bc], bc + 1
        else:                  # 한 줄짜리 then — 줄 끝까지
            nl = body.find('\n', k)
            nl = len(body) if nl < 0 else nl
            seg = body[k:nl]
            # **같은 줄의 `else` 앞에서 끊는다.** 안 끊으면 `if (a) "" else f()` 한 줄이
            # then 에 else 까지 통째로 들어가고, 정작 else 는 *없는 것*으로 읽혀
            # **분기가 멀쩡한 자리를 '실패 경로가 없다'로 오인한다**(거짓 위반).
            em_in = re.search(r"\belse\b", seg)
            if em_in:
                out.append((cond, seg[:em_in.start()], seg[em_in.end():]))
                continue
            then_src, after = seg, nl
        rest = body[after:]
        em = re.match(r"\s*else\b", rest)
        else_src = None
        if em:
            k2 = after + em.end()
            while k2 < len(body) and body[k2] in ' \t\r\n': k2 += 1
            if k2 < len(body) and body[k2] == '{':
                ec = match(body, k2, '{', '}')
                if ec >= 0: else_src = body[k2 + 1:ec]
            else:
                nl = body.find('\n', k2)
                else_src = body[k2:len(body) if nl < 0 else nl]
        out.append((cond, then_src, else_src))
    return out

FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(")

def enclosing_fun(t, pos):
    """site 를 감싸는 **가장 바깥** 함수 이름. 지역 함수가 아니라 멤버 함수를 집으라는 뜻이다
    (`refreshRelTypeChips` 같은 중첩 지역 함수를 집으면 이름이 자리를 가리키지 못한다)."""
    best = None
    for m in FUN.finditer(t):
        if m.start() > pos:
            break
        pc = match(t, m.end() - 1, '(', ')')
        if pc < 0:
            continue
        k = pc + 1
        while k < len(t) and t[k] not in '{\n':
            k += 1                       # 반환 타입·where 절을 건너뛴다
        if k >= len(t) or t[k] != '{':
            continue
        bc = match(t, k, '{', '}')
        if bc < 0 or not (k < pos < bc):
            continue
        if best is None:                 # 먼저 만난 것이 곧 가장 바깥이다
            best = m.group(1)
    return best or "?"

hits = []
for dirpath, _, files in os.walk(root):
    for f in sorted(files):
        if not f.endswith('.kt'): continue
        p = os.path.join(dirpath, f)
        raw = open(p, encoding='utf-8', errors='replace').read()
        t = blank_noise(raw)
        for m in re.finditer(r"setPositiveButton\s*\(", t):
            po = m.end() - 1
            pc = match(t, po, '(', ')')
            if pc < 0: continue
            k = pc + 1
            while k < len(t) and t[k] in ' \t\r\n': k += 1
            if k >= len(t) or t[k] != '{':
                continue                    # setPositiveButton(text, null) — 올바른 형태 후보
            bc = match(t, k, '{', '}')
            if bc < 0: continue
            body = t[k + 1:bc]
            if not READS_INPUT.search(body):
                continue                    # 유실될 입력이 없다
            bad = None
            for cond, then_src, else_src in statement_guards(body):
                if else_src is None:
                    bad = cond; break       # 실패 경로가 통째로 없다
                if branch_is_notice_only(then_src) or branch_is_notice_only(else_src):
                    bad = cond; break       # 실패 경로가 말만 하고 닫힌다
            if bad is not None:
                line = raw.count('\n', 0, m.start()) + 1
                rel = os.path.relpath(p, root)
                cond = ' '.join(bad.split())
                # **열쇠에 줄 번호를 넣지 않는다.** 넣으면 그 자리 위에 한 줄만 더해도
                # 기준선이 통째로 어긋나 *고치지도 않은 것*이 새 위반으로 뜬다 —
                # `check_text_style.sh`가 열쇠를 `종류|파일|낱말`로 둔 것이 같은 이유다.
                # 줄 번호는 사람이 찾아가라고 **출력에만** 싣는다.
                hits.append((f"{rel}|{enclosing_fun(t, m.start())}|{cond}", f"{p}:{line}"))

for key, where in hits:
    print(f"{key}\t{where}")
PY
}

# ── 판정기 자기 시험 — 진짜를 잡고, 닮은꼴은 안 잡는가 ──
# 이 검사가 **조용히 통과할 경로를 먼저 막는다**(`check_jvm_test_targets.sh`가 세운 관행).
# [B]는 구조를 읽으므로 판정기 자신이 틀리면 그 사실이 어디에도 안 드러난다.
SELF=$(mktemp -d)
cat > "$SELF/Fixture.kt" <<'KT'
fun a() {
    b.setPositiveButton(R.string.save) { _, _ ->          // GUARD_NO_ELSE — 잡아야 한다
        val name = input.text.toString().trim()
        if (name.isNotEmpty()) viewModel.save(name)
    }
}
fun b() {
    b.setPositiveButton(R.string.save) { _, _ ->          // NOTICE_ONLY — 잡아야 한다
        val name = input.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(ctx, R.string.required, Toast.LENGTH_SHORT).show()
        } else {
            viewModel.save(name)
        }
    }
}
fun c() {
    b.setPositiveButton(R.string.ok) { _, _ ->            // 두 분기가 다 동작 — 안 잡아야 한다
        val hex = hexEdit.text.toString().trim()
        if (hex.matches(Regex("#[0-9A-Fa-f]{6}"))) {
            onColorSelected(hex)
        } else {
            onColorSelected(fallback())
        }
    }
}
fun d() {
    b.setPositiveButton(R.string.ok) { _, _ ->            // 가드 없음 — 안 잡아야 한다
        val min = minInput.text.toString().toIntOrNull() ?: 0
        viewModel.setBias(min)
    }
}
fun e() {
    b.setPositiveButton(R.string.ok) { _, _ ->            // 입력을 안 읽는다 — 안 잡아야 한다
        if (pos >= 0) list.removeAt(pos)
    }
}
fun f() {
    b.setPositiveButton(R.string.save, null)              // 올바른 형태 — 안 잡아야 한다
    d.setValidatedPositiveButton { input.text.toString().isNotEmpty() }
}
fun g() {
    b.setPositiveButton(R.string.ok) { _, _ ->            // 식으로 쓰인 if 뿐 — 안 잡아야 한다
        val name = nameEdit.text.toString().trim()
        val json = if (types == DEFAULT) "" else JSONArray(types).toString()
        viewModel.save(name, json)
    }
}
fun h() {
    b.setPositiveButton(R.string.ok) { _, _ ->            // 한 줄 if/else — 안 잡아야 한다
        val name = nameEdit.text.toString().trim()
        if (name.isEmpty()) useDefault() else viewModel.save(name)
    }
}
KT
SELF_OUT=$(scan "$SELF" || true)
s_n=$(printf '%s' "$SELF_OUT" | grep -c . || true)
rm -rf "$SELF"
if [ "$s_n" -ne 2 ]; then
  echo "  ✗ [B] 판정기 자기 시험 실패 — 픽스처에서 2건을 기대했는데 ${s_n}건" >&2
  printf '%s\n' "$SELF_OUT" | sed 's/^/      /' >&2
  echo "      (가드 둘만 잡고 폴백·무가드·비입력·올바른 형태·식-if 넷은 안 잡아야 한다)" >&2
  exit 1
fi

SCAN_OUT=$(mktemp)
scan "$SRC" | sort -u > "$SCAN_OUT"

# 열쇠(1열)만 기준선에 든다. 2열의 `파일:줄`은 사람이 찾아가는 좌표라 비교에서 뺀다.
CUR_FILE=$(mktemp); BASE_FILE=$(mktemp)
cut -f1 "$SCAN_OUT" | grep . | sort -u > "$CUR_FILE"

if [ "${1:-}" = "--rebaseline" ]; then
  cp "$CUR_FILE" "$BASELINE"
  echo "  기준선을 현재 [B] 위반으로 재작성했습니다: $BASELINE ($(grep -c . "$CUR_FILE" || true)건)"
  rm -f "$SCAN_OUT" "$CUR_FILE" "$BASE_FILE"
  exit 0
fi

if [ ! -f "$BASELINE" ]; then
  echo "  ✗ [B] 기준선이 없습니다 — tools/check_dialog_validation.sh --rebaseline 으로 먼저 만드세요" >&2
  exit 1
fi

grep . "$BASELINE" | sort -u > "$BASE_FILE" || true
NEW=$(comm -13 "$BASE_FILE" "$CUR_FILE")
FIXED=$(comm -23 "$BASE_FILE" "$CUR_FILE" | grep -c . || true)
REMAIN=$(comm -12 "$BASE_FILE" "$CUR_FILE" | grep -c . || true)

if [ -n "$NEW" ]; then
  echo "  ✗ [B] 새 가드-래핑 — 검증 실패가 창을 닫아 입력이 유실됩니다" >&2
  # 좌표를 함께 낸다 — 열쇠만으로는 찾아갈 수 없다.
  printf '%s\n' "$NEW" | while IFS= read -r k; do
    awk -F'\t' -v K="$k" '$1==K{print "      " $2 "   (" $1 ")"}' "$SCAN_OUT" >&2
  done
  rm -f "$SCAN_OUT" "$CUR_FILE" "$BASE_FILE"
  echo "" >&2
  echo "  고치는 법: setPositiveButton(text, null) + create() 뒤" >&2
  echo "             setValidatedPositiveButton { ... } (실패 시 false 반환 → 창 유지)" >&2
  echo "             실패 문구는 showInlineError로 해당 입력 칸에 붙일 것" >&2
  exit 1
fi

rm -f "$SCAN_OUT" "$CUR_FILE" "$BASE_FILE"

echo "  ✓ [B] 새 가드-래핑 없음 (기준선 ${REMAIN}건 잔존)"
if [ "$FIXED" -ne 0 ]; then
  echo "  ※ 해소분 ${FIXED}건 — --rebaseline 으로 기준선을 줄여 두세요 (재유입 방지)"
fi
echo ""
echo "다이얼로그 검증 검사 통과"
