# NovelCharacter 전반적 코드 리뷰

> 검토일: 2026-03-20
> 대상: Kotlin Android 앱 (114 소스 파일, Room DB v15, MVVM 아키텍처)

---

## 1. 치명적 버그 (Critical)

### 1.1 위젯 `exported="false"` — Android 12+ 호환 불가

**파일:** `AndroidManifest.xml:42-73`

세 위젯 receiver 모두 `android:exported="false"`로 선언되어 있어 Android 12 (API 31) 이상에서 AppWidgetManager가 위젯을 발견하지 못합니다. 런처의 위젯 목록에 아예 나타나지 않습니다.

```xml
<!-- 문제 코드 -->
<receiver android:name=".widget.RecentCharactersWidget"
    android:exported="false">
```

**수정:** `android:exported="true"`로 변경해야 합니다. intent-filter가 `android.appwidget.action.APPWIDGET_UPDATE`를 포함하므로 exported는 반드시 true여야 합니다.

---

### 1.2 `CipherInputStream` + GCM 인증 태그 검증 문제

**파일:** `BackupEncryptor.kt:108-112`

`CipherInputStream`은 GCM 모드의 인증 태그 검증을 **스트림 종료 시점에 수행하지 않는** 구현이 일부 Android 버전에서 존재합니다. 이 경우 변조된 백업 파일을 정상으로 복호화할 수 있어 데이터 무결성이 보장되지 않습니다.

```kotlin
// 현재 코드 — 일부 JCE 구현에서 tag 미검증
javax.crypto.CipherInputStream(fis, cipher).use { cis ->
    FileOutputStream(tempFile).use { fos ->
        cis.copyTo(fos, bufferSize = 8192)
    }
}
```

**수정 방안:**
- 파일을 메모리에 읽은 후 `cipher.doFinal()`로 복호화 (소규모 파일에 적합)
- 또는 `CipherInputStream` 사용 후 `cipher.doFinal(ByteArray(0))`을 명시적으로 호출하여 태그 검증 보장

---

### 1.3 `StatsViewModel.loadAllStats()`에서 `fieldAnalysisStats` 미계산

**파일:** `StatsViewModel.kt:95-132`

`loadAllStats()`가 `characterStats`, `eventStats`, `relationshipStats`, `nameBankStats`, `dataHealthStats`를 모두 계산하지만, `_fieldAnalysisStats`는 누락되어 있습니다. `StatsFieldAnalysisDetailFragment`가 이 값을 관찰하므로, 해당 화면은 `loadAllStats()` 이후에도 데이터가 비어 있습니다.

```kotlin
// 누락된 코드 — loadAllStats() 끝에 추가 필요
val fieldAnalysis = withContext(Dispatchers.IO) { provider.computeFieldAnalysis(filtered) }
_fieldAnalysisStats.value = fieldAnalysis
```

---

### 1.4 KeyStore 키 손실 시 백업 복구 불가

**파일:** `BackupEncryptor.kt:24-48`

Android KeyStore의 키가 기기 초기화, 보안 업데이트, 또는 사용자 인증 변경 시 삭제될 수 있습니다. 키가 손실되면 모든 암호화된 백업이 영구적으로 복구 불가능합니다.

**권장 조치:**
- 앱 시작 시 KeyStore 키 존재 여부를 확인하고, 키가 없으면 사용자에게 경고
- 암호화 키의 파생(derivation) 방식을 사용자 비밀번호 기반(PBKDF2)으로 전환 검토
- 비암호화 수동 백업 옵션 제공

---

## 2. 높은 심각도 (High)

### 2.1 자동 백업에서 이미지 관련 필드 누락

**파일:** `AutoBackupWorker.kt:128-141`

`exportUniverses()`가 `imagePath`, `imageMode` 열을 내보내지 않습니다. 수동 내보내기(`ExcelExporter`)에서는 이 열이 포함됩니다. 자동 백업에서 복원하면 세계관/작품의 이미지 설정이 손실됩니다.

```kotlin
// AutoBackupWorker — 누락
val headers = listOf("이름", "설명", "코드", "정렬순서", "테두리색", "테두리두께")
// ExcelExporter에는 "이미지경로", "이미지모드"가 포함되어야 함
```

**수정:** `exportUniverses()`와 `exportNovels()`에 이미지 관련 열 추가.

---

### 2.2 관계 변화(RelationshipChange) 가져오기 시 중복 삽입

**파일:** `ExcelImportService.kt:893-956`

`importRelationshipChanges()`는 기존 레코드와의 중복을 체크하지 않고 무조건 `insert()`를 호출합니다. 동일한 Excel 파일을 두 번 가져오면 관계 변화 데이터가 모두 중복됩니다.

```kotlin
// 현재: 항상 삽입
db.characterRelationshipChangeDao().insert(CharacterRelationshipChange(...))
result.newRelationshipChanges++
```

**수정:** 상태변화 가져오기(`importStateChanges`)처럼 자연 키(relationshipId + year + relationshipType)로 기존 레코드를 조회한 후 upsert 적용.

---

### 2.3 `CharacterDao.getCharacterByNameAndNovel` — novelId가 null인 캐릭터 미처리

**파일:** `CharacterDao.kt:34-35`

```kotlin
@Query("SELECT * FROM characters WHERE name = :name AND novelId = :novelId LIMIT 1")
suspend fun getCharacterByNameAndNovel(name: String, novelId: Long): Character?
```

`novelId`가 nullable이 아닌 `Long`이므로, `novelId IS NULL`인 캐릭터를 검색할 수 없습니다. Excel 임포트 시 작품 미지정 캐릭터의 이름 기반 매칭이 `getCharacterByName()`으로 폴백되어 잘못된 캐릭터와 매칭될 수 있습니다.

---

### 2.4 `Novel.imageCharacterId`에 외래 키 제약 없음

**파일:** `Novel.kt` (데이터 모델)

`imageCharacterId`가 `characters.id`를 참조하지만, Room 엔티티 정의에 `@ForeignKey`가 없습니다. 참조되는 캐릭터가 삭제되어도 `imageCharacterId`가 갱신되지 않아 댕글링 참조가 발생합니다.

---

### 2.5 FormulaEvaluator — 순환 참조 미감지

**파일:** `FormulaEvaluator.kt`

CALCULATED 필드가 다른 CALCULATED 필드를 참조하면 무한 루프에 빠질 수 있습니다. `MAX_TOKENS = 500` 제한이 있지만, 이는 토큰 수 기반이므로 실제 순환 참조(필드 A → 필드 B → 필드 A)는 감지하지 못합니다.

**수정:** 필드 의존성 그래프를 위상 정렬(topological sort)하여 순환을 감지하거나, 재귀 깊이 제한을 추가.

---

### 2.6 검색 디바운스 패턴 불일치

**파일:** `CharacterListFragment.kt:212-225`

`Handler` + `Runnable` 기반 디바운스를 사용하지만, 다른 검색 구현(`GlobalSearchFragment`)에서는 코루틴 `Job` 기반을 사용합니다. Handler 기반은 Fragment 생명주기와 결합되지 않아 메모리 누수 위험이 있습니다.

```kotlin
// 현재: Handler 기반 (생명주기 비인식)
private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
```

`onDestroyView()`에서 `removeCallbacks()`를 호출하고 있어 실질적 누수는 방지되지만, 코루틴 기반으로 통일하면 더 안전합니다.

---

## 3. 중간 심각도 (Medium)

### 3.1 위젯의 `goAsync()` 타임아웃 위험

**파일:** `RecentCharactersWidget.kt:24-59`

`goAsync()`는 약 10초 시간 제한이 있습니다. Room 데이터베이스 쿼리가 느린 기기에서 이 제한을 초과하면 위젯이 업데이트되지 않습니다.

```kotlin
val pendingResult = goAsync()
CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
    // DB 쿼리 + 위젯 업데이트 — 10초 이내에 완료해야 함
    ...
    pendingResult.finish()
}
```

**권장:** DB 쿼리에 `withTimeout(8000)`을 적용하거나, 캐시된 데이터 사용.

---

### 3.2 이미지 경로 검증 불완전

`CharacterEditFragment.kt`는 `canonicalPath` 검증을 수행하지만, 세계관/작품 이미지 저장 로직에도 동일한 검증이 필요합니다. 경로 조작(path traversal) 공격을 전체 앱에서 일관되게 방어해야 합니다.

---

### 3.3 `ExcelImporter`의 Activity 참조 관리

**파일:** `ExcelImporter.kt:31,101-105`

`WeakReference<Activity>`를 사용하여 Activity 누수를 방지하지만, 가져오기 작업이 Activity 전환 중에도 계속 실행됩니다. `currentActivityRef`가 null이 되면 진행 대화상자 없이 백그라운드에서 작업이 완료되지만, 사용자에게 완료 피드백이 전달되지 않습니다.

---

### 3.4 데이터베이스 마이그레이션의 성능 문제

**파일:** `AppDatabase.kt:304-337` (MIGRATION_5_6)

`displayOrder` 백필에서 서브쿼리 기반의 `UPDATE` 문이 O(n²) 성능을 가집니다. 수천 개의 레코드가 있는 경우 마이그레이션이 느려질 수 있습니다.

```sql
-- O(n²) 서브쿼리
UPDATE `universes` SET `displayOrder` = (
    SELECT COUNT(*) FROM `universes` u2 WHERE u2.`createdAt` > `universes`.`createdAt` ...
)
```

---

### 3.5 `ExcelImportService` — 대규모 임포트 시 트랜잭션 크기

**파일:** `ExcelImportService.kt:140-151`

전체 임포트가 단일 `db.withTransaction` 블록 안에서 실행됩니다. 수천 행을 가져오면 트랜잭션 저널이 매우 커져 메모리 부족이나 ANR이 발생할 수 있습니다.

**권장:** 시트 또는 청크 단위로 트랜잭션을 분리.

---

## 4. 낮은 심각도 (Low)

### 4.1 `RelationshipGraphView` — Path 객체 재할당

**파일:** `RelationshipGraphView.kt:334`

`drawArrow()`에서 매 호출마다 새 `Path` 객체를 생성합니다. `onDraw()` 내에서 반복 호출되므로 GC 압박을 유발할 수 있습니다.

```kotlin
// 개선: 멤버 변수로 Path를 재사용
private val arrowPath = Path()
```

---

### 4.2 하드코딩된 문자열

여러 파일에서 한국어 문자열이 코드에 직접 포함되어 있습니다:
- `ExcelImportService.kt`: `"기본 정보"`, `"천개력"`, `"사용 안내"`, `"미분류 캐릭터"`
- `NovelCharacterApp.kt:79`: `"캐릭터 생일 알림"`, `"소설 캐릭터의 생일을 알려줍니다"`

이들을 `strings.xml`로 이동하면 국제화(i18n) 지원이 용이해집니다.

---

### 4.3 Gson 타입 토큰 반복 생성

`CharacterEditFragment.kt`와 `SystemMaintenanceService.kt` 등에서 `TypeToken<List<String>>`을 매번 새로 생성합니다. 재사용 가능한 상수로 추출하면 미세한 성능 향상이 가능합니다.

---

### 4.4 빈 catch 블록

여러 파일에서 `catch (_: Exception) {}`로 예외를 무시합니다:
- `ExcelImporter.kt:158`, `ExcelImporter.kt:209`
- `AutoBackupWorker.kt:54`

최소한 `Log.w()`로 경고를 기록해야 디버깅이 가능합니다.

---

## 5. 아키텍처 및 설계 관찰

### 5.1 긍정적 측면

| 영역 | 평가 |
|------|------|
| **MVVM 패턴** | ViewModel/LiveData/Repository 3계층이 일관되게 적용됨 |
| **Room 마이그레이션** | 15개 스키마 버전을 수동 마이그레이션으로 관리, 백필 로직 포함 |
| **코루틴 사용** | `viewModelScope`, `lifecycleScope`, `withContext(IO)` 적절히 사용 |
| **보안** | 이미지 경로 검증, ZIP bomb 방어, 파일 크기 제한 구현 |
| **Excel 왕복** | 코드 기반 안정 식별자로 import/export 라운드트립 지원 |
| **DiffUtil** | RecyclerView 어댑터에서 DiffUtil 일관 사용 |
| **백그라운드 작업** | WorkManager로 생일 알림, 자동 백업 적절히 스케줄링 |
| **Fragment 생명주기** | `_binding` null 체크, `isAdded` 검사가 전반적으로 잘 적용됨 |
| **데이터 무결성** | `withTransaction`으로 관련 데이터 원자적 처리 |

### 5.2 개선 권장 사항

| 영역 | 현재 상태 | 권장 |
|------|-----------|------|
| **DI (의존성 주입)** | `Application` 클래스에서 수동 생성 | Hilt 또는 Koin 도입으로 테스트 용이성 향상 |
| **이미지 로딩** | `BitmapFactory.decodeFile()` 직접 사용 | Coil 또는 Glide 라이브러리로 메모리 관리 위임 |
| **테스트** | 테스트 파일 미발견 | Unit/Integration 테스트 추가 필수 (특히 마이그레이션, 수식 평가기) |
| **에러 처리** | 대부분 `catch (e: Exception)` 사용 | 구체적 예외 타입 명시 |
| **접근성** | `contentDescription` 미설정 다수 | TalkBack 호환을 위해 이미지 뷰에 설명 추가 |

---

## 6. 우선순위 요약

| 우선순위 | 항목 | 영향도 |
|---------|------|--------|
| **P0** | 위젯 `exported="true"` 수정 | Android 12+ 위젯 완전 비작동 |
| **P0** | `loadAllStats()` fieldAnalysisStats 추가 | 필드 분석 화면 데이터 없음 |
| **P0** | CipherInputStream GCM 태그 검증 보장 | 백업 무결성 미보장 |
| **P1** | AutoBackup 이미지 필드 내보내기 추가 | 자동 백업 시 이미지 설정 손실 |
| **P1** | 관계 변화 가져오기 upsert 적용 | 중복 데이터 삽입 |
| **P1** | FormulaEvaluator 순환 참조 감지 | 무한 루프 가능성 |
| **P1** | KeyStore 키 무결성 검사 | 백업 복구 불가 시 사용자 미인지 |
| **P2** | 검색 디바운스 코루틴 통일 | 코드 일관성 |
| **P2** | Novel.imageCharacterId FK 추가 | 댕글링 참조 |
| **P2** | 이미지 라이브러리 도입 | 메모리 효율, OOM 방지 |
| **P2** | 대규모 임포트 트랜잭션 분리 | ANR/OOM 방지 |
| **P3** | Path 객체 재사용 | 미세 성능 향상 |
| **P3** | 하드코딩 문자열 리소스화 | i18n 준비 |
| **P3** | 테스트 코드 추가 | 장기적 품질 보장 |
