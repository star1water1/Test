package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character

/**
 * **캐릭터의 시스템 열을 대결 축에 걸 수 있게 하는 것** (B-167).
 *
 * 사용자 요청 원문(2026.08.08): *"현재 대결에서 이명 같은 못 넣는 필드가 있는 게 아쉬워"*.
 *
 * ## 무엇이 막혀 있었는가 — 타입이 아니라 **소속**이다
 * 축에 거는 창이 `FieldDefinition` 행만 냈고, 이명은 `FieldDefinition`이 아니라
 * [Character.anotherName]이라는 **표의 열**이다. 필터를 고쳐서 되는 자리가 아니었다 —
 * 목록에 낼 **행 자체가 없었다.** 그래서 필요한 것은 *열을 필드처럼 가리키는 이름*이다.
 *
 * ## 왜 `__`가 아니라 `sys:`인가 (설계 물음 ⓐ)
 * `com.novelcharacter.app.data.model.SemanticRole.linkedKey`가 이미 `__birth`·`__age`처럼
 * `__` 접두 의사키를 쓴다. **뜻이 정반대라 같은 어휘를 나눠 쓰면 안 된다:**
 *
 * | | 묻는 것 | 값이 어디 있는가 |
 * |---|---|---|
 * | `__age` (`SemanticRole`) | *"어느 **커스텀 필드**가 나이인가"* | `character_field_values` 행 |
 * | `sys:another_name` (여기) | *"캐릭터 **표의 어느 열**인가"* | `characters` 행 |
 *
 * 앞의 것은 **필드를 가리키는 표식**이고 뒤의 것은 **필드가 아예 없다는 선언**이다.
 * 접두를 늘려 한 어휘에 담으면, `__`로 시작하는 키를 만난 다음 사람이 *어느 쪽인지 가릴
 * 근거가 없다* — 백로그가 *"두 어휘가 겹치지 않게 갈라야 한다"*고 적어 둔 자리가 이것이다.
 *
 * **`@`를 쓰지 않은 것은 엑셀 때문이다.** 이 토큰은 `영향필드`·`프로필필드` 칸에 그대로
 * 실려 **사람이 손으로 고칠 수 있어야 하는데**(개발 의도 4번), 엑셀은 칸 첫 글자 `@`를
 * 함수 호출 기호로 읽어 `@이명`을 적은 사람에게 오류나 자동 교정을 돌려준다. `sys:`는
 * 어디서나 그냥 글자다.
 *
 * ## 진짜 필드가 이긴다 ([shadowed])
 * 필드 키는 사용자가 자유로 적는 글이라 `sys:memo`라는 키의 커스텀 필드를 **만들 수 있다.**
 * 그때 두 값이 같은 키를 다투면 [DuelFieldLinks]의 정규화가 **둘 중 하나를 차례에 따라
 * 조용히 버린다** — 무엇이 남는지가 목록 순서에 달리는 셈이다. 그래서 규칙을 미리 정한다:
 * **사용자가 만든 필드가 이기고, 가려진 시스템 열은 고르는 창이 사유와 함께 막는다**(R-24).
 * 조용히 감추지 않는 것은 *"내가 만든 필드가 왜 없지"*로 되돌아오지 않게 하기 위해서다.
 *
 * ## 산출 필드로는 걸 수 없다 (설계 물음 ⓑ)
 * 영향·프로필은 **읽기**라 성립하지만 산출은 아니다. 산출 필드는 ⓐ 대결 등급 산정이
 * 값을 **써 넣는** 자리이고(`FieldEditDialog`의 등급 등록) ⓑ 순위표가 그 값을 수로 읽어
 * 순위와 대조하는 자리다([DuelFieldLinks.outcomeReport]). 이명·메모·이름은 둘 다 성립하지
 * 않는다 — **고를 수 있는데 아무 일도 안 일어나는 자리**를 만들지 않는다.
 * 엑셀은 고르는 창을 지나지 않으므로 그 경로로 들어온 것은
 * [DuelFieldLinks.Axis.outcomeBlocked]가 사유와 함께 말한다(값은 지우지 않는다).
 */
object DuelSystemFields {

    /**
     * 시스템 열임을 가리키는 앞머리.
     *
     * 콜론을 쓰는 것은 **쉼표가 아니어야 하기 때문**이다 — 엑셀 칸에서 연결을 나누는 글자가
     * 쉼표라([DuelFieldLinks.parseText]) 그것을 담은 토큰은 두 조각으로 찢어진다.
     */
    const val PREFIX = "sys:"

    /**
     * 걸 수 있는 시스템 열.
     *
     * @property suffix [PREFIX] 뒤에 붙는 이름. **바꾸면 이미 저장된 연결이 끊긴다** —
     *   축에 실린 것도 엑셀 파일에 적힌 것도 이 글자 그대로다(`FieldDefinition.key`를
     *   id가 아니라 키로 둔 것과 같은 근거).
     * @property multiToken 쉼표로 나뉘는 다중값인가 (설계 물음 ⓓ). 이명이 그렇고
     *   ([Character.aliases]가 같은 규칙으로 나눈다) 태그가 그렇다.
     */
    enum class Column(val suffix: String, val multiToken: Boolean) {
        NAME("name", false),
        FIRST_NAME("first_name", false),
        LAST_NAME("last_name", false),

        /** 이 슬라이스를 부른 열 — 사용자가 이름을 대어 요청한 자리다. */
        ANOTHER_NAME("another_name", true),
        MEMO("memo", false),

        /** 소속 작품의 **제목**. 캐릭터 행에는 `novelId`만 있어 [Extras]가 들고 온다. */
        NOVEL("novel", false),

        /** 캐릭터 태그. 다른 표(`character_tags`)에 살아 [Extras]가 들고 온다. */
        TAGS("tags", true);

        /** 연결에 실리는 키. */
        val key: String get() = PREFIX + suffix
    }

    /** 키 → 열. 이 앱이 모르는 `sys:` 키는 null이다(엑셀로 들어온 오타가 그렇다). */
    fun columnOf(key: String?): Column? {
        if (key == null || !key.startsWith(PREFIX)) return null
        val suffix = key.substring(PREFIX.length)
        return Column.entries.firstOrNull { it.suffix == suffix }
    }

    /**
     * 이 키가 **시스템 열 자리**인가 — 이 앱이 아는 열인지와는 다른 물음이다.
     *
     * 모르는 `sys:` 키(`sys:오타`)도 참을 낸다. 그래야 산출 자리에 들어온 그것을
     * *"커스텀 필드인데 이 세계관에 없는 것"*으로 오인하지 않는다 — 둘은 사용자가 할 일이 다르다.
     */
    fun isSystemKey(key: String?): Boolean = key != null && key.startsWith(PREFIX)

    /**
     * 같은 키를 든 **진짜 필드에 가려진** 열.
     *
     * @param fieldKeys 이 세계관의 `FieldDefinition.key` 전부.
     */
    fun shadowed(fieldKeys: Collection<String>): Set<Column> {
        if (fieldKeys.isEmpty()) return emptySet()
        val taken = fieldKeys.toSet()
        return Column.entries.filter { it.key in taken }.toSet()
    }

    /** 지금 고를 수 있는 열 — 가려진 것은 빠진다. 차례는 [Column] 선언 순이다. */
    fun available(fieldKeys: Collection<String>): List<Column> {
        val hidden = shadowed(fieldKeys)
        return Column.entries.filter { it !in hidden }
    }

    /**
     * 캐릭터 행에 **없는** 재료.
     *
     * 작품 제목과 태그는 다른 표에 살아 부르는 쪽이 읽어 온다. 둘을 여기서 읽지 않는 것은
     * 이 계층이 순수해야 시험이 실제로 돌기 때문이고, **읽는 비용을 부르는 쪽이 보게**
     * 하려는 것이기도 하다 — `sys:tags`가 걸리지 않은 축에서는 그 질의가 아예 없다.
     */
    data class Extras(val novelTitle: String = "", val tags: List<String> = emptyList())

    /** 한 열의 값. 없으면 빈 문자열이다 — *"안 적었다"*의 표시는 화면의 몫이다. */
    fun valueOf(column: Column, character: Character, extras: Extras = Extras()): String =
        when (column) {
            Column.NAME -> character.name
            Column.FIRST_NAME -> character.firstName
            Column.LAST_NAME -> character.lastName
            Column.ANOTHER_NAME -> character.anotherName
            Column.MEMO -> character.memo
            Column.NOVEL -> extras.novelTitle
            Column.TAGS -> FieldValueTokenizer.join(extras.tags)
        }.trim()

    /**
     * 요청한 키들의 값 — `키 → 값`. **빈 값은 담지 않는다**(커스텀 필드 쪽
     * `DuelViewModel.fieldValuesOf`가 빈 값을 거르는 것과 같은 규약이라야 카드가 둘을
     * 구별 없이 그린다).
     *
     * @param keys 커스텀 필드 키가 섞여 있어도 된다 — 시스템 열만 골라 답한다.
     */
    fun valuesOf(
        character: Character,
        keys: Collection<String>,
        extras: Extras = Extras()
    ): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (key in keys) {
            val column = columnOf(key) ?: continue
            val value = valueOf(column, character, extras)
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }

    /**
     * 값의 토큰 (설계 물음 ⓓ) — 범주 집계(B-112)가 *"차례가 없는 값"*을 셀 때 쓴다.
     *
     * 커스텀 필드는 [FieldValueTokenizer.splitForStats]가 `FieldDefinition`의 타입·config를
     * 보고 나누는데 **시스템 열에는 그 정의가 없다.** 그래서 나누는 규칙을 [Column.multiToken]이
     * 든다 — 이명·태그는 쉼표로 나뉘고 나머지는 통째로 한 토큰이다.
     */
    fun tokensOf(column: Column, raw: String?): List<String> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        return if (column.multiToken) {
            value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(value)
        }
    }
}
