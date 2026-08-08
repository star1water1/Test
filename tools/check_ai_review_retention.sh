#!/bin/bash
# AI 검토 시트의 유료 응답 보존·고지 검사 (B-136 · B-140 · B-163 · B-144)
#
# 배경: 이미지 일괄 AI 태깅의 검토 시트는 **이미 결제한 응답**을 들고 있다. 그런데 세 자리에서
# 그것을 조용히 버리고 있었다.
#   ① 회전(B-136) — 제안이 Fragment의 지역 변수에만 살아, 재생성된 시트는 빈 껍데기였다.
#   ② 되받기(B-140) — '1장씩 다시 보내기'가 시트를 **먼저 닫고** 앞 실행의 성공분을 실어 나를
#      자리 없이 재실행해, 25장을 5요청에 돌려 1요청만 접혔을 때 20장분 제안이 소멸했다.
#      되받으려면 이미 성공한 4요청을 **다시 결제**해야 했다.
#   ③ 적용 실패(B-163) — 시트가 [적용]에 곧바로 닫히는데 호출측이 그 자리에서 결과를 비워,
#      트랜잭션이 깨졌을 때 되돌아가 다시 적용할 자리가 없었다. 다시 얻으려면 재결제다.
#
# 고장이 조용한 것이 이 검사를 다는 이유다: 아무것도 실패하지 않고, 제안이 줄어든 화면은
# *"AI가 그만큼만 답했다"*와 구별되지 않는다. 사용자는 잃은 줄도 모른다.
#
# **넷째 자리는 응답이 아니라 실행 자체가 조용히 사라진 것이다**(B-144) — 제안도 고지도
# 없으면 화면이 시트를 열지 않고 그대로 빠져, 비용을 확인받고 돌린 유료 실행이 **아무 흔적도
# 남기지 않은 채** 끝났다. 잃은 것이 응답이 아니라 *결과를 알 기회*라 위 셋과 축이 다르지만,
# 사용자가 보는 증상은 같다: 눌렀는데 아무 일도 일어나지 않는다.
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
# ⑤ 필수: **적용이 실패하면 유료 응답을 되살린다**(B-163) — 되살리기가 ViewModel에 있는가와
#         호출측(뷰)이 누른 자리에서 비우지 않는가를 **함께** 본다. 앞의 것만 보면
#         빈 값을 되살리며 통과한다.
# ⑥ 필수: **제안도 고지도 없는 실행이 말없이 빠지지 않는다**(B-144) — 시트를 열지 않고
#         `return`하는 그 블록이 사용자에게 한 줄이라도 말하는가.
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
# 폴더판의 시트를 **띄우고 콜백을 붙이는 쪽**은 Fragment가 아니라 컨트롤러다(⑤가 본다).
FOLDER_SHEET_CALLER="$IMG/OrganizeFolderController.kt"
VM="$IMG/ImageManagerViewModel.kt"

echo "── AI 검토 시트 유료 응답 보존·고지 검사 (B-136 · B-140 · B-163 · B-144) ──"

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

# 여는 줄의 **들여쓰기와 같은 깊이**의 닫는 중괄호까지를 한 블록으로 뜬다.
#
# `block_after`로는 함수 본문을 못 뜬다 — 그쪽은 들여쓰기를 보지 않아 `}`만 있는 **첫** 줄에서
# 끊긴다. 짧은 리스너 블록에는 맞지만 중첩이 깊은 함수에서는 안쪽 람다가 닫히는 자리에서
# 잘린다. **이 검사를 세우며 실제로 그렇게 잘렸다** — `applyImageTags`의 `fresh.map { … }`가
# 닫히는 줄에서 끝나 그 아래 되살리기를 못 보고 **위반 없는 코드를 위반이라 말했다.**
indented_block() {
  awk -v pat="$2" '
    !inblk && $0 ~ pat {
      inblk = 1
      match($0, /^[[:space:]]*/)
      close_re = "^" substr($0, 1, RLENGTH) "\\}[[:space:]]*$"
      print
      next
    }
    inblk {
      line = $0
      sub(/^[[:space:]]*/, "", line)
      if (line !~ /^(\/\/|\*|\/\*)/) print
      if ($0 ~ close_re) inblk = 0
    }
  ' "$1"
}

# ── 탐지기 자기 시험 2 — 깊은 중첩을 넘고, 옆 함수는 삼키지 않는가 ──
SELFTEST2=$(mktemp)
cat > "$SELFTEST2" <<'EOF'
    fun applyThing() {
        launch {
            val x = listOf(1).map {
                it + 1
            }
            target()
        }
    }

    fun other() {
        target()
    }
EOF
st2=$(indented_block "$SELFTEST2" 'fun +applyThing' | grep -c 'target()' || true)
rm -f "$SELFTEST2"
if [ "$st2" -ne 1 ]; then
  echo "  ✗ 탐지기 자기 시험 2 실패 — 함수 블록의 target 1건 중 ${st2}건을 잡았습니다" >&2
  echo "      (0이면 안쪽 람다가 닫히는 자리에서 잘린 것이고, 2면 옆 함수까지 삼킨 것이다)" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 2 통과 (안쪽 람다를 넘고 옆 함수 앞에서 멈춘다)"

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

# ── ⑤ 적용이 실패했을 때 유료 응답을 되살리는가 (B-163) ──
# 검토 시트는 [적용]을 누르면 결과를 기다리지 않고 곧바로 닫힌다. 그래서 호출측이 **누른 시점에 비우면**
# 트랜잭션이 깨졌을 때 되돌아가 다시 적용할 자리가 없고, 다시 얻으려면 재결제다 —
# ①②③이 막은 회전·되받기와 **같은 손실의 셋째 자리**다.
# 소비를 결과를 아는 곳(ViewModel)이 들어야 하는 이유는 그것만이 아니다: 되살리기가 뷰에 있으면
# **적용 중에 회전이 나면 되살리는 코드 자체가 사라진다.**
# 그래서 둘을 함께 본다 — 적용 함수 안에 되살리기가 있는가 · 호출측(뷰)이 누른 자리에서 비우지 않는가.
for apply_fn in applyImageTags applyFolderTags; do
  apply_block=$(indented_block "$VM" "fun +$apply_fn")
  if [ -z "$apply_block" ]; then
    echo "  ✗ 적용 함수를 찾지 못했습니다: $VM ($apply_fn)"
    fail=1
  elif ! printf '%s\n' "$apply_block" | grep -qE '(aiTagResult|folderTagResult)\.value = pending'; then
    echo "  ✗ 적용이 실패해도 유료 응답을 되살리지 않습니다: $VM ($apply_fn)"
    echo "      → 시트는 [적용]을 누르면 곧바로 닫힌다. 실패를 알았을 때 되살릴 것이 없으면"
    echo "        이미 결제한 제안이 소멸하고 다시 얻으려면 재결제다(B-163)."
    fail=1
  fi
done
# 호출측이 누른 자리에서 비우면 위 되살리기는 빈 값을 되살린다 — 통과하면서 아무 일도 안 한다.
for caller in "$FRAGMENT" "$FOLDER_SHEET_CALLER"; do
  [ -f "$caller" ] || continue
  apply_wiring=$(block_after "$caller" 'onApply[[:space:]]*=')
  if [ -z "$apply_wiring" ]; then
    echo "  ✗ 적용 배선을 찾지 못했습니다: $caller (onApply =)"
    fail=1
  elif printf '%s\n' "$apply_wiring" | grep -qE 'clear(AiTag|FolderTag)Result\(\)'; then
    echo "  ✗ 누른 시점에 유료 응답을 비웁니다: $caller"
    echo "      → 소비는 결과를 아는 곳(ViewModel)이 **성공했을 때만** 한다. 여기서 비우면"
    echo "        되살릴 것이 남지 않는다(B-163)."
    fail=1
  fi
done

# ── ⑥ 제안도 고지도 없는 실행이 말없이 빠지는가 (B-144) ──
# 이 조합은 실패가 아니라 정상 경로다 — 프롬프트가 *"근거를 찾을 수 없는 이미지는 빈 배열로
# 둔다"*고 시키고 빈 배열은 어디에도 세지 않으므로, 모델이 전부 그렇게 답하면 제안도 고지도
# 없이 끝난다. 그런데 **비용을 고지하고 확인받아 단독 실행한 유료 동작**이라, 그 자리에서
# 화면이 침묵하면 사용자가 보는 것은 아무 변화도 없는 화면뿐이다(고장과 구분되지 않는다 — R-17).
#
# **폴더판은 여기서 침묵하고 그것이 옳다** — 폴더 받아오기에 딸린 곁가지라 빈 창이 소음이다.
# 그래서 이 항목은 배치판만 본다. 형제를 함께 걸면 옳은 코드를 위반이라 말한다.
empty_path_notifies() {   # $1: 파일 — 말하면 0, 침묵하면 1, 블록을 못 뜨면 2
  local blk
  blk=$(indented_block "$1" 'suggestions[.]isEmpty[(][)] && notices[.]isEmpty[(][)]')
  [ -n "$blk" ] || return 2
  printf '%s\n' "$blk" | grep -qE 'notify[A-Za-z]*\(|Toast[.]makeText' && return 0
  return 1
}

# ── 탐지기 자기 시험 3 — 침묵을 잡고, 말하는 코드는 잡지 않는가 ──
# 한쪽만 재면 **아무것도 안 잡는 검사**가 통과로 보인다(B-146이 겪은 부류).
SELFTEST3=$(mktemp)
cat > "$SELFTEST3" <<'EOF'
        if (result.suggestions.isEmpty() && notices.isEmpty()) {
            viewModel.clearAiTagResult()
            return
        }
EOF
empty_path_notifies "$SELFTEST3"; st3_silent=$?
cat > "$SELFTEST3" <<'EOF'
        if (result.suggestions.isEmpty() && notices.isEmpty()) {
            notifyError(getString(R.string.image_ai_tag_nothing))
            viewModel.clearAiTagResult()
            return
        }
EOF
empty_path_notifies "$SELFTEST3"; st3_spoken=$?
rm -f "$SELFTEST3"
if [ "$st3_silent" -ne 1 ] || [ "$st3_spoken" -ne 0 ]; then
  echo "  ✗ 탐지기 자기 시험 3 실패 — 침묵=$st3_silent(1이어야 한다) · 말함=$st3_spoken(0이어야 한다)" >&2
  echo "      (침묵이 1이 아니면 이 항목은 아무것도 잡지 못하고, 말함이 0이 아니면 옳은 코드를 잡는다)" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 3 통과 (빈 결과의 침묵을 잡고 한 줄 말하는 코드는 통과시킨다)"

if [ -f "$FRAGMENT" ]; then
  empty_path_notifies "$FRAGMENT"; rc=$?
  if [ "$rc" -eq 2 ]; then
    echo "  ✗ 빈 결과 경로를 찾지 못했습니다: $FRAGMENT (suggestions.isEmpty() && notices.isEmpty())"
    echo "      → 조건의 모양이 바뀌었으면 이 검사를 함께 고칠 것. 못 찾은 채 통과하면"
    echo "        *위반 없음*과 구별되지 않는다."
    fail=1
  elif [ "$rc" -eq 1 ]; then
    echo "  ✗ 제안도 고지도 없는 실행이 말없이 빠집니다: $FRAGMENT"
    echo "      → 비용을 확인받고 돌린 유료 실행이다. 시트를 열지 않는 것은 옳으나(고를 것이"
    echo "        없는 창은 소음이다) 한 줄은 말해야 한다 — 침묵은 고장과 구분되지 않는다(B-144)."
    fail=1
  fi
fi

if [ "$fail" -eq 0 ]; then
  echo "  ✓ 되받기가 앞의 성공분을 들고 간다 (B-140)"
  echo "  ✓ 유료 응답과 검토 상태가 회전을 넘는다 (B-136)"
  echo "  ✓ 적용이 실패하면 유료 응답을 되살린다 (B-163)"
  echo "  ✓ 빈 결과로 끝난 유료 실행도 말한다 (B-144)"
  echo ""
  echo "AI 검토 시트 유료 응답 보존·고지 검사 통과"
  exit 0
fi
echo ""
echo "AI 검토 시트 유료 응답 보존·고지 검사 실패" >&2
exit 1
