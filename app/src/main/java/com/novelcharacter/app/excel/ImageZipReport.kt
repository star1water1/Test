package com.novelcharacter.app.excel

import java.io.File

/**
 * 이미지 수록 결과 — "무엇이 왜 담기지 않았는지"를 사실 그대로 집계한다.
 *
 * 이미지가 빠진 백업을 완전한 백업으로 오인해 원본 기기를 초기화하면 이미지가 영구 소멸한다.
 * 따라서 무음 제외는 그 자체로 결함이다(검증 → 경고 → 교정 경로 안내).
 * 불변식: referencedCount == includedCount + excludedCount
 *
 * **엑셀 ZIP 래핑과 월드패키지가 같은 벌을 쓴다**(B-225). 종전에는 엑셀 쪽만 이 집계를 들고
 * 월드패키지는 계수도 고지도 없었는데, 두 경로가 하는 일이 같다 —
 * *기기 밖으로 나가는 산물에 이미지를 싣고, 싣지 못한 것을 사유별로 말한다.*
 * 벌을 갈라 두면 한쪽에만 손실 고지가 자라고 다른 쪽은 침묵으로 남는다(감사 5장의 비대칭).
 */
data class ImageZipReport(
    /** 이미지 포함이 요청되었는가. false면 호출부는 아무것도 알리지 않는다(사실과 다른 경고 금지). */
    val requested: Boolean = false,
    /**
     * 산출물이 실제로 만들어졌는가. 엑셀 래핑에서 false면 호출부는 XLSX를 그대로 쓴다.
     * 월드패키지는 패키지 파일 자체가 언제나 만들어지므로 늘 true다.
     */
    val created: Boolean = false,
    /**
     * 이 산출물이 싣기로 훑은 참조의 총수.
     *
     * **세는 단위가 경로별인지 장별인지는 산출물이 정한다**(B-225 콜드 검토 — 벌을 합치면서
     * 이 KDoc이 엑셀 쪽만 서술하고 있었다):
     * - **엑셀 래핑**은 *경로*를 센다 — 엔티티(캐릭터/세계관/작품) ∪ 라이브러리(image_meta)를
     *   집합으로 모으므로 **중복이 제거된다**(같은 파일을 두 캐릭터가 가리켜도 ZIP 엔트리는 하나).
     * - **월드패키지**는 *쓴 장*을 센다 — 엔트리 이름이 `{엔티티}_{인덱스}`라 같은 경로가 두
     *   엔티티에 있으면 **엔트리도 둘**이다. 그쪽에서 중복을 접으면 계수가 실물과 어긋난다.
     *
     * 어느 쪽이든 불변식(`referencedCount == includedCount + excludedCount`)과 사유별 계수의
     * 뜻은 같다 — 갈리는 것은 *무엇을 한 건으로 보는가*뿐이다.
     */
    val referencedCount: Int = 0,
    /** 산출물에 실제로 담긴 이미지 수 */
    val includedCount: Int = 0,
    /** 참조되나 파일이 존재하지 않아 담지 못한 수 */
    val missingCount: Int = 0,
    /** 앱 내부 저장소 밖을 가리켜 담지 못한 수 */
    val outsideAppDirCount: Int = 0,
    /** 읽기·압축 중 오류로 담지 못한 수 */
    val failedCount: Int = 0,
    /**
     * **참조 목록 자체를 읽지 못한 항목 수**(B-225 — 캐릭터·세계관·작품 행의 `imagePaths`가
     * 깨졌거나 라이브러리 조회가 실패한 것).
     *
     * 이 항목들의 이미지는 **[referencedCount]에도 [excludedCount]에도 들지 못한다** —
     * 몇 장인지 알 방법이 없기 때문이다. 그래서 이 값이 0이 아니면 [referencedCount]는
     * 총량이 아니라 **하한**이고, 그 상태로 '완전한 백업'이라 말할 수 없다([referencesIncomplete]).
     *
     * 세는 단위가 '장'이 아니라 '항목'인 것은 일부러다 — 사유를 아는 자리가 아는 것이
     * 거기까지다(R-39: 세는 곳은 사유를 아는 곳이고, 모르는 것을 아는 척 세지 않는다).
     */
    val unreadableRefCount: Int = 0,
    /** 제외 항목의 표본 파일명 (최대 [SAMPLE_LIMIT]개) — 내부 절대경로 전체는 사용자에게 의미가 없다 */
    val sampleNames: List<String> = emptyList()
) {
    val excludedCount: Int get() = missingCount + outsideAppDirCount + failedCount

    /** 경고해야 하는 상태. 제외 0건이면 경고하지 않는다. */
    val hasLoss: Boolean get() = requested && excludedCount > 0

    /**
     * 집계 자체를 믿을 수 없는 상태 — 참조 목록을 못 읽은 항목이 있다.
     *
     * [hasLoss]와 **따로 두는 이유**: 제외 건수는 0인데 참조를 못 읽은 항목이 있는 경우가
     * 실재하고, 그때 "N장 중 N장 담김 · 0장 누락"은 전부 사실이면서 **결론만 거짓**이다.
     * 손실 문구에 이 상태를 합치면 없는 누락 건수를 말하게 되므로 갈라 둔다.
     */
    val referencesIncomplete: Boolean get() = requested && unreadableRefCount > 0

    /**
     * 이 결과가 낳는 고지 갈래 — **판정은 순수하게 여기서 하고, 문자열 자원 매핑만 호출부가 한다.**
     *
     * 종전에는 이 우선순위가 `ExcelExporter`의 사적 함수 안에 있어 어느 시험도 닿지 못했고,
     * 그 안에서 **'완전한 백업입니다'가 참조를 못 읽은 상태에서도 나갈 수 있었다**(B-225).
     * 같은 부류를 이 저장소가 이미 한 번 겪었다 — 셀 서식 우선순위(`cellStyleKindFor`)가
     * 사적 클래스 안에 있어 전 열이 잘못 보이는 채로 신규 12건이 초록이었다.
     *
     * 순서가 곧 규칙이다:
     * 1. 요청하지 않았으면 아무 말도 하지 않는다(사실과 다른 경고 금지).
     * 2. 제외가 있으면 손실을 먼저 말한다 — 담긴 것이 0장이냐 아니냐로 문구가 갈린다.
     * 3. **참조를 못 읽은 항목이 있으면 그 사실을 언제나 덧붙인다.** 손실 문구가 이미
     *    떴어도 그 문구의 숫자가 하한이라는 것은 따로 말해야 한다.
     * 4. '이미지 없음'과 '완전한 백업'은 **참조를 전부 읽었을 때만** 할 수 있는 말이다.
     */
    fun noticeKinds(isCompleteBackup: Boolean): List<ImageNoticeKind> {
        if (!requested) return emptyList()
        val kinds = ArrayList<ImageNoticeKind>(2)
        when {
            hasLoss && includedCount == 0 -> kinds.add(ImageNoticeKind.NONE_INCLUDED)
            hasLoss -> kinds.add(ImageNoticeKind.INCOMPLETE)
            referencesIncomplete -> Unit                       // 아래 줄이 이 상태를 말한다
            referencedCount == 0 -> kinds.add(ImageNoticeKind.NO_IMAGES)
            isCompleteBackup -> kinds.add(ImageNoticeKind.COMPLETE_BACKUP)
        }
        if (referencesIncomplete) kinds.add(ImageNoticeKind.REFS_UNREADABLE)
        return kinds
    }

    /**
     * 제외 사유별 내역 — **0인 사유는 빼고, 순서는 고정한다.**
     *
     * 뭉뚱그리지 않는 것이 R-39의 요구다(막힌 것과 못 읽은 것은 **처방이 다르다** —
     * 파일 확인 / 앱에 들이기). 반대로 0을 함께 늘어놓으면 *어디를 봐야 하는지*가 묻히므로
     * 실제로 걸린 사유만 든다. 순서를 고정하는 것은 같은 상황에서 문구가 흔들리지 않게 하기 위함이다.
     */
    fun lossReasons(): List<Pair<ImageLossReason, Int>> = buildList {
        if (missingCount > 0) add(ImageLossReason.MISSING to missingCount)
        if (outsideAppDirCount > 0) add(ImageLossReason.OUTSIDE_APP_DIR to outsideAppDirCount)
        if (failedCount > 0) add(ImageLossReason.FAILED to failedCount)
    }

    companion object {
        const val SAMPLE_LIMIT = 5

        /** 이미지 포함을 요청하지 않은 내보내기/백업 */
        val NOT_REQUESTED = ImageZipReport()
    }
}

/**
 * 이미지 수록 고지의 갈래([ImageZipReport.noticeKinds]가 정한다).
 *
 * 화면 문구를 여기 담지 않는 것은 이 계층이 순수(Android 무의존)여서다 —
 * 자원 id 매핑은 문구를 아는 계층이 한다.
 */
enum class ImageNoticeKind {
    /** 참조가 있는데 한 장도 담기지 않았다 */
    NONE_INCLUDED,

    /** 일부만 담겼다 */
    INCOMPLETE,

    /** 참조 목록을 못 읽은 항목이 있다 — 담긴 수도 빠진 수도 그 항목을 세지 못했다 */
    REFS_UNREADABLE,

    /** 담을 이미지가 애초에 없었다 (손실이 아니다) */
    NO_IMAGES,

    /** 참조를 전부 읽었고 전부 담겼다 — '완전한 백업' */
    COMPLETE_BACKUP
}

/**
 * 이미지를 담지 못한 사유([ImageZipReport.lossReasons]가 든다).
 *
 * **처방이 사유마다 다르므로 갈라 둔다**(R-39) — 없는 파일은 참조를 고치는 일이고,
 * 저장소 밖 파일은 앱에 들이는 일이며, 읽기 실패는 다시 시도해 볼 일이다.
 */
enum class ImageLossReason {
    /** 참조하는데 파일이 없다 */
    MISSING,

    /** 앱 내부 저장소 밖을 가리켜 봉쇄 가드가 막았다 */
    OUTSIDE_APP_DIR,

    /** 읽기·압축 중 오류 */
    FAILED
}

/**
 * 이미지 경로를 "담을 수 있음 / 파일 없음 / 앱 저장소 밖"으로 분류하는 **단일 소스**
 * (순수 JVM — 단위 테스트 대상).
 *
 * 같은 판정(존재 && filesDir 하위)이 ZIP 래핑과 유지보수 '이미지 경로 점검'에 각각 있어
 * 드리프트할 수 있었다. 판정이 갈리면 "내보내기는 N장 누락이라는데 점검은 0건"이 되어 안내가 거짓이 된다.
 * 파일 시스템 접근을 람다로 주입해 순수 JVM 테스트로 실제 실행 검증한다.
 */
object ImagePathClassifier {

    data class Classified(
        val includable: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val outsideAppDir: List<String> = emptyList()
    ) {
        /** 끊어진 참조(파일 없음 + 저장소 밖) — 유지보수 점검이 보고하는 집합 */
        val broken: List<String> get() = missing + outsideAppDir
    }

    /**
     * **[canonicalOf]는 일부러 `ImagePathMatch.canonical`을 쓰지 않는다 (B-106 ⓐ).**
     *
     * 모양은 같은 한 줄이지만 **실패 처분이 반대여야 하는 자리**다 — 여기서 정규화가 실패하면
     * `null`이 되고 아래 `?.startsWith(prefix) != true`가 그 경로를 **저장소 밖으로 민다(막는다).**
     * `ImagePathMatch.canonical`은 규약상 **원본을 그대로 돌려주므로**, 그것으로 바꾸면
     * `../`가 든 원본 문자열이 접두어만 맞으면 `includable`로 통과할 수 있다 —
     * **가드가 조용히 느슨해진다.**
     *
     * 봉쇄 판정의 단일 소스는 `ImagePathMatch.isInside`이지만 그쪽은 `File`을 받고,
     * 이 함수는 파일 시스템에 닿지 않는 **순수·주입 가능**한 형태(시험이 `exists`·[canonicalOf]를
     * 갈아 끼운다)라 그대로 받을 수 없다. 그래서 걷어내지 않고 이 주석을 남긴다.
     */
    fun classify(
        paths: Collection<String>,
        appDirCanonicalPath: String,
        separator: String = File.separator,
        exists: (String) -> Boolean = { File(it).exists() },
        canonicalOf: (String) -> String? = { runCatching { File(it).canonicalPath }.getOrNull() }
    ): Classified {
        // 접두 비교는 반드시 구분자 경계까지 — "/files"가 "/files_evil/x.jpg"를 삼키지 않게 한다.
        val prefix = appDirCanonicalPath.removeSuffix(separator) + separator
        val includable = ArrayList<String>()
        val missing = ArrayList<String>()
        val outside = ArrayList<String>()
        for (p in paths) {
            when {
                !exists(p) -> missing.add(p)
                canonicalOf(p)?.startsWith(prefix) != true -> outside.add(p)
                else -> includable.add(p)
            }
        }
        return Classified(includable, missing, outside)
    }

    /** 경고용 표본 파일명 */
    fun sampleNames(paths: List<String>, limit: Int = ImageZipReport.SAMPLE_LIMIT): List<String> =
        paths.take(limit).map { File(it).name }
}
