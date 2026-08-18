#!/bin/bash
# 🎲 생성기가 산출값을 자르는 자리와, 그 자리를 읽어 경고하는 자리가 **한 벌인가** (B-202).
#
# 배경: 축 구간 편집 창의 '들어갈 정수 cm가 없습니다' 경고는
# `GenerationPreset.emptyBands()`가 낸다. 그 판정이 옳으려면 **생성기가 실제로 자르는 수**를
# 그대로 써야 한다 — 키 140~200, 허리 45~110이 그것이다. 종전에는 그 수가 `BodyGenerator`의
# `coerceIn(140.0, 200.0)` 같은 날 숫자로만 있었고, 그래서 창은 잴 방법이 아예 없었다.
#
# **이 검사가 없으면 갈리는 방식은 조용하다.** 누가 생성기의 clamp만 고치면
# 경고는 옛 수로 계속 답하고, 화면에는 *아무 일도 일어나지 않는다* — 틀린 경고가 뜨거나
# 떠야 할 경고가 안 뜰 뿐이라 컴파일도 순수 시험도 못 본다(시험은 두 자리가 같은 상수를
# 읽는 한 언제나 초록이다).
#
# 축 셋:
#   ① BodyGenerator.kt 에 날 숫자 clamp가 없다 — 전부 Limits.*_REACH 를 지난다
#   ② 그 상수들이 실재한다 (이름이 바뀌면 ①이 통과하면서 뜻이 사라진다)
#   ③ emptyBands() 를 실제로 부르는 화면이 있다 — 아무도 안 부르면 판정이 죽은 코드다
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

GEN="app/src/main/java/com/novelcharacter/app/util/BodyGenerator.kt"
CFG="app/src/main/java/com/novelcharacter/app/data/model/BodyAnalysisConfig.kt"
DLG="app/src/main/java/com/novelcharacter/app/ui/character/BodyGenerationEditDialog.kt"
fail=0

echo "── 🎲 생성 범위 단일 소스 검사 (B-202) ──"

# ① 날 숫자 clamp 금지
raw=$(grep -nE 'coerceIn\([0-9]' "$GEN" || true)
if [ -n "$raw" ]; then
  echo "  ✗ 날 숫자 clamp — GenerationPreset.Limits.*_REACH 를 쓸 것:"
  echo "$raw" | sed 's/^/      /'
  fail=1
else
  echo "  ✓ 산출 clamp가 전부 Limits.*_REACH 를 지난다"
fi

# ② 상수 실재
missing=""
for name in HEIGHT_REACH WAIST_REACH BUST_REACH HIP_REACH WEIGHT_REACH; do
  grep -qE "val $name = " "$CFG" || missing="$missing $name"
done
if [ -n "$missing" ]; then
  echo "  ✗ Limits 에 없는 상수:$missing"
  fail=1
else
  echo "  ✓ Limits.*_REACH 다섯이 모두 있다"
fi

# ③ 판정을 부르는 화면이 있다
if grep -q 'emptyBands()' "$DLG"; then
  echo "  ✓ 축 편집 창이 emptyBands() 로 경고한다"
else
  echo "  ✗ emptyBands() 를 부르는 자리가 축 편집 창에 없다 — 판정이 죽은 코드다"
  fail=1
fi

if [ $fail -ne 0 ]; then
  echo "🎲 생성 범위 단일 소스 검사 실패"
  exit 1
fi
echo "🎲 생성 범위 단일 소스 검사 통과"
