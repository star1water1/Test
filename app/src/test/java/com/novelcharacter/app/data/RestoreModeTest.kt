package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.repository.CharacterRestoreMode
import com.novelcharacter.app.data.repository.RestoreModes
import com.novelcharacter.app.data.repository.StateChangeRestoreMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 복원 의미론 판정 (B-2/B-21).
 *
 * 이 판정이 틀리면 지워진 적 없는 캐릭터가 하나 더 생기거나(종전 결함), 살아 있는 데이터가
 * 동의 없이 덮어써진다. 저장소 안에 두면 실행해 볼 수 없어 둘 다 조용히 성립한다 — R-11에서
 * 배운 그대로 순수 클래스로 떼어 여기서 돌린다.
 */
class RestoreModeTest {

    // ── 캐릭터 ────────────────────────────────────────────────────────────

    @Test
    fun `편집 백업이고 원본이 살아 있으면 되돌리기다`() {
        assertEquals(
            CharacterRestoreMode.REVERT,
            RestoreModes.characterRestoreMode(TrashSnapshot.KIND_EDIT_BACKUP, livingSameCode = true)
        )
    }

    @Test
    fun `편집 백업인데 원본이 사라졌으면 되살린다`() {
        // 그 삭제가 만든 '삭제 백업'은 따로 있으므로 여기서 되살려도 사본이 생기지 않는다.
        assertEquals(
            CharacterRestoreMode.REVIVE,
            RestoreModes.characterRestoreMode(TrashSnapshot.KIND_EDIT_BACKUP, livingSameCode = false)
        )
    }

    @Test
    fun `삭제 백업인데 같은 코드가 살아 있으면 사본으로 되살린다`() {
        // 엑셀 덮어쓰기로 같은 code가 되살아난 경우다. 사용자가 가진 것은 '엑셀에서 온 그것'이고
        // 스냅샷은 '지워진 그것'이라 서로 다른 데이터다 — 덮어쓰면 방금 임포트한 내용을 파괴한다.
        assertEquals(
            CharacterRestoreMode.REVIVE_AS_COPY,
            RestoreModes.characterRestoreMode(TrashSnapshot.KIND_DELETE, livingSameCode = true)
        )
    }

    @Test
    fun `구버전 행은 종류가 없어도 삭제 백업으로 다룬다`() {
        // R-9: operationId·operationKind가 null인 행은 백필하지 않는다 — null이 이미 올바른 의미다.
        assertEquals(
            CharacterRestoreMode.REVIVE_AS_COPY,
            RestoreModes.characterRestoreMode(null, livingSameCode = true)
        )
        assertEquals(
            CharacterRestoreMode.REVIVE,
            RestoreModes.characterRestoreMode(null, livingSameCode = false)
        )
    }

    @Test
    fun `모르는 종류 문자열은 삭제 백업으로 다룬다`() {
        // 뒷 버전이 종류를 늘려도 옛 앱이 그것을 '되돌리기'로 오해해 덮어쓰면 안 된다.
        assertEquals(
            CharacterRestoreMode.REVIVE_AS_COPY,
            RestoreModes.characterRestoreMode("something_new", livingSameCode = true)
        )
    }

    // ── 되돌리기 교체 범위 ────────────────────────────────────────────────

    @Test
    fun `범위를 기록하지 않은 구버전 편집 백업은 필드값만 되돌린다`() {
        // 모르는 것은 되돌리지 않는다 — 덜 되돌리는 것은 고지로 메울 수 있지만
        // 손대지 않은 데이터를 지우는 것은 메울 수 없다.
        assertEquals(setOf(RestoreModes.SCOPE_FIELD_VALUES), RestoreModes.revertScopeOf(null))
    }

    @Test
    fun `경로별 범위가 그대로 해석된다`() {
        assertEquals(
            setOf(RestoreModes.SCOPE_FIELD_VALUES, RestoreModes.SCOPE_MEMBERSHIPS),
            RestoreModes.revertScopeOf(RestoreModes.SCOPE_UNIVERSE_MOVE)
        )
        assertEquals(
            setOf(RestoreModes.SCOPE_FIELD_VALUES, RestoreModes.SCOPE_STATE_CHANGES),
            RestoreModes.revertScopeOf(RestoreModes.SCOPE_LIBRARY_ENTRY_DELETE)
        )
        assertEquals(
            setOf(
                RestoreModes.SCOPE_CHARACTER_ROW,
                RestoreModes.SCOPE_FIELD_VALUES,
                RestoreModes.SCOPE_MEMBERSHIPS
            ),
            RestoreModes.revertScopeOf(RestoreModes.SCOPE_UNIVERSE_MOVE_WITH_ROW)
        )
    }

    @Test
    fun `어떤 경로의 범위에도 태그와 관계는 들어 있지 않다`() {
        // 편집 백업을 만드는 경로 중 태그·관계·사건 연결을 파괴하는 것은 하나도 없다.
        // 범위에 들어가는 순간 되돌리기가 그것을 지우는 새 파괴 경로가 된다.
        val all = RestoreModes.SCOPE_UNIVERSE_MOVE +
            RestoreModes.SCOPE_UNIVERSE_MOVE_WITH_ROW +
            RestoreModes.SCOPE_LIBRARY_ENTRY_DELETE +
            RestoreModes.LEGACY_REVERT_SCOPE
        assertTrue(all.none { it.contains("tag") || it.contains("relationship") || it.contains("event") })
    }

    @Test
    fun `모르는 범위 이름은 버린다`() {
        // 뒷 버전이 추가한 갈래를 옛 앱이 받으면 의미를 모른 채 지우게 된다.
        assertEquals(
            setOf(RestoreModes.SCOPE_FIELD_VALUES),
            RestoreModes.revertScopeOf(listOf(RestoreModes.SCOPE_FIELD_VALUES, "future_scope"))
        )
    }

    @Test
    fun `빈 범위는 아무것도 되돌리지 않는다`() {
        // 파괴한 것이 없다고 기록됐으면 되돌릴 것도 없다 — 빈 목록을 구버전으로 오해하면
        // 기록하지 않은 것과 기록한 것이 같아져 필드값을 멋대로 덮어쓴다.
        assertEquals(emptySet<String>(), RestoreModes.revertScopeOf(emptyList()))
    }

    // ── 이름 은행 링크 (B-3) ──────────────────────────────────────────────

    @Test
    fun `사본을 만드는 복원은 이름 은행 링크를 되붙이지 않는다`() {
        // 원본이 살아서 링크를 쥐고 있는 것이 정상이다. 사본 쪽에서 relink를 돌리면
        // "남이 쓰는 중" 판정이 전부 유실로 집계되어 미리보기(0건)와 어긋난다 —
        // 유령 유실이자 '예상 밖 유실' 거짓 경고다.
        assertTrue(RestoreModes.revivalRelinksNameBank(CharacterRestoreMode.REVIVE))
        assertEquals(false, RestoreModes.revivalRelinksNameBank(CharacterRestoreMode.REVIVE_AS_COPY))
        // 동의 없는 REVERT는 사본 폴백으로 내려간다 — 그때도 되붙이지 않는다.
        assertEquals(false, RestoreModes.revivalRelinksNameBank(CharacterRestoreMode.REVERT))
    }

    // ── 상태변화 ──────────────────────────────────────────────────────────

    @Test
    fun `같은 코드의 이력이 살아 있으면 건너뛴다`() {
        assertEquals(
            StateChangeRestoreMode.SKIP_EXISTING,
            RestoreModes.stateChangeRestoreMode(sameCodeLives = true, sameSlotLives = false)
        )
    }

    @Test
    fun `싱글턴 슬롯이 차 있으면 코드가 달라도 건너뛴다`() {
        // __birth·__death·__alive는 캐릭터당 하나를 전제로 읽힌다 — 두 줄이 되면 그때부터
        // 어느 쪽이 진짜인지 알 수 없다.
        assertEquals(
            StateChangeRestoreMode.SKIP_EXISTING,
            RestoreModes.stateChangeRestoreMode(sameCodeLives = false, sameSlotLives = true)
        )
    }

    @Test
    fun `같은 이력이 없으면 되살린다`() {
        // 일반 필드 키의 다중 이력은 정상이다 — 그것이 '상태변화'다.
        assertEquals(
            StateChangeRestoreMode.INSERT,
            RestoreModes.stateChangeRestoreMode(sameCodeLives = false, sameSlotLives = false)
        )
    }
}
