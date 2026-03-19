# NovelCharacter 앱 - 기능 및 UI 디자인 리뷰

## 1. 프로젝트 개요

소설/게임 작가를 위한 캐릭터 및 세계관 관리 앱. 세계관 → 소설 → 캐릭터 계층 구조를 기반으로 타임라인, 관계도, 성장 곡선, 이름 은행, 통계 등 풍부한 기능을 제공합니다.

---

## 2. 긍정적 평가 (잘 된 부분)

### 2.1 기능 완성도
- **계층 구조 (세계관 → 소설 → 캐릭터)**: 창작물의 복잡한 구조를 잘 반영한 설계
- **동적 필드 시스템**: 세계관별 커스텀 필드 7종 (TEXT, NUMBER, SELECT, MULTI_TEXT, GRADE, CALCULATED, BODY_SIZE) 지원은 매우 유연하고 강력
- **수식 평가 엔진**: `field('key') + field('key2')`, `sum_all_grades()` 등 계산 필드 지원은 차별화 포인트
- **Excel 가져오기/내보내기**: Apache POI 기반의 양방향 데이터 교환은 전문 사용자에게 필수적
- **자동 백업 + 암호화**: WorkManager 기반 일일 백업, 최근 3개 보관, 암호화 지원

### 2.2 UI 일관성
- **통일된 카드 디자인**: 모든 카드가 12dp corner radius, 2dp elevation으로 일관
- **4dp 간격 시스템**: 4dp, 8dp, 12dp, 16dp 단위의 일관된 spacing
- **빈 상태 패턴**: 64dp 아이콘 (0.4 alpha) + 16sp 제목 + 13sp 힌트 텍스트로 통일
- **FAB 위치**: 모든 목록 화면에서 우하단 16dp 마진으로 일관
- **Material 3 적용**: Toolbar, Card, Button, Slider 등 Material Design 3 컴포넌트 활용

### 2.3 아키텍처
- **MVVM 패턴**: ViewModel + LiveData로 반응형 UI
- **Repository 패턴**: DAO 위에 추상화 계층
- **Safe Navigation**: `navigateSafe()` 커스텀 확장으로 Fragment 파괴 시 크래시 방지
- **DiffUtil**: 모든 어댑터에서 적절한 DiffUtil 콜백으로 효율적 업데이트

---

## 3. 개선이 필요한 부분

### 3.1 [심각] 접근성 (Accessibility)

| 문제 | 위치 | 권장 조치 |
|------|------|-----------|
| ImageView에 contentDescription 누락 | 캐릭터 카드, 상세 화면 등 | 모든 이미지에 의미 있는 설명 추가 |
| 프로그래밍 방식 생성된 뷰에 hardcoded textSize | UniverseListFragment 등 | `sp` 단위 사용 + 시스템 폰트 스케일 존중 |
| 색상만으로 정보 전달 (색각 이상자 미고려) | 등급 색상, 관계 그래프 색상 | 아이콘/패턴 등 보조 수단 추가 |
| 다이얼로그의 시맨틱 구조 부족 | 커스텀 다이얼로그들 | 적절한 heading/label 추가 |
| RecyclerView 아이템 카운트 미공지 | 모든 목록 | TalkBack용 아이템 수 공지 |

### 3.2 [높음] 로딩 상태 불일치

**현재 상황:**
- StatsMainFragment만 ProgressBar 표시 (올바른 패턴)
- 캐릭터 저장 시 버튼만 비활성화 (시각적 피드백 없음)
- Excel 가져오기/내보내기 시 진행률 표시 없음
- 이미지 로딩 시 placeholder만 표시 (로딩 인디케이터 없음)

**권장:** 모든 비동기 작업에 일관된 로딩 UI 적용. Shimmer 효과 또는 ProgressBar 통일.

### 3.3 [높음] 애니메이션 부족

현재 앱에 전환 애니메이션이 거의 없습니다:
- RecyclerView 아이템 삽입/삭제 애니메이션 없음
- Fragment 전환 애니메이션 없음
- CharacterGrowthFragment의 차트 애니메이션(500ms)만 존재

**권장:**
```kotlin
// RecyclerView에 기본 아이템 애니메이터 추가
recyclerView.itemAnimator = DefaultItemAnimator()

// Fragment 전환에 SharedElement 또는 Fade 적용
enterTransition = MaterialFadeThrough()
exitTransition = MaterialFadeThrough()
```

### 3.4 [중간] 검색/디바운스 패턴 불일치

| Fragment | 디바운스 방식 | 문제 |
|----------|-------------|------|
| CharacterListFragment | `Handler` (300ms) | Fragment 파괴 시 Handler 미정리 → 메모리 누수 가능 |
| TimelineFragment | `Job` (300ms) | 올바른 방식 |
| GlobalSearchFragment | `Job` (300ms) | 올바른 방식 |

**권장:** 모든 검색에 `Job` 기반 디바운스로 통일.

```kotlin
private var searchJob: Job? = null

fun onSearchTextChanged(query: String) {
    searchJob?.cancel()
    searchJob = viewLifecycleOwner.lifecycleScope.launch {
        delay(300)
        performSearch(query)
    }
}
```

### 3.5 [중간] 다이얼로그 UX 개선 필요

**현재 문제:**
- 유니버스 색상 선택기: 저장 전 카드에 미리보기 불가
- 색상 HEX 입력 시 유효성 검증이 포커스 해제 시에만 동작
- 커스텀 다이얼로그에서 뷰를 프로그래밍 방식으로 생성 → Material Design 일관성 깨짐
- 다이얼로그 회전 시 상태 유지 안됨

**권장:**
- BottomSheetDialogFragment 또는 별도 Fragment로 전환
- Material ColorPicker 라이브러리 사용 고려
- SavedStateHandle로 다이얼로그 상태 보존

### 3.6 [중간] 비교 모드 UX

**현재 흐름:**
1. 비교 버튼 클릭 → 모드 진입 (시각적 피드백 미흡)
2. 캐릭터 선택 (최대 3개) → 3개 초과 시에야 제한 안내
3. 선택된 캐릭터의 alpha 값만 변경

**개선안:**
- 모드 진입 시 상단에 안내 배너 또는 Snackbar 표시: "비교할 캐릭터를 선택하세요 (최대 3개)"
- 선택 수를 실시간으로 표시: "2/3 선택됨"
- 선택된 캐릭터에 체크마크 오버레이 추가
- FAB를 일시적으로 "비교 시작" 버튼으로 변경

### 3.7 [중간] 위젯 기능 제한

**현재 상태:**
- RecentCharactersWidget: 텍스트만 표시 (이미지 없음, 최대 4개 고정)
- QuickAddWidget: 단순 버튼 2개
- TodayCharacterWidget: 생일 캐릭터 또는 랜덤 표시

**개선안:**
- `RemoteViewsService` + `RemoteViewsFactory`로 스크롤 가능한 목록 위젯
- 캐릭터 썸네일 이미지 표시
- 위젯 클릭 시 해당 캐릭터 상세로 직접 이동 (deeplink)
- 위젯 자동 갱신 트리거 추가

### 3.8 [낮음] 이미지 관리

**현재:** 직접 Bitmap 처리 + ImageView 태그에 저장

**문제:**
- 메모리 압박 시 Bitmap 손실 가능
- LRU 캐시 없음 (썸네일용 LruCache는 존재하나 제한적)
- 대용량 이미지 처리 시 OOM 위험

**권장:** Glide 또는 Coil 도입
```kotlin
// Coil 예시
imageView.load(imageUri) {
    crossfade(true)
    placeholder(R.drawable.ic_character_placeholder)
    error(R.drawable.ic_character_placeholder)
    size(120.dp)
}
```

### 3.9 [낮음] 관계 그래프 개선

**현재:**
- Force-directed 레이아웃 (Fruchterman-Reingold) - 좋은 선택
- 노드 200개 초과 시 자동 요약 모드
- 비동기 레이아웃 계산

**개선 제안:**
- 레이아웃 계산 중 ProgressBar 표시
- 노드 이름 4자 제한 해제 → 줌 레벨에 따라 동적 조절
- 관계 유형별 필터링 UI 추가
- 클러스터링 기능: 소설별로 노드 그룹화
- 미니맵 추가 (대규모 그래프 탐색 시)

---

## 4. 기능 추가 제안

### 4.1 온보딩 / 튜토리얼
현재 첫 실행 시 안내가 없습니다. 복잡한 기능이 많으므로:
- 첫 세계관 생성 시 단계별 가이드
- 주요 기능 하이라이트 (Spotlight 패턴)
- 도움말 화면 또는 FAQ 섹션

### 4.2 다크 모드 색상 최적화
다크 모드는 지원되지만, 커스텀 색상들(등급 색상, 관계 색상 등)이 다크 배경에서도 적절한지 검증이 필요합니다. `values-night/colors.xml`에 다크 모드 전용 색상 정의를 권장합니다.

### 4.3 데이터 시각화 확장
- **캐릭터 관계 타임라인**: 시간에 따른 관계 변화를 시각적으로 표현
- **세계관 지도**: 간단한 2D 맵 에디터로 지역/장소 표시
- **캐릭터 등장 빈도**: 소설/챕터별 캐릭터 등장 히트맵

### 4.4 협업 기능
- 세계관 패키지 (.ncworld) 공유 시 QR 코드 생성
- 클라우드 동기화 (Firebase 또는 자체 서버)

---

## 5. 우선순위 요약

| 우선순위 | 항목 | 영향도 | 난이도 |
|---------|------|--------|--------|
| P0 | 접근성 개선 (contentDescription, 색상 대비) | 높음 | 낮음 |
| P0 | 로딩 상태 일관성 확보 | 높음 | 낮음 |
| P1 | 애니메이션 추가 (Fragment 전환, 리스트 아이템) | 중간 | 낮음 |
| P1 | 검색 디바운스 패턴 통일 (Handler → Job) | 중간 | 낮음 |
| P1 | 비교 모드 UX 개선 | 중간 | 중간 |
| P2 | 다이얼로그 → BottomSheet/Fragment 전환 | 중간 | 중간 |
| P2 | 이미지 라이브러리 도입 (Coil/Glide) | 중간 | 중간 |
| P2 | 위젯 고도화 | 낮음 | 높음 |
| P3 | 관계 그래프 고도화 | 낮음 | 높음 |
| P3 | 온보딩 / 튜토리얼 | 낮음 | 중간 |
| P3 | 다크 모드 색상 최적화 | 낮음 | 낮음 |

---

## 6. 전체 평가

**NovelCharacter**는 창작자를 위한 캐릭터 관리 앱으로서 **기능적 완성도가 매우 높습니다**. 특히 동적 필드 시스템, 수식 엔진, Excel 연동, Force-directed 관계 그래프 등은 전문 도구 수준의 기능입니다.

**UI/UX 측면**에서는 Material Design 3를 기반으로 일관된 디자인 시스템을 갖추고 있으나, 접근성, 로딩 상태, 애니메이션 등에서 개선 여지가 있습니다. 위에 제시한 P0/P1 항목들은 비교적 적은 노력으로 사용자 경험을 크게 향상시킬 수 있는 부분입니다.

전반적으로 **견고한 아키텍처와 풍부한 기능을 갖춘 프로젝트**이며, 위의 개선 사항들을 적용하면 프로덕션 수준의 완성도를 달성할 수 있을 것으로 판단됩니다.
