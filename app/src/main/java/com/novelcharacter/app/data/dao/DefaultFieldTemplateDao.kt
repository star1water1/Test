package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.novelcharacter.app.data.model.DefaultFieldTemplate

/**
 * 전역 기본 필드 템플릿 DAO (B-119).
 *
 * **여기에는 `entityType` 기본값을 두지 않는다.** [FieldDefinitionDao]가 그 기본값(캐릭터)
 * 때문에 R-29의 사각을 만들었고, 이 표는 전량 조회가 자연스러운 짧은 목록이라 걸러야 할
 * 자리가 화면 하나뿐이다 — 종류로 거르는 것은 부르는 쪽이 명시한다.
 */
@Dao
interface DefaultFieldTemplateDao {

    /** 관리 화면이 관찰하는 전량. 종류로 나눠 보는 것은 화면의 몫이다. */
    @Query("SELECT * FROM default_field_templates ORDER BY entityType ASC, displayOrder ASC, id ASC")
    fun getAll(): LiveData<List<DefaultFieldTemplate>>

    @Query("SELECT * FROM default_field_templates ORDER BY entityType ASC, displayOrder ASC, id ASC")
    suspend fun getAllList(): List<DefaultFieldTemplate>

    @Query("SELECT * FROM default_field_templates WHERE entityType = :entityType ORDER BY displayOrder ASC, id ASC")
    suspend fun getByEntityTypeList(entityType: String): List<DefaultFieldTemplate>

    @Query("SELECT * FROM default_field_templates WHERE id = :id")
    suspend fun getById(id: Long): DefaultFieldTemplate?

    /** 심긴 필드의 config 표식([com.novelcharacter.app.data.model.DefaultFieldRef])이 이 길로 원본을 찾는다. */
    @Query("SELECT * FROM default_field_templates WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): DefaultFieldTemplate?

    /** 심는 자리의 정체 — 유니크 인덱스와 같은 짝이다. 승격이 "이미 템플릿인가"를 이것으로 묻는다. */
    @Query("SELECT * FROM default_field_templates WHERE entityType = :entityType AND `key` = :key LIMIT 1")
    suspend fun getBySlot(entityType: String, key: String): DefaultFieldTemplate?

    @Query("SELECT MAX(displayOrder) FROM default_field_templates WHERE entityType = :entityType")
    suspend fun getMaxOrder(entityType: String): Int?

    @Insert
    suspend fun insert(template: DefaultFieldTemplate): Long

    @Insert
    suspend fun insertAll(templates: List<DefaultFieldTemplate>)

    @Update
    suspend fun update(template: DefaultFieldTemplate)

    @Delete
    suspend fun delete(template: DefaultFieldTemplate)

    @Query("DELETE FROM default_field_templates")
    suspend fun deleteAll()
}
