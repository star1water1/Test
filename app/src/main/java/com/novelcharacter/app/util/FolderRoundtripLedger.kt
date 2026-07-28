package com.novelcharacter.app.util

/**
 * 정리 폴더 왕복이 기억해야 하는 두 장부의 **순수 로직** (Android 무의존 — JVM 테스트로 검증).
 * 실제 저장은 [FolderRoundtripPrefs](SharedPreferences)가 맡는다 — DB 스키마는 건드리지 않는다.
 *
 * ## 장부 ①: 이동 실패 지문
 *
 * 처리한 원본은 `_처리됨/`으로 옮기는 것이 중복 방지의 본체다(결정 D5). 하지만 SAF 권한·이름
 * 충돌로 이동이 실패할 수 있고, 그때 파일을 그대로 두면 **다음 스캔이 같은 파일을 또 편입**한다.
 * 그래서 이동에 실패한 파일의 지문(상대경로+크기+수정 시각)을 남겨 다음 스캔에서 건너뛴다.
 * 내용 해시가 아니라 메타데이터인 이유는 수백 장을 읽지 않기 위해서다 — 같은 자리에 같은
 * 크기·시각의 다른 파일을 놓는 경우는 실질적으로 없고, 있어도 결과는 "한 번 덜 편입"이다.
 *
 * ## 장부 ②: 개명 별칭
 *
 * 토큰은 파일명의 UUID에서 나오는데, 앱이 파일을 **새 UUID로 개명하는 경로가 둘** 있다
 * (재압축 커밋, zip 복원의 경로 재배치). 개명 전에 내보낸 사본이 돌아오면 토큰이 사전에 없어
 * 중복 편입된다(설계 9장 C-1). 두 개명 지점이 여기에 (옛 경로 → 새 경로)를 남기면
 * [FolderNameToken.buildDictionary]가 옛 토큰도 현재 파일로 이어 준다.
 *
 * ## 두 장부에 공통인 규칙
 *
 * **상한이 있고 오래된 것부터 버린다.** 상한 없는 장부는 "느리기만 하고 틀리지는 않는" 부채가
 * 아니라, 앱을 오래 쓸수록 커지는 실제 비용이다(매 스캔이 전량을 읽는다). 넘칠 때 버리는 쪽이
 * 오래된 항목인 이유: 지문은 오래될수록 그 파일이 이미 사라졌을 확률이 높고, 별칭은 오래될수록
 * 그 사본이 아직 폴더에 남아 있을 확률이 낮다.
 */
object FolderRoundtripLedger {

    /** 장부 하나가 보관하는 최대 항목 수. */
    const val DEFAULT_CAP = 2000

    private const val RECORD_SEPARATOR = '\n'
    private const val FIELD_SEPARATOR = '\t'

    /**
     * 지문 문자열 — 상대경로·크기·수정 시각. 구분자로 쓰는 문자는 경로에서 제거해
     * 직렬화가 깨지지 않게 한다(SAF 이름에 탭·줄바꿈이 들어갈 여지를 차단).
     */
    fun fingerprintOf(relativePath: String, size: Long, modifiedAt: Long): String {
        val safePath = relativePath.replace(RECORD_SEPARATOR, ' ').replace(FIELD_SEPARATOR, ' ')
        return "$safePath|$size|$modifiedAt"
    }

    /**
     * 목록에 항목을 덧붙이되 상한을 지킨다. 이미 있는 항목은 **맨 뒤로 옮긴다**(최근 사용) —
     * 계속 이동에 실패하는 파일이 상한 압박에 밀려 사라지고 다시 중복 편입되는 일을 막는다.
     */
    fun appendBounded(current: List<String>, add: Collection<String>, cap: Int = DEFAULT_CAP): List<String> {
        if (cap <= 0) return emptyList()
        val merged = LinkedHashSet<String>(current)
        for (item in add) {
            merged.remove(item)
            merged.add(item)
        }
        val list = merged.toList()
        return if (list.size <= cap) list else list.subList(list.size - cap, list.size)
    }

    /**
     * 개명 별칭을 기록한다.
     *
     * 연쇄를 **기록 시점에 접는다** — 이미 `X → oldPath`가 있으면 `X → newPath`로 고쳐 쓴다.
     * 그래야 A가 세 번 개명돼도 항목이 하나로 유지되고(상한 절약), 조회가 한 번에 끝난다.
     * 자기 자신을 가리키는 별칭(경로 무변경)은 담지 않는다.
     */
    fun putRenameBounded(
        current: Map<String, String>,
        oldPath: String,
        newPath: String,
        cap: Int = DEFAULT_CAP
    ): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>(current.size + 1)
        if (oldPath == newPath || oldPath.isEmpty() || newPath.isEmpty()) {
            result.putAll(current)
            return trimToCap(result, cap)
        }
        for ((key, value) in current) {
            if (key == oldPath) continue          // 아래에서 맨 뒤에 새로 넣는다
            result[key] = if (value == oldPath) newPath else value
        }
        result[oldPath] = newPath
        return trimToCap(result, cap)
    }

    private fun trimToCap(map: LinkedHashMap<String, String>, cap: Int): LinkedHashMap<String, String> {
        if (cap <= 0) return LinkedHashMap()
        if (map.size <= cap) return map
        val iterator = map.keys.iterator()
        var toDrop = map.size - cap
        while (toDrop > 0 && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
            toDrop--
        }
        return map
    }

    fun encodeList(items: List<String>): String = items.joinToString(RECORD_SEPARATOR.toString())

    fun decodeList(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RECORD_SEPARATOR).filter { it.isNotEmpty() }
    }

    fun encodeMap(map: Map<String, String>): String =
        map.entries.joinToString(RECORD_SEPARATOR.toString()) { "${it.key}$FIELD_SEPARATOR${it.value}" }

    /** 깨진 줄은 조용히 버린다 — 장부는 캐시이고, 없으면 한 번 더 일할 뿐 유실이 아니다. */
    fun decodeMap(raw: String?): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (raw.isNullOrEmpty()) return result
        for (line in raw.split(RECORD_SEPARATOR)) {
            if (line.isEmpty()) continue
            val index = line.indexOf(FIELD_SEPARATOR)
            if (index <= 0 || index == line.length - 1) continue
            result[line.substring(0, index)] = line.substring(index + 1)
        }
        return result
    }
}
