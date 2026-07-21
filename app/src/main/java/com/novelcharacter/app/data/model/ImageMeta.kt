package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 라이브러리 관리 이미지의 메타 행.
 *
 * 행의 존재 자체가 "이 파일은 라이브러리가 관리한다"는 표식이며, 모든 파일 삭제 경로
 * (고아 정리·휴지통 purge·엔티티 삭제·편집창 제거)에서 보호된다. 생성 경로: 이미지 탭 임포트,
 * 태그 부착, 링크 지정, 소유자 0이 되는 제거의 입양(adopt). 삭제는 이미지 탭의 명시적 삭제
 * 또는 스테일 정리(파일 소실 + 24h 경과)뿐이다.
 *
 * [path]는 [com.novelcharacter.app.util.ImageImportHelper.importImage]/스캔이 산출하는
 * absolutePath를 **그대로** 저장한다(코드베이스 관례 — 저장은 absolutePath, 비교만 canonical).
 */
@Entity(
    tableName = "image_meta",
    indices = [
        Index(value = ["path"], unique = true),
        Index("linkGroupId")
    ]
)
data class ImageMeta(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val path: String,
    /** 링크 그룹 토큰(UUID). null=미링크. 같은 토큰을 공유하는 이미지들은 "같은 캐릭터의 이미지"로 함께 배정된다. */
    val linkGroupId: String? = null,
    val importedAt: Long = 0
)
