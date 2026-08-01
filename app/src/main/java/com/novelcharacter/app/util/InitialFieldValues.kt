package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 필드 생성 창의 **값 사전 등록**(콤마 구분) 해석 — 세 경로의 단일 소스.
 *
 * **왜 여기로 모았는가:** 같은 규칙이 이미 두 벌이었고 **서로 달랐다.**
 * `FieldViewModel.insertField`는 실패를 바깥 catch로 올려 고지했고,
 * `NovelViewModel.registerInitialValues`는 `runCatching`으로 삼켰다 —
 * 같은 조작의 결과 고지가 화면에 따라 갈리는 자리다(OpResult 규약 위반).
 * 게다가 **사건 편집은 이 값을 아예 받지 않아 사용자가 적은 것이 조용히 사라졌다**(B-68).
 * 세 번째 벌을 만드는 대신 해석은 여기, 등재는 `FieldValueLibraryRepository`로 모은다.
 */
object InitialFieldValues {

    /**
     * 사전 등록 문자열을 등재할 값 목록으로 해석한다.
     *
     * 규칙(종전 두 벌이 공통으로 갖고 있던 것 그대로): 콤마로 나누고 · 공백을 떼고 ·
     * 빈 것을 버리고 · 중복을 없앤다. **라이브러리를 지원하지 않는 타입은 통째로 비운다** —
     * 등재해도 어디에도 표시되지 않아 사용자가 "적었는데 안 보인다"로 읽게 되기 때문이다.
     */
    fun parse(raw: String, field: FieldDefinition): List<String> {
        if (!FieldValueTokenizer.supportsLibrary(field)) return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
