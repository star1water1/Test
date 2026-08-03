package com.novelcharacter.app.util

/**
 * **"뗀 것"의 판정 규칙**(B-107 결정 D2) — 순수 로직(Android·DB 무의존, JVM 단위 테스트로 검증).
 *
 * 읽고 쓰는 손은 [DetachedImageMarker]에 있고, **무엇이 뗀 것인가는 여기 하나뿐이다.**
 * 붙는 자리가 셋(이미지 탭 배정 해제 · 편집창 제거 후 저장 · 폴더 왕복 배정 해제)이고 푸는
 * 자리가 셋(다시 붙기 · 사용자가 직접 · `_미배정/`으로 되돌리기)인데, 규칙을 자리마다 적으면
 * 반드시 갈라진다(아키텍처 3장).
 *
 * ### 불변식
 *
 * > **어떤 캐릭터가 쓰고 있는 이미지는 뗀 것이 아니다.**
 *
 * 그래서 붙이는 규칙과 푸는 규칙이 **한 함수**([decide])에서 함께 나온다. 둘을 따로 두면
 * "뗐다가 다시 붙였는데 서랍에 남아 있다"가 언제든 생길 수 있고, 그때 어느 쪽이 틀렸는지
 * 알 길이 없다.
 *
 * ### 작품·세계관은 판정에서 뺀다
 *
 * 요청이 겨눈 것은 *"캐릭터한테서 떼어낸"*이다. 캐릭터에서 뗐지만 작품이 아직 쓰는 이미지도
 * **뗀 것**이다 — 현재 소유 상태가 아니라 캐릭터 축의 이력이기 때문이다(설계 D2).
 */
object DetachedImageRule {

    /**
     * 이번 조작이 건드린 이미지 하나.
     *
     * @param fromCode 이 이미지를 뗀 캐릭터의 [com.novelcharacter.app.data.model.Character.code].
     *        모르면 null이고, 그때 화면은 출처 없이 시점만 말한다. **지어내지 않는다.**
     *        경로마다 다른 이유: 일괄 배정 해제는 **여러 캐릭터에 걸쳐** 일어난다.
     */
    data class Candidate(val path: String, val fromCode: String?)

    /** [decide]의 결과 — 어느 경로에 표식을 붙이고 어느 경로에서 지울 것인가(배타). */
    data class Decision(
        val toMark: List<Candidate>,
        /** 캐릭터가 아직 쓰고 있어 표식을 지울 경로(호출부가 넘긴 저장형 그대로). */
        val toClear: List<String>
    ) {
        val isEmpty: Boolean get() = toMark.isEmpty() && toClear.isEmpty()
    }

    /**
     * 같은 파일이 여러 번 들어오면 **마지막 것이 이긴다**: 칸의 뜻이 *"마지막으로 뗀 캐릭터"*라
     * 그쪽이 값의 정의와 맞는다.
     *
     * @param characterOwnedCanonical 지금 **어떤 캐릭터라도** 쓰고 있는 경로의 canonical 집합.
     */
    fun decide(candidates: Collection<Candidate>, characterOwnedCanonical: Set<String>): Decision {
        // canonical 기준으로 접되 순서는 보존한다(마지막 것이 이기도록 값만 덮어쓴다).
        val byCanonical = LinkedHashMap<String, Candidate>()
        for (c in candidates) {
            val canon = ImagePathMatch.canonical(c.path)
            // 빈 경로는 "지정 없음"이라 어떤 파일도 가리키지 않는다 — 표식의 대상이 아니다.
            if (canon.isEmpty()) continue
            byCanonical[canon] = c
        }
        val mark = ArrayList<Candidate>()
        val clear = ArrayList<String>()
        for ((canon, c) in byCanonical) {
            if (canon in characterOwnedCanonical) clear.add(c.path) else mark.add(c)
        }
        return Decision(mark, clear)
    }

    /**
     * 저장형과 canonical을 모두 넣는다 — 둘이 다른 경우가 있고, DB 갱신은 정확 일치로 돈다
     * (`promoteToUserByPaths`가 이미 쓰는 관례).
     */
    fun bothForms(paths: Collection<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (p in paths) {
            if (p.isNotBlank()) out.add(p)
            ImagePathMatch.canonical(p).takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out.toList()
    }
}
