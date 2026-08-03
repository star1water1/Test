package com.novelcharacter.app.util

import java.io.File

/**
 * 엑셀 `대표이미지` 열의 **쓰기·읽기 규약** (B-103 결정 D8 — 순수 로직, JVM 테스트로 검증).
 *
 * `이미지경로` 열이 내부 경로 원문을 그대로 싣는 것과 달리, 이 열은 **사람이 읽고 고칠 수
 * 있어야 값어치가 있다.** 내부 파일명은 `char_<UUID>.jpg` 꼴이라 눈으로 구별이 안 되므로
 * 기본은 파일명이고, 한 행 안에서 파일명이 겹칠 때만 전체 경로로 떨어진다.
 *
 * 읽기는 **거부가 아니라 수용·교정**이다(개발 의도 4번). 다섯 단으로 내려가며 맞춰 보고,
 * 그래도 못 찾으면 **버리지 않고 경고 + 기존 값 유지**로 끝낸다 — 외부에서 편집된 파일의
 * 오류를 이유로 사용자가 정해 둔 대표를 조용히 날리지 않는다.
 */
object RepresentativeImageCell {

    /** 읽기 결과. */
    sealed interface Resolution {
        /** 맞는 이미지를 찾았다. */
        data class Matched(val path: String, val rung: Rung) : Resolution

        /** 빈 칸 = 지정 해제(앱의 기존 왕복 규약과 같다). */
        data object Cleared : Resolution

        /** 열 자체가 없다 — 이 값을 건드리지 않는다. */
        data object Absent : Resolution

        /** 값이 있는데 못 찾았다. **버리지 않는다** — 호출부가 경고를 등재하고 기존 값을 유지한다. */
        data class Unresolved(val raw: String) : Resolution
    }

    /** 몇 번째 단에서 맞았는가 — 진단·시험용. */
    enum class Rung { EXACT, REMAPPED, FILE_NAME, ORDINAL }

    // ── 쓰기 ──

    /**
     * 그 행의 대표 이미지를 셀 값으로. 지정이 없으면 빈 문자열.
     *
     * @param imagePaths 같은 행의 `이미지경로`가 담은 목록.
     */
    fun toCell(representativePath: String?, imagePaths: List<String>): String {
        val index = ImagePathMatch.indexIn(imagePaths, representativePath)
        if (index < 0) {
            // 목록에 없는 지정은 셀에 싣지 않는다 — 실어 봐야 받는 쪽이 맞출 대상이 없고,
            // 없는 이름을 내보내면 그것이 곧 다음 왕복의 '못 찾음' 경고가 된다.
            return ""
        }
        val target = imagePaths[index]
        val name = File(target).name
        // 같은 행 안에서 파일명이 겹치면 이름만으로는 어느 장인지 정해지지 않는다 → 전체 경로.
        val duplicated = imagePaths.count { File(it).name == name } > 1
        return if (duplicated) target else name
    }

    // ── 읽기 ──

    /**
     * 셀 값을 그 행의 이미지 하나로 해석한다.
     *
     * @param cell 열이 없으면 null(= [Resolution.Absent]), 빈 칸이면 ""(= [Resolution.Cleared]).
     * @param imagePaths 재매핑을 **이미 마친** 이 기기의 경로 목록.
     * @param remap 다른 기기에서 온 경로 → 이 기기 경로. 2단이 쓴다.
     */
    fun resolve(
        cell: String?,
        imagePaths: List<String>,
        remap: Map<String, String> = emptyMap()
    ): Resolution {
        if (cell == null) return Resolution.Absent
        val raw = cell.trim()
        if (raw.isEmpty()) return Resolution.Cleared

        // 1단 — 그 행의 경로와 정확히 일치.
        ImagePathMatch.indexIn(imagePaths, raw).takeIf { it >= 0 }?.let {
            return Resolution.Matched(imagePaths[it], Rung.EXACT)
        }

        // 2단 — 재매핑된 경로와 일치(다른 기기에서 온 파일).
        val remapped = remap.entries.firstOrNull { ImagePathMatch.same(it.key, raw) }?.value
        if (remapped != null) {
            ImagePathMatch.indexIn(imagePaths, remapped).takeIf { it >= 0 }?.let {
                return Resolution.Matched(imagePaths[it], Rung.REMAPPED)
            }
        }

        // 3단 — 그 행 안에서 **유일한** 파일명. 겹치면 이름만으로 확정하지 않는다
        //       (`ImageMetaRowResolver`가 "이미지" 시트에서 같은 문제를 같은 방식으로 다룬다).
        val byName = imagePaths.filter { File(it).name == File(raw).name }
        if (byName.size == 1) return Resolution.Matched(byName.first(), Rung.FILE_NAME)

        // 4단 — 1부터 세는 번호("2" → 두 번째 이미지).
        val ordinal = raw.toIntOrNull()
        if (ordinal != null && ordinal >= 1 && ordinal <= imagePaths.size) {
            return Resolution.Matched(imagePaths[ordinal - 1], Rung.ORDINAL)
        }

        // 5단 — 못 찾았다. **버리지 않는다.**
        return Resolution.Unresolved(raw)
    }
}
