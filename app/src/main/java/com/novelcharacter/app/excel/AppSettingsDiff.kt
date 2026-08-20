package com.novelcharacter.app.excel

import com.novelcharacter.app.util.CsvTokens

/**
 * 복원 미리보기가 '앱 설정' 행 하나를 어떻게 셀 것인가 — **순수 판정** (B-263 ⓑ).
 *
 * ## 왜 이 범주만 셈이 다른가
 *
 * 다른 범주는 *항목이 있는가*로 신규·갱신·동일을 가르는데, 앱 설정은 **키가 늘 존재한다** —
 * 테마는 언제나 어떤 값이고, 파일이 그 값을 바꾸거나 안 바꾸거나 둘 중 하나다.
 * 그래서 성립하지 않는 것은 **'신규' 하나**이고, 나머지 셋은 그대로 성립한다.
 *
 * > 백로그(B-263 ⓑ)는 *"키-값이라 신규/갱신/동일 셈이 성립하지 않는다"*고 적었는데
 * > **절반만 맞았다.** [AppSettingsBindings.Binding]이 `write`와 함께 **`read`**를 들고 있어
 * > 지금 값을 떠 견줄 수 있다(확정 20-1의 실측 정정). 사용자 판정은 그 실측 위에서 나왔다.
 *
 * ## 표기가 아니라 뜻으로 견준다
 *
 * `read`가 돌려주는 것은 **내보내기 표기**(`Y`/`N`, `3`)이고 파일에 적힌 것은 사람이 손으로
 * 고친 글자일 수 있다(`TRUE`, `3.0`, ` 예 `). 글자로 견주면 **바뀌지 않을 설정이 '갱신'으로**
 * 예고되고, 미리보기가 거짓말을 한다 — 이 범주에서 미리보기의 값어치는 *무엇이 바뀌는가*
 * 하나뿐이라 그 거짓말은 곧 범주 전체를 쓸모없게 만든다.
 *
 * 그래서 [AppSettingsKeys.Kind]마다 가져오기가 쓰는 그 해석으로 견준다:
 * - [AppSettingsKeys.Kind.BOOLEAN] — [CsvTokens.parseBoolean] (가져오기의 `parseSheetBoolean`이
 *   위임하는 그 함수다. 두 벌로 적으면 둘이 다른 값을 참이라 부르는 날이 온다)
 * - [AppSettingsKeys.Kind.NUMBER] — 수로 견준다. `3`과 `3.0`은 같은 값이고, 숫자 셀은
 *   실제로 후자를 돌려준다([AppSettingsKeys.parseIntCell]의 KDoc이 그 사고를 적어 두었다).
 * - [AppSettingsKeys.Kind.TEXT] — 다듬은 글자 그대로. JSON 같은 값은 공백·순서가 달라도
 *   다르다고 보는데, **그쪽으로 틀리는 것이 안전한 방향**이다(안 바뀔 것을 바뀐다고 말하는
 *   것은 놀람이고, 바뀔 것을 안 바뀐다고 말하는 것은 유실이다).
 */
object AppSettingsDiff {

    /** 이 행에 가져오기가 할 일. **'신규'가 없는 것이 이 범주의 성질이다**(위 KDoc). */
    enum class Effect {
        /** 값이 달라 이번 가져오기가 덮어쓴다. */
        UPDATE,

        /** 파일의 값이 지금 값과 같아 아무 일도 일어나지 않는다. */
        UNCHANGED,

        /** 가져오기가 실행하지 않는다 — 이 버전이 모르는 키다. */
        SKIPPED
    }

    /**
     * 행 하나의 처분.
     *
     * @param spec 키가 가리키는 선언. `null`이면 **이 버전이 모르는 키**다 — 가져오기도
     *   같은 자리에서 세어 한 줄로 알리므로([SKIPPED]) 미리보기도 같게 센다.
     * @param fileValue 파일의 `설정값` 셀.
     * @param currentValue 지금 값의 내보내기 표기 — [AppSettingsBindings.Binding.read]의 결과다.
     *   `null`은 **값이 없다**는 뜻이고(비밀 키가 하나도 없는 경우 따위), 파일이 값을 들고
     *   있으면 그것이 들어가므로 [UPDATE]다.
     */
    fun effectOf(spec: AppSettingsKeys.Spec?, fileValue: String, currentValue: String?): Effect {
        if (spec == null) return Effect.SKIPPED
        // **수로 안 읽히는 숫자 설정은 가져오기가 거절하고 지금 값을 지킨다** — 그러니
        // '갱신'이 아니라 '건너뜀'이다(B-102 ⓑ: 실행되지 않을 행을 실행된다고 예고하면
        // 미리보기가 거짓말이 된다). 빈칸도 여기 걸린다.
        //
        // **이것은 [AppSettingsBindings]의 불변식에 기댄 판정이다** — 숫자 바인딩 열넷이
        // 전부 `Applied.No`로 거절한다(실측). 기댄 채로 두면 새 숫자 바인딩 하나가 조용히
        // 이 예고를 틀리게 만들 수 있어, `tools/check_app_settings_catalog.sh`가 그 불변식을
        // 기계로 지킨다(축 ④).
        if (spec.kind == AppSettingsKeys.Kind.NUMBER && fileValue.trim().toDoubleOrNull() == null) {
            return Effect.SKIPPED
        }
        if (currentValue == null) return Effect.UPDATE
        return if (sameValue(spec.kind, fileValue, currentValue)) Effect.UNCHANGED else Effect.UPDATE
    }

    private fun sameValue(kind: AppSettingsKeys.Kind, fileValue: String, currentValue: String): Boolean =
        when (kind) {
            AppSettingsKeys.Kind.BOOLEAN ->
                CsvTokens.parseBoolean(fileValue) == CsvTokens.parseBoolean(currentValue)
            AppSettingsKeys.Kind.NUMBER -> {
                val a = fileValue.trim().toDoubleOrNull()
                val b = currentValue.trim().toDoubleOrNull()
                // 파일 값이 수로 읽히는 것은 위에서 이미 걸렀다 — 여기서 남는 갈래는
                // *지금 값*이 안 읽히는 경우뿐이고, 그때는 **글자로 견준다**.
                // 못 읽는 값을 '같다'로 접으면 고쳐야 할 행이 미리보기에서 사라진다.
                if (a != null && b != null) a == b else fileValue.trim() == currentValue.trim()
            }
            AppSettingsKeys.Kind.TEXT -> fileValue.trim() == currentValue.trim()
        }
}
