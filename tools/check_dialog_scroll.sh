#!/bin/bash
# 다이얼로그 본문 스크롤에 높이 상한이 있는지 검사한다 (백로그 B-91의 재발 방지 · 규약 R-30).
#
# 배경: `ScrollView(ctx).apply { addView(list) }`만 쓰면 높이 제약이 없어 다이얼로그가
# 화면 밖으로 밀려나고, 긴 목록이 **잘린 채 끝까지 스크롤되지 않는다**(실기기 보고 2026.08.01 —
# AI 추천 검토 창). 그때 두 곳만 고치고 나머지는 "확인 없이 여덟 곳을 바꾸는 쪽이 더 위험하다"고
# 남겨 둔 것이 B-91이며, 2026.08.02에 전수로 닫았다. 이 검사는 **다음 자리가 다시 생기는 것**을 막는다.
#
# 올바른 형태: `util/AlertDialogExt.kt`의 `cappedScrollView(context)` — 내용이 짧으면 그만큼만
# 차지하고(작은 창 유지), 화면을 넘으면 상한에서 멈춘 뒤 안에서 스크롤된다.
#
# 면제: 다이얼로그 본문이 아니거나 다른 방법으로 이미 높이가 묶인 자리는 그 줄이나 바로 위
# 세 줄에 `scroll-cap-exempt: <이유>`를 적으면 통과한다. **줄 번호 목록을 쓰지 않는 것은
# 일부러다** — 목록은 코드가 움직이는 순간 낡지만 마커는 코드를 따라 움직인다.
#
# 범위: 코드로 만든 `ScrollView`만 본다. **레이아웃 XML 루트가 ScrollView인 다이얼로그
# 12곳은 아직 상한이 없다**(백로그 B-98) — 그쪽은 XML에서 쓸 이름 있는 커스텀 뷰가 필요해
# 별건이며, 처리되면 이 검사에 XML 축을 더할 것.
#
# 사용법: tools/check_dialog_scroll.sh
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"
SRC=app/src/main/java
DEF=$SRC/com/novelcharacter/app/util/AlertDialogExt.kt

echo "── 다이얼로그 스크롤 상한 검사 (백로그 B-91 · 규약 R-30) ──"

FAIL=0
while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  file=${hit%%:*}
  rest=${hit#*:}
  line=${rest%%:*}
  # 마커는 같은 줄 또는 바로 위 세 줄(주석 자리)에 있으면 인정한다.
  from=$(( line > 3 ? line - 3 : 1 ))
  if sed -n "${from},${line}p" "$file" | grep -q "scroll-cap-exempt"; then
    continue
  fi
  if [ "$FAIL" -eq 0 ]; then
    echo "  ✗ 높이 상한 없는 ScrollView — 긴 내용이 잘리고 끝까지 스크롤되지 않습니다"
    FAIL=1
  fi
  echo "      $hit"
done <<EOF
$(grep -rn "ScrollView(" "$SRC" --include=*.kt | grep -v "cappedScrollView(" | grep -v "^$DEF:" || true)
EOF

if [ "$FAIL" -ne 0 ]; then
  echo ""
  echo "  고치는 법: util/AlertDialogExt.kt의 cappedScrollView(context)를 쓸 것"
  echo "             (본문이 아니거나 이미 높이가 묶인 자리면 scroll-cap-exempt: <이유>를 적을 것)"
  exit 1
fi

echo "  ✓ 코드로 만든 다이얼로그 스크롤 전부 상한 있음(또는 사유 명시)"
echo ""
echo "다이얼로그 스크롤 상한 검사 통과"
