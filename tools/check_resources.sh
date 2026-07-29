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

echo "── 4. AAPT 문자열 이스케이프 (XML은 통과하지만 aapt가 거부하는 것) ──"
# 배경: XML 파서는 <string> 본문의 작은따옴표를 아무렇지 않게 통과시키지만 aapt2는
# 이스케이프를 요구하고 "Failed to flatten XML for resource ..." 로 빌드를 깬다.
# 이 검사가 없어서 정리 폴더 삭제 안내 문구가 CI에서 처음 걸렸다 — 로컬 게이트의 구멍이었다.
#
# **일부러 좁게 본다.** 게이트가 거짓 경보를 내면 무시당한다. 그래서 실제로 빌드를 깨는
# 둘만 본다: (1) 이스케이프 안 된 작은따옴표 (2) 알 수 없는 백슬래시 이스케이프.
# 큰따옴표로 **문자열 전체를 감싸는 것**은 앞뒤 공백을 보존하는 aapt의 정식 문법이므로
# 통과시킨다(`name_bank_bulk_used_moved` 등이 실제로 그렇게 쓰고 있다).
python3 - "$RES/values/strings.xml" <<'PYEOF' || FAIL=1
import re, sys
bad = []
for i, line in enumerate(open(sys.argv[1], encoding="utf-8"), 1):
    for m in re.finditer(r"<string[^>]*>(.*?)</string>", line):
        body = m.group(1)
        # 전체를 감싼 큰따옴표는 aapt의 공백 보존 문법 — 벗겨 내고 안쪽만 본다.
        if len(body) >= 2 and body.startswith('"') and body.endswith('"'):
            body = body[1:-1]
        j = 0
        while j < len(body):
            ch = body[j]
            if ch == "\\":
                nxt = body[j + 1] if j + 1 < len(body) else ""
                if nxt not in "nt'\"\\u@? ":
                    bad.append((i, "알 수 없는 이스케이프 \\" + nxt))
                j += 2
                continue
            if ch == "'":
                bad.append((i, "이스케이프되지 않은 작은따옴표 (\\' 로 쓸 것)"))
            j += 1
for i, why in bad:
    print("  \u2717 strings.xml:%d \u2014 %s" % (i, why))
sys.exit(1 if bad else 0)
PYEOF
[ $FAIL -eq 0 ] && echo "  ✓ 문자열 이스케이프 정상"

echo
if [ $FAIL -ne 0 ]; then echo "리소스 검사 실패"; exit 1; fi
echo "리소스 검사 통과"
