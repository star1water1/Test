# 동명이인(중복 캐릭터) 감지 및 해결 — 구현 계획

## 개요

캐릭터 저장 시(신규/수정 모두)와 엑셀 임포트 시 동일 이름 캐릭터 존재 여부를 감지하고, 사용자에게 해결 옵션을 제공한다.

---

## Part 1: DAO / Repository / ViewModel 레이어

### 1-1. CharacterDao에 쿼리 추가
**파일:** `app/src/main/java/com/novelcharacter/app/data/dao/CharacterDao.kt`

기존 `getCharacterByName`은 `LIMIT 1`이라 동명이인 시나리오에 부적합. 모든 매칭 캐릭터를 반환하는 새 쿼리 추가:

```kotlin
@Query("SELECT * FROM characters WHERE name = :name")
suspend fun getAllCharactersByName(name: String): List<Character>
```

### 1-2. CharacterRepository에 패스스루 추가
**파일:** `app/src/main/java/com/novelcharacter/app/data/repository/CharacterRepository.kt`

```kotlin
suspend fun getAllCharactersByName(name: String): List<Character> =
    characterDao.getAllCharactersByName(name)
```

### 1-3. CharacterViewModel에 패스스루 추가
**파일:** `app/src/main/java/com/novelcharacter/app/ui/character/CharacterViewModel.kt`

```kotlin
suspend fun getAllCharactersByName(name: String): List<Character> =
    characterRepository.getAllCharactersByName(name)
```

---

## Part 2: 앱 내 저장 플로우 — 중복 감지 다이얼로그

### 2-1. DuplicateCharacterDialog (DialogFragment)
**신규 파일:** `app/src/main/java/com/novelcharacter/app/ui/character/DuplicateCharacterDialog.kt`

- `DialogFragment` 기반, 기존 `FieldEditDialog` 패턴 따름
- 중복 캐릭터 목록을 `RecyclerView`로 표시 (썸네일, 이름, 작품명, 메모 일부)
- **신규 캐릭터 저장 시 (characterId == -1L):** 3가지 선택지
  - "취소" — 저장 중단
  - "기존 캐릭터 업데이트" — 선택한 기존 캐릭터를 현재 입력값으로 덮어씀
  - "동명이인으로 새로 생성" — 새 캐릭터로 삽입
- **기존 캐릭터 수정 시 (characterId != -1L, 이름 변경):** 2가지 선택지
  - "취소" — 저장 중단
  - "그대로 저장 (동명이인)" — 변경된 이름으로 그대로 업데이트
- 후보가 1개일 때 자동 선택, "기존 캐릭터 업데이트" 버튼은 선택 시에만 활성화

### 2-2. 다이얼로그 레이아웃
**신규 파일:** `res/layout/dialog_duplicate_character.xml`
- 제목 텍스트, RecyclerView(후보 목록), 하단 버튼 3개

**신규 파일:** `res/layout/item_duplicate_candidate.xml`
- 행 레이아웃: ImageView(48dp), 이름+작품 TextView, 메모 snippet TextView, 라디오 표시

### 2-3. CharacterEditFragment 통합
**파일:** `app/src/main/java/com/novelcharacter/app/ui/character/CharacterEditFragment.kt`

`setupSaveButton()` (라인 931-1013) 수정. 현재 플로우:
1. 이름 유효성 검사
2. Character 객체 생성
3. characterId에 따라 insert/update
4. 태그 저장, 뒤로가기

변경 플로우:
1. 이름 유효성 검사 (기존 코드)
2. Character 객체 생성 (기존 코드)
3. **[신규]** 코루틴 내에서 `viewModel.getAllCharactersByName(name)` 호출
   - 신규(characterId == -1L): 결과가 비어있지 않으면 다이얼로그 표시
   - 수정(characterId != -1L): 자기 자신 제외 후 결과가 비어있지 않으면 다이얼로그 표시
4. 다이얼로그 결과에 따라:
   - CANCEL: isSaving 리셋, return
   - CREATE_NEW: 기존 insert 경로 실행
   - UPDATE_EXISTING: 선택한 캐릭터 ID로 update 경로 실행
   - SAVE_ANYWAY (수정 시): 기존 update 경로 그대로 실행
5. 태그 저장, 뒤로가기 (기존 코드)

저장 로직을 `performSave()` 메서드로 추출하여 직접 호출과 다이얼로그 콜백 양쪽에서 사용.

---

## Part 3: 엑셀 임포트 — 일괄 충돌 해결

### 3-1. 충돌 데이터 모델
**파일:** `app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt`에 추가

```kotlin
data class CharacterConflict(
    val excelRowIndex: Int,
    val excelName: String,
    val excelNovelTitle: String?,
    val existingCharacters: List<Character>,
    var resolution: ConflictResolution = ConflictResolution.CREATE_NEW,
    var selectedExistingId: Long? = null
)

enum class ConflictResolution {
    SKIP,             // 가져오지 않음
    CREATE_NEW,       // 동명이인으로 새로 생성
    UPDATE_EXISTING   // 선택한 기존 캐릭터 업데이트
}
```

### 3-2. RestoreAnalysis 확장
**파일:** `app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt`

```kotlin
data class RestoreAnalysis(
    val categories: List<CategoryAnalysis>,
    val characterConflicts: List<CharacterConflict> = emptyList()
)
```

### 3-3. analyzeCharacters 확장
코드가 없고 이름으로만 매칭하는 행에서 `getAllCharactersByName` 호출. 매칭 결과가 2개 이상이면 `CharacterConflict`에 수집. `analyzeAll`에서 conflicts를 모아 `RestoreAnalysis`에 포함.

### 3-4. 충돌 해결 다이얼로그 (ExcelImporter에 추가)
**파일:** `app/src/main/java/com/novelcharacter/app/excel/ExcelImporter.kt`

Phase 3(미리보기) → **Phase 3.5(충돌 해결)** → Phase 4(실제 임포트)

`showConflictResolutionDialog()` 구현:
- `suspendCancellableCoroutine` 패턴 (기존 `showRestorePreviewDialog`와 동일)
- AlertDialog에 ScrollView 기반 커스텀 뷰
- 각 충돌 항목마다 RadioGroup:
  - "건너뛰기 (가져오지 않음)"
  - "동명이인으로 새로 생성" (기본값)
  - "기존 캐릭터 업데이트: [이름] ([작품명])" — 기존 매칭 캐릭터당 1개씩
- 확인/취소 버튼

### 3-5. importCharacterRows 수정
**파일:** `app/src/main/java/com/novelcharacter/app/excel/ExcelImportService.kt` (라인 1257-1407)

`importAll`과 `importCharacterRows`에 `resolvedConflicts: Map<Int, CharacterConflict>` 파라미터 추가.

임포트 루프에서 행 인덱스가 `resolvedConflicts`에 있으면:
- `SKIP` → 건너뛰기
- `CREATE_NEW` → 강제 insert (기존 매칭 무시)
- `UPDATE_EXISTING` → `selectedExistingId`로 해당 캐릭터 update

### 3-6. ExcelImporter 플로우 통합
**파일:** `app/src/main/java/com/novelcharacter/app/excel/ExcelImporter.kt` (라인 349 부근)

```
Phase 3: val strategy = showRestorePreviewDialog(analysis) ?: return
Phase 3.5 (NEW):
  val resolvedConflicts = if (analysis.characterConflicts.isNotEmpty()) {
      showConflictResolutionDialog(analysis.characterConflicts) ?: return
  } else emptyMap()
Phase 4: val result = importService.importAll(workbook, options, strategy, resolvedConflicts) { ... }
```

---

## Part 4: 문자열 리소스

**파일:** `app/src/main/res/values/strings.xml`

```xml
<!-- 캐릭터 중복 감지 -->
<string name="duplicate_character_title">동일한 이름의 캐릭터가 존재합니다</string>
<string name="duplicate_character_message">\'%s\' 이름을 가진 캐릭터가 이미 %d명 존재합니다.</string>
<string name="duplicate_action_cancel">취소</string>
<string name="duplicate_action_update">기존 캐릭터 업데이트</string>
<string name="duplicate_action_create_new">동명이인으로 새로 생성</string>
<string name="duplicate_action_save_anyway">그대로 저장 (동명이인)</string>
<string name="duplicate_select_target">업데이트할 캐릭터를 선택하세요</string>
<string name="duplicate_novel_none">작품 미지정</string>

<!-- 임포트 충돌 해결 -->
<string name="import_conflict_title">동명이인 충돌 해결</string>
<string name="import_conflict_message">%d건의 이름 충돌이 발견되었습니다</string>
<string name="import_conflict_skip">건너뛰기 (가져오지 않음)</string>
<string name="import_conflict_create_new">동명이인으로 새로 생성</string>
<string name="import_conflict_update">기존 캐릭터 업데이트: %s (%s)</string>
```

---

## Part 5: 구현 순서

1. **DAO + Repository + ViewModel** — `getAllCharactersByName` 쿼리 체인 추가
2. **문자열 리소스** — 모든 한국어 문자열 추가
3. **DuplicateCharacterDialog** — 다이얼로그 클래스 + 레이아웃 신규 생성
4. **CharacterEditFragment 통합** — `setupSaveButton()` 수정, `performSave()` 추출
5. **ExcelImportService 분석 확장** — 충돌 감지, `CharacterConflict` 데이터 클래스
6. **ExcelImporter 충돌 해결 다이얼로그** — `showConflictResolutionDialog()` 구현
7. **ExcelImporter/ImportService 플로우 통합** — Phase 3.5 추가, `importCharacterRows` 수정
