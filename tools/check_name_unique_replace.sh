#!/bin/bash
# 사용자가 적는 이름이 유니크 키인 표에 REPLACE로 넣는가 (B-191, 규약 R-60)
#
# 배경: 프리셋 두 표(`character_list_presets`·`search_presets`)는 `name`이 **유니크 색인**이고
# DAO의 `@Insert`가 `OnConflictStrategy.REPLACE`였다. 그래서 이미 있는 이름을 적어 저장하면
# **예외도 경고도 없이 옛 행이 지워지고 새 행이 들어갔다** — 공들여 짜 둔 필터·정렬 조합이
# 그 자리에서 없어지고, REPLACE는 지우고-다시-넣는 것이라 **id까지 바뀌어** 그 프리셋을
# 가리키던 값이 함께 끊겼다. 프리셋은 휴지통을 타지 않으므로 되돌릴 길이 없다.
#
# ── REPLACE 자체를 막는 검사가 아니다 ──
# 이 저장소의 다른 REPLACE는 **정당하다.** `CharacterFieldValue`·`NovelFieldValue`·
# `EventFieldValue`·`RecentActivity`·`DuelCounterVerdict`의 유니크 키는 (소유자, 대상) 같은
# **동일성 튜플**이라, 같은 키로 다시 넣는 것이 곧 *그 값의 갱신*이다 — 덮이는 것이 정답이다.
# 갈리는 것은 **키가 사람이 적는 이름일 때**다: 그때 같은 키는 *같은 것*이 아니라 **다른 것에
# 우연히 같은 이름을 붙인 것**이고, 덮으면 남의 데이터가 사라진다.
# 그래서 이 검사가 보는 것은 **유니크 색인에 `name` 열이 든 엔티티**뿐이다.
#
# ── 무엇을 보는가 ──
# ① `data/model/`에서 유니크 색인에 `name`이 든 엔티티를 뜬다(전수 — 새 엔티티가 늘면 함께 는다).
# ② 그 엔티티를 `@Insert`로 받는 DAO 함수의 바로 위 어노테이션에 `REPLACE`가 있으면 위반.
#
# ── 이 검사가 못 보는 자리 (적어 두지 않으면 다음 사람이 검사가 있다고 믿는다) ──
# **`@Upsert`와 손으로 짠 `@Query("INSERT OR REPLACE …")`.** 2026.08.17 현재 저장소에 그 둘은
# 없고(`grep -rn "@Upsert\|INSERT OR REPLACE" app/src/main`이 0줄), 생기면 이 검사는 조용하다.
# 그리고 **겹쳤을 때 무엇을 하는가**는 여기서 볼 수 없다 — 물어보는지, 그냥 실패하는지는
# 화면의 몫이고 그 축은 `PresetNameGuardTest`와 실기기 확인이 든다.
#
# 사용법: tools/check_name_unique_replace.sh   # 위반 시 exit 1
set -u
export LC_ALL=C.utf8
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

MODEL="app/src/main/java/com/novelcharacter/app/data/model"
DAO="app/src/main/java/com/novelcharacter/app/data/dao"

echo "── 이름 유니크 표의 REPLACE 검사 (B-191 · R-60) ──"

VIOLATIONS=0
NAMED_ENTITIES=""

# ① 유니크 색인에 name 열이 든 엔티티. `Index(value = [... "name" ...], unique = true)` 한 줄 안에서 본다 —
#    이 저장소의 색인 선언은 모두 한 줄이고, 여러 줄로 쓰면 아래 목록에서 빠지므로 그때 이 검사를 고쳐야 한다.
for f in "$MODEL"/*.kt; do
  [ -f "$f" ] || continue
  if grep -qE 'Index\(value = \[[^]]*"name"[^]]*\], *unique = true\)' "$f"; then
    NAMED_ENTITIES="$NAMED_ENTITIES $(basename "$f" .kt)"
  fi
done

if [ -z "$NAMED_ENTITIES" ]; then
  echo "  ✗ 이름 유니크 엔티티를 하나도 못 찾았다 — 색인 선언 모양이 바뀌었으면 이 검사가 아무것도 안 본다" >&2
  exit 1
fi
echo "  대상 엔티티:$NAMED_ENTITIES"

# ② 그 엔티티를 @Insert로 받는 자리에 REPLACE가 붙었는가.
for entity in $NAMED_ENTITIES; do
  for f in "$DAO"/*.kt; do
    [ -f "$f" ] || continue
    # `@Insert(...)`부터 **선언이 닫힐 때까지**를 한 덩이로 모아 그 안에서 엔티티 이름을 찾는다.
    #
    # **한 줄만 보면 안 된다** — 여러 줄로 쓴 시그니처가 사각지대가 된다(2026.08.17 콜드 검토가
    # 실증했다: `suspend fun insert(\n    preset: CharacterListPreset\n): Long`을 심으니 **검사가
    # 조용히 통과했다**). 이 저장소의 DAO는 지금 전부 한 줄이지만, 그 사실에 기대는 검사는
    # *지금 위반이 없는 것*과 *아무것도 안 보는 것*이 겉이 같아진다(R-34).
    hits=$(awk -v ent="$entity" '
      /@Insert/ { ann = $0; blk = $0; line = NR; pending = 1; next }
      pending {
        blk = blk " " $0
        # 선언이 닫혔는가 — fun 을 만난 뒤 닫는 괄호까지 봤으면 한 덩이가 끝난 것이다.
        if (blk ~ /fun / && $0 ~ /\)/) {
          if (index(blk, ": " ent) || index(blk, "<" ent ">") || index(blk, " " ent ")")) {
            if (ann ~ /REPLACE/) printf "%s:%d: %s\n", FILENAME, line, ann
          }
          pending = 0
        }
        # 덩이가 끝나지 않았는데 빈 줄·다음 어노테이션·블록 끝을 만나면 포기한다(무한 누적 방지).
        else if ($0 ~ /^[[:space:]]*$/ || $0 ~ /^[[:space:]]*@/ || $0 ~ /^\}/) pending = 0
      }
    ' "$f")
    if [ -n "$hits" ]; then
      echo "  ✗ $entity — 사용자가 적는 이름이 유니크 키인데 REPLACE로 넣는다:" >&2
      echo "$hits" >&2
      VIOLATIONS=$((VIOLATIONS + 1))
    fi
  done
done

if [ "$VIOLATIONS" -gt 0 ]; then
  echo >&2
  echo '  처방: @Insert(onConflict = OnConflictStrategy.ABORT)로 두고, 겹침은 저장 전에 물어 가른다.' >&2
  echo '        덮어쓰기는 update로 — **id를 지켜야** 그 행을 가리키던 참조가 끊기지 않는다(확정 15장 1번).' >&2
  echo "이름 유니크 표의 REPLACE 검사 실패 — 위반 $VIOLATIONS건" >&2
  exit 1
fi

echo "  ✓ 위반 0건"
echo "이름 유니크 표의 REPLACE 검사 통과"
