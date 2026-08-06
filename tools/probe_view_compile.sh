#!/bin/bash
# 커스텀 뷰 프로브 — Android 프레임워크 스텁으로 그리기 계층을 **진짜 타입 검사**한다.
#
# **왜 있는가:** 이 환경에는 `android.jar`가 없어서 `differential_compile.sh`는 `View`를
# 상속한 파일 하나당 수십 건의 "unresolved reference"를 낸다(상위 타입이 미해석이면 그
# 파일 전체가 무너진다). 그래서 진짜 오타·시그니처 오류가 그 노이즈에 묻히고,
# `probe_compile.sh`는 아예 `ui/**`를 범위 밖으로 뒀다 — **그 계층의 증명이 CI뿐이었다.**
#
# 이 스크립트는 **그리기·터치 API만 골라 스텁**을 세워(Canvas·Paint·Path·View…) 대상
# 커스텀 뷰를 컴파일한다. 잡히는 것은 메서드 이름·인자 타입·널 안전성처럼 기계가 볼 수
# 있는 자리다. **잡히지 않는 것은 "보이는 것"이다** — 렌더 결과·색 대비·터치 실물감은
# 여전히 CI 초록과 실기기 확인의 몫이다(CLAUDE.md 세션 착수 규칙 4번).
#
# **스텁은 실제 Android API 시그니처와 같아야 한다.** 다르게 적으면 이 프로브는 거짓
# 안심을 준다 — 아래 스텁을 고칠 때는 반드시 실제 API 문서를 대조할 것.
#
# 사용법: JARS_DIR=/path/to/jars tools/probe_view_compile.sh /tmp/view-probe.txt
set -eu

SP="${JARS_DIR:?JARS_DIR를 지정하세요 (setup_jvm_env.sh가 만든 곳)}"
OUT="${1:?출력 파일 경로가 필수 인자입니다}"
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
. "$(cd "$(dirname "$0")" && pwd)/jvm_env_versions.sh"   # jar 버전 단일 소스 (B-84)
WORK="$SP/view-probe-work"
rm -rf "$WORK"
mkdir -p "$WORK"

# ── 1. 프레임워크 스텁 (그리기·터치에 실제로 쓰는 멤버만) ──
# 패키지마다 파일을 나눈다 — 한 파일에 `package`를 여럿 적으면 컴파일이 **시작조차 못 하고**
# 오류 0으로 보인다(이 스크립트를 쓰면서 실제로 겪었다. 아래 0건 판정이 그래서 있다).
cat > "$WORK/ContextStub.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package android.content
open class Context {
    fun getColor(id: Int): Int = 0
    fun getString(id: Int): String = ""
    fun getString(id: Int, vararg args: Any?): String = ""
    val resources: android.content.res.Resources get() = android.content.res.Resources()
}
EOF
cat > "$WORK/ResourcesStub.kt" <<'EOF'
package android.content.res
class Resources { val displayMetrics: android.util.DisplayMetrics get() = android.util.DisplayMetrics() }
EOF
cat > "$WORK/UtilStub.kt" <<'EOF'
package android.util
class DisplayMetrics {
    @JvmField var density: Float = 1f
    @JvmField var heightPixels: Int = 0
}
interface AttributeSet
EOF
cat > "$WORK/GraphicsStub.kt" <<'EOF'
package android.graphics
class Color {
    companion object {
        const val BLACK: Int = 0; const val WHITE: Int = 0
        fun rgb(red: Int, green: Int, blue: Int): Int = 0
        fun red(color: Int): Int = 0
        fun green(color: Int): Int = 0
        fun blue(color: Int): Int = 0
    }
}
class Paint(flags: Int) {
    constructor() : this(0)
    companion object { const val ANTI_ALIAS_FLAG: Int = 1 }
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Align { LEFT, CENTER, RIGHT }
    var color: Int = 0
    var alpha: Int = 255
    var style: Style = Style.FILL
    var strokeWidth: Float = 0f
    var strokeJoin: Join = Join.MITER
    var strokeCap: Cap = Cap.BUTT
    var textSize: Float = 12f
    var textAlign: Align = Align.LEFT
    var isFakeBoldText: Boolean = false
    var pathEffect: PathEffect? = null
    fun measureText(text: String): Float = 0f
    fun descent(): Float = 0f
    fun ascent(): Float = 0f
}
open class PathEffect
class DashPathEffect(intervals: FloatArray, phase: Float) : PathEffect()
class Path {
    fun reset() {}
    fun moveTo(x: Float, y: Float) {}
    fun lineTo(x: Float, y: Float) {}
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
    fun close() {}
}
class RectF() {
    constructor(left: Float, top: Float, right: Float, bottom: Float) : this()
    var left: Float = 0f
    var top: Float = 0f
    var right: Float = 0f
    var bottom: Float = 0f
    fun set(left: Float, top: Float, right: Float, bottom: Float) {}
    fun centerX(): Float = 0f
    fun centerY(): Float = 0f
}
class Canvas {
    fun save(): Int = 0
    fun restore() {}
    fun clipPath(path: Path): Boolean = true
    fun clipRect(rect: RectF): Boolean = true
    fun drawPath(path: Path, paint: Paint) {}
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {}
    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {}
    fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {}
    fun drawOval(oval: RectF, paint: Paint) {}
    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {}
    fun drawText(text: String, x: Float, y: Float, paint: Paint) {}
}
EOF
cat > "$WORK/ViewStub.kt" <<'EOF'
package android.view
class MotionEvent {
    companion object {
        const val ACTION_DOWN: Int = 0
        const val ACTION_UP: Int = 1
        const val ACTION_MOVE: Int = 2
        const val ACTION_CANCEL: Int = 3
    }
    val actionMasked: Int get() = 0
    val x: Float get() = 0f
    val y: Float get() = 0f
}
interface ViewParent { fun requestDisallowInterceptTouchEvent(disallow: Boolean) }
open class View(context: android.content.Context, attrs: android.util.AttributeSet?, defStyleAttr: Int) {
    constructor(context: android.content.Context) : this(context, null, 0)
    val context: android.content.Context = context
    val resources: android.content.res.Resources get() = context.resources
    val parent: ViewParent? get() = null
    val width: Int get() = 0
    val height: Int get() = 0
    val paddingLeft: Int get() = 0
    val paddingTop: Int get() = 0
    val paddingRight: Int get() = 0
    val paddingBottom: Int get() = 0
    var contentDescription: CharSequence? = null
    fun invalidate() {}
    protected open fun onDraw(canvas: android.graphics.Canvas) {}
    open fun onTouchEvent(event: android.view.MotionEvent): Boolean = false
    open fun performClick(): Boolean = false
    protected open fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {}
    protected fun setMeasuredDimension(measuredWidth: Int, measuredHeight: Int) {}
    // 실제 API는 View의 public static 메서드다 — 하위 클래스에서 이름만으로 부른다.
    fun resolveSize(size: Int, measureSpec: Int): Int = size
    // 실제 API는 View의 public static 중첩 클래스다 — 값도 실제와 같게 둔다
    // (모드는 상위 2비트: UNSPECIFIED=0, EXACTLY=1<<30, AT_MOST=2<<30).
    object MeasureSpec {
        const val UNSPECIFIED: Int = 0
        const val EXACTLY: Int = 1073741824
        const val AT_MOST: Int = -2147483648
        fun getMode(measureSpec: Int): Int = 0
        fun getSize(measureSpec: Int): Int = 0
        fun makeMeasureSpec(size: Int, mode: Int): Int = 0
    }
}
open class ViewGroup(context: android.content.Context, attrs: android.util.AttributeSet?, defStyleAttr: Int)
    : View(context, attrs, defStyleAttr) {
    constructor(context: android.content.Context) : this(context, null, 0)
}
EOF
# 상한 스크롤(B-98)의 상위 타입 — `ui/common/CappedScrollView.kt`가 이 둘을 상속한다.
cat > "$WORK/ScrollStub.kt" <<'EOF'
package android.widget
open class FrameLayout(context: android.content.Context, attrs: android.util.AttributeSet?, defStyleAttr: Int)
    : android.view.ViewGroup(context, attrs, defStyleAttr) {
    constructor(context: android.content.Context) : this(context, null, 0)
}
open class ScrollView(context: android.content.Context, attrs: android.util.AttributeSet?, defStyleAttr: Int)
    : FrameLayout(context, attrs, defStyleAttr) {
    constructor(context: android.content.Context) : this(context, null, 0)
}
EOF
cat > "$WORK/NestedScrollStub.kt" <<'EOF'
package androidx.core.widget
open class NestedScrollView(context: android.content.Context, attrs: android.util.AttributeSet?, defStyleAttr: Int)
    : android.widget.FrameLayout(context, attrs, defStyleAttr) {
    constructor(context: android.content.Context) : this(context, null, 0)
}
EOF

# R은 aapt가 만드는 것이라 소스에 없다 — **실제 strings.xml·colors.xml에서 이름을 뽑아** 세운다
# (손으로 적으면 리소스가 늘 때 낡고, 없는 이름을 통과시켜 거짓 안심을 준다).
{
  echo "// 프로브 전용 — 실제 리소스 파일에서 이름을 뽑아 세운다."
  echo "package com.novelcharacter.app"
  echo "object R {"
  for kind in string color dimen; do
    echo "  object $kind {"
    grep -ho "<$kind name=\"[^\"]*\"" "$REPO"/app/src/main/res/values/*.xml |
      sed -E "s/<$kind name=\"([^\"]*)\"/\1/" | sort -u |
      sed 's/^/    const val /; s/$/: Int = 0/'
    echo "  }"
  done
  echo "}"
} > "$WORK/RProbe.kt"

# ── 2. 대상 — 프레임워크 스텁만으로 서는 커스텀 뷰 ──
M="$REPO/app/src/main/java/com/novelcharacter/app"
{
  echo "$M/ui/character/SilhouetteView.kt"
  echo "$M/ui/view/GradeCutSliderView.kt"
  echo "$M/ui/common/CappedScrollView.kt"
  echo "$M/util/DialogScrollCap.kt"
  echo "$M/util/BodySilhouetteSpec.kt"
  echo "$M/util/BodyMeasurements.kt"
  ls "$M"/data/model/*.kt
  echo "$WORK/ContextStub.kt"
  echo "$WORK/ResourcesStub.kt"
  echo "$WORK/UtilStub.kt"
  echo "$WORK/GraphicsStub.kt"
  echo "$WORK/ViewStub.kt"
  echo "$WORK/ScrollStub.kt"
  echo "$WORK/NestedScrollStub.kt"
  echo "$WORK/RProbe.kt"
} > "$WORK/files.txt"

# ── 3. 컴파일 ──
CP="$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar:$SP/out-room:$SP/gson-$GSON_VER.jar:$SP/json-20240303.jar"
# 컴파일러 **자신의** 클래스패스에도 coroutines가 있어야 한다 — 없으면 CoroutineScope
# NoClassDefFoundError로 컴파일이 시작조차 못 한다(probe_compile.sh의 같은 주석).
java -cp "$SP/kotlin-compiler-embeddable-2.0.21.jar:$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar:$SP/kotlinx-coroutines-core-jvm.jar:$SP/trove4j.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -no-stdlib -cp "$CP" -d "$WORK/out" "@$WORK/files.txt" 2>&1 \
  | grep -E "error:" \
  | sed "s|$REPO/||" \
  | sed -E 's/^([^:]+):[0-9]+:[0-9]+: (error: .*)$/\1| \2/' \
  | sort -u > "$OUT"

ERRS=$(wc -l < "$OUT")
echo "고유 오류 ${ERRS}건 → $OUT"
# 오류 0을 그냥 믿지 않는다 — 컴파일이 시작조차 못 하면 출력이 비어 "0"으로 보인다
# (probe_compile.sh의 같은 주석 참조. 2026-08-01에 실제로 겪은 함정이다).
if [ "$ERRS" -eq 0 ] && [ "$(find "$WORK/out" -name '*.class' 2>/dev/null | wc -l)" -eq 0 ]; then
  echo "⚠️  오류 0인데 클래스 파일도 0이다 — 컴파일이 시작조차 못 했을 수 있다. 믿지 말 것." >&2
  exit 1
fi
