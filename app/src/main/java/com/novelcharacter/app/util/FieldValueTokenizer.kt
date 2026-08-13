package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.StructuredInputConfig
import org.json.JSONObject

/**
 * 필드 값 토큰화의 단일 소스.
 *
 * 통계(StatsDataProvider)·폼 자동완성(DynamicFieldFormBuilder)·필드 데이터 라이브러리(수확/전파/검색)가
 * 각자 콤마 분리를 재구현하면 매칭 규칙이 어긋나므로, 모든 분리·재조합은 여기로 모은다.
 *
 * - [tokenize]/[join]: 라이브러리·검색·자동완성용. trim 후 빈 토큰 제거.
 * - [splitForStats]: 통계 전용 분리(BODY_SIZE·구조화 입력 파트 분기 포함) — StatsDataProvider의
 *   기존 Step 1 로직을 옮긴 것이다.
 *
 * ## 쉼표의 규칙은 [CsvTokens]가 든다 (B-178, 2026.08.11)
 *
 * 종전에는 여기가 예외 없는 `rawValue.split(",")`이라 **한 필드값 안에 쉼표를 담을 길이 원리적으로
 * 없었고**(원칙 01·03의 천장), 동시에 엑셀 목록 셀(R-47)은 이미 따옴표 규약을 갖고 있어
 * **같은 앱 안에서 같은 개념이 두 규칙**이었다(개발 의도 4번): `태그` 칸의 `"주인공, 남자"`는 한
 * 값인데 복수 텍스트 필드 칸의 같은 글자는 따옴표째 두 토큰으로 갈렸다. 이제 둘이 한 규칙이다.
 *
 * **인앱 값은 좁혀서 읽는다** — 감싼 것으로 보는 것은 [CsvTokens.quote]가 그렇게 감쌌을 값일
 * 때뿐이다(`quotedOnlyIfNeeded`). 그래서 `"별칭"`처럼 **따옴표를 글자로 쓰던 기존 값은 한 글자도
 * 바뀌지 않는다.** 뜻이 바뀌는 것은 따옴표 안에 쉼표가 든 값뿐이고, 그것은 지금도 `"a`·`b"`로
 * 갈려 있어 잃을 뜻이 없다 — 사유 원문은 확정 문서 14-2의 19번과 그 항의 착수 조건이다.
 *
 * **네 소비처가 함께 바뀐다**(통계·폼 자동완성·값 라이브러리·검색). 그 사실 자체가 이 판정을
 * 마지막까지 유보하게 한 이유이므로, 소비처를 세는 법을 여기 적어 둔다 —
 * `grep -rn 'FieldValueTokenizer' app/src/main/java` (개수는 적지 않는다. 적으면 낡는다).
 */
object FieldValueTokenizer {

    /** 라이브러리가 값 카탈로그를 관리하는 타입인가.
     *  제외: CALCULATED(파생값), NUMBER(연속값 — 통계 binning 담당),
     *  BODY_SIZE·구조화 입력(파트별 측정치는 카탈로그 값이 아님). */
    fun supportsLibrary(fd: FieldDefinition): Boolean {
        if (!isLibraryType(fd.fieldType)) return false
        return !StructuredInputConfig.fromConfig(fd.config).enabled
    }

    /** 한 캐릭터/사건이 이 필드에 여러 토큰(콤마 구분)을 가질 수 있는가 */
    fun isMultiToken(fd: FieldDefinition): Boolean {
        if (fd.fieldType == FieldType.MULTI_TEXT) return true
        val format = DisplayFormat.fromConfig(fd.config)
        return format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST
    }

    /** 라이브러리·검색·자동완성용 토큰화: trim + 빈 토큰 제거 */
    fun tokenize(fd: FieldDefinition, rawValue: String): List<String> {
        if (rawValue.isBlank()) return emptyList()
        return if (isMultiToken(fd)) splitTokens(rawValue) else listOf(rawValue.trim())
    }

    /**
     * **다중값이 이미 확정된 칸**을 토큰으로 나눈다 — 쉼표 규칙의 유일한 호출 자리(B-178).
     *
     * `FieldDefinition`을 손에 쥐지 않은 자리(읽기 화면의 표시 형식 분기 · AI 제안의 `FieldSpec` ·
     * 필드 설정의 사전 등록 문자열)가 **각자 `split(",")`을 적지 않게 하려고 공개한다.**
     * 그렇게 적힌 자리가 실제로 다섯 있었고, 그중 하나는 **붙이기만 새 규칙이라 값을 망가뜨렸다**
     * (AI 접기가 `join`으로 감싸 놓고 자기 `split`으로 되쪼갰다).
     *
     * 갈라짐은 언제나 *값이 조용히 쪼개지는* 모양으로만 드러난다 — 읽기 화면이 한 값을 두 조각으로
     * 그리는데 통계는 하나로 세는 식이라, 어느 쪽이 틀렸는지 사용자가 알아낼 방법이 없다.
     */
    fun splitMulti(rawValue: String): List<String> =
        CsvTokens.split(rawValue, quotedOnlyIfNeeded = true)

    private fun splitTokens(rawValue: String): List<String> = splitMulti(rawValue)

    /**
     * 통계용 분리 — BODY_SIZE·구조화 입력의 파트 분기를 포함한다.
     *
     * **다중값 갈래는 [tokenize]와 같은 함수를 지난다**(B-178). 종전 이 자리는 *"변경 금지"*라
     * 적혀 있었는데 그것은 *리팩터링이 통계 결과를 바꾸지 말 것*이라는 뜻이었고, 쉼표 규칙 자체가
     * 바뀌는 지금은 **오히려 함께 바뀌어야 한다** — 통계만 옛 규칙으로 남으면 라이브러리가 한 값으로
     * 세는 것을 분포는 두 조각으로 세어, 같은 데이터가 화면마다 다른 수를 낸다(개발 의도 2번).
     * BODY_SIZE·구조화 입력 갈래는 쉼표를 쓰지 않으므로 그대로다.
     */
    fun splitForStats(fd: FieldDefinition, rawValue: String): List<String> {
        return when {
            fd.fieldType == FieldType.BODY_SIZE || StructuredInputConfig.fromConfig(fd.config).enabled -> {
                // 구조화 입력: config의 파트 라벨로 개별 분석
                val structuredConfig = StructuredInputConfig.fromConfig(fd.config)
                if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                    structuredConfig.labeledParts(rawValue)
                } else {
                    // 구조화 설정 없는 BODY_SIZE — separator로 분리 (파트별 값만 반환)
                    val separator = try {
                        JSONObject(fd.config).optString("separator", "-")
                    } catch (_: Exception) { "-" }
                    val parts = rawValue.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.size >= 2) parts else listOf(rawValue.trim())
                }
            }
            isMultiToken(fd) -> splitTokens(rawValue)
            else -> listOf(rawValue.trim())
        }
    }

    /**
     * rename/merge 전파 시 토큰 재조합. 다중값 렌더러·폼 관례인 ", " 결합을 따르되,
     * **구분자를 품은 토큰은 감싼다**(B-178) — 그러지 않으면 전파가 저장한 값을 다음 읽기가
     * 두 토큰으로 되쪼개, 값 하나에 쉼표를 담게 한 이번 변경이 전파 한 번에 무효가 된다.
     * 규칙은 엑셀 목록 셀(R-47)과 **같은 함수**다.
     */
    fun join(tokens: List<String>): String = CsvTokens.join(tokens)

    /**
     * 라이브러리가 값 카탈로그를 관리하는 타입인가 (B-55 — 종전 `LIBRARY_TYPES` 집합).
     *
     * **집합이 아니라 `when`인 것이 요점이다.** 집합으로 두면 새 타입이 그냥 빠지는데, 그 처분은
     * *결정*이 아니라 *누락*이라 화면에 **"이 타입은 라이브러리를 쓰지 않습니다"**로 나타난다 —
     * 사용자는 그것이 의도인지 빠뜨린 것인지 알 길이 없다(원칙 02).
     */
    private fun isLibraryType(type: FieldType?): Boolean = when (type) {
        FieldType.TEXT, FieldType.SELECT, FieldType.MULTI_TEXT, FieldType.GRADE -> true
        // CALCULATED = 파생값 · NUMBER = 연속값(통계 binning 담당) ·
        // BODY_SIZE = 파트별 측정치라 카탈로그 값이 아니다.
        FieldType.CALCULATED, FieldType.NUMBER, FieldType.BODY_SIZE -> false
        null -> false
    }
}
