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
# **2026.08.17(B-190)에 크게 늘었다 — `ExcelExporter.kt`가 목록에 있으면서도 실제로는
# 타입 검사되지 않던 것을 고치기 위해서다.** 그 파일은 머리 두 줄이
# `appContext = context.applicationContext` · `db = AppDatabase.getDatabase(appContext)`인데
# **둘 다 미해석이었다**(`Context`에 `applicationContext`가 없었고, 아래 `AppDatabase` 스텁에
# `getDatabase`가 없었다). 수신 타입이 풀리지 않으면 그 아래 식의 진짜 타입 오류는
# **접혀서 안 보이는 것이 아니라 애초에 발행되지 않는다** — 즉 목록에 넣어 두고도 안 보는 자리였다.
# **가장 큰 몫은 `androidx.room.withTransaction`이었다**(`setup_jvm_env.sh`) — 없으면 그 블록 안이
# suspend 문맥이 아니게 되어 `ExcelImportService.kt` 하나에서 소음이 55건 발행된다.
# **스텁 수도 감소량도 여기 적지 않는다** — 이 저장소가 반복해 물린 자리다(값을 적으면 낡는다).
# 이 판의 실측과 남은 갈래는 `docs/remaining_work_2026-07.md` 4장 `B-190`·`B-251`이 든다.
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
    // `Any?`가 아니라 진짜 타입이다(2026.08.20) — `Any?`인 동안 `openOutputStream` 등
    // 수신 아래의 진짜 오류가 **발행조차 되지 않았다**(B-190이 이름 붙인 그 부류).
    // ContentResolver 스텁은 아래에 있고, 범위 안 코드가 실제로 부르는 멤버만 연다.
    val contentResolver: ContentResolver get() = ContentResolver()
    val applicationContext: Context get() = this
    val packageName: String get() = ""
    fun startActivity(intent: Intent) {}
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        throw UnsupportedOperationException("프로브 전용")
    // `getDatabasePath`는 `StorageAnalyzer` 하나가 문다 — 없는 동안 그 파일의 오류 아홉이
    // **전부 이 한 줄의 그림자**였다(수신 타입이 미해석이라 `.isFile`·`.length()`·`.name`이
    // 줄줄이 미해석이 되고 그 위의 람다가 타입 추론에 실패했다). 착수 대조에서 걸렸고
    // 「등재 문턱」(CLAUDE.md v2.2)대로 등재하지 않고 이 자리에서 처리했다.
    fun getDatabasePath(name: String): java.io.File = java.io.File(name)
    companion object {
        // 진짜는 `Context`의 static 필드다 — 코틀린은 `Context.MODE_PRIVATE`로 본다.
        const val MODE_PRIVATE: Int = 0
    }
}

// `SharedPreferences`는 2026.08.19(B-251 ⓐ)에 들어왔다. 설정 저장 경로 일곱 파일이
// **목록에 있으면서도 실제로는 타입 검사되지 않던 자리**다(B-190이 `ExcelExporter.kt`에서 겪은 것과 같은 부류 —
// 수신 타입이 미해석이면 그 아래 진짜 오류는 *발행조차 되지 않는다*).
//
// **`getAll()`이 아니라 `val all`로 적는다** — 진짜는 자바 인터페이스라 코틀린이 합성 속성을
// 만들지만(호출부는 `prefs.all`이다) **코틀린으로 선언한 함수에는 그 합성이 안 생긴다.**
// 속성으로 적으면 `prefs.getAll()` 꼴을 못 받아 진짜보다 **좁지만**, 좁은 쪽은 가짜 오류로
// 드러나고 넓은 쪽은 거짓 초록을 낸다 — 이 파일 머리의 경고 그대로 좁은 쪽을 고른다.
// 리스너 등록/해제 둘도 같은 이유로 뺀다(쓰는 자리가 이 범위에 없다 — 생기면 미해석으로 드러난다).
interface SharedPreferences {
    val all: Map<String, Any?>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

// `ContentResolver`는 2026.08.20에 들어왔다 — `ExcelExporter`의 저장 실패 처분이
// `DocumentsContract.deleteDocument(ContentResolver, Uri)`를 부르게 되면서, `contentResolver`가
// `Any?`인 채로는 그 호출이 가짜 인자 불일치를 낸다. 멤버는 범위 안 코드가 실제로 부르는
// 둘만 연다(진짜 시그니처 그대로 — 반환은 nullable 스트림이다). 쿼리·커서 계열은 일부러
// 안 연다: 그쪽 표면은 문서 트리 전체라, 넓은 스텁이 곧 거짓 초록이다(4-b의 판정 유지).
class ContentResolver {
    fun openInputStream(uri: android.net.Uri): java.io.InputStream? = null
    fun openOutputStream(uri: android.net.Uri): java.io.OutputStream? = null
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

// `Activity`는 2026.08.20에 들어왔다 — `ExcelExporter`가 종결 고지의 화면 판정
// (`WeakReference<Activity>` + isFinishing/isDestroyed)을 형제 `ExcelImporter`와 같은 꼴로
// 갖게 되면서 excel/ 범위가 이 타입을 처음 문다(LruCache가 들어온 것과 같은 부류 —
// 없는 채로 두면 신규 오류가 기준선에 얹혀 진짜 결함을 덮는다). 진짜 계보의 중간 고리
// (ContextThemeWrapper·ContextWrapper)는 범위가 안 쓰므로 관계만 비추고, 멤버도 범위가
// 실제로 읽는 둘만 둔다(진짜는 자바 메서드라 코틀린이 합성 속성으로 본다 — val이 그 꼴이다).
open class Activity : android.content.Context() {
    val isFinishing: Boolean get() = false
    val isDestroyed: Boolean get() = false
}
EOF

# `android.provider.DocumentsContract`의 **deleteDocument 하나만** 세운다 (2026.08.20).
# 4-b가 문서 트리 계열을 "넓은 스텁이 곧 거짓 초록"이라 열지 않기로 한 판정은 그대로다 —
# 그 판정이 막는 것은 *쿼리·커서·트리 URI의 넓은 표면*이고, 여기서는 `ExcelExporter`의
# 저장 실패 처분이 실제로 부르는 정적 메서드 하나를 **진짜 시그니처 그대로** 연다
# (`deleteDocument(@NonNull ContentResolver, @NonNull Uri): Boolean`). 좁은 것은 안전하다 —
# 다른 멤버를 쓰는 코드는 종전의 이름 미해석 대신 멤버 미해석으로 똑같이 드러난다.
cat > "$WORK/AndroidProviderStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package android.provider
object DocumentsContract {
    @JvmStatic fun deleteDocument(content: android.content.ContentResolver, documentUri: android.net.Uri): Boolean =
        throw UnsupportedOperationException("프로브 전용")
}
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

# ── 1-a. DataStore·AppCompat 스텁 (2026.08.19 · B-251 ⓐ) ──
#
# `util/`의 설정 저장 경로 일곱 파일이 여기 걸려 있었다. `SharedPreferences` 다섯은 위 스텁이
# 열고, `androidx.datastore`를 쓰는 둘(`ImageSettingsStore`·`ThemeHelper`)이 이 블록이다.
#
# **이 갈래가 위험한 부류라고 B-251 행이 미리 적어 두었다** — `Flow` + `Preferences.Key` +
# suspend `edit {}`라 표면이 넓고, **진짜보다 헐거우면 로컬 초록·CI 빨간불**이다.
# 그래서 두 가지를 지켰다:
#   ⓐ **모양은 진짜 그대로** — `get`은 `<T>(Key<T>): T?`이고 `set`은 `<T>(Key<T>, T)`다.
#      `Key<*>`·`Any?`로 뭉개면 `it[KEY_QUALITY] = "문자열"` 같은 진짜 오류가 통과한다.
#   ⓑ **애매하면 좁게** — `preferencesDataStore`의 선택 인자 셋(corruptionHandler ·
#      produceMigrations · scope)은 아예 안 적었다. 적으려면 `DataMigration`·
#      `ReplaceFileCorruptionHandler`까지 세워야 하고, `Any?`로 뭉개면 **진짜가 거부하는 인자를
#      받는다.** 빼 두면 그 인자를 쓰는 코드가 새로 들어올 때 **미해석으로 드러난다**(가짜 오류 —
#      이 파일 머리가 말하는 안전한 방향이다).
#
# `Preferences.Key`와 `MutablePreferences`를 **abstract로** 둔 것도 같은 결이다. 진짜는
# `internal constructor`라 앱이 세울 수 없는데, 프로브는 전부 한 모듈로 컴파일하므로
# `internal`이 그대로 보인다 — 그러면 진짜보다 넓어진다. abstract면 앱 쪽에서 세울 수 없어
# **같은 금지가 성립한다**(만드는 것은 이 파일 안의 private 구현체뿐이다).
cat > "$WORK/AndroidxDataStoreCoreStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package androidx.datastore.core

interface DataStore<T> {
    val data: kotlinx.coroutines.flow.Flow<T>
    suspend fun updateData(transform: suspend (t: T) -> T): T
}
EOF

cat > "$WORK/AndroidxDataStorePrefsCoreStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package androidx.datastore.preferences.core

import androidx.datastore.core.DataStore

abstract class Preferences {
    // 진짜는 `internal constructor`를 가진 final 클래스다 — 한 모듈로 컴파일하는 프로브에서는
    // internal이 그대로 보이므로 abstract로 같은 금지를 만든다(앱은 만들 수 없고 읽기만 한다).
    abstract class Key<T> internal constructor(val name: String)

    abstract operator fun <T> contains(key: Key<T>): Boolean
    abstract operator fun <T> get(key: Key<T>): T?
    abstract fun asMap(): Map<Key<*>, Any>
}

abstract class MutablePreferences internal constructor() : Preferences() {
    abstract operator fun <T> set(key: Key<T>, value: T)
    abstract fun <T> remove(key: Key<T>): T
    abstract fun clear()
}

private class ProbeKey<T>(name: String) : Preferences.Key<T>(name)

fun booleanPreferencesKey(name: String): Preferences.Key<Boolean> = ProbeKey(name)
fun intPreferencesKey(name: String): Preferences.Key<Int> = ProbeKey(name)
fun longPreferencesKey(name: String): Preferences.Key<Long> = ProbeKey(name)
fun floatPreferencesKey(name: String): Preferences.Key<Float> = ProbeKey(name)
fun doublePreferencesKey(name: String): Preferences.Key<Double> = ProbeKey(name)
fun stringPreferencesKey(name: String): Preferences.Key<String> = ProbeKey(name)
fun stringSetPreferencesKey(name: String): Preferences.Key<Set<String>> = ProbeKey(name)

// 진짜와 같은 시그니처다 — **suspend 블록을 받는 것**이 요점이고(`androidx.room.withTransaction`을
// 들일 때와 같은 이유다), 몸통은 부르지 않는다. 블록 안은 그래도 그대로 타입 검사된다.
suspend fun DataStore<Preferences>.edit(
    transform: suspend (MutablePreferences) -> Unit
): Preferences = throw UnsupportedOperationException("프로브 전용")
EOF

cat > "$WORK/AndroidxDataStorePrefsStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package androidx.datastore.preferences

// 선택 인자 셋(corruptionHandler·produceMigrations·scope)은 일부러 뺐다 — 위 블록 머리의 ⓑ.
fun preferencesDataStore(
    name: String
): kotlin.properties.ReadOnlyProperty<
    android.content.Context,
    androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
> = throw UnsupportedOperationException("프로브 전용")
EOF

# `AppCompatDelegate`는 `ThemeHelper` 하나가 문다. 진짜는 abstract class + static 멤버라
# 코틀린에서 `AppCompatDelegate.setDefaultNightMode(…)` 꼴로 닿는데, object로 두면 같은 호출이
# 그대로 서면서 **상속은 막힌다**(진짜보다 좁다 — 안전한 방향).
cat > "$WORK/AndroidxAppCompatStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package androidx.appcompat.app

object AppCompatDelegate {
    const val MODE_NIGHT_NO: Int = 1
    const val MODE_NIGHT_YES: Int = 2
    const val MODE_NIGHT_FOLLOW_SYSTEM: Int = -1
    const val MODE_NIGHT_AUTO_BATTERY: Int = 3
    const val MODE_NIGHT_UNSPECIFIED: Int = -100
    @JvmStatic fun setDefaultNightMode(mode: Int) {}
    @JvmStatic fun getDefaultNightMode(): Int = MODE_NIGHT_UNSPECIFIED
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
    | sed 's|: Context)|: android.content.Context)|' \
    | sed 's|$| = throw UnsupportedOperationException("프로브 전용")|'
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
      | sed -E 's#^(string-array|integer-array) #array #'
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

# `NovelCharacterApp`도 **실제 파일에서 접근자만 뽑아** 세운다.
# 목록으로 뜨면 접근자가 늘거나 이름이 바뀔 때 함께 따라간다. **프로브가 아는 타입만 남긴다** —
# `BackupStatusStore`처럼 범위 밖 타입을 실으면 그 자체가 미해석이 되어 이 스텁이 노이즈의 근원이 된다.
#
# **2026.08.19(B-251)에 두 자리를 넓혔다 — `share/`를 들이려면 `database`가 있어야 했다.**
#   ⓐ 꼴: 종전 정규식은 `by lazy { Type( }`만 봤는데 `database`는 `by lazy { AppDatabase.getDatabase(this) }`라
#      **점 하나 때문에 안 걸렸다.** 이제 `Type(` 와 `Type.factory(` 둘 다 뜬다(`[.(]`).
#   ⓑ 자리: 종전에는 `data/repository/`에 있는 타입만 남겼는데 `AppDatabase`는 `data/database/`에 살고
#      **이 프로브가 스스로 세우는 스텁**이다(바로 위 `AppDatabaseProbe.kt`). 남길 자격은 *어느 폴더냐*가
#      아니라 *이 컴파일이 그 타입을 아는가*이므로 그 자리도 함께 본다.
# **왜 한 줄이 아니라 결함인가:** 없는 동안 `app.database`가 미해석이라 `share/`의 두 파일에서
# **그 아래 진짜 오류가 발행조차 되지 않았다**(B-190이 `ExcelExporter.kt`에서 겪은 그 부류 —
# 목록에 넣어 두고도 안 보는 자리다). 실측: 넣기 전 신규 68건이 전부 이 한 줄의 그림자였고,
# 고치니 **신규 0**이 됐다.
#
# **2026.08.19 콜드 검토가 위 넓히기에서 두 결함을 냈다.**
#   ⓐ **`X.y(` 꼴이 잡는 것은 *수신자*이지 타입이 아니다.** 그것이 타입인 것은
#      `AppDatabase.getDatabase`처럼 **자기 타입을 돌려주는 팩토리**일 때뿐이고,
#      실제로 `val recentActivityDao by lazy { database.recentActivityDao() }`는 `database`를
#      잡는다(속성 이름이다). 오늘은 `database.kt`가 없어 조용히 빠졌지만, **이름이 겹치는
#      파일이 하나 생기는 순간 엉뚱한 타입을 단 스텁이 나온다** — 거짓 초록이다.
#      코틀린 관례상 타입은 대문자로 시작하므로 소문자로 시작하는 것은 타입이 아니다.
#   ⓑ **뺀 것을 말하지 않았다.** 열여섯 중 열넷을 내면서 산출은 침묵했다. 이 저장소가
#      2026.08.19 앞 판에서 세운 규약이 정확히 그것이다 — ***검사는 무엇을 못 보는지와
#      얼마나 보는지를 둘 다 말해야 한다.*** 스텁도 같다: 빠진 접근자는 나중에 미해석
#      노이즈로 나타나는데, 그때 그것이 *스텁이 뺀 것*인지 *진짜 결함*인지 가릴 수 없다.
#
# 접근자 목록을 파일로 먼저 뜬다 — 파이프 안에서 세면 서브셸이라 수가 안 남는다.
tr '\n' ' ' < "$REPO/app/src/main/java/com/novelcharacter/app/NovelCharacterApp.kt" \
  | grep -oE 'val [a-zA-Z]+ by lazy \{ *[A-Za-z]+[.(]' \
  | sed -E 's|val ([a-zA-Z]+) by lazy \{ *([A-Za-z]+)[.(]|\1 \2|' > "$WORK/app-accessors.txt"

ACC_SKIP=""
{
  echo "// 프로브 전용 — 실제 NovelCharacterApp의 접근자만 뽑아 세운다."
  echo "package com.novelcharacter.app"
  echo
  echo "import com.novelcharacter.app.data.repository.*"
  echo "import com.novelcharacter.app.data.database.*"
  echo
  echo "class NovelCharacterApp : android.app.Application() {"
  while read -r prop type; do
    case "$type" in
      [A-Z]*) ;;
      *) ACC_SKIP="$ACC_SKIP $prop(수신자 [$type]는 타입이 아니다)"; continue ;;
    esac
    if [ -f "$REPO/app/src/main/java/com/novelcharacter/app/data/repository/$type.kt" ] \
       || [ -f "$REPO/app/src/main/java/com/novelcharacter/app/data/database/$type.kt" ]; then
      echo "    val $prop: $type get() = throw UnsupportedOperationException(\"프로브 전용\")"
    else
      ACC_SKIP="$ACC_SKIP $prop($type — 프로브 범위 밖)"
    fi
  done < "$WORK/app-accessors.txt"
  echo "}"
} > "$WORK/NovelCharacterAppProbe.kt"

ACC_SEEN=$(wc -l < "$WORK/app-accessors.txt")
ACC_EMIT=$(grep -c 'get() = throw' "$WORK/NovelCharacterAppProbe.kt" || true)
echo "NovelCharacterApp 스텁: 접근자 ${ACC_SEEN}개 중 ${ACC_EMIT}개.${ACC_SKIP:+ 뺀 것 —$ACC_SKIP}"
# **하나도 못 내면 죽는다** — 정규식이 깨지거나 파일이 개명되면 빈 스텁이 나오는데,
# 그러면 `app.*`가 통째로 미해석이 되고 그 아래 진짜 오류는 발행조차 되지 않는다.
# base·cur 양쪽이 똑같이 비므로 `comm -13`도 침묵한다(이 파일이 스텁 가드를 둔 것과 같은 이유).
if [ "${ACC_EMIT:-0}" -eq 0 ]; then
  echo "⚠️  NovelCharacterApp 스텁이 접근자를 하나도 못 냈다 — 생성기가 깨졌다. 이 산출을 믿지 말 것." >&2
  exit 1
fi

# ── 2. 대상 파일 목록 ──
M="$REPO/app/src/main/java/com/novelcharacter/app"

# `share/`에서 뺄 것 — 갈래 ⓒ(android.graphics·print·webkit). 사유는 아래 목록 블록 주석에 있다.
SHARE_EXCLUDE="CharacterCardRenderer PdfExporter"
# `data/`에서 뺄 것 — `AppDatabase.kt`는 **이 프로브가 스스로 스텁으로 세우는 타입**이라
# (위 `AppDatabaseProbe.kt`) 진짜를 함께 넣으면 **중복 선언으로 컴파일이 통째로 죽는다.**
# 다른 제외와 성질이 다르다: 저쪽은 *못 보는 것*이고 이쪽은 *스텁으로 대신 보는 것*이다.
DATA_EXCLUDE="AppDatabase"
# `excel/`에서 뺄 것 — 사유는 아래 목록 블록 주석에 있다.
# **2026.08.19 콜드 검토가 이 자리를 명단 꼴로 세웠다.** 종전에는 `grep -vE "A.kt|B.kt"`로
# **자리에 박혀 있었고 두 가지가 달랐다:** ⓐ 패턴이 **고정되지 않아**(`/(…)\.kt$`가 아니다)
# `MyExcelImporter.kt` 같은 새 파일이 **조용히 함께 빠진다** ⓑ **낡음 가드가 없어** 개명·삭제돼도
# 아무 말이 없다. 나머지 셋(`share/`·`data/`·아래 목록)은 이미 그 꼴인데 여기만 남아 있었다 —
# B-265가 고친 병(`이름으로 고르면 조용히 빠진다`)의 쌍둥이라 같은 슬라이스에서 처리했다.
EXCEL_EXCLUDE="ExcelImporter AppSettingsBindings"
# `ai/`에서 뺄 것 — 갈래는 excel·share와 같다(프레임워크·외부 라이브러리에 깊게 묶인 것).
#   · AiService  — OkHttp 전송 계층. **진짜 대신 하네스 스텁을 문다**(아래 files.txt) —
#                  둘을 함께 넣으면 중복 선언으로 컴파일이 통째로 죽는다.
#   · AiKeyStore — `androidx.security` 암호화 저장소. 스텁이 없다.
AI_EXCLUDE="AiService AiKeyStore"
# **낡은 제외는 빨간불이다** — 개명·삭제되면 그 이름이 아무것도 안 빼는데, 그러면 명단이
# *무엇을 빼고 있는지*를 말하지 않게 된다(이 저장소가 예외 표식에 대해 세운 규약과 같다).
#
# **이 가드가 목록 블록 *밖*에 있는 이유:** 그 블록은 `{ … } | grep … > files.txt`라
# **파이프라인의 일부여서 서브셸**이고, 그 안의 `exit 1`은 스크립트가 아니라 서브셸만 죽인다.
# 처음엔 안에 뒀다가 자기 시험에서 잡았다 — 경고만 찍히고 **잘린 목록으로 컴파일이 계속됐다**
# (실측: 목록이 잘려 오류가 615 → 3843이 되고도 종료코드는 0이었다).
for _x in $SHARE_EXCLUDE; do
  [ -f "$M/share/$_x.kt" ] || {
    echo "⚠️  share/ 제외 명단이 낡았다: $_x.kt가 없다 — 명단을 고칠 것." >&2; exit 1; }
done
# `data/`는 하위 폴더가 있으므로 자리를 고정하지 않고 트리에서 찾는다 — 파일이 다른 하위로
# **옮겨져도** 명단은 살아 있어야 하고(옮김은 개명이 아니다), 사라지면 그때 빨간불이다.
for _x in $DATA_EXCLUDE; do
  [ -n "$(find "$M/data" -name "$_x.kt" -print -quit)" ] || {
    echo "⚠️  data/ 제외 명단이 낡았다: $_x.kt가 없다 — 명단을 고칠 것." >&2; exit 1; }
done
for _x in $EXCEL_EXCLUDE; do
  [ -f "$M/excel/$_x.kt" ] || {
    echo "⚠️  excel/ 제외 명단이 낡았다: $_x.kt가 없다 — 명단을 고칠 것." >&2; exit 1; }
done
for _x in $AI_EXCLUDE; do
  [ -f "$M/ai/$_x.kt" ] || {
    echo "⚠️  ai/ 제외 명단이 낡았다: $_x.kt가 없다 — 명단을 고칠 것." >&2; exit 1; }
done
{
  # `AppSettingsBindings.kt`도 뺀다(B-105) — 그 파일은 **설정 저장소를 잇는 것이 일**이라
  # `ai/`·`backup/`·`ui/`를 import 하는데 이 프로브의 범위는 excel·model·dao·util·repository다.
  # 넣으면 그 import가 전부 미해석으로 떠 신규 오류만 수십 줄 늘고 **진짜 결함을 덮는다.**
  # 그 파일의 컴파일 증명은 CI뿐이다(`ExcelImporter.kt`와 같은 부류이며, 짝인 선언
  # `AppSettingsKeys.kt`는 순수라 여기서도 순수 JVM 시험에서도 그대로 검사된다).
  ls "$M"/excel/*.kt | grep -vE "/($(echo $EXCEL_EXCLUDE | tr ' ' '|'))\.kt$"
  # **`ai/`는 2026.08.20에 들어왔다** — 그날 '앱 설정' 시트가 AI 메시지 양식 열넷을 싣게 되면서
  # `excel/`이 `ai/`를 가리키기 시작했고, 범위 밖이던 그 폴더 탓에 **`AppSettingsKeys` 하나에서만
  # 신규 오류 61건이 떴다.** 전부 `unresolved reference 'ai'`의 그림자이지 결함이 아니었는데,
  # 그런 수는 다음 판이 기준선을 견줄 때 **진짜 결함을 덮는다**(이 프로브가 존재하는 이유의 반대).
  # 폴더를 통째로 들이고 뺄 것만 이름으로 적는 꼴은 `share/`·`data/`와 같다(B-251·B-265).
  ls "$M"/ai/*.kt | grep -vE "/($(echo $AI_EXCLUDE | tr ' ' '|'))\.kt$"
  ls "$M"/util/*.kt
  # **`data/`는 폴더째 들이고 뺄 것만 이름으로 적는다 (2026.08.19 · B-265).**
  # 종전 이 자리는 `data/model` · `data/dao` · `data/repository` · `data/settings`를
  # **이름으로 하나씩** 적었다. 그 꼴이면 **새 하위 폴더가 조용히 범위 밖에 남는다** —
  # `data/settings/`가 실제로 그렇게 빠져 있었고(B-251 ⓐ가 설정 저장 경로를 열어 놓고도
  # 이 폴더만 놓쳤다), `data/maintenance/`도 같은 이유로 창설 이래 한 번도 안 보였다.
  # 같은 날 콜드 검토가 `share/`에서 **이름 접두사로 고르던 것을 되돌린** 그 병의 쌍둥이다.
  #
  # **방향이 중요하다:** 명단에 없는 새 파일은 **자동으로 범위에 든다.** 그것이 프레임워크에
  # 깊게 묶인 것이라면 다음 판의 기준선 재현에서 수가 어긋나 **눈에 띄고**(그 자리에서
  # `DATA_EXCLUDE`에 적으면 된다), 반대로 자동으로 빠지게 두면 **아무 데서도 안 보인다.**
  # 시끄러운 쪽이 조용한 쪽보다 안전하다.
  #
  # **`data/maintenance/`가 이 뒤집기로 들어왔다** — `SystemMaintenanceService`는
  # **되돌릴 수 없는 일**(고아 행 삭제·순번 재배치·이미지 정리)을 하는데 순수 하네스에도
  # 프로브에도 없어 **컴파일 증명이 CI뿐이었다**(B-89가 `data/repository`를, B-110이
  # `OrganizeFolderService`를, B-251 ⓐ가 `share/`를 들인 것과 **같은 사유**다).
  # 등재 당시 실측은 **신규 115건**이었는데 **그 전부가 `RoomDatabase.openHelper`
  # 한 줄의 그림자였다**(B-190·B-251 ⓐ가 겪은 *수신 타입 하나가 아래를 통째로 죽이는* 그 모양).
  # 스텁에 그 줄을 세우니 **신규 0**이다.
  find "$M/data" -name '*.kt' | sort \
    | grep -vE "/($(echo $DATA_EXCLUDE | tr ' ' '|'))\.kt$"
  # `share/`는 2026.08.19(B-251)에 들어왔다 — **B-234 판이 재고 이 판에 넘긴 몫이다**
  # (*"커밋하지 않았다 — 프로브 기준선을 옮기는 것은 판당 하나이고 그 몫은 B-251이다"*).
  # 그 계층은 **월드패키지 교환 형식**을 다루는데 순수 하네스에도 프로브에도 없어
  # **컴파일 증명이 CI뿐이었다**(B-89가 `data/repository`를, B-110이 `OrganizeFolderService`를
  # 같은 사유로 들인 것과 같은 부류다 — 되돌리기 어려운 것을 다루는데 지역 증명이 없는 자리).
  #
  # **폴더를 통째로 들이고 뺄 것만 이름으로 적는다** — 첫 판은 반대로 `WorldPackage*.kt`라는
  # **이름 접두사**로 골랐는데, 같은 날 콜드 검토가 그것을 되돌렸다: 그 꼴이면 `share/`에
  # **새 파일이 생겨도 조용히 범위 밖에 남는다.** 이 저장소가 반복해 무는 모양 그대로다
  # (`check_view_probe_targets.sh`가 생긴 이유 · `check_list_cell_csv.sh`가 폴더로 좁혔던 것).
  # **방향이 중요하다:** 명단에 없는 새 파일은 **자동으로 범위에 든다.** 그것이 그래픽류라면
  # 다음 판의 기준선 재현에서 수가 어긋나 **눈에 띄고**(그 자리에서 빼면 된다), 반대로 자동으로
  # 빠지게 두면 **아무 데서도 안 보인다.** 시끄러운 쪽이 조용한 쪽보다 안전하다.
  ls "$M"/share/*.kt | grep -vE "/($(echo $SHARE_EXCLUDE | tr ' ' '|'))\.kt$"
  echo "$WORK/AndroidProbeStubs.kt"
  echo "$WORK/AndroidUtilStubs.kt"
  echo "$WORK/AndroidOsStubs.kt"
  echo "$WORK/AndroidNetStubs.kt"
  echo "$WORK/AndroidWidgetStubs.kt"
  echo "$WORK/AndroidAppStubs.kt"
  echo "$WORK/AndroidProviderStubs.kt"
  echo "$WORK/AndroidxCoreStubs.kt"
  echo "$WORK/AndroidxDataStoreCoreStubs.kt"
  echo "$WORK/AndroidxDataStorePrefsCoreStubs.kt"
  echo "$WORK/AndroidxDataStorePrefsStubs.kt"
  echo "$WORK/AndroidxAppCompatStubs.kt"
  echo "$WORK/AppDatabaseProbe.kt"
  echo "$WORK/RProbe.kt"
  echo "$WORK/NovelCharacterAppProbe.kt"
  echo "$TOOLS/jvm-stubs/AndroidLogStub.kt"
  # `AiService`의 자리를 메우는 스텁 — 순수 JVM 하네스가 쓰는 그것 한 벌이다.
  # 이것이 없으면 `ai/`의 서제스터 여섯이 전부 `unresolved reference 'AiService'` 그림자를 내고,
  # 그 그림자 안에 진짜 결함(`when` 미완결 따위)이 섞여 구별되지 않는다.
  echo "$TOOLS/jvm-stubs/AiServiceStub.kt"
} | grep -vE "util/(AiImagePreparer|AiImageAttach)\.kt" > "$WORK/files.txt"

# ── 2-b. R 생성기가 *도중에* 실패했는가 (2026.08.17 · B-190 콜드 검토) ──
#
# **이 부류는 아래 스텁 가드에 안 걸린다.** 생성이 중간에 끊기면 나오는 것은 *깨진 스텁*이 아니라
# **일부만 든 멀쩡한 스텁**이고, 그러면 오류가 스텁이 아니라 **앱 파일의 미해석**으로 나타난다.
# 게다가 base와 cur이 같은 스크립트를 쓰므로 **양쪽이 똑같이 줄어 `comm -13`도 침묵한다** —
# 즉 *프로브가 눈을 잃은 것*이 아무 데서도 안 보인다.
# 실제로 그렇게 물렸다: `sed -E 's|…(a\|b)…|'`에서 `|`가 구분자와 겹쳐 그 단계가 죽었고,
# **values 계열 자원이 통째로 빠진 채** 오류만 780 → 859로 늘었다(스텁 가드는 조용했다).
#
# 그래서 **대상 코드가 실제로 부르는 자원 종류가 전부 생성됐는지**를 본다. 종류를 손으로 적지
# 않는 것이 요점이다 — 적으면 새 종류가 들어올 때 낡는다.
MISSING=""
for kind in $(grep -ohE '\bR\.[a-z]+\.' $(grep -v "^$WORK/\|^$TOOLS/" "$WORK/files.txt") \
                | sed -E 's|R\.([a-z]+)\.|\1|' | sort -u); do
  grep -q "object $kind {" "$WORK/RProbe.kt" || MISSING="$MISSING $kind"
done
if [ -n "$MISSING" ]; then
  echo "⚠️  R 스텁에 없는 자원 종류:$MISSING — 생성이 도중에 실패했다. 이 산출을 믿지 말 것." >&2
  exit 1
fi

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

# ── 4-b. 범위와 사각지대 (B-251 ⓑ·ⓒ · 2026.08.19) ──
#
# **왜 산출이 이것을 말해야 하는가.** 2026.08.19 콜드 검토가 세운 규약은
# ***"검사는 무엇을 못 보는지와 얼마나 보는지를 둘 다 말해야 한다"***이고, 그 판은
# 그것이 검사만의 것이 아니라 **자기 입력을 스스로 만드는 기계 전부**의 것이라고 못박았다.
# 이 프로브가 정확히 그 부류인데 **자기 범위에 대해서는 여태 침묵했다** — 산출은 오류 수만
# 말하고, *그 수가 어느 만큼을 보고 나온 것인지*는 문서를 뒤져야 알 수 있었다.
#
# **왜 손 목록이 아니라 세는가.** 사각지대를 문장으로만 적어 두면 낡는다(이 저장소가 반복해
# 물린 모양이다 — `check_view_probe_targets.sh`가 생긴 이유). 여기서는 **`app/src/main`의
# 코틀린 파일 전수에서 대상 목록을 빼서** 남는 것을 최상위 패키지로 접어 센다. 새 계층이
# 생기면 **자동으로 이 목록에 뜬다.**
#
# **B-251 ⓑ·ⓒ를 이 방향으로 닫는다.** 그 두 갈래(안드로이드 뷰 스택 · 그래픽·문서 트리)는
# 스텁을 세워 열지 **않기로** 한다 — 화면 계층의 컴파일 증명은 CI가 맡기로 이미 갈라 둔
# 자리이고(CLAUDE.md v2.0), `Bitmap`·`DocumentsContract` 계열은 진짜와 대조할 수단이 없어
# **넓은 스텁이 곧 거짓 초록**인 부류다. 대신 **못 보는 것을 못 본다고 말한다.**
ALL_MAIN=$(find "$REPO/app/src/main/java/com/novelcharacter/app" -name '*.kt' | sort)
SEEN=$(grep -E "^$REPO/app/" "$WORK/files.txt" | sort)
UNSEEN=$(comm -23 <(echo "$ALL_MAIN") <(echo "$SEEN"))
SEEN_N=$(echo "$SEEN" | grep -c . || true)
UNSEEN_N=$(echo "$UNSEEN" | grep -c . || true)
echo "범위: 본 파일 ${SEEN_N}개 · 못 본 파일 ${UNSEEN_N}개 — 못 본 쪽의 컴파일 증명은 CI뿐이다"
echo "$UNSEEN" | grep . | sed "s|$REPO/app/src/main/java/com/novelcharacter/app/||" \
  | awk -F/ '{ if (NF>1) print $1; else print "(최상위)" }' | sort | uniq -c | sort -rn \
  | awk '{ printf "  · %s %s\n", $2, $1 }'
# **본 것이 0이면 목록이 잘린 것이다** — 그 상태에서 "오류 0"은 초록이 아니라 침묵이다.
if [ "${SEEN_N:-0}" -eq 0 ]; then
  echo "⚠️  대상 목록에 앱 파일이 하나도 없다 — 목록이 잘렸다. 이 산출을 믿지 말 것." >&2
  exit 1
fi

# **스텁 자신이 오류를 내면 곧바로 죽는다** (2026.08.17 · B-190).
# 스텁은 대부분 실제 소스·자원에서 뜨므로 저쪽이 바뀌면 여기가 깨질 수 있는데, 그때 나오는
# 오류는 **기준선과 현재 양쪽에 똑같이 얹혀 `comm -13`이 아무것도 내지 않는다** — 즉
# *프로브가 망가진 것*과 *새 오류가 없는 것*이 겉이 같아진다. 이 저장소가 검사마다 자기 시험을
# 붙이는 것과 같은 이유이며, 실제로 R 생성기의 첫 판이 style 이름의 `.` 때문에 40건을 냈다.
#
# **가르는 법은 스텁의 경로가 아니라 *앱 파일이 아닌 것 전부*다.** 앱 소스만 `sed`가 `$REPO/`를
# 떼어 `app/`으로 시작하고, 스텁은 어디에 있든 절대 경로로 남는다 — `JARS_DIR`를 저장소 안에
# 두면 스텁 경로에서도 `$REPO/`가 떨어져 경로로 찾는 방식이 조용히 빗나간다(그때 침묵하는 것이
# 바로 이 가드가 막으려는 실패다).
STUB_ERRS=$(grep -vcE '^app/' "$OUT" || true)
if [ "${STUB_ERRS:-0}" -ne 0 ]; then
  echo "⚠️  스텁 자신이 ${STUB_ERRS}건 오류다 — 이 산출은 base 대 cur 비교에 쓸 수 없다." >&2
  grep -vE '^app/' "$OUT" >&2
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
