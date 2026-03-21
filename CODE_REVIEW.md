# NovelCharacter 전반적 코드 리뷰

> 검토일: 2026-03-21 (4차 갱신)
> 대상: Kotlin Android 앱 (122 소스 파일, Room DB v25, MVVM 아키텍처)

---

## 1. 이전 리뷰 대비 수정 완료 항목 ✅

아래 항목들은 모두 수정 완료되어 더 이상 이슈가 아닙니다.

| # | 이슈 | 수정 내용 |
|---|------|-----------|
| 1 | 위젯 `exported="false"` | `exported="true"`로 변경 완료 |
| 2 | CipherInputStream GCM 태그 미검증 | `cipher.doFinal()` 방식으로 전환 완료 |
| 3 | `loadAllStats()` fieldAnalysisStats 누락 | 계산 로직 추가 완료 |
| 4 | KeyStore 키 손실 처리 | 키 존재 검사 및 경고 구현 완료 |
| 5 | AutoBackup 이미지 필드 누락 | imagePath, imageMode 내보내기 추가 완료 |
| 6 | RelationshipChange 가져오기 중복 | upsert 로직 적용 완료 |
| 7 | CharacterDao nullable novelId | IS NULL 비교 쿼리 수정 완료 |
| 8 | Novel.imageCharacterId FK 없음 | ForeignKey(SET_NULL) 추가 완료 |
| 9 | FormulaEvaluator 순환 참조 | resolvingFields Set 기반 감지 구현 완료 |
| 10 | 검색 디바운스 Handler 사용 | 모든 검색에 코루틴 Job 기반으로 통일 완료 |
| 11 | RelationshipGraphView Path 재할당 | arrowPath 멤버 변수 재사용으로 수정 완료 |
| 12 | 빈 catch 블록 (주요 파일) | Log.w() 로깅 추가 완료 |
| 13 | 위젯 goAsync() 타임아웃 | withTimeoutOrNull(5000) 적용 완료 |
| 14 | 이미지 경로 검증 불일치 | UniverseListFragment, NovelListFragment에 canonicalPath 검증 추가 완료 |
| 15 | DashPathEffect 매 프레임 재생성 | 멤버 변수로 추출 완료 |
| 16 | TimeStateResolver 하드코딩 "오류" | 상수 FORMULA_ERROR_DISPLAY로 추출, strings.xml 리소스 추가 완료 |
| 17 | ExcelImporter 빈 catch 블록 | Log.w() 로깅 추가 완료 |
| 18 | ExcelImporter 하드코딩 결과 문자열 | strings.xml 리소스로 이동 완료 (17개 문자열) |
| 19 | Gson TypeToken 반복 생성 | GsonTypes 유틸 상수로 추출 완료 (6개 파일) |
| 20 | SettingsFragment deprecated ProgressDialog | MaterialAlertDialogBuilder + ProgressBar로 교체 완료 |
| 21 | StatsViewModel 통계 순차 로딩 | `async`/`awaitAll` 병렬 로딩으로 전환 완료 |
| 22 | FormulaEvaluator `avg()` 함수 미구현 | `avg(a, b)` 함수 추가 완료 |
| 23 | `filterByNovel()` nameBank/universe 필터 누락 | nameBank, universes 필터링 추가 완료 |
| 24 | 캐릭터 상세 개별 통계 미표시 | 잠재력 등급(S~D) + 특화 유형 인라인 표시 추가 완료 |

---

## 2. 잔여 이슈 — 중간 심각도 (Medium)

### 2.1 `ExcelImporter`의 Activity 참조 관리

**파일:** `ExcelImporter.kt`

`WeakReference<Activity>`를 사용하여 Activity 누수를 방지하지만, 가져오기 작업이 Activity 전환 중에도 계속 실행됩니다. `currentActivityRef`가 null이 되면 진행 대화상자 없이 백그라운드에서 작업이 완료되지만, 사용자에게 완료 피드백이 전달되지 않습니다.

---

### 2.2 데이터베이스 마이그레이션의 성능 문제

**파일:** `AppDatabase.kt` (MIGRATION_5_6)

`displayOrder` 백필에서 서브쿼리 기반의 `UPDATE` 문이 O(n²) 성능을 가집니다. 수천 개의 레코드가 있는 경우 마이그레이션이 느려질 수 있습니다. 단, 이미 실행된 과거 마이그레이션이므로 수정 시 위험이 있습니다.

---

### 2.3 `ExcelImportService` — 대규모 임포트 시 트랜잭션 크기

**파일:** `ExcelImportService.kt`

임포트가 시트별 3-phase 트랜잭션으로 분리되어 있어 기본적인 청크 처리는 되어 있으나, 단일 시트에 수천 행이 있는 경우 해당 트랜잭션이 커질 수 있습니다.

---

## 3. 잔여 이슈 — 낮은 심각도 (Low)

### 3.1 하드코딩된 문자열 (일부 잔존)

`ExcelImportService.kt`에서 엑셀 시트 이름으로 사용되는 내부 식별자 문자열이 있습니다:
- `"기본 정보"`, `"천개력"`, `"사용 안내"`, `"미분류 캐릭터"`

이들은 사용자에게 직접 노출되지 않는 내부 시트 식별자이므로 긴급하지 않으나, 국제화(i18n) 지원 시 이동이 필요합니다.

---

## 4. CLAUDE.md 원칙 위배 사항 (4차 갱신에서 추가)

### 원칙 01: 열린 구조 — 통계 분석 방법 인라인 설정 부재

`FieldStatsConfig`에서 분석 타입(DISTRIBUTION/NUMERIC/RANKING)과 차트 타입을 설정할 수 있으나, 이를 변경하는 UI가 `FieldEditDialog`에만 존재합니다. 통계 화면에서 직접 분석 방법을 변경할 수 있는 인라인 설정이 없어, 사용자가 통계 탭 → 필드 관리로 이동해야 합니다.

### 원칙 02: 실질적 기능성 — DataHealth 통계 입력량 중심

`DataHealthStats`의 `noImageChars`, `noMemoChars`, `noAnotherNameChars` 등이 "입력했는가/안 했는가" 중심입니다. 데이터 건강도 자체는 유용하나, 원칙에서 말하는 "편향이나 패턴 발견" 중심의 분석보다 입력 완료율 중심이 더 부각되어 있습니다.

### 원칙 04: 조작 마찰 최소화 — 재정렬 UX 불일치 (기존 잔여)

- Universe/Novel: 드래그 핸들 존재, 직접 드래그 가능
- Character: 메뉴에서 재정렬 모드 토글 필요, 핸들 부재

### 원칙 05: 데이터 유기적 연결 — 작품 간 필드 구성 비교 미흡

`CrossNovelComparison`이 존재하지만 작품 간 필드 구성 차이(어떤 작품에만 있는 필드 등)를 시각적으로 비교하는 기능이 약합니다.

---

## 5. 아키텍처 및 설계 관찰 (3차 기준)

### 4.1 긍정적 측면

| 영역 | 평가 |
|------|------|
| **MVVM 패턴** | ViewModel/LiveData/Repository 3계층이 일관되게 적용됨 |
| **Room 마이그레이션** | 22개 스키마 버전을 수동 마이그레이션으로 관리, 백필 로직 포함 |
| **코루틴 사용** | `viewModelScope`, `lifecycleScope`, `withContext(IO)` 적절히 사용 |
| **보안** | 이미지 경로 검증, GCM 인증, ZIP bomb 방어, 파일 크기 제한 구현 |
| **Excel 왕복** | 코드 기반 안정 식별자로 import/export 라운드트립 지원 |
| **DiffUtil** | RecyclerView 어댑터에서 DiffUtil 일관 사용 |
| **백그라운드 작업** | WorkManager로 생일 알림, 자동 백업 적절히 스케줄링 |
| **Fragment 생명주기** | `_binding` null 체크, `isAdded` 검사가 전반적으로 잘 적용됨 |
| **데이터 무결성** | `withTransaction`으로 관련 데이터 원자적 처리, FK 제약 적용 |
| **순환 참조 방지** | FormulaEvaluator에 resolvingFields 기반 순환 감지 구현 |
| **위젯 안정성** | goAsync() 타임아웃 적용, exported 플래그 올바르게 설정 |

### 4.2 개선 권장 사항

| 영역 | 현재 상태 | 권장 |
|------|-----------|------|
| **DI (의존성 주입)** | `Application` 클래스에서 수동 생성 | Hilt 또는 Koin 도입으로 테스트 용이성 향상 |
| **이미지 로딩** | `BitmapFactory.decodeFile()` 직접 사용 | Coil 또는 Glide 라이브러리로 메모리 관리 위임 |
| **테스트** | 테스트 파일 미발견 | Unit/Integration 테스트 추가 필수 (특히 마이그레이션, 수식 평가기) |
| **에러 처리** | 대부분 `catch (e: Exception)` 사용 | 구체적 예외 타입 명시 |
| **접근성** | `contentDescription` 미설정 다수 | TalkBack 호환을 위해 이미지 뷰에 설명 추가 |

---

## 7. 우선순위 요약

| 우선순위 | 항목 | 상태 |
|---------|------|------|
| ~~**P0**~~ | ~~위젯 exported, loadAllStats, GCM 태그~~ | ✅ 수정 완료 |
| ~~**P0**~~ | ~~SettingsFragment ProgressDialog 크래시 위험~~ | ✅ 수정 완료 (4차) |
| ~~**P1**~~ | ~~AutoBackup 이미지, 관계변화 upsert, 순환참조, KeyStore~~ | ✅ 수정 완료 |
| ~~**P1**~~ | ~~StatsViewModel 순차 로딩 → 병렬화~~ | ✅ 수정 완료 (4차) |
| ~~**P1**~~ | ~~FormulaEvaluator avg() 미구현~~ | ✅ 수정 완료 (4차) |
| ~~**P1**~~ | ~~filterByNovel() 필터링 누락~~ | ✅ 수정 완료 (4차) |
| ~~**P1**~~ | ~~캐릭터 상세 개별 통계 미표시~~ | ✅ 수정 완료 (4차) |
| ~~**P2**~~ | ~~검색 디바운스, FK 추가, 위젯 타임아웃, 이미지 경로 검증~~ | ✅ 수정 완료 |
| ~~**P3**~~ | ~~빈 catch 블록, Gson 타입토큰, ExcelImporter 문자열 리소스화~~ | ✅ 수정 완료 |
| **P1** | 재정렬 UX 통일 (캐릭터 핸들 추가) | 잔여 (원칙 04 위배) |
| **P1** | 통계 분석 방법 인라인 설정 UI | 잔여 (원칙 01 위배) |
| **P2** | ExcelImporter Activity 참조, 마이그레이션 O(n²), 임포트 트랜잭션 크기 | 잔여 (설계 개선 필요) |
| **P2** | 접근성 개선 (contentDescription, 색상 대비) | 잔여 |
| **P2** | 작품 간 필드 구성 비교 기능 | 잔여 (원칙 05 위배) |
| **P3** | ExcelImportService 내부 시트 식별자 문자열 | 잔여 (i18n 시 이동) |
| **P3** | 이미지 라이브러리 도입, DI 도입, 테스트 코드 추가 | 장기 과제 |
