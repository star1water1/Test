#!/bin/bash
# 이미지 축 참가자 코드의 개명 추종 검사 (R-42) — 파일을 개명해 놓고 대결의 참가자 코드를
# 함께 옮기지 않는 자리를 찾는다.
#
# 배경: 이미지 축의 참가자 코드는 **이미지 경로**다(`DuelMatch.aCode`의 규약). 경로를 바꾸면서
# 판의 코드를 그대로 두면 **오류가 나지 않는다** — 쌓아 둔 판이 조용히 고아가 되거나, 더 나쁘게는
# 그 경로를 물려받은 **남의 이미지에 붙는다**(R-1이 말하는 오배정). 빠뜨렸다는 사실 자체를
# 아무도 모르므로 리뷰로는 잡히지 않는다. R-32가 대표 이미지 포인터에서 배운 것과 같은 모양이고,
# 다른 것은 **가리키는 쪽이 다른 표에 산다**는 것뿐이라 `withImagePaths`로는 닿지 않는다.
#
# 그래서 앵커를 개명의 유일한 관문에 건다: `FolderRoundtripPrefs.recordRenames`.
# 그 함수의 KDoc이 이미 *"경로를 바꾸는 모든 지점이 불러야 한다"*고 선언해 두었다.
# 이 검사는 **그것을 부르는 자리가 `followImageRenames`도 부르는가**를 본다.
#
# 한계(일부러 좁게 잡는다): 같은 파일 안에서 판정한다. 개명을 기록하는 파일과 대결을 옮기는
# 파일이 갈리면 놓친다 — 오검출보다 미검출을 택했다. 새 개명 경로가 생기면 이 스크립트가
# 아니라 **그 파일이** 둘을 나란히 부르게 할 것.
#
# 사용법: tools/check_duel_image_codes.sh          # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

python3 - <<'PY'
import re, subprocess, sys, os

ROOT = "app/src/main/java"
# 선언 자신과 단일 소스는 대상에서 뺀다.
ALLOW_FILES = {
    "FolderRoundtripPrefs.kt",   # recordRenames를 **선언**하는 자리
    "DuelRepository.kt",         # followImageRenames를 선언하는 자리(KDoc이 짝을 설명한다)
}

files = subprocess.run(["find", ROOT, "-name", "*.kt"],
                       capture_output=True, text=True).stdout.split()

RECORD = re.compile(r'\.recordRenames\s*\(')
FOLLOW = re.compile(r'\.followImageRenames\s*\(')

violations = []
for f in files:
    if os.path.basename(f) in ALLOW_FILES:
        continue
    text = open(f, encoding="utf-8", errors="ignore").read()
    if not RECORD.search(text):
        continue
    if FOLLOW.search(text):
        continue
    # 줄 번호를 함께 낸다 — 어디를 고쳐야 하는지 바로 보이게.
    lines = text.splitlines()
    hits = [i + 1 for i, l in enumerate(lines) if RECORD.search(l)]
    violations.append((f, hits))

if violations:
    print("파일을 개명하고 대결 참가자 코드를 안 옮기는 자리 %d건 (R-42)" % len(violations))
    print()
    for f, hits in violations:
        print("  %s:%s" % (f, ",".join(str(h) for h in hits)))
    print()
    print("고치는 법: recordRenames(...) 곁에서 duelRepository.followImageRenames(같은 표)도 부를 것.")
    print("           이미지 축이 없으면 그 함수가 질의도 열지 않고 곧바로 돌아온다(공짜다).")
    sys.exit(1)

print("이미지 축 참가자 코드 개명 추종 검사 통과 — recordRenames를 부르는 자리가 전부 followImageRenames를 함께 부른다")
PY
