package com.novelcharacter.app.util

import java.io.File

/**
 * 이미지 **경로 대조의 단일 소스** (순수 로직 — JVM 테스트로 검증).
 *
 * "이 경로와 저 경로가 같은 파일인가"를 판정한다. 이 판단은
 * `runCatching { File(p).canonicalPath }.getOrNull() ?: p`라는 한 줄로 저장소 곳곳에
 * 복붙돼 있었고, **실패 처분이 자리마다 같지 않았다.**
 *
 * **2026.08.10(B-106 ⓐ)에 그 복붙을 전부 걷어 이 파일로 모았다.** 걷어내며 드러난 것이
 * 요점이다 — 여섯 자리가 정규화 실패 시 경로를 **조용히 버리고** 있었고, 그 여섯이 하필
 * *보호 집합*(`ImageOwnershipGuard`가 삭제해도 되는가) · *참조 집합*(`StorageAnalyzer`가
 * 고아인가) · *소유자 역맵*(이미지 탭이 미배정으로 그리는가) · **실제로 파일을 지우는
 * `SystemMaintenanceService`의 고아 정리**였다. 즉 정규화가 실패한 경로는 **보호를 잃었다.**
 * 바로 옆 `computeProtected`는 정반대로 *"실패하면 원문 그대로 포함 — 보호가 삭제보다
 * 안전"*이라 적어 두었으니, **같은 한 줄이 자리마다 반대로 동작하고 있었던 셈**이다.
 *
 * **새 코드는 이것만 쓴다.** 걷어내지 않은 예외가 하나 있고 그 사유는 그쪽에 적혀 있다
 * (`excel/ImageZipReport.classify` — 실패 처분이 **반대여야 하는** 자리라 옮기면 가드가
 * 느슨해진다).
 *
 * 규약:
 * - [canonical]은 **절대 던지지 않는다.** 심볼릭 링크 해석은 IO이고 IOException을 낼 수 있어
 *   메인스레드에서 부르는 자리가 있다(어댑터 bind). 실패하면 **원본을 그대로 돌려준다** —
 *   버리지 않는 것이 이 앱의 규약이다(개발 의도 2번: 말없이 유실되지 않는다).
 * - null·공백은 **빈 문자열**로 접는다. `""`은 "지정 없음"의 값이고, 빈 것끼리는 [same]이 아니다
 *   (지정 없음 둘을 "같은 이미지"로 보면 대표 판정이 통째로 틀린다).
 * - [isInside]는 **실패 처분이 반대다** — 위 셋과 갈리는 유일한 함수라 여기 함께 적어 둔다.
 *   대조는 *"모르면 원본을 들고 간다"*가 안전하지만 봉쇄는 *"모르면 막는다"*가 안전하다.
 *   그 함수의 KDoc이 근거를 든다.
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
     * **한 작업짜리 정규화기** — 같은 문자열을 두 번 재지 않는다 (B-175).
     *
     * [canonical]은 `File.canonicalPath`, 즉 **파일 시스템 호출**이다(경로 조각마다 stat이
     * 붙는다). 대결의 몫 가르기처럼 **같은 경로가 판 수만큼 되풀이되는 자리**에서는 그 비용이
     * 그림 수가 아니라 **판 수**에 비례해 붙는다 — 이미지 n장이면 짝이 `n(n-1)/2`이라
     * 한 경로가 대략 n번 재어지고, 그 자리가 하필 **한 판 누를 때마다 다시 도는 경로**다
     * (`DuelPlayFragment`의 다시 계산). 여기 담아 두면 **한 문자열당 한 번**이다.
     *
     * **작업보다 오래 살려 두지 말 것.** 파일이 옮겨지면 이 표의 값이 낡는데 이 객체는 그것을
     * 알 길이 없다 — 한 화면 한 번의 계산 안에서 만들고 버리는 것이 이 클래스의 수명이다.
     * 전역 캐시로 두지 않은 것이 그래서이고, 그 판단은 [canonical]이 *"실패해도 원본을
     * 돌려준다"*와 같은 뿌리다(모르면 버리지 않는다 — 낡은 값을 들고 있느니 다시 잰다).
     */
    class Canonicalizer {
        private val cache = HashMap<String, String>()

        /** [canonical]과 **글자 그대로 같은 답**을 준다 — 다르면 자리마다 대조가 갈린다. */
        fun of(path: String?): String {
            val raw = path?.trim().orEmpty()
            if (raw.isEmpty()) return ""
            return cache.getOrPut(raw) { canonical(raw) }
        }

        /** 지금까지 실제로 잰 문자열 수 — 시험이 *"두 번 재지 않았다"*를 확인하는 자리다. */
        val measured: Int get() = cache.size
    }

    /**
     * 두 경로가 같은 파일을 가리키는가. **둘 중 하나라도 비어 있으면 false다** —
     * "지정 없음"은 무엇과도 같지 않다.
     */
    fun same(a: String?, b: String?): Boolean {
        val ra = a?.trim().orEmpty()
        if (ra.isEmpty()) return false
        // **원문이 같으면 재지 않는다.** `canonical`은 파일 시스템 호출이고(경로 조각마다
        // stat), 같은 문자열은 반드시 같은 답을 내므로 이 지름길은 답을 바꾸지 않는다.
        // 이 함수는 어댑터 bind에서도 불린다 — 스크롤 한 번에 목록 길이만큼 붙던 비용이다.
        if (ra == b?.trim()) return true
        val ca = canonical(ra)
        if (ca.isEmpty()) return false
        return ca == canonical(b)
    }

    /**
     * [paths] 안에서 [target]과 같은 파일의 위치. 없으면 -1.
     *
     * 목록 쪽을 매번 정규화하므로 호출부가 순서를 바꾸거나 걸러도 결과가 흔들리지 않는다.
     *
     * **자리마다 원문을 먼저 본다 — 답은 그대로이고 파일 시스템 호출만 준다.**
     * 원문이 같으면 정규 경로도 반드시 같으므로(같은 입력·같은 함수), 자리 하나의 판정은
     * `원문 일치 || 정규 일치`와 `정규 일치`가 **글자 그대로 같은 답**이다. 걷는 차례를
     * 바꾸지 않는 것이 요점이다 — 목록을 통째로 원문 대조한 뒤 정규로 되짚으면 *"앞자리의
     * 다른 표기가 같은 파일인 경우"*에 위치가 달라진다. [target] 쪽도 **늦게 잰다**:
     * 첫 자리에서 원문으로 걸리면 아예 재지 않는다.
     *
     * 이 함수는 대표 이미지 판정을 지나 **어댑터 bind**에서 불린다(`CharacterAdapter`) —
     * 스크롤 한 번에 목록 길이만큼 stat이 붙던 자리다.
     */
    fun indexIn(paths: List<String>, target: String?): Int {
        val raw = target?.trim().orEmpty()
        if (raw.isEmpty()) return -1
        var ct: String? = null
        for (i in paths.indices) {
            val p = paths[i].trim()
            if (p == raw) return i
            val c = ct ?: canonical(raw).also { ct = it }
            if (c.isEmpty()) return -1
            if (canonical(p) == c) return i
        }
        return -1
    }

    /** [paths] 안에 [target]과 같은 파일이 있는가. */
    fun containedIn(paths: List<String>, target: String?): Boolean = indexIn(paths, target) >= 0

    /**
     * [path]가 [dir] **안**의 것인가 — 앱 저장소 봉쇄 가드 (B-141).
     *
     * 이 저장소는 "이 경로가 이 뿌리 안인가"를 묻는 자리마다 이 한 줄을 **복붙**해 왔고,
     * 뿌리는 둘이다 — **filesDir**(저장 이미지를 읽는 자리)와 **압축 해제 디렉터리**
     * (zip-slip 방어: `WorldPackageImporter`·`ExcelImporter`).
     * **2026.08.10(B-106 ⓐ)에 옛 자리를 전부 걷어 이리로 모았다** — 남은 것은 이 함수뿐이고,
     * 세는 법은 `grep -rn 'canonicalPath.startsWith' --include=*.kt app/src/main/java`다
     * (개수는 적지 않는다 — 값을 적으면 걷어낼 때마다 낡는다. `CLAUDE.md` v1.6이 배운 것).
     * **새 코드는 이것만 쓴다.**
     *
     * **걷어내며 함께 고친 것:** 옛 벌 둘은 `runCatching`이 없어 `canonicalPath`가 던지면
     * *그 항목만*이 아니라 **작업 전체가 죽었다**(`WorldPackageImporter`는 가져오기가,
     * `WorldPackageExporter`는 그 엔티티의 남은 장이 통째로). 이 함수는 던지지 않으므로
     * 이제 막히는 것은 그 한 장뿐이다.
     *
     * **경계 문자를 반드시 붙여 견준다** — `startsWith(root)`만 쓰면 `/files_backup/a.jpg`가
     * `/files`의 안으로 판정된다(형제 디렉터리가 접두어를 공유하는 자리).
     *
     * **실패 처분이 [canonical]과 반대인 이유:** 이 판정의 소비처는 *파일 바이트를 기기 밖으로
     * 내보내도 되는가*이고, 거기서 "모르겠으면 통과"는 곧 유출이다. 그래서 [dir]이 null이거나
     * 정규화가 실패하면 **막는다.** 막힌 것이 조용히 사라지지는 않는다 — 호출측이 개수로
     * 고지한다(R-14). 던지지 않는 것은 [canonical]과 같다.
     */
    fun isInside(path: String?, dir: File?): Boolean {
        if (dir == null) return false
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return runCatching {
            File(raw).canonicalPath.startsWith(dir.canonicalPath + File.separator)
        }.getOrDefault(false)
    }
}
