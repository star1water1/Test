package com.novelcharacter.app.util

/**
 * 서사 밀도 곡선의 **항목 수를 연도 폭이 아니라 상한에 묶는다** (순수 로직 — JVM 시험 대상).
 *
 * ## 무엇을 막는가 — 앱에서 유일하게 *폭*에 비용이 붙던 자리
 *
 * 통계 › 사건의 밀도 곡선은 **빈 연도를 채워** 그린다. 사건이 1년과 5년에만 있어도 2·3·4년이
 * 0으로 서야 곡선이 곡선이기 때문이고, 그 자체는 옳다. 그런데 채우는 방식이
 * `(minYear..maxYear).map { … }`이라 **항목 수가 사건 수가 아니라 연도 폭에 붙었다.**
 *
 * 이 앱은 판타지 역법을 일급으로 다루고(사건의 `calendarType` 기본값이 `천개력`이다),
 * 연도 입력에는 상한이 없다 — 월·일에는 범위 검사가 있는데 연도에만 없고, 엑셀로 들여오는
 * 값에도 없다. **그것이 결함이 아니다**: 12만 년짜리 연표는 정당한 데이터이고, 이 앱의
 * 개발 의도가 정확히 그 무한을 열어 두는 것이다. 결함은 *무한을 받는 입구*가 아니라
 * **그 무한을 항목 수로 그대로 옮긴 이 한 줄**이었다.
 *
 * 폭 12만이면 `Pair`·`BarEntry`·`String`이 각 12만 개씩 메인 스레드에서 만들어져 화면이
 * 멈추고, 폭이 수억이면 `OutOfMemoryError`다 — 그리고 그 오류는 `Error`라 적재 코루틴의
 * `catch (e: Exception)`이 **잡지 못한다.** 통계 탭이 비는 것이 아니라 앱이 죽는다.
 *
 * ## 처분 — 접는다. 거부하지도, 감추지도 않는다
 *
 * 상한을 넘으면 **연도를 구간으로 묶고 합을 보존한다**(R-14: 상한은 접는 장치다). 세는
 * 방식이 요점이다 — 범위를 다시 펼치지 않고 **있는 연도만**(`yearDensity`의 키, 크기 ≤ 사건
 * 수) 훑어 구간에 더한다. 그래서 비용이 `O(고유 연도 + 상한)`이고 **폭에 절대 비례하지 않는다.**
 *
 * **상한 아래에서는 한 칸도 달라지지 않는다** — 폭이 상한 이내면 종전과 글자까지 같은
 * 한 해짜리 구간이 나온다([DisplayCap] 전체가 지키는 계약이다).
 *
 * ## R-19와 충돌하지 않는다
 *
 * R-19는 *"계산은 전량을 돌려주고 자르는 일은 화면이 표시 직전에 한 번만 한다"*이다. 여기서
 * 접히는 것은 **실데이터가 아니라 합성된 0**이고, 진짜 데이터인 `EventStats.yearDensity`
 * (성긴 맵)는 같은 객체가 여전히 전량 돌려준다 — 어느 소비처도 분모를 잃지 않는다.
 * 게다가 화면에서 자르면 **이미 계산 쪽에서 난 OOM을 되돌릴 수 없다.**
 */
object NarrativeDensityCurve {

    /**
     * 곡선의 한 칸. 접히지 않았으면 [fromYear] == [toYear]이고, 그때 이 자료는 종전의
     * `(연도, 개수)` 쌍과 뜻이 같다.
     */
    data class Bucket(val fromYear: Int, val toYear: Int, val count: Int) {
        /** 여러 해를 묶었는가 — 화면이 라벨과 고지를 가르는 유일한 판정이다. */
        val folded: Boolean get() = fromYear != toYear
    }

    /**
     * @param yearDensity 연도 → 그 해의 사건 수 (**성긴 맵** — 사건이 있는 해만 든다)
     * @param maxBuckets 낼 칸 수의 상한. 단일 소스는 [DisplayCap.DENSITY_CURVE_BUCKETS]다.
     */
    fun build(yearDensity: Map<Int, Int>, maxBuckets: Int): List<Bucket> {
        if (yearDensity.isEmpty() || maxBuckets <= 0) return emptyList()
        val minYear = yearDensity.keys.min()
        val maxYear = yearDensity.keys.max()
        // **폭은 Long으로 센다.** `maxYear - minYear`는 기원전 표기와 큰 연도가 섞이면
        // Int 범위를 넘는데, 그 입력이 바로 이 함수가 견뎌야 할 것이다(넘치면 음수가 되어
        // 상한 판정이 거꾸로 서고, 접지 않은 채 그대로 터진다).
        val span = maxYear.toLong() - minYear.toLong() + 1

        if (span <= maxBuckets) {
            // 종전과 한 칸도 다르지 않다 — 빈 해는 0으로 채운다.
            return (minYear..maxYear).map { year -> Bucket(year, year, yearDensity[year] ?: 0) }
        }

        // 접는다. **범위를 다시 펼치지 않는 것이 요점이다** — `(minYear..maxYear).chunked(...)`
        // 류는 폭만큼을 재료화해 이 결함을 그대로 되살린다.
        val width = (span + maxBuckets - 1) / maxBuckets   // 올림 나눗셈
        val counts = IntArray(maxBuckets)
        for ((year, count) in yearDensity) {
            val idx = ((year.toLong() - minYear) / width).toInt().coerceIn(0, maxBuckets - 1)
            counts[idx] += count
        }
        return (0 until maxBuckets).map { i ->
            val from = minYear + i * width
            // 마지막 칸은 실측 최대에서 끊는다 — 올림 때문에 폭 밖까지 뻗을 수 있다.
            val to = minOf(from + width - 1, maxYear.toLong())
            Bucket(from.toInt(), to.toInt(), counts[i])
        }
    }
}
