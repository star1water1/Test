#!/bin/bash
# '앱 설정' 시트 카탈로그의 누락 검사 (B-105에서 신설 — 규약 R-45)
#
# 무엇을 보는가, 둘이다:
#   ① `AppSettingsKeys.SPECS`에 선언된 키가 전부 `AppSettingsBindings`에 바인딩을 갖는가.
#   ② 앱의 **설정 저장소**가 전부 카탈로그에 등재됐는가 — 실리는 키로든, `EXCLUDED_STORES`에
#      사유와 함께 빠지든.
#
# ## 왜 있는가 — 이 판이 고친 결함이 바로 '두 곳'이었다
#
# 종전 '앱 설정' 시트는 내보내기가 `writeTextRow`를 **손으로 나열**하고 가져오기가
# `when (key)`를 **손수 분기**했다. 코드 주석이 스스로 그렇게 적어 두었다:
# *"항목 추가는 가져오기(when 분기)와 짝으로 확장한다"* — 설정 하나에 두 곳을 고쳐야 하니
# 늘지 않았고, 저장소가 스무 곳이 넘는데 실린 것은 11개였다.
#
# B-105는 그 둘을 한 선언으로 합쳤지만 **`Context` 때문에 파일은 둘로 갈렸다**
# (선언은 순수해야 순수 JVM 시험이 본다 — B-132가 난 자리). 갈린 이상 **갈릴 수 있고**,
# 그러면 종전의 '두 곳'이 이름만 바꿔 되살아난다. 이 검사가 그것을 막는다.
#
# ## 왜 다른 검사가 이것을 못 잡는가 — **실패의 모양이 침묵이다**
#
#   - 컴파일: 못 본다. 바인딩이 빠진 키는 문법 오류가 아니라 **그냥 안 실리는 행**이다.
#   - 순수 JVM 시험: 못 본다. 바인딩은 `Context`를 쥐어 그 러너에 올릴 수 없다.
#   - CI: 못 본다. 컴파일되는 코드이기 때문이다.
#   - 사용자: **가장 늦게 본다.** 새 기기에서 그 설정만 기본값인데, 무엇이 안 넘어왔는지
#     알려면 설정 화면을 통째로 대조해야 한다.
#
# `check_jvm_test_targets.sh`·`check_view_probe_targets.sh`와 같은 계열이고,
# 셋 다 *"관행이라고 적어도 지켜지지 않는다"*는 같은 결론에서 나왔다.
#
# 사용법: tools/check_app_settings_catalog.sh   # 위반 시 exit 1
set -u
export LC_ALL=C
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

KEYS_FILE="app/src/main/java/com/novelcharacter/app/excel/AppSettingsKeys.kt"
BINDINGS_FILE="app/src/main/java/com/novelcharacter/app/excel/AppSettingsBindings.kt"
MAIN="app/src/main/java/com/novelcharacter/app"

fail=0
echo "── '앱 설정' 카탈로그 검사 ──"

for f in "$KEYS_FILE" "$BINDINGS_FILE"; do
  [ -f "$f" ] || { echo "  ✗ $f 가 없습니다"; exit 1; }
done

# ── ① 선언 ↔ 바인딩 ──
# 선언은 `val NAME = Spec("key", ...)` 꼴이고, 바인딩은 `Binding(AppSettingsKeys.NAME,` 또는
# `supplementFlag(AppSettingsKeys.NAME,` 꼴이다. **상수 이름으로 견준다** — 키 문자열로 견주면
# 바인딩 쪽이 문자열을 안 적으므로(그것이 이 설계의 요점이다) 검사가 성립하지 않는다.
declared=$(sed -nE 's/^[[:space:]]*val ([A-Z0-9_]+) = Spec\(.*/\1/p' "$KEYS_FILE" | sort -u)
bound=$(grep -oE 'AppSettingsKeys\.[A-Z0-9_]+' "$BINDINGS_FILE" | sed 's/AppSettingsKeys\.//' | sort -u)

missing=$(comm -23 <(echo "$declared") <(echo "$bound"))
if [ -n "$missing" ]; then
  echo "  ✗ 선언만 있고 바인딩이 없는 설정:"
  echo "$missing" | sed 's/^/      /'
  echo "      → $BINDINGS_FILE 에 Binding(AppSettingsKeys.<이름>, read=…, write=…) 한 줄을 더하세요."
  echo "      (바인딩이 없으면 그 설정은 **내보내지지도 들어오지도 않는데 아무 오류가 없습니다**)"
  fail=1
fi

# SPECS 목록에 넣는 것을 빠뜨리면 상수는 선언돼 있어도 시트에 나가지 않는다.
specs_block=$(sed -n '/val SPECS: List<Spec> = listOf(/,/^    )/p' "$KEYS_FILE")
not_listed=""
for name in $declared; do
  echo "$specs_block" | grep -qE "(^|[^A-Z0-9_])$name([^A-Z0-9_]|$)" || not_listed="$not_listed $name"
done
if [ -n "$not_listed" ]; then
  echo "  ✗ 선언은 있으나 SPECS 목록에 없는 설정:$not_listed"
  echo "      → 목록에 없으면 시트에 나가지 않습니다(실패가 아니라 침묵입니다)."
  fail=1
fi

# ── ② 설정 저장소가 전부 등재됐는가 ──
# 저장소 이름은 두 가지 모양으로 선언된다:
#   getSharedPreferences("name" …) / PREFS_NAME = "name" / preferencesDataStore(name = "name")
stores=$(
  {
    grep -rhoE 'getSharedPreferences\("[^"]+"' --include=*.kt "$MAIN" | sed 's/.*("//;s/"//'
    grep -rhoE '(PREFS_NAME|PREF_NAME) = "[^"]+"' --include=*.kt "$MAIN" | sed 's/.*= "//;s/"//'
    grep -rhoE 'preferencesDataStore\(name = "[^"]+"' --include=*.kt "$MAIN" | sed 's/.*name = "//;s/"//'
  } | sort -u
)

unregistered=""
for store in $stores; do
  # 등재의 인정 조건: EXCLUDED_STORES의 키로 적혀 있거나(사유와 함께), 설정 키의 접두로 쓰여
  # 실제로 실리고 있거나.
  if grep -qF "\"$store\" to" "$KEYS_FILE"; then continue; fi
  unregistered="$unregistered $store"
done
if [ -n "$unregistered" ]; then
  echo "  ✗ 카탈로그에 등재되지 않은 설정 저장소:$unregistered"
  echo "      → 싣는다면 $KEYS_FILE 에 Spec + $BINDINGS_FILE 에 Binding을,"
  echo "        싣지 않는다면 EXCLUDED_STORES에 **사유와 함께** 한 줄을 적으세요."
  echo "      (사유를 적게 하는 것이 이 검사의 값입니다 — 이름만 나열하면 다음 사람이"
  echo "       왜 빠졌는지 몰라 다시 조사하거나 지레 넘깁니다)"
  fail=1
fi

# ── ③ 사유가 빈 등재는 등재가 아니다 ──
empty_reason=$(grep -oE '"[a-z_]+" to[[:space:]]+""' "$KEYS_FILE" | sed 's/ to.*//' || true)
if [ -n "$empty_reason" ]; then
  echo "  ✗ 사유가 빈 제외 등재: $empty_reason"
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "  ✓ 선언 $(echo "$declared" | wc -l)개가 전부 바인딩을 갖고, 설정 저장소 $(echo "$stores" | wc -w)곳이 전부 등재돼 있음"
  echo "'앱 설정' 카탈로그 검사 통과"
fi
exit $fail
