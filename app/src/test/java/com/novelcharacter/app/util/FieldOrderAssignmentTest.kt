package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 새 필드의 표시 순서 배정 — **실제로 겹치던 자리다.**
 *
 * 필드 관리·작품·사건, 세 "새 필드 추가" 다이얼로그가 전부 `displayOrder`를 안 채운 채(기본 0)
 * 필드를 만들어 [com.novelcharacter.app.data.repository.UniverseRepository.insertField]로
 * 넘겼다 — 그 결과 같은 (세계관, 종류) 안에서 두 번째 이후로 만든 필드가 항상 0번을 받아
 * 이미 0번을 쓰는 필드(대개 그 세계관의 첫 필드)와 겹쳤다. 내보낸 파일을 열어 실측했다:
 * `테스트` 세계관에서 사용자가 만든 `frwd`("fgbde") 필드가 `birth_year`와 순서 0을 공유했다.
 */
class FieldOrderAssignmentTest {

    private fun newField(id: Long = 0L, displayOrder: Int = 0) = FieldDefinition(
        id = id,
        universeId = 1L,
        key = "custom_key",
        name = "커스텀",
        type = "TEXT",
        displayOrder = displayOrder
    )

    @Test
    fun `빈 세계관의 첫 필드는 0번을 받는다`() {
        val resolved = FieldOrderAssignment.resolve(newField(), existingMaxOrder = null)
        assertEquals(0, resolved.displayOrder)
    }

    @Test
    fun `기존 필드가 있으면 그 최댓값 다음 칸을 받는다 — 실제로 겹치던 자리`() {
        // birth_year가 이미 0번을 쓰는 세계관에서 사용자가 새 필드를 추가한 경우.
        val resolved = FieldOrderAssignment.resolve(newField(), existingMaxOrder = 0)
        assertEquals(1, resolved.displayOrder)
    }

    @Test
    fun `기존 최댓값이 0이 아니어도 정확히 다음 칸이다`() {
        val resolved = FieldOrderAssignment.resolve(newField(), existingMaxOrder = 27)
        assertEquals(28, resolved.displayOrder)
    }

    @Test
    fun `이미 저장된 필드(id 있음)는 순서를 건드리지 않는다`() {
        val existing = newField(id = 42L, displayOrder = 5)
        val resolved = FieldOrderAssignment.resolve(existing, existingMaxOrder = 99)
        assertSame(existing, resolved)
        assertEquals(5, resolved.displayOrder)
    }
}
