package com.novelcharacter.app.util

/**
 * 프리셋 이름이 겹쳤을 때 무엇을 할 것인가 — **목록 프리셋(캐릭터 탭)과 검색 프리셋(전역 검색)이
 * 공유하는 단일 소스**(B-191, 확정 15장 1번).
 *
 * ## 왜 판정을 여기로 내리는가
 * 두 화면은 엔티티도 저장소도 다르지만 **겹침을 다루는 규칙은 같아야 한다** — 한쪽만 고치면
 * 사용자는 *"목록 프리셋은 물어보는데 검색 프리셋은 안 물어본다"*를 겪는다. 그 갈림이 실제로
 * 이 앱에 있었다(B-75: 같은 개수 한도를 한쪽은 법으로, 한쪽은 권고로 다뤘다).
 *
 * ## 겹침의 잣대는 **정확 일치**다 — DB가 보는 것과 같아야 한다
 * 두 표 다 `name`이 유니크 색인이고 SQLite 기본 대조는 이진 비교라, 대소문자나 공백을
 * 무시하고 겹쳤다고 말하면 **DB는 겹치지 않는다고 보는 이름에 대해 창을 띄운다**(있지도 않은
 * 충돌을 알리고, 덮어쓰기를 고르면 남의 프리셋 이름이 바뀐다). 다듬기는 [normalize] 한 자리
 * ─ 앞뒤 공백만 ─ 에서 하고 그 값을 그대로 저장·조회에 쓴다.
 */
object PresetNameConflict {

    /**
     * 저장·개명이 쓰는 이름 다듬기. **판정·조회·저장이 전부 이 값을 쓴다** — 화면에서 다듬고
     * 저장할 때 원문을 넣으면 *겹치지 않는다고 판정한 이름과 다른 이름이* 들어간다.
     */
    fun normalize(raw: String): String = raw.trim()

    /** [verdict]의 답. 화면은 이 넷에만 반응하면 된다. */
    enum class Verdict {
        /** 쓸 수 있다 — 새로 넣는다. */
        FREE,

        /** 남의 프리셋이 그 이름을 쓰고 있다 — 덮어쓸지 물어본다. */
        TAKEN,

        /**
         * 기본 프리셋이 쓰고 있다 — **덮어쓰기를 제안하지 않는다.**
         * 기본 프리셋은 편집·삭제 경로 자체가 막혀 있어(전역 검색의 옵션 창), 여기서만
         * 내용을 갈아치울 수 있게 두면 같은 대상의 처분이 자리마다 갈린다.
         */
        TAKEN_BY_DEFAULT,

        /** 자기 자신이다 — 개명 창에서 이름을 안 바꾸고 저장한 경우이며 충돌이 아니다. */
        SELF
    }

    /**
     * [existingId]는 그 이름으로 이미 저장된 프리셋의 id(없으면 null),
     * [selfId]는 지금 고치고 있는 프리셋의 id(새로 저장하는 중이면 null).
     */
    fun verdict(existingId: Long?, existingIsDefault: Boolean, selfId: Long? = null): Verdict = when {
        existingId == null -> Verdict.FREE
        selfId != null && existingId == selfId -> Verdict.SELF
        existingIsDefault -> Verdict.TAKEN_BY_DEFAULT
        else -> Verdict.TAKEN
    }

    /**
     * '다른 이름으로 저장'을 골랐을 때 입력칸에 **채워 줄** 이름 — `이름 (2)`, 그것도 있으면 `(3)`…
     *
     * **자동 개명이 아니다.** 확정 15장 1번이 자동 개명을 기각한 이유는 *조용해서*이고
     * (겹쳤다는 사실을 모른 채 비슷한 프리셋이 쌓인다), 여기서는 이미 겹쳤다고 말한 뒤
     * 사용자가 고쳐 쓸 수 있는 칸에 제안을 넣는 것이라 그 기각에 걸리지 않는다.
     *
     * 이미 `이름 (2)` 꼴인 것을 다시 제안받으면 `이름 (2) (2)`로 늘어나지 않게 꼬리를 떼고 센다.
     */
    fun suggestAlternative(name: String, taken: Collection<String>): String {
        val base = stripSuffix(normalize(name))
        val takenSet = taken.toHashSet()
        // 후보는 유한하다 — 이미 쓰인 이름 수 + 2를 넘으면 반드시 빈자리가 나온다.
        for (n in 2..(takenSet.size + 2)) {
            val candidate = "$base ($n)"
            if (candidate !in takenSet) return candidate
        }
        return base
    }

    /** 끝의 ` (숫자)`를 뗀다. 없으면 그대로. */
    private fun stripSuffix(name: String): String {
        val m = SUFFIX.find(name) ?: return name
        val stripped = name.substring(0, m.range.first)
        return if (stripped.isBlank()) name else stripped
    }

    private val SUFFIX = Regex(" \\(\\d+\\)$")
}
