package com.novelcharacter.app.excel

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 가져오기 진입부의 ZIP 판별 3분기 (S-5).
 * 실제 ZIP 파일로 검증한다 — .xlsx도 .ncworld도 ZIP이라 규칙만 검사하는 테스트는
 * "정체는 엔트리가 정한다"는 계약을 잡지 못한다.
 */
class ImportFileFormatTest {

    private fun tempZip(vararg entryNames: String): File {
        val file = File.createTempFile("import_format_test", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in entryNames) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `data_xlsx 엔트리는 앱 백업 ZIP`() {
        assertEquals(
            ImportFileKind.EXCEL_BACKUP_ZIP,
            ImportFileFormat.detect(tempZip("data.xlsx", "images/1.jpg"))
        )
    }

    @Test
    fun `manifest_json 엔트리는 월드패키지`() {
        assertEquals(
            ImportFileKind.WORLD_PACKAGE,
            ImportFileFormat.detect(tempZip("manifest.json", "universe.json"))
        )
    }

    @Test
    fun `둘 다 있으면 앱 백업이 우선 - 종전 동작 보존`() {
        assertEquals(
            ImportFileKind.EXCEL_BACKUP_ZIP,
            ImportFileFormat.detect(tempZip("data.xlsx", "manifest.json"))
        )
    }

    @Test
    fun `xl_workbook 엔트리는 평범한 xlsx`() {
        assertEquals(
            ImportFileKind.PLAIN_XLSX,
            ImportFileFormat.detect(tempZip("[Content_Types].xml", "xl/workbook.xml"))
        )
    }

    @Test
    fun `아무것도 아닌 ZIP은 OTHER_ZIP - 원인별 안내 대상`() {
        assertEquals(
            ImportFileKind.OTHER_ZIP,
            ImportFileFormat.detect(tempZip("readme.txt"))
        )
    }

    @Test
    fun `ZIP이 아닌 파일은 NOT_ZIP - POI 판별로 넘어간다`() {
        val file = File.createTempFile("import_format_test", ".xls")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x00, 0x00, 0x00)) // PK로 시작하지만 ZIP 아님
        assertEquals(ImportFileKind.NOT_ZIP, ImportFileFormat.detect(file))
        val plain = File.createTempFile("import_format_test", ".txt")
        plain.deleteOnExit()
        plain.writeText("not a zip at all")
        assertEquals(ImportFileKind.NOT_ZIP, ImportFileFormat.detect(plain))
    }
}
