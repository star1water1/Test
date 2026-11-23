package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel

/**
 * **캐릭터 폼이 그릴 필드 목록 — 어느 목록인가의 단일 소스** (R-33).
 *
 * ## 왜 ViewModel에서 꺼냈는가
 *
 * 이 판정은 2026.08.22에 `CharacterViewModel.fieldsForNovel`로 **처음 한 자리에 모였다.**
 * 그때의 근거가 그 함수의 KDoc에 남아 있고, 읽어 둘 값어치가 있다 — 판정이 두 화면에 손으로
 * 두 벌 적혀 있었고 **실제로 갈렸다**: 편집 화면은 무소속 캐릭터에게 전역 필드를 줬는데
 * 보충(랜덤) 탭은 `emptyList()`였다. 그래서 어시스턴트의 *'필수 빈칸 N'* 카드를 보고 들어온
 * 사용자가 **빈 화면**을 만났다. 그 KDoc이 맺은 말이 이것이다 —
 * **"적어 두는 것으로는 안 지켜진다."**
 *
 * PC 동반 편집(`docs/pc_companion_design_2026-11.md`)이 **세 번째 창구**를 연다. 서버는
 * `AndroidViewModel`을 만들 수 없으니, 그 자리에서 목록을 다시 고르면 갈림이 한 번 더
 * 생긴다 — 같은 결함의 세 번째 판이다. 그래서 판정을 화면 밖으로 옮기고 세 창구가 전부
 * 이것을 부른다. `CharacterViewModel`은 위임만 한다.
 *
 * **동작은 바뀌지 않았다** — 옮긴 것이고 고친 것이 아니다.
 */
class CharacterFormFields(
    private val universes: UniverseRepository,
    private val templates: DefaultFieldTemplateRepository
) {

    /**
     * 전역 구역(무소속)의 필드 — 세계관 소속이 없는 캐릭터의 폼이 쓴다
     * (B-119 확장, 2026.08.07 사용자 확정: *"전역필드라면 세계관 소속이 없더라도 가지게"*).
     * 템플릿은 있는데 그림자가 없으면 저장소가 심는다.
     */
    suspend fun globalFields(): List<FieldDefinition> = templates.globalFields()

    /** 한 세계관의 캐릭터 필드. */
    suspend fun byUniverse(universeId: Long): List<FieldDefinition> =
        universes.getFieldsByUniverseList(universeId)

    /**
     * 고른 작품의 폼이 그릴 목록.
     *
     * **세계관 없는 작품과 작품 미선택이 같은 갈래다** — 둘 다 전역 구역을 받는다.
     * `novel?.universeId`가 `null`을 내는 길이 둘이라는 사실이 이 한 줄에 접혀 있다.
     *
     * @param novel 고른 작품. `null`이면 작품 미선택(완전 무소속)이다.
     */
    suspend fun forNovel(novel: Novel?): List<FieldDefinition> {
        val universeId = novel?.universeId ?: return globalFields()
        return byUniverse(universeId)
    }
}
