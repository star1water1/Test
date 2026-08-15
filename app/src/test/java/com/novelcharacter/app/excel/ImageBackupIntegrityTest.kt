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

    // ── [B-225] 참조 목록을 못 읽은 항목: 세지 못한 것을 '없는 것'으로 만들지 않는다 ──

    private fun complete(unreadable: Int = 0) = ImageZipReport(
        requested = true, created = true, referencedCount = 4, includedCount = 4,
        unreadableRefCount = unreadable
    )

    @Test
    fun completeBackupClaim_isBlockedWhenSomeReferencesCouldNotBeRead() {
        // 핵심 회귀: 손상된 imagePaths·조회 실패는 참조 집합에서 조용히 빠지므로 제외 계수가
        // 0이 된다. 그 상태로 '완전한 백업입니다'가 나가면 사용자는 그 백업만 믿고 원본을
        // 지울 수 있다 — 읽지 못한 항목의 이미지는 담기지 않았는데도.
        assertEquals(listOf(ImageNoticeKind.COMPLETE_BACKUP), complete().noticeKinds(isCompleteBackup = true))
        assertEquals(
            listOf(ImageNoticeKind.REFS_UNREADABLE),
            complete(unreadable = 2).noticeKinds(isCompleteBackup = true)
        )
    }

    @Test
    fun unreadableReferences_neverEnterTheLossCounts() {
        // 몇 장인지 모르는 것을 아는 척 세면 "0장 누락"이라는 참말이 거짓 결론을 만든다.
        val r = complete(unreadable = 3)
        assertEquals(0, r.excludedCount)
        assertFalse(r.hasLoss)
        assertTrue(r.referencesIncomplete)
        // 불변식은 그대로 성립한다 — 못 읽은 항목은 referencedCount에도 들지 않았기 때문이다
        assertEquals(r.referencedCount, r.includedCount + r.excludedCount)
    }

    @Test
    fun lossAndUnreadable_areBothSaidNotOneInsteadOfTheOther() {
        // 손실 문구가 이미 떴어도 그 숫자가 하한이라는 사실은 따로 말해야 한다.
        val r = ImageZipReport(
            requested = true, created = true, referencedCount = 10, includedCount = 7,
            missingCount = 3, unreadableRefCount = 1
        )
        assertEquals(
            listOf(ImageNoticeKind.INCOMPLETE, ImageNoticeKind.REFS_UNREADABLE),
            r.noticeKinds(isCompleteBackup = true)
        )
    }

    @Test
    fun noImagesClaim_alsoRequiresHavingReadEveryReference() {
        // "앱에 저장된 이미지가 없어…"는 참조를 전부 읽었을 때만 할 수 있는 말이다.
        val empty = ImageZipReport(requested = true, created = false, referencedCount = 0)
        assertEquals(listOf(ImageNoticeKind.NO_IMAGES), empty.noticeKinds(isCompleteBackup = false))
        assertEquals(
            listOf(ImageNoticeKind.REFS_UNREADABLE),
            empty.copy(unreadableRefCount = 1).noticeKinds(isCompleteBackup = false)
        )
    }

    @Test
    fun notRequested_saysNothingEvenWithUnreadableReferences() {
        // 사실과 다른 경고 금지 — 이미지를 요청하지 않은 내보내기는 이미지 얘기를 하지 않는다
        val r = ImageZipReport(requested = false, unreadableRefCount = 5, missingCount = 5)
        assertFalse(r.referencesIncomplete)
        assertTrue(r.noticeKinds(isCompleteBackup = true).isEmpty())
    }

    @Test
    fun noneIncluded_winsOverIncomplete() {
        val r = ImageZipReport(
            requested = true, created = false, referencedCount = 3, includedCount = 0, missingCount = 3
        )
        assertEquals(listOf(ImageNoticeKind.NONE_INCLUDED), r.noticeKinds(isCompleteBackup = true))
    }

    @Test
    fun lossReasons_dropZerosAndKeepAFixedOrder() {
        // 0을 함께 늘어놓으면 어디를 봐야 하는지가 묻힌다. 반대로 뭉뚱그리면 처방이 갈린다(R-39).
        val r = ImageZipReport(
            requested = true, created = true, referencedCount = 6, includedCount = 0,
            missingCount = 1, outsideAppDirCount = 2, failedCount = 3
        )
        assertEquals(
            listOf(
                ImageLossReason.MISSING to 1,
                ImageLossReason.OUTSIDE_APP_DIR to 2,
                ImageLossReason.FAILED to 3
            ),
            r.lossReasons()
        )
        assertEquals(
            listOf(ImageLossReason.FAILED to 3),
            r.copy(missingCount = 0, outsideAppDirCount = 0).lossReasons()
        )
        assertTrue(ImageZipReport(requested = true, created = true).lossReasons().isEmpty())
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
