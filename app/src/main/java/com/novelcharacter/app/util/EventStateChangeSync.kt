package com.novelcharacter.app.util

import android.util.Log
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.data.repository.TimelineRepository
import com.novelcharacter.app.data.repository.UniverseRepository

/**
 * 출생·사망 **사건 → 캐릭터 상태변화·필드** 동기화 — 연표 탭과 캐릭터 화면의 단일 소스.
 *
 * ## 왜 모았는가 — 같은 루프가 두 벌이었다
 *
 * `TimelineViewModel`과 `CharacterViewModel`이 `updateEventAndShiftOthers`와
 * `syncEventTypeToStateChanges`를 **글자까지 같은 복사본**으로 들고 있었다. 그 상태로 한쪽만
 * 고치면 같은 조작이 어디서 시작했느냐에 따라 다르게 동작한다 — R-53이 이름 붙인 그 모양이고,
 * 이 저장소가 이미 여러 번 겪은 축이다.
 *
 * ## 왜 일괄인가 — '이후 사건 전부 이동'이 상한 없는 트랜잭션이다
 *
 * 사건의 연도를 바꾸면 *"이후 N건도 함께 옮길까요"*를 묻는다. 종전에는 옮긴 사건 하나하나에
 * 대해 참여 캐릭터를 단건으로 묻고, 캐릭터마다 **다섯 질의 뭉치**(이력 조회 → 갱신/삽입 →
 * 캐릭터 조회 → 작품 조회 → 필드 동기화)를 쳤다. 그 전부가 **하나의 쓰기 트랜잭션 안**이고
 * 진행 표시도 취소도 없다. 세계관이 클수록 길어지는데 상한이 없다.
 *
 * 이 클래스는 같은 일을 **묶어서** 한다: 참여 캐릭터·캐릭터·작품·상태변화를 각각 한 번씩
 * 읽고(`SqlInChunks` — R-54), 쓰기도 모아서 한 번에 낸다. 질의 수가 *사건 × 캐릭터*가 아니라
 * **상수 개**가 된다.
 *
 * 저장소 안에 이미 같은 판단의 선례가 있다 — 엑셀 가져오기는 행 루프 안에서 이 동기화를
 * 돌리지 않고 `pendingSyncCharacters`에 접어 두었다가 루프가 끝난 뒤 한 번에 처리한다.
 *
 * ## 실패는 캐릭터 단위로 격리한다
 *
 * 종전 루프는 캐릭터 하나가 실패해도 나머지를 계속했다(try/catch가 루프 안에 있었다).
 * 그 성질을 그대로 지킨다 — 사건 이동은 이미 커밋될 트랜잭션 안이고, 파생 동기화 하나가
 * 사건 이동 전체를 되돌리면 사용자가 잃는 것이 더 크다.
 */
class EventStateChangeSync(
    private val timelineRepository: TimelineRepository,
    private val characterRepository: CharacterRepository,
    private val novelRepository: NovelRepository,
    private val universeRepository: UniverseRepository,
    private val semanticSyncHelper: SemanticFieldSyncHelper,
    /** 로그 태그 — 어느 화면에서 시작한 동기화인지 로그가 말하게 한다. */
    private val logTag: String
) {

    /** 한 사건과 그 사건에 참여하는 캐릭터들. */
    data class Target(val event: TimelineEvent, val characterIds: List<Long>)

    /** 사건 하나 — 참여 캐릭터를 부르는 쪽이 이미 알고 있을 때(방금 저장한 목록). */
    suspend fun sync(event: TimelineEvent, characterIds: List<Long>) =
        sync(listOf(Target(event, characterIds)))

    /**
     * 옮겨진 사건들 — 참여 캐릭터를 **한 번에** 읽는다.
     * 종전에는 사건마다 `getCharacterIdsForEvent`를 단건으로 쳤다.
     */
    suspend fun syncShifted(events: List<TimelineEvent>) {
        val relevant = events.filter { fieldKeyOf(it) != null }
        if (relevant.isEmpty()) return
        val byEvent = SqlInChunks.flat(relevant.map { it.id }) {
            timelineRepository.getCrossRefsByEventIds(it)
        }.groupBy { it.eventId }
        sync(relevant.map { Target(it, byEvent[it.id]?.map { ref -> ref.characterId }.orEmpty()) })
    }

    suspend fun sync(targets: List<Target>) {
        // 대상이 아닌 사건은 여기서 전부 걷어낸다 — 아래 조회가 헛돌지 않게.
        val work = targets.filter { fieldKeyOf(it.event) != null && it.characterIds.isNotEmpty() }
        if (work.isEmpty()) return

        val charIds = work.flatMap { it.characterIds }.distinct()
        val characters = characterRepository.getCharactersByIds(charIds).associateBy { it.id }
        val novels = novelRepository
            .getNovelsByIds(characters.values.mapNotNull { it.novelId }.distinct())
            .associateBy { it.id }
        // 캐릭터마다 이력 전량을 다시 묻지 않는다 — 한 번에 읽어 캐릭터별로 가른다.
        // **차례를 단건 질의와 맞춘다** — 일괄 질의에는 `ORDER BY`가 없고(청크 경계에서
        // 어긋나므로 DAO가 일부러 뺐다), 그 차이가 같은 키의 이력이 둘 이상일 때
        // *어느 줄을 갱신하는가*를 갈랐다. SQLite의 `ASC`는 NULL이 앞이다.
        val changesByCharacter = characterRepository
            .getChangesByCharacterIds(charIds)
            .groupBy { it.characterId }
            .mapValues { (_, list) ->
                list.sortedWith(
                    compareBy({ it.year }, { it.month ?: Int.MIN_VALUE }, { it.day ?: Int.MIN_VALUE })
                )
            }

        // 쓰기는 모아서 낸다. **삽입은 같은 (캐릭터, 키)가 한 번만 들어가게** 접는다 —
        // 한 캐릭터가 옮겨진 출생 사건 둘에 걸려 있으면 종전에는 두 번째가 첫 번째를 읽어
        // 갱신했지만, 일괄에서는 둘 다 '없음'으로 읽혀 두 줄이 들어간다.
        val updates = LinkedHashMap<Long, CharacterStateChange>()
        val inserts = LinkedHashMap<Pair<Long, String>, CharacterStateChange>()
        /** 필드 동기화에 넘길 최종 상태 — 같은 (캐릭터, 키)는 마지막 것이 이긴다(종전과 같다). */
        val toField = LinkedHashMap<Pair<Long, String>, Pair<Long, CharacterStateChange>>()

        for (target in work) {
            val event = target.event
            val fieldKey = fieldKeyOf(event) ?: continue
            for (charId in target.characterIds) {
                val character = characters[charId] ?: continue
                val existing = changesByCharacter[charId]?.find { it.fieldKey == fieldKey }
                val change = if (existing != null) {
                    existing.copy(
                        year = event.year,
                        month = event.month,
                        day = event.day,
                        newValue = event.year.toString()
                    ).also { updates[it.id] = it }
                } else {
                    CharacterStateChange(
                        characterId = charId,
                        year = event.year,
                        month = event.month,
                        day = event.day,
                        fieldKey = fieldKey,
                        newValue = event.year.toString()
                    ).also { inserts[charId to fieldKey] = it }
                }
                val universeId = character.novelId?.let { novels[it] }?.universeId ?: continue
                toField[charId to fieldKey] = universeId to change
            }
        }

        if (updates.isNotEmpty()) characterRepository.updateAllStateChanges(updates.values.toList())
        if (inserts.isNotEmpty()) characterRepository.insertAllStateChanges(inserts.values.toList())

        // 필드 동기화 — **세계관 필드 목록을 세계관당 한 번만 읽는다.**
        // 종전에는 캐릭터마다 전량을 다시 읽었고, 그 안에서 역할을 찾을 때마다 필드마다
        // JSON을 새로 파싱했다(필드 90개면 한 캐릭터에 180번).
        val fieldsByUniverse = HashMap<Long, List<FieldDefinition>>()
        for ((_, entry) in toField) {
            val (universeId, change) = entry
            try {
                val fields = fieldsByUniverse.getOrPut(universeId) {
                    universeRepository.getFieldsByUniverseList(universeId)
                }
                semanticSyncHelper.syncStateChangeToField(change.characterId, fields, change)
            } catch (e: Exception) {
                Log.w(logTag, "Failed to sync event type for character ${change.characterId}", e)
            }
        }
    }

    private fun fieldKeyOf(event: TimelineEvent): String? = when (event.eventType) {
        TimelineEvent.TYPE_BIRTH -> CharacterStateChange.KEY_BIRTH
        TimelineEvent.TYPE_DEATH -> CharacterStateChange.KEY_DEATH
        else -> null
    }
}
