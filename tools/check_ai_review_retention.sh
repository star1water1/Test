#!/bin/bash
# AI 검토 시트의 유료 응답 보존 검사 (B-136 · B-140)
#
# 배경: 이미지 일괄 AI 태깅의 검토 시트는 **이미 결제한 응답**을 들고 있다. 그런데 두 자리에서
# 그것을 조용히 버리고 있었다.
#   ① 회전(B-136) — 제안이 Fragment의 지역 변수에만 살아, 재생성된 시트는 빈 껍데기였다.
#   ② 되받기(B-140) — '1장씩 다시 보내기'가 시트를 **먼저 닫고** 앞 실행의 성공분을 실어 나를
#      자리 없이 재실행해, 25장을 5요청에 돌려 1요청만 접혔을 때 20장분 제안이 소멸했다.
#      되받으려면 이미 성공한 4요청을 **다시 결제**해야 했다.
#
# 고장이 조용한 것이 이 검사를 다는 이유다: 아무것도 실패하지 않고, 제안이 줄어든 화면은
# *"AI가 그만큼만 답했다"*와 구별되지 않는다. 사용자는 잃은 줄도 모른다.
#
# **순수 시험과 역할이 갈린다.** `ImageBatchTagSuggesterTest`는 *합치는 규칙이 옳은가*를 재고
# (mergeRetry·retryablePaths), 이 검사는 *화면이 그 규칙을 실제로 지나는가*를 본다.
# 화면 계층은 Fragment·ViewModel이라 순수 JVM이 원리적으로 못 보므로 — 로컬 프로브도 `ui/**`를
# 통째로 뺀다 — **한쪽만 두면 어느 쪽도 이 결함을 못 잡는다**(B-135가 마주친 그 사각).
#
# ── 무엇을 보는가 ──
# ① 필수: 되받기 배선이 **앞 실행의 결과를 들고 간다**(carryOver).
# ② 금지: 되받기 핸들러가 넘기기 **전에 시트를 닫는다**(dismiss).
# ③ 필수: 검토 시트가 **검토 상태를 회전에 넘긴다**(onSaveInstanceState).
# ④ 필수: 유료 응답의 실행 엔진이 **뷰에 열려 있지 않다**(입구는 결과를 보관하는 run* 하나다).
#
# 사용법: tools/check_ai_review_retention.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app"
IMG="$SRC/ui/image"
FRAGMENT="$IMG/ImageManagerFragment.kt"
SHEET="$IMG/ImageAiTagReviewSheet.kt"
FOLDER_SHEET="$IMG/ImageFolderTagReviewSheet.kt"
VM="$IMG/ImageManagerViewModel.kt"

echo "── AI 검토 시트 유료 응답 보존 검사 (B-136 · B-140) ──"

# 여는 줄부터 **같은 들여쓰기의 닫는 중괄호**까지를 한 블록으로 뜬다.
# 주석 줄은 빼고 본다 — 이 저장소는 *옛 조건을 고친 자리에 적어 두는* 관행이 있어
# (무엇이 왜 틀렸는지를 남긴다) 주석을 세면 바르게 고친 코드가 위반으로 걸린다.
block_after() {
  awk -v pat="$2" '
    $0 ~ pat { inblk = 1 }
    inblk {
      line = $0
      sub(/^[[:space:]]*/, "", line)
      if (line !~ /^(\/\/|\*|\/\*)/) print
    }
    inblk && /^[[:space:]]*\}[[:space:]]*$/ { inblk = 0 }
  ' "$1"
}

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 블록을 못 뜨면 위반이 있어도 "위반 없음"과 구별되지 않는다(B-146이 겪은 그 부류).
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
        retryButton.setOnClickListener {
            val paths = retryPaths
            dismiss()
            onRetryOneByOne(paths)
        }
        applyButton.setOnClickListener {
            dismiss()
        }
EOF
selftest_hits=$(block_after "$SELFTEST" 'retryButton.*setOnClickListener' | grep -c 'dismiss()' || true)
rm -f "$SELFTEST"
if [ "$selftest_hits" -ne 1 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 되받기 블록의 dismiss 1건 중 ${selftest_hits}건을 잡았습니다" >&2
  echo "      (0이면 블록을 못 뜬 것이고, 2 이상이면 옆 블록까지 삼킨 것이다)" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (되받기 블록만 떠서 그 안의 dismiss를 잡는다)"

fail=0

# ── ① 되받기가 앞 실행의 결과를 들고 가는가 ──
if [ ! -f "$FRAGMENT" ]; then
  echo "  ✗ 등재된 화면이 없습니다: $FRAGMENT (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
  fail=1
else
  retry_wiring=$(block_after "$FRAGMENT" 'onRetryOneByOne[[:space:]]*=')
  if [ -z "$retry_wiring" ]; then
    echo "  ✗ 되받기 배선을 찾지 못했습니다: $FRAGMENT (onRetryOneByOne =)"
    fail=1
  elif ! printf '%s\n' "$retry_wiring" | grep -q 'carryOver'; then
    echo "  ✗ 되받기가 앞 실행의 결과를 들고 가지 않습니다: $FRAGMENT"
    echo "      → 되받기는 접힌 배치만 다시 부른다. 앞의 성공분을 넘기지 않으면 이미 결제한"
    echo "        제안이 소멸하고, 되받으려면 성공한 요청까지 다시 결제해야 한다(B-140)."
    fail=1
  fi
fi

# ── ② 되받기 핸들러가 넘기기 전에 시트를 닫는가 ──
if [ ! -f "$SHEET" ]; then
  echo "  ✗ 등재된 시트가 없습니다: $SHEET (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
  fail=1
else
  retry_block=$(block_after "$SHEET" 'retryButton.*setOnClickListener')
  if [ -z "$retry_block" ]; then
    echo "  ✗ 되받기 핸들러를 찾지 못했습니다: $SHEET (retryButton ... setOnClickListener)"
    fail=1
  elif printf '%s\n' "$retry_block" | grep -q 'dismiss()'; then
    echo "  ✗ 되받기가 시트를 닫고 넘깁니다: $SHEET"
    echo "      → 닫으면 시트가 들고 있던 제안이 함께 사라진다. 되받은 결과는 앞의 성공분 위에"
    echo "        얹혀 이 시트로 돌아오므로 **닫지 않는다**(B-140)."
    fail=1
  fi
fi

# ── ③ 검토 시트가 검토 상태를 회전에 넘기는가 ──
# 제안은 ViewModel이 들지만 **어느 칩을 껐는지는 시트밖에 모른다.** 이것이 없으면 회전 뒤
# 제안만 되살아나고 수십 장을 훑어 지운 일이 통째로 되돌아간다 — 화면은 멀쩡해 보인다.
for sheet in "$SHEET" "$FOLDER_SHEET"; do
  if [ ! -f "$sheet" ]; then
    echo "  ✗ 등재된 시트가 없습니다: $sheet"
    fail=1
  elif ! grep -q 'override fun onSaveInstanceState' "$sheet"; then
    echo "  ✗ 검토 상태가 회전을 넘지 못합니다: $sheet"
    echo "      → onSaveInstanceState로 체크 상태를 지킬 것. 제안만 되살리면 사용자가 훑어"
    echo "        지운 일이 조용히 되돌아간다(B-136)."
    fail=1
  fi
done

# ── ④ 유료 응답의 실행 엔진이 뷰에 열려 있는가 ──
# 열려 있으면 다음 사람이 뷰에서 직접 불러 결과를 지역 변수에 담는다 — B-136이 난 경로 그대로다.
if [ ! -f "$VM" ]; then
  echo "  ✗ 등재된 ViewModel이 없습니다: $VM"
  fail=1
else
  for engine in suggestImageTags suggestFolderTags; do
    decl=$(grep -nE "fun +$engine\b" "$VM" || true)
    if [ -z "$decl" ]; then
      echo "  ✗ 실행 엔진을 찾지 못했습니다: $VM ($engine)"
      fail=1
    elif ! printf '%s\n' "$decl" | grep -q 'private'; then
      echo "  ✗ 유료 응답의 실행 엔진이 뷰에 열려 있습니다: $VM ($engine)"
      echo "      → 입구는 결과를 LiveData에 보관하는 run* 하나여야 한다. 엔진을 직접 열면"
      echo "        결과가 뷰의 지역 변수로 흘러 회전에 사라진다(B-136)."
      fail=1
    fi
  done
  for holder in aiTagResult folderTagResult; do
    if ! grep -q "val $holder" "$VM"; then
      echo "  ✗ 유료 응답을 보관하는 자리가 없습니다: $VM ($holder)"
      fail=1
    fi
  done
fi

if [ "$fail" -eq 0 ]; then
  echo "  ✓ 되받기가 앞의 성공분을 들고 간다 (B-140)"
  echo "  ✓ 유료 응답과 검토 상태가 회전을 넘는다 (B-136)"
  echo ""
  echo "AI 검토 시트 유료 응답 보존 검사 통과"
  exit 0
fi
echo ""
echo "AI 검토 시트 유료 응답 보존 검사 실패" >&2
exit 1
