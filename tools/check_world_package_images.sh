#!/usr/bin/env bash
# 월드패키지 이미지 엔트리 — 이름은 한 자리에서 나오고, **잘린 장은 결번처럼 다뤄진다** (B-234 · R-64)
#
# **무엇을 막는가:** 받는 기기가 *내용만 잘린 그림*을 멀쩡한 장으로 복원하는 것.
#
# 내보내기는 원본을 연 뒤에 엔트리를 열지만(B-225), `copyTo`가 **중간에** 던지는 갈래는 남는다 —
# 그때 `closeEntry()`가 지금까지 쓴 바이트로 엔트리를 **정상 종료**하므로 zip은 온전하고
# 내용만 잘린 장이 된다. 임포터는 장을 **위치**로 찾으므로 결번이라야 "유실"로 세는데,
# 이 엔트리는 결번이 아니라서 **아무도 모르게** 복원됐다. 그래서 내보내기가 잘린 장의 이름을
# `image_failures.json`에 싣고 가져오기가 그것을 결번처럼 다룬다.
#
# **왜 시험이 아니라 검사인가:** 두 파일이 `Context`·Room·`ZipFile`에 묶여 있어 순수 하네스가
# 원리적으로 닿지 않는다(프로브 범위 밖이기도 하다 — 그 계층의 컴파일 증명은 CI뿐이다).
# 판정 자체는 순수라 `WorldPackageImagesTest`가 잰다. **이 검사가 재는 것은 그 판정을
# *부르는가*다** — 판정만 순수로 세우면 아무도 안 부르는 죽은 코드가 되기 쉽고, 그것은
# 시험이 원리적으로 못 본다(`check_body_reach_bounds.sh` 축 ③이 세운 논리와 같다).
#
# **축 셋:**
#   ① 이미지 엔트리 이름을 손으로 짜는 자리가 있는가 — 이름은 `WorldPackageImages.entryName`
#      하나에서 나온다. 종전에는 내보내기가 접두사에 `images/`를 미리 붙여 들고 다니고
#      가져오기는 부를 때 붙여, **같은 이름을 짓는 규약이 둘**이었다.
#   ② 가져오기가 잘린 장 판정을 부르는가 — 이것이 빠지면 잘린 그림이 다시 조용히 복원된다.
#   ③ 내보내기가 그 목록을 실제로 싣는가 — 세기만 하고 싣지 않으면 받는 쪽이 알 길이 없다.
#      (**세는 것과 적는 것이 갈리는 것**은 검사가 아니라 구조가 막는다 — `ImageTally.failed`는
#      private이고 올리는 길이 `fail(entryName, path)` 하나다. 이 축은 그 목록이 zip까지
#      가는지만 본다.)
#
# 기준선은 **0건**이다.

set -uo pipefail
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
SHARE="$REPO/app/src/main/java/com/novelcharacter/app/share"

if [ ! -d "$SHARE" ]; then
  echo "대상 디렉터리를 찾을 수 없습니다: $SHARE" >&2
  exit 2
fi

echo "── 월드패키지 이미지 엔트리 검사 (B-234) ──"
FAIL=0

# ── 축 ① 이름을 손으로 짜는 자리 ──
# 단일 소스 자신(WorldPackageImages.kt)과 상수 선언(WorldPackageContents.kt)만 예외다.
# 주석 줄은 뺀다 — 규칙을 설명하는 주석이 위반으로 잡히면 다음 사람이 설명을 지워 통과시킨다
# (`check_export_font.sh`가 같은 이유로 정한 규칙).
# **잡는 것은 *이름을 붙이는* 자리이지 *접두사인지 묻는* 자리가 아니다.** zip을 훑으며
# `name.startsWith(IMAGES_PREFIX)`로 이미지 엔트리를 가려내는 것은 이름을 짓는 일이 아니라
# 분류이고, 그 자리는 단일 소스가 대신할 것이 없다(막으면 검사가 꺼진다 — R-53의 교훈).
HAND=$(grep -rnE '\$\{[^}]*IMAGES_PREFIX\}|IMAGES_PREFIX *\+' "$SHARE" \
        --include='*.kt' \
        | grep -v '/WorldPackageImages.kt:' \
        | grep -v '/WorldPackageContents.kt:' \
        | grep -vE ':[0-9]+: *(//|\*|/\*)')
if [ -n "$HAND" ]; then
  echo "⚠️ 이미지 엔트리 이름을 손으로 짓는 자리 — WorldPackageImages.entryName을 쓸 것:"
  echo "$HAND"
  FAIL=1
fi

# `.jpg` 문자열을 직접 붙이는 자리도 같은 부류다(확장자가 갈리면 엔트리를 못 찾는다).
EXT=$(grep -rn '"\.jpg"\|\.jpg"' "$SHARE" --include='*.kt' \
        | grep -v '/WorldPackageImages.kt:' \
        | grep -vE ':[0-9]+: *(//|\*|/\*)')
if [ -n "$EXT" ]; then
  echo "⚠️ 엔트리 확장자를 직접 적는 자리 — WorldPackageImages.EXTENSION을 쓸 것:"
  echo "$EXT"
  FAIL=1
fi

# ── 축 ② 가져오기가 잘린 장 판정을 부르는가 ──
IMPORTER="$SHARE/WorldPackageImporter.kt"
if ! grep -q 'WorldPackageImages\.isRestorable' "$IMPORTER"; then
  echo "⚠️ $IMPORTER 가 WorldPackageImages.isRestorable을 부르지 않는다 —"
  echo "   잘린 장이 다시 '멀쩡한 장'으로 복원된다(B-234가 고친 그 모양)."
  FAIL=1
fi
if ! grep -q 'truncatedImages' "$IMPORTER"; then
  echo "⚠️ $IMPORTER 가 contents.truncatedImages를 읽지 않는다 — 판정이 빈 집합으로만 돈다."
  FAIL=1
fi
# **부르기만 하고 안 쓰면 부르지 않은 것과 같다** (콜드 검토에서 찾은 자리).
# 판정의 결과를 담은 변수가 실제로 갈래를 가르는지 본다 — 이름은 **손으로 적지 않고 대입에서 뜬다**
# (적어 두면 변수를 개명하는 순간 검사가 조용히 통과한다).
RESVAR=$(grep -oE 'val [A-Za-z_][A-Za-z0-9_]* = *$' -A0 "$IMPORTER" >/dev/null 2>&1;          grep -B1 'WorldPackageImages\.isRestorable' "$IMPORTER"          | grep -oE 'val [A-Za-z_][A-Za-z0-9_]*' | tail -1 | awk '{print $2}')
if [ -z "${RESVAR:-}" ]; then
  echo "⚠️ isRestorable의 결과를 담는 변수를 뜨지 못했다(뜨는 법이 깨졌다) — 대입 꼴이 바뀌었는지 볼 것."
  FAIL=1
elif ! grep -qE "^[[:space:]]*${RESVAR} ->|if \(${RESVAR}\b" "$IMPORTER"; then
  echo "⚠️ $IMPORTER 가 판정 결과(\`$RESVAR\`)로 갈래를 가르지 않는다 —"
  echo "   부르기만 하고 옛 갈래(엔트리가 있으면 복원)를 그대로 쓰면 결함이 되돌아온다."
  FAIL=1
fi

# ── 축 ③ 내보내기가 목록을 싣는가 ──
EXPORTER="$SHARE/WorldPackageExporter.kt"
if ! grep -q 'WorldPackageEntries\.IMAGE_FAILURES' "$EXPORTER"; then
  echo "⚠️ $EXPORTER 가 image_failures.json을 싣지 않는다 — 잘린 장을 세고도 알리지 않는다."
  FAIL=1
fi
# 실패를 **적는 자리**가 살아 있는가 (콜드 검토). `fail(...)` 호출이 사라지고 로그만 남으면
# 계수도 목록도 0이라 **내보내는 쪽 고지까지 함께 조용해진다** — private 필드가 막는 것은
# *다른 길로 올리는 것*이지 *아무 길도 안 쓰는 것*이 아니다.
if ! grep -qE '\.fail\(' "$EXPORTER"; then
  echo "⚠️ $EXPORTER 가 ImageTally.fail(...)을 부르지 않는다 — 쓰다 실패한 장을 아무 데도 적지 않는다."
  FAIL=1
fi
# 파서가 그 엔트리를 읽는지도 함께 본다 — 싣기만 하고 읽지 않으면 왕복이 반쪽이다.
if ! grep -q 'IMAGE_FAILURES' "$SHARE/WorldPackageContents.kt"; then
  echo "⚠️ 파서가 IMAGE_FAILURES 엔트리를 읽지 않는다 — 실어도 도착하지 않는다."
  FAIL=1
fi

# ── 뜨는 법이 깨졌는가 ──
# 0건을 '위반이 없다'로 읽지 않는다 — 대상 파일이 없으면 위 grep은 전부 조용하다.
for f in "$IMPORTER" "$EXPORTER" "$SHARE/WorldPackageImages.kt"; do
  if [ ! -f "$f" ]; then
    echo "⚠️ 검사 대상 파일이 없다(뜨는 법이 깨졌다): $f"
    FAIL=1
  fi
done

if [ "$FAIL" -ne 0 ]; then
  echo "월드패키지 이미지 엔트리 검사 실패"
  exit 1
fi
echo "월드패키지 이미지 엔트리 검사 통과 (축 3 · 콜드 검토 보강 2)"
