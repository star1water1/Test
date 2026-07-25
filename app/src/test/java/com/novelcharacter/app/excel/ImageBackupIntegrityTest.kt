package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 이미지 백업 무결성 — ZIP 제외 집계([44])와 '이미지' 시트 중복 접기([40]).
 *
 * 이미지가 빠진 백업을 완전한 백업으로 믿고 원본 기기를 초기화하면 이미지가 영구 소멸한다.
 * 무음 제외·무음 덮어쓰기는 그 자체로 결함이다.
 */
class ImageBackupIntegrityTest {

    // ── [44] 경로 분류 (ZIP 래핑과 유지보수 점검의 단일 소스) ──

    private fun classify(paths: List<String>, existing: Set<String>) =
        ImagePathClassifier.classify(
            paths,
            appDirCanonicalPath = "/data/app/files",
            separator = "/",
            exists = { it in existing },
            canonicalOf = { it }
        )

    @Test
    fun classify_splitsIncludableMissingAndOutside() {
        val r = classify(
            listOf("/data/app/files/a.jpg", "/data/app/files/gone.jpg", "/sdcard/b.jpg"),
            existing = setOf("/data/app/files/a.jpg", "/sdcard/b.jpg")
        )
        assertEquals(listOf("/data/app/files/a.jpg"), r.includable)
        assertEquals(listOf("/data/app/files/gone.jpg"), r.missing)
        assertEquals(listOf("/sdcard/b.jpg"), r.outsideAppDir)
        assertEquals(2, r.broken.size)
    }

    @Test
    fun classify_prefixComparisonRespectsSeparatorBoundary() {
        // "/data/app/files"가 "/data/app/files_evil/x.jpg"를 삼키면 앱 밖 파일이 백업에 섞인다
        val r = classify(listOf("/data/app/files_evil/x.jpg"), existing = setOf("/data/app/files_evil/x.jpg"))
        assertTrue(r.includable.isEmpty())
        assertEquals(listOf("/data/app/files_evil/x.jpg"), r.outsideAppDir)
    }

    @Test
    fun classify_trailingSeparatorOnAppDirIsHarmless() {
        val r = ImagePathClassifier.classify(
            listOf("/data/app/files/a.jpg"), "/data/app/files/", "/",
            exists = { true }, canonicalOf = { it }
        )
        assertEquals(listOf("/data/app/files/a.jpg"), r.includable)
    }

    @Test
    fun classify_unreadableCanonicalPathIsExcludedNotIncluded() {
        // canonicalPath 실패는 "앱 안"으로 낙관하지 않는다 — 담기지 못할 파일을 담긴 것으로 세면 안 된다
        val r = ImagePathClassifier.classify(
            listOf("/weird"), "/data/app/files", "/", exists = { true }, canonicalOf = { null }
        )
        assertEquals(listOf("/weird"), r.outsideAppDir)
    }

    // ── [44] 결과 집계: 사실만 말한다 ──

    @Test
    fun report_invariant_referencedEqualsIncludedPlusExcluded() {
        val r = ImageZipReport(
            requested = true, created = true, referencedCount = 10,
            includedCount = 6, missingCount = 2, outsideAppDirCount = 1, failedCount = 1
        )
        assertEquals(4, r.excludedCount)
        assertEquals(r.referencedCount, r.includedCount + r.excludedCount)
        assertTrue(r.hasLoss)
    }

    @Test
    fun report_noLossWhenEverythingIncluded() {
        val r = ImageZipReport(requested = true, created = true, referencedCount = 3, includedCount = 3)
        assertFalse(r.hasLoss)
    }

    @Test
    fun report_notRequested_neverWarns() {
        // 이미지 포함을 요청하지 않은 내보내기에 손실 경고가 뜨면 사실과 다른 경고가 된다
        assertFalse(ImageZipReport.NOT_REQUESTED.hasLoss)
        assertFalse(ImageZipReport(requested = false, missingCount = 5).hasLoss)
    }

    @Test
    fun report_sampleNamesAreFileNamesAndCapped() {
        val paths = (1..10).map { "/data/app/files/img$it.jpg" }
        val names = ImagePathClassifier.sampleNames(paths)
        assertEquals(ImageZipReport.SAMPLE_LIMIT, names.size)
        assertEquals("img1.jpg", names.first())   // 내부 절대경로 전체는 사용자에게 의미가 없다
    }

    // ── [40] '이미지' 시트 중복 접기 ──

    private fun plan(rows: List<Pair<Int, String>>, local: Set<String> = emptySet()) =
        ImageMetaRowResolver.plan(rows, emptyMap()) { name ->
            if (name in local) "/data/app/files/$name" else null
        }

    @Test
    fun duplicateFileName_lastWinsAndIsReported() {
        // 핵심 회귀: 뒤 행의 빈 태그가 앞 행의 태그를 통째로 지우는데 경고가 없었다
        val p = plan(listOf(1 to "a.jpg", 2 to "a.jpg"), local = setOf("a.jpg"))
        assertEquals(1, p.rows.size)
        assertEquals(2, p.rows.single().rowIndex)     // 마지막 행 우선
        assertEquals(1, p.warnings.size)
        assertTrue(p.warnings.single().contains("행 1 과 행 2 에 중복됨"))
        assertTrue(p.warnings.single().contains("마지막 행 우선"))
    }

    @Test
    fun distinctFiles_produceNoWarning() {
        val p = plan(listOf(1 to "a.jpg", 2 to "b.jpg"), local = setOf("a.jpg", "b.jpg"))
        assertEquals(2, p.rows.size)
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun survivingRowsKeepFirstAppearanceOrder() {
        // 왕복 멱등성: 처리 순서가 파일 내 등장 순서에 고정되어야 결과가 흔들리지 않는다
        val p = plan(listOf(1 to "a.jpg", 2 to "b.jpg", 3 to "a.jpg"), local = setOf("a.jpg", "b.jpg"))
        assertEquals(listOf("a.jpg", "b.jpg"), p.rows.map { it.fileName })
        assertEquals(listOf(3, 2), p.rows.map { it.rowIndex })
    }

    @Test
    fun unresolvedFileNames_areCollectedNotSilentlyDropped() {
        val p = plan(listOf(1 to "gone.jpg", 2 to "gone.jpg", 3 to "a.jpg"), local = setOf("a.jpg"))
        assertEquals(listOf("gone.jpg"), p.unresolved)   // 중복 제거
        assertEquals(1, p.rows.size)
    }

    @Test
    fun differentNamesResolvingToSamePath_getDistinctWarning() {
        // 사실과 다른 경고 금지 — "파일명이 중복됨"이 아니라 "같은 이미지로 해석됨"이라고 말한다
        val p = ImageMetaRowResolver.plan(
            listOf(1 to "old.jpg", 2 to "new.jpg"),
            remapByBasename = mapOf("old.jpg" to "/p/x.jpg", "new.jpg" to "/p/x.jpg")
        ) { null }
        assertEquals(1, p.rows.size)
        assertTrue(p.warnings.single().contains("같은 이미지 파일로 해석되어"))
    }

    @Test
    fun remapByBasename_collisionIsDeterministicAndReported() {
        // 복원마다 결과가 흔들리지 않게 원경로 사전순 first-wins로 결정한다
        val r = ImageMetaRowResolver.buildRemapByBasename(
            mapOf("/z/dup.jpg" to "/new/z.jpg", "/a/dup.jpg" to "/new/a.jpg")
        )
        assertEquals("/new/a.jpg", r.byBasename["dup.jpg"])
        assertEquals(1, r.warnings.size)
        assertTrue(r.warnings.single().contains("dup.jpg"))
    }

    @Test
    fun remapByBasename_noCollision_noWarning() {
        val r = ImageMetaRowResolver.buildRemapByBasename(mapOf("/a/x.jpg" to "/new/x.jpg"))
        assertEquals(mapOf("x.jpg" to "/new/x.jpg"), r.byBasename)
        assertTrue(r.warnings.isEmpty())
    }
}
