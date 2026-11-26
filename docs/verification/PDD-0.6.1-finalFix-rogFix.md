# PDD 0.6.1-finalFix-rogFix — 진단 구현 및 검증 보고

## 기준 및 범위

- Repository: `kkddee0607/Create-Aeronautics-Pysical-Destructive-and-Deforming`; main `158f33a89c6a2100158853bcfcfc1531a932356e`, tree `c279d97f1488883f048c284f3f716c478a8e4b3b`.
- 첨부 finalFix ZIP의 395개 파일, Git blob bytes·경로·실행 권한으로 재계산한 Git tree가 위 main과 정확히 일치. 5,343,985 원본 파일 바이트. 최신 SHA 재조회 일치. 원격 변경 없음.
- IDS 1.0.9, 개발·배포 규약 v3.0 적용. 이번 변경은 사용자 요청의 진단 전용 릴리스로 물리 계수, 허용 조건, 큐/저장 계약 및 원본 콜백을 보존. 문제의 근본 원인을 확정하거나 고친 릴리스가 아님.
- 이전 latestlog.txt: 2,766 관측 그룹, 87 승인, 2,679 미발급, native cap 포화 poll 716, R2 성공 638(측별), HeatMap hold 누적 32,049(검사 호출 수), snapshot missing 32회 상세 기록 한계. 미포화 episode 16에서도 미해결 F 비율 69.85%, 물성 누락 0. 따라서 단순 cap 증가·보류 제거는 근거 없는 수정. 해당 로그는 정상 종료 기록 포함.

## ROG 진단 범위

모든 새 서버 상세 행은 `session`, `windowTick`, `dim`, `nativeTick`, `runtimePair`, `pair`, `episode`, `reason`을 공통 필드로 사용한다. 컨텍스트가 없는 단계는 null/-1이며, 큐 및 지속 처리에는 `applyId`, `job`, `key`, `credit` 등 실제 연결 가능한 기존 식별자를 쓴다. 로그 전용 새로운 물리 식별자를 발급하지 않는다.

| 단계 | 구현 파일/관측 |
|---|---|
| R01 | PhysicalDestroyDeform, RealFixRuntimeBridge: drain, 유효 프레임·시점, native 기록·드롭·포화, stale batch |
| R02 | RealFixRuntimeBridge: 소유권/epoch/그룹 매핑과 탈락 |
| R03 | BilateralContactSurface: 레코드 null/틱/차원/원천, 포즈, 법선 opposition, 주축, witness 오차 |
| R04 | BilateralContactSurface, SableContactRegions: 후보·기하학/면적/외부면, gap, 예산, 닫힌 성분 인증 |
| R05 | RealFixRuntimeBridge: episode FSM, 이전 상태·분리 증거·중복/승인·credit |
| R06 | ForcePropagation, ContactCreditLedger, RealFixRuntimeBridge: seed·edge·node·cutoff, 재료 감쇠와 unresolved F, F/Q 보존/발급 |
| R07 | TargetMaterials: 일반/Copycat 물성, generation, snapshot 결과 |
| R08 | MovePlan, MoveRuntime, MoveTransactionExecutor, MoveSafety, World/Plot gateway: yield, 경로, 예약, CAS, 부착/접착, 목적지, 월드 경계·다른 Plot·엔티티·미로드, 적용 결과 |
| R09 | PlotHeatMapReadCache, NeoForgePlotGateway: cache/live 조회, HeatMap pending 상태, owner/generation별 보류 변화 및 해제 시간, completed tick/spacing |
| R10 | WorldCommitCore, WorldIntentMapper, PlotIntentMapper: credit→applyId, read/plan/defer/receipt/readback, air/unloaded 상세 및 결과 |
| R11 | StressRuntime: 예약, live/frozen probe, budget, TTL, generation, seal, origin·handoff, archive |
| R12 | NeoForgePlotGateway 및 client mixins: 재연결·epoch, container 생성, tracking/finalize/split, snapshot 존재 여부와 handler 진입/반환 |
| R13 | PhysicalDestroyDeform, WorldCommitCore, StressRuntime: 큐, 예산, 작업량, 기존 PDD 성능/저장/메모리 요약과 연계 |

## 진단 비용 제어

- 기본 활성, NORMAL. `-Dpdd.rog.enabled=false`로 새 진단 비활성화. `pdd.rog.maxPerTick` 기본 128, 0..1024 제한. 0이면 상세 샘플을 출력하지 않고 관측 수는 집계. 각 stage의 별도 상한은 min(global,32,max(1,global/13)); 기본 9행/단계/틱, global=416이면 32행/단계/틱. NORMAL은 동일 이유를 summary interval마다 1회, DEBUG/TRACE는 이유당 틱당 최대 8회. `pdd.rog.summaryTicks` 기본 100, 20..1200 제한. 기존 `pdd.diagnostics.mode` 재사용.
- 단계당 이유 64개 이하(overflow 별도 집계), Plot 보류 추적 256 owner keys LRU, tick/session reset, client 독립 wall-second cap 32 및 10초 활동 기반 집계. `pdd.traceSublevelPackets=false`로 client 계측 비활성화. 패킷마다 기존 공개 API로 presence 조회: 종전 32회 이후 중단하는 구현 대신 누적 카운터를 유지하므로 비용은 늘 수 있음. 제한은 새 ROG 상세 로그에 해당하며 기존 PDD 로그·요약·예외 출력까지 전부 제한하지 않는다. `selected`는 실제 기록 확정이 아닌 샘플 선택 횟수. 출력 오류는 별도 집계(일부 예외는 안전하게 무시); 모든 원인에 대한 완전한 원시 로그 저장이 아님. 진단에서 authoritative state, 신규 WAL 또는 무제한 큐를 사용하지 않는다.
- CLIENT observationSequence는 계측 관측 순서로, 정확한 네트워크 wire 순서나 서버-클라이언트 공통 틱은 아님. RETURN은 성공 증명이 아닌 handler 반환. snapshot missing은 실제 lookup의 결과이며 handler 정책은 원본 유지. PacketContext로부터 미확인 정보를 추출하지 않아 일부 clientDimension은 UNOBSERVED. Client 요약은 다음 관측 또는 level 전환 시 출력; 마지막 10초는 종료 직전 요약이 없을 수 있음.

## API와 사전 검증

- 실제 첨부 Sable NeoForge 2.0.6 JAR의 클래스·필드·메서드 javap 확인. `Clientbound{StartTracking,StopTracking,Finalize,RecentlySplit}SubLevelPacket`의 `handle(Lfoundry/veil/api/network/handler/PacketContext;)V` 및 실제 plotCoordinate/UUID/gameTick 필드, `ClientSubLevelContainer` 생성자 `(Level;IIII)V`, 기존 Snapshot handler `(Level;PacketReceiveMode;)V`를 사용. Mixin의 CallbackInfo-only hook으로 Veil 컴파일 의존성 추가 없음. 실제 Mixin 적용/클라이언트 부팅 및 dedicated server는 미검증.
- Create 6.0.10, Aeronautics 1.3.2, Copycats 3.0.11에 대한 새로운 외부 호출/의존성 추가 없음. MoveSafety의 기존 Create 반사 API 조건은 기존 short-circuit 순서 유지. Sable API 의존성 2.0.6, companion 1.6.0, NeoForge 21.1.228, Java 21, 기존 클래스패스 구성 보존. 사용자 로그의 NeoForge 런타임은 21.1.267로 실제 호환 여부는 런타임 검증 필요. Native ABI 및 native 100 record cap 변경 없음.
- Java 21 compiler parser: 254 Java 파일, syntax errors=0. `RogTrace`, `DiagnosticMode`, `TraceBudget`, `FrameMath`, `GameImpactCredit`, `ForcePropagation`, `ContactCreditLedger`, `SparseStateKey`, `MovePlan`, `PlotHeatMapReadCache`, `BlockDamageState`, `DamageModel`, `ImpactSample`, `MaterialProperties`의 제한적인 javac 타입 검사 성공. SLF4J 및 SableConsumerTargets.MAX_CANDIDATES=8 단순 스텁 사용; 전체 NeoForge linkage 또는 build 검증이 아님.
- `python scripts/check-duperfix-contract.py`, `python scripts/check-superfix-assertions.py` 실제 정적 검사 PASS. 버전 메타데이터, DamageModel import 및 contact-face assertion inventory 확인. 기존 807 @Test 선언/기대값 보존(3개 기존 파일 끝에 테스트만 추가). 신규 12개, 총 819 선언 / 129 테스트 소스 파일. JUnit 미실행.
- 가상 검토: cap=0은 상세 0/seen 증가, tick별 cap reset, NORMAL 100틱 간격 및 DEBUG 8회, reason overflow 1,000개→63개+OTHER 937, 100 missing→seen 100/selected 8(동일 DEBUG window), 세션 context 초기화, 동일 geometry/F-Q 결과, cache read 100회→1회·force-live→2회. 기존 물리 수치/기대값 변경하지 않음. 실제 결과는 사용자 JUnit 필요.
- 기존 물리 callback mixin, persistence journal, Gradle wrapper, .github Actions, incoming 보호 파일 바이트 보존. 신규 client hook은 취소/반환값 변경 없음. 안전/물리 판단 결과와 로그는 분리했지만 진단의 실시간 비용·순서 영향 및 전체 API 타입 검증은 런타임/빌드 이전에 보장하지 않음.

## 배포/사용자 검증

`PDD-0.6.1-finalFix-rogFix-FULL-SOURCE.zip`을 incoming/에 업로드 후 기존 Apply 실행. 27 기존 파일 수정, 8 신규(본 문서 포함), 삭제 0. 원본 395개 모두 보존(수정 포함), 최종 403 파일. 보호 항목은 Apply가 유지. 원격 변경 없음.

```bash
python3 scripts/check-duperfix-contract.py
python3 scripts/check-superfix-assertions.py
./gradlew clean test --no-daemon
./gradlew build --no-daemon
```

`compileJava`, `compileTestJava`, JUnit 819 선언, `processResources`, `jar`, `clean test`, `build`, Actions, 인게임은 미실행/미검증. 결과를 제공해야 완료 여부를 판단 가능. JAR 이름 `physical-destroy-deform-0.6.1-finalFix-rogFix.jar`.

### 인게임 검증 (두 세션)

기존 월드 백업, 동일 모드/버전, JVM args에 다음 추가. 기존 `pdd.diagnostics.fullTrace=true`가 있으면 false로 교체. 아래는 분석용 설정으로 운영 권장 설정이 아님.

```
-Dpdd.diagnostics.mode=DEBUG -Dpdd.diagnostics.fullTrace=false -Dpdd.verf.enabled=true -Dpdd.f.trace.enabled=false -Dpdd.rog.enabled=true -Dpdd.rog.maxPerTick=416 -Dpdd.rog.summaryTicks=100
```

1. 새 테스트 월드: 소형 Plot→World, Plot→Plot, 같은 대상 재충돌, 평면 5×5/회전/부분 Copycat. 가능한 한 순서대로 각 충돌 전후 약 5초 확보. R01~R07에서 record→pair→episode→geometry→F/Q를 확인하고 R10에서 applyId→receipt 결과를 확인. 0 credit은 그 이유를 확인; 승인 강제하지 않는다. CONTACT_UNPROVED와 positive area, gap, normal, budget, cap 포화 여부를 비교. 모든 샘플이 저장되는 것은 아니므로 summary도 제출.
2. 백업 복사 월드: 기존 문제 재현, Plot 지형 충돌/연속 파괴·분리, 변형과 stress, 거리 이탈/복귀 또는 재접속(동일 객체 tracking). R08 PHYSICAL_* / CONNECTION_* 및 기존 이동 gate, R09 HOLD_TRANSITION/HOLD_CLEARED, R10 durable receipt/readback, R11 TTL/budget/handoff, R12 packet HEAD/RETURN/missing, R13 queue/perf/IO, 기존 native/source cap과 함께 확인. 정상 종료 후 재접속 시 저장 오류도 확인. 필요하면 416 cap→128로 줄여 부하 비교. 반드시 전체 latest.log(런처 로그 포함 가능), crash-report/hs_err/Android native 로그가 실제 생성된 경우 함께 제출. 성공 기준은 물리 문제가 반드시 해결되는 것이 아니라 실패 원인의 단계와 증거가 연결되고 기존 정상 기능이 유지되며 치명적 예외가 없는 것.

## 남은 한계

이 릴리스는 root-cause 식별을 위한 계측. native 내부 미관측 접촉/F는 추론 불가. 모든 native 및 외부 모드 내부 분기까지 로그가 생기는 것은 아님. 샘플링 때문에 특정 이벤트 상세 일부는 없을 수 있고 집계 및 기존 이벤트 ID를 함께 사용. 로그 증가 자체의 부하는 실제 기기 검증 필요. Mixin injection/Gradle/JUnit/게임 성공은 미검증 상태로 배포한다.
