package com.novelcharacter.app.util

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 캐릭터 자동 링크의 DB 적용 계층 — 계획은 [AutoLinkPlanner](순수)가 세우고, 여기서
 * 현재 등록 상태를 읽어 트랜잭션으로 반영한다.
 *
 * 모든 진입점(캐릭터 저장, 이미지 탭 배정/배정 해제, 엑셀·월드 패키지 가져오기, 설정 켜기,
 * 최초 1회 시드)이 같은 **전체 재동기화**를 부른다 — 증분 갱신 대신 전체 불변식을 다시
 * 세우므로 어떤 경로가 어떤 순서로 불려도 상태가 수렴하고, 낡은 토큰(캐릭터 삭제·id 재발급)도
 * 그때그때 치유된다. 비용은 캐릭터·meta 전량 1회 조회 + 차이분 쓰기뿐이라 수백 캐릭터
 * 규모에서도 저장 1회에 얹기 충분하다(받쳐주는 확장성).
 *
 * 자동 링크가 풀린 자동 입양 행(태그·링크 없는 adoptSource='auto')은 함께 반납한다 —
 * 편집창 제거 정책(LIBRARY_ONLY의 "라이브러리는 보존")의 적용 범위가 자동 링크 때문에
 * 조용히 넓어지지 않게 한다. 반납 후 파일 처리는 기존 경로(ImageOwnershipGuard·제거 정책)가
 * 그대로 맡는다.
 */
object CharacterImageAutoLinker {

    /** 재동기화 반영 결과 — 설정 켜기 토스트 등 고지용. */
    data class ResyncResult(val linked: Int, val released: Int) {
        val hasChanges: Boolean get() = linked > 0 || released > 0
    }

    /**
     * 자동 링크 설정이 켜져 있으면 전체 재동기화를 수행한다.
     * @return 반영 결과, 설정이 꺼져 있거나 [context]가 없으면 null(아무것도 하지 않음).
     */
    suspend fun resyncIfEnabled(context: Context?, db: AppDatabase): ResyncResult? {
        val appCtx = context?.applicationContext ?: return null
        if (!ImageSettingsStore(appCtx).getAutoLinkByCharacter()) return null
        return resync(db)
    }

    /**
     * 전체 재동기화 — 설정 게이트 없이 실행(설정 켜는 순간의 1회 정리 등 명시 호출용).
     * 경로 canonical 변환·JSON 해석이 파일 시스템을 만지므로 전체를 IO로 옮긴다
     * (메인 스레드 호출부 — 저장 코디네이터 등 — 가 젱크 없이 부를 수 있게).
     */
    suspend fun resync(db: AppDatabase): ResyncResult = withContext(Dispatchers.IO) {
        val gson = Gson()

        // 경로 정규화의 단일 소스 (B-106 ⓐ) — 실패 시 원본을 들고 간다.
        fun canonical(p: String): String = ImagePathMatch.canonical(p)

        fun parsePaths(json: String): List<String> {
            if (json.isBlank() || json == "[]") return emptyList()
            return runCatching { gson.fromJson<List<String?>>(json, GsonTypes.STRING_LIST) }
                .getOrNull()?.filterNotNull() ?: emptyList()
        }

        // 입력 조립은 트랜잭션 밖(읽기 전용) — 반영은 아래 트랜잭션에서 차이분만 쓴다.
        // canonical 경로로 계획하고, 반영 시 저장형(absolutePath)으로 되돌린다(코드베이스 관례).
        val storedByCanon = HashMap<String, String>()
        fun toCanon(stored: String): String? {
            if (!runCatching { File(stored).exists() }.getOrDefault(false)) return null
            val canon = canonical(stored)
            storedByCanon.putIfAbsent(canon, stored)
            return canon
        }

        val characters = db.characterDao().getAllCharactersList().map { c ->
            c.id to parsePaths(c.imagePaths).mapNotNull { toCanon(it) }
        }
        val metas = db.imageMetaDao().getAllList()
        val metaByCanon = HashMap<String, com.novelcharacter.app.data.model.ImageMeta>(metas.size)
        val states = ArrayList<AutoLinkPlanner.ImageState>(metas.size)
        for (m in metas) {
            val canon = toCanon(m.path) ?: continue   // 소실 파일 행은 판정에서 제외(스테일 정리 몫)
            metaByCanon[canon] = m
            states.add(AutoLinkPlanner.ImageState(canon, m.linkGroupId))
        }

        val plan = AutoLinkPlanner.plan(characters, states)
        if (plan.isEmpty) return@withContext ResyncResult(0, 0)

        var linked = 0
        var released = 0
        db.withTransaction {
            val now = System.currentTimeMillis()
            if (plan.releases.isNotEmpty()) {
                val ids = plan.releases.mapNotNull { metaByCanon[it]?.id }
                SqlInChunks.each(ids) { db.imageMetaDao().setGroup(it, null) }
                released += ids.size
            }
            for ((token, canonPaths) in plan.claims) {
                val ids = canonPaths.map { canon ->
                    metaByCanon[canon]?.id
                        ?: db.imageMetaDao().adoptAuto(storedByCanon[canon] ?: canon, now)
                }
                SqlInChunks.each(ids) { db.imageMetaDao().setGroup(it, token) }
                linked += ids.size
            }
            // 토큰마다 돌던 정리를 일괄판 하나로 접는다 (B-241). 읽기가 없는 순수 쓰기 루프라
            // 겹이 필요 없고, 묶음이 행을 나눠 가지므로 갈라 불러도 답이 같다
            // (근거의 단일 소스는 `clearSingletonGroups`의 주석 — B-239).
            SqlInChunks.each(plan.dirtyAutoTokens) { db.imageMetaDao().clearSingletonGroups(it) }
            // 링크만을 위해 자동 입양됐던 행은 링크가 풀리면(해제·singleton 정리 모두) 반납한다.
            // 태그가 붙었거나 사용자 행으로 승격된 것은 남는다.
            db.imageMetaDao().sweepBareAutoAdopted()
        }
        ResyncResult(linked, released)
    }
}
