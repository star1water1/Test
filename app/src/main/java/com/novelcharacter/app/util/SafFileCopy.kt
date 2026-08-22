package com.novelcharacter.app.util

import java.io.InputStream
import java.io.OutputStream

/**
 * 사용자가 고른 자리(SAF)에 파일 한 벌을 옮겨 쓰는 **세 단계**의 단일 소스.
 *
 * ① **열지 못하면 실패다.** `openOutputStream`은 널을 돌려줄 수 있고(권한 만료·자리가
 *    사라짐·provider 거절), 그것을 `?.use { }`로 흘리면 **아무 일도 안 하고 성공으로
 *    빠져나간다.**
 * ② **실패하면 목적지를 지운다.** `CreateDocument`는 자리를 고르는 순간 이미 **빈 문서를
 *    만들어 둔다.** 안 지우면 멀쩡한 이름의 0바이트(또는 반쪽) 파일이 사용자 폴더에 남고,
 *    그것이 정상 사본과 구별되지 않는다 (R-26).
 * ③ **지웠는지를 답에 담는다.** 지우기도 실패할 수 있으므로 확인 없는 단정을 하지 않는다
 *    (B-225) — 부르는 쪽이 *"지웠습니다"*와 *"남아 있을 수 있습니다"*를 가르는 재료다.
 *
 * **왜 한 곳에 모았는가.** SAF로 파일을 옮기는 커널이 셋(엑셀 내보내기·정리 폴더·백업
 * 내보내기)인데 백업만 ①을 안 갈라 **0바이트 파일을 남기고 "저장되었습니다"라고 말했다.**
 * 넷째 자리가 같은 자리에서 또 틀리지 않게 규약을 함수로 만든다 (R-43).
 *
 * **안드로이드 타입을 참조하지 않는다** — 열기·읽기·지우기를 람다로 받으므로 순수 JVM에서
 * 그대로 돌고([com.novelcharacter.app.util.SafFileCopyTest]가 네 갈래를 전부 잰다),
 * 부르는 쪽은 자기 상위 기계(재시도 보관·전송 등재·크기 검증)를 그대로 유지한다.
 *
 * `OrganizeFolderService`의 두 자리는 이 세 단계 **사이에** 크기 검증과 토큰 규약이
 * 끼어들어(복사 → 검증 → 그다음에만 원본 삭제) 경계가 다르다 — 그쪽은 자기 처분을 든다.
 */
object SafFileCopy {

    sealed class Result {
        /** 목적지에 전부 썼다. */
        object Success : Result()

        /**
         * 쓰지 못했다.
         * @param cause 터진 예외 — 열지 못한 갈래(널)에서는 `null`이다(예외가 아니라 답이었다).
         * @param destinationRemoved 만들어져 있던 목적지 문서를 **실제로** 지웠는가.
         */
        data class Failed(val cause: Exception?, val destinationRemoved: Boolean) : Result()
    }

    /**
     * @param openOutput 목적지 스트림을 연다 — 널이면 열지 못한 것이다.
     * @param openInput 원본 스트림을 연다.
     * @param deleteDestination 목적지 문서를 지운다 — 실제로 지웠으면 `true`.
     */
    fun copyOrDiscard(
        openOutput: () -> OutputStream?,
        openInput: () -> InputStream,
        deleteDestination: () -> Boolean
    ): Result {
        val output = try {
            openOutput()
        } catch (e: Exception) {
            return discard(e, deleteDestination)
        } ?: return discard(null, deleteDestination)
        try {
            output.use { out -> openInput().use { input -> input.copyTo(out) } }
        } catch (e: Exception) {
            return discard(e, deleteDestination)
        }
        return Result.Success
    }

    private fun discard(cause: Exception?, deleteDestination: () -> Boolean): Result.Failed =
        Result.Failed(cause, try { deleteDestination() } catch (_: Exception) { false })
}
