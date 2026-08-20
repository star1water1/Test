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
        // **이것은 [AppSettingsBindings]의 불변식에 기댄 판정이다** — 숫자 바인딩 전부가
        // `numberBinding` 헬퍼 한 벌을 지나고, 그 본문이 수 아닌 값을 `Applied.No`로 거절한다.
        // 기댄 채로 두면 헬퍼를 안 지나는 새 숫자 바인딩 하나가 조용히 이 예고를 틀리게 만들 수
        // 있어, `tools/check_app_settings_catalog.sh`가 그 불변식을 기계로 지킨다(축 ④).
        if (spec.kind == AppSettingsKeys.Kind.NUMBER && AppSettingsKeys.parseFiniteCell(fileValue) == null) {
            return Effect.SKIPPED
        }
        // **켬·끔으로 안 읽히는 불리언도 같은 처분이다** — 가져오기(boolBinding)가 거절하고
        // 지금 값을 지킨다(종전에는 전부 끔으로 접어 오타가 무음으로 정반대 값이 됐다).
        // 판정은 가져오기와 같은 함수이므로 두 답이 갈릴 수 없다(R-33).
        if (spec.kind == AppSettingsKeys.Kind.BOOLEAN && CsvTokens.parseBooleanOrNull(fileValue) == null) {
            return Effect.SKIPPED
        }
        // **거절될 양식 행은 '갱신'이 아니라 '건너뜀'이다** — 숫자 설정과 같은 근거다
        // (B-102 ⓑ: 실행되지 않을 행을 실행된다고 예고하면 미리보기가 거짓말이 된다).
        // 판정은 가져오기가 쓰는 그 검증기이므로 두 답이 갈릴 수 없다.
        val domain = spec.domain
        if (domain is AppSettingsKeys.Domain.Template &&
            !com.novelcharacter.app.ai.PromptTemplateValidator.isAcceptable(
                domain.id, fileValue.replace("\r\n", "\n").replace('\r', '\n').trim()
            )
        ) {
            return Effect.SKIPPED
        }
        if (currentValue == null) return Effect.UPDATE
        return if (sameValue(spec.kind, normalize(spec, fileValue), normalize(spec, currentValue)))
            Effect.UNCHANGED
        else Effect.UPDATE
    }

    /**
     * 견주기 **직전의 다듬기** — 가져오기가 쓰는 술어와 같아야 한다 (R-33).
     *
     * 둘이 갈리면 미리보기가 거짓말을 한다. AI 메시지 양식에 두 가지가 걸린다.
     *
     * ⓐ **빈 칸은 지우라는 뜻이 아니라 기본 양식으로 되돌리라는 뜻이다** — 빈 양식이라는 것은
     *   없다. 그대로 견주면 *"빈 칸 ↔ 긴 기본 양식"*이 늘 다르게 보여, 아무것도 안 고친
     *   파일이 매번 '갱신'으로 예고되고 실제로는 아무 일도 안 일어난다.
     * ⓑ **셀 안의 줄바꿈은 프로그램마다 다른 글자로 온다** — 쓰기가 `\n`으로 접으므로
     *   견주기도 같은 자리에서 접어야 한다. 안 그러면 무편집 왕복에서 매번 '갱신'이 뜬다.
     */
    private fun normalize(spec: AppSettingsKeys.Spec, raw: String): String {
        val domain = spec.domain
        // **양식 행에만 접는다** — 접는 자리가 가져오기에도 양식 바인딩 하나뿐이라,
        // 다른 TEXT 설정까지 여기서 접으면 *줄바꿈만 다른 값*을 '동일'로 예고해 놓고
        // 실제로는 덮어쓴다(R-33이 금지하는 방향 그대로).
        if (domain !is AppSettingsKeys.Domain.Template) return raw.trim()
        val folded = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        return if (folded.isEmpty()) {
            com.novelcharacter.app.ai.PromptTemplates.default(domain.id).trim()
        } else folded
    }

    private fun sameValue(kind: AppSettingsKeys.Kind, fileValue: String, currentValue: String): Boolean =
        when (kind) {
            AppSettingsKeys.Kind.BOOLEAN ->
                CsvTokens.parseBoolean(fileValue) == CsvTokens.parseBoolean(currentValue)
            // 술어는 한 벌이다 — 가져오기의 read-back 대조(`numberBinding`)와 같은 함수를
            // 부른다. 못 읽는 값의 처분(글자로 견준다)까지 그 함수의 KDoc이 든다.
            AppSettingsKeys.Kind.NUMBER -> AppSettingsKeys.sameNumericCell(fileValue, currentValue)
            AppSettingsKeys.Kind.TEXT -> fileValue.trim() == currentValue.trim()
        }
}
