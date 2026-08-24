package com.novelcharacter.app.ai

import android.content.Context
import java.time.LocalDate

/**
 * 사용량 원장의 영속 저장소(SharedPreferences `ai_usage`).
 *
 * - 무엇을 얼마나 더하는가는 [AiUsageLedger]가, 직렬화는 [AiUsageCodec]이 단일 소스다 —
 *   이 클래스는 입출력·시각(오늘이 몇 일인가)만 맡는다.
 * - **API 키·프로바이더 설정과 다른 파일이다** — 여기는 민감 정보가 없고, 백업·엑셀로
 *   나가지도 않는다(측정치는 설정이 아니라 이 기기의 기록이다).
 * - 기록은 [AiService]의 성공 출구 하나에서만 부른다(관문 기록 — 새 기능이 늘어도 자동).
 */
class AiUsageStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 오늘의 epoch day — 화면의 "오늘"과 같은 눈금이 되도록 기기 시간대 기준이다. */
    fun today(): Long = LocalDate.now().toEpochDay()

    fun snapshot(): AiUsageLedger.Data = AiUsageCodec.decode(sp.getString(KEY_LEDGER, null))

    /**
     * 성공 1건을 더한다. 읽기-수정-쓰기라 잠금으로 감싼다 — AI 실행 둘이 겹치면
     * (예: 다른 화면의 태깅과 추천) 한쪽 기록이 조용히 사라진다.
     */
    fun record(
        providerId: String,
        displayName: String,
        model: String,
        inputTokens: Int?,
        outputTokens: Int?
    ) = synchronized(LOCK) {
        val next = AiUsageLedger.record(
            snapshot(), today(), providerId, displayName, model, inputTokens, outputTokens
        )
        sp.edit().putString(KEY_LEDGER, AiUsageCodec.encode(next)).apply()
    }

    /** 기록 전체 삭제 — 파괴적 동작이므로 호출부가 실행 전에 확인을 받는다(R-4). */
    fun clear() = synchronized(LOCK) {
        sp.edit().remove(KEY_LEDGER).apply()
    }

    companion object {
        private const val PREFS_NAME = "ai_usage"
        private const val KEY_LEDGER = "ledger"

        /** 프로세스 전역 잠금 — 저장소 인스턴스가 호출 자리마다 새로 만들어지기 때문이다. */
        private val LOCK = Any()
    }
}
