package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.novelcharacter.app.data.model.GradeSystem

@Dao
interface GradeSystemDao {

    @Query("SELECT * FROM grade_systems WHERE universeId = :universeId ORDER BY displayOrder ASC, id ASC")
    suspend fun getByUniverseList(universeId: Long): List<GradeSystem>

    @Query("SELECT * FROM grade_systems ORDER BY universeId ASC, displayOrder ASC, id ASC")
    suspend fun getAllList(): List<GradeSystem>

    @Query("SELECT * FROM grade_systems WHERE id = :id")
    suspend fun getById(id: Long): GradeSystem?

    @Query("SELECT * FROM grade_systems WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): GradeSystem?

    @Query("SELECT * FROM grade_systems WHERE universeId = :universeId AND name = :name LIMIT 1")
    suspend fun getByUniverseAndName(universeId: Long, name: String): GradeSystem?

    @Insert
    suspend fun insert(gradeSystem: GradeSystem): Long

    @Update
    suspend fun update(gradeSystem: GradeSystem)

    @Delete
    suspend fun delete(gradeSystem: GradeSystem)
}
