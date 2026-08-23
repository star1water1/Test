package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character

/**
 * **엑셀에 적힌 캐릭터 이름 → 코드** 색인 (순수 계층 — 단위 시험 대상).
 *
 * ## 왜 두 표기를 함께 받는가
 *
 * 캐릭터의 이름은 두 글자로 적힐 수 있다. [Character.name]은 사용자가 이름 칸에 적은
 * 그대로이고, [Character.displayName]은 성·이름 칸이 있으면 **`"성 이름"`으로 새로 조립한
 * 글자**다. 둘은 자주 다르다 — 실측(2026.08.24 사용자가 내보낸 파일):
 *
 * | 이름 칸 | 조립된 표기 |
 * |---|---|
 * | 엘레아 | 엘 레아 (없던 띄어쓰기) |
 * | 유리엘 실라키아스 | 실라키아스 유리엘 (차례가 뒤집힌다) |
 * | 에녹 프로스트 | 프로스트 에녹 |
 *
 * 그런데 **엑셀 시트 아홉 장은 `name`을 적는데 '대결 기록'·'대결 상성'만 `displayName`을
 * 적고 있었다.** 같은 사람이 파일 안에서 두 이름으로 나와, ⓐ 읽는 사람이 같은 인물임을
 * 못 알아보고 ⓑ 이름으로 시트를 잇는 피벗·조회가 어긋나고 ⓒ **안내가 권하는 대로
 * 캐릭터 시트의 이름을 승자 칸에 적으면 그 행이 거부됐다**(어느 참가자와도 안 맞으므로).
 *
 * ## 처방 — **쓰는 쪽은 하나로, 읽는 쪽은 둘 다**
 *
 * 내보내기는 이제 형제 시트들과 같이 [Character.name]을 적는다. 읽는 쪽이 그 하나만 받으면
 * **이미 나간 파일이 통째로 안 읽히므로**([Character.displayName]으로 적힌 파일이 이미 있다)
 * 색인은 두 표기를 모두 키로 든다. 손으로 고치는 사람이 어느 쪽을 적어도 통한다는 뜻이기도 하다.
 *
 * **동명이인 판정은 그대로다** — 한 키에 코드가 둘 이상이면 부르는 쪽이 모호를 선언한다.
 * 한 캐릭터가 두 키에 실리되 **한 키 안에서 두 번 세어지지는 않는다**(그러면 자기 이름이
 * 스스로 모호해진다).
 */
object CharacterNameIndex {

    /**
     * 이름 → 그 이름으로 읽히는 캐릭터 코드들. 값이 둘 이상이면 동명이인이다.
     *
     * 코드가 빈 캐릭터는 싣지 않는다 — 코드가 이 색인의 답이라 빈 값을 돌려주면
     * 부르는 쪽이 *찾았는데 쓸 수 없는* 상태가 된다(형제 색인들의 `isNotBlank` 규약).
     */
    fun byWrittenName(characters: List<Character>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (character in characters) {
            val code = character.code
            if (code.isBlank()) continue
            for (name in setOf(character.name, character.displayName)) {
                if (name.isBlank()) continue
                out.getOrPut(name) { ArrayList(1) }.add(code)
            }
        }
        return out
    }

    /**
     * 코드 → 그 캐릭터가 읽히는 이름 전부. 승자 칸처럼 **이미 정해진 참가자**의 이름을
     * 대조하는 자리가 쓴다 — 그 자리는 *이름이 누구를 가리키는가*가 아니라
     * *이 글자가 이 사람인가*를 묻기 때문에 색인의 방향이 반대다.
     */
    fun namesByCode(characters: List<Character>): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>(characters.size)
        for (character in characters) {
            val code = character.code
            if (code.isBlank()) continue
            val names = setOf(character.name, character.displayName).filterTo(LinkedHashSet()) { it.isNotBlank() }
            if (names.isNotEmpty()) out[code] = names
        }
        return out
    }
}
