package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 대결의 **축** (B-104) — "무엇을 겨루는가". 강함·몸매·아름다움은 예시일 뿐이고 사용자가
 * 추가·편집·삭제한다(원칙 01). 설계 정본은 `docs/duel_system_design_2026-08.md` 4장이다.
 *
 * **세계관 단위다**(사용자 확정 ㄷ1) — 필드 정의·등급 체계와 같은 자리이며, 세계관이 지워지면
 * FK CASCADE로 축과 그 아래 판·처분이 함께 죽는다. 그래서 세계관 삭제 경로는 이 데이터를
 * 휴지통에 담아야 한다(`TrashRepository.snapshotUniverse` → [TrashSnapshot.TYPE_DUEL_AXIS]).
 *
 * ## 참가자가 무엇인가는 [targetType]이 정한다
 * 대상이 둘이라는 것이 백로그 원문의 요구다 — **캐릭터끼리**와 **같은 캐릭터의 이미지끼리**.
 * 순수 계층([com.novelcharacter.app.util.DuelRating])은 참가자를 `Long` id로만 보므로
 * 축의 종류와 무관하게 그대로 돈다. 저장 쪽에서 갈리는 것은 **참가자를 무엇으로 가리키는가**
 * 하나뿐이고, 그 규약은 [DuelMatch.aCode]가 정의한다.
 *
 * ## 이름은 세계관 안에서 유니크다
 * 엑셀 왕복(ㄹ1)이 축을 이름으로 해석할 자리가 있고(코드 열이 비어 들어오는 파일), 화면 목록도
 * 같은 이름 둘을 구별할 방법이 없다. [targetType]까지 넣어 유니크로 두는 것은 캐릭터 축 '아름다움'과
 * 이미지 축 '아름다움'이 **서로 다른 것을 겨루는 다른 축**이기 때문이다.
 */
@Entity(
    tableName = "duel_axes",
    foreignKeys = [
        ForeignKey(
            entity = Universe::class,
            parentColumns = ["id"],
            childColumns = ["universeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("universeId"),
        Index(value = ["universeId", "targetType", "name"], unique = true),
        Index(value = ["code"], unique = true)
    ]
)
data class DuelAxis(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val universeId: Long,
    val name: String,
    /** [TARGET_CHARACTER] 또는 [TARGET_IMAGE]. 축을 만든 뒤에는 바꾸지 않는다 — 바꾸면 쌓인 판의 참가자가 통째로 뜻을 잃는다. */
    val targetType: String = TARGET_CHARACTER,
    val displayOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** 안정 식별자 — 판·처분·휴지통·엑셀이 이 값으로 축을 가리킨다(R-1). */
    val code: String = generateEntityCode()
) {
    val isImageAxis: Boolean get() = targetType == TARGET_IMAGE

    companion object {
        /** 캐릭터끼리 겨루는 축. 참가자 코드는 `Character.code`다. */
        const val TARGET_CHARACTER = "character"

        /**
         * 같은 캐릭터의 이미지끼리 겨루는 축 — *"걸러낼 이미지의 근거를 쌓는"* 용도(백로그 원문).
         * 참가자 코드는 이미지 경로다([DuelMatch.aCode]가 그 규약의 원문).
         */
        const val TARGET_IMAGE = "image"

        val TARGET_TYPES: Set<String> = setOf(TARGET_CHARACTER, TARGET_IMAGE)
    }
}
