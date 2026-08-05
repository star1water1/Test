package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.CharacterListPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 목록 프리셋 시트의 **고정 열 순서 계약**과 정렬종류 목록의 단일 소스(B-117).
 *
 * 캐릭터 시트와 같은 부류다 — 머리글은 `characterListPresetSpec()`의 순서로 쓰이는데
 * **데이터 셀은 `row.createCell(n)`으로 번호를 손수 매긴다.** 둘이 어긋나도 오류가 나지 않고
 * **그 뒤 열이 통째로 한 칸씩 밀린다**: 정렬오름차순 자리에 축 코드가 들어가고, 그 파일을
 * 되받으면 `Y/N`을 축 코드로 읽는다.
 *
 * B-117이 `정렬필드키` 뒤에 `대결축코드`를 끼워 넣으면서 실제로 그 위험을 만들었다.
 * 순서를 여기 못 박아 두면 다음에 열을 끼우는 사람은 이 테스트가 깨지는 것으로
 * **내보내기 쪽 인덱스도 함께 밀어야 한다는 사실을 알게 된다.**
 */
class ListPresetSpecColumnOrderTest {

    private fun headers(): List<String> = characterListPresetSpec().columns.map { it.header }

    @Test fun fixedColumnOrderIsLocked() {
        assertEquals(
            listOf(
                "이름", "태그(JSON)", "필드필터(JSON)",
                "정렬종류", "정렬필드키", "대결축코드", "정렬오름차순", "신체파트번호",
                "작품코드목록", "기본값", "생성일", "수정일"
            ),
            headers()
        )
    }

    @Test fun duelAxisColumnSitsRightAfterSortFieldKey() {
        val h = headers()
        assertEquals(h.indexOf("정렬필드키") + 1, h.indexOf("대결축코드"))
    }

    /**
     * 드롭다운이 **엔티티의 목록 그대로**여야 한다.
     *
     * 종전에는 리터럴 다섯이 적혀 있었고 가져오기 쪽도 같은 다섯을 따로 나열했다. 그래서
     * 새 정렬이 들어와도 **엑셀에 그 값이 적힌 파일이 조용히 '수동'이 됐다** — 내보낸 파일을
     * 손대지 않고 되들이는 것만으로 정렬이 사라지는 자리다(개발 의도 4번 — 왕복 무결성).
     */
    @Test fun sortKindDropdownComesFromTheEntity() {
        val dropdown = characterListPresetSpec().columns.first { it.header == "정렬종류" }.dropdownOptions.orEmpty()
        assertEquals(CharacterListPreset.SORT_KINDS.toList(), dropdown)
        assertTrue("대결 정렬이 드롭다운에 있어야 사람이 손으로 적을 수 있다",
            dropdown.contains(CharacterListPreset.SORT_DUEL))
    }

    @Test fun everySortKindTheAppCanSaveIsAcceptedBack() {
        // 앱이 저장할 수 있는 종류는 전부 시트가 받아야 한다 — 한쪽만 알면 왕복에서 값이 죽는다.
        val dropdown = characterListPresetSpec().columns
            .first { it.header == "정렬종류" }.dropdownOptions.orEmpty().toSet()
        for (kind in CharacterListPreset.SORT_KINDS) {
            assertTrue("'$kind'가 시트의 유효값에 없다", kind in dropdown)
        }
    }
}
