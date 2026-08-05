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
# 로케일 고정 — 검사 결과가 호출자의 LANG에 따라 달라지지 않게 한다(기준선은 C에서 만들어졌다).
#
# **C.UTF-8로 올리지 말 것.** 아래 규칙의 `[가-힣]`은 UTF-8 로케일에서 콜레이션 범위로 해석되어
# grep이 "Invalid collation character"로 거부한다(전개 3단계에서 실제로 겪었다). C에서는 바이트
# 범위가 되어 "한글을 담았는가"라는 용도대로 동작한다.
#
# 그 대신 **새 규칙은 대괄호로 적지 않는다.** C 로케일에서 한글 대괄호는 글자가 아니라 바이트로
# 쪼개져 엉뚱한 음절에 걸린다 — `[아어여와워해]보세요`가 '살펴보세요'를 잡았다(자기 재공격에서 발견).
# 음절을 열거해야 하면 교체(|)를 쓸 것.
export LC_ALL=C
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
app/src/main/java/com/novelcharacter/app/util/DuelAiContext.kt
app/src/main/java/com/novelcharacter/app/excel/ExcelExporter.kt
app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt
app/src/main/java/com/novelcharacter/app/data/repository/TrashRepository.kt
app/src/main/java/com/novelcharacter/app/data/repository/EventFieldValueMerge.kt
app/src/main/java/com/novelcharacter/app/data/model/EntitySnapshots.kt
app/src/main/java/com/novelcharacter/app/data/maintenance/SystemMaintenanceService.kt
app/src/main/java/com/novelcharacter/app/ui/stats/StatsDataProvider.kt
app/src/main/java/com/novelcharacter/app/ui/field/FieldEditDialog.kt
"

# 등재 누락을 사람 기억에 맡기지 않는 법 — 전개 5단계에서 실제로 한 건 더 나왔다
# (`FieldEditDialog`의 측정값 토글 라벨 13개가 코드 하드코딩이라 '정규화 비율'이 표1 위반인
# 채로 검사·기준선 양쪽에 안 잡혔다. 파일럿 화면이었는데도 그랬다).
# 아래를 돌리면 미등재 파일의 위반 후보가 나온다 — 새 문구를 넣은 세션은 한 번 돌려 볼 것:
#   find app/src/main -name '*.kt' | while read f; do
#     grep -qxF "$f" <<<"$KT_USER_FACING" && continue
#     grep -vE '^[[:space:]]*(//|\*|/\*)' "$f" | grep -oE '"[^"]*[가-힣][^"]*"' | ...규칙 적용...
#   done
# 근본 해결은 코드에서 한국어 리터럴을 없애는 것이다(strings.xml로 옮기면 자동으로 검사된다) —
# 위 위반도 그렇게 고쳤다: 읽기 화면이 이미 쓰던 `body_normalized_ratio_label`을 함께 쓰게 했다.

# ── 규칙 (가이드 4·6장의 기계 검출 가능분) ──
# R1 TONE-YO   해요체 종결 금지 (합니다체 통일). '~세요'(하세요/보세요/주세요)는 허용.
#              확인 질문 '~까요?'도 금지 — '~하시겠습니까?'로 통일 (사용자 판정 2026.07.28).
# R2 SPACING   보조용언 붙여쓰기 금지 — '해 주세요/해 보세요'로 띄운다.
# R3 TERM      화면 금지 용어 — 우리말 대체가 있는 개발·문서 어휘 (가이드 5장 표1).
#              JSON·API 키·토큰·프로바이더 등 실존 외부 개념은 금지가 아니다(유지+도움말).
# '게요'는 -(으)ㄹ게요 종결 전용이라 어간을 열거하지 않는다 — 종전의 '볼게요|할게요'는
# 열거된 둘만 잡아 실제로 0건을 검출했고, 그사이 '알려드릴게요'가 통과했다(전개 2단계에서 발견).
YO_RE='(어요|에요|예요|아요|해요|께요|네요|데요|래요|게요|까요)'
# R2도 같은 함정을 갖고 있었다(전개 3단계에서 확인) — 종전의 열거형은 '해'로 시작하는 일곱 개만
# 잡아 '가져와주세요'·'비워두세요'·'적어주세요' 세 건을 통과시켰다. 보조용언 붙여쓰기는 본용언의
# 연결어미(아/어/여/와/워/려/해) + 보조용언(주다·보다·두다·놓다·드리다)이라는 **구조**라서,
# 어간을 열거하는 대신 그 구조를 적는다. 대괄호가 아니라 교체(|)로 적는 이유는 위 로케일 주석에 있다.
SPACING_RE='(아|어|여|와|워|려|해)(주세요|주십시오|주시고|보세요|봐요|두세요|놓으세요|드리세요|드릴게|드립니다)|해먹'
# 한 단어로 굳은 합성동사는 **붙여 쓰는 것이 옳다** — 구조 규칙이 잡아도 위반이 아니다.
# 이 목록을 지우면 '도와주세요'·'알아보세요' 같은 정상 문구가 검사에서 실패한다
# (자기 재공격에서 실제로 오검출됐다). 늘릴 때는 표준국어대사전 표제어인지 확인하고 적을 것.
LEXICALIZED_RE='(도와주|알아보|물어보|돌아보|살펴보|지켜보|여쭤보|들여다보|내려다보|찾아보)'
# '인사이트'는 표3이 화면 금지로 판정한 뒤에도 통계 화면에 7건이 남아 있었다(전개 4단계에서 발견) —
# 판정만으로는 소급되지 않는다는 것이 이 도구가 있는 이유다. 소거한 자리에서 바로 잠근다.
# 문서(통계 철학) 어휘로는 유지이며, R3는 strings.xml 값과 KT_USER_FACING만 보므로 문서에 닿지 않는다.
TERM_RE='(산출물|카탈로그|매핑|그룹핑|파싱|렌더링|시맨틱|직렬화|정규화|인사이트)'

# 보조용언 붙여쓰기 판정 — 한 단어로 굳은 합성동사를 먼저 지운 뒤 구조 규칙을 적용한다.
# 순서가 중요하다: 지우지 않고 검사하면 '알아보세요'가, 구조 규칙 없이 지우면 '메모해주세요'가 샌다.
#
# 첫 줄은 속도용 사전 걸러내기다. SPACING_RE는 반드시 아래 꼬리 중 하나를 포함하므로,
# 꼬리가 없으면 지우기 전에도 후에도 걸릴 수 없다(지우기는 없던 꼬리를 만들지 못한다) —
# 그래서 이 단축은 결과를 바꾸지 않는다. 문자열 대부분이 여기서 끝나 sed 호출이 사라진다.
SPACING_TAIL_RE='(주세요|주십시오|주시고|보세요|봐요|두세요|놓으세요|드리세요|드릴게|드립니다|해먹)'
has_spacing() {
  printf '%s' "$1" | grep -qE "$SPACING_TAIL_RE" || return 1
  printf '%s' "$1" | sed -E "s/$LEXICALIZED_RE//g" | grep -qE "$SPACING_RE"
}

violations() {
  # strings.xml — <string name="키">값</string> 의 값만 검사 (주석·이름 제외)
  sed -nE 's/.*<string[^>]*name="([^"]+)"[^>]*>(.*)<\/string>.*/\1\t\2/p' "$STRINGS" |
  while IFS=$'\t' read -r name value; do
    echo "$value" | grep -qE "${YO_RE}([^가-힣]|$)" && echo "TONE-YO|strings.xml|$name"
    has_spacing "$value" && echo "SPACING|strings.xml|$name"
    echo "$value" | grep -qE "$TERM_RE" && echo "TERM|strings.xml|$name"
  done
  # 코드 속 사용자 노출 문구 — 한국어를 담은 문자열 리터럴만 검사 (주석·로그 태그 제외)
  # 주석 제외는 이 줄이 한다. 종전에는 '주석 제외'라고 적어 두고도 실제로는 걸러내지 않아,
  # 따옴표를 품은 주석("파싱은 되지만 소비처가 못 읽는")이 기준선에 위반으로 앉아 있었다 —
  # 화면에 나가지 않는 문구라 고칠 대상이 아닌데도 잔여 건수를 부풀렸다(전개 3단계에서 확인).
  # 한 줄 주석·KDoc 본문 줄만 떨어낸다(줄 끝 주석은 그 줄의 실제 리터럴을 살려야 하므로 건드리지 않는다).
  for f in $KT_USER_FACING; do
    [ -f "$f" ] || continue
    grep -vE '^[[:space:]]*(//|\*|/\*)' "$f" | grep -oE '"[^"]*[가-힣][^"]*"' | sort -u |
    while read -r lit; do
      echo "$lit" | grep -qE "${YO_RE}([^가-힣]|$)" && echo "TONE-YO|$f|$lit"
      has_spacing "$lit" && echo "SPACING|$f|$lit"
      echo "$lit" | grep -qE "$TERM_RE" && echo "TERM|$f|$lit"
    done
  done
}

CURRENT=$(violations | sort -u)

if [ "${1:-}" = "--rebaseline" ]; then
  # 위반이 없으면 **정말로 빈 파일**을 쓴다(`echo ""`는 빈 줄 하나를 남긴다).
  # 전개 5단계로 기준선이 0건에 도달했고, 그때부터 이 파일의 크기 자체가 증명이다 —
  # 0바이트 = 위반 없음. 빈 줄 하나가 들어 있으면 그 증명을 눈으로 확인할 수 없다.
  if [ -n "$CURRENT" ]; then echo "$CURRENT" > "$BASELINE"; else : > "$BASELINE"; fi
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
