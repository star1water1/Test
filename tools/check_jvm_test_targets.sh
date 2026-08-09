#!/bin/bash
# 순수 JVM 시험의 대상 목록 누락 검사 (B-104 소비처 ⓑ·ⓒ에서 신설)
#
# 무엇을 보는가: **`app/src/test`의 `*Test.kt`가 전부 `tools/run_jvm_tests.sh`의
# `TESTS` 목록에 있는가.**
#
# ## 왜 있는가 — 이 판이 실제로 그 함정을 밟았다
#
# `run_jvm_tests.sh`는 시험 파일을 **손으로 적은 목록**으로 돌린다(디렉터리를 훑지 않는다).
# 2026.08.09 소비처 ⓑ·ⓒ 구현이 새 시험 파일 둘(`RepresentativeWeightingTest` ·
# `DuelImagePruneTest`, 시험 18건)을 만들고 목록에 넣지 않았는데, **초록이 그대로 떴다.**
# 잡힌 것은 오직 *"기준선이 2096에서 2108로 12만 늘었다 — 30이어야 하는데"*라는 손셈이었다.
# 다음 사람이 그 셈을 한다는 보장이 없다.
#
# `check_view_probe_targets.sh`가 커스텀 뷰에 대해 세운 논리와 **같은 논리다**(그쪽 머리
# 주석이 근거를 길게 든다): *"관행이라고 적어도 지켜지지 않는다. 지켜지게 하려면 적는 것
# 말고 기계가 있어야 한다."*
#
# ## 왜 다른 검사가 이것을 못 잡는가 — **실패의 모양이 침묵이다**
#
# 목록에서 빠진 시험은 실패하는 것이 아니라 **돌지 않는다.** 오류가 늘지 않고, 이름이
# 출력에 뜨지도 않으며, 빠졌다는 사실이 어디에도 적히지 않는다 —
# **빠뜨릴수록 결과가 깨끗해 보인다.** 새 기능의 방어선이 통째로 죽어 있어도
# 기준선 숫자는 초록이다.
#   - 컴파일: 못 본다. 목록에 없는 파일은 컴파일조차 되지 않는다(그래서 **깨진 시험도**
#     조용히 지나간다 — 더 나쁜 쪽이다).
#   - CI: 못 본다. CI도 같은 스크립트를 부른다.
#   - 스크립트 자신: **가장 못 본다.** 자기 목록이 맞는지는 자기가 물을 수 없다.
#
# ## 예외를 두는 방법
#
# 돌릴 수 없는 시험이 있다(`R` 참조처럼 안드로이드 자원이 필요한 것). 그런 파일은
# **스크립트 안에 사유와 함께 주석으로 적혀 있어야** 통과한다 — 아래 `excused`가 그
# 주석을 찾는다. 여기 목록을 두 벌로 적지 않는 것이 요점이다: 사유는 그 파일을 뺀
# 사람이 그 자리에 적는 것이고, 이 검사는 *적혀 있는가*만 본다.
#
# 사용법: tools/check_jvm_test_targets.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

TEST_DIR="app/src/test/java/com/novelcharacter/app"
RUNNER="tools/run_jvm_tests.sh"

# **등재됐다 = 주석이 아닌 줄에 `$TEST/<경로>` 가 있다.**
# 형태를 좁게 요구하는 것은 일부러다 — 목록의 형태가 바뀌면 이 검사는 **큰소리로 실패**하고
# 사람이 여기를 고치게 된다. 느슨하게 두어 조용히 통과하는 것보다 그쪽이 낫다(실패의 방향).
listed() {
  grep -v '^[[:space:]]*#' "$RUNNER" | grep -qF '$TEST/'"$1"
}

# **면제됐다 = 주석 줄에 그 클래스 이름이 적혀 있다.** 사유를 함께 적으라는 뜻이고,
# 이름만 적고 사유를 빼는 것까지는 막지 않는다(그건 리뷰의 몫이다).
excused() {
  grep '^[[:space:]]*#' "$RUNNER" | grep -qF "$1"
}

echo "── 순수 JVM 시험 대상 목록 검사 ──"

if [ ! -f "$RUNNER" ]; then
  echo "  ✗ $RUNNER 가 없다 — 러너가 사라졌으면 이 검사도 뜻을 잃는다." >&2
  exit 1
fi

# ── 판정기 자기 시험 — 진짜를 잡고, 주석·닮은꼴은 안 잡는가 ──
# 이 검사가 **조용히 통과할 경로를 먼저 막는다**(`check_view_probe_targets.sh`가 세운 관행).
SELF=$(mktemp)
{
  echo '$TEST/util/RealListedTest.kt'
  echo '# 주의: CommentOnlyTest는 R을 참조하므로 여기서 돌릴 수 없다 — CI 전용.'
  echo '# $TEST/util/CommentedOutTest.kt'
} > "$SELF"
_real="$RUNNER"; RUNNER="$SELF"
listed "util/RealListedTest.kt"     && s_ok=1  || s_ok=0
listed "util/CommentedOutTest.kt"   && s_out=1 || s_out=0
excused "CommentOnlyTest"           && s_ex=1  || s_ex=0
excused "NeverMentionedTest"        && s_nx=1  || s_nx=0
RUNNER="$_real"; rm -f "$SELF"
if [ "$s_ok" -ne 1 ] || [ "$s_out" -ne 0 ] || [ "$s_ex" -ne 1 ] || [ "$s_nx" -ne 0 ]; then
  echo "  ✗ 판정기 자기 시험 실패 — 등재 ${s_ok}(1) · 주석 처리된 등재 ${s_out}(0) · 면제 ${s_ex}(1) · 없는 것 ${s_nx}(0)" >&2
  echo "      (주석을 등재로 읽으면 이 검사는 자기가 막으려는 것을 그대로 통과시킨다)" >&2
  exit 1
fi

FOUND=$(find "$TEST_DIR" -name '*Test.kt' 2>/dev/null | sed "s|^$TEST_DIR/||" | sort)
COUNT=$(printf '%s\n' "$FOUND" | grep -c . || true)

# 하나도 못 찾았으면 **통과가 아니라 실패**다 — 시험이 전부 사라지는 일보다
# 경로가 낡는 일이 훨씬 흔하고, 둘을 같게 처리하면 낡은 순간부터 영원히 초록이다.
if [ "$COUNT" -eq 0 ]; then
  echo "  ✗ $TEST_DIR 에서 *Test.kt 를 하나도 못 찾았다 — 경로가 낡았을 수 있다." >&2
  exit 1
fi

MISSING=0
EXCUSED=0
while IFS= read -r rel; do
  [ -n "$rel" ] || continue
  if listed "$rel"; then
    continue
  fi
  cls=$(basename "$rel" .kt)
  if excused "$cls"; then
    echo "  · $rel — 목록에 없으나 사유가 적혀 있다(면제)"
    EXCUSED=$((EXCUSED + 1))
  else
    echo "  ✗ $rel — $RUNNER 의 TESTS 목록에 없다(**돌지 않는다**)" >&2
    MISSING=$((MISSING + 1))
  fi
done <<EOF
$FOUND
EOF

if [ "$MISSING" -ne 0 ]; then
  echo "" >&2
  echo "  누락 ${MISSING}건. $RUNNER 의 TESTS 목록에 아래 꼴로 한 줄씩 넣을 것:" >&2
  echo '      $TEST/<경로>.kt' >&2
  echo "  main 쪽 새 파일이 있으면 SOURCES 목록에도 함께 넣는다(없으면 컴파일이 깨진다)." >&2
  echo "  돌릴 수 없는 시험이면 **사유를 주석으로** 적을 것 — 그때 이 검사가 면제로 읽는다." >&2
  exit 1
fi

echo "  전부 등재됨 — ${COUNT}건 확인(면제 ${EXCUSED}건)"
