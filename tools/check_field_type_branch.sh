#!/bin/bash
# 필드 타입 분기의 단일 소스 검사 (B-55, 규약 R-52)
#
# 배경: `FieldType` enum이 있는데 실제 분기는 `fd.type == "CALCULATED"` 같은 **문자열 비교**로
# 스물아홉 파일에 흩어져 있었다. enum에 타입을 하나 더해도 그 자리들은 아무것도 모르므로
# 새 타입은 그 화면들에서 **조용히 빠진다** — 원칙 02 위반(껍데기 구현)의 가장 흔한 발생
# 경로이며 S-15(레거시 필드 분석이 CALCULATED를 통째로 누락)가 실제 사례다.
# 2026.08.13에 전부 `FieldDefinition.fieldType`(enum) 분기로 좁혔다.
#
# ── 왜 시험이 아니라 검사인가 ──
# 좁혀 둔 자리는 컴파일러가 지킨다(`when`의 exhaustive 검사). **그러나 컴파일러는 새로 적히는
# 생문자열을 막지 못한다** — 다음 사람이 `if (fd.type == "GRADE")` 한 줄을 적으면 컴파일도
# 시험도 전부 초록인 채 그 자리만 옛 방식으로 돌아간다. 그 한 줄이 다시 스물아홉이 되는 데
# 걸린 시간이 등재 이후 23 → 29였다. **되돌아오는 것을 보는 축은 grep뿐이다.**
#
# ── 무엇을 보는가 ──
# ① **생문자열 타입 이름** — `"CALCULATED"` 같은 리터럴. 아래 허용 목록 밖이면 위반.
# ② **`.name`을 거친 우회** — `fd.type == FieldType.GRADE.name`. 상수는 enum에서 왔지만
#    **여전히 문자열 비교이고 exhaustive 검사를 못 받는다.** ①만 막으면 이쪽으로 샌다
#    (실제로 이 판 착수 시점에 여덟 자리가 이 모양이었고, 원래 grep은 그것을 한 건도 못 셌다).
#
# ── 허용되는 자리 (전부 사유가 있다) ──
# - `data/database/AppDatabase.kt` — **마이그레이션은 이미 일어난 일을 재생한다.** 그때 심은
#   글자를 그대로 적어야 하고, enum을 참조하면 상수 이름을 고치는 순간 과거가 함께 바뀐다.
#   사유 원문은 그 파일 상단 블록 주석이다.
# - `data/model/FieldType.kt` — 선언 자신.
# - `.type = FieldType.X.name` 꼴의 **구성**(저장·직렬화). 분기가 아니라 값을 만드는 자리다.
#   구성까지 막으면 엔티티를 만들 길이 없어진다.
#
# ── 이 검사가 못 보는 자리 (적어 두지 않으면 다음 사람이 검사가 있다고 믿는다) ──
# **변수를 거쳐 가는 비교**: `val t = fd.type` 뒤 한참 아래에서 `if (t == someString)`을 쓰면
# 두 줄이 이웃이 아니라 이 검사는 조용하다. 그 모양을 잡으려면 데이터 흐름 분석이 필요하고
# grep이 표현할 수 있는 축이 아니다. **그래서 `when`으로 좁혀 두는 것이 본체이고 이 검사는
# 울타리다** — 울타리를 검사의 전부로 읽지 말 것.
#
# 사용법: tools/check_field_type_branch.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java"
TYPES='TEXT|NUMBER|SELECT|MULTI_TEXT|GRADE|CALCULATED|BODY_SIZE'

echo "── 필드 타입 분기 단일 소스 검사 (B-55 · R-52) ──"

# 주석을 지운다 — 이 저장소의 KDoc은 *종전에 무엇이 틀렸는지*를 원문으로 인용하는 관행이라
# (`종전에는 type == "MULTI_TEXT"였다`) 주석을 세면 검사가 자기 문서를 위반으로 잡는다.
strip_comments() { sed -E 's://.*$::' "$1" | grep -vE '^[[:space:]]*(\*|/\*)'; }

# ① 생문자열 타입 이름
raw_hits() { strip_comments "$1" | grep -nE "\"($TYPES)\"" || true; }

# ② `.name`을 거친 문자열 비교. **구성(`type = ...`, `type` 인자)은 세지 않는다** —
#    `[=!]=` 또는 `in`이 앞뒤에 붙은 것만이 분기다.
name_hits() {
  strip_comments "$1" | grep -nE "([=!]=[[:space:]]*FieldType\.($TYPES)\.name|FieldType\.($TYPES)\.name[[:space:]]*[-=!]=)" || true
}

# ── 탐지기 자기 시험 — 안 맞는 정규식은 "위반 없음"과 구별되지 않는다 ──
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
if (fd.type == "CALCULATED") return null
val t = setOf("NUMBER", "GRADE")
if (fd.type == FieldType.GRADE.name) skip()
if (FieldType.MULTI_TEXT.name == defsById[id]?.type) skip()
// 종전에는 `fd.type == "BODY_SIZE"`였다 — 이 줄은 주석이라 세지 않는다
val ok1 = FieldDefinition(key = "a", name = "b", type = FieldType.TEXT.name)
val ok2 = if (fd.fieldType == FieldType.CALCULATED) 1 else 0
val ok3 = FieldType.entries.map { it.name }
EOF
SELF_RAW=$(raw_hits "$SELFTEST" | wc -l | tr -d ' ')
SELF_NAME=$(name_hits "$SELFTEST" | wc -l | tr -d ' ')
rm -f "$SELFTEST"
if [ "$SELF_RAW" != "2" ] || [ "$SELF_NAME" != "2" ]; then
  echo "❌ 탐지기 자기 시험 실패: 생문자열 $SELF_RAW건(2 기대) · .name 비교 $SELF_NAME건(2 기대)"
  echo "   정규식이 어긋났다 — 이 상태의 '위반 0건'은 아무것도 증명하지 않는다."
  exit 1
fi

VIOLATIONS=0
while IFS= read -r f; do
  case "$f" in
    */data/database/AppDatabase.kt) continue ;;   # 마이그레이션 — 파일 상단 블록 주석이 사유
    */data/model/FieldType.kt) continue ;;        # 선언 자신
  esac
  hits=$(raw_hits "$f")
  if [ -n "$hits" ]; then
    echo "❌ 생문자열 타입 분기: $f"
    echo "$hits" | sed 's/^/     /'
    VIOLATIONS=$((VIOLATIONS + $(echo "$hits" | wc -l)))
  fi
  nhits=$(name_hits "$f")
  if [ -n "$nhits" ]; then
    echo "❌ .name을 거친 문자열 비교(exhaustive 검사를 못 받는다): $f"
    echo "$nhits" | sed 's/^/     /'
    VIOLATIONS=$((VIOLATIONS + $(echo "$nhits" | wc -l)))
  fi
done < <(find "$SRC" -name '*.kt' | sort)

if [ "$VIOLATIONS" -gt 0 ]; then
  echo
  echo "총 $VIOLATIONS건. 분기는 FieldDefinition.fieldType(enum)으로 한다 —"
  echo "  나쁨: if (fd.type == \"CALCULATED\")   ·   if (fd.type == FieldType.CALCULATED.name)"
  echo "  좋음: if (fd.fieldType == FieldType.CALCULATED)"
  echo "손에 쥔 것이 정의가 아니라 글자뿐이면 FieldType.fromName(s)으로 먼저 좁힐 것."
  exit 1
fi

echo "✅ 위반 없음 — 타입 분기는 전부 FieldType enum을 지난다"
