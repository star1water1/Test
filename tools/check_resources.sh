#!/bin/bash
# 리소스 정합성 검사 — Android 빌드 없이 mergeDebugResources 단계의 흔한 실패를 미리 잡는다.
#
# 배경: Kotlin 차분 컴파일은 XML 리소스를 보지 않는다. 실제로 strings.xml 에 같은 name 을
# 두 번 선언해 CI 의 mergeDebugResources 가 깨진 적이 있다("Found item String/... more than one time").
# 이 스크립트는 그 부류를 커밋 전에 잡는다.
#
# 사용법: tools/check_resources.sh
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"
RES="app/src/main/res"
FAIL=0

echo "── 1. values 리소스 중복 name ──"
for f in $(find "$RES" -name '*.xml' -path '*values*' | sort); do
  dup=$(grep -oE '<(string|color|dimen|style|integer|bool|string-array|plurals|array)[^>]*name="[^"]*"' "$f" \
        | sed -E 's/.*name="([^"]*)".*/\1/' | sort | uniq -d)
  if [ -n "$dup" ]; then
    echo "  ✗ $f"
    echo "$dup" | sed 's/^/      /'
    FAIL=1
  fi
done
[ $FAIL -eq 0 ] && echo "  ✓ 중복 없음"

echo "── 2. 참조되지만 정의되지 않은 문자열 ──"
# android.R.string.* 은 프레임워크 제공이므로 제외한다
grep -rhoE '(^|[^.a-zA-Z])R\.string\.[a-zA-Z0-9_]+' app/src/main/java \
  | sed -E 's/.*R\.string\.//' | sort -u > /tmp/_res_used.txt
grep -rhoE '<string[^>]*name="[^"]*"' "$RES"/values/strings.xml \
  | sed -E 's/.*name="([^"]*)".*/\1/' | sort -u > /tmp/_res_defined.txt
missing=$(comm -23 /tmp/_res_used.txt /tmp/_res_defined.txt)
if [ -n "$missing" ]; then
  echo "$missing" | sed 's/^/  ✗ 누락: /'
  FAIL=1
else
  echo "  ✓ 누락 없음"
fi

echo "── 3. 레이아웃이 참조하는 id/문자열의 기본 구문 ──"
for f in $(find "$RES" -name '*.xml' | sort); do
  if ! python3 -c "import xml.etree.ElementTree as E,sys; E.parse(sys.argv[1])" "$f" 2>/dev/null; then
    echo "  ✗ XML 구문 오류: $f"
    FAIL=1
  fi
done
[ $FAIL -eq 0 ] && echo "  ✓ 모든 XML 파싱 가능"

echo
if [ $FAIL -ne 0 ]; then echo "리소스 검사 실패"; exit 1; fi
echo "리소스 검사 통과"
