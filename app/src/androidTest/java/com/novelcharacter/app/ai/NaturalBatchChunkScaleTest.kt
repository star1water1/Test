package com.novelcharacter.app.ai

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NaturalBatchChunkScaleTest {
    @Test fun detailedContextQueryCountDoesNotGrowPerCharacterOrField() = runBlocking {
        val queries = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).setQueryCallback({ sql, _ ->
                if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) queries.incrementAndGet()
            }, Executor { it.run() }).build()
        try {
            db.universeDao().insert(Universe(id = 1, name = "청크 규모 검증"))
            db.novelDao().insert(Novel(id = 10, title = "Book", universeId = 1))
            (1..5).forEach { field -> db.fieldDefinitionDao().insert(FieldDefinition(id = field.toLong(),
                universeId = 1, key = "field$field", name = "속성$field", type = "TEXT")) }
            (1..100).forEach { person ->
                db.characterDao().insert(Character(id = person.toLong(), name = "인물$person", novelId = 10))
                (1..5).forEach { field -> db.characterFieldValueDao().insert(CharacterFieldValue(
                    characterId = person.toLong(), fieldDefinitionId = field.toLong(), value = "값${person}_$field")) }
            }
            var smallQueries = 0
            for (people in listOf(10, 100)) {
                val input = NaturalBatchInput.create(NaturalBatchInput.Scope(NaturalBatchInput.ScopeKind.WORK, 10),
                    (1..people).flatMap { person -> (1..5).map { field -> "인물${person}의 속성${field}은 값${person}_$field" } }
                        .joinToString("\n\n"))
                queries.set(0)
                val started = SystemClock.elapsedRealtimeNanos()
                val context = NaturalBatchContextLoader(db).load(input)
                val loadMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                val count = queries.get()
                assertEquals(people, context.characters.size)
                assertEquals(people * 5, context.values.size)
                val chunks = NaturalBatchChunks.partition(input)
                chunks.forEach { ids ->
                    val subset = NaturalBatchChunks.context(input, ids, context)
                    val chunkText = input.segments().filter { it.id in ids }.joinToString("\n") { it.text }
                    assertTrue(subset.characters.values.all { chunkText.contains(it.name) })
                    val request = NaturalBatchPrompt.request(input, subset, 4096, ids)
                    assertTrue(request.messages.single().text.contains(ids.first()))
                }
                Log.i("NaturalBatchScale", "characters=$people values=${context.values.size} queries=$count contextMs=$loadMs requests=${chunks.size}")
                if (people == 10) smallQueries = count else assertEquals(smallQueries, count)
            }
        } finally { db.close() }
    }
}
