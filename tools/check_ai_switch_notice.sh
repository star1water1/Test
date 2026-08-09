#!/bin/bash
# 프로바이더 자동 전환의 고지 동행 검사 (B-108) — **다른 곳이 답했으면 그렇게 말해야 한다.**
#
# 배경: 한도에 걸리면 관문([AiService])이 다음 프로바이더로 이어간다. 사용자는 그것을 켠 적이
# 없고 화면에도 표시가 없으므로, 고지가 빠지면 **자기가 고른 모델의 답인 줄 알고 다른 회사
# 다른 모델이 쓴 글을 받는다**(개발 의도 2번 — 변수 제어). 확정 ⓑ가 *"결과에 한 줄"*을 건 자리다.
#
# ── 왜 순수 JVM 시험만으로 부족한가 ──
# 시험은 `AiProviderFallback.switchNoteOf`가 **옳은 문장을 만드는지**를 잰다. 그런데 이 결함의
# 모양은 *문장이 틀린 것*이 아니라 **아무도 그 함수를 부르지 않는 것**이다. 인앱 기능이 일곱째로
# 늘면서 그 한 줄을 빠뜨리면, 시험은 전부 초록인 채 **그 기능에서만 조용히** 고지가 사라진다.
# B-150이 정확히 그 부류였다 — *"호출부에 맡기면 자리가 여덟이 되고, 빠뜨린 자리는 종전과
# 똑같이 조용하다."* 사람이 기억하는 것에 기대지 않으려고 기계를 세운다.
#
# ── 무엇을 보는가 ──
# ① 필수: `aiService.complete(`를 부르는 파일은 전부 `switchNoteOf`를 함께 쓰는가.
# ② 필수: 단일 소스(`switchNote`·`switchNoteOf`)가 제자리에 있는가 — ①만으로는 이름이
#    바뀌어 아무 데도 없는 상태를 못 가른다.
#
# 사용법: tools/check_ai_switch_notice.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app/ai"
OWNER="$SRC/AiProviderFallback.kt"

echo "── 자동 전환 고지 동행 검사 (B-108) ──"

# 주석을 지우고 줄바꿈을 없앤 뒤 본다. `aiService\n    .complete(` 처럼 끊어 적은 호출은
# 코틀린에서 흔하고, **줄 단위로 보는 검사는 그것을 조용히 놓친다**(B-137에서 겪었다).
flatten() {
  sed -E 's://.*$::' "$1" | grep -vE '^[[:space:]]*(\*|/\*)' | tr '\n' ' '
}

calls_complete() {
  flatten "$1" | grep -oE '\baiService[[:space:]]*\.[[:space:]]*complete[[:space:]]*\(' || true
}

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 정규식이 안 맞으면 위반이 있어도 "위반 없음"과 구별되지 않는다.
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
when (val result = aiService.complete(request)) {
val r = aiService
    .complete(aiRequest)
// aiService.complete(request) 는 주석이라 세지 않는다
fun completeSomething() = other.complete(request)
EOF
hits=$(calls_complete "$SELFTEST" | grep -c . || true)
rm -f "$SELFTEST"
if [ "$hits" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 호출 2건 중 ${hits}건만 잡았습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (끊어 적은 호출을 잡고, 주석·남의 complete은 세지 않는다)"

fail=0

# ── ② 단일 소스가 제자리에 있는가 ──
if [ ! -f "$OWNER" ]; then
  echo "  ✗ 고지의 단일 소스가 없습니다: $OWNER (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
  fail=1
else
  for fn in 'fun switchNote(' 'fun switchNoteOf('; do
    if ! grep -qF "$fn" "$OWNER"; then
      echo "  ✗ $OWNER 에 \`$fn\`가 없습니다"
      echo "      → 문구와 판정을 한자리에 두지 않으면 호출부마다 다른 말을 하게 된다."
      fail=1
    fi
  done
fi

# ── ① 관문을 부르는 파일은 고지도 함께 쓴다 ──
checked=0
for f in "$SRC"/*.kt; do
  [ -f "$f" ] || continue
  n=$(calls_complete "$f" | grep -c . || true)
  [ "$n" -eq 0 ] && continue
  checked=$((checked + 1))
  if ! grep -q 'switchNoteOf' "$f"; then
    echo "  ✗ $f 이 aiService.complete()를 부르면서 전환 고지를 쓰지 않습니다 (${n}건)"
    echo "      → \`AiProviderFallback.switchNoteOf(result)?.let { ... }\` 한 줄을 자기 고지"
    echo "        채널에 얹을 것. 빠뜨리면 한도로 밀려 **다른 회사 다른 모델**이 쓴 글을"
    echo "        사용자가 자기가 고른 모델의 답인 줄 알고 받는다 (확정 ⓑ)."
    fail=1
  fi
done

if [ "$checked" -eq 0 ]; then
  echo "  ✗ 관문을 부르는 파일을 하나도 찾지 못했습니다 — 이 검사가 무력화된 상태입니다" >&2
  echo "      → 경로($SRC)나 호출 모양이 바뀌었는지 확인할 것." >&2
  exit 1
fi

if [ "$fail" -ne 0 ]; then
  echo "── 자동 전환 고지 동행 검사 실패 ──" >&2
  exit 1
fi
echo "  ✓ 관문을 부르는 ${checked}곳이 전부 전환 고지를 쓴다"
echo "── 자동 전환 고지 동행 검사 통과 ──"
