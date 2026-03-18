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
     * Check for duplicate displayOrder values within each entity type.
     */
    suspend fun checkDuplicateDisplayOrders(): IntegrityResult {
        val details = mutableListOf<String>()
        var dupCount = 0

        val tables = listOf(
            "universes" to "displayOrder",
            "novels" to "displayOrder",
            "characters" to "displayOrder"
        )

        for ((table, col) in tables) {
            val cursor = db.openHelper.readableDatabase.query(
                "SELECT $col, COUNT(*) as cnt FROM $table GROUP BY $col HAVING cnt > 1"
            )
            while (cursor.moveToNext()) {
                val order = cursor.getLong(0)
                val count = cursor.getInt(1)
                dupCount += count - 1
                details.add("$table: displayOrder=$order duplicated $count times")
            }
            cursor.close()
        }

        return IntegrityResult(duplicateOrders = dupCount, details = details)
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
     * Reindex displayOrder for all entities so they are sequential (0, 1, 2, ...).
     */
    suspend fun reindexDisplayOrders() {
        // Universes
        val universes = db.universeDao().getAllUniversesList()
        val reindexedUniverses = universes.mapIndexed { index, u ->
            u.copy(displayOrder = index.toLong())
        }
        db.universeDao().updateAll(reindexedUniverses)

        // Novels (per universe group + unassigned)
        val novels = db.novelDao().getAllNovelsList()
        val reindexedNovels = novels.mapIndexed { index, n ->
            n.copy(displayOrder = index.toLong())
        }
        db.novelDao().updateAll(reindexedNovels)

        // Characters
        val characters = db.characterDao().getAllCharactersList()
        val reindexedCharacters = characters.mapIndexed { index, c ->
            c.copy(displayOrder = index.toLong())
        }
        db.characterDao().updateAll(reindexedCharacters)
    }
}
