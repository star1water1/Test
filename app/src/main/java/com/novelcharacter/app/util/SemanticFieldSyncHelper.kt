package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.data.repository.UniverseRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale

/**
 * 커스텀 필드(FieldDefinition)와 시스템 특수 필드(CharacterStateChange)를
 * SemanticRole 기반으로 양방향 동기화하는 헬퍼.
 */
class SemanticFieldSyncHelper(
    private val characterRepository: CharacterRepository,
    private val universeRepository: UniverseRepository,
    private val novelRepository: NovelRepository? = null
) {
    private val standardYearSyncHelper = StandardYearSyncHelper(characterRepository, universeRepository)
    // 양방향 동기화를 직렬화하는 단일 뮤텍스.
    // 동시에 양쪽 방향이 실행되어 비결정적 상태가 되는 것을 방지.
    private val syncMutex = Mutex()

    /**
     * 방향 1: 커스텀 필드값 저장 후 → CharacterStateChange 동기화.
     * 캐릭터의 필드값 목록에서 semanticRole이 있는 필드를 찾아 대응하는 상태변화를 생성/갱신.
     */
    suspend fun syncFieldToStateChange(
        characterId: Long,
        universeId: Long,
        values: List<CharacterFieldValue>,
        clearableFieldIds: Set<Long>? = null
    ) = syncMutex.withLock {
        applyFieldsToStateChange(
            characterId, universeRepository.getFieldsByUniverseList(universeId), values, clearableFieldIds
        )
    }

    /**
     * 같은 일을 하되 **세계관 필드 목록을 부르는 쪽이 든다.**
     *
     * 캐릭터 루프에서 위 함수를 부르면 캐릭터마다 필드 전량을 다시 읽는다 — 그 목록은 루프
     * 불변량이라 한 번만 읽으면 된다. 표준연도 변경(작품 캐스트 전원) · 일괄 편집 ·
     * 가져오기 꼬리가 그 루프이고, 뒤의 둘은 **쓰기 트랜잭션 안**이다.
     *
     * **캐시를 헬퍼에 심지 않는다.** 이 헬퍼는 ViewModel과 수명이 같고, 사용자가 필드 편집
     * 창에서 `semanticRole`을 고친 직후 같은 ViewModel로 저장하는 경로가 실재한다 —
     * 무효화를 놓치면 역할 연동이 조용히 옛 정의로 돈다(변수 제어). 루프가 만들어 넘기는
     * 표는 그 노출이 원리적으로 없다.
     */
    suspend fun syncFieldToStateChange(
        characterId: Long,
        fields: List<FieldDefinition>,
        values: List<CharacterFieldValue>,
        clearableFieldIds: Set<Long>? = null
    ) = syncMutex.withLock {
        applyFieldsToStateChange(characterId, fields, values, clearableFieldIds)
    }

    /**
     * @param clearableFieldIds **비움을 판정해도 되는 필드 id**. `null`이면 판정하지 않는다.
     *
     *   `values`는 *있는 값*만 담는다 — 필드를 비우면 행 자체가 사라지므로 이 루프는
     *   *지워짐*이라는 신호를 **원리적으로 볼 수 없다.** 그래서 종전에는 생일 필드를 비워도
     *   `__birth`가 그대로 남아 **알림·홈 배너·위젯이 계속 울렸고**, 상태변화 편집 창이
     *   그 값을 필드에 되쓰기까지 했다.
     *
     *   부재를 곧 비움으로 읽으면 반대 사고가 난다 — 폼이 그 필드를 그리지도 않은 저장이나
     *   전량 조회를 넘기는 경로에서 **연표 사건으로 생긴 `__birth`가 지워진다.** 그래서
     *   *"이 화면이 그 칸을 그렸는데 빈 채로 돌아왔다"*를 아는 쪽만 그 범위를 넘긴다.
     */
    private suspend fun applyFieldsToStateChange(
        characterId: Long,
        fields: List<FieldDefinition>,
        values: List<CharacterFieldValue>,
        clearableFieldIds: Set<Long>?
    ) {
        val fieldMap = fields.associateBy { it.id }
        // **명시가 파생을 이긴다 — 그 판단은 루프 전에 한 번 접는다**([SemanticAlivePrecedence]).
        // 루프는 값을 하나씩 처분하므로 *"같은 저장에 생존여부가 함께 실려 왔는가"*를 알 수
        // 없고, 몰랐기 때문에 사망연도 갈래가 사용자가 고른 '불명'을 매 저장마다 '사망'으로
        // 되덮었다. 미리 접으면 두 갈래의 **차례가 답을 바꾸지 못한다**.
        val deathDerivation = SemanticAlivePrecedence.deathDerivation(fields, values)
        // **비움 처분도 같은 판정을 쓴다.** 사망연도를 *비우는* 저장은 루프 뒤에 무조건 도는데,
        // 그쪽이 생존여부를 '생존'으로 되쓰면 **같은 저장에서 사용자가 고른 '사망'·'불명'을
        // 덮는다** — 위 갈래가 막은 되덮기와 방향만 반대인 같은 결함이다. 특히 «연도 불명
        // 사망»(아래 `deadVal` 갈래가 명시로 허용한다)은 사망연도 칸이 빈 채로 저장되므로
        // 편집 폼에서는 *언제나* 이 경로를 지난다 — 고른 '사망'이 저장 직후 '생존'이 됐다.
        val rewriteAliveOnClear = deathDerivation == SemanticAlivePrecedence.DeathDerivation.APPLY

        for (value in values) {
            val field = fieldMap[value.fieldDefinitionId] ?: continue
            val role = SemanticRole.fromConfig(field.config) ?: continue

            when (role) {
                SemanticRole.BIRTH_YEAR -> {
                    val year = value.value.trim().toIntOrNull() ?: continue
                    upsertStateChange(characterId, CharacterStateChange.KEY_BIRTH, year, null, null)
                    // standardYear가 있고 연동 활성이면 age 필드도 갱신
                    syncBirthYearToAge(characterId, year, fields)
                }
                SemanticRole.BIRTH_DATE -> {
                    val parts = parseBirthDate(value.value) ?: continue
                    val existingBirth = findStateChange(characterId, CharacterStateChange.KEY_BIRTH)
                    val year = existingBirth?.year ?: 0
                    upsertStateChange(characterId, CharacterStateChange.KEY_BIRTH, year, parts.first, parts.second)
                }
                SemanticRole.DEATH_YEAR -> {
                    val raw = value.value.trim()
                    val year = raw.toIntOrNull()
                    if (year != null) {
                        when (deathDerivation) {
                            // 생존여부가 안 실려 온 저장 — 종전 그대로 파생시킨다.
                            SemanticAlivePrecedence.DeathDerivation.APPLY -> {
                                upsertStateChange(characterId, CharacterStateChange.KEY_DEATH, year, null, null)
                                syncDeathToAlive(characterId, fields, isDead = true)
                            }
                            // '사망'·'불명' — 사망연도 이력은 사실이므로 남기되, 생존여부
                            // 필드값과 `__alive`는 아래 ALIVE 갈래가 정한다. 여기서 덮으면
                            // 사용자가 방금 고른 것이 저장 직후 사라진다.
                            SemanticAlivePrecedence.DeathDerivation.HISTORY_ONLY ->
                                upsertStateChange(characterId, CharacterStateChange.KEY_DEATH, year, null, null)
                            // '생존' — ALIVE 갈래가 사망연도와 `__death`를 지운다.
                            // 여기서 적으면 차례에 따라 그것을 **되살린다**.
                            SemanticAlivePrecedence.DeathDerivation.SKIP -> Unit
                        }
                    } else if (raw.isEmpty()) {
                        // 빈 값이 이 루프에 실려 오는 경로는 실사용에 없다(비우면 값 행 자체가
                        // 사라진다) — 그래도 오면 아래 비움 처분과 **같은 함수·같은 인자**를 쓴다.
                        clearDeathDerived(characterId, fields, rewriteAlive = rewriteAliveOnClear)
                    }
                }
                SemanticRole.ALIVE -> {
                    val raw = value.value.trim()
                    if (raw.isEmpty()) continue
                    val aliveField = fieldMap[value.fieldDefinitionId] ?: continue
                    val config = try { JSONObject(aliveField.config) } catch (_: Exception) { continue }
                    val aliveVal = config.stringOr("aliveValue", "")
                    val deadVal = config.stringOr("deadValue", "")

                    // 값 → 표식의 대응은 [SemanticAlivePrecedence.aliveMarker]가 든다 —
                    // 1회 마이그레이션이 같은 대응을 따로 적었다가 갈렸던 자리다(그쪽 KDoc).
                    // 여기 남는 것은 표식마다의 **곁 처분**뿐이다.
                    val marker = SemanticAlivePrecedence.aliveMarker(raw, aliveVal, deadVal) ?: continue
                    upsertAliveStateChange(characterId, marker)
                    if (marker == CharacterStateChange.ALIVE_MARKER_ALIVE) {
                        // "생존" 선택 → 사망연도 있으면 클리어
                        val deathYearField = findFieldByRole(fields, SemanticRole.DEATH_YEAR)
                        if (deathYearField != null) {
                            deleteFieldValueIfExists(characterId, deathYearField.id)
                        }
                        deleteStateChangeByKey(characterId, CharacterStateChange.KEY_DEATH)
                    }
                    // "사망"·"불명"은 사망연도를 건드리지 않는다(연도 불명 사망을 허용한다).
                }
                SemanticRole.AGE -> {
                    // 나이 → 출생연도 역산: birthYear = standardYear - age
                    val age = value.value.trim().toIntOrNull() ?: continue
                    val novel = getNovelForCharacter(characterId) ?: continue
                    val stdYear = novel.standardYear ?: continue
                    if (!standardYearSyncHelper.isLinked(characterId)) continue
                    val birthYear = stdYear - age
                    upsertStateChange(characterId, CharacterStateChange.KEY_BIRTH, birthYear, null, null)
                    // birth_year 필드도 갱신
                    val birthYearField = findFieldByRole(fields, SemanticRole.BIRTH_YEAR)
                    if (birthYearField != null) {
                        upsertFieldValue(characterId, birthYearField.id, birthYear.toString())
                    }
                }
                // HEIGHT·WEIGHT·BODY_SIZE는 체형 분석이 값을 *읽기만* 하고, GENDER는
                // 아예 잇는 시스템 필드가 없는 **표식 전용**이다(그 역할의 `linkedKey`가
                // 비어 있는 것이 그 사실이다). 여기서 아무것도 안 하는 것이 그 역할의 성질이지
                // 빠뜨린 자리가 아니다.
                else -> Unit
            }
        }

        // **비워진 역할을 처분한다** — 위 루프가 볼 수 없는 신호다(위 KDoc).
        // 판정 자체는 [SemanticClearPlan]이 든다(순수 — 시험이 닿는다).
        val cleared = SemanticClearPlan.clearedRoles(fields, values, clearableFieldIds)
        if (SemanticRole.DEATH_YEAR in cleared) {
            clearDeathDerived(characterId, fields, rewriteAlive = rewriteAliveOnClear)
        }
        val clearYear = SemanticRole.BIRTH_YEAR in cleared
        val clearDate = SemanticRole.BIRTH_DATE in cleared
        if (clearYear || clearDate) clearBirthDerived(characterId, fields, clearYear, clearDate)
    }

    /**
     * 출생 파생 정리 — **지운 칸에 해당하는 만큼만** 지운다.
     *
     * 출생연도를 비웠다고 월·일까지 날리면 사용자가 지우지 않은 것을 지우는 것이다. 그래서
     * 연도는 0(미상)으로 내리고 월·일은 그대로 두며, **셋이 다 비면 그때 행을 지운다.**
     * 연도를 비웠으면 그 연도에서 파생된 나이 값도 함께 지운다(안 지우면 나이만 남아
     * 근거 없는 수가 된다).
     */
    private suspend fun clearBirthDerived(
        characterId: Long,
        fields: List<FieldDefinition>,
        clearYear: Boolean,
        clearDate: Boolean
    ) {
        val existing = findStateChange(characterId, CharacterStateChange.KEY_BIRTH)
        if (existing != null) {
            val plan = SemanticClearPlan.planBirthClear(
                existing.year, existing.month, existing.day, clearYear, clearDate
            )
            if (plan.delete) {
                deleteStateChangeByKey(characterId, CharacterStateChange.KEY_BIRTH)
            } else {
                characterRepository.updateStateChange(
                    existing.copy(
                        year = plan.year, month = plan.month, day = plan.day,
                        newValue = buildStateChangeValue(CharacterStateChange.KEY_BIRTH, plan.year)
                    )
                )
            }
        }
        if (clearYear) {
            findFieldByRole(fields, SemanticRole.AGE)?.let { deleteFieldValueIfExists(characterId, it.id) }
        }
    }

    /**
     * 사망 파생 정리 — 사망연도를 비우면 사망 기록이 없어진 것이다.
     *
     * 이 본문은 종전에 `syncFieldToStateChange`의 *"사망연도가 빈 문자열이면"* 갈래에
     * 있었는데, **빈 값을 넘기는 호출처가 하나도 없어 닿지 못하는 코드였다**(필드를 비우면
     * 값 행 자체가 사라진다). 사건 삭제 경로([onDeathEventDeleted])와 같은 처분을 쓴다.
     *
     * @param rewriteAlive 생존여부까지 '생존'으로 되쓸 것인가. **같은 저장이 생존여부를
     *   명시로 실어 왔으면 `false`다** — 그때 생존여부를 정하는 것은 이쪽이 아니라 그 값이고
     *   ([SemanticAlivePrecedence] — 명시가 파생을 이긴다), 되쓰면 사용자가 방금 고른
     *   '사망'·'불명'이 저장 직후 '생존'으로 돌아간다. `__death` 이력을 지우는 것은 어느
     *   쪽이든 한다 — 사망연도를 비운 것은 사실이기 때문이다(연도 불명 사망은 허용된다).
     */
    private suspend fun clearDeathDerived(
        characterId: Long,
        fields: List<FieldDefinition>,
        rewriteAlive: Boolean
    ) {
        deleteStateChangeByKey(characterId, CharacterStateChange.KEY_DEATH)
        if (rewriteAlive) syncDeathToAlive(characterId, fields, isDead = false)
    }

    /**
     * 방향 2: CharacterStateChange 저장 후 → 커스텀 필드값 동기화.
     * 해당 유니버스에 대응 semanticRole 필드가 있으면 CharacterFieldValue를 갱신.
     */
    suspend fun syncStateChangeToField(
        characterId: Long,
        universeId: Long,
        change: CharacterStateChange
    ) = syncMutex.withLock {
        applyStateChangeToFields(characterId, universeRepository.getFieldsByUniverseList(universeId), change)
    }

    /**
     * 같은 일을 하되 **세계관 필드 목록을 부르는 쪽이 든다.**
     *
     * 사건 연쇄 이동처럼 한 세계관의 캐릭터 수십·수백을 잇달아 동기화할 때, 위 함수는
     * 캐릭터마다 필드 전량을 다시 읽는다(그 목록은 호출 사이에 바뀌지 않는다). 부르는 쪽이
     * 세계관당 한 번만 읽어 넘기면 그 반복이 사라진다 —
     * 단일 소스는 그대로다(아래 [applyStateChangeToFields] 하나가 판단을 든다).
     */
    suspend fun syncStateChangeToField(
        characterId: Long,
        fields: List<FieldDefinition>,
        change: CharacterStateChange
    ) = syncMutex.withLock {
        applyStateChangeToFields(characterId, fields, change)
    }

    private suspend fun applyStateChangeToFields(
        characterId: Long,
        fields: List<FieldDefinition>,
        change: CharacterStateChange
    ) {
        when (change.fieldKey) {
            CharacterStateChange.KEY_BIRTH -> {
                // year → birth_year 필드
                val birthYearField = findFieldByRole(fields, SemanticRole.BIRTH_YEAR)
                if (birthYearField != null && change.year != 0) {
                    upsertFieldValue(characterId, birthYearField.id, change.year.toString())
                }
                // month/day → birth_date 필드
                val birthDateField = findFieldByRole(fields, SemanticRole.BIRTH_DATE)
                if (birthDateField != null && change.month != null && change.day != null) {
                    val dateStr = String.format(Locale.US, "%02d-%02d", change.month, change.day)
                    upsertFieldValue(characterId, birthDateField.id, dateStr)
                }
            }
            CharacterStateChange.KEY_DEATH -> {
                val deathYearField = findFieldByRole(fields, SemanticRole.DEATH_YEAR)
                if (deathYearField != null && change.year != 0) {
                    upsertFieldValue(characterId, deathYearField.id, change.year.toString())
                }
                // 타임라인에서 사망 추가 시 alive 필드도 동기화
                syncDeathToAlive(characterId, fields, isDead = true)
            }
        }
    }

    /**
     * 출생 사건 삭제 시 관련 상태변화 + 필드값 정리.
     * - __birth CharacterStateChange 삭제
     * - birth_year, age, birth_date 필드값 클리어
     */
    suspend fun onBirthEventDeleted(characterId: Long, universeId: Long) = syncMutex.withLock {
        val fields = universeRepository.getFieldsByUniverseList(universeId)
        deleteStateChangeByKey(characterId, CharacterStateChange.KEY_BIRTH)
        findFieldByRole(fields, SemanticRole.BIRTH_YEAR)?.let { deleteFieldValueIfExists(characterId, it.id) }
        findFieldByRole(fields, SemanticRole.AGE)?.let { deleteFieldValueIfExists(characterId, it.id) }
        findFieldByRole(fields, SemanticRole.BIRTH_DATE)?.let { deleteFieldValueIfExists(characterId, it.id) }
    }

    /**
     * 사망 사건 삭제 시 관련 상태변화 + 필드값 정리.
     * - __death CharacterStateChange 삭제
     * - death_year 필드값 클리어
     * - alive 필드를 "생존"으로 복원 (출생 기록이 있을 때) 또는 클리어
     */
    suspend fun onDeathEventDeleted(characterId: Long, universeId: Long) = syncMutex.withLock {
        val fields = universeRepository.getFieldsByUniverseList(universeId)
        deleteStateChangeByKey(characterId, CharacterStateChange.KEY_DEATH)
        findFieldByRole(fields, SemanticRole.DEATH_YEAR)?.let { deleteFieldValueIfExists(characterId, it.id) }
        syncDeathToAlive(characterId, fields, isDead = false)
    }

    /** standardYear 연동 활성 여부를 외부에서 확인할 수 있도록 프록시 제공 */
    suspend fun isLinked(characterId: Long): Boolean =
        standardYearSyncHelper.isLinked(characterId)

    private fun findFieldByRole(fields: List<FieldDefinition>, role: SemanticRole): FieldDefinition? {
        return fields.find { SemanticRole.fromConfig(it.config) == role }
    }

    /**
     * "MM-DD", "M-D", 또는 "YYYY-MM-DD" 형식의 생일 문자열을 파싱.
     * 엑셀이 날짜를 자동 변환하여 연도가 붙는 경우 연도를 무시하고 월/일만 추출.
     * @return Pair(month, day) 또는 null
     */
    private fun parseBirthDate(value: String): Pair<Int, Int>? {
        val trimmed = value.trim()
        val parts = trimmed.split("-", "/", ".")
        val month: Int?
        val day: Int?
        when (parts.size) {
            2 -> {
                month = parts[0].trim().toIntOrNull()
                day = parts[1].trim().toIntOrNull()
            }
            3 -> {
                // YYYY-MM-DD → 연도 무시, 월/일만 추출
                month = parts[1].trim().toIntOrNull()
                day = parts[2].trim().toIntOrNull()
            }
            else -> return null
        }
        if (month == null || day == null) return null
        if (month !in 1..12 || !isValidDay(month, day)) return null
        return month to day
    }

    /**
     * 이 캐릭터의 [fieldKey] **정본 행**. 쓰는 쪽(여기)과 읽는 쪽이 같은 행을 집게
     * [SingletonStateChanges.pick]이 든다 — 종전 `find`는 DAO의 `ORDER BY`에 기댄 답이라
     * 그 차례를 아는 사람만 두 자리가 같다는 것을 알 수 있었다(R-50).
     */
    private suspend fun findStateChange(characterId: Long, fieldKey: String): CharacterStateChange? {
        val changes = characterRepository.getChangesByCharacterList(characterId)
        return SingletonStateChanges.pick(changes, fieldKey)
    }

    private suspend fun upsertStateChange(
        characterId: Long,
        fieldKey: String,
        year: Int,
        month: Int?,
        day: Int?
    ) {
        val existing = findStateChange(characterId, fieldKey)
        if (existing != null) {
            characterRepository.updateStateChange(
                existing.copy(
                    year = if (year != 0) year else existing.year,
                    month = month ?: existing.month,
                    day = day ?: existing.day,
                    newValue = buildStateChangeValue(fieldKey, if (year != 0) year else existing.year)
                )
            )
        } else {
            characterRepository.insertStateChange(
                CharacterStateChange(
                    characterId = characterId,
                    year = year,
                    month = month,
                    day = day,
                    fieldKey = fieldKey,
                    newValue = buildStateChangeValue(fieldKey, year)
                )
            )
        }
    }

    private fun buildStateChangeValue(fieldKey: String, year: Int): String {
        return when (fieldKey) {
            CharacterStateChange.KEY_BIRTH -> year.toString()
            CharacterStateChange.KEY_DEATH -> year.toString()
            else -> ""
        }
    }

    /**
     * 출생연도 변경 시, standardYear가 있고 연동 활성이면 age 필드를 자동 갱신.
     */
    private suspend fun syncBirthYearToAge(characterId: Long, birthYear: Int, fields: List<FieldDefinition>) {
        val novel = getNovelForCharacter(characterId) ?: return
        val stdYear = novel.standardYear ?: return
        if (!standardYearSyncHelper.isLinked(characterId)) return
        val ageField = findFieldByRole(fields, SemanticRole.AGE) ?: return
        val age = stdYear - birthYear
        upsertFieldValue(characterId, ageField.id, if (age >= 0) age.toString() else "")
    }

    private suspend fun getNovelForCharacter(characterId: Long): com.novelcharacter.app.data.model.Novel? {
        if (novelRepository == null) return null
        val character = characterRepository.getCharacterById(characterId) ?: return null
        val novelId = character.novelId ?: return null
        return novelRepository.getNovelById(novelId)
    }

    private suspend fun upsertFieldValue(characterId: Long, fieldId: Long, value: String) {
        val existing = characterRepository.getFieldValue(characterId, fieldId)
        if (existing != null) {
            characterRepository.updateFieldValue(existing.copy(value = value))
        } else {
            characterRepository.insertFieldValue(
                CharacterFieldValue(
                    characterId = characterId,
                    fieldDefinitionId = fieldId,
                    value = value
                )
            )
        }
    }

    // ── 생존여부 ↔ 사망연도 연동 헬퍼 ──

    /**
     * 사망연도 변경 시 alive 필드를 자동 갱신.
     * @param isDead true면 사망 확정, false면 사망연도 클리어됨
     */
    private suspend fun syncDeathToAlive(characterId: Long, fields: List<FieldDefinition>, isDead: Boolean) {
        val aliveField = findFieldByRole(fields, SemanticRole.ALIVE) ?: return
        val config = try { JSONObject(aliveField.config) } catch (_: Exception) { return }
        val targetValue = if (isDead) {
            config.stringOr("deadValue", "")
        } else {
            val hasBirth = findStateChange(characterId, CharacterStateChange.KEY_BIRTH) != null
            if (hasBirth) config.stringOr("aliveValue", "") else ""
        }
        if (targetValue.isNotBlank()) {
            upsertFieldValue(characterId, aliveField.id, targetValue)
            upsertAliveStateChange(
                characterId,
                if (isDead) CharacterStateChange.ALIVE_MARKER_DEAD
                else CharacterStateChange.ALIVE_MARKER_ALIVE
            )
        } else {
            deleteFieldValueIfExists(characterId, aliveField.id)
            deleteStateChangeByKey(characterId, CharacterStateChange.KEY_ALIVE)
        }
    }

    private suspend fun upsertAliveStateChange(characterId: Long, status: String) {
        val existing = findStateChange(characterId, CharacterStateChange.KEY_ALIVE)
        if (existing != null) {
            characterRepository.updateStateChange(existing.copy(newValue = status))
        } else {
            characterRepository.insertStateChange(
                CharacterStateChange(
                    characterId = characterId,
                    year = 0,
                    fieldKey = CharacterStateChange.KEY_ALIVE,
                    newValue = status
                )
            )
        }
    }

    private suspend fun deleteStateChangeByKey(characterId: Long, fieldKey: String) {
        val existing = findStateChange(characterId, fieldKey)
        if (existing != null) {
            // 파생 정리는 휴지통 스냅샷을 남기지 않는다 — 이 이력을 지우게 만든 원인(출생·사망
            // 사건 삭제, 필드값 변경)의 스냅샷이 이미 담고 있다. 여기서 또 담으면 같은 이력이
            // 두 벌 남아 복원이 중복되고, 휴지통에는 사용자가 지운 적 없는 항목이 쌓인다.
            characterRepository.deleteStateChange(existing, snapshot = false)
        }
    }

    private suspend fun deleteFieldValueIfExists(characterId: Long, fieldId: Long) {
        characterRepository.deleteFieldValue(characterId, fieldId)
    }
}
