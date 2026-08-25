package com.novelcharacter.app.data.maintenance

import androidx.sqlite.db.SupportSQLiteDatabase
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.util.BirthDateFormat

/**
 * **옛 경로가 남긴 규격 밖 값 정리** (1회 실행, 2026.08.24 · ④⑤ 2026.08.25) — 전부 *지금은
 * 들어올 수 없는데 이미 들어와 있는* 값이라, 들이는 문을 고친 것만으로는 낫지 않는다.
 *
 * **항목이 느는 자리다.** 새 항목을 더할 때는 부르는 쪽의 1회 플래그도 함께 올린다 —
 * 이미 마친 설치는 옛 플래그 때문에 다시 돌지 않아 **새 항목만 조용히 건너뛴다.**
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
 * ## ③ 0이 빠진 생일
 *
 * 실측: 생일이 있는 47명 중 둘이 `5-30`·`6-7`이고 나머지 마흔다섯은 `MM-DD`였다. 같은 캐릭터의
 * '캐릭터 상태변화' 행은 `05-30`·`06-07`이라 **한 파일이 같은 생일을 두 글자로 적었다.**
 * 만드는 자리 셋 중 편집 화면의 구조화 입력만 0을 안 채우던 것이 원인이고, 그 문은
 * [BirthDateFormat]이 막았다 — 여기서 이미 든 값을 올린다.
 *
 * **판정을 SQL로 적지 않는다.** 0 채움은 문자열 연산이라 SQLite로 적으면 그 규칙이 두 벌이
 * 되고(코틀린 쪽과), 무엇보다 *읽을 수 있는가*의 술어([isRealMonthDay])까지 옮겨 적어야 한다.
 * 그래서 후보만 넓게 긁어 오고 판정·변환은 [BirthDateFormat]이 든다.
 *
 * **읽을 수 없는 값은 건드리지 않는다** — 사용자가 적어 둔 글자를 우리가 못 읽는다고 해서
 * 바꾸지 않는다(개발 의도 2번).
 *
 * ## ④ 값 라이브러리에 남은 규격 밖 생일
 *
 * **③이 짝을 두고 갔다.** 캐릭터 값만 올리고 `field_value_entries`를 손대지 않아,
 * 라이브러리에는 `5-30`·`6-7`이 사용횟수 0으로 남고 **실제로 쓰이는 `05-30`·`06-07`은
 * 행이 아예 없었다**(실측 2026.08.25 파일). 판정과 처분은 [BirthDateEntryRepair]가 든다 —
 * 별칭 보존까지 앱의 '값 이름 변경'과 같은 규약이다.
 *
 * 여기서 고치는 것은 **이미 라이브러리에 있는 행**뿐이다. 라이브러리가 아예 모르는 값은
 * 수확이 해야 할 일이라, 부르는 쪽이 `field_library_harvest_pending`을 세워 다음 실행의
 * 수확·재집계에 맡긴다(그 플래그의 본래 뜻 그대로 — *"커밋은 됐는데 수확 전에 죽었다"*).
 *
 * ## ⑤ 세력 연결을 잃은 자동 관계
 *
 * '캐릭터 관계' 135행 중 **87행**이 소속 클리크와 정확히 일치하는 자동 관계인데 `factionId`가
 * 비어 있었다(실측 2026.08.25 파일). 들이는 문은 이미 고쳐져 있지만(`ExcelImportService`가
 * 세력을 관계보다 먼저 가져온다) **이미 강등된 행은 왕복으로 낫지 않는다** — 내보내기는 빈
 * 칸을 쓰고 가져오기는 빈 칸을 '연결 해제'로 읽어 **왕복마다 굳는다.**
 *
 * 증상 중 하나가 거짓 안심이다: 세력 삭제 고지가 *"자동 관계 0건"*이라 말하고 실제로는 수십
 * 행이 고아로 남는다(R-4). 판정은 [FactionAutoRelationRelink]가 들고, **수동 관계는 절대
 * 걸리지 않게** 생성자가 넣는 모양 전부 + 생성 시각까지 맞을 때만 잇는다.
 *
 * **멱등이다** — 몇 번을 돌려도 같은 상태로 수렴한다(고친 행은 조건에서 빠진다).
 */
object LegacyValueFormats {

    /** 몇 건을 올렸는가 — 부르는 쪽이 사용자에게 말할 재료다(말없이 고치지 않는다). */
    data class Repaired(
        val timestamps: Int,
        val colors: Int,
        val birthDates: Int = 0,
        val birthDateEntries: Int = 0,
        /**
         * ④가 엔트리를 옮긴 필드 — **부르는 쪽이 그 필드의 집계를 다시 세라는 뜻이다.**
         *
         * 이름 변경은 행의 `usageCount`를 그대로 둔 채 값만 옮긴다. 그래서 실측의 `05-30`처럼
         * *쓰이는데 0으로 적힌* 행이 한 세션 동안 남고, 그 상태의 행은
         * **'미사용 자동수집 정리'가 지우자고 권한다** — 이 저장소가 B-60에서 이미 겪고
         * 규칙으로 못박은 그 부류다(`FieldValueLibraryRepository`의 그 KDoc).
         *
         * 집계를 여기서 세지 않는 것이 요점이다: 세는 규칙은 표 셋을 도는 그 함수 하나이고,
         * SQL로 옮겨 적으면 그 규칙이 두 벌이 된다.
         */
        val birthDateEntryFieldIds: Set<Long> = emptySet(),
        val factionRelinks: Int = 0
    ) {
        /**
         * 값 라이브러리를 건드렸는가 — 부르는 쪽이 **다음 실행의 수확을 예약할지** 가른다.
         *
         * ③이 캐릭터 값을 올렸으면 라이브러리가 모르는 저장 모양이 생겼을 수 있고, ④가 행을
         * 옮겼으면 `usageCount`가 낡는다. 둘 중 하나라도 있으면 수확·재집계가 필요하다.
         */
        val needsLibraryHarvest: Boolean get() = birthDates > 0 || birthDateEntries > 0
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
        // **부르는 차례를 인자 자리에 숨기지 않는다** — 항목마다 쓰는 표가 달라 서로 독립이지만,
        // 그 사실이 참인지는 *읽어서* 알 수 있어야 하고, 언젠가 의존이 생기면 그때 이 자리에
        // 적혀야 한다. named argument의 평가 차례에 기대면 인자를 재배열하는 것만으로
        // 실행 차례가 조용히 바뀐다.
        val birthDates = repairBirthDates(db)
        val entryRepair = repairBirthDateEntries(db)
        val factionRelinks = relinkFactionAutoRelations(db)
        return Repaired(
            timestamps = timestamps,
            colors = colors,
            birthDates = birthDates,
            birthDateEntries = entryRepair.fixed,
            birthDateEntryFieldIds = entryRepair.fieldIds,
            factionRelinks = factionRelinks
        )
    }

    /**
     * ③ — 생일 필드값을 저장 모양(`MM-DD`)으로 올린다. @return 고친 행 수.
     *
     * 후보를 **넓게** 긁는다(`config LIKE '%birth_date%'`) — 역할 판정은 JSON이라 SQL이
     * 정확히 흉내 낼 수 없고(`{"semanticRole": "birth_date"}`처럼 빈칸이 든 config가 손편집·
     * 월드패키지로 들어올 수 있다), 좁게 긁으면 그 행만 조용히 남는다. 걸러 내는 것은
     * [SemanticRole.fromConfig]다 — 앱이 실제로 쓰는 그 판정이다.
     */
    private fun repairBirthDates(db: SupportSQLiteDatabase): Int {
        // (행 id, 지금 값) — 판정을 통과한 것만 담는다. 한 질의로 끝낸다.
        val targets = ArrayList<Pair<Long, String>>()
        db.query(
            "SELECT v.id, v.value, f.config FROM character_field_values v " +
                "JOIN field_definitions f ON f.id = v.fieldDefinitionId " +
                "WHERE f.config LIKE '%birth_date%' AND v.value <> ''"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (SemanticRole.fromConfig(cursor.getString(2)) != SemanticRole.BIRTH_DATE) continue
                val value = cursor.getString(1)
                if (BirthDateFormat.needsRepair(value)) targets.add(cursor.getLong(0) to value)
            }
        }
        var fixed = 0
        for ((id, value) in targets) {
            val canonical = BirthDateFormat.canonicalOrNull(value) ?: continue
            db.execSQL(
                "UPDATE character_field_values SET value = ? WHERE id = ?",
                arrayOf<Any>(canonical, id)
            )
            fixed++
        }
        return fixed
    }

    /**
     * ④ — 값 라이브러리에 남은 규격 밖 생일 표기를 저장 모양으로 올린다. @return 고친 행 수.
     *
     * 후보를 넓게 긁고 판정을 [SemanticRole.fromConfig]에 맡기는 것은 ③과 **같은 이유**다
     * (SQL이 JSON 역할 판정을 정확히 흉내 낼 수 없다). 처분은 [BirthDateEntryRepair]가 든다.
     *
     * **필드 단위로 통째로 넘긴다** — 저장 모양의 행이 이미 있는지 알아야 이름 변경과 병합이
     * 갈리고, 그것을 모르면 `(fieldDefinitionId, value)` 유니크 색인에 걸린다.
     */
    private class EntryRepair(val fixed: Int, val fieldIds: Set<Long>)

    private fun repairBirthDateEntries(db: SupportSQLiteDatabase): EntryRepair {
        // 필드 id → 그 필드의 엔트리 전부.
        val byField = LinkedHashMap<Long, MutableList<BirthDateEntryRepair.Entry>>()
        db.query(
            "SELECT e.id, e.fieldDefinitionId, e.value, e.aliasesJson, f.config " +
                "FROM field_value_entries e " +
                "JOIN field_definitions f ON f.id = e.fieldDefinitionId " +
                "WHERE f.config LIKE '%birth_date%'"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (SemanticRole.fromConfig(cursor.getString(4)) != SemanticRole.BIRTH_DATE) continue
                byField.getOrPut(cursor.getLong(1)) { ArrayList() }.add(
                    BirthDateEntryRepair.Entry(
                        id = cursor.getLong(0),
                        value = cursor.getString(2),
                        aliases = FieldValueEntry.parseAliases(cursor.getString(3))
                    )
                )
            }
        }

        var fixed = 0
        val touched = HashSet<Long>()
        val now = System.currentTimeMillis()
        for ((fieldId, entries) in byField) {
            for (action in BirthDateEntryRepair.plan(entries)) {
                when (action) {
                    is BirthDateEntryRepair.Action.Rename -> db.execSQL(
                        "UPDATE field_value_entries SET value = ?, aliasesJson = ?, updatedAt = ? WHERE id = ?",
                        arrayOf<Any>(
                            action.newValue,
                            FieldValueEntry.aliasesToJson(action.aliases),
                            now,
                            action.id
                        )
                    )
                    is BirthDateEntryRepair.Action.Merge -> {
                        db.execSQL(
                            "UPDATE field_value_entries SET aliasesJson = ?, updatedAt = ? WHERE id = ?",
                            arrayOf<Any>(
                                FieldValueEntry.aliasesToJson(action.targetAliases),
                                now,
                                action.targetId
                            )
                        )
                        db.execSQL(
                            "DELETE FROM field_value_entries WHERE id = ?",
                            arrayOf<Any>(action.sourceId)
                        )
                    }
                }
                fixed++
                touched.add(fieldId)
            }
        }
        return EntryRepair(fixed, touched)
    }

    /**
     * ⑤ — 세력 연결을 잃은 자동 관계를 도로 잇는다. @return 이은 행 수.
     *
     * 판정은 [FactionAutoRelationRelink]가 든다 — **수동 관계를 잡으면 탈퇴가 사용자의 관계를
     * 지우는 유실 경로가 열리므로**(`FactionRepository`의 금지) 규칙을 순수 계층에 두고
     * 시험이 고정한다.
     *
     * **읽는 양:** 세력·소속은 작고, 관계는 `factionId IS NULL`로 좁혀 뜬다 — 이 정리가 끝나면
     * 남는 것은 수동 관계뿐이고, 그 수는 캐릭터 수에 붙지 사건·이미지 축에 붙지 않는다.
     * 1회·백그라운드라 같은 실행의 `harvestAll`(전 필드값 순회)보다 작다.
     *
     * 판정에 쓰지 않는 열은 뜨지 않고 모델 기본값으로 둔다 — [FactionAutoRelationRelink]가
     * 보는 것은 아래에서 실제로 채우는 열뿐이다.
     */
    private fun relinkFactionAutoRelations(db: SupportSQLiteDatabase): Int {
        val factions = ArrayList<Faction>()
        db.query("SELECT id, universeId, name, autoRelationType, autoRelationIntensity FROM factions")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    factions.add(
                        Faction(
                            id = cursor.getLong(0),
                            universeId = cursor.getLong(1),
                            name = cursor.getString(2),
                            autoRelationType = cursor.getString(3),
                            autoRelationIntensity = cursor.getInt(4)
                        )
                    )
                }
            }
        if (factions.isEmpty()) return 0

        val memberships = ArrayList<FactionMembership>()
        // 탈퇴 행까지 뜬다 — *지금 소속*의 판정은 SQL이 아니라 FactionStanding이 든다(R-50).
        db.query("SELECT id, factionId, characterId, leaveType, createdAt FROM faction_memberships")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    memberships.add(
                        FactionMembership(
                            id = cursor.getLong(0),
                            factionId = cursor.getLong(1),
                            characterId = cursor.getLong(2),
                            leaveType = if (cursor.isNull(3)) null else cursor.getString(3),
                            createdAt = cursor.getLong(4)
                        )
                    )
                }
            }
        if (memberships.isEmpty()) return 0

        val orphans = ArrayList<CharacterRelationship>()
        db.query(
            "SELECT id, characterId1, characterId2, relationshipType, description, " +
                "intensity, isBidirectional, displayOrder, createdAt " +
                "FROM character_relationships WHERE factionId IS NULL"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                orphans.add(
                    CharacterRelationship(
                        id = cursor.getLong(0),
                        characterId1 = cursor.getLong(1),
                        characterId2 = cursor.getLong(2),
                        relationshipType = cursor.getString(3),
                        description = cursor.getString(4),
                        intensity = cursor.getInt(5),
                        isBidirectional = cursor.getInt(6) != 0,
                        displayOrder = cursor.getInt(7),
                        createdAt = cursor.getLong(8),
                        // 판정에 안 쓰는 열은 뜨지 않는다. `code`만 **명시로 비운다** —
                        // 기본값이 행마다 새 코드를 만드는데, 여기서 만든 것은 아무 데도 쓰이지
                        // 않고 관계 수만큼 버려진다(읽는 양이 가장 큰 축이 이 표다).
                        code = null
                    )
                )
            }
        }
        if (orphans.isEmpty()) return 0

        val links = FactionAutoRelationRelink.plan(factions, memberships, orphans)
        for (link in links) {
            db.execSQL(
                "UPDATE character_relationships SET factionId = ? WHERE id = ?",
                arrayOf<Any>(link.factionId, link.relationshipId)
            )
        }
        return links.size
    }

    /**
     * `COUNT(*)` 한 줄을 읽는다. 형제([SystemMaintenanceService])와 **같은 관용구**로 적는다 —
     * 갓 연 커서는 첫 행 앞에 서 있어 `moveToNext()`가 곧 첫 행이고, 그 짝이 프로브 스텁이
     * 실제로 든 표면이다(스텁을 넓히면 로컬이 거짓 초록을 낸다 — `tools/setup_jvm_env.sh`의 경고).
     */
    private fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { if (it.moveToNext()) it.getInt(0) else 0 }
}
