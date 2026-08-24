package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.TimelineEvent

/**
 * 스냅샷 하나만으로 캐릭터 데이터의 "진짜 모순"을 배치 검사한다.
 *
 * 변수 제어(개발 의도): 잘못된 입력을 조용히 넘기지 않고 찾아내 사용자에게 알린다.
 * 단건 DB 조회(N회)를 피하고 [StatsSnapshot] 인메모리 데이터만 사용한다.
 *
 * 검사 로직은 기존 `CharacterViewModel.detectAgeLinkageConflict`(저장 전 단건 검사)와
 * 동일한 규칙을 배치화한 것이다.
 *
 * ## 비용 (B-251 ⓓ · 2026.08.19에 실제로 그렇게 만들었다)
 *
 * **작품 수 × 캐릭터 수가 아니라 캐릭터 수에 비례한다.** 종전 [checkAgeMismatches]는
 * 작품 루프 안에서 `s.characters` **전체**를 훑으며 `char.novelId != novel.id`를 걸렀다 —
 * 목표 규모(`docs/scalability_performance_2026-07.md` 2장: 캐릭터 6,420 · 작품 510)에서
 * **327만 회**를 돌아 그중 6,420회만 일했다. 작품별로 미리 접으면 6,420회다.
 *
 * 같은 자리에 [SemanticRole.fromConfig]도 있었다 — 그 함수는 **`config` JSON을 통째로
 * 파싱**하는데 작품마다 세계관 필드 전체를 다시 훑었다. 세계관은 작품보다 적고(270 대 510)
 * 필드 정의는 세계관에 붙으므로, **세계관 단위로 한 번만 풀면 된다.**
 *
 * 이 문단을 남기는 이유는 종전 주석이 *"한 번의 계산으로 끝난다(받쳐주는 확장성)"*라고
 * **주장만** 하고 있었기 때문이다 — 코드는 그렇지 않았다. CLAUDE.md의 판정 기준은
 * *"받쳐주지 못하는 확장은 확장이 아니라 결함"*이고, 주장은 그 판정을 대신하지 못한다.
 */
object ConsistencyChecker {

    /** 나이 필드값과 출생연도 필드값이 작품 표준연도와 어긋나는 경우. */
    data class AgeMismatch(
        val characterId: Long,
        val characterName: String,
        val novelId: Long,
        val inputAge: Int,
        val inputBirthYear: Int,
        val standardYear: Int,
        val expectedAge: Int,
        /** 나이를 신뢰할 때의 올바른 출생연도(= 표준연도 - 나이). */
        val suggestedBirthYear: Int
    )

    /** 사망연도가 출생연도보다 앞서는 경우(__birth/__death 상태변화 비교). */
    data class DeathBeforeBirth(
        val characterId: Long,
        val characterName: String,
        val birthYear: Int,
        val deathYear: Int
    )

    /**
     * 연표의 **탄생·사망 사건 연도**가 그 캐릭터의 출생·사망연도와 다른 경우.
     *
     * 두 숫자는 같은 사실을 두 자리에 적은 것이라 갈리면 하나는 틀렸는데, **앱이 그것을 말하는
     * 자리가 없었다.** 실측(2026.08.24 사용자가 내보낸 파일): 연표 한 행이 두 캐릭터의 탄생을
     * 1303년이라 적고 그 둘의 출생연도는 1301년이었다.
     *
     * **어느 쪽이 옳은지는 알 수 없다** — 그래서 형제([DeathBeforeBirth])와 같이 *열기 전용*
     * 카드로 낸다. 자동으로 맞추면 사용자가 적어 둔 쪽을 우리가 고르는 것이 된다.
     *
     * @param isBirth 탄생 사건인가(아니면 사망 사건).
     * @param characterYear 캐릭터 쪽 연도. 사건 쪽은 [eventYear].
     */
    data class EventYearMismatch(
        val characterId: Long,
        val characterName: String,
        val eventId: Long,
        val eventDescription: String,
        val isBirth: Boolean,
        val eventYear: Int,
        val characterYear: Int
    )

    data class Result(
        val ageMismatches: List<AgeMismatch>,
        val deathBeforeBirth: List<DeathBeforeBirth>,
        val eventYearMismatches: List<EventYearMismatch> = emptyList()
    ) {
        val total: Int
            get() = ageMismatches.size + deathBeforeBirth.size + eventYearMismatches.size
    }

    fun check(s: StatsSnapshot): Result =
        Result(
            ageMismatches = checkAgeMismatches(s),
            deathBeforeBirth = checkDeathBeforeBirth(s),
            eventYearMismatches = checkEventYearMismatches(s)
        )

    /** 한 세계관에서 나이·출생연도 역할을 맡은 필드 정의. 둘 다 있어야 검사가 성립한다. */
    private data class RoleFields(val ageFieldId: Long, val birthYearFieldId: Long)

    private fun checkAgeMismatches(s: StatsSnapshot): List<AgeMismatch> {
        val novelsWithStdYear = s.novels.filter { it.standardYear != null && it.universeId != null }
        if (novelsWithStdYear.isEmpty()) return emptyList()

        // ⚠️ **접는 것은 전부 지연이다.** 역할 필드(나이·출생연도)를 **둘 다** 갖춘 세계관이
        // 하나도 없으면 아래 루프는 한 번도 몸통에 닿지 않는데, 그 경우가 드물지 않다
        // (사용자가 의미 역할을 지정해야만 성립한다). 그때 미리 접어 두면 **아무도 안 쓰는
        // 맵을 캐릭터 수·필드값 수만큼 만들고 버린다** — 종전 코드가 치르지 않던 비용을
        // 새로 지우는 셈이라, 고치려던 것보다 더 나빠진다.
        // 2026.08.19에 실제로 그렇게 만들었다가 목표 규모 벤치에서 **새 쪽이 두 배 느린 것**을
        // 보고 되돌렸다. **성능 수리는 재서 확인하기 전까지는 수리가 아니다.**
        val valuesByChar by lazy(LazyThreadSafetyMode.NONE) { s.fieldValues.groupBy { it.characterId } }
        // 작품마다 `s.characters` 전체를 훑던 자리다 — 목표 규모에서 작품 수만큼 헛돌았다.
        // 한 번 접어 두면 아래 루프가 실제로 검사할 캐릭터만 본다.
        val charactersByNovel by lazy(LazyThreadSafetyMode.NONE) { s.characters.groupBy { it.novelId } }
        // 표준연도 연동을 명시적으로 해제(__std_year_link = none)한 캐릭터는
        // 나이/출생연도 불일치가 의도된 것이므로 검사에서 제외한다.
        // *"해제란 무엇인가"*의 단일 소스는 [StandardYearLink]다 — 종전에는 이 자리와
        // [StandardYearSyncHelper.isLinked]가 같은 규칙을 따로 적고 있었다.
        val unlinkedCharIds by lazy(LazyThreadSafetyMode.NONE) {
            s.stateChanges
                .filter { it.fieldKey == StandardYearLink.KEY && StandardYearLink.isUnlinked(it.newValue) }
                .map { it.characterId }
                .toSet()
        }

        // `SemanticRole.fromConfig`는 config JSON을 통째로 파싱한다. 역할↔필드 대응은
        // **세계관**에 붙는데 종전에는 작품마다 다시 풀었다 — 한 세계관을 여러 작품이
        // 공유하므로 그만큼이 순수한 중복이었다.
        val fieldsByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        // **`getOrPut`을 쓰지 않는다** — 값이 null이면 '없음'과 구별되지 않아 매번 다시 푼다.
        // 하필 역할 필드가 없는 세계관이 그 경우이고, 그쪽이 정확히 *필드 전체를 헛되이
        // 파싱하는* 가장 비싼 경우다. `containsKey`로 *풀어 본 적 있는가*를 따로 묻는다.
        val roleFieldsByUniverse = HashMap<Long?, RoleFields?>()
        fun roleFieldsOf(universeId: Long?): RoleFields? {
            if (roleFieldsByUniverse.containsKey(universeId)) return roleFieldsByUniverse[universeId]
            val fields = fieldsByUniverse[universeId].orEmpty()
            val ageFieldId = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.AGE }?.id
            val birthYearFieldId =
                fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.BIRTH_YEAR }?.id
            val resolved = if (ageFieldId != null && birthYearFieldId != null) {
                RoleFields(ageFieldId, birthYearFieldId)
            } else {
                null
            }
            roleFieldsByUniverse[universeId] = resolved
            return resolved
        }

        val result = mutableListOf<AgeMismatch>()
        for (novel in novelsWithStdYear) {
            val stdYear = novel.standardYear ?: continue
            val roleFields = roleFieldsOf(novel.universeId) ?: continue

            for (char in charactersByNovel[novel.id].orEmpty()) {
                if (char.id in unlinkedCharIds) continue
                val vals = valuesByChar[char.id] ?: continue
                val age = vals.find { it.fieldDefinitionId == roleFields.ageFieldId }
                    ?.value?.trim()?.toIntOrNull() ?: continue
                val birthYear = vals.find { it.fieldDefinitionId == roleFields.birthYearFieldId }
                    ?.value?.trim()?.toIntOrNull() ?: continue

                val expectedAge = stdYear - birthYear
                if (age != expectedAge) {
                    result.add(
                        AgeMismatch(
                            characterId = char.id,
                            characterName = char.name,
                            novelId = novel.id,
                            inputAge = age,
                            inputBirthYear = birthYear,
                            standardYear = stdYear,
                            expectedAge = expectedAge,
                            suggestedBirthYear = stdYear - age
                        )
                    )
                }
            }
        }
        return result
    }

    private fun checkDeathBeforeBirth(s: StatsSnapshot): List<DeathBeforeBirth> {
        val changesByChar = s.stateChanges.groupBy { it.characterId }
        val charById = s.characters.associateBy { it.id }

        val result = mutableListOf<DeathBeforeBirth>()
        for ((charId, changes) in changesByChar) {
            val char = charById[charId] ?: continue
            // 정본 한 행은 [SingletonStateChanges]가 고른다 — `find`는 DAO의 `ORDER BY`에
            // 기대는 답이라, 이력을 다르게 실어 온 호출부에서 조용히 다른 행을 집었다.
            val birth = SingletonStateChanges.pick(changes, CharacterStateChange.KEY_BIRTH)?.year
            val death = SingletonStateChanges.pick(changes, CharacterStateChange.KEY_DEATH)?.year
            // year 0은 "연도 미상" placeholder일 수 있으므로 오탐 방지를 위해 제외한다.
            if (birth != null && death != null && birth != 0 && death != 0 && death < birth) {
                result.add(
                    DeathBeforeBirth(
                        characterId = charId,
                        characterName = char.name,
                        birthYear = birth,
                        deathYear = death
                    )
                )
            }
        }
        return result
    }

    /**
     * 연표의 탄생·사망 사건 연도 ↔ 캐릭터의 `__birth`·`__death` 연도.
     *
     * **비용은 참가자 연결 수에 비례한다** — 사건마다 캐릭터 전량을 훑지 않는다(형제
     * [checkAgeMismatches]가 목표 규모에서 327만 회를 돌던 그 모양을 되풀이하지 않는다).
     *
     * 연도 `0`은 *미상* 자리표시자라 양쪽 어디에 있어도 건너뛴다 — 형제
     * [checkDeathBeforeBirth]가 같은 이유로 같은 갈래를 둔다. 자리표시자를 어긋남이라 부르면
     * 생일만 적어 둔 캐릭터 전원이 경고에 걸린다(거짓 고지가 진짜 고지를 묻는다).
     */
    private fun checkEventYearMismatches(s: StatsSnapshot): List<EventYearMismatch> {
        val semanticEvents = s.events.filter {
            it.eventType == TimelineEvent.TYPE_BIRTH || it.eventType == TimelineEvent.TYPE_DEATH
        }
        if (semanticEvents.isEmpty()) return emptyList()

        val charIdsByEvent = s.crossRefs.groupBy({ it.eventId }, { it.characterId })
        val charById = s.characters.associateBy { it.id }
        // 정본 한 행은 [SingletonStateChanges]가 고른다 — 형제 검사와 같은 술어여야
        // 두 카드가 같은 캐릭터를 두고 다른 연도를 말하지 않는다(R-34).
        val changesByChar = s.stateChanges.groupBy { it.characterId }

        val result = mutableListOf<EventYearMismatch>()
        for (event in semanticEvents) {
            val isBirth = event.eventType == TimelineEvent.TYPE_BIRTH
            val key = if (isBirth) CharacterStateChange.KEY_BIRTH else CharacterStateChange.KEY_DEATH
            if (event.year == 0) continue
            for (charId in charIdsByEvent[event.id].orEmpty()) {
                val char = charById[charId] ?: continue
                val changes = changesByChar[charId] ?: continue
                val year = SingletonStateChanges.pick(changes, key)?.year ?: continue
                if (year == 0 || year == event.year) continue
                result.add(
                    EventYearMismatch(
                        characterId = charId,
                        characterName = char.name,
                        eventId = event.id,
                        eventDescription = event.description,
                        isBirth = isBirth,
                        eventYear = event.year,
                        characterYear = year
                    )
                )
            }
        }
        return result
    }
}
