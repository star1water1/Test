package com.novelcharacter.app.excel

/**
 * '필드 템플릿' 시트 행 → 기존 템플릿 매칭 (순수 JVM — 단위 테스트 대상).
 *
 * `user_preset_templates`는 name에 유니크 인덱스가 없어 **동명 템플릿이 정상적으로 공존한다**
 * (`UserPresetTemplate`의 `Index("name")`은 non-unique). 이름으로 맵을 만들면 동명 항목이
 * 한 건으로 접혀 사용자의 편집이 어디에도 남지 않은 채 "갱신 성공"만 보고된다.
 *
 * 이 시트에는 코드 열이 없으므로 **'생성일'(createdAt)을 안정 식별자로 승격**한다 —
 * 내보내기가 readOnly 숫자로 기록하고 가져오기가 갱신 시 건드리지 않아 왕복에서 불변이며,
 * 코드 열이 없는 '세력 소속' 시트가 이미 같은 규약을 쓴다.
 *
 * 매칭 순서: ① 생성일+이름 → ② 생성일 → ③ 이름.
 * 확정된 항목은 claim되어 뒤 행이 다시 가져가지 못한다(동명 붕괴·중복 갱신 차단).
 * 해석 실패를 조용히 버리지 않고 경고 문자열로 돌려준다 — 행 접두어는 호출측이 붙인다.
 *
 * 검색 프리셋·목록 프리셋은 name에 유니크 인덱스가 있어 이름이 이미 정체성이다 — 대상 아님.
 */
class PresetTemplateMatcher(candidates: List<Candidate>) {

    /** 매칭에 필요한 최소 정보 — Room 엔티티에 의존하지 않는다(순수 유지). */
    data class Candidate(val id: Long, val name: String, val createdAt: Long)

    sealed class Match {
        /** 기존 항목 확정. [nameBased] = 생성일이 아닌 이름으로 잡힌 매칭. */
        data class Matched(
            val id: Long,
            val nameBased: Boolean,
            val warnings: List<String> = emptyList()
        ) : Match()

        /** 기존 항목 없음 → 신규 생성 */
        data class New(val warnings: List<String> = emptyList()) : Match()
    }

    private class Entry(val id: Long, var name: String, val createdAt: Long, var claimedBy: Int? = null)

    private val entries = candidates.mapTo(mutableListOf()) { Entry(it.id, it.name, it.createdAt) }

    /**
     * [rowIndex] 행이 기술하는 템플릿을 확정한다.
     * @param createdAt '생성일' 열이 없거나 숫자로 읽히지 않으면 null — 이름만으로 매칭한다.
     */
    fun claim(name: String, createdAt: Long?, rowIndex: Int): Match {
        val warnings = mutableListOf<String>()

        if (createdAt != null) {
            // ① 생성일 + 이름 — 파일에 같은 행이 두 번 있어도 새 항목을 만들지 않고 마지막 행이 이긴다
            //    (세계관·작품·캐릭터 시트의 '코드 중복 → 마지막 행 우선'과 동일 규약)
            val exact = entries.filter { it.createdAt == createdAt && it.name == name }
            val pick = exact.firstOrNull { it.claimedBy == null } ?: exact.firstOrNull()
            if (pick != null) {
                pick.claimedBy?.let {
                    // claimedBy는 0-기반 행 색인 — 화면 문구는 엑셀 행 머리글(1-기반)로 적는다.
                    warnings.add("행 ${it + 1} 과(와) 같은 템플릿('$name')을 다시 덮어썼습니다 (마지막 행 우선)")
                }
                return claimed(pick, name, rowIndex, nameBased = false, warnings)
            }

            // ② 생성일만 — 엑셀에서 이름을 바꿔도 같은 템플릿으로 인식한다(rename 지원)
            val byCreated = entries.filter { it.createdAt == createdAt && it.claimedBy == null }
            if (byCreated.size == 1) {
                val renamed = byCreated[0]
                warnings.add("생성일이 같아 기존 템플릿 '${renamed.name}'의 이름을 '$name'(으)로 바꿉니다")
                return claimed(renamed, name, rowIndex, nameBased = false, warnings)
            }
        }

        // ③ 이름 — 생성일 열을 지웠거나 값을 고친 파일, 손으로 추가한 행
        val byName = entries.filter { it.name == name && it.claimedBy == null }
        if (byName.isNotEmpty()) {
            if (createdAt != null) {
                warnings.add("생성일이 기존 템플릿과 달라 이름 '$name'(으)로 매칭했습니다 — '생성일' 열은 수정하지 마세요")
            }
            if (byName.size > 1) {
                warnings.add("이름이 '$name'인 템플릿이 ${byName.size}개 있어 시트 순서대로 짝지었습니다 — 정확한 매칭 기준인 '생성일' 열을 지우지 마세요")
            }
            return claimed(byName[0], name, rowIndex, nameBased = true, warnings)
        }

        if (createdAt != null) {
            warnings.add("생성일 $createdAt·이름 '$name'에 해당하는 템플릿이 없어 새로 만듭니다 — 삭제됐거나 생성일을 고친 것은 아닌지 확인하세요")
        }
        return Match.New(warnings)
    }

    /** 새로 삽입한 템플릿을 후보에 등록한다 — 파일의 동일 행(같은 생성일+이름)이 또 만들지 않게. */
    fun register(id: Long, name: String, createdAt: Long, rowIndex: Int) {
        entries.add(Entry(id, name, createdAt, claimedBy = rowIndex))
    }

    private fun claimed(entry: Entry, name: String, rowIndex: Int, nameBased: Boolean, warnings: List<String>): Match {
        entry.name = name          // 파일이 권위 — 뒤 행의 이름 매칭이 갱신된 이름을 본다
        entry.claimedBy = rowIndex
        return Match.Matched(entry.id, nameBased, warnings)
    }
}
