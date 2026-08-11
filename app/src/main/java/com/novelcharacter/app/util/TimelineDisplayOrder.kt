package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.TimelineEvent

/**
 * 연표의 **표시 순서** — 시간순(오름차순)과 역순(최근 먼저)의 단일 소스 (B-47).
 *
 * ## 왜 저장 계층이 아니라 표시 계층에서 뒤집는가 (착수 세션 실측)
 *
 * `TimelineDao`의 정렬 술어 `ORDER BY year ASC, month ASC, day ASC, displayOrder ASC`는
 * **15자리**에 있고, 그 쿼리를 부르는 계층은 연표 화면만이 아니다 — 실측한 소비처에
 * `excel/ExcelExporter`·`share/PdfExporter`·`share/WorldPackageExporter`·
 * `ui/stats/StatsDataProvider`·`ui/search/GlobalSearchViewModel`이 함께 있다.
 * 쿼리를 뒤집으면 **연표의 보기 설정 하나가 내보낸 엑셀·PDF·월드패키지의 행 순서까지
 * 바꾼다** — 사용자가 켠 것은 '연표를 어느 쪽부터 볼 것인가'이지 '파일을 어떻게 쓸
 * 것인가'가 아니다(개발 의도 4번 — 왕복이 조용히 달라지면 안 된다).
 * 쿼리를 복제해 역순판을 15개 더 두는 길도 있지만, 같은 술어가 두 벌이 되는 순간
 * 한쪽이 낡는다(이 저장소가 반복해 겪은 모양).
 *
 * 그래서 **DB는 언제나 시간순 하나만 알고**, 뒤집기는 화면이 그리기 직전에 한 번 한다
 * (R-19가 상한에 대해 세운 것과 같은 자리 — 자르는 일도 뒤집는 일도 표시 계층의 관심사다).
 *
 * ## 역순은 '연도만' 뒤집는 것이 아니다
 *
 * 목록을 통째로 뒤집으므로 **같은 날짜 안의 순서(`displayOrder`)도 함께 뒤집힌다.**
 * 그것이 맞다 — 역순은 같은 줄을 반대쪽 끝에서 읽는 것이고, 한 축만 뒤집으면
 * 같은 날짜 묶음만 시간순으로 남아 읽는 방향이 화면 안에서 갈린다.
 *
 * 그래서 **드래그 재정렬은 [canonicalReorder]를 반드시 거친다.** 역순 화면에서 끌어
 * 만든 배치를 눈에 보이는 차례대로 `displayOrder`에 적으면, 시간순으로 돌아왔을 때
 * 그 묶음이 **사용자가 방금 만든 것과 정반대로** 뜬다. 보기 토글 하나가 저장된 순서를
 * 뒤집는 셈이라, 무엇을 잃었는지 알 수 없는 부류의 유실이 된다.
 *
 * ## 연도 축(밀도 바 · 연도 슬라이더)은 함께 뒤집지 **않는다** (착수 세션 판정)
 *
 * 로드맵이 이 판단을 착수 세션에 맡겼다. 답은 *뒤집지 않는다*이고 근거는 레이아웃 실측이다 —
 * `fragment_timeline.xml`에서 `EventDensityBar`(높이 8dp)는 `yearSlider` **바로 위에
 * 같은 좌우 여백으로** 얹혀 있어 둘이 한 벌의 눈금으로 읽힌다. 밀도 바만 거울로 뒤집으면
 * **같은 x가 두 컨트롤에서 서로 다른 해를 가리킨다** — 고치려던 것보다 나쁜 어긋남이다.
 * 슬라이더까지 뒤집는 길은 값의 부호를 뒤집거나 RTL 레이아웃에 기대야 하는데, 그것은
 * 눈금·BC 연도·스텝 정렬이 얽힌 자리라 얻는 것보다 잃을 것이 크다.
 *
 * 뒤집히는 것은 **목록의 읽는 차례**이지 연도 축이 아니다. 그래서 `center_year` 앵커도
 * 그대로다 — 그것은 자리 번호가 아니라 **연도 값**이고, 스크롤 복원이 연도로 찾으므로
 * 어느 방향에서도 같은 사건에 선다(방향을 타는 값이었다면 토글할 때마다 자리가 튄다).
 */
object TimelineDisplayOrder {

    /** SharedPreferences(`timeline_ui_state`) 키 — 저장 자리도 한 곳이다. */
    const val PREF_KEY_DESCENDING = "sort_descending"

    /** 기본은 시간순이다 — 연표는 과거에서 미래로 읽는 것이 기본 기대다. */
    const val DEFAULT_DESCENDING = false

    /**
     * 조회 결과(시간순)를 표시 순서로 놓는다.
     *
     * 정렬을 다시 하지 않고 뒤집기만 하는 것이 요점이다 — 정렬 규칙을 여기서 다시 적으면
     * DAO의 술어와 두 벌이 되고, `month`/`day`가 null인 사건의 자리(SQLite는 NULL을 앞에
     * 놓는다)가 두 곳에서 갈린다.
     */
    fun arrange(events: List<TimelineEvent>, descending: Boolean): List<TimelineEvent> =
        if (descending) events.reversed() else events

    /**
     * 드래그 재정렬 결과를 **저장 순서(시간순)** 기준의 `displayOrder`로 바꾼다.
     *
     * [visualOrder]는 사용자가 화면에서 만든 차례다. 역순 화면에서는 그 차례가 시간순의
     * 뒤집힌 모습이므로 번호도 뒤에서부터 매긴다 — 그래야 시간순으로 돌아왔을 때
     * 같은 배치의 거울상이 뜬다(뒤집힌 것이 아니라).
     */
    fun canonicalReorder(visualOrder: List<TimelineEvent>, descending: Boolean): List<TimelineEvent> {
        val last = visualOrder.lastIndex
        return visualOrder.mapIndexed { index, event ->
            event.copy(displayOrder = if (descending) last - index else index)
        }
    }

    /**
     * 표시 순서에서 [center]보다 **앞**(목록 위쪽)에 놓이는 연도인가.
     * 역순이면 '앞'이 더 늦은 연도다 — 이전/다음 버튼과 `N / M` 표시가 같은 방향을 보게 한다.
     */
    fun isEarlierInDisplay(year: Int, center: Int, descending: Boolean): Boolean =
        if (descending) year > center else year < center

    /**
     * 시간순 목록에서의 자리를 표시 순서의 자리로 옮긴다.
     * `-1`(어느 사건에도 걸치지 않음)은 그대로 둔다 — 뒤집어서 만들어 낼 자리가 아니다.
     */
    fun displayIndexOf(ascendingIndex: Int, size: Int, descending: Boolean): Int = when {
        ascendingIndex < 0 -> ascendingIndex
        descending -> size - 1 - ascendingIndex
        else -> ascendingIndex
    }

}
