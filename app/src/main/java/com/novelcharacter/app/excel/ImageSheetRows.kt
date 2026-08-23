package com.novelcharacter.app.excel

/**
 * **'이미지' 시트가 어떤 행을 싣는가** — 내보내기의 선별 (순수 JVM, 시험 대상).
 *
 * ## 왜 라이브러리 표만으로는 모자란가
 *
 * 종전 내보내기는 `image_meta` 표를 그대로 실었다. 그런데 그 표에 행이 생기는 것은
 * **사용자가 그 그림에 무언가를 할 때**다 — 태그를 붙이거나, 묶거나, 떼거나
 * (`ImageMetaDao.adopt`의 KDoc). 자동 링크도 **두 장 이상일 때만** 묶으므로
 * (`이미지` 시트 안내), **그림이 딱 한 장인 캐릭터의 그림은 영영 그 표에 안 들어간다.**
 *
 * 실측(2026.08.23, 사용자가 내보낸 파일):
 *
 * | 이미지 보유 장수 | 캐릭터 수 | 그중 시트에 없던 캐릭터 |
 * |---|---:|---:|
 * | **1장** | 6 | **6 (전부)** |
 * | 3장 이상 | 54 | 0 |
 *
 * 그 6장은 파일 안에 경로로는 실려 있는데(캐릭터 시트의 `이미지경로`) **시트에는 행이 없어
 * 엑셀에서 태그를 줄 방법이 없었다** — 원칙 04가 금지하는 *"일일이 확인하지 않으면 존재를
 * 알 수 없는 데이터"*다. 시트가 1,437행인데 파일이 실제로 참조하는 그림은 1,443장이었다.
 *
 * ## 그래서 합집합을 싣되, 라이브러리에 넣지는 않는다
 *
 * 행이 있다는 것만으로 라이브러리에 편입하지 않는다 — **편입의 진입점은 여전히 태그·링크·뗀
 * 표식이다.** 가져오기는 *아무것도 요구하지 않는 빈 행*을 무시하므로(`asksNothing`) 왕복이
 * 멱등하고, 사용자가 그 행에 태그를 적는 순간 비로소 편입된다.
 *
 * ## 차례
 *
 * 라이브러리 행이 **먼저, 받은 차례 그대로**다 — 이미 내보낸 파일들과 행 차례가 어긋나지
 * 않게 한다. 덧붙는 행만 파일명 순으로 뒤에 붙어, 같은 데이터를 두 번 내보내면 같은 파일이 된다.
 */
object ImageSheetRows {

    /**
     * @property path 앱 내부 절대경로 — 이 행이 가리키는 그림.
     * @property fileName 시트에 적히는 이름(파일명만). 시트의 정체 칸이다.
     * @property inLibrary `image_meta` 행이 있는가. false면 태그·링크·뗀 표식 칸이 비어 나간다.
     */
    data class Row(val path: String, val fileName: String, val inLibrary: Boolean)

    /** 경로에서 시트가 적는 이름 — 절대경로는 기기 간 이식성이 없어 파일명만 싣는다. */
    fun fileNameOf(path: String): String = path.substringAfterLast('/')

    /**
     * @param libraryPaths `image_meta`의 경로, **표가 준 차례 그대로**.
     * @param referencedPaths 캐릭터·세계관·작품이 가리키는 경로 전량.
     *
     * 같은 **파일명**을 이미 라이브러리 행이 쓰고 있으면 덧붙이지 않는다 — 시트의 정체가
     * 파일명이라 두 행이 서로를 덮는다(가져오기가 *마지막 행 우선*으로 접으며 고지한다).
     * 덧붙는 쪽끼리도 파일명이 겹치면 하나만 남는다(경로 순으로 앞선 것).
     */
    fun plan(libraryPaths: List<String>, referencedPaths: Collection<String>): List<Row> {
        val rows = ArrayList<Row>(libraryPaths.size + referencedPaths.size)
        val takenNames = HashSet<String>(libraryPaths.size * 2)
        for (path in libraryPaths) {
            val name = fileNameOf(path)
            rows.add(Row(path, name, inLibrary = true))
            takenNames.add(name)
        }
        val extras = referencedPaths
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy({ fileNameOf(it) }, { it }))
        for (path in extras) {
            val name = fileNameOf(path)
            if (!takenNames.add(name)) continue
            rows.add(Row(path, name, inLibrary = false))
        }
        return rows
    }
}
