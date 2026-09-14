package com.novelcharacter.app.ai

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.excel.AppSettingsBindings
import com.novelcharacter.app.excel.AppSettingsKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real preferences, app-settings bindings and XLSX cells; isolated from the user's settings. */
@RunWith(AndroidJUnit4::class)
class PromptSettingsCompatibilityTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val contexts = mutableListOf<IsolatedContext>()
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val names = mutableSetOf<String>()
        private val prefix = "m6-test-${UUID.randomUUID()}-"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return super.getSharedPreferences(isolated, mode)
        }
    }
    private fun isolated() = IsolatedContext(base).also { contexts.add(it) }
    @After fun removeOnlyTestPreferences() {
        contexts.flatMap { it.names }.forEach { base.deleteSharedPreferences(it) }
    }

    @Test fun legacyCustomTemplatesKeepExactStoredTextAcrossSettingsAndXlsx() = runBlocking {
        val source = isolated(); val destination = isolated()
        val settings = AiPromptSettings(source)
        val ids = listOf(PromptTemplates.Id.CHAR_FIELD_SYSTEM, PromptTemplates.Id.CHAR_FIELD_USER,
            PromptTemplates.Id.EVENT_FIELD_SYSTEM, PromptTemplates.Id.EVENT_FIELD_USER)
        val originals = ids.associateWith { id ->
            // Uses the existing required placeholders; no origin placeholder or stored-template migration.
            "사용자 고유 문체\n" + PromptTemplates.default(id) + "\n끝의 정정: 확정하지 마라."
        }
        for ((id, text) in originals) {
            assertTrue(settings.setTemplate(id, text).isEmpty())
            assertEquals(text, settings.storedTemplate(id))
        }
        val templateKeys = ids.map { AppSettingsKeys.templateSpecOf(it).key }.toSet()
        val bindings = AppSettingsBindings.exported(false).filter { it.spec.key in templateKeys }
        assertEquals(4, bindings.size)
        val bytes = ByteArrayOutputStream()
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("앱 설정")
            for ((index, binding) in bindings.withIndex()) {
                val row = sheet.createRow(index)
                row.createCell(0).setCellValue(binding.spec.key)
                row.createCell(1).setCellValue(binding.read(source))
            }
            workbook.write(bytes)
        }
        XSSFWorkbook(ByteArrayInputStream(bytes.toByteArray())).use { workbook ->
            for (row in workbook.getSheetAt(0)) {
                val binding = requireNotNull(AppSettingsBindings.bindingOf(row.getCell(0).stringCellValue))
                assertEquals(AppSettingsBindings.Applied.Yes, binding.write(destination, row.getCell(1).stringCellValue))
            }
        }
        val restored = AiPromptSettings(destination)
        for ((id, text) in originals) {
            assertEquals(text, restored.storedTemplate(id))
            assertEquals(text, restored.asTemplateSource().templateOf(id))
            assertEquals(text, settings.storedTemplate(id))
        }
        assertTrue(bindings.none { it.spec.key.contains("receipt") || it.spec.key.contains("transcript") })
    }
}
