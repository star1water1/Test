package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 새 필드를 심을 때 표시 순서(`displayOrder`)를 정한다 (순수 JVM — 단위 시험 대상).
 *
 * 필드 관리 화면·작품 편집·사건 편집, 세 곳의 "새 필드 추가" 다이얼로그 모두 `FieldDefinition`을
 * `displayOrder`를 채우지 않은 채(기본값 0) 만든다. 그 값을 그대로 심으면 같은 (세계관, 종류)
 * 안에서 이미 0을 쓰는 필드(대개 그 세계관의 첫 필드)와 순서가 겹친다 — 겹친 둘 중 어느 쪽이
 * 먼저 그려질지는 정해져 있지 않고, 내보내기마다 열 순서가 흔들릴 수 있다.
 *
 * 일괄 가져오기·프리셋 병합([com.novelcharacter.app.ui.field.FieldViewModel.importFieldsNow],
 * `applyMergePlan`)은 이미 자기 몫을 스스로 계산해 두므로(기존 최댓값 + 1), 여기서는 **id가
 * 아직 없는 새 필드에만** 개입한다 — 기존 필드를 고치는 호출(id != 0)은 그대로 둔다.
 */
object FieldOrderAssignment {

    /**
     * @param field 심으려는 필드. id == 0L이면 아직 저장되지 않은 새 필드다.
     * @param existingMaxOrder 같은 (세계관, 종류) 안 기존 필드들의 `displayOrder` 최댓값
     *   (행이 하나도 없으면 null — `FieldDefinitionDao.getMaxDisplayOrder*`가 그대로 낸다).
     * @return 새 필드라면 `existingMaxOrder + 1`(없으면 0)로 순서를 새로 매긴 사본,
     *   기존 필드를 고치는 것이라면 손대지 않은 원본.
     */
    fun resolve(field: FieldDefinition, existingMaxOrder: Int?): FieldDefinition =
        if (field.id != 0L) field
        else field.copy(displayOrder = (existingMaxOrder ?: -1) + 1)
}
