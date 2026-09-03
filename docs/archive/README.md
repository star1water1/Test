# 과거 계획·리뷰의 근거 색인

완료된 계획과 낡은 리뷰의 사본은 제거했다. 현재 작업은 [status.md](../status.md), 규약은 [conventions.md](../conventions.md)를 따른다.
아래는 같은 논의를 반복하지 않도록 남긴 검색어와 당시 원문 링크다. 링크는 정리 전 커밋에 고정돼 있으며 현재 사양을 뜻하지 않는다.
사용자 확정의 정본은 [judgment_confirmations_2026-08.md](../judgment_confirmations_2026-08.md)이며 그대로 유지한다.

| 당시 문서 / 원문 | 남겨야 할 판단·이어받은 곳 |
|------------------|---------------------------|
| [audit_remediation_2026-07.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/audit_remediation_2026-07.md) | 무음 유실·결과 고지: 규약 R-14·R-17·R-26으로 이어짐. |
| [character_filter_sort_2026-07.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/character_filter_sort_2026-07.md) | 필터·정렬은 사용자 정의 필드도 포함. 현행 짝 맞춤은 filter_sort_parity_2026-07.md. |
| [code_review_2026-03.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/code_review_2026-03.md) | DB v25 시점 코드 리뷰. 테스트 부재 주장은 폐기됐고 잔여 지적은 백로그 B-56~B-59로 이관됨. |
| [creator_assistant_2026-07.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/creator_assistant_2026-07.md) | 어시스턴트: 핵심은 오프라인, AI는 선택적 온라인. 입력량 자체를 성과로 삼지 않음. Phase B 제안은 당시 로드맵이며 현재 작업은 status.md로 판단. |
| [design_review_2026-03.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/design_review_2026-03.md) | 구시점 UI 리뷰. 비교 모드는 이후 구현됐으며 위젯·이미지 라이브러리 권고는 당시 관측임. |
| [device_verification_passed_2026-07.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/device_verification_passed_2026-07.md) | 사용자가 통과시킨 3-1~3-4·3-16·3-17의 회귀 재현 절차. 현재 미확인 절차는 device_walkthrough.md. |
| [feature_readiness_audit.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/feature_readiness_audit.md) | 카드 재정렬·사진 확대·anotherName·설정·인앱 관리의 과거 준비도 판정. 현재 기능 부재의 근거로 쓰지 않음. |
| [fix_design_round2_2026-07.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/fix_design_round2_2026-07.md) | F-1~F-7 완료 설계. 안정 식별자 code와 복원 무결성은 규약 R-1로 이어짐. |
| [plan_duplicate_character_detect.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/plan_duplicate_character_detect.md) | 동명이인 감지 계획. 현행 폴더 왕복의 동명 오묶음 정책은 image_folder_roundtrip_design_2026-07.md C-14. |
| [plan_zip_image_export.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/plan_zip_image_export.md) | ZIP 이미지 내보내기·복원 및 항목 선택의 완료 계획. 현행 경계는 아키텍처 5-4와 엑셀 규약. |
| [result_notification_design_2026-07.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/result_notification_design_2026-07.md) | 처리 결과 알림의 완료 설계. 잘라낸 개수·빈 결과의 사유·작업 진행도는 규약 R-14·R-17·R-26. |
| [search_filter_sort_perf_2026-07.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/search_filter_sort_perf_2026-07.md) | 에폭 무효화·단계별 메모이제이션의 도입 근거. 현행 성능 판단은 scalability_performance_2026-07.md. |
| [storage_optimization_design_2026-07.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/storage_optimization_design_2026-07.md) | 자동 백업 이미지 포함 기본값은 false로 변경됨. 기기 종속 암호화 백업은 기기 이전용이 아니며 외부 내보내기와 구분. |
| [usability_fix_design_2026-07.md](https://github.com/star1water1/Test/blob/ac7057af4014717743a2547dab5d0db176c4fce7/docs/archive/usability_fix_design_2026-07.md) | D-6 기각 근거: 전 필드를 0.0으로 시험 평가해 NaN 여부로 구문 오류를 판단하면 안 됨. 잘못된 식도 유한값을 반환하므로 그 방법은 오류를 놓침. |
