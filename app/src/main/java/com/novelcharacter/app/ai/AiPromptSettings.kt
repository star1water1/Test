package com.novelcharacter.app.ai

import android.content.Context

/**
 * AI 프롬프트에 **얼마나 실을 것인가**의 사용자 설정 (설정 → AI 연동 → 'AI 추천 일관성').
 *
 * 왜 설정으로 여는가: 기존 값 예시·문체 참고는 추천의 일관성을 올리지만 입력 토큰을 쓴다.
 * 그 교환비가 이득인지는 데이터 규모·모델·과금 방식마다 다르므로 개발자가 정할 수 없다
 * (자율성 우선 — 기능의 쓸모는 사용자가 가린다). 기본값은 켜져 있고, 0으로 두면 끈다.
 *
 * 값 범위·기본값·토큰 추정은 [AiPromptPolicy]에 순수 함수로 두어 단위 테스트한다
 * (SharedPreferences는 JVM 러너에서 실행할 수 없다).
 */
class AiPromptSettings(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 짧은 값 추천에 실을 필드별 기존 사용값 예시 개수. 0이면 싣지 않는다. */
    var usageExampleCount: Int
        get() = AiPromptPolicy.clampUsageExamples(
            sp.getInt(KEY_USAGE_EXAMPLES, AiPromptPolicy.USAGE_EXAMPLES_DEFAULT)
        )
        set(value) {
            sp.edit().putInt(KEY_USAGE_EXAMPLES, AiPromptPolicy.clampUsageExamples(value)).apply()
        }

    /** 서술형 작성에 실을 같은 필드의 다른 캐릭터 글(문체 참고) 개수. 0이면 싣지 않는다. */
    var styleSampleCount: Int
        get() = AiPromptPolicy.clampStyleSamples(
            sp.getInt(KEY_STYLE_SAMPLES, AiPromptPolicy.STYLE_SAMPLES_DEFAULT)
        )
        set(value) {
            sp.edit().putInt(KEY_STYLE_SAMPLES, AiPromptPolicy.clampStyleSamples(value)).apply()
        }

    /**
     * 받아올 추천의 **최소 근거 강도**. null이면 강도와 무관하게 전부 받는다(기본값).
     *
     * 프롬프트(모델이 애초에 안 만들게)와 파싱(그래도 오면 사유를 달아 제외)의 양쪽에 쓰인다.
     * 제외된 것은 조용히 사라지지 않고 `MissingCause.BELOW_CONFIDENCE`로 고지되므로,
     * 사용자는 "설정 때문에 빠졌다"는 사실과 되받는 방법을 함께 본다 (변수 제어).
     */
    var minConfidence: CharacterFieldAiSuggester.Confidence?
        get() = AiPromptPolicy.confidenceFromWire(
            sp.getString(KEY_MIN_CONFIDENCE, AiPromptPolicy.confidenceToWire(AiPromptPolicy.CONFIDENCE_DEFAULT))
        )
        set(value) {
            sp.edit().putString(KEY_MIN_CONFIDENCE, AiPromptPolicy.confidenceToWire(value)).apply()
        }

    /**
     * 창작도 (A-4). **값은 하나다** — 설정 화면·비용 고지 칩·보완 재요청 칩·서술형 시트 칩
     * 어디서 바꾸든 같은 값을 바꾼다. "이번 요청만"을 나누면 화면마다 다른 값이 살아 있어
     * 지금 무엇으로 요청되는지 일일이 열어봐야 알 수 있다(원칙 04 위반).
     */
    var creativity: AiCreativity
        get() = AiCreativity.fromWire(sp.getString(KEY_CREATIVITY, null))
        set(value) {
            sp.edit().putString(KEY_CREATIVITY, value.wire).apply()
        }

    /**
     * 필드 추천·서술형 작성에 **함께 보낼 캐릭터 이미지 장수** (A-7). 0이면 보내지 않는다.
     *
     * 기본값 1장은 사용자 확정이다(Q3) — 비용이 붙는 기본값은 보수적으로 두고, 올리는 것을
     * 사용자 선택으로 남긴다. 범위·상한은 [AiPromptPolicy]가 단일 소스다.
     */
    var attachImageCount: Int
        get() = AiPromptPolicy.clampAttachImages(
            sp.getInt(KEY_ATTACH_IMAGES, AiPromptPolicy.ATTACH_IMAGES_DEFAULT)
        )
        set(value) {
            sp.edit().putInt(KEY_ATTACH_IMAGES, AiPromptPolicy.clampAttachImages(value)).apply()
        }

    /**
     * 첨부에 **지정한 대표 이미지를 반드시 첫 장으로** 넣는가 (A-7).
     *
     * 끄면 전부 랜덤이다. 켰는데 지정 대표가 없으면 그 사실을 고지하고 랜덤으로 채운다 —
     * 조용히 다른 그림을 '대표'라 부르지 않는다([util.AiImageAttach]가 판정한다).
     */
    var attachRepresentativeFirst: Boolean
        get() = sp.getBoolean(KEY_ATTACH_REPRESENTATIVE, AiPromptPolicy.ATTACH_REPRESENTATIVE_DEFAULT)
        set(value) {
            sp.edit().putBoolean(KEY_ATTACH_REPRESENTATIVE, value).apply()
        }

    /**
     * **AI 이미지 태그 기조** — 폴더 이름으로 태그를 제안할 때 함께 보내는 사용자 지침
     * (설계 `image_folder_tag_ai` 4-1). 비우면 보내지 않는다.
     *
     * 어휘(기존 태그 + '어휘에 포함' 필드 값)만으로는 "무엇을 태그로 삼을 것인가"의 기준까지
     * 전달되지 않는다 — 같은 폴더 이름에서 장면을 뽑을지 인물 감정을 뽑을지는 작품마다 다르고,
     * 그것은 개발자가 정할 수 없다(자율성 우선). 상한·절단은 [AiPromptPolicy]가 단일 소스다.
     */
    var imageTagPolicy: String
        get() = AiPromptPolicy.clampImageTagPolicy(sp.getString(KEY_IMAGE_TAG_POLICY, null))
        set(value) {
            sp.edit().putString(KEY_IMAGE_TAG_POLICY, AiPromptPolicy.clampImageTagPolicy(value)).apply()
        }

    /**
     * 이미지 **일괄 태깅**에서 한 요청에 실을 장수 (B-121). 사용자가 정한다(설계 2-3).
     *
     * 기억해 두는 이유: 이 값은 폴더 구성처럼 매번 달라지는 것이 아니라 **사용자의 모델·요금제에
     * 달린 취향**이다. 매번 기본값으로 되돌리면 같은 사람이 같은 값을 매번 다시 맞춘다(원칙 04).
     * 비용 고지는 요청 수를 이 값으로 계산해 실행 전에 보인다.
     */
    var imageTagBatchSize: Int
        get() = AiPromptPolicy.clampImageTagBatch(
            sp.getInt(KEY_IMAGE_TAG_BATCH, AiPromptPolicy.IMAGE_TAG_BATCH_DEFAULT)
        )
        set(value) {
            sp.edit().putInt(KEY_IMAGE_TAG_BATCH, AiPromptPolicy.clampImageTagBatch(value)).apply()
        }

    /**
     * 일괄 AI 태깅에서 **링크 묶음마다 표본만 보낼 것인가** — 보낼 장수(비용)의 축이다.
     * 켜면 묶음마다 앞 [imageTagGroupSampleSize]장만 보내고, 끄면 고른 전원을 보낸다.
     *
     * **붙는 범위는 이 스위치와 무관하게 언제나 묶음 전원이다**(태그 공유 불변식 —
     * `LinkGroupFold` 헤더. 전개는 적용 시점의 살아 있는 명단으로 한다). 종전에는 끄면
     * 보낸 장에만 붙어 같은 묶음의 태그가 갈라졌다 — 2026.08.21 사용자 판정으로 갈랐다.
     *
     * 기억해 두는 이유는 [imageTagBatchSize]와 같다 — 묶음을 쓰는 사용자에게는 매번 같은
     * 선택이라, 매번 기본값으로 되돌리면 같은 스위치를 매번 다시 켠다(원칙 04).
     */
    var imageTagGroupUnit: Boolean
        get() = sp.getBoolean(KEY_IMAGE_TAG_GROUP_UNIT, true)
        set(value) {
            sp.edit().putBoolean(KEY_IMAGE_TAG_GROUP_UNIT, value).apply()
        }

    /** 묶음 단위 전송에서 링크 묶음당 보낼 표본 장수. 범위는 [AiPromptPolicy]가 단일 소스다. */
    var imageTagGroupSampleSize: Int
        get() = AiPromptPolicy.clampImageTagGroupSample(
            sp.getInt(KEY_IMAGE_TAG_GROUP_SAMPLE, AiPromptPolicy.IMAGE_TAG_GROUP_SAMPLE_DEFAULT)
        )
        set(value) {
            sp.edit().putInt(KEY_IMAGE_TAG_GROUP_SAMPLE, AiPromptPolicy.clampImageTagGroupSample(value)).apply()
        }

    /**
     * 이름 추천 한 라운드의 **다발 크기** (B-123). 사용자가 시트의 ⋮에서 정한다(설계 7-2).
     *
     * 기억해 두는 이유는 [imageTagBatchSize]와 같다 — 매번 기본값으로 되돌리면 같은 사람이
     * 같은 값을 매번 다시 맞춘다(원칙 04). 범위·기본값은 [AiPromptPolicy]가 단일 소스다.
     */
    var nameSuggestBatchSize: Int
        get() = AiPromptPolicy.clampNameSuggestBatch(
            sp.getInt(KEY_NAME_SUGGEST_BATCH, AiPromptPolicy.NAME_SUGGEST_BATCH_DEFAULT)
        )
        set(value) {
            sp.edit().putInt(KEY_NAME_SUGGEST_BATCH, AiPromptPolicy.clampNameSuggestBatch(value)).apply()
        }

    /**
     * 사건 필드 AI 추천에 실을 **재료 범위** (B-43, 확정 16번 ㄱ1/ㄱ2 인앱 선택).
     *
     * **앱 전체다**(P-7 해소 — 2026.08.04 사용자 확정). 근거는 실측이었다: AI 설정 저장소
     * 셋(`AiKeyStore`·`AiPromptSettings`·`AiProviderStore`)이 전부 앱 전체이고 세계관 단위
     * AI 설정은 하나도 없다 — 여기서만 세계관 단위로 가면 AI 설정이 두 축으로 갈린다.
     *
     * **키가 없는 것과 빈 값은 다르다** — 없으면 기본(전부 켬)이고, 빈 값은 사용자가 전부
     * 끈 것이다. 둘을 합치면 *"전부 끄기"*가 저장되지 않아 다음에 열 때 되살아난다.
     */
    var eventContextScope: Set<EventAiMaterial>
        get() = EventAiMaterial.parse(sp.getString(KEY_EVENT_CONTEXT_SCOPE, null))
        set(value) {
            sp.edit().putString(KEY_EVENT_CONTEXT_SCOPE, EventAiMaterial.serialize(value)).apply()
        }

    /**
     * **AI에 보내는 메시지 양식** (사용자 요청 2026.08.20). 비어 있으면 기본 양식이다.
     *
     * *"ai api에 보내지는 메세지 양식도 모두 사용자가 편집할 수 있게 하기(인앱, 엑셀 모두)."*
     *
     * **기본값과 같으면 아예 저장하지 않는다** — 이 저장소의 이웃(`FieldDescription`·
     * `FieldAiPolicy`)이 지키는 관행이고, 여기서는 값이 더 크다: 기본 양식이 앞으로 나아지면
     * 손대지 않은 사용자는 그 개선을 그대로 받는다. 옛 기본값을 통째로 얼려 두면 못 받는다.
     */
    fun templateOf(id: PromptTemplates.Id): String =
        PromptTemplates.effective(id, sp.getString(id.key, null))

    /** 사용자가 고친 글. 손댄 적이 없으면 `null`이다 — 화면의 '고침' 배지가 이것으로 갈린다. */
    fun storedTemplate(id: PromptTemplates.Id): String? =
        sp.getString(id.key, null)?.takeIf { it.isNotBlank() }

    /**
     * @return 저장했으면 빈 목록, 거절했으면 그 사유들. **자르지 않는다** —
     *   잘린 양식은 필수 자리표가 잘려나간 계약이 깨진 양식이다([AiPromptPolicy]).
     */
    fun setTemplate(id: PromptTemplates.Id, raw: String?): List<PromptTemplateValidator.Problem> {
        val text = raw.orEmpty().trim()
        val problems = PromptTemplateValidator.validate(id, text)
        if (problems.isNotEmpty()) return problems
        // 빈 값·기본과 같은 값은 **키를 지운다**(위 KDoc).
        if (text.isEmpty() || PromptTemplates.isDefault(id, text)) {
            sp.edit().remove(id.key).apply()
        } else {
            sp.edit().putString(id.key, text).apply()
        }
        return emptyList()
    }

    /**
     * 조립기에 건네줄 양식 공급자.
     *
     * 조립기가 이 객체를 직접 쥐면 `Context`가 딸려 가 순수 JVM 시험이 프롬프트를 못 본다 —
     * 그래서 함수 하나만 넘긴다([PromptTemplates.Source]의 KDoc).
     */
    fun asTemplateSource(): PromptTemplates.Source =
        PromptTemplates.Source { templateOf(it) }

    /** 손댄 양식이 몇 개인가 — 설정 화면의 요약 줄이 쓴다. */
    fun customizedTemplateCount(): Int =
        PromptTemplates.Id.entries.count { storedTemplate(it) != null }

    companion object {
        /** 키를 담지 않는다 — 이 파일은 `ai_keys`·`ai_providers`와 달리 민감 정보가 없다. */
        const val PREFS_NAME = "ai_prompt_settings"
        private const val KEY_USAGE_EXAMPLES = "usageExampleCount"
        private const val KEY_STYLE_SAMPLES = "styleSampleCount"
        private const val KEY_MIN_CONFIDENCE = "minConfidence"
        private const val KEY_CREATIVITY = "creativity"
        private const val KEY_IMAGE_TAG_POLICY = "imageTagPolicy"
        private const val KEY_ATTACH_IMAGES = "attachImageCount"
        private const val KEY_ATTACH_REPRESENTATIVE = "attachRepresentativeFirst"
        private const val KEY_IMAGE_TAG_BATCH = "imageTagBatchSize"
        private const val KEY_IMAGE_TAG_GROUP_UNIT = "imageTagGroupUnit"
        private const val KEY_IMAGE_TAG_GROUP_SAMPLE = "imageTagGroupSampleSize"
        private const val KEY_NAME_SUGGEST_BATCH = "nameSuggestBatchSize"
        private const val KEY_EVENT_CONTEXT_SCOPE = "eventContextScope"
    }
}
