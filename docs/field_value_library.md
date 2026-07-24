# 필드 데이터 라이브러리 (field_value_entries)

## 목적

모든 필드(시스템·커스텀, 캐릭터·사건)의 값을 **정규화된 카탈로그**로 관리한다.
값은 입력 즉시 자동 수집되고, 사용자는 그 위에 표시 라벨·별칭·카테고리·설명을 큐레이션한다.
자동완성·검색 칩·통계·엑셀·일괄편집·캐릭터 카드가 모두 이 카탈로그를 단일 소스로 소비한다.

> 개발 의도와의 관계: 받쳐주는 확장성(수백 캐릭터×수십 필드에서 동작하는 배치·diff 설계),
> 변수 제어(파괴적 조작 전 전파 건수 고지·휴지통 스냅샷·조용한 유실 금지),
> 자율성(필드별 입력 모드), 엑셀 왕복 무결성(무편집 왕복 불변 + 관대 수용).

## 스키마 (DB v41, `field_value_entries`)

| 컬럼 | 의미 |
|---|---|
| `fieldDefinitionId` | FK → field_definitions (CASCADE — 필드/세계관 삭제 시 함께 삭제) |
| `value` | canonical 토큰 (trim 저장). `(fieldDefinitionId, value)` UNIQUE |
| `displayLabel` | 표시명 (구 `FieldStatsConfig.valueLabels` 승계) |
| `aliasesJson` | 별칭 JSON 배열 — "데이터에 존재하는 변형 표기 → 이 canonical" 매핑 |
| `category` | 통계 카테고리 (구 `valueCategories` 승계) |
| `isHidden` | **입력 제안에서만** 숨김. 통계는 항상 포함, 사용 중 값은 검색 칩에도 포함 |
| `source` | AUTO(수확)·MANUAL(직접/큐레이션)·IMPORT(엑셀)·AI(AI 정리) |
| `usageCount` | 최종 일관성 캐시 — 라이브러리 화면 진입·임포트 후에 diff-only 재계산 |
| `code` | 엑셀 왕복 안정 식별자 (UNIQUE) |

라이브러리 대상 타입: TEXT / SELECT / MULTI_TEXT / GRADE (구조화 입력 제외).
제외: CALCULATED(파생값), NUMBER(통계 binning 담당), BODY_SIZE·structuredInput(파트 측정치).
판정 단일 소스: `FieldValueTokenizer.supportsLibrary`.

## 토큰화 규칙 (`util/FieldValueTokenizer`)

- 다중값(MULTI_TEXT, COMMA_LIST/BULLET_LIST)은 콤마 분리 토큰 단위로 등재·매칭.
- 정규화는 **trim 후 정확 일치, 대소문자 구분 유지** — 케이스·오탈자 변형의 병합은
  별칭/AI 정리가 사용자 확인 하에 수행한다 (등급 "S"/"s" 오병합 방지).
- `splitForStats`는 구 StatsDataProvider Step 1과 동작 동일(통계 불변 계약, 테스트로 고정).

## 자동 수확 (insert-only)

쓰기 경로 전부에 훅: 캐릭터 저장(일반·세계관 간 이동)·필드값 일괄 저장·일괄 편집·작품 일괄
이동·세계관 이관·**상태변화 기록**·사건 필드값 저장·휴지통 복원·엑셀 임포트 말미.
계약: 트랜잭션 커밋 후 실행, 내부 runCatching(수확 실패가 저장을 실패시키지 않음),
usageCount는 건드리지 않음(훅에서 recount 금지 — 저장 지연·무효화 폭풍 방지).

최초 백필: `NovelCharacterApp.seedFieldValueLibraryIfNeeded` — ① 구 valueLabels/valueCategories
이관(필드 단위 트랜잭션, **빈 칸만 채우는 병합** — 수확 경합·재실행에도 라벨 무손실, 이관 후
config에서 두 맵 제거) ② 전체 수확(상태변화 이력 포함) ③ 전 필드 재계산. 실패 시 다음 실행 재시도.
엑셀 임포트는 `field_library_harvest_pending` 플래그로 중단 시 재시도.

## 별칭 의미론

별칭은 "저장 데이터의 변형 표기 → canonical" 매핑이다 (표시명인 displayLabel과 별개).
- 통계: 토큰 → canonical 접기 → displayLabel 표시·category 그룹핑
  (`FieldValueResolver.statsKeys` — 구 카테고리의 라벨 키공간 조회 체인 보존).
- 검색: canonical 칩 하나가 별칭 저장값까지 매칭 (`expandForFilter`).
- 자동완성: 별칭 입력 시 "별칭 → canonical" 표시, 선택하면 canonical 삽입.
- 엑셀 임포트: 별칭 표기 값은 **원문 저장 + 고지**(F1-B, 무편집 왕복 불변) —
  정규화는 인앱 병합/AI 정리가 확인 후 수행.

## 전파 (rename / merge / delete)

로드→토큰화→치환→재조합으로 수행(SQL 정확일치 금지 — 미trim 행 "서울 "도 잡음).
대상: character_field_values + event_field_values + **character_state_changes**(연도 슬라이더·
성장 그래프가 읽는 이력 — 구 migrateFieldKey 선례) + **필드 config의 값 리터럴**
(SELECT options / GRADE grades / 시맨틱 aliveValue·deadValue — 생사 동기화·스피너 desync 방지).
모든 파괴적 조작은 사전 드라이런(`previewPropagation`) 건수 확인을 거치고,
`deleteEntry(alsoClearData)`는 영향 캐릭터를 휴지통에 스냅샷한다.

## 필드별 입력 모드 (`config.valueLibrary.inputMode`)

- `suggest`(기본): 자유 입력 + 라이브러리 자동완성
- `free`: 제안 끔
- `restricted`: 라이브러리 값만 허용 — 저장 시 검증하되 차단 대신
  "허용 값 안내 + 추가하고 저장 / 입력 수정" 경로 제공. 가드 위치는
  CharacterSaveCoordinator(편집·보충 공통)·사건 편집·일괄 편집.
  엑셀 임포트는 거부하지 않고 경고만 (수용·교정 원칙).

## AI 정리 (`ai/FieldLibraryAiOrganizer`)

온디맨드 전용(BYOK 비용): 가드 → 요청 수 고지 → 청킹(≤120값/6,000자) 분석 →
제안 체크리스트(전체 선택/해제) → **단일 통합 확인**(전파 드라이런 합계) → 선택 적용.
검증: 실존하지 않는 값 참조·기병합 별칭·청크 간 중복 제안은 드롭하고 드롭 수를 표면화.
`ai/AiJsonExtractor`는 후속 AI 기능이 공용하는 관대 JSON 추출기다.

## 엑셀 왕복 ("필드 데이터" 시트)

`options.fieldDefinitions` 게이트, 필드 정의 직후 내보내기/가져오기.
매칭: 코드 우선 → (세계관, 필드키, 대상, 값). 별칭 충돌은 해당 별칭만 제외+경고,
값 충돌은 그 행만 스킵 (거부 아닌 관대 수용). 전각 콤마 별칭 수용.
구버전 파일의 config valueLabels/valueCategories는 임포트 말미에 자동 이관+리포트.

## 결정 기록

- **usageCount는 캐시**: 수확 훅에서 재계산하지 않는다(저장 경로에서 O(필드 전체 값) 금지).
  갱신 지점은 라이브러리 화면 진입·임포트 후이며 diff-only(변한 행만 UPDATE — 무효화 폭풍 방지).
- **고아 정리 잡 없음**: `field_value_entries`는 FK CASCADE라 고아가 생기지 않는다.
  미사용 AUTO 엔트리 정리는 라이브러리 탭의 "미사용 자동수집 정리"(건수 확인 후 실행)가 담당하며,
  큐레이션 흔적(라벨·별칭·설명·카테고리·숨김)이 있는 엔트리는 어떤 정리로도 삭제되지 않는다.
- **세계관 간 병합 해석**: 통계의 (key,type) 세계관 병합은 기존과 동일하게 primary 필드의
  해석기를 적용한다 (구 valueLabels 동작과 패리티 — 세계관별 별칭 상이 시 primary 기준).
- **무효화**: 별칭 변경은 값 테이블을 건드리지 않고도 검색 결과를 바꾸므로 GlobalSearch·캐릭터탭
  InvalidationTracker가 `field_value_entries`를 직접 관측한다. 라이브러리 Live 쿼리는
  field_definitions JOIN으로 캐스케이드 삭제 무효화를 수신한다(recursive_triggers=OFF 우회).
