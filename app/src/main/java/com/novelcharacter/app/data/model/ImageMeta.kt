package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 라이브러리 관리 이미지의 메타 행.
 *
 * 행의 존재 자체가 "이 파일은 라이브러리가 관리한다"는 표식이며, 모든 파일 삭제 경로
 * (고아 정리·휴지통 purge·엔티티 삭제·편집창 제거)에서 보호된다. 생성 경로: 이미지 탭 임포트,
 * 태그 부착, 링크 지정, 소유자 0이 되는 제거의 입양(adopt), 캐릭터 자동 링크(adoptAuto).
 * 삭제는 이미지 탭의 명시적 삭제, 스테일 정리(파일 소실 + 24h 경과), 그리고 자동 입양분의
 * 자동 반납([com.novelcharacter.app.util.CharacterImageAutoLinker] — 아래 [adoptSource])뿐이다.
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
    /**
     * 링크 그룹 토큰. null=미링크. 같은 토큰을 공유하는 이미지들은 "같은 캐릭터의 이미지"로
     * 함께 배정된다. 수동 링크는 UUID, 캐릭터 자동 링크는 "char:<캐릭터id>"
     * ([com.novelcharacter.app.util.AutoLinkPlanner.AUTO_TOKEN_PREFIX]) — 형태로 구별되어
     * 자동화가 수동 그룹을 건드리지 않는다.
     */
    val linkGroupId: String? = null,
    val importedAt: Long = 0,
    /**
     * 이 행을 만든 주체. null=사용자(임포트·태그·수동 링크·배정 해제 입양 — 구버전 행 포함),
     * [ADOPT_SOURCE_AUTO]=캐릭터 자동 링크가 링크 부여를 위해 만든 행.
     *
     * 자동 행은 자동 링크가 풀릴 때 태그·링크가 없으면 함께 반납(행 삭제)된다 — 자동 링크가
     * 편집창 제거 정책(라이브러리 보존/삭제)의 의미를 바꾸지 않게 하기 위한 구분이다.
     * 사용자가 한 번이라도 직접 만진 행(태그·수동 링크·명시 입양)은 null로 승격되어 보존된다.
     */
    val adoptSource: String? = null
) {
    companion object {
        const val ADOPT_SOURCE_AUTO = "auto"
    }
}
