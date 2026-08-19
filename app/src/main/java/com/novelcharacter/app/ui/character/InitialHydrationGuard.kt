package com.novelcharacter.app.ui.character

/**
 * **초기 적재가 사용자가 이미 적은 것을 덮지 못하게 막는다** (B-268) — 순수 판정기.
 *
 * ## 무엇을 막는가
 *
 * 캐릭터 편집 창은 **폼을 먼저 세우고 값을 나중에 채운다.** `onViewCreated`는 동기로
 * 끝나 위젯과 워처가 곧바로 살아나는데(`setupChangeTracking`), 기존 캐릭터의 값은
 * 코루틴 안에서 온다 — `loadNovels()` → `getCharacterByIdSuspend()` →
 * `getTagsByCharacterList()` 세 질의를 **차례로** 지난 뒤에야 `fillForm`이 돈다.
 * 그 사이가 열려 있고, 그동안 사용자는 이미 칸을 만질 수 있다.
 *
 * 종전에는 `fillForm`이 **조건 없이** `setText`했다. 그래서:
 *
 * 1. 창이 열리고 사용자가 이름 칸에 적기 시작한다
 * 2. DB 적재가 돌아온다
 * 3. `fillForm`이 적던 것을 **DB 값으로 덮는다**
 * 4. `loadExistingCharacter` 끝의 `hasUnsavedChanges = false`가 **덮었다는 사실까지 지운다**
 *
 * 4번이 이 결함을 조용하게 만든 자리다 — 더티 표식이 꺼지므로 뒤로가기 확인창도 안 뜨고,
 * `onPause`의 드래프트 기록도 남길 것이 없다고 판단한다. **사용자 입력이 말없이 사라진다**
 * (개발 의도 2번의 정면 위반). B-267 수리(2026.08.19)가 회전 축에서 같은 부류를 닫으며
 * *"이건 회전과 무관한 별건으로 남아 있다"*고 적어 둔 바로 그 구멍이다.
 *
 * ## 왜 순수 클래스인가
 *
 * 판정에 필요한 재료가 **위젯이 아니라 사실 셋**뿐이기 때문이다 — *지금 어느 국면인가* ·
 * *어느 칸이 만져졌는가* · *지금 쓰려는 칸이 무엇인가*. 안드로이드 타입을 하나도 안 쓰므로
 * 순수 JVM 시험이 전수로 본다(`InitialHydrationGuardTest`). 화면 계층의 판정을 화면 밖으로
 * 꺼내는 것은 이 저장소가 `CharacterSaveCoordinator.FormSnapshot`에서 이미 쓴 손버릇이다.
 *
 * ## 국면 셋
 *
 * | 국면 | 언제 | 워처가 뜨면 | [mayWrite] |
 * |---|---|---|---|
 * | [Phase.AWAITING] | 뷰가 섰고 DB 값은 아직 안 왔다 | **사용자 편집이다** — 적어 둔다 | (안 부른다) |
 * | [Phase.HYDRATING] | `fillForm`·태그 적재가 도는 중 | 프로그램적 `setText`다 — **무시** | 만진 칸만 거절 |
 * | [Phase.SETTLED] | 적재가 끝났다 | 평범한 편집이다 | 전부 허용(부를 일 없음) |
 *
 * ## 부르는 쪽의 계약 — **적재 전의 프로그램적 쓰기는 알리지 않는다**
 *
 * [onFieldChanged]는 *"사용자가 만졌다"*를 받는 자리이지 *"위젯이 바뀌었다"*를 받는 자리가
 * 아니다. [Phase.AWAITING] 동안 화면이 **스스로** 칸을 쓰는 경로가 있으면 그 자리는
 * 알리지 말아야 한다 — 알리면 그 칸이 *만져진 것*으로 적혀 **뒤따르는 진짜 적재가 스스로
 * 막힌다.**
 *
 * 실례가 있다: 사건 편집 창의 역법 칸은 시드 경로(`updateCalendarSeed`)가 적재보다 **먼저**
 * `setCalendarProgrammatically("")`를 부르는 가지를 갖는다. 그 창은 이미 그 구별을
 * `suppressCalendarWatcher`로 들고 있었고, 그래서 알림도 같은 조건 안에 넣었다.
 * **[Phase.HYDRATING]은 적재 자신의 쓰기만 막는다 — 적재 *앞*의 쓰기는 부르는 쪽 몫이다.**
 *
 * **국면을 되돌리지 않는다** — [beginHydration]·[endHydration]은 각각 한 번만 뜻이 있고,
 * 두 번째 호출은 조용히 무시된다. 되돌리면 *"만진 칸"*의 뜻이 국면마다 갈려,
 * 이 판정기가 막으려던 바로 그 종류의 비대칭이 판정기 안으로 들어온다.
 */
class InitialHydrationGuard {

    enum class Phase { AWAITING, HYDRATING, SETTLED }

    var phase: Phase = Phase.AWAITING
        private set

    private val touched = LinkedHashSet<String>()

    /**
     * 워처가 [key] 칸의 변화를 봤다고 알린다.
     *
     * @return 이것이 **사용자가 적은 것**이면 true. 초기 적재가 쓴 것이면 false —
     *   부르는 쪽은 그 값으로 더티 표식을 세울지 정한다(가짜 양성 차단).
     */
    fun onFieldChanged(key: String): Boolean {
        if (phase != Phase.AWAITING) return phase == Phase.SETTLED
        touched.add(key)
        return true
    }

    /** 초기 적재를 시작한다 — 이 뒤의 워처 신호는 프로그램적 쓰기다. */
    fun beginHydration() {
        if (phase == Phase.AWAITING) phase = Phase.HYDRATING
    }

    /** 초기 적재가 끝났다 — 이 뒤는 평범한 편집이다. */
    fun endHydration() {
        if (phase != Phase.SETTLED) phase = Phase.SETTLED
    }

    /**
     * 초기 적재가 [key] 칸에 DB 값을 써도 되는가.
     *
     * **적재 전에 만져진 칸이면 false다.** 그 칸의 진실은 DB가 아니라 사용자가 적은 것이다.
     */
    fun mayWrite(key: String): Boolean = key !in touched

    /**
     * 적재 전에 사용자가 만진 칸이 하나라도 있는가.
     *
     * `loadExistingCharacter`가 끝에서 더티 표식을 끄기 전에 이것을 본다 — 켜져 있으면
     * **끄지 않는다.** 끄면 덮지 않고 지켜 낸 입력이 *"저장할 것 없음"*으로 취급돼
     * 뒤로가기 확인창도 드래프트 기록도 사라진다.
     */
    fun hasEarlyEdits(): Boolean = touched.isNotEmpty()

    /** 적재 전에 만져진 칸들 — 등록 순서대로. 진단·시험용. */
    fun earlyEditedKeys(): List<String> = touched.toList()

    companion object {
        // 폼 칸 이름 — 워처를 다는 자리와 [mayWrite]를 묻는 자리가 **같은 문자열**을 써야
        // 한다. 상수로 두는 이유가 그것이다(날 문자열이면 한쪽 오타가 조용히 통과한다).
        const val KEY_NAME = "name"
        const val KEY_FIRST_NAME = "firstName"
        const val KEY_LAST_NAME = "lastName"
        const val KEY_ANOTHER_NAME = "anotherName"
        const val KEY_MEMO = "memo"
        const val KEY_TAGS = "tags"

        /** 작품 스피너 — 텍스트가 아니라 선택이지만 덮이는 성질은 같다. */
        const val KEY_NOVEL = "novel"

        /** 이미지 스트립 — `fillForm`이 `setPaths`로 통째로 갈아 끼운다. */
        const val KEY_IMAGES = "images"

        // ── 사건 편집 창의 칸 (B-268 ⓑ) — 같은 판정기를 쓴다 ──────────────────
        // 화면이 달라도 **결함이 같은 부류**라 규칙을 복제하지 않는다. 키만 다르다.
        const val KEY_YEAR = "year"
        const val KEY_MONTH = "month"
        const val KEY_DAY = "day"
        const val KEY_CALENDAR = "calendar"
        const val KEY_DESCRIPTION = "description"
        const val KEY_EVENT_TYPE = "eventType"
    }
}
