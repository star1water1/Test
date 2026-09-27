package com.novelcharacter.app.data.database

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.excel.ExcelExporter
import com.novelcharacter.app.excel.ExportOptions
import com.novelcharacter.app.excel.ExportProgressSink
import com.novelcharacter.app.excel.ExportSheetStep
import com.novelcharacter.app.share.WorldPackageExporter
import com.novelcharacter.app.util.ImageDeletionService
import com.novelcharacter.app.util.RecoverableFileDelete
import kotlinx.coroutines.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ExportSnapshotTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private val files = mutableListOf<File>()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.universeDao().insert(Universe(id = 1, name = "Snapshot world", code = "world"))
        db.novelDao().insert(Novel(id = 10, title = "Old book", universeId = 1, code = "old-book"))
        db.characterDao().insert(Character(id = 100, name = "Old character", novelId = 10, code = "old-character"))
        Unit
    }
    @After fun close() { db.close(); files.forEach { it.deleteRecursively() } }

    @Test fun workbookCannotMixNovelsBeforeWriteWithCharactersAfterWrite() = runBlocking {
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val writerStarted = CompletableDeferred<Unit>()
        val options = ExportOptions()
        val novelStep = ExportSheetStep.of(options).indexOf(ExportSheetStep.NOVELS) + 1
        val progress = ExportProgressSink(onSheets = { done, _ ->
            if (done == novelStep) {
                reached.countDown()
                check(resume.await(20, TimeUnit.SECONDS))
            }
        }, onImages = { _, _ -> }, isCancelled = { false })
        XSSFWorkbook().use { workbook ->
            val export = async(Dispatchers.IO) { ExcelExporter(context, db).populateWorkbook(workbook, options, progress) }
            try {
                assertTrue(reached.await(20, TimeUnit.SECONDS))
                val writer = async(Dispatchers.IO) {
                    writerStarted.complete(Unit)
                    db.withTransaction {
                        db.novelDao().insert(Novel(id = 11, title = "New book", universeId = 1, code = "new-book"))
                        db.characterDao().insert(Character(id = 101, name = "New character", novelId = 11, code = "new-character"))
                    }
                }
                writerStarted.await()
                delay(200)
                assertFalse("Concurrent write must wait for the export snapshot", writer.isCompleted)
                resume.countDown()
                export.await()
                writer.await()
                val cells = (0 until workbook.numberOfSheets).flatMap { index ->
                    workbook.getSheetAt(index).flatMap { row -> row.map { it.toString() } }
                }
                assertTrue(cells.contains("Old book"))
                assertTrue(cells.contains("Old character"))
                assertFalse(cells.contains("New book"))
                assertFalse(cells.contains("New character"))
            } finally { resume.countDown() }
        }
    }

    @Test fun worldPackageKeepsSnapshotImageUntilPackagingFinishes() = runBlocking {
        val image = File(context.filesDir, "char_snapshot_${java.util.UUID.randomUUID()}.jpg").apply { writeText("snapshot image bytes") }
        files.add(image)
        db.characterDao().update(db.characterDao().getCharacterById(100)!!.copy(imagePaths = Gson().toJson(listOf(image.absolutePath))))
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val deletionStarted = CompletableDeferred<Unit>()
        val exporter = WorldPackageExporter(context, db)
        val export = async(Dispatchers.IO) {
            exporter.export(WorldPackageExporter.ExportConfig(1), WorldPackageExporter.ProgressSink(onSections = { done, _ ->
                if (done == 0) { reached.countDown(); check(resume.await(20, TimeUnit.SECONDS)) }
            }))
        }
        try {
            assertTrue(reached.await(20, TimeUnit.SECONDS))
            val recovery = File(context.filesDir, "snapshot-delete-${java.util.UUID.randomUUID()}").also { files.add(it) }
            val deletion = async(Dispatchers.IO) {
                deletionStarted.complete(Unit)
                ImageDeletionService.delete(db, image.absolutePath, ImageDeletionService.Owners(characterIds = listOf(100)),
                    null, recoveryDir = recovery)
            }
            deletionStarted.await()
            delay(200)
            assertFalse("Image deletion must wait until ZIP has copied the snapshot image", deletion.isCompleted)
            resume.countDown()
            val result = export.await()
            files.add(result.file.parentFile!!)
            assertNotNull(deletion.await())
            ZipFile(result.file).use { zip ->
                val entries = zip.entries().asSequence().filter { it.name.startsWith("images/") }.toList()
                assertEquals(1, entries.size)
                assertEquals("snapshot image bytes", zip.getInputStream(entries.single()).reader().readText())
            }
            assertFalse(image.exists())
        } finally { resume.countDown() }
    }
}
