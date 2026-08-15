#!/usr/bin/env bash
# 단일 소스 지도 등재 검사 (아키텍처 3장 · CLAUDE.md v1.9 「완료 표시는 네 자리」)
#
# **무엇을 막는가:** 참조 해석의 판정을 순수 객체로 새로 세우고 **아키텍처 3장의 단일 소스
# 표에 올리지 않는 것.** 그 표는 *"이 판단을 호출부에서 다시 구현하지 말 것"*의 목록이라,
# 빠진 판정은 **다음 사람에게 존재하지 않는 것과 같다** — 그래서 같은 규칙이 또 복제된다.
#
# **왜 검사인가 — 같은 자리가 반복해서 빠졌다.** `FactionIndex`(먼저 선 원본)는 표에 아예
# 없었고, `NovelTitleIndex`는 **슬라이스가 아니라 그 뒤 콜드 검토**가 올렸으며(B-224, 커밋
# 846c91e), `CharacterRefLadder`도 같은 모양으로 빠졌다가 콜드 검토가 올렸다(B-232).
# 네 자리 중 **아키텍처 지도가 매번 마지막에 빠지는 자리**다. 규약으로만 적어 두면 또 빠진다
# (B-147이 같은 결론에 닿았다 — "규약으로만 적어 두었더니 둘이 닷새를 빠져 있었다").
#
# **보는 범위:** `excel/*RefResolver.kt`가 선언한 **판정 보유 클래스**(이름이 Index·Ladder·
# Resolver로 끝나는 것). 결과 타입(`*LookupResult`)은 대상이 아니다 — 표에 오르는 것은
# *판정하는 자리*이지 *답의 모양*이 아니다.
#
# **못 보는 자리(적어 둔다 — R-49의 관행):**
#  · `util/`의 순수 단일 소스들은 이 검사의 범위가 아니다(이름 규칙이 없어 기계로 모을 수 없다).
#    그쪽은 여전히 사람이 지킨다.
#  · 표에 **행이 있는지**만 본다 — 그 행의 설명이 현행인지는 못 본다.

set -uo pipefail
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
SRC_DIR="$REPO/app/src/main/java/com/novelcharacter/app/excel"
DOC="$REPO/docs/architecture_2026-07.md"

if [ ! -d "$SRC_DIR" ] || [ ! -f "$DOC" ]; then
  echo "대상을 찾을 수 없습니다: $SRC_DIR / $DOC" >&2
  exit 2
fi

echo "── 단일 소스 지도 등재 검사 (아키텍처 3장) ──"

RESULT=$(python3 - "$SRC_DIR" "$DOC" <<'PY'
import glob, os, re, sys

src_dir, doc = sys.argv[1], sys.argv[2]
doc_lines = open(doc, encoding='utf-8').read().split('\n')
# 단일 소스 표의 행만 본다: `| `이름` | …` 꼴. 본문 어딘가에 이름이 스쳐 지나가는 것은
# 등재가 아니다(그 구분이 이 검사의 값이다 — FactionIndex는 NovelTitleIndex 행 **안에서**
# 언급되고 있었지만 자기 행은 없었다).
mapped = set()
for l in doc_lines:
    m = re.match(r'^\|\s*`([A-Za-z0-9_.]+)`\s*\|', l)
    if m:
        mapped.add(m.group(1).split('.')[0])

DECL = re.compile(r'^(?:data )?(?:sealed )?(?:class|object) ([A-Za-z0-9_]+)')
HOLDER = re.compile(r'(Index|Ladder|Resolver)$')

bad, seen = [], []
for path in sorted(glob.glob(os.path.join(src_dir, '*RefResolver.kt'))):
    names = [n for l in open(path, encoding='utf-8') for n in DECL.findall(l)]
    holders = [n for n in names if HOLDER.search(n)]
    base = os.path.basename(path)
    if not holders:
        bad.append(f"{base}\t판정 보유 클래스(이름이 Index·Ladder·Resolver로 끝나는 것)를 찾지 못했습니다")
        continue
    for h in holders:
        seen.append(f"{base}:{h}")
        if h not in mapped:
            bad.append(f"{base}\t`{h}`이(가) 아키텍처 3장 단일 소스 표에 없습니다")

for s in seen:
    print(f"SEEN\t{s}")
for b in bad:
    print(f"BAD\t{b}")
print(f"__COUNT__{len(bad)}\t__TOTAL__{len(seen)}")
PY
)

count=$(printf '%s\n' "$RESULT" | sed -n 's/^__COUNT__\([0-9]*\).*/\1/p')
total=$(printf '%s\n' "$RESULT" | sed -n 's/.*__TOTAL__\([0-9]*\)$/\1/p')

# ── 자기 출력 증명 (B-240) ──
# 스캐너가 죽어 `__COUNT__`를 못 내면 아래 판정의 `${count:-0}`이 **0 = 위반 없음**으로 떨어져
# **"✓ … 전부 표에 있습니다" + exit 0으로 조용히 통과한다** — 그것도 `total`이 비어 *"판정 종이
# 전부"*라는 빈 문장으로 나간다. **없는 것을 다 봤다고 말하는 모양**이라 더 나쁘다.
# 프로브의 "오류 0인데 클래스 파일도 0" 가드와 같은 부류다.
if [ -z "$count" ] || [ -z "$total" ]; then
  echo "  ✗ 검사가 자기 출력을 내지 못했습니다 (__COUNT__/__TOTAL__ 없음) — 스캐너 실패입니다" >&2
  printf '%s\n' "$RESULT" | tail -5 >&2
  exit 2
fi

if [ "${count:-0}" -gt 0 ]; then
  echo "  ✗ 단일 소스 표에 없는 판정이 있습니다 (${count}건)"
  echo
  printf '%s\n' "$RESULT" | sed -n 's/^BAD\t//p' | while IFS=$'\t' read -r file why; do
    [ -z "${file:-}" ] && continue
    echo "    $file — $why"
  done
  echo
  echo "  docs/architecture_2026-07.md 3장 표에 행을 더하세요 — 무엇의 유일한 정의인가 · 어긋났을 때 생긴 일."
  echo "  그 표는 '호출부에서 다시 구현하지 말 것'의 목록이라, 빠진 판정은 없는 것과 같습니다."
  exit 1
fi

echo "  ✓ 판정 ${total}종이 전부 아키텍처 3장 단일 소스 표에 있습니다"
printf '%s\n' "$RESULT" | sed -n 's/^SEEN\t/    · /p'
echo
echo "단일 소스 지도 등재 검사 통과"
