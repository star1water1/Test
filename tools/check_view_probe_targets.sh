#!/bin/bash
# 커스텀 뷰 프로브의 대상 목록 누락 검사 (R-31 · B-147)
#
# 무엇을 보는가: **`View`를 직접 상속하는 클래스가 전부
# `tools/probe_view_compile.sh`의 대상 목록에 있는가.**
#
# ## 왜 있는가 — 규약으로 적어 두었는데 지켜지지 않았다
#
# R-31은 이미 *"새 커스텀 뷰를 만들면 `tools/probe_view_compile.sh` 대상 목록에 등재할 것
# (누락하면 조용히 검사에서 빠진다)"*고 적어 두었다. 그런데 2026.08.08(B-147)에 세어 보니
# `View`를 직접 상속하는 넷 중 **둘(`RelationshipGraphView`·`EventDensityBar`)이 빠져
# 있었고**, 그 상태로 닷새가 지났다. 규약이 스스로 *"누락하면 조용히 빠진다"*고 경고한
# 바로 그 일이 그 규약 아래에서 났다.
#
# **이 저장소가 이미 같은 결론을 적어 두었다**(`check_config_column_fallback.sh` 머리):
# *"관행이라고 적어도 지켜지지 않는다. 지켜지게 하려면 적는 것 말고 기계가 있어야 한다."*
#
# ## 왜 다른 검사가 이것을 못 잡는가 — **실패의 모양이 침묵이다**
#
# 다른 결함은 무언가가 **틀리게** 나온다. 이것은 **아무 일도 일어나지 않는다.**
# 대상 목록에서 빠진 뷰는 프로브를 통과하는 것이 아니라 **프로브에 들어가지도 않는다** —
# 오류 건수가 늘지 않고, 출력에 이름이 뜨지도 않으며, 빠졌다는 사실 자체가 어디에도
# 적히지 않는다. **빠뜨릴수록 결과가 깨끗해 보인다.**
#   - 컴파일·타입: 못 본다. 뷰 자신은 멀쩡히 컴파일된다(앱 빌드에서). 문제는 *로컬 검증이
#     그 파일을 안 읽는다*는 것이고, 그것은 코드의 성질이 아니라 **도구 설정의 성질**이다.
#   - 순수 JVM 시험: 못 본다. `ui/**`는 Room·Android에 매달려 있어 원리적으로 닿지 않는다.
#   - 프로브 자신: **가장 못 본다.** 자기 대상 목록이 맞는지는 자기가 물을 수 없다.
#
# ## 범위 — 왜 이 둘인가
#
# 프로브가 세운 프레임워크 스텁으로 **설 수 있는 것**만 요구한다. 세울 수 없는 것을 요구하면
# 이 검사가 늘 빨간불이 되고, 그러면 다음 사람이 검사를 끈다.
#   - **`View` 직접 상속** — 처음부터의 범위다.
#   - **`AppCompatImageView` 상속** — 2026.08.10(B-166)에 넓혔다. `ZoomableImageView` 하나가
#     그 부류이고, 종전에는 **어느 로컬 검사에도 안 잡히는 유일한 뷰**였다. 넓히기 전에
#     `probe_view_compile.sh`에 스텁 셋(Matrix·ImageView·AppCompatImageView)을 먼저 세웠고,
#     세우고 나서 실제로 프로브가 초록인 것을 확인한 뒤 이 정규식을 고쳤다 — **순서가 규약이다.**
#   - `CappedScrollView`(ScrollView·NestedScrollView 상속)는 대상 목록에 이미 있다 —
#     그쪽 상위 타입도 스텁이 서 있다. 여기서는 세지 않지만 빠져 있지도 않다.
#
# **다음에 또 넓힐 때도 순서는 같다: 스텁 → 프로브 초록 확인 → 이 정규식.**
# `AppCompat*` 전부로 한 번에 넓히지 않은 것도 같은 이유다 — 스텁이 있는 것만 요구한다.
#
# 사용법: tools/check_view_probe_targets.sh   # 위반 시 exit 1
set -u
# 로케일 고정 — 정규식이 전부 ASCII라 C에서 안전하다.
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app"
PROBE="tools/probe_view_compile.sh"

# `) : View(`와 `) : AppCompatImageView(` 꼴만 잡는다. `: RecyclerView.ViewHolder(`처럼
# **이름이 View로 끝나는 다른 타입**은 콜론 바로 뒤가 그 둘이 아니므로 걸리지 않는다 —
# 그 구별이 이 정규식의 요점이다.
VIEW_RE="\)[[:space:]]*:[[:space:]]*((android\.view\.)?View|(androidx\.appcompat\.widget\.)?AppCompatImageView)[[:space:]]*\("

view_files() {
  grep -rlE "$VIEW_RE" --include=*.kt "$1" 2>/dev/null | sort -u
}

# **등재됐다 = 주석이 아닌 줄에 `echo "$M/<경로>"`가 있다.**
# 경로 문자열이 파일 어딘가에 있기만 하면 통과시키던 판이 있었는데, 그러면
# **주석에 이름만 적어 두고 실제 등재 줄을 지워도 초록이 뜬다**(이 검사를 세운 뒤
# 콜드 검토가 실제로 그 구멍을 잡았다 — 등재 줄을 지우고 그 경로를 주석에 남기니 통과했다).
# 하필 이 검사가 막으려는 것이 *조용히 빠지는 것*이라, 그 구멍은 검사 자체를 무의미하게 만든다.
#
# **형태를 좁게 요구하는 것은 일부러다.** 목록의 형태가 바뀌면 이 검사는 **큰소리로 실패**하고
# 사람이 여기를 고치게 된다 — 느슨하게 두어 조용히 통과하는 것보다 그쪽이 낫다(실패의 방향).
listed() {
  local rel="$1"
  grep -v '^[[:space:]]*#' "$PROBE" | grep -qF 'echo "$M/'"$rel"'"'
}

echo "── 커스텀 뷰 프로브 대상 목록 검사 (R-31 · B-147) ──"

# ── 탐지기 자기 시험 — 진짜를 잡고, 닮은 것은 안 잡는가 ──
# 이 검사가 **조용히 통과할 경로를 먼저 막는다.** 정규식이 낡아 아무것도 못 찾으면
# "전부 등재됨"과 구별되지 않는데, 그 둘은 정반대 사실이다(B-146이 겪은 부류).
SELFDIR=$(mktemp -d)
cat > "$SELFDIR/Real.kt" <<'EOF'
class RealView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
}
EOF
cat > "$SELFDIR/Qualified.kt" <<'EOF'
class QualifiedView(context: Context) : android.view.View(context) {
}
EOF
cat > "$SELFDIR/Image.kt" <<'EOF'
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
}
EOF
cat > "$SELFDIR/Lookalike.kt" <<'EOF'
class VH(view: View) : RecyclerView.ViewHolder(view)
class Capped(context: Context) : NestedScrollView(context) {
}
class Chip(context: Context) : AppCompatTextView(context) {
}
EOF
real_hits=$(view_files "$SELFDIR/Real.kt" | grep -c . || true)
qual_hits=$(view_files "$SELFDIR/Qualified.kt" | grep -c . || true)
img_hits=$(view_files "$SELFDIR/Image.kt" | grep -c . || true)
look_hits=$(view_files "$SELFDIR/Lookalike.kt" | grep -c . || true)
rm -rf "$SELFDIR"
if [ "$real_hits" -ne 1 ] || [ "$qual_hits" -ne 1 ] || [ "$img_hits" -ne 1 ] || [ "$look_hits" -ne 0 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 실제 ${real_hits}건(1) · 정규화 ${qual_hits}건(1) ·" >&2
  echo "      이미지뷰 ${img_hits}건(1) · 닮은꼴 ${look_hits}건(0)" >&2
  echo "      (진짜를 못 잡으면 이 검사는 장식이고, 닮은꼴을 잡으면 오검출로 다음 사람이 끈다)" >&2
  echo "      닮은꼴에 AppCompatTextView를 둔 것은 일부러다 — **스텁이 있는 것만 요구한다**는" >&2
  echo "      범위 규칙이 깨지면 여기서 큰소리로 실패한다." >&2
  exit 1
fi

# ── 본 검사 ──
if [ ! -f "$PROBE" ]; then
  echo "  ✗ $PROBE 가 없다 — 프로브가 사라졌으면 이 검사도 뜻을 잃는다." >&2
  exit 1
fi

# `listed`의 자기 시험 — **주석만으로는 등재가 아니다**를 실제로 잰다.
# 위 탐지기 시험이 *무엇이 커스텀 뷰인가*를 쟀다면 이쪽은 *무엇이 등재인가*를 잰다.
SELFPROBE=$(mktemp)
{
  echo '  echo "$M/ui/real/Listed.kt"'
  echo '  # 주석에만 적힌 것: ui/fake/CommentOnly.kt'
  echo '  # echo "$M/ui/fake/CommentedOut.kt"'
} > "$SELFPROBE"
_real_probe="$PROBE"; PROBE="$SELFPROBE"
listed "ui/real/Listed.kt"        && l_ok=1 || l_ok=0
listed "ui/fake/CommentOnly.kt"   && l_c1=1 || l_c1=0
listed "ui/fake/CommentedOut.kt"  && l_c2=1 || l_c2=0
PROBE="$_real_probe"; rm -f "$SELFPROBE"
if [ "$l_ok" -ne 1 ] || [ "$l_c1" -ne 0 ] || [ "$l_c2" -ne 0 ]; then
  echo "  ✗ 등재 판정 자기 시험 실패 — 진짜 ${l_ok}(1) · 주석 언급 ${l_c1}(0) · 주석 처리된 등재 ${l_c2}(0)" >&2
  echo "      (주석을 등재로 읽으면 이 검사는 자기가 막으려는 것을 그대로 통과시킨다)" >&2
  exit 1
fi

FOUND=$(view_files "$SRC")
COUNT=$(printf '%s\n' "$FOUND" | grep -c . || true)

# 하나도 못 찾았으면 **통과가 아니라 실패**다. 커스텀 뷰가 전부 사라지는 일보다
# 정규식이 낡는 일이 훨씬 흔하고, 둘을 같게 처리하면 낡은 순간부터 영원히 초록이다.
if [ "$COUNT" -eq 0 ]; then
  echo '  ✗ View를 직접 상속하는 클래스를 하나도 못 찾았다 — 정규식이 낡았을 수 있다.' >&2
  echo "      확인: grep -rnE '$VIEW_RE' $SRC --include=*.kt" >&2
  exit 1
fi

MISSING=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  rel="${f#"$SRC"/}"                       # 예: ui/graph/RelationshipGraphView.kt
  if listed "$rel"; then
    echo "  ✓ $rel"
  else
    echo "  ✗ $rel — $PROBE 대상 목록에 없다(조용히 검사에서 빠진다)" >&2
    MISSING=$((MISSING + 1))
  fi
done <<EOF
$FOUND
EOF

if [ "$MISSING" -ne 0 ]; then
  echo "" >&2
  echo "  누락 ${MISSING}건. $PROBE 의 '2. 대상' 목록에 아래 꼴로 한 줄씩 넣을 것:" >&2
  echo '      echo "$M/<경로>.kt"' >&2
  echo "  넣은 뒤 프로브를 돌려 **스텁이 모자라지 않은지**까지 확인한다(그리기 계층이 넓다)." >&2
  exit 1
fi

echo "  전부 등재됨 — ${COUNT}건 확인"
