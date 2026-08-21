package com.novelcharacter.app.data.model

import org.json.JSONObject
import com.novelcharacter.app.util.stringOr

/**
 * 심긴 필드가 **어느 전역 기본 필드에서 왔는가**의 표현 — 단일 소스 (B-119).
 *
 * 설계 정본은 `docs/feature_roadmap_2026-08.md` **1장**이다.
 *
 * ```json
 * "defaultField": "<DefaultFieldTemplate.code>"
 * ```
 *
 * ## 왜 config 키인가 — 그리고 왜 그것으로 충분한가
 *
 * 표식은 *"이 필드가 템플릿과 연결돼 있다"* 하나뿐이라 칸을 팔 이유가 없다. config 키로 두면
 * **[FieldDefinition] 쪽 마이그레이션이 없고**(설계 1-2), 이미 config를 통째로 나르는 자리들
 * (프리셋 직렬화·월드패키지·엑셀 `설정(JSON)`·휴지통 스냅샷)이 표식을 **공짜로 함께 나른다.**
 *
 * ## 이 참조는 세계관 경계에서 걷어내지 않는다 — [FieldConfigTransfer]와 정반대다
 *
 * 그 파일이 걷어내는 둘(등급 체계 code · 대결 축 code)은 **세계관 단위**라 경계를 넘으면 유령이
 * 되거나 남의 것을 정확히 집는다. 이쪽은 **전역**이다 — [DefaultFieldTemplate]에는 `universeId`가
 * 없고 모든 세계관이 같은 템플릿을 본다. 그래서 필드가 세계관 A에서 B로 복사돼도 표식은 여전히
 * 참인 사실이며, 걷어내면 되레 *"B의 그 필드만 기본 필드가 아닌 것처럼 보이는"* 어긋남이 난다.
 *
 * **이 문단이 [FieldConfigTransfer]에 새 키를 더하려는 다음 사람을 위해 있다** — 그 파일은
 * *"config 안의 참조는 경계에서 걷어낸다"*를 규약(R-35)으로 세워 뒀고, 이 키는 그 규약의
 * 예외가 아니라 **전제가 다른 것**이다(경계 안에서만 뜻이 있는 참조가 아니다).
 *
 * ## 가리키는 템플릿이 사라졌다면
 *
 * **관대하게 읽는다** — 읽는 쪽이 code로 템플릿을 찾지 못하면 그냥 연결이 없는 것으로 본다
 * ([GradeSystemRef]가 체계를 못 찾을 때와 같은 규칙). 필드 자체는 정의도 값도 온전하므로
 * 동작에 구멍이 없고, 표식 청소는 강등([remove])이 명시적으로 할 때만 일어난다.
 * 엑셀 가져오기만은 **찾지 못한 연결을 세어 말한다**(설계 1-5 — 거부가 아니라 수용·교정).
 */
object DefaultFieldRef {

    /** 연결 표식이 사는 config 키. 없으면 이 필드는 전역 기본 필드와 무관하다. */
    const val CONFIG_KEY = "defaultField"

    /**
     * 이 필드가 가리키는 템플릿 code. 키 없음·빈 값·손상 JSON = null.
     *
     * **빈 문자열을 null과 같게 보는 것**은 엑셀 왕복 때문이다 — `기본필드코드` 열을 비운 채
     * 들여온 파일이 `"defaultField": ""`를 만들 수 있는데, 그것은 연결이 아니라 해제다.
     */
    fun codeFromConfig(configJson: String): String? = try {
        JSONObject(configJson).stringOr(CONFIG_KEY, "").takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** 연결돼 있는가 — 화면이 배지 하나를 그릴 때 code까지 필요하지 않다. */
    fun isLinked(configJson: String): Boolean = codeFromConfig(configJson) != null

    /**
     * 표식을 적는다(그 외 키는 보존 — 수술적 편집. 손상 JSON은 원문 유지).
     *
     * [code]가 비면 [remove]와 같다 — 빈 표식은 *"어딘가에서 왔다"*고 말하면서 어디인지는
     * 말하지 않는 상태라, 화면은 배지를 그리는데 전파는 대상으로 세지 않는다.
     */
    fun write(configJson: String, code: String): String {
        if (code.isBlank()) return remove(configJson)
        return try {
            val json = if (configJson.isBlank()) JSONObject() else JSONObject(configJson)
            json.put(CONFIG_KEY, code)
            json.toString()
        } catch (_: Exception) {
            configJson
        }
    }

    /**
     * 표식을 걷어낸다 — **강등**이다(템플릿 삭제·승격 해제·연결을 못 찾은 가져오기).
     *
     * 강등은 필드를 지우지 않는다. 값도 정의도 그대로 있고 *"기본 필드에서 왔다"*는 사실만
     * 사라진다 — 이 기능이 캐릭터 데이터를 지우는 경로는 어디에도 없다(설계 1-3, 개발 의도 2번).
     *
     * 지울 키가 없으면 **원문 그대로** 돌려준다([DuelGradeRef.remove]와 같은 규칙) — 재직렬화는
     * 키 순서를 흔들어 무관한 config까지 *"바뀐 것"*으로 만들고, 프리셋 왕복·전파 미리보기의
     * 변경 감지가 그 비교 위에 서 있다.
     */
    fun remove(configJson: String): String = try {
        val json = JSONObject(configJson)
        if (!json.has(CONFIG_KEY)) {
            configJson
        } else {
            json.remove(CONFIG_KEY)
            json.toString()
        }
    } catch (_: Exception) {
        configJson
    }
}
