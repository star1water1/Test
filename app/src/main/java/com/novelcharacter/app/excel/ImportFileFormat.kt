package com.novelcharacter.app.excel

import com.novelcharacter.app.share.WorldPackageEntries
import java.io.File
import java.util.zip.ZipFile

/**
 * 가져오기 진입부의 파일 형식 판별 (S-5 — ZIP 판별 3분기).
 *
 * .xlsx도 월드패키지도 내부적으로는 ZIP이므로 매직바이트만으로는 구분할 수 없다 —
 * **정체는 내용(엔트리)이 정한다** (R-7의 시트 판별과 같은 취지).
 * 종전에는 data.xlsx 유무만 봤기 때문에 .ncworld를 고르면 xlsx로 오판되어
 * POI 파싱 실패 → "가져오기에 실패했습니다" 일반 오류만 떴다.
 *
 * 순수 로직(java.util.zip) — 순수 JVM 하네스가 실제 ZIP으로 실행 검증한다.
 */
enum class ImportFileKind {
    /** 앱의 이미지 포함 엑셀 백업 ZIP (data.xlsx 엔트리 보유) */
    EXCEL_BACKUP_ZIP,

    /** 월드패키지 .ncworld (manifest.json 엔트리 보유) */
    WORLD_PACKAGE,

    /** 평범한 .xlsx (xl/workbook.xml 엔트리 보유) */
    PLAIN_XLSX,

    /** ZIP이지만 셋 다 아님 — 지원하지 않는 형식으로 원인별 안내 대상 */
    OTHER_ZIP,

    /** OLE2 컨테이너 — 구형 .xls 등. POI에 넘겨 판별한다(종전 `NOT_ZIP`의 참뜻). */
    LEGACY_OLE2,

    /**
     * OLE2 컨테이너인데 **암호로 잠긴 통합문서**(`EncryptedPackage` 엔트리).
     *
     * 종전에는 이 판정이 파일이 아니라 *어느 여는 경로를 탔는가*에 붙어 있었다 —
     * 암호 판별이 `EncryptedDocumentException` 하나에 걸려 있고 그 예외는 DOM 갈래에서만
     * 나오므로, **파일 크기라는 무관한 축이 문구를 갈랐다**(큰 파일은 스트리밍으로 열려
     * "통합문서가 아니다"라는 엉뚱한 안내를 받았다).
     */
    ENCRYPTED_WORKBOOK,

    /**
     * ZIP도 OLE2도 아니다 — **통합문서일 수 없다.**
     *
     * 종전에는 이 부류가 `NOT_ZIP`에 섞여 POI로 넘어갔고, 판정이 열기 실패 뒤의
     * *예외 클래스 차단 목록*에 맡겨져 있었다. POI는 여는 경로마다 다른 예외를 내므로
     * (DOM은 평범한 `IOException`, 스트리밍은 `NotOfficeXmlFileException`) 그 목록이
     * 앞엣것을 몰라 **통상 크기의 파일에서만 판정이 뒤집혔다.**
     */
    NOT_WORKBOOK
}

object ImportFileFormat {

    fun detect(file: File): ImportFileKind {
        return try {
            ZipFile(file).use { zip ->
                when {
                    // 순서 주의: 앱 백업 판정이 먼저다 — 종전 동작(백업 ZIP 우선)을 보존한다
                    zip.getEntry("data.xlsx") != null -> ImportFileKind.EXCEL_BACKUP_ZIP
                    zip.getEntry(WorldPackageEntries.MANIFEST) != null -> ImportFileKind.WORLD_PACKAGE
                    zip.getEntry("xl/workbook.xml") != null -> ImportFileKind.PLAIN_XLSX
                    else -> ImportFileKind.OTHER_ZIP
                }
            }
        } catch (_: Exception) {
            // ZIP이 아니다 — **여기서 한 번 더 파일에 묻는다.** 열기 실패 뒤의 예외 클래스로
            // 추측하면 여는 경로(=파일 크기)가 답을 가른다.
            detectNonZip(file)
        }
    }

    /**
     * ZIP이 아닌 파일의 정체 — OLE2 컨테이너인가, 그 안이 암호 통합문서인가, 아무것도 아닌가.
     *
     * 매직바이트는 직접 본다(의존을 늘리지 않는다). OLE2 안의 `EncryptedPackage` 유무만
     * POI의 POIFS로 확인한다 — 디렉터리 구조를 손으로 파싱하는 것은 이 판정이 질 비용이 아니다.
     */
    private fun detectNonZip(file: File): ImportFileKind {
        if (!isOle2(file)) return ImportFileKind.NOT_WORKBOOK
        return try {
            org.apache.poi.poifs.filesystem.POIFSFileSystem(file).use { fs ->
                // OOXML을 암호화하면 OLE2 컨테이너 루트에 이 엔트리가 선다(표준 이름).
                if (fs.root.hasEntry("EncryptedPackage")) ImportFileKind.ENCRYPTED_WORKBOOK
                else ImportFileKind.LEGACY_OLE2
            }
        } catch (_: Exception) {
            // OLE2 머리는 맞는데 안을 못 읽었다 — 구형 .xls일 수 있으니 POI에 맡긴다
            // (종전 처분 그대로다. 여기서 '아니다'로 단정하지 않는다 — B-225).
            ImportFileKind.LEGACY_OLE2
        }
    }

    /** OLE2 복합 문서의 앞 8바이트. */
    private val OLE2_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )

    private fun isOle2(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(OLE2_MAGIC.size)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            read == head.size && head.contentEquals(OLE2_MAGIC)
        }
    } catch (_: Exception) {
        false
    }
}
