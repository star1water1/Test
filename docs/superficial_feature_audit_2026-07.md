# 구색만 갖춘 기능 전수 색출 및 개혁안 (2026-07)

> **목적:** "있어 보이지만 실제로는 없는" 기능 — 조용한 실패, 죽은 코드, 미배선, 껍데기 구현,
> 약속 불이행 — 을 전수 색출하고, 확정 건마다 개혁안을 설계한다.
> **방법:** 서브시스템별 8방향 병렬 수색 → 발견별 적대적 반증 검증(코드 실독 필수) →
> 검증 미귀환은 기각이 아니라 생존으로 집계(선행 세션 규약).
> **선행 문서:** `remaining_work_2026-07.md` 1-6장(이번 세션의 B-6·B-7·러너 수리),
> `app_inspection_round2_2026-07.md`.
> 이 문서의 항목 번호는 **S-n**(superficial)이다. 기존 백로그(B-n)와 겹치는 것은 병기한다.

---

## 0. 요약

- **수색 규모:** 8개 서브시스템(통계/엑셀·월드패키지/휴지통/연표·사건/필드/AI·이름은행/
  이미지·관계도/UI 전반), 원시 79건 → 중복 제거 75건.
- **이번 세션에서 즉시 수리한 것(4건):** S-1(검증 러너 파손), S-2(소스 제어문자),
  S-3(사건·상태변화 편집의 code 재발급), S-4(휴지통 편집 백업 무한 축적) — 1장 참조.
- **적대적 검증 결과:** 상위 18건 검증 → **확정 15건**(2장, 항목별 개혁안), 기각 3건(3장 —
  전부 "세션 중 이미 수리됨" 판정, 거짓 발견 0건). 나머지 57건은 미검증 잔류(5장).
- **개혁 우선순위 로드맵:** 4장 — 확정 15건을 7개 작업 묶음으로 재편성했다.

---

## 1. 이번 세션에서 즉시 수리한 것

### S-1. master의 검증 러너가 깨져 있었다 — 기능이 아니라 **검증 체계가 구색**이었다

PR #88/#89가 순수 JVM 테스트 13개와 `StatsDataProvider`→`UnassignedFilter` 의존을 추가하면서
`tools/run_jvm_tests.sh`를 갱신하지 않았다. 결과: **master에서 하네스가 컴파일조차 안 됐고**,
"손대기 전에 297건부터 재현하라"는 인수인계가 재현 불가였으며, PR #88의 신규 테스트 150건은
한 번도 하네스에서 실행된 적이 없는 **구색 테스트**였다.

**수리(완료):** 러너에 소스 15개·테스트 13개 편입, `FieldValueRules` 분리(순수 판정을
하네스 대상으로), `AiServiceStub` 신설. 기준선 297건 → **464건**.
**재발 방지 규약:** 테스트/순수 소스 의존을 추가하면 러너 목록 갱신까지가 한 작업이다.
러너가 컴파일에 실패하면 그 커밋은 검증되지 않은 것이다.

### S-2. 소스 파일의 인쇄 불가 제어문자 — 텍스트 도구 전체가 눈을 감는다

`AiSettingsFragment.kt`에 리터럴 NUL 바이트(`"$baseUrl<NUL>$key"`)가 박혀 있어 grep이
파일을 binary로 취급했다 — 리소스 점검(`check_resources.sh`)의 미정의 문자열 검사가 이
파일을 통째로 건너뛰었고, 모든 코드 수색·감사가 이 파일에 맹점을 가졌다.

**수리(완료):** `\u0000` 이스케이프로 교체(런타임 문자열 동일). 같은 부류로, 이번 세션 도구가
만든 `\x01` 구분자도 `:`(엑셀 시트명 금지 문자)로 교체했다.
**규약:** 소스에 인쇄 불가 문자를 넣지 말 것 — 구분자가 필요하면 이스케이프나 도메인 금지
문자를 쓴다.

### S-3. 사건·상태변화를 **편집할 때마다 안정 식별자(code)가 재발급**됐다 [R-1 파괴]

`EventEditDialogFragment`와 `StateChangeHelper`가 편집 저장 시 엔티티를 새로 구성하면서
`code`를 넘기지 않아 기본값 `generateEntityCode()`가 매번 실행됐고, Room `@Update`가 전
컬럼을 쓰므로 **편집 한 번에 code가 통째로 바뀌었다.** 결과:

- 엑셀 내보내기 → 앱에서 사건 연도 하나 수정 → 재가져오기 시 code도 자연키도 안 맞아
  **같은 사건이 중복 생성**된다(코드 열이 막겠다고 명시한 바로 그 결함).
- 캐릭터 삭제 → 연결 사건 편집 → 캐릭터 복원 시 사건 연계가 code 조회 실패로 유실된다.
- 상태변화의 '이미 있음' 중복 차단(ALREADY_EXISTS)이 편집된 행을 못 알아본다.

다른 구성 지점(TimelineViewModel·CharacterViewModel의 `existing.copy(...)`,
QuickAdd·BatchEdit의 신규 생성)은 전수 확인 결과 정상이었다 — 깨진 곳은 이 두 파일뿐.

**수리(완료):** 두 곳 모두 `code = 기존?.code ?: generateEntityCode()` — 편집은 정체성을
보존하고, 구버전 무코드 행(B-25)은 편집 시점에 1회 부여된다(종전에도 편집마다 새 code가
박혔으므로 회귀가 아니라 안정화다).
**규약:** **편집 경로는 엔티티를 새로 만들지 말고 `copy`로 시작할 것.** 새 구성이 불가피하면
정체성 컬럼(id·code·createdAt)을 전부 명시적으로 이어받는지 리뷰에서 확인한다.

### S-4. 휴지통 자동 정리가 **편집 직전 백업을 영원히 지우지 못했다** — 무한 축적

`pruneIfNeeded`는 모든 작업을 세지만(`getOperationsOldestFirst` — 종류 무구분),
`purgeOperation(key)`의 기본 `editBackup=false`가 **삭제 갈래 행만** 지운다
(`getImagesByOperation`의 종류 필터 — R-12의 산물). 결과: 순수 편집 백업 작업(작품 이동·
값 라이브러리 삭제·되돌리기 직전 백업)은 기한·한도 정리에서 **뽑히고도 0행이 지워져**
영원히 남았다 — 행·payload·보류 이미지가 무한 축적되고, 한도 계산까지 왜곡하며,
보관 안내 문구("최근 30건, 30일")는 거짓 약속이었다.

**수리(완료):** 정리 계획을 **(작업, 종류) 단위**로 승격 — `TrashPruneSelector.plan`이
종류별 독립 풀(각 30건)로 기한·한도를 판정하고 purge에 종류를 함께 전달한다. 표시
(`TrashGrouping`)·영구 삭제와 같은 축이다. 편집 백업 폭주가 삭제 백업을 밀어내지 않고,
그 반대도 성립한다. 순수 JVM 테스트 4건으로 고정. 보관 안내 문구도 실규칙에 맞게 갱신.
**규약:** **같은 축으로 묶고(표시), 거르고(삭제), 세어야(정리) 한다.** 한 축이라도 어긋나면
"뽑히고도 지워지지 않는" 유령 작업이 생긴다.

---

## 2. 확정 발견과 개혁안 (검증 통과 15건)

> 검증자 판정 심각도 기준 정렬. 개혁안(fix_sketch)은 검증자가 코드를 실독하고 설계한 수리 방향이다.

### ~~S-5~~. 월드패키지(.ncworld)는 내보내기만 존재 — 가져오기 경로가 앱 어디에도 없는 일방통행 기능 — **처리 완료 (remaining_work 1-8장)**
- **위치:** `app/src/main/java/com/novelcharacter/app/share/WorldPackageExporter.kt:81` · **분류:** 약속 불이행 · **심각도:** 상
- **증상:** 세계관 패키지를 공유받은 사용자가 그 파일을 열 방법이 전혀 없다. 설정의 가져오기에서 .ncworld를 고르면(파일 선택기가 application/zip을 허용하므로 선택은 됨) data.xlsx가 없어 xlsx로 오판 → POI 파싱 실패 → "가져오기에 실패했습니다" 일반 오류 토스트만 뜬다. '배포' 기능인데 수신 측이 없어 기능 전체가 구색이다.
- **개혁안:** WorldPackageImporter를 신설해 manifest.json의 schemaVersion(1/2)을 검증하고 exporter가 쓰는 전체 엔트리(universe/fields/characters/…/faction_relationships)를 역직렬화·트랜잭션 삽입하도록 구현한다. 가져오기 진입부의 ZIP 판별을 3분기(data.xlsx 엔트리→엑셀 ZIP, manifest.json 엔트리→월드패키지, 그 외→xlsx 시도)로 확장해 기존 백업 복원 경로와 충돌 없이 라우팅한다. 이름/ID 충돌은 이미 존재하는 죽은 문자열(share_world_conflict_title/overwrite/skip/new)을 살려 덮어쓰기·건너뛰기·새로 생성 선택 다이얼로그로 처리하고(변수 제어: 검증→알림→교정), v1 패키지는 exporter KDoc대로 "세력 간 관계 없음"을 구버전 형식으로 안내한다. 형식 인식 실패 시에도 일반 토스트가 아니라 "지원하지 않는 패키지 형식/손상됨" 등 원인별 메시지를 제공해 무통보 실패를 없앤다.

### ~~S-6~~. 사건 편집 다이얼로그가 작품 미선택/작품 소실 시 사건 필드값 전체를 무통보 삭제한다 (N2의 사건판, R-5 미적용) — **처리 완료 (remaining_work 1-7장)**
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/timeline/EventEditDialogFragment.kt:493` · **분류:** 조용한 실패 · **심각도:** 상
- **증상:** 작품이 삭제돼 연결이 끊긴(또는 다이얼로그에서 작품 체크를 해제한) 사건을 열어 설명만 고치고 저장하면, 채워 둔 회차·챕터 등 사건 필드값이 전부 소리 없이 사라진다. 필드 로딩이 끝나기 전에 빠르게 저장해도 동일하다.
- **개혁안:** 캐릭터 경로의 R-5 커버 집합을 사건 경로에 그대로 이식한다: DataProvider.updateEvent/insertEvent/updateEventAndShiftOthers에 '폼이 실제 렌더한 필드 정의 id 집합'을 함께 넘기고, TimelineViewModel이 replaceAllByEvent 대신 커버 집합 기준 병합(기존 EventFieldValueDao.replaceForFields 재사용)으로 커버 밖 기존 값을 보존한다. 세계관 미해결(universeId==null)·필드 섹션 로딩 미완이면 커버 집합을 공집합으로 넘겨 전량 보존하고, 보존 건수가 0보다 크면 CharacterSaveCoordinator.notifyPreservedFieldValues와 동일한 "N개 필드값을 보관했습니다" 고지를 띄운다(검증→알림→교정). 추가로 rebuildEventFieldSection의 세계관 해석에 editingEvent?.universeId 폴백을 두어 작품 연결이 끊긴 사건도 기존 필드값을 화면에 드러내 편집할 수 있게 한다(존재를 알 수 없는 데이터 금지, 원칙 04).

### ~~S-7~~. 다세계관 통합 인사이트 카드의 차트 탭이 대표 필드 1개만 조회해 캐릭터 목록이 과소집계된다 — **처리 완료 (remaining_work 1-9장)**
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/stats/StatsFieldInsightFragment.kt:237` · **분류:** 조용한 실패 · **심각도:** 상
- **증상:** 전체 세계관 보기에서 '직업' 카드가 세계관 A+B 합산으로 '검사 15명'을 그리는데, 그 조각을 탭하면 첫 세계관 필드의 캐릭터(예: 7명)만 나열된다. 어긋난 수치에 대한 고지가 없고, 이 목록을 모수로 쓰는 하위 그룹 분석까지 연쇄로 과소집계된다.
- **개혁안:** FieldInsightResult에 mergedFieldDefIds: List<Long>를 추가하고 computeFieldInsights/buildFieldInsight에서 fds.map{it.id}로 채운 뒤(단일 세계관도 원소 1개 리스트로 통일), StatsFieldInsightFragment의 탭 리스너와 StatsCharacterListBottomSheet 인자를 long[] 병합 id로 교체한다. 바텀시트는 getCharactersByFieldValue 대신 기존 getCharactersByFieldKeyValues(첫 def config로 파싱해 차트 값 공간과 일치)를 호출하는 ViewModel 경로를 신설해 차트 조각 수치와 목록 인원이 항상 일치하게 한다. 하위 그룹 분석의 현재 필드 제외(77행)도 병합 id 집합 기준으로 바꿔 형제 세계관 def가 대상에 섞이지 않게 하고, 만약 파싱 config 차이로 수치가 어긋날 수 있는 경우에는 목록 상단에 고지를 표시한다(변수 제어: 검증→알림→교정). 단일 id 경로를 남겨 특정 케이스만 때우는 방편식 분기는 두지 않는다.

### ~~S-8~~. 캐릭터 축 교차분석이 CALCULATED 필드 계산값을 합치지 않아 빈 표가 무통보로 나온다 — **처리 완료 (remaining_work 1-9장)**
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/stats/StatsDataProvider.kt:1463` · **분류:** 조용한 실패 · **심각도:** 중
- **증상:** 인사이트 목록에는 분포가 그려지는 CALCULATED 필드(예: 수식으로 계산한 '전투력')를 교차분석 축으로 고르고 실행하면 제목·캡션만 있는 빈 표가 나오고 아무 안내도 없다. B-4('사건 필드를 고르면 아무 일도 안 일어남')와 동일 부류의 잔존 구멍 — 사건 축만 고쳐지고 캐릭터 축의 CALCULATED는 남았다.
- **개혁안:** 근본 원인을 사건 축과 대칭으로 수리한다: computeCrossAnalysis에서 storedRows = s.fieldValues.groupBy(...) 뒤 mergeCalculatedRows(storedRows, computeAllCalculatedValues(s))를 rowsByEntity로 사용해 CALCULATED 계산값이 축·필터 어디에 쓰여도 집계되게 한다(이미 있는 mergeCalculatedRows/computeAllCalculatedValues 재사용, 한 축만 고치는 방편식 패치 금지). 방어선으로 showCrossAnalysisResult/renderCrossTable의 빈 crossTable 무통보 return을 "조건에 맞는 데이터 없음" 고지로 바꿔 진짜 데이터가 없는 경우도 변수 제어(검증→알림) 원칙대로 사용자에게 알린다. JVM 테스트에 캐릭터 축 CALCULATED 필드(축1·축2·필터 각각) 교차분석 케이스를 추가해 회귀를 막는다.

### ~~S-9~~. 사건 필드 인사이트 차트를 탭하면 항상 빈 캐릭터 목록 시트가 뜬다 (사건 드릴다운 미구현인데 탭 리스너는 부착) — **처리 완료 (remaining_work 1-9장)**
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/stats/StatsFieldInsightFragment.kt:244` · **분류:** 조용한 실패 · **심각도:** 중
- **증상:** 사건 커스텀 필드 카드(B-10으로 편입)의 파이/막대 조각을 탭하면 — 차트에는 사건 12건이라 그려져 있어도 — '필드명: 값' 시트가 열리고 0명·빈 목록이 나온다. 해당 값을 가진 사건을 보여주는 경로 자체가 없는데 탭 인터랙션은 살아 있어, 기능이 있는 것처럼 보이고 실제로는 아무것도 돌려주지 않는다.
- **개혁안:** 사건 드릴다운을 캐릭터 드릴다운과 대칭으로 완결 구현한다(방편식 패치 금지 — 리스너만 떼는 땜빵 불가): StatsDataProvider에 s.eventFieldDefinitions/s.eventFieldValues(+computeAllEventCalculatedValues)를 조회하는 getEventsByFieldValue를 추가하고, 시트를 엔티티 인지형으로 일반화(또는 사건용 시트 신설)해 탭한 값을 가진 사건 목록을 보여주고 사건 상세로 내비게이트한다. attachChartTapListener에 insight.fieldDefinition.entityType을 전달해 경로를 분기하고, 사건 축에서는 하위 그룹 분석 버튼도 사건 필드 목록으로 채운다. 변수 제어 원칙에 따라 getCharactersByFieldValue의 `?: return emptyList()` 폴백도 제거한다 — 대상 필드 정의를 찾지 못하면 빈 목록으로 위장하지 말고 오류를 상위로 알려 사용자에게 표시한다.

### S-10. StreamingXlsxReader 미배선 — 완성된 스트리밍 리더가 죽은 코드로 방치 (기지 백로그 B-8)
- **위치:** `app/src/main/java/com/novelcharacter/app/excel/StreamingXlsxReader.kt:30` · **분류:** 죽은 코드/미배선 · **심각도:** 중 · **기지 백로그:** B-8
- **증상:** 128MB 초과 백업 xlsx는 앱이 만든 파일인데도 복원이 거부되고, 한도 내 대형 파일도 기기 메모리에 따라 OOM으로 실패할 수 있다. 이를 해결하려고 만든(테스트까지 갖춘) 스트리밍 리더는 어떤 경로에서도 호출되지 않는다. 이미 알려진 백로그 B-8 재확인.
- **개혁안:** docs/excel_streaming_import_2026-07.md의 원칙대로 importFromXlsx에 크기 임계값(예: 기존 MAX_IMPORT_FILE_SIZE 또는 가용 힙 기반)을 두고, 초과 시 DOM 대신 StreamingXlsxReader 경로로 전환하되 per-row 해석 로직은 두 경로가 동일 코드를 타게 한다(이중 경로 + 비분기 — 방편식 패치 금지). 스트리밍 경로가 배선되면 :107/:153/:212의 128MB 거부를 스트리밍 처리로 대체해 앱 자신의 백업은 크기와 무관하게 복원되게 하고(:1141 주석의 왕복 무결성 선언 이행), 손상·형식 이탈 행은 조용히 버리지 말고 기존 결과 다이얼로그(buildResultMessage)에 건수·교정 경로로 고지한다(변수 제어: 검증→알림→교정). 기존 StreamingXlsxReaderTest의 DOM 동치 대조를 임포트 파이프라인 수준 통합 테스트로 확장해 두 경로 결과가 행 단위로 일치함을 강제한다.

### S-11. CALCULATED 수식 오류 신호(NaN)가 모든 표시 경로에서 소멸 — 오류와 미입력이 구분 불가
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/character/DynamicFieldRenderer.kt:591` · **분류:** 조용한 실패 · **심각도:** 중
- **증상:** 수식에 자기참조/순환 참조나 오타(fied(...), 0나눗셈)를 넣고 저장하면 아무 경고 없이 저장되고, 캐릭터 상세에는 '필드명 (자동 계산)'만 값 없이 표시된다. 입력값이 아직 없어서 빈 것인지 수식이 깨진 것인지 사용자는 알 길이 없고, 엑셀 내보내기에서도 빈 셀이 되어 오류가 어디에서도 드러나지 않는다. B-4(교차분석 null 무시)와 같은 부류.
- **개혁안:** 1) NaN/Infinite를 오류 신호로 소비자까지 관통시킨다: 계산 결과를 sealed 타입(성공값/오류사유)으로 반환하는 공용 헬퍼로 각 소비자의 중복 평가 코드를 통합하고, DynamicFieldRenderer·CharacterDetailFragment는 "필드명: 수식 오류 (자동 계산)"처럼 TimeStateResolver.FORMULA_ERROR_DISPLAY와 일관된 오류 표기를 렌더링한다(방편식 패치 금지 — 경로별 개별 땜빵 대신 단일 소스). 2) 엑셀/PDF 내보내기는 빈 셀 대신 오류 마커(예: "#수식오류")를 기록하거나 내보내기 완료 시 오류 필드 개수를 사용자에게 통지한다(엑셀 왕복 무결성). 3) FieldEditDialog.validateFormula에 자기참조·순환 참조 검사(currentKey 직접 참조 + CALCULATED 간 전이 참조)와 미지 함수명 검출을 추가해 저장 시점에 경고한다(검증→알림→교정, 기존 '그대로 저장' 선택지는 유지해 자율성 보존).

### ~~S-12~~. 관계 변화 추가·편집 다이얼로그 — 연도 미입력 시 저장 탭이 전체 입력을 무통보 폐기 — **처리 완료 (remaining_work 1-7장)**
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/character/RelationshipHelper.kt:413` · **분류:** 조용한 실패 · **심각도:** 중
- **증상:** 관계 변화 추가에서 유형·설명·강도·사건 연결까지 채우고 연도만 비운 채 저장을 누르면 다이얼로그가 닫히고 아무 일도 일어나지 않는다. 입력 전체가 알림 없이 사라진다 — B-4 전례(고르면 조용히 아무 일도 안 일어남)와 같은 부류이며 CLAUDE.md 변수 제어(검증→알림→교정 경로) 직접 위반.
- **개혁안:** Override the positive button's default auto-dismiss: build the dialog, call show(), then rebind dialog.getButton(BUTTON_POSITIVE).setOnClickListener. In that listener, validate the year; on failure set an inline error on the year field's TextInputLayout (e.g. "연도를 입력하세요") and keep the dialog open so the user can correct it (검증→알림→교정), clearing the error on text change; only on success run the existing insert/update logic and dismiss(). Apply the same complete fix to both showAddRelationshipChangeDialog (line 413) and showEditRelationshipChangeDialog (line 487) — patching only one would be a 방편식 패치.

### S-13. 앱 초기화가 휴지통 스냅샷·작업 이력·캐릭터탭 프리셋을 남기고, 휴지통이 보류 중인 이미지 파일만 삭제한다
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/settings/SettingsFragment.kt:1028` · **분류:** 약속 불이행 · **심각도:** 중
- **증상:** 초기화 후에도 (1) 설정>휴지통에 과거 삭제 항목이 그대로 남아 '복원' 가능해 보이고, 복원하면 이미지 파일은 이미 지워져 깨진 imagePaths를 단 캐릭터가 부활한다(거짓 예고+부분 파손). (2) 작업 이력 화면에 초기화 이전 기록 전부 잔존. (3) 캐릭터 탭 프리셋 칩이 초기화 후에도 그대로 남는다(검색 프리셋은 지워져 비대칭). '모든 데이터 삭제·되돌릴 수 없음' 약속과 셋 다 어긋난다.
- **개혁안:** executeReset()의 db.withTransaction 블록(SettingsFragment.kt:1028-1045)에 db.trashSnapshotDao().deleteAll(), db.operationLogDao().clear(), db.characterListPresetDao().deleteAll()을 추가해 '모든 데이터 삭제' 약속과 DB 상태를 일치시킨다 — 스냅샷 행을 먼저 지우므로 1069-1071행의 파일 일괄 삭제는 규약(스냅샷 생존 중 파일 유지)과 더는 충돌하지 않는다. 방편식 패치 금지 원칙에 따라 세 개만 덧붙이지 말고 AppDatabase의 전체 DAO 목록을 리셋 삭제 목록과 대조 감사해 FK CASCADE로 비워지지 않는 독립 테이블(예: imageMeta 등)이 더 누락됐는지 확인하고, 누락 재발을 막기 위해 '리셋이 모든 테이블을 비운다'를 검증하는 JVM 테스트(테이블 목록 열거 대 삭제 호출 대조)를 추가한다. 확인 다이얼로그 문구(strings.xml:591)는 그대로 두되, 백업 보존 선택처럼 휴지통·작업 이력 보존을 의도적으로 허용하려면 명시적 체크박스로 사용자에게 알리고 선택하게 한다(변수 제어: 검증→알림→교정).

### S-14. 패턴 인사이트가 '통계에 포함' 꺼진 필드도 분석한다 — 필드 통계 설정 무시
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/stats/StatsDataProvider.kt:2005` · **분류:** 약속 불이행 · **심각도:** 중
- **증상:** 사용자가 특정 필드(예: 민감한 메모성 필드)를 '통계에 포함' 해제해도 통계 메인의 패턴 인사이트에 '○○: △△ 편중' 카드가 계속 뜨고, 작품 간 편중 비교와 어시스턴트 인사이트에도 그 필드가 나온다. 설정 스위치가 약속한 동작(통계 제외)이 인사이트 목록에만 적용되고 패턴 감지에는 적용되지 않는다.
- **개혁안:** detectPatterns의 필드 그룹화(StatsDataProvider.kt:2005)를 computeFieldInsights(1168-1169)와 동일하게 `s.fieldDefinitions.filter { FieldStatsConfig.fromConfig(it.config).enabled }` 이후 groupBy 하도록 고친다. 이 한 곳을 고치면 필드별 편중/균형/이상치 패턴(2007-2091)과 작품 간 필드 편중 비교(2154-2181), AssistantEngine 경유 호출까지 같은 함수라 일괄 해결되어 방편식 패치가 아닌 완결 수정이 된다. config 파싱이 def당 2회가 되지 않도록 computeSummary(665)의 statsConfigCache 패턴처럼 파싱 결과를 재사용하고, 화면 간 수치 일치 원칙(663행 주석)에 맞춰 기존 JVM 테스트에 '통계 제외 필드는 패턴 인사이트에 나타나지 않는다' 케이스를 추가해 회귀를 막는다.

### S-15. 레거시 '필드 분석' 화면이 CALCULATED 필드를 통째로 누락하고 '통계에 포함' 설정도 무시한다
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/stats/StatsDataProvider.kt:1734` · **분류:** 껍데기 구현 · **심각도:** 중
- **증상:** 필드 분석 상세 화면에서 수식 필드(CALCULATED)는 값이 계산돼 인사이트 화면·순위에는 나오는데 여기서만 아무 항목도 없이 조용히 빠진다. 반대로 통계 제외한 필드는 계속 표시된다. '모든 필드가 통계에서 분석 가능해야 한다'(원칙 02)와 사용자 설정 양쪽에 어긋난다.
- **개혁안:** computeFieldAnalysis를 computeFieldInsights와 동일한 데이터 준비 규칙으로 통일한다: (a) computeAllCalculatedValues(s) 결과를 합성 CharacterFieldValue로 valuesByFieldDef에 합산(1151-1165행 방식 재사용을 공용 헬퍼로 추출), (b) 네 분기(이산 분포·BODY_SIZE 분포·NUMBER 요약·BODY_SIZE 요약) 진입 전에 FieldStatsConfig.fromConfig(fd.config).enabled 필터를 일괄 적용, (c) CALCULATED는 FieldStatsConfig.StatsType.forFieldType("CALCULATED")(분포+수치+순위)에 맞춰 NUMBER와 동일한 수치 요약 및 binning 기반 분포에 편입한다. 레거시·신규 두 경로가 같은 필드 적격성 판정을 공유하도록 헬퍼를 단일화해야 방편식 패치 금지 원칙에 부합하며, 향후 두 화면의 재분기(divergence)를 구조적으로 차단한다.

### S-16. 레거시 필드 분석의 BODY_SIZE 분포 조각 탭이 항상 0명을 돌려준다 (구간 라벨 vs 파싱값 불일치)
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/stats/StatsFieldAnalysisDetailFragment.kt:161` · **분류:** 조용한 실패 · **심각도:** 중
- **증상:** 필드 분석 화면에서 신체 사이즈 분포(예: '키 — 파트1'의 '160~170' 조각)를 탭하면 캐릭터 목록 시트가 항상 '0명'으로 뜬다. 그 구간에 실제로 캐릭터가 있어도 마찬가지 — 탭 인터랙션이 존재하지만 어떤 입력에서도 결과를 내지 못한다.
- **개혁안:** 문자열 라벨을 매칭 키로 재사용하는 대신, 분포 생성 시점의 구조 정보(partIdx, binMin, binMax, separator)를 FieldValueDistribution에 함께 담아 탭 시 그대로 전달하고, getCharactersByFieldValue에 타입드 매치 스펙(정확값 매치 vs 수치 범위+파트 인덱스 매치)을 추가한다. 범위 매치는 분포 계산과 동일한 파싱(separator split → partIdx → toFloatOrNull)과 동일한 경계 규칙(마지막 bin만 상한 포함)을 단일 소스로 공유해 분포 인원수와 드릴다운 인원수가 항상 일치하게 한다. NUMBER 커스텀 binning 라벨 매치와도 같은 스펙으로 통합해 문자열 우연 일치에 기대는 방편식 처리를 없애고, 매치 스펙이 없는 구버전 호출은 기존 정확값 경로로 동작을 보존한다.

### S-17. 분포 비율(%)이 상위 N개 잘라낸 합 기준으로 계산돼 실제 점유율을 왜곡하고, 잘린 값의 존재 고지도 없다
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/stats/StatsFieldInsightFragment.kt:497` · **분류:** 원칙 위반 · **심각도:** 중
- **증상:** 고유값이 limit(기본 10)보다 많은 필드(거주지·태그성 TEXT 등)에서 각 값의 '비율' 열이 전체 대비가 아니라 상위 10개 합 대비로 부풀려 표시된다. 편향 발견이 목적인 화면에서 편향 정도 자체가 왜곡되고, 나머지 값들이 몇 건 잘렸는지 알 길이 없다.
- **개혁안:** computeFieldDistribution은 전체 분포(또는 전체 합계·잘린 종수/건수 메타데이터 포함 결과 타입)를 반환하도록 바꾸고, 상한 적용은 표시 계층의 관심사로 옮긴다. UI에서는 분모를 전체 값 합으로 계산해 비율을 참값으로 표시하고, 상위 N행 아래에 "기타 N종 M건 (x.x%)" 집계 행을 추가하며 파이차트에도 동일한 '기타' 조각을 넣어 R-14(잘라낸 것은 개수로 알린다)를 충족한다. RANKING 경로도 같은 전체 분포에서 상위 N만 취하므로 함께 정리하되, limit 자체는 사용자 설정(자율성 우선)으로 유지한다. UI에서만 분모를 고치는 땜빵이 아니라 개수 정보 소실 지점(provider의 take)을 제거하는 것이 방편식 패치 금지 원칙에 부합한다.

### S-18. 순위(빈도 모드)가 통계 파싱 규칙을 쓰지 않아 콤마 표시 형식 TEXT·별칭 접기 필드에서 무의미한 결과가 나온다
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/stats/StatsDataProvider.kt:2517` · **분류:** 껍데기 구현 · **심각도:** 중
- **증상:** 콤마 목록 표시 형식으로 설정한 TEXT 필드를 순위 필드로 고르면 '검, 활 (1회)'처럼 원문 전체가 한 값으로 집계돼 전원 1회 동률의 무의미한 순위가 나온다. 별칭 사전을 쓰는 필드는 인사이트 분포 차트와 순위 탭의 빈도 수치가 서로 다른데 아무 고지가 없다.
- **개혁안:** computeRanking 빈도 모드에서 fd.type=="MULTI_TEXT" 하드코딩 분기(2518, 2566)를 제거하고, GRADE/BODY_SIZE가 이미 하듯 값 소유 필드(fieldDefMap[fv.fieldDefinitionId])를 기준으로 FieldValueTokenizer.tokenize(또는 splitForStats)로 분리해 frequencyMap 구축·매칭을 통일한다. 분리된 토큰은 resolversByFieldId의 FieldValueResolver(statsKeys 또는 canonical)로 접은 뒤 집계해 인사이트 분포와 동일한 키 공간에서 빈도를 계산한다 — 통계 파싱 단일 소스 계약 준수(방편식 재구현 금지). 검증→알림 원칙에 따라 COMMA_LIST TEXT·별칭 필드에 대해 인사이트 분포 카운트와 순위 빈도가 일치함을 확인하는 JVM 테스트를 추가하고, 토큰화로 인해 한 캐릭터가 복수 토큰을 갖는 경우의 대표값 표시 규칙(최다 빈도 토큰)을 MULTI_TEXT 기존 경로와 동일하게 적용한다.

### ~~S-19~~. 하위 그룹 분석이 CALCULATED 필드를 대상으로 고를 수 있게 해놓고 항상 '데이터 없음'을 돌려준다 — **처리 완료 (remaining_work 1-9장)**
- **위치:** `app/src/main/java/com/novelcharacter/app/ui/stats/StatsDataProvider.kt:2366` · **분류:** 껍데기 구현 · **심각도:** 중
- **증상:** 차트 탭 → '하위 그룹 분석'에서 수식 필드(예: 계산된 '나이대')를 고르면, 그 필드가 자기 카드에서는 분포를 그려주는데도 항상 '데이터 없음'이 뜬다. 목록에 고를 수 있게 나오는 것 자체가 동작을 약속하는데 어떤 경우에도 결과가 없다.
- **개혁안:** computeSubgroupAnalysis를 두 갈래로 보강한다: 대상 def가 CALCULATED면 getCharactersByFieldValue(2242행)·편향 드릴다운(2321행)과 동일하게 computeAllCalculatedValues(s)에서 characterIds에 속한 캐릭터의 해당 필드 값을 가져와 getFieldValues로 파싱해 분포에 합산한다. 아울러 targetFieldDefId 하나가 아니라 getRankableFields(2404-2424행)처럼 (key,type) 머지된 def 목록을 받아 전체 세계관 스코프에서 같은 필드의 타 세계관 값도 집계하고, 파싱은 기준 def config로 통일한다(2306-2309행의 기존 관례). 선택 목록에서 CALCULATED를 숨기는 방식은 기능 간소화(원칙 03 위반)이므로 금지 — 목록이 약속하는 필드는 전부 실제로 분석되게 만든다. 값이 진짜로 없는 경우에만 '데이터 없음'이 뜨도록 하여 변수 제어(검증→알림) 원칙을 지킨다.

## 3. 기각된 발견 (3건 — 전부 "이 세션이 이미 수리" 판정)

> 수색 시점에는 실재하던 결함을 세션 중반에 수리했고(1장 S-3·S-4), 검증자가 수리된 현행 코드를 읽고 기각했다 — 수리가 실렸다는 교차 확인이다. 거짓 발견은 0건이었다.

- **자동 정리(pruneIfNeeded)가 편집 직전 백업을 영원히 지우지 못한다 — 30일/30건 보관 약속 불이행 + 무한 축적** — `app/src/main/java/com/novelcharacter/app/data/repository/TrashRepository.kt:3004` → 1장 S-4에서 수리 완료.
- **사건 편집 저장 시 안정 식별자 code가 매번 재발급된다 — 엑셀 왕복·휴지통 참조 파괴** — `app/src/main/java/com/novelcharacter/app/ui/timeline/EventEditDialogFragment.kt:331` → 1장 S-3에서 수리 완료.
- **상태변화 편집 저장도 code를 재발급한다 — 같은 부류의 식별자 파괴** — `app/src/main/java/com/novelcharacter/app/ui/character/StateChangeHelper.kt:136` → 1장 S-3에서 수리 완료.

---

## 4. 개혁 우선순위 로드맵

확정 15건(S-5~S-19)을 작업 묶음으로 재편성한 것이다. 순서는 (유실 위험 → 원칙 위반의 폭 → 비용) 순.

| 순위 | 묶음 | 대상 | 요지 |
|------|------|------|------|
| ~~1~~ | ~~**사건 입력 보호**~~ | ~~S-6 (+S-12)~~ | **처리 완료** — `EventFieldValueMerge`(커버 집합) + `replaceForFields` 공용화 + universeId 보존 폴백 + 보존 Toast·이력 고지 + `setValidatedPositiveButton` 재조립. 상세·실기기 확인 절차는 remaining_work 1-7장·3-7장. 인접 발견: 통보-후-닫힘 입력 유실 잔여(B-28), CharacterViewModel 사건 CRUD 무통보 실패(B-29) 등재 |
| ~~2~~ | ~~**월드패키지 완결**~~ | ~~S-5~~ | **처리 완료** — `WorldPackageImporter` 신설(manifest v1~v3 검증 → 전 엔트리 역직렬화 → 원자 트랜잭션 삽입 + old→new id 재배선 + code 충돌 재발급 고지), 충돌 3분기 다이얼로그(share_world_conflict_* 문자열 소생), `ImportFileFormat` ZIP 판별 3분기, 원인별 오류 메시지. 미검증 목록의 "추가 누락 데이터"는 **전부 실재로 확인**되어 내보내기 v3으로 완결(사건 필드 정의·값, 값 라이브러리, 세계관/작품 이미지 추가 + 전역 이름 은행 과포함 축소). 상세·실기기 확인 절차는 remaining_work 1-8장·3-8장 |
| ~~3~~ | ~~**통계 조용한 실패 일소**~~ | ~~S-7·S-8·S-9·S-19~~ | **처리 완료** — ① `FieldInsightResult.mergedFieldDefIds` 신설로 드릴다운이 카드가 합산한 (key,type) 그룹 전체를 기준 def config로 조회(S-7), ② `computeCrossAnalysis`가 사건 축과 대칭으로 `mergeCalculatedRows(…, computeAllCalculatedValues)` 수행(S-8), ③ `getEventsByFieldValue`·`computeEventSubgroupAnalysis` 신설 + 시트를 엔티티 인지형으로 일반화, 사건 행 탭은 전역 검색과 같은 `center_year` 규약으로 연표 이동(S-9), ④ `computeSubgroupAnalysis`가 머지 id 목록을 받고 CALCULATED 계산값을 합산(S-19). 빈 결과 무통보 return은 전부 사유 고지로 교체(교차표 빈 표·드릴다운 필드 소실·하위 그룹 대상 없음). 신규 JVM 테스트 19건. 상세·실기기 확인 절차는 remaining_work 1-9장·3-9장 |
| 4 | **통계 정합성** | S-14·S-15·S-16·S-17·S-18 (+**B-31·B-32·B-33**) | '통계에 포함' 설정을 패턴 인사이트·레거시 필드 분석에 일관 적용, 비율 모수를 전체 합으로 교정 + 잘림 개수 고지(R-14), 순위 빈도를 FieldValueTokenizer·별칭 접기 규칙으로 통일, BODY_SIZE 드릴다운 키 일치. 레거시 필드 분석 화면은 인사이트 화면과의 통합(한쪽 소거)도 검토할 것 — 같은 질문에 다른 답을 주는 화면 둘은 그 자체가 변수 제어 위반. **로드맵 3 세션이 확정한 인접 5건을 이 묶음에 편입**: B-31(패턴 설정 버튼이 카드 안에 있어 모든 유형을 끄면 되돌릴 수 없는 일방통행 함정 — 심각도 상), B-32(`e.message` null 예외를 전 관측자가 걸러 백지 화면 — 심각도 상), B-33(CALCULATED 미병합 잔여 4경로 + `computeRanking`의 분기 구현), B-34(머지 카드의 톱니가 대표 def에만 써서 '통계에 포함' 끄기가 안 먹는 것처럼 보임 — 심각도 상), B-35(`crossFieldGroup`만 enabled 필터가 없어 인사이트와 교차분석이 다른 수치 — S-14와 같은 질문). **착수 전 R-15·R-16·R-17을 읽을 것** |
| 5 | **수식 오류 가시화** | S-11 | NaN을 "오류" 표식으로 표면화(미입력과 구분), 수식 편집기에 검증 피드백. FormulaEvaluator가 단일 소스이므로 표시 계층만 손대면 된다 |
| 6 | **B-8 스트리밍 리더 배선** | S-10 | 기지 백로그 — excel_streaming_import 설계 문서의 '이중 경로 + per-row 비분기' 원칙대로. B-7의 MergedCellMap을 스트리밍 경로에도 붙일 것(mergeCells 요소) |
| 7 | **초기화 완결** | S-13 | 전체 초기화에 휴지통(스냅샷+보류 이미지)·작업 이력·프리셋 포함, 삭제 범위를 초기화 확인 다이얼로그에 명시 |

미검증 57건(5장)은 이 로드맵과 별개로, 다음 감사 라인이 항목당 실재 확인 → 확정 시 이 표에 편입한다.
특히 **medium 조용한 실패 계열**(연표 재정렬 무효·회전 시 콜백 유실·프리셋 무통보 파괴·검색 필터 시트)은
표본 검증에서 확정률이 높았던 부류이므로 우선 확인을 권한다.

---

## 5. 미검증 잔류 발견 (57건 — 검증 상한 초과분)

> 수색은 됐으나 적대적 검증을 거치지 않았다 — **착수 전 반드시 현행 코드로 실재를 재확인할 것**(에이전트 보고는 근거가 아니다). 심각도·분류는 수색자 자기 평가다.

| 심각도 | 분류 | 위치 | 내용 | 비고 |
|--------|------|------|------|------|
| 중 | 조용한 실패 | `ExcelImportService.kt:2726` | 임포트에 병합 셀 감지 전무 + 코드 열 외 선행 0 손실 무통보 (기지 백로그 B-7) | **이 세션 B-7로 수리됨**(수색 시점 보고) |
| 중 | 조용한 실패 | `WorldPackageExporter.kt:50` | ~~월드패키지에서 B-6 외에 추가로 빠지는 데이터 — 사건 필드 정의·값, 필드 데이터 라이브러리, 세계관/작품 이미지 (+전역 이름 은행 과포함)~~ **S-5 착수 재확인에서 전부 실재 확인 → 내보내기 v3으로 처리 완료** |  |
| 중 | 원칙 위반 | `ExcelImporter.kt:159` | 일반 .xlsx 가져오기에서는 '가져오기 항목 선택' 다이얼로그에 도달 불가 — ZIP 경로 전용 |  |
| 중 | 원칙 위반 | `ExcelImportService.kt:5351` | '엑셀에 없는 항목 삭제'의 상태변화 삭제가 휴지통을 우회한 영구 삭제 + 6개 카테고리의 삭제 실패 무통보 삼킴 |  |
| 중 | 조용한 실패 | `TrashFragment.kt:287` | 복원 코루틴이 취소(화면 회전·이탈)를 실패로 오인 — 성공한 복원을 "복원 실패"로 보고하거나 결과 고지가 통째로 유실 |  |
| 중 | 조용한 실패 | `TrashRepository.kt:2569` | 출생·사망 사건 복원이 이미 존재하는 상태변화 이력을 무집계·무통보로 버리고 스냅샷을 소각한다 |  |
| 중 | 원칙 위반 | `TrashRepository.kt:1620` | 되돌리기 직전 백업이 앱 수명 캐시로 refs를 만든다 — 캐시 자신의 '삭제 트랜잭션 한 번' 유효성 규약 위반, 오배정 위험 |  |
| 중 | 조용한 실패 | `TimelineAdapter.kt:103` | 연표 드래그 재정렬이 연·월·일 경계를 넘으면 조용히 무효가 되면서 '순서가 저장되었습니다'를 띄운다 |  |
| 중 | 조용한 실패 | `TimelineFragment.kt:432` | 사건 밀도 바의 좌표계를 두 관찰자가 경합해서 덮어쓰고, 줌 5(월 단위)에서는 완전히 죽는다 |  |
| 중 | 조용한 실패 | `CharacterViewModel.kt:1466` | 캐릭터 화면에서 사건 유형을 출생/사망→없음으로 바꾸면 파생 상태변화가 정리되지 않는다 (연표 탭과 동작 불일치) |  |
| 중 | 조용한 실패 | `TimelineViewModel.kt:635` | 출생/사망 사건에서 캐릭터를 빼는 모든 경로(체크 해제·연결 해제)가 파생 이력을 정리도 통보도 하지 않는다 |  |
| 중 | 조용한 실패 | `TimelineViewModel.kt:716` | 미분류 캐릭터(작품 없음)의 출생·사망 사건을 지우면 __birth/__death 이력이 영구 잔존한다 |  |
| 중 | 조용한 실패 | `StateChangeHelper.kt:89` | 내부 관리 행(__std_year_link)이 상태변화 목록에 그대로 노출되고, 편집하면 '출생' 이력으로 둔갑한다 |  |
| 중 | 껍데기 구현 | `CharacterDetailFragment.kt:192` | 캐릭터 상세의 사건 카드에는 B-5 필드값 표시·연결 작품명이 아예 실리지 않는다 (카드 표시 설정이 안 먹는 경로) |  |
| 중 | 조용한 실패 | `FieldManageFragment.kt:331` | 필드 가져오기 토스트가 '선택한 개수'를 성공 개수로 보고 — 중복은 무통보 폐기 |  |
| 중 | 껍데기 구현 | `FieldViewModel.kt:186` | 사건 필드 탭에서 '다른 작품 필드 가져오기'가 캐릭터 필드만 소스로 삼아 결과가 화면에 나타나지 않음 |  |
| 중 | 조용한 실패 | `UniverseViewModel.kt:169` | 세계관을 프리셋으로 저장할 때 사건 필드가 무통보 누락 — 프리셋 직렬화에 entityType 자체가 없음 |  |
| 중 | 원칙 위반 | `FieldEditDialog.kt:1379` | GRADE 등급 체계가 UI에서 C/B/A/S 4종으로 고정 — 커스텀 등급은 인앱 추가 불가, 편집 화면에서 보이지도 않음 |  |
| 중 | 껍데기 구현 | `EventEditDialogFragment.kt:515` | 사건 CALCULATED 필드는 만들 수 있으나 사건별 계산 값이 어디에도 표시되지 않음 — '목록 카드 표시' 스위치는 헛약속 |  |
| 중 | 죽은 코드/미배선 | `NameBankViewModel.kt:115` | 이름은행 단건 '사용 처리' 흐름이 미배선 — VM·Repo 메서드와 결과 문구까지 만들어놓고 UI 진입점이 없음 |  |
| 중 | 껍데기 구현 | `NameBankViewModel.kt:205` | 일괄 등록 성별 매핑이 하드코딩 키 "gender"에만 동작 — 사용자가 직접 만든 성별 필드는 소외 |  |
| 중 | 약속 불이행 | `RelationshipGraphView.kt:472` | 세력 간 관계 엣지(B-3)가 '관계' 토글이 아니라 '영역' 토글에 종속 — 칩 약속과 실동작 불일치 |  |
| 중 | 죽은 코드/미배선 | `RelationshipGraphFragment.kt:586` | 시간 슬라이더의 '엣지만 갱신' 최적화가 characterFactionMap 관찰자에 의해 무력화 — 틱마다 전체 재레이아웃 + 줌/팬 리셋 |  |
| 중 | 조용한 실패 | `UniverseListFragment.kt:1151` | 세계관/작품 편집 다이얼로그 — 이름(제목) 비면 저장 시 이미지 연동 설정 포함 전체 입력 무통보 폐기 |  |
| 중 | 조용한 실패 | `SearchPresetDao.kt:21` | 프리셋 저장이 동명 프리셋을 무통보로 파괴한다 (REPLACE + unique name) — 기본 검색 프리셋은 영구 소실 |  |
| 중 | 원칙 위반 | `GlobalSearchViewModel.kt:347` | 검색 프리셋 이름변경을 중복 이름으로 하면 예외 미처리로 크래시하고, UI는 이미 '저장됨'을 보고한 뒤다 |  |
| 중 | 조용한 실패 | `SearchFilterBottomSheet.kt:29` | 화면 회전 시 검색 필터 시트·목록 컨트롤 시트의 콜백이 전부 유실되어 '적용'이 조용한 무동작이 된다 |  |
| 중 | 죽은 코드/미배선 | `RankingAdapter.kt:17` | 통계 랭킹 카드가 클릭 가능해 보이지만(리플까지 재생) 탭해도 아무 일도 일어나지 않는다 — onItemClick 미배선 |  |
| 중 | 원칙 위반 | `FieldFilterHelper.kt:40` | 필드 필터가 fieldId 단일 매칭이라 전역 검색에서 다른 세계관의 같은 key 필드가 무음 배제된다 (기지 백로그 B-11) | B-11 |
| 하 | 죽은 코드/미배선 | `StatsViewModel.kt:261` | StatsViewModel.loadRelationNetwork/_relationNetwork — 옵저버도 호출부도 없는 죽은 코드 |  |
| 하 | 죽은 코드/미배선 | `StatsDataProvider.kt:980` | 관계 유형별 시간 추세(typeChangeTrends) 등 계산만 되고 어디에도 표시되지 않는 통계 필드들 |  |
| 하 | 조용한 실패 | `StatsDataProvider.kt:1104` | '작품 미배정' 스코프에서 데이터 건강도의 그룹별 완성도만 미배정 처리 누락 — 형제 화면과 수치 불일치 |  |
| 하 | 조용한 실패 | `StatsDataProvider.kt:1214` | 사건 필드 인사이트 모수가 universeId 잃은 사건을 제외해 완성도가 100%를 넘을 수 있다 |  |
| 하 | 조용한 실패 | `ExcelImporter.kt:279` | ZIP 가져오기를 미리보기에서 취소하면 이미 복사된 이미지가 고아로 남고, 실패 카운터가 다음 가져오기에 거짓 경고로 누출 |  |
| 하 | 조용한 실패 | `ExcelImporter.kt:1007` | 관계 변화 '갱신' 건수가 결과 요약에서 누락 — 갱신만 있으면 '데이터 없음'으로 보고 |  |
| 하 | 죽은 코드/미배선 | `TrashRepository.kt:215` | RestoreResult.revertedMemberships/revertedStateChanges — 계산만 하고 어떤 UI도 읽지 않는 죽은 집계 |  |
| 하 | 약속 불이행 | `TrashFragment.kt:164` | 세계관 부가 데이터의 MISSING_UNIVERSE 차단 다이얼로그가 세력 전용 문구를 보여준다 |  |
| 하 | 조용한 실패 | `EntitySnapshots.kt:58` | 구형(분리 이전) 세계관 payload의 내장 값 라이브러리·고아 필드값을 현행 복원이 무집계로 버린다 |  |
| 하 | 죽은 코드/미배선 | `strings.xml:384` | 고아 문자열 trash_restore_partial_title — 참조하는 코드가 없는 죽은 리소스 |  |
| 하 | 조용한 실패 | `CharacterViewModel.kt:1193` | 이력에서 __birth를 삭제해도 생년 필드가 남아 있으면 다음 저장 때 조용히 부활한다 |  |
| 하 | 원칙 위반 | `QuickAddEventDialogFragment.kt:27` | 간편 사건 추가에서 설명이 비면 입력이 무통보 폐기된다 |  |
| 하 | 껍데기 구현 | `TimelineAdapter.kt:316` | 묶음 줌(1000/100/10년)에서 묶음 카드 클릭·롱프레스가 '첫 사건'만 연다 |  |
| 하 | 죽은 코드/미배선 | `TimelineDao.kt:178` | TimelineDao의 죽은 메서드 3종 — getNextDisplayOrder, getEventDensity, getEventsByYearMonthDay |  |
| 하 | 죽은 코드/미배선 | `PresetTemplates.kt:122` | inputReplace config 키는 내장 프리셋이 심기만 하고 아무 코드도 읽지 않는 죽은 설정 |  |
| 하 | 약속 불이행 | `ExcelExporter.kt:509` | placeholder config는 엑셀 가이드가 '형식 지정' 기능으로 안내하지만 읽는 코드가 없음 |  |
| 하 | 조용한 실패 | `CharacterStateChangeDao.kt:67` | 필드 키 변경 자동 마이그레이션이 미분류(작품 미배정) 캐릭터의 상태변화 이력을 제외 — 알려진 백로그 B-13 | B-13 |
| 하 | 원칙 위반 | `AssistantProviders.kt:385` | '사용됨'의 정의가 화면마다 다름 — 은행·통계는 isUsed, 어시스턴트 넛지는 usedByCharacterId |  |
| 하 | 원칙 위반 | `NameBankFragment.kt:248` | 이름은행 성별 입력이 고정 3옵션 스피너 — 열린 구조 원칙과 어긋나는 닫힌 어휘 |  |
| 하 | 죽은 코드/미배선 | `NameBankDao.kt:22` | 이름은행 데이터 계층의 죽은 코드 4건 — searchNames·insertAll·getAllNameBankList·getAvailableNamesList |  |
| 하 | 죽은 코드/미배선 | `AiProviderStore.kt:50` | AiProviderConfig.updatedAt — 저장·직렬화만 되고 어디서도 읽히지 않는 설정값 |  |
| 하 | 껍데기 구현 | `AiProtocolCodec.kt:131` | baseUrl 유연 수용이 OPENAI_COMPAT에만 구현 — Anthropic·Gemini는 /v1 포함 주소 입력 시 전 호출 404 |  |
| 하 | 조용한 실패 | `AiKeyStore.kt:32` | AiKeyStore.putKey — 암호화 실패 무방비: 복호화는 방어하면서 저장은 예외 시 크래시 |  |
| 하 | 조용한 실패 | `remaining_work_2026-07.md:149` | 백로그 문서에 리터럴 NUL 바이트 — grep이 binary로 취급해 텍스트 도구 전체에서 문서가 통째로 누락 | **이 세션에서 정화됨** |
| 하 | 원칙 위반 | `ImageManagerAdapter.kt:142` | 이미지 관리 그리드 썸네일 LruCache 부재 — 스크롤마다 디스크 재디코드 (알려진 백로그 B-12) | B-12 |
| 하 | 조용한 실패 | `UniverseAdapter.kt:250` | select_character 모드 저장 시 캐릭터 미선택(null)이면 카드가 영구 기본 아이콘 — 저장 시점 경고 없음 |  |
| 하 | 조용한 실패 | `RelationshipGraphFragment.kt:860` | 그래프 요약 모드 — 연결 3개 미만 노드뿐이면 빈 캔버스인데 카운트는 전체 수치 표기 |  |
| 하 | 원칙 위반 | `GlobalSearchFragment.kt:183` | 기본 검색 프리셋 3종은 편집·삭제가 불가능한 읽기 전용이다 |  |

---

## 6. 방법론 기록

- 수색 8방향: 통계 / 엑셀·월드패키지 / 휴지통·복원 / 연표·사건·상태변화 / 필드 시스템 /
  AI·이름은행 / 이미지·관계도 / UI 전반(설정·검색·정렬·온보딩). 원시 79건 → 중복 제거 75건.
- 수색 지침: 문서가 아니라 현행 코드로 판단, file:line + 코드 인용 + 사용자 증상 필수,
  호출부 추적 없이 "미배선" 주장 금지, 기존 백로그와 겹치면 병기.
- 검증 지침: 반증 시도(실재·실행 경로·사용자 가시성 3중 확인), 반신반의는 기각,
  이미 수정된 과거 결함은 기각, **판정 미귀환은 생존으로 집계**(N1 세션 실패의 교훈).
  이번 실행에서는 검증 18건 전부 판정이 귀환했다(미귀환 0).
- 검증 상한 18건: 심각도 상위부터. 초과 57건은 5장에 미검증으로 실었다 — 조용히 자르지 않는다(R-14).

---

## 문서 이력

| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026.07.27 | 최초 작성 — 즉시 수리 4건(S-1~S-4), 확정 15건(S-5~S-19) + 개혁안, 기각 3건(전부 세션 중 수리 교차확인), 미검증 57건, 개혁 우선순위 로드맵 |
| v1.1 | 2026.07.27 | 개혁 로드맵 2 처리 완료 — S-5 소진(WorldPackageImporter + 내보내기 v3 완결), 미검증 목록의 월드패키지 누락 데이터 항목 실재 확인·처리 |
| v1.2 | 2026.07.27 | 개혁 로드맵 3 처리 완료 — S-7·S-8·S-9·S-19 소진(통계 조용한 실패 일소). 공통 뿌리 둘(머지 축을 잃은 드릴다운 / 캐릭터 축의 CALCULATED 미병합)을 근본에서 수리하고 무통보 빈 결과를 사유 고지로 교체. 인접 확정 5건(B-31~B-35)을 로드맵 4에 편입 — 전부 코드 실독으로 확인 |
