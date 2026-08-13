package com.novelcharacter.app.data.model

/**
 * 필드 타입 — **저장은 [name] 문자열이고 분기는 이 enum이다** (B-55, 2026.08.13).
 *
 * ## 왜 갈라 두는가
 *
 * 저장 쪽이 문자열인 것은 되돌릴 수 없는 이유가 있다 — 엑셀·월드패키지·프리셋 JSON이 전부
 * 이 값을 글자로 싣고, 그 파일들은 **이 앱보다 오래 산다.** enum으로 컬럼을 바꾸면 모르는
 * 글자가 들어온 순간 행이 통째로 죽고, 그것은 개발 의도 2번(변수 제어)이 금지한 처분이다.
 *
 * 그래서 **경계에서 한 번 좁히고 안에서는 enum으로만 분기한다.** 좁히는 자리는
 * [FieldDefinition.fieldType] 하나이며, 모르는 글자는 `null`이 되어 각 분기가 *"모르는
 * 타입을 어떻게 대접할 것인가"*를 **명시적으로** 답하게 만든다.
 *
 * ## 새 타입을 더할 때
 *
 * 여기 한 줄 더하면 **`when`이 exhaustive를 잃어 컴파일이 깨진다** — 그것이 이 설계의 전부다.
 * 종전에는 `fd.type == "CALCULATED"` 같은 생문자열이 스물아홉 파일에 흩어져 있어 새 타입이
 * 그 화면들에서 **조용히 빠졌다**(원칙 02 위반의 가장 흔한 발생 경로 — S-15가 실제 사례다).
 * 깨진 자리를 하나씩 답하고 나면 그 타입은 앱 어디에서도 안 빠진다.
 *
 * 생문자열이 되돌아오는 것은 `tools/check_field_type_branch.sh`가 막는다.
 */
enum class FieldType(val label: String) {
    TEXT("텍스트"),
    NUMBER("숫자"),
    SELECT("단일 선택"),
    MULTI_TEXT("복수 텍스트"),
    GRADE("등급"),
    CALCULATED("자동 계산"),
    BODY_SIZE("신체 사이즈");

    companion object {
        /**
         * 이름 → 타입. **선형 훑기가 아니라 표다** (B-55).
         *
         * 종전은 `entries.find { it.name == name }`이라 부를 때마다 이터레이터를 만들고 최대
         * 일곱 번 문자열을 견줬다. 그 함수를 **한 블록 안에서 두세 번 부르는 자리가 실제로
         * 있었고**(폼 빌더·작품 목록·사건 편집), 그 자리들은 필드마다 도는 반복문 안이다.
         * 표로 두면 String의 캐시된 해시 하나로 끝난다 — 부르는 쪽을 하나도 안 고치고
         * 기존 스물몇 자리가 함께 빨라진다.
         */
        private val BY_NAME: Map<String, FieldType> = entries.associateBy { it.name }

        /** 모르는 이름은 `null`이다 — 조용히 [TEXT]로 갈음하지 않는다(개발 의도 2번). */
        fun fromName(name: String): FieldType? = BY_NAME[name]

        /**
         * **새 목록을 만들어 준다 — 캐시하지 말 것.**
         * 부르는 쪽이 `ArrayAdapter`에 그대로 넘기는데, 그 어댑터는 넘겨받은 목록을
         * 제 것으로 여기고 고친다(`add`/`clear`). 한 벌을 돌려쓰면 어댑터 하나가
         * 나머지 화면의 타입 목록을 함께 지운다.
         */
        fun labels(): List<String> = entries.map { it.label }
    }
}
