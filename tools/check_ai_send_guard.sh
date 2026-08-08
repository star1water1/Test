#!/bin/bash
# 앱 저장소 봉쇄 가드 검사 (B-141) — **기기 밖으로 나가는 읽기에는 문지기가 있어야 한다.**
#
# 배경: `util/AiImagePreparer`는 파일 바이트를 base64로 만들어 **제3자 서버로 올리는 유일한
# 경로**다. 이 저장소는 저장 이미지 경로를 읽는 자리마다 filesDir 가드를 두는데
# (`CharacterImageLoader`·`ImageViewerFragment`·`WorldPackageExporter` 등) 하필 이 자리에만
# 없어서, `exists()/isFile()/length()`만 보고 곧바로 올렸다. 입력을 믿을 수 없는 근거는
# 실재한다 — 캐릭터의 `imagePaths`는 엑셀 '이미지' 열 편집으로 외부에서 들어오고
# (`remapImagePaths`는 표에 없는 경로를 원본 그대로 유지한다), DB 적재 경로 둘은
# `validateInternalPaths` 없이 화면에 싣는다. 같은 경로를 **첨부 줄 썸네일은 플레이스홀더로
# 그리는데 준비기만 성공적으로 읽어 올렸다** — 표시와 전송의 비대칭이 결함의 본체였다.
#
# ── 왜 순수 JVM 시험만으로 부족한가 ──
# 준비기는 `BitmapFactory`·`Base64`를 쓰는 **Android 의존 파일**이라 순수 JVM 하네스가
# 원리적으로 닿지 않는다. 그래서 판정 자체는 `util/ImagePathMatch.isInside`로 갈라 두어
# `ImagePathMatchTest`가 잠그고(경계 문자·`..` 이탈·심볼릭 링크·실패 처분),
# **그 판정을 실제로 부르는가**는 기계가 볼 수 있는 축이 이 검사뿐이다.
#
# ── 무엇을 보는가 ──
# ① 필수: 준비기가 `ImagePathMatch.isInside`를 쓰는가 (가드 삭제).
# ② 필수: 두 진입점이 뿌리를 **필수 인자**로 받는가 — 기본값이 붙는 순간 빠뜨릴 자리가 생긴다.
# ③ 필수: **모든 호출 자리**가 `filesDir`를 넘기는가 (엉뚱한 뿌리를 넘겨 가드를 무력화하는 것).
#
# 호출 자리는 **훑어서 찾는다 — 손으로 적지 않는다.** 이 저장소가 이미 이름 붙인 함정이다:
# *"손으로 적은 목록은 그것이 불완전할 때 아무 말도 하지 않는다"*(B-145의 원인, B-158).
# 넷째 소비처가 생겨도 등재를 잊을 자리가 없어야 한다.
#
# 사용법: tools/check_ai_send_guard.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

MAIN="app/src/main/java/com/novelcharacter/app"
PREPARER="$MAIN/util/AiImagePreparer.kt"
GUARD="$MAIN/util/ImagePathMatch.kt"

echo "── 앱 저장소 봉쇄 가드 검사 (B-141) ──"

# 주석을 지우고 줄바꿈을 없앤 뒤 본다. `AiImagePreparer\n    .prepareEach(...)`처럼 끊어 적은
# 호출은 코틀린에서 흔하고, **줄 단위로 보는 검사는 그것을 조용히 놓친다**(B-137에서 겪었다).
flatten() {
  sed -E 's://.*$::' "$1" | grep -vE '^[[:space:]]*(\*|/\*)' | tr '\n' ' '
}

# 준비기 호출 자리 — 인자 창을 함께 떠서 뿌리를 넘겼는지 본다.
# 창을 고정 길이로 뜨는 이유: 인자 안에 `getApplication<Application>()`처럼 괄호가 들어
# `[^)]*`로는 첫 닫는 괄호에서 끊긴다.
# **호출마다 줄을 가르는 것이 요점이다** — 평탄화하면 파일이 한 줄이 되고, 그러면 `grep -o`의
# 첫 창이 뒤따르는 호출까지 통째로 삼켜 **둘째부터가 조용히 사라진다**(자기 시험이 잡았다).
preparer_calls() {
  flatten "$1" \
    | sed -E 's/AiImagePreparer/\n&/g' \
    | grep -oE '^AiImagePreparer[[:space:]]*(\.[[:space:]]*)prepare(Each)?[[:space:]]*\(.{0,200}' || true
}

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 정규식이 안 맞으면 위반이 있어도 "위반 없음"과 구별되지 않는다.
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
val a = AiImagePreparer.prepare(imagePaths, getApplication<Application>().filesDir)
val b = com.novelcharacter.app.util.AiImagePreparer
    .prepareEach(batch, someOtherDir)
val c = AiImagePreparer.prepare(paths)
// AiImagePreparer.prepare(paths) 는 주석이라 세지 않는다
EOF
found=$(preparer_calls "$SELFTEST" | grep -c . || true)
missing=$(preparer_calls "$SELFTEST" | grep -vc 'filesDir' || true)
rm -f "$SELFTEST"
if [ "$found" -ne 3 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 호출 3건 중 ${found}건만 잡았습니다" >&2
  exit 1
fi
if [ "$missing" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 뿌리를 안 넘긴 2건 중 ${missing}건만 잡았습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (끊어 적은 호출을 잡고, 주석은 세지 않으며, 뿌리 누락을 가른다)"

fail=0

# ── 단일 소스가 제자리에 있는가 ──
if [ ! -f "$GUARD" ]; then
  echo "  ✗ 판정 단일 소스가 없습니다: $GUARD (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
  fail=1
elif ! grep -q 'fun isInside' "$GUARD"; then
  echo "  ✗ $GUARD 에 isInside()가 없습니다"
  echo "      → 봉쇄 판정은 한 곳이 든다. 자리마다 적으면 실패 처분이 갈리고,"
  echo "        갈리면 어느 쪽이 '모르면 통과'인지 아무도 보증하지 못한다."
  fail=1
fi

# ── ①② 준비기 ──
if [ ! -f "$PREPARER" ]; then
  echo "  ✗ 전송용 준비기가 없습니다: $PREPARER (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
  fail=1
else
  # **평탄화한 본문에서 본다** — 날 `grep`은 KDoc의 `[ImagePathMatch.isInside]` 언급에
  # 걸려, 가드를 실제로 걷어내도 초록을 낸다(이 검사를 세우며 주입 시험이 잡았다).
  if ! flatten "$PREPARER" | grep -q 'ImagePathMatch[[:space:]]*\.[[:space:]]*isInside'; then
    echo "  ✗ $PREPARER 이 봉쇄 가드를 쓰지 않습니다"
    echo "      → 이 파일은 기기의 바이트를 제3자 서버로 올리는 유일한 경로다."
    echo "        가드가 없으면 화면에는 플레이스홀더로 그려지는 앱 밖 파일이 그대로 나간다."
    fail=1
  fi
  # 뿌리는 **필수 인자**여야 한다 — 기본값이 붙으면 넷째 소비처가 조용히 안 넘긴다.
  defaulted=$(flatten "$PREPARER" \
    | grep -oE 'filesDir[[:space:]]*:[[:space:]]*File[[:space:]]*=' | grep -c . || true)
  if [ "$defaulted" -ne 0 ]; then
    echo "  ✗ $PREPARER 의 filesDir에 기본값이 붙어 있습니다 (${defaulted}건)"
    echo "      → 필수 인자라야 '빠뜨릴 자리 자체가 없다'가 성립한다(B-139가 택한 모양)."
    fail=1
  fi
  for entry in 'fun prepare' 'fun prepareEach'; do
    sig=$(flatten "$PREPARER" | grep -oE "$entry[[:space:]]*\(.{0,120}" || true)
    if [ -z "$sig" ]; then
      echo "  ✗ $PREPARER 에 진입점 '$entry'가 없습니다 (이름이 바뀌었으면 이 검사를 함께 고칠 것)"
      fail=1
    elif ! printf '%s' "$sig" | grep -q 'filesDir'; then
      echo "  ✗ $PREPARER 의 '$entry'가 뿌리를 받지 않습니다"
      echo "      → 받지 않으면 가드가 무엇과 견줄지 정할 수 없다."
      fail=1
    fi
  done
fi

# ── ③ 호출 자리 전량 (훑어서 찾는다 — 손으로 적은 목록은 불완전할 때 침묵한다) ──
callers=$(grep -rl 'AiImagePreparer' "$MAIN" --include=*.kt | grep -v "$PREPARER" | sort)
seen=0
for f in $callers; do
  calls=$(preparer_calls "$f")
  [ -z "$calls" ] && continue
  n=$(printf '%s\n' "$calls" | grep -c . || true)
  seen=$((seen + n))
  bad=$(printf '%s\n' "$calls" | grep -vc 'filesDir' || true)
  if [ "$bad" -ne 0 ]; then
    echo "  ✗ $f 이 준비기에 filesDir를 넘기지 않습니다 (${bad}건)"
    echo "      → 엉뚱한 뿌리를 넘기면 가드가 서 있어도 아무것도 막지 못한다."
    fail=1
  fi
done

# 호출이 한 건도 안 잡히면 그것은 '위반 없음'이 아니라 **검사가 안 돈 것**이다
# (이름을 바꾸면 정규식이 통째로 빗나가고, 그때 이 검사는 조용히 초록이 된다).
if [ "$seen" -eq 0 ]; then
  echo "  ✗ 준비기 호출을 한 건도 찾지 못했습니다 — 검사가 대상을 놓쳤습니다"
  echo "      → 호출 모양이 바뀌었으면 preparer_calls 정규식을 함께 고칠 것."
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "── 앱 저장소 봉쇄 가드 검사 실패 ──" >&2
  exit 1
fi
echo "  ✓ 준비기는 가드를 지나고, 호출 ${seen}건이 전부 뿌리를 넘긴다"
echo "── 앱 저장소 봉쇄 가드 검사 통과 ──"
