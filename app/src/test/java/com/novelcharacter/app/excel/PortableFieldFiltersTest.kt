package com.novelcharacter.app.excel

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프리셋 필드 필터의 엑셀 왕복 이식성 — fieldId 재매핑·세계관코드 별칭·자연키 폴백·해석 실패 보고.
 *
 * 핵심 규약: **DB id는 어떤 단계에서도 단독 근거가 아니다.** id는 기기 이전·복원에서 재발급되므로
 * 존재검사만으로 신뢰하면 '성별' 라벨 아래 실제로는 '거주지'를 거르는 필터가 만들어진다.
 */
class PortableFieldFiltersTest {

    private val inAppJson =
        """[{"fieldId":7,"fieldName":"거주지","values":["서울","부산"],"matchMode":"exact"}]"""

    private fun field(id: Long, uCode: String, key: String, name: String, uName: String = uCode) =
        PortableFieldFilters.DeviceField(id, uCode, uName, key, name)

    private fun index(vararg fields: PortableFieldFilters.DeviceField, aliases: Map<String, String> = emptyMap()) =
        PortableFieldFilters.Index(fields.toList(), aliases)

    private fun augmented() = PortableFieldFilters.augment(
        inAppJson, mapOf(7L to field(7, "univA", "residence", "거주지"))
    )

    @Test
    fun augment_addsStableKey_resolve_remapsId() {
        val aug = augmented()
        assertTrue(aug.contains("residence"))
        assertTrue(aug.contains("univA"))

        // 새 기기: 같은 필드가 id 42로 존재
        val resolved = PortableFieldFilters.resolve(aug, index(field(42, "univA", "residence", "거주지")))
        assertTrue(resolved.warnings.isEmpty())
        val obj = JSONArray(resolved.json).getJSONObject(0)
        assertEquals(42L, obj.getLong("fieldId"))
        assertEquals("거주지", obj.getString("fieldName"))
        assertEquals(2, obj.getJSONArray("values").length())
        assertEquals("exact", obj.getString("matchMode"))
        // 왕복 보조 속성 중 **세계관코드만** 파일 전용이다 — 키는 인앱 필터의 정본이라 남는다(B-11).
        assertEquals("residence", obj.getString("fieldKey"))
        assertTrue(!obj.has("universeCode"))
    }

    // ── 키는 왕복에서 살아남는다 (B-11) ──

    @Test
    fun resolve_keepsDeviceFieldKey_soCrossUniverseFilterSurvivesRoundTrip() {
        // 인앱 필터의 정본이 키로 올라갔으므로(B-11), 가져오기가 표준 속성만 남기며 키를 떨어뜨리면
        // **엑셀을 한 번 다녀온 것만으로** 세계관 A·B를 함께 걸던 필터가 한쪽만 거르게 된다.
        // 사용자에게는 캐릭터가 줄어든 것으로만 보여 원인을 알 길이 없다.
        val aug = augmented()
        val resolved = PortableFieldFilters.resolve(aug, index(field(42, "univA", "residence", "거주지")))
        assertEquals("residence", JSONArray(resolved.json).getJSONObject(0).getString("fieldKey"))
    }

    @Test
    fun resolve_nameFallback_keepsDeviceKeyNotFileKey() {
        // 자연키(필드명)로 해석된 자리 — 파일의 키와 이 기기의 키가 다를 수 있다.
        // id는 기기 것으로 바꿔 놓고 키만 파일 것을 남기면 **둘이 서로 다른 필드를 가리킨다.**
        val fileJson =
            """[{"fieldId":7,"fieldName":"거주지","fieldKey":"residence","universeCode":"univA","values":["서울"],"matchMode":"exact"}]"""
        val resolved = PortableFieldFilters.resolve(fileJson, index(field(42, "univZ", "home_town", "거주지")))
        val obj = JSONArray(resolved.json).getJSONObject(0)
        assertEquals(42L, obj.getLong("fieldId"))
        assertEquals("home_town", obj.getString("fieldKey"))
    }

    @Test
    fun resolve_blankDeviceKey_omitsFieldKey_soIdRungStaysInCharge() {
        // 키가 빈 필드로 해석되면 키를 싣지 않는다 — 빈 키를 실으면 해석기의 키 사다리가
        // *키가 있다*고 읽고 id 폴백을 건너뛰어, 걸리는 것이 하나도 없는 필터가 된다.
        val fileJson =
            """[{"fieldId":7,"fieldName":"거주지","fieldKey":"residence","universeCode":"univA","values":["서울"],"matchMode":"exact"}]"""
        val resolved = PortableFieldFilters.resolve(fileJson, index(field(42, "univA", "", "거주지")))
        val obj = JSONArray(resolved.json).getJSONObject(0)
        assertEquals(42L, obj.getLong("fieldId"))
        assertTrue(!obj.has("fieldKey"))
    }

    @Test
    fun augment_refreshesDisplayName() {
        // 인앱에서 필드명을 바꾼 뒤 내보내면 파일이 현재 이름을 담아야 자연키 폴백이 진실을 본다
        val aug = PortableFieldFilters.augment(inAppJson, mapOf(7L to field(7, "univA", "residence", "거주 지역")))
        assertEquals("거주 지역", JSONArray(aug).getJSONObject(0).getString("fieldName"))
    }

    // ── [902] 세계관코드 별칭 ──

    @Test
    fun resolve_universeCodeAlias_survivesNameMatchedUniverse() {
        // 기기 B에 같은 이름·다른 코드의 세계관이 있어 세계관이 '이름'으로 매칭된 복원.
        // 별칭이 없으면 필터가 전량 폐기됐다.
        val resolved = PortableFieldFilters.resolve(
            augmented(),
            index(field(42, "univB", "residence", "거주지"), aliases = mapOf("univA" to "univB"))
        )
        assertTrue(resolved.warnings.isEmpty())
        assertEquals(42L, JSONArray(resolved.json).getJSONObject(0).getLong("fieldId"))
    }

    @Test
    fun resolve_exactCodeWinsOverAlias() {
        // 별칭이 실재하는 코드를 가리지 않는다 — 정확 일치를 먼저 시도한다
        val resolved = PortableFieldFilters.resolve(
            augmented(),
            index(
                field(11, "univA", "residence", "거주지"),
                field(22, "univB", "residence", "거주지"),
                aliases = mapOf("univA" to "univB")
            )
        )
        assertEquals(11L, JSONArray(resolved.json).getJSONObject(0).getLong("fieldId"))
    }

    @Test
    fun universeCodeAliases_dropsAmbiguousMapping() {
        val a = UniverseCodeAliases()
        a.note("f1", "d1")
        assertEquals(mapOf("f1" to "d1"), a.snapshot())
        // 같은 파일코드가 서로 다른 세계관 둘로 폴백되면 추측하지 않고 폐기한다
        a.note("f1", "d2")
        assertTrue(a.snapshot().isEmpty())
        a.note("f1", "d1")   // 한번 모호로 확정되면 되살아나지 않는다
        assertTrue(a.snapshot().isEmpty())
    }

    @Test
    fun universeCodeAliases_ignoresNoiseEntries() {
        val a = UniverseCodeAliases()
        a.note("", "d1")
        a.note("f1", "")
        a.note("same", "same")
        assertTrue(a.snapshot().isEmpty())
    }

    // ── [905a] 구버전 파일: id 단독 신뢰 금지 ──

    @Test
    fun legacyJson_idAloneIsNotTrusted_naturalKeyDecides() {
        // 구버전 파일(안정 식별자 없음). id 7은 이 기기에서 '성별'을 가리킨다.
        // 존재검사만 하던 예전 동작이면 '거주지' 라벨 아래 성별을 거르는 필터가 됐다.
        val resolved = PortableFieldFilters.resolve(
            inAppJson,
            index(field(7, "u", "gender", "성별"), field(9, "u", "residence", "거주지"))
        )
        assertEquals(9L, JSONArray(resolved.json).getJSONObject(0).getLong("fieldId"))
        assertEquals(1, resolved.nameBasedCount)
    }

    @Test
    fun legacyJson_nameGone_droppedWithActionableWarning() {
        val resolved = PortableFieldFilters.resolve(inAppJson, index(field(7, "u", "gender", "성별")))
        assertEquals("{}", resolved.json)
        assertEquals(1, resolved.warnings.size)
        val w = resolved.warnings.single()
        assertTrue(w.contains("거주지"))
        // 낡은 id가 가리키던 엉뚱한 필드를 사용하지 않았음을 사용자에게 밝힌다
        assertTrue(w.contains("성별"))
        assertTrue(w.contains("필드 정의"))
    }

    @Test
    fun legacyJson_ambiguousName_narrowedByFileFieldId() {
        // 동명 필드가 여럿이면 이름·번호가 합치할 때만 채택한다(두 신호 합치)
        val resolved = PortableFieldFilters.resolve(
            inAppJson,
            index(field(7, "uA", "residence", "거주지"), field(8, "uB", "residence", "거주지"))
        )
        assertEquals(7L, JSONArray(resolved.json).getJSONObject(0).getLong("fieldId"))
    }

    @Test
    fun legacyJson_ambiguousName_noTiebreak_droppedWithWarning() {
        // 번호도 일치하지 않으면 추측하지 않는다 — 오배정보다 미해석 후 보고가 낫다
        val resolved = PortableFieldFilters.resolve(
            inAppJson,
            index(field(31, "uA", "residence", "거주지", "세계관A"), field(32, "uB", "residence", "거주지", "세계관B"))
        )
        assertEquals("{}", resolved.json)
        val w = resolved.warnings.single()
        assertTrue(w.contains("세계관A") && w.contains("세계관B"))
    }

    @Test
    fun stableKeyGone_fallsBackToNameInsteadOfDropping() {
        // 필드키를 외부에서 고친 파일 — 예전에는 즉시 폐기했으나 이름 후보로 재해석한다
        val broken = """[{"fieldId":7,"fieldName":"거주지","fieldKey":"typo","universeCode":"univA"}]"""
        val resolved = PortableFieldFilters.resolve(broken, index(field(55, "univA", "residence", "거주지")))
        assertEquals(55L, JSONArray(resolved.json).getJSONObject(0).getLong("fieldId"))
        assertEquals(1, resolved.nameBasedCount)
    }

    @Test
    fun nameMatching_normalizesNotationOnly() {
        val legacy = """[{"fieldId":1,"fieldName":"  거주 지  "}]"""
        val resolved = PortableFieldFilters.resolve(legacy, index(field(5, "u", "residence", "거주 지")))
        assertEquals(5L, JSONArray(resolved.json).getJSONObject(0).getLong("fieldId"))
    }

    @Test
    fun resolvedLabel_usesDeviceFieldName() {
        // 파일의 낡은 표시명이 라벨-대상 불일치를 만들지 않게 이 기기의 현재 이름으로 저장한다
        val resolved = PortableFieldFilters.resolve(augmented(), index(field(42, "univA", "residence", "거주 지역")))
        assertEquals("거주 지역", JSONArray(resolved.json).getJSONObject(0).getString("fieldName"))
    }

    @Test
    fun emptyAndBrokenInput_tolerated() {
        assertEquals("{}", PortableFieldFilters.augment("{}", emptyMap()))
        assertEquals("", PortableFieldFilters.augment("", emptyMap()))
        assertEquals("{}", PortableFieldFilters.resolve("", index()).json)
        assertEquals("{}", PortableFieldFilters.resolve("{}", index()).json)
        // 파손 입력은 원문 유지 (인앱 파서가 빈 목록으로 관대 처리)
        val broken = "[{bad json"
        assertEquals(broken, PortableFieldFilters.augment(broken, emptyMap()))
        assertEquals(broken, PortableFieldFilters.resolve(broken, index()).json)
    }
}
