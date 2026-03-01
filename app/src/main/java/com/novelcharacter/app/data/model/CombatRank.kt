package com.novelcharacter.app.data.model

enum class CombatRank(val label: String, val description: String) {
    EX("EX", "어지간한 최상급 마물 혼자 가뿐히 쓰러뜨리기 가능"),
    S_PLUS("S+", "어지간한 최상급 마물 혼자 조금 힘겹게 쓰러뜨리기 가능"),
    S("S", "최상급 마물 혼자 목숨걸고 쓰러뜨리기 가능 (초월자, 공작급)"),
    A_PLUS("A+", "상급마물 혼자 가뿐히 쓰러뜨리기 가능"),
    A("A", "상급마물 혼자 조금 힘겹게 쓰러뜨리기 가능"),
    A_MINUS("A-", "상급마물 혼자 목숨걸고 쓰러뜨리기 가능"),
    B_PLUS("B+", "중급마물 혼자 가뿐히 쓰러뜨리기 가능"),
    B("B", "중급마물 혼자 조금 힘겹게 쓰러뜨리기 가능"),
    B_MINUS("B-", "중급마물 혼자 목숨걸고 쓰러뜨리기 가능"),
    C_PLUS("C+", "하급마물 혼자 가뿐히 쓰러뜨리기 가능"),
    C("C", "하급마물 혼자 조금 힘겹게 쓰러뜨리기 가능"),
    C_MINUS("C-", "하급마물 혼자 목숨걸고 쓰러뜨리기 가능"),
    D("D", "일반인");

    companion object {
        fun fromLabel(label: String): CombatRank? {
            return entries.find { it.label == label }
        }

        fun labels(): List<String> = entries.map { it.label }
    }
}
