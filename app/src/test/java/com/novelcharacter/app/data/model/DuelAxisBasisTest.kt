package com.novelcharacter.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기준 축 표식의 **판정 단일 소스** — `DuelAxis.isImageBasis` (B-104 소비처 ⓑ·ⓒ).
 *
 * ## 왜 이 한 줄에 시험이 붙는가 — 콜드 검토가 잡은 실결함이 여기서 났다
 *
 * 첫 구현은 유일성을 지키는 자리에서 **`isBasisAxis`만** 봤다(`saveAxis` · 엑셀 가져오기).
 * 그런데 표식을 *읽는* 자리는 전부 **이미지 축만** 찾으므로, 캐릭터 축에 켜진 값은
 * *아무 일도 하지 않아야 하는 값*이다. 두 범위가 갈리자 실제로는 **거꾸로** 동작했다:
 *
 * - `대결 축` 시트는 **모든 축 행에** Y/N 드롭다운을 싣는다. 캐릭터 축 행에 `Y`를 한 칸
 *   적어 들이면 그것이 형제를 내려 **살아 있는 이미지 축의 기준이 조용히 풀렸다.**
 *   다음 내보내기는 그 축에 `N`을 적으므로 **파일에서 되살릴 길도 사라진다.**
 * - 그 상태에서 축 목록을 **드래그해 순서만 바꿔도** 같은 일이 났다(`saveAxis`가 축마다
 *   불린다). 사용자가 한 일과 결과 사이에 아무 연결이 없어 원인을 찾을 수 없는 부류다.
 *
 * 처방은 *"판정을 한 줄로 모으고 쓰기가 그것을 본다"*였고, **그 한 줄이 이 시험의 대상이다.**
 * 쓰기 경로(저장소·가져오기·복원)는 Room을 타서 순수 JVM이 닿지 않으므로
 * `tools/verify_room_migration_56.py`가 소스로 그 셋을 함께 본다 — 여기와 한 벌이다.
 */
class DuelAxisBasisTest {

    private fun axis(target: String, basis: Boolean) = DuelAxis(
        id = 1L,
        universeId = 7L,
        name = "아름다움",
        targetType = target,
        isBasisAxis = basis,
        code = "DAX-1"
    )

    @Test
    fun `이미지 축에 켜져 있어야 기준이다`() {
        assertTrue(axis(DuelAxis.TARGET_IMAGE, basis = true).isImageBasis)
    }

    @Test
    fun `캐릭터 축의 표식은 기준이 아니다 — 값은 남되 아무 일도 하지 않는다`() {
        val stray = axis(DuelAxis.TARGET_CHARACTER, basis = true)
        assertFalse("이 한 줄이 엑셀 한 칸에 기준이 풀리는 것을 막는다", stray.isImageBasis)
        // **값을 지우지는 않는다** — 사용자가 적은 것을 버리지 않는다(엔티티 KDoc의 약속).
        assertTrue("표식 자체는 그대로 남아 엑셀 왕복을 견딘다", stray.isBasisAxis)
    }

    @Test
    fun `꺼져 있으면 이미지 축이어도 기준이 아니다`() {
        assertFalse(axis(DuelAxis.TARGET_IMAGE, basis = false).isImageBasis)
    }

    @Test
    fun `기본값은 꺼짐이다 — 소급해서 기준을 지어 주지 않는다`() {
        // 마이그레이션 56이 DEFAULT 0을 주는 것과 한 벌이다(기존 축은 전부 여기 선다).
        val fresh = DuelAxis(universeId = 7L, name = "강함")
        assertFalse(fresh.isBasisAxis)
        assertFalse(fresh.isImageBasis)
    }
}
