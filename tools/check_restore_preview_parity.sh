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

# 규약이 세운 이름꼴 **둘**: 한 행을 읽는 자리는 `read*Row`, 한 행이 **만들** 항목을 짓는
# 자리는 `new*From`이다(B-233 — R-33의 셋째 짝).
#
# **왜 같은 그물인가:** 갈리는 방식이 같다. 읽기가 한쪽만 있으면 *같은 행을 다르게 읽고*,
# 짓기가 한쪽만 있으면 *같은 행이 만들 것을 다르게 짓는다* — 후자의 결과가 B-233이었다.
# 미리보기는 매칭이 빈손일 때 가져오기가 넣을 그 항목을 지어 색인에 등재해야 같은 파일의
# 뒷 행이 첫 행의 쓰기를 본다. 미리보기가 **제 손으로** 지으면 그것이 바로 R-33이 없애려던
# *손으로 짠 두 벌*이다.
readers = sorted(set(re.findall(r'\bfun (read[A-Z][A-Za-z0-9_]*Row)\s*\(', '\n'.join(lines))))
readers += sorted(set(re.findall(r'\bfun (new[A-Z][A-Za-z0-9_]*From)\s*\(', '\n'.join(lines))))
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

# ── ⑤ 미리보기의 캐릭터 해석이 가져오기와 같은 사다리인가 (B-232) ──
# `analyze*`가 first-match 조회(이름으로 아무나 한 명)를 쓰면, 동명이인 행을 미리보기는
# '실행된다'고 예고하고 가져오기는 거부한다 — 열·읽기·적용·시트를 다 맞춰도 **행이 누구에게
# 붙는가**가 갈리면 예고가 거짓이 된다. ①~④는 이 부류를 원리적으로 못 본다(비교식도, 리더도,
# 시트도 같기 때문이다). 실제로 다섯 자리가 그렇게 서 있었다(상태변화 1 · 관계 2 · 관계변화 2).
#
# 판정: 짝(analyze ↔ import)을 등재하고, **미리보기가 부르는 해석 함수 집합이 가져오기의
# 것에 포함되는가**를 본다. 새 시트가 등재 없이 캐릭터를 해석하면 그것도 위반이다
# (짝을 적으라는 뜻 — 조용히 빠지는 것이 이 부류의 실패 모양이다).
#
# **못 보는 자리를 적어 둔다**(R-49가 같은 자리에서 세운 관행 — 검사의 사각은 검사 옆에 적는다):
#  · **간접 호출** — `analyze*`가 새 사설 헬퍼를 거쳐 해석하면 이 그물은 그 헬퍼를 못 본다.
#    (그때 지키는 것은 짝 등재의 **뜻**이고, 그 뜻을 아는 것은 사람이다.)
#  · **`suspend`가 아닌 `analyze*`** — 함수 범위를 `suspend fun`으로 잡으므로 목록에서 빠진다.
#    이 파일의 미리보기는 전부 suspend라 지금은 사각이 비어 있다(①~④도 같은 전제 위에 있다).
LADDER=$(python3 - "$TARGET" <<'PY5'
import re, sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')

# 캐릭터를 이름/코드로 되찾는 함수들. `characterByCode`는 뺀다 — 코드 조회는 모호가 없다.
HELPERS = ('findCharacterStrict', 'resolveCharByNameNovel', 'findCharacterByName',
           'characterByNameAndNovel', 'characterByName', 'charactersByName')
CALL = re.compile(r'\b(' + '|'.join(HELPERS) + r')\(')
DECL = re.compile(r'^    (?:private )?suspend fun (analyze[A-Za-z0-9_]*|import[A-Za-z0-9_]*)\s*\(')

# 짝 등재: analyze → (import, 허용 차집합과 그 사유)
PAIRS = {
    'analyzeStateChanges':       ('importStateChanges', set()),
    'analyzeRelationships':      ('importRelationships', set()),
    'analyzeRelationshipChanges':('importRelationshipChanges', set()),
    'analyzeFactionMemberships': ('importFactionMemberships', set()),
    # 캐릭터 시트만 미리보기가 더 부른다: 동명이인을 CharacterConflict로 **모으는** 것이
    # 미리보기의 일이고(사용자가 고른다), 가져오기는 그 결정을 받아 쓴다.
    'analyzeCharacterSheet':     ('importCharacterRows', {'charactersByName'}),
    # 필드값 오버플로 시트 (B-187) — 짝과 **같은 사다리**다(코드 우선 → 이름이 유일할 때만).
    'analyzeCharacterFieldValueSheet': ('importCharacterFieldValues', set()),
}

spans, i = {}, 0
while i < len(lines):
    m = DECL.match(lines[i])
    if not m:
        i += 1
        continue
    j = i + 1
    while j < len(lines) and lines[j] != '    }':
        j += 1
    calls = set()
    for l in lines[i:j]:
        calls.update(CALL.findall(l.split('//')[0]))
    spans[m.group(1)] = calls
    i = j + 1

bad = []
# 전역 first-match 헬퍼는 되살아나면 안 된다 — 이름 하나로 아무나 고르는 자리가 B-232였다.
if any(re.search(r'\bfun findCharacterByName\s*\(', l) for l in lines):
    bad.append("findCharacterByName\t이름 first-match 헬퍼가 되살아났습니다 (B-232에서 없앤 자리)")
for fn, calls in sorted(spans.items()):
    if not fn.startswith('analyze') or not calls:
        continue
    pair = PAIRS.get(fn)
    if pair is None:
        bad.append(f"{fn}\t캐릭터를 해석하는데 짝(import*)이 등재되지 않았습니다: {', '.join(sorted(calls))}")
        continue
    imp, allowed = pair
    # 등재된 짝이 파일에 없으면(개명·삭제) **검사가 조용히 약해진다** — 그 자리의 analyze*는
    # 빈 집합과 비교돼 무엇을 부르든 위반으로 뜨거나, 반대로 이름만 맞는 남의 함수와 비교된다.
    # 그래서 등재 자체의 유효성을 먼저 본다.
    if imp not in spans:
        bad.append(f"{fn}\t짝으로 등재된 '{imp}'을(를) 파일에서 찾지 못했습니다 — 개명됐다면 PAIRS를 함께 고치세요")
        continue
    extra = calls - spans.get(imp, set()) - allowed
    if extra:
        bad.append(f"{fn}\t{imp}는 부르지 않는 해석 함수를 씁니다: {', '.join(sorted(extra))}")

for b in bad:
    print(b)
print(f"__LCOUNT__{len(bad)}")
PY5
)
lcount=$(printf '%s\n' "$LADDER" | sed -n 's/^__LCOUNT__//p')
lbody=$(printf '%s\n' "$LADDER" | grep -v '^__LCOUNT__' || true)

# ── ⑥ 가져오기가 **세며 거부하는** 행을 미리보기도 세는가 (B-237) ──
# ①~⑤가 전부 **센 행끼리** 답이 같은가를 묻는 데 반해, 이것은 **세기 전**을 묻는다 —
# 그래서 위 다섯이 원리적으로 못 보는 축이다. `analyze*`가 필수 칸이 빈 행을 `inBackup++`
# **앞에서** `continue`로 버리면, 짝 가져오기가 같은 행을 `skippedRows`로 세고 소리 내어
# 거부하는 동안 미리보기의 '백업에 N건'만 파일의 실제 행보다 작아진다. 건너뜀도, 백업에
# 있음도 아닌 **무존재**이고, 그 차이가 어디로 갔는지 화면 어디에도 없다.
#
# 판정: 미리보기 행 루프에서 **`inBackup++` 없이 `continue`하는 가드**의 식별자와,
# 짝 가져오기에서 **`skippedRows++`와 함께 거부하는 가드**의 식별자가 겹치면 위반이다.
# 겹치지 않으면 그 가드는 양쪽 모두 조용히 지나가는 빈 행이라 정상이다.
#
# **엘비스-continue(`val year = r.year ?: continue`)를 따로 보는 것이 이 그물의 핵심이다** —
# `if` 꼴만 보면 B-237의 여섯 자리 중 **셋**(연표·상태변화·관계변화의 연도)이 통째로 빠진다.
# 실측으로 확인했다: 그 갈래 없이 수리 전 코드에 돌리면 3건, 넣으면 5함수 6자리 전부다.
#
# **못 보는 자리를 적어 둔다**(R-49 관행):
#  · 짝 가져오기가 `when` 갈래(`is CharLookupResult.Ambiguous ->`)로 거부하는 것 — `if (…) {`
#    꼴이 아니라 이 그물 밖이다. 그 부류는 ⑤가 사다리 축으로 이미 본다.
#  · 미리보기가 사설 헬퍼 안에서 버리는 것 — ⑤와 같은 사각이다.
NOTIFIED=$(python3 - "$TARGET" <<'PY6'
import re, sys
lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
DECL = re.compile(r'^    (?:private )?suspend fun (analyze[A-Za-z0-9_]*|import[A-Za-z0-9_]*)\s*\(')
spans, i = {}, 0
while i < len(lines):
    m = DECL.match(lines[i])
    if not m:
        i += 1; continue
    j = i + 1
    while j < len(lines) and lines[j] != '    }': j += 1
    spans[m.group(1)] = (i, j)
    i = j + 1

IDENT = re.compile(r'\b(?:r|rv)\.([A-Za-z][A-Za-z0-9_]*)|\b([A-Za-z][A-Za-z0-9_]*)\s*(?:\.isBlank\(\)|\.isEmpty\(\)|==\s*null)')
def idents(text):
    out = set()
    for a, b in IDENT.findall(text):
        if a: out.add(a)
        if b: out.add(b)
    return out

def rowloop(name):
    """행 루프의 시작 ~ **세는 자리**(마지막 inBackup++)까지. 그 뒤의 가드는 이미 센 행을
    가르는 것이라 이 검사의 대상이 아니다(그쪽은 skippedCount로 세는 것이 옳다)."""
    s, e = spans[name]
    start = None
    for k in range(s, e):
        if start is None and re.search(r'for \(i in 1\.\.', lines[k]): start = k
    if start is None: return None
    last = None
    for k in range(start, e):
        if 'inBackup++' in lines[k].split('//')[0]: last = k
    if last is None: return None
    return start, last + 1

def preview_skips(name):
    r = rowloop(name)
    if not r: return set(), False
    s, e = r
    out, k = set(), s
    while k < e:
        code = lines[k].split('//')[0]
        m = re.match(r'\s*if \((.*)\)\s*(\{)?\s*(continue)?\s*$', code)
        if m:
            cond, brace, cont = m.group(1), m.group(2), m.group(3)
            if cont and not brace:
                out |= idents(cond); k += 1; continue
            if brace:
                depth, blk, t = 1, [], k + 1
                while t < e and depth > 0:
                    depth += lines[t].count('{') - lines[t].count('}')
                    if depth > 0: blk.append(lines[t])
                    t += 1
                body = '\n'.join(blk)
                if 'continue' in body and 'inBackup++' not in body: out |= idents(cond)
                k = t; continue
        # 엘비스-continue: `val year = r.year ?: continue` — if 꼴이 아니라 위 갈래가 못 본다.
        # **이 꼴이 B-237의 셋을 숨기고 있던 자리다**(연표·상태변화·관계변화의 연도).
        m3 = re.match(r'\s*val\s+([A-Za-z][A-Za-z0-9_]*)\s*=\s*(?:r|rv)\.([A-Za-z][A-Za-z0-9_]*)\s*\?:\s*continue\s*$', code)
        if m3:
            out.add(m3.group(1)); out.add(m3.group(2)); k += 1; continue
        # 한 줄 블록: if (x) { a; b; continue }
        m2 = re.match(r'\s*if \((.*)\)\s*\{(.*)\}\s*$', code)
        if m2 and 'continue' in m2.group(2) and 'inBackup++' not in m2.group(2):
            out |= idents(m2.group(1))
        k += 1
    return out, True

def import_notified(name):
    s, e = spans[name]
    out, k = set(), s
    while k < e:
        code = lines[k].split('//')[0]
        m = re.match(r'\s*if \((.*)\)\s*\{\s*$', code)
        if m:
            depth, blk, t = 1, [], k + 1
            while t < e and depth > 0:
                depth += lines[t].count('{') - lines[t].count('}')
                if depth > 0: blk.append(lines[t])
                t += 1
            body = '\n'.join(blk)
            if 'result.skippedRows++' in body and 'continue' in body: out |= idents(m.group(1))
            k = t; continue
        k += 1
    return out, True

# 짝은 이름으로 짓는다(analyzeX ↔ importX). 이름이 갈리는 둘만 손으로 적고, **짝을 못 찾으면
# 위반이다** — 조용히 빠지는 것이 이 부류의 실패 모양이라 ⑤와 같은 규약을 쓴다.
OVERRIDE = {'analyzePresetTemplates': 'importUserPresetTemplates',
            'analyzeCharacterSheet': 'importCharacterRows'}
bad = []
for fn in sorted(spans):
    if not fn.startswith('analyze'): continue
    p, ok = preview_skips(fn)
    if not ok: continue
    imp = OVERRIDE.get(fn, 'import' + fn[len('analyze'):])
    if imp not in spans:
        bad.append(f"{fn}\t짝 '{imp}'을(를) 파일에서 찾지 못했습니다 — 개명됐다면 OVERRIDE에 적으세요")
        continue
    s, _ = import_notified(imp)
    hit = p & s
    if hit:
        bad.append(f"{fn}\t{imp}가 세며 거부하는 행을 미리보기는 세지 않고 버립니다: {', '.join(sorted(hit))}")
for b in bad:
    print(b)
print(f"__NCOUNT__{len(bad)}")
PY6
)
ncount=$(printf '%s\n' "$NOTIFIED" | sed -n 's/^__NCOUNT__//p')
nbody=$(printf '%s\n' "$NOTIFIED" | grep -v '^__NCOUNT__' || true)

count=$(printf '%s\n' "$violations" | sed -n 's/^__COUNT__//p')
body=$(printf '%s\n' "$violations" | grep -v '^__COUNT__' || true)

# ── 자기 출력 증명 (B-240) — 다섯 스캐너 전부 ──
# 스캐너가 죽어 표식을 못 내면 아래 판정의 `${x:-0}`이 **0 = 위반 없음**으로 떨어져
# **"✓ 위반 없음" + exit 0으로 조용히 통과한다** — CI가 초록인데 아무것도 안 본 상태다.
# **자기 시험이 이 구멍을 막지 못한다:** 자기 시험은 `-ne N`이라 빈 값이 0이 되면 *실패로*
# 가지만 본 판정은 `-gt 0`이라 빈 값이 0이 되면 *통과로* 간다 — **같은 기본값이 반대로
# 작용한다.** 프로브의 "오류 0인데 클래스 파일도 0" 가드와 같은 부류이고, 짝인
# `check_import_row_queries.sh`·`check_preview_row_queries.sh`가 그 본보기다.
require_count() {  # $1=뽑은 값  $2=표식 이름  $3=스캐너 산출(꼬리를 증거로 보인다)
  if [ -z "$1" ]; then
    echo "  ✗ 검사가 자기 출력을 내지 못했습니다 ($2 없음) — 스캐너 실패입니다" >&2
    printf '%s\n' "$3" | tail -5 >&2
    exit 2
  fi
}
require_count "$count"  "__COUNT__"  "$violations"
require_count "$pcount" "__PCOUNT__" "$PAIRING"
require_count "$tcount" "__TCOUNT__" "$TALLY"
require_count "$scount" "__SCOUNT__" "$SHEETS"
require_count "$lcount" "__LCOUNT__" "$LADDER"
require_count "$ncount" "__NCOUNT__" "$NOTIFIED"

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
  echo "  ✗ 읽기(read*Row)·짓기(new*From)를 한쪽에서만 부릅니다 (${pcount}건)"
  echo
  printf '%s\n' "$pbody" | while IFS=$'\t' read -r fn only; do
    [ -z "${fn:-}" ] && continue
    echo "    $fn — ${only}에서만 부름"
  done
  echo
  echo "  비교식만 맞추고 리더를 각자 두면 같은 결함이 한 겹 아래에서 되살아납니다."
  echo "  가져오기와 미리보기가 **같은 read*Row**를 부르게 하세요."
  echo "             설계: docs/restore_preview_parity_2026-08.md 1-1"
  echo
  echo "  new*From이라면: 미리보기도 매칭이 빈손일 때 그 함수로 항목을 지어 색인에 등재해야"
  echo "  같은 파일의 뒷 행이 첫 행의 쓰기를 봅니다(B-233 — 안 하면 '신규 2'라 예고하고"
  echo "  가져오기는 '신규 1 + 갱신 1'을 합니다)."
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

if [ "${lcount:-0}" -gt 0 ]; then
  echo "  ✗ 미리보기의 캐릭터 해석이 가져오기와 다른 사다리를 씁니다 (${lcount}건)"
  echo
  printf '%s\n' "$lbody" | while IFS=$'\t' read -r fn why; do
    [ -z "${fn:-}" ] && continue
    echo "    $fn — $why"
  done
  echo
  echo "  이름 first-match는 동명이인 행을 미리보기만 '실행된다'고 예고하고 가져오기는 거부합니다."
  echo "  짝 가져오기와 **같은 사다리**(findCharacterStrict / resolveCharByNameNovel)를 부르고,"
  echo "  모호(Ambiguous)는 skippedCount로 세세요(B-102 ⓑ · B-232)."
  echo "  새 시트라면 이 스크립트의 PAIRS에 짝을 등재하세요."
  exit 1
fi

if [ "${ncount:-0}" -gt 0 ]; then
  echo "  ✗ 가져오기가 세며 거부하는 행을 미리보기가 세지 않고 버립니다 (${ncount}건)"
  echo
  printf '%s\n' "$nbody" | while IFS=$'\t' read -r fn why; do
    [ -z "${fn:-}" ] && continue
    echo "    $fn — $why"
  done
  echo
  echo "  그 행은 '건너뜀'도 '백업에 있음'도 아닌 **무존재**가 되어, 미리보기의 '백업에 N건'만"
  echo "  파일의 실제 행보다 작아지고 그 차이가 화면 어디에도 없습니다(B-237)."
  echo "  가드를 세는 자리로 옮기세요:  if (…) { inBackup++; skippedCount++; continue }"
  echo "  inBackup은 **파일이 이 시트에 적어 둔 행**입니다 — 가져오기가 받아들일 행이 아닙니다."
  exit 1
fi

echo "  ✓ 모든 analyze*가 가져오기와 같은 merge* 판정을 씁니다"
echo "  ✓ read*Row + new*From ${ptotal}종을 가져오기와 미리보기가 함께 부릅니다 (B-233)"
echo "  ✓ '갱신' 집계가 전부 변경 판정 뒤에 있습니다 (B-111)"
echo "  ✓ analyze*의 시트 조회가 전부 가져오기와 같은 판정(SheetResolver)을 지납니다 (B-217)"
echo "  ✓ analyze*의 캐릭터 해석이 전부 짝 가져오기와 같은 사다리를 씁니다 (B-232)"
echo "  ✓ 가져오기가 세며 거부하는 행을 미리보기도 전부 셉니다 (B-237)"
echo
echo "복원 미리보기 정합 검사 통과"
