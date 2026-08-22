package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodySlot
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.StructuredInputConfig

/**
 * 체형 수치의 해석 단일 소스 (설계 `docs/body_visual_redesign_2026-07.md` 3장).
 *
 * 종전에는 읽기 화면 렌더러 안에 인라인으로 "구분자로 쪼개 앞 3개를 B/W/H로 본다"가
 * 박혀 있었다. 실루엣·편집기가 같은 파싱을 복제하면 B-55류 산재가 가중되므로
 * 부위 해석을 이 객체 하나로 모은다 — 분석(`computeBodyAnalysis`)·실루엣 스펙·편집기가
 * 전부 여기를 통한다.
 *
 * 해석 사다리는 [resolveSlots]가 든다: 명시 매핑 → 라벨 추론 → 위치 폴백 → 매핑 없음.
 * 마지막 단계까지 못 가면 [MappingMode.NONE]이며, 화면은 사유를 표시하고 실루엣 대신
 * 바로 폴백한다(무음 금지 — 개발 의도 2번 '변수 제어').
 */
data class BodyMeasurements(
    /** 부위별 수치(cm). 매핑되지 않았거나 수치로 못 읽은 부위는 없다. */
    val values: Map<BodySlot, Double>,
    /** 어느 사다리 단으로 부위를 정했는가. */
    val mode: MappingMode,
    /** 파트 인덱스 순 슬롯 배정 — 화면이 파트별로 무엇을 재는지 보여줄 때 쓴다. */
    val partSlots: List<BodySlot>,
    /** 파트 인덱스 순 원문 값(다듬지 않은 텍스트). */
    val partValues: List<String>,
    /** 부위가 정해지지 않은 파트의 표시 이름 — 미매핑 사유 표시용. */
    val unmappedParts: List<String>,
    /** 키(cm). 키 필드가 없거나 못 읽으면 null. */
    val heightCm: Double? = null,
    /** 체중(kg). 없거나 못 읽으면 null. */
    val weightKg: Double? = null
) {
    enum class MappingMode {
        /** 사용자가 설정에서 파트→부위를 직접 지정했다. */
        EXPLICIT,
        /** 파트 라벨에서 부위를 추론했다. */
        INFERRED,
        /** 라벨로는 못 알아봤고 숫자 파트 3개 이상이라 앞 3개를 B/W/H로 봤다(현행 동작 보존). */
        POSITIONAL,
        /** 부위를 하나도 정하지 못했다. */
        NONE
    }

    val bust: Double? get() = values[BodySlot.BUST]
    val waist: Double? get() = values[BodySlot.WAIST]
    val hip: Double? get() = values[BodySlot.HIP]
    val shoulder: Double? get() = values[BodySlot.SHOULDER]
    val underbust: Double? get() = values[BodySlot.UNDERBUST]

    /** 분석 엔진 `analyze()`의 계약(B/W/H 3값 필수)을 만족하는가. */
    val hasCoreThree: Boolean get() = bust != null && waist != null && hip != null

    /**
     * 컵 계산에 쓸 밑가슴(cm).
     *
     * 실측 밑가슴이 매핑돼 있으면 그 값을, 없으면 현행 근사(허리 + ribOffset)를 쓴다 —
     * 갈비뼈가 큰 캐릭터를 정확히 표현할 수 있게 하되, 안 쓰던 사람의 결과는 그대로다.
     */
    fun effectiveUnderbust(config: BodyAnalysisConfig): Double? =
        underbust ?: waist?.let { it + config.ribOffset }

    companion object {
        /** 라벨 추론표. 소문자·공백 제거 후 완전일치로 본다(부분일치는 '어깨너비'가 '너비'에 걸리는 식으로 샌다). */
        private val LABEL_SLOTS: Map<String, BodySlot> = buildMap {
            for (l in listOf("b", "가슴", "가슴둘레", "bust", "상의", "윗둘레")) put(l, BodySlot.BUST)
            for (l in listOf("w", "허리", "허리둘레", "waist")) put(l, BodySlot.WAIST)
            for (l in listOf("h", "힙", "엉덩이", "엉덩이둘레", "hip")) put(l, BodySlot.HIP)
            for (l in listOf("s", "어깨", "어깨너비", "shoulder")) put(l, BodySlot.SHOULDER)
            for (l in listOf("u", "밑가슴", "언더", "언더바스트", "underbust")) put(l, BodySlot.UNDERBUST)
        }

        /** 위치 폴백이 채우는 순서 — 현행 인라인 파서가 앞 3개를 B/W/H로 본 것을 그대로 잇는다. */
        private val POSITIONAL_ORDER = listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP)

        /**
         * 필드 정의와 저장된 값에서 부위별 수치를 해석한다.
         *
         * [heightText]·[weightText]는 SemanticRole로 찾은 키·체중 필드의 원문이며,
         * 여기서 함께 숫자로 읽는다(호출부마다 다르게 파싱하던 것을 한자리로).
         */
        fun resolve(
            field: FieldDefinition,
            rawValue: String,
            heightText: String? = null,
            weightText: String? = null,
            config: BodyAnalysisConfig = BodyAnalysisConfig.fromConfig(field.config)
        ): BodyMeasurements {
            val structured = StructuredInputConfig.fromConfig(field.config)
            val labels: List<String>
            val texts: List<String>
            if (structured.enabled && structured.parts.isNotEmpty()) {
                val split = structured.splitValue(rawValue)
                labels = split.map { it.first }
                texts = split.map { it.second }
            } else {
                // 구조화 설정이 없는 값 — 구분자로만 쪼갠다(라벨은 없다).
                labels = emptyList()
                texts = splitPlain(rawValue)
            }
            return resolveParts(labels, texts, config, heightText, weightText)
        }

        /**
         * 라벨·원문 목록에서 직접 해석한다. 편집기처럼 필드 정의 없이 파트만 든 자리에서 쓴다.
         *
         * [labels]가 비어 있으면 라벨 추론 단을 건너뛴다.
         */
        fun resolveParts(
            labels: List<String>,
            texts: List<String>,
            config: BodyAnalysisConfig = BodyAnalysisConfig.DEFAULT,
            heightText: String? = null,
            weightText: String? = null
        ): BodyMeasurements {
            val numbers = texts.map { parseNumber(it) }
            val (slots, mode) = resolveSlots(labels, numbers, config)

            val values = mutableMapOf<BodySlot, Double>()
            for ((i, slot) in slots.withIndex()) {
                if (slot == BodySlot.NONE) continue
                val n = numbers.getOrNull(i) ?: continue
                // 같은 부위가 두 번 배정되면 앞의 것을 남긴다(뒤엣것이 덮으면 순서 의존이 생긴다).
                if (!values.containsKey(slot)) values[slot] = n
            }

            val unmapped = slots.withIndex()
                .filter { (i, slot) -> slot == BodySlot.NONE && texts.getOrNull(i)?.isNotBlank() == true }
                .map { (i, _) -> labels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "${i + 1}번째 값" }

            return BodyMeasurements(
                values = values,
                // 아무 수치도 못 정했으면 "연결 없음"으로 보고한다 — 다만 **명시 연결은 예외다.**
                // 사용자가 전부 '사용 안 함'으로 정하면 값이 하나도 안 나오는 것이 정상이고,
                // 그것을 NONE으로 강등하면 소비처가 추론·위치 폴백으로 되돌아가 그 선택을 뒤집는다.
                mode = if (values.isEmpty() && mode != MappingMode.EXPLICIT) MappingMode.NONE else mode,
                partSlots = slots,
                partValues = texts,
                unmappedParts = unmapped,
                heightCm = parseNumber(heightText),
                weightKg = parseNumber(weightText)
            )
        }

        /**
         * 해석 사다리 (설계 3-2). 파트 인덱스 순 슬롯 배정과 그것을 정한 단을 돌려준다.
         *
         * 1. 명시 연결([BodyAnalysisConfig.partSlots])이 있으면 그대로.
         * 2. 없으면 파트 라벨로 추론.
         * 3. 추론이 하나도 안 되고 숫자 파트가 3개 이상이면 앞 3개 = B/W/H.
         * 4. 그 외 → 연결 없음.
         */
        fun resolveSlots(
            labels: List<String>,
            numbers: List<Double?>,
            config: BodyAnalysisConfig
        ): Pair<List<BodySlot>, MappingMode> {
            val count = maxOf(labels.size, numbers.size)

            // 1. 명시 연결 — 파트 수보다 짧거나 길어도 인덱스 기준으로 맞춘다(설정이 뒤에 늘어난 파트를 모를 수 있다).
            //
            // **비어 있지 않다는 것 자체가 명시의 표시다.** 종전에는 "NONE이 아닌 것이 하나라도
            // 있는가"로 봤는데, 그러면 사용자가 파트 연결 UI에서 **전부 '사용 안 함'으로 고른 것**이
            // 추론으로 되돌아간다 — 명시적으로 "이 칸들은 부위가 아니다"라고 말한 것을 말없이
            // 뒤집는 자리였다(개발 의도 2번 '변수 제어'). 저장 쪽은 추론과 같으면 아예 안 싣는
            // 규약(설계 3-1)이라, 실려 있으면 곧 사용자가 정한 것이다.
            if (config.partSlots.isNotEmpty()) {
                val slots = (0 until count).map { config.partSlots.getOrNull(it) ?: BodySlot.NONE }
                return slots to MappingMode.EXPLICIT
            }

            // 2. 라벨 추론
            if (labels.isNotEmpty()) {
                val inferred = labels.map { LABEL_SLOTS[normalizeLabel(it)] ?: BodySlot.NONE }
                if (inferred.any { it != BodySlot.NONE }) {
                    val slots = (0 until count).map { inferred.getOrNull(it) ?: BodySlot.NONE }
                    return slots to MappingMode.INFERRED
                }
            }

            // 3. 위치 폴백 — 숫자로 읽히는 파트가 3개 이상일 때만.
            if (numbers.count { it != null } >= POSITIONAL_ORDER.size) {
                val slots = MutableList(count) { BodySlot.NONE }
                var taken = 0
                for (i in 0 until count) {
                    if (numbers.getOrNull(i) == null) continue
                    if (taken >= POSITIONAL_ORDER.size) break
                    slots[i] = POSITIONAL_ORDER[taken]
                    taken++
                }
                return slots to MappingMode.POSITIONAL
            }

            // 4. 매핑 없음
            return List(count) { BodySlot.NONE } to MappingMode.NONE
        }

        /**
         * 설정 화면(파트 연결 UI)이 기본으로 보여 줄 배정 — 값 없이 **파트 정의만으로** 사다리를 돈다.
         *
         * 필드를 편집하는 자리에는 캐릭터 값이 없으므로 위치 폴백의 조건("숫자로 읽히는 파트")을
         * 실제 수치 대신 **파트의 입력 종류**로 판정한다([numericParts]). 사용자가 그 칸을 숫자로
         * 선언해 두었다면 값이 들어올 때 숫자로 읽힐 자리이므로, 화면이 보여 주는 기본값과
         * 실제 해석이 갈리지 않는다.
         *
         * 명시 연결은 여기서 보지 않는다 — 이 함수가 내는 것은 **"손대지 않았을 때의 결과"**이고,
         * 그것이 곧 [slotsToStore]가 "바꾸지 않았다"를 판정하는 기준선이다.
         */
        fun inferSlotsForParts(labels: List<String>, numericParts: List<Boolean>): List<BodySlot> {
            val count = maxOf(labels.size, numericParts.size)
            val numbers = (0 until count).map { if (numericParts.getOrNull(it) == true) 0.0 else null }
            return resolveSlots(labels, numbers, BodyAnalysisConfig.DEFAULT).first
        }

        /**
         * 저장할 명시 연결을 정한다 — **추론과 같으면 싣지 않는다**(설계 3-1).
         *
         * 기본값을 구워 두면 ⓐ 기존 필드 정의의 JSON이 이유 없이 불어나고 ⓑ 파트 이름을 나중에
         * 고쳐도 배정이 옛날에 굳은 채 따라오지 않는다. 사용자가 실제로 고른 것만 실어야
         * 추론이 살아 있는 상태로 남는다.
         */
        fun slotsToStore(selected: List<BodySlot>, inferred: List<BodySlot>): List<BodySlot> =
            if (selected == inferred) emptyList() else selected

        /** 구조화 설정이 없는 값의 분리 — 현행 인라인 파서와 같은 구분자(-, /, 공백)를 쓴다. */
        fun splitPlain(rawValue: String): List<String> =
            if (rawValue.isBlank()) emptyList()
            else rawValue.split(RegexCharClasses.DASH_SLASH_WHITESPACE).map { it.trim() }.filter { it.isNotEmpty() }

        private fun normalizeLabel(label: String): String =
            label.trim().lowercase().replace(" ", "")

        /**
         * 수치 하나를 읽는다. 단위 접미사("86cm")·자릿점("1,200")·소수점을 견디고,
         * 못 읽으면 null.
         *
         * ## **수가 둘 이상이면 null이다**
         *
         * 종전에는 숫자·점이 아닌 글자를 **전부 지우고 이어붙였다.** 그래서 `165~170`이
         * **165170**이 되고 `165cm (168 구두)`가 165168이 됐다 — *"못 읽었다"*가 아니라
         * **그럴듯한 수**가 나오는 것이 이 결함의 성질이다. 그 수가 어디까지 가는지 보면:
         * BMI가 0에 수렴해 체형이 *"마른 편"*이 되고, 골격은 `>= 175`라 **"대형"**이 되며,
         * 실루엣은 화면의 수백 배 위로 나가 **그림이 사라진다.** 화면 어디에도 경고가 없다
         * (기본 프리셋의 `키`·`체중`이 TEXT 필드라 타입 불일치 검사도 아무 말을 안 한다).
         *
         * **부호도 함께 지워졌다** — `-5`가 `5`가 되어 음수가 양수로 뒤집혔다.
         *
         * 이 앱에서 관대한 파서는 원래 한 자리뿐이고(대결 필드 연결 — 맨 앞 앵커라 이어붙지
         * 않는다) 나머지는 전부 엄격하다. 이 함수만 그 관행 밖이었다(개발 의도 2번 —
         * 조용히 버리거나 왜곡하지 않는다).
         *
         * [BodyAnalysisHelper.parseNumericFromText]와 같은 규칙이며 그쪽이 이 함수에 위임한다.
         */
        fun parseNumber(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            // 자릿점만 걷어낸다 — 나머지 글자는 **지우지 않고**, 수가 몇 개인지 센다.
            val cleaned = text.trim().replace(",", "")
            return NUMBER.findAll(cleaned).map { it.value }.toList()
                .singleOrNull()?.toDoubleOrNull()
        }

        /**
         * 수 하나의 모양 — **부호를 포함한다**(빼면 음수가 양수로 뒤집힌다).
         * 숫자 종류는 ASCII만 본다(R-69 — 기기의 정규식 엔진이 약칭을 다르게 읽는다).
         */
        private val NUMBER = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?")
    }
}
