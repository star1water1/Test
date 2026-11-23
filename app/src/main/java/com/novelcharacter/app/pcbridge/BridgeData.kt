package com.novelcharacter.app.pcbridge

import com.google.gson.Gson
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.repository.AgeLinkageConflict
import com.novelcharacter.app.data.repository.CharacterFormFields
import com.novelcharacter.app.data.repository.CharacterFormGates
import com.novelcharacter.app.data.repository.CharacterFormSaver
import com.novelcharacter.app.data.repository.CharacterRepository
import com.novelcharacter.app.data.repository.FactionRepository
import com.novelcharacter.app.data.repository.NovelRepository
import com.novelcharacter.app.data.repository.RelationshipRules
import com.novelcharacter.app.data.repository.UniverseRepository
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
    private val universes: UniverseRepository,
    private val factions: FactionRepository,
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

    // ───────── 관계 ─────────

    /**
     * 관계 목록과 편집 기술자. 캐릭터가 없으면 null.
     *
     * **비용은 관계 축이 아니라 캐릭터 축에 붙는다**(설계 9-5 ②를 이 판이 확인한 것이다).
     * 관계 행은 *이 캐릭터에 닿는 것만* 읽고(인덱스가 두 끝에 있다), 상대 이름은 행마다 묻지
     * 않고 **한 번에 묻는다**(R-54 — 행마다 묻는 모양이 앱의 관계 목록에 그대로 있다).
     * 남는 전량 순회는 상대 고르기 후보뿐이고 그것은 `charactersJson`이 이미 쓰는 축이다.
     */
    suspend fun relationshipsJson(characterId: Long): String? {
        val self = characters.getCharacterById(characterId) ?: return null
        val rows = characters.getRelationshipsForCharacterList(characterId)

        val novelList = novels.getAllNovelsList()
        val universeOf: Map<Long, Long?> = novelList.associate { it.id to it.universeId }
        val novelTitles = novelList.associate { it.id to it.title }
        val selfUniverseId = self.novelId?.let { universeOf[it] }

        val names = namesOf(rows.map { RelationshipRules.otherEndOf(it, characterId) })
        val factionNames = factionNamesOf(rows)

        val views = BridgeRelationships.describe(rows, characterId, names, factionNames)
        val candidates = BridgeRelationships.candidatesFor(
            all = characters.getAllCharactersList(),
            selfId = characterId,
            selfUniverseId = selfUniverseId,
            universeOf = universeOf,
            novelTitles = novelTitles
        )

        val rowsArr = JSONArray()
        for (v in views) rowsArr.put(relationshipViewJson(v))

        val typesArr = JSONArray()
        for (t in typesFor(selfUniverseId)) typesArr.put(t)

        val candArr = JSONArray()
        for (c in candidates) {
            candArr.put(
                JSONObject().put("id", c.id).put("name", c.name).put("novelTitle", c.novelTitle)
            )
        }

        return JSONObject()
            .put("characterId", characterId)
            .put("relationships", rowsArr)
            .put("types", typesArr)
            .put("candidates", candArr)
            .put("minIntensity", RelationshipRules.MIN_INTENSITY)
            .put("maxIntensity", RelationshipRules.MAX_INTENSITY)
            // 화살표 글자도 **코틀린이 든다.** 행마다 실어 보내는 그 글자와 방향 고르개의
            // 글자가 갈리면 같은 뜻이 두 모양으로 보인다(5-5의 단위 괄호와 같은 축이다).
            .put(
                "arrows",
                JSONObject()
                    .put("both", BridgeRelationships.ARROW_BOTH)
                    .put("out", BridgeRelationships.ARROW_OUT)
                    .put("in", BridgeRelationships.ARROW_IN)
            )
            .toString()
    }

    private fun relationshipViewJson(v: BridgeRelationships.RelationshipView): JSONObject =
        JSONObject()
            .put("id", v.id)
            .put("code", v.code ?: JSONObject.NULL)
            .put("otherCharacterId", v.otherCharacterId)
            .put("otherCharacterName", v.otherCharacterName)
            .put("type", v.type)
            .put("description", v.description)
            .put("intensity", v.intensity)
            .put("bidirectional", v.bidirectional)
            .put("outgoing", v.outgoing)
            .put("arrow", v.arrow)
            .put("readOnly", v.readOnly)
            .put("notice", v.notice ?: JSONObject.NULL)
            .put("factionName", v.factionName ?: JSONObject.NULL)

    /** 이 세계관의 관계 유형 — 세계관이 없으면 기본 유형이다(`CharacterViewModel`과 같다). */
    private suspend fun typesFor(universeId: Long?): List<String> =
        universeId?.let { universes.getUniverseById(it)?.getRelationshipTypes() }
            ?: Universe.DEFAULT_RELATIONSHIP_TYPES

    private suspend fun namesOf(ids: List<Long>): Map<Long, String> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return characters.getCharactersByIds(distinct)
            .associate { it.id to it.displayName.ifBlank { it.name } }
    }

    private suspend fun factionNamesOf(rows: List<CharacterRelationship>): Map<Long, String> {
        val out = HashMap<Long, String>()
        for (id in rows.mapNotNull { it.factionId }.distinct()) {
            factions.getFactionById(id)?.let { out[id] = it.name }
        }
        return out
    }

    /** 관계 저장 결과. 막히면 **쓰지 않고** 사유를 낸다. */
    sealed class RelationshipOutcome {
        data class Saved(val relationshipId: Long) : RelationshipOutcome()

        object CharacterNotFound : RelationshipOutcome()

        /** 고치려는 관계가 없거나 이 캐릭터의 것이 아니다. */
        object RelationshipNotFound : RelationshipOutcome()

        data class BadBody(val what: String) : RelationshipOutcome()

        /** 상대로 적힌 인물이 없다. */
        object OtherNotFound : RelationshipOutcome()

        /**
         * 상대가 **다른 세계관**이다. AI 쪽 `validEndpoints`가 마지막에 요구하는 그 조건이고,
         * 세계관이 다른 둘을 이으면 그 관계는 어느 세계관의 관계 유형에도 속하지 않는다.
         */
        object DifferentUniverse : RelationshipOutcome()

        /** [RelationshipRules]가 막았다. [blocking]은 길을 막은 관계(겹침 사유일 때만). */
        data class Rejected(
            val rejection: RelationshipRules.Rejection,
            val blocking: BridgeRelationships.RelationshipView?
        ) : RelationshipOutcome()

        /**
         * 유일 인덱스가 넣기를 거절했다 — [RelationshipRules]를 지난 뒤에도 남는 자리다.
         *
         * **조용히 성공으로 접지 않는다.** DAO의 넣기는 `OnConflictStrategy.IGNORE`라 충돌하면
         * 예외 대신 `-1`을 돌려주므로, 그 수를 보지 않으면 *"저장했습니다"*가 거짓이 된다.
         */
        object NotInserted : RelationshipOutcome()
    }

    /**
     * 관계 하나를 더하거나 고친다.
     *
     * 본문의 모양:
     * ```
     * { "otherCharacterId": 12, "type":"연인", "description":"…",
     *   "intensity": 7, "bidirectional": true, "outgoing": true }
     * ```
     *
     * @param relationshipId null이면 더하기, 있으면 그 행을 고치기.
     */
    suspend fun saveRelationship(
        characterId: Long,
        relationshipId: Long?,
        body: String
    ): RelationshipOutcome {
        val self = characters.getCharacterById(characterId)
            ?: return RelationshipOutcome.CharacterNotFound

        val draft = when (val r = BridgeRelationships.parseDraft(body)) {
            is BridgeRelationships.DraftResult.Bad -> return RelationshipOutcome.BadBody(r.what)
            is BridgeRelationships.DraftResult.Parsed -> r.draft
        }

        // 고치는 중이면 **그 행이 이 캐릭터의 것인지** 먼저 본다. 길에 적힌 id를 그대로 믿으면
        // 다른 캐릭터의 관계를 이 화면에서 고칠 수 있게 된다(이미지 서빙을 캐릭터로 묶은 것과
        // 같은 판단이다 — `Route.Image`의 KDoc).
        val existing = if (relationshipId == null) null else {
            val row = characters.getRelationshipById(relationshipId)
                ?: return RelationshipOutcome.RelationshipNotFound
            if (!RelationshipRules.touches(row, characterId)) {
                return RelationshipOutcome.RelationshipNotFound
            }
            row
        }

        val other = characters.getCharacterById(draft.otherCharacterId)
            ?: return RelationshipOutcome.OtherNotFound

        val novelList = novels.getAllNovelsList()
        val universeOf: Map<Long, Long?> = novelList.associate { it.id to it.universeId }
        val selfUniverseId = self.novelId?.let { universeOf[it] }
        val otherUniverseId = other.novelId?.let { universeOf[it] }
        if (selfUniverseId != otherUniverseId) return RelationshipOutcome.DifferentUniverse

        val proposed = BridgeRelationships.toRow(characterId, draft, existing)
        val others = characters.getRelationshipsForCharacterList(characterId)
            .filter { it.id != existing?.id }

        when (
            val verdict = RelationshipRules.validate(
                proposed = proposed,
                existing = existing,
                others = others,
                allowedTypes = typesFor(selfUniverseId)
            )
        ) {
            is RelationshipRules.Verdict.Rejected -> {
                val blocking = verdict.blocking?.let { row ->
                    BridgeRelationships.describe(
                        rows = listOf(row),
                        selfId = characterId,
                        names = namesOf(listOf(RelationshipRules.otherEndOf(row, characterId))),
                        factionNames = factionNamesOf(listOf(row))
                    ).firstOrNull()
                }
                return RelationshipOutcome.Rejected(verdict.rejection, blocking)
            }
            RelationshipRules.Verdict.Allowed -> Unit
        }

        if (existing == null) {
            val id = characters.insertRelationship(proposed)
            if (id <= 0) return RelationshipOutcome.NotInserted
            return RelationshipOutcome.Saved(id)
        }
        characters.updateRelationship(proposed)
        return RelationshipOutcome.Saved(proposed.id)
    }

    /** 관계 삭제 결과. */
    sealed class RelationshipDeleteOutcome {
        object Deleted : RelationshipDeleteOutcome()

        object CharacterNotFound : RelationshipDeleteOutcome()

        object RelationshipNotFound : RelationshipDeleteOutcome()

        /** 세력 자동 관계는 지우지 않는다 — 세력 쪽 소속을 고쳐야 한다. */
        data class Automatic(val factionName: String?) : RelationshipDeleteOutcome()

        /**
         * **확인을 받기 전에는 지우지 않는다**(설계 9-4).
         *
         * [historyCount]는 함께 사라지는 관계 변화 이력 수다 — 그 행들은 외래키가 `CASCADE`라
         * 관계를 지우면 **말없이 함께 지워진다.** 되돌릴 경로가 PC 화면에 없으므로 수를
         * 먼저 보여 주는 것이 이 관문의 일이다(앱 다이얼로그도 같은 수를 보여 준다).
         */
        data class NeedsConfirm(val historyCount: Int) : RelationshipDeleteOutcome()
    }

    /**
     * 관계 하나를 지운다.
     *
     * 본문의 모양: `{ "resolve": { "delete": true } }`
     *
     * `resolve.delete`가 없으면 [RelationshipDeleteOutcome.NeedsConfirm]이다 — 기본값으로
     * 통과시키지 않는다(관문을 건너뛰는 길을 만들지 않는다).
     */
    suspend fun deleteRelationship(
        characterId: Long,
        relationshipId: Long,
        body: String
    ): RelationshipDeleteOutcome {
        characters.getCharacterById(characterId)
            ?: return RelationshipDeleteOutcome.CharacterNotFound

        val row = characters.getRelationshipById(relationshipId)
            ?: return RelationshipDeleteOutcome.RelationshipNotFound
        if (!RelationshipRules.touches(row, characterId)) {
            return RelationshipDeleteOutcome.RelationshipNotFound
        }
        if (RelationshipRules.isAutomatic(row)) {
            return RelationshipDeleteOutcome.Automatic(
                row.factionId?.let { factions.getFactionById(it)?.name }
            )
        }

        val confirmed = runCatching { JSONObject(body) }.getOrNull()
            ?.optJSONObject("resolve")
            ?.optBoolean(BridgeRelationships.RESOLVE_DELETE) ?: false
        if (!confirmed) {
            // 이력 수는 **고지할 때만** 센다 — 확인이 끝난 요청에서 세면 쓰지 않을 질의다.
            return RelationshipDeleteOutcome.NeedsConfirm(
                characters.getRelationshipChangesList(relationshipId).size
            )
        }

        characters.deleteRelationshipById(relationshipId)
        return RelationshipDeleteOutcome.Deleted
    }
}
