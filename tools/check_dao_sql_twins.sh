#!/bin/bash
# SQL 리터럴 ↔ 코틀린 상수 쌍둥이 검사 (B-266 · R-68).
#
# ## 무엇을 막는가
#
# Room의 `@Query`와 `execSQL`은 **코틀린 상수를 참조할 수 없다.** 그래서 도메인 키·플래그가
# 상수로 한 벌, SQL 안에 **글자로 한 벌** 산다. 그 둘을 잇는 것이 아무것도 없으면
# **상수의 값을 고치는 순간 SQL만 옛 글자를 들고 남는다** — 컴파일도 통과하고 예외도 안 나고,
# 갈린 것은 *결과*뿐이다.
#
# 실제 자리(B-251 ⓓ가 `StandardYearLink`를 옮기다 발견):
#   `CharacterStateChangeDao`가 `fieldKey != '__std_year_link'`로 내부 플래그를 집계에서 뺀다.
#   그 글자가 `StandardYearLink.KEY`와 갈리면 **상태변화 집계가 내부 플래그를 사용자 변경으로
#   세기 시작한다**(캐릭터마다 없는 이력이 하나씩 는다). 오류는 나지 않는다.
#
# 이 저장소는 같은 병에 이미 처방을 하나 세워 두었다 — `check_faction_standing.sh`의 축 [B]
# (R-50)가 *'지금 소속' 술어의 SQL 쌍둥이*를 본다. **이 검사는 그 처방을 한 자리가 아니라
# 값 전체로 넓힌 것이다.**
#
# ## 어떻게 잇는가 — 명단이 아니라 **값으로** 찾는다
#
# 쌍둥이 목록을 스크립트에 적으면 그 순간 낡는다(이 저장소가 반복해 무는 모양이다).
# 그래서 **SQL 리터럴의 값이 어떤 `const val`의 값과 정확히 같으면 쌍둥이 후보로 본다.**
# 그러면 새 쌍둥이가 들어올 때 **자동으로 뜬다** — 실제로 이 검사를 세우며 등재가 세지 못한
# 자리가 여섯 더 나왔다(`'row:'`·`'none'`·`'auto'`·`'edit_backup'`·`'AUTO'`·`'character'` 계열).
#
# 짝은 **자리에** 적는다(명단이 아니라 — R-53·R-63과 같은 관행):
#
#     /** … 내부 플래그는 집계에서 뺀다. */
#     // SQL 쌍둥이: StandardYearLink.KEY
#     @Query("… WHERE fieldKey != '__std_year_link'")
#
# 값이 우연히 겹칠 뿐 쌍둥이가 아니면 사유를 적어 면제한다:
#
#     // SQL 쌍둥이 없음: '%' LIKE 이스케이프 문자이지 도메인 값이 아니다
#
# **면제는 값을 지목한다** — 덩어리 통째로 푸는 꼴이면 같은 `@Query`에 진짜 쌍둥이가 하나 더
# 있을 때 그것까지 덮는다(실제로 리터럴 둘을 든 `@Query`가 있다).
#
# ## 축 셋
#
#   ① **짝 맞춤** — 표식이 가리키는 상수를 소스에서 뜨고, 그 **현재 값**이 같은 덩어리의
#      SQL 리터럴과 같은가. 상수 값을 고치면 여기서 빨간불이다. **이 축이 이 검사의 몸통이다.**
#   ② **미등재 쌍둥이** — 값이 상수와 같은 리터럴이 표식 없이 있는가. 새 쌍둥이가 조용히
#      들어오는 것을 막는다.
#   ③ **낡은 표식** — 붙을 자리가 없는 표식·면제. 표식은 면제가 아니라 **사유**이므로,
#      가리키는 것이 사라지면 그 자체가 결함이다(R-53·R-63이 세운 규약과 같다).
#   ④ **범위 밖의 살아 있는 SQL** — 앞의 셋은 전부 `data/dao/`·`data/maintenance/` 안만 본다.
#      SQL이 다른 자리로 **옮겨 가거나 새로 생기면** 그 셋은 볼 것이 없어 **하나도 울리지 않는다.**
#      그래서 앱 전체를 훑어 대상도 예외도 아닌 자리를 빨간불로 낸다 —
#      **검사가 죽은 것과 위반이 없는 것이 겉이 같아지는 것**을 막는 축이다.
#
# ## 범위와 사각지대 — 산출이 스스로 말한다
#
# 보는 것은 **살아 있는 SQL**이다: `data/dao/`의 `@Query`와 `data/maintenance/`의
# `execSQL`·`query`. **마이그레이션 SQL(`data/database/AppDatabase.kt`)은 일부러 뺀다** —
# 그쪽은 *그 스키마 버전 당시의 데이터*를 기술하는 **얼어붙은 기록**이라 상수를 따라가면
# 오히려 틀린다(개명은 데이터 마이그레이션이 할 일이지 옛 마이그레이션을 고칠 일이 아니다).
#
# 사용법: tools/check_dao_sql_twins.sh          # 위반 시 exit 1
#         REPO=/tmp/tree tools/check_dao_sql_twins.sh --no-self-test
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

scan() {
  python3 - "$1" <<'PY'
import io, os, re, sys

SRC = os.path.join(sys.argv[1], 'app/src/main/java/com/novelcharacter/app')

def kt_files(rel):
    d = os.path.join(SRC, rel)
    if not os.path.isdir(d): return []
    return sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith('.kt'))

# ── 상수 전수: `const val NAME = "값"` — 소유 타입은 **가장 가까운 최상위 선언**에서 뜬다.
#    파일 이름으로 뜨면 한 파일에 선언이 둘일 때 갈리고, 손으로 적으면 낡는다.
CONSTS = {}                                    # "Type.NAME" -> 값
BY_VALUE = {}                                  # 값 -> ["Type.NAME", …]
TOP = re.compile(r'^(?:abstract |open |sealed |data |enum |value |)(?:class|object|interface) (\w+)')
CONST = re.compile(r'const val ([A-Za-z0-9_]+)\s*=\s*"((?:[^"\\]|\\.)*)"')
# **enum 항목의 코드도 상수다** (2026.08.19 콜드 검토). `const val`만 보면 `NarrativeMode.AUTO("auto")`
# 꼴로 사는 도메인 값이 통째로 안 보이고, 그러면 그 값만 든 SQL 리터럴이 **축 ②를 조용히 통과한다.**
# 실측으로 넓혔다 — 현행 트리에서 enum 코드 **54종** 중 DAO 리터럴과 겹치는 것은 둘이고
# (`''`는 빈 값이라 원래 뺀다, `'auto'`는 이미 표식이 붙어 있다) **위반은 0 그대로**다.
ENUM = re.compile(r'^\s+([A-Z][A-Z0-9_]*)\("((?:[^"\\]|\\.)*)"')
for root, _dirs, files in os.walk(SRC):
    for fn in sorted(files):
        if not fn.endswith('.kt'): continue
        owner = fn[:-3]
        text = io.open(os.path.join(root, fn), encoding='utf-8').read()
        has_enum = 'enum class' in text
        for line in text.split('\n'):
            t = TOP.match(line)
            if t: owner = t.group(1)
            c = CONST.search(line)
            e = ENUM.match(line) if has_enum else None
            if c:
                key = owner + '.' + c.group(1)
                CONSTS.setdefault(key, c.group(2))
                BY_VALUE.setdefault(c.group(2), []).append(key)
            if e:
                key = owner + '.' + e.group(1)
                CONSTS.setdefault(key, e.group(2))
                BY_VALUE.setdefault(e.group(2), []).append(key)

# ── 살아 있는 SQL 구간: `@Query(` · `execSQL(` · `.query(` 의 균형 괄호 안.
# **`.query(`는 *첫 인자가 문자열일 때만* SQL이다** (2026.08.19 콜드 검토가 실측으로 잡았다).
# 그냥 `\.query\s*\(`로 두면 `contentResolver.query(uri, projection, …)`가 함께 걸린다 —
# 그것은 SAF 질의이지 SQL이 아니고, 실제로 `util/OrganizeFolderService.kt` 두 자리가 그 꼴이라
# 축 ④가 **거짓 양성**을 냈다. 앞을 내다보는 괄호로 좁혀 구간의 시작은 `(` 바로 뒤에 둔다.
OPEN = re.compile(r'@Query\s*\(|execSQL\s*\(|\.query\s*\((?=\s*")')
LITERAL = re.compile(r"'([^']*)'")
MARK = re.compile(r'SQL 쌍둥이: *([A-Za-z0-9_]+\.[A-Za-z0-9_]+)')
# 면제는 **값을 지목한다** — 덩어리 통째로 풀면 같은 덩어리에 진짜 쌍둥이가 하나 더 있을 때
# 그것까지 덮는다(실제로 `TrashSnapshotDao`의 한 `@Query`가 리터럴 둘을 든다).
EXEMPT = re.compile(r"SQL 쌍둥이 없음: *'([^']*)'\s*\S")

targets = kt_files('data/dao') + kt_files('data/maintenance')
regions = literals = 0
rows = []                                      # (파일, 줄, 값)
marks = []                                     # (파일, 줄, "Type.NAME")
exempts = []                                   # (파일, 줄)
chunk_of = {}                                  # (파일, 줄) -> 덩어리 id
broken = []

for path in targets:
    text = io.open(path, encoding='utf-8').read()
    rel = path[len(SRC) + 1:]
    # 덩어리 = 빈 줄로 갈린 연속 줄 묶음. 표식은 같은 덩어리 안에서만 유효하다 —
    # 고정 줄 수로 잡으면 사이에 낀 새 선언이 남의 표식을 물려받는다(B-256이 겪은 그 모양).
    cid, lines = 0, text.split('\n')
    line_chunk = []
    for ln in lines:
        if ln.strip() == '': cid += 1
        line_chunk.append(cid)

    def lineno(pos): return text.count('\n', 0, pos) + 1

    for m in OPEN.finditer(text):
        i, depth = m.end(), 1
        while i < len(text) and depth:
            if text[i] == '(': depth += 1
            elif text[i] == ')': depth -= 1
            i += 1
        if depth:                              # 괄호가 안 닫힌다 = 이 파일을 못 읽었다
            broken.append(f'{rel}:{lineno(m.start())} 괄호가 닫히지 않는다')
            continue
        regions += 1
        body_start = m.end()
        for lit in LITERAL.finditer(text[body_start:i - 1]):
            literals += 1
            ln = lineno(body_start + lit.start())
            rows.append((rel, ln, lit.group(1)))
            chunk_of[(rel, ln)] = line_chunk[ln - 1]

    for idx, ln_text in enumerate(lines, 1):
        mk = MARK.search(ln_text)
        if mk:
            marks.append((rel, idx, mk.group(1)))
            chunk_of[(rel, idx)] = line_chunk[idx - 1]
        ex = EXEMPT.search(ln_text)
        if ex:
            exempts.append((rel, idx, ex.group(1)))
            chunk_of[(rel, idx)] = line_chunk[idx - 1]

def chunk(rel, ln): return (rel, chunk_of[(rel, ln)])

# 덩어리별 리터럴 값
in_chunk = {}
for rel, ln, val in rows:
    in_chunk.setdefault(chunk(rel, ln), []).append((ln, val))

out = []
# ── ① 짝 맞춤 ─────────────────────────────────────────────────────────────
for rel, ln, name in marks:
    if name not in CONSTS:
        out.append(f'A|{rel}:{ln}|표식이 가리키는 상수가 없다: {name}')
        continue
    want = CONSTS[name]
    here = [v for _l, v in in_chunk.get(chunk(rel, ln), [])]
    if want not in here:
        got = ' · '.join(f"'{v}'" for v in here) or '(리터럴 없음)'
        out.append(f'A|{rel}:{ln}|{name} = "{want}" 인데 이 SQL의 리터럴은 {got}')

# ── ② 미등재 쌍둥이 ───────────────────────────────────────────────────────
# 빈 문자열은 뺀다 — SQL의 `!= \'\'`는 *비었는가*를 묻는 관용구이고, 값이 ""인 상수와
# 겹치는 것은 우연이다(그 상수의 값을 고쳐도 이 질의는 틀리지 않는다).
for key, lits in sorted(in_chunk.items()):
    rel, _cid = key
    named = {CONSTS[n] for _r, _l, n in marks if chunk(_r, _l) == key and n in CONSTS}
    freed = {v for _r, _l, v in exempts if chunk(_r, _l) == key}
    for ln, val in lits:
        if val == '' or val not in BY_VALUE: continue
        if val in named or val in freed: continue
        who = ' · '.join(sorted(BY_VALUE[val])[:3])
        out.append(f"B|{rel}:{ln}|'{val}' 은 상수의 쌍둥이인데 표식이 없다 (후보: {who})")

# ── ③ 낡은 표식 ───────────────────────────────────────────────────────────
for rel, ln, val in exempts:
    lits = [v for _l, v in in_chunk.get(chunk(rel, ln), [])]
    if val not in lits:
        out.append(f"C|{rel}:{ln}|면제가 지목한 '{val}' 이 이 SQL에 없다")
    elif val == '' or val not in BY_VALUE:
        out.append(f"C|{rel}:{ln}|'{val}' 은 어떤 상수와도 겹치지 않는다 — 면제할 것이 없다")

# ── ④ 범위 밖의 살아 있는 SQL ─────────────────────────────────────────────
# **이 검사가 *자기 사각지대를 못 보게 되는 것*을 막는다** (2026.08.19 콜드 검토).
# 위 셋은 전부 `data/dao/`·`data/maintenance/` 안만 본다. SQL이 **다른 자리로 옮겨 가거나
# 새로 생기면** 축 ①②③은 볼 것이 없어 **하나도 울리지 않는다** — 검사가 죽은 것과
# 위반이 없는 것이 겉이 같아지는 그 배치다(`check_restore_preview_parity.sh` 축 ⑧이 같은 이유로 섰다).
# 그래서 **앱 전체에서 살아 있는 SQL을 든 파일을 세고**, 대상도 예외도 아닌 자리가 나오면 빨간불이다.
# 예외는 하나뿐이다 — 마이그레이션(`data/database/AppDatabase.kt`). 얼어붙은 기록이라 일부러 뺀다.
FROZEN = 'data/database/AppDatabase.kt'
scanned = {p[len(SRC) + 1:] for p in targets}
elsewhere = []
for root, _dirs, files in os.walk(SRC):
    for fn in sorted(files):
        if not fn.endswith('.kt'): continue
        rel = os.path.join(root, fn)[len(SRC) + 1:]
        if rel in scanned or rel == FROZEN: continue
        if OPEN.search(io.open(os.path.join(root, fn), encoding='utf-8').read()):
            elsewhere.append(rel)
for rel in elsewhere:
    out.append(f'D|{rel}|이 검사가 안 보는 자리에 살아 있는 SQL이 있다 — 대상에 넣거나 사유와 함께 빼라')

twins = sum(1 for _r, _l, v in rows if v and v in BY_VALUE)
for b in broken:
    out.append(f'X|{b}|SQL 구간을 읽지 못했다 — 이 산출을 믿지 말 것')
print(f'#SCOPE|{len(targets)}|{regions}|{literals}|{twins}|{len(marks)}|{len(exempts)}')
for line in out: print(line)
PY
}

report() {                                     # $1=스캔 산출  → 위반이 있으면 1
  local out="$1" fail=0 scope
  scope=$(echo "$out" | grep '^#SCOPE|' | head -1)
  local files regions lits twins marks exempts
  IFS='|' read -r _ files regions lits twins marks exempts <<<"$scope"
  echo "  범위: 파일 ${files}개 · SQL 구간 ${regions}개 · 리터럴 ${lits}개 · 쌍둥이 ${twins}개 · 표식 ${marks}개(면제 ${exempts}개)"
  echo "  못 보는 것: 마이그레이션 SQL(data/database/) — 얼어붙은 기록이라 상수를 따라가면 안 된다"

  # **자기 출력을 증명한다**(B-240): 대상이 없거나 SQL 구간을 하나도 못 뜨면
  # "위반 0"은 초록이 아니라 **침묵**이다. 정규식이 깨져도 겉이 똑같아진다.
  if [ "${files:-0}" -eq 0 ] || [ "${regions:-0}" -eq 0 ]; then
    echo "  ✗ 대상이나 SQL 구간을 하나도 못 떴다 — 이 검사가 아무것도 보고 있지 않다"; return 1
  fi

  local sec
  for sec in A:"① 상수와 SQL이 갈렸다" B:"② 표식 없는 쌍둥이" C:"③ 낡은 표식" D:"④ 범위 밖의 살아 있는 SQL" X:"⚠️ 스캐너 고장"; do
    local tag=${sec%%:*} title=${sec#*:}
    local hits; hits=$(echo "$out" | grep "^$tag|" || true)
    if [ -n "$hits" ]; then
      echo "  ✗ $title"
      echo "$hits" | sed 's/^[A-Z]|/      /' | sed 's/|/  — /'
      fail=1
    fi
  done
  [ "$fail" -eq 0 ] && echo "  ✓ 살아 있는 SQL의 리터럴이 전부 상수와 같다"
  return $fail
}

# ── 탐지기 자기 시험 ────────────────────────────────────────────────────────
# **복사본이 아니라 진짜를 부른다** — 2026.08.19 콜드 검토가 `triage_unresolved.sh`에서
# 잡은 결함이 정확히 그것이다(시험이 정규식을 베껴 두어, 진짜만 고치면 시험은 통과하고
# 그물은 죽는 배치였다). 여기서는 **이 스크립트 자신을** 가짜 트리에 대고 돌린다.
if [ "${1:-}" != "--no-self-test" ]; then
  T=$(mktemp -d)
  mk() {  # $1=트리, $2=상수값, $3=SQL 리터럴, $4=표식 줄(없으면 빈 문자열), $5=범위 밖 SQL 파일(선택)
    local d="$1/app/src/main/java/com/novelcharacter/app"
    mkdir -p "$d/data/dao" "$d/data/model" "$d/util"
    printf 'object Flag {\n    const val KEY = "%s"\n}\n' "$2" > "$d/data/model/Flag.kt"
    { [ -n "$4" ] && echo "    $4"
      printf '    @Query("SELECT 1 WHERE k != %s")\n    suspend fun q(): Int\n' "'$3'"
    } > "$d/data/dao/T.kt"
    # 범위 밖 자리에 **SAF 질의**를 늘 하나 둔다 — 축 ④가 `contentResolver.query(uri, …)`를
    # SQL로 오인하면 *모든* 시험이 빨간불이 되어 곧바로 드러난다(콜드 검토가 실측한 거짓 양성).
    printf 'fun saf(r: Any) { r.query(uri, projection, null, null, null) }\n' > "$d/util/Saf.kt"
    [ -n "${5:-}" ] && printf 'fun raw(db: Any) { db.execSQL("DELETE FROM x WHERE k = %s") }\n' "'$2'" > "$d/util/Raw.kt"
    return 0
  }
  st_fail=0
  probe() {  # $1=이름, $2=트리, $3=기대 종료코드
    local rc; REPO="$2" bash "$SELF" --no-self-test >/dev/null 2>&1; rc=$?
    if [ "$rc" -ne "$3" ]; then
      echo "  ✗ 탐지기 자기 시험 실패 — $1 (종료코드 $rc, 기대 $3)" >&2; st_fail=1
    fi
  }
  mk "$T/ok"      flag flag "// SQL 쌍둥이: Flag.KEY"      ; probe "바른 짝은 통과해야 한다"        "$T/ok"      0
  mk "$T/drift"   flagX flag "// SQL 쌍둥이: Flag.KEY"     ; probe "상수만 바뀐 것을 잡아야 한다"    "$T/drift"   1
  mk "$T/bare"    flag flag ""                             ; probe "표식 없는 쌍둥이를 잡아야 한다"  "$T/bare"    1
  mk "$T/ghost"   flag flag "// SQL 쌍둥이: Flag.MISSING"  ; probe "없는 상수를 가리키면 잡아야 한다" "$T/ghost"   1
  mk "$T/stale"   flag zzz  "// SQL 쌍둥이 없음: 'flag' 사유" ; probe "낡은 면제 표식을 잡아야 한다"    "$T/stale"   1
  mk "$T/exempt"  flag flag "// SQL 쌍둥이 없음: 'flag' 우연히 겹칠 뿐이다"; probe "값을 지목한 면제는 통과해야 한다" "$T/exempt" 0
  mk "$T/vague"   flag flag "// SQL 쌍둥이 없음: 사유만 적었다"; probe "값을 안 지목한 면제는 면제가 아니다" "$T/vague"  1
  mk "$T/outside" flag flag "// SQL 쌍둥이: Flag.KEY" raw    ; probe "범위 밖의 살아 있는 SQL을 잡아야 한다" "$T/outside" 1
  rm -rf "$T"
  if [ "$st_fail" -ne 0 ]; then
    echo "SQL 쌍둥이 검사: 탐지기가 자기 시험을 통과하지 못했다 — 이 산출을 믿지 말 것"; exit 1
  fi
  echo "── SQL 리터럴 ↔ 코틀린 상수 쌍둥이 검사 (B-266) ──"
  echo "  ✓ 탐지기 자기 시험 통과 (짝 맞음·상수 표류·표식 없음·유령 상수·낡은 면제·정당한 면제·값 없는 면제·범위 밖 SQL 여덟 — 모든 시험이 SAF 질의를 하나씩 이고 돈다)"
fi

OUT=$(scan "$REPO") || { echo "  ✗ 스캐너가 죽었다"; exit 1; }
if report "$OUT"; then
  echo ""
  echo "SQL 쌍둥이 검사 통과"
else
  echo ""
  echo "SQL 쌍둥이 검사 실패"
  exit 1
fi
