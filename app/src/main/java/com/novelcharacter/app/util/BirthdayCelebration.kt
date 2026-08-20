package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterQuote
import com.novelcharacter.app.data.model.CharacterStateChange

/**
 * **오늘 축하 창에 무엇이 뜨는가** (사용자 요청 2026.08.20).
 *
 * 화면에 두지 않는 것은 이 저장소의 자동 검증이 화면을 못 보기 때문이다
 * (「세션 착수 규칙」 — `DuelCardInfo`가 대결 카드에 대해 같은 자리에 있다).
 * 여기서 정하면 순수 JVM 시험이 실제로 돌려 본다.
 *
 * ## 나이를 세지 않는다
 * *"N번째 생일"*을 적으려면 **작품의 표준연도**와 그 연동 규칙([StandardYearLink])을 지나야
 * 하는데, 그 연동을 안 건 캐릭터·표준연도가 없는 작품·무소속 캐릭터가 전부 정답이 없다.
 * 셀 수 없는 것을 지어내면 축하 자리에서 **틀린 숫자**가 뜬다 — 안 적는 편이 낫다
 * (개발 의도 2번: 모르는 것을 아는 척하지 않는다).
 */
object BirthdayCelebration {

    /**
     * 축하 창의 한 쪽.
     *
     * @property quoteText 오늘 이 캐릭터의 대사. **`null`이면 대사 상자가 통째로 사라진다** —
     *   *"대사 없음"*을 적어 자리를 잡아 두지 않는다. 고르는 규칙은 [QuotePicker.forBirthday]다
     *   (생일 전용이 먼저, 없으면 일반 — 사용자 확정).
     * @property imagePath 대표가 있으면 그 장, 없으면 씨앗 추첨([CharacterRepresentativeImage]).
     */
    data class Page(
        val characterId: Long,
        val name: String,
        val month: Int,
        val day: Int,
        val novelTitle: String? = null,
        val imagePath: String? = null,
        val quoteText: String? = null,
        val quoteNote: String? = null
    )

    /**
     * 오늘 생일인 캐릭터의 쪽 — **없으면 빈 목록이고, 그때 창은 뜨지 않는다.**
     *
     * @param todayIds [BirthdayHelper.getTodayBirthdayCharacterIds]가 낸 오늘치. 판정을 여기서
     *   다시 하지 않는 것이 요점이다 — 윤년 2/29 규칙이 두 벌이 되면 위젯·알림과 갈린다.
     * @param birthChanges `__birth` 상태 변화. 월·일을 여기서 집는다.
     * @param quotesByCharacter 캐릭터별 대사. **차례는 부르는 쪽이 지킨다**(DAO 정렬) —
     *   [QuotePicker]의 답이 목록 차례에 달려 있어, 여기서 다시 정렬하면 대결 카드와 갈린다.
     * @param dayStamp 오늘의 씨앗. 같은 날 대결 카드와 **같은 대사**를 집게 하는 축이다.
     */
    fun pagesOf(
        todayIds: List<Long>,
        characters: List<Character>,
        birthChanges: List<CharacterStateChange>,
        quotesByCharacter: Map<Long, List<CharacterQuote>>,
        novelTitles: Map<Long, String> = emptyMap(),
        dayStamp: Long,
        imageSeed: Long = 0L
    ): List<Page> {
        if (todayIds.isEmpty()) return emptyList()
        val byId = characters.associateBy { it.id }
        val dateById = birthChanges
            .filter { it.month != null && it.day != null }
            .associateBy({ it.characterId }, { it.month!! to it.day!! })

        return todayIds.mapNotNull { id ->
            val character = byId[id] ?: return@mapNotNull null
            // 월·일을 모르면 쪽을 만들지 않는다 — 축하 창은 *"오늘"*을 말하는 자리라
            // 날짜 없는 줄이 서면 무엇을 축하하는지 알 수 없다.
            val (month, day) = dateById[id] ?: return@mapNotNull null
            val quote = QuotePicker.forBirthday(quotesByCharacter[id].orEmpty(), id, dayStamp)
            Page(
                characterId = id,
                name = character.displayName,
                month = month,
                day = day,
                novelTitle = character.novelId?.let { novelTitles[it] },
                imagePath = CharacterRepresentativeImage.path(
                    character.imagePaths,
                    character.representativeImagePath,
                    imageSeed,
                    character.id
                ),
                quoteText = quote?.text,
                quoteNote = quote?.note?.takeIf { it.isNotBlank() }
            )
        }
    }
}
