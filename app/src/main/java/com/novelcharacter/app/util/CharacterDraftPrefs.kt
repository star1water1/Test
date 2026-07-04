package com.novelcharacter.app.util

import android.content.Context
import com.google.gson.Gson

/**
 * 캐릭터 편집 화면의 영구 드래프트 저장소.
 *
 * onSaveInstanceState는 회전에는 살아남지만 최근 앱 목록에서 태스크를 제거하면
 * 함께 소실된다. 이 저장소는 SharedPreferences에 JSON으로 기록하여
 * 프로세스/태스크 종료 후에도 미저장 입력을 보존하고, 재진입 시 복원을 제안한다.
 *
 * 키는 characterId 기준(신규 작성은 -1L 슬롯 하나)이다.
 */
object CharacterDraftPrefs {

    private const val PREFS_NAME = "character_edit_drafts"

    data class Draft(
        val name: String = "",
        val firstName: String = "",
        val lastName: String = "",
        val anotherName: String = "",
        val tags: String = "",
        val memo: String = "",
        val novelId: Long = -1L,
        val imagePaths: List<String> = emptyList(),
        val fieldValues: Map<String, String> = emptyMap(),
        val savedAt: Long = 0L
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(characterId: Long) = "draft_$characterId"

    fun save(context: Context, characterId: Long, draft: Draft) {
        prefs(context).edit()
            .putString(key(characterId), Gson().toJson(draft))
            .apply()
    }

    fun load(context: Context, characterId: Long): Draft? {
        val json = prefs(context).getString(key(characterId), null) ?: return null
        return try {
            Gson().fromJson(json, Draft::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context, characterId: Long) {
        prefs(context).edit().remove(key(characterId)).apply()
    }

    /**
     * 저장된 모든 드래프트가 참조하는 이미지의 canonical 경로 집합.
     *
     * 드래프트 이미지는 DB에 아직 커밋되지 않았지만 사용자의 미저장 작업물이므로,
     * 고아 이미지 정리·저장 공간 분석의 "참조 집합"에 반드시 포함해야 오삭제/오분류를 막는다.
     */
    fun collectAllDraftImagePaths(context: Context): Set<String> {
        val gson = Gson()
        val result = mutableSetOf<String>()
        val all = try { prefs(context).all } catch (_: Exception) { return emptySet() }
        for ((k, v) in all) {
            if (!k.startsWith("draft_")) continue
            val json = v as? String ?: continue
            val draft = try { gson.fromJson(json, Draft::class.java) } catch (_: Exception) { null } ?: continue
            for (p in draft.imagePaths) {
                runCatching { java.io.File(p).canonicalPath }.getOrNull()?.let { result.add(it) }
            }
        }
        return result
    }
}
