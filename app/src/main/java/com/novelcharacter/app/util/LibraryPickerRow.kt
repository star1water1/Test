package com.novelcharacter.app.util

/**
 * 라이브러리 이미지 피커의 행 1장과 그 **선별 규칙** — 순수 계층(Android 무의존).
 *
 * **왜 떼어냈는가:** 피커 화면(`ui/character/ImageLibraryPickerBottomSheet`)은 이 저장소의
 * 실클래스패스 프로브가 열지 않는 자리라, 거기 둔 규칙은 **로컬에서 한 줄도 검사되지 않는다.**
 * 그중 조용히 깨질 수 있는 것이 [factsOf]다 — 배정 여부를 [ImageFilterHelper.StatusKind]로
 * 옮기는 이 한 줄이 틀리면 '아직 아무 데도 안 쓴 것만' 칩이 **아무것도 안 보여 주거나
 * 전부 보여 준다.** 둘 다 오류처럼 보이지 않아서 위험하다.
 *
 * **선별 자체는 [ImageFilterHelper]가 한다 — 여기서 다시 짜지 않는다.** 이 파일이 하는 일은
 * 피커의 행을 그 검사기가 아는 [ImageFilterHelper.Facts]로 옮기는 것뿐이다. 규칙을 복사하면
 * 이미지 탭에서 검색되던 것이 피커에서는 안 나오는 갈림이 생긴다.
 */
data class LibraryPickerRow(
    val path: String,
    val tags: List<String>,
    /** 이 이미지를 이미 쓰고 있는 대상 이름들. 비어 있으면 아직 아무 데도 안 쓴 것이다. */
    val ownerNames: List<String>,
    val ownerKinds: Set<ImageFilterHelper.OwnerKind>,
    val linkGroupId: String?,
    val importedAt: Long
) {
    val isAssigned: Boolean get() = ownerNames.isNotEmpty()

    /** 검색이 보는 파일명 — 경로의 마지막 조각. */
    val fileName: String get() = path.substringAfterLast('/')
}

object LibraryPickerRows {

    /**
     * 행을 [ImageFilterHelper]가 아는 사실로 옮긴다.
     *
     * 피커는 **고아·휴지통을 가르지 않는다** — 목록에 실리는 것은 파일이 실재하는 라이브러리
     * 이미지뿐이라(호출부가 존재 필터를 건다) 남는 갈래는 '쓰는 중'과 '아직 안 씀' 둘뿐이다.
     */
    fun factsOf(row: LibraryPickerRow): ImageFilterHelper.Facts = ImageFilterHelper.Facts(
        fileName = row.fileName,
        ownerNames = row.ownerNames,
        tags = row.tags,
        ownerKinds = row.ownerKinds,
        status = if (row.isAssigned) {
            ImageFilterHelper.StatusKind.REFERENCED
        } else {
            ImageFilterHelper.StatusKind.UNASSIGNED
        },
        linkGroupId = row.linkGroupId
    )

    /**
     * 표시 순서 — **최신 들여온 것 우선**, 같으면 경로 오름차순(결정적).
     *
     * 추천 스트립([ImageRecommendationHelper])의 2·3순위와 같은 결이다. 두 목록이 다른 순서를
     * 쓰면 같은 이미지가 화면마다 다른 자리에 있어 사용자가 찾지 못한다.
     */
    fun sorted(rows: List<LibraryPickerRow>): List<LibraryPickerRow> =
        rows.sortedWith(compareByDescending<LibraryPickerRow> { it.importedAt }.thenBy { it.path })

    /** 피커가 실제로 보여 줄 목록 — 이미 붙어 있는 것을 뺀 뒤 [ImageFilterHelper]로 거른다. */
    fun visible(
        rows: List<LibraryPickerRow>,
        criteria: ImageFilterHelper.Criteria,
        excludePaths: Set<String>
    ): List<LibraryPickerRow> {
        val pool = if (excludePaths.isEmpty()) rows else rows.filter { it.path !in excludePaths }
        return ImageFilterHelper.apply(pool, criteria) { factsOf(it) }
    }
}
