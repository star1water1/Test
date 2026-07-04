# 저장 공간 진단·최적화 설계서 (2026-07)

> 사용자 보고: 앱 용량이 ~3GB. 이미지는 500MB 선. 나머지 ~2.5GB의 정체 규명 + 인앱 가시화 + 최적화.
> 판단 기준: CLAUDE.md 개발 의도 — ①받쳐주는 확장성 ②변수 제어 ③자율성 우선 ④엑셀 왕복 무결성.
> 방침: 핵심 원칙·기능 저해 금지. 데이터 무손실. 사용자가 자율적으로 통제·되돌릴 수 있게.

## 1. 진단 (실코드 근거)

### S-1. 자동 백업 이미지 포함 (최대 원인, ~1.5GB)
- `BackupSettingsStore`: **기본값 `DEFAULT_INCLUDE_IMAGES=true`, `DEFAULT_MAX_BACKUPS=3`**.
- `AutoBackupWorker.saveEncryptedBackup`(83-123)이 매일 `ImageZipHelper.wrapWithImages`로 **전 이미지를 원본 해상도 그대로** ZIP에 담고(다운스케일 없음, ImageZipHelper 전체) 암호화해 `filesDir/backups/*.enc`에 저장.
- 로테이션은 `maxBackups`개까지 보존(`rotateBackups` 125-146). 즉 500MB 이미지 × 3벌 ≈ **~1.5GB가 상주**.
- **구조적 낭비**: .enc 백업은 이미지가 든 `filesDir` **안에** 저장된다. filesDir가 날아가면 백업도 함께 날아가므로 이미지 포함은 filesDir 손실에 대한 추가 보호가 **전무**. 기기 이전은 기기 종속 암호화라 불가하고 '이미지 포함 내보내기'가 담당(D-2/D-3). → 자동 백업 이미지 포함은 보호를 늘리지 않으면서 용량만 먹는 순수 낭비.

### S-2. 내보내기/공유 캐시 (0~1.5GB)
- `ExcelExporter.saveWorkbook`(335-343): `cacheDir/exports`에 저장, **최근 3개만 보존**(`.drop(3)`). 이미지 포함 내보내기는 500MB짜리 `.zip`이라 최대 3×500MB.
- 같은 디렉토리를 캐릭터 카드 PNG(`CharacterDetailFragment:880`)·월드패키지(`WorldPackageExporter:82`)가 공유. saveWorkbook의 `.drop(3)`은 엑셀 내보내기 시에만 실행되어, 그 사이 다른 산출물이 쌓일 수 있음.
- cacheDir는 OS가 캐시로 분류하나 사용자가 보는 '앱 용량'에는 포함됨.

### S-3. 고아 이미지 파일 (상한 없음, 가변)
- 이미지는 `filesDir` 루트에 플랫 저장: `char_<UUID>.jpg` / `universe_<UUID>.jpg` / `novel_<UUID>.jpg` (임포트도 `char_<UUID>.jpg`, `ExcelImporter:306`).
- `checkBrokenImagePaths`(175-199)는 **DB→파일 없음** 방향만 카운트. 반대 방향(디스크엔 있지만 DB 미참조)인 **고아 파일을 찾아 지우는 코드가 없다**.
- 고아 생성 경로: 편집 중 이미지 교체 후 저장(정상 경로는 `cleanupRemovedImages`가 처리하나), 임포트 이미지 재매핑 반복(매번 새 파일 복사), imagePaths JSON 파싱 실패로 참조 소실 등. 누적 상한 없음.

### 원인 아님 (확인 완료)
- **DB payload**: `CharacterSnapshot`(휴지통)은 이미지 경로 문자열만 담고 base64 미포함 → DB·payload 소형 유지.
- **로그**: `AppLogger` error_log.txt 512KB 캡(MAX_SIZE), crash_log.txt 1MB 캡. 무시 가능.
- **휴지통 이미지**: `pruneIfNeeded`(220)가 30일/30개 초과분 purge. 보류 이미지는 복원 위해 유지(정상).

## 2. 설계

### D-S1. 인앱 저장소 분석 화면 (StorageFragment) — 기준 ②③
- 새 화면: 설정 > 데이터 관리 > **저장 공간**. 분류별 크기 + 시각화(가로 막대) + 안전한 정리 액션.
- 분류: 참조 이미지 / 고아 이미지 / 자동 백업 / 내보내기 캐시 / 데이터베이스 / 로그 / 휴지통 보류 이미지 / 기타. 합계.
- 계산은 `StorageAnalyzer`(신규 util)가 담당 — filesDir·cacheDir 실측 + DB 참조 대조. IO 스레드, 화면은 로딩 표시.
- 액션(각각 확인 다이얼로그 + 결과 고지):
  - **고아 이미지 정리**: DB 미참조 + 휴지통 미보류 이미지 파일 삭제.
  - **내보내기 캐시 비우기**: `cacheDir/exports` 전체 삭제(공유 후 잔여물, 재생성 가능).
  - **자동 백업 관리**로 이동(기존 백업 옵션 다이얼로그 재사용).

### D-S2. 고아 이미지 파일 정리 — 기준 ②
- `SystemMaintenanceService.cleanOrphanImageFiles(): Int` 신설.
- 참조 집합 = `ImageZipHelper.collectAllImagePaths(db)`(Character/Universe/Novel imagePaths 전부) ∪ **휴지통 스냅샷 이미지**(`TrashSnapshot.imagePaths` 파싱 — 복원 대상이므로 반드시 제외).
- 대상 = filesDir 루트의 이미지 파일(`char_`/`universe_`/`novel_` 접두 + 이미지 확장자). backups/exports 등 하위 디렉토리·비이미지 파일은 건드리지 않음.
- 참조 집합에 없는 파일만 삭제, 삭제 건수 반환. **무손실 보장**: 참조 대조가 유일 기준, 휴지통 보류분 제외로 복원 안전.

### D-S3. 자동 백업 이미지 기본값 OFF + 1회 고지 — 기준 ②③④
- `DEFAULT_INCLUDE_IMAGES = false`로 변경(데이터 전용 자동 백업이 sane default).
- **기존 명시 선택 보존**: DataStore에 키가 이미 있으면 그 값을 그대로 사용(기본값은 미설정일 때만 적용). 코드상 `prefs[KEY] ?: DEFAULT`라 자동 충족.
- **변수 제어(무통보 변경 금지)**: 최초 1회 안내 — "자동 백업이 용량 절약을 위해 이제 데이터 전용입니다. 이미지는 앱에 그대로 보관되며, 기기 이전용 이미지 백업은 '이미지 포함 내보내기'를 사용하세요. 자동 백업에 이미지를 다시 포함하려면 설정 > 자동 백업 관리." (백업 옵션 진입점 안내)
- 데이터 보호 무손실 근거는 S-1 참조. 재활성화 경로(백업 옵션 다이얼로그)는 그대로.

## 3. 원칙 정합 검토
- **①받쳐주는 확장성**: 이미지 수백 장·백업 누적에도 용량이 통제됨. 분석 화면이 규모를 가시화.
- **②변수 제어**: 조용한 삭제 없음 — 모든 정리는 확인+결과 고지. 백업 기본값 변경도 1회 고지. 고아 정리는 참조/휴지통 대조로 무손실.
- **③자율성 우선**: 기본값은 합리적으로(데이터 전용), 이미지 포함·보관 개수는 사용자가 자유 설정. 정리는 사용자가 버튼으로 실행(강제 아님).
- **④엑셀 왕복 무결성**: 내보내기 캐시 비우기는 산출물(재생성 가능)만 삭제, 왕복 데이터·원본 이미지 불변.

## 4. 리스크
- 백업 기본값 OFF로 이미지 포함을 원하던 미설정 사용자가 변화를 겪음 → 1회 고지 + 재활성화 경로로 완화. 데이터 보호 손실은 없음(S-1).
- 고아 정리의 오삭제 → 참조 집합 + 휴지통 보류분 이중 제외로 방지. 캐노니컬 경로 검증 재사용.
- 대량 파일 실측 지연 → IO 스레드 + 로딩 표시. listFiles 1회 순회.

## 5. 사후 적대적 검토 후속 수정 (v1.1)

PR #23 병합 후 "이번 업데이트가 문제없이 적용됐는가" 재검토에서, **고아 이미지 정리에 데이터 유실 결함 2건**을 발견해 수정했다(분석 화면 보기·캐시 비우기·백업 기본값은 문제없음 확인).

- **결함 1 (드래프트 이미지 오삭제, P0):** 참조 집합이 DB 커밋 이미지만 근거로 삼아, 미저장 편집 드래프트(B-6, `CharacterDraftPrefs.imagePaths`는 SharedPreferences에 보관)의 이미지가 고아로 오삭제됐다. → `CharacterDraftPrefs.collectAllDraftImagePaths()` 신설, 정리·분석의 참조 집합에 합집합으로 포함.
- **결함 2 (JSON 손상 시 fail-open, P0):** `ImageZipHelper.parseImagePaths`가 파싱 실패와 "이미지 없음"을 모두 null로 반환해, 어느 캐릭터의 imagePaths JSON 손상 1건이 그 이미지 전부의 삭제로 번질 수 있었다. → `collectAllImagePathsWithStatus`/`collectTrashHeldPathsWithStatus`로 파싱 실패를 구별, 실패 감지 시 **삭제하지 않고 중단**(fail-safe) + 사용자 고지.
- **방어 심화:** 최근 24시간 내 수정된 파일은 편집/임포트 진행 중일 수 있어 삭제에서 보호(`recentGuardMillis`).
- **경미 보강:** 분석 총량에 루트 하위 디렉토리(datastore 등) 합산(backups 제외 — 중복 방지); 1회 고지를 실제 영향받는 사용자(현재 이미지 미포함)에게만 표시하고 `markShown`을 표시 성공 이후로 이동.

`cleanOrphanImageFiles` 반환형을 `OrphanCleanupResult(deleted, freed, skippedRecent, aborted)`로 확장, StorageFragment가 중단/보호 건수를 사용자에게 고지.

## 문서 이력
| 버전 | 날짜 | 변경 |
|------|------|------|
| v1.0 | 2026.07.04 | 초기 — 진단(S-1~S-3) + 설계(D-S1~D-S3) |
| v1.1 | 2026.07.04 | 사후 적대적 검토 — 고아 정리 데이터 유실 결함 2건 수정(드래프트 포함·fail-safe), 방어 심화 |
