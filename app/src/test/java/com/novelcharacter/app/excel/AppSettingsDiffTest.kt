package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 복원 미리보기가 '앱 설정'을 세는 판정 (B-263 ⓑ).
 *
 * **이 시험이 잠그는 것은 *거짓 예고*다.** 이 범주에서 미리보기의 값어치는 *무엇이 바뀌는가*
 * 하나뿐이라, 안 바뀔 설정을 '갱신'이라 말하거나 거절될 행을 '갱신'이라 말하는 순간
 * 범주 전체가 쓸모없어진다. 화면에서는 어떤 자동 검사도 그 거짓을 못 본다.
 */
class AppSettingsDiffTest {

    private val boolSpec = AppSettingsKeys.Spec("b", AppSettingsKeys.Kind.BOOLEAN)
    private val numSpec = AppSettingsKeys.Spec("n", AppSettingsKeys.Kind.NUMBER)
    private val textSpec = AppSettingsKeys.Spec("t", AppSettingsKeys.Kind.TEXT)

    private fun effect(spec: AppSettingsKeys.Spec?, file: String, current: String?) =
        AppSettingsDiff.effectOf(spec, file, current)

    @Test
    fun `이 버전이 모르는 키는 건너뜀이다`() {
        // 가져오기도 같은 자리에서 세어 한 줄로 알린다 — 미리보기가 '갱신'이라 하면
        // 예고한 것이 실행되지 않는다(B-102 ⓑ).
        assertEquals(AppSettingsDiff.Effect.SKIPPED, effect(null, "아무값", "Y"))
        assertEquals(AppSettingsDiff.Effect.SKIPPED, effect(null, "", null))
    }

    /**
     * **표기가 달라도 뜻이 같으면 안 바뀐다.** 파일은 사람이 손으로 고친 글자를 담고
     * (`TRUE`·`예`·` Y `), `read`는 내보내기 표기(`Y`)를 돌려준다 — 글자로 견주면
     * 바뀌지 않을 설정이 전부 '갱신'으로 예고된다.
     */
    @Test
    fun `불리언은 표기가 달라도 뜻으로 견준다`() {
        for (same in listOf("Y", "y", "YES", "TRUE", "T", "1", "O", "예", "참", " Y ", "Ｙ")) {
            assertEquals("파일값 '$same'", AppSettingsDiff.Effect.UNCHANGED, effect(boolSpec, same, "Y"))
        }
        for (same in listOf("N", "no", "FALSE", "0", "", "아무말")) {
            assertEquals("파일값 '$same'", AppSettingsDiff.Effect.UNCHANGED, effect(boolSpec, same, "N"))
        }
        assertEquals(AppSettingsDiff.Effect.UPDATE, effect(boolSpec, "Y", "N"))
        assertEquals(AppSettingsDiff.Effect.UPDATE, effect(boolSpec, "N", "Y"))
    }

    /**
     * 숫자 셀은 `51200`을 `51200.0`으로 돌려주기도 한다
     * ([AppSettingsKeys.parseIntCell]의 KDoc이 그 사고를 적어 두었다) — 글자로 견주면
     * **왕복만 해도 전부 '갱신'**이 된다.
     */
    @Test
    fun `숫자는 표기가 달라도 수로 견준다`() {
        assertEquals(AppSettingsDiff.Effect.UNCHANGED, effect(numSpec, "3.0", "3"))
        assertEquals(AppSettingsDiff.Effect.UNCHANGED, effect(numSpec, " 3 ", "3"))
        assertEquals(AppSettingsDiff.Effect.UNCHANGED, effect(numSpec, "1.3", "1.3"))
        assertEquals(AppSettingsDiff.Effect.UPDATE, effect(numSpec, "4", "3"))
    }

    /**
     * **수가 아닌 값은 가져오기가 거절하고 지금 값을 지킨다** — 숫자 바인딩 열넷이 전부
     * 그렇다(`check_app_settings_catalog.sh` 축 ④가 그 불변식을 지킨다).
     * 그러니 '갱신'이 아니라 '건너뜀'이다.
     */
    @Test
    fun `수가 아닌 숫자 값은 건너뜀이다`() {
        assertEquals(AppSettingsDiff.Effect.SKIPPED, effect(numSpec, "파랑", "2"))
        assertEquals(AppSettingsDiff.Effect.SKIPPED, effect(numSpec, "", "2"))
        assertEquals(AppSettingsDiff.Effect.SKIPPED, effect(numSpec, "   ", "2"))
        // 지금 값을 못 떠도(null) 마찬가지다 — 거절이 먼저다.
        assertEquals(AppSettingsDiff.Effect.SKIPPED, effect(numSpec, "파랑", null))
    }

    @Test
    fun `글자는 다듬은 뒤 그대로 견준다`() {
        assertEquals(AppSettingsDiff.Effect.UNCHANGED, effect(textSpec, " openai ", "openai"))
        assertEquals(AppSettingsDiff.Effect.UPDATE, effect(textSpec, "openai", "anthropic"))
        // 빈 값으로 비우는 것도 변경이다 — 조용히 '동일'로 접으면 유실을 예고 없이 낸다.
        assertEquals(AppSettingsDiff.Effect.UPDATE, effect(textSpec, "", "openai"))
    }

    /**
     * `read`가 `null`이면 **값이 없다**는 뜻이다(비밀 키가 하나도 없는 경우 따위).
     * 파일이 값을 들고 있으면 그것이 들어가므로 갱신이다.
     */
    @Test
    fun `지금 값이 없으면 갱신이다`() {
        assertEquals(AppSettingsDiff.Effect.UPDATE, effect(textSpec, "sk-…", null))
        assertEquals(AppSettingsDiff.Effect.UPDATE, effect(boolSpec, "Y", null))
        assertEquals(AppSettingsDiff.Effect.UPDATE, effect(numSpec, "3", null))
    }

    /**
     * **지금 값이 수로 안 읽히면 글자로 견준다** — 못 읽는 값을 '같다'로 접으면
     * 고쳐야 할 행이 미리보기에서 사라진다.
     */
    @Test
    fun `지금 값이 수가 아니면 글자로 견준다`() {
        assertEquals(AppSettingsDiff.Effect.UPDATE, effect(numSpec, "3", "알 수 없음"))
    }

    /**
     * **'신규'는 이 범주에서 나오지 않는다** — 설정 키는 늘 존재한다. 어떤 조합에서도
     * 세 처분 밖의 답이 없다는 것을 형태로 잠근다(새 갈래를 더하면 여기가 먼저 걸린다).
     */
    @Test
    fun `처분은 셋뿐이다`() {
        val specs = listOf(boolSpec, numSpec, textSpec, null)
        val files = listOf("", " ", "Y", "3", "3.0", "파랑", "openai")
        val currents = listOf(null, "", "Y", "N", "3", "openai")
        for (s in specs) for (f in files) for (c in currents) {
            val e = effect(s, f, c)
            assertEquals(
                "spec=${s?.kind} file='$f' current=$c",
                true,
                e in AppSettingsDiff.Effect.entries
            )
        }
    }
}
