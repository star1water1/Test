#!/bin/bash
# '설정(JSON)' 열의 위치 폴백 금지 검사 (R-36 · B-142 · B-162)
#
# 배경: 엑셀 가져오기에서 **없는 열**과 **빈 칸**은 다른 사실이다(R-36). 그런데 헤더를 열
# 번호로 푸는 자리에서 `cols["설정(JSON)"] ?: 4` 처럼 **위치 폴백**을 두면, 사용자가 그 열을
# 통째로 지운 파일에서 **4번 자리에 올라온 이웃 열이 config로 읽힌다.**
#
# 이 부류는 **두 번 났다:**
#   ① B-142 — '기본 필드' 시트. 열이 없을 때 `"{}"`로 뭉개 템플릿 설정을 **통째로 비웠고**,
#      이어 [전파]가 그 빈 설정을 모든 세계관 + 전역 구역으로 퍼뜨렸다.
#   ② B-162 — '필드 정의' 시트. 열 순서가 …·타입(3)·설정(JSON)(4)·그룹(5)이라 그 열을 지우면
#      **`그룹`이 4번 자리에 올라와** config가 `"기본 정보"` 같은 그룹명이 되고
#      `options`·`formula`·체형 설정·물질화된 등급표를 **덮는다.**
#
# **검사를 세우는 근거가 여기 있다.** B-142를 고친 판은 *"형제 칸 넷이 위치 폴백 금지라고
# 적어 두었는데 앞쪽 여섯만 옛 관행으로 남아 있다"*고 기록했다 — 즉 **주석으로 적힌 관행이
# 같은 파일 안에서 지켜지지 않았다.** 그 판이 스스로 남긴 말이 근거다:
# *"관행이라고 적어도 지켜지지 않는다. 지켜지게 하려면 적는 것 말고 기계가 있어야 한다."*
#
# **타입은 이것을 못 막는다.** `merge(sheetConfig: String?)`는 null을 받게 돼 있지만,
# 폴백을 `?: 4`로 되돌려도 **여전히 컴파일된다**(그냥 항상 non-null이 될 뿐이다).
# 컴파일·순수 시험·프로브 어느 것도 이 자리를 보지 않는다.
#
# 사용법: tools/check_config_column_fallback.sh   # 위반 시 exit 1
set -u
# 로케일 고정 — 한글 대괄호 범위를 쓰지 않으므로 C에서 안전하다(고정 문자열 검색만 쓴다).
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app"

# **목록이다** (B-223) — 종전에는 '설정(JSON)' 하나였고, 그동안 '그룹'은 `?: 5`인 채로
# 같은 함정을 그대로 재현하고 있었다(열을 지우면 이웃 `순서`가 그룹명이 된다).
# 한 칸을 내릴 때마다 여기 더한다 — **정체 열은 대상이 아니다**(없으면 행 자체를 못 읽는다).
NEEDLES=('cols["설정(JSON)"]' 'cols["그룹"]')

echo "── 선택 열 위치 폴백 금지 검사 (R-36 · B-142 · B-162 · B-223) ──"

# 한 줄이 옳은가: `cols["설정(JSON)"]` 뒤가 `?: -1` 인가.
# (`?:` 없이 쓰는 형태는 타입이 `Int?`라 아래 소비처가 컴파일되지 않으므로 여기서 보지 않는다.)
bad_lines() {  # $1=대상 경로  $2=바늘
  grep -rn -F "$2" --include=*.kt "$1" 2>/dev/null |
    grep -v -F '?: -1'
}

# ── 탐지기 자기 시험 — 나쁜 형태를 실제로 잡고, 좋은 형태는 안 잡는가 ──
# 이 검사가 조용히 통과할 경로를 먼저 막는다(B-141이 *가드를 무력화해도 초록인* 판을 만들어 본 부류).
SELFDIR=$(mktemp -d)
mkdir -p "$SELFDIR/nested"
cat > "$SELFDIR/nested/Good.kt" <<'EOF'
        val config = cols["설정(JSON)"] ?: -1
EOF
cat > "$SELFDIR/nested/Bad.kt" <<'EOF'
        val config = cols["설정(JSON)"] ?: 4
EOF
good_hits=$(bad_lines "$SELFDIR/nested/Good.kt" 'cols["설정(JSON)"]' | grep -c . || true)
bad_hits=$(bad_lines "$SELFDIR/nested/Bad.kt" 'cols["설정(JSON)"]' | grep -c . || true)
rm -rf "$SELFDIR"
if [ "$good_hits" -ne 0 ] || [ "$bad_hits" -ne 1 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 좋은 형태 ${good_hits}건(0이어야) · 나쁜 형태 ${bad_hits}건(1이어야)" >&2
  echo "      (좋은 형태가 잡히면 오검출이고, 나쁜 형태가 안 잡히면 이 검사는 장식이다)" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (?: 4 는 잡고 ?: -1 은 안 잡는다)"

# ── 본 검사 ──
# 자리 수는 여기 적지 않는다 — 시트가 늘면 낡는다. 세는 법이 원문이다:
#   grep -rn -F 'cols["설정(JSON)"]' --include=*.kt app/src/main/java
fail=0
for needle in "${NEEDLES[@]}"; do
  found=$(bad_lines "$SRC" "$needle" || true)
  total=$(grep -rn -F "$needle" --include=*.kt "$SRC" 2>/dev/null | grep -c . || true)

  if [ "$total" -eq 0 ]; then
    echo "  ✗ ${needle} 자리를 하나도 찾지 못했습니다" >&2
    echo "      → 헤더 문구가 바뀌었으면 이 검사의 NEEDLES를 함께 고칠 것." >&2
    echo "        찾지 못한 채로 통과하면 **위반이 없는 것과 구별되지 않는다**." >&2
    exit 1
  fi

  if [ -n "$found" ]; then
    echo "  ✗ ${needle}에 위치 폴백이 남아 있습니다:"
    printf '%s\n' "$found" | sed 's/^/      /'
    fail=1
  else
    echo "  ✓ ${needle} $total 자리 전부 -1 폴백 (R-36)"
  fi
done

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "      → 없는 열은 -1이고, 그때는 **기존 값을 지킨다**(R-36)."
  echo "        위치로 폴백하면 그 열을 지운 파일에서 **이웃 열이 그 값으로 읽힌다** —"
  echo "        '설정(JSON)'은 options·formula·등급표를 덮고(B-162), '그룹'은 `순서` 값이"
  echo "        그룹명이 된다(B-223). 둘 다 무경고이고 미리보기도 같은 코드라 못 잡는다."
  echo ""
  echo "선택 열 위치 폴백 금지 검사 실패" >&2
  exit 1
fi

echo ""
echo "선택 열 위치 폴백 금지 검사 통과"
exit 0
