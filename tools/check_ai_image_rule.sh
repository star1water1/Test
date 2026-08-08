#!/bin/bash
# 이미지 지시와 이미지의 동행 검사 (B-139) — **그림을 빼면 그림 지시도 빠져야 한다.**
#
# 배경: 시스템 프롬프트에는 *"이 요청에는 캐릭터의 이미지 N장이 순서대로 함께 실려 있다"*가
# 붙는다(A-7). 그런데 이미지가 빠지는 경로가 **둘**이다 —
#   ④ 모델이 400으로 거부해 빼고 1회 재시도하는 자리(`AiService`)
#   `strippedUpfront` — 거부를 이미 학습한 모델에는 애초에 싣지 않는 자리
# 종전에는 `AiRequest.withoutImages()`가 `messages[].images`만 비우고 `system`을 손대지 않아
# **둘 다** 지시를 그대로 내보냈다. 모델은 있지도 않은 그림을 근거로 삼아 **날조된 출처**를
# 낼 수 있었고, 후자는 학습된 뒤 **매 요청** 어긋났다.
#
# 고침은 문자열을 도로 걷어내는 쪽이 아니다 — 그러면 빼는 경로가 셋째로 늘 때 또 샌다.
# 지시를 `AiRequest.imageSystemRule`로 갈라 **이미지와 한 몸으로** 다니게 했고,
# `effectiveSystem()`이 `hasImages()`로 판정한다. 빠뜨릴 자리 자체가 없어진다.
#
# ── 왜 순수 JVM 시험만으로 부족한가 ──
# 시험은 **지금 있는** 세 프로토콜이 옳게 조립하는지를 잰다(`AiImageRequestTest`).
# 넷째 프로토콜이 `request.system`을 그대로 읽으며 들어오면 **그 시험은 아무 말도 하지 않고**,
# 새 지시 절을 `buildSystemPrompt`에 도로 이어 붙여도 마찬가지다. 그 두 부활을 이 검사가 막는다.
#
# ── 무엇을 보는가 ──
# ① 금지: 프로토콜 코덱이 `request.system`을 **직접** 읽는가 (반드시 `effectiveSystem()`이다).
# ② 필수: 코덱이 `effectiveSystem()`을 실제로 쓰는가 (①만으로는 아무 데도 안 싣는 것과 못 가른다).
# ③ 금지: 이미지 지시가 `imageSystemRule =` 말고 다른 자리로 가는가 (시스템 프롬프트 이어붙이기의 부활).
#
# 사용법: tools/check_ai_image_rule.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app/ai"
CODEC="$SRC/AiProtocolCodec.kt"
MODEL="$SRC/AiModels.kt"
# 이미지 절을 만드는 두 자리. 새로 생기면 여기 등재할 것 — 누락하면 조용히 검사에서 빠진다.
RULE_OWNERS="$SRC/CharacterFieldAiSuggester.kt $SRC/NarrativeFieldAiWriter.kt"

echo "── 이미지 지시 동행 검사 (B-139) ──"

# 주석을 지우고 줄바꿈을 없앤 뒤 본다. `request\n    .system` 처럼 끊어 적은 호출은
# 코틀린에서 흔한 모양이고, **줄 단위로 보는 검사는 그것을 조용히 놓친다**(B-137에서 겪었다).
flatten() {
  sed -E 's://.*$::' "$1" | grep -vE '^[[:space:]]*(\*|/\*)' | tr '\n' ' '
}

# ① 코덱이 원본 `system`을 직접 읽는 자리 — `effectiveSystem()`은 걸리지 않아야 한다.
raw_system_reads() {
  flatten "$1" | grep -oE '\brequest[[:space:]]*\??\.[[:space:]]*system\b' || true
}

# ③ 이미지 지시를 쓰는 자리 — 정의(`fun imageRule(`)는 대상이 아니다.
#    남는 것은 전부 `imageSystemRule = imageRule(...)` 모양이어야 한다.
rule_uses_outside_slot() {
  flatten "$1" \
    | grep -oE '(imageSystemRule[[:space:]]*=[[:space:]]*|fun[[:space:]]+)?imageRule[[:space:]]*\(' \
    | grep -vE '^(imageSystemRule[[:space:]]*=|fun[[:space:]])' || true
}

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 정규식이 안 맞으면 위반이 있어도 "위반 없음"과 구별되지 않는다.
# 끊어 적은 호출·주석 안의 호출·비슷하지만 옳은 호출을 함께 세워,
# **잡는지**와 **잘못 잡지 않는지**를 한 번에 잰다.
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
request.system?.let { addProperty("system", it) }
request
    .system?.let { add(sys(it)) }
val ok = request.effectiveSystem()?.let { it }
// request.system 은 주석이라 세지 않는다
EOF
hits=$(raw_system_reads "$SELFTEST" | grep -c . || true)
rm -f "$SELFTEST"
if [ "$hits" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 위반 2건 중 ${hits}건만 잡았습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (끊어 적은 request.system을 잡고, effectiveSystem·주석은 세지 않는다)"

SELFTEST2=$(mktemp)
cat > "$SELFTEST2" <<'EOF'
""".trimIndent() + creativity.promptRule() + imageRule(imageCount)
val sys = base + imageRule (n)
fun imageRule(imageCount: Int): String =
imageSystemRule = imageRule(images.size)
// imageRule(images.size) 는 주석이라 세지 않는다
EOF
u=$(rule_uses_outside_slot "$SELFTEST2" | grep -c . || true)
rm -f "$SELFTEST2"
if [ "$u" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 ② 실패 — 지어낸 위반 2건 중 ${u}건만 잡았습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 ② 통과 (이어붙이기를 잡고, 정의·정상 인자·주석은 세지 않는다)"

fail=0

# ── 단일 소스가 제자리에 있는가 ──
if [ ! -f "$MODEL" ]; then
  echo "  ✗ 요청 모델이 없습니다: $MODEL (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
  fail=1
elif ! grep -q 'fun effectiveSystem' "$MODEL"; then
  echo "  ✗ $MODEL 에 effectiveSystem()이 없습니다"
  echo "      → 이미지 절을 실을지 말지는 요청 하나가 정한다. 프로토콜마다 정하면 세 벌이 된다."
  fail=1
fi

# ── ①② 코덱 ──
if [ ! -f "$CODEC" ]; then
  echo "  ✗ 등재된 코덱이 없습니다: $CODEC (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
  fail=1
else
  raw=$(raw_system_reads "$CODEC" | grep -c . || true)
  if [ "$raw" -ne 0 ]; then
    echo "  ✗ $CODEC 이 request.system을 직접 읽습니다 (${raw}건)"
    echo "      → effectiveSystem()을 쓸 것. 원본을 그대로 실으면 이미지를 뺀 요청에도"
    echo "        '이미지 N장이 실려 있다'가 함께 나가고, 모델은 없는 그림을 근거로 답한다."
    fail=1
  fi
  if ! grep -q 'effectiveSystem()' "$CODEC"; then
    echo "  ✗ $CODEC 이 effectiveSystem()을 쓰지 않습니다"
    echo "      → 시스템 프롬프트를 아무 데도 싣지 않는 것과 구별되지 않는다."
    fail=1
  fi
fi

# ── ③ 이미지 절을 만드는 자리 ──
for f in $RULE_OWNERS; do
  if [ ! -f "$f" ]; then
    echo "  ✗ 등재된 이미지 절 소유자가 없습니다: $f (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
    fail=1
    continue
  fi
  bad=$(rule_uses_outside_slot "$f" | grep -c . || true)
  if [ "$bad" -ne 0 ]; then
    echo "  ✗ $f 이 imageRule()을 imageSystemRule 밖에서 씁니다 (${bad}건)"
    echo "      → 시스템 프롬프트에 이어 붙이면 이미지를 빼는 경로가 각자 도로 걷어내야 하고,"
    echo "        하나만 빠뜨리면 없는 그림을 근거로 삼으라는 지시가 그대로 나간다(B-139)."
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "── 이미지 지시 동행 검사 실패 ──" >&2
  exit 1
fi
echo "  ✓ 이미지 절은 이미지와 한 몸으로 간다 (코덱 + 등재된 소유자 전량)"
echo "── 이미지 지시 동행 검사 통과 ──"
