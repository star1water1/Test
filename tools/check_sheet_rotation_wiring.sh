#!/bin/bash
# 🔄 창(DialogFragment)의 회전 유실 배선 검사 (R-41-a · R-65 · B-201 · B-260)
#
# ## 무엇이 유실되는가
#
# 이 앱의 창은 대개 호스트가 인스턴스에 맨 `var`(콜백·로더·데이터)를 꽂아 쓴다. 회전하면
# FragmentManager가 창을 **기본 생성자로 다시 세우고** 호스트는 다시 꽂지 않는다 — 창은
# 빈 목록으로 **멀쩡히 떠 있고** [적용]·[저장]은 아무 데도 닿지 않는다. 눌러도 아무 일이
# 없으니 **고장과 구분되지 않고**, 사용자는 반영됐다고 읽는다(말없는 유실 — 개발 의도 2번).
#
# ## 왜 검사가 필요한가 — **이 배선은 어떤 자동 검증도 못 본다**
#
# 회전은 실기기에서만 보인다. 순수 JVM 시험은 화면 계층에 닿지 못하고, 실클래스패스 프로브와
# 뷰 프로브는 컴파일만 본다. **꽂기를 그만둔 코드는 완벽하게 컴파일된다.**
# 그리고 이 부류는 네 번 났다: B-201(실루엣 편집기) · B-260(축·프리셋 편집 창) ·
# 2026.08.20(FieldEditDialog 세 호출부) · 2026.08.22(목록 컨트롤·검색 필터 시트).
# **같은 부류가 두 번 넘게 난 자리**라 기계를 세운다(검사 동결 문턱).
#
# ## 무엇을 보는가
#
# 창이 **주입 `var`**(공개 `var` — 람다·로더·데이터)를 들고 있으면, 아래 셋 중 하나여야 한다.
#   ⓐ **꽂을 것이 없다** — 주입 `var`가 아예 없다(재료는 인자 번들·자기 뷰모델, 처분은
#      `setFragmentResult`). R-65가 가장 안전하다고 적은 길이고, 이 저장소의 최신 관행이다.
#   ⓑ **안전 종료** — 주입이 비었으면 `dismissAllowingStateLoss()`로 닫는다. 창이 드는 것이
#      *현재 상태의 사본*뿐일 때 쓴다(사용자가 그 자리에서 만든 작업물이 있으면 못 쓴다).
#   ⓒ **다시 꽂기** — 호스트가 `findFragmentByTag(<창>.TAG)`로 찾아 다시 꽂는다. 창이 받는
#      것이 많아 결과 API로 못 옮길 때 쓴다(R-41-a).
#
# **셋 다 아니면 위반이다.** 어느 것을 골랐는지는 각 창이 제 KDoc에 적는다.
#
# ## fail-closed
#
# 창을 하나도 못 찾으면(선언 꼴이 바뀌었다) **그 자체가 위반이다** — 아무것도 안 보면서
# 초록인 검사는 없는 것만 못하다(`check_generation_edit_rotation.sh` ④가 세운 관행).
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app"
FAIL=0
SEEN=0

echo "── 🔄 창 회전 배선 검사 ──"

for f in $(grep -rlE "^(class|internal class) [A-Za-z]+ : (BottomSheet)?DialogFragment" "$SRC" --include=*.kt | sort); do
  name=$(grep -oE "^(class|internal class) [A-Za-z]+ : (BottomSheet)?DialogFragment" "$f" | head -1 | awk '{print $2}')
  [ -z "$name" ] && continue
  SEEN=$((SEEN + 1))

  # 주입 var — 공개 `var`만 본다(private var는 창 자신의 상태다).
  inject=$(grep -cE "^    var [a-zA-Z_][a-zA-Z0-9_]*\s*:" "$f")
  [ "$inject" -eq 0 ] && continue

  # ⓑ 안전 종료
  if grep -q "dismissAllowingStateLoss()" "$f"; then continue; fi

  # ⓒ 호스트가 다시 꽂는다
  if grep -rq "findFragmentByTag($name.TAG)" "$SRC" --include=*.kt; then continue; fi
  if grep -rq "findFragmentByTag(\"$name\")" "$SRC" --include=*.kt; then continue; fi

  echo "  ✗ $name — 주입 var ${inject}개인데 회전 처분이 없다 (${f#"$SRC"/})"
  echo "     → ⓐ 주입을 걷고 인자·자기 뷰모델·setFragmentResult로 (R-65)"
  echo "     → ⓑ 주입이 비면 dismissAllowingStateLoss() (R-41-a)"
  echo "     → ⓒ 호스트가 findFragmentByTag($name.TAG)로 다시 꽂기"
  FAIL=1
done

if [ "$SEEN" -eq 0 ]; then
  echo "  ✗ 창을 하나도 못 찾았다 — 선언 꼴이 바뀌었거나 정규식이 낡았다(fail-closed)"
  exit 1
fi

if [ "$FAIL" -eq 0 ]; then
  echo "  ✓ 창 $SEEN개 — 주입을 든 창은 전부 회전 처분이 있다"
  exit 0
fi
exit 1
