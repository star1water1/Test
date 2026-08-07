package com.novelcharacter.app.ai

import com.novelcharacter.app.data.model.FieldValueEntry

/**
 * **이미지 태그 어휘의 단일 소스** (설계 `docs/feature_roadmap_2026-08.md` 2-3).
 *
 * 어휘를 쓰는 AI 기능이 둘이다 — 폴더 이름으로 제안하는 [ImageFolderTagSuggester]와 이미지
 * 내용을 보고 제안하는 [ImageBatchTagSuggester]. **두 벌로 두면 같은 앱의 두 AI가 다른 어휘로
 * 말한다**: 한쪽은 `흑발`을 알고 다른 쪽은 모르니, 같은 이미지가 어느 경로로 태깅됐는지에 따라
 * 표기가 갈린다. 그래서 조립을 여기 하나로 둔다(설계 2-3 '어휘 조립은 단일 소스로 뺀다').
 *
 * 이 자리는 2026.08.07(B-121)에 [ImageFolderTagSuggester]에서 옮겨 왔다 — 종전에는 폴더
 * 제안기의 companion에 있었고, 그대로 두면 새 소비처가 **폴더 기능의 이름을 부르며** 어휘를
 * 얻어야 했다.
 */
object ImageTagVocabulary {

    /** 프롬프트에 실을 어휘와, 그것을 만들며 잘라낸 양. */
    data class Vocabulary(
        val tags: List<String> = emptyList(),
        val fieldValues: List<String> = emptyList(),
        val truncated: Int = 0
    ) {
        val all: Set<String> get() = LinkedHashSet<String>(tags.size + fieldValues.size).apply {
            addAll(tags); addAll(fieldValues)
        }

        /**
         * 접기 대조용 목록 — **기존 이미지 태그가 필드 값보다 앞선다.**
         *
         * 순서가 뜻을 갖는 이유는 [ImageBatchTagSuggester.foldToVocabulary]가 첫 일치를 쓰기
         * 때문이다: `흑발`이 이미 태그로 쓰이고 있고 필드 값에도 `흑 발`이 있다면, 접힐 곳은
         * **이미 태그인 쪽**이어야 한다(태그 목록이 곧 사용자가 써 온 표기다).
         */
        val forFolding: List<String> get() = tags + fieldValues
    }

    /**
     * 어휘를 만든다 — 기존 이미지 태그(빈도 상위)와 '어휘에 포함' 필드의 값.
     *
     * 필드 값 선별은 [CharacterFieldAiSuggester.selectUsageExamples]를 **그대로 쓴다**
     * (빈도 상위 2/3 + 균등 간격 1/3, 난수 없음). 같은 문제이므로 같은 함수를 쓴다 —
     * 규칙이 갈리면 같은 데이터가 화면마다 다른 어휘를 낳는다.
     *
     * @param tagCounts 태그 → 사용 횟수.
     * @param entriesByField '어휘에 포함'이 켜진 필드별 값 라이브러리 엔트리.
     */
    fun build(
        tagCounts: Map<String, Int>,
        entriesByField: List<List<FieldValueEntry>>
    ): Vocabulary {
        val sortedTags = tagCounts.entries
            .filter { it.key.isNotBlank() }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        val tags = sortedTags.take(AiPromptPolicy.IMAGE_TAG_VOCAB_MAX)
        var truncated = sortedTags.size - tags.size

        val values = LinkedHashSet<String>()
        for (entries in entriesByField) {
            val picked = CharacterFieldAiSuggester.selectUsageExamples(
                entries, limit = AiPromptPolicy.IMAGE_TAG_VALUES_PER_FIELD
            )
            truncated += (entries.count { it.value.isNotBlank() } - picked.size).coerceAtLeast(0)
            for (v in picked) {
                if (values.size >= AiPromptPolicy.IMAGE_TAG_VALUES_TOTAL_MAX) { truncated++; continue }
                values.add(v)
            }
        }
        // 태그와 겹치는 값은 어휘에 두 번 싣지 않는다(토큰 낭비이지 잘라낸 것이 아니다).
        val tagSet = tags.toHashSet()
        return Vocabulary(tags, values.filterNot { it in tagSet }, truncated)
    }
}
