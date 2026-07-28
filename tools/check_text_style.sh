#!/bin/bash
# 텍스트 스타일 검사 — docs/text_style_guide_2026-07.md 의 기계 검출 가능 규칙을 지킨다.
#
# 배경: 화면 문구의 말투·용어 문제(해요체 혼입, 붙여쓰기, 개발 어휘 유출)는 리뷰에서
# 반복 지적해도 재발한다. check_resources.sh 가 리소스 중복을 잡듯, 기계로 잡을 수 있는
# 규칙은 도구가 지킨다 — 사람은 목적문·정보 구조처럼 기계가 못 보는 것만 본다.
#
# 기준선(baseline) 운영: 기존 위반은 tools/text_style_baseline.txt 로 동결돼 있다.
#   - 기준선에 없는 **새 위반** → 즉시 실패 (재발 방지는 오늘부터)
#   - 기준선에 있는 기존 위반 → 잔여 수만 보고 (파일럿·전개 단계에서 줄여 간다)
#   - 위반을 고친 뒤에는 --rebaseline 으로 기준선을 갱신한다 (늘리는 갱신은 리뷰에서 걸러라)
#
# 사용법: tools/check_text_style.sh                # 검사 (새 위반 시 exit 1)
#         tools/check_text_style.sh --rebaseline   # 현재 위반으로 기준선 재작성
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"
BASELINE="tools/text_style_baseline.txt"
STRINGS="app/src/main/res/values/strings.xml"

# 사용자 노출 한국어가 코드에 사는 파일들 — 순수 계층이라 strings.xml 로 옮길 수 없는 곳.
# 새 파일이 사용자 노출 문구를 갖게 되면 여기에 등재할 것 (등재 누락 = 검사 사각).
KT_USER_FACING="
app/src/main/java/com/novelcharacter/app/ai/CharacterFieldAiSuggester.kt
app/src/main/java/com/novelcharacter/app/ai/NarrativeFieldAiWriter.kt
app/src/main/java/com/novelcharacter/app/ai/FieldLibraryAiOrganizer.kt
app/src/main/java/com/novelcharacter/app/excel/ExcelExporter.kt
app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt
app/src/main/java/com/novelcharacter/app/data/repository/TrashRepository.kt
app/src/main/java/com/novelcharacter/app/data/repository/EventFieldValueMerge.kt
app/src/main/java/com/novelcharacter/app/data/model/EntitySnapshots.kt
app/src/main/java/com/novelcharacter/app/data/maintenance/SystemMaintenanceService.kt
"

# ── 규칙 (가이드 4·6장의 기계 검출 가능분) ──
# R1 TONE-YO   해요체 종결 금지 (합니다체 통일). '~세요'(하세요/보세요/주세요)는 허용.
# R2 SPACING   보조용언 붙여쓰기 금지 — '해 주세요/해 보세요'로 띄운다.
# R3 TERM      화면 금지 용어 — 우리말 대체가 있는 개발·문서 어휘 (가이드 5장 표1).
#              JSON·API 키·토큰·프로바이더 등 실존 외부 개념은 금지가 아니다(유지+도움말).
YO_RE='(어요|에요|예요|아요|해요|께요|네요|데요|래요|볼게요|할게요)'
SPACING_RE='(해주세요|해주십시오|해보세요|해봐요|해먹|해두세요|해놓으세요)'
TERM_RE='(산출물|카탈로그|매핑|그룹핑|파싱|렌더링|시맨틱|직렬화|정규화)'

violations() {
  # strings.xml — <string name="키">값</string> 의 값만 검사 (주석·이름 제외)
  sed -nE 's/.*<string[^>]*name="([^"]+)"[^>]*>(.*)<\/string>.*/\1\t\2/p' "$STRINGS" |
  while IFS=$'\t' read -r name value; do
    echo "$value" | grep -qE "${YO_RE}([^가-힣]|$)" && echo "TONE-YO|strings.xml|$name"
    echo "$value" | grep -qE "$SPACING_RE" && echo "SPACING|strings.xml|$name"
    echo "$value" | grep -qE "$TERM_RE" && echo "TERM|strings.xml|$name"
  done
  # 코드 속 사용자 노출 문구 — 한국어를 담은 문자열 리터럴만 검사 (주석·로그 태그 제외)
  for f in $KT_USER_FACING; do
    [ -f "$f" ] || continue
    grep -oE '"[^"]*[가-힣][^"]*"' "$f" | sort -u |
    while read -r lit; do
      echo "$lit" | grep -qE "${YO_RE}([^가-힣]|$)" && echo "TONE-YO|$f|$lit"
      echo "$lit" | grep -qE "$SPACING_RE" && echo "SPACING|$f|$lit"
      echo "$lit" | grep -qE "$TERM_RE" && echo "TERM|$f|$lit"
    done
  done
}

CURRENT=$(violations | sort -u)

if [ "${1:-}" = "--rebaseline" ]; then
  echo "$CURRENT" > "$BASELINE"
  echo "기준선 갱신: $(echo "$CURRENT" | grep -c .)건"
  exit 0
fi

if [ ! -f "$BASELINE" ]; then
  echo "기준선이 없습니다 — tools/check_text_style.sh --rebaseline 으로 먼저 만드세요" >&2
  exit 1
fi

NEW=$(comm -13 <(sort -u "$BASELINE") <(echo "$CURRENT"))
REMAIN=$(comm -12 <(sort -u "$BASELINE") <(echo "$CURRENT") | grep -c . || true)
FIXED=$(comm -23 <(sort -u "$BASELINE") <(echo "$CURRENT") | grep -c . || true)

echo "── 텍스트 스타일 검사 (가이드: docs/text_style_guide_2026-07.md) ──"
echo "  기준선 잔여 ${REMAIN}건 · 기준선 이후 해소 ${FIXED}건"
if [ "$FIXED" -gt 0 ]; then
  echo "  ※ 해소분이 있습니다 — --rebaseline 으로 기준선을 줄여 두세요 (재유입 방지)"
fi
if [ -n "$NEW" ]; then
  echo "  ✗ 새 위반 — 가이드의 톤(합니다체)·띄어쓰기·용어 표를 확인하세요:"
  echo "$NEW" | sed 's/^/      /'
  exit 1
fi
echo "  ✓ 새 위반 없음"
