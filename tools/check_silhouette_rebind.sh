#!/bin/bash
# 실루엣 편집기의 회전 보존 배선 검사 (B-201, 규약 R-41·R-38)
#
# ## 배경 — 무엇이 유실됐는가
#
# `BodySilhouetteEditorSheet`의 입력은 전부 호스트가 인스턴스에 꽂는 맨 `var`다. 회전하면
# FragmentManager가 시트를 **기본 생성자로 다시 세우고** 호스트는 다시 꽂지 않았다 — 그 시트는
# 기준 몸 초안을 그린 채 멀쩡히 떠 있고, [적용]은 `onApply`가 null이라 아무 데도 닿지 않는다.
# **끌어서 만든 수치가 회전 한 번에 사라지는데 화면은 정상으로 보인다**(개발 의도 2번 —
# 말없는 유실). 확정 15장 5번이 ⓐ 안전 종료가 아니라 **ⓑ 상태 저장**으로 판정했다.
#
# ## 왜 검사가 필요한가 — **이 배선은 어떤 자동 검증도 못 본다**
#
# 회전은 실기기에서만 보인다. 순수 JVM 시험은 화면 계층에 닿지 못하고, 실클래스패스 프로브는
# 화면 계층을 통째로 뺀다(그쪽 증명은 CI뿐인데 **CI도 컴파일만 본다** — 주입을 하나 빠뜨린
# 코드는 완벽하게 컴파일된다). `BodyEditorStateTest`가 담는 것의 왕복은 잠그지만,
# **담을 것을 시트가 애초에 안 받았으면 왕복이 맞아도 빈 값이 왕복한다.**
#
# 그래서 이 검사가 보는 것은 **짝**이다: 시트가 받는 칸과 호스트가 꽂는 칸이 같은가.
#
# ## 무엇을 보는가
#
# ① 시트의 주입 `var` 전수가 호스트의 `bindSilhouetteEditor`에서 대입되는가.
#    (새 주입을 더하고 호스트에 안 적으면 **회전 뒤에만 비는 칸**이 생긴다 — B-107이
#     같은 모양으로 물었고, R-41이 *"새 상태 필드는 그 벌에 함께 등재한다"*로 답한 그 자리다.)
# ② 시트에 `onSaveInstanceState`가 있는가 (R-38의 넷째 항 — 담지 않으면 되살릴 것이 없다).
# ③ `buildForm()`이 `rebindSilhouetteEditor()`를 부르는가
#    (폼이 선 뒤가 아니면 시트가 어느 필드의 것인지 가릴 수 없다).
# ④ 주입을 세우는 부름(`onInputsBound`)이 `bindSilhouetteEditor`의 **마지막**인가.
#    앞에 오면 그 뒤의 주입이 그려진 뒤에 도착해 그 칸 없이 선다.
# ⑤ **되살리는 길이 위젯을 다시 읽지 않는가** — 갈래가 있는가(`if (snapshot)`)와
#    `rebindSilhouetteEditor`가 `snapshot = false`로 꽂는가를 함께 본다.
#    폼은 `buildForm()` *뒤에* 값이 채워지므로(복원 번들 또는 DB 조회 — 뒤엣것은 정지 함수라
#    언제 끝나는지 알 수 없다), 되살리는 자리에서 읽으면 **빈 값이 되쓰기의 바탕**이 되어
#    사용자가 쓰던 칸이 [적용] 한 번에 지워진다. **이 판의 첫 구현이 실제로 그랬다** —
#    갈래는 세워 두고 되살리는 길에 `snapshot = sheet.isAwaitingRestore`를 넘겨,
#    하필 *되살릴 것을 들고 기다리는 시트*에서만 참이 됐다. 그래서 두 축을 함께 본다:
#    갈래만 보면 그 모양이 그대로 통과한다.
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SHEET="app/src/main/java/com/novelcharacter/app/ui/character/BodySilhouetteEditorSheet.kt"
HOST="app/src/main/java/com/novelcharacter/app/ui/character/DynamicFieldFormBuilder.kt"
STATE="app/src/main/java/com/novelcharacter/app/util/BodyEditorState.kt"

echo "── 실루엣 편집기 회전 보존 배선 검사 ──"

VIOLATIONS=0
for f in "$SHEET" "$HOST" "$STATE"; do
  if [ ! -f "$f" ]; then
    echo "  ✗ 파일이 없다: $f" >&2
    echo '    (옮겼다면 이 검사의 경로도 함께 옮길 것 — 경로가 낡으면 검사가 조용히 죽는다)' >&2
    exit 1
  fi
done

# ── ① 주입 var 전수가 호스트에서 대입되는가 ──
# 시트의 `// ── 호스트가 채우는 입력` 블록부터 `// ── 편집 상태` 블록 앞까지가 주입 구역이다.
INJECTED=$(awk '
  /^    \/\/ ── 호스트가 채우는 입력/ { inblock = 1; next }
  /^    \/\/ ── 편집 상태/            { inblock = 0 }
  inblock && /^    var [A-Za-z]/ {
    line = $0
    sub(/^    var /, "", line)
    sub(/[:( ].*$/, "", line)
    print line
  }
' "$SHEET")

if [ -z "$INJECTED" ]; then
  echo "  ✗ 시트에서 주입 구역을 찾지 못했다 — 구역 주석이 바뀌었으면 이 검사도 함께 고칠 것" >&2
  exit 1
fi

# 호스트의 bindSilhouetteEditor 본문만 뜬다(다른 함수에서의 대입은 세지 않는다 —
# 한 자리에 모으는 것이 R-41이 요구하는 것이고, 흩어지면 다음 사람이 또 빠뜨린다).
BIND_BODY=$(awk '
  /private fun bindSilhouetteEditor\(/ { inbind = 1 }
  inbind { print }
  inbind && /^    }$/ { inbind = 0 }
' "$HOST")

MISSING=""
for prop in $INJECTED; do
  if ! printf '%s\n' "$BIND_BODY" | grep -qE "sheet\.$prop[[:space:]]*="; then
    MISSING="$MISSING $prop"
  fi
done

COUNT=$(printf '%s\n' "$INJECTED" | grep -c .)
if [ -n "$MISSING" ]; then
  echo "  ✗ 호스트가 꽂지 않는 주입이 있다 —$MISSING" >&2
  echo "    ($HOST 의 bindSilhouetteEditor)" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
else
  echo "  ✓ 주입 $COUNT칸이 전부 호스트의 bindSilhouetteEditor에서 대입된다"
fi

# ── ② 시트가 담는가 ──
if grep -q "override fun onSaveInstanceState" "$SHEET"; then
  echo "  ✓ 시트가 onSaveInstanceState로 편집 상태를 담는다"
else
  echo "  ✗ 시트에 onSaveInstanceState가 없다 — 회전 뒤 되살릴 것이 남지 않는다" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
fi

# ── ③ 폼이 선 뒤에 다시 꽂는가 ──
BUILD_FORM=$(awk '
  /^    fun buildForm\(\)/ { inform = 1 }
  inform { print }
  inform && /^    }$/ { inform = 0 }
' "$HOST")
if printf '%s\n' "$BUILD_FORM" | grep -q "rebindSilhouetteEditor()"; then
  echo "  ✓ buildForm()이 rebindSilhouetteEditor()로 재생성된 시트를 되살린다"
else
  echo "  ✗ buildForm()이 rebindSilhouetteEditor()를 부르지 않는다 —" >&2
  echo "    회전으로 다시 세워진 시트가 영영 빈 껍데기로 남는다" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
fi

# ── ④ 세우기는 주입 뒤여야 한다 ──
LAST_ASSIGN=$(printf '%s\n' "$BIND_BODY" | grep -nE "sheet\.[A-Za-z]+[[:space:]]*=" | tail -1 | cut -d: -f1)
BOUND_AT=$(printf '%s\n' "$BIND_BODY" | grep -n "sheet.onInputsBound()" | tail -1 | cut -d: -f1)
if [ -z "$BOUND_AT" ]; then
  echo "  ✗ bindSilhouetteEditor가 sheet.onInputsBound()를 부르지 않는다 — 편집기가 서지 않는다" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
elif [ -n "$LAST_ASSIGN" ] && [ "$BOUND_AT" -lt "$LAST_ASSIGN" ]; then
  echo "  ✗ sheet.onInputsBound()가 주입보다 앞선다 — 그 뒤의 칸 없이 편집기가 그려진다" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
else
  echo "  ✓ 편집기 세우기(onInputsBound)가 주입 전수보다 뒤에 있다"
fi

# ── ⑤ 이미 선 시트에 스냅샷을 다시 읽어 꽂지 않는가 ──
REBIND_BODY=$(awk '
  /fun rebindSilhouetteEditor\(\)/ { inre = 1 }
  inre { print }
  inre && /^    }$/ { inre = 0 }
' "$HOST")

if ! printf '%s\n' "$BIND_BODY" | grep -q "if (snapshot) {"; then
  echo "  ✗ 스냅샷 갈래가 없다 — 되살리는 시트에도 위젯을 다시 읽어 꽂게 된다" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
elif ! printf '%s\n' "$REBIND_BODY" | grep -qE "bindSilhouetteEditor\(.*snapshot = false"; then
  # **이 자리가 이 검사에서 가장 중요하다.** 되살리는 길이 스냅샷을 다시 읽으면,
  # 폼이 값을 채우기 전의 빈 위젯이 되쓰기의 바탕이 되어 사용자가 쓰던 칸이 지워진다 —
  # 잃은 것을 되찾자고 만든 길이 새 유실을 내는 모양이고, 실제로 이 판의 첫 구현이 그랬다.
  echo "  ✗ rebindSilhouetteEditor가 snapshot = false로 꽂지 않는다 —" >&2
  echo "    폼이 값을 채우기 전의 빈 위젯이 되쓰기의 바탕이 되어, 되살린 편집기의" >&2
  echo "    [적용]이 사용자가 쓰던 칸을 지운다" >&2
  VIOLATIONS=$((VIOLATIONS + 1))
else
  echo "  ✓ 열 때 뜬 스냅샷은 처음 열 때만 읽는다(되살리는 길은 시트가 들고 있던 것을 쓴다)"
fi

if [ "$VIOLATIONS" -gt 0 ]; then
  echo >&2
  echo "실루엣 편집기 회전 보존 배선 검사 실패 — 위반 $VIOLATIONS건" >&2
  exit 1
fi

echo "실루엣 편집기 회전 보존 배선 검사 통과"
