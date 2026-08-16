#!/usr/bin/env bash
# 행·토큰마다 도는 DB 조회 검사 (B-238 · `check_preview_row_queries.sh`의 짝)
#
# **무엇을 막는가:** **일괄판이 있는 단수 조회**를 쓰는 것. 루프 안에서 부르면 조회 수가
# 행 수·토큰 수에 비례하고, 그 비용은 DB 왕복 × 그 수로 붙는다.
#
# **이름은 이 검사가 시작한 자리를 기록한다 — 규칙은 계층에 매이지 않는다(2026.08.16 B-241).**
# 처음에는 `ExcelImportService.kt` **한 파일만** 봤는데, 그 좁힘은 *규칙*이 아니라 **그때
# 고친 자리**였다. 실제로 같은 부류가 그 파일 **밖에 열넷** 살아 있었고(가져오기 밖 = 폴더
# 왕복·자동 링크·이미지 화면), B-241이 그것을 걷을 때까지 **아무 기계도 보지 않았다.**
# 파일을 바꾸지 않는 것은 이 검사를 가리키는 기록이 여럿이기 때문이고, **금지 목록을 두 벌로
# 적지 않으려고 형제 스크립트를 새로 만들지도 않았다** — 같은 사실이 두 자리에 살면 한쪽이
# 반드시 낡는다(이 저장소가 반복해 겪은 그 모양이다).
#
# **왜 짝인 미리보기 검사와 규칙이 다른가:** 그쪽은 `analyze*` 안의 **모든** `db.`를 막는다 —
# 미리보기는 세기만 하므로 루프 안에서 DB를 물 이유가 아예 없기 때문이다. 반면 쓰기 경로는
# **행마다 쓰는 것이 일**이라(삽입·갱신) 같은 규칙을 걸면 거짓 양성만 쌓인다. 그래서 이 검사는
# **일괄판이 있고 단수형을 쓸 이유가 없는 메서드만** 이름으로 든다.
#
# **그 기준은 "읽기인가"가 아니다(2026.08.15 B-239에서 넓혔다).** 처음 두 짝이 마침 읽기라
# 이 자리에 *"읽기 메서드만"*이라 적혀 있었는데, 그것은 **당시 있던 짝의 성질이지 규칙이 아니다** —
# 진짜 기준은 바로 윗줄의 *"단수형을 쓸 이유가 없다"*이다. `clearSingletonGroups`는 쓰기지만
# **토큰마다** 도는 정리라 그 성질을 그대로 지닌다(행마다 도는 삽입·갱신과 갈리는 자리가 여기다 —
# 그쪽은 여전히 대상이 아니다).
#
# **왜 루프를 찾지 않는가:** 아래 목록의 메서드는 *루프 밖이라도* 쓸 이유가 거의 없다
# (일괄판이 같은 답을 준다). 루프 탐지를 빼면 B-236이 물렸던 **지역 함수 사각지대**가
# 원리적으로 생기지 않는다 — 선언이 루프 앞이고 호출만 루프 안인 모양을 위치로 가리려면
# 호출 그래프를 따라가야 하는데, 이름으로 막으면 그 추적이 통째로 불필요하다.
#
# **정말로 단수여야 하는 자리는 그 줄에 사유를 적는다(B-241에서 신설).** 하나를 다루는 것이
# 그 함수의 일인 자리가 실재한다(이미지 **한 장**을 지우는 경로). 표식은 `// 단발 허용(사유)`
# 이고, **호출 줄이나 바로 윗줄**에 있어야 한다.
#
# **예외를 스크립트 안 명단으로 두지 않은 이유가 있다 — 처음엔 그렇게 짰고 실측으로 뒤집혔다.**
# (파일, 함수) 키로 적었더니 `OrganizeFolderService.applyPlan` 한 줄이 **그 함수 안의 위반
# 넷을 통째로 덮었다**(그 함수는 400줄이 넘는다). 수리 전 코드에 돌려 여덟이 아니라 다섯만
# 잡히는 것을 보고 알았다 — **거짓 음성을 조용히 내는 검사**였고, 그것이 B-242가 자기 탐지기에서
# 두 번 잡은 바로 그 부류다. 표식을 **자리에** 두면 덮을 수 있는 것이 그 한 줄뿐이다.
#
# **표식도 조용히 낡지 않게 잠근다:** 위반 호출에 붙어 있지 않은 표식은 **"낡은 표식"으로
# 빨간불**이다(고쳐진 자리에 남은 표식이 다음 사람에게 *"이 부류는 원래 예외"*로 읽힌다).
# 그리고 통과시킨 건수를 성공 줄에 **세어 적는다** — 명단이 소리 없이 자라지 않게.
#
# **못 보는 자리(적어 둔다 — R-49의 관행):**
#  · 목록에 없는 단수 조회는 침묵한다. 새 일괄판을 만들면 **짝을 여기 등재할 것.**
#  · DAO **안에서** 단수형을 부르는 것은 대상이 아니다(`adopt`가 `getByPath`를 쓴다 — 그쪽은
#    한 행을 쓰고 그 id를 돌려주는 것이 일이라 옳다). `data/dao/`를 통째로 뺀다.
#  · **일괄판이 아예 없는 단수 호출은 이 검사의 부류가 아니다** — `adopt`를 경로마다 부르는
#    루프가 그것이고, 그 축은 B-244로 따로 등재돼 있다.
#  · **별칭으로 받아 부르면 못 본다** — `val dao = db.imageMetaDao()` 뒤의 `dao.getByGroup(x)`.
#    지금 그 조합은 하나도 없다(2026.08.16 실측). **없다는 것이 규칙이 아니라 현재 상태**라
#    적어 둔다 — 따라가려면 지역 변수 추적이 필요하고 그것은 거짓 양성을 부른다.
#  · 조회 **횟수**는 세지 않는다. 이 검사는 *무엇을 부르는가*만 본다.

set -uo pipefail
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
SRC="$REPO/app/src/main/java/com/novelcharacter/app"

if [ ! -d "$SRC" ]; then
  echo "대상 디렉터리를 찾을 수 없습니다: $SRC" >&2
  exit 2
fi

echo "── 행·토큰마다 도는 DB 조회 검사 (B-238 · 범위 확장 B-241) ──"

scan() {
  python3 - "$@" <<'PY'
import bisect, os, re, sys

# 단수 조회 → 써야 할 일괄판. 새 짝이 생기면 여기 한 줄 더한다.
BANNED = {
    'getByPath':             'getByPaths(paths) — 경로 목록을 한 번에',
    'getTagsByImageList':    'getTagsByImages(imageIds) — 이미지 목록을 한 번에',
    'getByGroup':            'getByGroups(groupIds) — 묶음 토큰 목록을 한 번에 (B-239)',
    'clearGroupIfSingleton': 'clearSingletonGroups(groupIds) — 토큰 목록을 한 번에 (B-239)',
}

# 예외 표식 — **호출 범위 안이나 그 바로 윗줄**에 있어야 하고, 괄호 안 사유가 비면 안 된다.
ALLOW_MARK = re.compile(r'//.*단발 허용\(\s*[^)\s][^)]*\)')

# 최상위 멤버 함수(들여쓰기 4) 또는 파일 최상위 함수(들여쓰기 0)를 감싼 이름으로 삼는다.
FUN = re.compile(r'^ {0,4}(?:private |internal |public )?(?:suspend )?fun ([A-Za-z0-9_]+)\s*[(<]')

# **수신자를 `db.`로 못박지 않는다(2026.08.16 콜드 검토).** 이 저장소는 `app.database.…Dao()`도
# 쓰고(`FieldEditDialog`·`FieldViewModel`), DAO를 들고 있는 클래스는 수신자 없이 부를 수도 있다.
# `db\.`로 좁혀 두면 그 형태가 **조용히 빠진다** — 지금은 그 조합이 하나도 없지만, 없다는 것이
# 규칙이 아니라 현재 상태다(B-242가 두 번 잡은 그 부류라 좁힘의 근거가 없으면 좁히지 않는다).
#
# **줄바꿈도 넘는다.** 이 저장소는 `db.fieldDefinitionDao()` 다음 줄에 `.getAllFieldsList(…)`를
# 적는 형태를 **실제로 쓴다**(`NovelViewModel`·`TimelineViewModel`·`BirthdayWorker`·`TrashRepository`).
# 한 줄만 보면 그 형태가 전부 거짓 음성이라, 주석을 지운 뒤 파일을 통째로 훑는다.
CALL = re.compile(r'\b\w+Dao\(\)\s*\.\s*(' + '|'.join(BANNED) + r')\s*\(')

def rel(path):
    # 저장소 안이면 `com/novelcharacter/app` 뒤를, 아니면 파일명을 쓴다(자기 시험용).
    marker = 'com/novelcharacter/app/'
    norm = path.replace(os.sep, '/')
    return norm.split(marker, 1)[1] if marker in norm else os.path.basename(norm)

found, allowed, stale = [], [], []
for path in sys.argv[1:]:
    key_file = rel(path)
    with open(path, encoding='utf-8') as fh:
        raw = fh.read()
    lines = raw.split('\n')

    # **주석을 길이를 보존하며 지운다** — 호출이 줄바꿈을 넘으므로 파일을 통째로 훑어야 하고,
    # 그러려면 오프셋이 원문의 줄 번호와 그대로 맞아야 한다(잘라 내면 어긋난다).
    #
    # **블록 주석도 지운다(2026.08.16 콜드 검토).** 종전에는 `//`만 지웠는데, 수신자를 `db.`로
    # 못박아 둔 동안은 그 구멍이 **가려져 있었다** — KDoc이 `imageMetaDao().getByGroup(token)`처럼
    # 적어도 `db.`가 없어 안 걸렸기 때문이다. 수신자를 넓히자마자 **설계 문서 주석 둘이 위반으로
    # 잡혔다.** 좁은 규칙이 다른 구멍을 덮고 있던 자리이고, 넓히면 함께 드러난다.
    def _blank(m):
        return re.sub(r'[^\n]', ' ', m.group(0))
    text = re.sub(r'/\*.*?\*/', _blank, raw, flags=re.S)
    text = re.sub(r'//[^\n]*', _blank, text)

    line_start = []
    pos = 0
    for ln in lines:
        line_start.append(pos)
        pos += len(ln) + 1

    def line_of(offset):
        return bisect.bisect_right(line_start, offset)   # 1-based

    # 줄 → 감싼 함수 이름.
    fn_at = []
    current = '(파일 최상위)'
    for line in lines:
        fm = FUN.match(line)
        if fm:
            current = fm.group(1)
        fn_at.append(current)

    marked = set()          # 위반을 실제로 덮은 표식의 줄 번호
    for m in CALL.finditer(text):
        start_line = line_of(m.start())        # `…Dao()`가 시작한 줄
        call_line = line_of(m.start(1))        # 메서드 이름이 있는 줄
        # 표식이 설 수 있는 범위: **그 호출이 걸친 줄들 + 바로 윗줄 하나.**
        # 한 줄짜리 호출이면 종전과 같고(그 줄 · 윗줄), 여러 줄이면 그 범위만큼이다 —
        # 덮는 힘이 여전히 *그 호출*에 묶여 있고 함수로 번지지 않는다.
        span = range(max(1, start_line - 1), call_line + 1)
        hit = next((n for n in span if ALLOW_MARK.search(lines[n - 1])), None)
        if hit is not None:
            marked.add(hit)
            allowed.append((key_file, call_line, fn_at[call_line - 1], m.group(1)))
            continue
        found.append((key_file, call_line, fn_at[call_line - 1], m.group(1),
                      BANNED[m.group(1)], lines[call_line - 1].strip()))

    # 낡은 표식 — 위반 호출에 붙지 못한 표식은 지워야 한다.
    for n, line in enumerate(lines, 1):
        if ALLOW_MARK.search(line) and n not in marked:
            stale.append((key_file, n, line.strip()))

for f, ln, fn, meth, fix, text in found:
    print(f"{f}\t{ln}\t{fn}\t{meth}\t{fix}\t{text}")
for f, ln, fn, meth in allowed:
    print(f"__ALLOWED__{f}:{ln}\t{fn}\t{meth}")
for f, ln, text in stale:
    print(f"__STALE__{f}:{ln}\t{text}")
print(f"__COUNT__{len(found)}")
print(f"__ALLOWCOUNT__{len(allowed)}")
print(f"__STALECOUNT__{len(stale)}")
PY
}

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 정규식이 안 맞으면 위반이 있어도 "위반 없음"과 구별되지 않는다.
# 실제 경로 모양(`com/novelcharacter/app/…`)을 그대로 짓는다 — 명단이 그 상대 경로로 맞추므로
# 평평한 임시 파일로 시험하면 **명단 갈래가 통째로 안 돌고도 초록**이 된다.
SELFTEST_ROOT=$(mktemp -d)
SELFTEST_DIR="$SELFTEST_ROOT/com/novelcharacter/app/util"
mkdir -p "$SELFTEST_DIR"
cat > "$SELFTEST_DIR/ImageDeletionService.kt" <<'EOF'
    suspend fun delete(db: AppDatabase, path: String) {
        // 표식이 윗줄에 있는 자리 — 걸리지 않아야 한다. 단발 허용(한 장을 지우는 경로다)
        if (linkGroupId != null) db.imageMetaDao().clearGroupIfSingleton(linkGroupId)
        db.imageMetaDao().getByGroup(g)   // 같은 줄 표식. 단발 허용(사유가 여기 있다)
        // 사유가 빈 표식은 표식이 아니다 — 걸려야 한다. 단발 허용()
        db.imageMetaDao().getByPath(p)
    }
    suspend fun other(db: AppDatabase) {
        // **같은 파일 다른 자리**는 표식이 없으므로 걸려야 한다 — 표식은 자리를 덮지 함수를
        // 덮지 않는다(이 줄이 그 성질을 잡는다).
        db.imageMetaDao().clearGroupIfSingleton(token)
        // **줄바꿈을 넘는 호출**도 잡아야 한다 — 이 저장소가 실제로 쓰는 형태다.
        val a = db.imageMetaDao()
            .getByPath(p)
        // **수신자가 `db.`가 아니어도** 잡아야 한다.
        val b = app.database.imageTagDao().getTagsByImageList(id)
        // 줄바꿈 호출에 붙은 표식은 통해야 한다. 단발 허용(여러 줄 형태의 표식)
        val c = db.imageMetaDao()
            .getByGroup(g)
    }
    /**
     * 블록 주석 안의 `imageMetaDao().getByGroup(token)`은 **코드가 아니다** — 세지 않아야 한다.
     * 설계 문서가 옛 코드를 인용하는 자리라 실재한다.
     */
    suspend fun documented(db: AppDatabase) = db.imageMetaDao().getByPaths(paths)
EOF
cat > "$SELFTEST_DIR/Fake.kt" <<'EOF'
    private suspend fun importFake(workbook: Workbook) {
        val batched = paths.chunked(900).flatMap { db.imageMetaDao().getByPaths(it) }
        // 고쳐진 자리에 남은 표식 — 낡은 표식으로 잡혀야 한다. 단발 허용(이미 고쳤다)
        val done = paths.chunked(900).flatMap { db.imageMetaDao().getByPaths(it) }
        for ((i, _, path) in plan.rows) {
            val existing = db.imageMetaDao().getByPath(path)
            // db.imageTagDao().getTagsByImageList(id) 는 주석이라 세지 않는다
            suspend fun hidden(id: Long) = db.imageTagDao().getTagsByImageList(id)
        }
    }
EOF
selftest_out=$(scan "$SELFTEST_DIR/ImageDeletionService.kt" "$SELFTEST_DIR/Fake.kt")
rm -rf "$SELFTEST_ROOT"
selftest_count=$(printf '%s\n' "$selftest_out" | sed -n 's/^__COUNT__//p')
selftest_allow=$(printf '%s\n' "$selftest_out" | sed -n 's/^__ALLOWCOUNT__//p')
selftest_stale=$(printf '%s\n' "$selftest_out" | sed -n 's/^__STALECOUNT__//p')
selftest_fn=$(printf '%s\n' "$selftest_out" | grep -c 'importFake' || true)
# 지어낸 위반 여섯: 루프 안 · 지역 함수 · 사유가 빈 표식 · 표식 없는 다른 자리 ·
# **줄바꿈을 넘는 호출** · **수신자가 `db.`가 아닌 호출**.
if [ "${selftest_count:-0}" -ne 6 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 지어낸 위반 6건 중 ${selftest_count:-0}건만 잡았습니다" >&2
  # 작은따옴표다 — 큰따옴표 안의 백틱은 **명령 치환**이라 메서드 이름이 사라지고
  # "command not found"가 대신 뜬다(콜드 검토가 실측으로 잡았다). 이 줄은 탐지기가
  # 깨졌을 때만 뜨는 줄이라 **가장 필요할 때 틀리는** 부류였다.
  echo '      (루프 안 · 지역 함수 · 사유가 빈 표식 · 표식 없는 다른 자리 · 줄바꿈 호출 · 다른 수신자.' >&2
  echo '       일괄판 · 줄 주석 · **블록 주석** · 표식이 붙은 자리는 세지 않아야 합니다)' >&2
  exit 1
fi
# 통과 셋: 윗줄 표식 · 같은 줄 표식 · **줄바꿈 호출에 붙은 표식**.
# **덮는 힘이 자리에 묶여 있는지**를 이 수가 든다.
if [ "${selftest_allow:-0}" -ne 3 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 표식이 붙은 자리를 ${selftest_allow:-0}건 통과시켰습니다 (3이어야 합니다)" >&2
  echo '      (표식이 아무것도 안 덮어도, 함수를 통째로 덮어도 이 검사는 제 일을 못 합니다)' >&2
  exit 1
fi
if [ "${selftest_stale:-0}" -ne 1 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 낡은 표식을 ${selftest_stale:-0}건 잡았습니다 (1이어야 합니다)" >&2
  exit 1
fi
if [ "${selftest_fn:-0}" -ne 2 ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 감싼 함수 이름을 짚지 못했습니다" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (위반 6·표식 통과 3·낡은 표식 1 — 줄바꿈 호출·다른 수신자를 잡고 블록 주석은 통과)"

# `data/dao/` 아래는 대상이 아니다(위 머리 주석 — 일괄판이 단수형을 쓰는 것이 옳다).
mapfile -t FILES < <(find "$SRC" -name '*.kt' -not -path '*/data/dao/*' | sort)
if [ "${#FILES[@]}" -eq 0 ]; then
  echo "  ✗ 훑을 파일을 하나도 찾지 못했습니다 — 경로가 바뀌었습니까?" >&2
  exit 2
fi

RESULT=$(scan "${FILES[@]}")
count=$(printf '%s\n' "$RESULT" | sed -n 's/^__COUNT__//p')
allowcount=$(printf '%s\n' "$RESULT" | sed -n 's/^__ALLOWCOUNT__//p')
stalecount=$(printf '%s\n' "$RESULT" | sed -n 's/^__STALECOUNT__//p')

# **자기 출력을 증명한다** — `__COUNT__`가 없다는 것은 스캐너가 답을 못 냈다는 뜻이지
# *위반이 없다*는 뜻이 아니다. 이 줄이 없으면 python이 죽어도 `${count:-0}` = 0이 되어
# **"✓ 위반 없음"으로 조용히 통과한다**(콜드 검토가 잡았다 — 이 저장소가 프로브의
# "오류 0인데 클래스 파일도 0" 가드에서 이미 한 번 내린 결론과 같은 부류다).
if [ -z "$count" ] || [ -z "$allowcount" ] || [ -z "$stalecount" ]; then
  echo "  ✗ 검사가 자기 출력을 내지 못했습니다 (표식 없음) — 스캐너 실패입니다" >&2
  printf '%s\n' "$RESULT" | tail -5 >&2
  exit 2
fi

# **낡은 예외를 먼저 본다.** 고쳐진 자리가 명단에 남아 있으면 명단이 거짓말을 시작한다 —
# 다음 사람이 그것을 보고 *"이 부류는 원래 예외"*라고 읽는다.
if [ "$stalecount" -gt 0 ]; then
  echo "  ✗ 낡은 예외 표식이 남아 있습니다 (${stalecount}건)"
  printf '%s\n' "$RESULT" | sed -n 's/^__STALE__//p' | while IFS=$'\t' read -r where text; do
    echo "      $where — 이 표식이 덮는 단수 조회가 없습니다. 표식을 지울 것."
    echo "        $text"
  done
  exit 1
fi

if [ "$count" -gt 0 ]; then
  echo "  ✗ 일괄판이 있는 단수 조회를 씁니다 (${count}건)"
  printf '%s\n' "$RESULT" | grep -v '^__' | while IFS=$'\t' read -r f ln fn meth fix text; do
    echo "      $f:$ln  [$fn]  $meth → $fix"
    echo "        $text"
  done
  echo "      행 수·토큰 수에 비례하는 조회다 — 필요한 키만 모아 일괄로 물을 것"
  echo "      (B-238 · B-241 · scalability 3-11). 정말로 단발이어야 하는 자리면 호출 줄이나"
  echo "      **바로 윗줄**에 \`// 단발 허용(사유)\`를 한 줄로 적을 것 (사유가 비면 표식이 아니다)."
  exit 1
fi

echo "  ✓ 위반 없음 — 등재된 단수 조회가 모두 일괄판을 지난다 (${#FILES[@]}개 파일 · 명단 통과 ${allowcount}건)"
