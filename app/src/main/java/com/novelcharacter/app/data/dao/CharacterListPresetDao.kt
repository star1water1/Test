package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterListPreset

@Dao
interface CharacterListPresetDao {
    @Query("SELECT * FROM character_list_presets ORDER BY isDefault DESC, updatedAt DESC")
    fun getAllPresets(): LiveData<List<CharacterListPreset>>

    @Query("SELECT * FROM character_list_presets ORDER BY isDefault DESC, updatedAt DESC")
    suspend fun getAllPresetsList(): List<CharacterListPreset>

    @Query("SELECT * FROM character_list_presets WHERE id = :id")
    suspend fun getPresetById(id: Long): CharacterListPreset?

    @Query("SELECT COUNT(*) FROM character_list_presets")
    suspend fun getPresetCount(): Int

    /**
     * 이름으로 하나만 집는다 — 저장 전 겹침 판정(B-191)의 단일 조회다.
     * `name`이 유니크 색인이라 색인 한 번으로 끝나고, 전량 적재(`getAllPresetsList`)를
     * 쓰지 않는 이유가 그것이다.
     */
    @Query("SELECT * FROM character_list_presets WHERE name = :name LIMIT 1")
    suspend fun getPresetByName(name: String): CharacterListPreset?

    /** 이름만 — '다른 이름으로 저장'의 제안 이름을 지을 때만 쓴다(필터 JSON 본문을 싣지 않는다). */
    @Query("SELECT name FROM character_list_presets")
    suspend fun getAllNames(): List<String>

    /**
     * **REPLACE가 아니다**(R-60 · B-191). `name`은 유니크 색인이면서 **사용자가 적는 이름**이라,
     * REPLACE로 넣으면 같은 이름을 적은 순간 옛 프리셋이 *지워지고* 새 행이 들어간다 —
     * 예외도 경고도 없고 id까지 바뀐다. 겹침은 저장 전에 물어서 가르고(덮어쓰기는 `update`가
     * id를 지킨 채 한다), 그래도 뚫고 들어온 겹침은 **예외로 나가야** 호출부가 말할 수 있다.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(preset: CharacterListPreset): Long

    @Update
    suspend fun update(preset: CharacterListPreset)

    @Query("DELETE FROM character_list_presets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM character_list_presets")
    suspend fun deleteAll()
}
