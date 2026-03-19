# NovelCharacter 앱 — 전체 코드 점검 보고서

> 점검일: 2026-03-19
> 범위: Kotlin 소스 109개, XML 리소스 70+개, Gradle 빌드 설정 전체

---

## 목차
1. [치명적 버그 (Critical)](#1-치명적-버그-critical)
2. [높은 심각도 이슈 (High)](#2-높은-심각도-이슈-high)
3. [중간 심각도 이슈 (Medium)](#3-중간-심각도-이슈-medium)
4. [낮은 심각도 이슈 (Low)](#4-낮은-심각도-이슈-low)
5. [빌드/설정 이슈](#5-빌드설정-이슈)
6. [리소스/XML 이슈](#6-리소스xml-이슈)
7. [우선순위 요약](#7-우선순위-요약)

---

## 1. 치명적 버그 (Critical)

### C-1. 위젯 리시버 `exported="false"` — Android 12+에서 위젯 작동 불가
- **파일:** `AndroidManifest.xml` (QuickAddWidget, RecentCharactersWidget, TodayCharacterWidget)
- **문제:** 세 위젯 리시버 모두 `android:exported="false"`로 선언됨. Android 12+(API 31) 이상에서 시스템 런처가 위젯에 바인딩할 수 없어 **위젯이 전혀 표시되지 않음**.
- **수정:** `android:exported="true"`로 변경

### C-2. StatsViewModel.loadAllStats()에서 fieldAnalysisStats 미계산
- **파일:** `ui/stats/StatsViewModel.kt` (line ~78-110)
- **문제:** `loadAllStats()`에서 모든 통계를 계산하지만 `_fieldAnalysisStats`만 누락. `StatsMainFragment`가 `fieldAnalysisStats`를 observe(line 154)하므로 **필드 분석 미리보기 카드가 항상 빈 상태**.
- **수정:** `loadAllStats()` 내에 `_fieldAnalysisStats.postValue(provider.computeFieldAnalysis(filtered))` 추가

### C-3. CipherInputStream GCM 인증 태그 미검증 위험
- **파일:** `backup/BackupEncryptor.kt` (line 108-112)
- **문제:** `CipherInputStream` + GCM 모드 조합은 Java/Android 알려진 이슈로, 스트리밍 복호화 시 `AEADBadTagException`이 무시되어 **변조된 백업 파일이 정상으로 처리**될 수 있음.
- **수정:** 스트리밍 대신 `doFinal()` 기반 비스트리밍 복호화 사용 (line 126-138의 `decrypt()` 메서드 패턴 적용)

---

## 2. 높은 심각도 이슈 (High)

### H-1. 자동 백업 포맷이 수동 내보내기보다 데이터 손실
- **파일:** `backup/AutoBackupWorker.kt` vs `excel/ExcelExporter.kt`
- **문제:** AutoBackupWorker의 Universe 시트는 6열(imagePath, imageMode 누락), Novel 시트는 8열(imagePath, imageMode, imageCharacterId 누락). 자동 백업에서 복원 시 **이미지 관련 데이터 영구 손실**.
- **수정:** AutoBackupWorker 내보내기 열을 ExcelExporter와 동일하게 통합

### H-2. 관계 변경사항 가져오기 시 중복 데이터 생성
- **파일:** `excel/ExcelImportService.kt` (line 885-947)
- **문제:** `importRelationshipChanges`만 항상 `insert`하고 중복 체크 없음. 같은 파일 재가져오기 시 **매번 중복 레코드 생성**. 다른 엔티티는 모두 update-if-exists 로직 있음.
- **수정:** 기존 레코드 확인 후 upsert 로직 적용

### H-3. 계산 필드 간 의존성 순서/순환 참조 미처리
- **파일:** `util/TimeStateResolver.kt` (line 64-86)
- **문제:** 계산 필드 A가 계산 필드 B를 참조할 때, B가 아직 계산되지 않았으면 잘못된 값 사용. 순환 참조 시 **무한 루프 없이 잘못된 결과만 생성**되어 디버깅 어려움.
- **수정:** 위상 정렬(topological sort)로 계산 순서 결정 + 순환 참조 감지

### H-4. KeyStore 키 분실 시 모든 백업 복원 불가 (무경고)
- **파일:** `backup/BackupEncryptor.kt` (line 24-48)
- **문제:** 보안 잠금 변경/초기화 시 Android KeyStore 키가 삭제되면 새 키 생성됨. 기존 암호화된 백업은 **영구 복호화 불가**하지만 사용자에게 경고 없음.
- **수정:** 앱 시작 시 키 일관성 검증 + 복원 실패 시 명확한 오류 메시지

### H-5. 위젯 goAsync() 코루틴에 타임아웃 없음
- **파일:** `widget/RecentCharactersWidget.kt` (line 24-61), `widget/TodayCharacterWidget.kt` (line 25-71)
- **문제:** `goAsync()` + `Dispatchers.IO` 코루틴에 타임아웃 없음. DB가 크거나 잠겨있으면 **10초 BroadcastReceiver 제한 초과 → 프로세스 강제 종료**.
- **수정:** `withTimeout(8000)` 래핑

### H-6. CharacterEditFragment 저장 버튼 영구 비활성화
- **파일:** `ui/character/CharacterEditFragment.kt` (line 693, 713-715)
- **문제:** 저장 성공 시 `isSaving = true` 리셋 없이 `popBackStack()` 호출. 네비게이션 실패 시 **저장 버튼이 영구 비활성화**되어 재저장 불가.
- **수정:** finally 블록에서 `isSaving = false`, `binding.btnSave.isEnabled = true` 리셋

---

## 3. 중간 심각도 이슈 (Medium)

### M-1. 자동 백업 시 평문 임시 파일 디스크 잔존
- **파일:** `backup/AutoBackupWorker.kt` (line 81-92)
- **문제:** 암호화 전 `.xlsx` 평문 파일이 디스크에 기록됨. 프로세스 강제 종료 시 삭제되지 않고 남음.

### M-2. ExcelExporter CoroutineScope 명시적 cancel 없으면 누수
- **파일:** `excel/ExcelExporter.kt` (line 40-41)
- **문제:** `SupervisorJob()` 기반 자체 CoroutineScope. 호출자가 `cancel()` 미호출 시 코루틴 누수.

### M-3. TodayCharacterWidget — 전체 캐릭터 목록 메모리 로드
- **파일:** `widget/TodayCharacterWidget.kt` (line 45)
- **문제:** 랜덤 캐릭터 선택을 위해 전체 캐릭터 목록 로드. `SELECT ... ORDER BY RANDOM() LIMIT 1` 쿼리가 효율적.

### M-4. PdfExporter — 전체 필드값/관계 무필터 로드
- **파일:** `share/PdfExporter.kt` (line 50-51)
- **문제:** 특정 세계관 PDF 생성 시에도 전체 DB의 필드값과 관계를 메모리에 로드.

### M-5. WorldPackageExporter — 이미지 오류 무시
- **파일:** `share/WorldPackageExporter.kt` (line 106)
- **문제:** `catch (_: Exception) {}`로 이미지 내보내기 오류를 무시. 패키지에 이미지 누락되어도 사용자 알 수 없음.

### M-6. LIKE 쿼리 특수문자 미이스케이프
- **파일:** `data/dao/CharacterDao.kt` (line 31), `NovelDao.kt` (line 49)
- **문제:** `LIKE '%' || :query || '%'`에서 `%`, `_` 미이스케이프. 의도치 않은 검색 결과 반환.

### M-7. ExcelExporter.exportRelationshipChanges 빈 행 버그
- **파일:** `excel/ExcelExporter.kt` (line 738-754)
- **문제:** `forEachIndexed`에서 관계 미발견 시 `return@forEachIndexed`하지만 인덱스는 증가. 스프레드시트에 빈 행 생성.

### M-8. 시스템 유지보수 — 대량 트랜잭션 크기 무제한
- **파일:** `data/maintenance/SystemMaintenanceService.kt` (line 200-231)
- **문제:** `reindexDisplayOrders`가 전체 universe/novel/character를 단일 트랜잭션으로 업데이트. 대규모 DB에서 SQLite 저널 공간 부족 위험.

---

## 4. 낮은 심각도 이슈 (Low)

### L-1. ExcelExporter.exportRelationshipChanges SheetSpec 미적용
- **파일:** `excel/ExcelExporter.kt` (line 719-755)
- **헤더를 수동 작성하여 다른 시트와 서식 불일치 (열 너비, 자동필터, 동결 창 누락)**

### L-2. ImportResult.errors 리스트 크기 무제한
- **파일:** `excel/ExcelImportService.kt` (line 49)
- **비정상 파일에서 수천 개 에러 수집 시 OOM 가능**

### L-3. FormulaEvaluator 알 수 없는 문자 무시
- **파일:** `util/FormulaEvaluator.kt` (line 112)
- **잘못된 수식이 오류 없이 부분 평가됨**

### L-4. ExcelExporter 실패 시 임시 파일 미삭제
- **파일:** `excel/ExcelExporter.kt` (line 94-103)

### L-5. 수식 셀 가져오기 시 빈 문자열 반환 가능
- **파일:** `excel/ExcelImportService.kt` (line 1113-1123)

### L-6. CharacterListFragment Handler/Runnable 누수 위험
- **파일:** `ui/character/CharacterListFragment.kt` (line 212-213)
- **Fragment 파괴 시 Handler 참조 유지. Job 기반 디바운스로 통일 권장.**

---

## 5. 빌드/설정 이슈

### B-1. Apache POI 5.3.0 — Android에서 과도한 의존성
- **파일:** `app/build.gradle.kts`
- **poi-ooxml은 서버용 라이브러리. APK 크기 대폭 증가, DEX 메서드 수 문제 가능. FastExcel 등 경량 대안 고려.**

### B-2. Room 2.7.0 안정성 미확인
- **Kotlin 2.0 시점 최신 안정 Room은 2.6.1. 2.7.0이 alpha/beta면 프로덕션 위험.**

### B-3. 릴리스 서명 설정 없음
- **`signingConfigs` 미정의. 릴리스 빌드가 미서명 상태.**

### B-4. API 31+ 백업 규칙 미선언
- **`dataExtractionRules` 미설정. Room DB 등 민감 데이터가 Google Drive에 자동 백업될 수 있음.**

### B-5. tools:targetApi="34" ↔ targetSdk=35 불일치
- **AndroidManifest.xml**

### B-6. ProGuard 규칙 과도하게 넓음
- **Gson 전체 keep, Room 엔티티/DAO 불필요 keep → R8 최적화 저해, APK 비대화**

### B-7. MPAndroidChart v3.1.0 — 2019년 이후 미관리
- **Vico, AAChartCore-Kotlin 등 대안 고려**

### B-8. CardView 의존성 중복
- **Material Components의 MaterialCardView와 중복. 별도 cardview 의존성 불필요.**

### B-9. 테스트 의존성 전무
- **testImplementation, androidTestImplementation 없음. 최소 JUnit + Room Testing 추가 권장.**

---

## 6. 리소스/XML 이슈

### R-1. 네비게이션 그래프 불완전
- **15+ 이상의 프로그래밍 방식 네비게이션이 nav_graph에 action 미정의. Safe Args/Navigation Editor 미지원.**

### R-2. nav_graph 내 하드코딩 한국어 라벨
- **초반 6개 Fragment에 `android:label="세계관"` 등 직접 기입. 나머지는 `@string/` 참조. 다국어 지원 불가.**

### R-3. 다크 모드 색상 21개 미오버라이드
- **values/colors.xml 33색 중 values-night/colors.xml은 12색만 정의. 등급·상태·타임라인·검색 색상 미정의.**

### R-4. WCAG AA 색상 대비 위반 (7건)
| 색상 조합 | 대비율 | 기준 (4.5:1) |
|-----------|--------|-------------|
| `rank_ex` (#FFD700) on white | 1.3:1 | **심각 위반** |
| `rank_a_minus` (#FFB347) on white | 1.8:1 | **위반** |
| `rank_a` (#FFA500) on white | 2.1:1 | **위반** |
| `rank_c_plus` (#32CD32) on white | 2.5:1 | **위반** |
| `rank_a_plus` (#FF8C00) on white | 2.6:1 | **위반** |
| `alive_color` (#4CAF50) on white | 3.0:1 | **위반** |
| `rank_d` (#757575) on night surface | 4.1:1 | **위반** |

### R-5. dimens.xml 정의된 간격 시스템 미활용
- **4dp 그리드 시스템(`spacing_xs`~`spacing_xl`) 정의되어 있으나 거의 모든 레이아웃에서 하드코딩 dp/sp 값 사용. 사실상 dead resource.**

### R-6. 중복/유사 문자열 리소스
- `event_year_format` = `year_label_format` (동일 값)
- `auto_calculated` ≈ `auto_calculated_label` (콜론 유무만 차이)

### R-7. 삭제 확인 문자열에 불필요 공백
- `confirm_delete_universe`, `confirm_delete_name` — `" %s"` 앞에 공백 포함

---

## 7. 우선순위 요약

| 우선순위 | ID | 항목 | 난이도 |
|---------|-----|------|--------|
| **P0** | C-1 | 위젯 exported="true" 수정 | 낮음 |
| **P0** | C-2 | StatsViewModel fieldAnalysisStats 누락 수정 | 낮음 |
| **P0** | C-3 | CipherInputStream GCM 인증 문제 | 중간 |
| **P0** | H-1 | 자동 백업 데이터 손실 수정 | 중간 |
| **P0** | H-6 | 저장 버튼 비활성화 수정 | 낮음 |
| **P1** | H-2 | 관계 변경 중복 가져오기 수정 | 낮음 |
| **P1** | H-3 | 계산 필드 의존성 순서 | 중간 |
| **P1** | H-4 | KeyStore 키 분실 감지/경고 | 중간 |
| **P1** | H-5 | 위젯 코루틴 타임아웃 | 낮음 |
| **P1** | R-4 | WCAG 색상 대비 수정 | 낮음 |
| **P2** | M-1~M-8 | 중간 심각도 이슈 전체 | 다양 |
| **P2** | B-1~B-9 | 빌드 설정 개선 | 다양 |
| **P3** | L-1~L-6 | 낮은 심각도 이슈 전체 | 낮음 |
| **P3** | R-1~R-7 | 리소스 정리 | 낮음 |

---

## 전체 평가

**아키텍처:** MVVM + Repository 패턴이 일관되게 적용되어 있고, Room 엔티티에 적절한 인덱스와 Foreign Key가 설정되어 있음. DiffUtil, Safe Navigation 등 모범 사례 적용. 코드 품질은 전반적으로 양호.

**데이터 계층:** DAO 쿼리와 트랜잭션이 대체로 정확하나, 자동 백업과 수동 내보내기 간 포맷 불일치(H-1)가 가장 심각한 데이터 정합성 문제.

**UI 계층:** Fragment 생명주기 관리는 양호 (binding null 체크, onDestroyView 정리 등). StatsViewModel의 누락된 계산(C-2)과 저장 버튼 버그(H-6)가 사용자 경험에 직접 영향.

**보안:** 암호화 구현에 알려진 Java 플랫폼 이슈(C-3) 존재. KeyStore 키 관리에 복원력 부족(H-4).

**빌드:** Apache POI의 Android 적합성(B-1), 테스트 부재(B-9), 릴리스 서명 미설정(B-3)이 프로덕션 출시 전 해결 필요.

**P0 항목 5건을 우선 수정하면 가장 큰 사용자 영향 버그와 데이터 안전성 문제가 해결됩니다.**
