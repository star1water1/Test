package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.novelcharacter.app.ai.AiCreativity
import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.ai.PromptTemplates
import com.novelcharacter.app.ui.assistant.InsightCategory
import com.novelcharacter.app.ui.stats.PatternType
import org.junit.Test

/**
 * '앱 설정' 시트의 왕복 계약 — [AppSettingsKeys]가 단일 소스다 (B-105).
 *
 * **여기서 잴 수 있는 것과 없는 것을 먼저 적는다.** 실제 읽기·쓰기는 `Context`를 쥐므로
 * ([AppSettingsBindings]) 순수 JVM이 원리적으로 못 본다 — 그쪽의 짝 맞음은
 * `tools/check_app_settings_catalog.sh`(규약 R-45)와 실기기가 지킨다.
 * **이 시험이 지키는 것은 *시트가 무슨 말을 하는가*다.**
 *
 * 방어선 넷:
 * ① *"키가 겹치지 않는다"* — 겹치면 뒤 행이 앞 행을 덮는데 **화면 어디에도 표시가 없다.**
 * ② *"비밀은 동의 없이는 안 나간다"* — 이 판의 안전 약속 전부가 이 한 줄이다.
 * ③ *"종전 11개의 이름이 그대로다"* — 구조를 갈면서 이름을 갈면 **옛 파일이 그 설정을 잃는다.**
 * ④ *"싣지 않는 저장소에는 사유가 있다"* — 이름만 있는 목록은 다음 사람이 못 쓴다.
 */
class AppSettingsKeysTest {

    // ── ① 키가 겹치지 않는다 ──

    @Test
    fun `설정 키는 겹치지 않는다`() {
        val keys = AppSettingsKeys.SPECS.map { it.key }
        assertEquals("같은 키가 두 번 선언됐다 — 뒤 행이 앞 행을 조용히 덮는다", keys.size, keys.toSet().size)
    }

    @Test
    fun `키에 공백이 없다`() {
        // 셀에서 잘라 읽는 값이라 앞뒤 공백이 붙은 키는 영영 안 맞는다.
        for (spec in AppSettingsKeys.SPECS) {
            assertEquals(spec.key, spec.key.trim())
            assertTrue("빈 키", spec.key.isNotEmpty())
        }
    }

    // ── ② 비밀은 동의 없이 나가지 않는다 ──

    @Test
    fun `동의가 없으면 비밀은 내보내지지 않는다`() {
        val exported = AppSettingsKeys.exported(includeSecrets = false)
        assertTrue(
            "동의 없이 비밀이 실렸다 — 이 판의 안전 약속이 이 한 줄이다",
            exported.none { it.disposition == AppSettingsKeys.Disposition.SECRET }
        )
        assertTrue("API 키가 실렸다", AppSettingsKeys.AI_API_KEYS !in exported)
    }

    @Test
    fun `동의하면 비밀도 함께 나간다`() {
        // ①만 있으면 `exported`가 늘 빈 목록을 돌려줘도 통과한다 — 반대쪽을 함께 세워야
        // *가르는가*가 잠긴다.
        val exported = AppSettingsKeys.exported(includeSecrets = true)
        assertTrue(AppSettingsKeys.AI_API_KEYS in exported)
        assertEquals(AppSettingsKeys.SPECS.size, exported.size)
    }

    @Test
    fun `비밀은 API 키 하나뿐이다`() {
        // 비밀이 늘면 동의 문구와 실기기 확인이 함께 늘어야 한다 — 조용히 늘면 사용자가
        // 동의한 것과 실제로 나가는 것이 갈린다.
        assertEquals(
            listOf(AppSettingsKeys.AI_API_KEYS),
            AppSettingsKeys.SPECS.filter { it.disposition == AppSettingsKeys.Disposition.SECRET }
        )
    }

    // ── ③ 옛 파일이 뜻을 잃지 않는다 ──

    @Test
    fun `종전에 실리던 열한 개의 이름이 그대로다`() {
        // 2026.08.09 이전 앱이 실제로 내보내던 키다. 이름을 갈면 옛 파일의 그 설정이
        // '모르는 키'가 되어 통째로 안 들어온다 — 구조 개편이 왕복 무결성을 깨는 자리.
        val before = listOf(
            "theme_mode",
            "backup_include_images", "backup_max_backups",
            "image_compress_enabled", "image_quality_percent", "image_cap_dimension",
            "image_max_long_edge_px", "image_skip_below_enabled", "image_skip_below_bytes",
            "image_editor_remove_policy", "image_auto_link_by_character"
        )
        for (key in before) {
            assertNotNull("옛 키 '$key'가 사라졌다", AppSettingsKeys.specOf(key))
        }
    }

    @Test
    fun `옛 이름은 별칭을 지나 현행 키에 닿는다`() {
        // 지금 별칭은 비어 있다(이름을 하나도 안 갈았다). 그래도 경로 자체는 세워 둔다 —
        // 이름을 갈 때 여기 한 줄이면 옛 파일이 살아난다는 것이 이 함수의 약속이다.
        for ((old, canonical) in AppSettingsKeys.ALIASES) {
            assertEquals("별칭 '$old'가 가리키는 키가 없다", canonical, AppSettingsKeys.specOf(old)?.key)
        }
    }

    @Test
    fun `모르는 키는 없다고 답한다`() {
        assertNull(AppSettingsKeys.specOf("이런 설정은 없다"))
        assertNull(AppSettingsKeys.specOf(""))
    }

    @Test
    fun `키 앞뒤 공백은 잘라서 찾는다`() {
        // 엑셀 셀은 공백이 잘 붙는다 — 관대하게 받는다(개발 의도 4번).
        assertEquals("theme_mode", AppSettingsKeys.specOf("  theme_mode ")?.key)
    }

    // ── ④ 싣지 않는 것에는 사유가 있다 ──

    @Test
    fun `싣지 않는 저장소에는 전부 사유가 적혀 있다`() {
        for ((store, reason) in AppSettingsKeys.EXCLUDED_STORES) {
            assertTrue("'$store'의 사유가 비어 있다", reason.isNotBlank())
            assertTrue("'$store'의 사유가 너무 짧아 근거가 되지 못한다", reason.length >= 10)
        }
    }

    @Test
    fun `앱 내부 이행 기록은 싣지 않는다`() {
        // 이 하나는 '소음'이 아니라 **위험**이라 따로 못 박는다: 실어서 되돌리면 아직 안 한
        // 이행을 했다고 표시하게 되어 그 기기의 데이터가 조용히 옛 형식으로 남는다.
        assertTrue("app_migrations" in AppSettingsKeys.EXCLUDED_STORES)
        assertTrue(AppSettingsKeys.SPECS.none { it.key.startsWith("app_migrations") })
    }

    // ── 형식 ──

    @Test
    fun `숫자 설정만 숫자 셀로 나간다`() {
        // 불리언을 NUMBER로 적으면 "Y"가 숫자 셀에 들어가려다 글자로 떨어지고, 그러면
        // 같은 열에 두 형식이 섞여 엑셀에서 정렬·필터가 어긋난다.
        val numbers = AppSettingsKeys.SPECS.filter { it.kind == AppSettingsKeys.Kind.NUMBER }.map { it.key }
        assertEquals(
            listOf(
                "theme_mode", "backup_max_backups",
                // 휴지통 보관 정책 (B-74) — 초과분의 결과가 영구 삭제라 기기를 옮길 때 함께 간다.
                "trash_max_operations", "trash_retention_days",
                "image_quality_percent", "image_max_long_edge_px",
                "image_skip_below_bytes", "ai_usage_example_count", "ai_style_sample_count",
                "ai_attach_image_count", "ai_image_tag_batch_size",
                // 묶음 단위 전송의 표본 장수 — 배치 장수와 같은 부류의 숫자 취향 설정.
                "ai_image_tag_group_sample_size", "ai_name_suggest_batch_size",
                "stats_completion_required_weight", "supplement_field_threshold"
            ),
            numbers
        )
    }

    // ── 셀 표기 ──

    @Test
    fun `Float은 Double을 거치지 않고 적힌다`() {
        // **콜드 검토가 잡은 자리다.** `1.3f.toDouble()`은 `1.2999999523162842`이고,
        // 그대로 셀에 적으면 사용자가 엑셀에서 보는 것이 그 숫자이며 왕복도 같은 글자로
        // 돌아오지 않는다 — 완성도 가중(`stats_completion_required_weight`)이 실제로 Float다.
        assertEquals("1.3", AppSettingsKeys.formatNumber(1.3f))
        assertEquals("2.7", AppSettingsKeys.formatNumber(2.7f))
        assertEquals("1.5", AppSettingsKeys.formatNumber(1.5f))
    }

    @Test
    fun `정수는 소수점을 달지 않는다`() {
        // `3.0`이 셀에 적히면 사람이 고쳐 넣을 때 형식이 갈린다.
        assertEquals("2", AppSettingsKeys.formatNumber(2f))
        assertEquals("3", AppSettingsKeys.formatNumber(3))
        assertEquals("51200", AppSettingsKeys.formatNumber(51200L))
        assertEquals("100", AppSettingsKeys.formatNumber(100.0))
    }

    @Test
    fun `숫자 셀이 소수로 돌아와도 정수로 읽는다`() {
        // 숫자 셀은 `51200`을 `51200.0`으로 돌려주기도 한다 — `toIntOrNull()`로 바로 받으면
        // 멀쩡한 값이 "숫자가 아닙니다"가 된다.
        assertEquals(51200, AppSettingsKeys.parseIntCell("51200.0"))
        assertEquals(3, AppSettingsKeys.parseIntCell(" 3 "))
        assertNull(AppSettingsKeys.parseIntCell("셋"))
        assertNull(AppSettingsKeys.parseIntCell(""))
    }

    @Test
    fun `같은 수는 표기가 달라도 같다고 판정한다`() {
        // 이 술어 하나를 미리보기(AppSettingsDiff)와 가져오기의 read-back 대조(numberBinding)가
        // 함께 쓴다 — 두 벌이면 '동일' 예고와 '조정됨' 경고가 갈리는 날이 온다(R-33).
        assertTrue(AppSettingsKeys.sameNumericCell("3", "3.0"))
        assertTrue(AppSettingsKeys.sameNumericCell(" 85 ", "85"))
        // Float 값의 표기(formatNumber)와 사용자가 적은 글자가 같은 수로 읽혀야
        // 무편집 왕복에서 '조정됨'이 뜨지 않는다.
        assertTrue(AppSettingsKeys.sameNumericCell("0.1", AppSettingsKeys.formatNumber(0.1f)))
        // 접힌 값은 달라야 한다 — 같다고 접으면 조정 경고가 영영 안 뜬다.
        assertTrue(!AppSettingsKeys.sameNumericCell("500", "100"))
        assertTrue(!AppSettingsKeys.sameNumericCell("50.7", "50"))
        // 수로 안 읽히면 글자로 견준다 — 못 읽는 값을 '같다'로 접으면 다른 값이 숨는다.
        assertTrue(AppSettingsKeys.sameNumericCell("셋", "셋"))
        assertTrue(!AppSettingsKeys.sameNumericCell("셋", "넷"))
    }

    @Test
    fun `불리언 표기는 관대한 파서가 되읽는다`() {
        assertEquals("Y", AppSettingsKeys.formatBoolean(true))
        assertEquals("N", AppSettingsKeys.formatBoolean(false))
        // 내보낸 글자를 그대로 되읽어야 왕복이 성립한다.
        assertTrue(parseSheetBoolean(AppSettingsKeys.formatBoolean(true)))
        assertTrue(!parseSheetBoolean(AppSettingsKeys.formatBoolean(false)))
    }

    @Test
    fun `싣는 설정이 종전보다 늘었다`() {
        // 이 판의 목적 자체다 — 구조를 갈고도 11개면 아무것도 고치지 못한 것이다.
        assertTrue(
            "종전 11개보다 늘지 않았다 — 이 판이 고치려던 것이 '개수가 아니라 구조'이긴 하나, 구조를 갈았으면 늘 수 있어야 한다",
            AppSettingsKeys.SPECS.size > 11
        )
    }

    // ── '설명'·'입력 가능한 값' 두 칸 (사용자 요청 2026.08.20) ──

    @Test
    fun `모든 설정이 설명을 갖는다`() {
        // 빈 칸이 하나라도 있으면 사용자는 **그 행만** 뜻을 알 수 없고, 그 사실이
        // 화면에도 검사에도 드러나지 않는다(실패의 모양이 침묵인 부류).
        val blank = AppSettingsKeys.SPECS.filter { it.note.isBlank() }.map { it.key }
        assertTrue("설명이 빈 설정: $blank", blank.isEmpty())
    }

    @Test
    fun `모든 설정이 어떤 입력을 받는지 말한다`() {
        val blank = AppSettingsKeys.SPECS.filter { it.accepts().isBlank() }.map { it.key }
        assertTrue("허용값이 빈 설정: $blank", blank.isEmpty())
    }

    @Test
    fun `허용값 글은 상수에서 나온다`() {
        // R-14 — 손으로 적으면 상한을 옮기는 날 시트만 낡는다. 상수를 실제로 읽는지
        // 값으로 확인한다(문구를 통째로 견주면 문장을 다듬을 때마다 시험이 깨진다).
        val quality = AppSettingsKeys.IMAGE_QUALITY_PERCENT.accepts()
        assertTrue(quality, quality.contains("10~100"))
        val creativity = AppSettingsKeys.AI_CREATIVITY.accepts()
        AiCreativity.entries.forEach { assertTrue(creativity, creativity.contains(it.wire)) }
        val attach = AppSettingsKeys.AI_ATTACH_IMAGE_COUNT.accepts()
        assertTrue(attach, attach.contains(AiPromptPolicy.ATTACH_IMAGES_MAX.toString()))
    }

    @Test
    fun `기본이 켬인 불리언은 빈 칸이 끄기임을 말한다`() {
        // **이것을 말하지 않으면 값을 지운 사람은 자기가 그 기능을 껐다는 것을 모른다.**
        val on = AppSettingsKeys.SPECS.filter {
            (it.domain as? AppSettingsKeys.Domain.YesNo)?.defaultOn == true
        }
        assertTrue("기본 켬인 불리언이 하나도 없다 — 시험이 눈멀었다", on.isNotEmpty())
        on.forEach {
            assertTrue(it.key, it.accepts().contains("빈 칸도 끔입니다"))
            assertTrue(it.key, it.accepts().contains("기본 켬"))
        }
    }

    // ── AI 메시지 양식 열넷 (사용자 요청 2026.08.20) ──

    @Test
    fun `메시지 양식 열넷이 전부 실린다`() {
        // 선언을 손으로 적지 않고 `PromptTemplates.Id`에서 지으므로
        // `tools/check_app_settings_catalog.sh` 축 ①이 이 열넷을 보지 못한다. 여기서 본다.
        val keys = AppSettingsKeys.SPECS.map { it.key }.toSet()
        PromptTemplates.Id.entries.forEach {
            assertTrue("${it.key} 가 SPECS에 없다", it.key in keys)
        }
        assertEquals(PromptTemplates.Id.entries.size, AppSettingsKeys.AI_PROMPT_TEMPLATES.size)
    }

    @Test
    fun `메시지 양식은 자리표 이름을 안내에 싣는다`() {
        // 사용자가 엑셀에서 고칠 때 쓸 수 있는 이름을 **그 행에서** 알 수 있어야 한다.
        PromptTemplates.Id.entries.forEach { id ->
            val accepts = AppSettingsKeys.templateSpecOf(id).accepts()
            PromptTemplates.tokensOf(id).forEach { token ->
                assertTrue(
                    "${id.key} 안내에 ${token.name} 이 없다",
                    accepts.contains(PromptTemplates.wrap(token.name))
                )
            }
            assertTrue(id.key, accepts.contains("기본 양식으로 돌아갑니다"))
        }
    }

    @Test
    fun `메시지 양식은 비밀이 아니다`() {
        // 키가 아니라 지시문이라 평문으로 나가도 된다 — 동의를 묻는 대상이 아니다.
        AppSettingsKeys.AI_PROMPT_TEMPLATES.forEach {
            assertEquals(it.key, AppSettingsKeys.Disposition.PORTABLE, it.disposition)
        }
    }

    @Test
    fun `쉼표 목록 설정의 이름이 열거와 갈리지 않는다`() {
        // `AppSettingsKeys`는 순수 선언이라 `ui/` 열거를 직접 가리키지 않는다(프로브가 그
        // 폴더를 빼는 탓에 가리키는 순간 이 파일의 타입 검사가 눈먼다). 그래서 **갈리는 것을
        // 여기서 막는다** — 이 하네스는 양쪽을 다 본다.
        val patterns = AppSettingsKeys.STATS_PATTERN_TYPES.domain as AppSettingsKeys.Domain.ChoiceList
        assertEquals(PatternType.entries.map { it.name }, patterns.values)

        val categories = AppSettingsKeys.ASSISTANT_CATEGORIES.domain as AppSettingsKeys.Domain.ChoiceList
        assertEquals(InsightCategory.entries.map { it.name }, categories.values)
    }
}
