package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.FieldValueTokenizer

/**
 * 캐릭터 시트의 **동적 필드 열 머리** 규칙 — 내보내기와 가져오기의 단일 소스.
 *
 * 형제 시트(연표의 사건 필드 · 작품 시트의 작품 필드)는 [EntityFieldHeaders]가 같은 일을
 * 하고 있었는데, **캐릭터 시트만 그 짝이 없었다.** 머리를 짓는 식이 `characterSpec` 안에
 * 인라인으로 있었고, 읽는 쪽([com.novelcharacter.app.util.CharacterFieldColumns])은 그
 * 식을 모른 채 정규식으로 되짚었다. 그래서 세 가지가 어긋나 있었다.
 *
 * ## ① 기대 헤더 정확 일치 단이 없었다 — 무편집 왕복이 값을 뒤바꿨다
 *
 * 읽는 쪽 사다리의 첫 단은 `이름(필드키)` 병기 머리를 되짚는 정규식 `^(.+)\((.+)\)$`다.
 * 그런데 **이름 자체가 괄호로 끝나는 필드**가 있다(`총합(마력)` · 앱이 심는 프리셋의
 * `생일(월/일)`). 그 머리는 같은 목록에 키가 `마력`인 다른 필드가 있으면 **그 필드의 열로
 * 확정된다** — 내보내기가 적은 열의 주인이 아니라 남의 필드에게. 한 글자도 고치지 않은
 * 왕복 한 번에 값이 뒤바뀌고(고지 없음), 원래 필드는 아무 열도 못 받는다.
 *
 * 형제 시트는 이 위험을 **명시로 다루고 반대 순서를 골랐다**([EntityFieldHeaders] 머리 —
 * *"가져오기가 이 규칙을 정규식으로 되짚으면 이름이 괄호로 끝나는 필드(`규모(명)`)를 …
 * 오인해 … 기대 헤더 → 필드 정확 일치를 최우선으로 둔다"*). 캐릭터 시트만 그 단이 없었다.
 * [expectedHeaders]가 그 표이고, 정규식 사다리는 **손편집·구버전 머리용 폴백**으로 남는다.
 *
 * ## ② 머리가 겹치는 두 필드가 열을 둘 그렸다 — 뒤 열의 값은 되돌아오지 못했다
 *
 * 유니크 색인은 `(구역, 대상, 키)`라 **한 구역에 이름이 같은 필드가 둘 있을 수 있고**, 그때
 * 병기(`이름(키)`)로 갈라진다. 그런데 갈라도 글자가 같아지는 조합이 있다 — 예를 들어
 * 고정 열과 겹쳐 `메모(memo)`로 병기된 필드와, 이름 자체가 `메모(memo)`인 필드. 종전에는
 * **같은 머리의 열이 두 개 그려졌고**, 가져오기의 단사 보장이 뒤 열을 버렸다. 즉 그 필드의
 * 값은 내보내도 결코 되돌아오지 못했다. [plan]이 머리가 유일한 필드만 열로 그리고 나머지는
 * 오버플로 시트('캐릭터 필드값')가 키로 담는다 — [EntityFieldHeaders.plan]과 같은 처분이다.
 *
 * ## ③ 열 목록과 셀 목록이 두 벌이었다
 *
 * `characterSpec`(머리)과 `ExcelExporter.exportCharacterSheet`(셀)이 각자 `fields`를 돌았다.
 * 한쪽만 걸러지면 **전 필드 값이 한 칸씩 밀린다** — 이 결함들보다 나쁘다. 이제 둘 다
 * [plan]의 `columns`를 돈다.
 */
object CharacterFieldHeaders {

    /** 시트에 실제로 그릴 열과, 열로 담을 수 없어 오버플로로 가는 필드를 가른 결과. */
    data class Plan(
        /** 그릴 열 — `(필드, 열 머리)`이고 머리는 유일하다. */
        val columns: List<Pair<FieldDefinition, String>>,
        /** 열로 담긴 필드 id — 오버플로 선별의 `covered`가 이 집합이다. */
        val coveredFieldIds: Set<Long>
    )

    /**
     * 필드 목록 → `(필드, 열 머리)` — **머리를 짓는 규칙 그 자체**.
     *
     * 병기(`이름(키)`)는 고정 열과 겹치거나 동명 필드가 둘 이상일 때 붙는다. 쉼표를 쪼개는
     * 필드에는 안내 접미사가 붙고, 그 글자는 형제 시트와 **한 상수**를 쓴다(B-38·B-177 —
     * 시트마다 다른 말로 안내하면 외부 편집자가 시트마다 다시 배운다).
     */
    fun headersFor(fields: List<FieldDefinition>): List<Pair<FieldDefinition, String>> {
        val nameCounts = fields.groupingBy { it.name }.eachCount()
        return fields.map { field ->
            val disambiguate =
                collidesWithFixedHeader(field.name) || (nameCounts[field.name] ?: 0) > 1
            val core = characterFieldHeader(field.name, field.key, disambiguate)
            val header = if (FieldValueTokenizer.isMultiToken(field)) {
                core + EntityFieldHeaders.MULTI_SUFFIX
            } else {
                core
            }
            field to header
        }
    }

    /** 머리가 유일한 필드만 열로 그린다 — 겹친 뒤 필드는 오버플로 몫이다(②). */
    fun plan(fields: List<FieldDefinition>): Plan {
        val columns = ArrayList<Pair<FieldDefinition, String>>(fields.size)
        val covered = LinkedHashSet<Long>()
        val takenHeaders = HashSet<String>()
        for ((field, header) in headersFor(fields)) {
            if (!takenHeaders.add(header)) continue
            columns.add(field to header)
            covered.add(field.id)
        }
        return Plan(columns, covered)
    }

    /**
     * 기대 헤더 → 필드 (가져오기의 정확 일치 조회용). 머리가 겹치면 **먼저 온 필드가 이긴다** —
     * [plan]이 열을 주는 쪽과 같은 규칙이라 표와 시트가 갈릴 수 없다.
     *
     * **새 머리를 먼저 다 넣고, 접미사 없는 옛 머리를 뒤에 넣는다**([EntityFieldHeaders.expectedHeaders]와
     * 같은 순서). 접미사가 붙기 전(B-38 이전)에 내보낸 파일이 이미 있고, 그 파일의 `성격`은
     * 지금 규칙으로는 `성격 (쉼표 구분)`이라 정확 일치에 실패한다. 순서가 요점인 것은
     * `성격 (쉼표 구분)`이라는 *이름*의 필드가 실제로 있을 수 있기 때문이다 — 그 필드가 자기
     * 머리를 먼저 갖고, 옛 머리 폴백은 남은 자리에만 든다.
     */
    fun expectedHeaders(fields: List<FieldDefinition>): Map<String, FieldDefinition> {
        val map = LinkedHashMap<String, FieldDefinition>()
        val pairs = headersFor(fields)
        for ((field, header) in pairs) {
            if (header !in map) map[header] = field
        }
        for ((field, header) in pairs) {
            if (!header.endsWith(EntityFieldHeaders.MULTI_SUFFIX)) continue
            val legacy = header.removeSuffix(EntityFieldHeaders.MULTI_SUFFIX)
            if (legacy !in map) map[legacy] = field
        }
        return map
    }
}
