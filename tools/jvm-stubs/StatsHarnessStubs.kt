// 순수 JVM 테스트 하네스 전용 스텁 — Gradle 소스셋에 포함되지 않는다(tools/ 아래).
//
// `StatsDataProvider.loadSnapshot(app)`의 시그니처를 실제 타입으로 검사하기 위한 최소 표면과,
// util 계층이 부르는 android.util.Log의 자리만 채운다. 로직은 없다 —
// compute* 계열은 스냅샷만 보는 순수 계산이므로 스텁이 결과에 관여하지 않는다.
//
// DAO/저장소 메서드를 여기 반영하지 않으면 하네스 컴파일이 깨진다. 그것이 목적이다:
// loadSnapshot이 부르는 메서드가 사라지거나 시그니처가 바뀌면 CI 이전에 잡힌다.
package com.novelcharacter.app

import com.novelcharacter.app.data.model.*

class NovelCharacterApp {
    val database: HarnessDb = HarnessDb()
    val characterRepository: HarnessCharacterRepo = HarnessCharacterRepo()
    val novelRepository: HarnessNovelRepo = HarnessNovelRepo()
    val universeRepository: HarnessUniverseRepo = HarnessUniverseRepo()
    val timelineRepository: HarnessTimelineRepo = HarnessTimelineRepo()
    val factionRepository: HarnessFactionRepo = HarnessFactionRepo()
}

class HarnessDb {
    fun characterTagDao(): HarnessTagDao = HarnessTagDao()
    fun nameBankDao(): HarnessNameBankDao = HarnessNameBankDao()
    fun characterStateChangeDao(): HarnessStateChangeDao = HarnessStateChangeDao()
    fun fieldDefinitionDao(): HarnessFieldDefDao = HarnessFieldDefDao()
    fun characterFieldValueDao(): HarnessCharFieldValueDao = HarnessCharFieldValueDao()
    fun timelineDao(): HarnessTimelineDao = HarnessTimelineDao()
    fun eventFieldValueDao(): HarnessEventFieldValueDao = HarnessEventFieldValueDao()
    fun fieldValueEntryDao(): HarnessFieldValueEntryDao = HarnessFieldValueEntryDao()
}

class HarnessTagDao { suspend fun getAllTagsList(): List<CharacterTag> = emptyList() }
class HarnessNameBankDao { suspend fun getAllNamesList(): List<NameBankEntry> = emptyList() }
class HarnessStateChangeDao { suspend fun getAllChangesList(): List<CharacterStateChange> = emptyList() }
class HarnessFieldDefDao {
    suspend fun getAllFieldsList(
        entityType: String = FieldDefinition.ENTITY_CHARACTER
    ): List<FieldDefinition> = emptyList()
}
class HarnessCharFieldValueDao { suspend fun getAllValuesList(): List<CharacterFieldValue> = emptyList() }
class HarnessTimelineDao {
    suspend fun getAllCrossRefs(): List<TimelineCharacterCrossRef> = emptyList()
    suspend fun getAllEventNovelCrossRefs(): List<TimelineEventNovelCrossRef> = emptyList()
}
class HarnessEventFieldValueDao { suspend fun getAllValuesList(): List<EventFieldValue> = emptyList() }
class HarnessFieldValueEntryDao { suspend fun getAllList(): List<FieldValueEntry> = emptyList() }

class HarnessCharacterRepo {
    suspend fun getAllCharactersList(): List<Character> = emptyList()
    suspend fun getAllRelationships(): List<CharacterRelationship> = emptyList()
    suspend fun getAllRelationshipChanges(): List<CharacterRelationshipChange> = emptyList()
}
class HarnessNovelRepo { suspend fun getAllNovelsList(): List<Novel> = emptyList() }
class HarnessUniverseRepo { suspend fun getAllUniversesList(): List<Universe> = emptyList() }
class HarnessTimelineRepo { suspend fun getAllEventsList(): List<TimelineEvent> = emptyList() }
class HarnessFactionRepo {
    suspend fun getAllFactionsList(): List<Faction> = emptyList()
    suspend fun getAllMembershipsList(): List<FactionMembership> = emptyList()
}
