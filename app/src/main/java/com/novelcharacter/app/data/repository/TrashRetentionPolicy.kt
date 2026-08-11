package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.TrashSnapshot

/**
 * 휴지통 보관 정책의 **현행 값** — 순수 홀더 (B-74).
 *
 * ## 왜 저장소가 아니라 여기서 읽는가
 *
 * 정리(`TrashRepository.pruneIfNeeded`)를 부르는 자리는 열세 곳이고 전부 `Context`가 없는
 * 저장소 계층이다(`TrashRepository(db)`는 db 하나만 받는다). 설정 값을 인자로 흘리려면 그 열세
 * 곳과 그것을 부르는 화면까지 전부 고쳐야 하고, **한 곳이라도 빠뜨리면 그 삭제 경로만 조용히
 * 기본값으로 정리한다** — 사용자가 200건으로 올려 둔 채 캐릭터를 지웠는데 그 경로만 30건으로
 * 태우는 식이다. 값을 한 곳에 두고 모든 경로가 같은 것을 읽게 하는 편이 그 부류를 원리적으로
 * 없앤다.
 *
 * ## 준비되지 않았으면 **정리하지 않는다** — 이 파일에서 가장 중요한 규칙
 *
 * [current]는 설정을 **실제로 읽어 온 뒤에만** 값을 낸다. 아직이면 null이고, 그때 정리는
 * 통째로 건너뛴다([TrashRepository.pruneIfNeeded]).
 *
 * 기본값으로 대신 정리하지 않는 이유는 **되돌릴 수 없기 때문이다.** 정리의 결과는 영구 삭제이고,
 * 사용자가 보관을 200건·365일로 올려 두었는데 준비 전에 30건·30일로 정리하면 **동의한 적 없는
 * 백업이 사라진다.** 반대로 건너뛰어서 치르는 값은 *이번 삭제에서 한도 초과분이 남는 것*뿐이고,
 * 그것은 다음 삭제의 정리가 그대로 소진한다(R-3이 이미 같은 이유로 남기는 잔여분과 같은 성질이다).
 * **읽기가 실패했을 때도 같다** — 모르는 채로 지우는 것보다 남기는 쪽이 언제나 안전하다.
 */
object TrashRetentionPolicy {

    /**
     * 보관 정책 한 벌.
     *
     * @param maxOperations 종류별 보관 **작업** 수 상한 (항목 수가 아니다 — B-14)
     * @param retentionDays 보관 기한(일)
     */
    data class Settings(
        val maxOperations: Int = TrashSnapshot.DEFAULT_MAX_OPERATIONS,
        val retentionDays: Int = TrashSnapshot.DEFAULT_RETENTION_DAYS
    ) {
        val retentionMs: Long get() = retentionDays.toLong() * DAY_MS
    }

    /** 보관 개수 선택지 — 실사용 표본의 세계관 삭제 하나가 수백 항목이라 작업 수로 센다. */
    val MAX_OPERATION_CHOICES = listOf(10, 30, 50, 100, 200)

    /** 보관 기간 선택지(일) */
    val RETENTION_DAY_CHOICES = listOf(7, 30, 90, 180, 365)

    /**
     * `@Volatile`인 것은 쓰는 쪽(설정 화면·앱 시작)과 읽는 쪽(어느 스레드에서든 도는 삭제 경로)이
     * 다르기 때문이다. 값이 한 벌짜리 불변 객체라 [Settings] 하나만 보이면 짝이 어긋날 수 없다.
     */
    @Volatile
    private var loaded: Settings? = null

    /** 실제로 읽어 온 정책. **아직 읽지 못했으면 null이고, 그때는 정리하지 않는다.** */
    fun current(): Settings? = loaded

    /**
     * 화면 문구에 적을 값 — 아직 못 읽었으면 기본값을 든다.
     *
     * **[current]와 갈라 두는 것이 요점이다.** 지우는 쪽은 모르면 손대지 않아야 하지만
     * (되돌릴 수 없다), 적는 쪽은 모른다고 빈칸을 보일 수 없다. 앱 시작이 정책을 채우므로
     * 사용자가 삭제 창을 여는 시점에는 이미 실린 값이고, 이 폴백은 그 전의 짧은 틈에만 쓰인다.
     */
    fun currentOrDefault(): Settings = loaded ?: Settings()

    /** 설정 저장소가 값을 읽어 왔을 때만 부른다 — 추측한 값으로 부르지 말 것. */
    fun publish(settings: Settings) {
        loaded = sanitize(settings)
    }

    /**
     * 범위를 벗어난 값을 쓸 수 있는 값으로 좁힌다.
     *
     * 0 이하를 그대로 두면 [TrashPruneSelector.selectOverflow]가 *보호되지 않은 전부*를 대상으로
     * 삼아 휴지통이 삭제 즉시 비워진다. 엑셀 '앱 설정' 시트는 사람이 손으로 적는 칸이라
     * (개발 의도 4번 — 밖에서 편집된 값이 들어온다) 그 값이 실제로 들어올 수 있다.
     */
    fun sanitize(settings: Settings): Settings = Settings(
        maxOperations = settings.maxOperations.coerceIn(MIN_OPERATIONS, MAX_OPERATIONS_CAP),
        retentionDays = settings.retentionDays.coerceIn(MIN_DAYS, MAX_DAYS_CAP)
    )

    /** 되돌릴 수 없는 정리라 하한을 둔다 — 1건이면 다음 삭제 한 번에 직전 백업이 사라진다. */
    const val MIN_OPERATIONS = 5
    const val MAX_OPERATIONS_CAP = 1000
    const val MIN_DAYS = 1
    const val MAX_DAYS_CAP = 3650

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** 시험 전용 — 프로세스에 남은 값이 다음 시험으로 새지 않게 한다. */
    fun resetForTest() {
        loaded = null
    }
}
