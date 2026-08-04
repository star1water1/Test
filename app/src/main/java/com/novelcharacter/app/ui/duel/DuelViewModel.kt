package com.novelcharacter.app.ui.duel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.repository.DuelRepository
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 대결 화면 넷이 함께 쓰는 읽기·쓰기 통로 (B-104 화면 계층).
 *
 * **계산을 주 스레드에서 하지 않는다.** 점수 적합은 캐릭터 수의 제곱에 붙는 비용이고
 * 실측이 900명·18,000판에서 **적합 128ms + 짝 고르기 147ms + 상성 48ms**다
 * (`docs/duel_system_design_2026-08.md` 6장). 한 판을 누를 때마다 이만큼을 주 스레드에서
 * 돌리면 목표 규모의 위쪽에서 화면이 눈에 띄게 멈춘다 — 그래서 [stateOf]가
 * [Dispatchers.Default]로 넘긴다. Room의 중단 함수는 스스로 자기 실행기로 옮기므로
 * 이 감싸기와 겹치지 않는다.
 *
 * **참가자가 무엇인가는 축이 정하고, 그것을 아는 것은 이 계층이다**
 * ([DuelRepository.stateOf]의 계약). 캐릭터 축은 세계관의 캐릭터 코드이며,
 * **이미지 축은 아직 열지 않았다** — 폴더 왕복이 파일을 개명하면 참가자 코드(=경로)도
 * 함께 옮겨야 하는데 그 경로가 아직 없다(설계 4장 ①의 ⚠️). 열려면 그 슬라이스가 먼저다.
 */
class DuelViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val duelRepository = app.duelRepository
    private val characterRepository = app.characterRepository

    /**
     * 조작 결과 — 화면이 관측해 알리고, [reportResult]가 **작업 이력에도 함께 남긴다.**
     *
     * **한 판 한 판은 여기 오지 않는다**([OpResult.CAT_DUEL]의 설명). 대결은 단순반복이라
     * 누름마다 알리면 그 알림이 다음 짝을 가린다 — 화면이 곧바로 다음 둘을 띄우는 것 자체가
     * 이미 *"들어갔다"*는 신호다. 여기 오는 것은 **판이 무더기로 오가는 조작**뿐이다.
     */
    private val _result = MutableLiveData<OpResult?>()
    val result: androidx.lifecycle.LiveData<OpResult?> get() = _result

    fun clearResult() { _result.value = null }

    /**
     * 한 축을 화면에 올리는 데 필요한 전부.
     *
     * @property charactersByCode 참가자 코드 → 캐릭터. 순수 계층이 내놓는 것은 코드뿐이라
     *   이름·이미지는 여기서 붙인다.
     */
    data class Loaded(
        val axis: DuelAxis,
        val state: DuelRepository.AxisState,
        val charactersByCode: Map<String, Character>,
        val verdicts: List<DuelCounterVerdict>
    )

    // ──────────────────────────────────────────────────────────────────────
    // 축
    // ──────────────────────────────────────────────────────────────────────

    suspend fun axes(universeId: Long): List<DuelAxis> = duelRepository.axes(universeId)

    suspend fun axis(id: Long): DuelAxis? = duelRepository.axis(id)

    suspend fun saveAxis(axis: DuelAxis): DuelAxis = duelRepository.saveAxis(axis)

    /** 축 저장을 알리고 이력에 남긴다 — 순서 바꾸기처럼 사용자가 이미 본 조작은 부르지 않는다. */
    fun reportAxisSaved(axis: DuelAxis, isNew: Boolean) {
        reportResult(
            _result,
            OpResult.success(
                OpResult.CAT_DUEL,
                app.getString(
                    if (isNew) R.string.duel_op_axis_added else R.string.duel_op_axis_edited,
                    axis.name
                )
            )
        )
    }

    /** 같은 세계관·같은 대상에 같은 이름이 있는가 — 유니크 인덱스가 던지기 **전에** 묻는다. */
    suspend fun nameTaken(axis: DuelAxis): Boolean {
        val existing = app.database.duelAxisDao()
            .getByUniverseAndName(axis.universeId, axis.targetType, axis.name)
        return existing != null && existing.id != axis.id
    }

    /** 함께 사라질 판 수 — 삭제를 **묻기 전에** 규모를 알린다(R-4). */
    suspend fun matchCount(axisId: Long): Int = app.database.duelMatchDao().countByAxis(axisId)

    /**
     * 축을 지운다. 판·처분이 FK CASCADE로 함께 죽으므로 저장소가 **지우기 전에** 휴지통에 담는다.
     * @return 함께 들어간 판 수.
     */
    suspend fun deleteAxis(axis: DuelAxis): Int {
        val matches = duelRepository.deleteAxis(axis, app.trashRepository)
        reportResult(
            _result,
            OpResult.success(
                OpResult.CAT_DUEL,
                app.getString(R.string.duel_op_axis_deleted, axis.name, matches)
            )
        )
        return matches
    }

    // ──────────────────────────────────────────────────────────────────────
    // 상태
    // ──────────────────────────────────────────────────────────────────────

    /** 이 축의 참가자 — 캐릭터 축은 세계관의 캐릭터가 그대로 참가자다. */
    suspend fun participants(axis: DuelAxis): List<Character> = participantsOf(axis.universeId)

    /** 세계관의 참가자. 축 목록처럼 **여러 축이 같은 참가자를 보는 자리**가 이것을 직접 부른다. */
    suspend fun participantsOf(universeId: Long): List<Character> =
        characterRepository.getCharactersByUniverseList(universeId)
            // 순서가 순수 계층의 id 배정을 정한다 — 안정된 순서로 넘겨야 결과도 안정된다.
            .sortedBy { it.id }

    /**
     * 축의 현재 상태. 무거운 계산이라 [Dispatchers.Default]에서 돈다.
     *
     * @param characters [participants]가 낸 목록. 화면이 이미 들고 있으면 다시 읽지 않는다 —
     *   매 판 뒤 다시 계산하는 경로라 캐릭터 표를 그때마다 훑으면 그것이 더 비싸진다.
     */
    suspend fun load(axis: DuelAxis, characters: List<Character>): Loaded =
        withContext(Dispatchers.Default) {
            val byCode = characters.associateBy { it.code }
            val state = duelRepository.stateOf(axis, characters.map { it.code })
            Loaded(axis, state, byCode, duelRepository.verdicts(axis.id))
        }

    // ──────────────────────────────────────────────────────────────────────
    // 기록 · 처분
    // ──────────────────────────────────────────────────────────────────────

    /** 한 판. 승자가 두 참가자 중 어느 쪽도 아니면 저장소가 거절하고 null을 낸다. */
    suspend fun record(axisId: Long, aCode: String, bCode: String, winnerCode: String?): DuelMatch? =
        duelRepository.record(axisId, aCode, bCode, winnerCode)

    /** 층 B ① — 그 판을 지운다. 점수는 다시 적합하면 그것이 정확한 답이다. */
    suspend fun undo(match: DuelMatch) = duelRepository.undo(match)

    /**
     * 층 B ②·③ — 같은 관계에 다시 판정하면 덮어쓴다.
     *
     * ③은 **그 짝을 점수 적합에서 뺀다.** 즉 판정 하나가 순위표의 다른 줄까지 움직이므로
     * (둘과 무관한 제3자의 순위가 달라진다 — 설계 3장) 조용히 지나가서는 안 된다.
     */
    suspend fun recordVerdict(
        axisId: Long,
        members: List<String>,
        kind: String,
        relation: String
    ): DuelCounterVerdict? {
        val saved = duelRepository.recordVerdict(axisId, members, kind) ?: return null
        reportResult(
            _result,
            OpResult.success(
                OpResult.CAT_DUEL,
                app.getString(
                    if (kind == DuelCounterVerdict.KIND_COUNTER) {
                        R.string.duel_op_verdict_counter
                    } else {
                        R.string.duel_op_verdict_undecided
                    },
                    relation
                )
            )
        )
        return saved
    }

    /** 처분을 물린다 — 되돌리면 그 짝이 점수 적합으로 돌아온다(여기도 순위가 움직인다). */
    suspend fun clearVerdict(verdict: DuelCounterVerdict, relation: String) {
        duelRepository.clearVerdict(verdict)
        reportResult(
            _result,
            OpResult.success(OpResult.CAT_DUEL, app.getString(R.string.duel_op_verdict_cleared, relation))
        )
    }

    suspend fun verdictById(axisId: Long, id: Long): DuelCounterVerdict? =
        duelRepository.verdicts(axisId).firstOrNull { it.id == id }

    /**
     * 이 짝의 판들 — 층 B ①(*"잘못 눌렀다"*)이 상성 상세에서 지울 대상.
     *
     * 상세 화면에서 되돌리는 것은 *"방금 그거"*가 아니라 **그 관계를 만든 판들**이라,
     * 대결 화면의 되돌리기와 집는 대상이 다르다.
     */
    suspend fun matchesBetween(axisId: Long, codes: List<String>): List<DuelMatch> {
        val members = codes.toSet()
        return app.database.duelMatchDao().getByAxis(axisId)
            .filter { it.aCode in members && it.bCode in members }
    }

    /**
     * 층 B ①의 실행 — **되돌릴 수 없다.** 축 삭제와 달리 휴지통을 거치지 않으므로(판 하나하나가
     * 스냅샷을 갖지 않는다) **이력에 남기는 것이 유일한 자취**다.
     */
    suspend fun deleteMatches(matches: List<DuelMatch>, relation: String) {
        matches.forEach { duelRepository.undo(it) }
        reportResult(
            _result,
            OpResult.success(
                OpResult.CAT_DUEL,
                app.getString(R.string.duel_op_matches_deleted, relation, matches.size)
            )
        )
    }
}
