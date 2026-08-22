package com.novelcharacter.app.excel

import android.content.Context
import com.novelcharacter.app.ai.AiCreativity
import com.novelcharacter.app.ai.AiKeyStore
import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.ai.AiPromptSettings
import com.novelcharacter.app.ai.AiProviderStore
import com.novelcharacter.app.ai.PromptTemplateValidator
import com.novelcharacter.app.ai.PromptTemplates
import com.novelcharacter.app.ai.CharacterFieldAiSuggester
import com.novelcharacter.app.ai.EventAiMaterial
import com.novelcharacter.app.backup.BackupSettingsStore
import com.novelcharacter.app.data.settings.TrashSettingsStore
import com.novelcharacter.app.data.settings.FieldImportSettingsStore
import com.novelcharacter.app.ui.assistant.AssistantPrefs
import com.novelcharacter.app.ui.assistant.InsightCategory
import com.novelcharacter.app.ui.stats.CompletionWeightPrefs
import com.novelcharacter.app.ui.stats.PatternThresholds
import com.novelcharacter.app.ui.stats.PatternType
import com.novelcharacter.app.ui.stats.PatternTypePrefs
import com.novelcharacter.app.ui.supplement.RandomSupplementSettings
import com.novelcharacter.app.ui.supplement.SupplementCriteria
import com.novelcharacter.app.util.BirthdayCelebrationPrefs
import com.novelcharacter.app.util.CompletionWeights
import com.novelcharacter.app.util.CsvTokens
import com.novelcharacter.app.util.FieldNoteDisplayPrefs
import com.novelcharacter.app.util.ImageSettingsStore
import com.novelcharacter.app.util.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.novelcharacter.app.util.stringOr

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
 * 그러나 **적힌 그대로 들어가지 않은 것은 반드시 말한다** — 결과가 셋으로 갈리는 이유다:
 *
 * - [Applied.Yes] — 적힌 값이 그대로 들어갔다.
 * - [Applied.Adjusted] — **값은 들어갔지만 적힌 그대로가 아니다**(범위 밖이 접혔거나,
 *   목록의 일부만 해석됐거나). 종전에는 접힘이 [Applied.Yes]로 계수되어 *"앱 설정 N건 복원"*
 *   뒤에 숨었다 — `image_quality_percent`에 500을 적으면 100으로 접히는데 아무 말이 없었다.
 *   숫자 바인딩은 [numberBinding]이 **쓰고 나서 다시 읽어** 이것을 잡는다(범위 지식을 여기
 *   두 벌로 적지 않기 위해서다 — 범위의 단일 소스는 각 저장소다).
 * - [Applied.No] — **아무것도 쓰지 않았다.** 뜻을 알 수 없는 값(모르는 열거 이름 등)은 기존
 *   설정을 유지하고 사유를 돌려준다.
 *
 * 가져오기는 [Applied.Adjusted]·[Applied.No]의 사유를 경고로 싣는다. 조용히 무시하면
 * 사용자는 자기가 적은 값이 왜 안 먹었는지 알 길이 없다(개발 의도 2번 '변수 제어').
 */
object AppSettingsBindings {

    /** 쓰기의 결과. [Adjusted.reason]·[No.reason]은 그대로 사용자에게 나가는 문장이다. */
    sealed class Applied {
        /** 적힌 값이 그대로 들어갔다. */
        data object Yes : Applied()

        /** 값이 들어가긴 했지만 **적힌 그대로가 아니다** — 접힘·부분 적용. */
        data class Adjusted(val reason: String) : Applied()

        /** 아무것도 쓰지 않았다 — 기존 설정 유지. */
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

    private val BINDINGS: List<Binding> = listOf(
        // 해석 불가한 값(빈칸·글자)을 0(시스템 기본)으로 조용히 적용하면, 사용자가 적은
        // 것과 다른 값이 들어갔는데 '적용됨'으로 계수된다 — 헬퍼가 거절-유지로 막는다.
        // 0~2 클램프는 저장소에 없어 이 자리가 든다(이름 붙은 상수가 없다 — 선언 쪽 주석).
        numberBinding(AppSettingsKeys.THEME_MODE,
            read = { ThemeHelper.getSavedTheme(it) },
            write = { ctx, d -> ThemeHelper.saveTheme(ctx, d.toInt().coerceIn(0, 2)) },
            rejectHint = " (0=시스템, 1=라이트, 2=다크)"),
        // 읽기 화면의 필드 설명 ⓘ (B-44) — 불리언 셀의 허용 어휘는 boolBinding이 든다.
        boolBinding(AppSettingsKeys.READ_FIELD_NOTE_ENABLED,
            read = { FieldNoteDisplayPrefs.isReadScreenNoteEnabled(it) },
            write = { ctx, b -> FieldNoteDisplayPrefs.setReadScreenNoteEnabled(ctx, b) }),
        // 생일 축하 창 (사용자 요청 2026.08.20) — 같은 저장소의 '마지막으로 띄운 날'은
        // 여기 없다. 기기에서 일어난 일이라 싣지 않는다(선언 쪽 EXCLUDED_STORES에 사유).
        boolBinding(AppSettingsKeys.BIRTHDAY_CELEBRATION_ENABLED,
            read = { BirthdayCelebrationPrefs.isEnabled(it) },
            write = { ctx, b -> BirthdayCelebrationPrefs.setEnabled(ctx, b) }),

        // ── 백업 ──
        boolBinding(AppSettingsKeys.BACKUP_INCLUDE_IMAGES,
            read = { BackupSettingsStore(it).getSettings().includeImages },
            write = { ctx, b -> BackupSettingsStore(ctx).setIncludeImages(b) }),
        numberBinding(AppSettingsKeys.BACKUP_MAX_BACKUPS,
            read = { BackupSettingsStore(it).getSettings().maxBackups },
            write = { ctx, d -> BackupSettingsStore(ctx).setMaxBackups(d.toInt()) }),

        // ── 휴지통 보관 정책 (B-74) ──
        // 범위를 벗어난 값은 거절하지 않고 **좁혀서 받는다**(개발 의도 4번 — 밖에서 편집된
        // 파일을 유연하게 수용). 좁히는 규칙은 TrashRetentionPolicy.sanitize 한 벌이다.
        numberBinding(AppSettingsKeys.TRASH_MAX_OPERATIONS,
            read = { TrashSettingsStore(it).getSettings().maxOperations },
            write = { ctx, d -> TrashSettingsStore(ctx).setMaxOperations(d.toInt()) }),
        numberBinding(AppSettingsKeys.TRASH_RETENTION_DAYS,
            read = { TrashSettingsStore(it).getSettings().retentionDays },
            write = { ctx, d -> TrashSettingsStore(ctx).setRetentionDays(d.toInt()) }),

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
        boolBinding(AppSettingsKeys.IMAGE_COMPRESS_ENABLED,
            read = { ImageSettingsStore(it).getSettings().enabled },
            write = { ctx, b -> ImageSettingsStore(ctx).setEnabled(b) }),
        numberBinding(AppSettingsKeys.IMAGE_QUALITY_PERCENT,
            read = { ImageSettingsStore(it).getSettings().qualityPercent },
            write = { ctx, d -> ImageSettingsStore(ctx).setQualityPercent(d.toInt()) }),
        boolBinding(AppSettingsKeys.IMAGE_CAP_DIMENSION,
            read = { ImageSettingsStore(it).getSettings().capDimension },
            write = { ctx, b -> ImageSettingsStore(ctx).setCapDimension(b) }),
        numberBinding(AppSettingsKeys.IMAGE_MAX_LONG_EDGE_PX,
            read = { ImageSettingsStore(it).getSettings().maxLongEdgePx },
            write = { ctx, d -> ImageSettingsStore(ctx).setMaxLongEdgePx(d.toInt()) }),
        boolBinding(AppSettingsKeys.IMAGE_SKIP_BELOW_ENABLED,
            read = { ImageSettingsStore(it).getSettings().skipBelowEnabled },
            write = { ctx, b -> ImageSettingsStore(ctx).setSkipBelowEnabled(b) }),
        numberBinding(AppSettingsKeys.IMAGE_SKIP_BELOW_BYTES,
            read = { ImageSettingsStore(it).getSettings().skipBelowBytes },
            write = { ctx, d -> ImageSettingsStore(ctx).setSkipBelowBytes(d.toLong()) }),
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
        boolBinding(AppSettingsKeys.IMAGE_AUTO_LINK_BY_CHARACTER,
            read = { ImageSettingsStore(it).getAutoLinkByCharacter() },
            write = { ctx, b -> ImageSettingsStore(ctx).setAutoLinkByCharacter(b) }),

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
                        // 일부는 실제로 들어갔다 — '아무것도 안 썼다'(No)로 세면 계수가 거짓이 된다.
                        if (decoded.skipped > 0) Applied.Adjusted("프로바이더 ${decoded.skipped}개를 읽지 못해 건너뛰고 나머지를 넣었습니다")
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
        numberBinding(AppSettingsKeys.AI_USAGE_EXAMPLE_COUNT,
            read = { AiPromptSettings(it).usageExampleCount },
            write = { ctx, d -> AiPromptSettings(ctx).usageExampleCount = d.toInt() }),
        numberBinding(AppSettingsKeys.AI_STYLE_SAMPLE_COUNT,
            read = { AiPromptSettings(it).styleSampleCount },
            write = { ctx, d -> AiPromptSettings(ctx).styleSampleCount = d.toInt() }),
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
        numberBinding(AppSettingsKeys.AI_ATTACH_IMAGE_COUNT,
            read = { AiPromptSettings(it).attachImageCount },
            write = { ctx, d -> AiPromptSettings(ctx).attachImageCount = d.toInt() }),
        boolBinding(AppSettingsKeys.AI_ATTACH_REPRESENTATIVE_FIRST,
            read = { AiPromptSettings(it).attachRepresentativeFirst },
            write = { ctx, b -> AiPromptSettings(ctx).attachRepresentativeFirst = b }),
        Binding(AppSettingsKeys.AI_IMAGE_TAG_POLICY,
            read = { AiPromptSettings(it).imageTagPolicy },
            write = { ctx, v ->
                val settings = AiPromptSettings(ctx)
                settings.imageTagPolicy = v
                // 저장소가 상한으로 **조용히 자른다**(AiPromptPolicy.clampImageTagPolicy) —
                // 숫자 바인딩과 같은 read-back 대조로 잘림을 알린다. 거절로 바꾸지 않는 것은
                // 미리보기가 TEXT를 글자로 견줘 '갱신'이라 예고하기 때문이다(R-33 — 거절하면
                // 예고가 거짓이 된다). 공백만 다듬어진 것은 조정이 아니다.
                val stored = settings.imageTagPolicy
                if (stored == v.trim()) Applied.Yes
                else Applied.Adjusted(
                    "길이 상한 ${AiPromptPolicy.IMAGE_TAG_POLICY_MAX_CHARS}자를 넘어 " +
                        "앞 ${stored.length}자까지만 저장했습니다"
                )
            }),
        numberBinding(AppSettingsKeys.AI_IMAGE_TAG_BATCH_SIZE,
            read = { AiPromptSettings(it).imageTagBatchSize },
            write = { ctx, d -> AiPromptSettings(ctx).imageTagBatchSize = d.toInt() }),
        boolBinding(AppSettingsKeys.AI_IMAGE_TAG_GROUP_UNIT,
            read = { AiPromptSettings(it).imageTagGroupUnit },
            write = { ctx, b -> AiPromptSettings(ctx).imageTagGroupUnit = b }),
        numberBinding(AppSettingsKeys.AI_IMAGE_TAG_GROUP_SAMPLE_SIZE,
            read = { AiPromptSettings(it).imageTagGroupSampleSize },
            write = { ctx, d -> AiPromptSettings(ctx).imageTagGroupSampleSize = d.toInt() }),
        numberBinding(AppSettingsKeys.AI_NAME_SUGGEST_BATCH_SIZE,
            read = { AiPromptSettings(it).nameSuggestBatchSize },
            write = { ctx, d -> AiPromptSettings(ctx).nameSuggestBatchSize = d.toInt() }),
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
                    var failed = 0
                    for (id in obj.keys()) {
                        val raw = obj.stringOr(id, "")
                        if (raw.isBlank()) continue
                        // **저장에 실패한 것을 성공으로 세지 않는다** — 기기의 보안 저장소를
                        // 쓸 수 없는 상태에서 종전에는 예외가 통째로 위 `catch`에 잡혀
                        // *"형식이 올바르지 않습니다"*라는 **틀린 사유**로 보고됐다.
                        if (keyStore.putKey(id, raw)) applied++ else failed++
                    }
                    when {
                        failed > 0 && applied == 0 ->
                            Applied.No("기기의 보안 저장소를 쓸 수 없어 키를 저장하지 못했습니다")
                        failed > 0 ->
                            Applied.No("키 ${applied}개만 저장했습니다 — ${failed}개는 기기의 보안 저장소를 쓸 수 없어 실패했습니다")
                        applied > 0 -> Applied.Yes
                        else -> Applied.No("담긴 키가 없습니다")
                    }
                } catch (_: Exception) {
                    Applied.No("형식이 올바르지 않습니다(프로바이더id: 키 형태의 JSON)")
                }
            }),

        // ── 통계 기준 ──
        numberBinding(AppSettingsKeys.STATS_COMPLETION_REQUIRED_WEIGHT,
            read = { CompletionWeightPrefs.weights(it).requiredWeight },
            write = { ctx, d -> CompletionWeightPrefs.save(ctx, CompletionWeights.clamp(d.toFloat())) }),
        // 유형 목록은 쉼표로 적는다 — 사람이 엑셀에서 고치는 자리라 집합을 JSON으로 두면
        // 손으로 못 만진다(원칙 04). 모르는 이름은 그 저장소가 조용히 버리므로 여기서 먼저 세어 알린다.
        //
        // **전량 미지값은 적용하지 않는다**(B-226). 적힌 것이 하나도 해석되지 않았는데 그대로
        // 쓰면 켜져 있던 유형이 **전부 꺼진다** — 그리고 *"빼고 나머지를 적용했습니다"*라고
        // 말하는데 그 '나머지'가 없다(거짓 고지). 상위 버전 파일을 하위 버전 앱에 들일 때
        // 실제로 걸리는 갈래이고, 그때 사용자가 잃는 것은 **이 파일의 값이 아니라 지금 기기의
        // 설정**이다. 같은 파일의 `ai_event_context_scope`가 이미 거절-유지로 서 있다.
        Binding(AppSettingsKeys.STATS_PATTERN_TYPES,
            read = { joinCsv(PatternTypePrefs.enabled(it)) { t -> t.name } },
            write = { ctx, v ->
                val names = splitCsv(v)
                val known = names.mapNotNull { name -> PatternType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                val unknown = names.size - known.size
                // 빈 칸은 그대로 '전부 끔'이다 — 사용자가 전부 꺼 둔 설정이 왕복 한 번에
                // 되살아나면 안 된다(형제 항목과 같은 규약). 막는 것은 **적었는데 하나도
                // 해석되지 않은** 경우뿐이다.
                if (names.isNotEmpty() && known.isEmpty()) {
                    Applied.No("적힌 유형 ${names.size}개를 하나도 알 수 없어 그대로 두었습니다 — 허용: ${PatternType.entries.joinToString { it.name }} 또는 빈 칸(전부 끔)")
                } else {
                    PatternTypePrefs.save(ctx, known.toSet())
                    // 저장은 됐다 — 적힌 그대로가 아닐 뿐이다(No가 아니라 Adjusted).
                    if (unknown > 0) Applied.Adjusted("알 수 없는 유형 ${unknown}개를 빼고 나머지를 적용했습니다")
                    else Applied.Yes
                }
            }),
        // 민감도도 같은 이유로 `키=값` 쉼표 목록이다. 형식·범위·교정은 전부
        // `PatternThresholds`가 들고, 여기서는 **무엇이 그대로 되지 않았는지 말하는 일**만 한다 —
        // 모르는 키·못 읽는 값·범위 밖을 갈라 세는 것이 그 자리다(말없이 버리지 않는다).
        Binding(AppSettingsKeys.STATS_PATTERN_SENSITIVITY,
            read = { PatternTypePrefs.thresholds(it).encode() },
            write = { ctx, v ->
                // **빈 칸은 저장하지 않는다.** 비어 있는 것은 *전부 기본값으로 되돌리라*가 아니라
                // *이 칸에 아무것도 없다*이고, 그대로 쓰면 여섯 손잡이가 말없이 초기화된다
                // (같은 시트의 다른 숫자 항목도 빈 값을 거절한다).
                if (v.isBlank()) Applied.No("값이 비어 있습니다")
                else {
                    // **지금 저장된 값에서 시작한다** — 내보내기는 여섯을 다 적으므로 일부만 적힌
                    // 파일은 사람이 손으로 만든 것이고, 한 줄을 고친 뜻은 나머지를 지우라가 아니다.
                    val decoded = PatternThresholds.decode(v, PatternTypePrefs.thresholds(ctx))
                    PatternTypePrefs.saveThresholds(ctx, decoded.thresholds)
                    val notes = buildList {
                        if (decoded.unknownKeys.isNotEmpty())
                            add("알 수 없는 항목 ${decoded.unknownKeys.size}개")
                        if (decoded.invalidKeys.isNotEmpty())
                            add("숫자로 읽을 수 없는 항목 ${decoded.invalidKeys.size}개")
                        if (decoded.coerced) add("허용 범위를 벗어난 값")
                    }
                    if (notes.isEmpty()) Applied.Yes
                    // 셋은 처분이 다르다 — 못 읽은 것은 종전 값으로 남고, 범위 밖은 접혀서 들어간다.
                    // 한 문장으로 뭉뚱그리면 사용자가 무엇이 어떻게 됐는지 알 수 없다.
                    // 나머지는 실제로 저장됐으므로 No(아무것도 안 씀)가 아니라 Adjusted다.
                    else Applied.Adjusted("${notes.joinToString(", ")} — 그 자리는 종전 값이나 허용 범위로 두고 나머지를 적용했습니다")
                }
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
        numberBinding(AppSettingsKeys.SUPPLEMENT_FIELD_THRESHOLD,
            read = { SupplementCriteria.load(it).fieldCompletionThreshold },
            write = { ctx, d ->
                SupplementCriteria.save(
                    ctx,
                    SupplementCriteria.load(ctx).copy(fieldCompletionThreshold = d.toInt().coerceIn(0, 100))
                )
            }),
        boolBinding(AppSettingsKeys.SUPPLEMENT_AUTO_SAVE_ON_EXIT,
            read = { RandomSupplementSettings.load(it).autoSaveOnExit },
            write = { ctx, b -> RandomSupplementSettings.save(ctx, RandomSupplementSettings(autoSaveOnExit = b)) }),

        // ── 어시스턴트 ──
        // **전량 미지값은 적용하지 않는다**(B-226 — 위 `stats_pattern_types`와 같은 근거).
        // 이쪽은 기본값이 '전부 켜짐'이라 잃는 폭이 더 크다: 하나도 해석되지 않은 목록을
        // 그대로 쓰면 어시스턴트의 모든 범주가 꺼진다.
        Binding(AppSettingsKeys.ASSISTANT_CATEGORIES,
            read = { joinCsv(AssistantPrefs(it).enabledCategories()) { c -> c.name } },
            write = { ctx, v ->
                val names = splitCsv(v)
                val known = names.mapNotNull { name -> InsightCategory.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                if (names.isNotEmpty() && known.isEmpty()) {
                    Applied.No("적힌 항목 ${names.size}개를 하나도 알 수 없어 그대로 두었습니다 — 허용: ${InsightCategory.entries.joinToString { it.name }} 또는 빈 칸(전부 끔)")
                } else {
                    val prefs = AssistantPrefs(ctx)
                    // 적힌 것만 켜고 나머지는 끈다 — '전부 기본 켜짐'이라 빼기만으로는 끌 수 없다.
                    for (category in InsightCategory.entries) prefs.setCategoryEnabled(category, category in known)
                    val unknown = names.size - known.size
                    // 저장은 됐다 — 적힌 그대로가 아닐 뿐이다(No가 아니라 Adjusted).
                    if (unknown > 0) Applied.Adjusted("알 수 없는 항목 ${unknown}개를 빼고 나머지를 적용했습니다")
                    else Applied.Yes
                }
            })
    )

    /**
     * 숫자 설정 열다섯도 모양이 같다 — **한 벌로 짓는다**([supplementFlag]와 같은 근거).
     *
     * ## 쓰고 나서 다시 읽어 견준다
     *
     * 범위 밖 값은 각 저장소가 접어서 받는다(관대 임포트 — B-74 확정). **범위의 단일 소스는
     * 그 저장소들이고 여기 적으면 두 벌이 된다** — 그래서 접혔는지는 범위를 물어서가 아니라
     * **저장된 값을 다시 읽어** 안다. 적힌 값과 수로 다르면 [Applied.Adjusted]로 무엇이
     * 저장됐는지 알린다. 종전에는 접힘이 [Applied.Yes]로 계수되어 사용자가 적은 500이 100으로
     * 들어가고도 *"복원 N건"*만 남았다(변수 제어 위반 — 무슨 일이 있었는지 말하지 않았다).
     *
     * 견주는 술어는 복원 미리보기와 **같은 함수**다([AppSettingsKeys.sameNumericCell] — R-33).
     * 수 아닌 값은 [Applied.No]로 거절한다 — 미리보기의 '건너뜀' 예고([AppSettingsDiff])와
     * `tools/check_app_settings_catalog.sh` 축 ④가 이 거절 위에 선다.
     *
     * @param write 해석된 수를 저장소에 넣는다. 정수 저장소는 `d.toInt()`로 받는다 —
     *   소수점 아래가 버려지는 것도 read-back이 잡아 [Applied.Adjusted]로 알린다.
     * @param rejectHint 수가 아닐 때 사유에 덧붙일 안내(허용 값 열거 등). 문장 앞에 공백 포함.
     */
    private fun numberBinding(
        spec: AppSettingsKeys.Spec,
        read: suspend (Context) -> Number,
        write: suspend (Context, Double) -> Unit,
        rejectHint: String = ""
    ) = Binding(spec,
        read = { num(read(it)) },
        write = { ctx, v ->
            // NaN·Infinity도 거절이다 — 유한수 판정은 미리보기 게이트와 한 벌(parseFiniteCell).
            val parsed = AppSettingsKeys.parseFiniteCell(v)
            if (parsed == null) Applied.No("숫자가 아닙니다$rejectHint")
            else {
                write(ctx, parsed)
                val stored = num(read(ctx))
                if (AppSettingsKeys.sameNumericCell(v, stored)) Applied.Yes
                else Applied.Adjusted(
                    "적힌 값을 그대로 쓸 수 없어 ${stored}(으)로 조정해 저장했습니다 — " +
                        "허용 값은 '입력 가능한 값' 칸에 있습니다"
                )
            }
        })

    /**
     * 불리언 설정 열아홉도 모양이 같다 — **한 벌로 짓는다**([numberBinding]과 같은 근거).
     *
     * 켬·끔으로 읽히지 않는 글자는 [Applied.No]로 거절한다. 종전에는 전부 끔으로 접어서
     * **오타가 무음으로 '끔'이 되고 '복원'으로 계수됐다** — 시트가 기본값 표기로 쓰는 낱말
     * "켬"을 적어도 끔이 됐고, 안내의 *"뜻을 알 수 없는 값은 그 행만 건너뛰고 사유를 알려
     * 드립니다"*가 이 열아홉 행에서 거짓이었다. 빈 칸은 그대로 끔이다(기본 켬 설정을 끄는
     * 정당한 편집 — '입력 가능한 값'이 그렇게 적는다). 해석은 복원 미리보기와 같은 함수다
     * ([CsvTokens.parseBooleanOrNull] — R-33).
     *
     * 엔티티 시트의 불리언 열은 대상이 아니다 — 그쪽은 한 칸의 거절이 행 전체(같은 행의
     * 다른 편집까지)를 떨어뜨리므로 관대한 해석([parseSheetBoolean])이 처분이다.
     */
    private fun boolBinding(
        spec: AppSettingsKeys.Spec,
        read: suspend (Context) -> Boolean,
        write: suspend (Context, Boolean) -> Unit
    ) = Binding(spec,
        read = { bool(read(it)) },
        write = { ctx, v ->
            val parsed = CsvTokens.parseBooleanOrNull(v)
            if (parsed == null) {
                Applied.No(
                    "켬·끔으로 읽을 수 없습니다 — 허용: " +
                        CsvTokens.BOOLEAN_TRUE_TOKENS.joinToString("·") + "(켬) / " +
                        CsvTokens.BOOLEAN_FALSE_TOKENS.joinToString("·") + "(끔) / 빈 칸(끔)"
                )
            } else {
                write(ctx, parsed)
                Applied.Yes
            }
        })

    /**
     * 보충 기준의 불리언 아홉은 모양이 같다 — **한 벌로 짓는다.**
     * 손으로 아홉 번 적으면 그중 하나가 다른 칸을 읽거나 쓰는 오타가 **컴파일도 시험도
     * 통과한 채** 남는다(복사·붙여넣기의 고전적 실패이고, 증상은 *"그 항목만 안 켜진다"*다).
     */
    private fun supplementFlag(
        spec: AppSettingsKeys.Spec,
        get: (SupplementCriteria) -> Boolean,
        set: (SupplementCriteria, Boolean) -> SupplementCriteria
    ) = boolBinding(spec,
        read = { get(SupplementCriteria.load(it)) },
        write = { ctx, b -> SupplementCriteria.save(ctx, set(SupplementCriteria.load(ctx), b)) })

    /**
     * AI 메시지 양식 열넷 (사용자 요청 2026.08.20) — **한 벌로 짓는다.**
     *
     * 손으로 열넷을 적으면 그중 하나가 다른 양식을 읽거나 쓰는 오타가 **컴파일도 시험도
     * 통과한 채** 남는다(보충 기준 불리언 아홉이 같은 근거로 한 벌이다). 증상은
     * *"그 양식만 안 바뀐다"*이고, 사용자는 자기가 고친 글이 왜 안 나가는지 알 길이 없다.
     *
     * **읽기는 언제나 값을 돌려준다**(기본 양식이라도). 그래야 사용자가 엑셀에서 지금 나가는
     * 글을 보고 고칠 수 있다 — 요청의 절반이 *"엑셀에서도 편집"*이었고, 행이 없으면
     * 고칠 대상 자체가 안 보인다.
     *
     * **그 대가를 적어 둔다:** 앱의 기본 양식이 나중 판에서 나아졌을 때, 옛 판으로 뜬 파일을
     * 들이면 **그 옛 기본값이 사용자 양식으로 굳는다**(파일이 그 글을 값으로 말하고 있으므로
     * 쓰는 쪽은 그것을 지시로 읽는다). 되돌리는 길은 둘이고 둘 다 한 걸음이다 —
     * **칸을 비워 다시 들이거나**(빈 칸 = 기본으로 되돌리기, 시트 안내가 그렇게 적는다)
     * 인앱 편집 창의 '기본값으로'를 누르면 된다. 행을 안 싣는 대안은 *"엑셀에서 고친다"*를
     * 통째로 없애므로 이쪽을 골랐다.
     *
     * **쓰기는 자르지 않고 거절한다.** 잘린 양식은 필수 자리표가 잘려나간, 계약이 깨진 글이다.
     */
    private val PROMPT_TEMPLATE_BINDINGS: List<Binding> =
        PromptTemplates.Id.entries.map { id ->
            Binding(AppSettingsKeys.templateSpecOf(id),
                read = { AiPromptSettings(it).templateOf(id) },
                write = { ctx, v ->
                    // 셀 안의 줄바꿈은 프로그램마다 다른 글자로 온다. 그대로 두면 **아무것도
                    // 고치지 않은 왕복에서 매번 '복원됨'이 뜬다** — 왕복 규약이 금지하는 부류다.
                    val text = v.replace("\r\n", "\n").replace('\r', '\n')
                    val problems = AiPromptSettings(ctx).setTemplate(id, text)
                    if (problems.isEmpty()) Applied.Yes
                    else Applied.No(problems.joinToString(" / ") { templateProblemText(it) })
                })
        }

    /**
     * 거절 사유의 **엑셀판 문구**. 화면은 자기 문구를 따로 입힌다 — 순수 계층은
     * `Problem`만 내고 리소스를 모른다(`FormulaValidator`와 같은 관행).
     */
    private fun templateProblemText(problem: PromptTemplateValidator.Problem): String =
        when (problem) {
            is PromptTemplateValidator.Problem.UnknownTokens ->
                "쓸 수 없는 자리 " + problem.names.joinToString(", ") { PromptTemplates.wrap(it) } +
                    " — 쓸 수 있는 이름은 '입력 가능한 값' 칸에 있습니다"
            is PromptTemplateValidator.Problem.MissingRequired ->
                "반드시 들어가야 하는 자리가 빠졌습니다: " +
                    problem.names.joinToString(", ") { PromptTemplates.wrap(it) }
            is PromptTemplateValidator.Problem.Duplicated ->
                "한 번만 써야 하는 자리가 두 번 나옵니다: " +
                    problem.names.joinToString(", ") { PromptTemplates.wrap(it) }
            is PromptTemplateValidator.Problem.PaddedTokens ->
                "자리 이름 앞뒤의 공백까지 이름으로 읽습니다: " + problem.samples.joinToString(", ") +
                    " (공백을 지워 주세요)"
            is PromptTemplateValidator.Problem.MalformedTokens ->
                "자리 이름으로 읽을 수 없습니다: " + problem.samples.joinToString(", ") +
                    " (이름은 공백 없는 한글·영문·숫자입니다)"
            is PromptTemplateValidator.Problem.TooLong ->
                "${problem.limit}자를 넘습니다 (지금 ${problem.chars}자) — 줄여서 다시 가져와 주세요"
        }

    private val BY_KEY: Map<String, Binding> =
        (BINDINGS + PROMPT_TEMPLATE_BINDINGS).associateBy { it.spec.key }

    /** 시트 행 순서 = [AppSettingsKeys.SPECS] 순서. 선언에 없는 바인딩은 여기서 빠진다. */
    fun exported(includeSecrets: Boolean): List<Binding> =
        AppSettingsKeys.exported(includeSecrets).mapNotNull { BY_KEY[it.key] }

    /** 옛 이름도 [AppSettingsKeys.ALIASES]를 지나 같은 바인딩에 닿는다. */
    fun bindingOf(key: String): Binding? = AppSettingsKeys.specOf(key)?.let { BY_KEY[it.key] }

    // 「선언은 있는데 바인딩이 없는 키」를 보는 것은 **`tools/check_app_settings_catalog.sh`**다.
    //
    // 종전에는 같은 판정을 `UNBOUND_KEYS`라는 값으로도 들고 있었는데 **부르는 곳이 하나도
    // 없었다** — 앱 코드에도, 시험에도, 검사 스크립트에도. 그런데 그 KDoc은 자신을
    // *"검사 스크립트가 없을 때의 마지막 그물"*이라 불렀다. **그물이 아닌 것이 그물이라고
    // 적혀 있으면 다음 사람이 그것을 믿고 안심한다** — 이 저장소가 죽은 선언을 결함으로
    // 세어 온 근거가 바로 그것이다(*"없는 소비처를 가리키는 주석"*).
    //
    // 이 파일은 순수 JVM 하네스와 프로브의 범위 밖이라(`Context`에 묶여 있다) 시험으로
    // 되살릴 수도 없다. 그래서 값을 걷고 **실제로 도는 검사 하나만 남긴다**(2026.08.22).

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
        BY_KEY.values.any {
            it.spec.disposition == AppSettingsKeys.Disposition.SECRET &&
                runCatching { it.read(context) }.getOrNull() != null
        }
    }
}
