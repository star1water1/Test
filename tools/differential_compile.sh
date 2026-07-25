#!/bin/bash
# 차분 컴파일 — Android SDK 없이 "새로 생긴" 컴파일 오류만 골라낸다.
#
# 표준 kotlinc 로 전체 프로젝트를 컴파일하면 Android SDK/Room 이 없어 기준선에서도 수만 건의
# 오류가 난다. 그래서 기준선(HEAD) 오류 집합과 현재 작업본을 비교해 **새 카테고리만** 본다.
#
# 사용법:
#   git stash push -m base && JARS_DIR=... tools/differential_compile.sh /tmp/base.txt && git stash pop
#   JARS_DIR=... tools/differential_compile.sh /tmp/cur.txt
#   # 줄 번호를 지운 뒤 비교 (같은 심볼이 손대지 않은 파일에도 있으면 클래스패스 노이즈다)
#   sed -E 's/^([^:]+):[0-9]+:[0-9]+: (error: .*)$/\1: \2/' /tmp/base.txt | sort -u > /tmp/base-n.txt
#   sed -E 's/^([^:]+):[0-9]+:[0-9]+: (error: .*)$/\1: \2/' /tmp/cur.txt  | sort -u > /tmp/cur-n.txt
#   comm -13 /tmp/base-n.txt /tmp/cur-n.txt
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
  | sed -E 's/^([^:]+):[0-9]+:[0-9]+ (error: .*)$/\1: \2/' \
  | sort -u > "$OUT"
wc -l < "$OUT"
