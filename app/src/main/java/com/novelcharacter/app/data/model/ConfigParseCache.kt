package com.novelcharacter.app.data.model

import java.util.concurrent.ConcurrentHashMap

/**
 * `FieldDefinition.config`(JSON 문자열) → 파싱 결과의 메모이즈 (S6, 2026.07.30).
 *
 * **왜 필요한가.** 통계는 값 하나마다 설정을 다시 판정한다 — `FieldValueTokenizer.splitForStats`가
 * 값마다 [StructuredInputConfig.fromConfig]를, 그 분기를 지나면 [DisplayFormat.fromConfig]를
 * 부른다. 값 하나당 `JSONObject`가 2~3개 만들어지고, 통계 한 번은 값 테이블을 여러 번 지난다.
 *
 * 실측(실사용 표본 ×30, 빈 칸을 채운 경우 = 값 134,880개):
 * 토큰화 **1회 통과**가 894ms · 634MB인데, 필드당 규칙을 한 번만 풀면 41ms · 9.4MB다
 * (시간 −95% · 할당 −99%). 10계산 전체가 4.6초 · 3.0GB를 쓰던 중 큰 몫이 여기였다.
 * 근거는 `docs/scalability_performance_2026-07.md` 3-6, 배경은 백로그 **B-42**.
 *
 * **왜 캐시가 안전한가.** `fromConfig`는 문자열 하나만 받아 결과를 내는 **순수 함수**이고,
 * 결과 타입은 둘 다 불변이다(`DisplayFormat`은 enum, [StructuredInputConfig]는 `val`만 가진
 * data class이며 `parts`의 원소도 그렇다). 그래서 **무효화가 필요 없다** — 설정이 바뀌면
 * config 문자열이 바뀌고, 바뀐 문자열은 다른 키라 새로 파싱된다. 캐시가 낡을 수 있는 경로가
 * 구조적으로 없다.
 *
 * **왜 호출부를 고치지 않았는가.** B-42는 "필드별 파싱 규칙 값 타입을 만들어 호출부가 들고
 * 다니게" 하자고 적었는데, 그러면 엑셀·검색·자동완성·라이브러리 수확까지 21곳을 한 번에
 * 바꿔야 하고 **규칙이 갈릴 위험**(S-18이 그 사례)이 새로 생긴다. 같은 함수를 그대로 두고
 * 결과만 기억하면 호출부가 몰라도 되고 갈릴 것도 없다.
 */
internal class ConfigParseCache<T : Any>(
    private val maxEntries: Int = 256,
    private val parse: (String) -> T
) {
    // 통계 10계산이 Dispatchers.IO에서 동시에 도는 경로라 락 없는 읽기가 필요하다.
    private val cache = ConcurrentHashMap<String, T>()

    fun get(config: String): T {
        cache[config]?.let { return it }
        // 상한을 넘으면 통째로 비운다. LRU 추적은 이 경로에 붙이기엔 비싸고, 여기서 잃는 것은
        // 재파싱 몇 번뿐이다(정확성은 캐시 보유 여부와 무관하다). 실제로는 한 세계관의 필드
        // 정의 수만큼만 쌓이므로 상한에 닿는 일이 드물다.
        if (cache.size >= maxEntries) cache.clear()
        val parsed = parse(config)
        cache[config] = parsed
        return parsed
    }
}
