package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DefaultFieldRef
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * **캐릭터가 무소속 구역과 세계관 사이를 오갈 때, 필드값을 이어 줄 것인가** — 순수 판정 (B-128).
 *
 * 무소속 캐릭터도 전역 구역의 필드를 가진다(B-119 확장). 그 캐릭터가 작품에 들어가면 값이
 * 가리키던 정의는 전역 구역에 남고, 세계관에는 **같은 템플릿에서 심긴 그림자 필드**가 따로
 * 선다. 이어 주지 않으면 값은 보관 값(N2)이 되어 화면에 남기는 하지만, 사용자는 그것을
 * **손으로 옮겨 적어야 한다** — 그 반복을 없애자는 것이 이 항목의 이유다(원칙 04).
 *
 * ## 규칙 (2026.08.10 사용자 확정 — `docs/judgment_confirmations_2026-08.md` 13-2)
 *
 * **타입이 호환되고**([FieldTypeCompatibility]) **대상 필드가 비어 있을 때만** 옮긴다.
 * 둘 중 하나라도 아니면 **보관 값으로 남기고 사유를 고지한다.** 반대 방향(세계관 → 무소속)도
 * 같은 규칙이라 세 물음이 하나로 접혔다.
 *
 * **덮어쓰기를 열지 않는 것이 이 판정의 본체다.** 대상에 이미 값이 있으면 어느 쪽이 옳은지
 * **앱은 모른다.** 모르는 것을 앱이 정하면 그것은 편의가 아니라 말없는 유실이다(개발 의도 2번).
 * 타입 불일치도 같다 — 안 맞는 값을 밀어 넣으면 이관이 곧 오염이다.
 *
 * **기각된 쪽:** *항상 묻기*(안전한 대부분에 확인창을 물린다 — 원칙 04) ·
 * *지금 그대로*(손으로 옮겨 적는 반복이 남는데 그 반복이 이 항목의 이유다).
 *
 * ## 왜 순수 계층인가
 *
 * 이 판정이 틀리면 증상은 *"값이 조용히 덮였다"* 또는 *"엉뚱한 타입 칸에 글자가 들어갔다"*인데,
 * 둘 다 저장이 끝난 뒤에야 드러나고 코드는 멀쩡해 보인다. 저장소에 두면 Android 의존이라
 * 어떤 로컬 검사도 닿지 못한다(B-132가 실증한 자리와 같은 부류다).
 */
object GlobalScopeFieldMove {

    /** 경계를 넘는 값 하나 — 그 값이 지금 가리키는 정의와 값 자체. */
    data class Candidate(val def: FieldDefinition, val value: String)

    /** 옮기지 못한 이유. 화면이 사용자에게 그대로 말할 수 있어야 하므로 뭉뚱그리지 않는다. */
    enum class KeptReason {
        /** 대상 구역에 짝이 되는 필드가 없다(템플릿이 그 세계관에 아직 안 심겼다 등). */
        NO_COUNTERPART,

        /** 짝은 있는데 그 타입으로는 값이 뜻을 유지하지 못한다 — 밀어 넣으면 오염이다. */
        TYPE_MISMATCH,

        /** 짝에 이미 값이 있다 — 어느 쪽이 옳은지 앱은 모르므로 덮지 않는다. */
        TARGET_OCCUPIED
    }

    /**
     * @param transfers 옮길 것: **옛 정의 id → 대상 정의 id**.
     * @param kept 보관 값으로 남길 것: 옛 정의 id → 사유. 값은 그대로 살아 있다(유실 아님).
     */
    data class Plan(
        val transfers: Map<Long, Long> = emptyMap(),
        val kept: Map<Long, KeptReason> = emptyMap()
    )

    /**
     * [moving] 각각을 [targetFields] 중 짝에 이어 줄지 판정한다.
     *
     * @param moving 경계를 넘는 값들. **빈 값은 넣지 않아도 되고, 넣어도 세지 않는다** —
     *   빈 값은 어느 타입으로도 빈 값이라 이어 줄 것도 보관할 것도 없다.
     * @param targetFields 도착 구역의 필드 정의 전부.
     * @param occupiedTargetFieldIds 도착 구역에서 **이미 값이 있는** 필드 id.
     *   이 판정이 덮어쓰기를 열지 않는 근거이므로, 부르는 쪽은 폼이 방금 채운 값까지 넣어야 한다.
     *
     * 짝짓기는 **연결 표식(`defaultField` code) 우선, 없으면 필드키**다. 표식을 먼저 보는 것이
     * 요점이다 — 두 그림자는 같은 템플릿에서 났으므로 표식이 정확한 짝이고, **사용자가 한쪽의
     * 필드키를 고쳐도 짝이 유지된다.** 키만 보면 그 순간 짝을 잃고 값이 보관 값으로 굳는다.
     *
     * 한 대상에 둘 이상이 몰리면 **앞선 것 하나만** 가고 나머지는 [KeptReason.TARGET_OCCUPIED]다 —
     * 뒤엣것이 앞엣것을 덮으면 그것이야말로 앱이 모르는 채 고르는 것이다.
     */
    fun plan(
        moving: List<Candidate>,
        targetFields: List<FieldDefinition>,
        occupiedTargetFieldIds: Set<Long>
    ): Plan {
        if (moving.isEmpty() || targetFields.isEmpty()) {
            return Plan(kept = moving.filter { it.value.isNotBlank() }.associate { it.def.id to KeptReason.NO_COUNTERPART })
        }
        val byCode = HashMap<Pair<String, String>, FieldDefinition>()
        val byKey = HashMap<Pair<String, String>, FieldDefinition>()
        for (f in targetFields) {
            DefaultFieldRef.codeFromConfig(f.config)?.let { byCode.putIfAbsent(f.entityType to it, f) }
            byKey.putIfAbsent(f.entityType to f.key, f)
        }

        val transfers = LinkedHashMap<Long, Long>()
        val kept = LinkedHashMap<Long, KeptReason>()
        val claimed = HashSet(occupiedTargetFieldIds)

        for ((def, value) in moving) {
            if (value.isBlank()) continue
            val code = DefaultFieldRef.codeFromConfig(def.config)
            val target = code?.let { byCode[def.entityType to it] } ?: byKey[def.entityType to def.key]
            when {
                target == null -> kept[def.id] = KeptReason.NO_COUNTERPART
                // 자기 자신이 짝으로 나오는 경우는 이동이 아니다 — 옮길 것이 없으니 세지도 않는다.
                target.id == def.id -> Unit
                target.id in claimed -> kept[def.id] = KeptReason.TARGET_OCCUPIED
                !FieldTypeCompatibility.isValueCompatible(value, target.fieldType) ->
                    kept[def.id] = KeptReason.TYPE_MISMATCH
                else -> {
                    transfers[def.id] = target.id
                    claimed.add(target.id)
                }
            }
        }
        return Plan(transfers, kept)
    }
}
