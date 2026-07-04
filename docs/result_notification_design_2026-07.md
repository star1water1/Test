# 데이터 처리 결과 알림 설계서 (2026-07)

> 요청: "모든 데이터 처리 기능에 결과를 알려주는 기능을 추가."
> 판단 기준: CLAUDE.md **변수 제어 원칙** — 검증 → 알림 → 교정 경로, 데이터 무통보 유실 금지.
> 계획서: `~/.claude/plans/smooth-popping-mochi.md`

## 배경 (조사 결과)

3개 영역 병렬 조사로 갭을 규명했다:
- **완전 침묵(성공·실패 무통보)**: 세계관/작품/세력 생성·편집, 프리셋 이름 편집, 재정렬, 관계 순서변경, 관계변화 삭제, 이름은행 사용처리/해제.
- **성공만 조용**: 대부분의 삭제, 필드/관계 CRUD, 사건 저장/편집/삭제, 이름은행 CRUD.
- **IO 공백**: 월드패키지·PDF·카드 공유 성공 침묵, 엑셀 내보내기 건수 요약 없음, 휴지통 파기/비우기 완전 침묵, 배치 건수 부정확, 자동 백업 백그라운드 실패 능동 통지 없음.
- **인프라 부재**: 공통 알림 유틸 없음(인라인 137건), ViewModel→UI 결과 전달 4패턴 혼재, `Event<T>` 중복.

이미 모범적인 곳(엑셀 가져오기 `ImportResult`+다이얼로그, 휴지통 복원 `RestoreResult`, 유지보수, 저장 정리, 세력 멤버추가 건수)은 유지·재사용.

## 사용자 확정 결정

1. **범위**: 의미있는 조작은 성공 알림 + 모든 조작의 실패는 항상 알림. 재정렬 드래그·핀토글 등 초고빈도는 성공 알림 생략(실패만) — 원칙 04(조작 마찰 최소화).
2. **방식**: 즉시 알림(Toast/Snackbar) + 작업 이력 화면(Room 저장).
3. **자동 백업**: 실패 시 시스템 푸시 알림.

## 아키텍처 (3계층 재사용)

- **Layer 1 공통 인프라**: `util/Event.kt`(SingleLiveEvent 승격), `util/OperationResult.kt`(`OpResult(category, summary, success, detail)` + 카테고리 상수), `util/ResultNotify.kt`(`Fragment.notifyResult/notifySuccess/notifyError` — 생명주기 안전 Snackbar+Toast 폴백+상세 다이얼로그), `util/ResultReporting.kt`(`AndroidViewModel.reportResult(channel, result)` — 알림 채널 emit + 이력 기록을 한 번에).
- **Layer 2 결과 채널 표준화**: 각 데이터 ViewModel에 `result: LiveData<OpResult?>` + `clearResult()`(FieldViewModel `saveError/saveInfo` 패턴 일반화). 조작 완료(suspend 반환) 이후 `reportResult` 호출(낙관적 오탐 방지). Fragment는 `result.observe{ notifyResult(it); clearResult() }`.
- **Layer 3 작업 이력(신규, DB v35→v36)**: `OperationLog` 엔티티(id/category/summary/detail/success/createdAt, 상한 200) + DAO(RecentActivity 템플릿) + `OperationLogRepository`(logAsync fire-and-forget) + `OperationHistoryFragment`(설정>데이터관리>작업 이력, 성공/실패 점·카테고리·상대시각, 상세 다이얼로그, 이력 비우기).
- **Layer 4 자동 백업 실패 알림**: `NotificationHelper.showBackupFailedNotification` + 전용 채널, `AutoBackupWorker` 최종 실패 시 호출 + 이력 기록.

## 범위 규칙 (구현 기준)

| 조작 유형 | 성공 알림 | 실패 알림 | 이력 기록 |
|-----------|:---------:|:---------:|:---------:|
| 의미있는 조작(CRUD/가져오기/내보내기/일괄/복원/정리) | O | O | O |
| IO/공유(엑셀·월드패키지·PDF·카드) | O(건수) | O | O |
| 초고빈도(재정렬 드래그·핀토글·폼내 이미지) | X | O | 실패만 |

## 재사용/미사용
- 재사용: `FieldViewModel.saveError/saveInfo` 채널, `RecentActivity` 엔티티(이력 템플릿), `ExcelImporter` 결과 다이얼로그 구조, 건수 표기(`FactionManageFragment` addMembers).
- 미사용: `AppLogger`(오류 전용), `RecentActivity`(최근 열람 전용 — 신규 `OperationLog` 사용).

## 구현 배치 (모두 완료)
1. **인프라**: Layer 1~3 + 이력 화면·설정 진입. DB v36.
2. **Layer 4** 자동 백업 시스템 알림.
3. **Layer 5 갭 메우기**:
   - **5-a 세계관/작품/세력/프리셋**: `UniverseViewModel`·`NovelViewModel`·`FactionViewModel`에 result 채널. CRUD 성공+실패, 재정렬·핀토글 failure-only. 프리셋 복원/저장/수정/삭제/적용, 세력 멤버 추가(이력만)/제거/탈퇴. `UniverseListFragment`의 낙관적 Toast 3건 제거→실제 결과·건수 통보.
   - **5-b 필드/관계/상태변화/캐릭터삭제**: `FieldViewModel` `saveError/saveInfo`→`result` 일원화(키변경 자동교정 상세 노출). `CharacterViewModel`에 result 채널 — 관계·관계변화·상태변화 CRUD·캐릭터 삭제 성공+실패, 재정렬·핀토글 failure-only. `StateChangeHelper` 낙관적 Toast 제거→실제 완료 후 통보.
   - **5-c 사건/이름은행**: `TimelineViewModel` `_error`→`result` 일원화(showError 실패 라우팅, 자동클리어 타이머 제거, 실패도 이력 기록), 사건 저장/수정/이동/삭제·표준연도 변경 성공. `NameBankViewModel` 추가/수정/삭제·사용처리/해제.
   - **5-d IO/배치/휴지통**: `TrashRepository.emptyTrash()` 건수 반환, `TrashFragment` 영구삭제·비우기 성공(건수)/실패 통보 + 복원 이력. `BatchEditViewModel` 시맨틱 동기화 부분 실패 집계(`syncFailures`)→경고 승격 + 배치 이력. `ExcelExporter` 시트/행 요약 + 내보내기·저장 이력. PDF 공유 이력. (월드패키지·카드 공유는 UI 미연결/미존재로 대상 아님.)

### ViewModel 없는 Fragment 직접 조작 지원
`util/ResultNotify.kt`에 `Fragment.reportAndNotify(result)`(알림+이력)와 `Fragment.logOperation(result)`(이력만) 추가 — TrashFragment·ExcelExporter·PDF 공유 등 AndroidViewModel을 거치지 않는 IO/공유/휴지통 조작용. `ExcelExporter`는 `appContext`를 `NovelCharacterApp`으로 캐스팅해 `operationLogRepository.logAsync` 직접 호출.

### 중복 알림 방지 원칙
자체 Toast/Event로 이미 성공을 알리는 조작(프리셋 적용, 세력 멤버추가, 휴지통 복원)은 `logResult`/`logOperation`으로 **이력만** 추가하고 즉시 알림은 기존 경로가 담당. result 채널로 통보하는 조작은 낙관적 Toast를 제거해 실제 성공/실패를 정확히 반영.

## 문서 이력
| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026.07.04 | 초기 — 조사·설계·범위 규칙, 인프라(Layer 1~3) 구현 |
| v1.1 | 2026.07.04 | Layer 4·5 전 계층 구현 완료 기록 — 7개 ViewModel result 채널, Fragment 직접 조작용 헬퍼, 낙관적 Toast 정리 |
