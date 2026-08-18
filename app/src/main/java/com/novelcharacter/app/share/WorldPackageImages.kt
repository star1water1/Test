package com.novelcharacter.app.share

/**
 * 월드패키지 이미지 엔트리의 단일 소스 — **이름을 짜는 자리**와 **그 장을 복원해도 되는가**를
 * 한 자리에 둔다(B-234).
 *
 * **왜 한 자리인가:** 종전에는 이름을 두 쪽이 따로 지었다 — 내보내기는 접두사에 `images/`를
 * 이미 붙여 들고 다녔고(`"images/${id}_"`), 가져오기는 접두사를 `"${id}_"`로 들고 부를 때마다
 * [WorldPackageEntries.IMAGES_PREFIX]를 앞에 붙였다. 같은 이름이 나오지만 **규약이 둘이라**,
 * 한쪽만 바뀌면 엔트리가 통째로 안 잡힌다. 이제 둘 다 *엔티티 접두사*(`3_`·`universe_`·
 * `novel_5_`)만 들고 이름은 여기서 나온다.
 *
 * **잘린 장(truncated)이 이 객체의 본체다.** 내보내기는 원본을 연 뒤에 엔트리를 열지만
 * (B-225), `copyTo`가 **중간에** 던지는 갈래는 남는다 — 그때 `closeEntry()`가 지금까지 쓴
 * 바이트로 엔트리를 정상 종료하므로 **zip은 온전하고 내용만 잘린 이미지**가 된다.
 * 임포터는 장을 **위치**(`{접두사}{i}.jpg`)로 찾으므로 결번이라야 "유실"로 세는데, 이 엔트리는
 * 결번이 아니라서 **잘린 그림이 조용히 복원됐다.** 그래서 내보내기가 잘린 장의 이름을
 * `image_failures.json`에 함께 싣고, 가져오기가 그것을 **결번처럼** 다룬다.
 *
 * **바이트를 검증하지 않는 것은 성능이다(사용자 제1원칙 · 개발 의도 1번).** 받는 쪽이 장마다
 * 내용을 열어 보면 그 비용이 **정상 경로의 장마다** 붙는데, 막는 것은 드문 중간 실패다.
 * 보내는 쪽은 실패를 이미 아는 자리라 적는 비용이 0이다(확정 19장 5번 — 갈래 ⓐ).
 */
object WorldPackageImages {

    /**
     * 엔트리 확장자. 내용 바이트는 원본 그대로이고 소비자는 내용으로 판별한다 —
     * 이 표기는 v1 형식과의 호환을 위해 고정이다.
     */
    const val EXTENSION = ".jpg"

    /**
     * `images/{엔티티접두사}{i}.jpg` — 내보내기가 쓰고 가져오기가 찾는 그 이름.
     *
     * @param entityPrefix `3_`(캐릭터 id) · `universe_` · `novel_5_`(작품 id).
     *   **`images/`를 포함하지 않는다** — 붙이는 자리가 여기 하나다.
     */
    fun entryName(entityPrefix: String, index: Int): String =
        "${WorldPackageEntries.IMAGES_PREFIX}$entityPrefix$index$EXTENSION"

    /**
     * `image_failures.json`이 든 이름들을 집합으로 만든다. 공란·null은 버린다 —
     * 손편집·구버전 파일에서 나올 수 있고, 빈 이름은 어떤 엔트리도 가리키지 않는다.
     */
    fun truncatedSet(raw: List<String?>?): Set<String> =
        raw?.asSequence()
            ?.filterNotNull()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    /**
     * 이 장을 복원해도 되는가. **잘린 장은 엔트리가 있어도 없는 것으로 본다** — 그래야
     * 가져오기의 나머지 갈래가 그대로 산다: 같은 기기 재가져오기면 원본을 재사용하고,
     * 그것도 없으면 "잘려서 제외했다"로 **세어 고지된다**(무통보 유실 금지).
     */
    fun isRestorable(entryName: String, entryExists: Boolean, truncated: Set<String>): Boolean =
        entryExists && entryName !in truncated
}
