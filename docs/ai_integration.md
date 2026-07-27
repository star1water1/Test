# AI 연동 아키텍처 (토대 MVP)

## 목적

사용자가 **본인 명의의 API 키(BYOK)** 를 등록해, 앱의 인앱 작업·관리를 AI가 보조하게 하는
기능의 **토대 계층**이다. 이 문서는 구조와 경계, 그리고 이후 기능이 얹히는 방법을 기록한다.

> 개발 의도와의 관계: "가능한 모든 AI"는 프리셋 나열이 아니라 **프로토콜 추상화**로 달성한다.
> OpenAI 호환 프로토콜 하나로 OpenAI·OpenRouter·Groq·DeepSeek는 물론 목록에 없는 서비스까지
> 커스텀 등록으로 수용된다(원칙 01 — 열린 구조). 클로드는 Anthropic 전용 프로토콜로,
> Gemini는 전용 프로토콜로 지원한다.

## 구조 (`com.novelcharacter.app.ai`)

```
[인앱 기능들: 필드 제안·설명 초안·정합성 해설·이름 생성·작업 보조 …(예정)]
        │  AiRequest(system, messages, maxTokens) 만 만들면 됨
        ▼
   AiService  ←──────── 단일 관문 (suspend complete / testConnection)
        │
        ├─ AiProviderStore   프로바이더 설정 CRUD + 활성 선택 (SharedPreferences, 수동 JSON)
        ├─ AiKeyStore        API 키 암호화 저장 (Android Keystore AES-256-GCM)
        └─ AiProtocolCodec   프로토콜별 요청 조립·응답/오류 해석 (순수 JVM, 단위 테스트 대상)
                │
                ├─ ANTHROPIC      POST {base}/v1/messages
                ├─ OPENAI_COMPAT  POST {base}(/v1)/chat/completions
                └─ GEMINI         POST {base}/v1beta/models/{model}:generateContent
```

- **UI**: `ui/settings/AiSettingsFragment` (설정 → AI 연동). 프리셋 선택 → 편집 다이얼로그
  (발급 가이드 + 콘솔 딥링크 + 연결 테스트 + 검증 저장) → 목록에서 활성 선택/편집/삭제.
- **프리셋**: `AiPresets` — 데이터 주도. 새 서비스 지원 = 항목 1개 추가. 생성된 설정은
  전 필드 편집 가능(프리셋은 읽기 전용이 아님).

## 보안 경계 (절대 규칙)

1. **API 키는 `AiKeyStore` 밖으로 저장되지 않는다.** 설정 JSON·로그·오류 메시지에 키를 넣지 말 것.
2. 키는 Android Keystore 마스터 키(AES-256-GCM)로 암호화되어 `ai_keys` prefs에 저장된다.
   식별용으로 끝 4자 힌트만 평문 보관.
3. **백업·엑셀 내보내기에 키가 포함될 경로가 없어야 한다.** 현재 인앱 백업은 DB→xlsx 방식이라
   prefs를 건드리지 않고, 매니페스트 `allowBackup=false`로 OS 백업도 차단됨. 향후 백업 범위를
   prefs로 확장할 일이 생기면 `ai_keys`·`ai_providers` 는 반드시 제외할 것.
4. 기기 이전 등으로 복호화가 불가하면 조용히 실패하지 않고 `NO_KEY` 로 표면화해
   재등록을 안내한다(변수 제어).
5. 커스텀 서버는 https만 허용(평문 HTTP 차단). 사설망 예외 허용은 향후 검토 항목.

## 오류 처리 계약 (변수 제어)

`AiService` 는 예외를 던지지 않고 `AiResult.Failure(kind, detail, httpCode)` 를 돌려준다.
호출측 UI는 **결과를 반드시 사용자에게 보여줄 것** — `AiErrorMessages.of(context, failure)` 가
분류별 안내문 + 교정 경로 + 제공사 원문을 만들어 준다. 조용히 버리는 것 금지.

자동 교정: OpenAI 신형 모델이 `max_tokens` 를 거부하면 `max_completion_tokens` 로 1회
자동 재시도한다(사용자는 모름). 붙여넣은 키의 앞뒤 공백·개행은 저장 시 자동 정리.

## 출력 토큰 상한 정책 (`AiTokenPolicy`)

**상한을 두지 않는 선택지는 없다** — Anthropic Messages API는 `max_tokens`가 **필수**다.
그래서 문제는 "둘 것인가"가 아니라 **"무엇을 근거로 둘 것인가"**이고, 근거는 세 축이다:

```
실제 요청값 = min( 그 기능이 요구한 값 , 사용자 슬라이더 설정 , 탐지된 모델 상한 )
```

- **사용자 설정** — 설정 → AI 연동 → 프로바이더 편집의 슬라이더(`maxOutputTokens`).
  미설정이면 `DEFAULT_REQUEST = 4096`으로 **종전과 동일하게** 동작한다(회귀 없음).
- **탐지된 모델 상한**(`detectedOutputLimit`) — **정적 표를 두지 않는다**(표는 새 모델마다 낡는다).
  두 경로로 학습한다:
  ① Gemini는 모델 목록 응답에 `outputTokenLimit`을 실어 준다 → `parseModelInfos`가 읽는다.
  ② Anthropic·OpenAI는 목록에 없지만 **상한 초과 400 오류가 실제 상한을 본문에 적어 준다**
     → `parseMaxTokensLimitFromError`가 읽어 1회 재시도하고 설정에 기억한다.
     오판 방지: 요청값보다 작고 `FLOOR` 이상인 수만 후보이며, 상한 관련 문구가 없으면 학습하지 않는다.
- 슬라이더의 **최대값이 곧 탐지된 상한**이라 자동 탐지와 수동 설정이 따로 놀지 않는다.
  Material Slider는 `(valueTo-valueFrom) % stepSize != 0`이면 죽으므로 `sliderMax`/`snapToStep`이
  항상 눈금에 맞춘 값을 준다(탐지값은 임의의 수일 수 있다).

**청킹은 상한에서 파생된다.** 종전 상수 `MAX_TARGETS_PER_REQUEST = 15`는 "4096 대비"라는
주석만 있어 상한이 바뀌면 근거를 잃었다. 이제 `targetsPerRequest(maxTokens)`가 계산하고
(4096 → 15로 기존과 일치), **비용 고지도 같은 값으로 계산해야 한다** — 상수로 고지하면
사용자가 상한을 올렸을 때 "요청 N건" 안내가 거짓이 된다.

**절단은 성공이 아니다.** `stop_reason=max_tokens` / `finish_reason=length` /
`finishReason=MAX_TOKENS`를 **텍스트가 있어도** 읽어 `AiResult.Success.truncated`로 표면화한다.
종전에는 이 신호를 빈 응답일 때만 읽어서, 잘린 응답이 Success로 흐르고 그 뒤 JSON 파싱이
실패해 **"형식 오류 — 다시 시도해 주세요"라는 오진**이 떴다. 재시도해도 결정적으로 같은
결과이므로 안내가 사용자를 헛수고로 보냈다. 이제 원인("출력 상한에 걸려 잘렸습니다")과
교정 경로("대상을 줄이거나 상한을 올리세요")를 말하고, 일부만 받은 경우 몇 개가 빠졌는지
수로 알린다(R-14).

## 이후 기능이 얹히는 방법

새 AI 기능은 어시스턴트 provider처럼 **가산적으로** 추가한다:

1. `AiService(context)` 생성 → `hasUsableProvider()` 로 진입 가드(미설정 시 설정 화면 안내).
2. 캐릭터/작품 데이터를 프롬프트로 조립해 `AiRequest(system=…, userText=…)` 생성.
3. `val result = aiService.complete(request)` → `Success.text` 사용 / `Failure` 는
   `AiErrorMessages` 로 안내.
4. **AI 출력은 항상 사용자가 확인·수정 후 확정**하는 UI로 만들 것(러프 생성 → 정밀 조정,
   원칙 04). 데이터에 바로 쓰지 않는다.

구현된 기능:

- **필드 데이터 라이브러리 AI 정리** (`ai/FieldLibraryAiOrganizer` +
  `ui/fieldlibrary/AiOrganizeSheet`) — 유사값 병합·오탈자 교정·카테고리 제안. 첫 실데이터 AI
  기능으로서 공용 자산을 만들었다: `ai/AiJsonExtractor`(관대 JSON 추출), 청킹·환각 검증·부분 실패
  동반 반환 패턴, 체크리스트 검토→단일 통합 확인→선택 적용 UI 흐름 (docs/field_value_library.md).
- **캐릭터 필드 값 추천** (`ai/CharacterFieldAiSuggester` + `ui/character/AiFieldSuggestSheet`) —
  생일 포함 모든 편집 가능 필드의 값을 추천 이유와 함께 제안. 컨텍스트는 폼의 **라이브 입력값**
  (이름·이명·태그·메모·입력된 필드) + 이미지 태그·소속 세력·관계이며, 절단·조회 실패 결손은
  truncationNotes로 전부 고지한다(R-14). 대상은 요청당 15개로 청킹해 순차 실행 — 응답 절단
  없이 필드 수십 개를 받쳐주고, 실패·파싱 오류는 청크 단위로 격리해 성공분과 동반 반환한다
  (터미널 오류는 잔여 청크 중단). 검증은 SELECT/GRADE 옵션 일치·생일 달력 유효성·숫자 파싱·
  구조화 입력 형식(파트 수·구분자)으로 환각을 드롭하고 드롭 수를 표면화. 진입은 폼 인라인
  ✨ 버튼(필드별)과 폼 상단 버튼(전체, 빈 필드 기본·입력된 필드 포함 선택)의 이중 경로.
  실행은 `CharacterViewModel.runAiSuggest`(회전 생존 — 진행·결과가 화면 재생성을 넘어 복원)가
  수행하고, 적용은 `DynamicFieldFormBuilder.applyRandomValue`로 **폼 위젯에만** 기입 —
  영속화·`__birth` 동기화는 기존 저장 체인이 수행한다.

후보 기능(계획): 캐릭터 설명·서사 초안, 정합성 오류 자연어 해설·교정안,
이름은행 연동 이름 생성, 통계 내러티브, 인앱 작업 보조(상세 요구는 추후 확정).

## 한계·향후 과제

- 단일 턴 요청만 지원(멀티턴 대화·스트리밍은 필요 기능이 생길 때 `AiProtocolCodec` 확장).
  **스트리밍 부재는 서술형 생성의 선행 과제다** — 긴 글은 응답까지 무피드백 대기가 길어진다.
- 사용량(토큰) 집계는 **회당 표시만** 있다(실행 결과에 입력·출력 토큰). 기간 누적 집계는 없음.
- 서술형(긴 TEXT) 필드 작성 보조는 **미구현**. 현재 `CharacterFieldAiSuggester`는 짧은 값
  전용 설계다(요청당 15개 묶음 · 근거 한 문장 · 컨텍스트 값 300자 절단). 산문을 끼워 넣으면
  둘 다 망가지므로 **필드 1개 전용 경로**를 따로 만들어야 한다.
- 필드 **정의** 생성(어떤 필드를 둘지)에는 AI가 없다 — 수동 생성과 고정 프리셋(`PresetTemplates`)뿐.
  "도구를 만들 수 있는 도구"라는 앱 정체성의 정중앙이라 가치가 크다.
- 로컬 LLM(http) 미지원 — https 전용. 필요 시 network security config로 사설망 예외 검토.
- 모델 추천 목록은 시점 고정 — 필드가 자유 입력이므로 기능은 깨지지 않음. 프리셋 갱신은
  `AiPresets` 수정만으로 완료.
