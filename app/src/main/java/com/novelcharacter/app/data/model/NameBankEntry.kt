package com.novelcharacter.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "name_bank",
    foreignKeys = [
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["usedByCharacterId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("isUsed"), Index("usedByCharacterId"), Index("createdAt"),
               Index(value = ["isUsed", "createdAt"]),
               Index(value = ["code"], unique = true)]
)
data class NameBankEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val gender: String = "",
    val origin: String = "",
    val notes: String = "",
    val isUsed: Boolean = false,
    val usedByCharacterId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // 엑셀 왕복 안정 식별자 (F3-D): 이름/성별을 외부에서 편집해도 같은 항목으로 인식.
    // defaultValue = "" 는 MIGRATION_37_38의 ADD COLUMN ... NOT NULL DEFAULT '' 와 스키마를 일치시킨다.
    @ColumnInfo(defaultValue = "")
    val code: String = generateEntityCode()
)
