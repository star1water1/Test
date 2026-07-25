package com.novelcharacter.app.excel

import java.io.File

/**
 * "이미지" 시트 행 → 실제 이미지 경로 해석 규칙의 **단일 소스** (순수 JVM — 단위 테스트 대상).
 *
 * 파일명은 이 시트가 가진 유일한 식별자다. 같은 이미지를 가리키는 행이 둘 이상이면 뒤 행이
 * 앞 행을 덮어써 앞 행의 태그가 통째로 사라지는데, 예전에는 그 사실을 알리지 않았다.
 * 다른 시트의 코드 중복과 **같은 규약(마지막 행 우선 + 고지)** 으로 통일한다.
 */
object ImageMetaRowResolver {

    /** 살아남은 행. [rowIndex]는 시트의 실제 행 번호(0=헤더). */
    data class ResolvedRow(val rowIndex: Int, val fileName: String, val path: String)

    data class Plan(
        /** 경로당 마지막 행 1건씩. 첫 등장 위치 순서를 유지한다. */
        val rows: List<ResolvedRow>,
        /** 해석 실패한 파일명(중복 제거, 첫 등장 순) */
        val unresolved: List<String>,
        val warnings: List<String>
    )

    data class RemapResult(val byBasename: Map<String, String>, val warnings: List<String>)

    /**
     * zip 복원 리맵(원 절대경로 → 새 경로)을 파일명 키로 접는다.
     * basename이 겹치면 파일명만으로 어느 이미지인지 확정할 수 없다 — 임의 last-wins 대신
     * **원경로 사전순 first-wins**로 결정적으로 고르고(복원마다 결과가 흔들리지 않게) 고지한다.
     */
    fun buildRemapByBasename(imagePathRemap: Map<String, String>): RemapResult {
        val byBasename = LinkedHashMap<String, String>()
        val counts = LinkedHashMap<String, Int>()
        for ((origPath, newPath) in imagePathRemap.entries.sortedBy { it.key }) {
            val base = File(origPath).name
            counts[base] = (counts[base] ?: 0) + 1
            if (!byBasename.containsKey(base)) byBasename[base] = newPath
        }
        val warnings = counts.filterValues { it > 1 }.map { (base, count) ->
            "이미지: 파일명 '$base'을(를) 가진 이미지가 백업에 ${count}개 있어 파일명만으로 구별할 수 없습니다 — 태그·링크를 1개에만 복원했습니다"
        }
        return RemapResult(byBasename, warnings)
    }

    /**
     * @param rows (행 번호, 파일명) — 파일명이 빈 행은 호출측에서 제외한다.
     * @param resolveLocal 로컬 filesDir에 같은 파일명이 있으면 절대경로, 없으면 null.
     */
    fun plan(
        rows: List<Pair<Int, String>>,
        remapByBasename: Map<String, String>,
        resolveLocal: (String) -> String?
    ): Plan {
        val byPath = LinkedHashMap<String, ResolvedRow>()
        val unresolved = LinkedHashSet<String>()
        val warnings = mutableListOf<String>()
        for ((rowIndex, fileName) in rows) {
            val path = remapByBasename[fileName] ?: resolveLocal(fileName)
            if (path == null) {
                unresolved.add(fileName)
                continue
            }
            byPath[path]?.let { warnings.add(duplicateWarning(it, rowIndex, fileName)) }
            byPath[path] = ResolvedRow(rowIndex, fileName, path)  // LinkedHashMap: 첫 등장 위치 유지
        }
        return Plan(byPath.values.toList(), unresolved.toList(), warnings)
    }

    /** 같은 파일명이면 타 시트와 자구까지 동일한 문구, 다른 파일명이 같은 경로로 접힌 경우만 분기(사실과 다른 경고 금지). */
    private fun duplicateWarning(prev: ResolvedRow, rowIndex: Int, fileName: String): String =
        if (prev.fileName == fileName) {
            "이미지: 파일명 '$fileName'이(가) 행 ${prev.rowIndex} 과 행 $rowIndex 에 중복됨 (마지막 행 우선)"
        } else {
            "이미지: 행 ${prev.rowIndex}의 '${prev.fileName}'과(와) 행 $rowIndex 의 '$fileName'이(가) 같은 이미지 파일로 해석되어 중복됨 (마지막 행 우선)"
        }
}
