#!/bin/bash
# 정규식이 **기기와 데스크톱에서 같은 뜻인가** (R-69) — 앱 소스 전수.
#
# ── 왜 기계가 봐야 하는가 ──
# **데스크톱 JVM의 `java.util.regex`와 안드로이드의 그것은 같은 이름의 다른 엔진이다.**
# 안드로이드 쪽은 ICU(`com.android.icu.util.regex`)다. 이 저장소의 자동 검증은 전부
# 데스크톱 JVM에서 돈다(순수 JVM 시험 하네스 · 차분 컴파일 · 프로브 · CI APK 빌드) —
# 즉 **이 부류는 모든 검사가 초록인 채 기기에서만 다르게 돈다.** 실행으로는 원리적으로 못 본다.
#
# 실제로 두 번 났다:
#   ① 2026.08.21 — `\{\{([가-힣A-Za-z0-9]+)}}` 의 날 `}` 를 ICU가 문법 오류로 거절해
#      이미지탭 [AI 태그 제안]에서 앱이 튕겼다. JVM 시험 18건은 전부 초록이었다.
#   ② 같은 날 실측 — 앱 정규식 38개 중 **20개**가 `\d`·`\s`·`\w` 때문에 두 엔진에서
#      다르게 동작했다(엑셀 헤더 정규화 · 수치 파싱 · 파일명 정리 …).
#
# ── 무엇을 보는가 ──
#  ① **ICU가 거절하는 표기** — 이스케이프 안 된 `}`·`{`. 데스크톱은 글자로 읽어 주고
#     ICU는 `U_REGEX_RULE_SYNTAX`로 거절한다. 이것은 **크래시**다.
#  ② **엔진마다 뜻이 갈리는 약칭** — `\d \D \s \S \w \W \b \B` 와 POSIX `[[:x:]]`.
#     BMP 전 코드포인트를 ICU 74 / OpenJDK 21로 대조한 실측:
#         \d : JVM 10자   vs ICU 370자      \s : JVM 6자  vs ICU 25자
#         \w : JVM 63자   vs ICU 50,802자   \b : 위 \w로 정의되므로 같이 갈린다
#     이것은 **조용한 왜곡**이다 — 시험이 검증한 동작과 기기 동작이 다르다.
#
# ── 무엇을 안 보는가 (거짓 양성을 만들지 않으려고 일부러 뺀 것) ──
#  · **주석**은 통째로 뺀다. 설계 문서와 KDoc이 *옛 코드*를 인용하는 일이 흔하고
#    (이 저장소의 관행이다), 인용을 위반으로 세면 검사가 늘 빨간불이 된다.
#  · **코틀린 보간(`${...}`)** 은 자리표로 바꿔 읽는다. 보간되는 값이 안전한지는
#    **그 상수가 선언된 자리**에서 따로 본다 — `RegexCharClasses.kt`의 문자열은
#    전부 정규식 조각으로 치고 같은 규칙을 건다.
#  · `\p{...}` · `\P{...}` · `\Q...\E` · `\x{...}` 는 정규식 문법이지 날 중괄호가 아니다.
#
# ── 고치는 법 ──
# 값을 바꾸지 말고 **뜻을 표기로 적는다**(R-37이 날 제어문자에 대해 내린 것과 같은 판단).
# `util/RegexCharClasses.kt` 가 두 엔진에서 같은 것만 골라 놓은 단일 소스다:
#     \d  → `[0-9]`(파싱할 수) 또는 `ANY_DIGIT`(알아보고 지우기만)
#     \s  → `WHITESPACE`      \w → `FILENAME_KEEP` 등
#     `}` → `\}`
#
# ── 예외를 두는 법 ──
# 정말 약칭이 맞는 자리면 **그 줄이나 바로 윗줄에** 사유와 함께 표식을 남긴다:
#     // regex-parity-ok: <왜 이 자리는 갈려도 되는가>
# 표식 없이 통과시키지 않는다. 그리고 **검사를 넓혀 늘 빨간불로 만들지 않는다** —
# 그러면 다음 사람이 검사를 끈다.
#
# 사용법: tools/check_regex_engine_parity.sh   # 위반 시 exit 1
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

echo "── 정규식 엔진 일치 검사 (R-69) ──"

python3 - "$REPO" <<'PYEOF'
# -*- coding: utf-8 -*-
import io, os, re, sys

REPO = sys.argv[1]
SRC = os.path.join(REPO, 'app/src/main/java')
SINGLE_SOURCE = os.path.join('app', 'src', 'main', 'java', 'com', 'novelcharacter',
                             'app', 'util', 'RegexCharClasses.kt')

DIVERGENT = {
    r'\d': 'JVM [0-9] 10자 vs ICU 유니코드 Nd 370자',
    r'\D': r'\d 의 여집합이라 같이 갈린다',
    r'\s': 'JVM ASCII 6자 vs ICU 유니코드 공백 25자',
    r'\S': r'\s 의 여집합이라 같이 갈린다',
    r'\w': 'JVM 63자 vs ICU 유니코드 단어문자 50,802자',
    r'\W': r'\w 의 여집합이라 같이 갈린다',
    r'\b': r'\w 로 정의되므로 같이 갈린다',
    r'\B': r'\w 로 정의되므로 같이 갈린다',
}

OK_MARK = re.compile(r'//\s*regex-parity-ok\s*:')
INTERP = re.compile(r'\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')


def blank_comments(src):
    """주석을 공백으로 덮는다 — **오프셋을 보존**해 줄 번호가 어긋나지 않게."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' and src.startswith('"""', i):          # raw 문자열
            j = src.find('"""', i + 3)
            i = (j + 3) if j != -1 else n
            continue
        if c == '"':                                        # 보통 문자열
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            i = j + 1
            continue
        if c == "'":                                        # 문자 리터럴
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j == -1 else j
            for k in range(i, j):
                out[k] = ' '
            i = j
            continue
        if src.startswith('/*', i):                         # 코틀린은 중첩된다
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith('/*', j):
                    depth += 1; j += 2
                elif src.startswith('*/', j):
                    depth -= 1; j += 2
                else:
                    j += 1
            for k in range(i, min(j, n)):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
            continue
        i += 1
    return ''.join(out)


def unesc(s):
    """코틀린 비-raw 문자열의 이스케이프를 푼다 — 정규식 엔진이 실제로 보는 글자."""
    out, i = [], 0
    while i < len(s):
        if s[i] == '\\' and i + 1 < len(s):
            c = s[i + 1]
            out.append({'n': '\n', 't': '\t', 'r': '\r', '\\': '\\',
                        '"': '"', '$': '$', "'": "'"}.get(c, '\\' + c))
            i += 2
        else:
            out.append(s[i]); i += 1
    return ''.join(out)


def despecialize(rx):
    """보간을 자리표로 바꾼다. `1`을 쓰는 것은 일부러다 — `{$N}` 이 수량자로 읽혀야
    `^[0-9a-f]{$TOKEN_LENGTH}$` 같은 정상 코드가 거짓 양성이 되지 않는다."""
    return INTERP.sub('1', rx)


def bare_braces(rx):
    """이스케이프도 수량자도 정규식 문법도 아닌 **날 중괄호** — ICU가 거절한다."""
    bad, i, n = [], 0, len(rx)
    while i < n:
        c = rx[i]
        if c == '\\':
            nxt = rx[i + 1] if i + 1 < n else ''
            if nxt in 'pPx' and i + 2 < n and rx[i + 2] == '{':   # \p{L} \P{L} \x{1F600}
                close = rx.find('}', i + 2)
                i = (close + 1) if close != -1 else n
                continue
            if nxt == 'Q':                                        # \Q ... \E 는 통째로 글자
                end = rx.find(r'\E', i + 2)
                i = (end + 2) if end != -1 else n
                continue
            i += 2
            continue
        if c == '{':
            close = rx.find('}', i + 1)
            body = rx[i + 1:close] if close > i else ''
            if body and body[0].isdigit() and all(ch.isdigit() or ch == ',' for ch in body):
                i = close + 1
                continue
            bad.append(('{', i))
        elif c == '}':
            bad.append(('}', i))
        i += 1
    return bad


def shorthands(rx):
    """엔진마다 뜻이 갈리는 약칭 — 이스케이프를 존중하며 찾는다."""
    hits, i, n = [], 0, len(rx)
    while i < n:
        if rx[i] == '\\' and i + 1 < n:
            nxt = rx[i + 1]
            if nxt == 'Q':
                end = rx.find(r'\E', i + 2)
                i = (end + 2) if end != -1 else n
                continue
            tok = rx[i:i + 2]
            if tok in DIVERGENT:
                hits.append(tok)
            i += 2
            continue
        i += 1
    if re.search(r'\[:\w+:\]', rx):
        hits.append('[[:posix:]]')
    return sorted(set(hits))


TRIPLE = re.compile(r'"""(.*?)"""', re.S)
RE_TRIPLE = re.compile(r'(?:Regex|Pattern\.compile)\(\s*"""(.*?)"""', re.S)
RE_TORE_T = re.compile(r'"""(.*?)"""\s*\.toRegex\(\)', re.S)
RE_SINGLE = re.compile(r'(?:Regex|Pattern\.compile)\(\s*"((?:[^"\\\n]|\\.)*)"')
RE_TORE_S = re.compile(r'"((?:[^"\\\n]|\\.)*)"\s*\.toRegex\(\)')
ANY_STR = re.compile(r'"""(.*?)"""|"((?:[^"\\\n]|\\.)*)"', re.S)


def literals(src, rel):
    """정규식으로 쓰이는 문자열 조각 → (오프셋, 패턴). src는 주석이 덮인 것이어야 한다."""
    out = []
    if rel == SINGLE_SOURCE:
        # 단일 소스 파일에서는 **모든 문자열**이 조립돼 정규식이 된다 — 전부 본다.
        for m in ANY_STR.finditer(src):
            raw, is_raw = (m.group(1), True) if m.group(1) is not None else (m.group(2), False)
            out.append((m.start(), raw if is_raw else unesc(raw)))
        return out
    for m in RE_TRIPLE.finditer(src):
        out.append((m.start(), m.group(1)))
    for m in RE_TORE_T.finditer(src):
        out.append((m.start(), m.group(1)))
    masked = list(src)
    for m in TRIPLE.finditer(src):
        for i in range(m.start(), m.end()):
            if masked[i] != '\n':
                masked[i] = ' '
    masked = ''.join(masked)
    for m in RE_SINGLE.finditer(masked):
        out.append((m.start(), unesc(m.group(1))))
    for m in RE_TORE_S.finditer(masked):
        out.append((m.start(), unesc(m.group(1))))
    return out


def scan_text(raw_src, rel):
    """이 파일의 위반 목록."""
    src = blank_comments(raw_src)
    lines = raw_src.split('\n')
    viol = []
    for off, rx in literals(src, rel):
        rx = despecialize(rx)
        if not rx:
            continue
        ln = src[:off].count('\n') + 1
        near = '\n'.join(lines[max(0, ln - 2):ln + 1])
        if OK_MARK.search(near):
            continue
        for ch, idx in bare_braces(rx):
            viol.append((rel, ln, 'CRASH',
                         "이스케이프 안 된 '%s' (%d번째) — ICU가 거절한다" % (ch, idx), rx))
        for t in shorthands(rx):
            why = DIVERGENT.get(t, 'POSIX 이름을 ICU가 유니코드로 읽어 갈린다')
            viol.append((rel, ln, 'DIVERGE', "%s — %s" % (t, why), rx))
    return viol


# ── 탐지기 자기 시험: 되돌려 빨간불을 보는 것까지 확인한다 ──
SELFTEST = [
    # 잡아야 하는 것
    (r'val a = Regex("""\{\{(x)}}""")', 2, 'CRASH'),        # 날 `}` 가 둘
    (r'val c = Regex("\\d+")', 1, 'DIVERGE'),
    (r'val e = Regex("""\s+""")', 1, 'DIVERGE'),
    (r'val g = Regex("""\d{3,7}""")', 1, 'DIVERGE'),        # `{3,7}`은 수량자, `\d`만 위반
    (r'val j = Regex("""\bx\b""")', 1, 'DIVERGE'),
    (r'val k = Regex("""[[:digit:]]""")', 1, 'DIVERGE'),
    # 잡으면 안 되는 것
    (r'val b = Regex("""\{\{(x)\}\}""")', 0, None),
    (r'val d = Regex("[0-9]+")', 0, None),
    (r'val f = Regex("""[\t ]+""")', 0, None),
    (r'val h = Regex("""[0-9]{3,7}""")', 0, None),
    (r'val l = Regex("""\p{Nd}+""")', 0, None),             # \p{...} 는 문법이지 날 중괄호가 아니다
    (r'val m = Regex("""^[0-9a-f]{$TOKEN_LENGTH}$""")', 0, None),   # 보간된 수량자
    (r'val n = Regex("""[${Cls.WS}]+""")', 0, None),        # 보간된 문자 클래스
    (r'val o = Regex("""\Qa}b\E""")', 0, None),             # \Q..\E 안은 글자
    (r'// 종전에는 Regex("[-/\s]+") 였다' + '\n' + r'val p = Regex("""[0-9]+""")', 0, None),  # 주석 인용
    (r'// regex-parity-ok: 사유' + '\n' + r'val q = Regex("\\d+")', 0, None),
]
bad_self = []
for text, want, kind in SELFTEST:
    got = scan_text(text, 'selftest.kt')
    if len(got) != want or (want and got[0][2] != kind):
        bad_self.append((text, want, kind, got))
if bad_self:
    print("  ✗ 탐지기 자기 시험 실패 — 검사를 믿을 수 없다")
    for t, w, k, g in bad_self:
        print("      기대 %s/%s  실제 %s" % (w, k, [(x[2], x[3]) for x in g]))
        print("        입력: %r" % t)
    sys.exit(2)
print("  ✓ 탐지기 자기 시험 통과 (%d개 표본 — 날 중괄호·약칭은 잡고, "
      "명시적 표기·보간·\\p{}·\\Q\\E·주석 인용·표식은 안 잡는다)" % len(SELFTEST))

# ── 전수 훑기 ──
viol, nfile, nlit = [], 0, 0
for dp, _, fs in os.walk(SRC):
    for f in sorted(fs):
        if not f.endswith('.kt'):
            continue
        p = os.path.join(dp, f)
        rel = os.path.relpath(p, REPO)
        raw = io.open(p, encoding='utf-8').read()
        nfile += 1
        nlit += len(literals(blank_comments(raw), rel))
        viol += scan_text(raw, rel)

if viol:
    print("  ✗ 위반 %d건" % len(viol))
    for rel, ln, kind, msg, rx in viol:
        print("      [%s] %s:%d  %s" % (kind, rel, ln, msg))
        print("              %s" % rx)
    print()
    print("정규식 엔진 일치 검사 실패 — 고치는 법은 이 스크립트 머리말과 util/RegexCharClasses.kt 참조")
    sys.exit(1)

print("  ✓ 파일 %d개 · 정규식 조각 %d개에 엔진이 갈리는 표기 없음" % (nfile, nlit))
print()
print("정규식 엔진 일치 검사 통과")
PYEOF
