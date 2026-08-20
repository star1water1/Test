package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldAiPolicy
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldDescription
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.FieldValueLibraryConfig
import com.novelcharacter.app.data.model.NarrativeMode
import com.novelcharacter.app.data.model.RandomConfig
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.novelcharacter.app.util.DuelAiContext
import com.novelcharacter.app.util.FieldValueTokenizer

/**
 * 캐릭터 필드 값 AI 추천 — 생일 포함 모든 편집 가능 필드의 값을 추천 이유와 함께 제안한다.
 *
 * 계약 (docs/ai_integration.md):
 * - 온디맨드 전용. 호출측이 hasUsableProvider() 가드 + 실행 전 비용(요청 수) 고지.
 * - AI 출력은 절대 자동 적용하지 않는다 — 검토 UI에서 사용자가 선택 적용(폼 위젯에만 기입).
 * - 검증(변수 제어): 형식·옵션에 맞지 않는 제안은 드롭하고 드롭 수를 보고한다.
 * - 컨텍스트 절단은 조용히 하지 않는다 — truncationNotes로 전부 표면화 (R-14).
 *
 * **표기 기조(일관성)**: 캐릭터 한 명의 정보만 주면 모델은 이 작품이 그 필드를 어떤 표기·
 * 상세도로 써 왔는지 알 길이 없어, 기존 값들과 어긋난 표기("짙은 밤하늘빛 흑청색")를
 * 만들어 낸다. 그래서 각 대상 필드마다 **값 라이브러리(field_value_entries)의 기존 값 예시**를
 * 함께 싣는다 — 라이브러리는 이미 필드별로 중복 없이(UNIQUE) 정규화된 카탈로그이고
 * 사용 빈도까지 들고 있어, 캐릭터 전체를 훑지 않고 1쿼리로 "이 작품의 기조"를 얻는다.
 * 값 전량이 아니라 [selectUsageExamples]가 고른 소수만 실어 토큰을 통제한다.
 * 사용자가 그 값에 **설명**을 써 두었으면 뜻도 함께 간다 — 값 문자열만으로는 '북부'가
 * 무엇을 가리키는지 알 수 없어 *"같은 뜻이면 기존 값을 쓴다"*는 지시가 성립하지 않는다
 * (B-46). 예산은 목록과 분리돼 있어 설명이 길어도 허용 목록이 짧아지지 않는다
 * ([selectUsageDescriptions]).
 * 응답 쪽도 같은 카탈로그로 접는다: 별칭 표기는 canonical로 교정하고(값 분열 방지),
 * `inputMode=restricted` 필드의 목록 밖 값은 **버리지 않고 '목록 밖'으로 표시해 후보로 낸다**
 * (B-79 — 처분은 저장 경로가 이미 하는 그것이다: 추가하고 저장 / 입력 수정).
 *
 * 프롬프트 조립·응답 파싱은 AiService 미호출 순수 함수로 분리되어 단위 테스트된다.
 */
class CharacterFieldAiSuggester(private val aiService: AiService) {

    /** 프롬프트에 넣을 캐릭터 컨텍스트 스냅샷 — 호출측(편집 화면)이 라이브 위젯+DB에서 조립 */
    data class CharacterAiContext(
        val name: String,
        val aliases: List<String>,
        val tags: List<String>,
        val memo: String,
        /** (필드 표시명, 현재 값) — 폼의 라이브 입력값. 추천 대상 필드는 프롬프트 조립 시 제외된다 */
        val filledFields: List<Pair<String, String>>,
        val imageTags: List<String>,
        /** 활성 소속 세력명 목록 */
        val factions: List<String>,
        /** "상대이름 – 관계유형" 요약 목록 */
        val relationships: List<String>,
        /**
         * 대결 축별 이 캐릭터의 자리 (B-104 소비처 ⓔ). 조립 규칙은 [DuelAiContext]가 단일 소스다.
         *
         * **다른 섹션과 성격이 다르다** — 태그·메모·필드값은 사용자가 *적은* 것이지만 이쪽은
         * 어느 칸에도 적혀 있지 않다. 둘씩 비교해 고른 것이 쌓여 생긴 서수 데이터라,
         * 이 줄이 없으면 모델은 사용자가 이미 정해 둔 우열을 모른 채 값을 지어낸다.
         */
        val duelStandings: List<DuelAiContext.Standing> = emptyList(),
        /** 조회에 실패해 프롬프트에서 빠진 섹션명 — 절단 고지와 같은 경로로 표면화 (변수 제어) */
        val loadFailures: List<String> = emptyList()
    )

    /** 추천 대상 필드 스펙 — [fieldSpecOf]로 FieldDefinition에서 파생 */
    data class FieldSpec(
        val key: String,
        val name: String,
        val type: FieldType,
        /** SELECT/GRADE 실제 옵션 — 응답 검증의 기준 */
        val options: List<String>,
        val isBirthDate: Boolean,
        /** NUMBER 랜덤 설정의 min~max (있으면 프롬프트 힌트로만 사용) */
        val numberRange: Pair<Double, Double>?,
        /** 현재 입력값 — 덮어쓰기 제안 표시·동일값 제안 드롭 기준. 빈 필드는 "" */
        val currentValue: String,
        /**
         * 사용자가 쓴 필드 설명([FieldDescription]) — 이 필드가 뜻하는 바의 정의이자 계약.
         * 대상 필드의 프롬프트 줄에만 실린다(컨텍스트 필드에는 싣지 않는다 — 거기서는 값이 정보다).
         * 프롬프트 적재량 설정과 무관하게 항상 실린다: 설정이 끄는 것은 토큰이지 정확성이 아니다.
         */
        val description: String = "",
        /** 구조화 입력·생일 등 형식 지시 문구 (프롬프트용) */
        val formatHint: String? = null,
        /** 구조화 입력 검증용 구분자·파트 수 — 폼이 구조화 위젯을 렌더하는 필드(TEXT/BODY_SIZE)에만 설정 */
        val structuredSeparator: String? = null,
        val structuredPartCount: Int? = null,
        /** 값 라이브러리 조회 키. 0이면 라이브러리 연동 없음(순수 파싱 테스트 등) */
        val fieldId: Long = 0L,
        /**
         * 이 필드가 값 라이브러리 카탈로그 대상이며 제안이 켜져 있는가.
         * `inputMode=free`(제안 끔)는 사용자가 "이 필드엔 기존 값을 들이대지 마라"고 정한 것이므로
         * 용례도 접기도 하지 않는다 — 자율성은 AI 경로에서도 같은 뜻이어야 한다.
         */
        val libraryEligible: Boolean = false,
        /** 콤마 복수 토큰 필드인가 — 용례·별칭 접기·허용 검증을 토큰 단위로 수행 */
        val multiToken: Boolean = false,
        /** `inputMode=restricted` — 저장 시 검증과 같은 규칙을 추천에도 적용 */
        val restrictedToLibrary: Boolean = false,
        /** 프롬프트에 실을 기존 사용값 예시 (canonical, 중복 없음) — [withLibraryUsage]가 채운다 */
        val usageExamples: List<String> = emptyList(),
        /** 라이브러리에 등재된(숨김 제외) 값 종수 — "N종 중 M개" 고지용 */
        val usageTotal: Int = 0,
        /**
         * 예시 값의 **뜻** — `(값, 설명)`. 사용자가 값 라이브러리에 써 둔
         * [FieldValueEntry.description]이며 [withLibraryUsage]가 채운다 (B-46).
         *
         * [usageExamples]에 **실제로 실린 값만** 그 순서대로 담는다 — 프롬프트에 없는 값의
         * 뜻을 실으면 모델에게 못 쓰는 선택지를 알려 주는 토큰이 된다.
         */
        val usageDescriptions: List<Pair<String, String>> = emptyList(),
        /** 총 예산에 밀려 못 실은 값 설명 수 — 조용히 버리지 않는다 (R-14 고지용) */
        val usageDescriptionsOmitted: Int = 0,
        /** 길이 상한에 잘린 값 설명 수 (R-14 고지용) */
        val usageDescriptionsTruncated: Int = 0,
        /** 변형 표기(값·별칭) → canonical 접기 표. 숨김 엔트리도 포함(저장 검증과 동일 집합) */
        val canonicalByVariant: Map<String, String> = emptyMap(),
        /**
         * 다시 요청할 때 사용자가 덧붙인 지시("더 어둡게", "북부 출신 느낌으로").
         * 첫 요청에는 없고, 검토 화면의 '보완' 경로에서만 채워진다.
         */
        val userInstruction: String? = null,
        /**
         * 사용자가 이미 물린 값 — 다시 요청할 때 같은 답을 되받지 않기 위해 프롬프트에 싣고,
         * 그래도 되풀이하면 응답에서 드롭한다(REPEATED). 재요청이 같은 값을 주면 사용자는
         * 돈만 내고 아무것도 못 얻는다.
         */
        val rejectedValues: List<String> = emptyList()
    )

    data class Suggestion(
        val fieldKey: String,
        val value: String,
        val reason: String,
        /** 모델이 스스로 매긴 근거 강도. **null은 '미표기'**이며 절대 걸러 내지 않는다 */
        val confidence: Confidence? = null,
        /** 검토 화면에서 사용자가 값을 손봤는가 — 표시·기본 선택 판단에 쓴다 */
        val editedByUser: Boolean = false,
        /**
         * `restricted` 필드의 허용 목록 밖 값인가 (B-79). **거른다는 뜻이 아니라 표시한다는 뜻이다** —
         * 채택하면 저장 시 기존 가드가 "추가하고 저장 / 입력 수정"을 묻는다.
         */
        val outsideLibrary: Boolean = false
    )

    /**
     * 제안의 근거 강도 — 모델이 항목마다 스스로 매긴다.
     *
     * 왜 두는가: 최종 채택은 어차피 사용자가 항목별로 체크해서 한다. 그러니 앱이 할 일은
     * "약한 근거를 대신 버리는 것"이 아니라 **얼마나 기댈 만한지 함께 보여 주고, 어디까지
     * 받을지 사용자가 정하게 하는 것**이다(자율성 우선). 기본값은 전부 받기다 —
     * 넓게 받아 놓고 고르는 편이, 못 받은 것을 다시 요청하는 것보다 싸다.
     *
     * 미표기(null)는 걸러 내지 않는다: 강도를 모른다는 이유로 유료 응답을 버리면
     * 모델이 필드를 이름만 바꿔 생략하는 것과 같은 결과가 된다.
     */
    enum class Confidence(val wire: String, val label: String, val rank: Int) {
        HIGH("high", "확실", 3),
        MEDIUM("medium", "추론", 2),
        LOW("low", "추측", 1);

        /** 이 강도가 [floor] 이상인가 — [floor]가 null이면 언제나 참(전부 수용) */
        fun meets(floor: Confidence?): Boolean = floor == null || rank >= floor.rank

        companion object {
            /** 알 수 없는 표기는 null(미표기) — 임의로 등급을 지어내지 않는다 */
            fun fromWire(raw: String?): Confidence? {
                val v = raw?.trim().orEmpty()
                if (v.isEmpty()) return null
                return values().firstOrNull { it.wire.equals(v, ignoreCase = true) }
            }
        }
    }

    /**
     * 요청했는데 제안이 나오지 않은 대상 1건과 그 **사유**.
     *
     * 종전에는 결손이 `droppedCount`(모델이 값을 냈으나 검증에서 떨어진 수) 하나로만 잡혀,
     * **모델이 응답에 아예 넣지 않은 필드**는 어디에도 집계되지 않았다. 그래서 필드 열몇 개를
     * 요청하고 서너 개만 받아도 앱은 "정상"이라 말했고, 사용자는 원인이 모델의 임의 생략인지
     * 검증 드롭인지 요청 실패인지 구별할 방법이 없었다 — 전형적인 조용한 실패다.
     * 이제 [SuggestOutcome.suggestions] + [SuggestOutcome.missing] = 요청 대상 전체가 되어,
     * 빠진 필드는 반드시 사유를 달고 표면화된다 (변수 제어).
     */
    data class MissingField(
        val fieldKey: String,
        val fieldName: String,
        val cause: MissingCause,
        /** 모델이 밝힌 사유(DECLINED)나 검증이 거부한 원문 값(INVALID 등) */
        val detail: String = ""
    ) {
        fun describe(): String = buildString {
            append(fieldName).append(" — ").append(cause.label)
            if (detail.isNotBlank()) {
                append(": ").append(detail.take(MAX_MISSING_DETAIL_CHARS).replace('\n', ' '))
            }
        }
    }

    /** [MissingField]의 사유 — 교정 경로가 서로 다르므로 하나로 뭉뚱그리지 않는다 */
    enum class MissingCause(val label: String) {
        /** 스키마상 내야 할 항목을 모델이 응답에 넣지 않음 (프롬프트 계약 위반) */
        NOT_RETURNED("모델이 응답에 넣지 않음"),

        /** 모델이 value를 비우고 사유를 밝힘 — 계약대로의 '추천 불가' 표기 */
        DECLINED("모델이 추천 불가로 표시"),

        INVALID("형식·옵션에 맞지 않아 제외"),
        // RESTRICTED('허용 목록 밖 값이라 제외')는 B-79로 없앴다 — 목록 밖 값은 이제 결손이
        // 아니라 '목록 밖' 표시가 붙은 후보다. 사유를 남겨 두면 도달할 수 없는 갈래가 된다.
        SAME_AS_CURRENT("현재 값과 같아 제외"),
        DUPLICATE("같은 필드에 중복 제안이라 제외"),
        TRUNCATED("응답이 출력 상한에 잘려 못 받음"),
        UNREADABLE("응답 형식을 해석하지 못함"),
        REQUEST_FAILED("요청이 실패함"),
        NOT_REQUESTED("앞선 결정적 실패로 요청하지 않음"),

        /** 사용자가 정한 근거 강도 기준에 못 미쳐 제외 — 설정을 낮추면 받을 수 있다 */
        BELOW_CONFIDENCE("설정한 근거 강도에 못 미쳐 제외"),

        /** 다시 요청하면서 사용자가 물린 값을 모델이 되풀이함 */
        REPEATED("이미 물린 값을 되풀이해 제외")
    }

    /**
     * 일괄 추천 대상에서 제외된 사유 — 교정 경로가 다르므로 하나로 뭉뚱그리지 않는다.
     * [label]은 비용 고지의 "제외: … N개" 요약에 그대로 쓰인다.
     */
    enum class BulkExcludeCause(val label: String) {
        /** CALCULATED(파생값)·알 수 없는 타입 — 지금도 제외되지만 종전에는 아무도 알려주지 않았다 */
        UNSUPPORTED_TYPE("계산·미지원 타입"),

        /** 사용자가 필드 관리에서 AI 추천을 끔(FieldAiPolicy.SuggestMode.OFF) */
        AI_DISABLED("AI 추천 꺼짐"),

        /**
         * 사용자가 **개별만**으로 둠(FieldAiPolicy.SuggestMode.MANUAL_ONLY) — 끈 것이 아니다.
         * 일괄에서만 빠지고 필드별 ✨은 그대로이므로, 고지도 그렇게 말한다(B-80).
         */
        MANUAL_ONLY("개별 추천만"),

        /** 서술형 필드 — 짧은 값 경로가 아니라 필드별 ✨의 초안·이어쓰기로 작성한다 */
        NARRATIVE_PATH("서술형")
    }

    /** 일괄 추천 대상 1건 — '빈 필드만' 필터가 폼 위젯 id를 쓰므로 fieldId를 함께 나른다 */
    data class BulkTarget(val fieldId: Long, val spec: FieldSpec)

    /**
     * 일괄 추천 대상 산출 결과. 제외분은 버리지 않고 사유별 필드명으로 들고 나온다 —
     * [targets] + [excluded]의 합은 언제나 입력 전체다(조용한 결손 금지).
     */
    data class BulkTargets(
        val targets: List<BulkTarget>,
        val excluded: Map<BulkExcludeCause, List<String>>
    ) {
        val excludedCount: Int get() = excluded.values.sumOf { it.size }
    }

    /**
     * 조립된 사용자 프롬프트 + 그 과정에서 생긴 고지.
     *
     * **companion이 아니라 클래스 본문에 둔다** — 다른 축이 [FieldPromptSource]로 이 타입을
     * 돌려주는데, companion 안의 중첩 타입은 `Outer.Companion.Inner`로만 닿아
     * 시그니처마다 `Companion`이 끼어든다.
     */
    data class PromptBuild(val text: String, val truncationNotes: List<String>)

    /** 값 정규화 결과 — 실패 사유를 잃지 않기 위해 null 대신 사유를 들고 돌아온다 */
    sealed class Normalized {
        /**
         * 통과. [outsideLibrary]는 `restricted` 필드의 허용 목록 밖 값이라는 **표시**이지
         * 거부가 아니다 (B-79) — 저장 경로가 손 입력에 대해 하는 처분과 같은 자리에 둔다.
         */
        data class Ok(val value: String, val outsideLibrary: Boolean = false) : Normalized()
        data class Rejected(val cause: MissingCause) : Normalized()
    }

    data class SuggestOutcome(
        val suggestions: List<Suggestion>,
        /** 형식·옵션 불일치, 미지 key, 중복 등으로 제외된 제안 수 (조용히 버리지 않고 고지) */
        val droppedCount: Int,
        /** 요청 실패·파싱 실패 메시지 — 부분 실패도 성공분과 함께 반환 */
        val failures: List<String>,
        /** 프롬프트 조립 시 절단된 컨텍스트 고지 (R-14) */
        val truncationNotes: List<String>,
        val inputTokens: Int,
        val outputTokens: Int,
        /**
         * 요청 대상 중 제안이 나오지 않은 전부 — 사유와 함께. [suggestions]와 합치면 요청
         * 대상 전체가 된다(결손 0 보장). 호출측은 이것을 반드시 사용자에게 보여야 한다.
         */
        val missing: List<MissingField> = emptyList(),
        /** 응답에 섞여 온 목록 밖 key(환각) — 드롭 수에도 포함되지만 원인이 달라 따로 고지한다 */
        val unknownKeys: List<String> = emptyList()
    ) {
        /** 요청 대상 수 — 받은 수와 나란히 고지하기 위한 파생값 */
        val requestedCount: Int get() = suggestions.size + missing.size
    }

    /**
     * 대상 필드를 [MAX_TARGETS_PER_REQUEST] 단위로 청킹해 순차 요청한다.
     * 필드 수십 개에서도 응답이 maxTokens에 절단되지 않고(받쳐주는 확장성),
     * 파싱 실패·요청 실패는 해당 청크만 격리되어 성공분과 함께 반환된다.
     * 키·프로바이더 등 결정적 실패는 잔여 청크를 중단한다 (FieldLibraryAiOrganizer 선례).
     */
    suspend fun suggest(
        context: CharacterAiContext,
        targets: List<FieldSpec>,
        minConfidence: Confidence? = null,
        creativity: AiCreativity = AiCreativity.DEFAULT,
        images: List<AiImage> = emptyList(),
        /** 사용자가 고친 양식. 넘기지 않으면 기본 양식이다 (사용자 요청 2026.08.20). */
        templates: PromptTemplates.Source = PromptTemplates.Source.DEFAULTS,
        errorMessageOf: (AiResult.Failure) -> String
    ): SuggestOutcome = suggest(
        prompts = object : FieldPromptSource {
            override fun system(minConfidence: Confidence?, creativity: AiCreativity) =
                buildSystemPrompt(
                    minConfidence, creativity,
                    templates.templateOf(PromptTemplates.Id.CHAR_FIELD_SYSTEM)
                )

            override fun user(targets: List<FieldSpec>) = buildUserPrompt(
                context, targets, templates.templateOf(PromptTemplates.Id.CHAR_FIELD_USER)
            )
        },
        targets = targets,
        minConfidence = minConfidence,
        creativity = creativity,
        images = images,
        errorMessageOf = errorMessageOf
    )

    /**
     * 위 [suggest]의 본체이자 **다른 축이 프롬프트만 갈아 끼우는 입구** (B-43).
     *
     * 여기 있는 것은 프롬프트가 아니라 **실행 규칙**이다 — 청킹, 프로바이더 전환 고지,
     * 부분 실패 격리, 잘린 응답의 사유 교체, 결손 0 보장, 토큰 집계. 그 규칙은 축과 무관하고
     * 무엇보다 **틀렸을 때 조용히 틀린다**(유료 응답이 소리 없이 사라지는 부류). 축마다
     * 베껴 두면 한쪽만 고쳐지고 다른 쪽은 그 사실이 어디에도 드러나지 않는다.
     * 그래서 사건 축은 [EventFieldAiSuggester]가 프롬프트만 들고 이 함수로 들어온다 (R-13).
     */
    suspend fun suggest(
        prompts: FieldPromptSource,
        targets: List<FieldSpec>,
        /** 받아올 최소 근거 강도 (사용자 설정). null이면 강도와 무관하게 전부 받는다 */
        minConfidence: Confidence? = null,
        /** 창작도 (A-4) — 샘플링(temperature) + 지시 2층으로 적용된다. 기본은 무회귀(균형). */
        creativity: AiCreativity = AiCreativity.DEFAULT,
        /**
         * 함께 보낼 캐릭터 이미지 (A-7). 비어 있으면 요청이 종전과 동일하다.
         *
         * **청크마다 다시 실린다** — 대상 필드가 나뉘어도 각 요청이 그 필드를 판단할 근거를
         * 갖고 있어야 하기 때문이다. 첫 요청에만 붙이면 뒤쪽 필드는 그림을 못 본 채 답이
         * 나오고, 사용자에게는 그 차이가 보이지 않는다(조용한 비대칭 금지). 그 대신 **비용
         * 고지가 연인원을 말한다**([AiPromptPolicy.imageSendCount]).
         */
        images: List<AiImage> = emptyList(),
        errorMessageOf: (AiResult.Failure) -> String
    ): SuggestOutcome {
        val suggestions = mutableListOf<Suggestion>()
        var dropped = 0
        val failures = mutableListOf<String>()
        val truncationNotes = mutableListOf<String>()
        val missing = mutableListOf<MissingField>()
        val unknownKeys = mutableListOf<String>()
        var inputTokens = 0
        var outputTokens = 0

        val maxTokens = aiService.effectiveMaxTokens()
        val temperature = aiService.temperatureFor(creativity)
        // 이 모델이 temperature를 거부한다고 이미 학습한 경우 — 창작도를 올렸는데 아무 변화가
        // 없는 이유를 사용자가 알 수 있어야 한다 (§6-5 ④, 조용한 실패 금지)
        if (creativity != AiCreativity.BALANCED && aiService.isTemperatureUnsupported()) {
            failures.add(TEMPERATURE_UNSUPPORTED_NOTE)
        }
        // 이미지도 같다 (A-7) — 이미 거부를 배운 모델이면 붙였어도 나가지 않는다는 사실을 말한다.
        if (images.isNotEmpty() && aiService.isImagesUnsupported()) {
            failures.add(IMAGES_UNSUPPORTED_NOTE)
        }
        val chunks = chunkTargets(targets, maxTokens)
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            val prompt = prompts.user(chunk)
            // 청크별 targetNames 차이로 문구가 다를 수 있어 완전 중복만 접는다 (고지 과다는 무해 방향)
            prompt.truncationNotes.forEach { if (it !in truncationNotes) truncationNotes.add(it) }
            val request = AiRequest(
                system = prompts.system(minConfidence, creativity),
                userText = prompt.text,
                maxTokens = maxTokens,
                temperature = temperature,
                images = images,
                // 이미지 절은 이미지와 한 몸으로 간다 (B-139) — 빼는 경로가 둘이라
                // 시스템 프롬프트에 이어 붙이면 반드시 한쪽이 샌다.
                imageSystemRule = imageRule(images.size)
            )
            when (val result = aiService.complete(request)) {
                is AiResult.Success -> {
                    inputTokens += result.inputTokens ?: 0
                    outputTokens += result.outputTokens ?: 0
                    // 이번 요청에서 temperature 거부를 학습해 빼고 재시도한 성공 — 같은 고지 한 줄
                    if (result.temperatureOmitted && TEMPERATURE_UNSUPPORTED_NOTE !in failures) {
                        failures.add(TEMPERATURE_UNSUPPORTED_NOTE)
                    }
                    // 이번 요청에서 이미지 거부를 학습해 빼고 재시도한 성공 — 같은 고지 한 줄
                    if (result.imagesOmitted && IMAGES_UNSUPPORTED_NOTE !in failures) {
                        failures.add(IMAGES_UNSUPPORTED_NOTE)
                    }
                    // 한도로 밀려 다른 프로바이더가 답한 청크 (B-108 확정 ⓑ) — 조용히 바꾸면
                    // 사용자는 자기가 고른 모델의 답인 줄 알고 다른 회사 글을 받는다.
                    // 청크마다 뜰 수 있으므로 같은 줄은 한 번만 남긴다.
                    AiProviderFallback.switchNoteOf(result)
                        ?.let { if (it !in failures) failures.add(it) }
                    val parsed = parseResponse(result.text, chunk, minConfidence)
                    if (parsed == null) {
                        // 잘린 응답은 형식 오류가 아니다 — 원인과 교정 경로를 정확히 말해야 한다.
                        // 종전에는 둘 다 "형식 오류 — 다시 시도해 주세요"로 떨어져, 재시도해도
                        // 결정적으로 같은 결과가 나오는 길로 사용자를 보냈다.
                        failures.add(if (result.truncated) truncatedMessage(chunk.size) else PARSE_FAILURE_MESSAGE)
                        val cause =
                            if (result.truncated) MissingCause.TRUNCATED else MissingCause.UNREADABLE
                        chunk.forEach { missing.add(MissingField(it.key, it.name, cause)) }
                    } else {
                        suggestions.addAll(parsed.suggestions)
                        dropped += parsed.droppedCount
                        unknownKeys.addAll(parsed.unknownKeys)
                        // 잘린 응답에서 못 받은 항목은 '모델이 뺀 것'이 아니라 '상한에 잘린 것'이다 —
                        // 사유를 바꿔 달아야 사용자가 상한을 올리는 올바른 교정으로 간다.
                        missing.addAll(
                            if (result.truncated) {
                                parsed.missing.map {
                                    if (it.cause == MissingCause.NOT_RETURNED) {
                                        it.copy(cause = MissingCause.TRUNCATED)
                                    } else it
                                }
                            } else parsed.missing
                        )
                        // 형식은 살아남았어도 잘렸다면 일부 제안이 빠진 것이다 — 조용히 두지 않는다.
                        if (result.truncated) failures.add(truncatedPartialMessage(chunk.size, parsed.suggestions.size))
                    }
                }
                is AiResult.Failure -> {
                    failures.add(errorMessageOf(result))
                    chunk.forEach { missing.add(MissingField(it.key, it.name, MissingCause.REQUEST_FAILED)) }
                    if (result.kind in TERMINAL_ERRORS) {
                        // 잔여 청크는 요청조차 하지 않는다 — 그 사실도 결손으로 남긴다.
                        // 종전에는 여기서 break만 하고 끝나, 뒤쪽 필드들이 흔적 없이 사라졌다.
                        for (rest in chunks.drop(chunkIndex + 1)) {
                            rest.forEach {
                                missing.add(MissingField(it.key, it.name, MissingCause.NOT_REQUESTED))
                            }
                        }
                        break
                    }
                }
            }
        }
        return SuggestOutcome(
            suggestions = suggestions,
            droppedCount = dropped,
            failures = failures,
            truncationNotes = truncationNotes,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            missing = missing,
            unknownKeys = unknownKeys.distinct()
        )
    }

    companion object {
        // 컨텍스트 절단 상한 — 초과분은 truncationNotes로 반드시 고지 (R-14)
        const val MAX_MEMO_CHARS = 1500
        const val MAX_VALUE_CHARS = 300
        const val MAX_TAGS = 50
        const val MAX_RELATIONSHIPS = 30
        const val MAX_FILLED_FIELDS = 60

        /**
         * 필드 설명을 프롬프트에 싣는 길이 상한. 저장 상한([FieldDescription.MAX_CHARS] = 1000)과
         * 다른 이유: 인앱 설명은 길어도 되지만 프롬프트에는 요청당 대상 수만큼 실린다
         * (대략 15 × 300자 ≈ 2~3천 토큰). 초과분은 잘라 싣고 truncationNotes로 고지한다(R-14).
         */
        const val MAX_DESCRIPTION_PROMPT_CHARS = 300

        // ===== 기존 사용값 예시 (표기 기조 전달) 상한 =====
        // 필드 하나당 대략 (개수 × 값 길이) 토큰이 늘어난다. 12개 × 짧은 값이면 필드당 ~50토큰,
        // 요청당 대상 15개 기준 ~750토큰 — 출력 토큰과 달리 한 번만 실리는 입력 비용이라
        // 일관성 이득 대비 감당 가능한 크기다.
        const val MAX_USAGE_EXAMPLES = 12

        /**
         * 예시 하나의 길이 상한. 이보다 긴 값은 '표기 기조'가 아니라 산문이다 —
         * 서술형으로 쓰이는 TEXT 필드(성격·배경)는 라이브러리에 문단이 통째로 등재되므로
         * 이 상한이 없으면 예시 한 줄이 프롬프트를 삼킨다.
         * 단 `restricted` 필드는 목록 자체가 계약이라 이 상한을 적용하지 않는다.
         */
        const val MAX_USAGE_EXAMPLE_VALUE_CHARS = 40

        /** 필드 하나의 예시 총 길이 상한 */
        const val MAX_USAGE_EXAMPLE_TOTAL_CHARS = 240

        // ===== 값 하나의 설명 (값의 뜻 전달) 상한 — B-46 =====

        /**
         * 값 설명([FieldValueEntry.description]) 하나의 길이 상한.
         *
         * 값 설명은 *"이 값이 무슨 뜻인가"*의 주석이지 필드 설명 같은 계약문이 아니다 —
         * 예시 값 자체의 상한([MAX_USAGE_EXAMPLE_VALUE_CHARS], 40)보다 조금 넉넉한 선에서
         * 끊는다. 넘치면 잘라 싣고 자른 사실을 고지한다(R-14).
         */
        const val MAX_USAGE_DESCRIPTION_CHARS = 60

        /**
         * 한 필드가 값 설명에 쓸 수 있는 **총 예산**. 필드 설명의 프롬프트 상한과 **같은 수**다
         * (B-46 등재가 요구한 *"A-2의 필드 설명과 같은 상한 규칙"*) — 참조로 묶어 두어야
         * 한쪽만 바뀌어 예산이 갈리지 않는다.
         *
         * **예시 목록의 예산([MAX_USAGE_EXAMPLE_TOTAL_CHARS])과 분리한 것이 이 상수의 요점이다.**
         * 한 예산을 나눠 쓰면 설명이 긴 필드에서 **허용 값 목록 자체가 짧아진다** — restricted
         * 필드에서 그것은 '목록 밖' 표시를 늘리는 새 결함이지 절약이 아니다.
         */
        const val MAX_USAGE_DESCRIPTION_TOTAL_CHARS = MAX_DESCRIPTION_PROMPT_CHARS

        /**
         * 제안 1건(key + value + 근거 한 문장)의 출력 토큰 추정치.
         * 종전 상수 `MAX_TARGETS_PER_REQUEST = 15`는 "maxTokens 4096 대비"라는 주석만 있고
         * 상한이 바뀌면 근거를 잃었다. 이제 **상한에서 역산**하며, 4096 ÷ 270 = 15로
         * 기존 동작과 정확히 일치한다(회귀 없음).
         */
        const val TOKENS_PER_SUGGESTION = 270

        /** 한 요청에 담는 대상 수의 절대 상한 — 상한을 크게 잡아도 프롬프트가 무한정 길어지지 않게. */
        const val HARD_MAX_TARGETS_PER_REQUEST = 60

        /** 요청당 추천 대상 수 — 출력 상한에서 파생된다. */
        fun targetsPerRequest(maxTokens: Int): Int =
            AiTokenPolicy.itemsPerRequest(maxTokens, TOKENS_PER_SUGGESTION, HARD_MAX_TARGETS_PER_REQUEST)

        // 재시도해도 같은 결과인 실패 — 잔여 청크 중단 기준. 집합은 [AiErrorPolicy]가 단일 소스다
        // (종전에는 네 소비자가 각자 적고 주석으로만 "동일 집합"이라 선언했다 — B-153).
        private val TERMINAL_ERRORS = AiErrorPolicy.TERMINAL

        const val PARSE_FAILURE_MESSAGE = "응답 형식을 해석할 수 없습니다 — 다시 시도해 주세요"

        /** temperature 미지원 모델 고지 (A-4 §6-5 ④) — 빠뜨리면 창작도가 조용히 반쪽이 된다 */
        const val TEMPERATURE_UNSUPPORTED_NOTE =
            "이 모델은 창작도의 샘플링 조절을 지원하지 않아 지시 문구만 적용했습니다"

        /**
         * 이미지 미지원 모델 고지 (A-7). 빠뜨리면 사용자는 그림을 붙였는데 결과가 달라지지
         * 않는 이유를 모른 채 첨부만 계속 켜 둔다 — 돈은 나가고 근거는 안 들어간다.
         */
        const val IMAGES_UNSUPPORTED_NOTE =
            "이 모델이 이미지를 받지 않아 글만 보냈습니다"

        /** 결손 사유에 덧붙이는 원문·모델 사유의 표시 상한 (다이얼로그 한 줄 분량) */
        const val MAX_MISSING_DETAIL_CHARS = 60

        /**
         * 결손 고지에 한 번에 나열할 필드 수. 넘치면 "외 N개"로 접되 **총 수는 반드시 밝힌다**
         * — 접는 것은 표시량이지 사실이 아니다.
         */
        const val MAX_MISSING_LINES = 12

        /** "요청 N개 중 M개 수신" — 결손이 0이어도 수를 밝혀 사용자가 매번 세지 않게 한다 */
        fun receivedSummary(requested: Int, received: Int): String =
            "요청한 필드 ${requested}개 중 ${received}개를 받았습니다"

        /**
         * 결손 명세 — 사유별로 묶어 필드명을 나열한다. 사유가 곧 교정 경로라서(상한을 올려라 /
         * 옵션을 손봐라 / 캐릭터 정보를 더 채워라) 필드명만 나열하는 것으로는 부족하다.
         */
        fun missingLines(missing: List<MissingField>): List<String> {
            if (missing.isEmpty()) return emptyList()
            val lines = missing.take(MAX_MISSING_LINES).map { "· " + it.describe() }
            val rest = missing.size - lines.size
            return if (rest > 0) lines + "· 외 ${rest}개" else lines
        }

        /**
         * '목록 밖' 후보가 섞였을 때의 한 줄 (B-79). null이면 붙이지 않는다.
         *
         * **표식만으로는 부족하다** — 사용자가 알아야 할 것은 "목록 밖이다"가 아니라
         * *"채택하면 어떻게 되는가"*이고, 그 답(저장할 때 라이브러리에 추가할지 묻는다)은
         * 이 줄이 아니면 저장 버튼을 누른 뒤에야 나온다. 결손 고지가 사유와 함께 교정 경로를
         * 대는 것과 같은 취지다.
         */
        fun outsideLibraryLine(suggestions: List<Suggestion>): String? {
            val count = suggestions.count { it.outsideLibrary }
            if (count == 0) return null
            return "· '목록 밖' ${count}개 — 허용 목록에 없는 값입니다. " +
                "채택해 저장하면 라이브러리에 추가할지 묻습니다."
        }

        /** 잘려서 아무것도 못 건진 경우 — '다시 시도'는 같은 결과를 부르므로 안내하지 않는다. */
        fun truncatedMessage(targetCount: Int): String =
            "AI 응답이 출력 상한에 걸려 잘렸습니다(대상 ${targetCount}개). " +
                "한 번에 추천할 필드를 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        /** 잘렸지만 일부는 건진 경우 — 몇 개가 빠졌는지 수로 알린다(R-14). */
        fun truncatedPartialMessage(targetCount: Int, received: Int): String =
            "AI 응답이 출력 상한에 걸려 잘렸습니다 — 대상 ${targetCount}개 중 ${received}개만 받았습니다. " +
                "한 번에 추천할 필드를 줄이거나, 설정 → AI 연동에서 출력 토큰 상한을 올려 주세요."

        /** 개수 기준 단일 분할 — 비용 고지의 요청 수 계산과 반드시 일치해야 한다 (사전 고지 정확성) */
        fun chunkTargets(
            targets: List<FieldSpec>,
            maxTokens: Int = AiTokenPolicy.DEFAULT_REQUEST
        ): List<List<FieldSpec>> = targets.chunked(targetsPerRequest(maxTokens))

        /** [chunkTargets]와 같은 규칙의 요청 수 — 비용 고지용 */
        fun requestCountFor(
            targetCount: Int,
            maxTokens: Int = AiTokenPolicy.DEFAULT_REQUEST
        ): Int {
            if (targetCount <= 0) return 0
            val per = targetsPerRequest(maxTokens)
            return (targetCount + per - 1) / per
        }

        /**
         * **일괄 추천 대상 규칙의 단일 소스** — 편집 화면과 보충(랜덤) 탭이 같은 함수를 쓴다.
         * 호출측이 직접 필터를 조립하면 두 화면이 반드시 갈린다.
         *
         * 서술형 제외는 기존 결함의 수리다: 종전 일괄 경로는 [NarrativeMode.isNarrative]를 보지
         * 않아 성격·배경 같은 서술형 필드가 짧은 값 경로에 섞여 들어갔고, 모델은 "근거 한 문장"
         * 형식에 맞춰 산문 자리에 한 줄짜리 값을 냈다(docs/ai_integration.md가 경로를 나눈 이유가
         * 인라인 ✨에서만 지켜지고 일괄에서는 깨져 있었다).
         *
         * '빈 필드만'/'입력된 필드 포함' 선택은 이 함수의 일이 아니다 — 호출측이 [targets]에
         * 폼 상태를 얹어 거른다(폼 위젯 접근은 순수 계층 밖).
         */
        fun bulkTargetsOf(
            fields: List<FieldDefinition>,
            currentValues: Map<Long, String>
        ): BulkTargets {
            val targets = mutableListOf<BulkTarget>()
            val excluded = LinkedHashMap<BulkExcludeCause, MutableList<String>>()
            fun exclude(cause: BulkExcludeCause, name: String) {
                excluded.getOrPut(cause) { mutableListOf() }.add(name)
            }
            for (field in fields) {
                val spec = fieldSpecOf(field, currentValues[field.id] ?: "")
                when {
                    spec == null -> exclude(BulkExcludeCause.UNSUPPORTED_TYPE, field.name)
                    // 3단(B-80)이라 '일괄에서 빠지는' 사유가 둘이다 — 끈 것과 개별만 받기로 한 것.
                    // 한 사유로 뭉치면 "AI 추천 꺼짐"이 ✨이 멀쩡히 있는 필드까지 가리켜 고지가 거짓이 된다.
                    FieldAiPolicy.suggestMode(field.config) == FieldAiPolicy.SuggestMode.OFF ->
                        exclude(BulkExcludeCause.AI_DISABLED, field.name)
                    !FieldAiPolicy.isBulkTarget(field.config) ->
                        exclude(BulkExcludeCause.MANUAL_ONLY, field.name)
                    NarrativeMode.isNarrative(field) -> exclude(BulkExcludeCause.NARRATIVE_PATH, field.name)
                    else -> targets.add(BulkTarget(field.id, spec))
                }
            }
            return BulkTargets(targets, excluded)
        }

        /** 제외 요약 한 줄 — "AI 추천 꺼짐 3개 · 서술형 2개 · 계산·미지원 타입 1개". 제외가 없으면 null */
        fun bulkExcludedSummary(excluded: Map<BulkExcludeCause, List<String>>): String? {
            val parts = BulkExcludeCause.entries
                .mapNotNull { cause ->
                    excluded[cause]?.takeIf { it.isNotEmpty() }?.let { "${cause.label} ${it.size}개" }
                }
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

        /** 제외 명세에 사유당 나열할 필드명 상한 — 넘치면 "외 N개"로 접되 총 수는 밝힌다 */
        const val MAX_EXCLUDED_NAMES_PER_CAUSE = 12

        /**
         * 제외 상세 — 사유별 필드명 나열. 서술형 줄에는 교정 경로(필드별 ✨)를 병기해
         * 제외가 기능 부재로 읽히지 않게 한다(변수 제어).
         */
        fun bulkExcludedDetailLines(excluded: Map<BulkExcludeCause, List<String>>): List<String> =
            BulkExcludeCause.entries.mapNotNull { cause ->
                val names = excluded[cause]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val shown = names.take(MAX_EXCLUDED_NAMES_PER_CAUSE)
                buildString {
                    append(cause.label).append(' ').append(names.size).append("개: ")
                    append(shown.joinToString(", "))
                    if (names.size > shown.size) append(" 외 ").append(names.size - shown.size).append('개')
                    // 교정 경로를 병기하는 두 사유 — 제외가 '기능 부재'로 읽히지 않게 한다(변수 제어).
                    if (cause == BulkExcludeCause.NARRATIVE_PATH) {
                        append(" — 필드별 ✨에서 초안·이어쓰기로 작성합니다")
                    }
                    if (cause == BulkExcludeCause.MANUAL_ONLY) {
                        append(" — 필드별 ✨을 누르면 이 필드도 추천을 받습니다")
                    }
                }
            }

        /**
         * FieldDefinition → 추천 대상 스펙. CALCULATED(파생값)·알 수 없는 타입은 null.
         * BODY_SIZE 기본 B-W-H와 BIRTH_DATE 월/일 구조화는 폼 빌더(DynamicFieldFormBuilder)의
         * 자동 적용 규칙과 동일하게 형식 힌트를 만든다.
         */
        fun fieldSpecOf(field: FieldDefinition, currentValue: String): FieldSpec? {
            val type = FieldType.fromName(field.type) ?: return null
            if (type == FieldType.CALCULATED) return null
            val isBirth = SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE
            val options = when (type) {
                FieldType.SELECT -> com.novelcharacter.app.util.FieldOptionParser.parseSelectOptions(field.config)
                FieldType.GRADE -> com.novelcharacter.app.util.FieldOptionParser.parseGradeOptions(field.config)
                else -> emptyList()
            }
            val random = RandomConfig.fromConfig(field.config)
            val numberRange = if (type == FieldType.NUMBER && random.min != null && random.max != null) {
                random.min to random.max
            } else null
            // 구조화 입력은 폼이 실제로 파트 위젯을 렌더하는 타입(TEXT/BODY_SIZE)에만 유효 —
            // 그 외 타입(NUMBER 등)의 config 잔존 structuredInput은 힌트·검증 모두 무시한다
            var structured = StructuredInputConfig.fromConfig(field.config)
            if (type == FieldType.BODY_SIZE && !structured.enabled) {
                structured = StructuredInputConfig(
                    enabled = true,
                    separator = "-",
                    parts = listOf(
                        StructuredInputConfig.Part("B", "cm", "number"),
                        StructuredInputConfig.Part("W", "cm", "number"),
                        StructuredInputConfig.Part("H", "cm", "number")
                    )
                )
            }
            val structuredActive = !isBirth && type != FieldType.MULTI_TEXT &&
                (type == FieldType.TEXT || type == FieldType.BODY_SIZE) &&
                structured.enabled && structured.parts.isNotEmpty()
            val formatHint: String? = when {
                isBirth -> "MM-DD (월-일, 예: 03-15)"
                type == FieldType.MULTI_TEXT -> "콤마로 구분한 복수 값 (예: 값1, 값2)"
                structuredActive ->
                    structured.parts.joinToString(structured.separator) { it.label } +
                        " 형식 (구분자 '" + structured.separator + "')"
                else -> null
            }
            // 값 라이브러리 연동 판정은 라이브러리 자신의 단일 소스를 그대로 쓴다 —
            // 여기서 타입 목록을 다시 적으면 라이브러리 대상이 바뀔 때 AI 경로만 어긋난다.
            val libraryConfig = FieldValueLibraryConfig.fromConfig(field.config)
            val libraryEligible = FieldValueTokenizer.supportsLibrary(field) && libraryConfig.isSuggestEnabled
            return FieldSpec(
                key = field.key,
                name = field.name,
                type = type,
                options = options,
                isBirthDate = isBirth,
                numberRange = numberRange,
                currentValue = currentValue,
                description = FieldDescription.fromConfig(field.config),
                formatHint = formatHint,
                structuredSeparator = if (structuredActive) structured.separator else null,
                structuredPartCount = if (structuredActive) structured.parts.size else null,
                fieldId = field.id,
                libraryEligible = libraryEligible,
                multiToken = FieldValueTokenizer.isMultiToken(field),
                restrictedToLibrary = libraryEligible && libraryConfig.isRestricted
            )
        }

        /**
         * 라이브러리 엔트리를 스펙에 싣는다 (순수 — DB 조회는 호출측 VM이 1쿼리로 배치 수행).
         *
         * - 예시는 **숨김 제외**(숨김의 계약이 "입력 제안에서 제외"이고 AI 추천도 입력 제안이다)
         * - 접기 표는 **숨김 포함**(숨김 값도 저장 가능한 값이라 restricted 검증 집합과 같아야 한다)
         * - 별칭보다 canonical이 우선한다(다른 엔트리의 별칭이 이 값과 겹쳐도 자기 자신으로 접힌다)
         *
         * [exampleLimit]은 사용자 설정([AiPromptSettings.usageExampleCount])이다. **0이어도
         * 접기 표는 그대로 만든다** — 설정이 줄이는 것은 프롬프트 적재량(토큰)이지, 별칭 교정과
         * restricted 검증(정확성)이 아니다. 같은 이유로 restricted 필드는 허용 목록이 곧 계약이라
         * 설정이 0이어도 기본 개수만큼은 싣는다(목록을 안 주고 목록 밖이라 드롭할 수는 없다).
         */
        fun withLibraryUsage(
            spec: FieldSpec,
            entries: List<FieldValueEntry>,
            exampleLimit: Int = MAX_USAGE_EXAMPLES
        ): FieldSpec {
            if (!spec.libraryEligible || entries.isEmpty()) return spec
            val canonical = HashMap<String, String>(entries.size * 2)
            for (e in entries) {
                for (alias in e.aliases()) {
                    val a = alias.trim()
                    if (a.isNotEmpty()) canonical.putIfAbsent(a, e.value)
                }
            }
            for (e in entries) canonical[e.value] = e.value  // canonical이 별칭을 이긴다
            val visible = entries.filterNot { it.isHidden }.filter { it.value.isNotBlank() }
            val limit =
                if (spec.restrictedToLibrary) exampleLimit.coerceAtLeast(MAX_USAGE_EXAMPLES) else exampleLimit
            val examples = selectUsageExamples(
                visible,
                limit = limit,
                maxValueChars = if (spec.restrictedToLibrary) Int.MAX_VALUE else MAX_USAGE_EXAMPLE_VALUE_CHARS
            )
            val glosses = selectUsageDescriptions(examples, visible)
            return spec.copy(
                usageExamples = examples,
                usageTotal = visible.size,
                usageDescriptions = glosses.entries,
                usageDescriptionsOmitted = glosses.omitted,
                usageDescriptionsTruncated = glosses.truncated,
                canonicalByVariant = canonical
            )
        }

        /** [selectUsageDescriptions]의 산출 — 실린 것과, 싣지 못한/자른 수(R-14 고지용) */
        data class ValueDescriptions(
            val entries: List<Pair<String, String>> = emptyList(),
            val omitted: Int = 0,
            val truncated: Int = 0
        )

        /**
         * 값 설명 선별 (B-46) — 사용자가 값 라이브러리에 써 둔 *"이 값이 무슨 뜻인지"*를
         * 프롬프트에 싣는다.
         *
         * **왜 필요한가:** 값 문자열만 주면 모델은 '북부'가 이 작품에서 *한랭한 변경*인지
         * *왕도의 북쪽 구역*인지 알 수 없다. 규칙 9가 요구하는 *"같은 뜻이면 기존 값을 쓴다"*와
         * 규칙 10의 *"이 목록에서만 고른다"*는 **뜻을 알아야 지킬 수 있는 지시**다.
         *
         * **왜 예시를 먼저 고르고 설명을 나중에 붙이는가:** 값과 설명을 한 예산에서 함께 자르면
         * 설명이 긴 필드일수록 **허용 값 목록이 짧아진다.** restricted 필드에서 그것은
         * 절약이 아니라 '목록 밖' 표시를 늘리는 결함이다. 그래서 [selectUsageExamples]의
         * 결과는 손대지 않고, 이미 실리기로 정해진 값에만 설명을 얹는다.
         *
         * 순서는 예시 순서를 그대로 따른다 — 앞쪽이 더 대표적인 값이므로 예산이 모자라면
         * 뒤쪽(덜 대표적인 쪽)의 설명부터 빠진다([selectUsageExamples]와 같은 자르기 방향).
         *
         * 숨김 값의 뜻이 새지 않는 것은 **[examples]가 훑기의 축이기 때문**이다(숨김은 예시에서
         * 이미 빠졌다). 호출측이 숨김 제외 집합을 넘기는 것은 그 위의 겹 방어다 — 되돌리기로
         * 재 보면 **둘을 동시에 풀 때만** 빨간불이 뜬다.
         */
        fun selectUsageDescriptions(
            examples: List<String>,
            entries: List<FieldValueEntry>,
            maxChars: Int = MAX_USAGE_DESCRIPTION_CHARS,
            maxTotalChars: Int = MAX_USAGE_DESCRIPTION_TOTAL_CHARS
        ): ValueDescriptions {
            if (examples.isEmpty() || entries.isEmpty()) return ValueDescriptions()
            // 설명은 큐레이션한 사람만 쓴다 — 실사용 표본이 0/482였다(B-46 등재 근거).
            // 그 흔한 경우에 표를 짓지 않도록 먼저 훑어 나간다(맵 할당 0).
            if (entries.none { it.description.isNotBlank() }) return ValueDescriptions()
            val byValue = HashMap<String, String>(entries.size * 2)
            for (e in entries) {
                // 줄바꿈은 여기서 접는다 — 프롬프트 한 줄에 실리므로 원문 개행이 필드 경계를 흉내 낸다
                val d = e.description.replace('\n', ' ').replace('\r', ' ').trim()
                if (d.isNotEmpty()) byValue.putIfAbsent(e.value, d)
            }
            val describable = examples.filter { byValue.containsKey(it) }
            val out = mutableListOf<Pair<String, String>>()
            var chars = 0
            var truncated = 0
            for (value in describable) {
                val raw = byValue.getValue(value)
                val tooLong = raw.length > maxChars
                val text = if (tooLong) raw.take(maxChars) + "…" else raw
                // '값 = 설명 · ' 꼴로 나가므로 구분자 몫까지 세야 예산이 실제 길이와 맞는다
                val cost = value.length + text.length + 6
                if (out.isNotEmpty() && chars + cost > maxTotalChars) break
                out.add(value to text)
                chars += cost
                if (tooLong) truncated++
            }
            return ValueDescriptions(out, describable.size - out.size, truncated)
        }

        /**
         * 예시 선별 — "중복 없이 몇 개"의 구체 규칙.
         *
         * 최다 사용값만 상위 N개 자르면 그 필드의 **폭**이 안 보여서, 모델이 1위 값만 되풀이하거나
         * 반대로 롱테일의 표기 관례를 못 배운다. 그래서 앞 2/3은 빈도 상위(주류 기조),
         * 나머지 1/3은 잔여 목록을 **균등 간격으로 훑어**(분포의 폭) 채운다.
         * 난수를 쓰지 않으므로 같은 데이터면 항상 같은 예시가 나가고 단위 테스트가 가능하다.
         *
         * 정렬은 usageCount 내림차순 → 값 오름차순. usageCount는 최종 일관성 캐시라
         * 라이브러리 화면에 한 번도 안 들어간 사용자는 **전부 0**일 수 있다. 그때 앞 2/3을
         * 그대로 자르면 '가나다순 앞쪽'만 뽑히는 편향이 되므로, 전부 0이면 빈도 구간을 두지 않고
         * 목록 전체를 균등 간격으로 훑는다 (여기서 재계산하지 않는 이유: 재계산은 필드마다
         * 값 전량 스캔+쓰기라, 읽기 경로인 추천이 짊어질 비용이 아니다 — docs/field_value_library.md).
         */
        fun selectUsageExamples(
            entries: List<FieldValueEntry>,
            limit: Int = MAX_USAGE_EXAMPLES,
            maxValueChars: Int = MAX_USAGE_EXAMPLE_VALUE_CHARS,
            maxTotalChars: Int = MAX_USAGE_EXAMPLE_TOTAL_CHARS
        ): List<String> {
            if (limit <= 0) return emptyList()
            val usable = entries.filter { it.value.isNotBlank() && it.value.length <= maxValueChars }
            val sorted = usable.asSequence()
                .sortedWith(compareByDescending<FieldValueEntry> { it.usageCount }.thenBy { it.value })
                .map { it.value }
                .distinct()
                .toList()
            val picked = if (sorted.size <= limit) {
                sorted
            } else {
                // 빈도 정보가 전혀 없으면 상위 구간이라는 개념 자체가 성립하지 않는다 → 전량 균등 훑기
                val headCount = if (usable.none { it.usageCount > 0 }) 0 else (limit * 2 + 2) / 3
                val tailCount = limit - headCount
                val rest = sorted.drop(headCount)
                // rest.size > tailCount 이 보장되므로 간격 ≥ 1 — 같은 값이 두 번 뽑히지 않는다
                val step = rest.size.toDouble() / tailCount
                sorted.take(headCount) +
                    (0 until tailCount).map { rest[(it * step).toInt().coerceAtMost(rest.lastIndex)] }
            }
            // 총 길이 상한 — 넘치는 뒤쪽(덜 대표적인 쪽)부터 자른다. 최소 1개는 남긴다.
            val out = mutableListOf<String>()
            var chars = 0
            for (value in picked) {
                if (out.isNotEmpty() && chars + value.length + 2 > maxTotalChars) break
                out.add(value)
                chars += value.length + 2
            }
            return out
        }

        /**
         * 별칭 표기 → canonical 접기. 라이브러리가 "변형 표기 → 정규값"으로 정의한 매핑을
         * 그대로 쓴다 — 모델이 '흑발'이라 답해도 이 작품의 정규값이 '검은 머리'면 그쪽으로 넣어야
         * 통계·검색·restricted 검증이 갈라지지 않는다. 미등록 표기는 손대지 않는다(새 값 허용).
         */
        fun foldToLibrary(raw: String, spec: FieldSpec): String {
            if (spec.canonicalByVariant.isEmpty()) return raw
            if (!spec.multiToken) return spec.canonicalByVariant[raw.trim()] ?: raw
            // **붙이는 쪽과 같은 규칙이어야 한다** — 아래 join이 구분자를 품은 토큰을 감싸므로,
            // 여기서 옛 규칙으로 쪼개면 감싼 값을 되쪼개 따옴표째 망가뜨린다(B-178).
            val tokens = FieldValueTokenizer.splitMulti(raw)
            if (tokens.isEmpty()) return raw
            // 서로 다른 별칭이 같은 canonical로 접히면 중복이 생긴다 — 접은 뒤 중복 제거
            return FieldValueTokenizer.join(tokens.map { spec.canonicalByVariant[it] ?: it }.distinct())
        }

        /**
         * restricted 필드의 허용 검증 — 저장 시 가드(FieldValueRules.validateRestricted)와 같은 집합.
         * 라이브러리가 **비어 있으면 제한하지 않는다**: 허용 목록을 준 적이 없는데 목록 밖이라고
         * 표시하면 사용자가 고칠 수 없는 표식이 되고, 저장 가드도 그 경우 '추가하고 저장'을 연다.
         *
         * **이 판정의 결과는 이제 드롭이 아니라 표식이다** (B-79) — false여도 값은 후보로 나간다.
         */
        fun isAllowedByLibrary(value: String, spec: FieldSpec): Boolean {
            if (!spec.restrictedToLibrary || spec.canonicalByVariant.isEmpty()) return true
            val tokens = if (spec.multiToken) {
                FieldValueTokenizer.splitMulti(value)
            } else {
                listOf(value.trim())
            }
            return tokens.isNotEmpty() && tokens.all { it in spec.canonicalByVariant }
        }

        /**
         * 시스템 프롬프트 — **글은 [PromptTemplates]에, 값은 여기에.**
         *
         * 사용자가 양식을 고치면 그 글이 그대로 나간다(사용자 요청 2026.08.20). 다만
         * `{{응답형식}}`은 앱이 만들고 빠지면 저장이 거절되므로, 응답을 읽는 계약은 어떤
         * 양식에서도 성립한다.
         *
         * **전량 응답 계약**: 종전 규칙 "근거가 부족해 추천할 수 없는 필드는 응답에서 생략한다"는
         * 모델에게 마음껏 빠뜨릴 재량을 준 지시였다. 캐릭터 한 명의 정보는 원래 성기므로 모델은
         * 대부분의 필드를 '근거 부족'으로 판정했고, 필드 열몇 개를 요청해도 서너 개만 돌아왔다.
         * 출력 토큰을 올리거나 좋은 모델을 써도 달라지지 않는다 — 상한이 아니라 지시가 원인이다.
         *
         * 그래서 계약을 뒤집는다: **요청한 key 전부에 항목을 하나씩** 내되, 정말 정할 수 없으면
         * 생략이 아니라 빈 value + 사유로 표기하게 한다. 앱은 그 사유를 사용자에게 그대로
         * 보여줄 수 있고(변수 제어), 추측성 제안은 검토 UI에서 사용자가 걸러 낸다 — 채택 여부를
         * 가리는 것은 모델이 아니라 사용자다(자율성 우선).
         */
        fun buildSystemPrompt(
            minConfidence: Confidence? = null,
            creativity: AiCreativity = AiCreativity.DEFAULT,
            template: String = PromptTemplates.default(PromptTemplates.Id.CHAR_FIELD_SYSTEM)
        ): String = PromptTokens.expand(
            template,
            mapOf(
                PromptTemplates.T_RESPONSE to
                    PromptTemplates.responseFormat(PromptTemplates.Id.CHAR_FIELD_SYSTEM),
                PromptTemplates.T_CONFIDENCE_RULE to confidenceFloorRule(minConfidence),
                PromptTemplates.T_CREATIVITY_RULE to creativity.promptBlock()
            )
        )

        /**
         * 이미지 첨부 지시 (A-7). **번호 없는 이름표 블록이다** — 규칙 번호는 이제 사용자가
         * 고칠 수 있는 양식 안에 있어서, 앱이 번호를 박으면 반드시 어긋난다. 창작도·근거 강도
         * 지시가 쓰는 것과 같은 형태([AiCreativity.promptBlock])이므로 새 문법도 아니다.
         *
         * 몇 번째 이미지인지 적게 하는 것이 이 지시의 본체다 — 검토 화면의 근거 줄이 그대로
         * 보여 주므로, 사용자는 "이 값이 그림의 어디서 왔는가"를 새 UI 없이 확인한다.
         *
         * **[buildSystemPrompt]가 아니라 [AiRequest.imageSystemRule]로 간다** (B-139):
         * 이미지가 빠지는 경로가 둘인데 시스템 프롬프트에 미리 이어 붙이면 그 둘이 각자
         * 문자열을 걷어내야 하고, 실제로 둘 다 빠뜨려 **없는 그림을 근거로 삼으라는 지시가
         * 그대로 나갔다.** 이제 이미지와 한 몸으로 다녀 빠뜨릴 자리가 없다.
         */
        fun imageRule(imageCount: Int): String =
            if (imageCount <= 0) "" else "\n" + """
            [이미지] 이 요청에는 캐릭터의 이미지 ${imageCount}장이 순서대로 함께 실려 있다.
            글로 적힌 정보와 어긋나지 않는 선에서 그림에서 읽을 수 있는 것(외모·복장·분위기·
            소지품 등)을 근거로 삼아라. 그림에서 읽은 값이면 reason에 **몇 번째 이미지인지**
            '이미지 1'처럼 밝혀라. 그림에 없는 것을 있는 것처럼 적지 마라.
            """.trimIndent()

        /**
         * 근거 강도 하한 지시 — 설정이 '전부 받기'면 빈 글이고, 그러면 그 줄이 통째로 사라진다.
         *
         * 프롬프트와 응답 읽기 **양쪽**에서 거른다: 프롬프트만으로는 모델이 지킨다는 보장이 없고,
         * 읽기만으로는 쓸모없어질 값을 만드느라 출력 토큰을 낭비한다.
         *
         * **번호를 매기지 않는다** — 종전에는 `16.`으로 시작하고 *"규칙 5대로"*를 가리켰는데,
         * 사용자가 양식의 규칙을 열둘로 줄이면 1~12 다음에 16이 오고 스물로 늘리면 16이 두 번
         * 나온다. 가리키는 번호도 함께 틀린다. **두 축이 한 벌을 쓰는 것도 그래서 가능해졌다** —
         * 번호를 안 쓰니 사건 축이 자기 마지막 규칙 번호에 맞춰 사본을 둘 이유가 사라졌다.
         */
        fun confidenceFloorRule(minConfidence: Confidence?): String =
            if (minConfidence == null) "" else
                "[근거 강도 하한] 사용자는 근거 강도 '" + minConfidence.wire + "' 이상만 받기로 " +
                    "정했다. 그보다 낮은 추측은 값을 내지 말고 value를 빈 문자열로 두고 " +
                    "reason에 근거가 얕은 이유를 적어라."

        /**
         * 사용자 프롬프트 — **자르는 규칙은 여기, 이름표와 차례는 양식에.**
         *
         * 종전에는 `if (tags.isNotEmpty())` 가드를 절마다 손으로 걸었다. 그 일을
         * [PromptTokens]가 대신한다 — *자리표가 든 줄은 그 자리표가 전부 비면 줄째로 사라지고,
         * 그렇게 비워진 절은 머리까지 사라진다.* 그래서 `태그: ` 같은 빈 이름표가 나가는 일이
         * 없고, **사용자가 절의 차례를 바꾸거나 이름표를 고쳐도 그 성질이 그대로 유지된다.**
         *
         * 상한·절단·생략 고지는 그대로 이 함수가 든다(R-14) — 무엇을 얼마나 실을지는 양식이
         * 아니라 앱이 정하는 계약이고, 잘라 놓고 말하지 않으면 조용한 결손이 된다.
         */
        fun buildUserPrompt(
            context: CharacterAiContext,
            targets: List<FieldSpec>,
            template: String = PromptTemplates.default(PromptTemplates.Id.CHAR_FIELD_USER)
        ): PromptBuild {
            val notes = mutableListOf<String>()
            // 조회 실패로 빠진 섹션 — 절단과 같은 경로로 반드시 고지 (조용한 결손 금지, R-14)
            context.loadFailures.forEach { notes.add("$it 정보를 불러오지 못함") }

            fun <T> capList(list: List<T>, max: Int, label: String): List<T> =
                if (list.size > max) {
                    notes.add("$label ${list.size - max}건 생략 (상한 ${max}건)")
                    list.take(max)
                } else list

            fun capText(text: String, max: Int, label: String): String =
                if (text.length > max) {
                    notes.add("$label ${max}자 초과분 생략")
                    text.take(max) + "…"
                } else text

            // **사용자가 양식에서 뺀 재료는 만들지 않는다** (R-14) — 만들면 그 재료의 상한
            // 고지가 딸려 나오는데, 뺀 것은 잘린 것이 아니다. `{{태그목록}}`을 지운 사람에게
            // *"태그 3건 생략"*이라고 말하면 보내지도 않은 것을 잘랐다고 하는 셈이다.
            val used = PromptTokens.usedNames(template)
            fun ifUsed(name: String, build: () -> String): String =
                if (name in used) build() else ""

            // 대결 우열 — 어느 필드에도 적혀 있지 않은 정보다(위 duelStandings 주석).
            // 자르는 규칙과 고지 문구 모두 DuelAiContext가 단일 소스다: 서술형 조립기와
            // 각자 자르면 같은 캐릭터가 경로에 따라 다른 축을 받는다.
            val duelText = if ("대결우열" !in used || context.duelStandings.isEmpty()) "" else {
                val duel = DuelAiContext.promptLines(context.duelStandings)
                if (duel.omitted > 0) notes.add(DuelAiContext.omittedNote(duel.omitted))
                duel.lines.joinToString(DuelAiContext.PROMPT_SEPARATOR)
            }

            // 추천 대상 필드는 [입력된 필드]에서 제외 — 대상의 현재 값은 필드 스펙 쪽에 실린다
            val targetNames = targets.mapTo(HashSet()) { it.name }
            val filled = context.filledFields.filter { it.first !in targetNames && it.second.isNotBlank() }
            val filledText = if ("입력된필드표" !in used || filled.isEmpty()) "" else buildString {
                var longValues = 0
                for ((name, value) in capList(filled, MAX_FILLED_FIELDS, "입력된 필드")) {
                    val v = if (value.length > MAX_VALUE_CHARS) {
                        longValues++
                        value.take(MAX_VALUE_CHARS) + "…"
                    } else value
                    append(name).append(": ").append(v).append('\n')
                }
                if (longValues > 0) notes.add("긴 필드값 ${longValues}건을 ${MAX_VALUE_CHARS}자로 절단")
            }.trimEnd('\n')

            val targetSection = StringBuilder().also { appendTargetSection(it, targets, notes) }
                .toString()

            // **자리가 없어 안 실린 재료를 말한다** — 사용자가 뺀 것을 *잘렸다*고 하지 않는 것과
            // 짝이다(R-14의 뒷면). 이것이 없으면 `{{메모}}`를 지운 사람이 추천이 빈약해진
            // 이유를 가릴 수 없다.
            notes.addAll(
                PromptTemplates.unusedMaterialNotes(
                    used,
                    mapOf(
                        "이명목록" to context.aliases.isNotEmpty(),
                        "태그목록" to context.tags.isNotEmpty(),
                        "메모" to context.memo.isNotBlank(),
                        "이미지태그" to context.imageTags.isNotEmpty(),
                        "소속세력" to context.factions.isNotEmpty(),
                        "관계요약" to context.relationships.isNotEmpty(),
                        "대결우열" to context.duelStandings.isNotEmpty(),
                        "입력된필드표" to filled.isNotEmpty()
                    )
                )
            )

            val text = PromptTokens.expand(
                template,
                mapOf(
                    "캐릭터명" to context.name.trim().ifEmpty { "(미정)" },
                    "이명목록" to context.aliases.joinToString(", "),
                    "태그목록" to
                        ifUsed("태그목록") { capList(context.tags, MAX_TAGS, "태그").joinToString(", ") },
                    "메모" to ifUsed("메모") {
                        if (context.memo.isBlank()) "" else capText(context.memo.trim(), MAX_MEMO_CHARS, "메모")
                    },
                    "이미지태그" to ifUsed("이미지태그") {
                        capList(context.imageTags, MAX_TAGS, "이미지 태그").joinToString(", ")
                    },
                    "소속세력" to context.factions.joinToString(", "),
                    "관계요약" to ifUsed("관계요약") {
                        capList(context.relationships, MAX_RELATIONSHIPS, "관계").joinToString(" / ")
                    },
                    "대결우열" to duelText,
                    "입력된필드표" to filledText,
                    PromptTemplates.T_TARGET_FIELDS to targetSection
                )
            )
            return PromptBuild(text, notes)
        }

        /**
         * `[추천할 필드]` 절 — **축이 갈리지 않는 부분**이다 (R-13의 '공통 조립만 공유').
         *
         * 앞의 컨텍스트 블록은 캐릭터냐 사건이냐에 따라 통째로 다르지만, 이 절이 말하는 것은
         * *"이 스펙의 값을 하나씩 내라"*이고 그 계약은 축과 무관하다 — [parseResponse]가
         * 읽는 것도 이 절이 약속한 형태 하나뿐이다. 두 벌로 두면 옵션·형식·기존 사용값·
         * 재요청 맥락의 규칙이 축마다 갈려 **한쪽 화면에서만 검증이 느슨해진다.**
         */
        fun appendTargetSection(
            sb: StringBuilder,
            targets: List<FieldSpec>,
            notes: MutableList<String>
        ) {
            // 개수를 프롬프트에 못 박는다 — 목록만 주면 모델이 '고를 수 있는 만큼'으로 읽는다.
            sb.append("[추천할 필드] 총 ").append(targets.size).append("개 — 아래 ")
                .append(targets.size).append("개 전부에 대해 항목을 내라\n")
            for (t in targets) {
                sb.append("- key: ").append(t.key)
                    .append(" / 이름: ").append(t.name)
                    .append(" / 타입: ").append(typeLabel(t.type))
                if (t.options.isNotEmpty()) sb.append(" / 옵션: ").append(t.options.joinToString(", "))
                t.numberRange?.let { (min, max) ->
                    sb.append(" / 범위: ").append(formatNumber(min)).append('~').append(formatNumber(max))
                }
                t.formatHint?.let { sb.append(" / 형식: ").append(it) }
                // 필드 설명 — 값의 계약(규칙 14). 대상 필드에만 싣는다: [입력된 필드]에서는 값이
                // 정보이고, 설명은 "무엇을 만들지"의 지시라 대상에만 의미가 있다.
                // 저장 상한(1000)과 프롬프트 상한(300)이 다른 이유: 요청마다 대상 수만큼 실리기 때문.
                if (t.description.isNotBlank()) {
                    val desc = t.description.trim()
                    if (desc.length > MAX_DESCRIPTION_PROMPT_CHARS) {
                        notes.add("'${t.name}' 필드 설명 ${MAX_DESCRIPTION_PROMPT_CHARS}자 초과분 생략")
                        sb.append(" / 설명: ").append(desc.take(MAX_DESCRIPTION_PROMPT_CHARS)).append('…')
                    } else {
                        sb.append(" / 설명: ").append(desc)
                    }
                }
                if (t.currentValue.isNotBlank()) {
                    sb.append(" / 현재 값: ").append(t.currentValue.take(MAX_VALUE_CHARS))
                }
                // 표기 기조 — 이 작품이 이 필드를 실제로 어떻게 써 왔는지. 전량이 아니라 선별분이므로
                // 몇 종 중 몇 개인지 함께 적어 모델이 "이게 전부"라고 오해하지 않게 한다.
                if (t.usageExamples.isNotEmpty()) {
                    sb.append(" / 기존 사용값")
                    if (t.usageExamples.size < t.usageTotal) {
                        sb.append("(총 ").append(t.usageTotal).append("종 중 ")
                            .append(t.usageExamples.size).append("개 예시)")
                    }
                    sb.append(": ").append(t.usageExamples.joinToString(", "))
                    if (t.restrictedToLibrary) sb.append(" (이 목록의 값만 허용)")
                }
                // 값의 뜻 (B-46) — **목록과 분리해서** 싣는다. '북부(왕도 이북)'처럼 값에 섞어
                // 넣으면 restricted 필드에서 모델이 그 통짜 문자열을 값으로 되돌려 주고,
                // 그것은 목록에 없으니 '목록 밖'으로 표시된다 — 사용자가 돈을 내고 손질을 산다.
                // 목록은 모델이 **그대로 베껴야 하는 계약**이라 한 글자도 섞지 않는다.
                if (t.usageDescriptions.isNotEmpty()) {
                    sb.append(" / 값 뜻: ").append(
                        t.usageDescriptions.joinToString(" · ") { (value, desc) -> "$value = $desc" }
                    )
                }
                if (t.usageDescriptionsTruncated > 0) {
                    notes.add(
                        "'${t.name}'의 값 설명 ${t.usageDescriptionsTruncated}건을 " +
                            "${MAX_USAGE_DESCRIPTION_CHARS}자로 절단"
                    )
                }
                if (t.usageDescriptionsOmitted > 0) {
                    notes.add(
                        "'${t.name}'의 값 설명 ${t.usageDescriptionsOmitted}건을 싣지 못함 " +
                            "(필드당 상한 ${MAX_USAGE_DESCRIPTION_TOTAL_CHARS}자)"
                    )
                }
                // 재요청 맥락 — 사용자가 무엇을 더 원하고 무엇을 물렸는지. 이 둘이 없으면
                // 재요청은 첫 요청과 같은 프롬프트가 되어 같은 답을 되받는다(과금만 두 번).
                t.userInstruction?.takeIf { it.isNotBlank() }?.let {
                    sb.append(" / 사용자 지시: ").append(it.take(MAX_VALUE_CHARS))
                }
                if (t.rejectedValues.isNotEmpty()) {
                    sb.append(" / 이미 물린 값(다시 내지 말 것): ")
                        .append(t.rejectedValues.joinToString(", ") { it.take(MAX_VALUE_CHARS) })
                }
                // restricted 필드의 허용 목록을 다 싣지 못했으면 조용히 두지 않는다 (R-14).
                // **결손 고지가 아니라 정확도 고지다** — 목록 밖 제안은 B-79 이후 버려지지 않고
                // '목록 밖'으로 표시되지만, 목록을 다 못 준 탓에 그 표시가 늘 수는 있다.
                if (t.restrictedToLibrary && t.usageExamples.size < t.usageTotal) {
                    notes.add(
                        "'${t.name}'의 허용 값 ${t.usageTotal}종 중 ${t.usageExamples.size}개만 예시로 전달 " +
                            "('목록 밖' 표시가 늘 수 있음)"
                    )
                }
                sb.append('\n')
            }
            // 목록 끝에서 개수를 한 번 더 못 박는다 — 긴 목록일수록 앞머리 지시가 희석된다.
            sb.append("위 ").append(targets.size).append("개 필드 각각에 항목을 하나씩, 총 ")
                .append(targets.size).append("개 항목으로 응답하라. ")
                .append("정할 근거가 없는 필드도 빼지 말고 value를 \"\"로 두고 reason에 이유를 적어라.\n")
        }

        data class ParsedSuggestions(
            val suggestions: List<Suggestion>,
            val droppedCount: Int,
            /** 이 청크의 대상 중 제안이 안 나온 전부 — [suggestions]와 합치면 대상 전체가 된다 */
            val missing: List<MissingField> = emptyList(),
            /** 대상 목록에 없는 key(환각) */
            val unknownKeys: List<String> = emptyList()
        )

        /**
         * 응답 파싱 + 실제 필드 정의 기준 검증 (AiService 미호출 — 단위 테스트 대상).
         * 드롭 규칙: 미지 key, 같은 key 중복(첫 건만 채택), SELECT/GRADE 옵션 불일치,
         * NUMBER 비수치(선행 숫자 추출 실패), 생일 형식·달력 위반, 현재 값과 동일한 제안.
         *
         * 빈 value는 드롭이 아니라 **모델이 밝힌 추천 불가**(DECLINED)로 분류한다 — 형식을 어긴
         * 것이 아니라 계약대로 사유를 적어 낸 것이므로, 드롭 수에 섞으면 사유가 사라진다.
         *
         * 반환된 [ParsedSuggestions.missing]은 대상 중 제안이 안 나온 **전부**를 사유와 함께
         * 담는다 — 모델이 응답에 아예 넣지 않은 필드(NOT_RETURNED)까지 포함한다.
         */
        fun parseResponse(
            text: String,
            targets: List<FieldSpec>,
            minConfidence: Confidence? = null
        ): ParsedSuggestions? {
            val root = AiJsonExtractor.extractObject(text) ?: return null
            val arr = root.optJSONArray("suggestions")
            val byKey = targets.associateBy { it.key }
            val seenKeys = mutableSetOf<String>()
            val resolved = mutableSetOf<String>()
            // 같은 key에 사유가 여러 번 붙으면 **첫 사유**를 남긴다(뒤의 중복 항목이 원인을 덮지 않게)
            val causeByKey = LinkedHashMap<String, MissingField>()
            val unknownKeys = mutableListOf<String>()
            var dropped = 0
            val out = mutableListOf<Suggestion>()

            fun note(spec: FieldSpec, cause: MissingCause, detail: String) {
                causeByKey.putIfAbsent(spec.key, MissingField(spec.key, spec.name, cause, detail))
            }

            for (i in 0 until (arr?.length() ?: 0)) {
                val obj = arr?.optJSONObject(i) ?: continue
                val key = obj.optString("key").trim()
                val rawValue = obj.optString("value").trim()
                val reason = obj.optString("reason").trim()
                if (key.isEmpty() && rawValue.isEmpty()) continue
                val spec = byKey[key]
                if (spec == null) {
                    dropped++
                    if (key.isNotEmpty()) unknownKeys.add(key)
                    continue
                }
                if (rawValue.isEmpty()) { note(spec, MissingCause.DECLINED, reason); continue }
                if (!seenKeys.add(key)) {
                    dropped++
                    note(spec, MissingCause.DUPLICATE, rawValue)
                    continue
                }
                val confidence = Confidence.fromWire(obj.optString("confidence"))
                // 미표기(null)는 통과시킨다 — 강도를 모른다는 이유로 버리면 생략과 같은 결과다
                if (confidence != null && !confidence.meets(minConfidence)) {
                    dropped++
                    note(spec, MissingCause.BELOW_CONFIDENCE, "${confidence.label}: $rawValue")
                    continue
                }
                when (val normalized = normalizeChecked(rawValue, spec)) {
                    is Normalized.Ok ->
                        if (normalized.value == spec.currentValue) {
                            dropped++
                            note(spec, MissingCause.SAME_AS_CURRENT, normalized.value)
                        } else if (spec.rejectedValues.any { it.trim() == normalized.value }) {
                            // 사용자가 물린 값을 그대로 되돌려준 재요청 — 받아 봐야 또 물린다
                            dropped++
                            note(spec, MissingCause.REPEATED, normalized.value)
                        } else {
                            out.add(
                                Suggestion(
                                    key, normalized.value, reason, confidence,
                                    outsideLibrary = normalized.outsideLibrary
                                )
                            )
                            resolved.add(key)
                        }
                    is Normalized.Rejected -> {
                        dropped++
                        note(spec, normalized.cause, rawValue)
                    }
                }
            }
            // 제안이 나온 필드는 결손이 아니다 — 뒤따른 중복 제안의 사유가 남아 있어도 지운다
            resolved.forEach { causeByKey.remove(it) }
            val missing = targets.filter { it.key !in resolved }.map {
                causeByKey[it.key] ?: MissingField(it.key, it.name, MissingCause.NOT_RETURNED)
            }
            return ParsedSuggestions(out, dropped, missing, unknownKeys)
        }

        /**
         * 타입별 값 정규화 — 통과 못 하면 null(드롭).
         *
         * 라이브러리 접기를 **타입 검증보다 먼저** 한다: SELECT 옵션 '검은 머리'에 대해 모델이
         * 별칭 '흑발'을 답한 경우, 접고 나서 옳은 옵션으로 통과시키는 편이 드롭보다 낫다.
         * 접은 뒤 restricted 허용 검증을 마지막에 적용한다(접힌 canonical 기준으로 판정).
         */
        fun normalizeValue(raw: String, spec: FieldSpec): String? =
            (normalizeChecked(raw, spec) as? Normalized.Ok)?.value

        /** [normalizeValue]와 같은 판정이되 실패 사유를 들고 돌아온다 — 결손 고지의 근거 */
        fun normalizeChecked(raw: String, spec: FieldSpec): Normalized {
            val folded = foldToLibrary(raw, spec)
            val typed = when {
                spec.isBirthDate -> normalizeBirthDate(folded)
                spec.type == FieldType.SELECT || spec.type == FieldType.GRADE ->
                    matchOption(folded, spec.options)
                spec.type == FieldType.NUMBER -> normalizeNumber(folded)
                spec.structuredPartCount != null -> normalizeStructured(folded, spec)
                else -> folded
            } ?: return Normalized.Rejected(MissingCause.INVALID)
            // B-79 — 목록 밖이라고 버리지 않는다. 손으로는 넣을 수 있는 값을 유료 응답에서만
            // 버리던 비대칭을 없애고, 처분을 저장 경로에 맡긴다(추가하고 저장 / 입력 수정).
            return Normalized.Ok(typed, outsideLibrary = !isAllowedByLibrary(typed, spec))
        }

        /**
         * SELECT/GRADE 옵션 매칭 — 정확 일치 우선, 실패하면 공백·대소문자를 무시한 일치까지 본다.
         * '남 성'/'MALE' 같은 차이는 값이 틀린 것이 아니라 표기가 다른 것이라, 유료 응답을 통째로
         * 버리기보다 옵션 원문으로 교정하는 편이 옳다(유연한 수용). 반환값은 **언제나 옵션 원문**이라
         * 저장값이 옵션 목록 밖으로 새지 않는다.
         */
        fun matchOption(value: String, options: List<String>): String? {
            options.firstOrNull { it == value }?.let { return it }
            val normalized = normalizeForMatch(value)
            if (normalized.isEmpty()) return null
            return options.firstOrNull { normalizeForMatch(it) == normalized }
        }

        private fun normalizeForMatch(value: String): String =
            value.trim().replace(WHITESPACE, "").lowercase(java.util.Locale.ROOT)

        private val WHITESPACE = Regex("\\s+")

        /**
         * 구조화 입력 검증 — 파트 수만큼 구분자로 나뉘고 전 파트가 비어 있지 않아야 통과.
         * 형식 위반 값이 첫 파트에 통째로 들어가 "값--" 꼴로 저장되는 것을 막는다 (KDoc 계약).
         * 분리 규칙은 위젯 쪽(StructuredInputConfig.splitValue)과 동일: 빈 구분자 "-" 폴백, 파트 trim.
         */
        fun normalizeStructured(raw: String, spec: FieldSpec): String? {
            val count = spec.structuredPartCount ?: return raw
            val sep = (spec.structuredSeparator ?: "-").ifEmpty { "-" }
            val parts = raw.trim().split(sep, limit = count).map { it.trim() }
            if (parts.size != count || parts.any { it.isEmpty() }) return null
            return parts.joinToString(sep)
        }

        /** "M-D" 관용 수용 + 달력 유효성(2/29 허용) 검증 후 "MM-DD" 정규화. 실패 시 null */
        fun normalizeBirthDate(raw: String): String? {
            val match = Regex("^(\\d{1,2})-(\\d{1,2})$").find(raw.trim()) ?: return null
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            if (month !in 1..12) return null
            val maxDay = when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                else -> 29
            }
            if (day !in 1..maxDay) return null
            return String.format(java.util.Locale.US, "%02d-%02d", month, day)
        }

        /** 수치 정규화 — 단위가 붙었으면("172cm") 선행 숫자만 추출. 실패 시 null */
        fun normalizeNumber(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.toDoubleOrNull() != null) return trimmed
            return Regex("^-?\\d+(?:\\.\\d+)?").find(trimmed)?.value
        }

        private fun typeLabel(type: FieldType): String = when (type) {
            FieldType.TEXT -> "텍스트"
            FieldType.NUMBER -> "숫자"
            FieldType.SELECT -> "선택형"
            FieldType.MULTI_TEXT -> "복수 텍스트"
            FieldType.GRADE -> "등급"
            FieldType.CALCULATED -> "자동 계산"
            FieldType.BODY_SIZE -> "신체 사이즈"
        }

        private fun formatNumber(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}
