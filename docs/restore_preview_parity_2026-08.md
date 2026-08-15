# 복원 미리보기 ↔ 가져오기 정합 — B-101 + B-102 설계

> **지위:** 착수 설계. 사용자 확정으로 **B-101과 B-102를 묶어** 범주 전수로 훑는다
> (`docs/judgment_confirmations_2026-08.md` 5번·6번, 순서 강제표 「5번 = 6번」).
>
> **선행:** B-87(2026.08.02)이 *한 행을 기존 것과 비교하는 규칙*을 두 범주에서 단일 소스로 모았다
> (`util/FactionMembershipMatcher.kt` · `util/FactionRelationshipMatcher.kt`).
> **그 교훈이 규약이 되지 않아** 나머지가 그대로 갈려 있었다(건수는 아래 1장 표가 센다) — 이 문서가 그것을 닫고
> **규약 R-33으로 승격**해 다시 갈리지 못하게 한다.

---

## 0. 무엇이 문제인가 — 한 문장

미리보기는 **'동일'이라 말하고 가져오기는 실제로 바꾼다.**

B-87이 고친 것은 *'안 바뀌는데 바뀐다'*는 **거짓 경보**였지만 이쪽은
*'바뀌는데 안 바뀐다'*는 **거짓 안심**이라, 사용자가 되돌릴 기회 없이 덮어쓴다.
개발 의도 2번(변수 제어)의 **'알림'이 반대로 거짓말하는** 자리다.

---

## 1. 착수 대조 — 범주 전수 census (2026.08.03 실측)

백로그 B-101 행이 *"여기 건수를 적지 않는 것은 그래서다 — 적으면 낡고, 조사표를 원문으로
믿지 말라는 것이 이 저장소가 세 번 배운 것이다"*라고 적어 둔 그대로, **코드를 직접 세었다.**

세는 법: `analyze*`의 비교식 vs 짝이 되는 `import*`의 `existing.copy(...)` 인자 집합.

| 범주 | analyze가 보는 것 | import가 쓰는 것 | 갈림 |
|---|---|---|---|
| **세계관** | name, description | + displayOrder · borderColor · borderWidthDp · imagePaths · imageMode · customRelationshipTypes · customRelationshipColors · imageCharacterId · imageNovelId · createdAt | **10열** |
| **작품** | title, description | + universeId · displayOrder · borderColor · borderWidthDp · inheritUniverseBorder · isPinned · imagePaths · imageMode · imageCharacterId · standardYear · createdAt | **11열** |
| **필드 정의** | name, type | + config · groupName · displayOrder · isRequired | **4열** |
| **캐릭터** | name, memo, anotherName | + firstName · lastName · novelId · displayOrder · isPinned · createdAt · imagePaths · 대표이미지 · updatedAt | **9열** |
| **사건 연표** | year, description — **그나마 코드 매칭일 때만** | + month · day · calendarType · eventType · universeId · displayOrder · isTemporary · createdAt · code | **9열 + 자연키 매칭은 무조건 '동일'** |
| **상태 변화** | year, fieldKey, newValue, characterId — **코드 매칭일 때만** | + month · day · description · createdAt · code | **5열 + 자연키 매칭은 무조건 '동일'** |
| **관계** | relationshipType, description | + intensity · isBidirectional · displayOrder · factionId · createdAt · code | **6열** |
| **관계 변화** | year, month, day — **코드 매칭일 때만** | + relationshipType · description · intensity · isBidirectional · eventId · createdAt · code | **7열 + 자연키 매칭은 무조건 '동일'** |
| **이름 은행** | origin, notes | + name · gender · isUsed · usedByCharacterId · createdAt | **5열** |
| **세력** | name, description | + universeId · color · autoRelationType · autoRelationIntensity · displayOrder · createdAt | **6열** |
| **필드 템플릿** | name, description, fieldsJson | + isBuiltIn · updatedAt | **2열** |
| **검색 프리셋** | query, filtersJson | + sortMode · isDefault · updatedAt | **3열** |
| **목록 프리셋** | tagsJson, fieldFiltersJson | + sortKind · sortFieldKey · sortAscending · bodySizePartIndex · novelIdsJson · isDefault · updatedAt | **7열** |
| 세력 소속 | `FactionMembershipMatcher.changes` | `FactionMembershipMatcher.apply` | **없음 (B-87)** |
| 세력 관계 | `FactionRelationshipMatcher.changes` | `FactionRelationshipMatcher.apply` | **없음 (B-87)** |
| 등급 체계 | name + 정규화한 등급표 | name, gradesJson (`saved != existing`) | **census는 "없음"으로 봤으나 틀렸다 — 아래 1-2** |
| 이미지 태그·링크 | 태그·링크그룹·뗀날짜 | 같음 | **열 집합은 같으나 리더가 갈림 — 아래 1-1** |
| 필드 데이터 | `FieldValueSheetMapper.RowEffect` | 같은 매퍼 | **없음** |

**→ 열 집합이 갈린 것이 열셋.** 여기에 아래 1-1(리더 갈림)과 1-2(census 오판 둘)를 더하면
**갈린 범주는 모두 열다섯**이고, 갈리지 않은 것은 셋뿐이다(세력 소속·세력 관계·필드 데이터).

### 1-1. 세 번째 갈림 — **비교 열이 아니라 '읽는 법'이 다른 자리**

착수 대조가 백로그에 없던 부류를 하나 더 찾았다. `analyzeImageMeta`는

```kotlin
val tagColIndex = cols["태그"] ?: 1        // 위치 폴백 있음
val groupColIndex = cols["링크그룹"] ?: 2   // 위치 폴백 있음
```

인데 `importImageMeta`는 **같은 두 열에 위치 폴백을 명시적으로 금지**한다
(`?: -1`, 주석 *"위치 폴백 금지 — 열을 지우면 이웃 열을 오독한다"*).

→ **'태그' 열을 지운 시트**에서 분석은 1번 열(=링크그룹)을 태그로 읽어 '변경'이라 말하고,
가져오기는 태그를 손대지 않는다. 이번에도 방향은 **거짓말**이다.

**그래서 이 슬라이스가 단일 소스로 모을 것은 `changes` 하나가 아니라 `읽기 + 적용` 둘이다.**
비교식만 맞추고 리더를 각자 두면 **같은 결함이 한 겹 아래에서 되살아난다.**

### 1-2. census가 두 자리 틀렸고, **검사 도구가 그것을 잡았다**

4장의 정적 검사를 켜자마자 이 표가 "없음"으로 판정한 둘에서 손으로 짠 비교식이 걸렸다.
**표를 고치지 않고 이 절을 붙이는 것은, 전수 대조도 틀린다는 사실 자체가 기록이기 때문이다.**

- **등급 체계** — 분석이 `rename` 게이트를 모사하지 않았다. 가져오기는 이름 변경이 같은
  세계관의 다른 체계와 **충돌하면 이름을 유지**하는데, 분석은 그것을 모르고 '변경'이라
  말한다(이번엔 거짓 안심이 아니라 **거짓 경보**다). `mergeGradeSystem`으로 모았다.
  **아울러 가져오기가 거부하는 무리**(유효한 등급 행이 하나도 없는 것)를 '신규'로 세고
  있었다 — 3장 ⓑ와 같은 부류라 `skippedCount`에 넣었다.
- **이미지 태그·링크** — 위 1-1이 리더 갈림만 지적했으나, 비교 쪽도 하나 더 있었다:
  **'뗀날짜'가 숫자가 아닐 때** 가져오기는 *경고만 하고 손대지 않는데* 분석은 '변경'이라
  말했다. 이 범주는 상태가 **엔티티 셋**에 걸쳐 있어 `copy` 한 줄로 표현되지 않으므로
  `ImageMetaState`를 세우고 그 위에서 병합한다(가져오기도 그것으로 쓴다 — 덤으로
  안 바뀐 행의 태그 전량 삭제+재삽입이 사라졌다).

> **이것이 4장을 테스트가 아니라 검사로 만든 이유의 실증이다.** 내가 손으로 센 census가
> 두 자리 틀렸고, **기계는 틀리지 않았다.**

---

## 2. 설계 — 단일 소스의 모양

범주마다 **둘**을 서비스 안의 `private fun` 한 쌍으로 모으고, `import*`와 `analyze*`가
**같은 함수**를 부른다.

```kotlin
/** 엑셀 한 행이 말하는 값. 열이 없으면 null — '빈칸'(=지우라는 뜻)과 다르다(F1-A). */
private fun readUniverseRow(row: Row, cols: Map<String, Int>, ctx: String, result: ImportResult?): UniverseRowValues

/** 그 행을 기존 항목에 적용한 결과. 가져오기가 쓰는 값이 곧 미리보기가 비교하는 값이다. */
private fun mergeUniverse(existing: Universe, r: UniverseRowValues): Universe
```

- `import*` — `read(...)` → `merge(...)` → `update`
- `analyze*` — `read(..., result = null)` → `merge(...) != existing` 이면 '변경', 아니면 '동일'

`result: ImportResult?`가 null이면 경고를 쌓지 않는다. **분석에는 경고를 낼 자리가 없고**,
그 차이 때문에 리더를 두 벌로 두는 것이 애초의 사고 원인이었다.

### 2-1. 왜 순수 객체(`util/XxxMatcher.kt`)로 빼지 않는가

B-87은 `util/`에 순수 객체를 만들었다. 그 선례를 열셋으로 늘리지 않은 이유는 둘이다.

1. **B-87이 옮긴 것은 *규칙*이었다** — 3단 계층 매칭은 그 자체로 판단이 있어 단위 테스트가
   값을 한다. 이번에 옮기는 것은 `existing.copy(...)` **한 표현식**이고, 손으로 짠 기대값과
   대조하는 테스트는 **같은 열 목록을 세 번째로 베껴 적는 일**이 된다(그것이 낡으면 아무도 모른다).
2. **진짜 위험은 "새 열이 import에만 추가되는 것"이다.** 그것은 단위 테스트가 못 막는다 —
   테스트도 함께 낡기 때문이다. **기계가 막아야 한다.**

→ 그래서 이 슬라이스의 방어선은 테스트가 아니라 **정적 검사**다(아래 4장).
**R-32(`tools/check_image_pointer.sh`)가 같은 이유로 만들어졌다** —
*"D5의 '열여덟 지점' 목록은 다음 사람이 늘리는 순간 낡으므로 기계가 막게 했다."*

### 2-2. 시계 필드 — `updatedAt`을 어떻게 다루는가

프리셋 셋과 캐릭터는 갱신 시 **시각을 무조건 새로 찍는다**
(`updatedAt = updatedAt ?: System.currentTimeMillis()` · `updatedAt = System.currentTimeMillis()`).

그대로 비교하면 **모든 행이 '변경'**이 되어 미리보기가 쓸모를 잃는다(원칙 02 — 겉핥기 기능).
반대로 비교에서만 빼면 **미리보기가 또 거짓말**을 한다: 프리셋 셋은 전부
`ORDER BY ... updatedAt DESC`로 정렬되므로 **시각이 바뀌면 사용자 목록의 순서가 실제로 바뀐다.**

**→ 비교를 고치는 대신 쓰기를 고친다.** 병합 결과가 기존과 같으면 **DB에 쓰지 않는다.**

```kotlin
val merged = mergeSearchPreset(existing, r)
if (merged != existing) dao.update(merged.copy(updatedAt = r.updatedAt ?: now))
```

`updatedAt`의 뜻은 *"이 항목이 마지막으로 바뀐 때"*이므로, 아무것도 바뀌지 않았으면
그 값도 바뀌지 않는 것이 맞다. 덤으로 **쓸데없는 DB 쓰기가 사라진다.**

> 이것은 판정이 아니라 정정이다 — 어느 쪽을 골라도 말이 되는 자리가 아니라,
> 현행이 **사용자가 손대지 않은 것을 바꾸고 있었다**(개발 의도 2번).

### 2-3. 지연 해석(deferred) 열 — 중간 상태가 아니라 **순효과**를 비교한다

`importUniverses`는 이미지 참조를 일단 null로 두고 2단계에서 코드로 되붙인다.

```kotlin
imageCharacterId = if (imageCharCodeColIndex >= 0) null else existing.imageCharacterId
```

이 중간 상태를 그대로 비교하면 **이미지캐릭터코드 열이 있는 모든 행이 '변경'**이 된다 —
2단계가 같은 값으로 되돌려 놓는데도. 그래서 병합 함수는 **코드를 현행 DB에서 해석한 결과**로
비교한다. 해석되지 않으면(같은 파일이 그 캐릭터를 지금 만드는 중이면) **기존 유지로 본다** —
거짓 '변경'보다 거짓 '동일'이 낫다는 뜻이 아니라, **그 경우는 B-102 ⓑ의 '건너뜀' 축이
따로 세기 때문**이다(아래 3장).

---

## 3. B-102 — 실행 맥락 두 자리

### ⓐ 시트 안 중복 행

가져오기는 첫 행을 넣은 뒤 `existingByKey`·`siblings`에 **그것을 즉시 등재**하므로 둘째 행이
그것과 매칭된다(신규 1 + 변경/동일 1). 분석은 등재하지 않아 **둘 다 '신규'**로 센다.

→ 분석도 **가상 등재 맵**을 굴린다. 실제 DB에 넣지 않되, 이 시트가 앞서 만든 행을
`(키 → 방금 만든 값)`으로 기억하고 뒤 행이 그것과 매칭되게 한다.

해당 범주: 이름 은행 · 검색 프리셋 · 목록 프리셋 · 세력 관계 · 캐릭터
(가져오기가 새 행을 맵에 등재하는 자리 전수).

### ⓑ 가져오기가 건너뛸 행을 '신규'로 센다

세계관·필드가 없으면 분석은 '신규'로 세는데, **같은 가져오기가 그것들을 먼저 만드는
정상 복원에서는 맞다.** 그러나 사용자가 **'세계관'을 끄고 '필드 정의'만 켜면** 가져오기는
그 행을 경고와 함께 **건너뛰는데** 미리보기는 '신규 N'이라 말한다.

→ `CategoryAnalysis`에 **`skippedCount`**를 더하고, 분석이 `options`를 보고
*"이 가져오기에서 선행 범주가 꺼져 있어 이 행은 실행되지 않는다"*를 판정한다.
미리보기에 **'건너뜀 N'**을 표시한다.

> **ⓑ는 화면을 바꾼다** — 세션 착수 규칙 4번에 따라 **CI 초록과 실기기 확인 전까지
> 완료로 보고하지 않는다.**

---

## 4. 재발 방지 — 규약 R-33 + 정적 검사

**규약 R-33.** *복원 미리보기의 판정과 가져오기의 쓰기는 같은 함수에서 나온다.*

**검사 도구 `tools/check_restore_preview_parity.sh`** — `analyze*` 함수 본문에
**손으로 짠 필드 비교식**(`existing.<field> != <local>`)이 남아 있으면 실패한다.
새 범주를 만들거나 기존 범주에 열을 더할 때, 비교를 손으로 적는 순간 검사가 막는다.

기준선은 **0건**이다(텍스트 검사와 같은 방식 — 새 위반 즉시 실패).

**축은 이후 늘었고, 수는 여기 적지 않는다 — 세는 법은 스크립트의 성공 메시지다.**
비교식 축만 적혀 있던 동안 실제로는 read*Row 짝(1-1) · '갱신' 게이트(B-111)가 더해져
있었고, B-217이 **시트 조회 축**을 더했다: `analyze*`가 `workbook.getSheet(정확명)`으로
시트를 직접 찾으면 가져오기(`findSheet`)의 판정 — 캐릭터 시트 지문 배제 · 접미사 복구 ·
정확명 폴백 — 을 통째로 우회한다. 열아홉 자리가 그렇게 서 있었고 앞선 축들은 전부 못
봤다(같은 시트를 읽는다는 전제 위의 검사들이라). 판정 자체는 `SheetResolver`(순수,
`ImportWorkbook` 추상 위)로 내려 미리보기·가져오기·삭제 가드가 같은 함수를 보고,
`SheetResolverTest`가 DOM·스트리밍 양 경로에서 답을 잠근다 — **검사는 *지나는가*를,
시험은 *판정이 맞는가*를 본다.**

**B-232가 다섯째 축을 더했다 — *행이 누구에게 붙는가*.** 앞의 축들은 전부
*"같은 시트의 같은 행을 같은 규칙으로 읽고 비교하는가"*를 보는데, 그 위를 다 맞춰도
**그 행이 가리키는 캐릭터를 서로 다르게 고르면** 예고가 거짓이 된다. 실제로 미리보기
다섯 자리(상태 변화 1 · 관계 2 · 관계 변화 2)가 `findCharacterByName`(전역 `LIMIT 1`)으로
동명이인 중 **아무나 한 명**을 골라 그 행을 '신규/갱신'으로 세었고, 짝이 되는 가져오기는
`findCharacterStrict`·`resolveCharByNameNovel`로 **같은 행을 거부**했다(모호 고지 + 코드 안내).
유실은 없지만 — 가져오기가 소리 내어 거부한다 — **미리보기가 예고한 숫자가 실행되지 않는다.**

처방은 앞의 축들과 같다. **판정을 순수로 내리고**(`excel/CharacterRefResolver.kt`의
`CharacterRefLadder` — 재료는 호출부가 색인에서 떠 오고 판정만 여기서 한다),
**짝끼리 같은 것을 부르게** 한 뒤, 모호는 `skippedCount`로 센다(B-102 ⓑ).
**사다리를 하나로 합치지는 않았다** — 해소 힌트가 시트마다 다르기 때문이다(캐릭터코드 열이
있는 시트 대 '작품' 열로 좁히는 시트). 합치면 이번에는 가져오기 쪽 판정과 어긋난다.
`CharacterRefResolverTest`가 두 사다리의 답을 잠그고(**힌트가 없을 때 둘의 답이 같은지까지**),
검사 ⑤가 `analyze*`의 해석 함수 집합이 짝 `import*`의 것에 포함되는지를 본다 —
등재되지 않은 `analyze*`가 캐릭터를 해석하면 그것도 위반이다(**조용히 빠지는 것이 이 부류의
실패 모양**이라 새 시트는 짝을 적게 만든다). 전역 first-match 헬퍼는 아예 없앴고,
되살아나면 같은 검사가 잡는다.

---

## 5. 문서 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|-----------|
| v1.3 | 2026.08.15 | **B-232 반영 — 4장에 해석 사다리 축(⑤) 등재.** 1장 census도, 그 뒤 더해진 축 넷도 전부 *"같은 행을 같은 규칙으로 읽고 비교하는가"*를 보는데, **그 행이 가리키는 캐릭터를 서로 다르게 고르는 자리**는 어느 축에도 걸리지 않았다 — 비교식도 리더도 시트도 같기 때문이다. 미리보기 다섯 자리가 `findCharacterByName`(전역 `LIMIT 1`)으로 동명이인 중 아무나 골라 '신규/갱신'으로 예고하는 동안 짝 가져오기는 같은 행을 거부하고 있었다. 판정을 순수(`CharacterRefLadder`)로 내려 짝끼리 같은 것을 부르게 하고, 모호는 `skippedCount`로 센다(B-102 ⓑ). **사다리는 둘로 남겼다** — 해소 힌트가 시트마다 다르므로 억지로 합치면 이번에는 가져오기와 어긋난다. **함께 걷은 갈림 셋:** 상태 변화의 코드 미해석이 미리보기에서만 이름으로 폴백하던 것 · 관계에서 캐릭터가 미해석인데 관계 코드가 맞으면 '갱신'으로 세던 것(가져오기는 캐릭터를 먼저 확정한다) · 자기 자신과의 관계를 '신규'로 세던 것(가져오기는 언제나 거부) · 세력 힌트가 캐릭터1만 보던 것(가져오기는 캐릭터2로 넘어간다) |
| v1.2 | 2026.08.14 | **B-217 반영 — 4장에 시트 조회 축 등재.** 1장 census가 *행 안의* 갈림(비교식·리더)을 전수했는데, **그 앞 겹인 "어느 시트를 읽는가"가 census의 축에 없었다** — analyze* 열아홉 자리가 정확명 `getSheet`로 `findSheet`의 판정을 우회하고 있었고(접미사 복구·캐릭터 시트 지문·정확명 폴백 전부), 4장의 검사도 같은 시트를 읽는다는 전제 위라 못 봤다. 판정을 `SheetResolver`(순수)로 내려 세 소비처(미리보기·가져오기·삭제 가드)가 같은 함수를 보고, 검사 ④가 우회 재발을 막고, `SheetResolverTest`가 DOM·스트리밍 양 경로에서 답을 잠근다. 2-3(지연 해석 = 순효과 비교)은 **가져오기 결과 계수 쪽도** 같은 규약임이 이번에 시행됐다 — 병합이 deferred null과 비교해 이미지 연동 항목을 무편집 파일에서 '갱신'으로 세던 것을, 이미지 축 중립 계수 + 되붙는 자리의 승격(원본 id 동봉)으로 바로잡았다(확정 7-2) |
| v1.1 | 2026.08.03 | **구현 완료 반영.** ① **1-2 신설** — 4장의 검사 도구가 census의 오판 둘을 잡았다(등급 체계의 `rename` 게이트 · 이미지 '뗀날짜' 비수치 처분). **표를 고치지 않고 절을 붙인 것은 전수 대조도 틀린다는 사실이 기록이기 때문**이고, 그것이 곧 4장을 테스트가 아니라 검사로 만든 이유의 실증이다 ② 자기 재공격이 **실제 컴파일 오류 하나**를 잡았다 — `analyzeNovels`에 넣은 `val byTitle = { ... }` 람다 안에서 suspend 함수를 부르고 있었는데, **실클래스패스 프로브가 메시지 단위로 접어 감췄다**(같은 메시지가 기준선에 이미 있었다). 중복을 접지 않은 원본 출력으로 base 대 cur를 비교해 확인했다(양쪽 1573건·해당 파일 57건으로 동일) — **`remaining_work` 2장이 경고한 dedup 함정의 첫 실증이다** |
| v1.0 | 2026.08.03 | 착수 설계 — 범주 전수 census(열셋 갈림 + 리더 갈림 1건 신규 발견), 단일 소스 모양, 시계 필드 처분, B-102 ⓐⓑ, 규약 R-33 |
