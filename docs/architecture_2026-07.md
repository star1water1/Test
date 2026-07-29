# 아키텍처 개관 — 이 저장소의 진입점 (2026-07)

> **이 문서의 자리:** 코드베이스가 328파일·87,108줄이 되도록 **진입점 문서가 없었다.**
> 새 작업자(사람이든 세션이든)가 처음 잡는 문서는 1,650줄짜리 인수인계 로그
> (`remaining_work_2026-07.md`)였고, 거기에는 "지금 무엇을 해야 하는가"는 있어도
> "이 앱이 어떻게 생겼는가"는 없다. 이 문서가 그 자리를 채운다.
>
> **범위:** 현행 구조의 지도와 확장점, 그리고 규약 색인. **성능·확장 한계는 다루지 않는다** —
> 그쪽은 `docs/scalability_performance_2026-07.md`가 단일 소스다.
>
> **읽는 순서:** ① 이 문서로 지형을 잡고 → ② 손댈 영역의 설계 문서를 읽고 →
> ③ `remaining_work_2026-07.md` 5장에서 지금 할 일을 고른다.

---

## 1. 현행 규모 (2026-07-29 실측)

| 항목 | 값 | 측정 방법 |
|------|-----|-----------|
| 메인 소스 | **333 파일 / 88,775줄** (Kotlin) | `find app/src/main/java -name '*.kt'` |
| 테스트 소스 | **72 파일 / 순수 JVM 942건** | `tools/run_jvm_tests.sh` |
| Room DB | **v44** · 엔티티 **26** · 마이그레이션 **43**(v1→v44) | `data/database/AppDatabase.kt` |
| 사용자 문구 | `res/values/strings.xml` 단일 파일 | `tools/check_text_style.sh` |

> **테스트 파일 72 ≠ 러너가 도는 71.** `AiPresetsConsistencyTest`(3건)는 `R`을 참조해 순수
> 하네스에서 돌 수 없어 목록에서 빠져 있다(CI 전용). 그래서 소스의 `@Test`는 945인데 실행은
> 942다 — 이 차이를 결함으로 오인하지 말 것. 러너는 `$TESTS` 목록에 적힌 파일만 컴파일하므로
> **목록에 넣지 않은 새 테스트는 조용히 돌지 않는다**(5장 착수 시 확인 항목).

> **숫자를 문서에서 믿지 말 것.** 이 표의 값도 찍힌 순간의 스냅샷이다. 실제 작업 전에
> 위 명령으로 재현하라 — 실제로 인수인계 문서의 테스트 기준선이 760으로 적혀 있는 동안
> 실행값은 847이었다.

---

## 2. 계층 지도

```
app/src/main/java/com/novelcharacter/app/
├── data/            DB 계층 — 여기가 진실의 원본
│   ├── model/  (44)   엔티티 + 설정 값 객체(FieldStatsConfig·DisplayFormat·SemanticRole …)
│   ├── dao/    (24)   Room DAO
│   ├── repository/ (21)  트랜잭션 경계 · 휴지통 · 스냅샷 복원
│   └── database/      AppDatabase(v44) + 마이그레이션 43개(v1→v44)
├── util/       (61)  ★ 순수 계층이 사는 곳 — 39개는 Android 비의존(JVM 테스트 대상, 3장),
│                        22개는 Android 의존(화면 헬퍼·Prefs·이미지 로더)
├── excel/      (24)  엑셀 왕복 (내보내기·가져오기·시트 규약)
├── share/      (7)   월드패키지(ZIP) 내보내기·들이기
├── ai/         (15)  프롬프트 조립 · 응답 해석 · 정책
├── backup/     (4)   자동 백업 워커
├── widget/     (3)   홈 위젯
└── ui/         (124)  화면 — character(22) · stats(15) · adapter(13) · image(10) …
```

**의존 방향은 한 방향이다:** `ui → repository → dao → Room`, 그리고 모든 계층이 `util`을
바라본다. `util`은 아무도 바라보지 않는다 — 그래서 이 저장소에서 실행 검증이 가능한
거의 유일한 계층이다(4장).

> ⚠️ **`util/` = 순수 계층이 아니다. 디렉터리가 아니라 파일 단위로 갈린다.**
> 61개 중 **22개가 Android·Material에 의존한다**(`AlertDialogExt`·`ProgressDialogs`·
> `CharacterImageLoader`·`*Prefs`·`ThemeHelper` …). 이들은 JVM 하네스가 컴파일하지 않으므로
> **`util/`에 넣었다는 사실만으로 "테스트된다"고 가정하지 말 것.**
> 판정법은 하나다 — `tools/run_jvm_tests.sh`의 `$SOURCES` 목록에 그 파일이 있는가.
> (실측: `grep -rl '^import android\.\|^import androidx\.' …/util | wc -l` → 22)

### 데이터 모델 — 26 엔티티

| 묶음 | 엔티티 |
|------|--------|
| 뼈대 | `Universe` → `Novel` → `Character` |
| 사용자 정의 필드 | `FieldDefinition` · `CharacterFieldValue` · `EventFieldValue` · `FieldValueEntry`(값 라이브러리) |
| 시간 | `TimelineEvent` · `TimelineCharacterCrossRef` · `TimelineEventNovelCrossRef` · `CharacterStateChange` |
| 관계 | `CharacterRelationship` · `CharacterRelationshipChange` · `Faction` · `FactionMembership` · `FactionRelationship` |
| 이미지 | `ImageMeta` · `ImageTag` |
| 부가 | `CharacterTag` · `NameBankEntry` · `SearchPreset` · `CharacterListPreset` · `UserPresetTemplate` · `RecentActivity` |
| 안전망 | `TrashSnapshot` · `OperationLog` |

**`FieldDefinition`이 이 앱의 심장이다.** "도구를 만드는 도구"(CLAUDE.md 핵심 철학)라는 성격이
전부 여기서 나온다 — 필드의 타입·파싱 방식·통계 편입 여부·AI 정책·표시 형식이 모두
`FieldDefinition.config`(JSON)에 사용자 설정으로 들어간다. 그래서 **이 앱의 확장은 코드가
아니라 데이터로 일어난다.**

---

## 3. 단일 소스 지도 — 이 저장소의 핵심 자산

`util`의 순수 객체들은 "편의 함수 모음"이 아니라 **도메인 규칙의 유일한 정의 자리**다.
같은 규칙이 여러 화면에 복제되면 반드시 갈라진다는 것을 이 저장소는 여러 번 값비싸게 배웠다
(U-9: 수식 서식이 7곳에 복제돼 로케일과 오류 처리가 제각각이었다).

**규칙: 아래 표에 있는 판단을 호출부에서 다시 구현하지 않는다.**

| 단일 소스 | 무엇의 유일한 정의인가 | 어긋났을 때 생긴 일 |
|-----------|------------------------|---------------------|
| `FieldValueTokenizer` | 필드값 문자열 → 통계 토큰 분리 | 순위 탭이 자체 분리를 써서 인사이트와 수치가 달랐다(S-18) |
| `FieldValueResolver` | 별칭·표시 라벨 접기 | — (R-21로 스냅샷 파생으로 승격) |
| `FieldStatsConfig` / `StatsFieldPolicy` | '통계에 포함' 설정의 적용 규칙 | 패턴 감지가 설정을 무시했다(S-14) |
| `NumericBinning` + `FieldValueMatchSpec` | 수치 구간 + 드릴다운 매칭 | 라벨 문자열로 매칭해 항상 0명이었다(S-16 → R-20) |
| `ValueDistributions` | 분포 전량 + 잘라낸 개수 | 비율 분모가 상위 N 합이라 편향이 왜곡됐다(S-17 → R-19) |
| `FormulaLexer` | 수식 어휘 규칙 + **버려지는 구간** | 미지 함수가 조용히 버려져 그럴듯한 오답이 됐다(로드맵 5) |
| `FormulaEvaluator` / `FormulaValidator` / `FormulaDisplay` | 수식 평가 / 저장 시점 검증 / 표시 서식 | 서식·NaN 처리가 7곳에 복제(U-9) |
| `GradeValueResolver` | 등급 라벨 → 숫자 | — |
| `SnapshotRefs` / `SnapshotRefResolver` | 휴지통 복원의 안정 식별자 | 복원이 남의 엔티티에 붙었다(R-1) |
| `FolderNameToken` / `FolderRoundtripPlanner` / `FolderExportPlanner` / `FolderRoundtripLedger` | 이미지 폴더 왕복의 이름·계획·장부 | 개명 경로가 토큰을 깨 중복 편입(C-1) |
| `CharacterFieldValueOverflow` | 캐릭터 시트가 담지 못한 값의 판정 | — |
| `EventFieldValueMerge` / `CharacterFieldValueMerge` | 부분 저장의 커버 집합 | 사건 필드값이 무통보 폐기됐다(S-6) |
| `SortComparators` / `FieldFilterHelper` / `UnassignedFilter` | 목록 정렬·필터 | — |
| `ImportSource`(`ImportWorkbook`/`Sheet`/`Row`/`Cell`) | 가져오기가 워크북을 읽는 유일한 접근면 | DOM·스트리밍 두 경로가 갈리면 같은 파일이 다른 데이터가 된다(B-8) |
| `ResetPlan` | 앱 초기화가 비우는 테이블의 범위 | 26개 중 5개가 '모든 데이터 삭제' 뒤에도 살아남았다(S-13) |
| `MembershipTimeline` | 소속 구간의 시간 규칙 — 만들 때의 가입 연도, 고칠 때의 구간 겹침 | 재가입이 이전 탈퇴보다 앞이어도 통과해, 같은 해에 나가 있으면서 들어와 있는 이력이 됐다(1-h장) |

---

## 4. 검증 체계 — Android 빌드가 불가한 환경에서

`dl.google.com`이 차단돼 **`./gradlew`는 무엇을 하든 실패한다.** 그래서 검증은 네 갈래다.

| 도구 | 무엇을 보는가 | 기준선 |
|------|---------------|--------|
| `tools/run_jvm_tests.sh` | 순수 계층을 **실제로 실행**한다(표준 kotlinc + JUnitCore) | **942건** |
| `tools/check_text_style.sh` | 화면 문구의 말투·용어(가이드 기계 검출분) | 기준선 33건 동결, 새 위반은 즉시 실패 |
| `tools/check_resources.sh` | 리소스 중복·미정의 참조·XML 구문 | 통과 |
| `tools/check_dialog_validation.sh` | 자동 닫힘 버튼 안의 조기 return(R-27 위반) | 0건 동결 — 새 위반 즉시 실패 |
| `tools/differential_compile.sh` | 손댄 파일에 **새로 생긴** 컴파일 오류만 | 기준선 대조 |
| `tools/verify_room_migration*.py` | 마이그레이션 3종을 **실제 SQLite로** 실행 | 35 · 49 · 25건 |
| `tools/verify_reset_coverage.py` | 엔티티 목록 ↔ `ResetPlan` ↔ `executeReset` 호출부 3자 대조 | 30건 |

**이 구조가 강제하는 설계 규칙:** 판단 로직은 최대한 `util`의 순수 객체로 내려야 한다.
`ui`에 남은 로직은 **실행 검증이 불가능**하고 차분 컴파일의 잡음에 묻힌다(뷰바인딩 미해결만
기준선에 212종). 로드맵 5가 `FieldEditDialog`의 검증 로직을 `FormulaValidator`로 내린 것도
이 이유다.

> **차분 컴파일 함정:** 스크립트 내부 정규화 `sed`가 실제 출력(`파일:행:열: error:`)과 달리
> 콜론 하나가 빠져 **줄 번호를 지우지 못한다.** 그대로 비교하면 손댄 파일의 오류가 전부
> '신규'로 뜬다. 스크립트 헤더가 안내하는 수동 정규화를 반드시 따를 것.

> **⚠️ 하네스가 통과시키고 CI만 터지는 자리 — `android.util.Log`.**
> 로컬 하네스는 `tools/jvm-stubs/AndroidLogStub.kt`로 Log 자리를 채운다(0을 돌려주는 껍데기).
> 그런데 Gradle의 `testDebugUnitTest`가 쓰는 android.jar은 **모든 메서드가 예외를 던지는**
> 껍데기라, 순수 로직이 진단용으로 부르는 `Log.w` 하나가 테스트를 깨뜨린다.
> 로드맵 5에서 실제로 이것에 걸렸다 — `FormulaEvaluator`의 순환 참조·미존재 키 경로가
> `Log.w`를 부르는데, 그 경로를 처음으로 테스트한 순간 **로컬 전량 통과 / CI 4건 실패**가 났다.
> `app/build.gradle.kts`의 `testOptions { unitTests { isReturnDefaultValues = true } }`로 막았다.
>
> **일반화:** 하네스의 스텁은 "컴파일을 통과시키는 것"이 목적이라 **런타임 의미가 실제와 다르다.**
> 순수 계층에 Android API를 새로 부르는 코드를 넣을 때는 하네스 통과가 근거가 되지 못한다.

---

## 5. 확장점 — 무엇을 추가할 때 어디를 건드리는가

### 5-1. 새 필드 타입 (현재 7종: TEXT · NUMBER · SELECT · MULTI_TEXT · GRADE · CALCULATED · BODY_SIZE)

`FieldType` enum에 더하는 것으로 끝나지 않는다. 그리고 **분기 지점을 enum으로 세면 과소평가된다** —
실측하면 이렇다:

| 세는 방법 | 파일 수 |
|-----------|---------|
| `FieldType`을 참조 | 16 |
| `FieldType.<상수>`로 분기 | 7 |
| **`"CALCULATED"` 같은 생문자열로 분기** | **28** (2026-07-29 실측 — 등재 시점 23에서 늘었다) |

타입 판정이 `fd.type == "CALCULATED"` 같은 **문자열 비교로 28개 파일에 흩어져 있다.**
`FieldType`에 상수를 하나 더해도 이 28곳은 아무것도 모른다 — 새 타입은 그 화면들에서
조용히 빠지고, 그것이 원칙 02 위반(껍데기 구현)의 가장 흔한 발생 경로다.

착수 시 반드시 훑을 것: 입력 렌더(`DynamicFieldRenderer`) · 통계 적격성(`StatsFieldPolicy`) ·
분포 계산(`ValueDistributions`) · 드릴다운 매칭(`FieldValueMatchSpec`) · 엑셀 열 직렬화
(`FieldConfigColumns`·`CharacterFieldValueOverflow`) · 랜덤 생성(`FieldRandomGenerator`) ·
AI 정책(`FieldAiPolicy`).

> **구조 부채로 등재할 것:** 문자열 분기 28곳을 `FieldType`으로 좁히면 새 타입 추가가
> 컴파일러의 도움을 받는다(`when`의 exhaustive 검사). 지금은 전수 grep이 유일한 방어다.

### 5-2. 새 대상(entityType) — 지금은 `character` · `event`

`FieldDefinition.entityType`이 축이다. **DAO 기본 인자가 `ENTITY_CHARACTER`라는 것이 함정이다** —
호출부가 대상을 넘기지 않으면 조용히 캐릭터 필드를 본다(로드맵 5가 `FieldEditDialog`에서
실제로 이 결함을 잡았다). 새 대상을 열 때는 `getFieldsByUniverseList(universeId, entityType)`
호출부를 전수 확인할 것. 작품·세력으로의 확장은 백로그(확-3).

### 5-3. 새 통계 분석

**적응적 통계 원칙**(CLAUDE.md)상 분석 항목은 필드 구성에서 **동적으로 생성**된다.
새 분석을 붙일 자리는 `StatsDataProvider`이며, 셋을 반드시 지킬 것:
계산 필드 병합(R-16) · 자동 선택에만 설정 적용(R-18) · 라벨이 아닌 타입드 스펙으로 매칭(R-20).

### 5-4. 새 왕복 포맷

현재 둘: **엑셀 왕복**(`excel/` — 시트 규약, 오버플로 시트, 헤더 별칭)과
**월드패키지**(`share/` — ZIP + manifest v1~v3). 새 포맷은 기존 둘의 규약을 재사용해야 한다 —
특히 안정 식별자(`EntityCode`)와 "빈 칸 = 해제" 규약, 그리고 **거부가 아니라 수용·교정**
(개발 의도 4번).

---

## 6. 규약 색인 R-1 ~ R-27

정의는 전부 `docs/remaining_work_2026-07.md`에 있다(행 번호는 갱신되므로 제목으로 찾을 것).
**아래는 색인이며, 착수 전 해당 영역의 규약은 원문을 읽을 것.**

| # | 한 줄 | 영역 |
|---|-------|------|
| R-1 | 스냅샷도 안정 식별자를 병기한다 — 코드가 대상을 정하고 id는 확인 수단이다 | 복원 |
| R-2 | Gson 스냅샷에 추가하는 필드는 전부 nullable (기존 필드 타입 변경 금지) | 복원 |
| R-3 | 보관 한도가 방금 만든 백업을 파괴해서는 안 된다 | 휴지통 |
| R-4 | 파괴적 동작은 실행 전에 결과를 알리고 취소 경로를 남긴다 | 전역 |
| R-5 | 폼의 권한은 폼이 실제로 렌더한 것까지다 | 입력 |
| R-6 | 예약 시트명은 소유자만 가질 수 있다 | 엑셀 |
| R-7 | 시트의 정체는 이름이 아니라 헤더가 정한다 — 첫 열 하나로는 부족하다 | 엑셀 |
| R-8 | 스냅샷은 겹치지 않고 이어붙는다 | 휴지통 |
| R-9 | 정리도 복원도 '삭제 작업' 단위다 | 휴지통 |
| R-10 | payload 한 행은 읽을 수 있는 크기여야 한다 (CursorWindow) | 저장 |
| R-11 | 보류 판정은 참조 종류마다 키가 있어야 한다 | 복원 |
| R-12 | 스냅샷의 종류는 인스턴스가 아니라 호출이 정한다 | 휴지통 |
| R-13 | 집계의 셀 단위가 다르면 함수를 나눈다 | 통계 |
| R-14 | 목록에서 잘라낸 것은 개수로 존재를 알린다 | 전역 |
| R-15 | 머지된 카드가 약속한 범위 = 그 카드에서 뻗는 모든 경로의 범위 | 통계 |
| R-16 | 계산 필드는 저장 행이 없다 — 저장 값을 읽는 모든 경로가 그것을 알아야 한다 | 통계·엑셀 |
| R-17 | 빈 결과는 사유를 말한다 — "못 찾음"과 "없음"은 다르다 | 전역 |
| R-18 | 설정은 '자동 선택'에만 적용된다 — 사용자가 고른 것은 계산한다 | 통계 |
| R-19 | 상한은 표시 계층의 관심사다 | 통계 |
| R-20 | 라벨은 매칭 키가 아니다 | 통계 |
| R-21 | 계산기의 파생 상태는 인자에서만 나온다 | 통계 |
| R-22 | 서식이 곧 값의 정체성인 문자열은 로케일을 고정한다 | 엑셀·통계 |
| R-23 | 학습한 사실은 학습 대상이 바뀌면 함께 버린다 | AI |
| R-24 | 성립하지 않는 조합의 설정은 보이지 않는다 | UI |
| R-25 | 화면에 노출되는 설정에는 목적문이 붙는다 | 텍스트 |
| R-26 | 항목 순회형 대량 작업에는 결정형 진행도를, 조회형에는 가짜 진행도를 붙이지 않는다 | UI |
| R-27 | 검증 실패는 창을 닫지 않는다 — 알리는 것과 고칠 자리를 남기는 것은 다른 일이다 | UI·입력 |

---

## 7. 문서 지도 — 무엇을 언제 읽는가

| 목적 | 문서 |
|------|------|
| **판단 기준**(모든 설계·리뷰의 최상위) | `CLAUDE.md` — 개발 의도 4질문 · 5대 원칙 · 새 기능 체크리스트 |
| **지형 파악**(지금 이 문서) | `docs/architecture_2026-07.md` |
| **확장 한계·성능** | `docs/scalability_performance_2026-07.md` |
| **지금 할 일 / 백로그 / 실기기 확인** | `docs/remaining_work_2026-07.md` (5장이 시작점) |
| **미이행 기능 색출과 개혁 로드맵** | `docs/superficial_feature_audit_2026-07.md` |
| **실사용 데이터가 말한 것** | `docs/usage_reality_check_2026-07.md` (+ `_runbook`) |
| **화면 문구** | `docs/text_style_guide_2026-07.md` |
| **영역별 설계** | 엑셀 왕복 `excel_roundtrip_audit` · 엑셀 스트리밍 가져오기 `excel_streaming_import` · 이미지 폴더 왕복 `image_folder_roundtrip_design`(결정 근거 `image_external_management`) · AI `ai_integration`·`ai_control_and_ui_density` · 값 라이브러리 `field_value_library` · 필터·정렬 짝 `filter_sort_parity` |
| **점검 결과·수리 계획** | `app_inspection_round2` · `repair_plan` · `usability_review` · `design_intent` |
| **절차서** | `room_migration_verification` — 마이그레이션 하네스 3종을 어떻게 만들고 돌리는가 |
| **브랜치·병합** | `docs/branch_merge_rules.md` |
| **끝난 것 / 낡은 것** | `docs/archive/` — 구현 완료된 계획서와 구시점 리뷰. **근거로 쓰지 말 것**(각 문서 머리의 보관 헤더가 무엇이 낡았는지 적어 둔다) |

> **루트의 `.md`는 `CLAUDE.md` 하나다.** 예전에는 루트에 계획서 2종과 리뷰 2종이 더 있었는데,
> 넷 다 현재형으로 말하면서 실제로는 끝났거나 전제가 낡아 있었다(`CODE_REVIEW.md`는
> "테스트 파일 미발견"이라 적혀 있었다 — 지금은 924건이 돈다). `docs/archive/`로 옮기고
> 각각에 무엇이 낡았고 무엇이 이어받았는지를 헤더로 달았다.

---

## 문서 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|-----------|
| v1.2 | 2026.07.29 | **앞 커밋이 절반만 고친 수치 정정 + 계층 설명의 사실 오류 정정.** ① 4장 러너 기준선이 **896으로 남아 있었다**(1장만 924로 고쳐졌다 — 같은 문서 안에서 두 수치가 갈렸다) ② 규모 실측 현행화(328파일·87,108줄 → **332·88,420**, util 59→60·excel 22→24·ui 123→124) ③ **마이그레이션 "44개" → 43개**(v1→v44는 43단계다) ④ **`util/`을 "순수 계층 — Android 비의존"이라 단정하던 것 정정** — 60개 중 **22개가 Android 의존**이며 JVM 하네스가 컴파일하지 않는다(디렉터리가 아니라 `$SOURCES` 목록이 판정한다) ⑤ 테스트 파일 71 vs 러너 실행 70의 차이 명시(`AiPresetsConsistencyTest`는 CI 전용) ⑥ B-55 생문자열 분기 23→**28** 재실측. 규약 **R-27**(검증 실패는 창을 닫지 않는다) 등재 + `tools/check_dialog_validation.sh` 검증 체계 표 등재 |
| v1.1 | 2026.07.29 | 수치 현행화(테스트 896건·70파일) · 단일 소스 표에 `ImportSource`·`ResetPlan` 등재 · 검증 체계 표에 python 하네스 4종 추가 · **문서 지도의 누락 8종 등재**(특히 `excel_streaming_import` — 바로 다음 작업의 설계 단일 소스인데 지도에 없었다) |
| v1.0 | 2026.07.28 | 최초 작성 — 진입점 부재를 메운다. 현행 규모 실측(328파일·87,108줄·DB v44·엔티티 26), 계층·데이터 모델 지도, 단일 소스 표, 검증 체계, 확장점 4종, 규약 R-1~R-26 색인, 문서 지도 |
