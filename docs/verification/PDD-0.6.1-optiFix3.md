# PDD 0.6.1 optiFix3 — 구현·검수·사용자 검증

## 기준 및 완료 범위

- **기준:** GitHub `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`, `main` SHA `077cc8769b170732ee7668bfdfbe6e3efd442a68`; IDS 1.0.9.
- **확보/검증:** 기준 optiFix2 전체 소스 ZIP 373개 추적 파일의 경로·바이트(Git blob SHA-1)·실행 모드를 GitHub Git Tree와 전수 비교하여 일치 확인. `.github/workflows`, 기존 Gradle Wrapper, 리소스·테스트 보존.
- **구현 목표:** 직전 `PDD-0.6.1-optiFix2-safe-performance-remediation-plan.md`의 **A단계** 안전한 저장 성능 개선 및 진단. 저장소·물리 계약이 달라지는 B/C단계는 조건부 후속 설계로 남겨 놓음.
- **실제 Gradle, JUnit, Actions, Minecraft 인게임은 AI가 실행하지 않음.** 여기의 JUnit 숫자는 코드에서 집계한 `@Test` 개수이지 실제 통과 수가 아님.

## 구현

1. `WorldPersistentJournal`: 서버 운영 중 `enableWalChannelReuse()`로 한 세대에서 WAL 채널 재사용. 모든 frame마다 원래의 CRC, 예상 WAL 크기 확인, `force(true)` 동기화, 실패 시 `appendUncertain` fail-closed 유지. 새 WAL과 manifest가 내구화된 뒤에만 세대 회전·채널 교체. 종료 시 명시적 close. World 및 Plot 원장에서 동일하게 활성화.
2. 커밋에 관여하지 않는 구세대 파일 정리를 **manifest 내구화 이후** 단일 데몬 작업으로 분리. 최신 요청만 유지하고 이전 요청은 병합(무한 대기열 없음), 주기적으로 양보. 정리 대상은 정확한 PDD 관리 경로·과거 세대·현 manifest 미참조 파일만. 과거 schema, 파싱 불가 파일, 현재/미래 세대, 미등록 경로는 보존. 초기 복원 뒤 남은 orphan도 재검사. 정리 중단/실패는 손상 커밋 성공 판정에 포함하지 않음.
3. `JournalIoMetrics`: checkpoint snapshot 생성·파일 쓰기와 fsync, manifest 인코딩·파일 쓰기·원자 교체·directory fsync, background scan을 별도 기록. 기존 `[PDD-JOURNAL-IO]` 누적 진단에 반영.
4. `StressReservationJournal`/`StressRuntime`: `ENCODE`, `FILE_WRITE`, `FILE_FORCE`, `ATOMIC_MOVE`, `DIRECTORY_FORCE`, `ARCHIVE`, `PROBE`, `VALIDATE`, `SEAL`, `HANDOFF` 시간을 카운트 및 최댓값으로 측정. **응력 노드 수·TTL·저장 순서·데이터를 변경하지 않음.** `[PDD-STRESS-IO]` 추가.
5. `ContactBudgetTop`: 기존 첫 4개 발생 순서 대신 입력 F를 유지한 채 **미확정 F 절댓값 상위 4건**을 기록. `unresolvedRatio≥0.10` 또는 `unresolvedF≥10,000`인 유한 양수만 대상. 진단 전용, 회계·전파·손상 알고리즘 미변경.
6. `build.gradle` 분류자 및 manifest, 런타임 버전 표시 `0.6.1-optiFix3`로 갱신. Java 21, NeoForge, 의존성, JUnit, 원래 Gradle task와 workflow 미변경.

**의도적으로 구현하지 않은 범위:** 비동기 checkpoint snapshot/manifest, async WAL, 그룹 커밋, 내구 ACK 뒤 피해 적용 등은 별도 위험도 높은 단계이며 안전한 세대 fence와 회복 프로토콜이 추가 검증되기 전에는 도입하지 않는다. 따라서 동기식 단일 `force(true)` 또는 manifest 게시가 오래 걸리는 틱은 **아직 오래 걸릴 수 있다**. 이 릴리스로 실제 MSPT 개선이 검증됐다고 주장하지 않는다.

## 안전 불변식

- Sable Native/Plot 원본 콜백 횟수·순서·반환, 원본 World/Plot setter, owner generation, 좌표계와 CAS 불변.
- 직접 접촉 탄성계수 0.4, F/Q 공식, cross-owner 접촉·배정 증거, 손상/변형/응력·H/T/D/E 수치 불변.
- 실제 월드 변경 전 PREPARED 내구화, 변경 후 FINAL 영속화, 동일 ApplyId에 대한 단일 실행, 불확실한 시점은 격리 보존.
- 새로운 WAL은 기존 세대 WAL·샤드가 유효한 동안 먼저 내구화, 새 manifest와 directory fsync 성공 이후에만 구 파일 정리 가능. 파일 정리와 게임 월드 변경 분리.
- Copycats+ 실제 BE 및 재질 슬롯, NBT, 손상 상태와 원래 드롭 1회 계약 유지.

## 신규 JUnit 및 정적 검수

기존 758개 `@Test` 전부 보존하고 신규 14개 추가, 소스 기준 총 **772개** (실행 결과 아님).

| 신규 그룹 | 개수 | 검증 |
|---|---:|---|
| `WorldOptiFix3JournalTest` | 9 | WAL 채널 fsync/재접속, 세대 회전, 외부 변조 실패 폐쇄, 종료 차단, 지연 삭제 vs 활성 참조, 과거 schema 보존, 무관 파일 보존, 미래 세대 보존, 새 계측 |
| `ContactBudgetTopTest` | 3 | 미확정 F 순위, 비율 10% 미만의 큰 절댓값, NaN/Infinity 등 유효성 |
| `StressOptiFix3IoTimingTest` | 2 | 응력 파일 기록 단계 계측·재시작 값 유지, 중복 예약 I/O 없음 |
| **합계** | **14** | |

검증 메모: Java 21 문법 파서로 Java 파일 전체 구문 검사, 표준 Java 기반 새 진단 클래스 타입 검사, 기존 원본 바이트·Git Blob SHA 확인, 신규 소스/테스트와 Apply 경계 정적 분석. **실제 Java/NeoForge 전체 classpath 컴파일, Gradle, JUnit, Actions 성공은 사용자 검증 전까지 미확인.**

## 사용자 실제 검증

1. **시작:** 기존 월드 전체 백업, 이전 optiFix2 JAR 제거, optiFix3 단일 JAR로 교체. GitHub `incoming/`에 전체 프로젝트 ZIP 업로드 후 기존 Apply Action 진행. 보호 워크플로는 보존.
2. **Gradle·JUnit:** `./gradlew clean test --no-daemon` 및 `./gradlew build --no-daemon`. `compileJava`, `compileTestJava`, JUnit 실제 통과 개수(기대 772개), `processResources`, `jar` 확인. 실패 시 최초 실패 지점과 Gradle 로그 전체 제출.
3. **인게임 시험 1 (물리·성능):** 대형 Plot 충돌, Copycat 밀집 접촉, Plot A→B→정적 World C, B↔C 접촉 해제 비교. 기대: F/Q 모두 PASS, 이전과 동일 손상, 실제 제3 owner 전파는 접촉할 때만 발생, 새 `[PDD-STRESS-IO]`, `[PDD-JOURNAL-IO]`, `[PDD-CONTACT-BUDGET]` 출력. `Can't keep up`와 `MANIFEST_*`, `FILE_FORCE`, `CLEANUP_SCAN` 최대치 비교.
4. **인게임 시험 2 (저장·생명주기):** 분리된 Plot과 손상된 Copycat 포함 World/Plot 저장→정상 종료→같은 JVM 재접속. 손상/잔여값, 원장 영수증 수, stress pending, owner generation 일치·중복 파괴 없음. 정상 종료 `status=CLEAN`, `checkpointPending=false`, `cleanup=CLOSED`, WAL 크기 확인. CPU/메모리 스냅샷과 오류를 첨부.
5. **강제 종료/실패 주입:** 실제 월드 손상 위험이 있으므로 백업된 복제 월드에서만 검증. 사용자 인게임 검증 외에 강제 전원 종료 내구성은 자동 보장한다고 주장하지 않음.

## 남은 위험 및 확인 신호

- `force(true)`와 manifest 게시, 단일 Stress fsync가 여전히 서버 메인 틱에서 실행된다. 2ms 소프트 체크포인트 예산은 단일 fsync를 중단하지 못한다.
- 삭제 작업은 비동기이므로 STOP 시 중단·다음 START에서 재청소할 수 있다. 일시적인 구세대 디스크 사용량 증가 가능. 500ms 종료 대기/스캔 제한 등은 실제 기기에서 측정 필요.
- `CLEANUP_SCAN`이 오래 걸리거나 파일 삭제가 잦으면 background I/O가 gameplay와 저장소를 경쟁할 수 있으므로 실제 MSPT 추적.
- Studio/Android Native 메모리는 JVM 힙 외부이므로 PDD 내장 힙 로그만으로 Android LMK 또는 네이티브 메모리 부족을 판정하지 못함.
- 기존 `copycats:copycat_catwalk` 등록과 Sable SubLevel packet 경고는 외부 호환 문제. PDD의 물성 또는 저장 결과를 왜곡해 억지 수정하지 않음.

## 배포 기준

적용 기준 GitHub main `077cc8769b170732ee7668bfdfbe6e3efd442a68`. 원격 GitHub 파일·브랜치·설정 수정 또는 Actions 실행 없음. ZIP은 전체 프로젝트 루트 구조를 사용하며 `.github`, `incoming`, Gradle Wrapper를 원본 그대로 보존한다. 신규 Java 및 문서는 자체 경로만 추가한다.
