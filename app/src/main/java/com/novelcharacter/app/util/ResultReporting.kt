package com.novelcharacter.app.util

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.novelcharacter.app.NovelCharacterApp

/**
 * ViewModel이 데이터 처리 결과를 한 번에 (a) UI 알림 채널로 emit하고 (b) 작업 이력에 기록하도록
 * 묶은 헬퍼. 각 ViewModel에 이력 기록을 중복 배선하지 않기 위한 단일 진입점이다.
 *
 * 반드시 조작 완료(suspend 반환) 이후에 호출한다 — fire-and-forget 완료 전 낙관적 알림을 피한다.
 */
fun AndroidViewModel.reportResult(channel: MutableLiveData<OpResult?>, result: OpResult) {
    channel.postValue(result)
    (getApplication() as NovelCharacterApp).operationLogRepository.logAsync(result)
}

/**
 * 작업 이력에만 기록한다(즉시 알림은 하지 않음). 이미 자체 Toast/Event로 성공을 알리는 조작이
 * 이력 완성도를 위해 기록만 추가할 때 사용한다 — 중복 알림 방지.
 */
fun AndroidViewModel.logResult(result: OpResult) {
    (getApplication() as NovelCharacterApp).operationLogRepository.logAsync(result)
}

/**
 * 결과 채널을 갖는 ViewModel용 공통 인터페이스(선택적).
 * 구현하면 Fragment가 result/clearResult를 일관되게 관측할 수 있다.
 */
interface HasResultChannel {
    val result: androidx.lifecycle.LiveData<OpResult?>
    fun clearResult()
}
