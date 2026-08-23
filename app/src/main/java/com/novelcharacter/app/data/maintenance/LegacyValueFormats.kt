package com.novelcharacter.app.data.maintenance

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * **옛 경로가 남긴 규격 밖 값 정리** (1회 실행, 2026.08.24) — 둘 다 *지금은 들어올 수 없는데
 * 이미 들어와 있는* 값이라, 들이는 문을 고친 것만으로는 낫지 않는다.
 *
 * 부르는 쪽(`NovelCharacterApp`)이 1회 플래그와 고지를 든다. **SQL이 여기 사는 이유**는
 * `tools/check_dao_sql_twins.sh`가 살아 있는 SQL의 자리를 `data/dao/`·`data/maintenance/`로
 * 못박아 두었기 때문이다 — 그 밖으로 나가면 그 검사가 자기 사각지대를 못 보게 된다.
 *
 * ## ① 생성일보다 이른 수정일
 *
 * 두 시각의 처분이 일부러 다르다 — '생성일'은 그 행의 정체라 파일 값으로 덮지 않고,
 * '수정일'은 왕복 충실성을 위해 파일 값을 그대로 받는다
 * ([com.novelcharacter.app.util.RecordTimestamps]가 그 원문). 갈라 둔 것 자체는 맞는데,
 * **그 둘이 서로를 모르던 시절에 어긋난 행이 이미 만들어졌다.**
 *
 * 실측(2026.08.23·24 사용자가 내보낸 파일 — **두 판 연속 같은 자리**): '필드 템플릿' 두 행이
 * 생성일 2026-08-02, 수정일 **2026-03-20**으로 **134.8일 역전**.
 *
 * `RecordTimestamps`는 가져오기 경로에 규칙을 세웠고 그 문서는 *"다시 들이면 스스로 낫는다"*고
 * 적는다. 참이지만 **앱 안에서는 낫지 않는다** — 사용자가 내보낸 파일을 되들이는 일은 보통
 * 일어나지 않고, 그동안 그 열로 정렬한 화면·파일이 조용히 틀린 차례를 낸다.
 *
 * **두 시각을 함께 든 표를 전부 본다** — 규칙은 특정 표의 것이 아니라 *그런 행*의 것이다.
 * 값을 버리지 않는다: 생성일 아래로 내려간 수정일을 **생성일까지 올릴 뿐**이다.
 *
 * ## ② '#'이 빠진 색
 *
 * 실측: '세계관' 여덟 행 중 하나가 `000000`이고 나머지 일곱은 `#RRGGBB`였다. 앱은 양쪽을 다
 * 읽지만(`ui/common/parseColorOrNull`), **내보낸 파일이 스스로의 안내(*"테두리색(HEX)"*)와
 * 어긋나고** 같은 뜻의 값이 두 글자로 갈린다. 들이는 문은
 * [com.novelcharacter.app.util.ColorHex.normalizedOrNull]이 막았고, 여기서 이미 든 값을 올린다.
 *
 * 판정 조건은 그 함수의 것과 같다 — `#`이 없고, 길이가 3·6·8이고, 16진 글자만 들었을 때.
 * SQLite에는 정규식이 없어 `GLOB`으로 적는다: `*[^0-9A-Fa-f]*`가 *16진 아닌 글자가 하나라도
 * 있는가*이므로, 부정하면 *전부 16진 글자*가 된다.
 *
 * **멱등이다** — 몇 번을 돌려도 같은 상태로 수렴한다(고친 행은 조건에서 빠진다).
 */
object LegacyValueFormats {

    /** 몇 건을 올렸는가 — 부르는 쪽이 사용자에게 말할 재료다(말없이 고치지 않는다). */
    data class Repaired(val timestamps: Int, val colors: Int) {
        val any: Boolean get() = timestamps > 0 || colors > 0
    }

    /**
     * `createdAt`·`updatedAt`을 **함께** 든 표 전부 — ①의 대상.
     * 새 표가 그 짝을 가지면 여기에 더한다(빠뜨리면 그 표만 규칙 밖에 남는다).
     */
    private val TIMESTAMP_PAIR_TABLES = listOf(
        "user_preset_templates", "search_presets",
        "character_list_presets", "field_value_entries"
    )

    /** 색 글자를 든 (표, 열) 전부 — ②의 대상. */
    private val COLOR_COLUMNS = listOf(
        "universes" to "borderColor",
        "novels" to "borderColor",
        "factions" to "color"
    )

    fun repair(db: SupportSQLiteDatabase): Repaired {
        var timestamps = 0
        for (table in TIMESTAMP_PAIR_TABLES) {
            val where = "updatedAt < createdAt"
            timestamps += count(db, "SELECT COUNT(*) FROM $table WHERE $where")
            db.execSQL("UPDATE $table SET updatedAt = createdAt WHERE $where")
        }
        var colors = 0
        for ((table, column) in COLOR_COLUMNS) {
            val where = "$column IS NOT NULL AND $column <> '' AND $column NOT LIKE '#%' " +
                "AND length($column) IN (3, 6, 8) AND $column NOT GLOB '*[^0-9A-Fa-f]*'"
            colors += count(db, "SELECT COUNT(*) FROM $table WHERE $where")
            db.execSQL("UPDATE $table SET $column = '#' || $column WHERE $where")
        }
        return Repaired(timestamps = timestamps, colors = colors)
    }

    /**
     * `COUNT(*)` 한 줄을 읽는다. 형제([SystemMaintenanceService])와 **같은 관용구**로 적는다 —
     * 갓 연 커서는 첫 행 앞에 서 있어 `moveToNext()`가 곧 첫 행이고, 그 짝이 프로브 스텁이
     * 실제로 든 표면이다(스텁을 넓히면 로컬이 거짓 초록을 낸다 — `tools/setup_jvm_env.sh`의 경고).
     */
    private fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { if (it.moveToNext()) it.getInt(0) else 0 }
}
