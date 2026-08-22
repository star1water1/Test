package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 필드 정의 하나를 지울 때 **상태변화 이력을 어디까지 건드리는가** — 단일 소스.
 *
 * 이력은 필드 id가 아니라 `fieldKey` **문자열**로 필드를 가리키므로 FK CASCADE가 닿지
 * 않는다. 그래서 지우는 쪽이 범위를 손으로 정하는데, **그 범위를 스냅샷도 똑같이 알아야
 * 한다** — 스냅샷이 좁으면 지워진 이력의 일부가 백업에 없고(되살릴 수 없다), 넓으면
 * 지워지지도 않은 남의 이력이 백업에 들어가 복원이 그것을 한 벌 더 만든다(R-33).
 *
 * 규칙은 셋이다:
 * - 캐릭터 필드가 아니면 이력이 없다 — 사건·작품 필드값은 FK CASCADE가 가져간다.
 * - **같은 키의 형제 필드가 하나도 없으면** 그 키의 이력은 전부 이 필드의 것이다
 *   (작품 미배정 캐릭터의 이력까지 포함 — 그쪽은 어느 세계관 조인에도 안 걸린다).
 * - 형제가 남아 있으면 **이 필드의 세계관 안으로만** 좁힌다. 전역 구역(무소속) 필드에
 *   형제가 남은 경우는 지울 것이 없다 — 이력은 연표(세계관)가 만들고 무소속에는 연표가 없다.
 */
object FieldDeleteScope {

    sealed class Scope {
        /** 지울 이력이 없다. */
        object None : Scope()

        /** 그 키의 이력 전부. */
        object AllWithKey : Scope()

        /** 그 세계관 캐릭터의 이력만. */
        data class Universe(val universeId: Long) : Scope()
    }

    fun scopeOf(entityType: String, universeId: Long?, otherFieldsWithSameKey: Int): Scope = when {
        entityType != FieldDefinition.ENTITY_CHARACTER -> Scope.None
        otherFieldsWithSameKey == 0 -> Scope.AllWithKey
        universeId != null -> Scope.Universe(universeId)
        else -> Scope.None
    }
}
