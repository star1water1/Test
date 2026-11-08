package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Test

class StateChangeFieldNamesTest {

    private fun def(universe: Long?, key: String, name: String) =
        FieldDefinition(universeId = universe, key = key, name = name, type = "TEXT")

    @Test
    fun `special keys are left to the app translation`() {
        assertEquals(
            setOf("job_title"),
            StateChangeFieldNames.customKeys(listOf("__birth", "__alive", "job_title", ""))
        )
    }

    @Test
    fun `single definition gives its name`() {
        assertEquals(
            mapOf("job_title" to "직업/직위"),
            StateChangeFieldNames.unambiguous(listOf(def(1, "job_title", "직업/직위")))
        )
    }

    @Test
    fun `same name across zones still resolves`() {
        assertEquals(
            mapOf("title" to "Title"),
            StateChangeFieldNames.unambiguous(listOf(def(1, "title", "Title"), def(2, "title", " Title ")))
        )
    }

    @Test
    fun `different names for one key stay unresolved`() {
        assertEquals(
            emptyMap<String, String>(),
            StateChangeFieldNames.unambiguous(listOf(def(1, "rank", "Rank"), def(2, "rank", "계급")))
        )
    }
}
