package com.novelcharacter.app.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON에서 **문자열을 읽는 단일 소스** — `optString`을 직접 부르지 않는다.
 *
 * ## 왜 있는가 — `optString`은 기기와 하네스가 다른 값을 준다
 *
 * `org.json`은 **구현이 둘**이다. 기기는 안드로이드 libcore의 것을 쓰고, 이 저장소의 순수 JVM
 * 하네스·CI는 Maven의 참조 구현(`org.json:json`)을 싣는다. 그런데 **JSON `null`에 대한
 * `optString`의 답이 서로 다르다**(2026.08.21 실측):
 *
 * | 입력 | 참조 구현 (하네스·CI) | 안드로이드 libcore (기기) |
 * |---|---|---|
 * | `{"a":null}` → `optString("a")` | `""` | **`"null"`** |
 * | `[null]` → `optString(0)` | `""` | **`"null"`** |
 *
 * 기본값을 명시한 2인자 꼴(`optString(name, "")`)도 마찬가지다 — 안드로이드는 `JSON.toString`이
 * 먼저 `"null"`을 만들어 돌려주므로 **기본값에 닿지 못한다.**
 *
 * **그래서 시험이 초록인 채 기기에서만 값이 오염된다.** 실제로 이미지 일괄 태그 제안에서
 * 모델이 `"tags": ["실내", null]`을 돌려주면 기기에서만 **`null`이라는 이름의 태그**가
 * 이미지에 붙는다. 캐릭터 필드 제안도 같은 모양으로 `"null"`을 값으로 받는다.
 *
 * ## 앱이 쓴 config도 예외가 아니다
 *
 * *"앱이 직접 만든 JSON에는 `null`이 안 들어가니 안전하다"*는 이 앱에서 성립하지 않는다.
 * 필드 설정(JSON)은 **엑셀로 나갔다가 다시 들어온다** — 그 사이에 사람이 편집한다.
 * 그것이 이 앱의 핵심 기능이고(개발 의도 4번, 엑셀 왕복 무결성), 그러니 **모든 JSON은
 * 외부에서 쓰였을 수 있다.** 그래서 예외를 두지 않는다.
 *
 * ## 무엇을 하는가
 *
 * **JSON `null`을 '없음'으로 읽는다** — 키가 아예 없는 것과 같이 친다. 두 구현 다 `isNull`은
 * *없음*과 *`null`*을 함께 참으로 답하므로, 이 함수는 **어느 구현에서도 같은 값**을 낸다.
 * 값을 버리는 것이 아니다: `null`은 애초에 문자열이 아니고, `"null"`이라는 **네 글자**로
 * 둔갑시키는 쪽이 데이터를 만들어 내는 것이다(개발 의도 2번 — 조용한 왜곡을 만들지 않는다).
 */
fun JSONObject.stringOr(name: String, fallback: String = ""): String =
    if (isNull(name)) fallback else optString(name, fallback)

/**
 * 값이 없거나 JSON `null`이면 `null` — *"적히지 않았다"*와 *"빈 문자열이 적혔다"*를 갈라야
 * 하는 자리가 쓴다. 종전 `optString(name, null)`은 안드로이드에서 **JSON `null`에만**
 * `"null"`을 돌려주어 그 구분을 무너뜨렸다.
 */
fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name)

/** [stringOr]의 배열판 — 원소가 JSON `null`이면 [fallback]. */
fun JSONArray.stringOr(index: Int, fallback: String = ""): String =
    if (isNull(index)) fallback else optString(index, fallback)
