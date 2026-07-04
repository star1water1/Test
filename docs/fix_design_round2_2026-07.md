# 2차 점검 후속 수정 설계서 (2026-07)

> 원본 점검: `docs/app_inspection_round2_2026-07.md` (문제점 7 / 개선점 7 / 확장점 7 / 시나리오 7)
> 절차: **설계 → 적대적 검증(실코드 전제 확인) → 구현**. 이 문서의 모든 전제는 실코드로 재확인했다.
> 판단 기준: CLAUDE.md 개발 의도 — ①받쳐주는 확장성 ②변수 제어 ③자율성 우선 ④엑셀 왕복 무결성
> 설계 방침: **이번 업데이트용 방편이 아니라 장기 구조로.** 각 항목마다 "왜 이 구조가 장기적으로 옳은가"를 명시한다.

## 이번 구현 범위

권장 우선순위 1군(데이터 유실 직결)과 2군(원칙 위반 해소) 전체 — **문-1, 문-2, 문-6 + 문-3, 문-4, 문-5, 문-7**.
개선점(개-1~7)·확장점(확-1~7)은 후속 배치로 이관하되, 이번 설계가 그 방향과 충돌하지 않도록 구조를 잡는다
(예: 문-2의 code 체계는 확-6 JSON 왕복·개-1 휴지통 확대의 전제가 되고, 문-3의 통계 제네릭화는 확-3 entityType 전면화의 기반이 된다).

---

## F-1. 엑셀 셀 텍스트 한도 단일 소스화 [문-1 · P0급 · 기준 ④①]

**문제:** export는 32,767자(`ExcelExporter.kt XLSX_CELL_LIMIT`), import는 10,000자(`ExcelImportService.kt MAX_FIELD_LENGTH`)로
서로 다른 상수를 갖고 있어 순수 왕복만으로 최대 22,767자가 유실된다.

**장기 구조:** 한도는 "엑셀 규격"이라는 단일 사실에서 나오므로 상수도 단일 소스여야 한다.
시트 구조의 단일 소스인 `SheetSpec.kt`에 규격 상수를 두고 양쪽이 참조한다.

**설계:**
1. `SheetSpec.kt`에 `const val EXCEL_CELL_TEXT_LIMIT = 32767` 신설 (XLSX 셀 텍스트 규격 한도 — 주석 명기).
2. `ExcelExporter.XLSX_CELL_LIMIT` → 상수 참조로 교체(동작 불변).
3. `ExcelImportService.MAX_FIELD_LENGTH` → 상수 참조로 교체(10,000 → 32,767 상향). 잘림 경고 문구는 이미 `${MAX_FIELD_LENGTH}` 동적 참조라 자동 정합.
4. 사용 안내 시트(`exportInstructions`)의 한도 안내 문구를 "셀당 최대 32,767자(엑셀 규격), 가져오기도 동일 한도" 로 갱신.

**대안 검토:** DB 측 별도 상한 신설(예: 100,000자) — SQLite TEXT는 사실상 무제한이고 앱 내 입력 경로에 별도 상한이 없으므로,
엑셀 왕복 한도만 규격에 맞추면 충분하다. 인위적 DB 상한 추가는 새로운 절단점을 만들 뿐 → 기각.
**리스크:** 32,767자 값 다수 유입 시 메모리 — 이미 파일 자체가 50MB 파싱 상한으로 묶여 있어 총량은 기존과 동일 규모. 무시 가능.
**검증:** 두 상수 참조 지점 전수 추적(`getCellString` 기본 인자, 경고 문구, 안내 시트), 10,000 하드코딩 잔존 grep.

## F-2. 사건·상태변화·관계변화 안정 식별자(code) 도입 — DB v34→v35 [문-2 · 기준 ④②]

**문제:** 세 엔티티만 자연키 매칭이라 외부에서 설명·값·연도를 고치면 "편집"이 "복제"가 된다.
캐릭터·세계관·작품·세력은 이미 `code`(UUID 16자) 체계로 이 문제가 없다.

**장기 구조:** 기존 code 체계를 세 엔티티로 **동일 패턴 확장**. 새 매칭 규칙을 발명하지 않고
"코드 우선 → 자연키 폴백(구버전 파일 호환) → 부재 시 생성" 이라는 기존 규약을 그대로 따른다.
이 체계는 확-6(JSON 무손실 왕복)과 개-1(휴지통 확대: 스냅샷 복원 시 정체성 유지)의 전제가 된다.

**적대적 검증에서 확정한 사항:**
- `Character.code`는 `String = generateEntityCode()`(non-null)이지만, 세 엔티티는 **nullable(`String? = generateEntityCode()`)로 한다.**
  근거: 휴지통 `CharacterSnapshot` payload(상태변화·관계변화 포함)와 월드패키지 JSON은 Gson 역직렬화라서
  v35 이전에 만들어진 스냅샷/패키지에는 code 필드가 없어 **non-null 필드에 런타임 null이 주입**된다
  (Gson은 Kotlin null 검사를 우회). non-null로 하면 `copy()` 시 `checkNotNullParameter`로 복원이 크래시한다.
  nullable은 이 레거시를 자연 수용하고, 유니크 인덱스는 SQLite에서 NULL 다중 허용이라 제약 충돌도 없다.
- `CharacterRelationshipDao.insertAll`은 이미 `List<Long>` 반환 — F-5에서 재확인.
- `resolveHeaderColumns`는 미등록 헤더를 원문 그대로 통과시키므로 기존 "연결사건ID" 컬럼은 별칭 등록 없이도 동작해 왔다.
  신규 컬럼은 별칭을 정식 등록한다.

**설계:**

1. **엔티티** (3개 공통): `val code: String? = generateEntityCode()` + `Index(value=["code"], unique=true)`.
   - 앱 내 모든 신규 생성 경로는 기본 인자로 자동 부여(호출부 무변경).
   - Gson 레거시 역직렬화만 null → 기존(=코드 없던 시절) 행과 동일하게 동작.
2. **마이그레이션 v34→v35:** 각 테이블에 `ADD COLUMN code TEXT`(nullable) → 커서 루프로 기존 전 행에 UUID 백필 →
   유니크 인덱스 생성. (백필이 인덱스 생성보다 먼저 — 충돌 불가. 커서 루프는 v29→30 선례와 동일 패턴.)
3. **DAO:** `getEventByCode` / `getChangeByCode`(상태변화) / `getChangeByCode`(관계변화) 추가.
4. **SheetSpec:**
   - `timelineSpec`: "코드"(readOnly) 컬럼을 "임시배치" 뒤·"생성일" 앞에 추가 (사건 필드 동적 컬럼은 그 뒤).
   - `stateChangeSpec`: "코드"(readOnly)를 "캐릭터코드" 뒤에 추가.
   - `relationshipChangeSpec`: "연결사건ID" 컬럼을 **"연결사건코드"(readOnly)로 교체** + "코드"(readOnly) 추가.
     사건 id는 복원/기기 이전 시 변하는 값이라 참조로 부적합 — code 참조가 구조적으로 옳다.
     import는 "연결사건코드" 우선, 없으면 기존 "연결사건ID"도 계속 인식(하위 호환).
5. **Export:** 세 시트에 code 기록(null이면 빈 칸). 관계변화의 연결사건은 eventId→event.code 변환 기록
   (사건 export가 옵션에서 꺼져 있어도 DB 조회로 코드 확보).
6. **Import 매칭 규칙** (세 카테고리 공통, 기존 캐릭터 시트 규약과 동일):
   - 파일에 코드가 있으면 `getXxxByCode`로 매칭 → **자연키 구성 요소(설명/연도/값 등)까지 전부 갱신** (코드 매칭이므로 이들은 이제 편집 가능한 값).
   - 코드 없거나 미발견 → 기존 자연키 매칭 폴백. 매칭된 기존 행에 코드가 없으면 파일 코드(있으면) 또는 신규 생성 코드를 부여(점진적 백필).
   - 완전 신규 행: 파일 코드가 있으면 그대로 보존(기기 이전 시 정체성 유지), 없으면 자동 생성.
   - 파일 내 코드 중복: 뒤 행이 같은 행을 갱신(last-write-wins) + 경고 — 기존 `codesSeen` 규약 준용.
   - `matchedXxxIds` 집계는 기존 그대로 (MERGE 삭제 옵션·OVERWRITE 경로 무변경).
7. **analyze(미리보기) 정합:** 세 categoryAnalysis도 코드 우선 매칭으로 신규/갱신/변경없음을 재분류
   (코드 매칭 + 내용 상이 = 갱신으로 집계 — 기존 "자연키 일치=변경없음" 가정 교체).
8. **헤더 별칭:** `alias("연결사건코드", "linked_event_code", "event_code")` 추가. "코드"는 기존 별칭 재사용.
9. **안내 시트:** "사건 연표/상태변화/관계 변화 시트에 코드 열이 추가되었습니다. 지우지 마세요 —
   외부에서 설명·연도를 편집해도 같은 항목으로 인식하는 기준입니다. 구버전 파일(코드 없음)도 계속 가져올 수 있습니다." 추가.

**대안 검토:**
- (a) 자연키 유지 + 유사도 매칭(편집 감지 휴리스틱): 오매칭이 곧 데이터 오염, 규칙 설명 불가 → 기각.
- (b) 사건만 code 도입: 상태변화·관계변화도 동일 결함 보유, 반쪽 해법은 방편식 패치 → 기각.
- (c) non-null code: 위 검증 사항(레거시 Gson null 주입 크래시)으로 기각.

**리스크:**
- 마이그레이션 커서 루프 비용: 행당 UPDATE 1회, 수천 행 규모 1회성 — v29→30 선례상 수용 범위.
- 구버전 앱으로 다운그레이드 시 v35 스키마 비인식 — 기존 정책(다운그레이드 미지원)과 동일, 신규 리스크 아님.
- 코드 열을 사용자가 삭제한 파일: 자연키 폴백으로 기존과 동일 동작(퇴행 없음).
- 휴지통 복원 시 동일 code 재삽입: 원본 행이 삭제된 뒤라 유니크 충돌 없음. 예외 케이스(복원 전 임포트로 동일 code 재생성)는
  행 단위 try/catch로 흡수되고 오류 집계에 노출.
**검증:** ①마이그레이션 SQL의 컬럼/인덱스/백필 순서 추적 ②export 세 시트 인덱스 재계산 전수 확인
③import 코드/자연키/신규 3경로 + 구버전 파일(코드 열 부재) 경로 추적 ④연결사건코드→ID 폴백 추적.

## F-3. 사건 커스텀 필드 통계 편입 [문-3 · 원칙 02]

**문제:** 통계 스냅샷이 캐릭터 필드만 로드해 사건 필드(B-10)는 어떤 분석에도 안 잡힌다.

**장기 구조:** 분포/수치 분석 헬퍼가 `CharacterFieldValue`에 결합되어 있는 것이 근본 원인.
**값 문자열 목록 기반으로 제네릭화**하면 이후 확-3(작품/세계관/세력 필드)도 같은 경로로 편입된다 — 이번에 그 기반을 놓는다.

**설계:**
1. `StatsSnapshot`에 `eventFieldDefinitions: List<FieldDefinition> = emptyList()`, `eventFieldValues: List<EventFieldValue> = emptyList()` 추가.
   `loadSnapshot`에서 `getAllFieldsList(ENTITY_EVENT)` + `eventFieldValueDao().getAllValuesList()` 로드.
2. `computeFieldDistribution`/`computeNumericAnalysis`의 값 파라미터를 `List<CharacterFieldValue>` → `List<String>`으로 제네릭화
   (두 함수 모두 `.value`만 사용함을 확인). 호출부는 `.map { it.value }`로 적응 — 동작 불변.
3. `computeFieldInsights`가 캐릭터 필드 인사이트에 이어 **사건 필드 인사이트**를 같은 규칙으로 생성:
   - `(key, type)` 세계관 통합, `FieldStatsConfig.enabled` 필터, DISTRIBUTION/NUMERIC/RANKING 동일 적용.
   - 모수(totalCount) = 해당 세계관들의 사건 수(`universeId in universeIds`), filledCount = 값 수.
   - 사건 CALCULATED 필드: 사건별 값 맵으로 `FormulaEvaluator` 평가(`computeAllEventCalculatedValues` 신설 — 캐릭터판과 동일 구조).
4. `FieldInsightResult`는 `fieldDefinition.entityType`으로 구분 가능 — UI(`StatsFieldInsightFragment`)의 헤더 프리픽스에
   사건 필드일 때 "사건 · " 표기 추가 (기존 `universeName · ` 프리픽스 패턴 재사용).
5. `filterByNovel`: 작품 필터 시 남은 사건(id)의 값만 유지하도록 `eventFieldValues`/`eventFieldDefinitions` 필터 추가.

**범위 명시(후속):** 사건 필드의 교차분석 축 편입, 연표 카드 필드값 표시는 후속(개선 백로그)으로 남긴다 — 통계 편입(원칙 02 위반 해소)이 이번 완결 단위.
**리스크:** 스냅샷 메모리 증가는 사건 필드값 규모(작음)에 비례 — 무시 가능. 제네릭화는 시그니처 변경이므로 호출부 전수 추적 필수.
**검증:** 캐릭터 필드 인사이트 무회귀(호출부 diff), 사건 필드 분포·수치·랭킹 3경로 추적, 작품 필터 시 사건 값 필터 정합.

## F-4. GRADE 등급값 해석 단일화 [문-4 · 품질 기준·원칙 01]

**문제:** 등급→숫자 해석이 3곳에 있고 그중 성장 차트만 하드코딩 고정맵이라 커스텀 등급이 0으로 왜곡, 통계와 수치 불일치.

**장기 구조:** "등급 해석"이라는 도메인 규칙을 단일 유틸로 승격 — `util/GradeValueResolver`(object).
이후 등급을 다루는 모든 신규 화면은 이 유틸만 쓴다.

**설계:**
1. `GradeValueResolver` 신설:
   - `resolveFromConfig(fieldDef, label): Double?` — config `grades` 맵 + `allowNegative`(± 접두) 해석. 미존재 시 null.
     (기존 `FormulaEvaluator.resolveGradeValue`/`StatsDataProvider.resolveGradeValueForRanking`의 공통 로직을 이관)
   - `DEFAULT_GRADE_VALUES` — 성장 차트의 기존 SSS~F 기본맵을 상수로 이관(합리적 기본값).
   - `resolveForDisplay(fieldDef?, label): Double?` — config 우선 → 기본맵 폴백.
2. `FormulaEvaluator.resolveGradeValue` → `resolveFromConfig(...) ?: 0.0` (기존 동작 정확 보존 — 미존재=0.0).
3. `StatsDataProvider.resolveGradeValueForRanking` → `resolveFromConfig(...)` 위임 (null 시맨틱 보존).
4. `CharacterGrowthFragment`:
   - 필드 정의 맵(`key → FieldDefinition`)을 로드 시 보관, `parseNumericValue`가 `resolveForDisplay(fieldDef, value) ?: 0f` 사용.
   - 숫자 직접 파싱 우선은 유지(NUMBER 겸용). 라벨 미해석 시 0f 폴백은 기존 동작 유지(변경 범위 최소화).
   - 부수 수정: 데이터셋 라벨의 `fieldKey` → 필드 이름 사용(가독성, 기존 이름 부재 시 key 폴백).

**리스크:** FormulaEvaluator 위임 시 동작 편차 — "grades 맵 부재→0.0 / 라벨 미존재→0.0 / ±접두 처리" 3케이스를 위임식에서 동일 보존(검증 항목).
**검증:** 3 호출부의 기존/신규 반환값 케이스 표 대조(커스텀 등급, 표준 등급, 미존재 라벨, 음수 접두).

## F-5. 세력 자동관계 "수동 우선" 정책 명문화 + 통보 [문-5 · 기준 ②]

**문제:** 동일 (c1,c2,type) 수동 관계가 있으면 자동관계 insert가 IGNORE로 조용히 실패 — 사용자는 모르고, 탈퇴 시 해당 쌍 관계가 남는다.

**정책 결정(명문화):** **수동 관계 우선.** 세력 가입이 사용자가 만든 관계를 가로채거나(탈퇴 시 삭제 위험) 덮어쓰지 않는다.
자동관계는 "빈 자리에만" 생성하고, 건너뛴 사실을 **집계·통보**한다. (기존 관계에 factionId를 부착하는 대안은
탈퇴 시 수동 관계가 삭제되는 조용한 데이터 유실 경로를 만들므로 기각 — 변수 제어 원칙 정면 위반.)

**설계:**
1. `FactionRepository`에 결과 타입 신설: `data class MemberAddResult(val added: Int, val autoRelationsCreated: Int, val autoRelationsSkipped: Int)`.
2. `addMember`/`addMembers`가 `insertAll` 반환 `List<Long>`의 -1 개수를 세어 skipped 집계 후 `MemberAddResult` 반환.
   - `addMembers` 부수 개선: 매 반복 `getActiveMembershipsByFaction` 재조회 → 최초 1회 조회 + 로컬 누적 집합으로 교체(O(N²) 쿼리 제거, 관계 생성 쌍은 동일).
3. `FactionViewModel.addMembers(onResult: (MemberAddResult) -> Unit)` 로 전달, `addMember`도 결과 반환형으로 통일.
4. `FactionManageFragment`: skipped > 0이면 토스트 확장 — "멤버 N명 추가됨. 관계 M건은 이미 수동 관계가 있어
   자동 관계를 만들지 않았습니다(수동 관계 우선)." 문자열 리소스로 추가.

**리스크:** 시그니처 변경 — 호출부는 ViewModel 2곳 + Fragment 1곳뿐임을 확인(전수 수정).
**검증:** 수동 관계 선존재→가입→탈퇴 시나리오에서 (a) 수동 관계 보존 (b) 통보 발생 (c) 자동관계만 삭제되는 경로 추적.

## F-6. 삭제 고지 사실화 — 휴지통 안내 [문-6 · 기준 ②]

**문제:** 캐릭터 삭제 고지가 B-7(휴지통) 이전 사실("영구 삭제", "이미지 즉시 삭제")을 말한다. stale 주석 포함.

**설계 (문구·고지 정합화 — 동작 변경 없음):**
1. `strings.xml`:
   - `delete_impact_header`: "함께 삭제되는 항목:" (영구 문구 제거)
   - `delete_impact_images`: "· 이미지 %1$d장 (휴지통 정리 시 파일 삭제)"
   - 신설 `delete_trash_notice`: "삭제 후 30일간 휴지통(설정 > 데이터 관리 > 휴지통)에서 복원할 수 있습니다.
     휴지통은 최대 30개까지 보관되며, 초과 시 오래된 항목부터 영구 삭제됩니다."
2. `CharacterListFragment`: 다이얼로그 메시지 말미에 `delete_trash_notice` 추가, stale 주석
   ("undo가 없으므로…") → "휴지통 복원 가능 — 고지는 실제 동작(스냅샷 보관)과 일치시킨다"로 교체.
3. **일괄 삭제 경로**(BatchOperationBottomSheet → deleteSelected)의 확인 다이얼로그에도 동일 고지 추가.
4. 세계관 삭제 고지(`delete_impact_universe`)는 실제로 영구 삭제이므로 **그대로 유지** (개-1 휴지통 확대 시 함께 갱신).

**리스크:** 없음(문구 정합화). **검증:** 캐릭터 단건/일괄/세계관 3경로의 고지 문구가 실제 동작과 1:1 대응하는지 대조.

## F-7. 통계 집계 정합성 교정 [문-7 · 원칙 02]

**문제:** (a) 교차분석이 캐릭터 1명의 중복 값 쌍을 중복 집계(데카르트), (b) 요약 TOP5가 콤마 파싱 미경유 원문 집계로
필드 인사이트와 수치 불일치.

**설계:**
1. **교차분석**: 캐릭터당 값 쌍을 `distinct()` 처리 — 셀 값의 의미를 "해당 조합을 가진 **캐릭터 수**"로 확정
   (동일 캐릭터가 같은 조합에 2회 이상 기여하는 중복 제거). 다중값 필드로 인해 한 캐릭터가 **서로 다른** 여러 칸에
   기여하는 것은 다중값의 본질이므로 유지하되, `CrossAnalysisResult.multiValue: Boolean` 필드를 추가하고
   UI(`showCrossAnalysisResult`)에 캡션 노출: "다중값 필드: 한 캐릭터가 여러 칸에 집계될 수 있습니다."
2. **요약 TOP5**: `getFieldValues`(콤마/구조화/라벨/카테고리 파싱) 경유로 교체 — 필드 인사이트와 동일 규칙·동일 수치.
   `FieldStatsConfig.enabled` 필터도 동일 적용(통계 제외 필드는 요약에서도 제외 — 규칙 일원화).
   config 파싱은 필드당 1회 캐시(값 행마다 JSON 파싱 방지).

**리스크:** 기존 사용자에게 숫자가 달라져 보임 — 그것이 교정의 목적이며, 달라지는 방향이 "정확한 값". 캡션으로 해석 기준을 명시.
**검증:** 다중값 2필드 교차 케이스(태그 2개 × 소속 1개 캐릭터)의 셀 합 추적, 요약 TOP5와 필드 인사이트 분포 상위값 일치 확인.

---

## 구현 순서와 커밋 단위

1. F-1 + F-6 (소규모·독립) → 2. F-4 → 3. F-5 → 4. F-7 → 5. F-3 → 6. F-2 (최대 규모: 마이그레이션+엑셀 왕복)
— 단일 브랜치에서 항목별 검증 후 일괄 커밋. 로컬 빌드 불가 환경(Android SDK 부재)이므로 정적 검증(호출부 전수 추적) + PR CI 빌드로 확인한다.

## 구현 메모 (2026-07-04) — F-1~F-7 전체 구현 완료, DB v34→v35

- **F-1**: `SheetSpec.EXCEL_CELL_TEXT_LIMIT`(32,767) 신설, `ExcelExporter.XLSX_CELL_LIMIT`·`ExcelImportService.MAX_FIELD_LENGTH`가 참조. 안내 시트 문구 갱신("가져오기도 동일하게 32,767자").
- **F-2**: 세 엔티티에 `code: String? = generateEntityCode()` + 유니크 인덱스, `MIGRATION_34_35`(컬럼 추가→커서 백필→유니크 인덱스). DAO `getEventByCode`/`getChangeByCode`×2. 시트: 연표·상태변화에 "코드" 추가, 관계변화는 "연결사건ID"→"연결사건코드"(+자체 "코드") — 가져오기는 ID 컬럼도 계속 인식. 임포트 3종 모두 코드 우선 매칭(코드 매칭 시 자연키 구성 요소도 갱신), 코드 없는 기존 행 점진 백필, 파일 내 코드 중복 경고(`codesSeen` 규약), analyze 3종 동일 매칭으로 신규/갱신/변경없음 재분류. **추가 방어**: 휴지통 복원 시 상태변화·관계변화 code 충돌 재발급(캐릭터 code와 동일 규칙 — 복원 전 임포트로 동일 코드 재생성 시 복원 실패 방지). 월드패키지는 내보내기 전용이라 영향 없음 확인.
- **F-3**: `StatsSnapshot.eventFieldDefinitions/eventFieldValues` + 로드/작품필터. `computeFieldDistribution`/`computeNumericAnalysis`를 `List<String>` 기반으로 제네릭화(확-3 기반), 공용 `buildFieldInsight` 추출, `computeAllEventCalculatedValues` 신설. UI: 사건 필드 헤더 "사건 · " 프리픽스 + 모수 단위 "%d건 (사건)" 분리 표기. 인라인 분석 설정은 entityType 무관 동작 확인.
- **F-4**: `util/GradeValueResolver` 신설(`resolveFromConfig`/`resolveForDisplay`/`DEFAULT_GRADE_VALUES`). FormulaEvaluator(미정의=0.0 시맨틱 보존)·StatsDataProvider(null 시맨틱 보존)·성장 차트(config 우선→기본맵 폴백) 3곳 위임. 성장 차트 라벨도 필드 key→이름 표기.
- **F-5**: `MemberAddResult(added, autoRelationsCreated, autoRelationsSkipped)` 반환, `insertAll`의 -1 집계. `addMembers` 활성 멤버 로컬 누적으로 반복 재조회 제거. Fragment 토스트에 "수동 관계 우선" 스킵 통보(`faction_auto_relation_skipped`).
- **F-6**: `delete_impact_header`("함께 삭제되는 항목"), `delete_impact_images`(휴지통 정리 시 삭제), `delete_trash_notice` 신설(30일/30개 한도 명시), 단건·일괄 다이얼로그 반영, stale 주석 교체. 세계관 삭제 고지는 실제로 영구라 유지.
- **F-7**: 교차분석 캐릭터당 값 쌍 `distinct()` + `CrossAnalysisResult.multiValue` + UI 캡션(`stats_cross_multi_value_note`). 요약 TOP5는 `getFieldValues` 파싱 + `enabled` 필터 + config 캐시로 필드 인사이트와 수치 일원화.
- **검증**: 세 엔티티 생성자 호출부 전수(named args, 기본값 안전) 확인, MemberAddResult 호출부 3곳 전수 수정, 스탯 헬퍼 시그니처 변경 호출부 2곳 확인, Room 인덱스명(`index_<table>_code`) 엔티티-마이그레이션 일치 확인. 빌드는 PR CI로 확인.
- **후속(이번 범위 외)**: 사건 필드의 교차분석 축 편입, 연표 카드 필드값 표시, 개-1~7·확-1~7.

## 문서 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|-----------|
| v1.0 | 2026.07.04 | 초기 설계 — F-1~F-7 (점검 보고서 문-1~문-7 대응), 적대적 검증 확정 사항 반영 |
| v1.1 | 2026.07.04 | F-1~F-7 전체 구현 완료 (DB v35), 구현 메모 추가 |
