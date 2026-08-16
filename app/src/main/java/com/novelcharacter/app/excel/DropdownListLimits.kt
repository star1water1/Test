package com.novelcharacter.app.excel

/**
 * 드롭다운(유효성 검사)이 **엑셀의 한도에 걸려 통째로 사라지는 것**을 막는 판정 — 순수 계층 (B-221).
 *
 * ## 무엇이 깨지고 있었나
 *
 * 내보내기는 목록을 `createExplicitListConstraint`로 실었는데, 그것은 값들을 쉼표로 이어
 * **하나의 수식 문자열**(`"가,나,다"`)로 만든다. 엑셀의 그 수식 한도가 **255자**라,
 * 작품·세력·세계관·등급 체계 이름 목록처럼 **사용자가 늘리는 목록**은 실사용 규모에서 넘친다.
 * 넘긴 파일은 열 때 *"파일을 복구했습니다"* 경고가 뜨고 **그 열의 유효성 검사가 제거된다** —
 * 조용한 유실은 아니지만, 사용자가 보기에는 *"앱이 만든 파일이 깨졌다"*이고 드롭다운은 없다.
 *
 * 상한을 **입력이 정하게 두는** 자리라(개발 의도 1번 — 받쳐주는 확장성) 목록을 자르지 않고
 * **담는 방법을 바꾼다**: 한도를 넘으면 값들을 숨김 시트의 한 열에 적고 **범위 참조**로 건다.
 * 범위 참조에는 개수 한도가 없다.
 *
 * ## 길이만이 아니다 — 쉼표와 따옴표도 같은 수식을 깬다
 *
 * 명시 목록은 쉼표가 **구분자**이므로 값 안의 쉼표는 한 값을 둘로 쪼갠다(`"가,나"` 한 값이
 * 두 항목이 된다). 따옴표도 수식을 닫아 버린다. 둘 다 길이와 무관하게 깨지므로 같은 판정에
 * 넣는다 — 범위 참조 쪽은 셀 값이라 어떤 글자든 그대로 담긴다.
 */
object DropdownListLimits {

    /** 명시 목록 수식의 엑셀 한도. 넘으면 열 때 복구 경고와 함께 유효성 검사가 제거된다. */
    const val MAX_FORMULA_CHARS = 255

    /** 입력 안내 상자 본문의 엑셀 한도. */
    const val MAX_PROMPT_CHARS = 255

    /** 오류 상자 본문의 엑셀 한도 — 안내 상자보다 짧다. */
    const val MAX_ERROR_CHARS = 225

    /**
     * POI가 만드는 명시 목록 수식의 길이 — `"가,나,다"`(양끝 따옴표 포함).
     * 실제 문자열을 만들지 않고 세는 이유는 목록이 수천 개일 수 있어서다.
     */
    fun explicitFormulaLength(options: List<String>): Int {
        if (options.isEmpty()) return 2
        var len = 2 + options.size - 1  // 양끝 따옴표 + 쉼표
        for (o in options) len += o.length
        return len
    }

    /** 명시 목록으로 실어도 깨지지 않는가 — 길이·쉼표·따옴표 셋 다 본다. */
    fun fitsExplicitList(options: List<String>): Boolean =
        explicitFormulaLength(options) <= MAX_FORMULA_CHARS &&
            options.none { it.contains(',') || it.contains('"') }

    /**
     * 숨김 목록 시트의 한 열 구간을 가리키는 절대 참조 — `'목록 데이터'!$A$3:$A$120`.
     *
     * 시트명은 언제나 홑따옴표로 감싼다(공백·괄호가 들어 있으면 감싸지 않은 참조가 깨진다).
     * 이름 안의 홑따옴표는 **두 번 적어** 이스케이프하는 것이 엑셀 규약이다 —
     * [sanitizeSheetNameBase]가 앞뒤만 다듬으므로 가운데 것은 남을 수 있다.
     */
    fun rangeFormula(sheetName: String, columnIndex: Int, firstRow: Int, lastRow: Int): String {
        val col = columnLetter(columnIndex)
        val quoted = sheetName.replace("'", "''")
        return "'$quoted'!$$col$${firstRow + 1}:$$col$${lastRow + 1}"
    }

    /** 0-기준 열 번호를 엑셀 열 문자로 (0 → A, 25 → Z, 26 → AA). */
    fun columnLetter(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.append('A' + (n % 26))
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.reverse().toString()
    }

    /**
     * 안내·오류 상자에 실을 목록 요약 — [limit]을 넘지 않으면서 **몇 개를 뺐는지 말한다.**
     *
     * 말없이 자르지 않는 것이 요점이다(개발 의도 2번). 종전에는 목록 전체를 그대로 넣어
     * 긴 목록에서는 이 상자들까지 한도를 넘겼다 — 목록이 길어질수록 안내가 먼저 깨졌다.
     */
    fun summarize(options: List<String>, limit: Int): String {
        if (options.isEmpty()) return ""
        val full = options.joinToString(", ")
        if (full.length <= limit) return full
        // 항목 하나는 최소 1자 + 구분자 2자라, 후보 수는 limit을 넘을 수 없다.
        var shown = minOf(options.size - 1, limit)
        while (shown >= 1) {
            val text = options.take(shown).joinToString(", ") + " 외 ${options.size - shown}개"
            if (text.length <= limit) return text
            shown--
        }
        // 항목 하나조차 안 들어가는 자리. **값이 하나뿐이면 "외 0개"라 적지 않는다** —
        // 뺀 것이 없는데 뺐다고 말하는 문장이라, 있는 그대로 잘렸다는 표시만 남긴다.
        val tail = if (options.size > 1) " 외 ${options.size - 1}개" else ""
        val head = options[0].take((limit - tail.length - 1).coerceAtLeast(1))
        return "$head…$tail"
    }
}
