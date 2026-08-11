package com.novelcharacter.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelcharacter.app.util.PresetMerge
import kotlinx.coroutines.flow.first

private val Context.fieldImportSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "field_import_settings")

/**
 * **필드 가져오기가 종류를 바꿔 심을 때 허용하는 타입** 저장소 (B-63 · 확정 14번).
 *
 * 확정은 *"허용 타입을 고정하지 말고 사용자가 정하게"*였고, 기본값은 P-6이 **안전 우선**으로
 * 정했다(글자·선택·숫자만 켜진 채로 시작 — [PresetMerge.DEFAULT_CONVERTIBLE_TYPES]).
 *
 * ## 왜 설정인가 — 화면 상태가 아니다
 *
 * 이 값은 *"내가 필드를 어떻게 보는가"*가 아니라 **조작의 결과를 바꾸는 것**이다. 잃으면
 * 같은 조작이 다른 일을 하므로 화면 상태(`field_manage_ui_state`)와 같은 자리에 둘 수 없고,
 * 엑셀 '앱 설정' 시트에 실려 기기를 옮겨도 따라와야 한다(개발 의도 4번 · R-45).
 *
 * ## 저장 형식이 목록인 이유
 *
 * 타입은 앞으로 늘 수 있다(`FieldDefinition.type`은 열린 문자열이다). 스위치를 타입마다
 * 하나씩 두면 새 타입이 생길 때마다 키가 늘고, **옛 기기에서 온 파일에는 그 키가 없어**
 * 켜졌는지 꺼졌는지 알 수 없다. 목록 하나로 두면 *"이 목록에 있으면 허용"* 한 규칙이고,
 * 모르는 이름이 섞여 들어와도 [PresetMerge.convertEntityType]이 그냥 안 쓸 뿐이다.
 */
class FieldImportSettingsStore(private val context: Context) {

    companion object {
        private val KEY_CONVERTIBLE_TYPES = stringPreferencesKey("convertible_types")

        /** 목록의 셀 표기 — 엑셀 '앱 설정' 시트가 그대로 읽고 쓴다. */
        const val SEPARATOR = ","

        /**
         * 저장 문자열 → 타입 집합. **거절하지 않고 좁혀서 받는다**(개발 의도 4번) —
         * 손편집된 파일의 공백·빈 칸·대소문자는 다듬고, 모르는 이름은 그대로 담는다.
         *
         * 모르는 이름을 버리지 않는 것이 요점이다: 새 타입이 생긴 기기에서 만든 파일을
         * 옛 기기가 읽고 다시 내보내면, 버릴 경우 **사용자가 켜 둔 것이 왕복에서 사라진다.**
         */
        fun parse(raw: String): Set<String> = raw
            .split(SEPARATOR)
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        /** 집합 → 저장 문자열. 차례를 고정해 왕복이 같은 문자열을 낸다(무의미한 차이 방지). */
        fun format(types: Set<String>): String = types.sorted().joinToString(SEPARATOR)
    }

    /**
     * 종류를 바꿔 심어도 되는 필드 타입.
     *
     * **키가 아예 없으면 기본값**이고, 키가 있으면 그 값이다 — **빈 문자열도 값이다**
     * (사용자가 전부 꺼서 '종류 바꿔 심기'를 닫아 둔 상태이고, 그것을 기본값으로 되돌리면
     * 사용자가 끈 것이 켜진다).
     */
    suspend fun getConvertibleTypes(): Set<String> {
        val raw = context.fieldImportSettingsDataStore.data.first()[KEY_CONVERTIBLE_TYPES]
            ?: return PresetMerge.DEFAULT_CONVERTIBLE_TYPES
        return parse(raw)
    }

    suspend fun setConvertibleTypes(types: Set<String>) {
        context.fieldImportSettingsDataStore.edit {
            it[KEY_CONVERTIBLE_TYPES] = format(types)
        }
    }
}
