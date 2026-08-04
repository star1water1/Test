package com.novelcharacter.app.ui.stats

import android.content.Context
import com.novelcharacter.app.util.CompletionWeights

/**
 * '완성도 필수 가중' 설정의 **단일 소스** (B-100 — 사용자 확정 ㄱ2: 사용자가 바꿀 수 있게).
 *
 * 완성도를 읽는 화면이 여섯이다(통계 개요·데이터 건강 상세·통계 캐릭터 상세·캐릭터 상세·
 * 보충 완성도 검사·랜덤 보충 배지). 그 여섯이 각자 배수를 읽으면 **한 설정이 화면마다 다르게
 * 해석되는 상태**가 되고, 그것이 [PatternTypePrefs]가 고친 결함과 같은 부류다. 그래서 읽기·
 * 쓰기·기본값·범위 접기를 여기 하나로 둔다.
 *
 * ## 자리에 대한 유보 (P-4)
 *
 * **지금은 앱 전체 설정이다.** 확정 문서의 파생 판정 P-4(*"가중 배수 설정을 어디에 두는가 —
 * 세계관 단위인가 앱 전체인가"*)는 아직 답이 오지 않았고, 둘 중 **앱 전체가 되돌릴 수 있는
 * 쪽**이라 그쪽으로 세웠다: 여기는 prefs 키 하나라 DB 마이그레이션도, 엑셀 왕복·월드패키지·
 * 복원 미리보기 정합(R-33)도 건드리지 않는다. 세계관 단위로 정해지면 **바뀌는 것은 이 객체
 * 하나**이고 계산 계층([CompletionWeights])과 소비처 여섯은 그대로다 — 배수를 받아서 쓰기만
 * 하기 때문이다.
 *
 * 근거가 하나 더 있다: 통계 개요·데이터 건강은 **여러 세계관의 캐릭터를 한 평균으로 낸다.**
 * 배수가 세계관마다 다르면 그 평균은 서로 다른 자로 잰 값이 섞인 수가 된다.
 */
object CompletionWeightPrefs {

    private const val PREFS_NAME = "stats_prefs"
    private const val KEY = "completion_required_weight"

    /** 저장된 배수. 없으면 기본값이고, 범위 밖 값은 접힌다. */
    fun weights(context: Context): CompletionWeights {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY, CompletionWeights.DEFAULT_REQUIRED_WEIGHT)
        return CompletionWeights.clamp(stored)
    }

    fun save(context: Context, weights: CompletionWeights) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY, weights.requiredWeight)
            .apply()
    }
}
