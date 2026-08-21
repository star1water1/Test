#!/bin/bash
# 하네스(순수 JVM 검증 환경)가 쓰는 jar 버전의 **단일 소스**. 다른 스크립트는 이 파일을 source한다.
#
# **왜 생겼는가(B-84, 2026.08.02):** 같은 목록이 `setup_jvm_env.sh`·`run_jvm_tests.sh`·
# `probe_compile.sh`·`probe_view_compile.sh` 네 벌로 적혀 있었고, 그중 어느 것도 앱이 무엇을
# 싣는지 보지 않았다. 그래서 **하네스는 POI 5.2.5, 앱은 5.3.0**으로 갈렸다 —
# 가져오기 무결성의 사실상 유일한 방어(JVM 동치 테스트)가 앱과 다른 파서에서 돌고 있었다.
#
# **앱이 정하는 것은 여기 숫자를 적지 않는다** — `app/build.gradle.kts`에서 읽는다.
# 앱이 POI를 올리면 하네스도 다음 실행부터 따라 올라간다(사람이 기억할 일이 없다).
set -u
JVM_ENV_REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# app/build.gradle.kts의 `implementation("group:artifact:x.y.z")`에서 버전만 뽑는다.
gradle_dep_version() {
  local coord="$1"
  local found
  found=$(sed -n "s/.*\"${coord}:\([0-9][0-9A-Za-z.-]*\)\".*/\1/p" \
    "$JVM_ENV_REPO/app/build.gradle.kts" | head -1)
  if [ -z "$found" ]; then
    echo "jvm_env_versions.sh: app/build.gradle.kts에서 ${coord} 버전을 찾지 못했습니다" >&2
    exit 1
  fi
  printf '%s' "$found"
}

POI_VER=$(gradle_dep_version "org.apache.poi:poi")
GSON_VER=$(gradle_dep_version "com.google.code.gson:gson")

# POI 곁가지 — 앱은 직접 적지 않고 poi-ooxml의 POM이 끌어온다. 그래서 여기 숫자로 둔다.
# **맞는지 세는 법**(POI를 올렸다면 함께 확인할 것):
#   curl -sSfL https://repo1.maven.org/maven2/org/apache/poi/poi-ooxml/$POI_VER/poi-ooxml-$POI_VER.pom \
#     | tr -d ' \n' | grep -o '<artifactId>xmlbeans</artifactId><version>[^<]*'
XMLBEANS_VER=5.2.1
COMMONS_COMPRESS_VER=1.26.2
COMMONS_IO_VER=2.16.1
LOG4J_API_VER=2.23.1
COMMONS_COLLECTIONS4_VER=4.4
COMMONS_LANG3_VER=3.14.0
SPARSEBITSET_VER=1.3

# 기본 jar 디렉터리 — **세션마다 달라지는 자리라 상수로 적지 않는다.**
#
# 종전에는 `run_jvm_tests.sh`·`differential_compile.sh` 둘이 *어느 한 세션의 스크래치패드
# 절대 경로*를 기본값으로 박아 두었다. 그 세션이 끝나면 그 경로는 존재하지 않으므로,
# `JARS_DIR`를 손으로 주지 않는 모든 실행이 *"jar이 없습니다"*로 죽는다 —
# 2026.08.21에 실제로 그랬고, 그때 나오는 안내는 *"setup_jvm_env.sh를 다시 돌리세요"*라
# **이미 받아 둔 jar을 다시 받게 만든다.** 있는 곳을 찾아 쓰는 것이 맞다.
#
# 판정은 **jar 하나의 실재**로 한다(둘 다 반드시 쓰는 것). 나머지는 부르는 쪽의
# `jvm_env_require_jars`가 버전까지 본다 — 여기서 전부 재면 그 검사와 두 벌이 된다.
jvm_env_default_jars_dir() {
  local marker="kotlin-stdlib-2.0.21.jar" d
  for d in /tmp/claude-*/-home-user-Test/*/scratchpad; do
    [ -f "$d/$marker" ] && { printf '%s' "$d"; return 0; }
  done
  # 못 찾으면 종전 안내가 가리키는 자리를 그대로 낸다 — 죽는 자리는 부르는 쪽이다.
  printf '%s' "${TMPDIR:-/tmp}/novelcharacter-jvm"
}

# jar이 실제로 있는지 본다. **앱이 버전을 올렸는데 `setup_jvm_env.sh`를 다시 돌리지 않은
# 상태**가 이 검사의 표적이다 — kotlinc는 없는 클래스패스 항목을 경고만 하고 지나가므로,
# 그대로 두면 POI 미해결 오류 수십 건으로 나타나 원인을 엉뚱한 곳(테스트)에서 찾게 된다.
jvm_env_require_jars() {
  local sp="$1" missing="" jar
  for jar in $(excel_cp "$sp" | tr ':' ' '); do
    [ -f "$jar" ] || missing="$missing $jar"
  done
  if [ -n "$missing" ]; then
    echo "jar이 없습니다 — JARS_DIR=$sp tools/setup_jvm_env.sh 를 다시 돌리세요" >&2
    for jar in $missing; do echo "  없음: $jar" >&2; done
    exit 1
  fi
}

# 엑셀 계열 클래스패스(러너·프로브가 함께 쓴다). 인자는 jar 디렉터리.
excel_cp() {
  local sp="$1"
  printf '%s' "$sp/poi-$POI_VER.jar:$sp/poi-ooxml-$POI_VER.jar:$sp/poi-ooxml-lite-$POI_VER.jar"
  printf ':%s' "$sp/commons-collections4-$COMMONS_COLLECTIONS4_VER.jar"
  printf ':%s' "$sp/commons-io-$COMMONS_IO_VER.jar"
  printf ':%s' "$sp/commons-compress-$COMMONS_COMPRESS_VER.jar"
  printf ':%s' "$sp/xmlbeans-$XMLBEANS_VER.jar"
  printf ':%s' "$sp/log4j-api-$LOG4J_API_VER.jar"
}
