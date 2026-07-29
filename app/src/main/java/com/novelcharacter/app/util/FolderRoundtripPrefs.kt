package com.novelcharacter.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 정리 폴더 왕복이 기억하는 장부의 저장 계층 — 규칙은 [FolderRoundtripLedger](순수)에 있고
 * 여기는 SharedPreferences 입출력만 한다. **DB 스키마는 건드리지 않는다.**
 *
 * 세 가지를 보관한다:
 * - **이동 실패 지문**: `_처리됨/`으로 옮기지 못한 파일을 다음 스캔에서 다시 편입하지 않기 위한 표식.
 * - **개명 별칭**: 재압축 커밋·zip 복원이 파일을 새 UUID로 개명할 때 남기는 (옛 경로 → 새 경로).
 *   이것이 없으면 개명 전에 내보낸 사본이 돌아올 때 토큰이 끊겨 중복 편입된다(설계 9장 C-1).
 * - **흩어진 나머지**: 받아오기가 묶음을 쪼개 혼자 남게 된 이미지 경로
 *   (설계 `image_folder_tag_ai` 5-1). 어시스턴트 카드가 읽는다.
 *
 * 셋 다 **캐시 성격**이다 — 지워져도 데이터가 유실되지 않고 한 번 더 일할 뿐이다.
 * 그래서 저장 실패를 예외로 올리지 않고, 상한을 넘으면 오래된 것부터 버린다.
 *
 * **진입 감지 결과는 캐시하지 않는다.** SAF 트리의 루트 수정 시각은 하위 폴더 변경을 반영하지
 * 않아, 그것으로 캐시를 걸면 새 이미지를 조용히 놓친다 — 느린 것보다 나쁘다. 진입 감지는
 * 매번 실제로 나열하되 비동기이고 실패 시 조용히 생략한다(수동 메뉴가 항상 있다).
 */
object FolderRoundtripPrefs {

    private const val PREF_NAME = "folder_roundtrip_prefs"
    private const val KEY_FINGERPRINTS = "unmoved_fingerprints"
    private const val KEY_EXPORT_FINGERPRINTS = "export_fingerprints"
    private const val KEY_RENAME_ALIASES = "rename_aliases"
    private const val KEY_SCATTERED = "scattered_singletons"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── 처리 완료 지문 ──
    //
    // 두 용도가 **키를 따로 쓴다.** 한 목록에 합치면 상한(2000)을 나눠 쓰게 되어, 사본을 대량
    // 으로 내보내는 순간 이동 실패 지문이 통째로 밀려난다 — 그러면 `_처리됨/`으로 옮기지 못해
    // 폴더에 남아 있던 원본이 다음 스캔에서 **새 파일로 다시 편입된다**(조용한 중복 편입 —
    // 이 장부가 막으려던 바로 그 결과다). 상한을 얼마로 잡든 한 목록을 공유하는 한 사라지지
    // 않는 문제라, 숫자가 아니라 **키를 가르는 것**이 해법이다.
    //
    // 둘의 성격도 다르다: 이동 실패 지문은 드물게 하나씩 쌓이고 유실되면 중복 편입을 낳지만,
    // 내보내기 지문은 한 번에 수백 건이 쌓이고 유실돼도 배너 숫자만 커진다(그 사본들은 이미
    // 제자리라 받아오기가 할 일을 찾지 못한다). 값싼 쪽이 비싼 쪽을 밀어내게 두면 안 된다.

    /** 스캔이 건너뛸 지문 전체 — 두 장부의 합집합. */
    fun fingerprints(context: Context): Set<String> {
        val p = prefs(context)
        val unmoved = FolderRoundtripLedger.decodeList(p.getString(KEY_FINGERPRINTS, null))
        val exported = FolderRoundtripLedger.decodeList(p.getString(KEY_EXPORT_FINGERPRINTS, null))
        return LinkedHashSet<String>(unmoved.size + exported.size).apply {
            addAll(unmoved)
            addAll(exported)
        }
    }

    /** `_처리됨/` 이동에 실패해 폴더에 남은 원본 — 유실되면 중복 편입이 된다. */
    fun addUnmovedFingerprints(context: Context, added: Collection<String>) =
        append(context, KEY_FINGERPRINTS, added)

    /** 내보내기가 방금 쓴 사본 — 유실돼도 배너 숫자만 커진다(할 일 없는 파일로 잡힐 뿐). */
    fun addExportFingerprints(context: Context, added: Collection<String>) =
        append(context, KEY_EXPORT_FINGERPRINTS, added)

    private fun append(context: Context, key: String, added: Collection<String>) {
        if (added.isEmpty()) return
        val p = prefs(context)
        val current = FolderRoundtripLedger.decodeList(p.getString(key, null))
        val merged = FolderRoundtripLedger.appendBounded(current, added)
        p.edit().putString(key, FolderRoundtripLedger.encodeList(merged)).apply()
    }

    // ── 개명 별칭 ──

    fun renameAliases(context: Context): Map<String, String> =
        FolderRoundtripLedger.decodeMap(prefs(context).getString(KEY_RENAME_ALIASES, null))

    /**
     * 파일 개명을 기록한다. **경로를 바꾸는 모든 지점이 불러야 한다** — 부르지 않으면 그
     * 이미지의 옛 토큰이 조용히 끊긴다(중복 편입의 원인).
     *
     * 여러 건을 한 번에 넘기면 한 번만 쓴다(재압축 커밋·복원처럼 묶음으로 개명하는 경로용).
     */
    fun recordRenames(context: Context, renames: Map<String, String>) {
        if (renames.isEmpty()) return
        val p = prefs(context)
        var map = FolderRoundtripLedger.decodeMap(p.getString(KEY_RENAME_ALIASES, null))
        for ((old, new) in renames) {
            map = FolderRoundtripLedger.putRenameBounded(map, old, new)
        }
        p.edit().putString(KEY_RENAME_ALIASES, FolderRoundtripLedger.encodeMap(map)).apply()
    }

    // ── 흩어진 나머지 (설계 image_folder_tag_ai 5-1, 장부 ③) ──
    //
    // **왜 기록해야 하는가:** `ImageMetaDao.clearGroupIfSingleton`이 모든 해제 경로에서 1장짜리
    // 링크 그룹을 즉시 없앤다(`AutoLinkPlanner`도 "1장 링크 배지는 오해"라고 못박는다). 그래서
    // "원래 2장 이상이던 묶음에서 혼자 남은 것"은 **현재 상태로는 셀 수 없다** — 파생으로 만든
    // 카드는 영원히 0건인 껍데기가 된다(원칙 02). 고른 기준이 애초에 역사적 서술이므로 기록이
    // 정직한 구현이다.
    //
    // **의도한 잡동사니는 적지 않는다.** 폴더 하나를 통째로 서랍에 넣어 그룹 전원이 함께 풀린
    // 경우는 사용자가 원한 결과다 — 그것까지 세면 서랍을 쓸 때마다 카드가 떠서 꺼지게 된다.
    // 판정(누가 남았는가)은 실행부가 그룹 잔여 인원을 보고 한다.

    fun scatteredPaths(context: Context): List<String> =
        FolderRoundtripLedger.decodeList(prefs(context).getString(KEY_SCATTERED, null))

    fun addScatteredPaths(context: Context, added: Collection<String>) =
        append(context, KEY_SCATTERED, added)

    /** 사용자가 다시 묶었거나 카드를 숨겼거나 이미지가 사라졌을 때 — 목록에서 뺀다. */
    fun removeScatteredPaths(context: Context, removed: Collection<String>) {
        if (removed.isEmpty()) return
        val p = prefs(context)
        val current = FolderRoundtripLedger.decodeList(p.getString(KEY_SCATTERED, null))
        val drop = removed.toHashSet()
        val kept = current.filterNot { it in drop }
        if (kept.size == current.size) return
        p.edit().putString(KEY_SCATTERED, FolderRoundtripLedger.encodeList(kept)).apply()
    }

    fun clearScatteredPaths(context: Context) {
        prefs(context).edit().remove(KEY_SCATTERED).apply()
    }

    /**
     * 정리 폴더를 바꾸거나 해제하면 지문은 의미를 잃는다 — 지문의 축이 **그 폴더 기준
     * 상대경로**라, 다른 폴더에서 같은 상대경로·크기의 파일이 우연히 건너뛰어질 수 있다.
     * 별칭은 폴더와 무관하므로 남긴다.
     */
    fun clearFolderScopedState(context: Context) {
        prefs(context).edit()
            .remove(KEY_FINGERPRINTS)
            .remove(KEY_EXPORT_FINGERPRINTS)
            .apply()
    }
}
