package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.FieldDeleteScope.Scope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FieldDeleteScope] — 지우는 범위와 담는 범위가 **같은 함수에서 나온다**(R-33).
 *
 * 이 하네스가 지키는 것: 스냅샷이 좁으면 지워진 이력을 되살릴 수 없고, 넓으면 지워지지도
 * 않은 남의 이력이 백업에 들어가 복원이 그것을 한 벌 더 만든다.
 */
class FieldDeleteScopeTest {

    private fun scope(
        entityType: String = FieldDefinition.ENTITY_CHARACTER,
        universeId: Long? = 1L,
        others: Int = 0
    ) = FieldDeleteScope.scopeOf(entityType, universeId, others)

    @Test
    fun `사건 작품 필드에는 이력이 없다`() {
        assertEquals(Scope.None, scope(entityType = FieldDefinition.ENTITY_EVENT))
        assertEquals(Scope.None, scope(entityType = FieldDefinition.ENTITY_NOVEL))
    }

    @Test
    fun `형제가 없으면 그 키의 이력 전부다`() {
        // 작품 미배정 캐릭터의 이력은 어느 세계관 조인에도 안 걸린다 — 전부여야 그것까지 담긴다.
        assertEquals(Scope.AllWithKey, scope(others = 0))
        assertEquals(Scope.AllWithKey, scope(universeId = null, others = 0))
    }

    @Test
    fun `형제가 남으면 그 세계관 안으로 좁힌다`() {
        assertEquals(Scope.Universe(7L), scope(universeId = 7L, others = 1))
    }

    @Test
    fun `전역 구역에 형제가 남으면 지울 것이 없다`() {
        assertEquals(Scope.None, scope(universeId = null, others = 2))
    }
}
