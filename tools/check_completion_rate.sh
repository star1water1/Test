#!/bin/bash
# 완성도 판정 단일 소스 검사 (규약 R-34) — 필드 완성도 백분율은 CompletionRate만 낸다.
#
# 배경(B-100 착수 대조): 완성도를 내는 자리가 열 곳인데 **분자를 세는 법이 갈려 있었다.**
# 여섯 곳은 그 캐릭터의 값을 전부 셌고(`values.count { it.value.isNotBlank() }`),
# 네 곳만 해당 세계관 정의와 교집합을 냈다. 전자는 CharacterFieldValueMerge가 일부러 남기는
# 보존 값(다른 세계관·사건 정의를 가리키는 값)까지 분자에 넣어 완성도를 부풀렸고, 100%를
# 넘을 수도 있었다. 게다가 캐릭터 상세 화면은 분모에서 CALCULATED를 빼지 않아 **같은 캐릭터가
# 화면마다 다른 %로 보였다.**
#
# 이것은 R-33(복원 미리보기 정합)과 **같은 부류의 세 번째**다. 그래서 같은 처방을 쓴다 —
# 고치는 데서 그치지 않고 규약으로 올리고 검사를 단다. B-101 세션이 남긴 교훈 그대로:
# *"고치고 규약으로 올리지 않으면 같은 결함이 나머지 자리에서 살아남는다."*
#
# ── 무엇을 보는가 ──
# ① 금지: 필드 정의 컬렉션의 `.size`로 나눠 백분율을 내는 식이 CompletionRate 밖에 있는 것.
#    (별명 보유율처럼 `characters.size`·`nameBank.size`로 나누는 것은 완성도가 아니므로 대상 밖.
#     개별 필드 완성도(`valuesByFieldDef[fd.id]` / 캐릭터 수)도 축이 달라 대상 밖이다.)
# ② 필수: 완성도를 소비한다고 알려진 파일이 CompletionRate를 실제로 부르는가.
#    ①만 두면 "아무 계산도 안 하게" 고쳐 놓고 화면이 옛 값을 그대로 쓰는 우회가 통과한다.
#
# 사용법: tools/check_completion_rate.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app"
SINGLE_SOURCE="$SRC/util/CompletionRate.kt"

echo "── 완성도 판정 단일 소스 검사 (R-34) ──"

fail=0

# ── ① 필드 정의 수로 나누는 백분율은 CompletionRate 밖에 있으면 안 된다 ──
# 분모 식별자에 field/def 가 들어간 것만 본다(대소문자 무시) — 그것이 '칸의 개수'라는 뜻이다.
#
# **앞자리를 선택으로 두는 것이 요점이다.** 처음에는 `[A-Za-z_][A-Za-z0-9_.]*(field|def)`로 적었는데
# 그러면 접두사가 **반드시 한 글자 이상**이라 가장 흔한 이름인 `fields.size`가 통과했다 —
# 자기 재공격(이 검사를 병합 전 코드에 돌려 보기)에서 여섯 자리 중 하나만 잡히는 것으로 드러났다.
# `.size` 역시 선택이다: 종전 코드에는 `totalFields`처럼 이미 세어 둔 Int로 나누는 자리가 있었다.
offenders=$(grep -rnE '/ *[A-Za-z0-9_.]*([Ff]ield|[Dd]ef)[A-Za-z0-9_.]*(\.size)? *\* *100' \
              "$SRC" --include=*.kt 2>/dev/null | grep -v "util/CompletionRate.kt" || true)
if [ -n "$offenders" ]; then
  echo "  ✗ 필드 수로 나누는 완성도 계산이 단일 소스 밖에 있습니다:"
  echo "$offenders" | sed 's/^/      /'
  echo "      → CompletionRate.of/percentOf 를 쓸 것. 분자는 반드시 정의 집합과의 교집합이어야 한다."
  fail=1
fi

# ── ② 완성도 소비처는 단일 소스를 지나야 한다 ──
# 새 소비처가 생기면 여기 등재할 것. 등재를 잊으면 그 화면만 조용히 갈린다.
CONSUMERS="
$SRC/ui/stats/StatsDataProvider.kt
$SRC/ui/character/CharacterDetailFragment.kt
$SRC/ui/supplement/SupplementViewModel.kt
"
for f in $CONSUMERS; do
  [ -f "$f" ] || { echo "  ✗ 등재된 소비처가 없습니다: $f (이름이 바뀌었으면 이 목록을 고칠 것)"; fail=1; continue; }
  if ! grep -q "CompletionRate" "$f"; then
    echo "  ✗ 완성도 소비처가 단일 소스를 부르지 않습니다: $f"
    fail=1
  fi
done

[ -f "$SINGLE_SOURCE" ] || { echo "  ✗ 단일 소스가 없습니다: $SINGLE_SOURCE"; fail=1; }

if [ "$fail" -eq 0 ]; then
  echo "  ✓ 완성도 백분율은 CompletionRate 하나가 낸다"
  echo ""
  echo "완성도 판정 단일 소스 검사 통과"
  exit 0
fi
echo ""
echo "완성도 판정 단일 소스 검사 실패" >&2
exit 1
