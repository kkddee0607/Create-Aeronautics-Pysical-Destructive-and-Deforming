# PDD 0.6.0 릴리스 검증

2026-11-18 · 설계 기준 IDS 1.0.9 · main `7e64189d2758c81e45d7d71f066c90e51adc127d`.

## 코드 경로

| 기능 | 런타임·저장 근거 |
|---|---|
| F-first 충돌/응력 | RealFixRuntimeBridge, TargetMaterials, StressRuntime, StressReservationJournal |
| 방향·K(D) | TargetDirections, OwnerResolver.NativeAuditBatch, MovePlan, WorldCommitCore |
| 역층 이동 | MoveRuntime, WorldCommitService, PlotCommitService, StressRuntime |
| World 이동 | NeoForgeWorldGateway, MoveSafety, MoveTransactionExecutor |
| Plot 이동 | NeoForgePlotGateway, PlotMoveTopology, PlotHeatMapTickWitness |
| 실패 예약 Q | MoveTransactionJournal, MoveTransactionExecutor, WorldCommitCore.settleBlockedMove |
| Copycats+ | CopycatMaterial, TargetMaterials, CopycatMovePayload, 실제 World/Plot gateway |
| 재시작 | WorldPersistentJournal schema 6, StressReservationJournal schema 4, MoveTransactionJournal schema 2; 이전 형식 읽기 |

불확실한 원본/목적지, BE, 소유권, 보호 이벤트, Sable HeatMap, 중단 PREPARED는 실제 성공으로 추정하지 않는다. 이미 지급된 Q를 재발행하지 않으며 안전하게 재개할 증거가 부족한 예약은 미사용으로 종료한다. 새 방향 증거에는 원래 F→Q 정책에서 사전 계산한 차단 후보 Q가 포함된다. 구형 방향 증거만 복구한 경우 차단 후보값을 현재 정책으로 재계산하지 않는다. 미확인 기능성 BE는 안전 정책상 이동·파괴 제외.

## 로그 및 사용자 검증

기본 응력/변형/Copycat 상세 로그는 활성화되어 있다. 필요시 JVM 옵션 `-Dpdd.stress.trace=false`, `-Dpdd.deform.trace=false`, `-Dpdd.copycat.trace=false`로 감소; 추가 World gateway 진단 `-Dpdd.deform.verbose=true`.

1. **World 세션:** PDD + Create/Aeronautics/Sable만 먼저 로딩하여 optional adapter ABSENT_OPTIONAL 확인. Copycats+ 3.0.11을 추가하고 같은 테스트 월드의 새 사본에서 단일/다중 장식 카피캣과 정상 블록 배열을 준비. 직렬/diamond 충돌, 빈 경로 이동, 점유 차단, 대각 충돌 및 D0/D100 비교. 재질교체 전후 기존 damage/remainder/history 유지, H/D/E/T 및 revision 차이, 파괴 시 실제 카피캣/소비 재질 드롭 수와 BE 상태 확인. 같은 ApplyId 재발행 없음 확인.
2. **Plot 세션:** 같은 재료를 서로 다른 UUID Plot에 조립하여 충돌. 변형 전후 owner-local 좌표, 실제 BlockState/BE, sparse state key, 변형 이력(+1), 이후 clean HeatMap 완료를 확인. 점유·glue·기계·연결성·claim 차단, 분리/재조립, 중간 저장 종료/재접속, 재질 generation 변화 및 미로드 경계에서 원래 예약 Q가 안전하게 종료/재개되는지 확인. 강제 종료 테스트는 반드시 복사 월드에서 실행.

로그 기대: `[PDD-MOVE]` PREPARED → MUTATION_READBACK → APPLIED, Plot은 PLOT_HEATMAP_WAIT → PLOT_POSTSTATE를 거쳐야 한다. 차단은 `[PDD-DEFORM-Q]` reservedQ, spentQ, unusedQ 및 `[PDD-DEFORM-BLOCKED]` durable receipt 확인. unknown/ambiguous는 위치 이동 및 추가 Q 없음. `[PDD-STRESS-LEDGER]`의 원본 Q, residual, sealed handoff와 `[PDD-STRESS-APPLY]` 영수증을 대조. 재질은 `[PDD-COPYCAT]` material/fingerprint/revision/profile 확인.

제출: 전체 latest.log, 최초 실패 stack trace, JUnit XML 또는 테스트 요약, Build/Callback Contract/Apply 작업 결과, 사용한 모드 버전 및 JVM 옵션, World/Plot 좌표와 UUID, 시험 전후 스크린샷.

## 실제 검증 상태

| 항목 | 결과 |
|---|---|
| compileJava, compileTestJava, processResources, jar | NOT_RUN |
| clean test, JUnit, build | NOT_RUN |
| GitHub Actions, Apply 실제 실행 | NOT_RUN |
| 인게임 (G-DEFORM, G-Q-RESERVE, G-STRESS, G-CC) | NOT_RUN |

AI 정적 검토는 실제 모드 빌드/실행을 대신하지 않는다.

## AI 사전 검토

- 기준 첨부 dev4 305개 파일 → 릴리스 320개 파일: 신규 15, 내용 변경 34, 누락 0. 원격 main 기준 목록 273개 중 누락 0, 보호 경로의 파일 크기 및 Git blob SHA 불일치 0. `gradlew`의 원격 모드 100755 복원.
- Java 21 문법 파서(JavacTask.parse 전용)로 Java 소스 200개 검사, 구문 오류 0. 타입 검사·컴파일·Gradle 실행은 하지 않았다.
- JUnit 선언 535개(dev4 505개 대비 +30). 기존 선언 제거 및 skip 없음. 실제 실행 0개. 정적 수치 검토: T 고정 단위 256, D=0/100의 K=1/.25, Q=10/H=0 차단 피해 E=0:6656 fixed, E=100:4437 fixed; 8 후보/3 예약:3 소비 상한/5 shortfall; 10 damage + 100 remainder +512 fixed:12 damage +100 remainder. 혼합 철/참나무 double slab H86/D53/E30/T406; 흑요석 half slab T493, byte T123.
- Plot gateway는 실제 embedded `setBlock(BlockPos,BlockState,int,int)`, 일반 블록 변경 callback, 이후 completed HeatMap tick, Java selfMass 및 기대 mass를 확인한다. 네이티브 collider의 독립 readback 및 Minecraft 청크 저장과 PDD WAL의 물리적 동기화는 실제 게임/강제 종료 검증 항목이다.
- Copycats+ JAR 3.0.11 SHA-256 `eab9c71e0cc133c38f1063fc9c892599207240a1f0559ce1e3ecddc4ad7aa759`; Create 6.0.10 및 Sable 2.0.6 bytecode 기반 API 검토. 해당 JAR는 배포 소스 ZIP에 포함하지 않는다.
