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

    /** ZIP이 아님 — 구형 .xls 등, POI에 넘겨 판별 */
    NOT_ZIP
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
            ImportFileKind.NOT_ZIP
        }
    }
}
