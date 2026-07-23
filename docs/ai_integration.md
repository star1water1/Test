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

## 이후 기능이 얹히는 방법

새 AI 기능은 어시스턴트 provider처럼 **가산적으로** 추가한다:

1. `AiService(context)` 생성 → `hasUsableProvider()` 로 진입 가드(미설정 시 설정 화면 안내).
2. 캐릭터/작품 데이터를 프롬프트로 조립해 `AiRequest(system=…, userText=…)` 생성.
3. `val result = aiService.complete(request)` → `Success.text` 사용 / `Failure` 는
   `AiErrorMessages` 로 안내.
4. **AI 출력은 항상 사용자가 확인·수정 후 확정**하는 UI로 만들 것(러프 생성 → 정밀 조정,
   원칙 04). 데이터에 바로 쓰지 않는다.

후보 기능(계획): 필드 채우기 제안, 캐릭터 설명·서사 초안, 정합성 오류 자연어 해설·교정안,
이름은행 연동 이름 생성, 통계 내러티브, 인앱 작업 보조(상세 요구는 추후 확정).

## 한계·향후 과제

- 단일 턴 요청만 지원(멀티턴 대화·스트리밍은 필요 기능이 생길 때 `AiProtocolCodec` 확장).
- 사용량(토큰) 집계 UI 없음 — `AiResult.Success` 가 토큰 수를 이미 담고 있어 붙이기 쉬움.
- 로컬 LLM(http) 미지원 — https 전용. 필요 시 network security config로 사설망 예외 검토.
- 모델 추천 목록은 시점 고정 — 필드가 자유 입력이므로 기능은 깨지지 않음. 프리셋 갱신은
  `AiPresets` 수정만으로 완료.
