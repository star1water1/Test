package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterFieldValue

/**
 * 가져오기 한 번 동안 **(캐릭터, 필드) 값의 현재 상태**를 들고 있는 장부 (B-72 ②).
 *
 * ## 무엇을 없애는가
 *
 * 엑셀 가져오기는 값을 두 시트에서 받는데 둘 다 **값 한 건마다 DB를 한 번 물었다.**
 * - 캐릭터 시트: 필드가 **열**이라 `(행 × 필드 열)` 번 — 실사용 ×30이면 6,420행 × 90열 ≈ **57만 회**.
 * - '캐릭터 필드값' 시트: 값이 **행**이라 행마다 한 번 — ×30이면 **36,510회**.
 *
 * 둘 다 조회 단위가 **값**이었다. 그런데 값의 정체는 `(캐릭터, 필드)`이고 한 캐릭터의 값은
 * 많아야 필드 수만큼이라, **캐릭터가 이 조회의 자연스러운 단위**다. 장부는 캐릭터당 한 번
 * 받아 두고 그 뒤의 물음에 메모리에서 답한다.
 *
 * ## 왜 순수 클래스인가
 *
 * 담는 것이 HashMap 둘뿐이라 [ExcelImportService] 안에 지역 변수로 두는 것이 짧다. 그런데
 * 그 파일은 8,700줄짜리 suspend 함수 뭉치라 **이 저장소의 어떤 로컬 검증도 닿지 못한다** —
 * 로직을 util로 내려야 순수 JVM 시험이 잰다(저장소의 기존 처방이고 [EpochMemo]가 같은 자리다).
 * 아래 세 성질은 어기면 **조용히** 틀리는 부류라 특히 그렇다.
 *
 * ## 지켜야 하는 세 가지
 *
 * 1. **쓴 것은 곧바로 읽힌다.** 같은 `(캐릭터, 필드)`를 한 가져오기에서 두 번 만나면 —
 *    두 시트가 같은 항목을 담거나 한 시트에 같은 행이 두 번 있을 때 — 두 번째는 첫 번째의
 *    결과를 봐야 한다. 못 보면 *있는 값을 없다고 보고* insert를 치는데, 그 표에는
 *    `(characterId, fieldDefinitionId)` **유니크 색인**이 있어 가져오기가 통째로 실패한다.
 * 2. **지운 것은 사라진다.** 빈 셀로 값을 비운 뒤 다시 물으면 없어야 한다. 남아 있으면
 *    이미 지운 행을 update하려 들고(무동작), 사용자에게는 *"지웠다"*고 세어 놓고
 *    값이 그대로인 것처럼 보인다.
 * 3. **안 실은 캐릭터는 안 실었다고 답한다.** [isLoaded]가 없으면 "값이 0건인 캐릭터"와
 *    "아직 안 물어본 캐릭터"가 똑같이 빈 맵으로 보여, **새로 만든 캐릭터마다 헛조회**가
 *    돌거나 반대로 **기존 값을 못 보고 덮어쓴다.**
 *
 * ## 메모리
 *
 * 최대량은 *가져오기가 건드린 캐릭터의 값 전부*이고, 그 값들이 실려 온 시트는
 * `StreamingImportWorkbook`이 **이미 통째로** 들고 있다(값 한 건이 한 행/한 칸이므로
 * 시트 쪽이 언제나 크다). 즉 상한이 한 단계 오르지 않고 이미 서 있는 상한
 * (*가장 큰 시트 하나*) 안에 들어간다.
 */
class CharacterValueLedger {

    private val byCharacter = HashMap<Long, MutableMap<Long, CharacterFieldValue>>()

    /** 이 캐릭터의 값을 이미 실었는가. 거짓이면 호출부가 DB에서 읽어 [load]로 넘긴다. */
    fun isLoaded(characterId: Long): Boolean = byCharacter.containsKey(characterId)

    /**
     * 이 캐릭터의 현재 값을 싣는다. **이미 실려 있으면 덮어쓰지 않는다** — 그 사이에 이 장부가
     * 기록한 쓰기가 있을 수 있고, 다시 실으면 그 쓰기가 조용히 없던 일이 된다.
     * 새로 만든 캐릭터는 값이 있을 수 없으므로 `emptyList()`로 실어 조회 자체를 건너뛴다.
     */
    fun load(characterId: Long, rows: List<CharacterFieldValue>) {
        if (byCharacter.containsKey(characterId)) return
        byCharacter[characterId] = rows.associateByTo(HashMap()) { it.fieldDefinitionId }
    }

    /** 이 캐릭터를 장부에서 내린다 — 다음 [isLoaded]가 거짓이 되어 DB를 다시 읽는다. */
    fun forget(characterId: Long) {
        byCharacter.remove(characterId)
    }

    /**
     * `(캐릭터, 필드)`의 현재 값. 실리지 않은 캐릭터에도 `null`을 돌려준다 —
     * **호출부는 반드시 [isLoaded]로 먼저 가른다.** 안 그러면 위 3번을 어긴다.
     */
    fun get(characterId: Long, fieldId: Long): CharacterFieldValue? =
        byCharacter[characterId]?.get(fieldId)

    /** 값을 썼다고 기록한다(insert·update 양쪽). DB에 쓴 **그 행**을 그대로 넘길 것. */
    fun put(value: CharacterFieldValue) {
        byCharacter.getOrPut(value.characterId) { HashMap() }[value.fieldDefinitionId] = value
    }

    /** 값을 지웠다고 기록한다. */
    fun remove(characterId: Long, fieldId: Long) {
        byCharacter[characterId]?.remove(fieldId)
    }

    /**
     * 장부를 통째로 비운다 — **가져오기 한 번마다 반드시 부른다.**
     * 이 객체는 [ExcelImportService]와 수명이 같은데 그 서비스는 연속 가져오기에서 재사용되고,
     * 두 가져오기 사이에 사용자가 앱에서 값을 고칠 수 있다. 안 비우면 둘째 가져오기가
     * **DB가 아니라 지난번 사본**을 보고 판단한다(같은 파일의 형제 상태들이 전부 그래서 비워진다).
     */
    fun reset() {
        byCharacter.clear()
    }
}
