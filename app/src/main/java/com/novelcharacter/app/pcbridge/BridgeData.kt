package com.novelcharacter.app.pcbridge

import com.google.gson.Gson
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.repository.AgeLinkageConflict
import com.novelcharacter.app.data.repository.CharacterFormFields
import com.novelcharacter.app.data.repository.CharacterFormGates
import com.novelcharacter.app.data.repository.CharacterFormSaver
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.util.MultiValueInput
import com.novelcharacter.app.util.stringOr
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * PC 창구가 읽고 쓰는 것 — **화면 없는 계층**.
 *
 * ## 이 파일이 지키는 것
 *
 * - **판정을 새로 적지 않는다.** 필드 목록은 [CharacterFormFields], 저장 전 관문은
 *   [CharacterFormGates], 실제 쓰기는 [CharacterFormSaver], 커버 집합과 타입 분기는
 *   [BridgeForm], 이미지 봉쇄는 [BridgeImageRef]가 든다. 여기 있는 것은 **이음새**뿐이다.
 * - **관문을 건너뛰는 길을 만들지 않는다.** [save]는 관문이 걸리면 멈추고 사유를 돌려주며,
 *   사용자가 고른 해결을 받아서만 다시 간다. 그것이 검증 → 고지 → 바로잡을 경로다.
 * - **JSON은 손으로 짠다.** 리플렉션 직렬화를 쓰면 필드를 더할 때 웹이 조용히 모양을 바꿔
 *   받는다. `org.json`은 `StructuredInputConfig`가 쓰는 그것이다.
 */
class BridgeData(
    private val characters: CharacterRepository,
    private val novels: NovelRepository,
    private val gates: CharacterFormGates,
    private val formFields: CharacterFormFields,
    private val formSaver: CharacterFormSaver,
    private val filesDir: File?,
    private val gson: Gson = Gson()
) {

    // ───────── 읽기 ─────────

    /** 작품 목록 — PC가 범위를 고르는 데 쓴다. */
    suspend fun novelsJson(): String {
        val arr = JSONArray()
        for (n in novels.getAllNovelsList()) {
            arr.put(JSONObject().put("id", n.id).put("title", n.title))
        }
        return JSONObject().put("novels", arr).toString()
    }

    /**
     * 캐릭터 목록.
     *
     * **채운 칸 수를 함께 싣는다.** 사용자의 표본은 정의된 5,617칸 중 1,217칸만 채워져 있고
     * (`docs/scalability_performance_2026-07.md` 2장) PC에서 할 일의 대부분이 그 빈 칸을
     * 채우는 것이다 — 어느 캐릭터가 비었는지 목록에서 바로 보여야 보충이 시작된다.
     *
     * 비용은 캐릭터 축에 붙는 질의 하나다(값 수를 캐릭터별로 세는 것). 측정된 축이다.
     */
    suspend fun charactersJson(novelId: Long?): String {
        val list = if (novelId == null) characters.getAllCharactersList()
        else characters.getCharactersByNovelList(novelId)
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.displayName.ifBlank { c.name })
                    .put("novelId", c.novelId ?: JSONObject.NULL)
                    .put("filledCount", characters.getValuesByCharacterList(c.id).count { it.value.isNotBlank() })
            )
        }
        return JSONObject().put("characters", arr).toString()
    }

    /** 캐릭터 하나의 편집 기술자. 없으면 null. */
    suspend fun characterJson(characterId: Long): String? {
        val c = characters.getCharacterById(characterId) ?: return null
        val novel = c.novelId?.let { novels.getNovelById(it) }
        val fields = formFields.forNovel(novel)
        val values = characters.getValuesByCharacterList(characterId).associate { it.fieldDefinitionId to it.value }
        val tags = characters.getTagsByCharacterList(characterId).map { it.tag }

        // CALCULATED 표시는 아직 싣지 않는다 — 계산기를 부르는 경로는 다음 판이고,
        // 그때까지 **빈 글자로 둔다**(0으로 접지 않는다. BridgeForm의 그 시험이 잠근 자리다).
        val described = BridgeForm.describe(fields, values)

        val images = BridgeImageRef.servable(imagePathsOf(c), filesDir)

        val fieldsArr = JSONArray()
        for (f in described.fields) fieldsArr.put(fieldViewJson(f))

        val imagesArr = JSONArray()
        for ((id, _) in images.entries) imagesArr.put(id)

        val novelsArr = JSONArray()
        for (n in novels.getAllNovelsList()) {
            novelsArr.put(JSONObject().put("id", n.id).put("title", n.title))
        }

        return JSONObject()
            .put("id", c.id)
            .put("name", c.name)
            .put("lastName", c.lastName)
            .put("firstName", c.firstName)
            .put("anotherName", c.anotherName)
            // 태그는 **저장된 토큰을 칸 한 줄로 만들어** 보낸다 — 되쪼개기는 저장 때 폰이 한다.
            // 짝을 갈라 두면 왕복 한 번에 값이 갈린다(R-63 · `MultiValueInput`의 KDoc).
            .put("tags", MultiValueInput.format(tags))
            .put("memo", c.memo)
            .put("novelId", c.novelId ?: JSONObject.NULL)
            .put("novels", novelsArr)
            .put("fields", fieldsArr)
            .put("images", imagesArr)
            // 잘라낸 것은 개수로 존재를 알린다 (R-14) — 앱 저장소 밖이라 못 보내는 그림 수다.
            .put("blockedImageCount", images.blockedCount)
            .toString()
    }

    private fun fieldViewJson(f: BridgeForm.FieldView): JSONObject {
        val o = JSONObject()
            .put("id", f.id)
            .put("key", f.key)
            .put("name", f.name)
            .put("group", f.group)
            .put("type", f.type)
            .put("required", f.required)
            .put("description", f.description)
            .put("readOnly", f.readOnly)
            .put("notice", f.notice ?: JSONObject.NULL)
        when (val input = f.input) {
            is BridgeForm.Input.Line -> o.put("kind", "line").put("value", input.value)
            is BridgeForm.Input.Numeric -> o.put("kind", "numeric").put("value", input.value)
            is BridgeForm.Input.Choice -> {
                val opts = JSONArray()
                for (x in input.options) opts.put(x)
                o.put("kind", "choice").put("value", input.value).put("options", opts)
            }
            is BridgeForm.Input.Parts -> {
                val parts = JSONArray()
                for (p in input.parts) {
                    parts.put(
                        JSONObject()
                            .put("label", p.label)
                            .put("hint", p.hint)
                            .put("numeric", p.numeric)
                            .put("value", p.value)
                    )
                }
                o.put("kind", "parts").put("parts", parts)
            }
            is BridgeForm.Input.Readonly -> o.put("kind", "readonly").put("display", input.display)
        }
        return o
    }

    /**
     * 보충 모드의 다음 대상 — **빈 칸이 가장 많은 쪽부터**가 아니라 목록 순서의 다음이다.
     *
     * 순서를 바꾸지 않는 것이 요점이다. '저장하고 다음'은 사용자가 보고 있는 목록을 따라가야
     * 하고, 매번 "가장 빈 캐릭터"로 뛰면 어디까지 했는지 알 수 없게 된다.
     *
     * @param after 지금 자리. null이면 처음부터.
     */
    suspend fun supplementNextJson(novelId: Long?, after: Long?): String {
        val list = if (novelId == null) characters.getAllCharactersList()
        else characters.getCharactersByNovelList(novelId)
        val idx = if (after == null) -1 else list.indexOfFirst { it.id == after }
        val next = list.getOrNull(idx + 1)
        return JSONObject()
            .put("nextId", next?.id ?: JSONObject.NULL)
            .put("remaining", (list.size - (idx + 2)).coerceAtLeast(0))
            .toString()
    }

    // ───────── 이미지 ─────────

    sealed class ImageOutcome {
        data class Found(val file: File) : ImageOutcome()
        object NotFound : ImageOutcome()
        /** 앱 저장소 밖 — **바이트는 나가지 않는다**(R-39). */
        object Blocked : ImageOutcome()
    }

    suspend fun image(characterId: Long, imageId: String): ImageOutcome {
        val c = characters.getCharacterById(characterId) ?: return ImageOutcome.NotFound
        return when (val r = BridgeImageRef.resolve(imageId, imagePathsOf(c), filesDir)) {
            is BridgeImageRef.Resolution.Found -> {
                val f = File(r.path)
                if (f.isFile) ImageOutcome.Found(f) else ImageOutcome.NotFound
            }
            BridgeImageRef.Resolution.NotFound -> ImageOutcome.NotFound
            is BridgeImageRef.Resolution.Blocked -> ImageOutcome.Blocked
        }
    }

    /**
     * `imagePaths` JSON을 경로 목록으로.
     *
     * > **발견 사항(이 판에서 고치지 않는다):** 같은 파싱이 `ui/`와 `data/repository`에
     * > `parseImagePaths`라는 사적 함수로 **다섯 벌 이상** 흩어져 있다. 여기서 공용 단일
     * > 소스를 새로 세우지 **않은** 이유는 그 다섯을 함께 옮기지 못하기 때문이다 —
     * > 아무도 쓰지 않는 단일 소스는 권위처럼 보이면서 복사본이 따로 흐르게 두는
     * > 함정이고(R-43이 배운 것), 그 통합은 `ui/`를 건드리는 별개 작업이다.
     */
    private fun imagePathsOf(c: Character): List<String> =
        runCatching { gson.fromJson(c.imagePaths, Array<String>::class.java)?.toList() }
            .getOrNull().orEmpty()

    // ───────── 저장 ─────────

    /** 저장 결과. 관문이 걸리면 **쓰지 않고** 사유를 낸다. */
    sealed class SaveOutcome {
        data class Saved(val characterId: Long, val preserved: Int, val keptGlobal: Int) : SaveOutcome()

        /** 캐릭터가 없다. */
        object NotFound : SaveOutcome()

        /** 본문을 읽을 수 없다. [what]은 사유 코드. */
        data class BadBody(val what: String) : SaveOutcome()

        /** 나이–출생연도가 어긋났다. 사용자가 해결을 고르면 다시 보낸다. */
        data class AgeConflict(val conflict: AgeLinkageConflict) : SaveOutcome()

        /** restricted 라이브러리에 없는 토큰이 있다. */
        data class Restricted(val violations: List<Pair<FieldDefinition, List<String>>>) : SaveOutcome()

        /** 세계관을 옮기는 저장이고 **잃는 것이 있다**. */
        data class CrossUniverseLoss(val universeId: Long, val summary: String) : SaveOutcome()
    }

    /**
     * 웹이 보낸 본문으로 캐릭터를 저장한다.
     *
     * 본문의 모양:
     * ```
     * { "name":"…", "lastName":"…", "firstName":"…", "anotherName":"…",
     *   "tags":"쉼표로 적은 한 줄", "memo":"…", "novelId": 12|null,
     *   "fields": { "<필드정의id>": "날 글자" | ["파트1","파트2"] },
     *   "resolve": { "restricted":"add" } }
     * ```
     *
     * `resolve`는 **사용자가 고른 해결**이다. 없으면 관문이 그대로 선다 — 기본값으로
     * 통과시키지 않는다(관문을 건너뛰는 길을 만들지 않는다).
     */
    suspend fun save(characterId: Long, body: String): SaveOutcome {
        val existing = characters.getCharacterById(characterId) ?: return SaveOutcome.NotFound
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return SaveOutcome.BadBody("json")

        val novelId = if (root.isNull("novelId")) null else root.optLong("novelId").takeIf { it > 0 }
        val novel = novelId?.let { novels.getNovelById(it) }
        val fields = formFields.forNovel(novel)

        val submitted = parseSubmitted(root.optJSONObject("fields"))
        val described = BridgeForm.describe(fields, emptyMap())
        val values = BridgeForm.collect(fields, submitted, characterId)

        // ── 관문 ①: restricted 입력 ──
        val violations = gates.findRestrictedViolations(values)
        if (violations.isNotEmpty()) {
            val choice = root.optJSONObject("resolve")?.stringOr("restricted").orEmpty()
            if (choice != "add") return SaveOutcome.Restricted(violations)
            // '추가하고 저장' — 앱 다이얼로그와 같은 교정 경로다.
            gates.addRestrictedValuesToLibrary(violations)
        }

        // ── 관문 ②: 나이–출생연도 ──
        val conflict = gates.detectAgeLinkageConflict(novelId, characterId, values)
        if (conflict != null) return SaveOutcome.AgeConflict(conflict)

        // ── 관문 ③: 세계관 이동 손실 ──
        // 1단계의 웹 폼은 작품을 고칠 수 있으므로 이 관문이 실제로 선다.
        val oldUniverseId = existing.novelId?.let { novels.getNovelById(it)?.universeId }
        val newUniverseId = novel?.universeId
        if (newUniverseId != null && oldUniverseId != null && newUniverseId != oldUniverseId) {
            val loss = gates.countCrossUniverseLoss(characterId, newUniverseId)
            if (loss.hasRemoval) {
                val confirmed = root.optJSONObject("resolve")?.optBoolean("crossUniverse") ?: false
                if (!confirmed) {
                    return SaveOutcome.CrossUniverseLoss(newUniverseId, describeLoss(loss))
                }
            }
        }

        val character = existing.copy(
            // **`optString`을 쓰지 않는다** — 기기(libcore)는 JSON null에 문자열 "null"을
            // 돌려주므로 이름이 그 글자로 저장될 수 있다(R-70 · `tools/check_platform_api_parity.sh`).
            name = root.stringOr("name", existing.name),
            lastName = root.stringOr("lastName", existing.lastName),
            firstName = root.stringOr("firstName", existing.firstName),
            anotherName = root.stringOr("anotherName", existing.anotherName),
            memo = root.stringOr("memo", existing.memo),
            novelId = novelId,
            updatedAt = System.currentTimeMillis()
        )

        // 태그 줄을 되쪼갠다 — 채우기와 짝인 그 함수다(`characterJson`이 `format`을 썼다).
        val tagList = MultiValueInput.parse(root.stringOr("tags"))

        val crossUniv = if (newUniverseId != null && oldUniverseId != null && newUniverseId != oldUniverseId)
            newUniverseId else null
        val leavingUniverse = oldUniverseId != null && newUniverseId == null

        val saved = formSaver.save(
            character = character,
            values = values,
            tags = tagList,
            coveredIds = described.coveredFieldDefinitionIds,
            updating = true,
            crossUniverseId = crossUniv,
            leavingUniverse = leavingUniverse
        )
        return SaveOutcome.Saved(saved.id, saved.preserved, saved.keptGlobal)
    }

    /**
     * 세계관 이동이 **무엇을 잃는가**를 사람이 읽는 한 문장으로.
     *
     * `UniverseMoveCounts`를 그대로 글자로 바꾸면 개발자용 표기가 화면에 나간다. 그리고
     * **`keptGlobalValues`는 세지 않는다** — 그 수는 유실이 아니라 보관이고(그 필드의 KDoc이
     * 적어 둔 그대로), 합쳐 세면 *"사라집니다"*가 거짓 고지가 된다.
     */
    private fun describeLoss(loss: com.novelcharacter.app.data.repository.UniverseMoveCounts): String {
        val parts = ArrayList<String>(3)
        if (loss.removedValues > 0) parts.add("값 ${loss.removedValues}개")
        if (loss.removedMemberships > 0) parts.add("세력 소속 ${loss.removedMemberships}개")
        if (parts.isEmpty()) return ""
        val lost = parts.joinToString(" · ")
        val kept = if (loss.remappedValues > 0) " 이름이 같은 값 ${loss.remappedValues}개는 그대로 옮겨집니다." else ""
        // **복구를 약속하지 않는다.** 저장소가 스냅샷을 남기지만 그 복구 경로가 PC 화면에
        // 열려 있지 않으므로, 여기서 "되돌릴 수 있습니다"라고 적으면 지키지 못하는 말이 된다.
        return "$lost 가 사라집니다.$kept"
    }

    /** `fields` 객체를 [BridgeForm.Submitted]로. 글자는 글자로, 배열은 파트로 읽는다. */
    private fun parseSubmitted(obj: JSONObject?): Map<Long, BridgeForm.Submitted> {
        if (obj == null) return emptyMap()
        val out = HashMap<Long, BridgeForm.Submitted>()
        for (key in obj.keys()) {
            val id = key.toLongOrNull() ?: continue
            when (val v = obj.get(key)) {
                is JSONArray -> {
                    val parts = ArrayList<String>(v.length())
                    for (i in 0 until v.length()) parts.add(v.stringOr(i))
                    out[id] = BridgeForm.Submitted.Parts(parts)
                }
                else -> out[id] = BridgeForm.Submitted.Text(v?.toString().orEmpty())
            }
        }
        return out
    }
}
