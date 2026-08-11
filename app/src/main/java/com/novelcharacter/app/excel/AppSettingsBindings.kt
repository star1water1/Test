package com.novelcharacter.app.excel

import android.content.Context
import com.novelcharacter.app.ai.AiCreativity
import com.novelcharacter.app.ai.AiKeyStore
import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.AiProviderStore
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.ai.EventAiMaterial
import com.novelcharacter.app.backup.BackupSettingsStore
import com.novelcharacter.app.data.settings.TrashSettingsStore
import com.novelcharacter.app.data.settings.FieldImportSettingsStore
import com.novelcharacter.app.ui.assistant.AssistantPrefs
import com.novelcharacter.app.ui.assistant.InsightCategory
import com.novelcharacter.app.ui.stats.CompletionWeightPrefs
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.ui.stats.PatternTypePrefs
import com.novelcharacter.app.ui.supplement.RandomSupplementSettings
import com.novelcharacter.app.ui.supplement.SupplementCriteria
import com.novelcharacter.app.util.CompletionWeights
import com.novelcharacter.app.util.ImageSettingsStore
import com.novelcharacter.app.util.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * '앱 설정' 시트의 **읽기·쓰기** — [AppSettingsKeys]가 선언한 것을 실제 저장소에 잇는다 (B-105).
 *
 * ## 한 설정이 한 자리다
 *
 * 종전에는 내보내기의 나열과 가져오기의 `when` 분기가 **두 곳**이었고, 그래서 설정이 늘지
 * 않았다. 여기서는 [Binding] 하나가 읽기와 쓰기를 **함께** 든다 — 한쪽만 적는 것이
 * 문법적으로 불가능하다. 새 설정은 [AppSettingsKeys]에 선언 한 줄 + 여기 바인딩 한 줄이고,
 * **선언 없는 바인딩은 컴파일되지 않는다**([Binding]이 `Spec`을 받기 때문이다).
 * 반대쪽(선언만 있고 바인딩이 없는 것)은 `tools/check_app_settings_catalog.sh`가 본다.
 *
 * ## 쓰기는 관대하되 조용하지 않다
 *
 * 저장소 setter가 대개 자체 클램프를 갖고 있어 범위 밖 값도 안전하게 수용된다(관대 임포트).
 * 그러나 **뜻을 알 수 없는 값**(모르는 열거 이름 등)은 기존 설정을 유지하고 [Applied.No]로
 * 사유를 돌려준다 — 가져오기가 그것을 경고로 싣는다. 조용히 무시하면 사용자는 자기가 적은
 * 값이 왜 안 먹었는지 알 길이 없다(개발 의도 2번 '변수 제어').
 */
object AppSettingsBindings {

    /** 쓰기의 결과. [No]의 [No.reason]은 그대로 사용자에게 나가는 문장이다. */
    sealed class Applied {
        data object Yes : Applied()
        data class No(val reason: String) : Applied()
    }

    /**
     * 한 설정의 실제 입출력.
     *
     * @param read 저장소 → 셀 값. `null`이면 **그 행을 아예 쓰지 않는다**(값이 없는 것과
     *   빈 값은 다르다 — 비밀 키가 하나도 없을 때 빈 칸을 내보내면 *"동의했는데 아무것도
     *   안 실렸다"*가 *"키가 없다"*와 구별되지 않는다).
     */
    class Binding(
        val spec: AppSettingsKeys.Spec,
        val read: suspend (Context) -> String?,
        val write: suspend (Context, String) -> Applied
    )

    // 셀 표기는 순수 계층이 든다([AppSettingsKeys]) — *시트가 무슨 글자를 담는가*도 왕복
    // 계약이라 시험이 닿아야 한다. 여기서 다시 적으면 두 벌이 되어 한쪽만 고쳐지는 날이 온다.
    private fun bool(value: Boolean) = AppSettingsKeys.formatBoolean(value)
    private fun num(value: Number) = AppSettingsKeys.formatNumber(value)
    private fun intOf(value: String) = AppSettingsKeys.parseIntCell(value)

    private val BINDINGS: List<Binding> = listOf(
        Binding(AppSettingsKeys.THEME_MODE,
            read = { num(ThemeHelper.getSavedTheme(it)) },
            write = { ctx, v ->
                ThemeHelper.saveTheme(ctx, (intOf(v) ?: 0).coerceIn(0, 2))
                Applied.Yes
            }),

        // ── 백업 ──
        Binding(AppSettingsKeys.BACKUP_INCLUDE_IMAGES,
            read = { bool(BackupSettingsStore(it).getSettings().includeImages) },
            write = { ctx, v -> BackupSettingsStore(ctx).setIncludeImages(parseSheetBoolean(v)); Applied.Yes }),
        Binding(AppSettingsKeys.BACKUP_MAX_BACKUPS,
            read = { num(BackupSettingsStore(it).getSettings().maxBackups) },
            write = { ctx, v ->
                intOf(v)?.let { BackupSettingsStore(ctx).setMaxBackups(it); Applied.Yes }
                    ?: Applied.No("숫자가 아닙니다")
            }),

        // ── 휴지통 보관 정책 (B-74) ──
        // 범위를 벗어난 값은 거절하지 않고 **좁혀서 받는다**(개발 의도 4번 — 밖에서 편집된
        // 파일을 유연하게 수용). 좁히는 규칙은 TrashRetentionPolicy.sanitize 한 벌이다.
        Binding(AppSettingsKeys.TRASH_MAX_OPERATIONS,
            read = { num(TrashSettingsStore(it).getSettings().maxOperations) },
            write = { ctx, v ->
                intOf(v)?.let { TrashSettingsStore(ctx).setMaxOperations(it); Applied.Yes }
                    ?: Applied.No("숫자가 아닙니다")
            }),
        Binding(AppSettingsKeys.TRASH_RETENTION_DAYS,
            read = { num(TrashSettingsStore(it).getSettings().retentionDays) },
            write = { ctx, v ->
                intOf(v)?.let { TrashSettingsStore(ctx).setRetentionDays(it); Applied.Yes }
                    ?: Applied.No("숫자가 아닙니다")
            }),

        // ── 필드 가져오기 종류 변환 (B-63) ──
        // 모르는 타입 이름을 **버리지 않는다** — 새 타입이 생긴 기기의 파일을 옛 기기가
        // 읽고 다시 내보낼 때 사용자가 켜 둔 것이 왕복에서 사라지면 안 된다(개발 의도 4번).
        Binding(AppSettingsKeys.FIELD_IMPORT_CONVERTIBLE_TYPES,
            read = {
                FieldImportSettingsStore.format(FieldImportSettingsStore(it).getConvertibleTypes())
            },
            write = { ctx, v ->
                // **빈 칸도 값이다** — 전부 꺼서 '종류 바꿔 심기'를 닫아 둔 상태다.
                FieldImportSettingsStore(ctx)
                    .setConvertibleTypes(FieldImportSettingsStore.parse(v))
                Applied.Yes
            }),

        // ── 이미지 저장 ──
        Binding(AppSettingsKeys.IMAGE_COMPRESS_ENABLED,
            read = { bool(ImageSettingsStore(it).getSettings().enabled) },
            write = { ctx, v -> ImageSettingsStore(ctx).setEnabled(parseSheetBoolean(v)); Applied.Yes }),
        Binding(AppSettingsKeys.IMAGE_QUALITY_PERCENT,
            read = { num(ImageSettingsStore(it).getSettings().qualityPercent) },
            write = { ctx, v ->
                intOf(v)?.let { ImageSettingsStore(ctx).setQualityPercent(it); Applied.Yes }
                    ?: Applied.No("숫자가 아닙니다")
            }),
        Binding(AppSettingsKeys.IMAGE_CAP_DIMENSION,
            read = { bool(ImageSettingsStore(it).getSettings().capDimension) },
            write = { ctx, v -> ImageSettingsStore(ctx).setCapDimension(parseSheetBoolean(v)); Applied.Yes }),
        Binding(AppSettingsKeys.IMAGE_MAX_LONG_EDGE_PX,
            read = { num(ImageSettingsStore(it).getSettings().maxLongEdgePx) },
            write = { ctx, v ->
                intOf(v)?.let { ImageSettingsStore(ctx).setMaxLongEdgePx(it); Applied.Yes }
                    ?: Applied.No("숫자가 아닙니다")
            }),
        Binding(AppSettingsKeys.IMAGE_SKIP_BELOW_ENABLED,
            read = { bool(ImageSettingsStore(it).getSettings().skipBelowEnabled) },
            write = { ctx, v -> ImageSettingsStore(ctx).setSkipBelowEnabled(parseSheetBoolean(v)); Applied.Yes }),
        Binding(AppSettingsKeys.IMAGE_SKIP_BELOW_BYTES,
            read = { num(ImageSettingsStore(it).getSettings().skipBelowBytes) },
            write = { ctx, v ->
                v.trim().toDoubleOrNull()?.let { ImageSettingsStore(ctx).setSkipBelowBytes(it.toLong()); Applied.Yes }
                    ?: Applied.No("숫자가 아닙니다")
            }),
        Binding(AppSettingsKeys.IMAGE_EDITOR_REMOVE_POLICY,
            read = { ImageSettingsStore(it).getEditorRemovePolicy().name },
            write = { ctx, v ->
                val policy = ImageSettingsStore.EditorRemovePolicy.entries
                    .firstOrNull { it.name.equals(v.trim(), ignoreCase = true) }
                if (policy != null) {
                    ImageSettingsStore(ctx).setEditorRemovePolicy(policy); Applied.Yes
                } else {
                    Applied.No("허용: ${ImageSettingsStore.EditorRemovePolicy.entries.joinToString { it.name }}")
                }
            }),
        Binding(AppSettingsKeys.IMAGE_AUTO_LINK_BY_CHARACTER,
            read = { bool(ImageSettingsStore(it).getAutoLinkByCharacter()) },
            write = { ctx, v -> ImageSettingsStore(ctx).setAutoLinkByCharacter(parseSheetBoolean(v)); Applied.Yes }),

        // ── AI 연동 ──
        // 프로바이더 목록은 코덱이 낸 JSON 그대로 싣는다. 앱이 쓰는 형식을 그대로 두는 것이
        // 요점이다 — 여기서 시트 전용 형식을 새로 만들면 코덱이 바뀔 때마다 두 벌이 갈린다.
        Binding(AppSettingsKeys.AI_PROVIDERS,
            read = { ctx -> AiProviderStore(ctx).list().takeIf { it.isNotEmpty() }?.let { com.novelcharacter.app.ai.AiProviderCodec.encode(it) } },
            write = { ctx, v ->
                val decoded = com.novelcharacter.app.ai.AiProviderCodec.decode(v)
                // **읽지 못한 것과 하나도 없는 것을 가른다** — 코덱이 이미 그 둘을 갈라 두었고
                // (`unreadable`), 합치면 형식 오류가 *"프로바이더가 없는 파일"*로 보여
                // 사용자가 고칠 자리를 못 찾는다.
                when {
                    decoded.unreadable -> Applied.No("프로바이더 목록의 형식이 올바르지 않습니다")
                    decoded.configs.isEmpty() -> Applied.No("담긴 프로바이더가 없습니다")
                    else -> {
                        val store = AiProviderStore(ctx)
                        for (config in decoded.configs) store.save(config)
                        if (decoded.skipped > 0) Applied.No("프로바이더 ${decoded.skipped}개를 읽지 못해 건너뛰고 나머지를 넣었습니다")
                        else Applied.Yes
                    }
                }
            }),
        Binding(AppSettingsKeys.AI_ACTIVE_PROVIDER,
            read = { AiProviderStore(it).activeId() },
            write = { ctx, v ->
                val id = v.trim()
                val store = AiProviderStore(ctx)
                if (id.isBlank()) Applied.No("값이 비어 있습니다")
                else if (store.get(id) == null) Applied.No("그 id의 프로바이더가 없습니다 — ${AppSettingsKeys.AI_PROVIDERS.key} 행이 먼저 들어와야 합니다")
                else { store.setActiveId(id); Applied.Yes }
            }),
        Binding(AppSettingsKeys.AI_USAGE_EXAMPLE_COUNT,
            read = { num(AiPromptSettings(it).usageExampleCount) },
            write = { ctx, v -> intOf(v)?.let { AiPromptSettings(ctx).usageExampleCount = it; Applied.Yes } ?: Applied.No("숫자가 아닙니다") }),
        Binding(AppSettingsKeys.AI_STYLE_SAMPLE_COUNT,
            read = { num(AiPromptSettings(it).styleSampleCount) },
            write = { ctx, v -> intOf(v)?.let { AiPromptSettings(ctx).styleSampleCount = it; Applied.Yes } ?: Applied.No("숫자가 아닙니다") }),
        // 최소 확신은 **'제한 없음'이 null**이라 빈 칸이 뜻을 갖는다 — 그래서 여기만
        // 빈 칸을 유실이 아니라 값으로 읽는다(`confidenceToWire`가 그 규약의 단일 소스다).
        Binding(AppSettingsKeys.AI_MIN_CONFIDENCE,
            read = { AiPromptPolicy.confidenceToWire(AiPromptSettings(it).minConfidence) },
            write = { ctx, v ->
                val wire = v.trim()
                val parsed = AiPromptPolicy.confidenceFromWire(wire)
                // **빈 칸과 모르는 이름을 갈라야 한다** — 둘 다 `null`로 파싱되는데 뜻이 정반대다:
                // 빈 칸은 사용자가 고른 '제한 없음'이고, 모르는 이름은 오타다. 합치면 오타가
                // 조용히 '제한 없음'이 되어 **거르라고 적어 둔 설정이 통째로 풀린다.**
                if (wire.isNotEmpty() && parsed == null) {
                    Applied.No("허용: ${CharacterFieldAiSuggester.Confidence.entries.joinToString { it.wire }} 또는 빈 칸(제한 없음)")
                } else {
                    AiPromptSettings(ctx).minConfidence = parsed; Applied.Yes
                }
            }),
        Binding(AppSettingsKeys.AI_CREATIVITY,
            read = { AiPromptSettings(it).creativity.wire },
            write = { ctx, v ->
                val parsed = AiCreativity.entries.firstOrNull { it.wire.equals(v.trim(), ignoreCase = true) }
                if (parsed == null) Applied.No("허용: ${AiCreativity.entries.joinToString { it.wire }}")
                else { AiPromptSettings(ctx).creativity = parsed; Applied.Yes }
            }),
        Binding(AppSettingsKeys.AI_ATTACH_IMAGE_COUNT,
            read = { num(AiPromptSettings(it).attachImageCount) },
            write = { ctx, v -> intOf(v)?.let { AiPromptSettings(ctx).attachImageCount = it; Applied.Yes } ?: Applied.No("숫자가 아닙니다") }),
        Binding(AppSettingsKeys.AI_ATTACH_REPRESENTATIVE_FIRST,
            read = { bool(AiPromptSettings(it).attachRepresentativeFirst) },
            write = { ctx, v -> AiPromptSettings(ctx).attachRepresentativeFirst = parseSheetBoolean(v); Applied.Yes }),
        Binding(AppSettingsKeys.AI_IMAGE_TAG_POLICY,
            read = { AiPromptSettings(it).imageTagPolicy },
            write = { ctx, v -> AiPromptSettings(ctx).imageTagPolicy = v; Applied.Yes }),
        Binding(AppSettingsKeys.AI_IMAGE_TAG_BATCH_SIZE,
            read = { num(AiPromptSettings(it).imageTagBatchSize) },
            write = { ctx, v -> intOf(v)?.let { AiPromptSettings(ctx).imageTagBatchSize = it; Applied.Yes } ?: Applied.No("숫자가 아닙니다") }),
        Binding(AppSettingsKeys.AI_NAME_SUGGEST_BATCH_SIZE,
            read = { num(AiPromptSettings(it).nameSuggestBatchSize) },
            write = { ctx, v -> intOf(v)?.let { AiPromptSettings(ctx).nameSuggestBatchSize = it; Applied.Yes } ?: Applied.No("숫자가 아닙니다") }),
        // 재료 범위는 **빈 칸이 '전부 끔'**이라 최소 확신과 같은 부류다 — 빈 칸을 유실로
        // 보면 사용자가 전부 꺼 둔 설정이 왕복 한 번에 기본값으로 되살아난다.
        // 모르는 이름은 조용히 버리지 않고 거절한다: 오타를 무시하면 **적어 넣은 재료가
        // 빠진 채로 "적용됨"이 뜬다.**
        Binding(AppSettingsKeys.AI_EVENT_CONTEXT_SCOPE,
            read = { EventAiMaterial.serialize(AiPromptSettings(it).eventContextScope) },
            write = { ctx, v ->
                // 쪼개기는 순수 계층이 든다 — 여기서 다시 적으면 이 칸만 따옴표·전각 쉼표를 모른다(R-47)
                val unknown = EventAiMaterial.unknownTokens(v)
                if (unknown.isNotEmpty()) {
                    Applied.No("모르는 재료: ${unknown.joinToString()} — 허용: ${EventAiMaterial.entries.joinToString { it.key }} 또는 빈 칸(전부 끔)")
                } else {
                    AiPromptSettings(ctx).eventContextScope = EventAiMaterial.parse(v); Applied.Yes
                }
            }),

        // 비밀 — 읽기는 **키가 하나라도 있을 때만** 값을 낸다(위 [Binding.read]의 null 규약).
        Binding(AppSettingsKeys.AI_API_KEYS,
            read = { ctx ->
                val keyStore = AiKeyStore(ctx)
                val obj = JSONObject()
                for (config in AiProviderStore(ctx).list()) {
                    keyStore.getKey(config.id)?.takeIf { it.isNotBlank() }?.let { obj.put(config.id, it) }
                }
                if (obj.length() == 0) null else obj.toString()
            },
            write = { ctx, v ->
                try {
                    val obj = JSONObject(v)
                    val keyStore = AiKeyStore(ctx)
                    var applied = 0
                    for (id in obj.keys()) {
                        val raw = obj.optString(id, "")
                        if (raw.isNotBlank()) { keyStore.putKey(id, raw); applied++ }
                    }
                    if (applied > 0) Applied.Yes else Applied.No("담긴 키가 없습니다")
                } catch (_: Exception) {
                    Applied.No("형식이 올바르지 않습니다(프로바이더id: 키 형태의 JSON)")
                }
            }),

        // ── 통계 기준 ──
        Binding(AppSettingsKeys.STATS_COMPLETION_REQUIRED_WEIGHT,
            read = { num(CompletionWeightPrefs.weights(it).requiredWeight) },
            write = { ctx, v ->
                v.trim().toFloatOrNull()?.let {
                    CompletionWeightPrefs.save(ctx, CompletionWeights.clamp(it)); Applied.Yes
                } ?: Applied.No("숫자가 아닙니다")
            }),
        // 유형 목록은 쉼표로 적는다 — 사람이 엑셀에서 고치는 자리라 집합을 JSON으로 두면
        // 손으로 못 만진다(원칙 04). 모르는 이름은 그 저장소가 조용히 버리므로 여기서 먼저 세어 알린다.
        Binding(AppSettingsKeys.STATS_PATTERN_TYPES,
            read = { joinCsv(PatternTypePrefs.enabled(it)) { t -> t.name } },
            write = { ctx, v ->
                val names = splitCsv(v)
                val known = names.mapNotNull { name -> PatternType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                val unknown = names.size - known.size
                PatternTypePrefs.save(ctx, known.toSet())
                if (unknown > 0) Applied.No("알 수 없는 유형 ${unknown}개를 빼고 나머지를 적용했습니다")
                else Applied.Yes
            }),

        // ── 보충 기준 ──
        supplementFlag(AppSettingsKeys.SUPPLEMENT_CHECK_IMAGES, { it.checkImages }, { c, b -> c.copy(checkImages = b) }),
        supplementFlag(AppSettingsKeys.SUPPLEMENT_CHECK_MEMO, { it.checkMemo }, { c, b -> c.copy(checkMemo = b) }),
        supplementFlag(AppSettingsKeys.SUPPLEMENT_CHECK_ALIASES, { it.checkAliases }, { c, b -> c.copy(checkAliases = b) }),
        supplementFlag(AppSettingsKeys.SUPPLEMENT_CHECK_NOVEL, { it.checkNovel }, { c, b -> c.copy(checkNovel = b) }),
        supplementFlag(AppSettingsKeys.SUPPLEMENT_CHECK_TAGS, { it.checkTags }, { c, b -> c.copy(checkTags = b) }),
        supplementFlag(AppSettingsKeys.SUPPLEMENT_CHECK_CUSTOM_FIELDS, { it.checkCustomFields }, { c, b -> c.copy(checkCustomFields = b) }),
        supplementFlag(AppSettingsKeys.SUPPLEMENT_CHECK_RELATIONSHIPS, { it.checkRelationships }, { c, b -> c.copy(checkRelationships = b) }),
        supplementFlag(AppSettingsKeys.SUPPLEMENT_CHECK_EVENTS, { it.checkEvents }, { c, b -> c.copy(checkEvents = b) }),
        supplementFlag(AppSettingsKeys.SUPPLEMENT_CHECK_FACTIONS, { it.checkFactions }, { c, b -> c.copy(checkFactions = b) }),
        Binding(AppSettingsKeys.SUPPLEMENT_FIELD_THRESHOLD,
            read = { num(SupplementCriteria.load(it).fieldCompletionThreshold) },
            write = { ctx, v ->
                intOf(v)?.let {
                    SupplementCriteria.save(ctx, SupplementCriteria.load(ctx).copy(fieldCompletionThreshold = it.coerceIn(0, 100)))
                    Applied.Yes
                } ?: Applied.No("숫자가 아닙니다")
            }),
        Binding(AppSettingsKeys.SUPPLEMENT_AUTO_SAVE_ON_EXIT,
            read = { bool(RandomSupplementSettings.load(it).autoSaveOnExit) },
            write = { ctx, v ->
                RandomSupplementSettings.save(ctx, RandomSupplementSettings(autoSaveOnExit = parseSheetBoolean(v)))
                Applied.Yes
            }),

        // ── 어시스턴트 ──
        Binding(AppSettingsKeys.ASSISTANT_CATEGORIES,
            read = { joinCsv(AssistantPrefs(it).enabledCategories()) { c -> c.name } },
            write = { ctx, v ->
                val names = splitCsv(v)
                val known = names.mapNotNull { name -> InsightCategory.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                val prefs = AssistantPrefs(ctx)
                // 적힌 것만 켜고 나머지는 끈다 — '전부 기본 켜짐'이라 빼기만으로는 끌 수 없다.
                for (category in InsightCategory.entries) prefs.setCategoryEnabled(category, category in known)
                val unknown = names.size - known.size
                if (unknown > 0) Applied.No("알 수 없는 항목 ${unknown}개를 빼고 나머지를 적용했습니다")
                else Applied.Yes
            })
    )

    /**
     * 보충 기준의 불리언 아홉은 모양이 같다 — **한 벌로 짓는다.**
     * 손으로 아홉 번 적으면 그중 하나가 다른 칸을 읽거나 쓰는 오타가 **컴파일도 시험도
     * 통과한 채** 남는다(복사·붙여넣기의 고전적 실패이고, 증상은 *"그 항목만 안 켜진다"*다).
     */
    private fun supplementFlag(
        spec: AppSettingsKeys.Spec,
        get: (SupplementCriteria) -> Boolean,
        set: (SupplementCriteria, Boolean) -> SupplementCriteria
    ) = Binding(spec,
        read = { bool(get(SupplementCriteria.load(it))) },
        write = { ctx, v ->
            SupplementCriteria.save(ctx, set(SupplementCriteria.load(ctx), parseSheetBoolean(v)))
            Applied.Yes
        })

    private val BY_KEY: Map<String, Binding> = BINDINGS.associateBy { it.spec.key }

    /** 시트 행 순서 = [AppSettingsKeys.SPECS] 순서. 선언에 없는 바인딩은 여기서 빠진다. */
    fun exported(includeSecrets: Boolean): List<Binding> =
        AppSettingsKeys.exported(includeSecrets).mapNotNull { BY_KEY[it.key] }

    /** 옛 이름도 [AppSettingsKeys.ALIASES]를 지나 같은 바인딩에 닿는다. */
    fun bindingOf(key: String): Binding? = AppSettingsKeys.specOf(key)?.let { BY_KEY[it.key] }

    /** 선언은 있는데 바인딩이 없는 키 — 검사 스크립트가 없을 때의 마지막 그물이다. */
    val UNBOUND_KEYS: List<String> = AppSettingsKeys.SPECS.map { it.key }.filterNot { it in BY_KEY }

    /**
     * **동의를 물을 것이 실제로 있는가** — 내보내기 창이 이것으로 묻고 말고를 가른다.
     *
     * *무엇이 비밀인가*를 아는 자리는 카탈로그이지 다이얼로그가 아니다. 창이 `AiKeyStore`를
     * 직접 뒤지면 비밀이 늘 때 **묻는 쪽과 싣는 쪽이 갈리고**, 그러면 동의 없이 새 비밀이
     * 실리거나(더 나쁜 쪽) 동의를 받고도 안 실린다.
     *
     * 읽기가 `null`을 내면 그 비밀은 담긴 값이 없다는 뜻이다([Binding.read]의 규약).
     */
    suspend fun hasStoredSecrets(context: Context): Boolean = withContext(Dispatchers.IO) {
        // **IO로 옮기는 것이 이 함수의 조건이다** — 부르는 곳이 내보내기 창(주 스레드)이고,
        // 비밀을 읽는 일은 저장소 읽기 + 복호화다(`AiKeyStore`는 AES/GCM을 지난다).
        // 나머지 read·write는 이미 배경에서 도는 내보내기·가져오기가 부르므로 여기만 감싼다.
        BINDINGS.any {
            it.spec.disposition == AppSettingsKeys.Disposition.SECRET &&
                runCatching { it.read(context) }.getOrNull() != null
        }
    }
}
