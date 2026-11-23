package com.novelcharacter.app.pcbridge

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldDescription
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.novelcharacter.app.util.FieldOptionParser

/**
 * PC 편집 폼의 **기술자 생성과 값 수집** (순수 계층 — Android 비의존).
 *
 * ## 자바스크립트는 도메인 로직을 갖지 않는다
 *
 * 이 파일이 있는 이유다. 브라우저는 *입력칸을 그리고 날 글자를 돌려보내는 일*만 하고,
 * **타입별 결합·분해·검증·서식은 전부 폰의 기존 단일 소스가 한다.** 선택지 목록은
 * [FieldOptionParser], 구조화 입력의 파트와 결합은 [StructuredInputConfig], 다중값 줄은
 * 저장된 글자 그대로다.
 *
 * 규약으로 적어 둘 값어치가 있다 — **판정을 자바스크립트에 한 벌 더 두면 그 순간 두 언어에
 * 같은 질문이 살고, 둘은 반드시 갈린다**(R-50이 SQL·코틀린 쌍둥이에서 배운 것과 같은 축이다).
 * 예컨대 쉼표 분해를 JS에 적으면 `"별칭", 본명` 꼴 옛 값이 앱과 PC에서 다르게 쪼개진다.
 *
 * ## 타입 분기는 enum 전수다
 *
 * [FieldType] `when`을 쓰고 생문자열을 견주지 않는다(R-52 · `tools/check_field_type_branch.sh`).
 * **`null`(알 수 없는 타입)도 갈래 하나다** — 월드패키지·프리셋 JSON·손으로 고친 DB가
 * 모르는 글자를 실어 올 수 있고([FieldDefinition.fieldType]의 KDoc), 그것을 조용히 TEXT로
 * 접으면 저장이 값의 모양을 바꾼다. 읽기 전용 + 사유로 내보낸다.
 *
 * ## 커버 집합은 기술자와 **한 함수에서** 나온다
 *
 * R-5가 정한 것이 이것이다 — 폼의 권한은 폼이 실제로 렌더한 것까지다. 기술자를 만든 목록과
 * 커버 집합이 갈리면 그 차이만큼 값이 조용히 지워지거나 남는다. 그래서 [Described]가 둘을
 * 함께 들고 나간다 — 호출부가 따로 셀 수 없게 하는 것이 목적이다.
 */
object BridgeForm {

    /** 알 수 없는 타입을 웹에 알리는 이름. [FieldType]에는 없는 글자라 충돌하지 않는다. */
    const val TYPE_UNKNOWN = "UNKNOWN"

    /** 기술자 + 커버 집합. **함께 나가는 것이 R-5의 요구다.** */
    data class Described(
        val fields: List<FieldView>,
        val coveredFieldDefinitionIds: Set<Long>
    )

    /** 입력칸 하나의 기술자. */
    data class FieldView(
        val id: Long,
        val key: String,
        val name: String,
        val group: String,
        /** [FieldType.name] 또는 [TYPE_UNKNOWN]. */
        val type: String,
        val required: Boolean,
        /** 필드 설명(ⓘ). 빈 글자면 웹이 아무것도 그리지 않는다. */
        val description: String,
        val input: Input,
        /** 저장 대상이 아닌가 — CALCULATED와 알 수 없는 타입이 참이다. */
        val readOnly: Boolean,
        /** 읽기 전용인 **사유**. null이면 사유가 없다(=읽기 전용이 아니다). */
        val notice: String? = null
    )

    /** 입력칸의 모양. 웹은 이 갈래만 보고 그린다. */
    sealed class Input {
        /** 한 줄 글자 — TEXT(구조화 아님) · MULTI_TEXT의 날 글자. */
        data class Line(val value: String) : Input()

        /** 숫자 한 칸. 글자로 나른다 — 소수·빈 값·단위가 섞인 기존 값을 숫자로 접지 않는다. */
        data class Numeric(val value: String) : Input()

        /** 선택 하나. [options]의 첫 자리는 웹이 "비움"으로 그린다. */
        data class Choice(val value: String, val options: List<String>) : Input()

        /** 구조화 입력 — 파트별 칸. */
        data class Parts(val parts: List<Part>) : Input()

        /** 읽기 전용 표시. */
        data class Readonly(val display: String) : Input()
    }

    /**
     * 구조화 입력의 파트 하나.
     *
     * [hint]를 **코틀린이 조립해서 보낸다** — 앱 폼이 라벨에 단위를 괄호로 붙이는 그 규칙이고
     * (`DynamicFieldFormBuilder`의 파트 힌트),
     * 자바스크립트에 한 벌 더 두면 단위 표기가 두 화면에서 갈린다.
     * [numeric]은 앱이 숫자 키패드를 띄우는 파트(`inputType == "number"`)와 같은 판정이다.
     */
    data class Part(
        val label: String,
        val hint: String,
        val numeric: Boolean,
        val value: String
    )

    /** 웹이 되돌려보낸 한 필드의 값. 파싱되지 않은 **날 글자**다. */
    sealed class Submitted {
        data class Text(val raw: String) : Submitted()
        data class Parts(val raw: List<String>) : Submitted()
    }

    /**
     * 필드 정의와 현재 값으로 기술자를 만든다.
     *
     * @param fields 이 캐릭터의 폼이 렌더하는 필드 정의 — **커버 집합의 모집단이다.**
     * @param values 필드 정의 id → 저장된 값.
     * @param calculatedDisplays CALCULATED 필드의 계산 결과(호출부가 기존 계산기로 낸다).
     *   없는 id는 빈 글자로 그린다 — 계산 실패를 숫자 0으로 접지 않는다.
     */
    fun describe(
        fields: List<FieldDefinition>,
        values: Map<Long, String>,
        calculatedDisplays: Map<Long, String> = emptyMap()
    ): Described {
        val views = ArrayList<FieldView>(fields.size)
        for (field in fields) {
            val stored = values[field.id].orEmpty()
            views.add(viewFor(field, stored, calculatedDisplays[field.id]))
        }
        // 커버 집합은 **기술자를 만든 바로 이 목록**에서 낸다 (R-5).
        // 앱 폼의 `coveredFieldDefinitionIds()`와 같은 모양이다 — CALCULATED도 포함한다.
        return Described(views, fields.mapTo(HashSet()) { it.id })
    }

    private fun viewFor(field: FieldDefinition, stored: String, calculated: String?): FieldView {
        val structured = StructuredInputConfig.effectiveFor(field)
        val type = field.fieldType

        val input: Input
        val readOnly: Boolean
        val notice: String?

        when (type) {
            FieldType.TEXT, FieldType.BODY_SIZE -> {
                // 구조화 입력은 TEXT·BODY_SIZE 공통이다 — 앱 폼이 두 타입을 같은 갈래에 둔다.
                if (structured.enabled && structured.parts.isNotEmpty()) {
                    // 앱 편집 폼과 **같은 함수로 쪼갠다**(`DynamicFieldFormBuilder` 659행).
                    // 구분자가 없는 옛 자유 글이 첫 파트로 몰리는 것까지 같다 — 읽기 화면의
                    // `displayValue`가 그 경우를 따로 다루지만 편집 쪽은 이 모양이고,
                    // PC가 읽기 화면을 닮으면 저장이 값을 바꾼다.
                    val split = structured.splitValue(stored)
                    input = Input.Parts(
                        structured.parts.mapIndexed { idx, part ->
                            Part(
                                label = part.label,
                                hint = if (part.suffix.isNotEmpty()) "${part.label} (${part.suffix})" else part.label,
                                numeric = part.inputType == "number",
                                value = split.getOrNull(idx)?.second.orEmpty()
                            )
                        }
                    )
                } else {
                    input = Input.Line(stored)
                }
                readOnly = false
                notice = null
            }

            FieldType.NUMBER -> {
                input = Input.Numeric(stored)
                readOnly = false
                notice = null
            }

            FieldType.SELECT -> {
                input = Input.Choice(stored, FieldOptionParser.parseSelectOptions(field.config))
                readOnly = false
                notice = null
            }

            FieldType.GRADE -> {
                input = Input.Choice(stored, FieldOptionParser.parseGradeOptions(field.config))
                readOnly = false
                notice = null
            }

            FieldType.MULTI_TEXT -> {
                // **저장된 글자 그대로 나른다.** 쪼개기는 소비처(통계·라이브러리)가 하고,
                // 저장은 줄 자체다 — 앱 폼도 같다. 여기서 쪼개 다시 붙이면 왕복 한 번에
                // 따옴표 관용이 갈린다(R-63).
                input = Input.Line(stored)
                readOnly = false
                notice = null
            }

            FieldType.CALCULATED -> {
                // R-16 — 계산 필드는 저장 행이 없다. 웹에서도 쓰지 않는다.
                input = Input.Readonly(calculated.orEmpty())
                readOnly = true
                notice = NOTICE_CALCULATED
            }

            null -> {
                // 모르는 타입을 TEXT로 접지 않는다. 값은 보여 주고 고치는 길은 열지 않는다 —
                // 어떤 모양으로 저장해야 하는지 이 앱이 모르기 때문이다(개발 의도 2번).
                input = Input.Readonly(stored)
                readOnly = true
                notice = NOTICE_UNKNOWN_TYPE
            }
        }

        return FieldView(
            id = field.id,
            key = field.key,
            name = field.name,
            group = field.groupName,
            type = type?.name ?: TYPE_UNKNOWN,
            required = field.isRequired,
            description = FieldDescription.fromConfig(field.config),
            input = input,
            readOnly = readOnly,
            notice = notice
        )
    }

    /** 읽기 전용 사유 코드 — 화면 문구는 호출부가 입힌다. */
    const val NOTICE_CALCULATED = "calculated"
    const val NOTICE_UNKNOWN_TYPE = "unknownType"

    /**
     * 웹이 보낸 값들을 저장할 [CharacterFieldValue]로 옮긴다.
     *
     * **앱 폼의 `collectFieldValues`와 같은 계약을 지킨다:**
     * - CALCULATED는 담지 않는다(저장 행이 없다).
     * - 알 수 없는 타입도 담지 않는다 — 읽기 전용으로 내보냈으니 돌려받은 것도 믿지 않는다.
     * - 빈 값은 담지 않는다. 담지 않는 것이 *"사용자가 비웠다"*의 표현이고, 커버 집합 안의
     *   그 필드는 병합에서 지워진다([com.novelcharacter.app.data.repository.CharacterFieldValueMerge]).
     * - 구조화 입력은 파트가 **하나라도 비지 않았을 때만** 합쳐 담는다.
     *
     * @param submitted 필드 정의 id → 날 값. 목록에 없는 필드는 *"보내지 않았다"*이고,
     *   그것은 빈 값과 같게 다룬다 — 웹이 그 칸을 그리지 않았을 수는 없기 때문이다
     *   (기술자와 커버 집합이 한 함수에서 나오므로 그릴 수 없는 필드는 커버에도 없다).
     */
    fun collect(
        fields: List<FieldDefinition>,
        submitted: Map<Long, Submitted>,
        characterId: Long
    ): List<CharacterFieldValue> {
        val out = ArrayList<CharacterFieldValue>(fields.size)
        for (field in fields) {
            val type = field.fieldType
            if (type == FieldType.CALCULATED || type == null) continue

            val value = when (val s = submitted[field.id]) {
                null -> ""
                is Submitted.Text -> s.raw.trim()
                is Submitted.Parts -> {
                    val parts = s.raw.map { it.trim() }
                    if (parts.any { it.isNotEmpty() }) {
                        val structured = StructuredInputConfig.effectiveFor(field)
                        // 구조화가 꺼진 필드에 파트가 왔으면 앱 폼의 비구조화 경로와 같게 붙인다.
                        if (structured.enabled) structured.joinValues(parts)
                        else parts.joinToString("-")
                    } else ""
                }
            }
            if (value.isEmpty()) continue
            out.add(
                CharacterFieldValue(
                    characterId = characterId,
                    fieldDefinitionId = field.id,
                    value = value
                )
            )
        }
        return out
    }
}
