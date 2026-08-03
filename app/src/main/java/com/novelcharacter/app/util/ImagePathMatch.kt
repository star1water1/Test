package com.novelcharacter.app.util

import java.io.File

/**
 * 이미지 **경로 대조의 단일 소스** (순수 로직 — JVM 테스트로 검증).
 *
 * "이 경로와 저 경로가 같은 파일인가"를 판정한다. 지금까지 이 판단은
 * `runCatching { File(p).canonicalPath }.getOrNull() ?: p`라는 한 줄로
 * **여덟 곳에 복붙**돼 있었고(`ImageOwnershipGuard`·`CharacterImageAutoLinker`·
 * `StorageAnalyzer`·`OrganizeFolderService`·`ImageManagerViewModel`·`CharacterDraftPrefs`),
 * 실패 처분(예외 시 원본 반환)이 자리마다 같은지 아무도 보증하지 않았다.
 *
 * **이 파일은 대표 이미지(B-103)가 세우는 새 단일 소스이고, 옛 여덟 곳은 아직 그대로다** —
 * 그쪽을 함께 걷어내는 것은 이 슬라이스 밖이라 백로그 **B-106**에 등재돼 있다
 * (세션 착수 규칙 2번: 범위 밖이면 등재만 하고 진행). **새 코드는 이것만 쓴다.**
 *
 * 규약:
 * - [canonical]은 **절대 던지지 않는다.** 심볼릭 링크 해석은 IO이고 IOException을 낼 수 있어
 *   메인스레드에서 부르는 자리가 있다(어댑터 bind). 실패하면 **원본을 그대로 돌려준다** —
 *   버리지 않는 것이 이 앱의 규약이다(개발 의도 2번: 말없이 유실되지 않는다).
 * - null·공백은 **빈 문자열**로 접는다. `""`은 "지정 없음"의 값이고, 빈 것끼리는 [same]이 아니다
 *   (지정 없음 둘을 "같은 이미지"로 보면 대표 판정이 통째로 틀린다).
 */
object ImagePathMatch {

    /**
     * 대조용 정규 경로. 실패하면 원본(공백 정리만 한 것)을 돌려준다.
     * null·공백이면 빈 문자열.
     */
    fun canonical(path: String?): String {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return runCatching { File(raw).canonicalPath }.getOrNull() ?: raw
    }

    /**
     * 두 경로가 같은 파일을 가리키는가. **둘 중 하나라도 비어 있으면 false다** —
     * "지정 없음"은 무엇과도 같지 않다.
     */
    fun same(a: String?, b: String?): Boolean {
        val ca = canonical(a)
        if (ca.isEmpty()) return false
        return ca == canonical(b)
    }

    /**
     * [paths] 안에서 [target]과 같은 파일의 위치. 없으면 -1.
     *
     * 목록 쪽을 매번 정규화하므로 호출부가 순서를 바꾸거나 걸러도 결과가 흔들리지 않는다.
     */
    fun indexIn(paths: List<String>, target: String?): Int {
        val ct = canonical(target)
        if (ct.isEmpty()) return -1
        return paths.indexOfFirst { canonical(it) == ct }
    }

    /** [paths] 안에 [target]과 같은 파일이 있는가. */
    fun containedIn(paths: List<String>, target: String?): Boolean = indexIn(paths, target) >= 0
}
