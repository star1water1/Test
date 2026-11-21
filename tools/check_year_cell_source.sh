#!/bin/bash
# 연도 칸의 단일 소스 검사 (규약 R-71의 짝 · 2026.11.21)
#
# ## 무엇을 보는가
#
# **연도 열을 읽는 자리가 둘 중 하나인가.**
#
#   ① 선택 칸 — `readYearCell(...)`이 읽는다. 못 읽으면 **경고하고** 빈 값으로 둔다.
#   ② 행의 정체 — `val yearRaw = getCellString(row, …)`로 날글자를 쥐고, 못 읽으면
#      `errors`로 **그 행을 건너뛴다**(연도가 없으면 그 행이 무엇인지 알 수 없다 — R-36).
#
# 그 밖의 읽기, 특히 `parseNumber(getCellString(row, c.…Year))`는 위반이다.
#
# ## 왜 검사가 필요한가
#
# 2026.08.22에 세력 소속의 `가입연도`·`탈퇴연도`가 **맨손 `parseNumber`**라, `1990년`·`미상`
# 같은 꼴이 경고 한 줄 없이 `null`이 되고 **DB에 있던 연도를 그 null이 덮던** 결함을 고쳤다
# (`readYearCell` KDoc). 그 수리가 **`표준연도`(작품) 한 칸을 빠뜨렸고**, 그 칸만 석 달 뒤
# 워크스루 S4에서 관찰로 다시 잡혔다 — *"작품 표준연도 `abc`는 경고 없이 빈 값이 됐다."*
#
# 같은 부류가 **같은 처방으로 두 번** 났다는 것이 이 검사의 근거다. 실패의 모양은 침묵이고
# (매칭은 '생성일' 안정 식별자로 그대로 붙으니 행은 살고 값만 지워진다), 열이 늘 때마다
# 이 함정이 새로 생긴다.
#
# 사용법: tools/check_year_cell_source.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt"

echo "── 연도 칸 단일 소스 검사 (R-71 짝) ──"

# 주석을 지운다 — 이 저장소의 KDoc은 종전의 틀린 코드를 원문으로 인용하는 관행이라
# (`종전에는 parseNumber(getCellString(...))였다`) 주석을 세면 검사가 자기 문서를 잡는다.
strip_comments() { sed -E 's://.*$::' "$1" | grep -vE '^[[:space:]]*(\*|/\*)'; }

# ── ① 금지: 연도 색인을 `readYearCell` 밖에서 날로 읽는 자리 ──
# 허용되는 단 하나의 예외는 `yearRaw`에 쥐는 것이다(행의 정체 갈래).
bad_reads() {
  strip_comments "$1" \
    | grep -nE 'getCellString\(row, [^)]*[Yy]ear' \
    | grep -vE 'val yearRaw = getCellString' \
    || true
}

# ── ② 완전성: `cols["…연도"]`로 집은 변수가 둘 중 한 갈래에 실제로 닿는가 ──
# 변수 이름만 집어 쓰이는 자리를 되짚는다. 어느 갈래에도 없으면 그 열은 **아무도 읽지
# 않거나**(열이 죽었다) **다른 길로 읽힌다**(위 ①이 못 본 모양일 수 있다).
year_vars() {
  strip_comments "$1" | grep -oE 'val [A-Za-z]+ = cols\["[^"]*연도"\]' \
    | sed -E 's/^val ([A-Za-z]+) = .*/\1/' | sort -u
}

# ── 탐지기 자기 시험 ──
SELF=$(mktemp)
cat > "$SELF" <<'EOF'
        val standardYear = cols["표준연도"] ?: -1
        val year = cols["연도"] ?: 0
        val leaveYear = cols["탈퇴연도"] ?: -1
            standardYear = readYearCell(row, c.standardYear, ctx, "표준연도", result),
        val yearRaw = getCellString(row, c.year)
            leave = parseNumber(getCellString(row, c.leaveYear))?.toInt(),
EOF
SELF_BAD=$(bad_reads "$SELF" | wc -l | tr -d ' ')
SELF_VARS=$(year_vars "$SELF" | wc -l | tr -d ' ')
rm -f "$SELF"
if [ "$SELF_BAD" != "1" ] || [ "$SELF_VARS" != "3" ]; then
  echo "❌ 탐지기 자기 시험 실패: 금지 읽기 $SELF_BAD건(1 기대) · 연도 변수 $SELF_VARS개(3 기대)"
  echo "   규칙이 어긋났다 — 이 상태의 '위반 0건'은 아무것도 증명하지 않는다."
  exit 1
fi

if [ ! -f "$SRC" ]; then
  echo "❌ $SRC 가 없다 — 경로가 바뀌었으면 이 검사를 함께 옮길 것."
  exit 1
fi

VIOLATIONS=0

HITS=$(bad_reads "$SRC")
if [ -n "$HITS" ]; then
  echo "❌ 연도 색인을 readYearCell 밖에서 날로 읽는 자리:"
  echo "$HITS" | sed 's/^/     /'
  VIOLATIONS=$((VIOLATIONS + $(echo "$HITS" | wc -l)))
fi

VARS=$(year_vars "$SRC")
if [ -z "$VARS" ]; then
  echo "❌ cols[\"…연도\"] 바인딩을 한 건도 못 찾았다 — 열을 집는 모양이 바뀌었다."
  echo "   그 경우 이 검사는 늘 초록이면서 아무것도 보지 않는다. 규칙을 함께 고칠 것."
  exit 1
fi

COUNTED=0
for v in $VARS; do
  COUNTED=$((COUNTED + 1))
  if strip_comments "$SRC" | grep -qE "readYearCell\(row, [A-Za-z.]*\.?$v\b|readYearCell\(row, $v\b"; then
    continue
  fi
  if strip_comments "$SRC" | grep -qE "val yearRaw = getCellString\(row, [A-Za-z.]*\.?$v\b"; then
    continue
  fi
  # 세째 갈래 — **열 묶음(`…Cols`)에 넘겨 둔 것**. 읽는 자리는 그 묶음의 속성이고,
  # 그 읽기는 위 ①이 본다(`getCellString(row, c.year)`가 `yearRaw`가 아니면 거기서 걸린다).
  # 넘김까지 위반으로 세면 '연도' 열을 묶음으로 든 세 시트가 전부 빨간불이 된다.
  if strip_comments "$SRC" | grep -qE "Cols\(.*\b$v\b"; then
    continue
  fi
  echo "❌ 연도 열 '$v'이(가) 두 갈래 어느 쪽으로도 읽히지 않는다"
  echo "     선택 칸이면 readYearCell(row, …, \"<열 이름>\", result)로,"
  echo "     행의 정체면 val yearRaw = getCellString(row, …) + errors 갈래로 읽을 것."
  VIOLATIONS=$((VIOLATIONS + 1))
done

if [ "$VIOLATIONS" -gt 0 ]; then
  echo
  echo "총 $VIOLATIONS건. 연도는 조용히 null이 되어서는 안 된다 — 그 null이 기존 값을 덮는다."
  exit 1
fi

echo "✅ 연도 열 ${COUNTED}개가 전부 두 갈래 중 하나로 읽힌다"
