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

    /**
     * ZIP도 OLE2도 아니면 **통합문서일 수 없다** — 열어 보고 예외 클래스로 추측하지 않는다.
     *
     * 종전에는 이 부류가 `NOT_ZIP`으로 POI에 넘어갔고, 판정이 열기 실패 뒤의 차단 목록에
     * 맡겨져 **파일 크기가 안내를 갈랐다**(DOM은 평범한 IOException, 스트리밍은
     * NotOfficeXmlFileException을 낸다).
     */
    @Test
    fun `ZIP도 OLE2도 아닌 파일은 통합문서가 아니다`() {
        val file = File.createTempFile("import_format_test", ".xls")
        file.deleteOnExit()
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x00, 0x00, 0x00)) // PK로 시작하지만 ZIP 아님
        assertEquals(ImportFileKind.NOT_WORKBOOK, ImportFileFormat.detect(file))
        val plain = File.createTempFile("import_format_test", ".txt")
        plain.deleteOnExit()
        plain.writeText("not a zip at all")
        assertEquals(ImportFileKind.NOT_WORKBOOK, ImportFileFormat.detect(plain))
    }

    /** OLE2 머리를 단 파일은 구형 .xls일 수 있으므로 **POI에 맡긴다** — 여기서 단정하지 않는다. */
    @Test
    fun `OLE2 머리를 단 파일은 POI 판별로 넘어간다`() {
        val file = File.createTempFile("import_format_test", ".xls")
        file.deleteOnExit()
        file.writeBytes(
            byteArrayOf(
                0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
                0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
            ) + ByteArray(64)
        )
        assertEquals(ImportFileKind.LEGACY_OLE2, ImportFileFormat.detect(file))
    }

    /**
     * 암호로 잠긴 통합문서는 **파일이 스스로 말한다**(OLE2 루트의 `EncryptedPackage`).
     *
     * 종전에는 이 판정이 DOM 갈래에서만 나는 `EncryptedDocumentException`에 걸려 있어,
     * 같은 파일이 커서 스트리밍으로 열리면 "통합문서가 아니다"라는 엉뚱한 안내를 받았다.
     */
    @Test
    fun `암호로 잠긴 통합문서는 크기와 무관하게 암호로 판정된다`() {
        val file = File.createTempFile("import_format_test", ".xlsx")
        file.deleteOnExit()
        org.apache.poi.poifs.filesystem.POIFSFileSystem().use { fs ->
            fs.root.createDocument("EncryptedPackage", java.io.ByteArrayInputStream(ByteArray(32)))
            fs.root.createDocument("EncryptionInfo", java.io.ByteArrayInputStream(ByteArray(16)))
            java.io.FileOutputStream(file).use { out -> fs.writeFilesystem(out) }
        }
        assertEquals(ImportFileKind.ENCRYPTED_WORKBOOK, ImportFileFormat.detect(file))
    }
}
