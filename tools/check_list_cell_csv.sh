#!/bin/bash
# 목록 셀의 결합·분해 단일 소스 검사 (B-27 ② + B-38, 규약 R-47)
#
# 배경: 엑셀의 목록 셀(참여 캐릭터·관련 작품·태그·별칭·대결 참가자…)은 **앱이 이름을 이어 붙이고
# 다시 쪼개는** 자리다. 종전에는 붙이는 쪽이 자리마다 `joinToString(", ")`이었고 쪼개는 쪽은
# `split(",")`이라, **이름·제목에 쉼표가 있으면 왕복 한 번에 두 값으로 파손됐다**(B-27 ②).
# 2026.08.10에 따옴표 규약(엑셀·CSV와 같은 방식)으로 둘을 한 쌍(`joinCsv`/`splitCsv`)에 모았다.
#
# ── 왜 시험이 아니라 검사인가 ──
# 규칙 자체는 `SheetSpecCsvTest`가 순수 시험으로 잠근다. 그러나 이 결함의 모양은
# **아무도 그 쌍을 부르지 않는 것**이다 — 새 목록 열이 하나 늘면서 `joinToString(", ")`을
# 한 줄 적으면 **시험은 전부 초록인 채 그 열에서만 조용히 파손된다**(R-31·R-43·R-44와 같은 계열이고,
# 넷 다 실패의 모양이 침묵이다). 붙이는 쪽이 쌍을 쓰는가는 기계가 볼 수 있는 유일한 축이다.
#
# ── 무엇을 보는가 ──
# ① **쪼개는 쪽**: `excel/`에서 단일 소스 밖의 `.split(",")` / `.split(',')`.
#    한 자리라도 남으면 그 셀만 따옴표를 모르는 채 옛 규칙으로 쪼갠다.
# ② **붙이는 쪽**: 셀에 쓰는 자리(`createCell`·`setTextSafe`)의 `joinToString`.
#    사람이 읽는 안내 문구의 `joinToString(", ")`은 셀이 아니므로 세지 않는다 —
#    그래서 '셀에 쓰는가'를 기준으로 좁힌다(문구까지 걸면 검사가 거짓말을 하기 시작한다).
# ③ **`excel/` 밖에 사는 셀 도우미**: `setTextSafe(X.y(...))`로 셀에 실리는 `X`가 이 폴더 밖
#    파일이면 그 파일도 ①②로 훑는다 (B-261에서 신설).
#    **이 축이 없어서 결함 하나가 살아 있었다:** `util/DuelFieldLinks`가 `toText`/`parseText`로
#    `대결 축` 시트의 세 칸을 잇고 되쪼개는데, 폴더가 `excel/`이 아니라 이 검사가 못 봤고
#    짝인 `check_multi_value_input.sh`는 **되쪼개는 쪽만** 본다(붙이는 쪽 창이 `setText(`라
#    함수로 감싼 결합이 안 걸린다). 그래서 **양쪽 검사가 다 초록인 채로** 그 칸이 감싸기를
#    모르고 있었다 — `내, 키` 키 하나가 왕복 한 번에 둘로 갈렸다.
#    **명단을 손으로 적지 않는다** — `setTextSafe(X.` 호출에서 기계로 뜬다. 손 명단이면
#    새 도우미가 조용히 빠지고, 그 사각이 정확히 이 축을 부른 사고의 모양이다.
#    그 파일의 결합 창은 `setTextSafe` 근처가 아니라 **쉼표를 구분자로 쓰는 `joinToString`
#    전부**다 — 셀에 실리는 글을 만드는 것이 그 파일의 일이라 이웃 조건이 성립하지 않는다.
#
# ── 이 검사가 못 보는 자리 (적어 두지 않으면 다음 사람이 검사가 있다고 믿는다) ──
# **변수를 거쳐 가는 결합**: `val s = list.joinToString(", ")`을 먼저 만들고 한참 뒤에
# `setTextSafe(s)`로 쓰면 두 줄이 이웃이 아니라 이 검사는 조용하다. 2026.08.10 현재
# `excel/`에 그런 자리는 없고(남은 `joinToString(", ")`은 전부 안내 문구다), 그 사실을
# 여기 적어 두는 것이 이 검사가 할 수 있는 전부다 — 셀에 닿는 값의 출처를 따라가려면
# 데이터 흐름 분석이 필요하고, 그것은 grep이 표현할 수 있는 축이 아니다.
#
# 사용법: tools/check_list_cell_csv.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

EXCEL="app/src/main/java/com/novelcharacter/app/excel"
SOURCE="$EXCEL/SheetSpec.kt"

echo "── 목록 셀 결합·분해 단일 소스 검사 (B-27 ② · R-47) ──"

# 주석을 지운다. **줄을 잇지는 않는다** — ②는 '셀에 쓰는 자리 근처'라는 거리가 판정의 일부라,
# 파일을 통째로 한 줄로 펴면 무관한 문구의 joinToString까지 이웃이 된다.
strip_comments() { sed -E 's://.*$::' "$1" | grep -vE '^[[:space:]]*(\*|/\*)'; }

# ① 쪼개는 쪽
split_hits() { strip_comments "$1" | grep -oE '\.split\((\",\"|'"'"','"'"')\)' || true; }

# ② 붙이는 쪽 — 셀에 쓰는 자리의 joinToString.
#    **뒤따르는 두 줄을 통째로 이웃으로 보지 않는 것이 요점이다** — 그렇게 하면 셀 쓰기 바로 아래에
#    선 안내 문구의 joinToString까지 걸려 검사가 거짓 경보를 낸다(자기 시험이 실제로 그것을 잡았다).
#    그래서 창은 **인자가 다음 줄로 넘어간 모양**(`setTextSafe(`로 줄이 끝난 자리)에만 연다.
join_hits() {
  strip_comments "$1" | awk '
    { lines[NR] = $0 }
    END {
      for (i = 1; i <= NR; i++) {
        if (lines[i] ~ /setTextSafe.*joinToString/ || lines[i] ~ /createCell.*joinToString/) {
          print "line " i; continue
        }
        if (lines[i] ~ /setTextSafe\([ \t]*$/) {
          win = lines[i+1] " " lines[i+2]
          if (win ~ /joinToString/) print "line " i
        }
      }
    }' || true
}

# ③ `excel/` 밖 셀 도우미의 결합 창 — 쉼표를 구분자로 쓰는 joinToString 전부.
#    그 파일은 셀에 실릴 글을 만드는 것이 일이라 `setTextSafe` 이웃 조건이 성립하지 않는다.
#    **창이 넓으므로 끌 손잡이를 함께 둔다** — 도우미 파일에도 사람이 읽는 문구가 있을 수 있고,
#    끌 수 없는 거짓 양성은 곧 검사를 꺼지게 한다(2026.08.18 콜드 검토가 짝 검사에서 겪은 자리).
#    표식은 `// 셀 결합 예외(사유)`이고 **그 줄이나 바로 윗줄**에 있어야 한다.
#    짝 검사(`check_multi_value_input.sh`)의 `// 쉼표 예외(`와 **글자를 다르게 둔 것은 일부러다** —
#    같은 글자면 한쪽에서 켠 면제가 다른 쪽에서 *낡은 표식* 빨간불이 된다.
HELPER_MARK='// 셀 결합 예외('

helper_marked() {  # $1 = 파일, $2 = 줄번호
  local n="$2" prev
  prev=$(( n - 1 ))
  if [ "$prev" -ge 1 ]; then
    sed -n "${prev}p;${n}p" "$1" 2>/dev/null | grep -qF "$HELPER_MARK"
  else
    sed -n "${n}p" "$1" 2>/dev/null | grep -qF "$HELPER_MARK"
  fi
}

# 주석 줄은 빼되 **줄을 지우지는 않는다** — 표식 판정이 원본 줄번호에 걸려 있다.
helper_join_hits() {  # $1 = 파일 → "줄번호:본문"
  grep -nE 'joinToString\([^)]*"[^"]*,[^"]*"' "$1" 2>/dev/null \
    | awk -F: '{ body=$0; sub(/^[0-9]+:/, "", body);
                 if (body ~ /^[[:space:]]*(\/\/|\*)/) next; print }' \
    | while IFS= read -r hit; do
        n="${hit%%:*}"
        helper_marked "$1" "$n" && continue
        printf '%s\n' "$hit"
      done
}

# ── 탐지기 자기 시험 — 안 맞는 정규식은 "위반 없음"과 구별되지 않는다 ──
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
val a = raw.split(",").map { it.trim() }
val b = cell("참가자들").split(',').filter { it.isNotEmpty() }
val ok1 = splitCsv(cell("참가자코드들"))
val ok2 = ref.split(':')
row.createCell(4).setTextSafe(members.joinToString(", ") { nameByCode[it] ?: it })
row.createCell(col++).setTextSafe(
    (allTags[id] ?: emptyList()).joinToString(", ") { it.tag }
)
row.createCell(5).setTextSafe(joinCsv(members))
result.warnings.add("행 $i: 열 ${unknown.joinToString(", ")}을 무시했습니다")
fun toText(links: List<Link>): String = normalize(links).joinToString(", ") { token(it) }
fun labelOf(x: List<String>): String = x.joinToString(" / ")
val notice = keys.joinToString(", ")  // 셀 결합 예외(사람이 읽는 문구다)
EOF
s=$(split_hits "$SELFTEST" | grep -c . || true)
j=$(join_hits "$SELFTEST" | grep -c . || true)
h=$(helper_join_hits "$SELFTEST" | grep -c . || true)
rm -f "$SELFTEST"
# 쪼개기 2건(`:` 분리와 splitCsv 호출은 세지 않는다) · 붙이기 2건(joinCsv와 문구는 세지 않는다)
if [ "$s" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 쪼개기 복붙 2건 중 ${s}건만 잡았습니다" >&2; exit 1
fi
if [ "$j" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 붙이기 복붙 2건 중 ${j}건만 잡았습니다" >&2; exit 1
fi
# ③의 창은 넓다(파일 전체) — 그래서 **쉼표가 구분자인 것만** 세고 **표식은 끄는지**를 함께 확인한다.
# 자기 시험 글의 쉼표 구분자는 넷(문구 하나 · 셀 쓰기 둘 · 도우미 하나)이고,
# `" / "`는 구분자가 아니라 세지 않으며 표식이 붙은 다섯째 줄도 세지 않는다.
if [ "$h" -ne 4 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 도우미 결합 4건을 기대했는데 ${h}건입니다(쉼표 아닌 구분자를 셌거나 표식이 안 듣는다)" >&2; exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (한 줄·끊어 적은 것 둘을 잡고, 쌍 호출·':' 분리·안내 문구는 세지 않는다)"

fail=0

# ── 단일 소스가 제자리에 있는가 ──
if [ ! -f "$SOURCE" ]; then
  echo "  ✗ 단일 소스가 없습니다: $SOURCE (이름이 바뀌었으면 이 검사를 함께 고칠 것)"; fail=1
else
  for fn in 'fun splitCsv' 'fun joinCsv' 'fun quoteCsvToken'; do
    grep -q "$fn" "$SOURCE" || { echo "  ✗ $SOURCE 에 '$fn'이 없습니다"; fail=1; }
  done
fi

# ── 전수 훑기 (손으로 적은 목록은 불완전할 때 침묵한다) ──
checked=0
for f in $(find "$EXCEL" -name '*.kt' | sort); do
  [ "$f" = "$SOURCE" ] && continue
  checked=$((checked + 1))
  hs=$(split_hits "$f" | grep -c . || true)
  hj=$(join_hits "$f" | grep -c . || true)
  if [ "$hs" -ne 0 ]; then
    echo "  ✗ $f — 쉼표 분리를 직접 적었습니다 (${hs}건)"
    echo "      → splitCsv(value)를 쓸 것. 자리마다 적으면 그 셀만 따옴표를 모르고,"
    echo "        따옴표를 모르는 셀은 이름에 쉼표가 든 순간 값을 둘로 쪼갠다."
    fail=1
  fi
  if [ "$hj" -ne 0 ]; then
    echo "  ✗ $f — 셀에 쓰는 자리에서 joinToString을 썼습니다 (${hj}건)"
    join_hits "$f" | sed 's/^/        /'
    echo "      → joinCsv(values) 또는 joinCsv(values) { ... }를 쓸 것. 감싸지 않고 이어 붙이면"
    echo "        내보낸 파일이 스스로를 다시 읽지 못한다(왕복이 그 자리에서 깨진다)."
    fail=1
  fi
done

# 한 파일도 안 봤으면 그것은 '위반 없음'이 아니라 **검사가 안 돈 것**이다.
if [ "$checked" -eq 0 ]; then
  echo "  ✗ 검사 대상 파일을 하나도 찾지 못했습니다 — 경로가 바뀌었으면 EXCEL을 함께 고칠 것"; fail=1
fi

# ── ③ `excel/` 밖에 사는 셀 도우미 (B-261) ──
# 명단을 적지 않는다 — `setTextSafe(X.` 호출에서 기계로 뜬다.
APP="app/src/main/java/com/novelcharacter/app"
helpers=$(grep -rhoE 'setTextSafe\([A-Z][A-Za-z0-9_]*\.' "$EXCEL" \
  | sed -E 's/setTextSafe\(([A-Za-z0-9_]+)\./\1/' | sort -u)
outside=0
for name in $helpers; do
  [ -f "$EXCEL/$name.kt" ] && continue          # `excel/` 안이면 위 훑기가 이미 봤다
  f=$(find "$APP" -name "$name.kt" | head -1)
  [ -n "$f" ] || continue                        # 파일이 아니라 같은 파일 안의 object일 수 있다
  outside=$((outside + 1))
  hs=$(split_hits "$f" | grep -c . || true)
  hh=$(helper_join_hits "$f" | grep -c . || true)
  if [ "$hs" -ne 0 ]; then
    echo "  ✗ $f — 셀 도우미인데 쉼표 분리를 직접 적었습니다 (${hs}건)"
    echo "      → CsvTokens.split(...)을 쓸 것. 이 파일은 excel/ 밖이라 위 훑기가 보지 못한다."
    fail=1
  fi
  if [ "$hh" -ne 0 ]; then
    echo "  ✗ $f — 셀 도우미인데 쉼표로 직접 이어 붙였습니다 (${hh}건)"
    helper_join_hits "$f" | sed 's/^/        /'
    echo "      → CsvTokens.join(...)을 쓸 것. 감싸지 않고 이어 붙이면 그 칸의 값에 쉼표가"
    echo "        든 순간 왕복이 깨지고, 짝인 check_multi_value_input.sh는 결합 쪽을 보지 못한다."
    fail=1
  fi
done

# ── ③-b 낡은 표식 — 표식은 면제가 아니라 사유다 ──
# 고친 뒤 표식만 남으면 다음 사람이 **여기는 봐준 자리**로 읽고 그 아래에 진짜 위반을 얹는다.
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  f="${hit%%:*}"; rest="${hit#*:}"; n="${rest%%:*}"
  body=$(sed -n "${n}p;$(( n + 1 ))p" "$f" 2>/dev/null)
  if ! printf '%s' "$body" | grep -qE 'joinToString\([^)]*"[^"]*,[^"]*"'; then
    echo "  ✗ [낡은 표식] $f:$n — 표식은 있는데 그 자리에 쉼표 결합이 없다(고친 뒤 남은 것이면 지울 것)"
    fail=1
  fi
done <<EOF
$(grep -rn --include='*.kt' -F "$HELPER_MARK" "$APP" || true)
EOF

# 하나도 못 찾았으면 그것은 '밖에 없다'가 아니라 **뜨는 법이 깨진 것**이다.
if [ "$outside" -eq 0 ]; then
  echo "  ✗ excel/ 밖 셀 도우미를 하나도 뜨지 못했습니다 — setTextSafe 호출 모양이 바뀌었으면 이 검사를 함께 고칠 것"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "── 목록 셀 결합·분해 단일 소스 검사 실패 ──" >&2
  exit 1
fi
echo "  ✓ ${checked}개 파일이 결합·분해를 모두 단일 소스에 맡긴다 (excel/ 밖 셀 도우미 ${outside}개 포함)"
echo "── 목록 셀 결합·분해 단일 소스 검사 통과 ──"
