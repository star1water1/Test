#!/bin/bash
# 필드 config의 세계관 경계 강등 검사 (B-113) — 경계를 넘는 복사는 두 참조를 함께 걷어낸다.
#
# 배경: config에는 **세계관 안에서만 뜻이 있는 참조가 둘** 산다.
#   - `gradeSystem` (등급 체계 code)  — 넘어가면 남의 세계관 체계를 가리키는 유령이 된다
#   - `duelGrade.axisCode` (대결 축 code) — **넘어가면 남의 세계관 축을 정확히 찾는다**
# 아래쪽이 더 나쁘다. 축 code는 전역 유니크라 "못 찾는" 것이 아니라 **오배정**이고,
# 오배정은 생략보다 나쁘다(R-1). 걷어내는 자리는 여럿이고(프리셋 직렬화·복원, 필드 가져오기,
# 프리셋 병합, 월드패키지 — **수는 적지 않는다. 이 검사가 세는 것이 원문이다**), 각 자리에서
# 두 줄씩 쓰면 **다음 자리가 생길 때 한 줄만 쓰는 것이 자연스러워진다** — 그때 빠지는 쪽은
# 늘 새로 들어온 키다(R-29가 "종류를 손으로 더하는 형태"로 겪은 그 결함과 같다).
#
# ── 무엇을 보는가 ──
# ① 경계를 아는 파일(GradeSystemRef.demote / remapCode 를 부르는 파일)은 대결 몫도 걷어내야
#    한다 — FieldConfigTransfer.demoteAcrossUniverse 를 쓰거나 DuelGradeRef.remove 를 함께 부른다.
# ② 예외는 **경계를 넘지 않는 자리**뿐이고, 목록으로 못 박는다(사유는 목록 옆에).
#    그쪽은 대결 축이 그대로 살아 있으므로 컷도 그대로 성립한다 — 걷어내면 되레 기능이
#    조용히 꺼지고, 복원의 경우에는 **되살릴 수 없는 유실**이 된다.
# ③ 단일 소스 파일이 실재하는가.
#
# 새 파일이 이 목록 밖에서 demote/remapCode 를 부르면 **실패한다**(fail-closed). 그것이 목적이다:
# 작성자가 "경계를 넘는가"를 한 번 판단하게 만든다.
#
# 사용법: tools/check_config_boundary.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app"
SINGLE_SOURCE="$SRC/data/model/FieldConfigTransfer.kt"

# 경계를 넘지 않는 자리 — 대결 컷은 **살아남아야 한다.**
#   GradeSystemRepository : 체계 삭제. 같은 세계관 안이고 축도 그대로다.
#   ExcelImportService    : 덮어쓰기가 낡은 체계를 지우는 자리. 위와 같다.
#   TrashRepository       : 휴지통 복원. **경계를 넘는 복사가 아니라 같은 세계관이 돌아오는
#                           것이다.** 축 code는 비어 있으면 그대로 되살아나므로 컷이 다시
#                           들어맞고, 그 사이 남이 그 code를 가져간 드문 경우는 읽는 쪽의
#                           소속 검사(설계 4-2 ⓑ)가 막는다. 여기서 걷어내면 **복원이 사용자
#                           설정을 지우는 동작**이 되고, 축을 나중에 복원해도 되돌아오지 않는다.
NOT_A_BOUNDARY="
$SRC/data/repository/GradeSystemRepository.kt
$SRC/excel/ExcelImportService.kt
$SRC/data/repository/TrashRepository.kt
"

echo "── 필드 config 경계 강등 검사 (B-113) ──"

fail=0

[ -f "$SINGLE_SOURCE" ] || { echo "  ✗ 단일 소스가 없습니다: $SINGLE_SOURCE"; fail=1; }

# 경계를 아는 파일 = 체계 참조를 강등하거나 재발급하는 파일.
aware=$(grep -rlE 'GradeSystemRef\.(demote|remapCode)\(' "$SRC" --include=*.kt 2>/dev/null || true)

for f in $aware; do
  # 단일 소스 자신은 대상 밖 — 그 파일이 곧 규칙이다.
  [ "$f" = "$SINGLE_SOURCE" ] && continue

  allowed=0
  for exempt in $NOT_A_BOUNDARY; do
    [ "$f" = "$exempt" ] && allowed=1
  done
  [ "$allowed" -eq 1 ] && continue

  if ! grep -qE 'FieldConfigTransfer\.demoteAcrossUniverse\(|DuelGradeRef\.remove\(' "$f"; then
    echo "  ✗ 경계 강등이 반쪽입니다: $f"
    echo "      등급 체계 참조는 걷어내는데 대결 등급 산정(duelGrade)은 그대로 남습니다."
    echo "      → 세계관을 넘는 복사면 FieldConfigTransfer.demoteAcrossUniverse 를 쓸 것."
    echo "      → 경계를 넘지 않는 자리면(체계 삭제·복원) 이 스크립트의 NOT_A_BOUNDARY 목록에 사유와 함께 올릴 것."
    fail=1
  fi
done

# 예외 목록이 낡지 않았는가 — 파일이 사라졌는데 목록만 남으면 다음 위반이 조용히 통과한다.
for exempt in $NOT_A_BOUNDARY; do
  [ -f "$exempt" ] || { echo "  ✗ 예외 목록의 파일이 없습니다: $exempt (이름이 바뀌었으면 목록을 고칠 것)"; fail=1; }
done

if [ "$fail" -eq 0 ]; then
  echo "  ✓ 경계를 넘는 복사는 등급 체계 참조와 대결 축 참조를 함께 걷어낸다"
  echo ""
  echo "필드 config 경계 강등 검사 통과"
  exit 0
fi
echo ""
echo "필드 config 경계 강등 검사 실패" >&2
exit 1
