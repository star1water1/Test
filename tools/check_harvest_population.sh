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
    # 선언의 **매개변수 목록이 닫힌 뒤**의 나머지를 돌려준다 (없으면 빈 문자열).
    # 이것이 없으면 기본값의 `=`(`fun harvestX(id: Long = 3)`)를 식 본문의 `=`로 오인해
    # **그 함수를 통째로 건너뛴다** — 검사가 조용히 빗나가는 바로 그 모양이다(콜드 검토가 잡았다).
    function afterParams(t,   i, d, opened, c) {
      d = 0; opened = 0
      for (i = 1; i <= length(t); i++) {
        c = substr(t, i, 1)
        if (c == "(") { d++; opened = 1 }
        else if (c == ")") { d--; if (opened && d == 0) return substr(t, i + 1) }
      }
      return ""
    }
    {
      line = $0
      # 깊이 계산용 사본에서만 문자열·줄 주석을 지운다(출력은 원문 그대로 쓴다).
      #
      # **블록 주석은 지우지 않는다 — 지울 필요가 없고, 지우려던 규칙이 위험했다.**
      # 잡기 시작하는 자리가 `fun harvest…` 줄이라 그 **위의 KDoc은 애초에 안 들어온다.**
      # 종전에는 `*`부터 줄 끝까지를 지웠는데, 그러면 곱셈이 든 줄(`a * b) {`)에서
      # **여는 중괄호까지 함께 지워져 함수 경계가 밀린다** — 그러면 검사가 조용히 빗나간다.
      probe = line
      gsub(/"[^"]*"/, "", probe)
      gsub(/\/\/.*/, "", probe)

      if (depth == 0 && line ~ /fun[ \t]+harvest[A-Za-z]*[ \t]*\(/) {
        inFn = 1; name = line
      }

      if (inFn) {
        opens = gsub(/\{/, "{", probe)
        closes = gsub(/\}/, "}", probe)
        was = started
        started = (started || opens > 0)
        depth += opens - closes
        # **시그니처가 여러 줄이면 여는 중괄호가 뒷줄에 온다** (B-183) — 그때까지의 줄을
        # 미뤄 두었다가 함께 낸다. 종전에는 `started`가 서기 전 줄을 그냥 버려서
        # **`fun` 줄이 출력에서 빠졌고**, 뒤 단계가 함수를 이름으로 세지 못했다.
        # (몸통 자체는 그때도 잡혔다 — 그래서 위반 검출은 멀쩡했고 **세는 수만 틀렸다.**
        #  등재는 *"그 함수가 검사에서 통째로 빠진다"*고 적었는데 실측은 그렇지 않았다.)
        if (started && !was) { for (i = 1; i <= nPend; i++) print pend[i]; nPend = 0 }
        if (started) print FILENAME ":" FNR ":" line
        else pend[++nPend] = FILENAME ":" FNR ":" line

        # **블록 없는 식 본문**(`suspend fun harvestAll() = harvestUniverses(null)`)은 여기서 닫는다.
        # 안 닫으면 `inFn`이 선 채로 남아, 다음에 나오는 **아무 함수의 여는 중괄호**가
        # `started`를 세워 **그 함수의 몸통을 수확 몸통으로 검사한다.**
        # 실재하는 자리다 — `FieldValueLibraryRepository`의 `harvestAll`·`harvestAllOrThrow`
        # 둘이 그 꼴이고, 그래서 종전 검사는 바로 뒤 `insertNewTokens`를 수확으로 읽고 있었다.
        # (위반은 *더 많이* 잡는 쪽이라 조용히 통과하지는 않았지만, 세는 수도 잡는 대상도 틀렸다.)
        # 판정: **매개변수 목록이 닫힌 뒤**에 `{`가 없고 `=`가 있으면 식 본문이다.
        # *괄호 뒤*를 보는 것이 요점이다 — 줄 전체에서 `=`를 찾으면 기본값
        # (`fun harvestX(id: Long = 3)`)이 걸려 그 함수를 통째로 건너뛴다.
        # 여러 줄 시그니처의 첫 줄은 괄호가 안 닫혀 나머지가 빈 문자열이라 걸리지 않고,
        # `)`로 끝나 블록이 다음 줄에 오는 꼴도 나머지가 비어 걸리지 않는다.
        if (inFn && !started) {
          rest = afterParams(probe)
          if (rest != "" && rest !~ /\{/ && rest ~ /=/) { inFn = 0; nPend = 0 }
        }
        # 그래도 안 닫히는 꼴이 남으면 **조용히 틀리게 두지 않는다** — 시그니처가 이만큼
        # 길 수는 없으므로 소리 내어 죽는다(검사가 낡은 것을 통과로 읽는 것이 최악이다).
        if (nPend > 40) {
          print "EXTRACTOR-STUCK:" FILENAME ":" FNR > "/dev/stderr"
          exit 3
        }
        if (started && depth <= 0) { inFn = 0; started = 0; depth = 0; nPend = 0 }
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

    /**
     * **여러 줄 시그니처** — B-183이 연 자리다. 여는 중괄호가 `fun` 줄에 없어서
     * 종전 추출기는 이 함수를 이름으로 세지 못했다(몸통은 잡았다).
     */
    suspend fun harvestForNovelMultiline(
        id: Long,
        opts: Int
    ) {
        for (v in novelValueDao.getAllValuesList()) tokens.add(v.value)
    }

    /**
     * **블록 없는 식 본문** — 여기서 닫지 않으면 `inFn`이 선 채로 남아
     * *다음 함수의 몸통*이 수확 몸통으로 검사된다(실제로 그랬다).
     */
    suspend fun harvestAllSelf() = harvestForCharacter(1L)

    /**
     * **기본값의 `=`는 식 본문이 아니다** — 콜드 검토가 잡은 자리다.
     * 줄 전체에서 `=`를 찾으면 이 함수가 통째로 건너뛰어지고, 그러면 아래 위반이
     * **조용히 통과한다**(자기 시험의 `self_bad`가 2가 아니라 1이 되어 잡힌다).
     */
    suspend fun harvestWithDefault(id: Long = 3)
    {
        for (c in stateChangeDao.getAllChangesList()) tokens.add(c.newValue)
    }

    /** 전파는 이력을 반드시 읽는다 — 여기 걸리면 안 된다. */
    suspend fun propagate(id: Long) {
        for (c in stateChangeDao.getChangesByFieldKeyForUniverse(1L, "k")) stateChangeDao.update(c)
    }
}
EOF
SELF=$(harvest_bodies "$SELFDIR/Self.kt"); self_rc=$?
if [ "$self_rc" -ne 0 ]; then
  rm -rf "$SELFDIR"
  echo "  ✗ 추출기가 자기 시험에서 막혔다(종료 $self_rc) — 위 EXTRACTOR-STUCK 자리를 볼 것." >&2
  exit 1
fi
self_good=$(printf '%s\n' "$SELF" | grep -c "harvestForCharacter" || true)
self_bad=$(printf '%s\n' "$SELF" | grep -c "$FORBIDDEN" || true)
self_prop=$(printf '%s\n' "$SELF" | grep -c "getChangesByFieldKeyForUniverse" || true)
# 여러 줄 시그니처의 `fun` 줄이 출력에 들어오는가 (B-183). **세는 자리가 이것 하나다** —
# 본 검사의 `FNS`가 같은 정규식으로 세므로, 여기가 0이면 그쪽도 조용히 적게 센다.
self_multi=$(printf '%s\n' "$SELF" | grep -cE 'fun[ \t]+harvestForNovelMultiline' || true)
rm -rf "$SELFDIR"
if [ "$self_good" -lt 1 ] || [ "$self_bad" -ne 2 ] || [ "$self_prop" -ne 0 ] || [ "$self_multi" -ne 1 ]; then
  echo "  ✗ 추출기 자기 시험 실패 — 수확 인식 ${self_good}(≥1) · 위반 ${self_bad}(2) · 전파 오검출 ${self_prop}(0) · 여러 줄 시그니처 ${self_multi}(1)" >&2
  echo "      (위반 2 = harvestBad + harvestWithDefault. 뒤엣것이 1로 줄면 **기본값의 = 를 식 본문으로 오인해** 그 함수를 통째로 건너뛴 것이다)" >&2
  echo "      (진짜를 못 뽑으면 이 검사는 장식이고, 전파를 잡으면 오검출로 다음 사람이 끈다)" >&2
  exit 1
fi

# ── 본 검사 ──
if [ ! -f "$SRC" ]; then
  echo "  ✗ $SRC 가 없다 — 파일이 옮겨졌으면 이 검사의 경로도 함께 옮길 것." >&2
  exit 1
fi

BODIES=$(harvest_bodies "$SRC"); bodies_rc=$?
if [ "$bodies_rc" -ne 0 ]; then
  echo "  ✗ 추출기가 $SRC 에서 막혔다(종료 $bodies_rc) — 위 EXTRACTOR-STUCK 자리를 볼 것." >&2
  exit 1
fi
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
