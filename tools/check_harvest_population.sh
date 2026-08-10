#!/bin/bash
# 값 라이브러리 수확의 모집단 검사 (B-60 · 사용자 확정 20번 ㄱ1)
#
# 무엇을 보는가: **`FieldValueLibraryRepository`의 `harvest*`가 상태변화 이력을 읽는가.**
# 읽으면 위반이다.
#
# ## 왜 있는가 — 비대칭이 몇 달을 살아남았고, 증상이 침묵이었다
#
# `usageCount`를 세는 쪽(`recountUsageForFieldsOrThrow`)은 처음부터 캐릭터·사건·작품의
# **현재 값 세 표**만 셌는데, 수확 쪽은 거기에 **상태변화 이력**까지 담았다. 두 쪽이 서로
# 다른 모집단을 본 것이다. 그 결과가 조용하다 — 이력에만 있는 값은 엔트리로 들어오고도
# `usageCount`가 **영원히 0**이라, '미사용 자동수집 정리'가 **살아 있는 값을 지우자고 권한다.**
#   - 컴파일·타입: 못 본다. 양쪽 다 멀쩡한 코드다.
#   - 순수 JVM 시험: 못 본다. 두 함수 다 DAO에 매달려 있어 원리적으로 닿지 않는다.
#   - 사람: 못 봤다. 실제로 `repair_plan` 10-5에 *"판단이 필요하다"*로만 적힌 채
#     **살아 있는 백로그 어디에도 없이** 오래 있었다.
#
# 어느 쪽에 맞출지는 *집계의 의미*를 정하는 일이라 사용자 판정을 받았고, 답은
# **`usageCount` = "지금 쓰이는 횟수"** — 즉 **수확 쪽을 좁힌다**였다.
#
# ## 왜 검사가 필요한가 — 걷은 자리가 넷이었다
#
# 고칠 때 보니 이력을 수확하는 자리가 한 곳이 아니라 **넷**이었다(`harvestForCharacter` ·
# `harvestUniversesOrThrow` · `harvestStateChange` · 휴지통의 상태변화 복원).
# **한 자리만 고치면 나머지로 같은 비대칭이 그대로 돌아온다.** 그리고 새 수확 경로는
# 앞으로도 는다 — 이 저장소가 이미 적어 둔 결론 그대로다
# (`check_config_column_fallback.sh` 머리): *"관행이라고 적어도 지켜지지 않는다.
# 지켜지게 하려면 적는 것 말고 기계가 있어야 한다."*
#
# ## 범위 — 왜 `harvest*` 안쪽만인가
#
# 같은 파일의 **전파**(rename/merge/delete)는 `character_state_changes`를 **반드시** 읽는다 —
# 표기를 바꾸면 이력도 따라가야 하기 때문이고, 그것은 이 확정이 건드린 축이 아니다.
# 그래서 파일 전체에 `stateChangeDao`를 금지할 수 없고, **함수 단위로** 갈라 본다.
#
# 사용법: tools/check_harvest_population.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app/data/repository/FieldValueLibraryRepository.kt"

# 이력을 읽는 통로 — DAO 접근자 이름 하나가 단일 통로라 이것만 막으면 된다.
FORBIDDEN="stateChangeDao"

# `harvest*` 함수의 몸통만 뽑아 `파일:줄:내용`으로 낸다.
#
# 중괄호로 함수 끝을 찾으므로 **문자열·주석 안의 중괄호를 먼저 지운다** — 안 지우면
# `"${'$'}{...}"` 같은 것이 깊이를 흔들어 함수 경계가 밀린다(그러면 검사가 조용히 빗나간다).
harvest_bodies() {
  awk '
    {
      line = $0
      # 깊이 계산용 사본에서만 문자열·주석을 지운다(출력은 원문 그대로 쓴다).
      probe = line
      gsub(/"[^"]*"/, "", probe)
      gsub(/\/\/.*/, "", probe)
      gsub(/\*.*/, "", probe)

      if (depth == 0 && line ~ /fun[ \t]+harvest[A-Za-z]*[ \t]*\(/) {
        inFn = 1; name = line
      }

      if (inFn) {
        opens = gsub(/\{/, "{", probe)
        closes = gsub(/\}/, "}", probe)
        started = (started || opens > 0)
        depth += opens - closes
        if (started) print FILENAME ":" FNR ":" line
        if (started && depth <= 0) { inFn = 0; started = 0; depth = 0 }
      }
    }
  ' "$1"
}

echo "── 값 라이브러리 수확 모집단 검사 (B-60) ──"

# ── 자기 시험 — 진짜를 잡고, 전파는 안 잡는가 ──
# **조용히 통과할 경로를 먼저 막는다.** 추출기가 낡아 아무것도 못 뽑으면 "위반 없음"과
# 구별되지 않는데, 그 둘은 정반대 사실이다.
SELFDIR=$(mktemp -d)
cat > "$SELFDIR/Self.kt" <<'EOF'
class Fake {
    suspend fun harvestForCharacter(id: Long) = safely("harvestForCharacter") {
        for (v in charValueDao.getAllValuesList()) tokens.add(v.value)
        insertNewTokens(tokens)
    }

    suspend fun harvestBad(id: Long) {
        for (c in stateChangeDao.getAllChangesList()) tokens.add(c.newValue)
    }

    /** 전파는 이력을 반드시 읽는다 — 여기 걸리면 안 된다. */
    suspend fun propagate(id: Long) {
        for (c in stateChangeDao.getChangesByFieldKeyForUniverse(1L, "k")) stateChangeDao.update(c)
    }
}
EOF
SELF=$(harvest_bodies "$SELFDIR/Self.kt")
self_good=$(printf '%s\n' "$SELF" | grep -c "harvestForCharacter" || true)
self_bad=$(printf '%s\n' "$SELF" | grep -c "$FORBIDDEN" || true)
self_prop=$(printf '%s\n' "$SELF" | grep -c "getChangesByFieldKeyForUniverse" || true)
rm -rf "$SELFDIR"
if [ "$self_good" -lt 1 ] || [ "$self_bad" -ne 1 ] || [ "$self_prop" -ne 0 ]; then
  echo "  ✗ 추출기 자기 시험 실패 — 수확 인식 ${self_good}(≥1) · 위반 ${self_bad}(1) · 전파 오검출 ${self_prop}(0)" >&2
  echo "      (진짜를 못 뽑으면 이 검사는 장식이고, 전파를 잡으면 오검출로 다음 사람이 끈다)" >&2
  exit 1
fi

# ── 본 검사 ──
if [ ! -f "$SRC" ]; then
  echo "  ✗ $SRC 가 없다 — 파일이 옮겨졌으면 이 검사의 경로도 함께 옮길 것." >&2
  exit 1
fi

BODIES=$(harvest_bodies "$SRC")
FNS=$(printf '%s\n' "$BODIES" | grep -cE 'fun[ \t]+harvest[A-Za-z]*[ \t]*\(' || true)

# 하나도 못 찾았으면 **통과가 아니라 실패**다. 수확 경로가 전부 사라지는 일보다
# 추출기가 낡는 일이 훨씬 흔하고, 둘을 같게 처리하면 낡은 순간부터 영원히 초록이다.
if [ "$FNS" -eq 0 ]; then
  echo "  ✗ harvest* 함수를 하나도 못 찾았다 — 추출기가 낡았을 수 있다." >&2
  echo "      확인: grep -nE 'fun[ \\t]+harvest' $SRC" >&2
  exit 1
fi

HITS=$(printf '%s\n' "$BODIES" | grep -F "$FORBIDDEN" || true)
if [ -n "$HITS" ]; then
  echo "  ✗ 수확이 상태변화 이력을 읽는다 — 집계는 현재 값만 세므로 그 값은 usageCount가" >&2
  echo "     영원히 0이고, '미사용 자동수집 정리'가 살아 있는 값을 지우자고 권한다." >&2
  printf '%s\n' "$HITS" | sed 's/^/      /' >&2
  echo "" >&2
  echo "  usageCount = '지금 쓰이는 횟수'가 사용자 확정이다(20번 ㄱ1)." >&2
  echo "  이력에만 있는 값이 카탈로그에 필요하다면 그것은 이 검사를 끄는 일이 아니라" >&2
  echo "  **집계의 의미를 다시 묻는 일**이다 — docs/field_value_library.md 「결정 기록」." >&2
  exit 1
fi

echo "  수확 ${FNS}개 전부 현재 값만 읽는다 — 집계와 같은 모집단"
