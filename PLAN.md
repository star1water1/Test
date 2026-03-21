# ZIP 기반 이미지 백업 + 선택 옵션 구현 계획

## 개요
수동 내보내기/가져오기에 이미지 포함 기능과 카테고리별 선택 옵션을 추가한다.
자동 백업(AutoBackupWorker)은 기존 방식 유지 (용량 문제).

## 핵심 설계

### 파일 형식
- **이미지 미포함**: 기존 `.xlsx` 그대로 (하위호환)
- **이미지 포함**: `.zip` (data.xlsx + images/ 폴더)

### 가져오기 자동 감지
- 파일 확장자/매직바이트로 xlsx vs zip 자동 판별
- .zip: 내부 data.xlsx 추출 + images/ 폴더 복원
- .xlsx: 기존 흐름 그대로

---

## 구현 단계

### 1단계: ExportOptions / ImportOptions 데이터 클래스 (새 파일)
**파일**: `excel/ExportOptions.kt`

```kotlin
data class ExportOptions(
    val universes: Boolean = true,
    val novels: Boolean = true,
    val characters: Boolean = true,
    val fieldDefinitions: Boolean = true,
    val timeline: Boolean = true,
    val stateChanges: Boolean = true,
    val relationships: Boolean = true,
    val relationshipChanges: Boolean = true,
    val nameBank: Boolean = true,
    val presetTemplates: Boolean = true,
    val searchPresets: Boolean = true,
    val appSettings: Boolean = true,
    val images: Boolean = false  // 기본 OFF (용량 큼)
) {
    companion object {
        val ALL = ExportOptions()
        val ALL_WITH_IMAGES = ExportOptions(images = true)

        // 다이얼로그 표시용 라벨 목록
        val LABELS = listOf(
            "세계관", "작품", "캐릭터", "필드 정의",
            "사건 연표", "상태 변화", "관계", "관계 변화",
            "이름 은행", "필드 템플릿", "검색 프리셋", "앱 설정",
            "이미지"
        )
    }
}
```

### 2단계: ExcelExporter 수정
**파일**: `excel/ExcelExporter.kt`

변경:
- `exportAll()` → `exportAll(options: ExportOptions = ExportOptions(), ...)`
- 각 export 호출을 options 플래그로 분기
- 이미지 포함 시: xlsx 생성 → ZIP으로 감싸기 (xlsx + images/)
- 이미지 불포함 시: 기존대로 xlsx 반환
- 이미지 ZIP 로직은 WorldPackageExporter 패턴 재사용

핵심 추가 메서드:
```kotlin
private suspend fun wrapWithImages(xlsxFile: File, fileName: String): File {
    // ZIP 생성: data.xlsx + images/ 폴더
    // Character.imagePaths, Universe.imagePath, Novel.imagePath 모두 수집
    // 이미지를 images/{원본파일명} 으로 ZIP에 추가
    // image_map.json 에 {원본경로 → ZIP내경로} 매핑 저장
}
```

### 3단계: ExcelImportService 수정
**파일**: `excel/ExcelImportService.kt`

변경:
- `importAll()` → `importAll(workbook, options: ExportOptions = ExportOptions(), imageMap: Map<String,String> = emptyMap(), ...)`
- 각 import 호출을 options 플래그로 분기
- imageMap: ZIP에서 추출된 이미지의 {원본경로 → 새경로} 매핑
- 캐릭터/세계관/작품 import 시 imagePaths를 remapping

### 4단계: ExcelImporter 수정 (ZIP 감지 + 이미지 추출)
**파일**: `excel/ExcelImporter.kt`

변경:
- `importFromExcel(uri)` 에서 ZIP vs XLSX 자동 감지
- ZIP일 때: 임시 디렉토리에 추출 → data.xlsx 파싱 + images/ 복원
- 이미지 복원: images/ 내 파일을 `filesDir`로 복사, 경로 매핑 생성
- 매핑을 ExcelImportService에 전달

핵심 추가 메서드:
```kotlin
private fun isZipFile(uri: Uri): Boolean  // 매직바이트 PK(0x504B) 검사
private suspend fun importFromZip(uri: Uri)  // ZIP 해제 → xlsx import + 이미지 복원
private fun extractImages(zipFile: File): Map<String, String>  // 이미지 추출 및 경로 매핑
```

### 5단계: 옵션 선택 다이얼로그
**파일**: `ui/settings/SettingsFragment.kt`

내보내기 흐름 변경:
```
[내보내기 클릭]
  → [체크박스 다이얼로그: 카테고리 선택 (전체 기본 선택)]
  → [공유/파일저장 선택]
  → ExcelExporter.exportAll(options)
```

가져오기 흐름 변경:
```
[가져오기 클릭]
  → [파일 선택 (SAF)]
  → [ZIP/XLSX 자동 감지]
  → [체크박스 다이얼로그: 발견된 시트 중 가져올 항목 선택]
  → ExcelImportService.importAll(workbook, options)
```

### 6단계: SAF MIME 타입 + 파일 확장자 변경
- 내보내기(이미지 포함): `application/zip`, 확장자 `.zip`
- 내보내기(이미지 미포함): 기존 xlsx MIME
- 가져오기: `application/zip` + xlsx MIME 모두 허용

### 7단계: 문자열 리소스 추가
**파일**: `res/values/strings.xml`

```xml
<string name="export_options_title">내보내기 항목 선택</string>
<string name="import_options_title">가져오기 항목 선택</string>
<string name="export_select_all">전체 선택</string>
<string name="export_deselect_all">전체 해제</string>
<string name="export_option_images">이미지 (파일 크기 증가)</string>
```

---

## 이미지 경로 재매핑 전략

### 내보내기 시
1. 모든 이미지 경로 수집 (Character.imagePaths + Universe.imagePath + Novel.imagePath)
2. 각 이미지를 `images/{파일명}` 으로 ZIP에 저장
3. `image_map.json` 생성: `{"원본절대경로": "images/파일명"}` 매핑

### 가져오기 시
1. ZIP 내 `image_map.json` 파싱
2. `images/` 폴더의 파일을 `filesDir`로 복사 (새 UUID 파일명)
3. 역매핑 생성: `{"images/파일명": "새절대경로"}`
4. 캐릭터 import 시 imagePaths JSON 내 경로를 역매핑으로 치환
5. 세계관/작품 import 시 imagePath를 역매핑으로 치환

---

## 수정 파일 목록

| 파일 | 변경 내용 |
|------|-----------|
| `excel/ExportOptions.kt` | **신규** - 옵션 데이터 클래스 |
| `excel/ExcelExporter.kt` | options 파라미터 추가, ZIP 래핑 로직 |
| `excel/ExcelImportService.kt` | options 파라미터 추가, 이미지 경로 재매핑 |
| `excel/ExcelImporter.kt` | ZIP 감지, 이미지 추출, 옵션 다이얼로그 |
| `ui/settings/SettingsFragment.kt` | 내보내기 옵션 다이얼로그 추가 |
| `ui/novel/NovelListFragment.kt` | 내보내기 옵션 다이얼로그 추가 |
| `ui/universe/UniverseListFragment.kt` | 내보내기 옵션 다이얼로그 추가 |
| `res/values/strings.xml` | 새 문자열 추가 |

## 자동 백업은 변경 없음
AutoBackupWorker는 기존 방식(암호화 xlsx) 유지. 이미지 미포함.
