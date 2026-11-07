package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenderFieldResolverTest {

    private fun field(id: Long, key: String, config: String = "{}") =
        FieldDefinition(id = id, universeId = 1L, key = key, name = key, type = "SELECT", config = config)

    @Test
    fun `role wins over the fallback key`() {
        val fields = listOf(
            field(1, "gender", """{"options":["남","여"]}"""),
            field(2, "sex", """{"options":["M","F"],"semanticRole":"gender"}""")
        )
        assertEquals(2L, GenderFieldResolver.pick(fields)?.id)
    }

    @Test
    fun `preset gender field without a role is still found by key`() {
        // 프리셋 템플릿이 만드는 모양 그대로 — 역할이 없다 (S5 10-ㄱ 실패의 재현)
        val fields = listOf(
            field(1, "age", """{"semanticRole":"age"}"""),
            field(5, "gender", """{"options":["남","여","?"]}""")
        )
        assertEquals(5L, GenderFieldResolver.pick(fields)?.id)
    }

    @Test
    fun `first role field wins when two carry the role`() {
        val fields = listOf(
            field(3, "a", """{"semanticRole":"gender"}"""),
            field(4, "b", """{"semanticRole":"gender"}""")
        )
        assertEquals(3L, GenderFieldResolver.pick(fields)?.id)
    }

    @Test
    fun `no role and no gender key gives null`() {
        assertNull(GenderFieldResolver.pick(listOf(field(1, "race"), field(2, "sexuality"))))
    }
}
