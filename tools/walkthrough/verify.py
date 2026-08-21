# -*- coding: utf-8 -*-
"""체크 시트가 단계를 하나도 안 잃었는지 검산한다. 위반 시 종료코드 1.

**수를 여기 적지 않는다** — 적으면 낡는다(2026.08.21에 실제로 낡았다: 319라 적힌 채 그 몇 배가 됐다).
세는 것은 아래 ①②가 하고, 그 수를 출력으로 낸다.

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
# **걷어낸 단계(`drop`)는 시트에서 빠지고 부록에 산다** — 아래 ①②④는 활성 단계만 보고,
# ③(원 절 번호)은 걷어낸 것까지 전부 본다(부록이 그 번호를 들고 있어야 한다).
ACTIVE = [r for r in STEPS if not r.get('drop')]
DROPPED = [r for r in STEPS if r.get('drop')]
ids = {f"{r['round']}-{r['id']}" for r in ACTIVE}

# ① 묶음 정의가 활성 단계를 정확히 한 번씩 쓰는가
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

# ② 생성된 시트에 그 단계가 다 있는가
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

# ⑤ 걷어낸 단계가 **사유를 대는가** — 그냥 빠진 것과 걷어낸 것을 여기서 가른다
#
# 이 저장소는 *"덮으니 뺀다"*를 **흔한 낱말 grep**으로 검증해 19/19 통과를 받은 적이 있고,
# 그 열아홉은 전부 무관한 파일이었다(`따옴표 멱등` → *"멱등"* 한 낱말이 걸렸다).
# 그래서 여기서는 **시험 이름이 소스에 실재하는지**를 본다 — 파일을 열어 함수 선언을 찾는다.
#
# `코드` 사유는 시험을 만들지 않는 부류라(사용자 지시) 시험 이름을 요구하지 않는다.
# 대신 **왜 읽으면 끝나는지**를 한 줄로 적게 한다 — 빈 사유는 사유가 아니다.
if DROPPED:
    TESTROOT = os.path.join(HERE, '..', '..', 'app', 'src', 'test')
    # **`@Test`가 붙은 것만 센다** (콜드 검토 2026.08.19). 종전에는 아무 `fun`이나 걷었는데,
    # 그러면 이 검산의 주장(*"시험 이름이 실재한다"*)과 실제로 보는 것(*"그 이름의 함수가
    # 있다"*)이 어긋난다 — 헬퍼 함수 이름을 근거로 대도 통과했다.
    names = set()
    TESTFUN = re.compile(
        r'@Test[^\n]*\n(?:\s*@[^\n]*\n)*\s*(?:suspend\s+)?fun\s+(?:`([^`]+)`|([^`(\s]+))\s*\(')
    for dp, _, fns in os.walk(TESTROOT):
        for fn in fns:
            if not fn.endswith('.kt'):
                continue
            src = io.open(os.path.join(dp, fn), encoding='utf-8').read()
            cls = fn[:-3]
            for m in TESTFUN.finditer(src):
                names.add(f"{cls}.{m.group(1) or m.group(2)}")
    bad = []
    for r in DROPPED:
        d = r.get('drop') or {}
        step = f"{r['round']}-{r['id']}"
        why = d.get('why')
        if why in ('시험', '시험+코드'):
            ts = d.get('test') or []
            if not ts:
                bad.append(f"{step}: '{why}'인데 시험 이름이 없다"); continue
            missing = [t for t in ts if t not in names]
            if missing:
                bad.append(f"{step}: 저장소에 없는 시험 {missing}")
        if why in ('코드', '시험+코드'):
            # **어디를 읽으면 끝나는지까지 적게 한다.** *"코드 보면 안다"*는 말만으로는
            # 다음 사람이 그 자리를 다시 찾아야 하고, 그러느니 기기에서 밟는 편이 싸다.
            #
            # **줄 번호가 아니라 `anchor`(그 자리의 코드 한 토막)를 본다** (콜드 검토
            # 2026.08.19). 종전에는 `파일:줄`을 적고 **파일 존재만** 봤는데, 줄 번호가
            # *같은 판 안에서* 이미 낡았다 — `9-ㅂ`가 적어 둔 870줄은 그 판이 그 파일에
            # 예순 줄을 더하면서 엉뚱한 자리(`buildEventFieldInputs()`)를 가리키게 됐고,
            # 검산은 그것을 보지 못한 채 통과했다.
            #
            # 앵커는 낡지 않을 뿐 아니라 **더 세다**: 그 코드가 바뀌면 검산이 깨지고,
            # 그때가 바로 이 단계가 순회표로 돌아와야 할 때다.
            where = (d.get('where') or '').strip()
            anchor = (d.get('anchor') or '').strip()
            if not where:
                bad.append(f"{step}: '{why}'인데 읽을 자리(`where`)가 없다"); continue
            if ':' in where:
                bad.append(f"{step}: `where`에 줄 번호를 적지 않는다 — 낡는다 ({where})"); continue
            full = os.path.join(HERE, '..', '..', where)
            if not os.path.exists(full):
                bad.append(f"{step}: 읽을 자리의 파일이 없다 ({where})"); continue
            if not anchor:
                bad.append(f"{step}: '{why}'인데 앵커(`anchor`)가 없다"); continue
            if anchor not in io.open(full, encoding='utf-8').read():
                bad.append(f"{step}: 앵커가 그 파일에 없다 — 코드가 바뀌었으면 이 단계는 "
                           f"순회표로 돌아와야 한다 ({where} · {anchor[:48]!r})")
            if not (d.get('note') or '').strip():
                bad.append(f"{step}: '{why}'인데 사유가 비었다")
        if why not in ('시험', '코드', '시험+코드'):
            bad.append(f"{step}: 사유가 '시험'·'코드'·'시험+코드' 어느 것도 아니다 ({why!r})")

        # **대조 기록과 어긋나면 잡는다** (콜드 검토 2026.08.19). 이 판이 배운 것이
        # 정확히 이 자리다 — 적대 검증이 *"그 시험은 expect의 절반만 덮는다"*고 반증한
        # 단계를 `시험`만으로 걷으면 나머지 절반이 조용한 구멍이 된다.
        a = r.get('audit')
        if a and a.get('refuted') and why == '시험':
            bad.append(f"{step}: 적대 검증이 반증한 단계인데 '시험'만으로 걷었다 — '시험+코드'여야 한다")
        if a and not a.get('covered') and why in ('시험', '시험+코드'):
            bad.append(f"{step}: 대조가 '안 덮임'이라 적었는데 시험을 근거로 걷었다")
    if bad:
        print(f"  ✗ 걷어낸 단계의 사유 {len(bad)}건이 성립하지 않는다:")
        for b in bad[:12]:
            print(f"      · {b}")
        fail = 1
    else:
        from collections import Counter as _C
        tally = _C(r['drop']['why'] for r in DROPPED)
        print(f"  ✓ 걷어낸 {len(DROPPED)}단계가 전부 사유를 댄다 "
              + " · ".join(f"{k} {v}" for k, v in sorted(tally.items())))

    # ⑥ 걷어낸 단계도 부록에 **한 줄씩** 살아 있는가 — 표에서 빠지면 조용한 구멍이다
    goneFromAppendix = sorted(
        f"{r['round']}-{r['id']}" for r in DROPPED
        if f"`{r['round']}-{r['id']}`" not in sheet
    )
    if goneFromAppendix:
        print(f"  ✗ 부록에서 사라진 단계 {len(goneFromAppendix)}개: {goneFromAppendix[:8]}"); fail = 1
    else:
        print(f"  ✓ 걷어낸 {len(DROPPED)}단계가 전부 부록에 산다")

# ⑦ 자기 출력 증명 — 아무것도 못 뜨면 위가 전부 조용히 통과한다
if not ids or not inSheet or not sec:
    print("  ✗ 뜬 것이 하나도 없다 — 이 검사가 아무것도 보고 있지 않다"); fail = 1

print("체크 시트 검산 " + ("실패" if fail else "통과"))
sys.exit(fail)
