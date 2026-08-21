package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodySlot
import com.novelcharacter.app.util.BodySilhouetteSpec.Measures
import org.json.JSONArray
import org.json.JSONObject

/**
 * 실루엣 편집기가 **회전을 넘겨 지키는 것**의 전수 목록 (B-201 · 확정 15장 5번 · R-41).
 *
 * ## 왜 순수 계층에 있는가
 *
 * 이 저장소의 자동 검증은 화면을 못 본다(`CLAUDE.md` 「세션 착수 규칙」 4번) — 시트 안에서
 * `Bundle`에 칸을 넣고 빼면 **왕복이 맞는지 증명할 수단이 없다.** 그래서 담는 것과 푸는 것을
 * 여기로 내리고 시트는 [encode] 한 줄과 [decode] 한 줄만 든다. `BodyEditorModel`이 슬라이더
 * 범위·되쓰기에 대해 하는 분업과 같은 것이다.
 *
 * ## 무엇을 담는가 — **사용자가 만든 것 + 열 때 뜬 스냅샷**
 *
 * 담지 않는 것은 셋뿐이다 — `analysisConfig`와 콜백 둘. 그 셋은 **필드 정의에서만 나오고 폼
 * 위젯의 *값*과 무관해서**, 회전 뒤 호스트가 폼을 다시 세우는 그 자리에서 곧바로 다시 꽂을 수
 * 있다(`DynamicFieldFormBuilder.rebindSilhouetteEditor`).
 *
 * **나머지(열 때 뜬 스냅샷 — [initial]·[partValues]·[writableSlots]과 고지 깃발들)는 담는다.**
 * 값이 위젯에서 나오기 때문이다: 폼은 `buildForm()`으로 서고 값은 그 *뒤에* 채워지는데(복원
 * 번들 또는 DB 조회, 조회는 정지 함수라 언제 끝나는지 이 시트가 알 수 없다), 그 사이에 위젯을
 * 읽으면 **빈 값이 되쓰기의 바탕이 된다** — 되쓰기는 원문 목록 위에 얹는 일이라 바탕이 비면
 * 사용자가 쓰던 "허벅지"·"발 크기" 같은 칸이 통째로 지워진다. 게다가 시트가 떠 있는 동안
 * 폼은 손댈 수 없으므로 **스냅샷이 곧 정답이다** — 다시 읽는 것은 같은 값에 이르는 더 위험한
 * 길일 뿐이다.
 *
 * 담는 것은 **다시 만들 수 없는 것**이다:
 * - [current]·[weightKg] — 끌어 만든 수치. 이 판이 존재하는 이유다.
 * - [touched] — *이 자리에서 만든 값*의 집합. **없으면 조용히 유실된다:** 되쓰기는 원래 있던
 *   값과 여기 담긴 값만 적으므로([BodySilhouetteEditorSheet.applyAndDismiss]), 회전 뒤 이 집합이
 *   비면 사용자가 방금 채운 빈 칸이 [적용]에서 말없이 빠진다. 수치는 화면에 보이는데 저장이
 *   안 되는 모양이라 **알아채지도 못한다**(개발 의도 2번).
 * - [previous] — 🎲 [직전으로] 한 단계. 굴리기 비교는 편집 행위이므로 함께 넘긴다.
 * - 축·컵·기준 캐릭터 선택 — 라디오는 `View.generateViewId()`로 세우므로 **id가 매번 달라져
 *   안드로이드 자동 복원이 원리적으로 닿지 못한다.** 담지 않으면 고르던 자리가 기본값으로 돌아간다.
 * - [generatorOpen]·[sideFacing]·[overlayOn] — 보던 자리. 잃어도 데이터는 아니지만 되찾는 데
 *   조작이 든다(원칙 04).
 *
 * ## 칸을 더할 때
 *
 * **R-41이 요구하는 '한 벌'이 이 클래스다.** 시트에 새 편집 상태를 더하면 여기 프로퍼티와
 * [encode]·[decode] 양쪽에 함께 등재한다 — 한쪽만 넣으면 왕복이 조용히 절반만 돈다.
 * [BodyEditorStateTest]의 '전 칸 왕복' 시험이 그 누락을 잡는다.
 */
/**
 * 🎲 [직전으로] 한 단계가 되돌릴 **편집 상태 한 벌**.
 *
 * 종전에는 `Pair<Measures, Double?>`라 *보이는 수치*만 담았다. 그런데 굴리기는 수치만
 * 만드는 것이 아니라 **되쓰기 범위([BodyEditorState.touched])도 넓힌다** — 되돌려도 그
 * 범위가 남아 있으면, 화면에서는 되돌리기가 끝난 것처럼 보이는데 [적용]이 비어 있던
 * 가슴·허리·엉덩이 칸을 굴리기의 값으로 채운다(사용자가 만든 적 없는 값이 저장된다).
 * 되돌릴 것을 *수치*가 아니라 **그 순간의 편집 상태**로 못박는다.
 */
data class RollSnapshot(
    val measures: BodySilhouetteSpec.Measures,
    val weightKg: Double?,
    val touched: Set<BodySlot>,
    val touchedHeight: Boolean,
    val touchedWeight: Boolean
)

data class BodyEditorState(
    /** 어느 BODY_SIZE 필드의 편집기인가 — 호스트가 다시 꽂을 자리를 이것으로 찾는다. */
    val fieldId: Long,
    /** 열 때 폼에서 읽은 수치 — [current]와 견주어 *바뀌었는가*를 정하고, 되쓰기 대상도 가른다. */
    val initial: BodyMeasurements,
    /** 열 때 폼에서 읽은 파트 원문 — 되쓰기가 이 목록 위에 얹는다. */
    val partValues: List<String>,
    /** 되쓸 자리(파트 인덱스 순). */
    val writableSlots: List<BodySlot>,
    val hasHeightField: Boolean,
    val hasWeightField: Boolean,
    val positionalFallback: Boolean,
    val noWritableSlot: Boolean,
    val current: Measures,
    val weightKg: Double?,
    /** 🎲 [직전으로] 한 단계 — 되돌릴 **편집 상태 한 벌**이다. 굴린 적이 없으면 null. */
    val previous: RollSnapshot?,
    val touched: Set<BodySlot>,
    /**
     * 키·체중도 *이 자리에서 만든 값*인가 — [touched]가 부위에 대해 하는 일을 이 둘이 한다.
     *
     * **없으면 조용한 왜곡이 된다:** 되쓰기는 키·체중을 `touched`와 무관하게 언제나 적었고,
     * 열 때 초안이 채운 기준 키(165)가 *사용자가 적은 값*으로 행세해 빈 키 칸에 저장됐다.
     * 여는 것만으로 [적용]이 켜지는 것도 같은 뿌리다(초안 키 ≠ 빈 폼 원문).
     */
    val touchedHeight: Boolean,
    val touchedWeight: Boolean,
    val cupMode: Boolean,
    val overlayOn: Boolean,
    val generatorOpen: Boolean,
    val sideFacing: Boolean,
    val axisHeight: Int,
    val axisTorso: Int,
    val axisBust: Int,
    val axisHip: Int,
    val cupSizeIndex: Int,
    val relativeHeightIndex: Int,
    val relativeVolumeIndex: Int,
    /**
     * 상대 생성의 기준 캐릭터 — **자리가 아니라 id로 담는다.**
     * 작품 캐릭터는 시트가 뜬 뒤 비동기로 도착하므로(`setPeers`), 복원 시점에 스피너 자리는
     * 아직 뜻이 없다. id로 담으면 목록이 늦게 와도 그 사람을 다시 고를 수 있다.
     */
    val baseCharacterId: Long?
) {

    fun encode(): String = JSONObject().apply {
        put(K_FIELD, fieldId)
        put(K_INITIAL, initialToJson(initial))
        put(K_PART_VALUES, JSONArray().also { arr -> for (v in partValues) arr.put(v) })
        put(K_WRITABLE, JSONArray().also { arr -> for (s in writableSlots) arr.put(s.name) })
        put(K_HAS_HEIGHT, hasHeightField)
        put(K_HAS_WEIGHT, hasWeightField)
        put(K_POSITIONAL, positionalFallback)
        put(K_NO_SLOT, noWritableSlot)
        put(K_CURRENT, measuresToJson(current))
        weightKg?.let { put(K_WEIGHT, it) }
        previous?.let { p ->
            put(K_PREV, measuresToJson(p.measures))
            p.weightKg?.let { put(K_PREV_WEIGHT, it) }
            put(K_PREV_TOUCHED, JSONArray().also { arr -> for (s in p.touched) arr.put(s.name) })
            put(K_PREV_T_HEIGHT, p.touchedHeight)
            put(K_PREV_T_WEIGHT, p.touchedWeight)
        }
        put(K_TOUCHED, JSONArray().also { arr -> for (s in touched) arr.put(s.name) })
        put(K_TOUCHED_HEIGHT, touchedHeight)
        put(K_TOUCHED_WEIGHT, touchedWeight)
        put(K_CUP_MODE, cupMode)
        put(K_OVERLAY, overlayOn)
        put(K_GEN_OPEN, generatorOpen)
        put(K_SIDE, sideFacing)
        put(K_AX_HEIGHT, axisHeight)
        put(K_AX_TORSO, axisTorso)
        put(K_AX_BUST, axisBust)
        put(K_AX_HIP, axisHip)
        put(K_CUP_SIZE, cupSizeIndex)
        put(K_REL_HEIGHT, relativeHeightIndex)
        put(K_REL_VOLUME, relativeVolumeIndex)
        baseCharacterId?.let { put(K_BASE_CHAR, it) }
    }.toString()

    companion object {

        /**
         * 담긴 것을 되푼다 — **못 읽으면 null이고 예외를 내지 않는다.**
         *
         * 복원은 옛 판이 담은 것을 새 판이 읽는 자리라(앱 판올림 뒤 첫 회전) 모양이 어긋날 수
         * 있다. 그때 죽으면 편집기가 아니라 **화면이 통째로 죽는다** — 잃은 것을 되찾자고
         * 만든 길이 새 크래시가 되는 것이 가장 나쁜 처분이다. 못 읽으면 편집기가 처음 열린 것
         * 처럼 서고, 그 처분은 종전(회전 = 전부 유실)과 같으므로 더 나빠지지 않는다.
         */
        fun decode(raw: String?): BodyEditorState? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                val current = measuresFromJson(o.optJSONObject(K_CURRENT)) ?: return null
                val prevMeasures = measuresFromJson(o.optJSONObject(K_PREV))
                BodyEditorState(
                    fieldId = o.optLong(K_FIELD, 0L),
                    initial = initialFromJson(o.optJSONObject(K_INITIAL)),
                    partValues = stringsFromJson(o.optJSONArray(K_PART_VALUES)),
                    writableSlots = slotListFromJson(o.optJSONArray(K_WRITABLE)),
                    hasHeightField = o.optBoolean(K_HAS_HEIGHT, false),
                    hasWeightField = o.optBoolean(K_HAS_WEIGHT, false),
                    positionalFallback = o.optBoolean(K_POSITIONAL, false),
                    noWritableSlot = o.optBoolean(K_NO_SLOT, false),
                    current = current,
                    weightKg = o.optDoubleOrNull(K_WEIGHT),
                    // 옛 판이 담은 JSON에는 되돌림의 표식 셋이 없다 — 그때는 빈 집합·false로
                    // 읽는다(종전 동작 그대로이고, 없는 사실을 지어내지 않는다).
                    previous = prevMeasures?.let {
                        RollSnapshot(
                            measures = it,
                            weightKg = o.optDoubleOrNull(K_PREV_WEIGHT),
                            touched = slotsFromJson(o.optJSONArray(K_PREV_TOUCHED)),
                            touchedHeight = o.optBoolean(K_PREV_T_HEIGHT, false),
                            touchedWeight = o.optBoolean(K_PREV_T_WEIGHT, false)
                        )
                    },
                    touched = slotsFromJson(o.optJSONArray(K_TOUCHED)),
                    touchedHeight = o.optBoolean(K_TOUCHED_HEIGHT, false),
                    touchedWeight = o.optBoolean(K_TOUCHED_WEIGHT, false),
                    cupMode = o.optBoolean(K_CUP_MODE, false),
                    overlayOn = o.optBoolean(K_OVERLAY, false),
                    generatorOpen = o.optBoolean(K_GEN_OPEN, false),
                    sideFacing = o.optBoolean(K_SIDE, false),
                    axisHeight = o.optInt(K_AX_HEIGHT, DEFAULT_AXIS),
                    axisTorso = o.optInt(K_AX_TORSO, DEFAULT_AXIS),
                    axisBust = o.optInt(K_AX_BUST, DEFAULT_AXIS),
                    axisHip = o.optInt(K_AX_HIP, DEFAULT_AXIS),
                    cupSizeIndex = o.optInt(K_CUP_SIZE, NO_SELECTION),
                    relativeHeightIndex = o.optInt(K_REL_HEIGHT, DEFAULT_RELATIVE),
                    relativeVolumeIndex = o.optInt(K_REL_VOLUME, DEFAULT_RELATIVE),
                    baseCharacterId = o.optLongOrNull(K_BASE_CHAR)
                )
            } catch (e: Exception) {
                null
            }
        }

        /** 축 라디오의 기본 선택 — 시트가 `checked = 1`로 세우는 그 자리다. */
        const val DEFAULT_AXIS = 1

        /** 상대 생성 배수의 기본 선택 — 시트가 `i == 2`로 세우는 '그대로' 자리다. */
        const val DEFAULT_RELATIVE = 2

        /** 라디오 '지정 안 함'(tag = -1). */
        const val NO_SELECTION = -1

        private const val K_FIELD = "field"
        private const val K_INITIAL = "init"
        private const val K_PART_VALUES = "parts"
        private const val K_WRITABLE = "writable"
        private const val K_HAS_HEIGHT = "hasH"
        private const val K_HAS_WEIGHT = "hasW"
        private const val K_POSITIONAL = "posFallback"
        private const val K_NO_SLOT = "noSlot"
        private const val K_CURRENT = "cur"
        private const val K_WEIGHT = "w"
        private const val K_PREV = "prev"
        private const val K_PREV_WEIGHT = "prevW"
        private const val K_PREV_TOUCHED = "prevTouched"
        private const val K_PREV_T_HEIGHT = "prevTH"
        private const val K_PREV_T_WEIGHT = "prevTW"
        private const val K_TOUCHED = "touched"
        private const val K_TOUCHED_HEIGHT = "touchedH"
        private const val K_TOUCHED_WEIGHT = "touchedW"
        private const val K_CUP_MODE = "cupMode"
        private const val K_OVERLAY = "overlay"
        private const val K_GEN_OPEN = "genOpen"
        private const val K_SIDE = "side"
        private const val K_AX_HEIGHT = "axH"
        private const val K_AX_TORSO = "axT"
        private const val K_AX_BUST = "axB"
        private const val K_AX_HIP = "axP"
        private const val K_CUP_SIZE = "cupSize"
        private const val K_REL_HEIGHT = "relH"
        private const val K_REL_VOLUME = "relV"
        private const val K_BASE_CHAR = "baseChar"

        private const val M_HEIGHT = "h"
        private const val M_SHOULDER = "s"
        private const val M_BUST = "b"
        private const val M_WAIST = "w"
        private const val M_HIP = "p"
        private const val M_UNDERBUST = "u"
        private const val M_ESTIMATED = "est"
        private const val M_HEIGHT_KNOWN = "hk"
        private const val M_RIB = "rib"

        private const val I_VALUES = "v"
        private const val I_MODE = "mode"
        private const val I_PART_SLOTS = "ps"
        private const val I_PART_VALUES = "pv"
        private const val I_UNMAPPED = "un"
        private const val I_HEIGHT = "h"
        private const val I_WEIGHT = "w"

        private fun initialToJson(m: BodyMeasurements): JSONObject = JSONObject().apply {
            put(I_VALUES, JSONObject().also { o -> for ((slot, v) in m.values) o.put(slot.name, v) })
            put(I_MODE, m.mode.name)
            put(I_PART_SLOTS, JSONArray().also { arr -> for (s in m.partSlots) arr.put(s.name) })
            put(I_PART_VALUES, JSONArray().also { arr -> for (v in m.partValues) arr.put(v) })
            put(I_UNMAPPED, JSONArray().also { arr -> for (v in m.unmappedParts) arr.put(v) })
            m.heightCm?.let { put(I_HEIGHT, it) }
            m.weightKg?.let { put(I_WEIGHT, it) }
        }

        /**
         * 없거나 못 읽으면 **빈 수치**다 — null이 아니다.
         *
         * `initial`은 시트가 처음 열릴 때도 "값이 하나도 없는 폼"이면 비어 있는 것이 정상이라,
         * 빈 것과 못 읽은 것을 가를 필요가 없다. 전체 복원이 실패하는 갈래는 [decode]가
         * `current`에서 이미 가른다(그림이 설 수 없는 자리).
         */
        private fun initialFromJson(o: JSONObject?): BodyMeasurements {
            if (o == null) return EMPTY_MEASUREMENTS
            val values = LinkedHashMap<BodySlot, Double>()
            o.optJSONObject(I_VALUES)?.let { obj ->
                for (key in obj.keys()) {
                    val slot = BodySlot.entries.firstOrNull { it.name == key } ?: continue
                    values[slot] = obj.optDouble(key).takeIf { !it.isNaN() } ?: continue
                }
            }
            val mode = BodyMeasurements.MappingMode.entries
                .firstOrNull { it.name == o.stringOr(I_MODE) } ?: BodyMeasurements.MappingMode.NONE
            return BodyMeasurements(
                values = values,
                mode = mode,
                partSlots = slotListFromJson(o.optJSONArray(I_PART_SLOTS)),
                partValues = stringsFromJson(o.optJSONArray(I_PART_VALUES)),
                unmappedParts = stringsFromJson(o.optJSONArray(I_UNMAPPED)),
                heightCm = o.optDoubleOrNull(I_HEIGHT),
                weightKg = o.optDoubleOrNull(I_WEIGHT)
            )
        }

        private val EMPTY_MEASUREMENTS = BodyMeasurements(
            values = emptyMap(), mode = BodyMeasurements.MappingMode.NONE,
            partSlots = emptyList(), partValues = emptyList(), unmappedParts = emptyList()
        )

        private fun stringsFromJson(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { arr.stringOr(it) ?: "" }
        }

        /**
         * 자리가 뜻을 가지는 목록이라 **모르는 이름은 버리지 않고 [BodySlot.NONE]으로 남긴다.**
         * ([slotsFromJson]은 집합이라 버려도 자리가 안 밀리지만, 이쪽은 밀리면 되쓰기가
         * 옆 칸에 적는다.)
         */
        private fun slotListFromJson(arr: JSONArray?): List<BodySlot> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val name = arr.stringOr(i)
                BodySlot.entries.firstOrNull { it.name == name } ?: BodySlot.NONE
            }
        }

        /**
         * `Measures`의 JSON 코덱 — **화면 사이로 그림을 넘기는 모든 자리가 이것을 쓴다.**
         *
         * 회전·다크모드 전환은 프래그먼트를 무인자 생성자로 되살리므로, 인스턴스 프로퍼티에
         * 꽂아 둔 그림 재료는 통째로 기본값이 된다(실루엣 크게 보기가 그래서 빈 화면이 됐다).
         * 코덱을 두 벌로 만들면 축 하나가 늘 때 한쪽만 낡으므로 여기 하나만 둔다.
         */
        fun measuresToJson(m: Measures): JSONObject = JSONObject().apply {
            put(M_HEIGHT, m.height)
            put(M_SHOULDER, m.shoulder)
            put(M_BUST, m.bust)
            put(M_WAIST, m.waist)
            put(M_HIP, m.hip)
            m.underbust?.let { put(M_UNDERBUST, it) }
            put(M_ESTIMATED, JSONArray().also { arr -> for (s in m.estimated) arr.put(s.name) })
            put(M_HEIGHT_KNOWN, m.heightKnown)
            put(M_RIB, m.ribOffset)
        }

        /** [measuresToJson]의 짝 — 다섯 축이 없으면 `null`이다(부분 복원을 하지 않는다). */
        fun measuresFromJson(o: JSONObject?): Measures? {
            if (o == null) return null
            // 다섯 축은 필수다 — 하나라도 없으면 그림이 설 수 없으므로 부분 복원을 하지 않는다.
            for (key in listOf(M_HEIGHT, M_SHOULDER, M_BUST, M_WAIST, M_HIP)) {
                if (!o.has(key)) return null
            }
            return Measures(
                height = o.getDouble(M_HEIGHT),
                shoulder = o.getDouble(M_SHOULDER),
                bust = o.getDouble(M_BUST),
                waist = o.getDouble(M_WAIST),
                hip = o.getDouble(M_HIP),
                underbust = o.optDoubleOrNull(M_UNDERBUST),
                estimated = slotsFromJson(o.optJSONArray(M_ESTIMATED)),
                heightKnown = o.optBoolean(M_HEIGHT_KNOWN, true),
                ribOffset = o.optDouble(M_RIB, BodySilhouetteSpec.FIGURE_RIB_OFFSET_CM)
            )
        }

        /** 모르는 이름은 버린다 — 열거가 바뀌어도 나머지 칸은 살아남는다. */
        private fun slotsFromJson(arr: JSONArray?): Set<BodySlot> {
            if (arr == null) return emptySet()
            val out = LinkedHashSet<BodySlot>()
            for (i in 0 until arr.length()) {
                val name = arr.stringOr(i) ?: continue
                BodySlot.entries.firstOrNull { it.name == name }?.let { out.add(it) }
            }
            return out
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key) else null
    }
}
