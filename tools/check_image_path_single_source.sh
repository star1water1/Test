#!/bin/bash
# 이미지 경로 판단의 단일 소스 검사 (B-106 ⓐ) — **단일 소스는 세우는 것으로 끝나지 않는다.**
#
# 배경: `canonicalPath` 한 줄이 저장소 곳곳에 복붙돼 있었다. B-103이 `ImagePathMatch.canonical`을,
# B-141이 `ImagePathMatch.isInside`를 세웠지만 **옛 벌들은 그대로 남았고**, 남은 동안 새 벌이
# 또 늘었다(B-106이 *"단일 소스가 또 하나 생기고 옛 벌들은 남았다"*고 두 번 적은 그 자리다).
# 2026.08.10에 전부 걷었고, **이 검사가 없으면 다음 판이 같은 한 줄을 다시 심는다.**
#
# ── 왜 시험이 아니라 검사인가 ──
# 복붙이 사는 파일 대부분이 Android 의존(`Context`·`Fragment`·`AppDatabase`)이라 순수 JVM
# 하네스가 원리적으로 닿지 않는다. 판정 자체는 `ImagePathMatchTest`가 잠그지만
# *"그 판정을 쓰는가, 아니면 옆에 또 적었는가"*는 기계가 볼 수 있는 축이 이 검사뿐이다.
#
# ── 무엇을 보는가 ──
# ① **봉쇄 복붙**: `X.canonicalPath.startsWith(root.canonicalPath + separator)`.
#    실패 처분이 *막는다*여야 하는데, 자리마다 적으면 `runCatching`을 빠뜨린 벌이 섞인다
#    (실제로 둘이 그랬고 그 둘은 던지면 **작업 전체가 죽었다**).
# ② **정규화 복붙**: `runCatching`/`try`로 감싼 `canonicalPath`.
#    이쪽은 실패 처분이 *원본을 들고 간다*여야 하는데, **여섯 자리가 조용히 버리고 있었다** —
#    그리고 그 여섯이 하필 보호 집합·참조 집합·소유자 역맵·고아 삭제였다.
#
# ── 예외는 사유와 함께 적는다 ──
# 이름만 나열한 제외 목록은 다음 사람이 못 쓴다(R-45가 배운 것). 아래 ALLOW는 **왜 걷지
# 않는가**를 함께 들고, 사유가 사라지면 목록에서도 빠져야 한다.
#
# 사용법: tools/check_image_path_single_source.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

MAIN="app/src/main/java/com/novelcharacter/app"
SOURCE="$MAIN/util/ImagePathMatch.kt"

# 걷어내지 않은 자리 — **사유가 함께 있어야 한다.**
#   excel/ImageZipReport.kt : `classify`의 `canonicalOf` 기본값. 모양은 같지만 **실패 처분이
#     반대여야 하는 자리**다 — 실패하면 null이 되고 호출측이 그 경로를 '저장소 밖'으로 민다.
#     `ImagePathMatch.canonical`은 규약상 원본을 돌려주므로 옮기면 `../`가 든 원본이 접두어만
#     맞으면 통과해 **가드가 조용히 느슨해진다.** 봉쇄 단일 소스(`isInside`)는 `File`을 받는데
#     이 함수는 파일 시스템에 닿지 않는 순수·주입 가능 형태라 그대로 받을 수 없다.
ALLOW="$MAIN/excel/ImageZipReport.kt"

echo "── 이미지 경로 판단 단일 소스 검사 (B-106 ⓐ) ──"

# 주석을 지우고 한 줄로 편다 — 끊어 적은 호출을 줄 단위 검사가 놓치는 함정(B-137)을 피한다.
flatten() {
  sed -E 's://.*$::' "$1" | grep -vE '^[[:space:]]*(\*|/\*)' | tr '\n' ' '
}

# ① 봉쇄 복붙
blocking_hits() { flatten "$1" | grep -oE 'canonicalPath[[:space:]]*\.[[:space:]]*startsWith' || true; }
# ② 정규화 복붙 — runCatching{…canonicalPath…} / try{…canonicalPath…}
normalize_hits() {
  flatten "$1" \
    | grep -oE '(runCatching|try)[[:space:]]*\{[^{}]{0,120}canonicalPath' || true
}

# ── 탐지기 자기 시험 — 안 맞는 정규식은 "위반 없음"과 구별되지 않는다 ──
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
val a = file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
val b = runCatching { File(p).canonicalPath }.getOrNull() ?: p
val c = try { java.io.File(p).canonicalPath } catch (_: Exception) { p }
val ok1 = ImagePathMatch.isInside(p, dir)
val ok2 = ImagePathMatch.canonical(p)
val ok3 = pruneCandidates.contains(item.canonicalPath)
val ok4 = storedPathByCanonical[canonicalPath] ?: canonicalPath
// val d = runCatching { File(p).canonicalPath } 는 주석이라 세지 않는다
EOF
b=$(blocking_hits "$SELFTEST" | grep -c . || true)
n=$(normalize_hits "$SELFTEST" | grep -c . || true)
rm -f "$SELFTEST"
if [ "$b" -ne 1 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 봉쇄 복붙 1건 중 ${b}건만 잡았습니다" >&2; exit 1
fi
if [ "$n" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 정규화 복붙 2건 중 ${n}건만 잡았습니다" >&2; exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (복붙 셋을 잡고, 단일 소스 호출·속성 이름·주석은 세지 않는다)"

fail=0

# ── 단일 소스가 제자리에 있는가 ──
if [ ! -f "$SOURCE" ]; then
  echo "  ✗ 단일 소스가 없습니다: $SOURCE (이름이 바뀌었으면 이 검사를 함께 고칠 것)"; fail=1
else
  for fn in 'fun canonical' 'fun isInside'; do
    grep -q "$fn" "$SOURCE" || { echo "  ✗ $SOURCE 에 '$fn'이 없습니다"; fail=1; }
  done
fi

# ── 전수 훑기 (손으로 적은 목록은 불완전할 때 침묵한다) ──
checked=0
for f in $(find "$MAIN" -name '*.kt' | sort); do
  [ "$f" = "$SOURCE" ] && continue
  case " $ALLOW " in *" $f "*) continue ;; esac
  checked=$((checked + 1))
  hb=$(blocking_hits "$f" | grep -c . || true)
  hn=$(normalize_hits "$f" | grep -c . || true)
  if [ "$hb" -ne 0 ]; then
    echo "  ✗ $f — 봉쇄 판정을 직접 적었습니다 (${hb}건)"
    echo "      → ImagePathMatch.isInside(path, dir)를 쓸 것. 자리마다 적으면 실패 처분이"
    echo "        갈리고, 갈리면 어느 쪽이 '모르면 통과'인지 아무도 보증하지 못한다."
    fail=1
  fi
  if [ "$hn" -ne 0 ]; then
    echo "  ✗ $f — 경로 정규화를 직접 적었습니다 (${hn}건)"
    echo "      → ImagePathMatch.canonical(path)를 쓸 것. 직접 적으면 실패 시 버릴지"
    echo "        원본을 들고 갈지가 자리마다 갈리고, 버리는 쪽은 그 경로의 보호를 없앤다."
    fail=1
  fi
done

# 한 파일도 안 봤으면 그것은 '위반 없음'이 아니라 **검사가 안 돈 것**이다.
if [ "$checked" -eq 0 ]; then
  echo "  ✗ 검사 대상 파일을 하나도 찾지 못했습니다 — 경로가 바뀌었으면 MAIN을 함께 고칠 것"; fail=1
fi

# 예외 목록이 실재하는 파일을 가리키는가 — 파일이 사라졌으면 사유도 함께 지워야 한다.
for a in $ALLOW; do
  [ -f "$a" ] || { echo "  ✗ 예외 목록의 $a 가 없습니다 — 사유와 함께 목록에서 지울 것"; fail=1; }
done

if [ "$fail" -ne 0 ]; then
  echo "── 이미지 경로 판단 단일 소스 검사 실패 ──" >&2
  exit 1
fi
echo "  ✓ ${checked}개 파일에 복붙 없음 (예외 1건은 사유와 함께 등재돼 있다)"
echo "── 이미지 경로 판단 단일 소스 검사 통과 ──"
