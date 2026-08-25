package com.novelcharacter.app.util

import com.google.gson.Gson

/**
 * 엑셀 '이미지경로' 칸의 **표기 규약** — 순수 판정(JVM 시험이 왕복을 잠근다).
 *
 * ## 무엇이 문제였나 (2026.08.25 사용자 파일)
 *
 * 이 칸은 앱이 든 절대경로 배열을 **글자 그대로** 실었다:
 *
 * ```
 * ["/data/user/0/com.novelcharacter.app/files/img_9eb0e4c1-….jpg", …]
 * ```
 *
 * 경로 하나가 86자이고 그중 **42자가 모든 항목에 똑같이 붙는 접두**다. 실측에서 한 캐릭터의
 * 68장이 한 칸에 6,053자를 썼고, 엑셀 셀 상한(`EXCEL_CELL_TEXT_LIMIT` — 32,767자)까지
 * **약 368장**이 남는다. 넘으면 내보내기가 자르는데 **잘린 JSON은 배열로 읽히지 않아**
 * 그 칸이 통째로 무용해진다(가져오기는 기존 배정을 지키고 경고를 낸다 — 유실은 아니지만
 * 사용자가 그 열로 할 수 있는 일이 없어진다).
 *
 * 같은 부류를 이 저장소가 이미 한 번 만났다: `DuelGradeRef.MAX_CONFIG_CHARS`가
 * *"config는 셀 하나에 실리고, 잘리면 JSON이 깨져 등급 표까지 함께 잃는다"*를 근거로 상한을
 * 셀 상한의 절반 아래로 잡았다. 이미지는 **자를 수 없는 데이터**라 같은 처방을 못 쓴다.
 * 그래서 `DropdownListLimits`가 고른 쪽을 고른다 — **담는 방법을 바꾼다.**
 *
 * ## 파일명으로 담는다
 *
 * 앱의 그림은 전부 `filesDir` **루트**에 `<prefix>_<UUID>.jpg`로 산다
 * (`ImageImportHelper` — *"파일명 규약은 기존과 동일: filesDir 루트"*). 그래서 파일 이름이
 * 곧 그 그림의 정체이고, 이 저장소는 이미 두 자리에서 그 사실 위에 서 있다:
 * '이미지' 시트의 정체 열이 **파일명**이고(`importImageMeta` — *"절대경로는 기기 간 이식성
 * 없음"*), 같은 행의 '대표이미지' 칸도 **파일명**이다. **한 행 안에서 두 열이 같은 그림을
 * 다른 표기로 부르고 있었던 셈이다.**
 *
 * 얻는 것 둘:
 *  - 상한이 대략 **두 배**로 는다(경로 86자 → 파일명 44자 · 셀 하나에 368장 → 697장).
 *  - **기기에 매인 글자가 파일에서 사라진다** — `/data/user/0/<패키지>/files/`는 그 기기의
 *    사정이지 사용자의 데이터가 아니다.
 *
 * ## 옛 파일도 그대로 들어온다
 *
 * [fromCell]은 **둘 다 받는다**: `/`가 든 토큰은 옛 절대경로로, 없는 토큰은 파일명으로 읽는다.
 * 그래서 이 판 이전에 내보낸 파일도, 사용자가 손으로 절대경로를 적은 파일도 종전과 같이 들어온다.
 */
object ImagePathCell {

    private val gson = Gson()

    /** 경로에서 파일 이름만. `/`가 없으면 받은 글자 그대로다. */
    fun fileName(path: String): String = path.trim().substringAfterLast('/')

    /**
     * 셀에 적을 글자 — **파일명 배열**. 규약의 두 단은 종전 `cellText`에서 그대로 잇는다:
     * 목록으로 안 읽히는 값은 **원문 그대로**(내보내기가 앱의 글자를 지우면 그 백업으로는
     * 되돌릴 수 없다), 읽혀서 비었으면 **빈 칸**(편집 가능한 칸에 `[]`를 세우지 않는다).
     *
     * 한 가지가 달라진다: 종전에는 읽히는 목록을 **원문 문자열 그대로** 실었는데 이제
     * [CharacterRepresentativeImage.paths]가 낸 목록을 다시 적는다. 그래서 **빈 원소가
     * 떨어져 나간다**(`["", "/a/b.jpg"]` → `["b.jpg"]`). 유실이 아니다 — 빈 경로는 그림이
     * 아니고, 앱의 모든 읽는 자리가 이미 그 함수를 지나 그것을 걸러 왔다.
     */
    fun toCell(imagePathsJson: String?): String {
        val raw = imagePathsJson.orEmpty()
        if (raw.isBlank()) return ""
        if (!CharacterRepresentativeImage.isPathListJson(raw)) return raw
        val names = CharacterRepresentativeImage.paths(raw).map { fileName(it) }
        return if (names.isEmpty()) "" else gson.toJson(names)
    }

    /**
     * 파일명으로 쓸 수 있는 글자인가 — **디렉터리를 벗어나는 이름을 막는다.**
     *
     * [fromCell]이 파일명을 `filesDir` 아래로 붙이므로, `..`이 그대로 통과하면 셀 한 칸으로
     * 앱 저장소 밖을 가리킬 수 있다(`ImageImportHelper.isInside`가 막는 것과 같은 부류의
     * 위험이고, `ImagePathMatch.isInside`의 KDoc이 *"모르면 막는다"*를 이 축의 규약으로 적어
     * 두었다). 여기서 걸린 토큰은 [fromCell]이 **원문 그대로** 남긴다 — 버리지 않는다.
     */
    fun isPlainFileName(token: String): Boolean =
        token.isNotBlank() &&
            token != "." && token != ".." &&
            !token.contains('/') && !token.contains('\\')

    /**
     * 셀의 글자 → 저장할 경로 목록(JSON 배열 문자열).
     *
     * @param resolveName 파일명 하나를 이 기기의 절대경로로. null을 돌려주면 원문 그대로 남긴다.
     * @param remapPath 옛 절대경로 토큰의 재매핑(zip 복원) — 종전 동작 그대로다.
     *
     * 읽을 수 없는 글자는 여기서 판정하지 않는다 — 부르는 쪽이 이미
     * [CharacterRepresentativeImage.isPathListJson]으로 가르고 *기존 유지 + 경고*로 처분한다.
     */
    fun fromCell(
        cellJson: String,
        resolveName: (String) -> String?,
        remapPath: (String) -> String = { it }
    ): String {
        if (cellJson.isBlank() || cellJson == "[]") return "[]"
        val tokens = runCatching {
            gson.fromJson(cellJson, Array<String>::class.java)?.filterNotNull()
        }.getOrNull()
        if (tokens == null) {
            // 레거시: 단일 경로 문자열 → 배열로. 종전 `remapImagePaths`의 catch 갈래와 같다.
            return gson.toJson(listOf(resolveToken(cellJson, resolveName, remapPath)))
        }
        return gson.toJson(tokens.map { resolveToken(it, resolveName, remapPath) })
    }

    private fun resolveToken(
        token: String,
        resolveName: (String) -> String?,
        remapPath: (String) -> String
    ): String {
        val raw = token.trim()
        if (raw.isEmpty()) return token
        // `/`가 들었으면 옛 표기(절대경로)다 — 종전 그대로 재매핑만 한다.
        if (raw.contains('/')) return remapPath(raw)
        if (!isPlainFileName(raw)) return token
        return resolveName(raw) ?: token
    }
}
