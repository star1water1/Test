#!/bin/bash
# 차분 컴파일 — Android SDK 없이 "새로 생긴" 컴파일 오류만 골라낸다.
#
# 표준 kotlinc 로 전체 프로젝트를 컴파일하면 Android SDK/Room 이 없어 기준선에서도 수만 건의
# 오류가 난다. 그래서 기준선(HEAD) 오류 집합과 현재 작업본을 비교해 **새 카테고리만** 본다.
#
# 출력 형식: `파일경로: error: 메시지` — **줄 번호는 이 스크립트가 지운다.**
# 줄 번호를 남기면 한 줄만 삽입해도 그 아래 오류가 전부 '신규'로 보인다(실제로 겪었다:
# 2줄 삽입에 신규 311건. 정규화하면 0건이었다). 지우는 것이 이 도구의 일이지 호출자의 일이 아니다.
#
# 사용법:
#   git stash push -m base && JARS_DIR=... tools/differential_compile.sh /tmp/base.txt && git stash pop
#   JARS_DIR=... tools/differential_compile.sh /tmp/cur.txt
#   comm -13 /tmp/base.txt /tmp/cur.txt      # 두 출력은 이미 정규화·정렬돼 있다
#
# 파일 경로까지 지우고 **메시지만** 비교하고 싶을 때가 있다 — 신규·대폭 수정 파일은 경로가
# 키에 섞이면 모든 오류가 '새 것'으로 보이기 때문이다(2장 요령). 그때는 한 단계 더 지운다:
#   sed -E 's/^[^:]+: (error: .*)$/\1/' /tmp/base.txt | sort -u > /tmp/base-m.txt
#   sed -E 's/^[^:]+: (error: .*)$/\1/' /tmp/cur.txt  | sort -u > /tmp/cur-m.txt
#   comm -13 /tmp/base-m.txt /tmp/cur-m.txt
set -u
SP="${JARS_DIR:-/tmp/claude-0/-home-user-Test/6a87d14f-0af6-505a-8734-77051e12d059/scratchpad}"
OUT="$1"
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"
find app/src/main/java -name '*.kt' > $SP/files.txt
java -cp "$SP/kotlin-compiler-embeddable-2.0.21.jar:$SP/kotlin-stdlib-2.0.21.jar:$SP/annotations-13.0.jar:$SP/kotlinx-coroutines-core-jvm.jar:$SP/trove4j.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -d $SP/out-trash @$SP/files.txt 2>&1 \
  | grep -E '^app/.*error:' \
  | sed -E 's/^([^:]+):[0-9]+:[0-9]+: (error: .*)$/\1: \2/' \
  | sort -u > "$OUT"
wc -l < "$OUT"
