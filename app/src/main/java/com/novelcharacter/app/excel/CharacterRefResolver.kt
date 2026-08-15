package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.Character

/**
 * 캐릭터 참조(이름/코드) 해석 결과.
 *
 * characters 테이블의 유니크 인덱스는 code 하나뿐이고 **이름은 중복이 허용된다**
 * (동명이인은 이 앱이 정상으로 받는 상태다 — 작품이 다르면 같은 이름을 쓴다).
 * 이름만으로 first-match 하면 동명이인 중 아무에게나 상태 변화·관계·관계 변화가 붙고,
 * 그 오배정은 **행이 조용히 성공한 것처럼 보이므로** 사용자가 되돌릴 계기를 얻지 못한다.
 * [FactionLookupResult]·[NovelTitleLookup]과 동형이다(4-3 규약 — 동명 참조는 범위로 좁히고,
 * 안 되면 모호를 선언한다).
 */
sealed class CharLookupResult {
    data class Found(val character: Character) : CharLookupResult()

    /** 동명이인이 여럿이고 힌트로도 하나로 좁혀지지 않음 — 호출부가 고지 후 거부한다 */
    data class Ambiguous(val count: Int) : CharLookupResult()

    data object NotFound : CharLookupResult()
}

/**
 * 캐릭터 참조 해석의 **사다리**(순수 JVM — 단위 테스트 대상).
 *
 * 재료(코드로 찾은 한 명 · 이름으로 찾은 전부)는 호출부가 색인에서 떠 오고, 여기는 **판정만**
 * 한다. 판정을 여기 모아 두는 이유는 R-33이다 — 같은 시트를 두 번 읽는 코드
 * (`analyze*` 미리보기 / `import*` 쓰기)가 각자 사다리를 들면 **반드시 갈리고**, 갈리면
 * 미리보기는 '실행된다'고 예고한 행을 가져오기가 거부한다(B-232 — 미리보기 다섯 자리가
 * 그 상태였다).
 *
 * **사다리가 둘인 것은 시트마다 해소 힌트가 다르기 때문이다** — 코드 열이 있는 시트는
 * [codeFirst], '작품' 열로 좁히는 시트는 [nameWithNovelHint]. 억지로 하나로 합치면
 * 짝이 되는 가져오기의 판정과 어긋나므로, 합치는 것이 아니라 **짝끼리 같은 것을 부르게** 한다.
 */
object CharacterRefLadder {

    /**
     * 코드(안정 식별자) 우선 → 이름 폴백. 동명이인 모호성 감지 포함.
     *
     * [byCode]가 있으면 이름은 보지 않는다(코드가 권위) — 호출부는 그때 [nameMatches]를
     * 뜨지 않아도 된다. 코드가 적혀 있는데 못 찾은 경우도 이름 폴백으로 내려간다:
     * 두 기기를 병합하는 파일은 코드가 전부 상대 기기 것이라, 그때 거부하면 해소 가능한 행이
     * 통째로 밀린다(F1-C와 같은 처분).
     */
    fun codeFirst(byCode: Character?, name: String, nameMatches: List<Character>): CharLookupResult {
        if (byCode != null) return CharLookupResult.Found(byCode)
        if (name.isBlank()) return CharLookupResult.NotFound
        return when {
            nameMatches.isEmpty() -> CharLookupResult.NotFound
            nameMatches.size == 1 -> CharLookupResult.Found(nameMatches[0])
            else -> CharLookupResult.Ambiguous(nameMatches.size)
        }
    }

    /**
     * F3-B: 이름 기반 해석(동명이인 안전) — 여러 명이면 [preferredNovelId]로 좁히고,
     * 그래도 하나가 아니면 Ambiguous다(호출부가 고지 후 거부).
     *
     * **동명이 여럿일 때만** 힌트를 쓴다 — [FactionIndex.resolve]·[NovelTitleIndex.resolve]와
     * 동형이다. 후보가 하나면 힌트 없이도 Found여야 '작품' 열이 없는 구버전 파일이 무조건
     * 실패하지 않는다(무조건 skip은 조작 마찰 최소화 원칙에 역행한다).
     */
    fun nameWithNovelHint(
        name: String,
        nameMatches: List<Character>,
        preferredNovelId: Long?
    ): CharLookupResult {
        if (name.isBlank()) return CharLookupResult.NotFound
        return when {
            nameMatches.isEmpty() -> CharLookupResult.NotFound
            nameMatches.size == 1 -> CharLookupResult.Found(nameMatches[0])
            else -> {
                val narrowed = preferredNovelId?.let { nid -> nameMatches.filter { it.novelId == nid } }
                    ?: emptyList()
                if (narrowed.size == 1) CharLookupResult.Found(narrowed[0])
                else CharLookupResult.Ambiguous(nameMatches.size)
            }
        }
    }
}
