package com.novelcharacter.app.util

import android.content.Context
import android.content.SharedPreferences

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
