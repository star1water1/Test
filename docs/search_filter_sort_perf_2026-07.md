# 캐릭터 목록 필터/정렬 재계산 성능 설계서 (2026-07) — PR-E

> 요청: "검색창 필터/정렬 전체 재빌드" 성능 개선.
> 판단 기준: CLAUDE.md 개발 의도 #1(**받쳐주는 확장성** — 무한 확장이 실제로 동작해야 한다; 받쳐주지 못하는 확장은 결함) · #2(**변수 제어** — 데이터가 말없이 유실·왜곡되지 않는다) · 원칙 03(기능 우선) · 원칙 04(조작 마찰 최소화).

## 배경 — 결함

`CharacterViewModel.recompute()`는 `listTrigger`(검색어·정렬·필드필터·태그필터·작품필터 중 무엇이든 변할 때)마다 **파이프라인 전체**를 처음부터 다시 계산했다. 특히 **키 입력 1회**마다 다음이 전부 재실행됐다:

1. **필드 필터 재조회** — `FieldFilterHelper.applyFieldFilters(dao, filters)`가 (필터 수 × 값 수)만큼 `character_field_values` DB 쿼리. 필터가 안 바뀌어도 매번.
2. **태그 필터 전체 스캔** — `characterTagDao().getAllTagsList()`로 **전 태그 로우**를 읽어 메모리 필터. 태그 필터가 안 바뀌어도 매번.
3. **필드/CALCULATED 정렬키 재계산** — `sortByField`가 `getValuesForCharacters` DB 조회 + (CALCULATED는) 캐릭터별 `FormulaEvaluator` 수식 평가. 정렬 사양·데이터가 안 바뀌어도, 심지어 방향(오름/내림)만 뒤집어도 매번.

캐릭터 수백 명 + 커스텀 필드 수십 개 + CALCULATED 정렬이 걸린 상태에서 키 입력마다 전체 DB 재조회·수식 재평가가 일어난다 — 개발 의도 #1이 경계하는 "받쳐주지 못하는 확장 = 결함".

> RecyclerView 측은 이미 `ListAdapter`+`DiffUtil`(비동기 diff)이므로 UI 재렌더는 정상. 결함은 **ViewModel 데이터 파이프라인**에 있다.

## 해법 — 스테이지드 메모이제이션 + Room 테이블 변경 무효화

각 스테이지를 **자기 입력이 바뀔 때만** 재계산하도록 메모한다. 캐시 키에 **Room 테이블 변경 에폭**을 포함해, 외부 편집(단건·일괄·엑셀 임포트·휴지통 복원·세계관 이동 등)이 목록에 즉시·정확히 반영되게 한다.

### 무효화 에폭 (Room InvalidationTracker)

`baseCharacters`는 `characters` 테이블 LiveData만 관측하므로 **필드값/태그/필드정의 편집을 감지하지 못한다**(예: `saveAllFieldValues`는 `characters` 로우를 건드리지 않음). 그래서 세 부류의 테이블 변경을 `AtomicInteger` 에폭으로 관측한다:

| 에폭 | 관측 테이블 | 무효화 대상 |
|------|-------------|-------------|
| `fieldValueEpoch` | `character_field_values`, **`field_definitions`** | 필드필터 id셋, 정렬키맵 |
| `tagEpoch` | `character_tags` | 태그필터 id셋 |
| `structEpoch` | `field_definitions`, `novels`, `universes` | 정렬키맵(필드정의·CALCULATED 수식·작품→세계관 귀속 변경) |

- `field_definitions`는 `fieldValueEpoch`와 `structEpoch`를 **함께** 올린다: 필드 정의 삭제는 FK 캐스케이드로 그 필드의 `character_field_values`를 지우지만, Room `recursive_triggers=OFF`라 **자식 테이블 무효화 트리거가 울리지 않는다**. 그래서 필드정의 변경을 필드값 변경으로도 간주해 필드필터 메모(키=`fieldValueEpoch`)가 stale해지지 않게 한다. (GlobalSearch도 `field_definitions`를 함께 관측.)

- `onInvalidated`는 Room 쿼리 실행자(**오프메인**)에서 호출된다 → 에폭 bump(원자적) 후 `_tableInvalidation.postValue(Unit)`로 메인에 위임. `_tableInvalidation`을 `listTrigger`의 소스로 추가 → 기존 `recompute()`의 120ms 디바운스를 그대로 재사용(자기 쓰기·버스트 흡수).
- **누수 방지**: InvalidationTracker는 옵저버를 **강한 참조**로 보관하고 `AppDatabase`는 프로세스 수명 싱글턴이다. `onCleared()`에서 `removeObserver` **필수**(신설). VM은 `by viewModels()`라 백스택 왕복에서 생존하므로 캐시·옵저버가 유지된다 → 무효화가 반드시 필요.

### 스테이지별 캐시

- **필드필터 id셋** — `EpochMemo<List<FieldFilter>, Set<Long>>`, 키=(필터 리스트, `fieldValueEpoch`). 검색어/정렬만 바뀌면 재조회 없이 재사용.
- **태그필터 id셋** — `EpochMemo<Set<String>, Set<Long>>`, 키=(태그 집합, `tagEpoch`). 전체 스캔(`getAllTagsList`)을 **타깃 쿼리** `getCharacterIdsByTags(tags)`로 교체.
- **필드 정렬키맵** — 증분 per-id 캐시. 정체성 키=(kind, fieldKey, partIndex, **scopeSig**, `fieldValueEpoch`, `structEpoch`). `ascending`·검색어·필터는 **제외** → 방향 반전·타이핑은 키맵 재사용, 정렬키 재계산 0.

### 정확성 불변식 (변수 제어 — 잘못된 목록 = 조용한 왜곡)

1. **도메인 완전성** — 정렬키맵은 **후보 목록의 모든 id에 대해** 키를 보장한다. 캐시에 없는 **살아있는 id**는 즉시 계산해 채운다(`computed` 집합으로 "계산됨(값 없음=null)"과 "미계산"을 구분). 검색 해제·필터 제거·스코프 확대·휴지통 복원으로 보이는 집합이 **커져도** 새 id가 null 키로 최후순에 처박히지 않는다. → 어드버서리얼 리뷰가 지목한 최대 리스크의 방지책.
2. **스코프 포함** — `scopeSig`(현재 작품 id)를 정렬 정체성 키에 포함. 스코프에 따라 `getCharacterFieldsForKey`의 (key,type) 병합·CALCULATED 세계관 해석이 달라지므로.
2-1. **allCalc의 novelId 재검** — CALCULATED 키는 `char.novelId → 세계관 → 수식`에 의존한다. 그런데 `batchChangeNovel`이 **무세계관(미배정/universeId=null) 작품으로** 이동시키면 필드값 재작성을 건너뛰어 `fieldValueEpoch`가 오르지 않는다. 이때 `characters`만 바뀌어 recompute는 돌지만 정체성 키는 그대로라 stale한 CALCULATED 키가 재사용될 수 있다. 그래서 allCalc 캐시는 각 id의 계산 시점 novelId(`novelIdAtCompute`)를 저장하고, novelId가 달라진 id는 재계산 대상에 포함한다.
3. **FK CASCADE 사각지대 커버** — Room은 `recursive_triggers=OFF`라 캐릭터 삭제로 인한 자식 테이블 CASCADE 삭제는 `character_field_values`/`character_tags` 트리거를 **발화시키지 않는다**. 그러나 모든 캐릭터 삭제 경로는 `characters`를 변경 → `baseCharacters` 재발화 → recompute. 캐시에 남은 삭제 id는 **살아있는 base와 교집합**으로 무해(같은 이유로 필드/태그 id셋의 stale 잔여 id는 안전).
4. **핀/재정렬/이름·최근 정렬** — `characters` 변경 → base 재발화로 신선하게 재계산. 이때 필드/태그/정렬키 메모는 **재사용해야 옳다**(원본 테이블 불변) — 무효화하지 않는다.

## GlobalSearch 파리티

`GlobalSearchViewModel`도 동일한 **필드필터 재조회** 결함을 가졌다. CALCULATED·태그 정렬이 없어 정렬키 메모는 불필요 → 필드필터만 `EpochMemo`(`fieldValueEpoch` + `field_definitions` 관측)로 메모, `onCleared`에서 옵저버 해제. 규칙은 계속 `FieldFilterHelper`에 공유하고 **캐시 상태만 VM별**로 보유(스테이트리스 object에 뮤터블 캐시를 넣지 않음 — 스레드 안전·바운딩·수명 문제 회피).

### 후속 — base 재조회 분리 (구조 개선)

기존 `GlobalSearchViewModel.searchResults`는 `(query, sort, filters)` 결합 키의 `switchMap`이라, **정렬 모드나 필터만 바꿔도** 3개의 Room LIKE 검색(캐릭터·사건·작품 전체 스캔)을 통째로 헐고 재발급했다(행 집합은 그대로인데도). 캐릭터 탭과 동일한 원리로 분리:

- `rawResults = baseKey.switchMap { … }` — Room 검색은 **query에 의존**(=query 변경 시에만 재발급). `switchMap`이 이전 내부 mediator의 소스를 자동 비활성화하므로 기존의 수동 소스 정리(`previous*`)를 제거.
- **유휴 부하 제거** — base 키는 `(query, loadAllForFilter)`이고 `loadAllForFilter`는 **blank query에서 필터가 활성일 때만** true다. 따라서 blank+무필터 기본 상태에서는 `allCharacters`를 아예 구독하지 않아 전체 캐릭터 테이블 로드가 없다(대형 라이브러리에서 빈 힌트를 그리려고 전체를 읽지 않음 — 개발 의도 #1). active query에서 필터 토글은 키를 바꾸지 않으므로 base 재조회도 없다(재조회는 query 변경 또는 blank 상태의 필터 활성 전이에서만).
- `resultsTrigger`(rawResults·sort·filters·필드값무효화) → `rebuild()` — 정렬/필터 변경은 base 재조회 없이 **인메모리 재랭킹**만. 필드필터 id셋은 기존 `EpochMemo`로 재조회 최소화, 순수 `buildItems()`로 랭킹·섹션 구성. 필터 계산을 먼저 await하므로 미완성 결과가 노출되지 않는다(기존 `filterReady` 게이팅 대체).
- 보너스: `_fieldValueInvalidation`로 필드값/정의 편집 시 결과를 즉시 재발화(캐릭터 탭과 일관).

## 재사용·테스트 (순수 JVM)

메모/비교 로직을 순수 클래스로 추출해 Robolectric 없이 검증:
- `util/EpochMemo.kt` — 단일 슬롯, (키,에폭) 일치 시 재사용, 불일치 시 재계산, `invalidate()`.
- `util/SortComparators.kt` — `nullsLast`(값 없음 최후순, 방향 무관 · 동값은 타이브레이크 오름).
- `app/src/test/` 신설 + `testImplementation`(junit, kotlinx-coroutines-test). CI(`build-apk.yml`)에 `testDebugUnitTest` 스텝 추가(로컬 SDK 부재 → CI가 유일 검증).
- 테스트: `EpochMemoTest`(무효화 계약), `SortComparatorsTest`(nullsLast·타이브레이크), `FieldFilterHelperTest`(필터 AND·값 OR·contains/exact 라우팅·빈 필터, 페이크 DAO 호출 카운트).

## 검증

- 로컬 안드로이드 SDK 부재 → 컴파일·마이그레이션·패키징은 PR CI(Build APK), 로직은 신설 `testDebugUnitTest`.
- 어드버서리얼 리뷰(다수 스켑틱)로 캐시 무효화 정확성(편집/일괄/임포트/복원/세계관이동/프리셋/핀/정렬필드삭제/방향반전/blank 전환) 반박 검증.

## 문서 이력
| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026.07.20 | 필터/정렬 재계산 메모이제이션 + Room 테이블 무효화 설계·구현(PR-E) |
