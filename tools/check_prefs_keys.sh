#!/bin/bash
# SharedPreferences 키 타입 충돌 검사 — 같은 prefs 파일의 같은 키를 두 곳이 다른 타입으로 쓰면 실패.
#
# 배경: `field_library_ui_state`의 `sort_mode`를 필드 라이브러리 홈은 String으로,
# 값 목록은 Int로 읽고 썼다. SharedPreferences는 값의 타입을 기억하므로 한 화면에서 정렬을
# 바꾼 뒤 다른 화면을 열면 **읽는 순간 ClassCastException으로 죽었다** — 양쪽 다
# `onViewCreated`의 맨몸 호출이라 가드도 없었다(2026-07-30 문서 감사에서 발견·수리).
#
# 이 결함은 리뷰로 잡기 어렵다. 두 파일을 나란히 놓고 봐야 보이는데, 각 파일만 보면
# 양쪽 다 자연스럽다. 그래서 도구가 지킨다 — check_resources·check_text_style과 같은 자리다.
#
# 한계(일부러 좁게 잡는다): 같은 파일 안의 문자열 리터럴과 `val KEY = "..."` 상수만 해석한다.
# 다른 파일의 상수를 import해 쓰면 `<이름>`으로 남아 비교 대상에서 빠진다 — 오검출보다
# 미검출을 택했다. 새 prefs 접근 방식이 생기면 이 스크립트도 함께 갱신할 것.
#
# 사용법: tools/check_prefs_keys.sh          # 충돌 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

python3 - "$@" <<'PY'
import re, subprocess, collections, os, sys

ROOT = "app/src/main/java"
files = subprocess.run(["find", ROOT, "-name", "*.kt"],
                       capture_output=True, text=True).stdout.split()

# prefs 파일명 → { 키 → {(타입, 소스파일)} }
usage = collections.defaultdict(lambda: collections.defaultdict(set))
GETPUT = re.compile(
    r'(get|put)(Int|String|Boolean|Long|Float|StringSet)\s*\(\s*'
    r'([A-Za-z_][A-Za-z0-9_]*|"[^"]*")')

for f in files:
    src = open(f, encoding="utf-8", errors="ignore").read()
    prefnames = re.findall(r'getSharedPreferences\(\s*"([^"]+)"', src)
    if not prefnames:
        continue
    consts = dict(re.findall(r'val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"]+)"', src))
    for m in GETPUT.finditer(src):
        _, typ, key = m.groups()
        k = key.strip('"') if key.startswith('"') else consts.get(key, "<%s>" % key)
        for pn in set(prefnames):
            usage[pn][k].add((typ, os.path.basename(f)))

conflicts = []
for pn, keys in sorted(usage.items()):
    for k, entries in sorted(keys.items()):
        types = {t for t, _ in entries}
        srcs = {s for _, s in entries}
        # 한 파일 안에서 타입이 갈리는 것은 이 검사의 대상이 아니다(그건 그 파일을 읽으면 보인다).
        if len(types) > 1 and len(srcs) > 1:
            conflicts.append((pn, k, sorted(types), sorted(srcs)))

print("── SharedPreferences 키 타입 검사 ──")
if not conflicts:
    print("  ✓ 타입 충돌 없음")
    sys.exit(0)

print("  ✗ 같은 키를 서로 다른 타입으로 읽고 씁니다 — 화면을 오가면 ClassCastException입니다:")
for pn, k, types, srcs in conflicts:
    print("      [%s] 키 '%s': %s ← %s" % (pn, k, "/".join(types), ", ".join(srcs)))
print()
print("  고치는 법: 두 용도가 다른 개념이면 **키를 가른다**(합치지 말 것).")
print("  이미 잘못된 타입이 저장된 기기가 있으므로, 읽는 쪽에 ClassCastException 복구도 함께 둘 것.")
sys.exit(1)
PY
