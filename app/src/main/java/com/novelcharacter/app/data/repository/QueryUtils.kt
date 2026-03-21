package com.novelcharacter.app.data.repository

/**
 * SQL LIKE 쿼리에서 와일드카드 문자(%와 _)를 이스케이프하여
 * 사용자 입력이 리터럴 문자로 처리되도록 한다.
 * DAO 쿼리에는 ESCAPE '\' 절이 있어야 한다.
 */
fun sanitizeLikeQuery(query: String): String = query
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")
