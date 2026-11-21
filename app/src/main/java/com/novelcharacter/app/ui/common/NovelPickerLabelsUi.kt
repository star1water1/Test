package com.novelcharacter.app.ui.common

import android.content.Context
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.util.NovelPickerLabels

/**
 * **작품을 고르는 목록의 이름표** — 화면들이 쓰는 한 경로.
 *
 * 규칙(겹치는 제목에만 세계관을 붙인다)은 [NovelPickerLabels]가 들고, 여기서는 세계관 이름을
 * 앱 저장소에서 가져오는 배선만 한다. 동명이 없으면 세계관 표를 읽지 않는다.
 * 돌려주는 목록은 [novels]와 **같은 순서·같은 길이**다 — 고른 자리로 작품을 되찾는다.
 */
suspend fun Context.novelPickerLabels(novels: List<Novel>): List<String> =
    NovelPickerLabels.forNovels(novels) {
        (applicationContext as NovelCharacterApp).universeRepository
            .getAllUniversesList().associate { it.id to it.name }
    }
