package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldConfigTransfer
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType

/**
 * 프리셋·다른 세계관의 필드를 **이미 있는 세계관에 합치는** 계획 (B-89).
 *
 * 종전에는 프리셋이 **세계관을 만드는 순간에만** 쓰였고(`UniverseViewModel.applyPreset`),
 * 이미 만든 세계관에 넣는 길은 필드 관리의 '필드 가져오기'뿐이었다. 그 경로는 이미
 * 프리셋을 소스로 갖고 있었으나 **처분이 하나뿐이었다** — 같은 key가 있으면 조용히 걸렀다.
 * 사용자가 그 항목을 체크해도 결과는 같았고, 화면은 "N개 가져왔습니다"라고 말했다.
 * 그것이 이 파일이 있는 이유다: **처분을 셋으로 갈라 사용자가 고르게 한다.**
 *
 * ## 처분 셋 (사용자 확정 2026.08.02)
 * - [Disposition.ADD] — 대상에 없는 필드. 기본값이 '들어온다'이다.
 * - [Disposition.SKIP] — 이미 있는 필드의 **기본값**. 값 유실이 0인 쪽이 기본이어야 한다.
 * - [Disposition.OVERWRITE] — 이미 있는 필드를 소스의 정의로 덮는다. 고른 것만 올라온다.
 *
 * "건너뛰기냐 덮어쓰기냐"의 택일이 아니라 **덮어쓰기가 건너뛰기를 품는 상위 개념**이다 —
 * 기본이 안전하고, 필요할 때만 항목 단위로 유연해진다.
 *
 * ## 이 계층이 순수한 이유
 * 판정(무엇이 새 것인가·무엇이 실제로 달라지는가)과 산정(`displayOrder`를 어디에 잇는가)은
 * DB도 화면도 필요 없다. 떼어 두면 **미리보기가 보여 준 것과 실제로 심기는 것이 갈리지 않는다** —
 * 화면은 [buildPlan]이 만든 것을 그리고, 쓰기는 [resolve]가 만든 것을 그대로 넣는다.
 */
object PresetMerge {

    /** 한 항목의 처분. */
    enum class Disposition {
        /** 대상에 없다 — 새로 심는다. */
        ADD,

        /** 이미 있다 — 손대지 않는다(기본). */
        SKIP,

        /** 이미 있다 — 소스의 정의로 덮는다. */
        OVERWRITE
    }

    /**
     * 덮어쓰기가 실제로 바꾸는 갈래. 미리보기가 "무엇이 달라지는가"를 말하기 위한 것이며,
     * **비어 있으면 덮어쓸 이유가 없다**(같은 정의다).
     */
    enum class Change { NAME, TYPE, CONFIG, GROUP, REQUIRED }

    /**
     * 미리보기 한 줄. 소스 필드 하나와, 대상 세계관에 이미 있는 같은 자리의 필드다.
     *
     * 같은 자리인가는 **`(entityType, key)`**로 판정한다 — key 유일성 제약이 그것이기 때문이다
     * (`FieldDefinition`의 유니크 인덱스는 `(universeId, entityType, key)`). 종류를 빼고 key만
     * 보면 캐릭터의 `place`와 사건의 `place`가 서로를 중복으로 걸어 **멀쩡한 필드를 못 넣는다**(R-29).
     */
    data class Item(
        val source: FieldDefinition,
        /** 대상 세계관의 같은 자리 필드. null이면 새로 들어올 것이다. */
        val existing: FieldDefinition?,
        /** 덮어쓰기가 바꾸는 것. [existing]이 null이면 비어 있다. */
        val changes: Set<Change>
    ) {
        val entityType: String get() = source.entityType
        val key: String get() = source.key

        /** 이미 있는 자리인가 — 그렇다면 기본 처분은 [Disposition.SKIP]이다. */
        val isDuplicate: Boolean get() = existing != null

        /**
         * 이미 있는데 **바뀌는 것이 하나도 없는가.** 이 항목은 덮어써도 결과가 같으므로
         * 화면이 선택지를 주지 않는다 — 줄 수 있는 선택지를 다 주는 것과, 아무것도 하지 않는
         * 조작을 선택지로 파는 것은 다르다.
         */
        val isIdentical: Boolean get() = existing != null && changes.isEmpty()

        /** 화면·결과 고지가 같은 문자열로 항목을 가리키도록 하는 안정 키. */
        val itemKey: String get() = itemKey(entityType, key)
    }

    /**
     * [Item.itemKey]의 정의 — 화면이 고른 것과 [resolve]가 받는 것이 같은 규칙이어야 한다.
     *
     * 구분자가 NUL인 것은 **`entityType`에도 `key`에도 들어갈 수 없는 문자**라야
     * `("a", "b_c")`와 `("a_b", "c")`가 같은 키로 접히지 않기 때문이다.
     *
     * **날바이트가 아니라 `\u0000` 이스케이프로 적는다 — 되돌리지 말 것**(B-146).
     * 값은 글자 그대로 같지만, 날바이트를 넣으면 git이 이 파일을 binary로 판정해
     * **diff가 사라지고**(PR #225 diffstat에 `Bin 16610 -> 17066 bytes`로 찍혔다),
     * grep은 `binary file matches`만 뱉어 **grep으로 소스를 훑는 도구가 이 파일을 통째로
     * 건너뛴다** — `tools/triage_unresolved.sh`가 세운 선언 목록에 `PresetMerge`가 실제로
     * 빠져 있었다. 재발은 `tools/check_no_nul_bytes.sh`가 막는다(R-37).
     */
    fun itemKey(entityType: String, key: String): String = "$entityType\u0000$key"

    /**
     * 한 소스(프리셋 하나·다른 세계관 하나)를 대상 세계관에 합칠 때의 전체 그림.
     *
     * 항목은 **종류 순서를 보존한 채** 담는다 — 화면이 종류별로 묶어 보여 주므로(③),
     * 여기서 뒤섞으면 화면이 다시 정렬해야 하고 그 정렬이 여기와 갈릴 수 있다.
     */
    data class Plan(val items: List<Item>) {
        val isEmpty: Boolean get() = items.isEmpty()

        /** 새로 들어올 것 — 미리보기의 'N개'. */
        val additions: List<Item> get() = items.filter { !it.isDuplicate }

        /** 이미 있는 것 — 미리보기의 'M개'. 같은 정의(=덮어쓸 것이 없는)까지 포함한다. */
        val duplicates: List<Item> get() = items.filter { it.isDuplicate }

        /** 이미 있으면서 **덮어쓰면 실제로 달라지는** 것 — 선택지를 줄 수 있는 자리다. */
        val overwritable: List<Item> get() = items.filter { it.isDuplicate && !it.isIdentical }

        /** 종류별 항목 — 화면이 묶는 단위. 목록 순서를 보존한다. */
        fun byEntityType(): Map<String, List<Item>> =
            items.groupByTo(linkedMapOf()) { it.entityType }

        /** 기본 처분 — 새 것은 들어오고, 이미 있는 것은 손대지 않는다. */
        fun defaultSelection(): Set<String> = additions.map { it.itemKey }.toSet()
    }

    // ──────────────────────────────────────────────────────────────────────
    // 종류 바꿔 심기 (B-63 · 확정 14번)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * **종류를 바꿔 심을 때 기본으로 허용하는 필드 타입** (P-6 — 2026.08.04 사용자 확정,
     * "안전 우선"). 나머지는 사용자가 연다 — 고정된 틀을 두지 않는다(원칙 01).
     *
     * 글자·선택·숫자만인 것은 **뜻이 종류에 매이지 않는 타입**이기 때문이다. `BODY_SIZE`는
     * 체형 분석이, `GRADE`는 등급 체계가, `CALCULATED`는 같은 종류의 다른 필드를 가리키는
     * 수식이 함께 있어야 뜻이 서고, 그 셋 다 사건·작품에는 없다.
     *
     * **현행에서 한 칸 넓히는 값이지 좁히는 값이 아니다** — 지금은 옵션 자체가 없고
     * 같은 종류만 들어오므로, 이 기본값으로 처음 열리는 길이 셋 생긴다.
     *
     * ## 왜 여기만 `Set<String>`으로 남는가 (B-55, 2026.08.13)
     *
     * 이 집합은 **사용자가 고쳐 저장한다** — `FieldImportSettingsStore`가 prefs에 타입 이름을
     * 글자로 담고, 그 저장본은 이 코드보다 오래 산다. `Set<FieldType>`으로 바꾸면 저장 경계에서
     * 어차피 글자로 되돌려야 하고, 모르는 글자가 담긴 옛 설정은 **읽는 순간 조용히 사라진다**.
     *
     * **새 타입이 여기 안 드는 것은 누락이 아니라 결정이다** — 사용자 확정이 "안전 우선"이라
     * 기본은 닫힌 채로 시작하고 사용자가 연다. 그래서 exhaustive `when`으로 답을 강요할 자리가
     * 아니고, 대신 **글자를 [FieldType]에서 뽑아** 상수 이름이 바뀌면 컴파일이 깨지게 둔다.
     */
    val DEFAULT_CONVERTIBLE_TYPES: Set<String> =
        setOf(FieldType.TEXT.name, FieldType.SELECT.name, FieldType.NUMBER.name)

    /**
     * 종류를 바꾼 결과. **미리보기가 보여 준 것과 심기는 것이 갈리지 않게** [fields]를
     * 그대로 [buildPlan]에 넘긴다 — 걸러 내는 자리와 심는 자리가 갈리면 화면에 없는 것이
     * 들어가거나 고른 것이 사라진다(R-29).
     */
    data class Conversion(
        /** 바뀐 종류로 다시 쓴 필드. 종류가 그대로인 것도 함께 담긴다. */
        val fields: List<FieldDefinition>,
        /** 허용 타입 밖이라 넘어가지 못한 필드 — **버리지 않고 화면이 사유와 함께 보인다.** */
        val blocked: List<FieldDefinition>,
        /** 넘어가되 설정 일부를 잃는 필드: [Item.itemKey] → 잃는 config 키. */
        val configLoss: Map<String, List<String>>
    ) {
        /** 바뀐 것이 하나도 없는가 — 화면이 '종류 그대로'와 같게 그릴 수 있다. */
        val isNoop: Boolean get() = blocked.isEmpty() && configLoss.isEmpty()
    }

    /**
     * 소스 필드를 [targetEntityType]으로 바꿔 심을 준비를 한다 (B-63).
     *
     * P5의 F2가 소스·중복·순서·삽입을 같은 `entityType`으로 맞춘 뒤로 **캐릭터 필드를 사건
     * 필드로 들이는 길이 없었다.** 이미 만들어 둔 '장소'·'분위기'를 사건에서 재사용하려면
     * 처음부터 다시 만드는 수밖에 없었고, 그것이 이 함수가 있는 이유다.
     *
     * ## 세 가지가 이 함수의 계약이다
     *
     * ① **허용 타입은 *종류가 실제로 바뀌는* 필드에만 건다.** 이미 대상 종류인 필드까지
     *    거르면 오늘 되던 가져오기가 안 되는 자리가 생긴다 — 이 항목은 길을 넓히는 것이지
     *    좁히는 것이 아니다.
     * ② **막힌 것은 버리지 않고 [Conversion.blocked]로 돌려준다.** 조용히 사라지면 사용자는
     *    자기가 고른 필드가 왜 목록에 없는지 알 길이 없다(개발 의도 2번).
     * ③ **잃는 설정은 심기 **전에** 센다**([FieldConfigTransfer.droppedKeys]). 걷는 쪽과
     *    세는 쪽이 같은 함수라 미리보기가 예고한 것과 실제가 갈리지 않는다.
     *
     * `key`는 그대로 둔다 — 종류가 달라지면 유니크 인덱스도 다른 자리라(`(universeId,
     * entityType, key)`) 캐릭터의 '장소'와 사건의 '장소'가 서로를 중복으로 걸지 않는다(R-29).
     *
     * @param allowedTypes 종류를 바꿔도 되는 **필드 타입** 집합. 사용자가 정한다(확정 14번).
     */
    fun convertEntityType(
        sourceFields: List<FieldDefinition>,
        targetEntityType: String,
        allowedTypes: Set<String> = DEFAULT_CONVERTIBLE_TYPES
    ): Conversion {
        val fields = ArrayList<FieldDefinition>(sourceFields.size)
        val blocked = ArrayList<FieldDefinition>()
        val configLoss = LinkedHashMap<String, List<String>>()
        // **`buildPlan`이 같은 자리를 접을 때 쓰는 규칙을 여기서도 그대로 쓴다 — 먼저 온 것이
        // 이긴다.** 종류를 한쪽으로 모으면 서로 다른 종류였던 둘이 **같은 자리로 접히는데**
        // (캐릭터 '장소'와 사건 '장소'), 두 규칙이 갈리면 *버려진 필드의 손실*이 *남은 필드의
        // 줄*에 붙어 잃는 것이 없는 항목에 "설정이 빠집니다"가 뜬다 — 미리보기의 거짓말이다.
        val seen = HashSet<String>()
        for (source in sourceFields) {
            if (source.entityType == targetEntityType) {
                // 바뀌는 것이 없다 — 오늘의 경로 그대로다. 허용 타입도 묻지 않는다(①).
                // **접힘에는 함께 센다** — 이쪽이 먼저 오면 뒤엣것의 손실은 기록되지 않는다.
                seen.add(itemKey(targetEntityType, source.key))
                fields.add(source)
                continue
            }
            if (source.type !in allowedTypes) {
                // 막힌 것은 심기지 않으므로 접힘 대상이 아니다 — `seen`에 넣지 않는다.
                blocked.add(source)
                continue
            }
            val converted = source.copy(
                entityType = targetEntityType,
                config = FieldConfigTransfer.demoteAcrossEntityType(
                    source.config, source.entityType, targetEntityType
                )
            )
            val key = itemKey(targetEntityType, source.key)
            if (seen.add(key)) {
                val lost = FieldConfigTransfer.droppedKeys(
                    source.config, source.entityType, targetEntityType
                )
                if (lost.isNotEmpty()) configLoss[key] = lost
            }
            fields.add(converted)
        }
        return Conversion(fields, blocked, configLoss)
    }

    /**
     * 계획을 세운다.
     *
     * @param sourceFields 소스가 내주는 필드. **종류가 섞여 있어도 된다** — 프리셋은
     *   `entityType`을 담고 있고, 걸러 받으면 열린 구조를 스스로 닫는 것이다(원칙 01).
     *   원치 않는 종류는 미리보기에서 빼면 된다(③ 사용자 확정).
     * @param existingFields 대상 세계관의 **전 종류** 필드. 한 종류만 넘기면 다른 종류의
     *   중복을 놓쳐 삽입이 유니크 제약에 걸린다(R-29 — 판정과 쓰기가 같은 모집단을 봐야 한다).
     */
    fun buildPlan(
        sourceFields: List<FieldDefinition>,
        existingFields: List<FieldDefinition>
    ): Plan {
        val existingByKey = existingFields.associateBy { itemKey(it.entityType, it.key) }
        // 소스 안에 같은 자리가 두 번 나오면 뒤엣것을 버린다 — 손편집 JSON·엑셀로 들어온
        // 프리셋에는 실재할 수 있고, 그대로 두면 미리보기는 둘을 보여 주는데 삽입은 하나만
        // 성공한다(유니크 제약). 판정 단계에서 하나로 접어야 화면과 결과가 갈리지 않는다.
        val seen = HashSet<String>()
        val items = ArrayList<Item>(sourceFields.size)
        for (source in sourceFields) {
            val key = itemKey(source.entityType, source.key)
            if (!seen.add(key)) continue
            val existing = existingByKey[key]
            items.add(Item(source, existing, if (existing == null) emptySet() else diff(existing, source)))
        }
        return Plan(items)
    }

    /**
     * 덮어쓰기가 바꾸는 갈래를 센다.
     *
     * `config`는 **강등한 뒤에** 비교한다 — 등급 체계 참조(U-1)는 세계관 안에서만 성립하므로
     * 어차피 심을 때 벗겨진다([mergedConfig]). 벗기기 전 문자열로 비교하면 참조만 다른 같은
     * 정의가 "설정이 다릅니다"로 떠서, 덮어써 봐야 아무것도 안 바뀌는 항목을 권하게 된다.
     */
    private fun diff(existing: FieldDefinition, source: FieldDefinition): Set<Change> {
        val changes = linkedSetOf<Change>()
        if (existing.name != source.name) changes.add(Change.NAME)
        if (existing.type != source.type) changes.add(Change.TYPE)
        if (!configEquals(existing.config, mergedConfig(existing, source))) changes.add(Change.CONFIG)
        if (existing.groupName != source.groupName) changes.add(Change.GROUP)
        if (existing.isRequired != source.isRequired) changes.add(Change.REQUIRED)
        return changes
    }

    /**
     * 두 `config`가 **뜻이 같은가** — 문자열이 같은가가 아니다.
     *
     * 문자열 비교로는 안 된다. `JSONObject`는 키 순서를 보존하지 않으므로 재직렬화 한 번에
     * 같은 설정이 다른 문자열이 되고, [mergedConfig]는 등급 체계 참조를 벗기느라 실제로
     * 재직렬화한다. 그대로 두면 **참조만 달랐던 같은 정의가 "설정이 바뀝니다"로 뜬다** —
     * 덮어써 봐야 아무것도 안 바뀌는 항목을 권하게 되고, 미리보기가 거짓을 말한다.
     *
     * `GradeSystemRef.demote`가 "지울 키가 없으면 원문 그대로" 돌려주는 것도 같은 이유이며,
     * 그쪽은 재직렬화를 **피하는** 방식이고 이쪽은 재직렬화를 **견디는** 방식이다.
     *
     * **공개인 이유**(2026.08.07, B-119): 전역 기본 필드의 전파 미리보기가 *"이 세계관의 필드가
     * 템플릿과 같은가"*를 물을 때 **같은 판정**을 써야 한다(설계 1-3이 이 함수를 이름으로
     * 지목한다). 같은 물음에 둘째 구현이 서면 한쪽만 canonical을 쓰게 되고, 그때 미리보기는
     * 손대지 않은 필드를 "다름"으로 세어 **덮어쓰기를 권한다.**
     */
    fun configEquals(a: String, b: String): Boolean =
        a == b || canonical(a) == canonical(b)

    /** 키를 정렬해 만든 표준형. 파싱할 수 없으면 원문을 그대로 쓴다(비교가 문자열로 내려간다). */
    private fun canonical(json: String): String = try {
        canonicalValue(org.json.JSONTokener(json).nextValue())
    } catch (_: Exception) {
        json
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        is org.json.JSONObject -> value.keys().asSequence().sorted().joinToString(
            separator = ",", prefix = "{", postfix = "}"
        ) { key -> "${org.json.JSONObject.quote(key)}:${canonicalValue(value.get(key))}" }
        // 배열은 **정렬하지 않는다** — 선택지 목록(`options`)처럼 순서가 곧 뜻인 값이 있다.
        is org.json.JSONArray -> (0 until value.length()).joinToString(
            separator = ",", prefix = "[", postfix = "]"
        ) { canonicalValue(value.get(it)) }
        is String -> org.json.JSONObject.quote(value)
        // 숫자는 표기가 갈린다(1 vs 1.0) — 값으로 견준다. 정수로 떨어지면 정수 표기로 모은다.
        is Number -> value.toDouble().let {
            if (it == Math.floor(it) && !it.isInfinite()) it.toLong().toString() else it.toString()
        }
        null, org.json.JSONObject.NULL -> "null"
        else -> value.toString()
    }

    /**
     * 덮어쓸 때 실제로 들어가는 `config`.
     *
     * 세계관 안에서만 뜻이 있는 참조는 **늘 벗긴다**([FieldConfigTransfer]). 소스가
     * 프리셋이면 참조가 애초에 없고(프리셋은 체계를 만들지 않는다), 소스가 다른 세계관이면
     * 그 참조는 **남의 세계관을 가리키는 유령**이 된다. 실효 표(`grades`)는 config에
     * 물질화되어 있어 벗겨도 등급 표·값·통계는 그대로다 —
     * `FieldViewModel.importFieldsNow`가 삽입 경로에서 지키는 것과 같은 규약이다.
     */
    fun mergedConfig(existing: FieldDefinition, source: FieldDefinition): String =
        if (source.universeId != existing.universeId) FieldConfigTransfer.demoteAcrossUniverse(source.config)
        else source.config

    /** [resolve]가 내놓는 쓰기 목록 — 이 셋이 한 트랜잭션에 들어간다. */
    data class Resolution(
        /** 새로 심을 행. `universeId`·`displayOrder`가 이미 대상 기준으로 채워져 있다. */
        val inserts: List<FieldDefinition>,
        /** 덮어쓸 행 — 살아 있는 행의 id를 유지한 채 정의만 바꾼 것이다. */
        val updates: List<FieldDefinition>,
        /**
         * 덮이기 **직전**의 행. 되돌리기 스냅샷의 내용이며 [updates]와 같은 순서다.
         *
         * **덮어쓰기를 고른 항목에만 남는다**(④ 사용자 확정) — 건너뛴 것은 바뀐 것이 없어
         * 되돌릴 대상이 아니고, 담으면 휴지통이 아무 일도 하지 않는 백업으로 찬다.
         */
        val backups: List<FieldDefinition>
    ) {
        val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty()

        /** 종류별 삽입 수 — 결과 고지가 "어느 목록에 몇 개가 늘었는가"를 말하기 위한 것. */
        fun insertsByEntityType(): Map<String, Int> =
            inserts.groupingBy { it.entityType }.eachCount()

        /** 종류별 덮어쓰기 수. */
        fun updatesByEntityType(): Map<String, Int> =
            updates.groupingBy { it.entityType }.eachCount()
    }

    /**
     * 고른 처분을 실제 쓰기로 바꾼다.
     *
     * @param selected 사용자가 켠 항목의 [Item.itemKey]. 새 항목이면 '심는다', 이미 있는
     *   항목이면 '덮어쓴다'는 뜻이다 — 한 집합으로 두는 이유는 화면의 체크박스가 하나이기
     *   때문이고, 뜻이 갈리는 것은 [Item.isDuplicate]가 이미 안다.
     * @param targetUniverseId 심을 세계관.
     * @param maxOrderByEntityType 대상 세계관의 종류별 현행 `displayOrder` 최댓값.
     *   없는 종류는 -1로 본다(첫 필드가 0에서 시작한다).
     *
     * `displayOrder`는 **최댓값 뒤에 이어 붙인다**(② 루틴 처분) — 기존 순서를 흔들지 않는다.
     * 종류별로 따로 세는 것은 같은 세계관 안에서 두 종류의 순서가 서로 독립이기 때문이다.
     * **덮어쓰기는 순서를 건드리지 않는다** — 사용자가 끌어다 정한 자리를 소스가 뒤집으면
     * 되돌리기 대상이 정의만이 아니게 되고, 그것은 사용자가 고른 '덮어쓰기'의 뜻이 아니다.
     */
    fun resolve(
        plan: Plan,
        selected: Set<String>,
        targetUniverseId: Long,
        maxOrderByEntityType: Map<String, Int>
    ): Resolution {
        val nextOrder = HashMap<String, Int>()
        val inserts = ArrayList<FieldDefinition>()
        val updates = ArrayList<FieldDefinition>()
        val backups = ArrayList<FieldDefinition>()

        for (item in plan.items) {
            if (item.itemKey !in selected) continue
            val existing = item.existing
            if (existing == null) {
                val order = nextOrder.getOrElse(item.entityType) {
                    (maxOrderByEntityType[item.entityType] ?: -1) + 1
                }
                nextOrder[item.entityType] = order + 1
                inserts.add(
                    item.source.copy(
                        id = 0,
                        universeId = targetUniverseId,
                        // 소스가 종류를 갖고 오므로 그대로 쓴다(③). 명시해 두는 것은
                        // 이 자리가 R-29의 '쓰기' 축이라는 사실을 코드에 남기기 위해서다.
                        entityType = item.entityType,
                        displayOrder = order,
                        config = if (item.source.universeId != targetUniverseId) {
                            FieldConfigTransfer.demoteAcrossUniverse(item.source.config)
                        } else {
                            item.source.config
                        }
                    )
                )
            } else {
                // 같은 정의는 덮어써도 결과가 같다 — 쓰기도 백업도 만들지 않는다.
                // (화면이 선택지를 주지 않지만, 고른 목록이 화면 밖에서 올 수도 있다.)
                if (item.isIdentical) continue
                backups.add(existing)
                updates.add(
                    existing.copy(
                        // 바뀌는 것은 정의뿐이다. id·universeId·entityType·key·displayOrder는
                        // 살아 있는 행의 것을 유지한다 — key를 바꾸면 그것은 덮어쓰기가 아니라
                        // 새 필드이고, 저장된 값은 옛 정의를 가리킨 채 고아가 된다.
                        name = item.source.name,
                        type = item.source.type,
                        config = mergedConfig(existing, item.source),
                        groupName = item.source.groupName,
                        isRequired = item.source.isRequired
                    )
                )
            }
        }
        return Resolution(inserts, updates, backups)
    }
}
