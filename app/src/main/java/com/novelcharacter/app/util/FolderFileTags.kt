package com.novelcharacter.app.util

/**
 * 파일명 태그 규약 (B-53 ②) — `제목#태그1#태그2.jpg`.
 *
 * 정리 폴더에 넣는 **새 파일**에만 쓴다. 받아오기가 편입하면서 `#` 뒤 조각을 이미지 태그로 단다 —
 * 폴더로 들여온 뒤 앱에서 한 장씩 태그를 다는 수작업을 파일 이름 짓기로 옮긴 것이다.
 *
 * - 첫 조각(`#` 앞)은 제목이라 태그가 아니다. `#바다.jpg`처럼 제목이 비어도 된다.
 * - 조각은 앞뒤 공백을 떼고, 빈 조각과 같은 태그의 반복은 버린다. 순서는 적힌 대로다.
 * - **앱이 내보낸 사본에는 적용하지 않는다.** 사본의 이름은 `[라벨]-[번호].[토큰].[확장자]`이고
 *   라벨은 캐릭터 이름이라, 이름에 `#`이 든 캐릭터의 사본이 태그를 얻으면 안 된다. 그래서
 *   토큰 자리가 있는 이름은 태그를 읽지 않는다(토큰이 사전에 없어 새로 편입되는 사본도 같다).
 * - 폴더 이름의 `#`(동명 캐릭터의 `이름#코드`)과는 자리가 달라 겹치지 않는다 — 여기는 파일 이름만 본다.
 */
object FolderFileTags {
    const val SEPARATOR = '#'

    fun parse(fileName: String): List<String> {
        if (FolderNameToken.tokenCandidateOf(fileName) != null) return emptyList()
        val stem = fileName.substringBeforeLast('.', fileName)
        if (SEPARATOR !in stem) return emptyList()
        return stem.split(SEPARATOR).drop(1).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
