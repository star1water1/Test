package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "characters",
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("novelId")]
)
data class Character(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val novelId: Long? = null,
    val age: String = "",                    // "528+(223)" 같은 복합 나이 지원
    val isAlive: Boolean? = null,            // 생존 여부 (o/x/?)
    val gender: String = "",                 // 남/여/?
    val height: String = "",                 // cm (문자열로 유연하게)
    val bodyType: String = "",               // 체형
    val race: String = "",                   // 종족
    val jobTitle: String = "",               // 직업/직책
    val isTranscendent: Boolean? = null,     // 초월 유무
    val transcendentNumber: Int? = null,     // 제N 초월자
    val transcendentGeneration: String = "", // 1세대/2세대
    val magicPower: String = "",             // 마력 종류
    val authority: String = "",              // 권능
    val residence: String = "",              // 거주지
    val affiliation: String = "",            // 소속
    val likes: String = "",                  // 좋아하는 것
    val dislikes: String = "",               // 싫어하는 것
    val personality: String = "",            // 성격/MBTI
    val specialNotes: String = "",           // 특이사항
    val appearance: String = "",             // 외모특징
    val combatRank: String = "",             // EX/S+/S/A+/A/A-/B+/B/B-/C+/C/C-/D
    val birthday: String = "",               // 생일 (MM-dd 형식)
    val imagePaths: String = "[]",           // JSON 배열로 여러 이미지 경로
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
