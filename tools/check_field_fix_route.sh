#!/bin/bash
# 값을 고치러 가는 길의 인자 짝 검사 (B-198에서 신설 · R-61)
#
# 무엇을 보는가 — **축 셋**:
#   ① 초점 인자 이름이 `util/FieldValueFixRoute.kt` 밖에서 **생문자열로** 나타나는가
#   ② 그 이름이 `nav_graph.xml`에 인자로 선언돼 있는가
#   ③ 그 이름을 **읽는 파일이 지우기도 하는가**(한 번 쓰고 지운다)
#
# ## 왜 있는가 — **이 부류의 실패는 침묵이다**
#
# 보내는 쪽과 받는 쪽이 이름을 다르게 쓰면 **오류도 고지도 없이 아무 일이 일어나지 않는다.**
# 누른 사람은 화면이 그냥 뜬 줄 알고 자기가 고치려던 칸을 손으로 다시 찾는다 — 기능이
# 죽었는데 죽은 것처럼 보이지 않는다. 컴파일도 순수 시험도 이 자리를 못 본다:
# 인자는 문자열 키라 타입이 없고, 짝은 **다른 파일 다른 계층**에 있다.
#
# ③이 축인 이유는 따로 있다. 인자를 지우지 않으면 **회전할 때마다 창이 다시 열려**
# 사용자가 그사이 하던 일을 화면 재생성이 덮는다(같은 처방을 `ImageManagerFragment`가
# `ARG_LINK_FILTER`에 쓴다). 이것도 오류가 아니라 *이상한 동작*이라 어떤 빨간불도 안 뜬다.
#
# ②는 값이 아니라 **선언**을 지킨다 — 그래프에 없는 인자는 기본값이 없어, 인자 없이 들어온
# 화면과 0을 실어 들어온 화면을 코드가 같은 자리에서 다르게 다루게 된다.
#
# ## 무엇을 일부러 안 보는가 — `ARG_CHARACTER_ID`
#
# 대상 인자 넷 중 `characterId`만 검사에서 뺀다. **그 이름은 이 판이 정한 것이 아니라
# 캐릭터 상세가 이미 쓰던 것**이고, 저장소 곳곳이 생문자열로 싣는다(대시보드·검색·그래프 …).
# 그 전부를 위반으로 잡으면 이 검사는 첫날부터 빨간불이고, 빨간불인 검사는 꺼진다.
# **`ARG_FOCUS_*`만 보는 것이 요점**이다 — 이 판이 새로 만든 이름이라 처음부터 0이고,
# 0인 채로 얼어 있으면 새로 드는 이름도 같은 규칙을 받는다.
#
# 사용법: tools/check_field_fix_route.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app/util/FieldValueFixRoute.kt"
NAV="app/src/main/res/navigation/nav_graph.xml"
KT_ROOT="app/src/main/java"

echo "── 값 고치기 경로 인자 검사 (B-198) ──"
fail=0

if [ ! -f "$SRC" ]; then
    echo "  ✗ 단일 소스가 없다: $SRC"
    exit 1
fi

# 단일 소스에서 초점 인자 이름을 뜬다 — 여기 적힌 것이 곧 검사 대상이다(목록을 두 벌로
# 적지 않는다. 새 축을 더하면 검사도 함께 는다).
keys=$(grep -oE 'const val ARG_FOCUS_[A-Z_]+ = "[a-zA-Z]+"' "$SRC" | sed -E 's/.*"(.*)"/\1/')
if [ -z "$keys" ]; then
    echo "  ✗ $SRC 에서 ARG_FOCUS_* 상수를 찾지 못했다"
    exit 1
fi

for key in $keys; do
    # ① 생문자열 재유입 — 단일 소스와 nav 그래프(자원이라 상수를 못 쓴다)만 예외다.
    raw=$(grep -rn "\"$key\"" "$KT_ROOT" --include=*.kt | grep -v "^$SRC:" || true)
    if [ -n "$raw" ]; then
        echo "  ✗ '$key'을(를) 생문자열로 쓴 자리 — FieldValueFixRoute의 상수를 쓸 것"
        echo "$raw" | sed 's/^/      /'
        fail=1
    fi

    # ② nav 그래프 선언
    if ! grep -q "android:name=\"$key\"" "$NAV"; then
        echo "  ✗ '$key'이(가) $NAV 에 인자로 선언되지 않았다"
        fail=1
    fi

    # ③ 읽으면 지운다 — 상수 이름으로 찾는다(생문자열은 ①이 이미 막았다).
    const=$(grep -oE "const val (ARG_FOCUS_[A-Z_]+) = \"$key\"" "$SRC" | sed -E 's/const val ([A-Z_]+).*/\1/')
    [ -n "$const" ] || continue
    for f in $(grep -rl "$const" "$KT_ROOT" --include=*.kt | grep -v "^$SRC$"); do
        # 그 파일이 이 인자를 **읽는가**
        if grep -qE "get(Long|String)\([^)]*$const" "$f"; then
            # 읽었으면 같은 파일에서 **지우기도** 해야 한다
            if ! grep -qE "remove\([^)]*$const" "$f"; then
                echo "  ✗ $f: '$const'을(를) 읽고 지우지 않는다 — 회전 때마다 다시 걸린다"
                fail=1
            fi
        fi
    done
done

if [ "$fail" -eq 0 ]; then
    echo "  ✓ 인자 짝·선언·소비 이상 없음 (초점 인자 $(echo "$keys" | wc -w)종)"
    exit 0
fi
exit 1
