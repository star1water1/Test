package com.novelcharacter.app.excel

import com.novelcharacter.app.ai.AiPromptPolicy
import com.novelcharacter.app.util.CsvTokens

/**
 * '앱 설정' 시트가 **무엇을 싣는가**의 단일 소스 — 키·형식·처분의 순수 선언 (B-105).
 *
 * ## 왜 있는가 — 개수가 아니라 구조가 문제였다
 *
 * 종전 내보내기는 `writeTextRow`/`writeNumberRow`를 **손으로 나열**하고 가져오기는
 * `when (key)`를 **손수 분기**했다. 코드 주석이 스스로 그렇게 적어 두었다:
 * *"key/value 구조라 항목 추가는 가져오기(when 분기)와 짝으로 확장한다"* —
 * **설정 하나를 늘릴 때마다 두 곳을 손대야 하니 늘지 않는 것이 당연했다.**
 * 그래서 실린 것은 11개뿐이고 저장소는 스무 곳이 넘었다(원칙 01이 정면으로 겨누는 자리).
 *
 * ## 이 파일과 [AppSettingsBindings]가 갈린 이유
 *
 * **여기는 순수하고 저기는 `Context`를 쥔다.** 한 파일에 합치면 선언까지 `Context`에
 * 딸려 가 **순수 JVM 시험이 원리적으로 못 본다**(B-132가 난 자리이고, B-108이 판정만
 * 갈라 낸 것과 같은 근거다). 시트의 왕복 계약 — *어떤 키가 있고, 어떤 형식이며, 실리는가* —
 * 은 여기 있고 시험이 잠근다. 실제 읽기·쓰기만 저쪽이다.
 *
 * **두 벌이 갈리는 것은 기계가 본다** — `tools/check_app_settings_catalog.sh`(규약 R-45):
 * ① [SPECS]의 키가 전부 바인딩을 갖는가 ② 앱의 설정 저장소가 전부 이 카탈로그에
 * 등재되거나 [EXCLUDED_STORES]에 **사유와 함께** 빠져 있는가. 그 검사가 없으면
 * 이 분리가 곧 종전의 '두 곳'을 이름만 바꿔 되살린다.
 *
 * ## 처분 3분류 — 사용자 확정 3번(ㄱ1·ㄴ1)
 *
 * - [Disposition.PORTABLE] **실어야 하는 것** — 새 기기에서 다시 맞추기 싫은 것.
 * - [Disposition.SECRET] **평문으로 새면 안 되는 것** — API 키. 엑셀은 평문이고 사용자가
 *   남에게 보내기도 하는 파일이라 **기본 제외 + 별도 동의**다. 편의가 아니라 안전 문제다.
 * - **싣지 않는 것** — 기기 로컬 화면 흔적(마지막 스크롤 위치·마지막 선택 작품 등).
 *   왕복해 봐야 소음이다. 이쪽은 키가 아니라 저장소 단위라 [EXCLUDED_STORES]에 적는다.
 */
object AppSettingsKeys {

    /** 셀에 적히는 형식. [NUMBER]만 숫자 셀이고 나머지는 글자다(종전 왕복과 같은 모양). */
    enum class Kind { TEXT, NUMBER, BOOLEAN }

    /** 확정 3번의 3분류 중 **싣는** 둘. 싣지 않는 것은 [EXCLUDED_STORES]가 든다. */
    enum class Disposition { PORTABLE, SECRET }

    /**
     * 한 설정의 선언.
     *
     * @param key 시트의 `설정키` 셀 값. **바꾸면 옛 파일이 그 설정을 잃는다** — 이름을
     *   갈고 싶으면 옛 이름을 [ALIASES]에 남길 것.
     */
    data class Spec(
        val key: String,
        val kind: Kind,
        val disposition: Disposition = Disposition.PORTABLE,
        /** 시트의 `설명` 칸 — 이 설정이 무엇인가. 비어 있으면 안 된다(시험이 잠근다). */
        val note: String = "",
        /** 시트의 `입력 가능한 값` 칸 — 어떤 입력을 받는가. */
        val domain: Domain = Domain.AppOwned("")
    ) {
        /** 시트에 적히는 허용값 글. **손으로 적지 않는다** — 상수가 단일 소스다(R-14). */
        fun accepts(): String = domain.describe()
    }

    /**
     * **어떤 입력을 받는가** — 시트의 `입력 가능한 값` 칸을 만드는 순수 선언 (사용자 요청 2026.08.20).
     *
     * ## 왜 있는가
     *
     * *"엑셀에서 지금 설정을 편집할 수는 있는 거로 알고 있는데 각각의 칸이 뭘 의미하고 어떤
     * 입력을 받는지를 알 방법이 없거든?"*
     *
     * 실제로 그랬다 — 시트는 `설정키`·`설정값` 두 칸뿐이었고, 허용 범위는 전부 코드 안
     * (바인딩 람다와 각 저장소의 좁히기 함수)에만 있었다. 사용자가 범위를 알 수 있는 길은
     * **틀린 값을 넣고 가져오기를 돌려 경고를 읽는 것**뿐이었고, 범위 밖 숫자는 조용히
     * 좁혀져 그 경고조차 뜨지 않았다.
     *
     * ## 왜 문구가 아니라 타입인가
     *
     * R-14: *"상한 숫자는 상수가 단일 소스다. 안내 문구에 숫자를 박아 두면 상한을 옮길 때
     * 문구가 거짓이 된다."* 여기서 [describe]가 만드는 글은 전부 상수·열거에서 나온다.
     * 손으로 적은 글이었다면 `AiPromptPolicy`를 고치는 날 시트만 낡는다.
     */
    sealed interface Domain {

        /**
         * 모양만으로는 알 수 없는 것 — **좁혀서 받는가, 거절하는가, 빈 칸이 무슨 뜻인가.**
         *
         * 이것을 [Ints] 같은 타입 안에 규칙으로 넣지 않은 이유: 같은 `0~100 사이 숫자`라도
         * 어떤 설정은 범위 밖을 좁혀 받고 어떤 설정은 거절한다(그 판단은 바인딩마다 다르다).
         * 규칙으로 지어내면 **안내가 실동작과 어긋나고**, 그것이 이 저장소가 여러 번 겪은 사고다.
         * 그래서 이 칸은 사람이 코드를 읽고 적는다.
         */
        val extra: String

        /** 기본값 — 상수에서 받는다. 특히 **빈 칸이 곧 끄기**인 불리언에서 이것이 없으면 안 된다. */
        val defaultText: String

        /** 받는 값의 **모양** — 숫자 범위·열거 이름처럼 상수에서 그대로 나오는 부분. */
        fun shape(): String

        /** 시트에 적히는 한 칸. 모양 · 사람이 적은 단서 · 기본값의 차례다. */
        fun describe(): String = listOf(
            shape(),
            extra,
            if (defaultText.isBlank()) "" else "기본 $defaultText."
        ).filter { it.isNotBlank() }.joinToString(" ")

        /** 정수 범위. [max]가 null이면 위쪽이 열려 있다. */
        data class Ints(
            val min: Int,
            val max: Int? = null,
            val unit: String = "",
            override val defaultText: String = "",
            override val extra: String = ""
        ) : Domain {
            override fun shape(): String {
                val range = if (max == null) "$min 이상의 정수" else "$min~$max 사이 정수"
                return if (unit.isBlank()) "$range." else "$range($unit)."
            }
        }

        /** 소수를 받는 자리 — 완성도 가중처럼 배수로 쓰는 값. */
        data class Decimals(
            val min: String,
            val max: String,
            override val defaultText: String = "",
            override val extra: String = ""
        ) : Domain {
            override fun shape(): String = "$min~$max 사이 숫자(소수 가능)."
        }

        /** 숫자가 뜻을 갖는 자리 — `0=시스템, 1=라이트` 처럼 짝을 그대로 보인다. */
        data class Labeled(
            val pairs: List<Pair<Int, String>>,
            override val defaultText: String = "",
            override val extra: String = ""
        ) : Domain {
            override fun shape(): String =
                pairs.joinToString(", ") { it.first.toString() } + " 중 하나 — " +
                    pairs.joinToString(", ") { "${it.first}=${it.second}" } + "."
        }

        /** 정해진 이름 중 하나. */
        data class Choice(
            val values: List<String>,
            override val defaultText: String = "",
            override val extra: String = ""
        ) : Domain {
            override fun shape(): String = values.joinToString(", ") + " 중 하나."
        }

        /** 정해진 이름을 쉼표로 여럿 잇는 자리. */
        data class ChoiceList(
            val values: List<String>,
            override val defaultText: String = "",
            override val extra: String = ""
        ) : Domain {
            override fun shape(): String =
                values.joinToString(", ") + " 중에서 골라 쉼표로 잇습니다."
        }

        /**
         * 켬·끔.
         *
         * **빈 칸이 곧 끄기다** — 기본이 켬인 설정에서 이 사실을 말하지 않으면, 값을 지운
         * 사람은 자기가 그 기능을 껐다는 것을 모른다. 켬·끔으로 읽히지 않는 글자는 그 행을
         * 건너뛰고 사유를 알린다 — 종전에는 전부 끔으로 접어서, **앱이 기본값 표기로 쓰는
         * 낱말 "켬"을 적어도 끔이 됐다**(오타·모르는 글자가 무음으로 정반대 값이 되는 자리).
         *
         * 어휘 목록은 해석기와 같은 상수다([CsvTokens] — R-14. 손으로 적으면 어휘가 늘 때
         * 이 문구만 낡는다).
         */
        data class YesNo(
            val defaultOn: Boolean,
            override val extra: String = ""
        ) : Domain {
            override val defaultText: String get() = if (defaultOn) "켬" else "끔"
            override fun shape(): String =
                CsvTokens.BOOLEAN_TRUE_TOKENS.joinToString("·") + "을 켬으로, " +
                    CsvTokens.BOOLEAN_FALSE_TOKENS.joinToString("·") + "을 끔으로 읽습니다" +
                    "(대소문자·전각 무관). 빈 칸도 끔입니다. " +
                    "그 밖의 글자는 그 행을 건너뛰고 사유를 알려 드립니다."
        }

        /** 자유로 쓰는 글. */
        data class FreeText(
            val maxChars: Int,
            override val defaultText: String = "",
            override val extra: String = ""
        ) : Domain {
            override fun shape(): String = "자유롭게 쓰는 글, ${maxChars}자까지."
        }

        /** `키=값` 쌍을 쉼표로 잇는 자리. */
        data class Pairs(
            val ranges: List<Pair<String, String>>,
            override val defaultText: String = "",
            override val extra: String = ""
        ) : Domain {
            override fun shape(): String =
                "`키=값`을 쉼표로 잇습니다. 쓸 수 있는 키와 범위는 " +
                    ranges.joinToString(", ") { "${it.first} ${it.second}" } + "."
        }

        /** AI에 보내는 메시지 양식 — 자리표 목록까지 함께 적는다. */
        data class Template(
            val id: com.novelcharacter.app.ai.PromptTemplates.Id,
            override val extra: String = "",
            override val defaultText: String = ""
        ) : Domain {
            override fun shape(): String {
                val tokens = com.novelcharacter.app.ai.PromptTemplates.tokensOf(id)
                val must = tokens.filter { it.required }
                val may = tokens.filterNot { it.required }
                return buildString {
                    append("여러 줄 글, ")
                    append(com.novelcharacter.app.ai.AiPromptPolicy.PROMPT_TEMPLATE_MAX_CHARS)
                    append("자까지. 비우면 기본 양식으로 돌아갑니다.")
                    if (must.isNotEmpty()) {
                        // **'한 번만'은 그렇게 선언된 자리에만 붙인다** — 전부에 붙이면
                        // 기본 양식이 두 번 쓰는 자리(`{{장수}}`)에서 안내가 곧바로 거짓이 된다.
                        append("\n· 반드시 들어가야 하는 자리:\n")
                        append(must.joinToString("\n") {
                            "  " + com.novelcharacter.app.ai.PromptTemplates.wrap(it.name) +
                                (if (it.single) " (한 번만)" else "") + " — " + it.meaning
                        })
                    }
                    if (may.isNotEmpty()) {
                        append("\n· 그 밖에 쓸 수 있는 자리:\n")
                        append(may.joinToString("\n") {
                            "  " + com.novelcharacter.app.ai.PromptTemplates.wrap(it.name) +
                                " — " + it.meaning
                        })
                    }
                }
            }
        }

        /**
         * 앱이 채우는 값 — 사람이 손으로 지어낼 수 없는 꼴(JSON·식별자)이다.
         *
         * **회색 칸으로 두지 않는 이유:** 새 기기로 옮길 때 이 행이 실려야 설정이 따라간다.
         * 고치지 말라고 말하되 지우지는 않는다.
         */
        data class AppOwned(
            val where: String = "",
            override val extra: String = "",
            override val defaultText: String = ""
        ) : Domain {
            override fun shape(): String =
                "앱이 채웁니다. 직접 고치지 마세요" +
                    (if (where.isBlank()) "." else " — " + where + "에서 바꿉니다.")
        }
    }


    // ── 화면 ──
    val THEME_MODE = Spec("theme_mode", Kind.NUMBER,
        note = "앱 화면을 밝게 볼지 어둡게 볼지 정합니다.",
        // 0~2는 바인딩의 `coerceIn(0, 2)`가 단일 소스다(이름 붙은 상수가 없다).
        domain = Domain.Labeled(
            listOf(0 to "시스템", 1 to "라이트", 2 to "다크"), defaultText = "0(시스템)",
            extra = "범위 밖은 좁혀서 받고(음수는 0, 3 이상은 2), 소수는 소수점 아래를 버립니다. " +
                "숫자로 읽히지 않으면 그 행을 건너뛰고 사유를 알려 드립니다."
        ))

    /**
     * 읽기 화면에도 필드 설명 ⓘ를 보이는가 (B-44 · 확정 17번 · P-8 기본 꺼짐).
     *
     * **화면 흔적이 아니라 취향 설정이라 싣는다** — 새 기기에서 다시 찾아 켜야 하는 부류다.
     */
    val READ_FIELD_NOTE_ENABLED = Spec("read_field_note_enabled", Kind.BOOLEAN,
        note = "읽기 화면에서도 필드 설명 아이콘을 보입니다.",
        domain = Domain.YesNo(defaultOn = false))

    /**
     * 생일인 캐릭터가 있는 날 축하 창을 띄우는가 (사용자 요청 2026.08.20 · 기본 켬).
     *
     * **취향 설정이라 싣는다** — 끈 사람이 새 기기에서 다시 찾아 꺼야 하는 부류다.
     * *오늘 이미 띄웠는가*는 같은 저장소에 있지만 **싣지 않는다**(EXCLUDED_STORES의 사유 참조).
     */
    val BIRTHDAY_CELEBRATION_ENABLED = Spec("birthday_celebration_enabled", Kind.BOOLEAN,
        note = "생일인 캐릭터가 있는 날 축하 창을 띄웁니다.",
        domain = Domain.YesNo(defaultOn = true))

    // ── 백업 ──
    val BACKUP_INCLUDE_IMAGES = Spec("backup_include_images", Kind.BOOLEAN,
        note = "백업에 이미지까지 함께 담습니다.",
        // '지금 백업'도 같은 워커를 지난다 — 자동 백업 전용이라 적으면 거짓이 된다.
        domain = Domain.YesNo(defaultOn = false, extra = "'지금 백업'도 이 설정을 따릅니다."))
    val BACKUP_MAX_BACKUPS = Spec("backup_max_backups", Kind.NUMBER,
        note = "백업 파일을 몇 개까지 남겨 둘지 정합니다.",
        // 하한 1은 `BackupSettingsStore.setMaxBackups`의 coerceAtLeast(1)가 단일 소스다.
        domain = Domain.Ints(1, defaultText = "3",
            extra = "0 이하는 1로 좁혀 받고 위쪽 상한은 없습니다. 설정 화면의 선택지는 1·2·3·5·10이며 " +
                "'지금 백업'도 같은 개수에 함께 셉니다. 숫자로 읽히지 않으면 그 행을 건너뜁니다."))

    // ── 휴지통 보관 정책 (B-74) ──
    // 초과분의 결과가 **영구 삭제**라, 기기를 옮길 때 함께 가야 하는 설정이다.
    val TRASH_MAX_OPERATIONS = Spec("trash_max_operations", Kind.NUMBER,
        note = "휴지통에 삭제 작업을 몇 개까지 남길지 정합니다.",
        // 5~1000은 `TrashRetentionPolicy`의 상수가 단일 소스다(여기서는 값만 적는다 —
        // 그 파일은 저장소를 쥐고 있어 순수 시험 묶음에 올릴 수 없다).
        domain = Domain.Ints(5, 1000, defaultText = "30",
            extra = "범위 밖은 좁혀서 받고 소수는 소수점 아래를 버립니다. 설정 화면의 선택지는 " +
                "10·30·50·100·200입니다. 넘친 작업은 영영 지워지므로 줄일 때 주의해 주세요."))
    val TRASH_RETENTION_DAYS = Spec("trash_retention_days", Kind.NUMBER,
        note = "휴지통의 삭제 기록을 며칠 동안 남길지 정합니다.",
        domain = Domain.Ints(1, 3650, unit = "일", defaultText = "30",
            extra = "범위 밖은 좁혀서 받고 소수는 소수점 아래를 버립니다. 설정 화면의 선택지는 " +
                "7·30·90·180·365입니다."))

    // ── 필드 가져오기 (B-63 · 확정 14번) ──
    // 종류를 바꿔 심어도 되는 필드 타입 목록. 콤마로 잇는다 — 타입은 앞으로 늘 수 있어
    // 스위치를 타입마다 두면 옛 파일에 그 키가 없을 때 켬·끔을 가릴 수 없다.
    val FIELD_IMPORT_CONVERTIBLE_TYPES = Spec("field_import_convertible_types", Kind.TEXT,
        note = "다른 종류로 옮겨 심어도 되는 필드 타입입니다.",
        domain = Domain.ChoiceList(
            com.novelcharacter.app.data.model.FieldType.entries.map { it.name },
            defaultText = "TEXT, SELECT, NUMBER",
            extra = "소문자로 적어도 대문자로 바꿔 받습니다. 모르는 이름도 버리지 않고 그대로 담아 둡니다 — " +
                "새 타입이 생긴 기기의 파일을 옛 기기가 읽고 다시 내보낼 때 켜 둔 것이 사라지지 " +
                "않게 하기 위해서입니다. 비우면 종류를 바꿔 심는 길을 닫습니다."))

    // ── 이미지 저장 ──
    val IMAGE_COMPRESS_ENABLED = Spec("image_compress_enabled", Kind.BOOLEAN,
        note = "불러온 이미지를 자동으로 압축합니다.",
        domain = Domain.YesNo(defaultOn = false))
    val IMAGE_QUALITY_PERCENT = Spec("image_quality_percent", Kind.NUMBER,
        note = "압축할 때 쓰는 JPEG 품질입니다.",
        // 10~100·기본 85는 `ImageSettingsStore`의 MIN_QUALITY·MAX_QUALITY·DEFAULT_QUALITY다.
        domain = Domain.Ints(10, 100, unit = "%", defaultText = "85",
            extra = "범위 밖은 좁혀서 받고(0은 10, 500은 100) 소수는 소수점 아래를 버립니다. " +
                "숫자로 읽히지 않으면 그 행을 건너뜁니다."))
    val IMAGE_CAP_DIMENSION = Spec("image_cap_dimension", Kind.BOOLEAN,
        note = "압축할 때 긴 변 길이를 제한합니다.",
        domain = Domain.YesNo(defaultOn = true, extra = "꺼도 8192px 안전 상한은 남습니다."))
    val IMAGE_MAX_LONG_EDGE_PX = Spec("image_max_long_edge_px", Kind.NUMBER,
        note = "이미지 긴 변의 상한 길이입니다.",
        // 512~8192·기본 2048은 `ImageSettingsStore`의 MIN_LONG_EDGE·MAX_LONG_EDGE·DEFAULT다.
        domain = Domain.Ints(512, 8192, unit = "픽셀", defaultText = "2048",
            extra = "범위 밖은 좁혀서 받고 소수는 소수점 아래를 버립니다. 설정 화면은 " +
                "1024·2048·4096·8192만 고르게 하지만 이 칸은 그 사이 숫자도 받습니다."))
    val IMAGE_SKIP_BELOW_ENABLED = Spec("image_skip_below_enabled", Kind.BOOLEAN,
        note = "작은 파일은 압축하지 않고 원본을 둡니다.",
        domain = Domain.YesNo(defaultOn = false))
    val IMAGE_SKIP_BELOW_BYTES = Spec("image_skip_below_bytes", Kind.NUMBER,
        note = "이 크기보다 작은 파일은 압축하지 않습니다.",
        domain = Domain.Ints(0, unit = "바이트", defaultText = "524288(512KB)",
            extra = "음수는 0으로 좁혀 받고 위쪽 상한은 없습니다. 설정 화면의 선택지는 " +
                "262144(256KB)·524288(512KB)·1048576(1MB)·2097152(2MB)이며 이 칸은 그 밖의 숫자도 받습니다."))
    val IMAGE_EDITOR_REMOVE_POLICY = Spec("image_editor_remove_policy", Kind.TEXT,
        note = "편집창에서 이미지를 뺄 때 파일을 어떻게 할지 정합니다.",
        // 이름 둘은 `ImageSettingsStore.EditorRemovePolicy`가 단일 소스다.
        domain = Domain.Choice(listOf("LIBRARY_ONLY", "ALWAYS_ADOPT"), defaultText = "ALWAYS_ADOPT",
            extra = "대소문자는 가리지 않습니다. ALWAYS_ADOPT는 뗀 이미지를 지우지 않고 " +
                "라이브러리로 옮기고, LIBRARY_ONLY는 편집창에서 직접 고른 것만 지웁니다. " +
                "그 밖의 글자와 빈 칸은 그 행을 건너뛰고 사유를 알려 드립니다."))
    val IMAGE_AUTO_LINK_BY_CHARACTER = Spec("image_auto_link_by_character", Kind.BOOLEAN,
        note = "같은 캐릭터의 이미지를 자동으로 묶습니다.",
        domain = Domain.YesNo(defaultOn = true))

    // ── AI 연동 ──
    /**
     * 프로바이더 목록 — `AiProviderCodec`가 낸 JSON 그대로.
     *
     * **키는 여기 없다**(`AiProviderConfig`에 그 칸이 없고, 키는 [AI_API_KEYS]가 따로 든다).
     * 그래서 이 행은 평문 위험 없이 실린다 — 새 기기는 주소·모델·순서를 그대로 받고
     * 키만 다시 넣으면 된다.
     */
    val AI_PROVIDERS = Spec("ai_providers", Kind.TEXT,
        note = "등록해 둔 AI 프로바이더 목록입니다.",
        domain = Domain.AppOwned("설정의 AI 연동",
            extra = "항목마다 id·protocol·displayName·baseUrl·model이 있는 JSON 배열입니다. " +
                "배열로 읽히지 않으면 그 행을 건너뛰고, 항목 하나가 깨지면 그 항목만 건너뛴 뒤 " +
                "개수를 알려 드립니다. 파일에 없는 프로바이더는 지워지지 않습니다. " +
                "API 키는 이 칸에 들어가지 않습니다."))
    val AI_ACTIVE_PROVIDER = Spec("ai_active_provider", Kind.TEXT,
        note = "지금 AI 요청을 보낼 프로바이더입니다.",
        domain = Domain.AppOwned("설정의 AI 연동",
            extra = "`ai_providers`에 실려 있는 항목의 id 하나입니다. 목록에 없는 id는 그 행을 " +
                "건너뛰므로 `ai_providers` 행이 먼저 들어와야 합니다."))
    val AI_USAGE_EXAMPLE_COUNT = Spec("ai_usage_example_count", Kind.NUMBER,
        note = "짧은 값 추천에 함께 보낼 기존 값 예시 개수입니다.",
        domain = Domain.Ints(0, AiPromptPolicy.USAGE_EXAMPLES_MAX, unit = "개",
            defaultText = AiPromptPolicy.USAGE_EXAMPLES_DEFAULT.toString(),
            extra = "${AiPromptPolicy.USAGE_EXAMPLES_STEP} 단위로만 저장되어 그 사이 숫자는 " +
                "아래 눈금으로 내려 맞춥니다. 범위 밖은 좁혀서 받습니다. 0이면 예시를 보내지 않습니다."))
    val AI_STYLE_SAMPLE_COUNT = Spec("ai_style_sample_count", Kind.NUMBER,
        note = "서술형 작성에 문체 참고로 보낼 글 편수입니다.",
        domain = Domain.Ints(0, AiPromptPolicy.STYLE_SAMPLES_MAX, unit = "편",
            defaultText = AiPromptPolicy.STYLE_SAMPLES_DEFAULT.toString(),
            extra = "범위 밖은 좁혀서 받습니다. 0이면 문체 참고를 보내지 않습니다."))
    val AI_MIN_CONFIDENCE = Spec("ai_min_confidence", Kind.TEXT,
        note = "추천을 받아올 최소 근거 강도입니다.",
        domain = Domain.Choice(
            com.novelcharacter.app.ai.CharacterFieldAiSuggester.Confidence.entries.map { it.wire },
            defaultText = "빈 칸(제한 없음)",
            extra = "대소문자는 가리지 않습니다. **빈 칸은 오타가 아니라 값입니다** — 근거가 얕은 " +
                "추천까지 전부 받는다는 뜻입니다. 그 밖의 글자는 그 행을 건너뛰고 사유를 알려 드립니다."))
    val AI_CREATIVITY = Spec("ai_creativity", Kind.TEXT,
        note = "AI 추천의 폭을 정하는 창작도 단계입니다.",
        domain = Domain.Choice(
            com.novelcharacter.app.ai.AiCreativity.entries.map { it.wire },
            defaultText = com.novelcharacter.app.ai.AiCreativity.DEFAULT.wire,
            extra = "차례로 정확·균형·자유·실험입니다. 대소문자는 가리지 않습니다. " +
                "빈 칸을 포함해 그 밖의 값은 그 행을 건너뛰고 사유를 알려 드립니다."))
    val AI_ATTACH_IMAGE_COUNT = Spec("ai_attach_image_count", Kind.NUMBER,
        note = "AI 요청에 함께 보낼 캐릭터 이미지 장수입니다.",
        domain = Domain.Ints(0, AiPromptPolicy.ATTACH_IMAGES_MAX, unit = "장",
            defaultText = AiPromptPolicy.ATTACH_IMAGES_DEFAULT.toString(),
            extra = "범위 밖은 좁혀서 받습니다. 0이면 이미지를 보내지 않습니다. " +
                "장수가 곧 요청 비용이니 올릴 때 주의해 주세요."))
    val AI_ATTACH_REPRESENTATIVE_FIRST = Spec("ai_attach_representative_first", Kind.BOOLEAN,
        note = "대표 이미지를 첫 장으로 넣을지 정합니다.",
        domain = Domain.YesNo(
            defaultOn = AiPromptPolicy.ATTACH_REPRESENTATIVE_DEFAULT,
            extra = "`ai_attach_image_count`가 0이면 이 설정은 아무 일도 하지 않습니다."))
    val AI_IMAGE_TAG_POLICY = Spec("ai_image_tag_policy", Kind.TEXT,
        note = "AI 태그 제안에 함께 보낼 지침입니다.",
        domain = Domain.FreeText(AiPromptPolicy.IMAGE_TAG_POLICY_MAX_CHARS,
            // 제안 시점 고지는 약속하지 않는다 — `DropTally.policyTruncated` 배선이 있기는 한데
            // `AiPromptSettings.imageTagPolicy`가 **읽기·쓰기 양쪽에서 좁히는** 탓에 제안기가
            // 받는 값이 이미 잘린 값이고, 그래서 그 수는 언제나 0이다. **엑셀 가져오기는
            // 잘리면 그 행에서 알린다** — 바인딩이 read-back으로 대조해 `Applied.Adjusted`를
            // 낸다(숫자 설정의 접힘 고지와 같은 벌).
            extra = "앞뒤 공백은 지우고, 넘는 글자는 잘라 저장합니다. 비우면 지침을 보내지 않습니다."))
    val AI_IMAGE_TAG_BATCH_SIZE = Spec("ai_image_tag_batch_size", Kind.NUMBER,
        note = "이미지 태그 요청 한 번에 보낼 장수입니다.",
        domain = Domain.Ints(
            AiPromptPolicy.IMAGE_TAG_BATCH_MIN, AiPromptPolicy.IMAGE_TAG_BATCH_MAX, unit = "장",
            defaultText = AiPromptPolicy.IMAGE_TAG_BATCH_DEFAULT.toString(),
            extra = "범위 밖은 좁혀서 받습니다. 적게 잡을수록 요청 수가 늘어 되레 비쌉니다."))
    val AI_IMAGE_TAG_GROUP_UNIT = Spec("ai_image_tag_group_unit", Kind.BOOLEAN,
        note = "링크 묶음 단위로 이미지를 보낼지 정합니다.",
        domain = Domain.YesNo(defaultOn = true,
            extra = "켜면 묶음마다 표본만 보내고 받은 태그를 묶음 전원에 붙입니다."))
    val AI_IMAGE_TAG_GROUP_SAMPLE_SIZE = Spec("ai_image_tag_group_sample_size", Kind.NUMBER,
        note = "묶음 하나에서 표본으로 보낼 이미지 장수입니다.",
        domain = Domain.Ints(
            AiPromptPolicy.IMAGE_TAG_GROUP_SAMPLE_MIN, AiPromptPolicy.IMAGE_TAG_GROUP_SAMPLE_MAX,
            unit = "장", defaultText = AiPromptPolicy.IMAGE_TAG_GROUP_SAMPLE_DEFAULT.toString(),
            extra = "범위 밖은 좁혀서 받습니다. `ai_image_tag_group_unit`을 껐으면 쓰이지 않습니다."))
    val AI_NAME_SUGGEST_BATCH_SIZE = Spec("ai_name_suggest_batch_size", Kind.NUMBER,
        note = "이름 추천을 한 번에 몇 개 받을지 정합니다.",
        domain = Domain.Ints(
            AiPromptPolicy.NAME_SUGGEST_BATCH_MIN, AiPromptPolicy.NAME_SUGGEST_BATCH_MAX, unit = "개",
            defaultText = AiPromptPolicy.NAME_SUGGEST_BATCH_DEFAULT.toString(),
            extra = "범위 밖은 좁혀서 받습니다."))

    // ── AI 메시지 양식 (사용자 요청 2026.08.20) ──
    //
    // **선언을 손으로 열넷 적지 않는다** — `PromptTemplates.Id`가 이미 열넷을 들고 있고,
    // 손으로 베끼면 그중 하나가 다른 양식을 가리키는 오타가 **컴파일도 시험도 통과한 채**
    // 남는다(보충 기준 불리언 아홉을 한 벌로 지은 것과 같은 근거).
    //
    // **선언과 바인딩이 갈릴 수 없다는 것이 이 꼴의 값이다** — 둘 다 `PromptTemplates.Id`
    // 하나에서 나오므로(저쪽은 `Id.entries.map { Binding(templateSpecOf(it), …) }`), 한쪽에만
    // 있는 키를 만들 방법이 문법적으로 없다. 손으로 열넷을 적었다면 그중 하나가 다른 양식을
    // 가리키는 오타가 컴파일도 시험도 통과한 채 남았을 것이다(보충 기준 불리언 아홉과 같은 근거).
    //
    // 그 대신 `tools/check_app_settings_catalog.sh` 축 ①은 `val NAME = Spec(` 꼴을 sed로 뜨므로
    // **여기서 만든 것을 못 본다.** 검사가 보는 몫은 [SPECS] 등재 여부이고, 그것은
    // `AppSettingsKeysTest`가 든다.
    val AI_PROMPT_TEMPLATES: List<Spec> =
        com.novelcharacter.app.ai.PromptTemplates.Id.entries.map { id ->
            Spec(
                id.key, Kind.TEXT,
                note = "AI에 보내는 메시지 양식 — " + id.label,
                domain = Domain.Template(id)
            )
        }

    fun templateSpecOf(id: com.novelcharacter.app.ai.PromptTemplates.Id): Spec =
        AI_PROMPT_TEMPLATES.first { it.key == id.key }

    /**
     * 사건 AI 추천의 재료 범위 — 켠 재료의 키를 쉼표로 이은 것 (B-43).
     *
     * **빈 칸이 뜻을 갖는다**([AI_MIN_CONFIDENCE]와 같은 부류) — '전부 끔'이라서다.
     * 그래서 이 행은 값이 비어도 유실이 아니고, 가져오기도 빈 칸을 값으로 읽는다.
     */
    val AI_EVENT_CONTEXT_SCOPE = Spec("ai_event_context_scope", Kind.TEXT,
        note = "사건 AI 추천에 함께 보낼 재료입니다.",
        domain = Domain.ChoiceList(
            com.novelcharacter.app.ai.EventAiMaterial.entries.map { it.key },
            defaultText = "넷 전부",
            // 각 이름의 뜻은 열거가 든다 — 여기 손으로 적으면 재료가 늘 때 안내만 낡는다.
            extra = "차례로 " +
                com.novelcharacter.app.ai.EventAiMaterial.entries.joinToString("·") { it.label } +
                "입니다. 대소문자는 " +
                "가리지 않고 전각 쉼표와 따옴표로 감싼 칸도 같은 규칙으로 읽습니다. 모르는 이름이 " +
                "하나라도 섞이면 그 행을 통째로 건너뜁니다. **빈 칸은 오타가 아니라 값입니다** — " +
                "재료를 전부 끄고 끌 수 없는 사건 설명·때·유형·세계관만 보냅니다."))

    /**
     * API 키 전부 — `{"프로바이더id":"키"}` JSON 한 칸.
     *
     * **기본 제외다**(사용자 확정 3번 ㄴ1). 켜려면 내보내기에서 별도 동의를 눌러야 하고,
     * 그때도 경고를 먼저 본다 — **엑셀은 평문이고 사용자가 남에게 보내기도 하는 파일**이다.
     * 한 칸에 모으는 것은 프로바이더 수가 가변이라 [SPECS]에 정적으로 적을 수 없어서다.
     */
    val AI_API_KEYS = Spec("ai_api_keys", Kind.TEXT, Disposition.SECRET,
        note = "동의했을 때만 실리는 프로바이더별 API 키입니다.",
        domain = Domain.AppOwned("설정의 AI 연동",
            extra = "`{\"프로바이더id\":\"키\"}` 꼴의 JSON 한 칸입니다. 내보내기에서 따로 " +
                "동의해야 이 행이 실립니다 — 엑셀은 평문이고 남에게 보내기도 하는 파일입니다. " +
                "JSON으로 읽히지 않거나 담긴 키가 없으면 그 행을 건너뜁니다."))

    // ── 통계 기준 ──
    val STATS_COMPLETION_REQUIRED_WEIGHT = Spec("stats_completion_required_weight", Kind.NUMBER,
        note = "완성도를 셀 때 필수 칸을 몇 배로 셀지 정합니다.",
        domain = Domain.Decimals(
            com.novelcharacter.app.util.CompletionWeights.MIN_REQUIRED_WEIGHT.toString(),
            com.novelcharacter.app.util.CompletionWeights.MAX_REQUIRED_WEIGHT.toString(),
            defaultText = com.novelcharacter.app.util.CompletionWeights.DEFAULT.requiredWeight.toString(),
            extra = "범위 밖은 접어서 받습니다. 숫자로 읽히지 않으면 그 행을 건너뜁니다."))
    val STATS_PATTERN_TYPES = Spec("stats_pattern_types", Kind.TEXT,
        note = "통계·어시스턴트에서 볼 패턴 유형을 고릅니다.",
        // **이름 목록을 `ui.stats.PatternType`에서 직접 받지 않는다** — 이 파일은 순수 선언이고
        // `excel/` → `ui/` 간선을 만들면 프로브가 `ui/**`를 통째로 빼는 탓에 **이 파일의 타입
        // 검사가 통째로 눈먼다**(B-251 ⓓ가 없앤 병의 재현). 그 대신 열거와 갈리는 것을
        // `AppSettingsKeysTest`가 막는다 — 그 하네스는 양쪽을 다 본다.
        domain = Domain.ChoiceList(
            listOf("DOMINANCE", "CLUSTER", "ABSENCE", "OUTLIER", "BALANCE", "CROSS_NOVEL"),
            defaultText = "여섯 전부",
            extra = "대소문자는 가리지 않고 전각 쉼표와 따옴표도 같은 규칙으로 읽습니다. 적은 것만 " +
                "켜고 나머지는 끕니다. 일부만 모르는 이름이면 나머지를 적용하고 알려 드리며, " +
                "전부 모르는 이름이면 지금 값을 그대로 둡니다. 비우면 유형을 전부 끕니다."))

    /**
     * 유형별 민감도 (B-70). `키=값` 쉼표 목록이라 엑셀에서 한 줄만 고칠 수 있다 —
     * 형식의 단일 소스는 `PatternThresholds.encode`/`decode`다.
     */
    val STATS_PATTERN_SENSITIVITY = Spec("stats_pattern_sensitivity", Kind.TEXT,
        note = "무엇부터를 편중·집중이라 부를지 정합니다.",
        // 여섯 범위는 `StatsDataProvider`의 PatternThresholds가 단일 소스다.
        domain = Domain.Pairs(
            listOf(
                "dominance" to "10~100", "balance" to "10~100", "outlier" to "1~100",
                "cluster" to "10~100", "absence_years" to "1~1000000",
                "cross_novel_ratio" to "1.1~1000"
            ),
            defaultText = "dominance=60, balance=35, outlier=5, cluster=50, absence_years=100, cross_novel_ratio=3",
            extra = "키는 대소문자를 가리지 않습니다. 적지 않은 키는 지금 값을 그대로 둡니다. " +
                "범위 밖은 접어서 받고, 모르는 키와 숫자로 못 읽는 값은 그 자리만 지금 값으로 두고 " +
                "알려 드립니다. 빈 칸은 그 행을 건너뜁니다."))

    // ── 보충 기준 ──
    val SUPPLEMENT_CHECK_IMAGES = Spec("supplement_check_images", Kind.BOOLEAN,
        note = "이미지가 없는 캐릭터를 보충 대상으로 잡습니다.",
        domain = Domain.YesNo(defaultOn = true))
    val SUPPLEMENT_CHECK_MEMO = Spec("supplement_check_memo", Kind.BOOLEAN,
        note = "메모가 빈 캐릭터를 보충 대상으로 잡습니다.",
        domain = Domain.YesNo(defaultOn = false))
    val SUPPLEMENT_CHECK_ALIASES = Spec("supplement_check_aliases", Kind.BOOLEAN,
        note = "이명이 빈 캐릭터를 보충 대상으로 잡습니다.",
        domain = Domain.YesNo(defaultOn = false))
    val SUPPLEMENT_CHECK_NOVEL = Spec("supplement_check_novel", Kind.BOOLEAN,
        note = "작품에 배정되지 않은 캐릭터를 잡습니다.",
        domain = Domain.YesNo(defaultOn = true))
    val SUPPLEMENT_CHECK_TAGS = Spec("supplement_check_tags", Kind.BOOLEAN,
        note = "태그가 없는 캐릭터를 보충 대상으로 잡습니다.",
        domain = Domain.YesNo(defaultOn = false))
    val SUPPLEMENT_CHECK_CUSTOM_FIELDS = Spec("supplement_check_custom_fields", Kind.BOOLEAN,
        note = "필드 완성도가 기준에 못 미치는 캐릭터를 잡습니다.",
        domain = Domain.YesNo(defaultOn = true, extra = "기준값은 `supplement_field_threshold`가 정하고, 작품이 배정된 캐릭터만 봅니다."))
    val SUPPLEMENT_CHECK_RELATIONSHIPS = Spec("supplement_check_relationships", Kind.BOOLEAN,
        note = "관계가 하나도 없는 캐릭터를 잡습니다.",
        domain = Domain.YesNo(defaultOn = false))
    val SUPPLEMENT_CHECK_EVENTS = Spec("supplement_check_events", Kind.BOOLEAN,
        note = "사건에 엮이지 않은 캐릭터를 잡습니다.",
        domain = Domain.YesNo(defaultOn = false))
    val SUPPLEMENT_CHECK_FACTIONS = Spec("supplement_check_factions", Kind.BOOLEAN,
        note = "세력 없는 캐릭터를 보충 대상으로 잡습니다.",
        domain = Domain.YesNo(defaultOn = false, extra = "세력이 하나라도 있는 세계관에서만 봅니다."))
    val SUPPLEMENT_FIELD_THRESHOLD = Spec("supplement_field_threshold", Kind.NUMBER,
        note = "필드 완성도가 이 값 미만이면 보충 대상이 됩니다.",
        // 0~100은 바인딩의 `coerceIn(0, 100)`이 단일 소스다(이름 붙은 상수가 없다).
        domain = Domain.Ints(0, 100, unit = "%", defaultText = "50",
            extra = "범위 밖은 접어서 받고 소수는 소수점 아래를 버립니다. " +
                "`supplement_check_custom_fields`를 켰을 때만 쓰입니다."))
    val SUPPLEMENT_AUTO_SAVE_ON_EXIT = Spec("supplement_auto_save_on_exit", Kind.BOOLEAN,
        note = "랜덤 보충에서 편집을 끝낼 때 묻지 않고 저장합니다.",
        domain = Domain.YesNo(defaultOn = false))

    // ── 어시스턴트 ──
    val ASSISTANT_CATEGORIES = Spec("assistant_categories", Kind.TEXT,
        note = "어시스턴트에서 표시할 항목을 고릅니다.",
        // 이름 목록을 직접 받지 않는 이유는 위 `STATS_PATTERN_TYPES`와 같다 —
        // 갈리는 것은 `AppSettingsKeysTest`가 막는다.
        domain = Domain.ChoiceList(
            listOf("CONSISTENCY", "HEALTH", "BIAS", "NUDGE"),
            defaultText = "넷 전부",
            extra = "대소문자는 가리지 않고 전각 쉼표와 따옴표도 같은 규칙으로 읽습니다. 적은 것만 " +
                "켜고 나머지는 끕니다. 일부만 모르는 이름이면 나머지를 적용하고 알려 드리며, " +
                "전부 모르는 이름이면 지금 값을 그대로 둡니다. 비우면 항목을 전부 끕니다."))

    /**
     * 시트에 실리는 설정 전수 — **순서가 곧 시트의 행 순서**다.
     *
     * 갈래별로 묶어 두는 것은 사람이 엑셀에서 훑기 위해서다(이 사용자는 엑셀 우선
     * 작업자다 — `docs/usage_reality_check_2026-07.md`). 알파벳순으로 늘어놓으면
     * `ai_`·`image_`·`supplement_`가 서로를 끊는다.
     */
    val SPECS: List<Spec> = listOf(
        THEME_MODE, READ_FIELD_NOTE_ENABLED, BIRTHDAY_CELEBRATION_ENABLED,
        BACKUP_INCLUDE_IMAGES, BACKUP_MAX_BACKUPS,
        TRASH_MAX_OPERATIONS, TRASH_RETENTION_DAYS,
        FIELD_IMPORT_CONVERTIBLE_TYPES,
        IMAGE_COMPRESS_ENABLED, IMAGE_QUALITY_PERCENT, IMAGE_CAP_DIMENSION,
        IMAGE_MAX_LONG_EDGE_PX, IMAGE_SKIP_BELOW_ENABLED, IMAGE_SKIP_BELOW_BYTES,
        IMAGE_EDITOR_REMOVE_POLICY, IMAGE_AUTO_LINK_BY_CHARACTER,
        AI_PROVIDERS, AI_ACTIVE_PROVIDER,
        AI_USAGE_EXAMPLE_COUNT, AI_STYLE_SAMPLE_COUNT, AI_MIN_CONFIDENCE, AI_CREATIVITY,
        AI_ATTACH_IMAGE_COUNT, AI_ATTACH_REPRESENTATIVE_FIRST,
        AI_IMAGE_TAG_POLICY, AI_IMAGE_TAG_BATCH_SIZE,
        AI_IMAGE_TAG_GROUP_UNIT, AI_IMAGE_TAG_GROUP_SAMPLE_SIZE, AI_NAME_SUGGEST_BATCH_SIZE,
        AI_EVENT_CONTEXT_SCOPE,
        AI_API_KEYS,
        STATS_COMPLETION_REQUIRED_WEIGHT, STATS_PATTERN_TYPES, STATS_PATTERN_SENSITIVITY,
        SUPPLEMENT_CHECK_IMAGES, SUPPLEMENT_CHECK_MEMO, SUPPLEMENT_CHECK_ALIASES,
        SUPPLEMENT_CHECK_NOVEL, SUPPLEMENT_CHECK_TAGS, SUPPLEMENT_CHECK_CUSTOM_FIELDS,
        SUPPLEMENT_CHECK_RELATIONSHIPS, SUPPLEMENT_CHECK_EVENTS, SUPPLEMENT_CHECK_FACTIONS,
        SUPPLEMENT_FIELD_THRESHOLD, SUPPLEMENT_AUTO_SAVE_ON_EXIT,
        ASSISTANT_CATEGORIES
    // AI 메시지 양식 열넷은 맨 뒤다 — 한 행이 여러 줄 글이라 위쪽 설정들 사이에 끼우면
    // 짧은 행을 훑어보는 눈이 매번 그 덩어리를 넘어야 한다(이 사용자는 엑셀 우선 작업자다).
    ) + AI_PROMPT_TEMPLATES

    /**
     * 옛 이름 → 현행 키. **이름을 갈 때 여기 한 줄을 남기면 옛 파일이 그대로 들어온다.**
     *
     * 지금은 비어 있다 — 종전 11개의 이름을 하나도 바꾸지 않았기 때문이고, **그것이 판단이다**:
     * 이 슬라이스는 구조를 갈았지 시트의 말을 갈지 않았다. 왕복 무결성은 *같은 파일이 같은 뜻*
     * 이어야 성립하고, 구조 개편을 이유로 옛 파일의 뜻을 바꾸면 그 자리에서 깨진다.
     */
    val ALIASES: Map<String, String> = emptyMap()

    /** 키로 찾는다. 옛 이름도 [ALIASES]를 지나 같은 설정에 닿는다. */
    fun specOf(key: String): Spec? {
        val normalized = key.trim()
        val canonical = ALIASES[normalized] ?: normalized
        return SPECS.firstOrNull { it.key == canonical }
    }

    /** 이 선택으로 시트에 나갈 설정 — [includeSecrets]가 꺼져 있으면 비밀은 빠진다. */
    fun exported(includeSecrets: Boolean): List<Spec> =
        if (includeSecrets) SPECS else SPECS.filter { it.disposition != Disposition.SECRET }

    // ── 셀 값의 표기 — 순수이므로 여기 있다(시트가 무슨 글자를 담는가도 왕복 계약이다) ──

    /** 불리언의 셀 표기. 읽기는 `parseSheetBoolean`이 관대하게 받는다(Y/예/1/TRUE…). */
    fun formatBoolean(value: Boolean): String = if (value) "Y" else "N"

    /**
     * 숫자의 셀 표기 — 정수면 소수점을 붙이지 않는다(`3.0`이 아니라 `3`).
     *
     * **[Float]를 [Double]로 넓혀서 적으면 안 된다.** `1.3f.toDouble()`은
     * `1.2999999523162842`이고, 그대로 셀에 적으면 **사용자가 엑셀에서 보는 것이 그 숫자**이며
     * 왕복도 같은 글자로 돌아오지 않는다(완성도 가중이 실제로 `Float`다).
     * `Float`는 그 폭 그대로 문자열로 만든다 — `1.3f.toString()`은 `"1.3"`이다.
     */
    fun formatNumber(value: Number): String = when (value) {
        is Float -> if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
        else -> {
            val d = value.toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
    }

    /**
     * 셀 글자 → **유한한 수**. `NaN`·`Infinity`는 `toDoubleOrNull`이 수로 읽지만
     * 설정값으로는 뜻이 없다 — 그대로 흘리면 `NaN.toInt()==0`이 저장되고 *"조정해
     * 저장했습니다"*라는 거짓 문구까지 붙는다(콜드 검토 2026.08.20). 숫자 설정의 쓰기
     * ([AppSettingsBindings]의 `numberBinding`)와 미리보기의 건너뜀 게이트([AppSettingsDiff])가
     * **이 한 벌**을 지나 거절이 갈리지 않는다(R-33).
     */
    fun parseFiniteCell(value: String): Double? =
        value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    /**
     * 두 셀 글자가 **같은 수**인가 — `3`과 `3.0`은 같다. 한쪽이라도 수로 안 읽히면 글자로
     * 견준다(못 읽는 값을 '같다'로 접으면 다른 값이 같은 값으로 보인다).
     *
     * 복원 미리보기([AppSettingsDiff])와 가져오기의 read-back 대조([AppSettingsBindings]의
     * `numberBinding`)가 **이 한 벌**을 쓴다 — 술어가 두 벌이면 미리보기의 예고와 실제 처분이
     * 갈리는 날이 온다(R-33).
     */
    fun sameNumericCell(a: String, b: String): Boolean {
        val x = a.trim().toDoubleOrNull()
        val y = b.trim().toDoubleOrNull()
        return if (x != null && y != null) x == y else a.trim() == b.trim()
    }

    /**
     * 싣지 않는 저장소와 **그 사유** — 확정 3번의 ⓒ('실을 값어치가 없는 것').
     *
     * **사유를 함께 적는 것이 이 목록의 값이다.** 이름만 나열하면 다음 사람이
     * *"왜 빠졌지"*를 알 수 없어 다시 조사하거나, 값어치가 없다고 지레 넘긴다.
     * 검사 스크립트는 *등재돼 있는가*만 보고 사유의 내용은 사람이 읽는다.
     */
    val EXCLUDED_STORES: Map<String, String> = mapOf(
        "app_migrations" to
            "앱 내부 이행 기록이다. 실어서 되돌리면 **아직 안 한 이행을 했다고 표시**하게 되어 " +
            "그 기기의 데이터가 조용히 옛 형식으로 남는다 — 소음이 아니라 위험이다.",
        "onboarding_prefs" to "첫 실행 안내를 봤는가. 기기마다 처음이 다르다.",
        "birthday_celebration" to
            "생일 축하 창 저장소. **켬·끔은 ${BIRTHDAY_CELEBRATION_ENABLED.key}로 실리고**, "  +
            "같은 저장소의 *오늘 이미 띄웠는가*는 싣지 않는다 — 그 기기에서 일어난 일이라 " +
            "옮기면 새 기기가 하지도 않은 축하를 했다고 말해 그날 창이 안 뜬다(`backup_status`와 같은 근거).",
        "character_edit_drafts" to "저장하지 않은 편집 초안. 그 기기의 화면 상태다.",
        "folder_roundtrip_prefs" to "폴더 왕복이 기억한 기기 경로·URI. 다른 기기에서는 가리킬 곳이 없다.",
        "theme_cache" to "테마 캐시 — 값 자체는 ${THEME_MODE.key}로 실린다(저장소가 아니라 키로 등재된 자리).",
        "field_display_prefs" to
            "읽기 화면의 필드 설명 ⓘ 표시 여부. 값은 ${READ_FIELD_NOTE_ENABLED.key}로 실린다.",
        "settings" to "테마의 DataStore 본체. 값은 ${THEME_MODE.key}로 실린다.",
        "image_settings" to "이미지 저장 설정의 DataStore. 값은 `image_`로 시작하는 키들로 실린다.",
        "trash_settings" to
            "휴지통 보관 정책의 DataStore. 값은 ${TRASH_MAX_OPERATIONS.key}·${TRASH_RETENTION_DAYS.key}로 실린다.",
        "field_import_settings" to
            "필드 가져오기의 종류 변환 허용 타입 DataStore. 값은 ${FIELD_IMPORT_CONVERTIBLE_TYPES.key}로 실린다.",
        "backup_status" to
            "마지막 백업의 성공·실패 시각과 사유. **설정이 아니라 그 기기에서 일어난 일의 기록이다** — " +
            "옮기면 새 기기가 하지도 않은 백업을 했다고 말한다.",
        "character_list_ui" to "목록 화면의 마지막 보기 상태(스크롤·펼침).",
        "image_manager_ui_state" to "이미지 탭의 마지막 필터·정렬·보기 상태.",
        "character_detail_ui_state" to "상세 화면의 마지막 정렬.",
        "search_ui_state" to "검색 화면의 마지막 입력·펼침.",
        "timeline_ui_state" to "연표 화면의 마지막 축척·위치.",
        "graph_ui_state" to "관계도 화면의 마지막 배치.",
        "field_manage_ui_state" to "필드 관리 화면의 마지막 보기.",
        "field_library_ui_state" to "값 라이브러리 화면의 마지막 보기.",
        "namebank_ui_state" to "이름 은행 화면의 마지막 보기.",
        "analysis_ui_state" to "분석 홈의 마지막 탭·펼침.",
        "duel_entry" to "대결 입장 화면의 마지막 선택.",
        "duel_view" to
            "대결 카드 배치·그림 맞춤. **취향이라 기기 설정이다** — 같은 데이터를 두고도 " +
            "기기마다 달리 보고 싶을 수 있고, 갈려도 데이터가 갈리지 않는다(그 저장소의 KDoc이 든 근거).",
        "duel_image_basis" to
            "기준 이미지 축을 얼마나 세게 쓰는가. 위와 같은 근거로 기기 설정이다 — " +
            "**어느 축을 따르는가는 DB가 들어** 이미 백업·엑셀을 따라 넘어간다.",
        "ai_keys" to "API 키의 암호화 저장소. 값은 ${AI_API_KEYS.key}로 **동의했을 때만** 실린다.",
        "ai_providers" to "프로바이더 저장소. 값은 ${AI_PROVIDERS.key}·${AI_ACTIVE_PROVIDER.key}로 실린다.",
        "ai_prompt_settings" to "AI 프롬프트 설정 저장소. 값은 `ai_`로 시작하는 키들로 실린다.",
        "stats_prefs" to "통계 설정 저장소. 값은 `stats_`로 시작하는 키들로 실린다.",
        "supplement_criteria" to "보충 기준 저장소. 값은 `supplement_check_`·`supplement_field_threshold`로 실린다.",
        "supplement_ui_state" to
            "보충 화면 상태. 그중 자동 저장 스위치만 ${SUPPLEMENT_AUTO_SAVE_ON_EXIT.key}로 실린다 — " +
            "이름이 `ui_state`라고 전부 화면 흔적인 것은 아니다.",
        "assistant_prefs" to
            "어시스턴트 저장소. 카테고리 on/off는 ${ASSISTANT_CATEGORIES.key}로 실리고, " +
            "**카드 숨김은 싣지 않는다** — 숨김은 그 시점의 수치와 짝인 상태라 다른 기기로 옮기면 " +
            "엉뚱한 카드가 숨는다.",
        "backup_settings" to "백업 설정 저장소(DataStore). 값은 `backup_`으로 시작하는 키들로 실린다."
    )
}
