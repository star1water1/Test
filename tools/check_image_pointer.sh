#!/bin/bash
# 대표 이미지 포인터 정합 검사 (R-32) — 캐릭터의 `imagePaths`를 고치면서 대표 포인터를
# 함께 고치지 않는 자리를 찾는다.
#
# 배경: `Character.representativeImagePath`(B-103)는 `imagePaths` 목록의 **한 원소를
# 가리키는 포인터**다. 목록을 고치면서 포인터를 그대로 두면 **오류가 나지 않는다** —
# 포인터만 조용히 어긋나고, 다음 화면이 엉뚱한 그림을 보여 줄 뿐이다. 빠뜨렸다는 사실
# 자체를 아무도 모르므로 리뷰로는 잡히지 않는다.
#
# 그래서 옳은 길을 하나로 두었다: `character.withImagePaths(새 JSON, 재매핑)`.
# 이 검사는 그것을 우회하는 `<캐릭터>.copy(imagePaths = ...)`를 막는다.
#
# **작품·세계관은 대상이 아니다.** 그쪽에는 포인터가 없고(`imageMode` + 연동 id를 쓴다)
# `copy(imagePaths = ...)`가 정상이다. 그래서 수신자 이름이 아니라 **그 줄이 어느 DAO로
# 가는가**와 **선언 타입**으로 캐릭터를 가린다.
#
# 한계(일부러 좁게 잡는다): 한 줄 안에서 판정한다. 여러 줄에 걸쳐 `characterDao().update(`와
# `copy(imagePaths =`가 갈라져 있으면 앞뒤 3줄까지만 본다 — 오검출보다 미검출을 택했다.
# 새 쓰기 방식이 생기면 이 스크립트도 함께 갱신할 것.
#
# 사용법: tools/check_image_pointer.sh          # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

python3 - <<'PY'
import re, subprocess, sys, os

ROOT = "app/src/main/java"
# 단일 소스 자신은 대상에서 뺀다 — 여기가 유일하게 copy(imagePaths=)를 쓰는 자리다.
ALLOW_FILES = {"CharacterRepresentativeImage.kt"}

files = subprocess.run(["find", ROOT, "-name", "*.kt"],
                       capture_output=True, text=True).stdout.split()

COPY = re.compile(r'\.copy\s*\(\s*imagePaths\s*=')
# 그 줄(또는 바로 앞뒤)이 캐릭터를 다루고 있다는 신호.
CHARACTER_HINT = re.compile(
    r'characterDao\(\)|:\s*Character\b|\bCharacter\.|existingChar|'
    r'OwnerType\.CHARACTER|charImagePaths')
# 작품·세계관임이 분명한 신호 — 있으면 캐릭터가 아니다.
OTHER_HINT = re.compile(r'novelDao\(\)|universeDao\(\)|:\s*(Novel|Universe)\b')

violations = []
for f in files:
    if os.path.basename(f) in ALLOW_FILES:
        continue
    lines = open(f, encoding="utf-8", errors="ignore").read().splitlines()
    for i, line in enumerate(lines):
        if not COPY.search(line):
            continue
        window = "\n".join(lines[max(0, i - 3):i + 4])
        if OTHER_HINT.search(line):
            continue
        if CHARACTER_HINT.search(line) or CHARACTER_HINT.search(window):
            violations.append((f, i + 1, line.strip()))

if violations:
    print("대표 이미지 포인터를 함께 고치지 않는 자리 %d건 (R-32)" % len(violations))
    print()
    for f, ln, text in violations:
        print("  %s:%d" % (f, ln))
        print("      %s" % text)
    print()
    print("고치는 법: character.copy(imagePaths = X) → character.withImagePaths(X)")
    print("           경로가 바뀐 자리는 재매핑도 함께: withImagePaths(X, mapOf(옛경로 to 새경로))")
    sys.exit(1)

print("대표 이미지 포인터 정합 검사 통과 — 캐릭터 imagePaths 쓰기가 전부 withImagePaths를 지난다")
PY
