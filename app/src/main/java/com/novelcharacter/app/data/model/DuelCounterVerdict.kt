package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 상성 층의 **사용자 처분** (B-104 층 B) — 앱이 찾아낸 어긋남을 사용자가 무엇으로 판정했는가.
 *
 * 확정 문서 2-1이 처분을 셋으로 갈랐다. 그중 **행이 되는 것은 둘뿐이다**:
 *
 * | 처분 | 저장 |
 * |---|---|
 * | ① 잘못 눌렀다 | **행이 없다.** 해당 판을 지우면 어긋남 자체가 사라지므로 남길 것이 없다 |
 * | ② 아직 안 정했다 | [KIND_UNDECIDED] — *"나중에 다시 묻는다"*를 지키려면 지금 묻지 않았다는 사실이 남아야 한다 |
 * | ③ 상성이다 | [KIND_COUNTER] — **점수 적합에서 이 짝을 뺀다**(설계 3장) |
 *
 * **③은 파생이 아니라 사용자 판정이다.** 잔차 계산은 후보를 낼 뿐이고 *"이것은 상성이다"*는
 * 창작자만 말할 수 있다. 그래서 점수·순위처럼 다시 낼 수 있는 값과 달리 이 표에 남는다.
 *
 * ## 왜 상성이 점수에서 빠져야 하는가
 * B가 A를 늘 잡는데 그 판이 적합에 남아 있으면 B의 강함이 부풀고 A의 강함이 깎여
 * **둘과 무관한 제3자의 순위까지 틀어진다.** 확정 문서 2-1의 *"상성은 점수 위에 별도 층으로
 * 얹혀야 한다"*를 계산으로 옮긴 것이 이것이다 — 두 층을 가르면 양쪽이 다 정확해진다.
 *
 * ## 참가자는 코드로 가리킨다 (R-1)
 * [DuelMatch]와 같은 규약이다. [memberCodes]는 **뜻이 있는 순서**로 담고
 * ([SHAPE_DIRECT]는 `[센 쪽, 잡는 쪽]`, [SHAPE_CYCLE]은 이기는 차례대로),
 * [memberKey]는 그 순서를 지운 **정규 키**라 같은 관계가 회전·반전으로 두 번 등재되지 않는다.
 */
@Entity(
    tableName = "duel_counter_verdicts",
    foreignKeys = [
        ForeignKey(
            entity = DuelAxis::class,
            parentColumns = ["id"],
            childColumns = ["axisId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("axisId"),
        Index(value = ["axisId", "memberKey"], unique = true),
        Index(value = ["code"], unique = true)
    ]
)
data class DuelCounterVerdict(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val axisId: Long,
    /** [KIND_COUNTER] 또는 [KIND_UNDECIDED]. */
    val kind: String,
    /** [SHAPE_DIRECT](천적, 둘) 또는 [SHAPE_CYCLE](순환, 셋 이상). */
    val shape: String,
    /**
     * 참가자 코드 JSON 배열 — **뜻이 있는 순서**로 담는다.
     * 읽고 쓰는 것은 `com.novelcharacter.app.util.DuelRecords`가 단일 소스다.
     */
    val memberCodes: String,
    /** 순서를 지운 정규 키(코드 오름차순 `|` 이음). 같은 관계의 중복 등재를 인덱스가 막는다. */
    val memberKey: String,
    val decidedAt: Long = System.currentTimeMillis(),
    val code: String = generateEntityCode()
) {
    /** 점수 적합에서 빼야 하는 처분인가 — ③만 뺀다. ②는 아직 아무것도 정하지 않았다. */
    val excludesFromRating: Boolean get() = kind == KIND_COUNTER

    companion object {
        /** ③ 상성이다 — 관계로 등재되고 점수 적합에서 빠진다. */
        const val KIND_COUNTER = "counter"

        /** ② 아직 안 정했다 — 표시만 하고 점수는 건드리지 않는다. */
        const val KIND_UNDECIDED = "undecided"

        /** 천적 — `[센 쪽, 잡는 쪽]` 둘. */
        const val SHAPE_DIRECT = "direct"

        /** 순환 — 이기는 차례대로 셋 이상. */
        const val SHAPE_CYCLE = "cycle"
    }
}
