#!/bin/bash
# 백로그 표의 머리 표식이 행의 상태와 맞는가 (`docs/remaining_work_2026-07.md` 4장).
#
# 4장 머리의 규약: **🔴🟠🟡🟢는 *살아 있는* 항목의 심각도이고, 닫힌 항목은 ✅다.**
# 닫으면서 심각도를 그대로 두면 **표가 거짓말을 한다** — *"무엇이 급한가"*를 심각도로 훑는
# 콜드 세션이 닫힌 행을 급한 것으로 읽고, 가르려면 행 본문을 끝까지 읽어야 한다.
#
# **이 검사를 만드는 이유:** 그 규약은 2026.08.07 콜드 검토가 다섯 행을 손으로 고치며
# 세웠는데, **하루 만에 다섯 행이 다시 어긋났다**(B-132·B-145·B-146·B-150·B-151 —
# 2026.08.08 콜드 검토가 실측). 같은 자리가 두 판 연속 낡았으면 고칠 것은 값이 아니라
# **손으로 지키는 관행**이다. 이 저장소가 R 범위 표기에서 내린 처방과 같다.
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
EOF

scan() {
  python3 - "$1" <<'PYEOF'
import sys
path = sys.argv[1]
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
    if '~~' in cells[1]:
        continue
    closed = ('이 행은 닫혔다' in line) or ('처리 완료' in line)
    if marker in '🔴🟠🟡🟢' and closed:
        bad.append('%s  %s' % (bid, marker))
for b in bad:
    print(b)
sys.exit(1 if bad else 0)
PYEOF
}

if scan "$SELFTEST" | grep -q 'B-902'; then
  echo "  ✓ 탐지기 자기 시험 통과 (닫힌 행의 남은 표식을 잡는다)"
else
  echo "  ✗ 탐지기 자기 시험 실패 — 이 검사는 지금 아무것도 못 봅니다" >&2
  rm -f "$SELFTEST"; exit 1
fi
if scan "$SELFTEST" | grep -q 'B-903'; then
  echo "  ✗ 탐지기 자기 시험 실패 — 바르게 닫힌 행을 위반으로 셉니다" >&2
  rm -f "$SELFTEST"; exit 1
fi
rm -f "$SELFTEST"

OUT=$(scan "$DOC")
STATUS=$?
TOTAL=$(grep -c '^| B-' "$DOC")

if [ $STATUS -eq 0 ]; then
  echo "  ✓ 백로그 행 ${TOTAL}개 — 닫힌 행이 살아 있는 심각도를 든 자리 없음"
  echo
  echo "백로그 표식 검사 통과"
  exit 0
fi

echo "  ✗ 닫혔는데 살아 있는 심각도를 든 행:" >&2
echo "$OUT" | sed 's/^/      /' >&2
echo >&2
echo "  머리 표식을 ✅로 바꾸십시오 (4장 머리 규약)." >&2
exit 1
