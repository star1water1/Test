package com.novelcharacter.app.util

import java.io.File

/**
 * 앱의 `filesDir` — **Context가 없는 저장소 계층**이 이미지 삭제 문지기
 * ([ImagePathMatch.isDeletableImage])에 뿌리를 건네기 위해 쓴다.
 *
 * `UniverseRepository`·`NovelRepository`·`TrashRepository`는 `AppDatabase` 하나만 받는다.
 * 그 셋이 지우는 그림의 뿌리를 얻으려고 수십 개의 생성 자리에 Context를 흘리는 대신,
 * 프로세스가 시작될 때(`NovelCharacterApp.onCreate`) 한 번 적어 둔다.
 * Context가 있는 자리는 이것 대신 `context.filesDir`를 직접 넘긴다.
 *
 * 등록 전이면 [dir]은 null이고, 문지기는 **모르면 막으므로** 아무것도 지우지 않는다
 * (파일이 남을 뿐이다 — 삭제보다 보존이 안전).
 */
object AppFilesRoot {
    @Volatile
    var dir: File? = null
        private set

    fun register(filesDir: File) {
        dir = filesDir
    }
}
