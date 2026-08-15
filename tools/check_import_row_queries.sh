#!/usr/bin/env bash
# 가져오기 행별 DB *조회* 검사 (B-238 · `check_preview_row_queries.sh`의 짝)
#
# **무엇을 막는가:** `ExcelImportService.kt`에서 **일괄판이 있는 단수 조회**를 쓰는 것.
# 시트 루프 안에서 부르면 조회 수가 행 수에 비례하고, 그 비용은 DB 왕복 × 행 수로 붙는다.
#
# **왜 짝인 미리보기 검사와 규칙이 다른가:** 그쪽은 `analyze*` 안의 **모든** `db.`를 막는다 —
# 미리보기는 세기만 하므로 루프 안에서 DB를 물 이유가 아예 없기 때문이다. 반면 `import*`는
# **행마다 쓰는 것이 일**이라(삽입·갱신) 같은 규칙을 걸면 거짓 양성만 쌓인다. 그래서 이 검사는
# **일괄판이 있고 이 파일에서 단수형을 쓸 이유가 없는 메서드만** 이름으로 든다.
#
# **그 기준은 "읽기인가"가 아니다(2026.08.15 B-239에서 넓혔다).** 처음 두 짝이 마침 읽기라
# 이 자리에 *"읽기 메서드만"*이라 적혀 있었는데, 그것은 **당시 있던 짝의 성질이지 규칙이 아니다** —
# 진짜 기준은 바로 윗줄의 *"단수형을 쓸 이유가 없다"*이다. `clearSingletonGroups`는 쓰기지만
# **토큰마다** 도는 정리라 그 성질을 그대로 지닌다(행마다 도는 삽입·갱신과 갈리는 자리가 여기다 —
# 그쪽은 여전히 대상이 아니다).
#
# **왜 루프를 찾지 않는가:** 아래 목록의 메서드는 이 파일에서 *루프 밖이라도* 쓸 이유가 없다
# (일괄판이 같은 답을 준다). 루프 탐지를 빼면 B-236이 물렸던 **지역 함수 사각지대**가
# 원리적으로 생기지 않는다 — 선언이 루프 앞이고 호출만 루프 안인 모양을 위치로 가리려면
# 호출 그래프를 따라가야 하는데, 이름으로 막으면 그 추적이 통째로 불필요하다.
#
# **못 보는 자리(적어 둔다 — R-49의 관행):**
#  · 목록에 없는 단수 조회는 침묵한다. 새 일괄판을 만들면 **짝을 여기 등재할 것.**
#  · DAO **안에서** 단수형을 부르는 것은 대상이 아니다(`adopt`가 `getByPath`를 쓴다 — 그쪽은
#    한 행을 쓰고 그 id를 돌려주는 것이 일이라 옳다).
#  · 조회 **횟수**는 세지 않는다. 이 검사는 *무엇을 부르는가*만 본다.

set -uo pipefail
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
TARGET="$REPO/app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt"

if [ ! -f "$TARGET" ]; then
  echo "대상 파일을 찾을 수 없습니다: $TARGET" >&2
  exit 2
fi

echo "── 가져오기 행별 DB 조회 검사 (B-238) ──"

scan() {
  python3 - "$1" <<'PY'
import re, sys

# 단수 조회 → 써야 할 일괄판. 새 짝이 생기면 여기 한 줄 더한다.
BANNED = {
    'getByPath':             'getByPaths(paths) — 경로 목록을 한 번에',
    'getTagsByImageList':    'getTagsByImages(imageIds) — 이미지 목록을 한 번에',
    'getByGroup':            'getByGroups(groupIds) — 묶음 토큰 목록을 한 번에 (B-239)',
    'clearGroupIfSingleton': 'clearSingletonGroups(groupIds) — 토큰 목록을 한 번에 (B-239)',
}
CALL = re.compile(r'\bdb\.\w+Dao\(\)\.(' + '|'.join(BANNED) + r')\s*\(')
# 이 파일의 최상위 멤버 함수는 전부 들여쓰기 4다.
FUN = re.compile(r'^    (?:private |internal )?(?:suspend )?fun ([A-Za-z0-9_]+)\s*[(<]')

lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
found = []
current = '(파일 최상위)'
for n, line in enumerate(lines, 1):
    fm = FUN.match(line)
    if fm:
        current = fm.group(1)
    code = line.split('//')[0]
    m = CALL.search(code)
    if m:
        found.append((n, current, m.group(1), BANNED[m.group(1)], line.strip()))

for ln, fn, meth, fix, text in found:
    print(f"{ln}\t{fn}\t{meth}\t{fix}\t{text}")
print(f"__COUNT__{len(found)}")
PY
}

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 정규식이 안 맞으면 위반이 있어도 "위반 없음"과 구별되지 않는다.
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
    private suspend fun importFake(workbook: Workbook) {
        val batched = paths.chunked(900).flatMap { db.imageMetaDao().getByPaths(it) }
        for ((i, _, path) in plan.rows) {
            val existing = db.imageMetaDao().getByPath(path)
            // db.imageTagDao().getTagsByImageList(id) 는 주석이라 세지 않는다
            suspend fun hidden(id: Long) = db.imageTagDao().getTagsByImageList(id)
        }
    }
EOF
selftest_out=$(scan "$SELFTEST")
rm -f "$SELFTEST"
selftest_count=$(printf '%s\n' "$selftest_out" | sed -n 's/^__COUNT__//p')
selftest_fn=$(printf '%s\n' "$selftest_out" | grep -c 'importFake' || true)
if [ "${selftest_count:-0}" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 위반 2건 중 ${selftest_count:-0}건만 잡았습니다" >&2
  # 작은따옴표다 — 큰따옴표 안의 백틱은 **명령 치환**이라 메서드 이름이 사라지고
  # "command not found"가 대신 뜬다(콜드 검토가 실측으로 잡았다). 이 줄은 탐지기가
  # 깨졌을 때만 뜨는 줄이라 **가장 필요할 때 틀리는** 부류였다.
  echo '      (루프 안 하나 + 지역 함수 하나. 일괄판 `getByPaths`와 주석은 세지 않아야 합니다)' >&2
  exit 1
fi
if [ "${selftest_fn:-0}" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 감싼 함수 이름을 짚지 못했습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (루프 안·지역 함수 둘을 잡고, 일괄판과 주석은 세지 않는다)"

RESULT=$(scan "$TARGET")
count=$(printf '%s\n' "$RESULT" | sed -n 's/^__COUNT__//p')

# **자기 출력을 증명한다** — `__COUNT__`가 없다는 것은 스캐너가 답을 못 냈다는 뜻이지
# *위반이 없다*는 뜻이 아니다. 이 줄이 없으면 python이 죽어도 `${count:-0}` = 0이 되어
# **"✓ 위반 없음"으로 조용히 통과한다**(콜드 검토가 잡았다 — 이 저장소가 프로브의
# "오류 0인데 클래스 파일도 0" 가드에서 이미 한 번 내린 결론과 같은 부류다).
if [ -z "$count" ]; then
  echo "  ✗ 검사가 자기 출력을 내지 못했습니다 (__COUNT__ 없음) — 스캐너 실패입니다" >&2
  printf '%s\n' "$RESULT" | tail -5 >&2
  exit 2
fi

if [ "$count" -gt 0 ]; then
  echo "  ✗ 가져오기가 일괄판이 있는 단수 조회를 씁니다 (${count}건)"
  printf '%s\n' "$RESULT" | grep -v '^__COUNT__' | while IFS=$'\t' read -r ln fn meth fix text; do
    echo "      $TARGET:$ln  [$fn]  $meth → $fix"
    echo "        $text"
  done
  echo "      행 수에 비례하는 조회다 — 필요한 키만 모아 일괄로 물을 것 (B-238 · scalability 3-11)."
  exit 1
fi

echo "  ✓ 위반 없음 — 등재된 단수 조회가 모두 일괄판을 지난다"
