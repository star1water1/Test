#!/bin/bash
# 기기와 하네스가 **같은 이름의 다른 구현**을 쓰는 API를 막는다 (R-22 · R-70).
#
# ── 왜 기계가 봐야 하는가 ──
# 이 저장소의 자동 검증은 전부 **데스크톱 JVM**에서 돈다(순수 JVM 시험 하네스 · 차분 컴파일 ·
# 프로브 · CI의 APK 빌드는 컴파일만 본다). 그런데 아래 API들은 **기기에서 다른 구현이거나
# 다른 값을 낸다.** 그래서 이 부류는 **모든 검사가 초록인 채 기기에서만 틀린다** — 실행으로는
# 원리적으로 못 본다.
#
# 실측(2026.08.21, ICU 74 / OpenJDK 21 / 안드로이드 libcore 소스 대조):
#
#  ① `optString` — org.json이 구현 둘이다. JSON `null`에 대해
#     참조 구현(하네스·CI)은 `""`, **안드로이드 libcore는 `"null"`** 이라는 네 글자를 준다.
#     2인자 꼴(`optString(k, "")`)도 기본값에 닿지 못한다. 모델이 `"tags":["실내",null]`을
#     돌려주면 **기기에서만 `null`이라는 이름의 태그**가 붙었다.
#     → `util/JsonText.kt`의 `stringOr`/`stringOrNull`만 쓴다.
#
#  ② `Calendar.getInstance()` — **로케일이 역법을 고른다.** `th_TH`면 `BuddhistCalendar`가
#     돌아와 `get(YEAR)`가 2024가 아니라 **2567**이고, `ja_JP_JP`면 `JapaneseImperialCalendar`다.
#     그 수를 그레고리력으로 읽는 판정(윤년·기준연도)은 **원리적으로 성립하지 않는다.**
#     → `Calendar.getInstance(Locale.US)`. (`GregorianCalendar()`는 이름이 역법을 이미
#       못박으므로 대상이 아니다.)
#
#  ③ 로케일 없는 `%d`·`%f` 서식 — `de_DE`에서 `%.1f`가 `72,4`, `ar_EG`·`bn_BD`에서는
#     **`%d`조차 아랍-인도 숫자**가 된다. 그 문자열이 저장·되파싱·키로 쓰이면 값이 갈린다.
#     `%s`·`%x`·`%X`는 로케일 영향을 받지 않으므로(실측) 보지 않는다.
#     → `String.format(Locale.US, …)`.
#
#  ④ `SimpleDateFormat(…, Locale.getDefault())` — 역법과 숫자체계를 함께 따른다.
#     파일 이름·되파싱 대상에 쓰면 `2567-…`이나 아랍-인도 숫자가 찍히고, **파일은 로케일보다
#     오래 산다** — 로케일을 바꾸면 자기가 쓴 파일을 자기가 못 읽는다.
#     → `Locale.US`.
#
# ── 어디를 보는가 ──
# ③④는 **`ui/` 밖만** 본다. R-22가 *"사람에게 보이기만 하는 문구는 그대로 기본 로케일을
# 쓴다 — 구분해서 정할 것"*이라고 갈라 두었고, 화면 문구는 사용자의 로케일을 따르는 것이 옳다.
# ①②는 어디에 있든 틀리므로 전수로 본다.
#
# ── 예외를 두는 법 ──
# 그 줄이나 바로 윗줄에 사유와 함께 표식을 남긴다:
#     // platform-parity-ok: <왜 이 자리는 괜찮은가>
# 표식 없이 통과시키지 않는다. **검사를 넓혀 늘 빨간불로 만들지 않는다** — 그러면 다음 사람이
# 검사를 끈다.
#
# 사용법: tools/check_platform_api_parity.sh   # 위반 시 exit 1
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

echo "── 플랫폼 API 일치 검사 (R-22 · R-70) ──"

python3 - "$REPO" <<'PYEOF'
# -*- coding: utf-8 -*-
import io, os, re, sys

REPO = sys.argv[1]
SRC = os.path.join(REPO, 'app/src/main/java')
JSON_SINGLE_SOURCE = os.path.join('com', 'novelcharacter', 'app', 'util', 'JsonText.kt')
UI_PREFIX = os.path.join('com', 'novelcharacter', 'app', 'ui') + os.sep

MARK = re.compile(r'//\s*platform-parity-ok\s*:')
INTERP = re.compile(r'\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')
# 로케일에 반응하는 변환만 — %s·%x·%X·%c 는 영향을 받지 않는다(실측)
LOCALIZED_CONV = re.compile(r'%[-#+ 0,(]*\d*(?:\.\d+)?[dfeEgG]')

FMT_CALL = re.compile(r'String\.format\(\s*"((?:[^"\\\n]|\\.)*)"|"((?:[^"\\\n]|\\.)*)"\s*\.format\(')
SDF_DEFAULT = re.compile(r'SimpleDateFormat\([^)]*Locale\.getDefault')
CAL_NOLOCALE = re.compile(r'\bCalendar\.getInstance\(\s*\)')
OPTSTRING = re.compile(r'\.optString\(')


def blank_comments(src):
    """주석을 공백으로 덮는다(오프셋 보존) — 설계 문서가 옛 코드를 인용하는 관행이 있다."""
    out = list(src); i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3); i = (j + 3) if j != -1 else n; continue
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            i = j + 1; continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1; continue
        if src.startswith('//', i):
            j = src.find('\n', i); j = n if j == -1 else j
            for k in range(i, j): out[k] = ' '
            i = j; continue
        if src.startswith('/*', i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith('/*', j): depth += 1; j += 2
                elif src.startswith('*/', j): depth -= 1; j += 2
                else: j += 1
            for k in range(i, min(j, n)):
                if out[k] != '\n': out[k] = ' '
            i = j; continue
        i += 1
    return ''.join(out)


def marked(raw_lines, ln):
    return MARK.search('\n'.join(raw_lines[max(0, ln - 2):ln]))


def scan_text(raw, rel):
    src = blank_comments(raw)
    raw_lines = raw.split('\n')
    is_ui = rel.startswith(UI_PREFIX)
    is_json_src = (rel == JSON_SINGLE_SOURCE)
    viol = []

    def add(off, kind, msg):
        ln = src[:off].count('\n') + 1
        if marked(raw_lines, ln):
            return
        viol.append((rel, ln, kind, msg))

    if not is_json_src:
        for m in OPTSTRING.finditer(src):
            add(m.start(), 'JSON',
                'optString — 기기(libcore)는 JSON null에 "null"을 준다. util/JsonText.kt의 stringOr/stringOrNull을 쓴다')

    for m in CAL_NOLOCALE.finditer(src):
        add(m.start(), 'CALENDAR',
            'Calendar.getInstance() — 로케일이 역법을 고른다(th_TH면 불기 2567). Calendar.getInstance(Locale.US)')

    if not is_ui:
        for m in SDF_DEFAULT.finditer(src):
            add(m.start(), 'DATEFMT',
                'SimpleDateFormat(..., Locale.getDefault()) — 파일은 로케일보다 오래 산다. Locale.US')
        for m in FMT_CALL.finditer(src):
            lit = m.group(1) if m.group(1) is not None else m.group(2)
            if lit is None:
                continue
            probe = INTERP.sub('1', lit).replace('%%', '')
            conv = LOCALIZED_CONV.findall(probe)
            if conv:
                add(m.start(), 'NUMFMT',
                    '로케일 없는 %s 서식 — de_DE면 소수점이 콤마, ar_EG면 아랍-인도 숫자. String.format(Locale.US, ...)'
                    % ' '.join(sorted(set(conv))))
    return viol


# ── 탐지기 자기 시험 ──
SELFTEST = [
    ('val a = obj.optString("k")', 'com/novelcharacter/app/data/X.kt', 1),
    ('val b = obj.stringOr("k")', 'com/novelcharacter/app/data/X.kt', 0),
    ('val c = obj.optString("k")', JSON_SINGLE_SOURCE, 0),
    ('val d = Calendar.getInstance()', 'com/novelcharacter/app/util/X.kt', 1),
    ('val e = Calendar.getInstance(Locale.US)', 'com/novelcharacter/app/util/X.kt', 0),
    ('val f = GregorianCalendar()', 'com/novelcharacter/app/util/X.kt', 0),
    ('val g = String.format("%02d-%02d", m, d)', 'com/novelcharacter/app/util/X.kt', 1),
    ('val h = String.format(Locale.US, "%02d-%02d", m, d)', 'com/novelcharacter/app/util/X.kt', 0),
    ('val i = "%.2f".format(v)', 'com/novelcharacter/app/util/X.kt', 1),
    ('val j = "%.${n}f".format(v)', 'com/novelcharacter/app/util/X.kt', 1),
    ('val k = String.format("#%06X", c)', 'com/novelcharacter/app/util/X.kt', 0),
    ('val l = String.format("%s", c)', 'com/novelcharacter/app/util/X.kt', 0),
    ('val m2 = "%.2f".format(v)', 'com/novelcharacter/app/ui/X.kt', 0),
    ('val n2 = SimpleDateFormat("y", Locale.getDefault())', 'com/novelcharacter/app/util/X.kt', 1),
    ('val o = SimpleDateFormat("y", Locale.getDefault())', 'com/novelcharacter/app/ui/X.kt', 0),
    ('// platform-parity-ok: 사유\nval p = String.format("%d", n)', 'com/novelcharacter/app/util/X.kt', 0),
    ('// 종전에는 String.format("%d", n) 였다\nval q = String.format(Locale.US, "%d", n)', 'com/novelcharacter/app/util/X.kt', 0),
]
bad = []
for text, rel, want in SELFTEST:
    got = scan_text(text, rel.replace('/', os.sep))
    if len(got) != want:
        bad.append((text, rel, want, got))
if bad:
    print("  ✗ 탐지기 자기 시험 실패 — 검사를 믿을 수 없다")
    for t, r, w, g in bad:
        print("      기대 %d  실제 %d  [%s]  %r" % (w, len(g), r, t))
        for x in g:
            print("           -> %s %s" % (x[2], x[3][:70]))
    sys.exit(2)
print("  ✓ 탐지기 자기 시험 통과 (%d개 표본)" % len(SELFTEST))

viol, nfile = [], 0
for dp, _, fs in os.walk(SRC):
    for f in sorted(fs):
        if not f.endswith('.kt'):
            continue
        p = os.path.join(dp, f)
        rel = os.path.relpath(p, SRC)
        nfile += 1
        viol += scan_text(io.open(p, encoding='utf-8').read(), rel)

if viol:
    print("  ✗ 위반 %d건" % len(viol))
    for rel, ln, kind, msg in viol:
        print("      [%-8s] %s:%d" % (kind, rel, ln))
        print("                 %s" % msg)
    print()
    print("플랫폼 API 일치 검사 실패 — 고치는 법은 이 스크립트 머리말 참조")
    sys.exit(1)

print("  ✓ 파일 %d개에 기기/하네스가 갈리는 API 없음" % nfile)
print()
print("플랫폼 API 일치 검사 통과")
PYEOF
