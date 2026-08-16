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
app/src/main/java/com/novelcharacter/app/ai/CharacterNameAiSuggester.kt
app/src/main/java/com/novelcharacter/app/ai/FieldLibraryAiOrganizer.kt
app/src/main/java/com/novelcharacter/app/util/DuelAiContext.kt
app/src/main/java/com/novelcharacter/app/util/BodyTargetRatio.kt
app/src/main/java/com/novelcharacter/app/excel/ExcelExporter.kt
app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt
app/src/main/java/com/novelcharacter/app/excel/PresetTemplateMatcher.kt
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
# **2026.08.10에 세 번째가 나왔다 — `PresetTemplateMatcher.kt`**(위 목록에 방금 넣은 것).
# 가져오기 경고문 다섯을 화면에 올리는데 미등재라, R1·R2·R3 **전부가 그 파일을 못 보고 있었다**
# (`매칭` 판정의 착수 대조가 발견 — 등재된 26곳을 세는 동안 이 파일의 2곳이 어느 셈에도
# 들어가지 않았다. 실제 사용자 노출은 28곳이다). **등재해도 새 위반은 0건이라 등재 자체는
# 무료였고, 그런 채로 엿새를 빠져 있었다**(파일 생성 2026-08-04 → 발견 08-10. 실측이다).
# **요지는 기간이 아니라 그것이다 — 무료인데도 아무도 안 했다.** 사람 기억에 맡기는 유일한
# 항목이라 그렇고, 세 번 다 같은 병이다.
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
# R4 DUEL-TERM 대결 영역 한정 금지 — `매칭`은 표2(유지)지만 **대결에는 쓰지 않는다**.
#              R3와 방향이 다르다: R3는 저장소 전역에서 그 말을 없애고, R4는 **한 영역에만**
#              걸어 나머지에서는 그대로 쓰게 둔다. 뜻이 둘로 갈리는 것을 막는 것이 목적이다.
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
# R4 DUEL-TERM 대결 영역에서 `매칭` 금지 (규약 R-48) — 사용자 확정이 만든 규칙이다
# (확정 문서 1장 23번 · 4장 순서 강제 23번↔2번).
# `매칭`은 **표2(유지)**로 확정됐다. 금지어가 아니라 그 반대로, **엑셀 왕복에서 파일의 행과
# 앱의 레코드를 잇는 것**을 가리키는 자리를 받았다. 확정이 함께 단 조건이 이 규칙이다 —
# **대결은 '대결'·'판'·'짝'으로 말한다.** 한 단어가 두 가지를 가리키면 그 확정이 무너지므로,
# 유지 판정과 이 금지는 한 벌이다(둘 중 하나만 있으면 뜻이 갈린다).
#
# **종전에 이 약속을 지킨 것은 strings.xml 대결 절의 주석 한 줄뿐이었다.** 주석은 어겨도
# 아무 일도 일어나지 않고, 어긴 것을 아무도 세지 못한다 — R-40이 배운 그것이다.
# 그래서 확정을 기록이 아니라 **기계**에 건다.
#
# **이 규칙이 못 보는 자리 — 적어 두지 않으면 다음 사람이 검사가 있다고 믿는다(R-40).**
# ① ~~**레이아웃 XML을 안 본다.**~~ **R5가 막았다(B-193, 2026.08.16)** — 아래를 볼 것.
# ② **`duel_` 접두를 안 쓰는 대결 문자열은 못 본다.** 지금은 전량 그 접두를 쓴다(288개).
DUEL_TERM_RE='매칭'

# R5 LAYOUT-LIT 레이아웃 XML의 하드코딩 한국어 = **등재 누락** (B-193)
#
# R1~R4는 `strings.xml` 값과 `KT_USER_FACING` 코틀린 리터럴만 읽는다. 그래서 새 화면이
# `android:text`에 한국어를 박으면 **네 규칙이 한꺼번에 조용해진다.** B-181이 마지막
# 하드코딩 하나를 옮겨 저장소가 0건이 됐지만, 그 온전함은 *검사가 지키는 것*이 아니라
# *마침 위반이 없는 것*이었다(R-40이 이름 붙인 부류).
#
# **문구를 검사하지 않고 `strings.xml`을 거치지 않은 것을 잡는다.** 그래야 하드코딩을
# 허용하는 부작용 없이 구멍이 막히고, **기준선 0에서 시작할 수 있다** — 위반이 생긴 뒤에는
# 0이 아니게 되므로 지금이 그 자리다(2026.08.16 실측 0건).
#
# ⚠️ **`[가-힣]`으로 적지 않는다 — C 로케일에서 그것은 한글 범위가 아니다.**
# 이 파일 머리는 *"C에서는 바이트 범위가 되어 한글을 담았는가라는 용도대로 동작한다"*고
# 적어 두었는데 **그 문장이 틀렸다**(B-193 착수 실측). `[가-힣]`은 바이트로 쪼개져
# `[\xEA\xB0\x80-\xED\x9E\xA3]`이 되고, 그 안의 `\x80-\xED` 범위가 **모든 비ASCII 바이트**를
# 문다 — 실제로 `◀ ▶ ↔ › ✨` 다섯 기호가 전부 걸린다(그것이 이 규칙의 거짓 양성 여섯이었다).
# R1~R4에서는 해가 없었다(후보만 넓히고 실제 판정은 한글 낱말로 하므로) 그러나 **여기서는
# 후보가 곧 판정이라 그대로 거짓 양성이 된다.** 그래서 UTF-8 바이트로 정확히 적는다 —
# 한글 음절 U+AC00~U+D7A3.
HANGUL_SYLLABLE=$'(\xEA[\xB0-\xBF]|[\xEB\xEC][\x80-\xBF]|\xED[\x80-\x9F])[\x80-\xBF]'
# 사용자에게 그대로 읽히는 속성만 본다. `tools:text`(미리보기 전용)는 대상이 아니다.
LAYOUT_TEXT_ATTRS='android:(text|hint|contentDescription)'

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
    case "$name" in
      duel_*) echo "$value" | grep -qE "$DUEL_TERM_RE" && echo "DUEL-TERM|strings.xml|$name" ;;
    esac
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
  # R4 — 대결 코드의 한국어 리터럴. **KT_USER_FACING과 별개로 경로로 연다.**
  # 위 목록에 기대지 않는 이유는 이 파일이 이미 세 번 겪은 그것이다 — 등재 누락 = 검사 사각.
  # 여기서 찾는 것이 우리말 한 낱말('매칭')뿐이라 그렇게 열 수 있다: 키·태그·로그처럼
  # 사용자에게 안 나가는 리터럴과 겹칠 일이 없으므로, 대결 파일을 통째로 훑어도 거짓 경보가 없다
  # (거짓 경보를 내는 검사는 곧 꺼진다 — R-47이 창을 좁히며 배운 것과 같은 기준을
  # 반대 방향으로 적용한 자리다. 그쪽은 흔한 관용구라 좁혔고, 이쪽은 드문 낱말이라 넓혔다).
  find app/src/main/java -name '*.kt' -path '*[Dd]uel*' | sort |
  while read -r f; do
    grep -vE '^[[:space:]]*(//|\*|/\*)' "$f" | grep -oE '"[^"]*[가-힣][^"]*"' | sort -u |
    while read -r lit; do
      echo "$lit" | grep -qE "$DUEL_TERM_RE" && echo "DUEL-TERM|$f|$lit"
    done
  done

  # ── R5 LAYOUT-LIT — 레이아웃의 한국어가 strings.xml을 안 거쳤다 ──
  # `@string/...`로 시작하는 값은 제외한다(`[^@"]*`) — 그쪽은 R1~R4가 이미 본다.
  find app/src/main/res -name '*.xml' -path '*layout*' | sort |
  while read -r f; do
    grep -oE "${LAYOUT_TEXT_ATTRS}=\"[^@\"]*${HANGUL_SYLLABLE}[^\"]*\"" "$f" | sort -u |
    while read -r hit; do
      echo "LAYOUT-LIT|$f|$hit"
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
