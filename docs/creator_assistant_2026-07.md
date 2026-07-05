# 창작 어시스턴트 (Creator Assistant) — Phase A 설계서 (2026-07)

> 요청: "기능이 많은 만큼 더 **적극적으로 사용자를 서포팅**하는 기능. 외부 API 확장은 선택, 중요한 건 목적."
> 사용자 결정: 코치+검사기+자동화+AI를 **별도 '어시스턴트' 탭**에. 완전 오프라인 원칙 완화(핵심만 오프라인, AI는 온라인 OK).
> 판단 기준(개발 의도): 변수 제어(검증→알림→**교정 경로**), 실질적 인사이트(**입력량 지표 금지**, 원칙 02), 조작 마찰 최소화(원칙 04), 자율성 우선.

## 배경 — 무엇이 문제였나

앱은 통계·데이터 건강·정합성 검증 로직이 이미 풍부하다. 그러나 **전부 수동·수동적**이다: 사용자가 Stats 깊은 곳을 직접 찾아가야만 본다. "지금 뭘 손봐야 하는가"를 앱이 **먼저** 알려주지 않는다. Phase A는 그 능동 신호를 담는 허브를 만든다.

## 아키텍처 — 플러그형 provider 레지스트리 (원칙 01)

```
StatsDataProvider.loadSnapshot()  ─┐
                                    ├─▶ AssistantEngine.run(snapshot)
computeDataHealth / detectPatterns ┘        │  (공통 입력 1회 계산)
                                            ▼
        [ConsistencyProvider][HealthProvider][BiasProvider][NudgeProvider]
                                            │  각자 List<AssistantInsight>
                                            ▼
                       심각도순 병합 → AssistantViewModel → 카드 목록 + 탭 배지
```

- `InsightProvider`는 서로를 모른 채 독립적으로 카드를 만든다. **검사·카드 추가 = provider 추가**(가산적, 테스트 용이).
- 무거운 계산(스냅샷·건강·패턴·정합성)은 엔진이 **한 번만** 수행해 `InsightContext`로 넘긴다 → provider가 재계산하지 않는다(성능·받쳐주는 확장성).
- 재사용 자산: `StatsDataProvider`의 스냅샷 로더·`computeDataHealth`·`detectPatterns`를 **그대로 호출**(로직 중복 0). 상세는 기존 화면(데이터 건강/필드 인사이트/캐릭터 상세)으로 **링크**.

## 2단 모델 — 신뢰 유지

| 단계 | 카테고리 | 톤 | 예 |
|------|----------|-----|-----|
| ① 정합성 문제(오류) | `CONSISTENCY` | 진지, 교정 우선, **탭 배지 집계** | 나이-출생연도 모순, 사망<출생 |
| ② 제안·인사이트 | `HEALTH`·`BIAS`·`NUDGE` | 부드러움, 끌 수 있음 | 편향 패턴, 관계 공백, 임박 생일 |

"이미지 없음/관계 없음"은 오류가 아니라 편집 판단 → 제안으로만. **완성도(fill-rate %)를 헤드라인으로 쓰지 않는다**(원칙 02 안티패턴 회피). 헤드라인은 정합성 오류 + 편향/패턴 인사이트.

## 구현

### 신규
- `ui/assistant/AssistantInsight.kt` — 카드 모델(`InsightCategory`, `InsightAction.Navigate`). `resurfaceValue`로 재노출 판정.
- `util/ConsistencyChecker.kt` — **스냅샷 1개로 배치 검사**(단건 N회 조회 없음). 나이-출생연도 불일치(`detectAgeLinkageConflict`와 동일 규칙, 표준연도 연동 해제 캐릭터 제외), 사망연도<출생연도(`__birth`/`__death` 비교, year 0 제외).
- `ui/assistant/AssistantEngine.kt` — provider 인터페이스·`InsightContext`·심각도 상수·엔진.
- `ui/assistant/AssistantProviders.kt` — 4개 provider.
- `ui/assistant/AssistantPrefs.kt` — 카테고리 on/off + 카드 숨김(악화 시 재노출).
- `ui/assistant/AssistantViewModel.kt` — 스냅샷 로드·엔진 구동·백그라운드 계산. 카드 목록 + 오류 수 LiveData.
- `ui/assistant/AssistantFragment.kt`(+어댑터) — 심각도순 카드, 오버플로(새로고침/표시할 항목), 카테고리 다중선택.
- 레이아웃 `fragment_assistant.xml`·`item_assistant_insight.xml`, 문자열 다수.

### 변경
- `HomeFragment` — ViewPager `getItemCount 3→4` + 어시스턴트 탭(마지막). 랜딩은 세계관(index 0) 유지, 연표=index 1 의존 보존. **탭 배지 = 표시 중 정합성 오류 수**. ViewModel을 부모 스코프로 공유해 스냅샷 1회 로드.

## 자율성 & 피로 방지 (자율성 우선 · 원칙 04)

- **유형별 집계 1카드 + 인원수**(캐릭터마다 카드 X). 심각도 정렬.
- **카테고리 on/off**(4종) + 개별 카드 숨김. 숨김은 영구가 아니라 **상황 악화 시 재노출**(숨긴 시점의 `resurfaceValue`보다 커지면 재등장). 생일은 날짜가 가까워지면 재노출.
- 랜딩 탭을 뺏지 않고 **탭 배지**로만 능동 신호. 매 실행 강요 X.

## 카드 카탈로그

| provider | 카드 | 이동 |
|----------|------|------|
| 정합성 | 나이·출생연도 불일치 / 사망<출생 | 첫 캐릭터 상세(기존 교정 플로우) |
| 건강 | 작품 미배정·관계 없음·사건 없음·중복 태그·빈 관계 설명·이미지 없음 | 데이터 건강 화면 |
| 편향 | `detectPatterns` 결과(쏠림·집중·공백·희소·균형, 커스텀 필드 자동) | 필드 인사이트 화면 |
| 넛지 | 임박 생일·미사용 이름(≥20)·오래 방치(90일+·3명+) | 캐릭터/이름은행 |

## 검증

- 로컬 SDK 부재 → **PR CI(Build APK)**로 컴파일·리소스 링크·패키징.
- 정적: R.string/R.id·레이아웃 ID↔바인딩 교차확인 완료. 스냅샷·`computeDataHealth`·`detectPatterns` 재사용 배선.
- 앱 실측(사용자): 나이 모순·관계 없는 캐릭터 생성 → 정합성/제안 카드·탭 배지·이동·건강 시 격려 화면; 카테고리 끄기·숨기기·재노출.

## 다음 단계

- **Phase B**: 규칙 기반 자동화 제안(관계 추천, 자동 채우기 링크, 로컬 요약).
- **Phase C**: AI 옵트인(온라인) — 이때 완전 오프라인 자세를 "핵심 오프라인 + AI 온라인"으로 명문화하고 INTERNET 권한·암호화 키를 도입한다.

## 문서 이력
| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026.07.05 | Phase A — 어시스턴트 허브(엔진·2단 카드·탭 배지·정합성 스캔) |
