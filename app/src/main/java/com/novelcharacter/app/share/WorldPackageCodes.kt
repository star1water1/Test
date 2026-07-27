package com.novelcharacter.app.share

import com.novelcharacter.app.data.model.generateEntityCode

/**
 * 월드패키지 가져오기의 코드(안정 식별자) 충돌 정책 — 순수 로직, JVM 하네스가 실행 검증한다.
 *
 * R-1과의 관계: 코드는 엔티티의 정체성이다. 패키지를 **새 엔티티로** 들여올 때 기존 코드와
 * 충돌하면, 살아 있는 남의 엔티티를 가로채지 않도록 **재발급**한다(가로채면 엑셀 왕복·휴지통
 * 복원이 그 남을 대상으로 오배정된다). 재발급은 무통보로 두지 않는다 — [Registry.reissuedCount]를
 * 호출부가 반드시 고지한다.
 */
object WorldPackageCodes {

    /** 한 테이블(코드 네임스페이스)의 사용 중 코드 집합과 재발급 카운터. */
    class Registry(existing: Collection<String?>) {
        private val used: MutableSet<String> = existing.filterNotNull().filter { it.isNotBlank() }.toHashSet()

        /** 원하는 코드가 이미 사용 중이라 재발급된 건수 (코드가 아예 없던 행의 신규 발급은 세지 않는다). */
        var reissuedCount: Int = 0
            private set

        /**
         * @param wanted 패키지 행이 갖고 온 코드. null/공란이면 무조건 신규 발급(재발급 아님).
         * @return 이 기기에서 유일함이 보장된 코드 — 반환 즉시 사용 중으로 등록된다.
         */
        fun claim(wanted: String?): String {
            val w = wanted?.takeIf { it.isNotBlank() }
            if (w != null && used.add(w)) return w
            var candidate = generateEntityCode()
            while (!used.add(candidate)) candidate = generateEntityCode()
            if (w != null) reissuedCount++
            return candidate
        }
    }

    /** "이름", "이름 (2)", "이름 (3)" … — 기존 이름과 겹치지 않는 첫 변형을 돌려준다. */
    fun uniqueName(existingNames: Set<String>, base: String): String {
        if (base !in existingNames) return base
        var i = 2
        while ("$base ($i)" in existingNames) i++
        return "$base ($i)"
    }
}
