#!/bin/bash
# 내보내기 워크북의 갈래를 **손으로 적지 않는가** (B-250).
#
# 배경: `SXSSFSheet` 생성자는 `AutoSizeColumnTracker`를 무조건 만들고, 그것이 `java.awt`를
# 문다. **안드로이드에는 `java.awt`가 없다** — 그래서 `ExportWorkbooks.create(streaming = true)`가
# 손으로 박혀 있던 동안 **사용자 내보내기와 자동 백업이 함께 죽었다**(실기기 크래시 2026.08.17).
# 갈래는 이제 `ExportWorkbooks.isStreamingSupported()`가 런타임에 정한다.
#
# **왜 시험이 아니라 검사인가:** 그 호출부 둘은 `ExcelExporter`(Android `Context`)와
# `AutoBackupWorker`(WorkManager)라 **순수 JVM 하네스가 원리적으로 못 닿는다.**
# `ExportStreamingAvailabilityTest`는 *검사가 옳게 답하는가*를 재지만, **그 답을 호출부가
# 쓰는가**는 못 잰다 — 이 스크립트가 그 몫이다. 값이 아니라 **통로를 지나는가**를 본다
# (R-54가 `IN` 절 청크에서 같은 결론에 닿은 그 모양이다).
#
# 사용법: tools/check_export_streaming_probe.sh
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

MAIN="app/src/main/java/com/novelcharacter/app"
PROBE='isStreamingSupported()'

echo "── 내보내기 스트리밍 갈래 검사 ──"

# 위반은 `파일:줄:본문`으로 낸다 — create( 호출인데 통로를 안 지나는 것.
scan() {
  local root="$1"
  grep -rn "ExportWorkbooks\.create(" "$root" --include=*.kt 2>/dev/null |
  while IFS= read -r hit; do
    case "$hit" in
      *"$PROBE"*) ;;                      # 통로를 지난다 — 통과
      *) echo "$hit" ;;
    esac
  done
}

# ── 탐지기 자기 시험 — 조용히 통과할 경로를 먼저 막는다 ──
# grep이 못 찾거나 경로가 틀리면 "위반 없음"과 구별되지 않는다(이 저장소가 겪은 그 부류).
SELF=$(mktemp -d)
mkdir -p "$SELF/ok" "$SELF/bad"
cat > "$SELF/bad/Bad.kt" <<'EOF'
val workbook = ExportWorkbooks.create(streaming = true)
EOF
cat > "$SELF/ok/Ok.kt" <<'EOF'
val workbook = ExportWorkbooks.create(streaming = ExportWorkbooks.isStreamingSupported())
EOF
if [ -z "$(scan "$SELF/bad")" ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 손으로 박은 갈래를 못 잡습니다" >&2
  rm -rf "$SELF"; exit 1
fi
if [ -n "$(scan "$SELF/ok")" ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 통로를 지나는 호출을 위반으로 셉니다" >&2
  rm -rf "$SELF"; exit 1
fi
rm -rf "$SELF"
echo "  ✓ 탐지기 자기 시험 통과 (손으로 박은 것은 잡고, 통로를 지나는 것은 통과)"

# ── 본검사 ──
OUT=$(scan "$MAIN")
TOTAL=$(grep -rn "ExportWorkbooks\.create(" "$MAIN" --include=*.kt 2>/dev/null | grep -c . || true)

# 호출부가 0이면 이 검사는 아무것도 보지 않는 것이다 — 그 상태를 통과로 읽지 않는다.
if [ "$TOTAL" -eq 0 ]; then
  echo "  ✗ 호출부를 하나도 못 찾았습니다 — 경로가 바뀌었거나 검사가 죽었습니다" >&2
  exit 1
fi

if [ -n "$OUT" ]; then
  echo "  ✗ 갈래를 손으로 박은 자리:" >&2
  printf '%s\n' "$OUT" | sed 's/^/      /' >&2
  echo "    → ExportWorkbooks.isStreamingSupported() 를 넘기십시오." >&2
  echo "      안드로이드에는 java.awt가 없어 스트리밍 시트를 못 만듭니다(B-250)." >&2
  exit 1
fi

echo "  ✓ 호출부 ${TOTAL}곳 — 전부 isStreamingSupported()를 지납니다"
echo "내보내기 스트리밍 갈래 검사 통과"
