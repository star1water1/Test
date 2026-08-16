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
cat > "$WORK/AndroidProbeStubs.kt" <<'EOF'
// 프로브 전용. 실제 소스가 아니며 Gradle 소스셋 밖에 있다.
package android.content
class Context {
    val filesDir: java.io.File get() = java.io.File(".")
    val cacheDir: java.io.File get() = java.io.File(".")
    fun getString(id: Int): String = ""
    fun getString(id: Int, vararg args: Any?): String = ""
    val contentResolver: Any? get() = null
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
{
  echo "// 프로브 전용 — 실제 AppDatabase의 DAO 접근자만 뽑아 세운다(생성 시각의 실제 목록)."
  echo "package com.novelcharacter.app.data.database"
  echo
  echo "import com.novelcharacter.app.data.dao.*"
  echo
  echo "abstract class AppDatabase {"
  grep -o 'abstract fun [a-zA-Z]*(): [A-Za-z]*' \
    "$REPO/app/src/main/java/com/novelcharacter/app/data/database/AppDatabase.kt"
  echo "}"
} > "$WORK/AppDatabaseProbe.kt"

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
  echo "$WORK/AppDatabaseProbe.kt"
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
# **오류 0을 그냥 믿지 않는다.** 컴파일러가 시작조차 못 하면(예: 컴파일러 클래스패스에
# coroutines 누락) 출력이 비어 "오류 0"으로 보인다 — 2026-08-01에 실제로 겪었고, 헛된
# 안심을 준다. 오류가 0인데 클래스 파일도 0이면 그 경우다.
# (오류가 있으면 kotlinc는 원래 클래스 파일을 내지 않으므로 그때는 판정하지 않는다.)
if [ "$ERRS" -eq 0 ] && [ "$(find "$WORK/out" -name '*.class' 2>/dev/null | wc -l)" -eq 0 ]; then
  echo "⚠️  오류 0인데 클래스 파일도 0이다 — 컴파일이 시작조차 못 했을 수 있다. 믿지 말 것." >&2
  exit 1
fi
