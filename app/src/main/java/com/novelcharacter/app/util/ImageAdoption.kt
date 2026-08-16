package com.novelcharacter.app.util

import androidx.room.withTransaction
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.ImageMeta

/**
 * 입양(insert-or-get)의 **일괄판** — [ImageMetaDao.adopt]/[ImageMetaDao.adoptAuto]를 경로마다
 * 부르던 자리를 목록 하나로 내린다 (B-244).
 *
 * 단수판은 행이 이미 있으면 **왕복 셋**(`insert` 실패 → `promoteToUserByPaths` → `getByPath`)이고,
 * 게다가 `@Transaction`이라 **경로마다 트랜잭션이 하나씩** 열린다. 이 통로는 목록 길이와
 * 무관하게 **읽기 하나 · 삽입 하나 · 승격 하나**를 한 트랜잭션 안에서 친다.
 *
 * **왜 DAO가 아니라 `util`인가.** `IN (:paths)` 질의를 쓰므로 [SqlInChunks]를 지나야 하는데
 * (R-54), 그것은 `util`이고 **`data/dao/`가 `util`을 import 하는 전례가 없다**(2026.08.16 실측 0 —
 * DAO의 KDoc이 `util`을 FQN으로 *가리키기만* 하는 자리는 있어도 의존은 없다). 계층을 뒤집는
 * 대신 통로를 `util`에 두고, DAO에는 통로가 필요 없는 조각([ImageMetaDao.insertAll])만 더했다.
 *
 * **판정은 여기 없다** — 무엇이 새 행이고 무엇이 승격 대상인가는 [ImageAdoptionPlanner]가 정하고
 * 이 파일은 그 답을 DB에 옮긴다. 그 갈라놓음이 방어선이다: 이 파일은 실클래스패스 프로브가
 * 타입 검사할 뿐이고, **답이 옛 루프와 같은지는 순수 시험(`ImageAdoptionPlannerTest`)이 잰다.**
 *
 * 계약:
 * - **호출부가 이미 트랜잭션 안이어도 된다.** Room의 `withTransaction`은 겹쳐 열면 바깥 것에
 *   합류하므로(이 저장소가 이미 기대는 성질이다 —
 *   [com.novelcharacter.app.data.repository.GradeSystemRepository.propagate]의 주석이 같은 말을
 *   한다), 단수판의 `@Transaction`이 주던 원자성이 그대로다. **안쪽이 던지면 바깥까지 롤백되는
 *   것도 그 성질이고, 그것이 이 통로가 바라는 바다** — 입양이 실패했는데 뒤따르는 태그·링크만
 *   들어가면 안 된다.
 * - **하나라도 id를 못 얻으면 던진다.** 옛 단수판이 `requireNotNull(getByPath(path))`로 던지던
 *   바로 그 자리다. 규칙을 호출부마다 따로 두지 않는 것이 요점이다 — 답에서 **조용히 빠지게**
 *   두면 뒤따르는 태그·링크가 그 경로만 건너뛴 채 *성공*이라 보고하고, 그것이 개발 의도 2번이
 *   금지하는 무통보 유실이다. 던지면 호출부의 기존 실패 경로(트랜잭션 롤백·실패 고지)가
 *   **글자 그대로 그대로 작동한다.**
 */
object ImageAdoption {

    /**
     * 사용자 행위로서의 입양 — [ImageMetaDao.adopt]의 일괄판.
     *
     * 이미 있던 행은 **사용자 소유로 승격한다**(`adoptSource = null`). 빠뜨리면 자동 반납
     * ([ImageMetaDao.sweepBareAutoAdopted])이 **사용자가 태그를 붙인 행을 지운다.**
     *
     * @return 경로 → 행 id. 순서는 [paths]의 순서(중복은 첫 자리에서 접힌다).
     */
    suspend fun adoptAll(db: AppDatabase, paths: Collection<String>, now: Long): Map<String, Long> =
        run(db, paths, now, promote = true, adoptSource = null)

    /**
     * 자동 링크 전용 입양 — [ImageMetaDao.adoptAuto]의 일괄판.
     *
     * 새 행만 자동 소유(`adoptSource = 'auto'`)로 만들고 **기존 행의 소유는 건드리지 않는다** —
     * 자동화는 사용자 의사 표시가 아니다(그 함수의 판단 그대로).
     */
    suspend fun adoptAllAuto(db: AppDatabase, paths: Collection<String>, now: Long): Map<String, Long> =
        run(db, paths, now, promote = false, adoptSource = ImageMeta.ADOPT_SOURCE_AUTO)

    private suspend fun run(
        db: AppDatabase,
        paths: Collection<String>,
        now: Long,
        promote: Boolean,
        adoptSource: String?
    ): Map<String, Long> {
        if (paths.isEmpty()) return emptyMap()
        return db.withTransaction {
            // ① 이미 있는 행 — 경로마다 묻던 `getByPath`가 여기 하나로 접힌다.
            // **접는 규칙은 [ImageAdoptionPlanner.wanted] 하나다** — 여기서 따로 접으면
            // *무엇을 물었는가*와 *무엇을 답에 세우는가*가 갈릴 수 있다.
            val wanted = ImageAdoptionPlanner.wanted(paths)
            val existing = SqlInChunks.flat(wanted) { db.imageMetaDao().getByPaths(it) }
            val split = ImageAdoptionPlanner.split(wanted, existing, promote)

            // ② 없는 것만 넣는다. `IN` 절이 아니라 바인드 상한과 무관하므로 통로를 지나지 않는다.
            val rowIds =
                if (split.fresh.isEmpty()) emptyList()
                else db.imageMetaDao().insertAll(
                    split.fresh.map { ImageMeta(path = it, importedAt = now, adoptSource = adoptSource) }
                )

            // ③ 경합분만 다시 묻는다 — 없으면 질의도 없다(흔한 경우의 비용은 0이다).
            val raced = ImageAdoptionPlanner.raced(split, rowIds)
            val recovered =
                if (raced.isEmpty()) emptyList()
                else SqlInChunks.flat(raced) { db.imageMetaDao().getByPaths(it) }

            val resolved = ImageAdoptionPlanner.resolve(split, rowIds, recovered)
            // 옛 단수판의 `requireNotNull`과 같은 자리 — 조용히 빠뜨리지 않는다(위 계약).
            if (resolved.unresolved.isNotEmpty()) {
                throw IllegalStateException(
                    "이미지 입양 실패 — 행을 얻지 못한 경로 ${resolved.unresolved.size}건"
                )
            }

            // ④ 승격 — 이미 있던 행에만. `adoptAll`에서만 목록이 찬다.
            if (resolved.promotePaths.isNotEmpty()) {
                SqlInChunks.each(resolved.promotePaths) { db.imageMetaDao().promoteToUserByPaths(it) }
            }
            resolved.idByPath
        }
    }
}
