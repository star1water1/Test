# 브랜치·병합 규칙 — 항상 최신 업데이트를 메인에 병합한다

## 1) 이 문서의 목적

이 저장소의 모든 작업 결과물이 **항상 메인 브랜치(`master`)에 병합**되어, master가 언제나 최신 코드의 유일한 기준(source of truth)이 되도록 하는 규칙을 정의한다. 모든 작업자(사람, Claude Code 세션 포함)는 이 규칙을 따라야 한다.

## 2) 배경 — 왜 이 규칙이 필요한가

2026-07-03 기준으로 이 저장소는 다음 문제를 갖고 있었다:

- 실제 최신 코드는 `master`에 있으나, **GitHub 기본 브랜치가 작업 브랜치인 `claude/decide-next-project-wXxtv`로 설정**되어 있었다. 이 기본 브랜치는 최신 master보다 **336커밋 뒤처진** 상태였다.
- 그 결과 새 작업(특히 새 Claude Code 세션)이 오래된 기본 브랜치에서 분기하여, **이미 수정된 버그를 다시 수정하거나 최신 기능이 빠진 코드를 기준으로 작업**하는 일이 반복되었다.
- 39개의 `claude/*` 작업 브랜치가 병합 여부가 불분명한 채 남아 있었다.

## 3) 핵심 규칙 (필수)

### 규칙 1 — `master`가 메인 브랜치다

- 이 저장소의 메인 브랜치는 `master`다. (`main` 브랜치는 존재하지 않는다.)
- **모든 최신 업데이트는 항상 master에 병합한다.** master에 병합되지 않은 작업은 완료된 것으로 간주하지 않는다.
- master는 항상 빌드 가능한 최신 상태를 유지한다.

### 규칙 2 — 작업은 반드시 최신 master에서 시작한다

새 작업 브랜치는 반드시 최신 `origin/master`에서 분기한다:

```bash
git fetch origin master
git checkout -B <작업브랜치> origin/master
```

이미 만들어진 작업 브랜치가 최신 master보다 뒤처져 있고 고유 커밋이 없다면, 작업 시작 전에 위 명령으로 master 위로 옮긴다. 고유 커밋이 있다면 `git rebase origin/master` 또는 `git merge origin/master`로 최신화한다.

**상태 판정 방법** — `git fetch origin master` 후:

```bash
git rev-list --count HEAD..origin/master   # 0보다 크면 master보다 뒤처진 상태
git rev-list --count origin/master..HEAD   # 0이면 고유 커밋 없음
```

**rebase 후 push:** 이미 원격에 push된 브랜치를 rebase했다면 `git push --force-with-lease origin <작업브랜치>`로 push한다. 작업 브랜치에 대한 force-push는 규칙 5 위반이 아니다 — 규칙 5의 금지는 master에만 적용된다.

### 규칙 3 — 작업이 끝나면 즉시 master로 병합한다

- 작업 완료 시 **base 브랜치를 `master`로 지정한 Pull Request**를 만들어 병합한다.
- **주의:** 저장소 기본 브랜치가 master가 아닌 동안에는 GitHub이 PR 생성 시 잘못된 base 브랜치를 기본값으로 제안한다. PR을 만들 때마다 base가 `master`인지 반드시 확인한다.
- 병합 방식은 **Merge commit(기본)** 을 사용한다. 커밋 이력이 지저분한 경우에는 Squash and merge를 사용해도 된다.

### 규칙 4 — 충돌은 작업 브랜치에서 해결한다

master와 충돌이 있으면, 작업 브랜치에 `origin/master`를 먼저 merge(또는 rebase)하여 충돌을 해결한 뒤 병합한다. master 쪽에서 충돌을 해결하지 않는다.

### 규칙 5 — master 직접 조작 금지

- master에 직접 push하지 않는다. 모든 변경은 PR을 통해서만 들어간다.
- master에 force-push는 어떤 경우에도 금지한다.

### 규칙 6 — 병합이 끝난 브랜치는 삭제한다

master에 병합이 완료된 작업 브랜치는 삭제하여, "어느 브랜치가 최신인지 알 수 없는" 상태가 재발하지 않도록 한다.

## 4) Claude Code 세션 규칙

Claude Code로 작업하는 경우 위 규칙을 다음과 같이 적용한다:

1. **세션 시작 시:** 지정받은 작업 브랜치가 최신 `origin/master`를 포함하는지 확인하고, 뒤처져 있으면 규칙 2에 따라 최신화한 뒤 작업한다.
2. **세션 종료 시:** 작업 브랜치를 push한 뒤, base를 `master`로 지정한 PR 생성까지가 한 세션의 완결이다. (PR 생성 권한/지시가 없는 경우, 사용자에게 "master로 병합 필요"를 명시적으로 알린다.)
3. 오래된 기본 브랜치에서 시작된 세션이라도, 결과물은 반드시 master 기준으로 병합 가능해야 한다.

## 5) 일회성 정리 작업 (이 규칙의 전제 조건)

아래 작업은 저장소 소유자가 GitHub 웹에서 수행해야 한다. 이 절의 내용은 **2026-07-03 시점의 스냅숏**이다 — 각 항목을 완료하면 상태 표시를 `완료(날짜)`로 갱신하고 문서 이력에 기록한다:

### 5-1. GitHub 기본 브랜치를 `master`로 변경 — **가장 중요** `[상태: 완료 (2026-07-03)]`

**Settings → General → Default branch → `master`로 변경.** — 2026-07-03 완료, 원격 HEAD가 `refs/heads/master`를 가리키는 것을 확인함.

이 설정이 바뀌지 않으면 새 세션·새 PR이 계속 낡은 브랜치를 기준으로 생성되어 이 규칙 전체가 무력화된다. (이 변경은 git 명령으로 불가능하며 저장소 설정에서만 가능하다.)

### 5-2. 미병합 브랜치 7개 처리 `[상태: 조치 완료 (2026-07-03) — 브랜치 삭제만 남음]`

> **조치 결과:** `claude/fix-code-bugs-clTCB`는 PR #10으로 master에 병합 완료. 아래의 유효 수정 10건은 최신 master 기준으로 재작성되어 PR #11로 병합 완료(CI 빌드 통과 확인). 이제 7개 브랜치 모두 살릴 내용이 남아 있지 않으므로 5-3과 함께 삭제하면 된다.

2026-07-03 기준, master에 없는 고유 커밋을 가진 브랜치는 아래 7개다. 각 브랜치의 수정 내용을 현재 master 코드와 전수 대조하고(브랜치별 분석 + 독립 재검증 2단계), 충돌을 `git merge-tree`로 테스트한 결과:

| 브랜치 | 고유 커밋 | 판정 | 근거 요약 |
|--------|-----------|------|-----------|
| `claude/fix-code-bugs-clTCB` | 1 | **병합** | 현재 master HEAD에서 분기. 유일한 수정(CharacterViewModel `newValue` 누락 — 출생/사망 연도 수정 시 나이 오표시)이 여전히 유효하고 충돌 없음(fast-forward 가능) |
| `claude/code-review-GpR1S` | 3 | **선별 재적용 후 삭제** | 수정 16건 중 유효 8건 / 이미 반영 5건 / 대체됨 1건 / **적용 시 회귀 유발 2건**. 9개 파일 충돌 → 통째 병합 금지 |
| `claude/general-code-review-D7RIN` | 1 | **선별 재적용 후 삭제** | 수정 7건 중 유효 2건(Excel 가져오기 파일 크기 검사 우회, 내보내기 하드코딩 문자열)뿐. 4개 파일 충돌 |
| `claude/code-review-5rddC` | 5 | **삭제** | 수정 25건 중 24건이 이미 master에 동일하거나 더 나은 형태로 반영, 1건은 브랜치 내에서 스스로 되돌린 것. 24개 파일 충돌 |
| `claude/code-review-W3FT4` | 1 | **삭제** | 두 문제 모두 master가 더 나은 방식으로 해결(테마 설정 보존 방식 ANR 수정 등). 브랜치안 적용 시 오히려 회귀(설정 유실, bitmap.recycle 크래시) |
| `claude/review-project-status-Tzh8W` | 3 | **삭제** | 수정 18건 중 16건 이미 반영, 남은 것은 실익 없는 마이크로 최적화 2줄뿐. 19개 파일 충돌 |
| `claude/sprint-2-implementation-e8g5R` | 1 | **삭제** | 고유 커밋이 불필요한 merge commit 1개뿐. 브랜치 트리가 master에 이미 포함(병합해도 변화 0) |

**유효 수정 목록 (낡은 브랜치를 병합하지 말고 최신 master 위에 새로 재적용할 것):**

- GpR1S에서 8건: FormulaEvaluator 이중 부정 버그(`--3`이 `-3`으로 계산됨), FieldManageFragment 드래그 순서 저장 race, TimelineFragment error LiveData 미관찰(에러가 조용히 사라짐), MainActivity 하단 네비 크래시 가드(기존 `navigateSafe` 유틸 재사용), NameBankFragment·FieldEditDialog 검증 실패 시 다이얼로그 유지(입력 유실 방지), TimeSliderHelper 동시 실행 Job 취소, AutoBackupWorker rotateBackups 실패 분리
- D7RIN에서 2건: ExcelImporter 파일 크기 `-1L`일 때 ZIP bomb 크기 제한 우회, ExcelExporter 검증 박스 하드코딩 문자열 외부화

> 주의: 위 7개 브랜치는 최대 405커밋 낡은 코드 기준이므로 rebase/병합하면 이미 개선된 master 코드를 되돌리는 회귀가 발생한다. clTCB를 제외하고는 브랜치 자체를 살리지 말고, 유효 수정만 master 기준으로 새로 작성한다.

### 5-3. 이미 병합된 브랜치 삭제 `[상태: 검증 완료 (2026-07-03) — 소유자 실행 대기]`

> **2026-07-03 재검증 결과:** 원격의 `claude/*` 브랜치 42개 전부가 삭제 안전 판정. Claude Code 원격 환경의 git 프록시가 브랜치 삭제 push를 차단(403)하므로, 삭제는 **저장소 소유자가 로컬 또는 GitHub 웹**(Branches 페이지의 휴지통 버튼)에서 실행해야 한다.

**소유자 실행용 일괄 삭제 명령 (로컬 클론에서):**

```bash
git push origin --delete \
  claude/add-feature-audit-docs-IEJoE claude/check-repo-status-x0ZGj claude/code-review-4AWIG \
  claude/code-review-5vDrS claude/code-review-BYF96 claude/code-review-Qwbrw \
  claude/code-review-audit-3I54e claude/code-review-audit-jvTTz claude/code-review-fixes-tCBvo \
  claude/code-review-lDBLu claude/code-review-lklof claude/code-review-session-z4dXq \
  claude/decide-next-project-wXxtv claude/excel-integration-refactor-4jY1w claude/fix-bugs-DHYJA \
  claude/fix-bugs-and-errors-EYrbg claude/fix-bugs-and-errors-p4HXB claude/fix-bugs-ypCEq \
  claude/fix-code-bugs-2k2gI claude/fix-code-bugs-BTbwL claude/fix-code-bugs-F28Yo \
  claude/fix-code-bugs-RmTHB claude/fix-code-bugs-U9V3K claude/fix-code-bugs-UVKKC \
  claude/fix-code-bugs-clTCB claude/fix-code-bugs-eyArv claude/fix-startup-crash-9GjCN \
  claude/main-merge-update-rules-yv8wrk claude/project-review-XHj6Y claude/project-review-odoSj \
  claude/reapply-valid-review-fixes claude/review-codebase-ukmPz claude/review-design-feedback-SjaTO \
  claude/review-errors-IZIR3 claude/review-principles-code-wtnGC claude/usability-review-fixes \
  claude/code-review-5rddC claude/code-review-GpR1S claude/code-review-W3FT4 \
  claude/general-code-review-D7RIN claude/review-project-status-Tzh8W \
  claude/sprint-2-implementation-e8g5R claude/branch-cleanup-record
```

**삭제 근거 및 복구용 스냅숏 (2026-07-03 tip 기준):**

- 36개는 모든 커밋이 master에 완전 포함(`git rev-list --count origin/master..<브랜치> = 0`) — 내용 유실 없음.
- 고유 커밋 보유 6개는 5-2 검토(분석+독립 재검증)에서 "이미 반영/대체됨/재작성 완료" 판정을 받았고, 삭제 직전 재검증에서 **tip이 검토 시점과 동일**함을 확인 — 검토 이후 새 커밋 없음:
  `code-review-5rddC@00e1fb0`, `code-review-GpR1S@6a0942f`, `code-review-W3FT4@94748bd`, `general-code-review-D7RIN@46cd503`, `review-project-status-Tzh8W@41ba5bd`, `sprint-2-implementation-e8g5R@7936dba`
- 복구가 필요하면 위 SHA로 브랜치를 재생성하면 된다 (GitHub는 미도달 커밋을 약 90일 보관).

## 6) 요약 (한 줄 규칙)

> **최신 master에서 시작하고, 끝나면 master로 병합하고, 병합한 브랜치는 지운다.**

---

## 문서 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|-----------|
| v1.0 | 2026.07.03 | 초기 작성 — 병합 규칙 6개, Claude Code 세션 규칙, 일회성 정리 작업 수록 |
| v1.1 | 2026.07.03 | 5-1 완료 표시 — GitHub 기본 브랜치가 master로 변경됨 (원격 HEAD 확인) |
| v1.2 | 2026.07.03 | 5-2 조치 완료 표시 — clTCB 병합(PR #10), 유효 수정 10건 재적용 병합(PR #11) |
| v1.3 | 2026.07.03 | 5-3 검증 완료 — 42개 전 브랜치 삭제 안전 판정, 복구 스냅숏·일괄 삭제 명령 수록 (환경 제약으로 삭제 실행은 소유자 몫) |
