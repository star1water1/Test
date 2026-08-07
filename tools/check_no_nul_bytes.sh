#!/bin/bash
# 소스에 날 NUL 바이트가 없는가 (B-146, R-37) — grep 기반 도구의 사각을 원천에서 막는다.
#
# 배경: `util/PresetMerge.kt`가 항목 키 구분자를 "entityType + NUL + key"로 적으면서 그 NUL을
# **이스케이프 없이 날바이트로** 넣었다. 값으로는 아무 문제가 없다 — 문제는 **파일이 텍스트가
# 아니게 된다는 것**이다:
#
#   ① git이 binary로 판정해 **diff가 사라진다.** PR #225 diffstat에 `Bin 16610 -> 17066 bytes`로
#      찍혔고, 그 PR이 고친 파일인데 무엇이 바뀌었는지 리뷰에서 볼 수 없었다.
#   ② grep이 `binary file matches`만 뱉는다. 그래서 **grep으로 소스를 훑는 도구가 그 파일을
#      통째로 건너뛴다** — `tools/triage_unresolved.sh`가 세운 선언 목록에 `PresetMerge`가
#      실제로 빠져 있었다. **`CLAUDE.md` v2.0이 CI 완화의 유일한 근거로 지목한 도구**가 한
#      파일을 원리적으로 못 보는 상태였고, 그 도구가 막으려던 사고(다른 패키지 타입을 import
#      없이 쓰기)가 그 파일에서 났다면 **조용히 통과했다.**
#
# ── 왜 도구 쪽에 `grep -a`를 박지 않는가 ──
# B-146 등재 문구가 그 선택지를 함께 올렸다. 기각한다: `-a`는 **진짜 바이너리까지 열어**
# 서명 키스토어·jar을 텍스트로 훑게 만들고, 그러면 도구 하나하나가 각자 예외 목록을 갖게 된다
# (같은 판단을 여러 벌로 적는 부류 — R-34·B-131이 지적한 그 모양이다). 대신 **원천을 막는다:
# 소스에는 날 NUL이 없다.** 그러면 기존 grep 도구 전부가 손대지 않고도 옳아진다.
#
# ── 고치는 법 ──
# 값을 바꾸지 말고 **표기만** 바꾼다. 이스케이프는 같은 값을 낸다.
#   Kotlin:  "$a\u0000$b"   ← \u0000는 여섯 글자다(역슬래시·u·0·0·0·0). 날바이트가 아니다.
# 문서(.md)에서 NUL을 **인용**할 때도 같다. 2026.08.07에 이 저장소의 문서가 실제로 그렇게
# 깨졌다 — B-146을 적는 그 문장에서 구분자를 인용하려다 이스케이프를 빠뜨려 문서가 통째로
# binary로 판정됐고, grep이 그 문서에 대해 `binary file matches`만 뱉었다.
#
# ── 무엇을 보는가 ──
# 추적 파일 + **추적되지 않은 새 파일**(gitignore 제외)을 함께 본다. 새로 만든 파일이
# 커밋 전에는 `git ls-files`에 없어서, 추적분만 보면 **이 검사가 한 커밋 늦게 잡는다** —
# 정작 날바이트는 파일을 만드는 그 순간 들어간다.
#
# 사용법: tools/check_no_nul_bytes.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

# 본성이 바이너리인 확장자 — 이것만 건너뛴다. **나머지는 전부 텍스트여야 한다(fail-closed).**
# 목록에 없는 새 확장자가 NUL을 물고 들어오면 실패하고, 작성자가 한 번 판단하게 된다:
# 진짜 바이너리면 이 목록에 사유와 함께 올리고, 아니면 이스케이프로 고친다.
BINARY_EXT="jks jar keystore png jpg jpeg webp gif ico ttf otf so zip apk aab pdf bin"

echo "── 소스 날 NUL 바이트 검사 (B-146, R-37) ──"

is_binary_ext() {
  case "$1" in *.*) ;; *) return 1 ;; esac
  ext="${1##*.}"
  for b in $BINARY_EXT; do [ "$ext" = "$b" ] && return 0; done
  return 1
}

# git이 아는 파일 + 아직 추적되지 않은 새 파일. 빌드 산출물·jar 캐시는 gitignore가 걷어낸다.
LIST=$( { git ls-files 2>/dev/null; git ls-files --others --exclude-standard 2>/dev/null; } | sort -u )

targets=""
count=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  is_binary_ext "$f" && continue
  [ -f "$f" ] || continue          # 삭제 예정으로 index에만 남은 것
  targets="$targets $f"
  count=$((count + 1))
done <<EOF
$LIST
EOF

if [ "$count" -eq 0 ]; then
  echo "  ✗ 검사 대상이 하나도 없습니다 — git 저장소 안에서 돌리고 있습니까?" >&2
  echo ""
  echo "소스 날 NUL 바이트 검사 실패" >&2
  exit 1
fi

# `-a`가 필요한 것이 이 검사의 요점이다 — 없으면 grep이 바로 그 파일을 건너뛴다.
hits=$(printf '%s\n' $targets | xargs grep -laP '\x00' 2>/dev/null || true)

if [ -n "$hits" ]; then
  echo "  ✗ 날 NUL 바이트가 든 소스가 있습니다 — git이 binary로 보고 grep 도구가 건너뜁니다:"
  for f in $hits; do
    echo "      $f"
    grep -anP '\x00' "$f" 2>/dev/null | cut -c1-110 | sed 's/^/          /'
  done
  echo ""
  echo "      → 값은 그대로 두고 표기만 고칠 것: 날바이트 → \u0000 이스케이프(여섯 글자)."
  echo "      → 진짜 바이너리라면 이 스크립트의 BINARY_EXT 목록에 확장자를 사유와 함께 올릴 것."
  echo ""
  echo "소스 날 NUL 바이트 검사 실패" >&2
  exit 1
fi

echo "  ✓ 텍스트 소스 ${count}개에 날 NUL 없음 (바이너리 확장자는 제외: $BINARY_EXT)"
echo ""
echo "소스 날 NUL 바이트 검사 통과"
exit 0
