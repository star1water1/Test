#!/usr/bin/env bash
# 복원 미리보기 행별 DB 조회 검사 (B-236 · 규약 R-33의 성능 축)
#
# **무엇을 막는가:** `analyze*`(복원 미리보기)의 **행 루프 안에서 DB를 묻는 것.**
# 짝이 되는 `import*`는 B-210에서 그 조회를 *표 한 번 읽기*로 내렸는데 미리보기만 남아 있었고,
# 잣대는 이미 실측돼 있다(`docs/scalability_performance_2026-07.md` 3-11): 코드 열에 인덱스가
# 없어 조회 하나하나가 풀스캔이라 비용이 `행 수 × 표 크기`로 붙는다 — 상태변화 3,390행이
# **635 ms**, 색인 뒤 **10 ms**. **미리보기는 사용자가 화면 앞에서 기다리는 자리**라 체감이
# 가져오기보다 앞서고, 미리보기·가져오기를 잇달아 돌리면 같은 비용을 두 번 낸다.
#
# **왜 테스트가 아니라 검사인가:** 미리보기의 계수는 DB에 묶여 있어 순수 시험이 원리적으로
# 닿지 못한다. 닿는 것(키 모양·싣는 순서)은 `ImportIdentityIndexesTest`가 재고, **"행마다 묻지
# 않는가"는 구조라 기계가 본다.** R-32·R-33의 검사들이 같은 이유로 서 있다.
#
# **두 갈래를 본다 — 둘째가 요점이다:**
#  ① 행 루프 뒤에 있는 `db.` — 눈에 보이는 갈래.
#  ② `analyze*` 안에 선언된 **지역 함수** 속의 `db.` — 선언은 루프 *앞*이지만 호출은 루프
#     *안*이다. B-236 착수 때 `analysisNovelIdByTitle`이 정확히 그 모양으로 숨어 있었고
#     (캐릭터 시트 6,420행 × 2회), ①만 보는 눈은 그것을 영원히 못 본다.
#
# **못 보는 자리(적어 둔다 — R-49의 관행):**
#  · `analyze*`가 부르는 **클래스 수준 private 함수**(예: `characterByCode`)는 대상이 아니다.
#    그쪽은 이미 색인을 지나므로 지금은 옳지만, 새 헬퍼가 DAO를 직접 물면 이 검사가 침묵한다.
#  · 루프 **앞**의 `db.`는 통과다(그것이 이 수리가 원하는 모양이다) — 다만 *루프 앞이면 무조건
#    한 번*인지는 못 본다(루프 앞에 또 다른 루프가 있으면 세지 못한다).
#  · 조회 **횟수**는 세지 않는다. 이 검사는 *어디서 묻는가*만 본다.

set -uo pipefail
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
TARGET="$REPO/app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt"

if [ ! -f "$TARGET" ]; then
  echo "대상 파일을 찾을 수 없습니다: $TARGET" >&2
  exit 2
fi

echo "── 복원 미리보기 행별 DB 조회 검사 (B-236) ──"

scan() {
  python3 - "$1" <<'PY'
import re, sys

lines = open(sys.argv[1], encoding='utf-8').read().split('\n')
DECL = re.compile(r'^    (?:private )?suspend fun (analyze[A-Za-z0-9_]*)\s*\(')
# 행 루프 — 이 파일의 시트 루프는 전부 이 두 모양이다.
LOOP = re.compile(r'\bfor \(\s*\w+ in 1\.\.|\bfor \(\s*\w+ in \w+\.rows\b')
LOCAL_FUN = re.compile(r'^(\s{8,})(?:suspend )?fun (\w+)')
DB = re.compile(r'\bdb\.\w+Dao\(\)')

found = []
i = 0
while i < len(lines):
    m = DECL.match(lines[i])
    if not m:
        i += 1
        continue
    fn = m.group(1)
    j = i + 1
    end = len(lines)
    while j < len(lines) and lines[j] != '    }':
        j += 1
    end = j
    body = lines[i:end + 1]

    # ① 행 루프 뒤의 db.
    loop_at = None
    for k, line in enumerate(body):
        if LOOP.search(line.split('//')[0]):
            loop_at = k
            break
    if loop_at is not None:
        for k in range(loop_at + 1, len(body)):
            code = body[k].split('//')[0]
            if DB.search(code):
                found.append((i + k + 1, fn, '행 루프 안', body[k].strip()))

    # ② analyze* 안에 선언된 지역 함수 속의 db.
    k = 1
    while k < len(body):
        lm = LOCAL_FUN.match(body[k])
        if not lm:
            k += 1
            continue
        indent = len(lm.group(1))
        close = ' ' * indent + '}'
        t = k + 1
        while t < len(body) and body[t] != close:
            t += 1
        for u in range(k, min(t + 1, len(body))):
            code = body[u].split('//')[0]
            if DB.search(code):
                found.append((i + u + 1, fn, f'지역 함수 `{lm.group(2)}`', body[u].strip()))
        k = t + 1

    i = end + 1

for ln, fn, where, text in found:
    print(f"{ln}\t{fn}\t{where}\t{text}")
print(f"__COUNT__{len(found)}")
PY
}

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 정규식이 안 맞으면 위반이 있어도 "위반 없음"과 구별되지 않는다.
SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
    private suspend fun analyzeFake(workbook: Workbook): CategoryAnalysis {
        val existingTotal = db.fakeDao().getAllList().size
        suspend fun helperThatHides(title: String): Long? {
            return db.novelDao().getNovelByTitleNoUniverse(title)?.id
        }
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val existing = db.fakeDao().getByCode(row.code)
            // db.fakeDao().getByCode(x) 는 주석이라 세지 않는다
            if (existing == null) continue
        }
        return CategoryAnalysis()
    }
EOF
selftest_out=$(scan "$SELFTEST")
rm -f "$SELFTEST"
selftest_count=$(printf '%s\n' "$selftest_out" | sed -n 's/^__COUNT__//p')
selftest_local=$(printf '%s\n' "$selftest_out" | grep -c '지역 함수' || true)
if [ "${selftest_count:-0}" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 위반 2건 중 ${selftest_count:-0}건만 잡았습니다" >&2
  echo "      (루프 뒤 하나 + 지역 함수 하나. 루프 **앞**의 총계 조회와 주석은 세지 않아야 합니다)" >&2
  exit 1
fi
if [ "${selftest_local:-0}" -ne 1 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지역 함수에 숨은 갈래를 잡지 못했습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (루프 뒤·지역 함수 둘을 잡고, 루프 앞 총계와 주석은 세지 않는다)"

RESULT=$(scan "$TARGET")
count=$(printf '%s\n' "$RESULT" | sed -n 's/^__COUNT__//p')

# **자기 출력을 증명한다** (B-238 콜드 검토가 짝에서 잡아 이쪽에도 시행). `__COUNT__`가
# 없다는 것은 스캐너가 답을 못 냈다는 뜻이지 *위반이 없다*는 뜻이 아니다. 이 줄이 없으면
# python이 죽어도 `${count:-0}` = 0이 되어 **"✓ 위반 없음"으로 조용히 통과한다** —
# 대상 파일이 utf-8로 안 읽히게 만들어 실측했다(고치기 전: 초록 + `exit 0`).
# 자기 시험은 이 구멍을 못 막는다: 그쪽은 `-ne 2`라 빈 값이 0이 되어 **실패로** 떨어지지만,
# 이쪽은 `-gt 0`이라 빈 값이 0이 되면 **통과로** 떨어진다. 같은 기본값이 반대로 작용한다.
if [ -z "$count" ]; then
  echo "  ✗ 검사가 자기 출력을 내지 못했습니다 (__COUNT__ 없음) — 스캐너 실패입니다" >&2
  printf '%s\n' "$RESULT" | tail -5 >&2
  exit 2
fi

if [ "$count" -gt 0 ]; then
  echo "  ✗ 미리보기가 행마다 DB를 묻습니다 (${count}건)"
  echo
  printf '%s\n' "$RESULT" | grep -v '^__COUNT__' | while IFS=$'\t' read -r ln fn where text; do
    [ -z "${ln:-}" ] && continue
    echo "    $TARGET:$ln  $fn ($where)"
    echo "        $text"
  done
  echo
  echo "  → 표를 **루프 앞에서 한 번** 읽어 색인으로 답하세요."
  echo "    키 모양과 싣는 순서는 util/ImportIdentityIndexes.kt가 단일 소스입니다 —"
  echo "    직접 지으면 가져오기와 순서가 갈려 '변경/동일' 예고가 거짓이 됩니다(R-33)."
  exit 1
fi

echo '  ✓ analyze* 의 행 루프 안에 DB 조회가 없습니다'
echo
echo "복원 미리보기 행별 DB 조회 검사 통과"
