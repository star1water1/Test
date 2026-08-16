#!/usr/bin/env bash
# `IN (:목록)` 질의에 청크가 붙어 있는가 (R-54 · B-242)
#
# **무엇을 막는가:** DAO의 `WHERE x IN (:목록)` 메서드에 **길이 상한이 없는 목록**을 그대로
# 넘기는 것. SQLite는 한 문장의 바인드 변수 수에 상한이 있고(API 31 미만 999), 목록이 그것을
# 넘으면 질의가 통째로 죽는다. 목록을 사용자가 고르는 자리(일괄 선택·태그 전체·복원 참조)는
# 길이에 상한이 없다.
#
# **왜 규약이 아니라 검사인가:** 이 저장소는 이 부류를 **B-51에서 이미 한 번 겪었다** —
# `removeTagsFromImages`가 선택분 전량을 한 질의에 실었고 **그 예외를 자기 `catch`가 삼켜**
# *"지웠다고 생각했는데 그대로"*가 됐다(조용한 유실). 그때 `chunked(900)` 관례가 섰는데,
# **관례로만 두었더니 다시 벌어졌다**(B-242가 세어 보니 24곳). `check_view_probe_targets.sh`가
# 같은 결론에 닿은 그 모양이다 — 사람이 지키기로 한 것은 사람이 빠뜨린다.
#
# **인정하는 통로:** `util/SqlInChunks.kt`(each/flat/sum) · `.chunked(...)` ·
# `for (x in ….chunked(…))` · 길이가 코드에 박힌 리터럴 목록(`listOf(a, b)`).
#
# **콜백은 한 겹 따라간다.** `buildIndex(fetchByIds = { dao.getByIds(it) })`처럼 DAO 호출이
# 콜백 안에 있으면 **그 `it`이 청크인지는 콜백을 부르는 쪽이 정한다.** 그래서 그 함수를 찾아
# 콜백을 부르는 자리가 청크인지까지 본다 — 이 갈래를 안 보면 `TrashRepository`의 12곳이
# 통째로 거짓 양성이 되어 검사를 못 쓰게 된다.
#
# **못 보는 자리(적어 둔다 — R-49의 관행):**
#  · **인자가 실제로 짧은지는 못 본다.** 상한이 없는 모양이면 든다 — 짧은 것이 확실하면
#    리터럴로 적거나 통로를 지날 것(지나도 한 덩이면 나누지 않으므로 비용이 늘지 않는다).
#  · 콜백은 **한 겹**만 따라가고, 받는 함수는 **같은 파일에서 이름으로** 찾는다.
#    이름이 같은 오버로드가 여럿이면 **먼저 선언된 것**을 본다(`DetachedImageMarker.sync`가 그 모양이다 —
#    지금은 콜백 수신자가 아니라 무해하나, 그런 자리가 생기면 이 갈래를 먼저 볼 것).
#  · 수신자가 DAO인 호출만 본다(`…Dao().m(…)` · `…Dao.m(…)`). 저장소 래퍼는 그 안의
#    DAO 호출에서 잡히므로 두 번 세지 않는다.

set -uo pipefail
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
APP="$REPO/app/src/main/java/com/novelcharacter/app"

if [ ! -d "$APP/data/dao" ]; then
  echo "DAO 디렉터리를 찾을 수 없습니다: $APP/data/dao" >&2
  exit 2
fi

echo "── IN 절 청크 검사 (B-242) ──"

scan() {
  python3 - "$1" "$2" <<'PY'
import os, re, sys

DAO_DIR, SRC_DIR = sys.argv[1], sys.argv[2]


def balanced(src, i):
    """src[i]가 '(' 일 때 짝의 위치."""
    depth = 0
    while i < len(src):
        if src[i] == '(':
            depth += 1
        elif src[i] == ')':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return len(src) - 1


def split_args(s):
    out, d, cur, instr = [], 0, '', None
    i = 0
    while i < len(s):
        ch = s[i]
        if instr:
            if ch == '\\':
                cur += s[i:i + 2]; i += 2; continue
            if ch == instr:
                instr = None
        elif ch in '"\'':
            instr = ch
        elif ch in '(<[{':
            d += 1
        elif ch in ')>]}':
            d -= 1
        elif ch == ',' and d == 0:
            out.append(cur); cur = ''; i += 1; continue
        cur += ch
        i += 1
    out.append(cur)
    return [a.strip() for a in out]


# ── 1. DAO에서 `IN (:param)`을 쓰는 메서드를 모은다(손 목록이 아니라 기계로) ──
methods = {}
for fn in sorted(os.listdir(DAO_DIR)):
    if not fn.endswith('.kt'):
        continue
    src = open(os.path.join(DAO_DIR, fn), encoding='utf-8').read()
    for qm in re.finditer(r'@Query\s*\(', src):
        j = balanced(src, qm.end() - 1)
        in_params = set(re.findall(r'IN\s*\(\s*:(\w+)\s*\)', src[qm.end() - 1:j + 1]))
        if not in_params:
            continue
        fm = re.search(r'\bfun\s+(\w+)\s*\(', src[j:])
        if not fm:
            continue
        sig_open = j + fm.end() - 1
        sig = src[sig_open + 1:balanced(src, sig_open)]
        pnames, d, cur = [], 0, ''
        for ch in sig:
            if ch in '(<[':
                d += 1
            elif ch in ')>]':
                d -= 1
            if ch == ',' and d == 0:
                pnames.append(cur); cur = ''
            else:
                cur += ch
        pnames.append(cur)
        names = []
        for p in pnames:
            pm = re.match(r'\s*(?:vararg\s+)?(\w+)\s*:', p)
            names.append(pm.group(1) if pm else None)
        for ip in in_params:
            if ip in names:
                methods.setdefault(fm.group(1), {})[ip] = names.index(ip)

# ── 2. 청크 판정 ──
LITERAL = re.compile(r'^(listOf|listOfNotNull|setOf|setOfNotNull)\s*\(')
DAO_RECV = re.compile(r'(\w*[Dd]ao\s*\(\s*\)|\b\w*[Dd]ao)\s*$')
# 인정하는 통로. **메서드 이름까지 본다** — `SqlInChunks.`만 보면
# `foo(SqlInChunks.LIMIT) { dao.getByIds(it) }`처럼 *상수만 빌린* 사슬이 통로로 오인된다.
GATE = re.compile(r'\.chunked\s*\(|SqlInChunks\s*\.\s*(each|flat|sum)\b')


def enclosing_lambda(src, pos):
    """pos를 감싸는 가장 안쪽 `{`의 위치 (없으면 None)."""
    depth, i = 0, pos
    while i > 0:
        ch = src[i]
        if ch == '}':
            depth += 1
        elif ch == '{':
            if depth == 0:
                return i
            depth -= 1
        i -= 1
    return None


def open_paren(src, close):
    """src[close]가 ')'일 때 짝인 '('의 위치."""
    depth, i = 0, close
    while i > 0:
        if src[i] == ')':
            depth += 1
        elif src[i] == '(':
            depth -= 1
            if depth == 0:
                return i
        i -= 1
    return None


def owner_chain(src, brace):
    """
    `{` 바로 앞의 **수신자 사슬**을 그대로 읽는다 — 이 람다가 누구의 것인가.

    **길이로 자르지 않는 것이 요점이다.** 종전에는 `{` 앞 260자를 훑었는데, 그 창은
    앞선 *다른 줄*까지 삼켜서 **옆 함수의 `SqlInChunks.`를 이 람다의 것으로 읽었다**
    (자기 시험이 그 거짓 음성을 잡았다). 사슬은 식별자·`.`·균형 잡힌 `(…)`로만 이어지고
    `=`·`fun`·`{`를 만나면 끝나므로, 문장 경계를 원리적으로 넘지 않는다.
    줄바꿈은 사슬 안에서만 건너뛴다(`paths.chunked(900)\n  .flatMap { … }` 꼴).
    """
    out, i = '', brace - 1
    while i > 0:
        while i > 0 and src[i].isspace():
            i -= 1
        if i <= 0:
            break
        ch = src[i]
        if ch == ')':
            op = open_paren(src, i)
            if op is None:
                break
            out = src[op:i + 1] + out
            i = op - 1
        elif ch == '.':
            out = '.' + out
            i -= 1
        elif ch.isalnum() or ch == '_':
            j = i
            while j > 0 and (src[j - 1].isalnum() or src[j - 1] == '_'):
                j -= 1
            word = src[j:i + 1]
            if word in ('fun', 'val', 'var', 'return', 'suspend', 'private', 'override'):
                break
            out = word + out
            i = j - 1
        else:
            break
    return out


def function_body(src, name):
    """`fun name(…)`의 **본문만** 돌려준다 — 파일 끝까지가 아니라."""
    dm = re.search(r'\bfun\s+(?:<[^>]*>\s*)?' + re.escape(name) + r'\s*\(', src)
    if not dm:
        return None
    close = balanced(src, dm.end() - 1)
    brace = src.find('{', close)
    eq = src.find('=', close)
    # 식 본문(`= expr`)이면 그 줄부터 다음 선언 전까지, 블록 본문이면 그 블록.
    if brace != -1 and (eq == -1 or brace < eq):
        depth, i = 0, brace
        while i < len(src):
            if src[i] == '{':
                depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0:
                    return src[brace:i + 1]
            i += 1
        return src[brace:]
    if eq != -1:
        nxt = re.search(r'\n\s*(?:@|(?:private |internal |override |suspend )*fun )', src[eq:])
        return src[eq:eq + nxt.start()] if nxt else src[eq:]
    return None


def callback_ok(src, brace, seen):
    """
    람다가 **콜백으로 넘어간** 경우 — 그 콜백을 *부르는 쪽*이 청크인가.

    `buildIndex(fetchByIds = { dao.getByIds(it) })`에서 `it`이 청크인지는 이 자리가 아니라
    `buildIndex`가 정한다. 이 갈래를 안 보면 `TrashRepository`의 열두 곳이 통째로 거짓
    양성이 되어 검사를 못 쓴다.
    """
    nm = re.search(r'(\w+)\s*=\s*$', src[max(0, brace - 120):brace])
    if not nm:
        return False
    param = nm.group(1)
    if param in seen:            # 순환 방지
        return False
    op = open_paren(src, brace - 1) if src[brace - 1] == ')' else None
    if op is None:
        depth, i = 0, brace - 1
        while i > 0:
            if src[i] == ')':
                depth += 1
            elif src[i] == '(':
                if depth == 0:
                    break
                depth -= 1
            i -= 1
        op = i if i > 0 else None
    if op is None:
        return False
    fm = re.search(r'(\w+)\s*$', src[max(0, op - 80):op])
    if not fm:
        return False
    body = function_body(src, fm.group(1))
    if body is None:
        return False
    calls = list(re.finditer(r'\b' + re.escape(param) + r'\s*\(', body))
    if not calls:
        return False
    for cm in calls:
        inner = split_args(body[cm.end():balanced(body, cm.end() - 1)])
        if not inner or not chunk_bound(body, cm.end() - 1, inner[0], seen | {param}):
            return False
    return True


def chunk_bound(src, call_start, arg, seen=frozenset()):
    if LITERAL.match(arg) or GATE.search(arg):
        return True
    root = re.match(r'^(\w+)$', arg)
    # 바깥 람다를 세 겹까지 거슬러 오른다
    pos, levels = call_start, 0
    while levels < 3:
        brace = enclosing_lambda(src, pos)
        if brace is None:
            break
        if GATE.search(owner_chain(src, brace)):
            return True
        if callback_ok(src, brace, seen):
            return True
        pos, levels = brace - 1, levels + 1
    if root:
        head = src[max(0, call_start - 2000):call_start]
        if re.search(r'for\s*\(\s*' + re.escape(root.group(1)) + r'\s+in\s+[^)]*\.chunked\s*\(', head):
            return True
    return False


# ── 3. 호출부를 훑는다 ──
found = []
for dirpath, _, files in os.walk(SRC_DIR):
    if os.path.abspath(dirpath) == os.path.abspath(DAO_DIR):
        continue
    for fn in sorted(files):
        if not fn.endswith('.kt'):
            continue
        path = os.path.join(dirpath, fn)
        src = open(path, encoding='utf-8').read()
        for name, binds in methods.items():
            for m in re.finditer(r'\.\s*' + re.escape(name) + r'\s*\(', src):
                if not DAO_RECV.search(src[max(0, m.start() - 60):m.start()]):
                    continue
                start = m.end() - 1
                args = split_args(src[start + 1:balanced(src, start)])
                for pname, idx in binds.items():
                    named = [a for a in args if re.match(rf'^{pname}\s*=', a)]
                    if named:
                        arg = named[0].split('=', 1)[1].strip()
                    elif idx < len(args):
                        arg = args[idx]
                    else:
                        continue
                    if chunk_bound(src, start, arg):
                        continue
                    found.append((
                        os.path.relpath(path, SRC_DIR),
                        src[:start].count('\n') + 1,
                        name, pname, ' '.join(arg.split())[:70]
                    ))

for rel, ln, name, pname, arg in sorted(found):
    print(f"{rel}\t{ln}\t{name}\t{pname}\t{arg}")
print(f"__MCOUNT__{len(methods)}")
print(f"__COUNT__{len(found)}")
PY
}

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 정규식이 안 맞으면 위반이 있어도 "위반 없음"과 구별되지 않는다(B-240이 세운 관행).
SELFTEST=$(mktemp -d)
mkdir -p "$SELFTEST/dao" "$SELFTEST/src"
cat > "$SELFTEST/dao/FakeDao.kt" <<'EOF'
@Dao
interface FakeDao {
    @Query("SELECT * FROM t WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Long>

    @Query("DELETE FROM t WHERE tag IN (:tags) AND id IN (:ids)")
    suspend fun wipe(ids: List<Long>, tags: List<String>)
}
EOF
cat > "$SELFTEST/src/Caller.kt" <<'EOF'
class Caller {
    suspend fun bad(ids: List<Long>) = db.fakeDao().getByIds(ids)            // 위반 1
    suspend fun badNamed(ids: List<Long>) = db.fakeDao().wipe(ids, tags)     // 위반 2 (ids)  위반 3 (tags)
    suspend fun okGate(ids: List<Long>) = SqlInChunks.flat(ids) { db.fakeDao().getByIds(it) }
    suspend fun okChunked(ids: List<Long>) = ids.chunked(900).flatMap { db.fakeDao().getByIds(it) }
    suspend fun okForLoop(ids: List<Long>) { for (c in ids.chunked(900)) db.fakeDao().getByIds(c) }
    suspend fun okLiteral() = db.fakeDao().getByIds(listOf(1L, 2L))
    suspend fun okCallback(ids: List<Long>) = helper(ids, fetch = { db.fakeDao().getByIds(it) })
    suspend fun helper(ids: List<Long>, fetch: suspend (List<Long>) -> List<Long>) {
        SqlInChunks.each(ids) { chunk -> fetch(chunk) }
    }
    suspend fun badCallback(ids: List<Long>) = leaky(ids, fetch = { db.fakeDao().getByIds(it) })
    suspend fun leaky(ids: List<Long>, fetch: suspend (List<Long>) -> List<Long>) { fetch(ids) }
    // 상수만 빌린 사슬은 통로가 아니다 — 이름까지 보지 않으면 여기가 조용히 통과한다
    suspend fun badBorrowed(ids: List<Long>) = other(SqlInChunks.LIMIT) { db.fakeDao().getByIds(ids) }
}
EOF
selftest_out=$(scan "$SELFTEST/dao" "$SELFTEST/src")
rm -rf "$SELFTEST"
st_m=$(printf '%s\n' "$selftest_out" | sed -n 's/^__MCOUNT__//p')
st_c=$(printf '%s\n' "$selftest_out" | sed -n 's/^__COUNT__//p')
if [ "${st_m:-0}" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 DAO 메서드 2종 중 ${st_m:-0}종만 모았습니다" >&2
  exit 1
fi
if [ "${st_c:-0}" -ne 5 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 위반 5건 중 ${st_c:-0}건만 잡았습니다" >&2
  echo '      (맨목록 둘 + 이중 IN의 tags 하나 + 콜백을 안 나누고 부르는 helper 하나 + 상수만 빌린 사슬 하나.' >&2
  echo '       통로·chunked·for·리터럴·나누는 콜백 다섯은 세지 않아야 합니다)' >&2
  printf '%s\n' "$selftest_out" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (위반 5건을 잡고, 인정 통로 다섯은 세지 않는다)"

RESULT=$(scan "$APP/data/dao" "$APP")
count=$(printf '%s\n' "$RESULT" | sed -n 's/^__COUNT__//p')
mcount=$(printf '%s\n' "$RESULT" | sed -n 's/^__MCOUNT__//p')

# **자기 출력을 증명한다** — `__COUNT__`가 없다는 것은 스캐너가 답을 못 냈다는 뜻이지
# *위반이 없다*는 뜻이 아니다. 이 줄이 없으면 python이 죽어도 `${count:-0}` = 0이 되어
# **"✓ 위반 없음"으로 조용히 통과한다**(B-240이 다섯 자리에서 잡은 그 부류다).
if [ -z "$count" ] || [ -z "$mcount" ]; then
  echo "  ✗ 검사가 자기 출력을 내지 못했습니다 (__COUNT__/__MCOUNT__ 없음) — 스캐너 실패입니다" >&2
  printf '%s\n' "$RESULT" | tail -5 >&2
  exit 2
fi
# DAO를 하나도 못 모았다면 정규식이 깨진 것이지 위반이 없는 것이 아니다.
if [ "$mcount" -eq 0 ]; then
  echo "  ✗ `IN (:목록)` DAO 메서드를 하나도 모으지 못했습니다 — 스캐너 실패입니다" >&2
  exit 2
fi

if [ "$count" -gt 0 ]; then
  echo "  ✗ 상한 없는 목록을 IN 절에 그대로 넘깁니다 (${count}건 / DAO 메서드 ${mcount}종)"
  printf '%s\n' "$RESULT" | grep -v '^__[MC]' | while IFS=$'\t' read -r rel ln name pname arg; do
    echo "      $rel:$ln  $name($pname = $arg)"
  done
  echo "      목록이 999를 넘으면 질의가 죽는다 — util/SqlInChunks.kt를 지날 것 (B-242 · R-54)."
  exit 1
fi

echo "  ✓ 위반 없음 — IN 절 목록이 전부 청크를 지난다 (DAO 메서드 ${mcount}종 대조)"
