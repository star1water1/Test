#!/bin/bash
# 하네스 스텁이 가린 클래스를 시험이 세우는가 (2026.08.12 신설 — B-157 CI가 잡았다)
#
# 무엇을 보는가: **`tools/jvm-stubs/`가 선언한 클래스 중 진짜가 `app/src/main`에도 있는 것**을
# **`app/src/test`가 생성자로 부르는가.** 부르면 실패한다.
#
# ## 왜 있는가 — 로컬 초록이 CI 컴파일 실패였다
#
# 순수 JVM 하네스(`run_jvm_tests.sh`)는 `AiService`처럼 Android에 묶인 클래스를 **스텁으로
# 대신**한다. 스텁은 진짜의 *모양을 비추는* 것이 일인데, **생성자는 원리적으로 비출 수 없다** —
# 진짜는 `AiService(context: Context)`이고 스텁은 `Context`를 만들 수 없어 인자가 없다.
#
# 그래서 시험이 `AiService()`를 세우면 **하네스는 초록이고 Gradle(CI)은 컴파일 실패**다.
# 2026.08.12에 그대로 겪었다: B-157 시험이 스텁에만 있는 `AiService()`와 `imagesUnsupported`를
# 썼고, 로컬 2694건 초록 뒤 CI가 `No value passed for parameter 'context'`로 죽었다.
#
# ## 왜 다른 검사가 이것을 못 잡는가 — **로컬의 모든 도구가 스텁 쪽을 본다**
#
#   - 순수 JVM 시험: **가장 못 본다.** 그 시험이 스텁으로 컴파일된 당사자다.
#   - 실클래스패스 프로브·커스텀 뷰 프로브: `app/src/test`를 아예 안 읽는다.
#   - 차분 컴파일: SDK가 없어 미해석 수천 건에 묻힌다.
#   - `check_jvm_test_targets.sh`: *목록에 있는가*를 볼 뿐 *무엇과 컴파일되는가*는 모른다.
# **로컬에 증명 수단이 없는 부류라 CI만이 잡았고, 그래서 이 검사는 CI가 아니라 그 앞자리에 선다.**
#
# ## 어떻게 고치는가 (실패했을 때 읽을 것)
#
# 스텁을 늘려 시험을 맞추지 말 것 — **갈림의 뿌리는 못 고친다**(진짜는 `Context`에 묶여 있다).
# 대신 **시험이 그 클래스를 아예 부르지 않게** 만든다. B-157이 택한 모양:
# `ImageBatchTagSuggester`가 `AiService`에서 쓰는 몫 셋을 **람다로 받고** `AiService`용 보조
# 생성자를 둔다 — 호출측은 그대로이고, 시험은 람다를 넘겨 **두 환경에서 같은 것을 본다**
# (덤으로 그 시험이 CI에서도 돈다. 그쪽이 더 강하다).
#
# **목록을 손으로 적지 않는다** — 스텁 파일에서 이름을 뽑고 `app/src/main`에 같은 이름의
# 선언이 있는 것만 남긴다. 새 스텁이 생겨도 등재를 잊을 자리가 없다(B-145·B-158의 교훈).
#
# 사용법: tools/check_stub_shadow_use.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

STUBS="tools/jvm-stubs"
MAIN="app/src/main/java"
TEST="app/src/test/java"

echo "── 하네스 스텁이 가린 클래스의 시험 사용 검사 ──"

# 스텁이 선언한 최상위 클래스 이름.
stub_classes() {
  grep -rhoE '^[[:space:]]*(open[[:space:]]+)?class[[:space:]]+[A-Z][A-Za-z0-9_]*' "$1" 2>/dev/null \
    | sed -E 's/.*class[[:space:]]+//' | sort -u
}

# 시험이 그 이름을 **생성자로** 부르는 자리. 주석은 세지 않는다.
# `ImageBatchTagSuggester(` 같은 남의 이름에 걸리지 않도록 앞이 식별자면 안 된다.
uses_of() {
  local name="$1" dir="$2"
  grep -rnE "(^|[^A-Za-z0-9_.])${name}[[:space:]]*\(" "$dir" --include=*.kt 2>/dev/null \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)' || true
}

# ── 탐지기 자기 시험 — 조용히 통과할 경로를 먼저 막는다 ──
SELF=$(mktemp -d)
mkdir -p "$SELF/stubs" "$SELF/main" "$SELF/test"
cat > "$SELF/stubs/S.kt" <<'EOF'
class AiService {
    fun complete(): Int = 0
}
class HarnessOnly { }
EOF
cat > "$SELF/main/M.kt" <<'EOF'
class AiService(context: Context) { }
EOF
cat > "$SELF/test/T.kt" <<'EOF'
val a = AiService()
val b = ImageBatchTagSuggester(AiService())
val c = HarnessOnly()
// val d = AiService() 는 주석이라 세지 않는다
val e = SomeOtherAiService()
EOF
sc=$(stub_classes "$SELF/stubs" | tr '\n' ' ')
hits=$(uses_of "AiService" "$SELF/test" | grep -c . || true)
other=$(uses_of "HarnessOnly" "$SELF/test" | grep -c . || true)
rm -rf "$SELF"
if [ "$sc" != "AiService HarnessOnly " ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 스텁 클래스 추출: 기대 'AiService HarnessOnly', 실제 '${sc}'" >&2
  exit 1
fi
# 진짜 둘(맨 호출·인자 안의 호출)만 잡고, 주석과 `SomeOtherAiService`는 안 잡아야 한다.
if [ "$hits" -ne 2 ] || [ "$other" -ne 1 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 사용 탐지: AiService ${hits}건(2) · HarnessOnly ${other}건(1)" >&2
  echo "      (못 잡으면 장식이고, 주석·닮은 이름을 잡으면 오검출로 다음 사람이 검사를 끈다)" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (인자 안의 호출까지 잡고, 주석·닮은 이름은 세지 않는다)"

if [ ! -d "$STUBS" ]; then
  echo "  ✗ 스텁 디렉터리가 없습니다: $STUBS (경로가 바뀌었으면 이 검사를 함께 고칠 것)" >&2
  exit 1
fi

fail=0
shadowed=""
for name in $(stub_classes "$STUBS"); do
  # 진짜가 main에 없으면 가린 것이 아니다(`Harness*`가 그 부류다).
  grep -rqE "(^|[^A-Za-z0-9_])class[[:space:]]+${name}[[:space:](<]" "$MAIN" --include=*.kt 2>/dev/null || continue
  shadowed="$shadowed $name"
  hits=$(uses_of "$name" "$TEST")
  if [ -n "$hits" ]; then
    echo "  ✗ 시험이 스텁으로 가려진 '${name}'을(를) 세웁니다:"
    printf '%s\n' "$hits" | sed 's/^/      /'
    echo "      → 하네스는 스텁을, Gradle(CI)은 진짜를 컴파일한다. 생성자가 달라 **로컬 초록·CI 실패**가 된다."
    echo "        스텁을 늘려 맞추지 말 것 — 시험이 그 클래스를 부르지 않게 만든다(머리 주석의 B-157 사례)."
    fail=1
  fi
done

if [ -z "$shadowed" ]; then
  echo "  ✗ 가려진 클래스를 한 건도 찾지 못했습니다 — 검사가 대상을 놓쳤습니다" >&2
  echo "      → 스텁이나 main의 선언 모양이 바뀌었으면 정규식을 함께 고칠 것." >&2
  exit 1
fi

if [ "$fail" -ne 0 ]; then
  echo "── 스텁 가림 검사 실패 ──" >&2
  exit 1
fi
echo "  ✓ 가려진 클래스(${shadowed# })를 시험이 세우지 않는다"
echo "── 스텁 가림 검사 통과 ──"
