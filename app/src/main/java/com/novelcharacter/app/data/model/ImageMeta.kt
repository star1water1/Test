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
    val adoptSource: String? = null,
    /**
     * **캐릭터** 소유가 0이 된 시각. null = 뗀 적 없음(또는 사용자가 표식을 지웠다).
     *
     * 값 하나가 상태와 시점을 겸한다 — `!= null`이 곧 "뗀 것"이고, 언제 뗐는지는 오래 방치된
     * 것부터 정리하는 데 쓴다(설계 D1). 작품·세계관 소유는 판정에서 뺀다: 요청이 겨눈 것이
     * "캐릭터에서 떼어낸 것"이고, 캐릭터에서 뗐지만 작품이 아직 쓰는 이미지도 뗀 것이다(D2).
     *
     * 붙는 자리 셋 — 이미지 탭 배정 해제 · 편집창 제거 후 저장 · 폴더 왕복 배정 해제.
     * 풀리는 자리 둘 — 다시 어떤 캐릭터에 붙을 때(자동) · 사용자가 "뗀 표식 지우기"(수동).
     */
    val detachedAt: Long? = null,
    /**
     * 마지막으로 뗀 캐릭터의 안정 식별자([Character.code]). null = 뗀 적 없거나 알 수 없음.
     *
     * **지금 넣는 이유는 소급이 불가능해서다**(설계 D1) — 나중에 칸을 더하면 그 사이에 뗀
     * 이미지는 출처가 영영 없다. 표시 여부와 무관하게 기록은 지금 시작해야 한다.
     *
     * `id`가 아니라 `code`인 것은 R-1과 같은 이유다 — 캐릭터가 지워져도 문자열은 남고,
     * 못 찾으면 화면이 "지워진 캐릭터에서 뗌"이라고 말하면 된다.
     * **마지막 하나만 남긴다**: 여럿에서 뗀 이력을 다 남기려면 표가 하나 더 필요한데,
     * 서랍의 쓸모에 비해 비싸다.
     */
    val detachedFromCode: String? = null
) {
    /** 뗀 것인가 — 서랍(필터·폴더·엑셀)이 전부 이 한 판정을 쓴다. */
    val isDetached: Boolean get() = detachedAt != null

    companion object {
        const val ADOPT_SOURCE_AUTO = "auto"
    }
}
