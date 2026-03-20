package com.novelcharacter.app.util

import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * 자주 사용하는 Gson TypeToken 상수 모음.
 * 매번 익명 클래스를 생성하지 않고 재사용한다.
 */
object GsonTypes {
    val STRING_LIST: Type = object : TypeToken<List<String>>() {}.type
    val STRING_ANY_MAP: Type = object : TypeToken<Map<String, Any>>() {}.type
}
