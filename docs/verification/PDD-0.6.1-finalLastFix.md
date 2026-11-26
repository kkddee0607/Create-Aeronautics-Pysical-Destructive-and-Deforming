# PDD 0.6.1-finalLastFix — 개발 및 사전 검증 보고

## 상태와 기준

- 기준 main `ccc078a08f256f77266986882dc1b8a0385633de`, Git tree `b5c625ff24bd39696b7c4ed7ec164da94f00f916`.
- GitHub 전체 Tree 409 blob, 5,405,555 bytes. 기준 originlastFix ZIP의 경로/바이트/실행 모드로 Git tree SHA 재계산, 정확히 일치. 원본 CRC 확인. 보호 대상 유지. 원격 수정/Actions 실행 없음.
- **전체 소스 검증 후보**. 핵심 수정 반영 및 정적 검사 완료. 모든 필수 기능의 완성 및 실제 인게임 꼭짓점 문제 해결을 확정한 것은 아니다. 정확한 Sable substep block pair, 실제 충돌 환경, 전체 NeoForge compile classpath 및 실제 API 호출 검증이 남아 있다.

## 근본 원인과 수정

1. 참조 Rust `ryanhcode/sable@ad8b4d377741fbd923f4d863b2cf9e462daff9a6`: dispatcher의 Face↔Face/Face↔Edge/Interior 최적화, event_handler의 pair force 반복 보고. 참조 소스와 사용자의 Native 바이너리 동일성은 미확정. Native 점 개수를 실제 면적과 동일시하거나 scalar 합산하지 않는다. 과거 0.5.1-persistFix의 단측 hull/halo는 0.6.0에도 있었지만 상대 물성/형상 근거 없이 전체 면을 승인할 수 있었으며 재도입하지 않았다.
2. `BilateralContactSurface`: 양측 local witness를 독립 Anchor로 해석. effect world witness 간격만으로 프레임 거절하지 않고 실제 양측 외부면과 양수 교차 면적·연결 성분을 검증. 동일 capture PRE와 effect 사이 COM/owner/상대 회전/접선 위치가 일치하는 경우 관측된 상대 법선 변위의 범위가 표면 간격 0을 포함할 때만 swept planar 접촉 후보 승인. 임의 확대된 상수/변위/회전 적용 없음. 각 면 자체의 exterior 검사, 예산·미로드·다중 성분 유지. 이는 관측된 표본 기반 게임 복원이며 정확한 solver substep의 면적 또는 접촉력 증명은 아니다. 평행면 아닌 실제 국소 충돌은 기존 직접점/형상 판정에 의존한다.
3. `SableContactRegions`: 완전한 모호 증거별 후보 집합, 동일 증거 중복 제거, 최대 이분 매칭으로 확정 씨앗 외 추가 고유 대상 수의 상한을 계산. 매칭 결과를 피해 씨앗으로 만들지 않는다. 불완전 후보의 보수적 상한 유지. 확정 기하 블록쌍이 모호한 상대 후보에 묻히지 않도록 명시적 관계 추가; `ContactCreditLedger`에서는 완전하게 확정 상대 집합으로 덮인 모호 집합의 중복 가중 제거, 부분/빈 증거 및 mixed/missing E 보류 유지. 기존 F/2, 탄성 공식, G(F), Q cap 및 후속 전파 유지.
4. `OwnerResolver`/`NeoForgePlotGateway`/`PlotCommitService`: 실제 Sable allocation 외부와 live owner의 holder table에서 미할당인 청크를 그래프 비연결로 구분. 기존 holder가 있으나 미로드이면 미확정. mutation observation을 가짜 AIR로 생성하지 않음. stress owner fence, split, generation, 이동 quarantine 검사 유지.
5. `StressPropagation`: unavailable root를 뒤로 돌리고 독립 root의 graph probe를 계속한다. 확정 root 결과는 canonical index 순서대로 분배해 기존 부동소수점 합류 순서 보존. 모든 root 완료 전 derived plan 생성 금지, pending Q 보존. 기존 TTL, depth/node/tick cap 및 journal schema 유지. `StressRuntime`: 미로드 무진행 재시도 최대 32틱, 기존 deadline 초과 금지, invalidation/material change는 대기 우회. 실제 진행 시 초기화, session cap 및 내부 BudgetEnd 집계 추가. 이미 실행된 root/노드 재발행 없음. 동일 depth frontier의 완전한 합류 조건 유지; 모든 미확정 경계에서 TTL을 피한다고 보장하지 않는다.
6. `NativeCollisionTelemetry`: 1회 native 배열 수집 내 body별 읽기 전용 pose 캐시. 원래 15-double 기록/개수/순서/원본 배열 반환 및 100 native cap, queue cap 유지. 수집 간 캐시 없음. DEBUG `CONTACT_SEED_SEALED`에서 직접/최종 개수, 측별 최대 32 좌표, 보류 단위 기록. 기존 F 원장 로그와 pair/episode 연결.

## 요구사항 추적 (§37.2~37.4)

| 요구 | 실제 경로 | 이번 변경/제약 |
|---|---|---|
| G-COL/G-OBS/G-TARGET | RapierCollisionTelemetryMixin, OwnerResolver, SableContactRegions, BilateralContactSurface | 원본 콜백 유지, shape audit 수정. 실제 인게임 미검증 |
| G-LEDGER/REST/CLEAR | GameImpactCredit, RegionalContactProof, ImpactContinuityGate, ContactCreditLedger, ForcePropagation | 상대 증거 정규화 및 예약 변경; 신규 Credit/재충돌/내부 F 전파 유지 |
| G-MATERIAL/STATE | MaterialRegistry, TargetMaterials, DamageModel, BlockDamageState | 수치/고정소수점 계약 유지 |
| G-WORLD/COMMIT, G-PLOT | WorldCommitCore, WorldCommitService, PlotCommitService, NeoForgePlotGateway | Plot graph boundary만 보완; 실제 변경 전 기존 protection/CAS/HeatMap 계약 유지 |
| G-DEFORM/Q-RESERVE | MovePlan, MoveRuntime, MoveTransactionExecutor/Journal | 방향 권한/이동/파괴 우선/예약 Q 유지. actual movement regression 미실행 |
| G-SAVE/RECOVERY | WorldPersistentJournal, WorldReceiptJournal, StressReservationJournal | 기존 WAL/CRC/shard/receipt index/복구 유지. 신규 journal schema/압축/삭제 없음 |
| G-STRESS | StressPropagation, StressProbeCursor, StressRuntime | Root 대기/graph boundary/재시도 수정. 실제 응력 전달 미검증 |
| G-CC-01~06 | CopycatMaterial, CopycatMovePayload, TargetMaterials | 기존 단일/복수 material snapshot, revision/BE 보호 경로 유지; 전체 실제 Copycat 회귀 미검증 |
| G-SPLIT/G-CC-LINEAGE | SableAssemblyLineageMixin, NativeLineageTransfer, LineageJournal, LineageRecovery, PlotOwnerFence | 기존 native moveBlocks actual position/rotated state/BE NBT/readback 및 durable 2-leg transfer 유지. 같은 owner native relocation, cap 초과, 불완전 증거는 기존 quarantine. 모든 split/merge/reassembly 및 pending 소유권 이전 완료를 새로 증명하지 못함 |

## API 및 의존성

Sable NeoForge 1.21.1-2.0.6 실제 JAR bytecode에서 LevelPlot.contains(ChunkPos), toLocal(ChunkPos), getChunkHolder(ChunkPos) 확인. getChunkHolder는 plot-local array, toLocal은 allocation 원점 상대. ServerLevelPlot save/load 경로 및 holder table 검토. 기존 embedded accessor는 center-relative이므로 별도 좌표 변환 유지. `Rapier3D.clearCollisions(J)[D`, `processCollisionEffects()V` 관측 및 moveBlocks mixin 유지. Copycats+ 3.0.11 JAR의 ICopycatBlockEntity, IMultiStateCopycatBlock(Entity), MaterialItemStorage 확인; 기존 adapter 유지. Create 6.0.10/Aeronautics 1.3.2 파일 사용 환경을 보존, 새 API 추가 없음. Gradle NeoForge 21.1.228, Java 21, Sable common 2.0.6, companion common 1.6.0, JUnit 5.11.4/SLF4J 2.0.16 보존. 실제 compileClasspath/testCompileClasspath/testRuntimeClasspath 해석 및 전체 Minecraft/NeoForge type check 미수행.

## 검증 증거

- Java 21 JavacTask.parse: main/test 합계 259 Java units, 오류 0.
- `scripts/check-duperfix-contract.py`: PASS, 826 @Test 선언, 131 test source files, metadata/import 확인.
- `scripts/check-superfix-assertions.py`: PASS, 폐기된 unilateral 경로 참조 없음, 기존 direct/expand/seal live wiring 확인.
- 제한적 Java 21 javac 타입 검사: 실제 production 27 units, test source 8 units, compile-only Minecraft/SLF4J/OwnerResolver/ContactPoseDiagnostics/JUnit stand-in. 이들은 ZIP에 포함하지 않으며 실제 JUnit 실행하지 않음. StressRuntime/NeoForgePlotGateway/OwnerResolver 전체 외부 API 타입 해석은 미확정. 초기 선택 소스 검사에서 빠진 실제 ImpactMath/ImpactSample/DirectionEvidence를 추가한 후 제한적 검사 통과.
- 독립 Python 수학 모델: 4 evidence groups × 3 possible cells와 확정 부분집합 19,208 경우에서 최대 매칭 구현 모델과 완전탐색 일치. 관측 법선 변위 범위 gap 6 사례 확인. 실제 Java 런타임 테스트 결과는 아님.
- @Test 816→826 (+10); 기존 파일 삭제 없음. 신규/수정 테스트는 1/3/5 평면, 16 ring, 관측 sweep, 실제 지속 gap·tangent drift, 한쪽 hole, 모호 후보 매칭/중복, 정상 root 진행과 합류·Q 보존, per-poll frame cache 등. 기존 테스트의 완전 후보 예약 기대값은 새 정책으로 수정; 불완전 8후보 기대값 유지. 기존 큰 witness gap 테스트는 frame 후보 허용 후 실제 면 미승인으로 검증. 이전 5×5 production-ledger 테스트 유지. 실제 테스트 실행수와 성공 여부 미검증.

## 남은 위험 및 미완료 항목

- 정확한 Native per-contact block pair/solver substep pose는 15-double Java ABI로 제공되지 않음. 서로 다른 회전·접선 이동, PRE/effect 양쪽 모두 분리되고 중간 substep에만 접촉하는 경우, 국소 witness가 실제 형상면에서 벗어난 경우에는 양측 면을 확정할 수 없고 미해결 F를 유지할 수 있다. 이번 수정은 5×5 실제 면 전체 F를 목표로 하는 후보이며, 사용자 실측에서 25개 씨앗이 나오기 전 해결 완료 선언 금지.
- Stress의 미로드·미확정·cap·안전 hold가 실제로 지속되면 기존 TTL 종료 가능. 동일 depth frontier barrier 및 commit hold 순서는 안전 계약 때문에 유지. 실제 정상 로드 Root 불필요 TTL 0건은 인게임 확인 필요.
- 새 HeatMap owner queue는 만들지 않았다. 기존 owner heatmap cache와 개별 commit 재검증/보수적 순서를 유지한다. 별도 장기 receipt 압축은 replay 안전성 증거 부족으로 변경하지 않았으며 기존 shard/index 유지. 메모리/TPS 절대 개선 보장 없음.
- 외부 Sable 패킷 등록 순서 및 Copycat Catwalk resource mismatch는 PDD 원인으로 입증되지 않아 원본 수정하지 않음. 같은-owner native relocation 및 source cap quarantine 포함 전체 상태 이전의 실증, 전체 Copycat BE/Glue/drop 회귀는 남음. 0.6.1 전체 기능 최종 완료 선언 불가.
- 실제 compileJava/compileTestJava/processResources/jar/clean test/build/Actions/게임 미실행(프로젝트 지침). 이번 버전의 실제 빌드 성공으로 해석 금지.

## 인계

1. ZIP 전체 소스 백업 및 GitHub incoming에 정확히 하나만 업로드, Apply 성공 확인. `.github`, `gradlew`, `gradlew.bat`, `gradle`, `incoming` 보호. `./gradlew clean test --no-daemon`, `./gradlew build --no-daemon`. 최초 컴파일/Assertion 오류와 Actions 로그 전달. 기존 jar와 동시 설치 금지.
2. 세션 A: DEBUG JVM `-Dpdd.rog.enabled=true -Dpdd.diagnostics.mode=DEBUG -Dpdd.rog.maxPerTick=416 -Dpdd.rog.summaryTicks=100`. 실제 정렬 1×1/3×3/5×5 양측 각각 1/9/25 초기 씨앗, 중앙 3×3 공극 16, 일부 중첩/회전/모서리/꼭짓점/Plot-Plot/지속 접촉 후 분리 재충돌. `DIRECT_SEEDS`, `FRAME_ANCHOR`, `CONTACT_COVERAGE`, `CONTACT_SEED_SEALED`, `CONTACT_SIDE_SEAL`, `F_CREDIT_SUMMARY`, `F-INVARIANT` 제출. 25개 씨앗의 위치와 초기 F를 내부/Stress 피해와 구분. 가짜 씨앗/중복 Credit/보존 실패 0. 단순 최종 파괴 수로 합격 판단 금지.
3. 세션 B: 실제 응력 전달, 정상/미로드 Plot 경계, 이동/차단 변형, 파괴/drop, Copycat 재질 변경, split/merge/reassembly 및 저장/재접속. `PROBE_GRAPH_BOUNDARY`, `PROBE_UNAVAILABLE`, `STRESS_JOB_SUMMARY` 및 TTL, `PDD-TRANSFER`, `PDD-LINEAGE`, commit/readback, 저장 종료 요약, 예외 제출. 분리 전후 소유자별 위치/손상/BE/재질 보존 및 중복 0 확인. 테스트 환경 성능 자체와 PDD 반복 연산 구별.

## 파일 변경 목록
- 수정: `README.md`
- 수정: `build.gradle`
- 수정: `scripts/check-duperfix-contract.py`
- 수정: `scripts/check-superfix-assertions.py`
- 수정: `src/main/java/com/physicaldestroydeform/PhysicalDestroyDeform.java`
- 수정: `src/main/java/com/physicaldestroydeform/collision/BilateralContactSurface.java`
- 수정: `src/main/java/com/physicaldestroydeform/collision/ContactCreditLedger.java`
- 수정: `src/main/java/com/physicaldestroydeform/collision/OwnerResolver.java`
- 수정: `src/main/java/com/physicaldestroydeform/collision/SableContactRegions.java`
- 수정: `src/main/java/com/physicaldestroydeform/diagnostics/NativeCollisionTelemetry.java`
- 수정: `src/main/java/com/physicaldestroydeform/plot/NeoForgePlotGateway.java`
- 수정: `src/main/java/com/physicaldestroydeform/plot/PlotCommitService.java`
- 수정: `src/main/java/com/physicaldestroydeform/stress/StressPropagation.java`
- 수정: `src/main/java/com/physicaldestroydeform/stress/StressRuntime.java`
- 수정: `src/main/resources/META-INF/neoforge.mods.toml`
- 수정: `src/test/java/com/physicaldestroydeform/collision/FinalFixContactSurfaceTest.java`
- 수정: `src/test/java/com/physicaldestroydeform/collision/OriginLastFixContactTest.java`
- 수정: `src/test/java/com/physicaldestroydeform/collision/SableContactRegionsTest.java`
- 수정: `src/test/java/com/physicaldestroydeform/diagnostics/NativeCollisionTelemetryTest.java`
- 수정: `src/test/java/com/physicaldestroydeform/stress/StressPropagationSelfTest.java`
- 수정: `src/test/java/com/physicaldestroydeform/stress/StressPropagationTest.java`
- 추가: `src/test/java/com/physicaldestroydeform/collision/FinalLastFixSurfaceTest.java`
- 추가: `docs/verification/PDD-0.6.1-finalLastFix.md`
삭제: 0
