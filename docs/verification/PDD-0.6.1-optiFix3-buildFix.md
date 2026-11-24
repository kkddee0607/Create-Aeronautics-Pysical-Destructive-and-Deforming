# PDD 0.6.1 optiFix3-buildFix — GitHub Actions compileJava 수정

## 기준 및 오류 증거

- 기준 GitHub main: `57e19941da4716c78745ab15813935f6f690d97a` (기존 optiFix3 적용).
- 실패한 사용자 GitHub Actions: [PDD Manual Build #45412649984](https://github.com/kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming/actions/runs/45412649984).
- 최초 실패: `:compileJava`, `WorldCommitService.java:122`, `journal.migrated()` 메서드 미발견. Build JAR, JUnit은 실행되지 않음.
- 원인: `WorldPersistentJournal`의 마이그레이션 상태 플래그와 `stats().migrated()`가 존재하지만 기존 호출과 JUnit이 요구하는 `migrated()` API 접근자가 소실됨.

## 수정

1. `WorldPersistentJournal.migrated()`를 기존 내부 `migrated` 플래그에 대한 `public synchronized boolean` 읽기 전용 조회 메서드로 복원. 기존 schema 6, WAL/manifest 경로, 마이그레이션·복원·체크포인트 동작 불변.
2. `WorldPersistentJournalTest.freshJournalReportsNoLegacyMigration()`을 추가하여 신규 저장소에서 `migrated()==false`, `stats().migrated()`와 일치함을 검증하도록 함. 기존 `WorldPersistentSchemaUpgradeTest` 및 `WorldPersistentJournalTest`의 구버전 이관 검증 4곳은 보존.
3. JAR 분류자, manifest `PDD-Revision`, 런타임 시작 로그를 `0.6.1-optiFix3-buildFix`로 일치시킴. `version=0.6.1`, Java 21, NeoForge, Wrapper, Actions, 의존성, 테스트 구성 불변.
4. README의 실행 JAR 이름 및 검증 안내 갱신.

## Gradle/JUnit 인계

- optiFix3의 기존 `@Test` 선언 772개를 모두 보존, 신규 1개를 추가해 정적 예상 `@Test` 총 773개. 테스트 실행 성공 개수와 일치한다는 보장은 없음.
- 기존 손상 수치·F/Q 기대값·Copycats+/Sable 계약·영수증 및 스키마 fixture 불변.
- 실제 Gradle, JUnit 및 Actions는 AI가 실행하지 않음. `compileJava` 오류 수정은 GitHub 최초 오류에 대한 정적 검증이며, 실제 빌드 성공 주장은 아님.

사용자 실행 순서:

```bash
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

검증 대상: `compileJava` → `compileTestJava` → JUnit(정적 예상 773개) → `processResources` → `jar`, 이후 기존 Build / Test / Callback Contract / Apply Actions. 실패 시 **새로운 첫 번째 오류**부터 로그 제출.

## 인게임 검증

이 수정은 컴파일 조회 API만 복구한다. 기존 optiFix3의 물리/저장 기능 검증 절차(`PDD-0.6.1-optiFix3.md`)를 그대로 사용한다. 기존 월드 전체 백업 후 이전 JAR을 제거하고 새 JAR 하나만 탑재한다.
