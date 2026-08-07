package com.novelcharacter.app.util

import com.novelcharacter.app.util.ImageFilterHelper.BaseFilter
import com.novelcharacter.app.util.ImageFilterHelper.Criteria
import com.novelcharacter.app.util.ImageFilterHelper.Facts
import com.novelcharacter.app.util.ImageFilterHelper.OwnerKind
import com.novelcharacter.app.util.ImageFilterHelper.StatusKind
import com.novelcharacter.app.util.ImageFilterHelper.TagFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/** 이미지 필터 매칭 계약 — 기본필터×태그OR×검색 AND 조합, 미배정/고아/휴지통 구분. */
class ImageFilterHelperTest {

    private data class Img(val name: String, val facts: Facts)

    private fun img(
        name: String,
        owners: List<Pair<OwnerKind, String>> = emptyList(),
        tags: List<String> = emptyList(),
        status: StatusKind = if (owners.isEmpty()) StatusKind.ORPHAN else StatusKind.REFERENCED,
        linkGroupId: String? = null,
        detachedAt: Long? = null
    ) = Img(name, Facts(name, owners.map { it.second }, tags, owners.mapTo(HashSet()) { it.first }, status, linkGroupId, detachedAt))

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

    // ── 태그 유무 축 (B-121 — 일괄 AI 태깅의 진입 동선) ──

    @Test fun tagPresence_untagged_keepsOnlyImagesWithNoTags() {
        assertEquals(
            listOf("novel_b.jpg", "char_d.jpg", "char_e.jpg"),
            apply(Criteria(tagPresence = TagFilter.UNTAGGED))
        )
    }

    @Test fun tagPresence_untagged_treatsBlankTagsAsNoTag() {
        // 공백만 든 태그는 태그가 아니다 — 옛 데이터·엑셀 들이기가 남길 수 있고,
        // 그것을 태그로 세면 "태그 없는 것"에서 빠져 일괄 태깅 대상에서 조용히 사라진다.
        val blankOnly = img("blank.jpg", tags = listOf("  ", ""))
        val out = ImageFilterHelper.apply(listOf(blankOnly), Criteria(tagPresence = TagFilter.UNTAGGED)) { it.facts }
        assertEquals(listOf("blank.jpg"), out.map { it.name })
    }

    @Test fun tagPresence_isActiveOnItsOwn() {
        // isActive가 이 축을 못 보면 apply가 통째로 건너뛰어 필터가 조용히 무시된다.
        assertEquals(true, Criteria(tagPresence = TagFilter.UNTAGGED).isActive)
        assertEquals(false, Criteria().isActive)
    }

    @Test fun tagPresence_untagged_andNamedTags_yieldsNothing() {
        // 특례를 두지 않는다 — 태그 없는 이미지가 특정 태그를 가질 수는 없으므로 AND의 정직한 답이다.
        assertEquals(emptyList<String>(), apply(Criteria(tags = setOf("흑발"), tagPresence = TagFilter.UNTAGGED)))
    }

    @Test fun tagPresence_combinesWithBaseFilter() {
        assertEquals(listOf("char_d.jpg"), apply(Criteria(base = BaseFilter.ORPHAN, tagPresence = TagFilter.UNTAGGED)))
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

    // ── 링크 상태 축 (설계 image_folder_tag_ai 5-2) ──

    private val manual = img("m.jpg", listOf(OwnerKind.CHARACTER to "가온"), linkGroupId = "uuid-1")
    private val auto = img("a.jpg", listOf(OwnerKind.CHARACTER to "가온"), linkGroupId = "char:7")
    private val loose = img("l.jpg", listOf(OwnerKind.CHARACTER to "가온"))
    private val linkAll = listOf(manual, auto, loose)

    private fun applyLink(c: Criteria) = ImageFilterHelper.apply(linkAll, c) { it.facts }.map { it.name }

    @Test fun linkAny_isIdentity() {
        assertEquals(listOf("m.jpg", "a.jpg", "l.jpg"), applyLink(Criteria()))
    }

    /** 자동 묶음도 '링크됨'에 든다 — 묶여 있는 것은 사실이다. */
    @Test fun linkLinked_includesAutoGroups() {
        assertEquals(listOf("m.jpg", "a.jpg"), applyLink(Criteria(link = ImageFilterHelper.LinkFilter.LINKED)))
    }

    @Test fun linkUnlinked_findsLooseImages() {
        assertEquals(listOf("l.jpg"), applyLink(Criteria(link = ImageFilterHelper.LinkFilter.UNLINKED)))
    }

    /** 자동만 따로 볼 수 있어야 "내가 묶은 것"을 가려낼 수 있다. */
    @Test fun linkAuto_matchesOnlyCharTokens() {
        assertEquals(listOf("a.jpg"), applyLink(Criteria(link = ImageFilterHelper.LinkFilter.AUTO)))
    }

    /** 링크 축은 소유·상태 축과 **직교**한다 — 둘은 AND로 조합된다. */
    @Test fun linkAxis_combinesWithBaseFilter() {
        val mixed = listOf(
            img("x.jpg", listOf(OwnerKind.CHARACTER to "가온")),
            img("y.jpg", tags = listOf("t"), status = StatusKind.UNASSIGNED),
            img("z.jpg", tags = listOf("t"), status = StatusKind.UNASSIGNED, linkGroupId = "uuid-9")
        )
        val out = ImageFilterHelper.apply(
            mixed,
            Criteria(base = BaseFilter.UNASSIGNED, link = ImageFilterHelper.LinkFilter.UNLINKED)
        ) { it.facts }.map { it.name }
        assertEquals(listOf("y.jpg"), out)
    }

    /** 링크 축만 걸어도 필터가 활성이어야 한다(항등 단축 경로에 걸리면 안 된다). */
    @Test fun linkAxisAlone_marksCriteriaActive() {
        assertEquals(true, Criteria(link = ImageFilterHelper.LinkFilter.UNLINKED).isActive)
        assertEquals(false, Criteria().isActive)
    }

    // ===== 뗀 것 서랍 (B-107 D3) — 요청의 본체는 "섞이지 않게"이므로 배타성이 계약이다 =====

    private val neverDetached = img("fresh.jpg", status = StatusKind.UNASSIGNED)
    private val detached = img("was_used.jpg", status = StatusKind.UNASSIGNED, detachedAt = 1_700_000_000_000L)
    private val drawerSet = listOf(neverDetached, detached)

    private fun applyDrawer(base: BaseFilter) =
        ImageFilterHelper.apply(drawerSet, Criteria(base = base)) { it.facts }.map { it.name }

    /** **이 슬라이스의 핵심 계약**: 뗀 것은 미배정 칩에 잡히지 않는다. */
    @Test fun unassignedChip_excludesDetached() {
        assertEquals(listOf("fresh.jpg"), applyDrawer(BaseFilter.UNASSIGNED))
    }

    @Test fun detachedChip_matchesOnlyDetached() {
        assertEquals(listOf("was_used.jpg"), applyDrawer(BaseFilter.DETACHED))
    }

    /** 배타의 정의 그 자체 — 두 칩의 결과가 겹치지 않고, 합치면 원래 집합이다. */
    @Test fun twoChips_partitionTheUnassignedAxis() {
        val a = applyDrawer(BaseFilter.UNASSIGNED)
        val b = applyDrawer(BaseFilter.DETACHED)
        assertEquals(emptyList<String>(), a.intersect(b.toSet()).toList())
        assertEquals(drawerSet.map { it.name }.toSet(), (a + b).toSet())
    }

    /**
     * 작품이 아직 쓰는 이미지도 뗀 것이다 — 캐릭터 축의 이력이라 현재 소유와 독립이다(D2).
     * 이것이 `DETACHED`가 [StatusKind]를 보지 않는 이유이고, 보게 만들면 이 사례가 사라진다.
     */
    @Test fun detachedChip_ignoresCurrentOwnership() {
        val stillUsedByNovel = img(
            "novel_keeps.jpg", listOf(OwnerKind.NOVEL to "은하전기"),
            detachedAt = 1_700_000_000_000L
        )
        val out = ImageFilterHelper.apply(listOf(stillUsedByNovel), Criteria(base = BaseFilter.DETACHED)) { it.facts }
        assertEquals(listOf("novel_keeps.jpg"), out.map { it.name })
    }

    /** 서랍 칩도 다른 축과 AND로 조합된다(태그·검색이 서랍 안에서 듣는다). */
    @Test fun detachedChip_combinesWithTagAxis() {
        val tagged = img("t.jpg", tags = listOf("배경"), status = StatusKind.UNASSIGNED, detachedAt = 5L)
        val untagged = img("u.jpg", status = StatusKind.UNASSIGNED, detachedAt = 5L)
        val out = ImageFilterHelper.apply(
            listOf(tagged, untagged),
            Criteria(base = BaseFilter.DETACHED, tags = setOf("배경"))
        ) { it.facts }.map { it.name }
        assertEquals(listOf("t.jpg"), out)
    }
}
