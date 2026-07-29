#!/bin/bash
# 다이얼로그 검증 실패가 입력을 유실하지 않는지 검사한다 (백로그 B-28의 재발 방지).
#
# 배경: `setPositiveButton(text) { ... }`의 람다는 **무조건 닫힌다.** 그 안에서 검증하고
# `return@setPositiveButton`으로 빠져나오면, 토스트는 뜨지만 창은 이미 닫히는 중이라
# 사용자가 적어 둔 나머지 입력이 통째로 사라진다. S-12가 관계변화 다이얼로그 2곳에서 고쳤는데
# 같은 형태가 여섯 곳 더 남아 있었다(B-28) — 사람 리뷰로는 반복해서 놓치는 자리다.
#
# 올바른 형태: `.setPositiveButton(text, null)` + `.create()` 뒤에
#   `dialog.setValidatedPositiveButton { ... false /* 실패: 창 유지 */ ... true /* 성공 */ }`
# 그리고 실패는 `showInlineError`로 고칠 칸에 붙인다(util/AlertDialogExt.kt가 단일 소스).
#
# 사용법: tools/check_dialog_validation.sh
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"
SRC=app/src/main/java

echo "── 다이얼로그 검증 검사 (백로그 B-28) ──"

HITS=$(grep -rn "return@setPositiveButton" "$SRC" --include=*.kt || true)
COUNT=$(printf '%s' "$HITS" | grep -c . || true)

if [ "$COUNT" -ne 0 ]; then
  echo "  ✗ 자동 닫힘 버튼 안에서 조기 return — 검증 실패 시 입력이 유실됩니다 ($COUNT건)"
  printf '%s\n' "$HITS" | sed 's/^/      /'
  echo ""
  echo "  고치는 법: setPositiveButton(text, null) + create() 뒤"
  echo "             setValidatedPositiveButton { ... } (실패 시 false 반환 → 창 유지)"
  echo "             실패 문구는 showInlineError로 해당 입력 칸에 붙일 것"
  exit 1
fi

echo "  ✓ 자동 닫힘 버튼 안의 조기 return 없음"
echo ""
echo "다이얼로그 검증 검사 통과"
