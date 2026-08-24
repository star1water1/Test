package com.novelcharacter.app.util

import java.util.Locale

/**
 * 생일(월/일) 글자를 읽고 **저장 모양을 하나로** 만드는 단일 소스 — [ColorHex]와 같은 자리다.
 *
 * ## 무엇이 갈려 있었나
 *
 * 이 앱에는 생일 글자를 만드는 자리가 셋인데 **둘만 0을 채웠다**:
 *
 * | 만드는 자리 | 내던 글자 |
 * |---|---|
 * | [FieldRandomGenerator] (계절 기반 생성) | `05-30` |
 * | [SemanticFieldSyncHelper]의 상태변화 → 필드값 역방향 | `05-30` |
 * | **편집 화면의 구조화 입력**(월 칸 · 일 칸을 `-`로 잇는다) | **`5-30`** |
 *
 * 세 번째가 규칙 밖이라, 같은 캐릭터의 같은 생일이 **파일 한 벌 안에서 두 글자**가 됐다 —
 * 캐릭터 시트는 `5-30`, '캐릭터 상태변화' 시트는 `05-30`(실측 2026.08.24 사용자가 내보낸
 * 파일: 생일이 있는 47명 중 둘이 그 모양). 안내 시트는 그 형식을 `MM-DD`라 적는다.
 *
 * 읽는 쪽은 양쪽을 다 받으므로 **동작이 틀리지는 않는다.** 갈리는 것은 *같은 사실의 표기*이고,
 * 그래서 값 라이브러리·자동완성·통계가 `5-30`과 `05-30`을 **다른 값 둘로 센다**(원칙 02).
 *
 * ## 왜 `-`로 못박는가 — 새 결정이 아니다
 *
 * 역방향([SemanticFieldSyncHelper]의 `applyStateChangeToFields`)이 이미 `%02d-%02d`를
 * **조건 없이** 되쓰고 있다. 연표에서 출생 연도를 한 번 고치면 그 순간 `5/30`도 `05-30`이
 * 된다는 뜻이다. 정방향이 같은 모양을 쓰는 것은 **두 방향을 일치시키는 것**이지 표기를
 * 새로 좁히는 것이 아니다. 읽기는 종전 그대로 `-`·`/`·`.`를 다 받는다([parse]).
 *
 * 순수 코틀린인 것은 의도다 — 안드로이드 의존이 없어야 들이는 문(엑셀 가져오기)과 1회 정리
 * ([com.novelcharacter.app.data.maintenance.LegacyValueFormats])가 **같은 판정**을 쓰고,
 * 순수 JVM 시험이 그것을 잰다.
 */
object BirthDateFormat {

    /**
     * `MM-DD` · `M-D` · `YYYY-MM-DD` 형식의 생일에서 (월, 일)을 뽑는다. 아니면 `null`.
     *
     * 구분자는 `-`·`/`·`.` 셋 다 받는다 — 외부 편집기와 손입력이 실제로 그렇게 적는다.
     * 세 토막이면 **맨 앞을 연도로 보고 버린다**(엑셀이 날짜 셀로 바꿔 연도를 붙이는 경로).
     */
    fun parse(value: String?): Pair<Int, Int>? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split("-", "/", ".")
        val month: Int?
        val day: Int?
        when (parts.size) {
            2 -> {
                month = parts[0].trim().toIntOrNull()
                day = parts[1].trim().toIntOrNull()
            }
            3 -> {
                // YYYY-MM-DD → 연도 무시, 월/일만 추출
                month = parts[1].trim().toIntOrNull()
                day = parts[2].trim().toIntOrNull()
            }
            else -> return null
        }
        // 실재하는 날인가의 판정은 [isRealMonthDay]가 든다 — 종전에는 이 자리가
        // `month !in 1..12 || !isValidDay(...)`로 그 술어를 손으로 다시 적고 있었다.
        if (!isRealMonthDay(month, day)) return null
        return month!! to day!!
    }

    /** 저장 모양(`MM-DD`)으로 다듬은 글자, 읽을 수 없으면 `null`. */
    fun canonicalOrNull(value: String?): String? = parse(value)?.let { (m, d) -> of(m, d) }

    /** (월, 일) → 저장 모양. 글자를 만드는 자리는 **전부 이 함수를 지난다.** */
    fun of(month: Int, day: Int): String = String.format(Locale.US, "%02d-%02d", month, day)

    /**
     * 이 값을 저장 모양으로 **고쳐야 하는가** — 읽히는데 지금 글자가 규격 밖일 때만 `true`.
     *
     * 읽을 수 없는 글자는 대상이 아니다: 사용자가 적어 둔 것을 우리가 못 읽는다고 해서
     * 지우거나 바꾸지 않는다(개발 의도 2번 — 조용한 유실 금지). 그런 값은 그대로 남고,
     * 파생 이력(`__birth`)이 안 생기는 것으로 사용자가 알아챈다.
     */
    fun needsRepair(value: String?): Boolean {
        val canonical = canonicalOrNull(value) ?: return false
        return canonical != value?.trim()
    }
}
