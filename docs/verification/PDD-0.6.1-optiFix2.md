# PDD 0.6.1 optiFix2 — 성능 병목 추적 및 원장 안전 검증

## 기준 및 범위

- 기준 GitHub `main`: `7209c45e34a789dfcdb158fc8db7cabf13b5d229`, 2026-11-23. 확보 방식: 이전 optiFix 전체 ZIP을 복원하고 핵심 Git blob SHA를 현재 main과 확인. `git clone`은 컨테이너 DNS 접근 실패로 사용하지 못함.
- 통합설계서 IDS 1.0.9, Java 21, Minecraft 1.21.1, NeoForge Gradle **21.1.228** (실행 로그에서는 21.1.267), Sable 2.0.6, Create 6.0.10, Aeronautics 1.3.2, 선택적 Copycats+ 3.0.11.
- 모드 버전/저장 schema 6/알고리즘 revision/체크포인트/WAL/manifest 포맷 변경 **없음**. 기존 월드 전체를 백업하고 이 JAR만 장착한다.
- 빌드 JAR 예상 이름: `physical-destroy-deform-0.6.1-optiFix2.jar`. 기존 optiFix JAR를 동시에 설치하지 않는다.

## 구현 항목

| 영역 | 변경 | 변경하지 않은 계약 |
| --- | --- | --- |
| 틱 추적 | `LINEAGE`, `CAPTURE`, `BRIDGE`, `DISPATCH`, `STRESS`, `WORLD_COMMIT`, `PLOT_COMMIT`, `DIAGNOSTICS`의 구간별 비용/512 최근 표본 P50·P95·P99, 서버 세션 최대 지연 추가 | 원본 호출 순서, F/Q 및 실제 물리 계산 |
| 저장 추적 | WAL append/force, WAL 세대 생성, 샤드 직렬화·인덱싱·쓰기·force, directory force, 콜드 수신증 조회, publish, checkpoint의 별도 시간 계측 및 최근 P95·세션 최대 | `force(true)`와 동기식 WAL, fsync 성공 전 블록 변경 금지, CRC, 원자 manifest 게시, 원본 복구 순서 |
| 고미확정 F | 원장 미확정 비율 10% 이상 Credit의 양측 `endReason`, Native 기록 포화·지역 판정 기록. 서버 틱당 최대 4건 | 실제로 증명되지 않은 대상의 F/Q 발행 금지, 모든 계산식과 상한 |
| 반복 접촉 로그 | NORMAL에서 동일 물리 배치의 교차 접촉 상세 로그를 최대 2건으로 제한하고 나머지는 `PDD-CROSS-OWNER-SUMMARY`에 집계. DEBUG/TRACE는 기존 상세 출력 유지 | 검증 및 실제 F 전달의 차이, 기존 접촉 조건 |
| 재접속 | 계측 데이터의 세션별 초기화; 기존 자원 정리 유지 | Damage, remainder, deformation, Copycat NBT, owner generation, 기존 세션 수명 계약 |
| JAR 식별 | `optiFix2` classifier/revision, README/검증 문서 | API와 모드 ID, 물리/원장 알고리즘 revision |

### 44.5초 지연의 원인 확정 조건

현재 로그에서는 PDD 후처리 P99와 전체 서버 지연을 확인했지만 장시간 정지한 *정확한 작업*은 확인되지 않았다. 첫 번째 구현의 목적은 원인 분리를 가능하게 만드는 것이다. 새로운 `[PDD-PHASE-PERF]`, `[PDD-JOURNAL-IO]` 및 기존 `[PDD-PERFORMANCE]`의 `maxNanos`를 비교한다. `maxNanos`는 해당 세션 시작 이후 최대이고, P95는 최근 표본(512틱 또는 저장 작업 128회) 기준이다. 서로 다른 시간 구간의 수치를 같은 사건의 인과관계로 단정하지 않는다.

**고위험 비동기 저장/그룹 커밋은 이번 릴리스에 넣지 않았다.** 이전 기술 설계에서 checkpoint/WAL 중 실제 주병목이 확인될 때만 적용하도록 조건부로 제안했다. 근거 없는 비동기화는 PREPARED/FINAL 영속화 순서를 깨고 중복 피해·데이터 손실을 만들 수 있다. I/O 결과가 확보되면 immutable snapshot revision, generation WAL fence, bounded queue, 안전한 완료 게시와 fault-injection JUnit을 설계한 다음 별도의 수정을 수행한다. 이번 optiFix2는 저장 데이터를 바꾸지 않는 원인 계측 및 저위험 진단 개선판이다.

### 고미확정 F 원장 검증

`[PDD-CONTACT-BUDGET]`에는 `unresolvedRatio`, `unresolvedF`, `reasonA`, `reasonB`, `nativeCap`, `regionStatus`를 남긴다. `ContactCreditLedger`의 기존 `CONTACT_UNPROVED`, `CONTACT_MISSING_MATERIAL`, `CONTACT_MIXED_E`를 함께 확인한다. 로그가 없다고 F가 완전히 증명됐다는 의미는 아니며 **미확정 비율 10% 미만이거나 로그 상한에 도달한 경우일 수 있다.**

완전한 양측 접촉 후보 집합과 재질이 증명되지 않은 Credit은 기존 방식대로 unresolved로 남긴다. Native 100건 배열 포화가 실제 101번째 접촉 손실을 증명하지는 않는다.

## JUnit 및 정적 검증

기존 `@Test` **744개** 유지, 신규 **14개**, 정적 합계 **758개**. 기존 테스트 기대 물성·F/Q 수치 임의 수정 없음. 신규: `JournalIoMetricsTest` 5, `OptiFix2PhaseMetricsTest` 5, `WorldJournalIoInstrumentationTest` 4. 파일별 테스트 수와 실제 JUnit 실행 통과 수는 다를 수 있으므로 사용자의 Gradle 결과로 확정할 것.

신규 검증은 고지연이 링 버퍼에서 이탈해도 최대값이 유지되는지, 세션 초기화/음수 시간 필터, 개별 단계 시간 분리, WAL append/force 추적, checkpoint/publish 추적, 정확한 콜드 영수증 조회를 포함한다. 기존 `WorldReceiptColdCacheTest`, `WorldPersistentJournalTest`, 구조물 이전, F/Q 및 콜백 계약 테스트는 모두 보존한다.

정적 검증은 Java 21 문법 파싱, helper 타입 컴파일, 파일 일치·ZIP 압축 무결성 및 가상 경계 검토로 구분한다. 프로젝트 규약상 전체 Gradle·JUnit·Actions·인게임 실행은 AI가 수행하지 않으며 **NOT_RUN** 상태다.

## 사용자 검증 인계 — 최대 두 번의 게임 테스트

1. **충돌과 지연:** 동일한 월드 백업 및 모드에서 대형 차량/블록 충돌, Copycat 밀집 접촉, Plot A→Plot B→World C 연쇄 충돌, B↔C 간극 대조. `NORMAL` 모드에서 `[PDD-PHASE-PERF]`, `[PDD-JOURNAL-IO]`, `[PDD-PERFORMANCE]`, `[PDD-CONTACT-BUDGET]`, `[PDD-F-INVARIANT]`, `[PDD-CHAIN-LEDGER]`, `[PDD-CROSS-OWNER]`, `[PDD-CROSS-OWNER-SUMMARY]`, `[PDD-MEMORY]` 및 `Can't keep up!`를 수집한다. 각 Credit F/Q PASS, 간극 허위 피해 0, 정확한 3-owner 전파, 이중 피해 없음이 필수. 극단 지연이 `WAL_FORCE`/`CHECKPOINT`와 함께 관측되면 저장 I/O 원인 조사, 그렇지 않으면 `BRIDGE`, `STRESS`, Commit 등의 실제 최대값으로 분리한다.
2. **재접속/영속성:** 손상·변형·카피캣 상태를 가진 Plot 분리 후 저장·종료→동일 JVM 재접속, 상태·원장 수량 비교, 이전 Credit 중복 적용 금지, 실제 파괴 후 드롭 1회 확인. `status=CLEAN`, `cleanup=CLOSED`, 저장 원장의 `receiptReadFault=false` 확인. 0.6.1 이전 격리된 원본 데이터는 임의 삭제하지 않는다. Native/RSS를 구분하려면 Android logcat 또는 프로세스 메모리 로그도 첨부한다.

실행 명령: `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon` 후 GitHub Build/Test/Callback Contract/Apply 결과 확인. **컴파일 오류가 나오면 최초 오류 줄과 관련 테스트 전체 출력 필요**.

## 남은 미검증·위험

- 실제 44.5초 틱 지연의 근본 원인 미확정; 실제 하드웨어에서 병목 완화 여부 미검증.
- Native 접촉 최대 100건의 상류 손실 여부 미검증. 이번 릴리스는 F 배정 수치를 임의 변경하지 않음.
- 마지막 종료 이후 OS 메모리/네이티브 메모리 누수 여부는 Java 힙 측정만으로 알 수 없음.
- Java 21 JAR/NeoForge 21.1.267 런타임 호환 및 전체 Gradle·JUnit은 사용자 검증 대기.
