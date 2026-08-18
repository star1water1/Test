#!/bin/bash
# 🎲 축·프리셋 편집 창의 회전 보존 배선 검사 (B-260, 규약 R-41·R-41-a)
#
# ## 배경 — 무엇이 유실됐는가
#
# 이 창은 `object`가 `MaterialAlertDialogBuilder…create().show()`로 세우는 **맨 AlertDialog**였다.
# 액티비티가 다시 서면 창은 통째로 사라지고 **칸 스물에 적던 것이 함께 사라진다.** 창 자신이
# *"손이 미끄러지면 적던 것이 말없이 사라진다"*며 바깥 누름을 막아 둔 자리인데 **회전은 그
# 방어를 지나갔다.** 확정 15장 5번이 *그 자리에서 만들어진 작업물*을 안전 종료의 예외로 세운
# 그 부류다(B-201이 같은 편집기의 형제 시트에서 겪었다).
#
# ## 왜 검사가 필요한가 — **이 배선은 어떤 자동 검증도 못 본다**
#
# 회전은 실기기에서만 보인다. 순수 JVM 시험은 화면 계층에 닿지 못하고, 실클래스패스 프로브는
# 화면 계층을 통째로 뺀다. **CI도 컴파일만 본다** — 담기를 그만둔 창은 완벽하게 컴파일된다.
# `BodyGenerationEditStateTest`가 *담은 것의 왕복*은 잠그지만, **창이 애초에 안 담으면
# 왕복이 맞아도 담긴 것이 없다.**
#
# ## 무엇을 보는가
#
# ① 창이 [DialogFragment]인가 — 맨 AlertDialog로 돌아가면 이 판이 통째로 되돌아간다.
# ② 담는가 — `onSaveInstanceState`가 있고 순수 계층의 `encode()`를 지나는가.
# ③ 푸는가 — `onCreateDialog`가 `decode()`를 지나고, **못 읽으면 안전 종료**인가(R-41-a).
#    껍데기를 띄우면 [저장]이 사용자가 손대지도 않은 기본값을 진짜 설정 위에 덮어쓴다.
# ④ **칸 전수가 담기는가** — 창이 든 행 필드가 전부 `snapshot()`에서 읽히는가.
#    R-41이 *"새 상태 필드는 그 벌에 함께 등재한다"*로 답한 그 자리이고, **명단이 아니라
#    선언에서 뜬다**(적어 두면 새 칸이 조용히 빠진다 — `check_view_probe_targets.sh`의 논리).
# ⑤ **폼이 담긴 것에서 서는가** — 행을 `current.*Options`에서 그리면 회전 뒤에 *저장된 값*이
#    서고 적던 것은 사라진다. 담기·풀기가 다 맞아도 **그리는 자리가 딴 데를 보면** 헛일이다.
# ⑥ 받는 쪽이 듣는가 — 호스트가 `RESULT_KEY`를 듣고, 그 등록이 **`onCreate`** 안인가.
#    뷰 수명주기에 걸면 회전 중에 온 결과를 놓친다(그 유실은 조용하다).
# ⑦ 보내는 쪽이 보내는가 — 창이 `setFragmentResult(RESULT_KEY`를 부르는가.
#    ⑥과 짝이고 **둘 다 있어야 저장이 닿는다** — 한쪽만 보면 나머지 반이 조용히 죽는다.
# ⑧ **주입보다 결과가 먼저 와도 버리지 않는가** — 회전 직후에는 시트가 먼저 서고 폼(과
#    `onSaveGeneration` 주입)은 그 뒤에 다시 선다. 그 사이에 도착한 결과를 그냥 `return`하면
#    **사용자가 누른 [저장]이 말없이 사라진다** — 화면은 멀쩡하고 아무 말도 없다.
#    들고 기다렸다가 주입이 끝나는 자리(`onInputsBound`)에서 마저 드는지 **양쪽을 함께** 본다
#    (한쪽만 보면 *담아 두고 영영 안 꺼내는* 모양이 그대로 통과한다).
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

DIALOG="app/src/main/java/com/novelcharacter/app/ui/character/BodyGenerationEditDialog.kt"
HOST="app/src/main/java/com/novelcharacter/app/ui/character/BodySilhouetteEditorSheet.kt"
STATE="app/src/main/java/com/novelcharacter/app/util/BodyGenerationEditState.kt"

echo "── 🎲 축·프리셋 편집 창 회전 보존 배선 검사 ──"

for f in "$DIALOG" "$HOST" "$STATE"; do
  if [ ! -f "$f" ]; then
    echo "  ✗ 파일이 없다: $f" >&2
    echo '    (옮겼다면 이 검사의 경로도 함께 옮길 것 — 경로가 낡으면 검사가 조용히 죽는다)' >&2
    exit 1
  fi
done

VIOLATIONS=0
fail() { echo "  ✗ $1" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }

# ── ① 창이 DialogFragment인가 ──
if grep -q "^class BodyGenerationEditDialog : DialogFragment()" "$DIALOG"; then
  echo "  ✓ 창이 DialogFragment다(맨 AlertDialog가 아니다)"
else
  fail "창이 DialogFragment가 아니다 — 맨 AlertDialog는 액티비티가 다시 서면 통째로 사라진다"
fi

# ── ② 담는가 ──
SAVE_BODY=$(awk '
  /override fun onSaveInstanceState\(/ { ins = 1 }
  ins { print }
  ins && /^    }$/ { ins = 0 }
' "$DIALOG")
if [ -z "$SAVE_BODY" ]; then
  fail "창에 onSaveInstanceState가 없다 — 회전 뒤 되살릴 것이 남지 않는다"
elif ! printf '%s\n' "$SAVE_BODY" | grep -q "encode()"; then
  fail "onSaveInstanceState가 순수 계층의 encode()를 지나지 않는다 — 창이 제 손으로 담으면 왕복을 증명할 수단이 없다"
else
  echo "  ✓ 창이 onSaveInstanceState에서 순수 계층의 encode()로 담는다"
fi

# ── ③ 푸는가 + 못 읽으면 안전 종료인가 ──
CREATE_BODY=$(awk '
  /override fun onCreateDialog\(/ { inc = 1 }
  inc { print }
  inc && /^    }$/ { inc = 0 }
' "$DIALOG")
if [ -z "$CREATE_BODY" ]; then
  fail "창에 onCreateDialog가 없다 — 담긴 것을 되살릴 자리가 없다"
elif ! printf '%s\n' "$CREATE_BODY" | grep -q "BodyGenerationEditState.decode("; then
  fail "onCreateDialog가 BodyGenerationEditState.decode()를 지나지 않는다 — 담아도 풀지 않는다"
elif ! printf '%s\n' "$CREATE_BODY" | grep -q "dismissAllowingStateLoss()"; then
  fail "못 읽었을 때 안전 종료로 돌아가지 않는다(R-41-a) — 껍데기의 [저장]이 손대지도 않은 값을 덮어쓴다"
else
  echo "  ✓ 담긴 것을 decode()로 풀고, 못 읽으면 안전 종료로 돌아간다"
fi

# ── ④ 칸 전수가 담기는가 (명단이 아니라 선언에서 뜬다) ──
ROW_FIELDS=$(grep -oE '^    private var ([A-Za-z]+Rows):' "$DIALOG" | sed -E 's/^    private var ([A-Za-z]+Rows):/\1/')
SNAP_BODY=$(awk '
  /private fun snapshot\(\)/ { ins = 1 }
  ins { print }
  ins && /^    }$/ { ins = 0 }
' "$DIALOG")
if [ -z "$ROW_FIELDS" ]; then
  # **0건을 '위반이 없다'로 읽지 않는다** — 선언 꼴이 바뀌면 이 축이 눈먼 채 초록이 된다
  # (`check_restore_preview_parity.sh` 축 ⑥이 실제로 그렇게 열흘을 눈먼 채 지났다).
  fail "창에서 행 필드 선언을 하나도 뜨지 못했다 — 선언 꼴이 바뀌었는지 보라(이 축이 눈먼 채 초록이 된다)"
elif [ -z "$SNAP_BODY" ]; then
  fail "창에 snapshot()이 없다 — 위젯을 읽어 담는 자리가 없다"
else
  MISSING=""
  for f in $ROW_FIELDS; do
    printf '%s\n' "$SNAP_BODY" | grep -q "\b$f\b" || MISSING="$MISSING $f"
  done
  if [ -n "$MISSING" ]; then
    fail "snapshot()이 읽지 않는 칸이 있다 —$MISSING (그 칸은 회전 한 번에 사라진다)"
  else
    echo "  ✓ 창이 든 행 필드 $(printf '%s\n' "$ROW_FIELDS" | grep -c .)칸이 전부 snapshot()에서 읽힌다"
  fi
fi

# ── ⑤ 폼이 담긴 것에서 서는가 ──
if grep -qE 'current\.[A-Za-z]+Options\.map \{ *axisRow\(|current\.bodyPresets\.map' "$DIALOG"; then
  fail "행을 current에서 그린다 — 회전 뒤 *저장된 값*이 서고 적던 것은 사라진다(담기·풀기가 맞아도 헛일이다)"
elif ! grep -qE 'state\.[A-Za-z]+Rows\.map' "$DIALOG"; then
  fail "행을 담긴 상태(state.*Rows)에서 그리지 않는다 — 되살린 값이 화면에 서지 않는다"
else
  echo "  ✓ 행을 담긴 상태에서 그린다(처음 열 때와 회전 뒤가 같은 길이다)"
fi

# ── ⑥ 받는 쪽 — 호스트가 onCreate에서 듣는가 ──
HOST_CREATE=$(awk '
  /override fun onCreate\(savedInstanceState/ { inc = 1 }
  inc { print }
  inc && /^    }$/ { inc = 0 }
' "$HOST")
if ! grep -q "BodyGenerationEditDialog.RESULT_KEY" "$HOST"; then
  fail "호스트가 창의 결과를 듣지 않는다 — [저장]이 아무 데도 닿지 않는다"
elif [ -z "$HOST_CREATE" ] || ! printf '%s\n' "$HOST_CREATE" | grep -q "listenGenerationEdit()"; then
  fail "결과 등록이 onCreate 안이 아니다 — 회전 중에 온 결과를 놓치고, 그 유실은 조용하다"
else
  echo "  ✓ 호스트가 onCreate에서 창의 결과를 듣는다"
fi

# ── ⑦ 보내는 쪽 — 창이 결과를 보내는가 ──
if grep -q "setFragmentResult(" "$DIALOG" && grep -q "RESULT_KEY," "$DIALOG"; then
  echo "  ✓ 창이 저장 결과를 RESULT_KEY로 보낸다"
else
  fail "창이 setFragmentResult(RESULT_KEY …)를 부르지 않는다 — 듣는 쪽이 있어도 올 것이 없다"
fi

# ── ⑧ 주입보다 결과가 먼저 와도 버리지 않는가 ──
BOUND_BODY=$(awk '
  /    fun onInputsBound\(\)/ { inb = 1 }
  inb { print }
  inb && /^    }$/ { inb = 0 }
' "$HOST")
APPLY_BODY=$(awk '
  /private fun applyGenerationUpdate\(/ { ina = 1 }
  ina { print }
  ina && /^    }$/ { ina = 0 }
' "$HOST")
if [ -z "$APPLY_BODY" ] || ! printf '%s\n' "$APPLY_BODY" | grep -q "pendingGenerationUpdate = updated"; then
  fail "주입 전에 온 결과를 들고 기다리지 않는다 — 회전 직후의 [저장]이 말없이 사라진다"
elif [ -z "$BOUND_BODY" ] || ! printf '%s\n' "$BOUND_BODY" | grep -q "pendingGenerationUpdate"; then
  fail "들고 기다린 결과를 onInputsBound가 꺼내지 않는다 — 담아 두고 영영 안 꺼낸다"
else
  echo "  ✓ 주입 전에 온 결과를 들고 기다렸다가 onInputsBound에서 마저 든다"
fi

if [ "$VIOLATIONS" -gt 0 ]; then
  echo >&2
  echo "🎲 축·프리셋 편집 창 회전 보존 배선 검사 실패 — 위반 $VIOLATIONS건" >&2
  exit 1
fi

echo "🎲 축·프리셋 편집 창 회전 보존 배선 검사 통과"
