package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.TimelineEvent
import java.text.Collator
import java.util.Locale

/**
 * 캐릭터 상세 화면의 인라인 목록 세 개(관계·상태 변화·관련 사건)의 **보기 정렬**(B-85).
 *
 * ## 보기 정렬과 저장 순서는 다른 것이다
 * 관계 목록은 드래그로 바꾼 순서를 `displayOrder`로 **DB에 저장**한다 — 그 순서는 보기 설정이
 * 아니라 데이터다. 그래서 [RelationshipMode.MANUAL]은 "정렬하지 않음"(comparator `null`)이고,
 * 나머지 기준은 그 데이터를 **읽지도 쓰지도 않은 채** 보이는 순서만 바꾼다.
 * 정렬이 걸린 동안 드래그를 열어 두면 정렬된 화면의 위치가 그대로 `displayOrder`로 저장되어
 * **사용자가 손으로 잡아 둔 순서가 조용히 지워진다** — 그래서 호출부(`RelationshipHelper`)가
 * 비기본 정렬에서 드래그를 닫고, 닫힌 사유를 화면에 적는다. 이 파일은 그 판정의 근거인
 * [RelationshipMode.allowsManualReorder]를 든다(같은 사실을 두 곳에서 다시 적지 않는다).
 *
 * 상태 변화·사건에는 저장 순서가 없어(DAO가 연·월·일 고정) 그 충돌이 없다.
 *
 * ## 확장(원칙 01)
 * 새 기준은 각 enum에 상수 하나 + `when` 분기 하나다. 라벨은 호출부가 붙이므로 이 계층은
 * 안드로이드를 모른다(순수 JVM 검증 대상).
 */
object DetailListSort {

    /**
     * 한국어 이름 비교자. `String.compareTo`는 유니코드 코드포인트 순이라 초성·자모 결합 이름에서
     * 사용자가 기대하는 순서와 어긋난다. '전체 보기' 시트(`AffectedCharactersSheet`)가 이미
     * 같은 판단을 했으므로 같은 방식을 쓴다.
     */
    val koreanNames: Comparator<String> =
        Collator.getInstance(Locale.KOREAN).let { c -> Comparator { a, b -> c.compare(a, b) } }

    // ── 관계 ──

    /** @property allowsManualReorder 이 기준에서 드래그 재정렬(=`displayOrder` 저장)을 허용하는가. */
    enum class RelationshipMode(val allowsManualReorder: Boolean) {
        /** 저장된 수동 순서 그대로(기본) — 정렬하지 않는다. */
        MANUAL(true),
        NAME(false),
        /** 강한 관계 먼저 — "이 인물에게 누가 가장 크게 걸려 있는가". */
        INTENSITY(false),
        TYPE(false)
    }

    /**
     * 관계 목록 비교자. `null`이면 **정렬하지 않는다**(받은 순서 = DB의 `displayOrder` 순서).
     *
     * 표시 항목 타입(`RelationshipDisplayItem`)이 안드로이드에 묶인 파일에 있어 이 계층이
     * 직접 참조할 수 없다. 그래서 필요한 값만 뽑아 받는다 — 순수 JVM 검증을 포기하지 않기 위한
     * 선택이고, 덕분에 다른 표현형이 생겨도 이 비교자를 그대로 쓴다.
     */
    fun <T> relationships(
        mode: RelationshipMode,
        name: (T) -> String,
        intensity: (T) -> Int,
        type: (T) -> String
    ): Comparator<T>? = when (mode) {
        RelationshipMode.MANUAL -> null
        RelationshipMode.NAME -> Comparator { a, b -> koreanNames.compare(name(a), name(b)) }
        RelationshipMode.INTENSITY -> compareByDescending<T> { intensity(it) }
            .thenComparator { a, b -> koreanNames.compare(name(a), name(b)) }
        RelationshipMode.TYPE -> Comparator<T> { a, b -> koreanNames.compare(type(a), type(b)) }
            .thenComparator { a, b -> koreanNames.compare(name(a), name(b)) }
    }

    // ── 상태 변화 ──

    enum class StateChangeMode {
        /** 연·월·일 오름차순(기본) — DAO 순서와 같다. */
        CHRONO,
        CHRONO_DESC,
        /** 필드별로 묶고 그 안에서 시간순 — "이 값이 어떻게 변해 왔는가"를 한 덩어리로 본다. */
        FIELD
    }

    /**
     * 월·일이 비어 있으면 0으로 본다 — 월은 1~12, 일은 1~31이므로 0은 **연도만 아는 항목**을
     * 그 해 맨 앞에 둔다. DAO의 `ORDER BY month ASC`(SQLite는 NULL이 먼저)와 같은 결과다.
     */
    fun stateChanges(mode: StateChangeMode): Comparator<CharacterStateChange> {
        val chrono = compareBy<CharacterStateChange>({ it.year }, { it.month ?: 0 }, { it.day ?: 0 })
            .thenBy { it.id }
        return when (mode) {
            StateChangeMode.CHRONO -> chrono
            // 역순은 시간축만 뒤집는다 — id 타이브레이크까지 뒤집으면 같은 날짜 항목이
            // 정렬을 오갈 때마다 자리를 바꿔 읽는 사람이 데이터가 변한 줄 안다.
            StateChangeMode.CHRONO_DESC -> compareByDescending<CharacterStateChange> { it.year }
                .thenByDescending { it.month ?: 0 }
                .thenByDescending { it.day ?: 0 }
                .thenBy { it.id }
            StateChangeMode.FIELD -> compareBy<CharacterStateChange> { it.fieldKey }.then(chrono)
        }
    }

    // ── 관련 사건 ──

    enum class EventMode {
        /** 연·월·일 오름차순(기본) — DAO 순서와 같다. */
        CHRONO,
        CHRONO_DESC,
        /** 최근 등록순 — 방금 여러 건을 넣은 직후 "내가 지금 넣은 것"을 찾는 자리. */
        RECENT_ADDED
    }

    fun events(mode: EventMode): Comparator<TimelineEvent> {
        // displayOrder까지 포함해야 연표 화면에서 잡아 둔 같은 날짜 안의 순서가 상세에서도 같다(DAO와 동일).
        val chrono = compareBy<TimelineEvent>(
            { it.year }, { it.month ?: 0 }, { it.day ?: 0 }, { it.displayOrder }
        ).thenBy { it.id }
        return when (mode) {
            EventMode.CHRONO -> chrono
            EventMode.CHRONO_DESC -> compareByDescending<TimelineEvent> { it.year }
                .thenByDescending { it.month ?: 0 }
                .thenByDescending { it.day ?: 0 }
                .thenByDescending { it.displayOrder }
                .thenBy { it.id }
            EventMode.RECENT_ADDED -> compareByDescending<TimelineEvent> { it.createdAt }
                .thenByDescending { it.id }
        }
    }

    // ── 필드 관리 (B-48) ──

    /**
     * 필드 관리 목록의 **보기 정렬**.
     *
     * **이 자리는 관계 목록보다 파급이 넓다.** 관계의 `displayOrder`는 그 목록의 순서일 뿐이지만
     * 필드의 `displayOrder`는 **편집 폼의 칸 순서**(`DynamicFieldFormBuilder`가
     * `sortedBy { it.displayOrder }`로 정한다)이자 **엑셀 필드 정의 시트의 열**로 왕복까지 한다.
     * 정렬된 화면에서 드래그를 열어 두면 그 위치가 그대로 저장되어 **폼과 엑셀이 함께 어긋난다** —
     * 그래서 [FieldMode.allowsManualReorder]가 false인 동안 호출부가 **잡기와 쓰기 두 자리**를
     * 닫고 사유를 화면에 적는다(B-85가 세운 처방 그대로, 확정 7-7).
     *
     * @property allowsManualReorder 이 기준에서 드래그 재정렬(=`displayOrder` 저장)을 허용하는가.
     */
    enum class FieldMode(val allowsManualReorder: Boolean) {
        /** 저장된 수동 순서 그대로(기본) — 정렬하지 않는다. 이것이 폼·엑셀의 순서다. */
        MANUAL(true),
        NAME(false),
        /** 종류끼리 모아 본다 — "숫자 필드가 몇 개나 됐지"를 훑는 자리. */
        TYPE(false),
        /** 그룹끼리 모아 본다. 그룹 없는 필드는 뒤로 몬다(빈 이름이 앞에 서면 목록 머리가 빈다). */
        GROUP(false)
    }

    /**
     * 필드 목록 비교자. `null`이면 **정렬하지 않는다**(받은 순서 = `displayOrder` 순서).
     *
     * 관계 비교자와 같은 이유로 값만 뽑아 받는다 — 이 계층이 안드로이드를 모르게 하기 위해서다.
     * 같은 기준에서 값이 같으면 **언제나 이름으로 마무리한다**: 그러지 않으면 같은 종류·같은
     * 그룹의 필드들이 매번 다른 순서로 서서 목록이 손대지 않았는데 움직인다.
     */
    fun <T> fields(
        mode: FieldMode,
        name: (T) -> String,
        type: (T) -> String,
        group: (T) -> String
    ): Comparator<T>? {
        val byName = Comparator<T> { a, b -> koreanNames.compare(name(a), name(b)) }
        return when (mode) {
            FieldMode.MANUAL -> null
            FieldMode.NAME -> byName
            FieldMode.TYPE -> Comparator<T> { a, b -> koreanNames.compare(type(a), type(b)) }
                .then(byName)
            // 그룹 없는 필드를 뒤로 — 빈 문자열이 사전순 맨 앞이라 그냥 두면 목록 머리가
            // '그룹 없음' 덩어리가 되고, 그룹으로 묶어 보려던 목적이 무너진다.
            FieldMode.GROUP -> compareBy<T> { if (group(it).isBlank()) 1 else 0 }
                .thenComparator { a, b -> koreanNames.compare(group(a), group(b)) }
                .then(byName)
        }
    }
}
