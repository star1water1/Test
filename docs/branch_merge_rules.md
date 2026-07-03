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

### 5-1. GitHub 기본 브랜치를 `master`로 변경 — **가장 중요** `[상태: 미완료]`

**Settings → General → Default branch → `master`로 변경.**

이 설정이 바뀌지 않으면 새 세션·새 PR이 계속 낡은 브랜치를 기준으로 생성되어 이 규칙 전체가 무력화된다. (이 변경은 git 명령으로 불가능하며 저장소 설정에서만 가능하다.)

### 5-2. 미병합 브랜치 7개 처리 `[상태: 미완료]`

2026-07-03 기준, master에 없는 고유 커밋을 가진 브랜치는 아래 7개다. 내용을 검토하여 유효하면 master로 병합하고, 이미 다른 경로로 반영되었거나 폐기 대상이면 삭제한다:

| 브랜치 | 고유 커밋 | 내용 | 권고 |
|--------|-----------|------|------|
| `claude/code-review-5rddC` | 5 | 코드 리뷰 버그 수정 (1~5차, 총 39건) | 검토 후 병합 |
| `claude/code-review-GpR1S` | 3 | 데이터 유실/크래시/race condition 수정 | 검토 후 병합 |
| `claude/code-review-W3FT4` | 1 | ThemeHelper ANR·비트맵 메모리 누수 수정 | 검토 후 병합 |
| `claude/fix-code-bugs-clTCB` | 1 | CharacterViewModel newValue 누락 수정 | 검토 후 병합 |
| `claude/general-code-review-D7RIN` | 1 | 보안 검증·에러 처리·문자열 외부화 | 검토 후 병합 |
| `claude/review-project-status-Tzh8W` | 3 | Critical/High/Medium 29건 수정 + .gitignore | 검토 후 병합 |
| `claude/sprint-2-implementation-e8g5R` | 1 | PR #6 merge commit뿐 (내용은 master에 이미 반영) | 삭제 가능 |

> 주의: 이 브랜치들은 서로 다른 시점의 코드를 기준으로 하므로, 병합 시 충돌이 나거나 이미 다른 커밋으로 해결된 문제를 다시 건드릴 수 있다. 병합 전 반드시 최신 master 기준으로 rebase하고 내용의 유효성을 확인한다.

### 5-3. 이미 병합된 브랜치 삭제 `[상태: 미완료]`

위 7개를 제외한 나머지 `claude/*` 브랜치 32개는 모든 커밋이 이미 master에 포함되어 있으므로 안전하게 삭제할 수 있다. 기본 브랜치를 master로 변경한 후(5-1 완료 후) `claude/decide-next-project-wXxtv`도 삭제 대상에 포함된다.

## 6) 요약 (한 줄 규칙)

> **최신 master에서 시작하고, 끝나면 master로 병합하고, 병합한 브랜치는 지운다.**

---

## 문서 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|-----------|
| v1.0 | 2026.07.03 | 초기 작성 — 병합 규칙 6개, Claude Code 세션 규칙, 일회성 정리 작업 수록 |
