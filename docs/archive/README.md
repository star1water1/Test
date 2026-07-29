# 보관 문서 (archive)

여기 있는 문서는 **더 이상 현행 사양이 아니다.** 계획이 이미 구현됐거나, 특정 시점의 리뷰라
전제(코드 규모·DB 버전·기능 유무)가 낡았다.

## 규칙

1. **여기 있는 문서를 근거로 착수하지 않는다.** 각 문서 머리의 보관 헤더가 "무엇이 낡았는지"와
   "무엇이 그것을 이어받았는지"를 적어 둔다. 헤더부터 읽을 것.
2. **살아 있는 지적은 백로그로 옮겨져 있다** — 옮길 때는 반드시 현행 코드로 실재를 재확인하고,
   확인한 날짜와 근거 위치를 백로그 항목에 적는다. 재확인하지 않은 항목은 옮기지 않으며,
   그런 항목이 남아 있다는 사실을 헤더에 명시한다.
   *(이 저장소의 규칙: 문서나 에이전트의 지적은 보조이지 근거가 아니다.)*
3. **지우지 않는다.** "그때 무엇을 하려 했는가"와 "무엇이 왜 바뀌었는가"는 나중에 같은 논의를
   반복하지 않기 위해 필요하다.

   > **이 규칙은 한 번 시험을 거쳤다(2026-07-28).** "끝난 건 지우자"는 제안에 대해 실측한 결과,
   > 로드맵 5 세션이 근거로 쓴 `usability_fix_design`의 **피참조는 0**이었다 — 링크가 아니라
   > `grep`에 우연히 걸려 찾았다. 그 문서가 없었다면 D-6이 이미 반려한 방식("전 필드 0.0으로
   > 시험 평가해 NaN이면 오류")을 다시 제안했을 것이다. git은 지운 파일을 보관하지만
   > **아무도 지운 파일을 grep하지 않는다.**
   >
   > 그래서 판정 기준은 "끝났는가"가 아니라 **"작업 지시서인가, 결정 근거인가"**다.
   > 전자는 끝나면 값이 0이고, 후자는 끝나도 남는다.

## 목록

| 문서 | 원래 위치 | 상태 | 이어받은 곳 |
|------|-----------|------|-------------|
| `plan_zip_image_export.md` | 루트 `PLAN.md` | **구현 완료** | `excel_roundtrip_audit_2026-07.md` · `architecture_2026-07.md` 5-4 |
| `plan_duplicate_character_detect.md` | 루트 `plan.md` | **구현 완료** | `image_folder_roundtrip_design_2026-07.md`(동명 판정이 여기서 다른 의미를 얻었다) |
| `code_review_2026-03.md` | 루트 `CODE_REVIEW.md` | 2026-03-21 시점 리뷰 — 전제 낡음(122파일·DB v25 / 현행 328파일·v44) | `architecture_2026-07.md` · `scalability_performance_2026-07.md` · 백로그 B-56~B-59 |
| `design_review_2026-03.md` | 루트 `DESIGN_REVIEW.md` | 2026-03 시점 리뷰 — 일부 해결·일부 유효 | `usability_review_2026-07.md` · `ai_control_and_ui_density_2026-07.md` · `text_style_guide_2026-07.md` |
| `usability_fix_design_2026-07.md` | `docs/` | **D-1~D-N 구현 완료** (마지막 D-6, 2026-07-28) | 원본 검토는 `usability_review_2026-07.md`(현행 유지) |
| `result_notification_design_2026-07.md` | `docs/` | **구현 완료** — `util/ResultNotify`·`ResultReporting` | 규약 R-14·R-17·R-26 (색인: `architecture_2026-07.md` 6장) |
| `creator_assistant_2026-07.md` | `docs/` | **Phase A 구현 완료** — `ui/assistant/` 8파일 | `ai_integration.md` · `ai_control_and_ui_density_2026-07.md`. Phase B는 백로그 |
| `character_filter_sort_2026-07.md` | `docs/` | **구현 완료** — `FieldFilterHelper`·`SortComparators` | `filter_sort_parity_2026-07.md`(짝 맞춤) · `archive/search_filter_sort_perf`(성능) |
| `search_filter_sort_perf_2026-07.md` | `docs/` | **구현 완료** — 에폭 메모이제이션(`CharacterViewModel`) | `scalability_performance_2026-07.md` 부록 |
| `storage_optimization_design_2026-07.md` | `docs/` | **구현 완료** — `StorageAnalyzer`, 백업 이미지 기본값 `true`→`false` | `scalability_performance_2026-07.md` 부록 |
| `fix_design_round2_2026-07.md` | `docs/` | **F-1~F-7 구현 완료** — 안정 식별자 `code` 도입(DB v34→v35) | 원본 점검 `app_inspection_round2_2026-07.md`(현행 유지) · 규약 R-1 |
| `audit_remediation_2026-07.md` | `docs/` | **PR-F1~F3 구현 완료** — 무음 유실 3건 등 | 규약 R-14·R-17 · `superficial_feature_audit_2026-07.md` |
| `feature_readiness_audit.md` | `docs/` | 시점 준비도 리뷰 — 점검 대상 5종 전부 구현됨 | `usability_review_2026-07.md` · `superficial_feature_audit_2026-07.md` |

## 왜 루트에서 옮겼는가

루트에 있는 문서는 **저장소의 진입점처럼 읽힌다.** 네 문서 모두 그 자리에 있으면서
현재형으로 말하고 있었고, 그중 `code_review_2026-03.md`의 *"테스트 파일 미발견 →
테스트 추가 필수"* 한 줄은 실제로는 테스트 68파일·순수 JVM 882건이 도는 지금
**정반대의 사실**을 안내하고 있었다.

부수적으로 파일시스템 문제도 함께 해소됐다 — 루트에 `PLAN.md`와 `plan.md`가 대소문자만
다르게 공존해, 대소문자를 구분하지 않는 파일시스템(macOS 기본 APFS·Windows)에서는
체크아웃이 충돌한다.

이제 루트의 `.md`는 `CLAUDE.md` 하나이며, 그 문서의 '어디서부터 읽는가' 표가 진입점이다.
