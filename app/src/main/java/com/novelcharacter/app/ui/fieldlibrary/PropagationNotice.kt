package com.novelcharacter.app.ui.fieldlibrary

import android.content.Context
import com.novelcharacter.app.R
import com.novelcharacter.app.data.repository.FieldValueLibraryRepository.PropagationReport

/**
 * **가리지 못해 손대지 않은 상태변화 이력**의 고지 — 값 전파를 말하는 모든 자리의 단일 소스 (R-33).
 *
 * 값 이름변경·병합·삭제는 미분류 캐릭터(`novelId IS NULL`)의 값까지 고쳐 쓰지만, 그 캐릭터의
 * **이력 중 소속 세계관을 가릴 수 없는 몫**은 손대지 않는다(`UnassignedHistoryScope`).
 * 유실이 아니라 *옛 표기로 남는 것*이고, 말하지 않으면 사용자는 자기 연표 일부가 값과 어긋난
 * 것을 영영 모른다(개발 의도 2번 — 조용히 버리지 않는다).
 *
 * **자리가 다섯이라 함수로 모은다**(이름변경 확인 · 병합 확인 · 삭제 확인 · AI 정리 드라이런 ·
 * 실행 결과 알림). 문구를 자리마다 적으면 한 곳만 고쳐져 같은 수가 다른 말을 한다(R-51).
 */
fun Context.unattributedHistoryNotice(report: PropagationReport): String =
    if (report.unattributedHistoryCharacters > 0)
        getString(R.string.field_library_propagation_unattributed, report.unattributedHistoryCharacters)
    else ""

/**
 * 확인 다이얼로그 본문에 덧붙이는 꼴 — 앞 문장과 붙지 않게 줄을 바꾼다.
 *
 * 0건이면 **빈 문자열**이라 정상 조작에는 아무것도 붙지 않는다(원칙 04 — 잔소리를 달지 않는다).
 */
fun Context.unattributedHistoryLine(report: PropagationReport): String =
    unattributedHistoryNotice(report).let { if (it.isEmpty()) "" else "\n$it" }
