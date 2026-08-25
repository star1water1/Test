package com.novelcharacter.app.data.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **마이그레이션을 실제 Room으로 밟는다** (B-9).
 *
 * ## 왜 이 시험이 필요한가 — 로컬 하네스가 원리적으로 못 보는 축이 있다
 *
 * `tools/verify_room_migration*.py` 열다섯은 **소스만 읽는다.** 그것이 잡는 것은
 * *"이 마이그레이션이 적어야 할 SQL을 적었는가"*이고, 잡지 못하는 것은 *"그 SQL이 실제
 * SQLite에서 도는가"*와 **가장 중요한 것 — *"결과 스키마가 Room이 기대하는 것과 같은가"*** 다.
 *
 * 뒤엣것이 무서운 이유는 실패의 모양 때문이다. Room은 열 때 **엔티티에서 계산한
 * `identityHash`**를 DB에 적힌 것과 견주고, 다르면 그 자리에서 던진다. 즉 증상은
 * *"업데이트한 사용자의 앱이 실행되자마자 죽는다"*이고, **그때는 이미 사용자 기기다.**
 * `docs/remaining_work_2026-07.md` 3장의 *"실기기 미검증"*이 사용자에게 남는 이유가 정확히
 * 이것이었다 — CI에 실제 마이그레이션 검증이 없었다.
 *
 * ## 스키마 JSON이 커밋돼 있어야 돈다
 *
 * `room.schemaLocation`이 켜져 있어 빌드마다 `app/schemas/`에 JSON이 나오지만 **커밋되지
 * 않고 버려지고 있었다.** 이 시험은 그것을 assets에서 읽으므로(`build.gradle.kts`의
 * `androidTest` 소스셋), **커밋이 곧 이 시험의 재료다.**
 *
 * **손으로 적지 않는다.** JSON에는 Room이 계산한 `identityHash`가 들어 있어, 한 글자라도
 * 어긋나면 Room이 거부한다 — 손으로 맞추려 드는 것 자체가 방편식 패치다. 빌드 산출물을
 * 그대로 커밋한다(워크플로가 아티팩트로 올려 준다).
 *
 * ## 세 가지를 본다
 *
 * ⓐ **현재 버전의 스키마가 커밋돼 있고 코드와 맞는가** — 스키마 파일 하나만 있어도 돈다.
 *   버전을 올리고 스키마 커밋을 잊는 것이 이 부류의 가장 흔한 사고이고, 그것을 여기서 잡는다.
 * ⓑ **커밋된 이웃 버전 사이의 마이그레이션이 실제로 도는가** — 쌍이 생기는 대로 자동으로 는다.
 * ⓒ **마이그레이션 목록에 구멍이 없는가** — 1→현재가 한 칸씩 이어지는가.
 *   [AppDatabase.ALL_MIGRATIONS]가 앱과 이 시험의 **단일 소스**라 두 벌이 갈릴 자리가 없다.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** assets에 실제로 들어온 스키마 버전들 — 손으로 적지 않는다(적으면 낡는다). */
    private fun committedVersions(): List<Int> {
        val dir = AppDatabase::class.java.canonicalName!!
        val names = InstrumentationRegistry.getInstrumentation().context.assets.list(dir).orEmpty()
        return names.mapNotNull { it.removeSuffix(".json").toIntOrNull() }.sorted()
    }

    /**
     * ⓐ **버전을 올리고 스키마 커밋을 잊지 않았는가.**
     *
     * 커밋된 최신 스키마로 DB를 만든 뒤 **진짜 Room으로 연다.** 엔티티가 그 사이에 바뀌었는데
     * 스키마를 다시 커밋하지 않았다면 `identityHash`가 어긋나 여기서 던진다 —
     * **이 한 시험이 B-9이 막으려던 사고의 대부분을 덮는다.**
     *
     * ## 한 판 뒤처짐을 받는 이유 (2026.08.25)
     *
     * 아래 버전 대조는 **세울 수 없는 것을 요구하고 있었다.** 스키마 JSON은 손으로 못 적고
     * (`identityHash`), 만드는 것은 `build-apk.yml`의 Gradle 빌드이며, 그 빌드가 커밋을
     * 미는 것은 **이 워크플로가 이미 돈 뒤**다. 그래서 **버전을 올린 PR은 첫 회차에서 반드시
     * 빨간불**이고, 올린 사람이 로컬에서 할 수 있는 일이 없다.
     *
     * *"봇이 커밋하면 다음 회차에 낫는다"*도 그대로는 성립하지 않았다 — 그 커밋은
     * `GITHUB_TOKEN`으로 밀려 **워크플로를 다시 트리거하지 않고**(GitHub 기본 규칙),
     * 재실행에는 별도 권한이 필요하다. 실측(2026.08.25): 재실행·수동 실행이 둘 다
     * `403 Resource not accessible by integration`이라 **또 한 번 푸시하는 것 말고 길이 없었다.**
     *
     * 그래서 **딱 한 판 뒤처지는 것만**, 그것도 **코드에 그 구간을 잇는 마이그레이션이 실제로
     * 선언돼 있을 때만** 받는다. 그러면 *"올리는 중"*과 *"올려 놓고 잊었다"*가 갈린다 —
     * 두 판 벌어지거나 다리가 없으면 종전처럼 빨간불이다.
     *
     * **이 완화가 무엇을 약하게 하지 않는지가 요점이다:** 이 시험의 값어치는 위의
     * `identityHash` 대조이고 **그것은 조건 없이 그대로 돈다**(DB를 여는 순간 Room이 던진다).
     * 완화되는 것은 *"새 JSON을 커밋했는가"*라는 부기 한 줄뿐이며, 그 커밋은 사람이 아니라
     * 봇이 하므로 잊힐 자리도 아니다.
     *
     * 순수 하네스 쪽 쌍(`tools/verify_room_migration_57.py`)이 같은 규칙을 든다 —
     * **둘이 갈리면 한쪽이 반드시 낡는다.**
     */
    @Test
    fun 커밋된_최신_스키마가_현행_엔티티와_맞는다() {
        val versions = committedVersions()
        // 하나도 없으면 **통과가 아니라 실패다.** 스키마가 전부 사라지는 일보다 커밋을
        // 빠뜨리는 일이 훨씬 흔하고, 둘을 같게 처리하면 빠뜨린 순간부터 영원히 초록이다.
        assertTrue(
            "커밋된 스키마 JSON이 하나도 없다 — app/schemas/ 를 커밋할 것. " +
                "빌드가 만든 것을 그대로 넣는다(손으로 적으면 identityHash가 어긋나 Room이 거부한다).",
            versions.isNotEmpty()
        )
        val latest = versions.last()

        helper.createDatabase(TEST_DB, latest).close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            // 여는 순간 Room이 스키마를 대조한다 — 어긋나면 여기서 던진다.
            // **`use`로 감싸지 않는다** — 그러면 Room이 들고 있는 열린 DB를 밑에서 닫아 버리고,
            // 뒤따르는 `db.close()`가 이미 닫힌 것을 다시 닫는다. 닫는 일은 아래 `finally` 하나가 진다.
            val open = db.openHelper.writableDatabase
            // 한 판 뒤처짐은 **그 구간을 잇는 마이그레이션이 있을 때만** 받는다(위 KDoc).
            val bridged = open.version == latest + 1 &&
                AppDatabase.ALL_MIGRATIONS.any { it.startVersion == latest && it.endVersion == open.version }
            assertTrue(
                "커밋된 최신 스키마($latest)와 코드의 DB 버전(${open.version})이 맞지 않는다 — " +
                    "같거나, 한 판 뒤이되 MIGRATION_${latest}_${open.version}이 선언돼 있어야 한다. " +
                    "버전을 올렸으면 새 스키마 JSON도 함께 커밋할 것" +
                    "(빌드가 만든 것을 그대로 넣는다 — 손으로 적으면 identityHash가 어긋난다).",
                open.version == latest || bridged
            )
        } finally {
            db.close()
        }
    }

    /**
     * ⓑ **커밋된 이웃 버전 사이를 실제로 걸어 본다.**
     *
     * 쌍이 하나도 없는 동안(스키마가 이제 막 커밋되기 시작한 때)에는 걸을 것이 없고 그것이
     * 정상이다 — ⓐ가 이미 값을 낸다. **쌍이 생기는 대로 이 시험이 저절로 늘어난다.**
     */
    @Test
    fun 커밋된_이웃_버전_사이의_마이그레이션이_실제로_돈다() {
        val versions = committedVersions()
        val pairs = versions.zipWithNext().filter { (from, to) -> to == from + 1 }
        for ((from, to) in pairs) {
            helper.createDatabase(TEST_DB, from).close()
            // 스키마까지 검증한다 — 도는 것과 **옳은 결과를 내는 것**은 다르다.
            helper.runMigrationsAndValidate(TEST_DB, to, true, *AppDatabase.ALL_MIGRATIONS).close()
        }
    }

    /**
     * ⓒ **1→현재가 한 칸씩 이어지는가.**
     *
     * 마이그레이션을 만들고 [AppDatabase.ALL_MIGRATIONS]에 등재하는 것을 잊으면, 그 버전을
     * 건너뛴 사용자의 앱이 열리지 않는다. 목록이 앱과 이 시험의 단일 소스라 **여기서 잡히면
     * 앱에서도 빠져 있는 것**이다(두 벌이었다면 이 시험만 통과할 수 있었다).
     */
    @Test
    fun 마이그레이션_사슬에_구멍이_없다() {
        val steps = AppDatabase.ALL_MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }
        assertTrue("마이그레이션이 하나도 없다 — 목록을 못 읽은 것이 아닌지 볼 것", steps.isNotEmpty())
        assertEquals("사슬은 1에서 시작해야 한다", 1, steps.first().first)
        for ((i, step) in steps.withIndex()) {
            assertEquals("한 칸씩 올라가야 한다: ${step.first}→${step.second}", step.first + 1, step.second)
            if (i > 0) {
                assertEquals(
                    "사슬이 끊겼다: ${steps[i - 1].second} 다음이 ${step.first}이다",
                    steps[i - 1].second, step.first
                )
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
