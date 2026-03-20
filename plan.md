# CALCULATED 필드 백분위 및 관련 버그 수정 계획

## 발견된 문제점

### 1. CALCULATED 필드 백분위 미표시 (핵심 버그)
- **증상**: NUMBER 필드(키, 체중)는 "(작품 상위 50%)" 정상 표시, CALCULATED 필드(종합잠재력, 특화잠재력)는 "(자동 계산)"만 표시
- **설정 상태**: 필드 편집 다이얼로그에서 상위 % 표시 ON, 같은 작품 내 기준 체크됨, 저장 완료된 상태
- **코드 분석**: `computePercentileData()`의 CALCULATED 경로가 정적으로는 올바른 것으로 보임. 런타임 디버깅 필요

### 2. 표시 형식 이중 괄호 문제
- CALCULATED 값 표시: `"필드: 값 (자동 계산)"`
- 백분위 추가 시: `"필드: 값 (자동 계산) (작품 상위 50%)"` — 이중 괄호
- 개선 필요: `"필드: 값 (자동 계산, 작품 상위 50%)"` 형태로 통합

### 3. TimeSlider 리셋 시 백분위 손실
- `TimeSliderHelper.resetTimeView()` (line 86)에서 `displayDynamicFields(cachedFields, cachedValues)` 호출 시 percentileData 파라미터 누락
- 시점 보기를 리셋하면 백분위 정보가 모두 사라짐

### 4. 수식 입력 필드 잘림 (UX)
- `max(field('mana_affinity'...` — 수식이 잘림
- 단일 행 EditText로 긴 수식 확인/편집 어려움

---

## 수정 계획

### Step 1: CALCULATED 백분위 디버그 로깅 추가 및 버그 수정
**파일**: `CharacterDetailFragment.kt`

`computePercentileData`의 CALCULATED 경로에 `Log.d` 추가:
- percentile config 파싱 결과
- formula 값
- `evaluator.evaluate(formula)` 결과 (myValue)
- `computeCalculatedValuesForScope` 반환값 (allValues 크기)
- 최종 percentile 값

로그를 통해 정확한 실패 지점을 식별하고 수정. 수정 완료 후 로그 제거.

또한, `computeCalculatedValuesForScope` 호출에 try-catch 추가하여 예기치 않은 에러 방지:
```kotlin
val allValues: List<Double> = if (isCalculated) {
    try {
        computeCalculatedValuesForScope(field, novel.id, null)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to compute calculated values for scope", e)
        emptyList()
    }
} else { ... }
```

### Step 2: 표시 형식 수정 (이중 괄호 해결)
**파일**: `DynamicFieldRenderer.kt` (lines 148-171)

CALCULATED 필드에 백분위가 있는 경우 형식 통합:
```kotlin
val displayValue = if (isCalculated) {
    val computedValue = calculatedResults[field.id]
    val baseValue = computedValue ?: fieldValue.ifEmpty { null }
    val percentileInfo = percentileData[field.id]

    if (baseValue != null) {
        val suffixParts = mutableListOf("자동 계산")
        percentileInfo?.novelPercentile?.let { suffixParts.add("작품 상위 ${"%.0f".format(it)}%") }
        percentileInfo?.universePercentile?.let { suffixParts.add("세계관 상위 ${"%.0f".format(it)}%") }
        "${field.name}: $baseValue (${suffixParts.joinToString(", ")})"
    } else {
        getStringWithArg(R.string.auto_calculated_label, field.name)
    }
} else {
    "${field.name}: ${fieldValue.ifEmpty { "-" }}"
}

// CALCULATED이면 이미 백분위를 포함했으므로 추가하지 않음
val percentileSuffix = if (!isCalculated) {
    percentileData[field.id]?.let { info -> ... } ?: ""
} else ""
```

### Step 3: TimeSlider 리셋 시 백분위 보존
**파일**: `TimeSliderHelper.kt` (line 86) 및 `CharacterDetailFragment.kt`

방법: TimeSliderHelper에 캐시된 percentileData를 보관:
```kotlin
// TimeSliderHelper에 추가
var cachedPercentileData: Map<Long, DynamicFieldRenderer.PercentileInfo> = emptyMap()
```

`CharacterDetailFragment.displayCharacter`에서 설정:
```kotlin
timeSliderHelper.cachedPercentileData = percentileData
```

`resetTimeView`에서 사용:
```kotlin
fun resetTimeView() {
    isTimeViewActive = false
    currentSliderYear = null
    binding.btnResetTimeView.visibility = View.GONE
    binding.timeViewIndicator.visibility = View.GONE
    if (cachedFields.isNotEmpty()) {
        fieldRenderer.displayDynamicFields(cachedFields, cachedValues, cachedPercentileData)
    }
}
```

### Step 4: 수식 입력 필드 다중 행 지원
**파일**: `dialog_field_edit.xml`

수식 EditText를 다중 행으로 변경:
```xml
<com.google.android.material.textfield.TextInputEditText
    android:id="@+id/editFormula"
    ...
    android:inputType="textMultiLine"
    android:minLines="2"
    android:maxLines="4"
    android:gravity="top|start"
    android:scrollbars="vertical" />
```

---

## 수정 순서
1. Step 1 → Step 2 → Step 3 → Step 4
2. 각 단계 완료 후 빌드 확인
3. 모든 수정 완료 후 디버그 로깅 제거
