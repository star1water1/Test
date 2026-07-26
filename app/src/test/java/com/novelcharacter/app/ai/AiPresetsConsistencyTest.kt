package com.novelcharacter.app.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프리셋 데이터의 자체 정합성.
 *
 * `AiPresets`가 `R`을 참조하므로 순수 JVM 하네스(tools/run_jvm_tests.sh)로는 돌릴 수 없고,
 * CI의 `testDebugUnitTest`에서 검증된다. 순수 로직 테스트는 `AiModelSuggestionsTest`에 있다.
 */
class AiPresetsConsistencyTest {

    @Test
    fun `기본 모델은 추천 목록 안에 있다`() {
        // 기본값이 추천 칩에 없으면 편집 화면이 어떤 칩도 체크되지 않은 채로 열려,
        // 사용자가 무엇이 선택돼 있는지 알 수 없다.
        for (preset in AiPresets.ALL) {
            if (preset.defaultModel.isBlank()) continue
            assertTrue(
                "${preset.id}: 기본 모델 '${preset.defaultModel}'이 추천 목록에 없다",
                preset.defaultModel in preset.suggestedModels
            )
        }
    }

    @Test
    fun `추천 목록에 중복이나 빈 값이 없다`() {
        for (preset in AiPresets.ALL) {
            val models = preset.suggestedModels
            assertTrue("${preset.id}: 빈 모델명", models.none { it.isBlank() })
            assertTrue("${preset.id}: 중복 모델명", models.size == models.distinct().size)
        }
    }

    @Test
    fun `키 발급 콘솔과 모델 문서 주소는 https다`() {
        for (preset in AiPresets.ALL) {
            listOf(preset.consoleUrl, preset.modelDocsUrl)
                .filter { it.isNotBlank() }
                .forEach { assertTrue("${preset.id}: $it", it.startsWith("https://")) }
        }
    }
}
