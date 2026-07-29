package com.novelcharacter.app.util

/**
 * 정리 폴더 왕복의 **이름 토큰 규약 단일 소스** (순수 로직 — JVM 테스트로 검증).
 *
 * 내보낸 사본이 폴더를 옮겨 다니다 돌아와도 "그게 어느 이미지였는지" 알아보게 하는 것이
 * 이 파일의 전부다. 내용 지문 없이 **파일명 안의 토큰**만으로 해결한다(결정 D4).
 *
 * 규약:
 * - 내부 파일명은 `<접두>_<UUID>.<확장자>`다. **토큰 = 그 UUID의 하이픈 제거·소문자 16진
 *   앞 12자**(`[0-9a-f]{12}`, 48비트 — 수천 장에서 충돌 확률 무시 가능).
 * - 내보내기 파일명은 `[라벨]-[번호].[토큰].[확장자]`. 라벨·번호는 **사람용일 뿐**이고
 *   정체성은 토큰만 진다. 라벨은 파일명 안전 문자로 정리하고 [MAX_LABEL_LENGTH]자로 자른다
 *   (한글은 UTF-8 3바이트라 상한 없이 두면 파일명 255바이트를 넘겨 내보내기가 실패한다).
 * - 12자가 겹치는 파일들은 **그 파일들만** 전체 UUID(32자)로 연장한다(결정적 — 같은 입력이면
 *   같은 결과). 사전에는 유일한 12자와 전체 32자를 **둘 다** 등재해, 연장 전에 내보낸 사본과
 *   연장 후에 내보낸 사본이 모두 되돌아올 수 있게 한다.
 *
 * **접두 목록에 의존하지 않는다.** `StorageAnalyzer.IMAGE_PREFIXES`를 참조하면 Android
 * 계층을 끌어와 순수 테스트가 불가능해지고, 접두가 늘 때마다 두 곳이 드리프트한다. 대신
 * "마지막 `_` 뒤가 UUID 형태인가"만 본다 — `char_`·`img_`·`universe_`·`novel_`은 물론
 * 앞으로 늘어날 접두도 규칙 변경 없이 통과한다.
 *
 * **토큰은 영구 식별자가 아니다(설계 9장 C-1).** 재압축 커밋과 zip 복원은 파일을 새 UUID로
 * 개명한다. 그래서 [buildDictionary]는 개명 별칭(옛 경로 → 새 경로)을 함께 받아 옛 토큰도
 * 현재 경로로 잇는다. 별칭으로도 못 잇는 토큰꼴 파일은 호출부가 **개수를 고지**해야 한다
 * (조용한 중복 편입 금지).
 */
object FolderNameToken {

    /** 짧은 토큰 길이(16진 자릿수). */
    const val TOKEN_LENGTH = 12

    /** 연장 토큰 길이 — UUID 전체(하이픈 제거). */
    const val FULL_TOKEN_LENGTH = 32

    /** 라벨 절단 길이(문자 수). 한글 3바이트 기준으로 96바이트 + 번호·토큰·확장자 여유. */
    const val MAX_LABEL_LENGTH = 32

    /**
     * 캐릭터 폴더명 길이 상한(문자 수). 한글 3바이트 기준 192바이트로 255바이트 안에 든다.
     *
     * 라벨보다 후한 이유: 라벨은 **잘라도 되지만**(정체성은 토큰이 진다) 폴더명은 자르는 순간
     * 받아오기의 정확 일치가 깨져 그 캐릭터로 되돌아오지 못한다. 그래서 폴더명은 자르지 않고,
     * 상한을 넘는 이름은 [isFolderNameSafe]가 **거절**해 내보내기가 고지한다.
     */
    const val MAX_FOLDER_NAME_LENGTH = 64

    /** 라벨이 통째로 지워졌을 때 쓰는 대체 라벨(미배정 이미지의 기본 라벨이기도 하다). */
    const val FALLBACK_LABEL = "이미지"

    /**
     * 캐릭터 폴더명에 코드를 병기할 때 쓰는 구분자 — `홍길동#a1b2c3d4e5f60718`.
     *
     * **왜 필요한가:** 폴더명이 캐릭터 이름뿐이면 동명이인 둘이 같은 폴더명을 요구한다.
     * 파일시스템이 그것을 허용하지 않을뿐더러, 받아오기가 그 폴더를 누구 것인지 정할 수 없다.
     * 그래서 **동명일 때만** 안정 식별자(`Character.code`)를 덧붙인다 — 이름이 유일하면
     * 종전처럼 `홍길동/`으로 깨끗하게 둔다.
     *
     * 엑셀 왕복이 `관련캐릭터코드` 열로 같은 문제를 푸는 것과 같은 수단이며, 규약 R-1
     * ("코드가 대상을 정하고 이름은 확인 수단")의 재사용이다.
     *
     * `#`을 고른 이유: 윈도우·FAT 금지 문자가 아니고([FORBIDDEN_LABEL_CHARS]), 사람 이름에
     * 거의 쓰이지 않아 오해석 여지가 작다. 이름에 `#`이 있어도 **뒤가 코드 형태일 때만**
     * 코드로 읽으므로 안전하다.
     */
    const val CODE_SEPARATOR = '#'

    /** `Character.code` 형태 — `generateEntityCode()`가 만드는 소문자 16진 16자. */
    private val CODE_SHAPE = Regex("^[0-9a-f]{16}$")

    /** 폴더명 해석 결과. [code]가 null이면 이름만 있는 폴더(종전 규약). */
    data class ParsedFolder(val name: String, val code: String?)

    /**
     * 캐릭터 폴더명을 만든다. [code]가 null이면 이름 그대로.
     *
     * 조립 결과가 폴더로 쓸 수 없으면(길이 초과 등) 호출부가 [isFolderNameSafe]로 걸러야 한다 —
     * 여기서 자르지 않는 이유는 [MAX_FOLDER_NAME_LENGTH] 주석과 같다. **자르는 순간 되돌아오지
     * 못한다.**
     */
    fun buildCharacterFolderName(name: String, code: String?): String {
        val trimmed = name.trim()
        if (code.isNullOrBlank()) return trimmed
        return "$trimmed$CODE_SEPARATOR$code"
    }

    /**
     * 폴더명을 (이름, 코드)로 가른다. 마지막 [CODE_SEPARATOR] 뒤가 **코드 형태일 때만** 코드로
     * 읽고, 아니면 통째로 이름이다.
     *
     * 그래서 `홍길동#1`·`C#으로 배우기` 같은 이름은 그대로 이름으로 남는다 — 사용자가 이미
     * 쓰고 있던 폴더명을 이 규약이 빼앗지 않는다(하위 호환).
     */
    fun parseCharacterFolderName(folder: String): ParsedFolder {
        val trimmed = folder.trim()
        val sep = trimmed.lastIndexOf(CODE_SEPARATOR)
        if (sep <= 0 || sep == trimmed.length - 1) return ParsedFolder(trimmed, null)
        val candidate = trimmed.substring(sep + 1)
        if (!CODE_SHAPE.matches(candidate)) return ParsedFolder(trimmed, null)
        return ParsedFolder(trimmed.substring(0, sep).trim(), candidate)
    }

    /** 파일명 금지 문자 — 윈도우·FAT 기준(가장 좁은 쪽에 맞춘다). */
    private val FORBIDDEN_LABEL_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    private val UUID_SHAPE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val SHORT_TOKEN_SHAPE = Regex("^[0-9a-f]{$TOKEN_LENGTH}$")
    private val FULL_TOKEN_SHAPE = Regex("^[0-9a-f]{$FULL_TOKEN_LENGTH}$")

    /**
     * 내부 파일명에서 전체 토큰(32자)을 뽑는다. UUID 형태가 아니면 null(레거시 파일 —
     * 위치 반영이 불가능하므로 내보내기에서 개수 고지 후 제외한다).
     *
     * @param fileName 확장자를 포함한 파일명(경로 아님).
     */
    fun fullTokenOfFileName(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.', fileName)
        val candidate = stem.substringAfterLast('_', stem).lowercase()
        if (!UUID_SHAPE.matches(candidate)) return null
        return candidate.replace("-", "")
    }

    /** [fullTokenOfFileName]의 경로판 — 마지막 `/` 뒤를 파일명으로 본다. */
    fun fullTokenOfPath(path: String): String? =
        fullTokenOfFileName(path.substringAfterLast('/', path))

    /** 파일명이 토큰 규약을 만족하는지(= 위치 반영 가능한 앱 파일인지). */
    fun isTokenizable(path: String): Boolean = fullTokenOfPath(path) != null

    /**
     * 내보내기 파일명에서 **토큰 후보**를 읽는다 — 끝에서 두 번째 점 구간(`이름.토큰.확장자`).
     *
     * 16진 12자·32자만 후보로 인정한다. 그 외(점이 있는 평범한 파일명)는 null이라 신규 파일과
     * 자연히 갈린다. 후보를 돌려줬는데 사전에 없으면 그것이 "토큰꼴이나 사전에 없음" —
     * 신규로 편입하되 **반드시 고지**할 대상이다(C-1).
     */
    fun tokenCandidateOf(fileName: String): String? {
        val parts = fileName.split('.')
        if (parts.size < 3) return null
        val candidate = parts[parts.size - 2].lowercase()
        return if (SHORT_TOKEN_SHAPE.matches(candidate) || FULL_TOKEN_SHAPE.matches(candidate)) {
            candidate
        } else null
    }

    /**
     * 라벨 정리 — 금지 문자·제어문자를 `_`로 바꾸고, 앞뒤 공백·마침표를 떼고,
     * [MAX_LABEL_LENGTH]자로 자른 뒤 다시 다듬는다. 빈 결과는 [FALLBACK_LABEL].
     *
     * 앞뒤 마침표를 떼는 이유: 윈도우가 마침표로 끝나는 이름을 거부하고, 앞 마침표는 일부
     * 파일 관리자에서 숨김 파일이 된다. 라벨 **안쪽**의 마침표는 남긴다 — 해석은 항상
     * "끝에서 두 번째 점"이라 라벨의 점에 영향받지 않는다.
     */
    fun sanitizeLabel(raw: String): String {
        val replaced = buildString(raw.length) {
            for (ch in raw) {
                when {
                    ch in FORBIDDEN_LABEL_CHARS -> append('_')
                    ch.code < 0x20 || ch.code == 0x7F -> append('_')
                    else -> append(ch)
                }
            }
        }
        val trimmed = replaced.trim().trim('.').trim()
        val cut = trimmed.take(MAX_LABEL_LENGTH).trim().trim('.').trim()
        return cut.ifBlank { FALLBACK_LABEL }
    }

    /**
     * 이 이름을 **고치지 않고 그대로** 폴더명으로 쓸 수 있는가 — 캐릭터 폴더의 왕복 가능 판정.
     *
     * 라벨과 규칙이 다른 이유는 [MAX_FOLDER_NAME_LENGTH]에 적었다: 폴더명은 받아오기가
     * 캐릭터 이름과 **정확 일치**로 되돌리는 축이라, 정리하거나 자르면 그 폴더는 더 이상 그
     * 캐릭터를 가리키지 않는다("기타 이름"으로 읽혀 엉뚱한 링크 묶음이 된다). 그래서 고쳐서
     * 내보내는 대신, 고쳐야 하는 이름은 여기서 걸러 내보내기가 **개수와 이름을 고지**한다.
     *
     * 앞뒤 공백은 문제가 아니다 — 받아오기가 폴더명을 트림해 맞추므로 트림된 이름으로
     * 폴더를 만들면 그대로 돌아온다.
     */
    fun isFolderNameSafe(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_FOLDER_NAME_LENGTH) return false
        if (trimmed.startsWith('.') || trimmed.endsWith('.')) return false
        return trimmed.none { it in FORBIDDEN_LABEL_CHARS || it.code < 0x20 || it.code == 0x7F }
    }

    /** 확장자 정리 — 앞 점 제거·소문자·비어 있으면 `jpg`. */
    fun normalizeExtension(raw: String): String {
        val cleaned = raw.removePrefix(".").trim().lowercase()
        return cleaned.ifBlank { "jpg" }
    }

    /**
     * 내보내기 파일명 조립 — `[라벨]-[번호].[토큰].[확장자]`.
     *
     * @param index 폴더 안 순번(1부터). 2자리로 채우고 100 이상은 자연히 3자리가 된다.
     */
    fun exportFileName(label: String, index: Int, token: String, extension: String): String {
        val safeLabel = sanitizeLabel(label)
        val number = if (index < 10) "0$index" else index.toString()
        return "$safeLabel-$number.$token.${normalizeExtension(extension)}"
    }

    /**
     * 토큰 사전 — 내보내기가 쓸 "경로 → 토큰"과 받아오기가 쓸 "토큰 → 경로"를 함께 만든다.
     *
     * @param tokenByPath 내보낼 때 그 파일에 심을 토큰(12자, 겹치면 32자로 연장).
     *        UUID 형태가 아닌 레거시 파일은 실리지 않는다.
     * @param pathByToken 받아올 때의 조회표. 유일한 12자·전체 32자·개명 별칭이 모두 실린다.
     * @param legacyPaths 토큰을 만들 수 없는 파일들(내보내기 제외 대상 — 개수 고지용).
     */
    data class Dictionary(
        val tokenByPath: Map<String, String>,
        val pathByToken: Map<String, String>,
        val legacyPaths: List<String>
    )

    /**
     * 앱 관리 파일 목록으로 사전을 만든다.
     *
     * @param paths 현재 존재하는 앱 관리 이미지 경로 전체(호출부가 무엇을 실을지 정한다 —
     *        예: 재압축 임시 파일은 빼고 넘긴다).
     * @param renameAliases 개명 별칭(옛 경로 → 새 경로). 연쇄 개명(A→B→C)도 따라간다.
     *        최종 경로가 [paths]에 없으면(삭제된 파일) 그 별칭은 버린다.
     */
    fun buildDictionary(
        paths: Collection<String>,
        renameAliases: Map<String, String> = emptyMap()
    ): Dictionary {
        val livePaths = LinkedHashSet(paths)

        // 1) 전체 토큰별로 경로를 모은다. 전체 UUID가 겹치는 일은 없지만(UUID의 정의),
        //    같은 파일이 두 번 실려 오는 입력은 방어적으로 접는다.
        val byFullToken = LinkedHashMap<String, String>()
        val legacy = ArrayList<String>()
        for (path in livePaths) {
            val full = fullTokenOfPath(path)
            if (full == null) legacy.add(path) else byFullToken.putIfAbsent(full, path)
        }

        // 2) 짧은 토큰이 겹치는 무리를 찾는다 — 겹친 것들만 연장한다(결정적).
        val shortGroups = LinkedHashMap<String, MutableList<String>>()
        for (full in byFullToken.keys) {
            shortGroups.getOrPut(full.take(TOKEN_LENGTH)) { mutableListOf() }.add(full)
        }

        val tokenByPath = LinkedHashMap<String, String>()
        val pathByToken = LinkedHashMap<String, String>()
        for ((short, fulls) in shortGroups) {
            val unique = fulls.size == 1
            for (full in fulls) {
                val path = byFullToken.getValue(full)
                tokenByPath[path] = if (unique) short else full
                // 조회는 항상 전체 토큰을 받아 준다 — 연장 전후에 내보낸 사본이 모두 돌아온다.
                pathByToken[full] = path
                if (unique) pathByToken[short] = path
            }
        }

        // 3) 개명 별칭 — 옛 파일명의 토큰을 현재 경로로 잇는다. 살아 있는 토큰을 덮지 않는다
        //    (현재 파일이 언제나 우선).
        for ((oldPath, _) in renameAliases) {
            val finalPath = resolveAlias(oldPath, renameAliases, livePaths) ?: continue
            val oldFull = fullTokenOfPath(oldPath) ?: continue
            pathByToken.putIfAbsent(oldFull, finalPath)
            val oldShort = oldFull.take(TOKEN_LENGTH)
            // 짧은 토큰은 현재 파일 중 유일할 때만 이어 준다 — 겹치면 어느 쪽인지 알 수 없다.
            if (shortGroups[oldShort] == null) pathByToken.putIfAbsent(oldShort, finalPath)
        }

        return Dictionary(tokenByPath, pathByToken, legacy)
    }

    /** 별칭 연쇄를 따라 현재 살아 있는 경로를 찾는다. 순환·과도한 길이는 포기(null). */
    private fun resolveAlias(
        start: String,
        aliases: Map<String, String>,
        livePaths: Set<String>
    ): String? {
        var current = aliases[start] ?: return null
        val seen = HashSet<String>()
        seen.add(start)
        var hops = 0
        while (hops < MAX_ALIAS_HOPS) {
            if (current in livePaths) return current
            if (!seen.add(current)) return null
            current = aliases[current] ?: return null
            hops++
        }
        return null
    }

    private const val MAX_ALIAS_HOPS = 16
}
