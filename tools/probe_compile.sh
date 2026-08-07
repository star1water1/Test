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
# **범위 밖:** UI(`ui/**`)와 `ExcelImporter`·`OrganizeFolderService`는 Android 프레임워크에
# 너무 깊게 묶여 있어 뺐다. **그 계층의 컴파일 증명은 CI(`assembleDebug`)뿐이다** —
# 화면을 손대는 변경은 CI 초록과 실기기 확인 전까지 미완으로 다룰 것.
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
  ls "$M"/excel/*.kt | grep -v "ExcelImporter.kt"
  ls "$M"/data/model/*.kt "$M"/data/dao/*.kt "$M"/util/*.kt "$M"/data/repository/*.kt
  echo "$WORK/AndroidProbeStubs.kt"
  echo "$WORK/AppDatabaseProbe.kt"
  echo "$TOOLS/jvm-stubs/AndroidLogStub.kt"
} | grep -vE "util/(OrganizeFolderService|AiImagePreparer|AiImageAttach)\.kt" > "$WORK/files.txt"

# ── 3. 컴파일 ──
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
  | sort -u > "$OUT"

ERRS=$(wc -l < "$OUT")
echo "고유 오류 ${ERRS}건 → $OUT"
# **오류 0을 그냥 믿지 않는다.** 컴파일러가 시작조차 못 하면(예: 컴파일러 클래스패스에
# coroutines 누락) 출력이 비어 "오류 0"으로 보인다 — 2026-08-01에 실제로 겪었고, 헛된
# 안심을 준다. 오류가 0인데 클래스 파일도 0이면 그 경우다.
# (오류가 있으면 kotlinc는 원래 클래스 파일을 내지 않으므로 그때는 판정하지 않는다.)
if [ "$ERRS" -eq 0 ] && [ "$(find "$WORK/out" -name '*.class' 2>/dev/null | wc -l)" -eq 0 ]; then
  echo "⚠️  오류 0인데 클래스 파일도 0이다 — 컴파일이 시작조차 못 했을 수 있다. 믿지 말 것." >&2
  exit 1
fi
