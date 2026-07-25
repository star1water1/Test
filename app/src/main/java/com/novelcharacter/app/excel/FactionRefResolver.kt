package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.Faction

/**
 * 세력 참조(이름/코드) 해석 결과.
 *
 * 매칭 규약: 코드(안정 식별자) 우선 → 자연키(이름) 폴백.
 * factions 테이블의 유니크 인덱스는 code 하나뿐이고 이름은 세계관 간 중복이 허용되므로
 * (`FactionDao.getByNameAndUniverse`가 그 설계를 명시한다), 이름만으로 first-match 하면
 * 다른 세계관의 동명 세력에 무경고로 연결되고 그 소속으로 자동 관계까지 생성돼 오염이 번진다.
 */
sealed class FactionLookupResult {
    /** @param matchedByCode 코드로 확정됐는지(false면 이름 폴백 — 호출부가 고지) */
    data class Found(val faction: Faction, val matchedByCode: Boolean) : FactionLookupResult()

    /** 동명 세력이 여럿이고 세계관으로도 하나로 좁혀지지 않음 */
    data class Ambiguous(val count: Int, val candidates: List<Faction>) : FactionLookupResult()

    data object NotFound : FactionLookupResult()
}

/**
 * 한 시트를 처리하는 동안 재사용하는 세력 색인 (순수 JVM — 단위 테스트 대상).
 * 행마다 `getAllFactionsList()`를 다시 읽지 않아 대형 파일에서도 동작을 받쳐준다.
 * (스냅샷 — 이 색인을 쓰는 루프들은 세력을 생성/수정하지 않는다)
 */
class FactionIndex(factions: List<Faction>) {
    private val byCode: Map<String, Faction> =
        factions.filter { it.code.isNotBlank() }.associateBy { it.code }
    private val byName: Map<String, List<Faction>> = factions.groupBy { it.name }

    /**
     * 동명이 **여럿일 때만** [preferredUniverseId]로 좁힌다 — `resolveCharByNameNovel`과 동형.
     * 후보가 하나면 힌트 없이도 Found다(미분류 캐릭터처럼 힌트가 없는 행이 무조건 실패하지 않게 —
     * 무조건 skip은 조작 마찰 최소화 원칙에 역행한다).
     */
    fun resolve(name: String, code: String, preferredUniverseId: Long?): FactionLookupResult {
        if (code.isNotBlank()) {
            byCode[code]?.let { return FactionLookupResult.Found(it, matchedByCode = true) }
        }
        if (name.isBlank()) return FactionLookupResult.NotFound
        val matches = byName[name] ?: return FactionLookupResult.NotFound
        if (matches.size == 1) return FactionLookupResult.Found(matches[0], matchedByCode = false)
        val narrowed = preferredUniverseId?.let { uid -> matches.filter { it.universeId == uid } } ?: emptyList()
        return if (narrowed.size == 1) FactionLookupResult.Found(narrowed[0], matchedByCode = false)
        else FactionLookupResult.Ambiguous(matches.size, matches)
    }
}

/**
 * '세력 관계' 시트 전용 — 이 시트에는 세계관 열이 없으므로 **상대 세력**을 힌트로 쓴다.
 * 인앱 세력 관계는 항상 같은 세계관 안에서만 만들어지므로(FactionManageFragment의 상대 목록이
 * 그 세계관 세력으로 한정된다) 어느 쪽이든 먼저 확정된 편이 반대편 동명 해소의 기준이 된다.
 */
fun resolveFactionPair(
    index: FactionIndex,
    name1: String,
    code1: String,
    name2: String,
    code2: String
): Pair<FactionLookupResult, FactionLookupResult> {
    val first = index.resolve(name1, code1, null)
    val second = index.resolve(name2, code2, (first as? FactionLookupResult.Found)?.faction?.universeId)
    val firstFinal = if (first is FactionLookupResult.Ambiguous) {
        index.resolve(name1, code1, (second as? FactionLookupResult.Found)?.faction?.universeId)
    } else first
    return firstFinal to second
}

/** 동명 세력 모호성 문구 — 모든 시트가 같은 문구를 쓰도록 단일 소스 (교정 경로 포함) */
fun factionAmbiguityMessage(
    rowLabel: String,
    name: String,
    r: FactionLookupResult.Ambiguous,
    codeHeader: String,
    universeNameOf: (Long) -> String?
): String {
    val where = r.candidates.mapNotNull { universeNameOf(it.universeId) }.distinct().joinToString(", ")
    return "$rowLabel: 동명 세력 '$name'이(가) ${r.count}개 있습니다" +
        (if (where.isNotBlank()) " (세계관: $where)" else "") +
        " — '$codeHeader' 열에 '세력' 시트의 코드를 적어 지정하세요"
}
