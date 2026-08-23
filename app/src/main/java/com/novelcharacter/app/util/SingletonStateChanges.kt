package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange

/**
 * **캐릭터당 한 행뿐인 상태변화 키** — 그 불변식과 *두 줄이 남았을 때 누가 이기는가*의
 * 단일 소스 (순수 계층, Android 비의존).
 *
 * ## 왜 생겼는가 — 같은 캐릭터의 나이가 화면마다 달랐다
 *
 * `__birth`·`__death`·`__alive`는 사용자가 적는 이력이 아니라 **앱이 캐릭터의 필드값에서
 * 스스로 만드는 파생 행**이다(`SemanticFieldSyncHelper`). 그래서 캐릭터당 하나뿐이고,
 * 내보내는 파일의 안내도 *"캐릭터당 한 행"*이라 적는다.
 *
 * 그런데 **DB가 그것을 강제하지 않는다.** 2026.08.22 이전의 일괄 삽입은 같은 (캐릭터, 키)를
 * 두 줄로 넣을 수 있었고, 엑셀 가져오기는 오늘도 `(캐릭터·연도·필드키·값)` 자연키로 잡으므로
 * **연도만 고친 행이 두 번째 줄이 된다.** 그리고 두 줄이 남으면 읽는 자리마다 답이 갈렸다:
 *
 * | 읽는 자리 | 종전 방식 | 두 줄(1301·1303)에서 |
 * |---|---|---|
 * | `SemanticFieldSyncHelper`(쓰는 쪽) | DAO 차례의 첫 건 | **1301** |
 * | `ConsistencyChecker` · `StatsDataProvider` | `find` | **1301** |
 * | `AliveAtYear`의 `declaredBirthYear` | `min` | **1301** |
 * | `AliveAtYear`의 `birthYear` · `TimeStateResolver` | `findLast` | **1303** |
 *
 * 실제 사용자 데이터에서 이 모양이 두 캐릭터에 남아 있었다(2026.08.23 — 내보낸 파일을
 * 뜯어 찾았다). 프로필은 165살, 연표는 163살이었고 **어느 화면도 틀렸다고 말하지 않았다.**
 *
 * ## 정본을 *가장 이른 행*으로 정한 근거
 *
 * 고를 수 있는 답은 둘뿐이었고(첫 건 / 마지막 건), **쓰는 쪽이 이미 첫 건을 쓴다** —
 * 사용자가 캐릭터 편집기에서 출생연도를 고치면 [CANONICAL_ORDER]의 첫 행이 갱신된다.
 * 마지막 건을 정본으로 삼으면 *사용자가 방금 고친 값이 화면에 안 나오는* 자리가 생긴다.
 * 휴지통 복원 규약(`RestoreModes.stateChangeRestoreMode`)도 *"첫 건을 쓴다"*를 근거로 적혀
 * 있어, 첫 건이 이 저장소가 이미 고른 답이다. 여기서는 그것을 **글자로 못박고 기계로 지킨다.**
 *
 * ## 부르는 자리
 *
 * - 읽기: [pick] — `find`/`findLast`/`min`을 자리마다 다시 적지 않는다.
 * - 가져오기: [isSingleton] — 자연키가 빗나가도 **슬롯으로 다시 잡아** 두 번째 줄을 안 만든다.
 * - 정리: [strays] — 이미 남아 있는 둘째 줄을 가려낸다.
 */
object SingletonStateChanges {

    /**
     * 캐릭터당 **하나만** 있는 것으로 읽히는 상태변화 키.
     *
     * 일반 필드의 이력은 여러 줄이 정상이다 — 그것이 '상태변화'다. 이 셋만은 파생 행이라
     * 둘이 될 수 없다. (`TrashRepository.SINGLETON_STATE_KEYS`가 여기를 가리킨다 —
     * 두 벌로 적으면 복원이 막는 키와 읽기가 정본을 고르는 키가 갈리는 날이 온다.)
     */
    val KEYS: Set<String> = setOf(
        CharacterStateChange.KEY_BIRTH,
        CharacterStateChange.KEY_DEATH,
        CharacterStateChange.KEY_ALIVE
    )

    fun isSingleton(fieldKey: String): Boolean = fieldKey in KEYS

    /**
     * 정본을 고르는 차례 — `CharacterStateChangeDao`의
     * `ORDER BY year ASC, month ASC, day ASC`와 **같은 답**을 낸다.
     *
     * `month`·`day`의 null을 [Int.MIN_VALUE]로 낮추는 것이 그 일치의 요점이다:
     * SQLite의 `ASC`는 NULL을 앞에 둔다. `id`는 앞의 셋이 모두 같을 때의 고정 꼬리표다
     * (질의의 `LIMIT 1`이 사실상 삽입 순서를 집는 것과 같은 답).
     */
    val CANONICAL_ORDER: Comparator<CharacterStateChange> =
        compareBy({ it.year }, { it.month ?: Int.MIN_VALUE }, { it.day ?: Int.MIN_VALUE }, { it.id })

    /**
     * 한 캐릭터의 이력에서 [fieldKey]의 **정본 한 행**. 없으면 null.
     *
     * 밑줄 키가 아닌 키에도 그대로 쓸 수 있다 — 그때는 *가장 이른 이력*이라는 뜻이고,
     * 종전 `find`가 DAO 차례에 기대어 내던 답과 같다(기대는 것을 없애는 것이 요점이다: R-50).
     *
     * @param changes 한 캐릭터의 이력. 차례는 상관없다 — 이 함수가 [CANONICAL_ORDER]로 정한다.
     */
    fun pick(changes: List<CharacterStateChange>, fieldKey: String): CharacterStateChange? =
        changes.asSequence()
            .filter { it.fieldKey == fieldKey }
            .minWithOrNull(CANONICAL_ORDER)

    /**
     * 한 캐릭터의 이력에서 **정본이 아닌 밑줄 키 행들** — 불변식을 어긴 둘째 줄부터.
     *
     * 정리(소급)가 지울 대상이고, 정상 데이터에서는 언제나 빈 목록이다.
     * 반환 차례는 [CANONICAL_ORDER]라 *어느 것이 살아남았는지*를 부르는 쪽이 함께 적을 수 있다.
     */
    fun strays(changes: List<CharacterStateChange>): List<CharacterStateChange> {
        if (changes.size < 2) return emptyList()
        val out = ArrayList<CharacterStateChange>()
        for (key in KEYS) {
            val rows = changes.filter { it.fieldKey == key }
            if (rows.size < 2) continue
            val sorted = rows.sortedWith(CANONICAL_ORDER)
            out.addAll(sorted.drop(1))
        }
        return out.sortedWith(CANONICAL_ORDER)
    }
}
