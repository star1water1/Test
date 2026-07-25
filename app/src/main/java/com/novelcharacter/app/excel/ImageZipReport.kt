package com.novelcharacter.app.excel

import java.io.File

/**
 * 이미지 ZIP 래핑 결과 — "무엇이 왜 담기지 않았는지"를 사실 그대로 집계한다.
 *
 * 이미지가 빠진 백업을 완전한 백업으로 오인해 원본 기기를 초기화하면 이미지가 영구 소멸한다.
 * 따라서 무음 제외는 그 자체로 결함이다(검증 → 경고 → 교정 경로 안내).
 * 불변식: referencedCount == includedCount + excludedCount
 */
data class ImageZipReport(
    /** 이미지 포함이 요청되었는가. false면 호출부는 아무것도 알리지 않는다(사실과 다른 경고 금지). */
    val requested: Boolean = false,
    /** ZIP 파일이 실제로 생성되었는가. false면 호출부는 XLSX를 그대로 쓴다. */
    val created: Boolean = false,
    /** 수집된 참조 경로 총수 — 엔티티(캐릭터/세계관/작품) ∪ 라이브러리(image_meta), 중복 제거 후 */
    val referencedCount: Int = 0,
    /** ZIP에 실제로 담긴 이미지 수 */
    val includedCount: Int = 0,
    /** 참조되나 파일이 존재하지 않아 담지 못한 수 */
    val missingCount: Int = 0,
    /** 앱 내부 저장소 밖을 가리켜 담지 못한 수 */
    val outsideAppDirCount: Int = 0,
    /** 읽기·압축 중 오류로 담지 못한 수 */
    val failedCount: Int = 0,
    /** 제외 항목의 표본 파일명 (최대 [SAMPLE_LIMIT]개) — 내부 절대경로 전체는 사용자에게 의미가 없다 */
    val sampleNames: List<String> = emptyList()
) {
    val excludedCount: Int get() = missingCount + outsideAppDirCount + failedCount

    /** 경고해야 하는 상태. 제외 0건이면 경고하지 않는다. */
    val hasLoss: Boolean get() = requested && excludedCount > 0

    companion object {
        const val SAMPLE_LIMIT = 5

        /** 이미지 포함을 요청하지 않은 내보내기/백업 */
        val NOT_REQUESTED = ImageZipReport()
    }
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
