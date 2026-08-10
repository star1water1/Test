#!/bin/bash
# 백로그 표의 머리 표식이 행의 상태와 맞는가 (`docs/remaining_work_2026-07.md` 4장).
#
# 4장 머리의 규약: **🔴🟠🟡🟢🔵는 *살아 있는* 항목의 심각도이고, 닫힌 항목은 ✅다.**
# 닫으면서 심각도를 그대로 두면 **표가 거짓말을 한다** — *"무엇이 급한가"*를 심각도로 훑는
# 콜드 세션이 닫힌 행을 급한 것으로 읽고, 가르려면 행 본문을 끝까지 읽어야 한다.
#
# **이 검사를 만드는 이유:** 그 규약은 2026.08.07 콜드 검토가 다섯 행을 손으로 고치며
# 세웠는데, **하루 만에 다섯 행이 다시 어긋났다**(B-132·B-145·B-146·B-150·B-151 —
# 2026.08.08 콜드 검토가 실측). 같은 자리가 두 판 연속 낡았으면 고칠 것은 값이 아니라
# **손으로 지키는 관행**이다. 이 저장소가 R 범위 표기에서 내린 처방과 같다.
#
# **보는 것이 둘로 늘었다 (2026.08.10, B-182).**
#   [A] 닫힌 행이 살아 있는 심각도를 들고 있는가 (원래의 것)
#   [B] **표식이 아예 없는 행이 있는가** — 새로 더한 것이다. 2026.08.10까지 4장에는 표식
#       없는 행이 **80개** 있었고(전체의 44%), 그것이 *"⑥이 전수다"*라는 인수인계 판의
#       거짓 선언을 떠받치고 있었다 — 표식이 없으면 **살아 있는지 닫혔는지를 세는 방법 자체가
#       없어서** 아무도 셈을 반증할 수 없다. [A]만으로는 이 부류가 영영 안 잡힌다:
#       표식이 없으면 *"닫혔는데 심각도를 들었다"*에 해당할 수가 없기 때문이다.
#
# **탈출구 하나 — 반만 닫힌 행.** 본문에 *"이 행이 열려 있는 이유"*를 적은 행은 *처리 완료*
# 라는 글자가 있어도 살아 있는 것으로 본다(B-27이 그 모양이다: ②는 닫혔고 ①이 남았다).
# 그 문구가 없으면 검사는 글자만 보고 닫힌 행으로 읽으므로, **반만 닫았으면 사유를 적을 것.**
#
# 사용법: tools/check_backlog_markers.sh
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DOC="$REPO/docs/remaining_work_2026-07.md"

[ -f "$DOC" ] || { echo "대상 문서가 없습니다: $DOC" >&2; exit 1; }

echo "── 백로그 표식 검사 ──"

# 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다.
# python3이 없거나 정규식이 안 맞으면 오류가 억제되어 "위반 없음"과 구별되지 않는다
# (2026.08.07 B-146 슬라이스가 겪은 그 부류다). 지어낸 입력에 반응하는지부터 본다.
command -v python3 >/dev/null 2>&1 || { echo "  ✗ python3이 없어 이 검사는 아무것도 볼 수 없습니다" >&2; exit 1; }

SELFTEST=$(mktemp)
cat > "$SELFTEST" <<'EOF'
| B-901 | 🔴 **살아 있는 행** | 아직 안 고쳤다 |
| B-902 | 🟠 **닫힌 행인데 표식이 남았다** | → ✅ 처리 완료. 이 행은 닫혔다 |
| B-903 | ✅ **바르게 닫힌 행** | → ✅ 처리 완료. 이 행은 닫혔다 |
| B-904 | **표식이 아예 없는 행** | 아직 안 고쳤다 |
| ~~B-905~~ | ~~**취소선으로 닫은 옛 행**~~ — 처리 완료 |
| B-906 | 🟡 **반만 닫힌 행** | ②는 처리 완료. **이 행이 열려 있는 이유**는 ①이다 |
EOF

# 위반은 `종류<TAB>번호[<TAB>표식]`으로 낸다 — A: 닫힘인데 심각도 · B: 표식 없음.
scan() {
  python3 - "$1" <<'PYEOF'
import sys
path = sys.argv[1]
LIVE = '🔴🟠🟡🟢🔵'
bad = []
for line in open(path, encoding='utf-8'):
    if not line.startswith('| B-'):
        continue
    cells = line.split('|')
    if len(cells) < 3:
        continue
    bid = cells[1].strip()
    marker = cells[2].strip()[:1]
    # 취소선으로 닫은 옛 관행(~~B-1~~)은 이 표식 규약 이전 것이라 대상이 아니다.
    # 번호 자체가 기계로 잡히므로 소급할 필요가 없다(4장 머리).
    if '~~' in cells[1]:
        continue
    # 반만 닫힌 행은 사유를 적었으면 살아 있는 것으로 본다(4장 머리의 탈출구).
    closed = (('이 행은 닫혔다' in line) or ('처리 완료' in line)) \
        and ('이 행이 열려 있는 이유' not in line)
    if marker in LIVE and closed:
        bad.append('A\t%s\t%s' % (bid, marker))
    elif marker not in LIVE and marker != '✅':
        bad.append('B\t%s' % bid)
for b in bad:
    print(b)
sys.exit(1 if bad else 0)
PYEOF
}

# 세는 법 — 이 출력이 "남은 일이 몇 건인가"의 단일 소스다.
# **문서 어디에도 그 값을 적지 않는다**(적으면 다음 판에 낡는다 — 4장 머리).
census() {
  python3 - "$1" <<'PYEOF'
import sys
path = sys.argv[1]
ORDER = ['🔴', '🟠', '🟡', '🟢', '🔵']
counts = {m: 0 for m in ORDER}
closed = struck = 0
for line in open(path, encoding='utf-8'):
    if not line.startswith('| B-') and not line.startswith('| ~~B-'):
        continue
    cells = line.split('|')
    if len(cells) < 3:
        continue
    if '~~' in cells[1]:
        struck += 1
        continue
    marker = cells[2].strip()[:1]
    if marker in counts:
        counts[marker] += 1
    elif marker == '✅':
        closed += 1
live = sum(counts.values())
print('살아 있는 행 %d개 — %s' % (
    live, ' · '.join('%s %d' % (m, counts[m]) for m in ORDER if counts[m])))
print('닫힌 행 %d개 (✅) + 취소선으로 닫은 옛 행 %d개' % (closed, struck))
PYEOF
}

SELF=$(scan "$SELFTEST")
selfcheck() {  # $1 = 기대 위반 줄, $2 = 설명
  if printf '%s\n' "$SELF" | grep -qF "$1"; then return 0; fi
  echo "  ✗ 탐지기 자기 시험 실패 — $2" >&2
  rm -f "$SELFTEST"; exit 1
}
selfcheck "A	B-902" "닫힌 행의 남은 심각도를 못 잡습니다"
selfcheck "B	B-904" "표식이 없는 행을 못 잡습니다"
for ok in B-901 B-903 B-905 B-906; do
  if printf '%s\n' "$SELF" | grep -q "$ok"; then
    echo "  ✗ 탐지기 자기 시험 실패 — 바른 행($ok)을 위반으로 셉니다" >&2
    rm -f "$SELFTEST"; exit 1
  fi
done
echo "  ✓ 탐지기 자기 시험 통과 (남은 심각도 · 표식 없음 둘 다 잡고, 취소선·반만 닫힌 행은 통과)"
rm -f "$SELFTEST"

OUT=$(scan "$DOC")
STATUS=$?
TOTAL=$(grep -c '^| B-\|^| ~~B-' "$DOC")

if [ $STATUS -eq 0 ]; then
  echo "  ✓ 백로그 행 ${TOTAL}개 — 닫힌 행이 든 심각도 없음 · 표식 없는 행 없음"
  echo
  census "$DOC" | sed 's/^/  /'
  echo
  echo "백로그 표식 검사 통과"
  exit 0
fi

if printf '%s\n' "$OUT" | grep -q '^A'; then
  echo "  ✗ 닫혔는데 살아 있는 심각도를 든 행:" >&2
  printf '%s\n' "$OUT" | awk -F'\t' '$1=="A"{print "      " $2 "  " $3}' >&2
  echo "    → 머리 표식을 ✅로 바꾸십시오. 반만 닫혔다면 **이 행이 열려 있는 이유**를 적으십시오." >&2
fi
if printf '%s\n' "$OUT" | grep -q '^B'; then
  echo "  ✗ 머리 표식이 없는 행:" >&2
  printf '%s\n' "$OUT" | awk -F'\t' '$1=="B"{print "      " $2}' >&2
  echo "    → 닫혔으면 ✅, 살아 있으면 심각도(🔴🟠🟡🟢🔵)를 다십시오 (4장 머리 규약)." >&2
fi
exit 1
