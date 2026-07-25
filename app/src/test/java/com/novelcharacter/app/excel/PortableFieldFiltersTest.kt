package com.novelcharacter.app.excel

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 프리셋 필드 필터의 엑셀 왕복 이식성 — fieldId 재매핑·구버전 관대 수용·해석 실패 보고. */
class PortableFieldFiltersTest {

    private val inAppJson =
        """[{"fieldId":7,"fieldName":"거주지","values":["서울","부산"],"matchMode":"exact"}]"""

    @Test
    fun augment_addsStableKey_resolve_remapsId() {
        val augmented = PortableFieldFilters.augment(
            inAppJson, mapOf(7L to PortableFieldFilters.StableKey("univA", "residence"))
        )
        assertTrue(augmented.contains("residence"))
        assertTrue(augmented.contains("univA"))

        // 새 기기: 같은 필드가 id 42로 존재
        val resolved = PortableFieldFilters.resolve(
            augmented,
            mapOf(PortableFieldFilters.StableKey("univA", "residence") to 42L),
            existingFieldIds = emptySet()
        )
        assertTrue(resolved.droppedNames.isEmpty())
        val arr = JSONArray(resolved.json)
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals(42L, obj.getLong("fieldId"))
        assertEquals("거주지", obj.getString("fieldName"))
        assertEquals(2, obj.getJSONArray("values").length())
        assertEquals("exact", obj.getString("matchMode"))
        // 저장 JSON에는 왕복 보조 속성이 남지 않는다
        assertTrue(!obj.has("fieldKey") && !obj.has("universeCode"))
    }

    @Test
    fun resolve_unresolvableStableKey_dropsWithReport() {
        val augmented = PortableFieldFilters.augment(
            inAppJson, mapOf(7L to PortableFieldFilters.StableKey("univA", "residence"))
        )
        // 낡은 fieldId(7)가 우연히 현존해도 안정 식별자 미해석이면 신뢰하지 않는다
        val resolved = PortableFieldFilters.resolve(augmented, emptyMap(), existingFieldIds = setOf(7L))
        assertEquals("{}", resolved.json)
        assertEquals(listOf("거주지"), resolved.droppedNames)
    }

    @Test
    fun resolve_legacyJsonWithoutStableKey_keptOnlyIfIdExists() {
        val kept = PortableFieldFilters.resolve(inAppJson, emptyMap(), existingFieldIds = setOf(7L))
        assertTrue(kept.droppedNames.isEmpty())
        assertEquals(7L, JSONArray(kept.json).getJSONObject(0).getLong("fieldId"))

        val dropped = PortableFieldFilters.resolve(inAppJson, emptyMap(), existingFieldIds = emptySet())
        assertEquals("{}", dropped.json)
        assertEquals(listOf("거주지"), dropped.droppedNames)
    }

    @Test
    fun emptyAndBrokenInput_tolerated() {
        assertEquals("{}", PortableFieldFilters.augment("{}", emptyMap()))
        assertEquals("", PortableFieldFilters.augment("", emptyMap()))
        assertEquals("{}", PortableFieldFilters.resolve("", emptyMap(), emptySet()).json)
        assertEquals("{}", PortableFieldFilters.resolve("{}", emptyMap(), emptySet()).json)
        // 파손 입력은 원문 유지 (인앱 파서가 빈 목록으로 관대 처리)
        val broken = "[{bad json"
        assertEquals(broken, PortableFieldFilters.augment(broken, emptyMap()))
        assertEquals(broken, PortableFieldFilters.resolve(broken, emptyMap(), emptySet()).json)
    }
}
