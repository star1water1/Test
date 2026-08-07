package com.novelcharacter.app.excel

/**
 * 시트의 **세계관 칸이 무엇을 뜻하는가**의 단일 소스 (Android 비의존 — 단위 테스트 대상).
 *
 * 전역 구역(`universeId IS NULL` — B-119 확장)은 소속이 없으므로 세계관 칸이 빈 채로 오간다.
 * 즉 **빈 칸은 '못 찾았다'가 아니라 '무소속'이라는 값**이고, 그 판정을 각 경로가 따로 적으면
 * 갈린다 — 실제로 갈려 있었다(B-130): '필드 정의'는 갈래가 있고 오버플로 두 시트는 없어서,
 * 내보내기가 빈 칸으로 내보낸 전역 값을 가져오기가 `skippedRows`로 버렸다.
 * 미리보기까지 세면 **한 축이 다섯 경로에 걸려 있었고 둘만 알고 있었다.**
 *
 * 그래서 판정을 함수 하나로 모은다(R-33·R-34와 같은 처방) — 새 경로가 생겨도 이 함수를
 * 부르지 않으면 컴파일은 되지만, 부르는 자리가 한 곳이라 *어디가 빠졌는지*는 셀 수 있다.
 *
 * **빈 칸과 없는 열은 다르다(R-36).** 이 함수는 *값*만 본다 — 열 자체가 없는 파일의 처분은
 * 읽는 쪽(`read*Row`)이 `cols.containsKey`로 이미 가르고, 그쪽이 기존값 유지를 책임진다.
 */
object FieldScopeCell {

    /**
     * 세계관 칸이 비어 전역(무소속) 구역을 가리키는가.
     *
     * @param universeName 세계관 이름 칸
     * @param universeCode 세계관코드 칸 — 그 열이 있는 시트만 넘긴다. 이름이 비어도 **코드가
     *                     있으면 전역이 아니다**(코드로 해석되는 세계관 행이다).
     */
    fun isGlobal(universeName: String, universeCode: String = ""): Boolean =
        universeName.isBlank() && universeCode.isBlank()

    /** 고지 문구에서 구역을 부르는 이름 — 사용자가 읽는 자리에서도 어휘가 갈리지 않게 한다. */
    const val GLOBAL_LABEL = "전역(무소속) 구역"
}
