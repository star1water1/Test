#!/bin/bash
# AI 이미지 태그 설정의 소비처 등재 검사 (B-165)
#
# 무엇을 보는가: **AI 이미지 태그 기조(`imageTagPolicy`)와 어휘(`loadTagVocabulary`)를
# 실제로 싣는 자리가 등재된 목록과 같은가.**
#
# ## 왜 있는가 — 설정 문구가 자기 소비처 하나를 통째로 빠뜨렸다
#
# `ai_image_tag_policy_desc`는 *"이미지 폴더 받아오기가 … 이 지침을 함께 전달합니다.
# **이 기능은 폴더 이름만 읽고 이미지 자체는 보내지 않습니다.**"*라고 적고 있었다. 그런데
# 그 설정을 읽는 곳은 **둘**이었다 — B-121이 **그림 자체를 보내는** 둘째 소비처를 붙이면서
# 문구를 함께 넓히지 않았다. 두 겹으로 틀렸다:
#   ⓐ 어디에 듣는지가 좁게 적혀(R-25가 요구하는 *"어디에"*) 배치 태깅 결과가 마음에 안 드는
#      사용자는 **자기가 쓸 수 있는 유일한 조종간을 못 찾는다.**
#   ⓑ *"이미지 자체는 보내지 않습니다"*가 **거짓**이 됐다. 프라이버시 문장이라 무게가 다르고,
#      배치판 자신은 `image_ai_tag_privacy`로 옳게 고지해 **한 앱이 같은 사실을 두 자리에서
#      반대로 말했다.**
# 어휘 스위치(`field_image_tag_vocab_desc`)도 같은 사고를 함께 겪고 있었다 —
# `loadTagVocabulary`가 한 벌을 만들어 양쪽에 넘기는데 문구는 폴더 쪽만 말했다.
#
# ## 왜 다른 검사가 이것을 못 잡는가 — **실패의 모양이 침묵이다**
#
# 셋째 소비처가 붙어도 **아무것도 깨지지 않는다.** 컴파일은 통과하고(설정을 하나 더 읽을
# 뿐이다), 순수 JVM 시험은 화면 문구를 모르며, `check_text_style.sh`는 *문체*를 볼 뿐
# **그 문장이 사실인지**는 원리적으로 못 본다. 틀린 것은 코드가 아니라 **코드와 문구 사이의
# 어긋남**이고, 그것은 양쪽을 함께 보는 검사만 볼 수 있다.
#
# ## 무엇을 요구하는가 — 사실 판정이 아니라 **등재의 일치**
#
# 이 검사는 한국어 문장이 옳은지 읽지 않는다(읽을 수 없다). 대신 **자리의 집합**을 본다:
# 새 자리가 생기거나 있던 자리가 사라지면 **큰소리로 실패**하고, 사람이 그때 문구를 함께
# 손보게 만든다. `check_view_probe_targets.sh`가 커스텀 뷰에 대해 세운 논리와 같다 —
# *"관행이라고 적어도 지켜지지 않는다. 지켜지게 하려면 적는 것 말고 기계가 있어야 한다."*
#
# **읽는 자리를 역할로 가른다.** 기능이 아니라 *운반*인 셋은 소비처가 아니다:
#   · `AiPromptSettings.kt`     — 정의 자신
#   · `AiSettingsFragment.kt`   — 그 값을 **고치는** 화면(읽기는 현재값 표시다)
#   · `AppSettingsBindings.kt`  — 엑셀 왕복의 운반
# 이 셋 **밖**에서 읽으면 그것은 새 소비처이고, 등재하지 않으면 여기서 실패한다.
#
# 사용법: tools/check_ai_tag_consumers.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

SRC="app/src/main/java/com/novelcharacter/app"
STRINGS="app/src/main/res/values/strings.xml"

# 등재된 소비처 — **함수 이름으로 적는다.** 파일로 적으면 한 파일에 둘이 있을 때 갈리지 않고,
# 이 저장소가 바로 그 모양이다(둘 다 ImageManagerViewModel에 있다).
REGISTERED="suggestFolderTags suggestImageTags"

# 운반 역할 — 소비처가 아니다. 늘리려면 **사유를 여기 적을 것**(위 머리 주석의 역할 구분).
TRANSPORT="AiPromptSettings.kt AiSettingsFragment.kt AppSettingsBindings.kt"

echo "── AI 이미지 태그 소비처 등재 검사 (B-165) ──"

# 어떤 `fun` 안에서 읽었는가 — 마지막으로 지나친 `fun 이름(`을 기억해 두었다가 적중 줄에서 낸다.
# 코틀린은 `private suspend fun x(` 처럼 앞이 길고 줄바꿈도 흔해, 선언 줄만 훑는 이 방식이
# 괄호 균형을 세는 것보다 낡지 않는다.
readers_in() {
  local pattern="$1" file="$2"
  awk -v pat="$pattern" '
    { decl = 0 }
    /(^|[^A-Za-z0-9_])fun[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*\(/ {
      line = $0
      sub(/.*[^A-Za-z0-9_]fun[[:space:]]+/, "", line)
      sub(/^fun[[:space:]]+/, "", line)
      sub(/[[:space:]]*\(.*/, "", line)
      cur = line
      decl = 1
    }
    $0 ~ /^[[:space:]]*(\*|\/\/|\/\*)/ { next }          # 주석은 세지 않는다
    # **선언 줄이 자기 이름에만 걸린 것은 부르는 자리가 아니다** — `fun loadTagVocabulary(`이
    # 호출 정규식에 그대로 걸려, 정의가 자기 자신의 소비처로 잡히던 자리다(자기 시험이 아니라
    # 실제 실행이 잡았다). 이름을 지우고 다시 재서 **그 줄에 남은 다른 적중이 있는지**만 본다 —
    # 통째로 건너뛰면 기본 인자에 적힌 읽기를 놓친다.
    decl {
      probe = $0
      gsub(/fun[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*\(/, "fun (", probe)
      if (probe !~ pat) next
    }
    $0 ~ pat { if (cur != "") print cur }
  ' "$file"
}

# 읽기만 센다 — `imageTagPolicy =` 꼴의 쓰기는 값을 고치는 자리이지 싣는 자리가 아니다.
POLICY_RE="\\.imageTagPolicy([^[:space:]=]|[[:space:]]*$|[[:space:]]+[^=])"
VOCAB_RE="loadTagVocabulary[[:space:]]*\\("

# ── 탐지기 자기 시험 — 이 검사가 **조용히 통과할 경로**를 먼저 막는다 ──
# 정규식이 낡아 아무것도 못 찾으면 "전부 등재됨"과 구별되지 않는데, 그 둘은 정반대 사실이다.
SELF=$(mktemp)
cat > "$SELF" <<'EOF'
private suspend fun realConsumer(): Result {
    val policy = AiPromptSettings(getApplication()).imageTagPolicy
    val vocab = loadTagVocabulary()
}
fun editor() {
    settings.imageTagPolicy = input.text.toString()
}
private fun definition() {
    // val policy = settings.imageTagPolicy 는 주석이라 세지 않는다
}
private suspend fun loadTagVocabulary(): Vocabulary {
    return build()
}
EOF
p_hits=$(readers_in "$POLICY_RE" "$SELF" | sort -u | tr '\n' ' ')
v_hits=$(readers_in "$VOCAB_RE" "$SELF" | sort -u | tr '\n' ' ')
rm -f "$SELF"
if [ "$p_hits" != "realConsumer " ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 기조 읽기: 기대 'realConsumer', 실제 '${p_hits}'" >&2
  echo "      (쓰기와 주석까지 세면 오검출로 다음 사람이 검사를 끈다. 못 잡으면 장식이다)" >&2
  exit 1
fi
if [ "$v_hits" != "realConsumer " ]; then
  echo "  ✗ 탐지기 자기 시험 실패 — 어휘 호출: 기대 'realConsumer', 실제 '${v_hits}'" >&2
  exit 1
fi
echo "  ✓ 탐지기 자기 시험 통과 (읽기를 잡고, 쓰기·주석은 세지 않는다)"

fail=0

# ── 실제 소비처를 훑는다 (손으로 적은 파일 목록을 쓰지 않는다) ──
collect() {
  local re="$1" found=""
  local f base
  for f in $(grep -rlE "$re" "$SRC" --include=*.kt 2>/dev/null | sort); do
    base=$(basename "$f")
    case " $TRANSPORT " in *" $base "*) continue ;; esac
    found="$found $(readers_in "$re" "$f" | sort -u | tr '\n' ' ')"
  done
  printf '%s\n' $found | grep -v '^$' | sort -u | tr '\n' ' '
}

policy_consumers=$(collect "$POLICY_RE")
vocab_consumers=$(collect "$VOCAB_RE")
expected=$(printf '%s\n' $REGISTERED | sort -u | tr '\n' ' ')

for axis in "기조:$policy_consumers" "어휘:$vocab_consumers"; do
  name="${axis%%:*}"
  actual="${axis#*:}"
  if [ "$actual" != "$expected" ]; then
    echo "  ✗ ${name}를 싣는 자리가 등재와 다릅니다"
    echo "      등재: ${expected:-(없음)}"
    echo "      실제: ${actual:-(없음)}"
    echo "      → 새 자리가 생겼으면 **문구를 함께 넓힌 뒤** 이 스크립트의 REGISTERED에 등재할 것:"
    echo "         · ai_image_tag_policy_desc  (기조가 어디에 듣는가 · 그림이 나가는가)"
    echo "         · field_image_tag_vocab_desc (어휘가 어디에 실리는가)"
    echo "        넓히지 않으면 설정 문구가 자기 소비처를 빠뜨린 채로 남고, 사용자는"
    echo "        자기가 쓸 수 있는 조종간을 못 찾는다(B-165가 그 상태였다)."
    fail=1
  fi
done

# ── 등재된 소비처가 문구에 실제로 반영됐는지의 **최소 조건** ──
# 문장이 옳은지는 기계가 못 읽지만, **거짓으로 되돌아가는 한 가지 모양**은 잡을 수 있다:
# 그림을 보내는 소비처가 살아 있는 동안 "이미지 자체는 보내지 않습니다"가 부활하는 것.
if [ ! -f "$STRINGS" ]; then
  echo "  ✗ 문자열 파일이 없습니다: $STRINGS (경로가 바뀌었으면 이 검사를 함께 고칠 것)"
  fail=1
else
  desc=$(grep -F 'name="ai_image_tag_policy_desc"' "$STRINGS" || true)
  if [ -z "$desc" ]; then
    echo "  ✗ $STRINGS 에 ai_image_tag_policy_desc 가 없습니다 (이름이 바뀌었으면 함께 고칠 것)"
    fail=1
  elif printf '%s' "$desc" | grep -q '이미지 자체는 보내지 않습니다'; then
    echo "  ✗ ai_image_tag_policy_desc 가 '이미지 자체는 보내지 않습니다'로 되돌아갔습니다"
    echo "      → 같은 설정이 그림을 보내는 기능(suggestImageTags)에도 실린다. 프라이버시"
    echo "        문장이라 무게가 다르고, image_ai_tag_privacy가 바로 옆에서 반대로 말한다."
    fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo "── AI 이미지 태그 소비처 등재 검사 실패 ──" >&2
  exit 1
fi
echo "  ✓ 기조·어휘 둘 다 등재된 소비처와 일치한다 (${expected})"
echo "── AI 이미지 태그 소비처 등재 검사 통과 ──"
