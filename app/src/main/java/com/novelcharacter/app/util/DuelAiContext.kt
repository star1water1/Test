package com.novelcharacter.app.util

/**
 * **대결이 AI 프롬프트에서 무엇으로 보이는가의 단일 소스** (B-104 소비처 ⓔ).
 *
 * 설계 정본은 `docs/duel_system_design_2026-08.md` **10장**이다.
 *
 * 대결이 쌓는 것은 **적을 수 없는 성질의 서수 데이터**다 — 사용자가 "이 둘 중 누가 센가"를
 * 반복해서 골라 만든 우열이라, 근력 필드에 숫자를 적는 것과 달리 **어느 필드에도 적혀 있지
 * 않다.** 그래서 AI 추천은 지금까지 그 사실을 몰랐다: 대결이 A를 그 세계관 최강으로 세워
 * 두었어도 추천은 태그·메모·필드값만 보고 값을 지어냈다.
 *
 * ## 지키는 계약 셋
 *
 * 1. **여기서 점수를 내지 않는다.** [DuelScoreIndex.AxisScores]가 낸 것을 그대로 옮긴다 —
 *    프롬프트가 순위표와 다른 수를 말하면 사용자가 화면에서 본 것과 AI가 받은 것이 갈린다.
 *    설계 9-1이 세운 *"계산의 출구를 하나로 묶는다"*가 이 경로에도 그대로 적용된다.
 *    **등수는 예외이고 그것이 규칙이다** — 설계 9-3이 세운 *"점수는 그대로이고 등수만 이 표의
 *    것이다"*를 따라, 등수는 **이 목록에 실린 참가자끼리** 다시 매긴다
 *    ([DuelScoreIndex.rankWithinScored] — 등급 산정(B-113)이 같은 재매김을 필요로 해
 *    2026.08.07에 그쪽으로 올렸다. 두 소비처가 각자 매기면 같은 캐릭터가 화면마다 다른
 *    등수를 갖는다).
 *    통계 순위 탭이 작품 필터에서 하는 그 처분과 같다.
 * 2. **한 판도 안 치른 축은 싣지 않는다.** [DuelScoreIndex]의 계약 2가 그런 참가자를 이미
 *    **값 없음**으로 두었고([DuelStandings.Row.provisional]이 순위표에서 가리는 그 사실이다),
 *    없는 값을 프롬프트에 앵커 1500으로 실으면 **모델은 그것을 측정된 실력으로 읽는다.**
 *    한 판도 안 한 캐릭터가 "평균은 된다"는 근거를 얻는 셈이라, 조용한 침묵이 옳다.
 * 3. **상한에 걸려 뺀 축은 세어서 알린다**(R-14 · 개발 의도 2번). 무엇이 버려지는가의 답은
 *    **판이 가장 적은 축**이다 — 판 수가 곧 그 줄을 믿을 근거의 양이고, 상한이 있는 자리에서
 *    버릴 것을 고르라면 근거가 가장 얕은 쪽이다.
 *
 * ## 여기 없는 것 — 판을 읽고 적합하는 일
 *
 * 이 파일은 **적합이 끝난 뒤**를 맡는다(형제 파일 [DuelScoreIndex]와 같은 자리다).
 * 축을 고르고 적합을 돌리는 것은 `CharacterViewModel.getDuelStandingsForCharacter`이며,
 * 그쪽이 [com.novelcharacter.app.data.repository.DuelRepository.scoresOf]를 타기 때문에
 * — 곧 순위표와 **같은 진입점**을 타기 때문에 — 계약 1이 성립한다.
 */
object DuelAiContext {

    /**
     * 한 캐릭터가 한 축에서 차지한 자리 — 프롬프트가 쓸 것만 남긴 것.
     *
     * @property rank **점수가 있는 참가자끼리** 다시 매긴 등수([DuelScoreIndex.rankWithinScored]).
     *   순위표 줄의 등수를 그대로 쓰면 안 된다 — 그 등수는 한 판도 안 친 참가자까지 세는데
     *   그들의 앵커 1500이 **표 한가운데에 끼므로**, 1500보다 낮은 캐릭터의 등수가 분모를
     *   넘어선다(*"3명 중 4위"*). 자기 재검토가 실제로 이 자리를 잡았다.
     * @property scored 그 축에서 **점수가 매겨진** 참가자 수. 곧 [rank]의 분모다.
     *   참가자 전체가 아닌 것은 계약 2로 빠진 인원이 등수에도 없기 때문이며, 전체를 적으면
     *   *"20명 중 3위"*라 해 놓고 표에는 12명만 있는 어긋남이 된다.
     * @property played 이 캐릭터가 이 축에서 치른 판 수. **믿을 근거의 양**이자 상한 판정의 기준.
     */
    data class Standing(
        val axisName: String,
        val score: Int,
        val scoreError: Int,
        val rank: Int,
        val scored: Int,
        val played: Int
    ) {
        /**
         * 프롬프트 한 조각 — `강함: 12명 중 3위 · 1523 ±37 (12판)`.
         *
         * **등수를 먼저 적는다.** 1523이라는 수는 그 자체로 뜻이 없고(BT 점수의 눈금은 축마다
         * 다르다) 모델이 해석할 수 있는 것은 *"몇 명 중 몇 위"*다. 점수를 함께 적는 것은
         * 등수만으로는 **1위와 2위가 종이 한 장 차이인지 압도적인지** 알 수 없기 때문이고,
         * 오차를 붙이는 것은 설계 9-4-1이 든 이유 그대로다 — **세 판 친 1520과 백 판 친
         * 1520은 같은 수가 아니다.**
         */
        fun line(): String =
            "$axisName: ${scored}명 중 ${rank}위 · $score ±$scoreError (${played}판)"
    }

    /**
     * 프롬프트에 싣는 축 수의 상한.
     *
     * 형제 상한들([com.novelcharacter.app.ai.CharacterFieldAiSuggester.MAX_RELATIONSHIPS] 30 ·
     * `MAX_TAGS` 50)과 같은 부류이고 같은 이유로 있다 — 한 줄이 짧아도 목록이 길어지면
     * 프롬프트를 삼킨다. **넘치면 조용히 자르지 않고 몇 건을 뺐는지 말한다**(계약 3).
     */
    const val MAX_AXES = 20

    /**
     * 이 참가자가 축들에서 차지한 자리 — **값이 있는 축만**(계약 2).
     *
     * @param axes 이 캐릭터가 속한 세계관의 캐릭터 축들을 적합한 결과. 순서는 상관없다 —
     *   반환은 언제나 **판 수 내림차순, 동수는 축 이름**으로 다시 세운다. 축 목록의 원래
     *   차례(만든 순서)를 그대로 두면 상한에 걸릴 때 **먼저 만든 축이 이긴다**는, 근거의
     *   양과 무관한 기준이 되어 계약 3의 답이 성립하지 않는다.
     */
    fun standingsOf(
        participantCode: String,
        axes: List<DuelScoreIndex.AxisScores>
    ): List<Standing> = axes
        .mapNotNull { axis ->
            val entry = axis.entryOf(participantCode) ?: return@mapNotNull null
            Standing(
                axisName = axis.axisName,
                score = entry.score,
                scoreError = entry.scoreError,
                rank = DuelScoreIndex.rankWithinScored(axis)[participantCode] ?: return@mapNotNull null,
                scored = axis.scored,
                played = entry.played
            )
        }
        .sortedWith(compareByDescending<Standing> { it.played }.thenBy { it.axisName.lowercase() })

    /**
     * 상한을 적용한 프롬프트 조각들과 **뺀 수**.
     *
     * 두 프롬프트 조립기(짧은 값 추천 · 서술형 작성)가 이것을 함께 쓴다 — 각자 자르면
     * 두 경로가 다른 상한을 갖게 되고, 그것은 이 저장소가 반복해 겪은 실패 형태다.
     *
     * @property omitted 상한에 걸려 빠진 축 수. **0이 아니면 호출부는 반드시 고지한다**(R-14).
     */
    data class PromptLines(val lines: List<String>, val omitted: Int)

    fun promptLines(standings: List<Standing>, max: Int = MAX_AXES): PromptLines {
        val kept = if (standings.size > max) standings.take(max) else standings
        return PromptLines(kept.map { it.line() }, standings.size - kept.size)
    }

    /**
     * 프롬프트의 절 이름. **괄호의 설명이 본체다** — `강함: 12명 중 3위`만 주면 모델은 그것이
     * 어디서 온 수인지 몰라 필드값처럼 읽거나 무시한다. 사용자가 **둘씩 비교해 직접 고른**
     * 결과라는 사실이 이 데이터의 값어치 전부이므로(백로그 원문의 *"단순반복으로 얻어내는
     * 비교우위 데이터"*) 그 출처를 한 번에 밝힌다.
     */
    const val PROMPT_LABEL = "대결 우열(사용자가 둘씩 비교해 쌓은 순위): "

    /** 두 조립기가 같은 구분자를 쓴다 — 관계 줄이 쓰는 그것이다. */
    const val PROMPT_SEPARATOR = " / "

    /** 상한 고지 문구 — 형제 목록들의 절단 고지와 같은 꼴이다. */
    fun omittedNote(omitted: Int, max: Int = MAX_AXES): String =
        "대결 축 ${omitted}건 생략 (상한 ${max}건)"
}
