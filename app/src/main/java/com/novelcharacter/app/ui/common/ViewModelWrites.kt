package com.novelcharacter.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async

/**
 * **쓰기는 화면 수명이 아니라 뷰모델 수명에서 돈다** — 이 저장소의 규약을 담은 한 자리.
 *
 * `viewLifecycleOwner.lifecycleScope`는 **회전 한 번에 통째로 취소된다.** 그 스코프에서
 * 쓰기를 이어 하면 중단 지점마다 잘릴 수 있고, 그러면 **앞쪽만 커밋되고 뒤가 사라진다** —
 * 그리고 대개 아무 고지도 없다. 이 저장소가 그 모양을 되풀이해 겪었다:
 *
 * | 자리 | 무엇이 잘렸나 |
 * |---|---|
 * | `TimelineViewModel.insertEventField` | 사건 필드가 만들어졌는데 값 등재가 없다 |
 * | 대결 판정·되돌리기 (2026.08.22) | 트랜잭션이 롤백돼 **판정이 통째로 사라진다** |
 * | 캐릭터 저장 사슬 (2026.08.23) | 캐릭터는 저장됐는데 **태그만 옛 값** |
 * | 작품·사건 필드 생성 (2026.08.23) | 정의는 섰는데 사전 등록 값이 없다 |
 *
 * **`async{}.await()`인 것이 요점이다.** 만든 코루틴은 호출부의 자식이 아니라
 * `viewModelScope`의 자식이므로 **호출부가 취소돼도 계속 간다.** 뷰모델은 회전을 넘겨
 * 살아남으므로 사슬이 끝까지 도착하고, 호출부는 살아 있으면 결과를 받아 고지한다.
 *
 * **화면이 필요한 일은 여기 넣지 않는다** — 묻는 창·뷰 갱신은 화면이 없으면 할 수 없다.
 * 옮기는 것은 *묻기가 끝난 뒤의 쓰기*뿐이다.
 *
 * 남용하지 말 것 — 여기서 도는 일은 **호출부가 취소해도 멈추지 않는다.** 사용자가 이미
 * 지시한 쓰기에만 쓴다(`NovelCharacterApp.runDetached`가 결과를 안 받는 판이라면 이쪽은
 * 결과를 받아 고지까지 잇는 판이다).
 */
suspend fun <T> ViewModel.inViewModelScope(block: suspend () -> T): T =
    viewModelScope.async { block() }.await()
