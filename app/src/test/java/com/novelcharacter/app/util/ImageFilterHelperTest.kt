package com.novelcharacter.app.util

import com.novelcharacter.app.util.ImageFilterHelper.BaseFilter
import com.novelcharacter.app.util.ImageFilterHelper.Criteria
import com.novelcharacter.app.util.ImageFilterHelper.Facts
import com.novelcharacter.app.util.ImageFilterHelper.OwnerKind
import com.novelcharacter.app.util.ImageFilterHelper.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** 이미지 필터 매칭 계약 — 기본필터×태그OR×검색 AND 조합, 미배정/고아/휴지통 구분. */
class ImageFilterHelperTest {

    private data class Img(val name: String, val facts: Facts)

    private fun img(
        name: String,
        owners: List<Pair<OwnerKind, String>> = emptyList(),
        tags: List<String> = emptyList(),
        status: StatusKind = if (owners.isEmpty()) StatusKind.ORPHAN else StatusKind.REFERENCED
    ) = Img(name, Facts(name, owners.map { it.second }, tags, owners.mapTo(HashSet()) { it.first }, status))

    private val charImg = img("char_a.jpg", listOf(OwnerKind.CHARACTER to "홍길동"), listOf("여성", "흑발"))
    private val novelImg = img("novel_b.jpg", listOf(OwnerKind.NOVEL to "어떤 소설"))
    private val unassigned = img("img_c.jpg", tags = listOf("남성"), status = StatusKind.UNASSIGNED)
    private val orphan = img("char_d.jpg", status = StatusKind.ORPHAN)
    private val trash = img("char_e.jpg", status = StatusKind.TRASH)
    private val all = listOf(charImg, novelImg, unassigned, orphan, trash)

    private fun apply(c: Criteria) = ImageFilterHelper.apply(all, c) { it.facts }.map { it.name }

    @Test fun emptyCriteria_isIdentity() {
        assertEquals(all.map { it.name }, apply(Criteria()))
    }

    @Test fun baseFilter_unassigned_vs_orphan_vs_trash() {
        assertEquals(listOf("img_c.jpg"), apply(Criteria(base = BaseFilter.UNASSIGNED)))
        assertEquals(listOf("char_d.jpg"), apply(Criteria(base = BaseFilter.ORPHAN)))
        assertEquals(listOf("char_e.jpg"), apply(Criteria(base = BaseFilter.TRASH)))
    }

    @Test fun baseFilter_ownerKinds() {
        assertEquals(listOf("char_a.jpg"), apply(Criteria(base = BaseFilter.CHARACTER)))
        assertEquals(listOf("novel_b.jpg"), apply(Criteria(base = BaseFilter.NOVEL)))
    }

    @Test fun tagFilter_isOrWithinSet() {
        // "여성" 또는 "남성" 중 하나라도 → charImg(여성)와 unassigned(남성) 매치
        assertEquals(listOf("char_a.jpg", "img_c.jpg"), apply(Criteria(tags = setOf("여성", "남성"))))
    }

    @Test fun query_matchesFileOwnerTag_caseInsensitive() {
        assertEquals(listOf("char_a.jpg"), apply(Criteria(query = "홍길")))          // 소유자명
        assertEquals(listOf("img_c.jpg"), apply(Criteria(query = "IMG_C")))          // 파일명 대소문자
        assertEquals(listOf("char_a.jpg"), apply(Criteria(query = "흑발")))          // 태그
    }

    @Test fun dimensionsComposeWithAnd() {
        // 캐릭터 소유 AND 태그 여성 AND 검색 '흑' → charImg만
        assertEquals(
            listOf("char_a.jpg"),
            apply(Criteria(base = BaseFilter.CHARACTER, tags = setOf("여성"), query = "흑"))
        )
        // 조합이 모순이면 빈 결과
        assertEquals(emptyList<String>(), apply(Criteria(base = BaseFilter.NOVEL, tags = setOf("여성"))))
    }
}
