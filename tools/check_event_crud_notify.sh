#!/bin/bash
# 사건 CRUD의 결과 고지 검사 (B-29)
#
# 배경: `CharacterViewModel`의 `insertEvent`·`updateEvent`·`updateEventAndShiftOthers`가
# 실패를 catch 후 **`Log.e` 한 줄로 끝냈다.** 같은 파일의 `deleteEvent`는 `reportResult`로
# 알리고 있었고, 같은 조작을 다른 호스트에서 하는 `TimelineViewModel`도 알리고 있었다 —
# **같은 조작의 결과 고지가 호스트에 따라 갈렸다.** 증상은 조용하다: 사건 저장이 실패해도
# 창은 그대로 닫히므로 **사용자는 성공한 줄 안다.**
#
# ── 축이 둘이다 ──
#
# ① **ViewModel이 실패를 고지하는가.** catch 블록에 `reportResult`/`showError`가 있는가.
# ② **호스트가 그 채널을 관측하는가.** ①만 지키면 고지가 **아무 데도 닿지 않는다** —
#    실제로 `CharacterEditFragment`가 정확히 그 상태였다. ViewModel은 결과를 낼 수 있었지만
#    그 화면에는 `viewModel.result.observe`가 없어 사건 편집 창이 **편집 화면에서 뜰 때만**
#    침묵했다. 배선 한쪽만 보는 검사는 이 결함을 통과시킨다.
#
# **②가 이 검사의 존재 이유다.** ①은 눈으로도 보이지만(catch 블록이 짧다), ②는 파일이
# 갈라져 있어 *"고지를 달았다"*와 *"고지가 사용자에게 닿는다"*가 다르다는 것을 아무도
# 알아채지 못한다. 순수 JVM은 `ui/**`를 원리적으로 못 보고 로컬 프로브도 그쪽을 통째로 빼므로,
# 이 부류를 잡는 자동 검사는 이것 하나다.
#
# 사용법: tools/check_event_crud_notify.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app"
VMS="$SRC/ui/character/CharacterViewModel.kt $SRC/ui/timeline/TimelineViewModel.kt"
# 사건 편집 창의 provider를 구현한 화면 — 이 창을 띄우는 모든 호스트가 대상이다.
HOSTS="$SRC/ui/character/CharacterDetailFragment.kt $SRC/ui/character/CharacterEditFragment.kt $SRC/ui/timeline/TimelineFragment.kt"

# 고지 수단 — `reportResult`(결과 채널) · `showError`(그 채널의 실패 래퍼) · `toastAndLogResult`
# (창이 이미 닫힌 뒤의 부가 고지). 셋 다 작업 이력에도 남는다.
NOTIFY_RE='reportResult|showError|toastAndLogResult'

# 사건 CRUD 함수 이름 — `deleteEvent`도 넣는다. 지금은 지키고 있지만 **본보기가 무너지면
# 나머지가 따라 무너진다**(이 결함이 처음 눈에 띈 것도 그것과 견줘서였다).
#
# ⚠️ **여는 괄호를 `\(`가 아니라 `[(]`로 적는다 — 백슬래시를 쓰면 awk 구현마다 갈린다.**
# 이 문자열은 `awk -v`로 넘어가 **먼저 문자열 이스케이프 처리를 거친 뒤** 정규식이 된다.
# 그 처리에서 `\(`의 백슬래시를 떼는 awk가 있고(그러면 `(`만 남아 *짝 없는 괄호*로 죽는다)
# 떼지 않는 awk가 있다. 2026.08.11에 **로컬 mawk는 통과하고 CI는 죽었다**:
#   `awk: fatal: invalid regexp: Unmatched ( or \(: /fun[ \t]+(...)[ \t]*(/`
# 대괄호는 어느 구현에서도 이스케이프가 필요 없어 이 갈림이 원리적으로 없다.
# **같은 함정이 `-v`로 넘기는 모든 정규식에 있다** — 정규식 리터럴(`/…/`)은 해당하지 않는다.
FN_RE='fun[ \t]+(insertEvent|updateEvent|updateEventAndShiftOthers|deleteEvent)[ \t]*[(]'

# 함수 하나를 여는 줄부터 같은 깊이의 닫는 중괄호까지 통째로 뜬다.
# 깊이 계산용 사본에서만 문자열·줄 주석을 지운다 — 주석에 든 중괄호가 경계를 밀지 않게.
# **블록 주석은 지우지 않는다**: 잡기 시작하는 자리가 `fun …` 줄이라 위의 KDoc은 안 들어오고,
# `*`부터 지우는 규칙은 곱셈이 든 줄에서 여는 중괄호까지 함께 지워 경계를 망가뜨린다
# (check_harvest_population.sh가 같은 함정을 적어 두었다).
event_fn_bodies() {
  awk -v fnre="$FN_RE" '
    {
      line = $0
      probe = line
      gsub(/"[^"]*"/, "", probe)
      gsub(/\/\/.*/, "", probe)

      if (depth == 0 && line ~ fnre) { inFn = 1; started = 0; depth = 0 }

      if (inFn) {
        # **`fun` 줄부터 곧바로 낸다.** 종전에는 `started`(첫 여는 중괄호)부터 냈는데,
        # 이 저장소의 사건 CRUD는 시그니처가 **여러 줄**이라 `fun insertEvent(` 줄에
        # 중괄호가 없다 — 그러면 그 줄이 출력에서 빠지고, 뒤 단계가 함수의 시작을
        # 영영 못 알아본다. 실제로 첫 판이 그래서 **`deleteEvent` 하나만 검사하면서
        # "2개가 고지한다"고 초록을 냈다**(방향 ① 재발 시험에서 드러났다).
        print FILENAME ":" FNR ":" line
        opens = gsub(/\{/, "{", probe)
        closes = gsub(/\}/, "}", probe)
        started = (started || opens > 0)
        depth += opens - closes
        if (started && depth <= 0) { inFn = 0; started = 0; depth = 0 }
      }
    }
  ' "$1"
}

# **catch 블록 안**에 고지가 있는지 본다 — 함수 단위로 보면 안 된다.
#
# 이 구분이 이 검사의 정확도를 가른다. 세 함수는 성공도 함께 알리므로(`result_event_added`),
# *"함수 어딘가에 reportResult가 있는가"*로 물으면 **성공 고지가 실패 고지의 부재를 덮는다** —
# 실제로 이 검사의 첫 판이 그렇게 통과했다(고지를 도로 빼고 돌렸는데 초록이었다).
# 그래서 catch 블록의 경계를 중괄호 깊이로 직접 따라간다.
offenders_in() {
  event_fn_bodies "$1" | awk -v notify="$NOTIFY_RE" -v fnre="$FN_RE" -F: '
    function flush() {
      # 함수 이름을 함께 낸다 — 오류 문구가 *어느 함수인가*를 말해야 고치러 갈 수 있고,
      # 자기 시험도 줄 번호가 아니라 이름으로 걸 수 있다(줄 번호는 본보기를 고치면 낡는다).
      if (fname != "" && bad) print fname " (" fnLabel ")"
    }
    {
      body = $0
      sub(/^[^:]*:[0-9]*:/, "", body)
      probe = body
      gsub(/"[^"]*"/, "", probe)
      gsub(/\/\/.*/, "", probe)

      if (body ~ fnre) {
        flush()
        fname = $1 ":" $2; depth = 0; bad = 0; inCatch = 0
        fnLabel = body
        sub(/^.*fun[ \t]+/, "", fnLabel)
        sub(/[ \t]*[(].*$/, "", fnLabel)
      }

      pending = (!inCatch && probe ~ /catch[ \t]*[(]/)
      opens = gsub(/\{/, "{", probe)
      closes = gsub(/\}/, "}", probe)
      depth += opens - closes

      if (pending) {
        # catch 본문은 지금 깊이에 있다 — 그 아래로 내려오면 블록이 닫힌 것이다.
        inCatch = 1; catchNotify = 0; catchEndDepth = depth - 1
        if (body ~ notify) catchNotify = 1
      } else if (inCatch) {
        if (body ~ notify) catchNotify = 1
        if (depth <= catchEndDepth) {
          if (!catchNotify) bad = 1
          inCatch = 0
        }
      }
    }
    END { flush() }
  '
}

echo "── 사건 CRUD 결과 고지 검사 (B-29) ──"

# ── 자기 시험 — 진짜를 잡고, 멀쩡한 것은 안 잡는가 ──
# **조용히 통과할 경로를 먼저 막는다.** 추출기가 낡아 아무것도 못 뽑으면 "위반 없음"과
# 구별되지 않는데, 그 둘은 정반대 사실이다.
SELFDIR=$(mktemp -d)
cat > "$SELFDIR/Self.kt" <<'EOF'
class Fake {
    fun insertEvent(e: Int) = scope.launch {
        try { save(e) } catch (ex: Exception) { Log.e("x", "failed", ex) }
    }

    // 시그니처가 **여러 줄**인 모양 — 실제 사건 CRUD 셋이 전부 이 모양이고,
    // 첫 판의 추출기가 이것을 통째로 놓쳤다. 본보기에 반드시 남겨 둘 것.
    fun updateEvent(
        e: Int,
        ids: List<Long> = emptyList()
    ) = scope.launch {
        try {
            save(e)
            reportResult(_result, OpResult.success(CAT, "고침"))
        } catch (ex: Exception) {
            Log.e("x", "failed", ex)
        }
    }

    fun deleteEvent(e: Int) = scope.launch {
        try { drop(e) } catch (ex: Exception) {
            Log.e("x", "failed", ex)
            reportResult(_result, OpResult.failure(CAT, "지움 실패"))
        }
    }
}
EOF
SELF=$(offenders_in "$SELFDIR/Self.kt")
self_bad=$(printf '%s\n' "$SELF" | grep -c "Self.kt" || true)
self_update=$(printf '%s\n' "$SELF" | grep -c "(updateEvent)" || true)
self_delete=$(printf '%s\n' "$SELF" | grep -c "(deleteEvent)" || true)
rm -rf "$SELFDIR"
if [ "$self_delete" -ne 0 ]; then
  echo "  ✗ 추출기 자기 시험 실패 — 고지가 붙은 deleteEvent를 위반으로 잡았다(오검출)" >&2
  exit 1
fi
# **`updateEvent`가 이 자기 시험의 핵심이다** — 성공은 알리고 실패는 안 알리는 모양이라,
# 함수 단위로 보는 검사는 이것을 통과시킨다(첫 판이 실제로 그랬다). 셋 중 둘을 잡아야 한다.
if [ "$self_bad" -ne 2 ] || [ "$self_update" -ne 1 ]; then
  echo "  ✗ 추출기 자기 시험 실패 — 위반 검출 ${self_bad}건(2여야 한다) · 성공만 알리는 함수 ${self_update}건(1이어야 한다)" >&2
  echo "    무통보 insertEvent와 '성공만 알리는' updateEvent를 잡고, 고지가 붙은 deleteEvent는 놓아 주어야 한다." >&2
  exit 1
fi

FAIL=0

# ── ① ViewModel이 실패를 고지하는가 ──
for f in $VMS; do
  [ -f "$f" ] || { echo "  ✗ 대상 파일이 없다: $f" >&2; FAIL=1; continue; }
  while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    echo "  ✗ 실패를 고지하지 않는 사건 CRUD: $hit" >&2
    FAIL=1
  done < <(offenders_in "$f")
done

# ── ② 그 창을 띄우는 호스트가 결과 채널을 관측하는가 ──
for f in $HOSTS; do
  [ -f "$f" ] || { echo "  ✗ 대상 파일이 없다: $f" >&2; FAIL=1; continue; }
  # provider를 구현하지 않는 화면은 이 창을 띄우지 않으므로 대상이 아니다.
  grep -qE 'override[ \t]+fun[ \t]+insertEvent' "$f" || continue
  if ! grep -qE '(viewModel|vm)\.result\.observe' "$f"; then
    echo "  ✗ 사건 편집 창을 띄우면서 결과 채널을 관측하지 않는다: $f" >&2
    echo "    ViewModel이 고지해도 이 화면에서는 아무 데도 닿지 않는다 (B-29가 그 상태였다)." >&2
    FAIL=1
  fi
done

if [ "$FAIL" -ne 0 ]; then
  echo "사건 CRUD 결과 고지 검사 실패" >&2
  exit 1
fi

vm_count=$(echo $VMS | wc -w)
host_count=$(echo $HOSTS | wc -w)
echo "  ViewModel ${vm_count}개의 사건 CRUD가 실패를 고지하고, 호스트 ${host_count}개가 그 채널을 관측한다"
echo "사건 CRUD 결과 고지 검사 통과"
