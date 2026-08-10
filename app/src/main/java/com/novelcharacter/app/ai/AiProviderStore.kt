package com.novelcharacter.app.ai

import android.content.Context
import com.novelcharacter.app.util.AppLogger
import java.util.UUID

/**
 * 프로바이더 설정 목록의 영속 저장소(SharedPreferences).
 *
 * - 직렬화 규칙은 [AiProviderCodec]이 단일 소스다 — 순수라 왕복 전체를 시험이 잠근다(B-132).
 *   이 클래스는 SharedPreferences 입출력과 활성 지정만 맡는다.
 * - API 키는 여기 절대 저장하지 않는다 — [AiKeyStore] 전담(설계 경계).
 * - 손상된 항목은 전체를 버리지 않고 해당 항목만 건너뛰며 로그를 남긴다(데이터 유실 최소화, 변수 제어).
 */
class AiProviderStore(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore = AiKeyStore(appContext)

    fun list(): List<AiProviderConfig> {
        val decoded = AiProviderCodec.decode(sp.getString(KEY_PROVIDERS, null))
        if (decoded.unreadable) AppLogger.error(TAG, "프로바이더 목록 해석 실패", null)
        if (decoded.skipped > 0) {
            AppLogger.error(TAG, "프로바이더 항목 ${decoded.skipped}건 해석 실패 — 해당 항목만 건너뜀", null)
        }
        return decoded.configs
    }

    fun get(id: String): AiProviderConfig? = list().firstOrNull { it.id == id }

    /**
     * upsert. 새 항목이면 활성으로 세울지 [AiProviderActivation]이 판정한다 (B-150) —
     * 판정 자체는 순수 계층이고 여기서는 그 답을 실행만 한다.
     *
     * **키를 먼저 저장하고 이 함수를 불러야 한다.** 판정이 "키가 등록됐는가"를 보는데,
     * 순서를 뒤집으면 방금 만든 항목은 언제나 키 없음으로 읽혀 자동 활성이 영영 돌지 않는다.
     *
     * @return 활성이 **다른 프로바이더에서 이 항목으로 옮겨 갔는가** — 호출측은 참일 때
     *   반드시 사용자에게 알린다(묻지 않고 하는 대신 하고 나서 말한다 — 확정 판정).
     */
    fun save(config: AiProviderConfig): Boolean {
        val now = System.currentTimeMillis()
        val current = list()
        val exists = current.any { it.id == config.id }
        val stamped = if (exists) {
            config.copy(updatedAt = now)
        } else {
            config.copy(createdAt = now, updatedAt = now)
        }
        val merged = if (exists) {
            current.map { if (it.id == stamped.id) stamped else it }
        } else current + stamped
        persist(merged)

        // "활성이 있는가"는 저장 **전** 목록에서, 그리고 id가 실재하는지까지 본다 —
        // 활성 id가 이미 지워진 항목을 가리키는 매달린 상태는 '없음'으로 읽혀야 하고,
        // 그래야 그 다음 등록이 활성을 세워 저절로 낫는다(그대로 두면 NO_PROVIDER가 계속 뜬다).
        val outcome = AiProviderActivation.decide(
            isNew = !exists,
            hasKey = keyStore.hasKey(stamped.id),
            hasActiveProvider = current.any { it.id == activeId() }
        )
        if (outcome != AiProviderActivation.Outcome.KEEP) setActiveId(stamped.id)
        return outcome == AiProviderActivation.Outcome.ACTIVATE_MOVED
    }

    /**
     * 삭제 — 연결된 암호화 키도 반드시 함께 지운다.
     *
     * **활성이었다면 남은 것 중 하나가 이어받는다** (B-153) — 누가 이어받는지는
     * [AiProviderActivation.succeedAfterDelete]가 단일 소스다(순수라 시험이 잠근다).
     * 종전에는 활성 id를 지우고 끝이라 **남은 프로바이더가 몇 개든 활성이 비었고**,
     * 그러면 인앱 AI가 전부 실패하는데 목록에는 쓸 수 있는 프로바이더가 그대로 서 있었다.
     *
     * @return 활성을 새로 이어받은 프로바이더. null이면 승계가 없었다는 뜻이고 이유는 둘이다 —
     *   지운 것이 활성이 아니었거나, 남은 것이 하나도 없거나. 호출측은 null이 아닐 때
     *   반드시 사용자에게 알린다(묻지 않고 하는 대신 하고 나서 말한다 — [save]와 같은 관행).
     */
    fun delete(id: String): AiProviderConfig? {
        val remaining = list().filterNot { it.id == id }
        val wasActive = activeId() == id
        persist(remaining)
        keyStore.removeKey(id)
        if (!wasActive) return null

        // 키 조회는 **지운 뒤** 남은 것들에만 묻는다 — 방금 지운 키를 세면 이미 없는 것을 본다.
        val heir = AiProviderActivation.succeedAfterDelete(remaining) { keyStore.hasKey(it) }
        if (heir == null) sp.edit().remove(KEY_ACTIVE).apply() else setActiveId(heir.id)
        return heir
    }

    fun activeId(): String? = sp.getString(KEY_ACTIVE, null)

    fun setActiveId(id: String) {
        sp.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** 활성 프로바이더 설정. 활성 id가 유효하지 않으면(삭제 등) null. */
    fun active(): AiProviderConfig? = activeId()?.let { get(it) }

    fun newId(): String = UUID.randomUUID().toString()

    private fun persist(configs: List<AiProviderConfig>) {
        sp.edit().putString(KEY_PROVIDERS, AiProviderCodec.encode(configs)).apply()
    }

    companion object {
        private const val TAG = "AiProviderStore"
        private const val PREFS_NAME = "ai_providers"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_ACTIVE = "active_id"
    }
}
