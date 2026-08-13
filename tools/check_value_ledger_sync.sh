#!/bin/bash
# 가져오기가 `character_field_values`를 건드리는 자리마다 장부([CharacterValueLedger])를
# 함께 맞추는가 (B-72 ②).
#
# **왜 기계가 봐야 하는가.** 장부는 가져오기 한 번 동안 `(캐릭터, 필드)` 값의 현재 상태를
# 들고 있고, 조회를 값 단위에서 캐릭터 단위로 내린 것이 그 전부다(실사용 ×30에서 57만 회 →
# 6,420회). 그 대가로 **DB와 사본이 갈릴 수 있는 자리**가 생겼는데, 갈렸을 때의 모양이 둘 다 나쁘다:
#   · 지운 것을 안 지운 것으로 기억하면 → **있는 값에 insert** → `(characterId, fieldDefinitionId)`
#     유니크 색인 위반으로 **가져오기가 통째로 실패한다**(시끄럽지만 사용자가 못 쓴다)
#   · 다시 쓴 것을 옛 것으로 기억하면 → **조용히 옛 값으로 판단한다**(이쪽이 더 나쁘다)
#
# 실제로 2026.08.13 콜드 검토가 그런 자리를 하나 잡았다 — `migrateCharacterToUniverse`가
# 세계관 이동에서 **값을 통째로 다시 쓰는데** 장부는 옛 필드 id를 들고 있었다. 그 부류는
# **새 코드가 쓰기를 하나 더할 때마다 다시 생기고**, 순수 시험은 장부의 성질만 잴 뿐
# *가져오기가 그것을 제대로 쓰는가*에 닿지 못한다(그 파일은 8,700줄짜리 suspend 함수 뭉치다).
#
# **무엇을 보는가.** `ExcelImportService.kt`에서 `characterFieldValueDao()`로 **쓰는** 호출을
# 전부 찾아, 같은 자리 가까이에 장부를 맞추는 호출(`valueLedger.put/remove/forget/load`)이
# 있는지 본다. 없으면 실패하고, **일부러 없는 자리는 사유 주석으로 면제**한다
# (`장부 무관:` 을 그 호출 위 6줄 안에 적을 것 — 저장소의 다른 검사와 같은 관행).

set -u
REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SRC="$REPO/app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt"
LEDGER="$REPO/app/src/main/java/com/novelcharacter/app/util/CharacterValueLedger.kt"

echo "── 값 장부 동기화 검사 (B-72 ②) ──"

FAIL=0

if [ ! -f "$SRC" ] || [ ! -f "$LEDGER" ]; then
  echo "  ✗ 대상 파일을 찾지 못했다: $SRC / $LEDGER"
  exit 1
fi

# 탐지기 자기 시험 — 이 검사가 아무것도 못 찾고 '통과'하는 것이 가장 나쁜 결말이다
# (저장소의 fail-closed 관행. check_backlog_markers.sh가 같은 자리에 같은 것을 둔다).
PROBE=$(grep -cE 'characterFieldValueDao\(\)\.(insert|update|deleteValue|deleteValuesNotInUniverse)' "$SRC")
if [ "$PROBE" -eq 0 ]; then
  echo "  ✗ 자기 시험 실패 — 쓰기 호출을 하나도 못 찾았다. 이름이 바뀌었으면 이 스크립트를 고칠 것"
  exit 1
fi
echo "  · 자기 시험: 쓰기 호출 ${PROBE}건 탐지"

# 장부가 맞추는 법을 실제로 제공하는가
for fn in put remove forget load isLoaded reset; do
  if ! grep -q "fun $fn(" "$LEDGER"; then
    echo "  ✗ CharacterValueLedger에 '$fn'이 없다 — 이 검사의 전제가 무너졌다"
    FAIL=1
  fi
done

# 쓰기 호출마다 앞뒤 6줄 안에 장부 호출이 있는가
while IFS=: read -r line _; do
  from=$((line > 6 ? line - 6 : 1))
  to=$((line + 6))
  WINDOW=$(sed -n "${from},${to}p" "$SRC")
  if echo "$WINDOW" | grep -q 'valueLedger\.\(put\|remove\|forget\|load\)'; then
    continue
  fi
  if echo "$WINDOW" | grep -q '장부 무관:'; then
    REASON=$(echo "$WINDOW" | grep -o '장부 무관:.*' | head -1)
    echo "  · $SRC:$line — 면제($REASON)"
    continue
  fi
  echo "  ✗ $SRC:$line — character_field_values를 쓰는데 장부를 맞추지 않는다"
  echo "      $(sed -n "${line}p" "$SRC" | sed 's/^[[:space:]]*//')"
  echo "      → valueLedger.put/remove/forget 중 하나를 부르거나, 위에 '장부 무관: 사유'를 적을 것"
  FAIL=1
done < <(grep -nE 'characterFieldValueDao\(\)\.(insert|update|deleteValue|deleteValuesNotInUniverse)' "$SRC")

# 가져오기마다 비우는가 — 안 비우면 둘째 가져오기가 DB가 아니라 지난번 사본을 본다
if ! grep -q 'valueLedger\.reset()' "$SRC"; then
  echo "  ✗ valueLedger.reset() 호출이 없다 — 연속 가져오기에서 지난번 사본이 남는다"
  FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then
  echo "  ✓ 쓰기 자리가 전부 장부와 짝을 이룬다"
  echo ""
  echo "값 장부 동기화 검사 통과"
  exit 0
fi
echo ""
echo "값 장부 동기화 검사 실패"
exit 1
