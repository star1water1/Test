# -*- coding: utf-8 -*-
"""체크 시트가 319단계를 하나도 안 잃었는지 검산한다. 위반 시 종료코드 1.

**`tools/check_*.sh` 무리에 넣지 않는다.** 2026.08.19 사용자 판정으로 검사는 동결에
가깝게 다루고, 새 검사는 *같은 부류의 앱 결함이 두 번 난 자리*에만 세운다(CLAUDE.md).
이것은 앱이 아니라 **문서의 자기 정합**을 보는 것이라 그 문턱 밖이다 —
시트를 고칠 때 손으로 돌린다.

자기 재공격(2026.08.19 실측): 묶음 정의에서 단계를 빼면 ①이, 두 묶음에 겹쳐 넣으면
①이, 시트에서 줄을 지우면 ②가 각각 종료코드 1로 죽는다.
"""
import json, io, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SHEET = os.path.join(HERE, '..', '..', 'docs', 'device_walkthrough.md')
STEPS = json.load(io.open(os.path.join(HERE, 'steps.json'), encoding='utf-8'))
SESS = json.load(io.open(os.path.join(HERE, 'sessions.json'), encoding='utf-8'))
sheet = io.open(SHEET, encoding='utf-8').read()

fail = 0
ids = {f"{r['round']}-{r['id']}" for r in STEPS}

# ① 묶음 정의가 319단계를 정확히 한 번씩 쓰는가
used = [f"{a}-{b}" for s in SESS for a, b in s['items']]
from collections import Counter
c = Counter(used)
dup = sorted(k for k, v in c.items() if v > 1)
miss = sorted(ids - set(c))
unknown = sorted(set(c) - ids)
if dup or miss or unknown:
    print(f"  ✗ 묶음 정의 — 중복 {dup} · 누락 {miss} · 미상 {unknown}"); fail = 1
else:
    print(f"  ✓ 묶음 정의가 {len(ids)}단계를 정확히 한 번씩 쓴다 (묶음 {len(SESS)}개)")

# ② 생성된 시트에 그 319단계가 다 있는가
inSheet = set(re.findall(r'\*\*`(\d+-[ㄱ-ㅎ][0-9-]*)`\*\*', sheet))
gone = sorted(ids - inSheet)
if gone:
    print(f"  ✗ 시트에서 사라진 단계 {len(gone)}개: {gone[:8]}"); fail = 1
else:
    print(f"  ✓ 시트가 {len(ids)}단계를 전부 담고 있다")

# ③ 원 절 번호가 시트에서 살아 있는가 (뺀 둘은 부록이 든다)
refs = {r['ref'].strip('`') for r in STEPS if r['ref'] and r['ref'] != '—'}
sec = {m for m in re.findall(r'3-\d+', ' '.join(refs))}
lost = sorted(s for s in sec if s not in sheet)
if lost:
    print(f"  ✗ 원 절 번호가 시트에서 사라졌다: {lost}"); fail = 1
else:
    print(f"  ✓ 원 절 번호 {len(sec)}종이 전부 시트에 산다")

# ④ 체크박스가 단계마다 있는가 (준비물 체크박스는 더 많으므로 하한만 본다)
boxes = sheet.count('- [ ]')
if boxes < len(ids):
    print(f"  ✗ 체크박스가 {boxes}개로 단계 수({len(ids)})보다 적다"); fail = 1
else:
    print(f"  ✓ 체크박스 {boxes}개 (단계 {len(ids)} + 준비물)")

# ⑤ 자기 출력 증명 — 아무것도 못 뜨면 위 넷이 전부 조용히 통과한다
if not ids or not inSheet or not sec:
    print("  ✗ 뜬 것이 하나도 없다 — 이 검사가 아무것도 보고 있지 않다"); fail = 1

print("체크 시트 검산 " + ("실패" if fail else "통과"))
sys.exit(fail)
