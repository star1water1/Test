package com.novelcharacter.app.util

/**
 * 회전으로 다시 세워진 실루엣 편집기를 **닫아도 되는가** — 판정 한 자리 (B-201의 셋째 갈래).
 *
 * 편집기는 체형 필드에 묶여 있어, 다시 선 폼에 그 필드가 없으면 되쓸 자리가 없으므로 닫는다.
 * 그런데 *"폼에 없다"*는 **폼이 이 캐릭터의 것으로 다 선 뒤에만** 참말이다. 회전 복원에서
 * 작품 스피너의 첫 콜백(0번)은 **전역 필드 폼**을 먼저 짓고(B-267이 적어 둔 부트스트랩
 * 콜백 — 템플릿이 전역 필드를 심으므로 사실상 늘 비어 있지 않다), 그 폼에는 세계관의 체형
 * 필드가 없다. 종전 판정은 그 폼을 보고 닫아 **회전 한 번에 끌어 만든 수치가 사라졌다**
 * (S6 에뮬레이터 2026-11-08 — 앱을 새로 띄운 뒤의 회전에서는 거의 매번).
 *
 * B-259가 같은 콜백에서 *"없는 칸"*을 잘못 말하던 것을 **저장 게이트가 열린 뒤**로 미뤄
 * 고쳤다. 같은 신호를 쓴다: 게이트가 열리기 전에는 닫지 않고, 열리는 순간 한 번 더 본다.
 */
object SilhouetteRebindRule {

    /**
     * @param awaitingRestore 시트가 되살릴 것을 들고 아직 세워지지 않았다
     * @param fieldInForm 시트의 체형 필드가 지금 폼에 그려져 있다
     * @param formHasFields 지금 폼의 필드 목록이 비어 있지 않다(아직 조회 중인 빈 목록과 가른다)
     * @param formSettled 폼이 이 캐릭터의 작품으로 다 섰다(호스트의 저장 게이트)
     */
    fun shouldDismiss(
        awaitingRestore: Boolean,
        fieldInForm: Boolean,
        formHasFields: Boolean,
        formSettled: Boolean
    ): Boolean = awaitingRestore && !fieldInForm && formHasFields && formSettled
}
