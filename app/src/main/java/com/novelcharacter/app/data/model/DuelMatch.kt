package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 대결 **한 판** (B-104) — 사용자가 둘을 보고 하나를 고른 사실 그 자체. **사용자 데이터다.**
 *
 * 점수·순위·상성 후보·진행률은 전부 이 표에서 다시 낼 수 있는 파생이라 저장하지 않는다
 * (설계 4장). 저장하면 두 자리가 갈라지고, 갈라지면 어느 쪽이 진짜인지 알 방법이 없다.
 *
 * ## 참가자를 id가 아니라 **코드**로 가리킨다 (R-1)
 * 캐릭터를 지우고 휴지통에서 되살리면 DB id는 그대로일 때도 있고 **재발급될 때도 있다**
 * (`TrashRepository`는 옛 id가 비어 있을 때만 유지한다). id로 가리키면 두 가지가 한꺼번에
 * 어긋난다 — 되살아난 캐릭터의 판이 안 붙거나, 더 나쁘게는 그 사이 옛 id를 물려받은
 * **남의 캐릭터에 붙는다.** R-1이 *"오배정은 생략보다 나쁘다"*라고 못 박은 자리다.
 *
 * 그래서 판은 **[DuelAxis.targetType]이 정하는 안정 식별자**를 담는다:
 * - [DuelAxis.TARGET_CHARACTER] → `Character.code`
 * - [DuelAxis.TARGET_IMAGE] → 이미지 경로(`ImageMeta.path` — 이 앱에서 이미지의 자연키는 경로다)
 *
 * **판은 참가자가 사라져도 지우지 않는다.** 캐릭터 삭제의 처분이 이것이다(설계 1장) — 적합에서만
 * 빠져 `DuelRating.Fit.orphanMatches`로 **세어서 알리고**, 휴지통에서 캐릭터를 복원하면
 * 그 판들이 **그대로 되살아난다.** 지웠다면 되살릴 것이 없다.
 *
 * ⚠️ **이미지 축을 실제로 여는 슬라이스는 경로 재작성 경로와 함께 봐야 한다** — 폴더 왕복이
 * 파일을 개명하면 `imagePaths`가 다시 쓰이는데, 그때 이 표의 참가자 코드도 함께 옮겨야 한다.
 * 캐릭터 축은 이 문제가 없다(코드는 개명되지 않는다).
 *
 * ## 무승부와 묶음은 v1에서 **받아만 둔다**
 * [winnerCode]가 nullable인 것과 [groupId]가 있는 것은 화면이 아직 안 내주는 두 갈래
 * ('비슷하다' 버튼 · k지선다)의 저장 형식을 미리 세워 두는 것이다(설계 2장 표). **저장 형식은
 * 나중에 바꾸기 어려운 축**이라 되돌릴 수 있는 쪽으로 먼저 세운다 — 칸을 더하는 것은
 * `ALTER TABLE ADD COLUMN` 한 줄이지만, 이미 쌓인 수만 판의 뜻을 바꾸는 것은 되돌릴 길이 없다.
 */
@Entity(
    tableName = "duel_matches",
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
        Index(value = ["axisId", "aCode"]),
        Index(value = ["axisId", "bCode"]),
        Index("groupId"),
        Index(value = ["code"], unique = true)
    ]
)
data class DuelMatch(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val axisId: Long,
    /** 왼쪽 참가자의 안정 식별자. 무엇인지는 [DuelAxis.targetType]이 정한다(위 설명). */
    val aCode: String,
    /** 오른쪽 참가자의 안정 식별자. */
    val bCode: String,
    /**
     * 이긴 쪽의 코드. **null이면 무승부다** — 순수 계층이 0.5승으로 처리한다.
     * [aCode]·[bCode] 어느 쪽도 아닌 값이 들어오면(외부 편집된 엑셀 등) 적합이 그 판을
     * `malformedMatches`로 **세어서 알린다.** 조용히 버리지 않는다(개발 의도 2번).
     */
    val winnerCode: String? = null,
    /**
     * 한 화면이 낸 판들의 묶음 — k지선다(3~4 중 1등)가 한 번에 `k−1`판을 만들 때 그 되돌리기
     * 단위다. null이면 단독 판이고 되돌리기 단위는 판 하나다.
     */
    val groupId: String? = null,
    val decidedAt: Long = System.currentTimeMillis(),
    /**
     * 이 판의 안정 식별자. **엑셀 왕복이 같은 판을 두 번 들이지 않으려면 행에 정체가 있어야
     * 한다**(개발 의도 4번) — 같은 짝을 여러 번 붙일 수 있으므로 `(축, 짝, 시각)`으로는
     * 유일성이 보장되지 않는다. 나중에 붙일 수 없는 칸이라 처음부터 둔다.
     */
    val code: String = generateEntityCode()
) {
    /** 무승부인가 — 화면·엑셀·적합이 전부 이 한 판정을 쓴다. */
    val isDraw: Boolean get() = winnerCode == null

    /** 승자가 두 참가자 중 하나인가. 아니면 깨진 판이다(적합이 세어서 알린다). */
    val isWellFormed: Boolean
        get() = aCode != bCode && (winnerCode == null || winnerCode == aCode || winnerCode == bCode)
}
