#!/usr/bin/env bash
# 전역 필드키의 유일성은 색인이 아니라 **앱이** 지킨다 (B-230 ⓑ)
#
# **무엇을 막는가:** `field_definitions`에 새로 쓰는 자리가 *전역 구역의 키 중복*을 생각하지
# 않고 들어오는 것.
#
# 이 표의 유니크 색인은 `(universeId, entityType, key)`인데 **`universeId`가 nullable이다**
# (전역 구역 = 무소속 캐릭터의 필드. 2026.08.07 사용자 확정, B-119 확장). SQLite는 NULL끼리를
# 서로 다른 값으로 보므로 `(NULL, 'CHARACTER', 'gender')`는 **몇 번이고 들어간다** — 즉
# 전역 구역에서는 색인이 아무것도 막지 않고, **막는 것은 앱 코드뿐이다.**
#
# 그 사실이 위험한 이유는 *지금 새는 데가 있어서*가 아니다. 2026.08.16 전수 확인 결과
# 여섯 자리 전부 막혀 있었다. 위험한 것은 **일곱째가 조용히 들어오는 것**이다 — 세계관 필드는
# 같은 실수를 해도 색인이 즉시 잡아 주는데, 전역 구역만 잡히지 않아 **테스트도 통과하고
# 예외도 없이 중복 정의가 쌓인다.** 그러면 무소속 캐릭터 폼에 같은 필드가 두 줄로 뜨고,
# 값은 그중 하나에만 붙는다(원칙 04 — 일일이 확인해야 존재를 아는 데이터).
#
# **왜 시험이 아니라 검사인가:** 쓰는 자리가 전부 Room에 묶인 저장소·서비스라 순수 하네스가
# 원리적으로 닿지 않는다(`AppDatabase`를 든다). 그리고 진짜 위험은 *새 자리가 생기는 것*이라
# 시험으로는 막을 수 없다 — 시험은 이미 아는 자리만 잰다. `check_import_row_queries.sh`·
# `check_in_clause_chunk.sh`와 같은 근거다.
#
# **고치는 법:** 쓰는 줄이나 그 위 여섯 줄 안에 **무엇이 유일성을 지키는지**를 적는다.
#
#     // 전역키 보증(universeId 비-null — 유니크 색인이 잡는다)
#     // 전역키 보증(getGlobalFieldByKey로 먼저 조회)
#     // 전역키 보증(DefaultFieldPlan이 기존 key를 걸러 심는다)
#
# 명단이 아니라 **자리에** 적는 것은 이 저장소의 규약이다(`check_import_row_queries.sh`가
# 먼저 겪었다 — 명단으로 짰더니 함수 하나가 그 안의 위반 넷을 통째로 덮었다).
#
# **이 검사가 못 보는 것 — 적어 두지 않으면 다음 사람이 초록을 전수로 읽는다.**
# 아래는 `val` 별칭까지만 따라간다. 그래서 이 셋은 원리적으로 빠진다:
#   ① `by lazy { db.fieldDefinitionDao() }` 꼴의 위임 (현재 저장소에 0건 — 실측)
#   ② DAO를 **인자로 받아** 쓰는 함수 (`fun f(dao: FieldDefinitionDao) { dao.insert(…) }`)
#   ③ DAO를 필드에 담아 다른 클래스로 넘기는 경우
# 셋 다 지금은 없고, 생기면 이 검사는 **조용히 통과한다.** 늘리려면 별칭 추적을 넓히기보다
# *그 자리에서* 표식을 요구하는 편이 낫다 — 별칭 추적을 깊게 하면 거짓 양성이 늘고,
# **거짓 양성이 쌓인 검사는 꺼진다**(R-53이 같은 자리에서 배운 것).
#
# 기준선은 **0건**이다.

set -uo pipefail
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
MAIN="$REPO/app/src/main/java/com/novelcharacter/app"

if [ ! -d "$MAIN" ]; then
  echo "대상 디렉터리를 찾을 수 없습니다: $MAIN" >&2
  exit 2
fi

echo "── 전역 필드키 보증 검사 (B-230 ⓑ) ──"

SCAN=$(python3 - "$MAIN" <<'PY'
import os, re, sys

root = sys.argv[1]
# 이 표에 실제로 행을 넣는 호출. update는 대상이 아니다 — 기존 행의 키를 바꾸는 것은
# 색인이 있든 없든 같은 위험이지만, 전역 구역에 *새 중복을 만드는* 것은 insert뿐이다.
DIRECT = r'fieldDefinitionDao(?:\(\))?'
MARK = re.compile(r'전역키 보증\(')
LOOKBACK = 6

# **별칭을 따라간다.** `private val fieldDao get() = db.fieldDefinitionDao()` 꼴로 한 번
# 이름을 갈아 끼우면 이름으로만 찾는 검사는 그 파일을 통째로 놓친다 — 그리고 하필
# 전역 구역에 실제로 심는 자리(`DefaultFieldTemplateRepository`)가 그 모양이다.
# 이 검사를 처음 쓸 때 실제로 그 넷을 놓쳤고, 그래서 이 단계가 있다.
ALIAS = re.compile(r'\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?(?:get\(\))?\s*=\s*[A-Za-z0-9_.]*fieldDefinitionDao\(\)')

violations = []
for dirpath, _, names in os.walk(root):
    for name in names:
        if not name.endswith('.kt'):
            continue
        path = os.path.join(dirpath, name)
        lines = open(path, encoding='utf-8').read().split('\n')

        holders = [DIRECT] + [re.escape(m.group(1)) for m in
                              (ALIAS.search(l) for l in lines) if m]
        write = re.compile(r'(?:' + '|'.join(holders) + r')\.insert(?:All)?\s*\(')

        for i, line in enumerate(lines):
            if not write.search(line):
                continue
            window = lines[max(0, i - LOOKBACK):i + 1]
            if any(MARK.search(w) for w in window):
                continue
            rel = os.path.relpath(path, root)
            violations.append(f"{rel}:{i + 1}: {line.strip()[:90]}")

for v in violations:
    print(v)
print(f"COUNT={len(violations)}")
PY
)

COUNT=$(echo "$SCAN" | sed -n 's/^COUNT=//p')
echo "$SCAN" | grep -v '^COUNT=' || true

if [ "${COUNT:-1}" -ne 0 ]; then
  echo ""
  echo "❌ 전역 필드키 보증이 적히지 않은 쓰기 $COUNT자리."
  echo "   유니크 색인은 universeId가 NULL이면 중복을 통과시킨다 — 무엇이 막는지 자리에 적을 것."
  exit 1
fi

echo "✅ 위반 0 — field_definitions에 쓰는 자리가 전부 전역키 보증을 밝힌다."
