package com.novelcharacter.app.data.model

enum class FieldType(val label: String) {
    TEXT("텍스트"),
    NUMBER("숫자"),
    SELECT("단일 선택"),
    MULTI_TEXT("복수 텍스트"),
    GRADE("등급"),
    CALCULATED("자동 계산"),
    BODY_SIZE("신체 사이즈");

    companion object {
        fun fromName(name: String): FieldType? = entries.find { it.name == name }
        fun labels(): List<String> = entries.map { it.label }
    }
}
