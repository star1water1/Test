#!/bin/bash
# 실제 클래스패스 프로브 — 차분 컴파일이 **못 보는** 자리를 타입 검사한다.
#
# **왜 있는가:** `differential_compile.sh`는 Android SDK·Room 없이 컴파일하므로
# `androidx`를 import한 타입이 전부 미해석이 되고, 그 위의 모든 멤버 접근이 "unresolved"로
# 뜬다(기준선에 1만 건 이상). 그래서 **진짜 오류가 노이즈에 묻힌다.**
# 이 스크립트는 실제 jar(POI·gson·coroutines) + androidx 스텁 + **DAO 접근자만 가진
# AppDatabase 스텁**을 물려, `excel` + `data/model` + `data/dao` + `util` +
# **`data/repository`**를 통째로 진짜 타입 검사한다(파일 수는 적지 않는다 — 세면 낡는다).
#
# **`data/repository`는 2026-08-03에 들어왔다(B-89).** 그전까지 이 계층은 **어떤 로컬 검사에도
# 잡히지 않았다** — 프로브 범위 밖이었고, 차분 컴파일에서는 노이즈에 묻혔다. 그런데 휴지통
# 복원·스냅샷처럼 **되돌릴 수 없는 것을 다루는 코드**가 거기 산다. 넣어 보니 오류가 늘기는커녕
# **줄었다**(480 → 435): 그 파일들이 정의를 제공해 종전에 미해석이던 참조들이 풀렸기 때문이다.
# 넣은 뒤 `TrashRepository`의 새 코드에 일부러 오타를 내 435 → 439로 오르는 것을 확인했다
# (자기 재공격 — 범위에 들었다는 증명이며, 이 저장소가 1-ba장에서 쓴 것과 같은 방법이다).
#
# **재현 가능하게 남기는 이유는 이 저장소의 관행 그대로다** — 세션마다 손으로 다시 세우면
# 같은 함정에 같은 시간을 쓴다(`setup_jvm_env.sh`의 머리말과 같은 취지).
# 실제로 2026-08-01 세션이 이 프로브를 다섯 번 다시 세웠다.
#
# 사용법:
#   JARS_DIR=/path/to/jars tools/probe_compile.sh /tmp/probe-cur.txt
#   # 기준선은 워크트리에서 따로 뜬다 — 차분 컴파일과 같은 방식이다.
#   git worktree add /tmp/base-wt origin/master
#   REPO=/tmp/base-wt JARS_DIR=/path/to/jars tools/probe_compile.sh /tmp/probe-base.txt
#   comm -13 /tmp/probe-base.txt /tmp/probe-cur.txt      # 신규 오류만 남는다
#
# **범위 밖:** UI(`ui/**`)와 `ExcelImporter`는 Android 프레임워크에 너무 깊게 묶여 있어 뺐다.
# **그 계층의 컴파일 증명은 CI(`assembleDebug`)뿐이다** — 화면을 손대는 변경은
# **실기기 확인 전까지 미완으로 다룰 것**이고, CI는 화면 파일을 건드린 변경이 2~3개 쌓이면
# 한 번 돌린다(CLAUDE.md v2.0 — 둘은 증명하는 것이 다르다: CI는 *컴파일되는가*,
# 실기기는 *제대로 보이는가*. 종전 이 줄은 *"CI 초록과 실기기 확인 전까지"*로 둘을 묶고 있었다).
#
# **`OrganizeFolderService.kt`는 2026.08.10에 범위 안으로 들여왔다 (B-110).** 이 자리는
# *"Android 프레임워크에 너무 깊게 묶여 있어"*라고 그 파일까지 함께 적고 있었는데
# **실측이 그것을 반증했다** — 넣어도 컴파일은 정상으로 끝나고, 느는 것은 **25건뿐이며
# 그 전부가 기존 447건과 같은 부류**다(`ContentResolver`·`DocumentsContract`·`Uri`·
# 커서 메서드 등 프레임워크 스텁의 빈자리. 2026.08.03 B-107 세션의 실측 25건과도 같다).
# **프로브는 어차피 기준선 대비 비교라 상수 노이즈는 아무것도 가리지 않는다** — 그 원칙으로
# `excel/`도 세 자릿수를 내면서 들어와 있다. 반대로 빼 두는 값은 컸다: 이 파일은 **되돌릴 수
# 없는 일**(파일 편입·`_처리됨/` 이동·`_삭제승인` 삭제)을 하는데 순수 하네스에도 없어
# **어떤 로컬 검사에도 안 잡혔다**(B-89이 같은 이유로 `data/repository`를 들인 것과 같은 부류).
#
# **`util/AiImage*.kt` 둘도 뺐다 (2026.08.07 · B-120).** 사유가 서로 다르다:
#   - `AiImagePreparer` — `android.graphics.Bitmap`·`android.util.Base64`에 묶여 있다
#     (`ImageImportHelper`가 이 목록에 남아 20건의 미해석을 내는 것과 같은 부류인데,
#     **그쪽을 뺄지는 이 슬라이스가 정할 일이 아니라 그대로 뒀다** — 새로 들어오는 것만
#     막는다). 증명은 CI뿐이다.
#   - `AiImageAttach` — **순수인데 `ai/AiPromptPolicy`를 참조한다.** 이 프로브는 `ai/**`를
#     통째로 빼므로 원리적으로 해석할 수 없다. 대신 **`run_jvm_tests.sh`가 이 파일을
#     컴파일하고 실제로 돌린다**(`AiImageAttachTest`) — 프로브보다 강한 증명이라 손실이 없다.
# 빼는 대신 노이즈로 두지 않는 이유: 남겨 두면 다음 세션이 base 대 cur를 비교할 때마다
# **이것이 무해하다는 사실을 매번 다시 알아내야 한다.**
set -eu

SP="${JARS_DIR:?JARS_DIR를 지정하세요 (setup_jvm_env.sh가 만든 곳)}"
OUT="${1:?출력 파일 경로가 필수 인자입니다 (빼면 어디에 썼는지 알 수 없다)}"
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
TOOLS="$(cd "$(dirname "$0")" && pwd)"
. "$TOOLS/jvm_env_versions.sh"   # jar 버전 단일 소스 (B-84)
jvm_env_require_jars "$SP"
WORK="$SP/probe-work"
mkdir -p "$WORK"

# ── 1. 프로브 전용 스텁 (저장소 밖에 만든다 — 실제 빌드와 섞이지 않게) ──
#
# **2026.08.17(B-190)에 여섯이 늘었다 — `ExcelExporter.kt`가 목록에 있으면서도 실제로는
# 타입 검사되지 않던 것을 고치기 위해서다.** 그 파일은 머리 두 줄이
# `appContext = context.applicationContext` · `db = AppDatabase.getDatabase(appContext)`인데
# **둘 다 미해석이었다**(`Context`에 `applicationContext`가 없었고, 아래 `AppDatabase` 스텁에
# `getDatabase`가 없었다). 수신 타입이 풀리지 않으면 그 아래 식의 진짜 타입 오류는
# **접혀서 안 보이는 것이 아니라 애초에 발행되지 않는다** — 즉 목록에 넣어 두고도 안 보는 자리였다.
# 실측: 그 파일 **732 → 28**, 프로브 전체 **1969 → 1190**. 스텁 여섯 중 위 둘이 거의 전부를 정한다
# (나머지 넷 `Intent`·`Uri`·`Toast`·`FileProvider`만 넣으면 732 → 656으로 거의 안 준다).
#
# ⚠️ **스텁은 진짜의 모양을 *비추기만* 한다 — 여기서 표면을 늘리면 프로브가 거짓 초록을 낸다.**
# 진짜에 없는 멤버·헐거운 시그니처를 적으면 **로컬은 초록이고 CI가 빨간불**이다
# (`tools/jvm-stubs/AiServiceStub.kt` 말미가 그 실증이고, `check_stub_shadow_use.sh`가
# 시험 쪽에 대해 세운 그물과 같은 취지다). 좁은 것은 안전하고(진짜가 받는 것을 못 받을 뿐이라
# 가짜 오류로 드러난다) **넓은 것이 위험하다.**
cat > "$WORK/AndroidProbeStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package android.content
// `open`인 것은 `android.app.Application`이 상속해야 하기 때문이다 — 그 관계가 있어야
// `appContext as? NovelCharacterApp`이 컴파일된다(관계 없는 타입 간 캐스트는 오류다).
open class Context {
    val filesDir: java.io.File get() = java.io.File(".")
    val cacheDir: java.io.File get() = java.io.File(".")
    fun getString(id: Int): String = ""
    fun getString(id: Int, vararg args: Any?): String = ""
    val contentResolver: Any? get() = null
    val applicationContext: Context get() = this
    val packageName: String get() = ""
    fun startActivity(intent: Intent) {}
}

// `type`은 진짜에서 `getType()`/`setType()` 쌍이라 코틀린이 합성 속성으로 본다 — 대입 꼴을
// 그대로 재려면 스텁도 `var`여야 한다.
class Intent(action: String? = null) {
    var type: String? = null
    fun putExtra(name: String, value: android.os.Parcelable): Intent = this
    fun addFlags(flags: Int): Intent = this
    companion object {
        const val ACTION_SEND: String = "android.intent.action.SEND"
        const val EXTRA_STREAM: String = "android.intent.extra.STREAM"
        const val FLAG_GRANT_READ_URI_PERMISSION: Int = 1
        const val FLAG_ACTIVITY_NEW_TASK: Int = 2
        @JvmStatic fun createChooser(target: Intent, title: CharSequence?): Intent = target
    }
}
EOF

# `android.os.Parcelable`은 `Intent.putExtra(String, Parcelable)`의 인자 타입을 진짜와 같게
# 두기 위한 것이다 — `Uri`로 좁혀 적으면 그 한 호출은 통과하지만 **진짜보다 좁은 계약**이 된다.
cat > "$WORK/AndroidOsStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package android.os
interface Parcelable
EOF

# `Uri`는 이 프로브의 범위에서 **멤버 호출이 하나도 없다**(전부 인자·필드 타입으로만 쓰인다 —
# `ExcelExporter.writeToUri`·`ImageImportHelper`·`OrganizeFolderService`). 그래서 빈 선언이
# 진짜를 정직하게 비춘다. 멤버를 쓰는 코드가 새로 들어오면 **미해석으로 드러난다**(그것이 옳다).
cat > "$WORK/AndroidNetStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package android.net
class Uri : android.os.Parcelable
EOF

cat > "$WORK/AndroidWidgetStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package android.widget
class Toast {
    fun show() {}
    companion object {
        const val LENGTH_SHORT: Int = 0
        const val LENGTH_LONG: Int = 1
        @JvmStatic fun makeText(context: android.content.Context?, text: CharSequence, duration: Int): Toast = Toast()
        @JvmStatic fun makeText(context: android.content.Context?, resId: Int, duration: Int): Toast = Toast()
    }
}
EOF

cat > "$WORK/AndroidAppStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
// 진짜 계보는 Application : ContextWrapper : Context다 — 중간 고리는 이 프로브가 쓰지 않으므로
// **관계만** 비춘다(NovelCharacterApp이 Context로 캐스트되는 성질이 그 관계에 걸려 있다).
package android.app
open class Application : android.content.Context()
EOF

cat > "$WORK/AndroidxCoreStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package androidx.core.content
object FileProvider {
    @JvmStatic fun getUriForFile(context: android.content.Context, authority: String, file: java.io.File): android.net.Uri =
        android.net.Uri()
}
EOF

# `android.util.LruCache` — 2026.08.12(B-12)에 들어왔다. 종전에는 이 타입을 **`ui/**`의
# 어댑터 넷만** 썼고 그쪽은 프로브의 범위 밖이라 스텁이 필요 없었는데, 썸네일 캐시를
# 인자로 받는 공용 로더(`util/CharacterImageLoader.kt`)가 생기며 범위 안으로 들어왔다.
# **없는 채로 두면 신규 오류 2건이 기준선에 얹혀 진짜 결함을 덮는다** — 프로브의 값은
# base 대 cur의 차이가 0이라는 데 있으므로, 가짜 오류를 남겨 두면 그 값이 줄어든다.
# 시그니처는 실제 프레임워크와 같게 둔다(`sizeOf` 재정의가 컴파일돼야 의미가 있다).
cat > "$WORK/AndroidUtilStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package android.util
open class LruCache<K : Any, V : Any>(maxSize: Int) {
    open fun get(key: K): V? = null
    open fun put(key: K, value: V): V? = null
    open fun remove(key: K): V? = null
    open fun evictAll() {}
    open fun size(): Int = 0
    open fun maxSize(): Int = 0
    protected open fun sizeOf(key: K, value: V): Int = 1
}
EOF

# AppDatabase는 **실제 파일에서 DAO 접근자만 뽑아** 세운다 — 손으로 적으면 접근자가 늘 때 낡는다.
# `getDatabase`도 **실제 선언에서 뽑는다**(2026.08.17 · B-190) — 이 한 줄이 없어서
# `ExcelExporter.db`가 미해석이었고, 그 파일의 절반이 거기서 연쇄로 죽었다.
# 이름이 바뀌면 `grep`이 빈손이 되고 **그 순간 프로브가 빨간불로 말한다**(손으로 적으면 조용히 낡는다).
{
  echo "// 프로브 전용 — 실제 AppDatabase의 DAO 접근자만 뽑아 세운다(생성 시각의 실제 목록)."
  echo "package com.novelcharacter.app.data.database"
  echo
  echo "import com.novelcharacter.app.data.dao.*"
  echo
  # `: RoomDatabase()`는 진짜 선언 그대로다 — `androidx.room.withTransaction`이 그 수신 타입의
  # 확장이라, 이 관계가 없으면 저장소·가져오기의 트랜잭션 블록이 통째로 미해석이 된다(B-190).
  echo "abstract class AppDatabase : androidx.room.RoomDatabase() {"
  grep -o 'abstract fun [a-zA-Z]*(): [A-Za-z]*' \
    "$REPO/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
  echo "    companion object {"
  grep -o 'fun getDatabase(context: Context): AppDatabase' \
    "$REPO/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt" \
    | head -1 \
    | sed 's|Context|android.content.Context|; s|$|: AppDatabase = throw UnsupportedOperationException("프로브 전용")|' \
    | sed 's|): AppDatabase: AppDatabase|): AppDatabase|'
  echo "    }"
  echo "}"
} > "$WORK/AppDatabaseProbe.kt"

# ── 1-b. 앱 소유 심볼은 **실제 자원·소스에서 뽑는다** (2026.08.17 · B-190) ──
#
# `R`을 손으로 적으면 **없는 문자열 이름이 통과해 검사가 거짓이 된다** — `R.string.오타`가
# 조용히 초록이면 프로브가 잡으라고 있는 그 부류를 스스로 놓친다. 그래서 aapt가 하는 것과
# 같은 자리에서 뜬다: `res/values*/`의 선언 + 파일 기반 자원의 파일명 + 레이아웃의 `@+id/`.
# (`check_resources.sh`는 *코드가 없는 문자열을 부르는가*를 보고, 이쪽은 **타입 검사 중에**
# 같은 것을 본다 — 둘은 축이 다르고 겹치는 만큼이 이득이다.)
#
# 값을 전부 0으로 두지 않고 하나씩 올리는 것은 `when(id)` 분기가 같은 상수로 겹치는 것을
# 피하기 위해서다(진짜 R도 서로 다른 값이다).
{
  echo "// 프로브 전용 — 실제 res/에서 뽑은 자원 이름(생성 시각의 실제 목록)."
  echo "package com.novelcharacter.app"
  echo
  {
    # 파일 기반 자원: res/<타입>[-수식어]/<이름>.<확장자>
    find "$REPO/app/src/main/res" -mindepth 2 -maxdepth 2 -type f -print \
      | sed -E 's|.*/res/([^/-]+)[^/]*/([^/.]+)\..*$|\1 \2|'
    # values 계열 선언
    grep -hoE '<(string|color|dimen|bool|integer|fraction|style|attr|plurals|string-array|integer-array|array)[[:space:]]+name="[^"]+"' \
      "$REPO"/app/src/main/res/values*/*.xml 2>/dev/null \
      | sed -E 's|<([a-z-]+)[[:space:]]+name="([^"]+)"|\1 \2|' \
      | sed -E 's|^(string-array\|integer-array) |array |'
    # <item name="X" type="Y"/> — ids.xml이 이 꼴이다
    grep -hoE '<item[[:space:]]+name="[^"]+"[[:space:]]+type="[^"]+"' \
      "$REPO"/app/src/main/res/values*/*.xml 2>/dev/null \
      | sed -E 's|<item[[:space:]]+name="([^"]+)"[[:space:]]+type="([^"]+)"|\2 \1|'
    # 레이아웃·메뉴·내비게이션이 선언하는 id
    grep -rhoE '@\+id/[A-Za-z0-9_]+' "$REPO/app/src/main/res" 2>/dev/null \
      | sed -E 's|@\+id/|id |'
  } | grep -vE '^values ' | sort -u | awk '
      # aapt와 같은 규칙으로 이름을 다듬는다 — `.`을 품은 style 이름(`Base.Theme.…`)이 실제로
      # 마흔 있고, 다듬지 않으면 코틀린이 `name contains illegal characters`로 죽는다.
      # 진짜 R도 `R.style.Base_Theme_NovelCharacter`다.
      { name = $2; gsub(/[^A-Za-z0-9_]/, "_", name)
        if (seen[$1 "/" name]++) next                   # 다듬은 뒤 겹치는 것은 한 번만
        if ($1 != prev) { if (prev != "") print "    }"; print "    object " $1 " {"; prev = $1 }
        printf "        const val `%s`: Int = %d\n", name, ++n }
      END { if (prev != "") print "    }" }' \
    | sed '1s|^|object R {\n|'
  echo "}"
} > "$WORK/RProbe.kt"

# `NovelCharacterApp`도 **실제 파일에서 저장소 접근자만 뽑아** 세운다.
# 이 프로브 범위가 무는 것은 `operationLogRepository` 하나지만(`ExcelExporter`·`ResultNotify`),
# 목록으로 뜨면 접근자가 늘거나 이름이 바뀔 때 함께 따라간다. **`data/repository`에 실재하는
# 타입만 남긴다** — `BackupStatusStore`처럼 범위 밖 타입을 실으면 그 자체가 미해석이 되어
# 이 스텁이 노이즈의 근원이 된다.
{
  echo "// 프로브 전용 — 실제 NovelCharacterApp의 저장소 접근자만 뽑아 세운다."
  echo "package com.novelcharacter.app"
  echo
  echo "import com.novelcharacter.app.data.repository.*"
  echo
  echo "class NovelCharacterApp : android.app.Application() {"
  tr '\n' ' ' < "$REPO/app/src/main/java/com/novelcharacter/app/NovelCharacterApp.kt" \
    | grep -oE 'val [a-zA-Z]+ by lazy \{ *[A-Za-z]+\(' \
    | sed -E 's|val ([a-zA-Z]+) by lazy \{ *([A-Za-z]+)\(|\1 \2|' \
    | while read -r prop type; do
        [ -f "$REPO/app/src/main/java/com/novelcharacter/app/data/repository/$type.kt" ] || continue
        echo "    val $prop: $type get() = throw UnsupportedOperationException(\"프로브 전용\")"
      done
  echo "}"
} > "$WORK/NovelCharacterAppProbe.kt"

# ── 2. 대상 파일 목록 ──
M="$REPO/app/src/main/java/com/novelcharacter/app"
{
  # `AppSettingsBindings.kt`도 뺀다(B-105) — 그 파일은 **설정 저장소를 잇는 것이 일**이라
  # `ai/`·`backup/`·`ui/`를 import 하는데 이 프로브의 범위는 excel·model·dao·util·repository다.
  # 넣으면 그 import가 전부 미해석으로 떠 신규 오류만 수십 줄 늘고 **진짜 결함을 덮는다.**
  # 그 파일의 컴파일 증명은 CI뿐이다(`ExcelImporter.kt`와 같은 부류이며, 짝인 선언
  # `AppSettingsKeys.kt`는 순수라 여기서도 순수 JVM 시험에서도 그대로 검사된다).
  ls "$M"/excel/*.kt | grep -vE "ExcelImporter.kt|AppSettingsBindings.kt"
  ls "$M"/data/model/*.kt "$M"/data/dao/*.kt "$M"/util/*.kt "$M"/data/repository/*.kt
  echo "$WORK/AndroidProbeStubs.kt"
  echo "$WORK/AndroidUtilStubs.kt"
  echo "$WORK/AndroidOsStubs.kt"
  echo "$WORK/AndroidNetStubs.kt"
  echo "$WORK/AndroidWidgetStubs.kt"
  echo "$WORK/AndroidAppStubs.kt"
  echo "$WORK/AndroidxCoreStubs.kt"
  echo "$WORK/AppDatabaseProbe.kt"
  echo "$WORK/RProbe.kt"
  echo "$WORK/NovelCharacterAppProbe.kt"
  echo "$TOOLS/jvm-stubs/AndroidLogStub.kt"
} | grep -vE "util/(AiImagePreparer|AiImageAttach)\.kt" > "$WORK/files.txt"

# ── 3. 컴파일 ──
#
# **출력은 `sort`이지 `sort -u`가 아니다 (B-211, 2026.08.16).** 줄 번호는 지우고(한 줄만
# 넣어도 그 아래가 전부 '신규'가 된다) **겹은 남긴다** — 둘은 다른 일이다.
# `-u`를 쓰면 키가 (파일, 문구)의 *집합*이 되어, **이미 그 문구를 들고 있는 파일에 같은 문구의
# 새 오류가 나면 `comm -13`에 아무것도 안 나온다.** 기준선에 오류를 열 줄쯤 이고 있는 파일이
# 실제로 셋이고(`ExcelExporter.kt`·`ExcelTransferController.kt`·`ExcelImportService.kt` — B-190·B-211),
# 그중 넷은 `cannot infer type for this parameter.`처럼 **자리를 말하지 않는 일반 문구**라
# 그 파일 어디서 새로 나든 묻혔다. 겹을 남기면 `comm`이 *많아진 만큼*을 그대로 낸다
# (실측: base 1건·cur 2건이면 `comm -13`이 한 줄을 낸다 — 정렬만 같으면 다중집합 차가 나온다).
#
# **이 저장소는 같은 함정에 이미 한 번 물렸다** — `tools/triage_unresolved.sh` 머리의
# *"1차(2026.08.03): `sort -u`가 같은 문구의 진짜 신규를 함께 감췄다"*가 그 기록이고,
# 그때는 차분 컴파일 쪽만 고쳤다. 프로브 둘에는 그대로 남아 있었다.
# 주의: 컴파일러 자신의 클래스패스에도 coroutines가 있어야 한다(없으면 CoroutineScope
# NoClassDefFoundError로 **컴파일이 시작조차 못 하고**, 오류 0으로 보여 헛된 안심을 준다).
CP="$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar:$SP/out-room"
CP="$CP:$SP/json-20240303.jar:$SP/gson-$GSON_VER.jar"
CP="$CP:$(excel_cp "$SP"):$SP/kotlinx-coroutines-core-jvm.jar"

java -cp "$SP/kotlin-compiler-embeddable-2.0.21.jar:$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar:$SP/kotlinx-coroutines-core-jvm.jar:$SP/trove4j.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -no-stdlib -cp "$CP" -d "$WORK/out" "@$WORK/files.txt" 2>&1 \
  | grep -E "error:" \
  | sed "s|$REPO/||" \
  | sed -E 's/^([^:]+):[0-9]+:[0-9]+: (error: .*)$/\1| \2/' \
  | sort > "$OUT"

ERRS=$(wc -l < "$OUT")
echo "오류 ${ERRS}건(겹 포함) → $OUT"

# **스텁 자신이 오류를 내면 곧바로 죽는다** (2026.08.17 · B-190).
# 스텁은 대부분 실제 소스·자원에서 뜨므로 저쪽이 바뀌면 여기가 깨질 수 있는데, 그때 나오는
# 오류는 **기준선과 현재 양쪽에 똑같이 얹혀 `comm -13`이 아무것도 내지 않는다** — 즉
# *프로브가 망가진 것*과 *새 오류가 없는 것*이 겉이 같아진다. 이 저장소가 검사마다 자기 시험을
# 붙이는 것과 같은 이유이며, 실제로 R 생성기의 첫 판이 style 이름의 `.` 때문에 40건을 냈다.
STUB_ERRS=$(grep -cF -e "$WORK/" -e "$TOOLS/jvm-stubs/" "$OUT" || true)
if [ "${STUB_ERRS:-0}" -ne 0 ]; then
  echo "⚠️  스텁 자신이 ${STUB_ERRS}건 오류다 — 이 산출은 base 대 cur 비교에 쓸 수 없다." >&2
  grep -F -e "$WORK/" -e "$TOOLS/jvm-stubs/" "$OUT" >&2
  exit 1
fi
# **오류 0을 그냥 믿지 않는다.** 컴파일러가 시작조차 못 하면(예: 컴파일러 클래스패스에
# coroutines 누락) 출력이 비어 "오류 0"으로 보인다 — 2026-08-01에 실제로 겪었고, 헛된
# 안심을 준다. 오류가 0인데 클래스 파일도 0이면 그 경우다.
# (오류가 있으면 kotlinc는 원래 클래스 파일을 내지 않으므로 그때는 판정하지 않는다.)
if [ "$ERRS" -eq 0 ] && [ "$(find "$WORK/out" -name '*.class' 2>/dev/null | wc -l)" -eq 0 ]; then
  echo "⚠️  오류 0인데 클래스 파일도 0이다 — 컴파일이 시작조차 못 했을 수 있다. 믿지 말 것." >&2
  exit 1
fi
