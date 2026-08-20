package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 캐릭터의 **명대사 한 줄** (사용자 요청 2026.08.20).
 *
 * 원문: *"캐릭터마다 명대사 여럿 써서 저장할 수 있게 해주면 좋겠고 생일 전용으로도 대사 적을
 * 수 있게 해서 생일에는 해당 캐릭터의 생일 대사중 하나를 모달에 같이 띄워주면 좋을 거 같고.
 * 이 명대사의 경우 다른 곳에서도 잘 쓰일 수 있잖아."*
 *
 * ## 왜 커스텀 필드가 아니라 자기 표인가
 *
 * 값이 **여럿이고 각자 속성을 든다**(상황·생일용 여부·차례). `CharacterFieldValue`는 한
 * 캐릭터·한 필드에 **칸 하나**이고([CharacterFieldValue]의 유니크 색인이 그것이다), 거기에
 * 여럿을 담으려면 한 칸에 JSON을 말아 넣어야 한다 — 그러면 **엑셀에서 사람이 고칠 수 없다**
 * (개발 의도 4번). 상태 변화·관계도 같은 이유로 자기 표를 든다.
 *
 * **그래도 대결 축에는 걸린다** — `DuelSystemFields`의 `sys:quote`가 그 통로다. 그쪽은
 * *"필드가 아닌 것을 필드처럼 가리키는 이름"*이라, 태그(`character_tags`)·세력이 이미 같은
 * 길로 카드에 오른다. 사용자 원문의 *"대결탭에서 프로필 필드로 설정했을때"*가 그 자리다.
 *
 * @property occasionKey 이 대사가 **어느 자리의 것인가**. 빈 값이 일반 명대사이고
 *   [KEY_BIRTHDAY]가 생일 전용이다. **닫힌 목록이 아니다**(원칙 01) — `__` 접두는
 *   [CharacterStateChange.KEY_BIRTH]와 같은 예약 표기이고, 그 밖의 글자는 사용자가 자기
 *   상황을 적어 둔 것으로 그대로 보존된다. 앱이 모르는 상황은 **일반으로 대접하지 않고
 *   자기 이름으로 남는다** — 조용히 갈음하면 사용자가 적은 구분이 사라진다(개발 의도 2번).
 * @property note 이 대사가 나온 자리 — 장면·회차·상대. 비워 둘 수 있다.
 * @property sortOrder 사용자가 정한 차례(원칙 04 — 드래그로 바꾼다). 같은 값이면 [id] 순.
 * @property code 엑셀 왕복 안정 식별자 — 대사 글자를 외부에서 고쳐도 **같은 행**임을 잇는다.
 *   nullable인 이유는 [CharacterStateChange.code]와 같다(옛 Gson 스냅샷 역직렬화 수용).
 */
@Entity(
    tableName = "character_quotes",
    foreignKeys = [
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("characterId"), Index("occasionKey"), Index(value = ["code"], unique = true)]
)
data class CharacterQuote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: Long,
    val text: String,
    val occasionKey: String = "",
    val note: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val code: String? = generateEntityCode()
) {
    /** 생일 자리의 대사인가. */
    val isBirthday: Boolean get() = occasionKey == KEY_BIRTHDAY

    companion object {
        /** 생일 전용 대사의 예약 상황키. */
        const val KEY_BIRTHDAY = "__birthday"

        /** 일반 명대사 — 상황을 안 적은 것. */
        const val KEY_GENERAL = ""
    }
}
