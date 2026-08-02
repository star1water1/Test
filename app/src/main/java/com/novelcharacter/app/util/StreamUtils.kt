package com.novelcharacter.app.util

import java.io.InputStream
import java.io.OutputStream

/**
 * 스트림 복사가 취소를 받아들이고 멈췄다.
 *
 * 실패가 아니다 — 호출부는 만들다 만 파일을 지우고 "취소했습니다"만 알린다. 반쯤 쓴 파일을
 * 정상 결과로 넘기면 그것으로 복원할 때 조용히 유실된다(개발 의도 2번 '변수 제어').
 */
class CopyCancelledException : Exception("Copy cancelled by user")

/**
 * 스트림을 복사하되 [limit]을 초과하면 즉시 중단한다.
 * 반환값이 [limit]보다 크면 상한 초과이므로 호출자가 결과 파일을 폐기해야 한다.
 * 크기를 미리 알 수 없는(statSize = -1) 콘텐츠 프로바이더에서도 상한을 강제할 수 있다.
 *
 * [onProgress]는 **누적 복사 바이트**를 받는다(증분이 아니다 — 호출부가 합계를 따로 들고 있다가
 * 어긋나는 자리를 만들지 않는다). 750MB짜리 백업을 SAF에서 받아오는 데 분 단위가 걸리므로
 * 이 구간에도 표시가 필요하다는 것이 B-51의 실사용 근거다(규약 R-26).
 *
 * [isCancelled]가 true를 돌려주면 [CopyCancelledException]을 던진다. 복사 중간에 멈춘 파일은
 * 어차피 쓸 수 없으므로 "중단 시점까지 반영"이라는 선택지가 없다 — 취소는 곧 폐기다.
 */
fun copyWithLimit(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    onProgress: ((Long) -> Unit)? = null,
    isCancelled: (() -> Boolean)? = null
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    // 보고 주기 — 8KB마다 메인 스레드로 올리면 갱신이 복사보다 비싸진다. 1MB마다면
    // 진행도의 MB 눈금(ProgressScale)이 딱 한 칸 움직이는 간격이라 낭비도 누락도 없다.
    var nextReport = ProgressScale.MEGABYTE
    while (true) {
        if (isCancelled?.invoke() == true) throw CopyCancelledException()
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        total += read
        if (total > limit) break
        if (onProgress != null && total >= nextReport) {
            onProgress(total)
            nextReport = total + ProgressScale.MEGABYTE
        }
    }
    // 마지막 한 조각(1MB 미만)도 보고한다 — 없으면 막대가 끝에서 한 칸 모자란 채 멈춘다.
    onProgress?.invoke(total)
    return total
}
