package com.novelcharacter.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 카드에 보일 이미지의 **영속 랜덤 인덱스**.
 *
 * > **캐릭터 몫은 2026.08.03에 빠졌다(B-103).** 캐릭터는 이제 `CharacterRepresentativeImage`가
 * > 시드로 고른다 — 상태를 저장하지 않으므로 쓰기가 0이고, 목록이 줄었을 때 인덱스가 **다른
 * > 그림을 가리키는** 문제도 없다(`idx % size`가 가려 두고 있던 상태다).
 * >
 * > 남은 것은 **작품·세계관**뿐이고, 그쪽은 *재방출마다 재추첨*이라 주기도 다르다.
 * > 통일할지 지금이 맞는지는 **판정이 필요해 백로그 B-106에 있다** — 이 슬라이스 밖이라
 * > 손대지 않았다(세션 착수 규칙 2번).
 */
object ImageIndexPrefs {
    private const val PREF_NAME = "image_index_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun key(entityType: String, entityId: Long) = "${entityType}_${entityId}"

    fun save(context: Context, entityType: String, entityId: Long, index: Int) {
        prefs(context).edit().putInt(key(entityType, entityId), index).apply()
    }

    fun load(context: Context, entityType: String, entityId: Long): Int? {
        val prefs = prefs(context)
        val k = key(entityType, entityId)
        return if (prefs.contains(k)) prefs.getInt(k, 0) else null
    }

    fun loadAll(context: Context, entityType: String): Map<Long, Int> {
        val prefix = "${entityType}_"
        val result = mutableMapOf<Long, Int>()
        prefs(context).all.forEach { (k, v) ->
            if (k.startsWith(prefix) && v is Int) {
                val id = k.removePrefix(prefix).toLongOrNull()
                if (id != null) result[id] = v
            }
        }
        return result
    }

    fun clear(context: Context, entityType: String, entityId: Long) {
        prefs(context).edit().remove(key(entityType, entityId)).apply()
    }

    fun clearAll(context: Context, entityType: String) {
        val prefs = prefs(context)
        val prefix = "${entityType}_"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }
}
