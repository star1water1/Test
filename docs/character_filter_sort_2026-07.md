# 캐릭터 탭 필터링·정렬 설계서 (2026-07)

> 요청: "캐릭터탭에 필터링, 정렬 기능을 넣기."
> 판단 기준: CLAUDE.md 원칙 01(열린 구조)·02(실질적 기능성)·05(유기적 연결) — 사용자가 만든 모든 필드가 필터/정렬에서 소외되지 않아야 한다. 원칙 04(조작 마찰 최소화).

## 배경

캐릭터 목록 탭은 작품 스코프 + 이름 검색만 있었고, 정렬은 SQL 고정(`isPinned DESC, displayOrder ASC, name ASC`), 태그·커스텀 필드 필터/정렬은 없었다. 앱에는 이미 검증된 인프라가 있어 대부분 재사용·이식으로 구현했다:
- **Global Search**: `FieldFilter` 모델, `applyFieldFilters`(필드값 ID 교집합), 인메모리 정렬, `SearchFilterBottomSheet`, `SearchPreset`.
- **Stats**: `StatsDataProvider.computeRanking`의 값→비교 변환(하위 헬퍼 `GradeValueResolver`·`FormulaEvaluator`·`StructuredInputConfig`).

## 사용자 확정 결정

1. 커스텀 필드까지 **적응형** 필터·정렬.
2. UI: 검색창 아래 **정렬 칩 + 필터 칩 줄**.
3. 프리셋: 이름 붙인 **DB 프리셋**(필터+정렬만, 작품 스코프 미저장).
4. 핀 정렬: **기본(수동) 정렬에만** 최상단, 명시적 정렬은 순수 정렬.

## 구현

### 공통 추출 (중복 방지)
- `data/model/FieldFilter.kt` — GlobalSearch의 `FieldFilter`를 top-level 승격.
- `util/FieldFilterHelper.kt` — `applyFieldFilters`(필터 AND·값 OR) + JSON 직렬화. GlobalSearch도 이걸 사용(무회귀).
- `util/FieldValueSorter.kt` — 필드값→비교키. 수치형(NUMBER/GRADE/BODY_SIZE/CALCULATED)은 stats와 동일 하위 헬퍼로 일치, TEXT/SELECT/MULTI_TEXT는 목록 직관성 위해 가나다.

### DB (v36→v37)
- `CharacterListPreset` 엔티티/DAO/Repository + `MIGRATION_36_37`. 작품 스코프 미저장, 상한 20.

### ViewModel (`CharacterViewModel`)
- 상태: `sortSpec`(CharacterSort) / `fieldFilters` / `tagFilters`, SharedPreferences 영속.
- 파이프라인: `baseCharacters`(작품+검색) → 필드필터∩태그필터 → 정렬. 백그라운드(Dispatchers.Default) + 120ms 디바운스.
- 정렬: Manual만 핀 최상단; 명시적 정렬은 순수(값 없음 최후순). CALCULATED는 세계관별 `FormulaEvaluator` 라이브 계산. 전체 스코프는 세계관 간 `(key,type)` 병합, 작품 선택 시 해당 세계관 스코프.
- 프리셋: `saveAsPreset`/`applyPreset`/`renamePreset`(이름만)/`overwritePreset`(현재상태)/`deletePreset` — 결과 채널 통보.

### UI
- `fragment_character_list.xml`: 정렬 버튼 + 방향 버튼 + 필터 버튼 + 프리셋/활성필터 칩 줄. 빈 상태에 필터 안내 + '필터 지우기'.
- `CharacterSortBottomSheet`: 기준(기본/이름/생성일/최근/필드) + 필드/BODY_SIZE 파트.
- `CharacterFilterBottomSheet`: 태그(다중) + 필드값(세계관→필드→값, exact/contains) 단일 시트(중첩 없음, `SearchFilterBottomSheet` 흐름 답습).
- `CharacterListFragment`: 정렬 칩(탭=방향반전, 기본은 시트), 필터 시트, 프리셋 칩(적용/이름변경/현재상태 갱신/삭제), 활성필터 칩(닫기), 빈 상태 분기, 재정렬 게이팅(기본정렬+필터없음+검색없음), 배치/비교/재정렬 모드 시 바 숨김.

## 핵심 동작

| 항목 | 동작 |
|------|------|
| 핀 고정 | Manual 정렬만 최상단; 명시적 정렬은 순수 |
| 정렬 방향 | 방향 버튼 탭으로 오름↔내림 즉시 반전 |
| 재정렬(드래그) | Manual + 필터 없음 + 검색 없음일 때만 |
| 전체 스코프 | 세계관 간 (key,type) 병합; 작품 선택 시 해당 세계관 |
| CALCULATED | 정렬 O(라이브) / 필터 X(DB 값 없음) |
| 값 없는 캐릭터 | 필드 정렬 시 최후순 |
| 빈 결과 | 필터로 비면 "필터에 맞는 캐릭터 없음 · 필터 지우기" |
| 프리셋 | 필터+정렬만 저장(작품 스코프 미포함); 저장·삭제만 통보+이력 |

## 검증

- 로컬 SDK 부재 → PR CI(Build APK)로 컴파일·마이그레이션·패키징(v37).
- 회귀: `FieldFilter`/헬퍼 공용화 후 Global Search 필터·정렬·프리셋 정상.
- 앱 실측(사용자): 커스텀 필드(성별 SELECT/나이 NUMBER/등급 GRADE) 생성 후 필터·정렬·프리셋·재정렬 게이팅·빈 상태·CALCULATED 정렬 확인.

## 문서 이력
| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026.07.04 | 캐릭터 탭 필터/정렬/프리셋 구현 |
