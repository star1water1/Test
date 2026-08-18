#!/usr/bin/env bash
# 다중값 입력칸의 결합·분해 단일 소스 검사 (B-186 · 규약 R-63 · `check_list_cell_csv.sh`의 인앱 짝)
#
# **무엇을 막는가:** 태그·이명·별칭·선택지처럼 **한 줄에 여러 값을 쉼표로 적는 칸**의 두 반쪽이
# 서로 다른 규칙을 쓰는 것. 채우는 쪽이 `joinToString(", ")`이고 되쪼개는 쪽이 맨 `split(",")`이면
# **쉼표를 품은 값이 왕복 한 번에 둘로 갈린다** — 그리고 갈린 쪽은 되돌릴 방법이 없다.
#
# ── 왜 시험이 아니라 검사인가 ──
# 규칙 자체는 `MultiValueInputTest`가 순수 시험으로 잠근다. 그러나 이 결함의 모양은
# **아무도 그 쌍을 부르지 않는 것**이다 — 새 입력칸이 하나 늘면서 `split(",")`을 한 줄 적으면
# **시험은 전부 초록인 채 그 칸에서만 조용히 파손된다.** R-31·R-43·R-44·R-47과 같은 계열이고,
# 다섯 다 실패의 모양이 **침묵**이라 다른 검사가 원리적으로 못 본다.
#
# ── 무엇을 보는가 ──
# ① **되쪼개는 쪽**: 단일 소스 밖에서 **쉼표를 구분자로 쓰는 `split`** — 인자가 하나든 여럿이든 본다.
#    **처음에는 `.split(",")`·`.split(',')` 두 모양만 봤고, 그 좁힘이 실제로 한 자리를 놓쳤다**(2026.08.18 콜드 검토):
#    `DuelFieldLinks.parseText`가 `split(',', '\n')`이라 **정규식 밖이었다.** 쪼개는 방법은 여럿이고
#    문제는 *어떻게 적었는가*가 아니라 *쉼표로 나누는가*다 — 그래서 인자 목록 어디든
#    **쉼표 리터럴**(`","` 또는 `','`)이 있으면 본다.
#    **`", "`(쉼표+공백)는 일부러 안 본다** — 넣어 봤더니 `split("-", "/", ".")`의 *인자 구분 쉼표*를
#    구분자로 오해해 거짓 경보를 냈다(탐지기 자기 시험이 잡았다). 창을 넓힐 때 **무엇을 넓히는지가
#    아니라 무엇이 걸리는지를 봐야 한다**는 자리다.
# ② **채우는 쪽**: `setText(`와 같은 줄의 `joinToString` — 입력칸에 목록을 담는 자리다.
# ③ **낡은 표식**: 예외 표식이 붙었는데 그 자리에 위반이 없는 것(고친 뒤 표식만 남은 자리).
#
# **②의 창을 `setText(`로 잡은 것이 판단이고, 근거는 실측이다.** 앱에는 사람이 읽는 안내
# 문구의 `joinToString(", ")`이 **백 곳 넘게** 있고 그것들은 입력칸이 아니다. 그런데 이 저장소는
# **표시용 텍스트를 `.text = …` 속성으로 쓰고 입력칸에만 `setText(…)` 호출을 쓴다**(EditText의
# setText는 오버로드가 있어 속성 대입이 어색하다). 그래서 창을 `setText(`로 좁히면
# **거짓 경보가 0이다** — 신설 시점 실측으로, 걸린 여덟 자리가 전부 진짜 입력칸이었다.
# **문구까지 걸면 검사가 거짓 경보를 내기 시작하고, 거짓 경보를 내는 검사는 곧 꺼진다**(R-47이
# 같은 자리에서 배운 것).
#
# **정말로 쉼표로 갈라야 하는 자리는 그 줄에 사유를 적는다.** 사람이 적는 다중값 칸이 아니라
# **앱이 만든 기계 형식**인 자리가 실재한다(숫자 id를 잇는 화면 인자 · `키=값` 설정 문자열).
# 표식은 `// 쉼표 예외(사유)`이고 **그 줄이나 바로 윗줄**에 있어야 한다.
# **명단이 아니라 자리에 두는 이유**는 `check_import_row_queries.sh`가 실측으로 배운 것과 같다 —
# (파일, 함수) 명단으로 두면 함수 하나가 그 안의 위반 여럿을 통째로 덮어 **거짓 음성을 조용히 낸다.**
#
# ── 이 검사가 못 보는 자리 (적어 두지 않으면 다음 사람이 검사가 있다고 믿는다) ──
# **변수를 거쳐 가는 결합**: `val s = list.joinToString(", ")`을 먼저 만들고 한참 뒤에
# `setText(s)`로 담으면 두 줄이 이웃이 아니라 이 검사는 조용하다. 신설 시점 `ui/`에 그런 자리는
# 없고, 그 사실을 여기 적어 두는 것이 grep이 할 수 있는 전부다 — 담기는 값의 출처를 따라가려면
# 데이터 흐름 분석이 필요하고 그것은 이 도구가 표현할 수 있는 축이 아니다.
#
# 사용법: tools/check_multi_value_input.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

# **범위는 `ui/`가 아니라 앱 전체다 — 이름은 이 검사가 시작한 자리를 기록할 뿐이다.**
# 처음엔 `ui/`만 볼 생각이었다. 그 좁힘은 *규칙*이 아니라 **그때 고친 자리**이고,
# `check_import_row_queries.sh`가 똑같은 좁힘으로 **같은 부류 열넷을 놓쳤다**(B-241).
# 다중값 칸을 되쪼개는 코드가 `ai/`나 `data/`로 내려가는 것을 막을 규칙이 없으므로 전부 본다.
# 빼는 것은 둘뿐이고 둘 다 사유가 있다:
#   · `excel/` — 셀은 **넓은 모드**(CSV 관용 그대로)라 규칙이 갈린다. 그쪽은 `check_list_cell_csv.sh`.
#   · `util/CsvTokens.kt` — 규칙의 **정의 자신**이다(`splitLegacy`가 옛 경로를 든다).
SRC="app/src/main/java/com/novelcharacter/app"
SKIP='/excel/|util/CsvTokens\.kt'
MARK='// 쉼표 예외('
FAIL=0

echo "── 다중값 입력칸 결합·분해 단일 소스 검사 (B-186 · R-63) ──"

# 주석 줄은 대상에서 뺀다. **줄을 잇지는 않는다** — ②는 '같은 줄'이라는 거리가 판정의 일부다.
scan() {  # $1 = 정규식 → "파일:줄번호:본문"
  grep -rn --include='*.kt' -E "$1" "$SRC" \
    | grep -vE "$SKIP" \
    | awk -F: '{ line=$0; sub(/^[^:]*:[^:]*:/, "", line);
                 if (line ~ /^[[:space:]]*(\/\/|\*)/) next; print }' 
}

# 표식이 그 줄이나 바로 윗줄에 있는가.
# **첫 줄을 따로 다룬다** — `sed -n "1p;0p"`는 주소 `0`이 올 수 없어 **통째로 실패하고 아무것도 안 찍는다.**
# 그러면 첫 줄에 붙인 표식이 영영 인정되지 않아 **끌 수 없는 거짓 양성**이 된다(탐지기 자기 시험이 잡았다).
marked() {  # $1 = 파일, $2 = 줄번호
  local n="$2" prev
  prev=$(( n - 1 ))
  if [ "$prev" -ge 1 ]; then
    sed -n "${prev}p;${n}p" "$1" 2>/dev/null | grep -qF "$MARK"
  else
    sed -n "${n}p" "$1" 2>/dev/null | grep -qF "$MARK"
  fi
}

# ── ① 되쪼개는 쪽 ──
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  f="${hit%%:*}"; rest="${hit#*:}"; n="${rest%%:*}"
  if marked "$f" "$n"; then continue; fi
  echo "  ✗ [분해] $hit"
  echo "     → MultiValueInput.parse(...)를 쓸 것 (예외라면 그 줄에 '$MARK사유)')"
  FAIL=1
done <<EOF
$(scan '\.split\([^)]*(","|'"'"','"'"')')
EOF

# ── ② 채우는 쪽 ──
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  f="${hit%%:*}"; rest="${hit#*:}"; n="${rest%%:*}"
  if marked "$f" "$n"; then continue; fi
  echo "  ✗ [결합] $hit"
  echo "     → MultiValueInput.format(...)을 쓸 것 (예외라면 그 줄에 '$MARK사유)')"
  FAIL=1
done <<EOF
$(scan 'setText\(.*joinToString')
EOF

# ── ③ 낡은 표식 ──
# 표식이 붙은 자리에 위반이 없으면 그 표식은 고쳐진 뒤 남은 것이다. 남겨 두면 다음 사람이
# **여기는 봐준 자리**로 읽고 그 아래에 진짜 위반을 얹는다 — 표식은 면제가 아니라 사유다.
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  f="${hit%%:*}"; rest="${hit#*:}"; n="${rest%%:*}"
  body=$(sed -n "$n p;$(( n + 1 ))p" "$f" 2>/dev/null)
  if ! printf '%s' "$body" | grep -qE '\.split\([^)]*(","|'"'"','"'"')|setText\(.*joinToString'; then
    echo "  ✗ [낡은 표식] $f:$n — 표식은 있는데 그 자리에 위반이 없다(고친 뒤 남은 것이면 지울 것)"
    FAIL=1
  fi
done <<EOF
$(grep -rn --include='*.kt' -F "$MARK" "$SRC" | grep -vE "$SKIP")
EOF

if [ "$FAIL" -eq 0 ]; then
  echo "  ✓ 위반 0 (표식 통과 $(grep -rc --include='*.kt' -F "$MARK" "$SRC" | grep -vE "$SKIP" | awk -F: '{s+=$2} END {print s+0}'))"
  exit 0
fi
echo "  ✗ 위반이 있다 — 규약 R-63"
exit 1
