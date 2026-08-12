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
 * 결과를 Toast로만 알린다(이력은 남기지 않는다) — **이력을 이미 남겼거나 따로 남길 때**의
 * 고지 경로다(B-164). Toast는 뷰 계층과 무관하게 도달하므로 **화면이 이미 사라진 뒤**에도 닿는다.
 *
 * [toastAndLogResult]와 갈라 두는 이유: 이력을 *먼저* 남기고 고지를 *나중에* 하는 순서가
 * 필요한 자리가 있다. 그 순서라야 **고지 경로가 무엇을 하든 기록이 선다** — 묶여 있으면
 * 고지가 실패하는 순간 기록도 함께 없어진다.
 * 메인 스레드에서 부를 것(viewModelScope 기본 디스패처면 충분하다).
 */
fun AndroidViewModel.toastResult(result: OpResult) {
    val app = getApplication() as NovelCharacterApp
    android.widget.Toast.makeText(app, result.summary, android.widget.Toast.LENGTH_LONG).show()
}

/**
 * 결과를 Toast로 즉시 알리고 작업 이력에도 기록한다. 다이얼로그 저장처럼 **고지 시점에
 * 호출 화면의 뷰가 이미 사라졌을 수 있는 조작**의 부가 고지용 — Toast는 뷰 계층과 무관하게
 * 도달한다(CharacterSaveCoordinator.notifyPreservedFieldValues와 같은 사유).
 * 메인 스레드에서 부를 것(viewModelScope 기본 디스패처면 충분하다).
 */
fun AndroidViewModel.toastAndLogResult(result: OpResult) {
    toastResult(result)
    logResult(result)
}

/**
 * 결과 채널을 갖는 ViewModel용 공통 인터페이스(선택적).
 * 구현하면 Fragment가 result/clearResult를 일관되게 관측할 수 있다.
 */
interface HasResultChannel {
    val result: androidx.lifecycle.LiveData<OpResult?>
    fun clearResult()
}
