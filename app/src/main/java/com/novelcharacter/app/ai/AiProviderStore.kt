package com.novelcharacter.app.ai

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.novelcharacter.app.util.AppLogger
import java.util.UUID

/**
 * 프로바이더 설정 목록의 영속 저장소(SharedPreferences + 수동 JSON).
 *
 * - 수동 JsonObject 직렬화: R8 난독화에서 리플렉션 직렬화가 깨지는 문제를 원천 차단.
 * - API 키는 여기 절대 저장하지 않는다 — [AiKeyStore] 전담(설계 경계).
 * - 손상된 항목은 전체를 버리지 않고 해당 항목만 건너뛰며 로그를 남긴다(데이터 유실 최소화, 변수 제어).
 */
class AiProviderStore(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore = AiKeyStore(appContext)

    fun list(): List<AiProviderConfig> {
        val raw = sp.getString(KEY_PROVIDERS, null) ?: return emptyList()
        return try {
            JsonParser.parseString(raw).asJsonArray
                .mapNotNull { element ->
                    try {
                        fromJson(element.asJsonObject)
                    } catch (e: Exception) {
                        AppLogger.error(TAG, "프로바이더 항목 해석 실패 — 해당 항목만 건너뜀", e)
                        null
                    }
                }
                .sortedBy { it.createdAt }
        } catch (e: Exception) {
            AppLogger.error(TAG, "프로바이더 목록 해석 실패", e)
            emptyList()
        }
    }

    fun get(id: String): AiProviderConfig? = list().firstOrNull { it.id == id }

    /** upsert. 새 항목이 첫 등록이면 자동으로 활성 지정(조작 마찰 최소화 — 원칙 04). */
    fun save(config: AiProviderConfig) {
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
        if (!exists && current.isEmpty()) setActiveId(stamped.id)
    }

    /** 삭제 — 연결된 암호화 키도 반드시 함께 지운다. 활성이었다면 활성 해제. */
    fun delete(id: String) {
        persist(list().filterNot { it.id == id })
        keyStore.removeKey(id)
        if (activeId() == id) sp.edit().remove(KEY_ACTIVE).apply()
    }

    fun activeId(): String? = sp.getString(KEY_ACTIVE, null)

    fun setActiveId(id: String) {
        sp.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** 활성 프로바이더 설정. 활성 id가 유효하지 않으면(삭제 등) null. */
    fun active(): AiProviderConfig? = activeId()?.let { get(it) }

    fun newId(): String = UUID.randomUUID().toString()

    private fun persist(configs: List<AiProviderConfig>) {
        val array = JsonArray().apply { configs.forEach { add(toJson(it)) } }
        sp.edit().putString(KEY_PROVIDERS, array.toString()).apply()
    }

    private fun toJson(c: AiProviderConfig): JsonObject = JsonObject().apply {
        addProperty("id", c.id)
        addProperty("protocol", c.protocol.name)
        addProperty("displayName", c.displayName)
        addProperty("baseUrl", c.baseUrl)
        addProperty("model", c.model)
        c.presetId?.let { addProperty("presetId", it) }
        addProperty("createdAt", c.createdAt)
        addProperty("updatedAt", c.updatedAt)
    }

    private fun fromJson(o: JsonObject): AiProviderConfig = AiProviderConfig(
        id = o.get("id").asString,
        protocol = AiProtocol.valueOf(o.get("protocol").asString),
        displayName = o.get("displayName").asString,
        baseUrl = o.get("baseUrl").asString,
        model = o.get("model").asString,
        presetId = o.get("presetId")?.takeIf { it.isJsonPrimitive }?.asString,
        createdAt = o.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
        updatedAt = o.get("updatedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
    )

    companion object {
        private const val TAG = "AiProviderStore"
        private const val PREFS_NAME = "ai_providers"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_ACTIVE = "active_id"
    }
}
