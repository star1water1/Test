package com.novelcharacter.app.util

import java.io.File

/**
 * 앱 캐시를 훑어 지우는 **순수 판정** — `Context`도 `android.*`도 들지 않는다.
 *
 * ## 왜 [StorageAnalyzer]에서 떼어 냈나
 *
 * 지우는 일은 되돌릴 수 없으므로 **실행으로 잠글 수 있어야 한다.** 그런데
 * [StorageAnalyzer]는 `Context`·`AppDatabase`를 들고 있어 순수 JVM 하네스가 닿지 못한다
 * (이 저장소가 [TransferInterruption][com.novelcharacter.app.excel.TransferInterruption]에서
 * 같은 이유로 판정만 떼어 낸 그 자리와 같은 결이다). 그래서 *"무엇을 지우고 무엇을
 * 남기는가"*만 여기 두고, *"어디를 훑고 무엇이 약속되어 있는가"*는 부르는 쪽이 정한다.
 *
 * 범위와 안전장치의 근거는 [StorageAnalyzer.clearTransferCache]의 KDoc에 있다.
 */
object CacheSweep {

    /** [sweep]의 재귀 상한 — 심링크 순환에서 지우기가 영영 도는 것을 막는다. */
    private const val MAX_SWEEP_DEPTH = 8

    /**
     * 캐시 비우기의 결과 — **지운 것과 지키느라 건너뛴 것을 함께 낸다**(R-14).
     *
     * 건너뛴 수를 안 내면 *"120MB를 비웠습니다"*가 전부인데, 사용자가 화면에서 본 캐시가
     * 1.2GB였다면 그 차이를 설명할 길이 없다 — 남은 것이 *지켜진 것*인지 *못 지운 것*인지
     * 알 수 없고, 그 둘은 사용자가 할 일이 다르다.
     */
    data class Result(
        val freedBytes: Long,
        /** 지운 파일 수. */
        val deletedFiles: Int,
        /** 약속되어 있어 **일부러 남긴** 파일 수(저장을 기다리는 내보내기 등). */
        val keptFiles: Int,
        /** 지우려 했으나 실패한 파일 수 — 조용히 삼키지 않는다. */
        val failedFiles: Int,
    )

    /**
     * 캐시 뿌리를 훑어 지운다 — **순수 함수**라 시험이 실행으로 잠근다.
     *
     * @param root 지울 뿌리. **이 디렉터리 자신은 지우지 않는다**(다음 전송이 곧 쓴다).
     * @param protectedPaths 건드리지 않을 절대 경로. 디렉터리를 넘기면 그 **아래 전부**가 지켜진다.
     *
     * 비워진 하위 디렉터리는 함께 정리한다 — 남겨 두면 `poi-temp`·`world_import_*` 같은
     * 껍데기가 회차마다 쌓인다. **지켜진 것이 하나라도 든 디렉터리는 남긴다.**
     */
    fun sweep(root: File, protectedPaths: Set<String>): Result {
        var freed = 0L
        var deleted = 0
        var kept = 0
        var failed = 0

        // 심링크가 자기 조상을 가리키면 무한 재귀가 된다. 세는 쪽(dirSize)은 한 바퀴 더 도는
        // 것으로 끝나지만 **지우는 쪽은 되돌릴 수 없으므로** 깊이를 막아 둔다.
        // 캐시가 이보다 깊을 일은 없다(가장 깊은 자리가 `world_import_*/images/`다).
        fun walk(dir: File, depth: Int): Boolean {
            if (depth > MAX_SWEEP_DEPTH) return false
            var allGone = true
            val children = dir.listFiles() ?: return false
            for (child in children) {
                val path = child.absolutePath
                if (isProtected(path, protectedPaths)) {
                    kept++
                    allGone = false
                    continue
                }
                if (child.isDirectory) {
                    // **뿌리 밖으로 나가지 않는다.** 캐시 안의 심링크가 바깥(예: `filesDir`)을
                    // 가리키면 재귀가 그 안으로 걸어 들어가 **사용자의 이미지를 지운다** —
                    // `delete()`는 링크만 지우지만 그 전에 내려간 재귀가 이미 목적지를 비운
                    // 뒤다. 깊이 상한은 순환만 막지 탈출을 막지 못한다.
                    //
                    // 판정은 [ImagePathMatch.isInside]가 든다 — 이 저장소에서 **봉쇄만 실패
                    // 처분이 반대인**(모르면 막는다) 유일한 함수이고, 정규화를 손으로 다시
                    // 적으면 실패 처분이 자리마다 갈린다(B-106 ⓐ가 걷어낸 그 복붙이다).
                    if (!ImagePathMatch.isInside(child.absolutePath, root)) {
                        kept++
                        allGone = false
                        continue
                    }
                    val emptied = walk(child, depth + 1)
                    if (emptied && !child.delete()) allGone = false
                    if (!emptied) allGone = false
                } else {
                    val len = child.length()
                    if (child.delete()) {
                        freed += len
                        deleted++
                    } else {
                        failed++
                        allGone = false
                    }
                }
            }
            return allGone
        }

        walk(root, 0)
        return Result(freed, deleted, kept, failed)
    }

    /** 이 경로가 지켜지는가 — 정확히 그 파일이거나, 지켜지는 디렉터리 **아래**에 있으면 참. */
    private fun isProtected(path: String, protectedPaths: Set<String>): Boolean {
        if (path in protectedPaths) return true
        return protectedPaths.any { path.startsWith(it + File.separator) }
    }

}
