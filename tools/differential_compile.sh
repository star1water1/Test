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
# **다만 겹은 남긴다 — `sort`이지 `sort -u`가 아니다 (B-211, 2026.08.16).**
# 줄 번호를 지우는 것과 겹을 접는 것은 다른 일인데 한 옵션에 묶여 있었다. 접으면 키가
# (파일, 문구)의 집합이라 **이미 그 문구가 있는 파일에서 같은 문구의 새 오류가 안 보인다** —
# 이 저장소가 그 함정에 물린 기록이 `tools/triage_unresolved.sh` 머리에 남아 있다
# (*"1차(2026.08.03): `sort -u`가 같은 문구의 진짜 신규를 함께 감췄다"*). 겹을 남기면
# `comm -13`이 **늘어난 만큼**을 낸다(정렬이 같은 두 다중집합의 차).
#
# ⚠️ **겹을 남기면 *노이즈의 증가분*도 함께 보인다 — 그것을 결함으로 읽지 말 것.**
# 이 스크립트는 `-cp` 없이 컴파일하므로 기준선에 이미 수만 건이 있고, 그중에는
# `suspension functions can only be called within coroutine body.` 같은 **일반 문구**가
# 수백 건이다(실측 587). 그래서 **suspend 호출을 한 줄 더 쓰기만 해도 그 문구가 하나 는다.**
# 종전 `sort -u`는 그것을 접었지만 **진짜 신규도 함께 접었다** — 지금은 둘 다 보인다.
#
# 가르는 법(순서대로):
#   1. `unresolved reference` 부류는 `tools/triage_unresolved.sh`가 기계로 가른다.
#   2. 그 밖의 문구는 **실클래스패스 프로브가 그 파일을 보는가**를 먼저 볼 것 —
#      `excel`·`data/model`·`data/dao`·`util`·`data/repository`면 그쪽이 정답이고
#      (거기 신규 0이면 이 줄은 노이즈다), 범위 밖이면 CI가 정답이다.
#   3. 그래도 애매하면 **같은 꼴이 이미 master에 있는지** 찾아볼 것. 있으면 노이즈다.
#      (2026.08.16 실측 사례: `?.let { suspendCall() }` 한 줄이 이 문구를 587 → 588로 올렸고,
#       `CharacterRepository`에 같은 꼴이 이미 둘 있어 노이즈로 판정했다. 프로브도 신규 0이었다.)
#
# 사용법:
#   git stash push -m base && JARS_DIR=... tools/differential_compile.sh /tmp/base.txt && git stash pop
#   JARS_DIR=... tools/differential_compile.sh /tmp/cur.txt
#   comm -13 /tmp/base.txt /tmp/cur.txt      # 두 출력은 이미 정규화·정렬돼 있다
#
# 파일 경로까지 지우고 **메시지만** 비교하고 싶을 때가 있다 — 신규·대폭 수정 파일은 경로가
# 키에 섞이면 모든 오류가 '새 것'으로 보이기 때문이다(2장 요령). 그때는 한 단계 더 지운다:
#   sed -E 's/^[^:]+: (error: .*)$/\1/' /tmp/base.txt | sort > /tmp/base-m.txt
#   sed -E 's/^[^:]+: (error: .*)$/\1/' /tmp/cur.txt  | sort > /tmp/cur-m.txt
#   comm -13 /tmp/base-m.txt /tmp/cur-m.txt
# **여기서도 `-u`를 쓰지 않는다** — 경로까지 지운 이 모드는 접기가 더 거칠어서, `-u`를 얹으면
# 저장소 전체에 그 문구가 하나라도 있는 한 새 오류가 **영영** 안 보인다.
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
  | sort > "$OUT"
wc -l < "$OUT"
