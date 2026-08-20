package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐릭터 시트의 **고정 열 순서 계약**.
 *
 * 이 테스트가 있는 이유: 머리글 행은 `characterSpec`의 순서로 쓰이는데
 * (`ExcelExporter.writeHeaderRow`가 `spec.columns`를 돈다) **데이터 셀은 `col++`로 손수
 * 센다.** 둘이 어긋나면 오류가 나지 않고 **그 뒤 열이 통째로 한 칸씩 밀린다** — 메모 자리에
 * 작품이, 코드 자리에 태그가 들어가고, 그 파일을 되받으면 값이 엉뚱한 칸으로 들어간다.
 *
 * 실제로 B-103이 `이미지경로` 뒤에 `대표이미지`를 끼워 넣으면서 이 위험을 한 번 만들었다.
 * 순서를 여기 못 박아 두면, 다음에 열을 끼우는 사람은 이 테스트가 깨지는 것으로
 * **내보내기 쪽도 함께 고쳐야 한다는 사실을 알게 된다.**
 */
class CharacterSpecColumnOrderTest {

    private fun headers(): List<String> =
        characterSpec(fields = emptyList(), novelTitles = emptyList()).columns.map { it.header }

    @Test fun fixedColumnOrderIsLocked() {
        // 커스텀 필드가 없을 때는 고정 열만 남는다 — 그 순서가 곧 내보내기의 `col++` 순서다.
        assertEquals(
            listOf(
                "이름", "성", "이름(First)", "이명",
                "이미지경로", "대표이미지",
                "작품", "메모", "태그", "코드", "작품코드", "정렬순서", "고정", "생성일"
            ),
            headers()
        )
    }

    @Test fun representativeColumnSitsRightAfterImagePaths() {
        val h = headers()
        assertEquals(h.indexOf("이미지경로") + 1, h.indexOf("대표이미지"))
    }

    @Test fun everyFixedHeaderIsDeclaredInTheSharedSet() {
        // `CHARACTER_FIXED_HEADERS`는 가져오기 해석과 커스텀 필드 충돌 판정이 함께 쓴다.
        // 여기 빠진 열은 **커스텀 필드 열로 오인**되어 값이 엉뚱한 곳으로 들어간다.
        for (header in headers()) {
            assertTrue("'$header'이 CHARACTER_FIXED_HEADERS에 없다", header in CHARACTER_FIXED_HEADERS)
        }
    }

    @Test fun representativeColumnResolvesThroughAliases() {
        // 다른 기기·다른 판이 쓴 이름으로도 같은 열에 닿아야 한다(왕복 수용).
        for (alias in listOf("대표이미지", "대표 이미지", "representative_image", "대표이미지경로")) {
            assertEquals("별칭 '$alias'", "대표이미지", ExcelHeaderAliases.canonical(alias))
        }
    }

    @Test fun representativeColumnIsNotReadOnly() {
        // 사람이 고쳐 넣는 열이다 — 잠그면 D8의 값어치가 사라진다.
        val col = characterSpec(emptyList(), emptyList()).columns.first { it.header == "대표이미지" }
        assertTrue("사람이 고칠 수 있어야 한다", !col.readOnly)
    }

    @Test fun imagePathsColumnIsNotReadOnly() {
        // 가져오기가 이 열을 읽어 반영하고(B-222 WD-6) 사용 안내도 그렇게 말한다 —
        // 회색(readOnly)으로 잠그면 범례("그대로 두세요")가 그 편집 능력을 가린다.
        // 세계관·작품 시트의 같은 열과 한 규약이다.
        val col = characterSpec(emptyList(), emptyList()).columns.first { it.header == "이미지경로" }
        assertTrue("편집이 반영되는 열은 회색으로 잠그지 않는다", !col.readOnly)
    }
}
