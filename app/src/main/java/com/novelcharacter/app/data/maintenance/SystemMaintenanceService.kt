package com.novelcharacter.app.data.maintenance

import android.content.Context
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
        while (cursor.moveToNext()) {
            fkCount++
            val table = cursor.getString(0)
            val rowId = cursor.getLong(1)
            details.add("FK violation: $table (row $rowId)")
        }
        cursor.close()

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
        val uCursor = db.openHelper.readableDatabase.query(
            "SELECT displayOrder, COUNT(*) as cnt FROM universes GROUP BY displayOrder HAVING cnt > 1"
        )
        while (uCursor.moveToNext()) {
            val order = uCursor.getLong(0)
            val count = uCursor.getInt(1)
            dupCount += count - 1
            details.add("universes: displayOrder=$order duplicated $count times")
        }
        uCursor.close()

        // Novels: per universeId scope
        val nCursor = db.openHelper.readableDatabase.query(
            "SELECT universeId, displayOrder, COUNT(*) as cnt FROM novels GROUP BY universeId, displayOrder HAVING cnt > 1"
        )
        while (nCursor.moveToNext()) {
            val uid = if (nCursor.isNull(0)) "NULL" else nCursor.getLong(0).toString()
            val order = nCursor.getLong(1)
            val count = nCursor.getInt(2)
            dupCount += count - 1
            details.add("novels(universe=$uid): displayOrder=$order duplicated $count times")
        }
        nCursor.close()

        // Characters: per novelId scope
        val cCursor = db.openHelper.readableDatabase.query(
            "SELECT novelId, displayOrder, COUNT(*) as cnt FROM characters GROUP BY novelId, displayOrder HAVING cnt > 1"
        )
        while (cCursor.moveToNext()) {
            val nid = if (cCursor.isNull(0)) "NULL" else cCursor.getLong(0).toString()
            val order = cCursor.getLong(1)
            val count = cCursor.getInt(2)
            dupCount += count - 1
            details.add("characters(novel=$nid): displayOrder=$order duplicated $count times")
        }
        cCursor.close()

        // Check for negative displayOrder values
        for (table in listOf("universes", "novels", "characters")) {
            val negCursor = db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM $table WHERE displayOrder < 0"
            )
            if (negCursor.moveToNext()) {
                val count = negCursor.getInt(0)
                if (count > 0) {
                    negativeCount += count
                    details.add("$table: negative displayOrder $count rows")
                }
            }
            negCursor.close()
        }

        // Check for excessive sparseness (max order > count * 2)
        for (table in listOf("universes", "novels", "characters")) {
            val sparseCursor = db.openHelper.readableDatabase.query(
                "SELECT MAX(displayOrder), COUNT(*) FROM $table"
            )
            if (sparseCursor.moveToNext()) {
                val maxOrder = sparseCursor.getLong(0)
                val count = sparseCursor.getLong(1)
                if (count > 0 && maxOrder > count * 2) {
                    sparseCount++
                    details.add("$table: sparse displayOrder (max=$maxOrder, count=$count)")
                }
            }
            sparseCursor.close()
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
     * Reindex displayOrder for all entities using scope-based ordering.
     * Sprint B: Scope-aware reindexing
     * - Universes: single global scope, 0..N-1
     * - Novels: per universeId scope, 0..N-1 within each scope
     * - Characters: per novelId scope, 0..N-1 within each scope
     */
    suspend fun reindexDisplayOrders() {
        // Universes: single global scope
        val universes = db.universeDao().getAllUniversesList()
        val reindexedUniverses = universes.mapIndexed { index, u ->
            u.copy(displayOrder = index.toLong())
        }
        db.universeDao().updateAll(reindexedUniverses)

        // Novels: per universeId scope
        val novels = db.novelDao().getAllNovelsList()
        val novelsByUniverse = novels.groupBy { it.universeId }
        val reindexedNovels = mutableListOf<com.novelcharacter.app.data.model.Novel>()
        for ((_, scopeNovels) in novelsByUniverse) {
            scopeNovels.forEachIndexed { index, n ->
                reindexedNovels.add(n.copy(displayOrder = index.toLong()))
            }
        }
        db.novelDao().updateAll(reindexedNovels)

        // Characters: per novelId scope
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
