package com.novelcharacter.app.data.maintenance

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.data.database.AppDatabase

class SystemMaintenanceService(
    private val context: Context,
    private val db: AppDatabase
) {
    data class IntegrityResult(
        val fkViolations: Int = 0,
        val duplicateOrders: Int = 0,
        val orphanImages: Int = 0,
        val negativeOrders: Int = 0,
        val sparseOrders: Int = 0,
        val details: List<String> = emptyList()
    )

    /**
     * Check foreign key integrity violations.
     */
    suspend fun checkForeignKeyIntegrity(): IntegrityResult {
        val details = mutableListOf<String>()
        var fkCount = 0

        val cursor = db.openHelper.readableDatabase.query("PRAGMA foreign_key_check")
        try {
            while (cursor.moveToNext()) {
                fkCount++
                val table = cursor.getString(0)
                val rowId = cursor.getLong(1)
                details.add("FK violation: $table (row $rowId)")
            }
        } finally {
            cursor.close()
        }

        // Check for orphaned imageCharacterId references (no FK constraint)
        db.openHelper.readableDatabase.query(
            "SELECT id, title FROM novels WHERE imageCharacterId IS NOT NULL AND imageCharacterId NOT IN (SELECT id FROM characters)"
        ).use { orphanCursor ->
            while (orphanCursor.moveToNext()) {
                fkCount++
                val title = orphanCursor.getString(1)
                details.add("FK violation: novels.imageCharacterId (novel '$title') references deleted character")
            }
        }

        return IntegrityResult(fkViolations = fkCount, details = details)
    }

    /**
     * Check for duplicate displayOrder values within each scope.
     * Sprint B: scope-aware checking
     * - Universes: global scope
     * - Novels: per universeId scope
     * - Characters: per novelId scope
     */
    suspend fun checkDuplicateDisplayOrders(): IntegrityResult {
        val details = mutableListOf<String>()
        var dupCount = 0
        var negativeCount = 0
        var sparseCount = 0

        // Universes: global scope
        db.openHelper.readableDatabase.query(
            "SELECT displayOrder, COUNT(*) as cnt FROM universes GROUP BY displayOrder HAVING cnt > 1"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val order = cursor.getLong(0)
                val count = cursor.getInt(1)
                dupCount += count - 1
                details.add("universes: displayOrder=$order duplicated $count times")
            }
        }

        // Novels: per universeId scope
        db.openHelper.readableDatabase.query(
            "SELECT universeId, displayOrder, COUNT(*) as cnt FROM novels GROUP BY universeId, displayOrder HAVING cnt > 1"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uid = if (cursor.isNull(0)) "NULL" else cursor.getLong(0).toString()
                val order = cursor.getLong(1)
                val count = cursor.getInt(2)
                dupCount += count - 1
                details.add("novels(universe=$uid): displayOrder=$order duplicated $count times")
            }
        }

        // Characters: per novelId scope
        db.openHelper.readableDatabase.query(
            "SELECT novelId, displayOrder, COUNT(*) as cnt FROM characters GROUP BY novelId, displayOrder HAVING cnt > 1"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val nid = if (cursor.isNull(0)) "NULL" else cursor.getLong(0).toString()
                val order = cursor.getLong(1)
                val count = cursor.getInt(2)
                dupCount += count - 1
                details.add("characters(novel=$nid): displayOrder=$order duplicated $count times")
            }
        }

        // Check for negative displayOrder values
        for (table in listOf("universes", "novels", "characters")) {
            db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM $table WHERE displayOrder < 0"
            ).use { cursor ->
                if (cursor.moveToNext()) {
                    val count = cursor.getInt(0)
                    if (count > 0) {
                        negativeCount += count
                        details.add("$table: negative displayOrder $count rows")
                    }
                }
            }
        }

        // Check for excessive sparseness — scope-aware
        // Universes: global
        db.openHelper.readableDatabase.query(
            "SELECT MAX(displayOrder), COUNT(*) FROM universes"
        ).use { cursor ->
            if (cursor.moveToNext()) {
                val maxOrder = cursor.getLong(0)
                val count = cursor.getLong(1)
                if (count > 0 && maxOrder > count * 2) {
                    sparseCount++
                    details.add("universes: sparse displayOrder (max=$maxOrder, count=$count)")
                }
            }
        }
        // Novels: per universeId scope
        db.openHelper.readableDatabase.query(
            "SELECT universeId, MAX(displayOrder), COUNT(*) FROM novels GROUP BY universeId"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uid = if (cursor.isNull(0)) "NULL" else cursor.getLong(0).toString()
                val maxOrder = cursor.getLong(1)
                val count = cursor.getLong(2)
                if (count > 0 && maxOrder > count * 2) {
                    sparseCount++
                    details.add("novels(universe=$uid): sparse displayOrder (max=$maxOrder, count=$count)")
                }
            }
        }
        // Characters: per novelId scope
        db.openHelper.readableDatabase.query(
            "SELECT novelId, MAX(displayOrder), COUNT(*) FROM characters GROUP BY novelId"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val nid = if (cursor.isNull(0)) "NULL" else cursor.getLong(0).toString()
                val maxOrder = cursor.getLong(1)
                val count = cursor.getLong(2)
                if (count > 0 && maxOrder > count * 2) {
                    sparseCount++
                    details.add("characters(novel=$nid): sparse displayOrder (max=$maxOrder, count=$count)")
                }
            }
        }

        return IntegrityResult(
            duplicateOrders = dupCount,
            negativeOrders = negativeCount,
            sparseOrders = sparseCount,
            details = details
        )
    }

    /**
     * Check for broken image paths in characters.
     */
    suspend fun checkBrokenImagePaths(): IntegrityResult {
        val details = mutableListOf<String>()
        var orphanCount = 0
        val gson = Gson()
        val type = object : TypeToken<List<String>>() {}.type
        val appDir = context.filesDir

        val characters = db.characterDao().getAllCharactersList()
        for (character in characters) {
            val paths: List<String> = try {
                gson.fromJson(character.imagePaths, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            for (path in paths) {
                val file = java.io.File(path)
                if (!file.exists() || !file.canonicalPath.startsWith(appDir.canonicalPath)) {
                    orphanCount++
                    details.add("${character.name}: $path")
                }
            }
        }

        return IntegrityResult(orphanImages = orphanCount, details = details)
    }

    /**
     * Fix NameBankEntry records where isUsed=true but usedByCharacterId is NULL
     * (can happen if FK SET_NULL fires without going through CharacterRepository.deleteCharacter).
     */
    suspend fun cleanOrphanedNameBankUsage() {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE name_bank SET isUsed = 0 WHERE isUsed = 1 AND usedByCharacterId IS NULL"
        )
    }

    /**
     * Clear orphaned imageCharacterId references in novels
     * where the referenced character no longer exists.
     */
    suspend fun cleanOrphanedImageCharacterIds() {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE novels SET imageCharacterId = NULL WHERE imageCharacterId IS NOT NULL " +
            "AND imageCharacterId NOT IN (SELECT id FROM characters)"
        )
    }

    /**
     * Reindex displayOrder for all entities using scope-based ordering.
     * Sprint B: Scope-aware reindexing
     * - Universes: single global scope, 0..N-1 (preserving existing sort)
     * - Novels: per universeId scope, 0..N-1 within each scope
     * - Characters: per novelId scope, 0..N-1 within each scope
     *
     * getAllUniversesList/getAllNovelsList/getAllCharactersList already return
     * results ORDER BY displayOrder ASC, so reindexing preserves user order.
     */
    suspend fun reindexDisplayOrders() {
        db.withTransaction {
            // Universes: single global scope (already sorted by displayOrder ASC)
            val universes = db.universeDao().getAllUniversesList()
            val reindexedUniverses = universes.mapIndexed { index, u ->
                u.copy(displayOrder = index.toLong())
            }
            db.universeDao().updateAll(reindexedUniverses)

            // Novels: per universeId scope (already sorted by displayOrder ASC)
            val novels = db.novelDao().getAllNovelsList()
            val novelsByUniverse = novels.groupBy { it.universeId }
            val reindexedNovels = mutableListOf<com.novelcharacter.app.data.model.Novel>()
            for ((_, scopeNovels) in novelsByUniverse) {
                scopeNovels.forEachIndexed { index, n ->
                    reindexedNovels.add(n.copy(displayOrder = index.toLong()))
                }
            }
            db.novelDao().updateAll(reindexedNovels)

            // Characters: per novelId scope (already sorted by displayOrder ASC)
            val characters = db.characterDao().getAllCharactersList()
            val charactersByNovel = characters.groupBy { it.novelId }
            val reindexedCharacters = mutableListOf<com.novelcharacter.app.data.model.Character>()
            for ((_, scopeChars) in charactersByNovel) {
                scopeChars.forEachIndexed { index, c ->
                    reindexedCharacters.add(c.copy(displayOrder = index.toLong()))
                }
            }
            db.characterDao().updateAll(reindexedCharacters)
        }
    }
}
