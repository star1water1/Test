# 브랜치·병합 규칙

사람·Codex·Claude 등 모든 작업자에게 적용하는 Git 절차다. 작업 범위와 검증 기준은
[AGENTS.md](../AGENTS.md)를 따른다. 과거 브랜치 정리 기록은 Git 이력에 있으며, 현행 작업 절차가 아니다.

## 1. 기준 브랜치

- 기본 브랜치와 최신 코드의 기준은 `master`다.
- 새 작업은 최신 `origin/master`에서 시작한다. 오래된 `claude/*` 등 다른 작업 브랜치를 기준으로 삼지 않는다.
- `master`에 직접 push하거나 force-push하지 않는다. 모든 변경은 PR을 거친다.

## 2. 작업 시작·최신화

먼저 미커밋 변경과 진행 중인 작업을 확인한다. 사용자 변경을 버리거나 기존 브랜치를 강제로 재설정하지 않는다.
새 작업 브랜치에는 목적이 드러나는 이름을 사용한다(`codex/...` 등).

```bash
git status --short
git fetch origin master
git switch -c codex/task-name origin/master  # task-name을 실제 작업 이름으로 바꾼다
```

진행 중인 작업을 이어받았다면 새로 분기하지 않고 현재 브랜치를 먼저 확인한다:

```bash
git rev-list --count HEAD..origin/master   # 현재 브랜치가 origin/master보다 뒤처진 커밋 수
git rev-list --count origin/master..HEAD   # 현재 브랜치의 고유 커밋
```

최신화가 필요하면 작업 브랜치에 `origin/master`를 merge하거나 적절한 경우 rebase한다.
충돌은 작업 브랜치에서 해결하고 변경 누락과 회귀를 검토한다.
이미 push한 자신의 작업 브랜치를 rebase했다면 다른 작업자의 변경 여부를 확인한 뒤
`--force-with-lease`를 사용한다. 이 예외는 `master`에는 적용하지 않는다.

## 3. PR·병합·완료

- 완료한 변경은 base를 **`master`**로 지정해 PR을 만든다.
- PR에는 변경 이유·결과와 실행한 검증·미검증 항목을 적는다.
- 앱 코드 변경은 관련 CI가 초록인지 확인한다. 문서 전용 변경은 문서 검증으로 확인하며, 경로 필터에 의한 CI 미실행을 통과로 보고하지 않는다.
- 사용자 요청이 PR 작성·검토까지만이라면 그 범위를 따른다. 병합까지 맡긴 작업은 검증된 PR을 병합한다. 새 권한이나 결정이 필요한 경우에만 구체적인 사유와 함께 묻는다.
- 병합은 기본적으로 Merge commit을 사용하고, 이력 정리가 필요하면 Squash and merge도 가능하다.
- 병합 직전 PR head가 검토한 커밋인지 확인하고, 병합 후 `master` 반영을 확인한다.
- 병합한 작업 브랜치는 고유한 미병합 변경이 없는 것을 확인한 뒤 삭제한다.

실기기 확인은 `docs/device_walkthrough.md`의 별도 큐다. 기기 결과를 확인하지 않았다면 그 사실을 남긴다.
