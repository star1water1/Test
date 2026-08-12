#!/bin/bash
# 다이얼로그 본문 스크롤에 높이 상한이 있는지 검사한다 (백로그 B-91의 재발 방지 · 규약 R-31).
#
# 배경: `ScrollView(ctx).apply { addView(list) }`만 쓰면 높이 제약이 없어 다이얼로그가
# 화면 밖으로 밀려나고, 긴 목록이 **잘린 채 끝까지 스크롤되지 않는다**(실기기 보고 2026.08.01 —
# AI 추천 검토 창). 그때 두 곳만 고치고 나머지는 "확인 없이 여덟 곳을 바꾸는 쪽이 더 위험하다"고
# 남겨 둔 것이 B-91이며, 2026.08.02에 전수로 닫았다. 이 검사는 **다음 자리가 다시 생기는 것**을 막는다.
#
# 올바른 형태: `util/AlertDialogExt.kt`의 `cappedScrollView(context)` — 내용이 짧으면 그만큼만
# 차지하고(작은 창 유지), 화면을 넘으면 상한에서 멈춘 뒤 안에서 스크롤된다.
# 구현은 `ui/common/CappedScrollView`이고 측정 규칙은 `util/DialogScrollCap` 한 벌이다.
#
# 면제: 다이얼로그 본문이 아니거나 다른 방법으로 이미 높이가 묶인 자리는 그 줄이나 바로 위
# 세 줄에 `scroll-cap-exempt: <이유>`를 적으면 통과한다. **줄 번호 목록을 쓰지 않는 것은
# 일부러다** — 목록은 코드가 움직이는 순간 낡지만 마커는 코드를 따라 움직인다.
#
# 범위: 축이 둘이다. ① 코드로 만든 `ScrollView` ② **레이아웃 XML 루트**(B-98, 2026.08.03).
# XML은 익명 객체를 세울 수 없어 이름 있는 커스텀 뷰가 필요했고, 그것이
# `ui/common/CappedScrollView`(중첩 스크롤판은 `CappedNestedScrollView`)다.
# **바텀시트(`bottom_sheet_*`·`sheet_*`)와 전체 화면(`fragment_*`)은 대상이 아니다** —
# 앞은 시트 자체가 높이를 잡고 뒤는 화면이 잡는다.
#
# 사용법: tools/check_dialog_scroll.sh
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"
SRC=app/src/main/java
DEF=$SRC/com/novelcharacter/app/util/AlertDialogExt.kt
# 상한을 **구현하는** 파일은 스스로 상위 타입을 상속하므로 검사에서 뺀다(B-98).
IMPL=$SRC/com/novelcharacter/app/ui/common/CappedScrollView.kt

echo "── 다이얼로그 스크롤 상한 검사 (백로그 B-91 · 규약 R-31) ──"

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
$(grep -rn "ScrollView(" "$SRC" --include=*.kt | grep -v "cappedScrollView(" | grep -v "^$DEF:" | grep -v "^$IMPL:" || true)
EOF

if [ "$FAIL" -ne 0 ]; then
  echo ""
  echo "  고치는 법: util/AlertDialogExt.kt의 cappedScrollView(context)를 쓸 것"
  echo "             (본문이 아니거나 이미 높이가 묶인 자리면 scroll-cap-exempt: <이유>를 적을 것)"
  exit 1
fi

echo "  ✓ 코드로 만든 다이얼로그 스크롤 전부 상한 있음(또는 사유 명시)"

# ── XML 축 (B-98) ── 루트가 맨 ScrollView/NestedScrollView인 다이얼로그 레이아웃을 잡는다.
# 파일 목록을 여기 적지 않는 것은 일부러다 — 적으면 레이아웃이 늘 때 낡는다.
LAYOUT=app/src/main/res/layout
XFAIL=0
for f in "$LAYOUT"/dialog_*.xml; do
  [ -e "$f" ] || continue
  root=$(grep -m1 -o '^<[A-Za-z][A-Za-z0-9_.]*' "$f" | tail -c +2)
  case "$root" in
    ScrollView|androidx.core.widget.NestedScrollView) ;;
    *) continue ;;
  esac
  # 마커는 파일 앞부분(루트 태그 위 주석 자리)에 있으면 인정한다.
  if head -12 "$f" | grep -q "scroll-cap-exempt"; then
    continue
  fi
  if [ "$XFAIL" -eq 0 ]; then
    echo "  ✗ 높이 상한 없는 XML 루트 스크롤 — 긴 폼이 잘리고 끝까지 스크롤되지 않습니다"
    XFAIL=1
  fi
  echo "      $f  (루트: $root)"
done

if [ "$XFAIL" -ne 0 ]; then
  echo ""
  echo "  고치는 법: 루트를 com.novelcharacter.app.ui.common.CappedScrollView 로 바꿀 것"
  echo "             (안에 RecyclerView가 있어 중첩 스크롤이 필요하면 CappedNestedScrollView)"
  exit 1
fi

echo "  ✓ 다이얼로그 레이아웃 XML 루트 전부 상한 있음(또는 사유 명시)"

# ── 긴 창인데 **스크롤이 아예 없는** 축 (B-171, 2026.08.12) ──
#
# 위 두 축은 *스크롤이 있는데 상한이 없는 것*만 본다. **스크롤이 없는 창은 원리적으로 못 본다** —
# 그런데 그쪽이 더 나쁘다: 상한 없는 스크롤은 잘린 채로도 일부는 움직이지만, 스크롤이 없으면
# 화면을 넘긴 부분에 **닿을 방법이 아예 없다**(저장 버튼이 그 아래면 저장을 못 한다).
#
# 실증: `dialog_duel_axis_edit.xml`이 뷰 38개를 스크롤 없이 세우고 있었고(이름·겨루는 대상·
# 기준 축·연결 셋·후보 필터·경고 셋), 이 검사가 두 축 모두에서 초록이었다. B-91·B-98이
# 닫았다고 믿은 결함이 **검사가 안 보는 모양으로** 그대로 살아 있던 자리다.
#
# **금은 오늘의 실측이 정한다**(값을 지어내지 않는다): 상한을 가진 창의 최소가 뷰 11개이고,
# 상한 없는 창의 최대가 8개(그것도 자기 스크롤 목록을 든 창)다. 그 사이가 자연스러운 금이라
# 10을 넘기면 묻는다. 자기 스크롤 목록만으로 본문이 끝나는 창은 마커로 사유를 적으면 된다 —
# 목록을 여기서 자동 예외로 두면 *목록 + 긴 폼*인 창이 조용히 빠진다.
MAX_VIEWS_WITHOUT_SCROLL=10
LFAIL=0
for f in "$LAYOUT"/dialog_*.xml; do
  [ -e "$f" ] || continue
  root=$(grep -m1 -o '^<[A-Za-z][A-Za-z0-9_.]*' "$f" | tail -c +2)
  case "$root" in
    com.novelcharacter.app.ui.common.Capped*) continue ;;
  esac
  head -12 "$f" | grep -q "scroll-cap-exempt" && continue
  views=$(grep -cE '^[[:space:]]*<[A-Za-z]' "$f")
  [ "$views" -le "$MAX_VIEWS_WITHOUT_SCROLL" ] && continue
  if [ "$LFAIL" -eq 0 ]; then
    echo "  ✗ 스크롤이 없는 긴 다이얼로그 — 화면을 넘긴 부분에 닿을 방법이 없습니다"
    LFAIL=1
  fi
  echo "      $f  (루트: $root · 뷰 ${views}개 > $MAX_VIEWS_WITHOUT_SCROLL)"
done

if [ "$LFAIL" -ne 0 ]; then
  echo ""
  echo "  고치는 법: 루트를 com.novelcharacter.app.ui.common.CappedScrollView 로 감쌀 것"
  echo "             (본문이 자기 스크롤 목록 하나로 끝나면 scroll-cap-exempt: <이유>를 적을 것)"
  exit 1
fi

echo "  ✓ 상한 없는 다이얼로그는 전부 짧음(또는 사유 명시)"
echo ""
echo "다이얼로그 스크롤 상한 검사 통과"
